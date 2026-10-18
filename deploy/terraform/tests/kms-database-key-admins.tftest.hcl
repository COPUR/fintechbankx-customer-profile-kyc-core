# Review (round 10, main.tf:71): key administration of aws_kms_key.database went
# to IAM through the root statement, so any principal IAM lets edit the key policy
# could remove its denies. Platform's answer (round 11): the key policy names two
# kinds of administrator per environment, so losing one never locks the key: the
# deployer tf-apply role (policy, deletion, rotation, tags, aliases, reads) and a
# break-glass Identity Center administrator role (all of that plus DisableKey and
# EnableKey). The account root statement is gone, so IAM alone grants nothing on the
# key; key users get an explicit allow through RDS and Secrets Manager only, and the
# tf-plan role may read the key. Offline: the AWS provider is mocked, so these checks
# prove the policy document and the validations, not IAM evaluation.

mock_provider "aws" {
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "111122223333"
    }
  }
  mock_data "aws_partition" {
    defaults = {
      partition = "aws"
    }
  }
  mock_resource "aws_rds_cluster" {
    defaults = {
      master_user_secret = [{ kms_key_id = "mock", secret_arn = "arn:aws:secretsmanager:me-central-1:111122223333:secret:rds!cluster-mock", secret_status = "active" }]
    }
  }
  mock_resource "aws_kms_key" {
    defaults = {
      arn    = "arn:aws:kms:me-central-1:111122223333:key/mock"
      key_id = "mock"
    }
  }
}

variables {
  aws_region                 = "me-central-1"
  environment                = "dev"
  vpc_id                     = "vpc-0123456789abcdef0"
  private_subnet_ids         = ["subnet-0123456789abcdef0", "subnet-0fedcba9876543210"]
  workload_security_group_id = "sg-0123456789abcdef0"
  eks_oidc_provider_arn      = "arn:aws:iam::111122223333:oidc-provider/oidc.eks.me-central-1.amazonaws.com/id/EXAMPLE"
  eks_oidc_provider_url      = "oidc.eks.me-central-1.amazonaws.com/id/EXAMPLE"
  identity_provider_url      = "https://identity.dev.example.internal/realms/fintechbankx"
  observability_endpoint     = "https://otel.dev.example.internal"
  database_key_user_role_arns = [
    "arn:aws:iam::111122223333:role/dev-terraform-deployer",
    "arn:aws:iam::111122223333:role/dev-dba-break-glass",
  ]
  database_key_admin_role_arns       = ["arn:aws:iam::111122223333:role/dev-terraform-deployer"]
  database_key_reader_role_arns      = ["arn:aws:iam::111122223333:role/dev-terraform-plan"]
  database_key_break_glass_role_arns = ["arn:aws:iam::111122223333:role/aws-reserved/sso.amazonaws.com/AWSReservedSSO_BreakGlassAdministrator_0123456789abcdef"]
}

run "no_statement_grants_the_account_root" {
  command = apply

  assert {
    condition     = length([for s in jsondecode(aws_kms_key.database.policy).Statement : s if s.Effect == "Allow" && anytrue([for p in flatten([try(s.Principal.AWS, [])]) : endswith(p, ":root")])]) == 0
    error_message = "the database key policy must not allow the account root, or IAM alone could administer and use the key"
  }
}

run "deployer_administers_the_key_without_disabling_it" {
  command = apply

  assert {
    condition = length([for s in jsondecode(aws_kms_key.database.policy).Statement : s if try(
      s.Sid == "AllowKeyAdministrationByDeployer"
      && s.Effect == "Allow"
      && toset(flatten([s.Principal.AWS])) == toset(["arn:aws:iam::111122223333:role/dev-terraform-deployer"])
      && toset(s.Action) == toset([
        "kms:DescribeKey", "kms:GetKeyPolicy", "kms:GetKeyRotationStatus", "kms:ListResourceTags",
        "kms:PutKeyPolicy", "kms:ScheduleKeyDeletion", "kms:CancelKeyDeletion", "kms:EnableKeyRotation",
        "kms:UpdateKeyDescription", "kms:TagResource", "kms:UntagResource",
        "kms:CreateAlias", "kms:UpdateAlias", "kms:DeleteAlias",
      ])
    , false)]) == 1
    error_message = "the deployer tf-apply roles must administer the database key without DisableKey, EnableKey or DisableKeyRotation"
  }
}

run "break_glass_may_also_disable_and_enable_the_key" {
  command = apply

  assert {
    condition = length([for s in jsondecode(aws_kms_key.database.policy).Statement : s if try(
      s.Sid == "AllowKeyAdministrationByBreakGlass"
      && s.Effect == "Allow"
      && toset(flatten([s.Principal.AWS])) == toset(["arn:aws:iam::111122223333:role/aws-reserved/sso.amazonaws.com/AWSReservedSSO_BreakGlassAdministrator_0123456789abcdef"])
      && length(setsubtract(["kms:PutKeyPolicy", "kms:ScheduleKeyDeletion", "kms:CancelKeyDeletion", "kms:DisableKey", "kms:EnableKey", "kms:DisableKeyRotation", "kms:EnableKeyRotation"], s.Action)) == 0
    , false)]) == 1
    error_message = "the break-glass role must hold full key administration, DisableKey and EnableKey included"
  }
}

run "only_named_key_admins_may_administer_the_database_key" {
  command = apply

  assert {
    condition = length([for s in jsondecode(aws_kms_key.database.policy).Statement : s if try(
      s.Sid == "DenyKeyAdministrationOutsideKeyAdmins"
      && s.Effect == "Deny"
      && s.Principal.AWS == "*"
      && toset(s.Action) == toset(["kms:PutKeyPolicy", "kms:ScheduleKeyDeletion", "kms:CancelKeyDeletion"])
      && toset(s.Condition.ArnNotLike["aws:PrincipalArn"]) == toset([
        "arn:aws:iam::111122223333:role/dev-terraform-deployer",
        "arn:aws:iam::111122223333:role/aws-reserved/sso.amazonaws.com/AWSReservedSSO_BreakGlassAdministrator_0123456789abcdef",
      ])
    , false)]) == 1
    error_message = "the key policy must deny policy edits and deletion of aws_kms_key.database to every principal but the deployer and break-glass roles"
  }

  assert {
    condition = length([for s in jsondecode(aws_kms_key.database.policy).Statement : s if try(
      s.Sid == "DenyKeyStateChangesOutsideBreakGlass"
      && s.Effect == "Deny"
      && s.Principal.AWS == "*"
      && toset(s.Action) == toset(["kms:DisableKey", "kms:EnableKey", "kms:DisableKeyRotation"])
      && toset(s.Condition.ArnNotLike["aws:PrincipalArn"]) == toset(["arn:aws:iam::111122223333:role/aws-reserved/sso.amazonaws.com/AWSReservedSSO_BreakGlassAdministrator_0123456789abcdef"])
    , false)]) == 1
    error_message = "the key policy must deny DisableKey, EnableKey and DisableKeyRotation to every principal but the break-glass role"
  }
}

run "key_users_get_an_explicit_allow_through_rds_and_secrets_manager_only" {
  command = apply

  assert {
    condition = length([for s in jsondecode(aws_kms_key.database.policy).Statement : s if try(
      s.Sid == "AllowKeyUsersThroughRdsAndSecretsManager"
      && s.Effect == "Allow"
      && toset(flatten([s.Principal.AWS])) == toset(var.database_key_user_role_arns)
      && contains(s.Action, "kms:Decrypt") && contains(s.Action, "kms:CreateGrant")
      && toset(s.Condition.StringEquals["kms:ViaService"]) == toset(["rds.me-central-1.amazonaws.com", "secretsmanager.me-central-1.amazonaws.com"])
    , false)]) == 1
    error_message = "the listed key users need an explicit allow, limited to RDS and Secrets Manager, once the root statement is gone"
  }

  assert {
    condition = length([for s in jsondecode(aws_kms_key.database.policy).Statement : s if try(
      s.Sid == "AllowRdsServiceLinkedRole"
      && s.Effect == "Allow"
      && flatten([s.Principal.AWS]) == ["arn:aws:iam::111122223333:role/aws-service-role/rds.amazonaws.com/AWSServiceRoleForRDS"]
    , false)]) == 1
    error_message = "the RDS service-linked role needs an explicit allow on the database key"
  }
}

run "plan_role_may_read_the_key" {
  command = apply

  assert {
    condition = length([for s in jsondecode(aws_kms_key.database.policy).Statement : s if try(
      s.Sid == "AllowPlanRead"
      && toset(flatten([s.Principal.AWS])) == toset(["arn:aws:iam::111122223333:role/dev-terraform-plan"])
      && toset(s.Action) == toset(["kms:DescribeKey", "kms:GetKeyPolicy", "kms:GetKeyRotationStatus", "kms:ListResourceTags"])
    , false)]) == 1
    error_message = "the tf-plan role must be able to read the database key, and only read it"
  }
}

run "no_plan_read_statement_without_readers" {
  command = apply

  variables {
    database_key_reader_role_arns = []
  }

  assert {
    condition     = length([for s in jsondecode(aws_kms_key.database.policy).Statement : s if try(s.Sid, "") == "AllowPlanRead"]) == 0
    error_message = "an empty reader list must not render a statement with no principal"
  }
}

run "break_glass_is_required" {
  command = plan

  variables {
    database_key_break_glass_role_arns = []
  }

  expect_failures = [var.database_key_break_glass_role_arns]
}

run "break_glass_is_a_full_role_arn" {
  command = plan

  variables {
    database_key_break_glass_role_arns = ["arn:aws:iam::111122223333:role/*"]
  }

  expect_failures = [var.database_key_break_glass_role_arns]
}

run "break_glass_from_another_account_is_refused" {
  command = plan

  variables {
    database_key_break_glass_role_arns = ["arn:aws:iam::444455556666:role/break-glass"]
  }

  expect_failures = [aws_kms_key.database]
}

run "break_glass_must_differ_from_the_deployer" {
  command = plan

  variables {
    database_key_break_glass_role_arns = ["arn:aws:iam::111122223333:role/dev-terraform-deployer"]
  }

  expect_failures = [aws_kms_key.database]
}

run "key_admins_are_required" {
  command = plan

  variables {
    database_key_admin_role_arns = []
  }

  expect_failures = [var.database_key_admin_role_arns]
}

run "key_admins_are_full_role_arns" {
  command = plan

  variables {
    database_key_admin_role_arns = ["arn:aws:iam::111122223333:role/*"]
  }

  expect_failures = [var.database_key_admin_role_arns]
}

run "key_admins_from_another_account_are_refused" {
  command = plan

  variables {
    database_key_admin_role_arns = ["arn:aws:iam::444455556666:role/dev-terraform-deployer"]
  }

  expect_failures = [aws_kms_key.database]
}
