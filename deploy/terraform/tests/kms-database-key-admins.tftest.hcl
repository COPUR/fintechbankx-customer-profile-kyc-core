# Review (round 10, main.tf:71): key administration of aws_kms_key.database went
# to IAM through the root statement, so any principal IAM lets edit the key policy
# could remove its denies. The key policy now denies PutKeyPolicy,
# ScheduleKeyDeletion, DisableKey and DisableKeyRotation to every principal
# except the named key-admin roles and the account root user (the break-glass
# path AWS needs against lockout). Offline: the AWS provider is mocked, so these
# checks prove the policy document and the validations, not IAM evaluation.

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
  database_key_admin_role_arns = ["arn:aws:iam::111122223333:role/dev-terraform-deployer"]
}

run "only_named_key_admins_may_administer_the_database_key" {
  command = apply

  assert {
    condition = length([for s in jsondecode(aws_kms_key.database.policy).Statement : s if try(
      s.Sid == "DenyKeyAdministrationOutsideKeyAdmins"
      && s.Effect == "Deny"
      && s.Principal.AWS == "*"
      && s.Resource == "*"
      && toset(s.Action) == toset(["kms:PutKeyPolicy", "kms:ScheduleKeyDeletion", "kms:DisableKey", "kms:DisableKeyRotation"])
      && toset(s.Condition.ArnNotLike["aws:PrincipalArn"]) == toset([
        "arn:aws:iam::111122223333:role/dev-terraform-deployer",
        "arn:aws:iam::111122223333:root",
      ])
    , false)]) == 1
    error_message = "the key policy must deny key administration of aws_kms_key.database to every principal but the named key admins and the account root user"
  }
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
