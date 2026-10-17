# Review partial (platform-architect, round 6): DenyExternalSecrets on
# aws_kms_key.database matched only the default External Secrets role names
# (role/*-external-secrets[-platform]), so a role under any other name with an
# IAM allow could decrypt with the database key; and var.tags could add other
# fintechbankx.io/ tags to every resource. The key policy now denies every
# principal outside an explicit allow-list (plus the RDS service-linked role and
# AWS service principals using their grants), and var.tags refuses the whole
# fintechbankx.io/ prefix. Offline: the AWS provider is mocked, so these checks
# prove the policy document and the validations, not IAM evaluation on AWS.

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
  database_key_break_glass_role_arns = ["arn:aws:iam::111122223333:role/aws-reserved/sso.amazonaws.com/AWSReservedSSO_BreakGlassAdministrator_0123456789abcdef"]
}

run "database_key_denies_every_principal_outside_the_allow_list" {
  command = apply

  assert {
    condition     = length([for s in jsondecode(aws_kms_key.database.policy).Statement : s if s.Sid == "DenyAllButAllowedKeyUsers"]) == 1
    error_message = "aws_kms_key.database's key policy must carry one DenyAllButAllowedKeyUsers statement"
  }

  assert {
    condition = alltrue([for s in jsondecode(aws_kms_key.database.policy).Statement : (
      s.Effect == "Deny"
      && s.Principal.AWS == "*"
      && s.Resource == "*"
      && length(setsubtract(["kms:Decrypt", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:CreateGrant"], s.Action)) == 0
    ) if s.Sid == "DenyAllButAllowedKeyUsers"])
    error_message = "DenyAllButAllowedKeyUsers must deny Decrypt, ReEncrypt*, GenerateDataKey* and CreateGrant to every principal (*)"
  }

  # The exception is the allow-list, not a name pattern: any role whose ARN is
  # not listed (whatever it is called) is denied.
  assert {
    condition = alltrue([for s in jsondecode(aws_kms_key.database.policy).Statement : (
      keys(s.Condition) == ["ArnNotLike", "Bool"]
      && toset(s.Condition.ArnNotLike["aws:PrincipalArn"]) == toset([
        "arn:aws:iam::111122223333:role/dev-terraform-deployer",
        "arn:aws:iam::111122223333:role/dev-dba-break-glass",
        "arn:aws:iam::111122223333:role/aws-service-role/rds.amazonaws.com/AWSServiceRoleForRDS",
      ])
      && s.Condition.Bool["aws:PrincipalIsAWSService"] == "false"
    ) if s.Sid == "DenyAllButAllowedKeyUsers"])
    error_message = "DenyAllButAllowedKeyUsers must except exactly var.database_key_user_role_arns plus the RDS service-linked role (ArnNotLike aws:PrincipalArn), and AWS service principals using their grants"
  }

  # Defence in depth: the named External Secrets deny stays, so listing such a
  # role by mistake still cannot open the key.
  assert {
    condition     = length([for s in jsondecode(aws_kms_key.database.policy).Statement : s if s.Sid == "DenyExternalSecrets" && s.Effect == "Deny"]) == 1
    error_message = "the named DenyExternalSecrets statement must stay"
  }

  assert {
    condition     = !strcontains(aws_kms_key.secrets.policy == null ? "" : aws_kms_key.secrets.policy, "DenyAllButAllowedKeyUsers")
    error_message = "aws_kms_key.secrets must stay usable by External Secrets"
  }
}

run "allow_list_cannot_name_an_external_secrets_role" {
  command = plan

  variables {
    database_key_user_role_arns = ["arn:aws:iam::111122223333:role/dev-eks-External-Secrets-sync"]
  }

  expect_failures = [var.database_key_user_role_arns]
}

run "allow_list_cannot_use_wildcards" {
  command = plan

  variables {
    database_key_user_role_arns = ["arn:aws:iam::111122223333:role/*"]
  }

  expect_failures = [var.database_key_user_role_arns]
}

run "allow_list_entries_are_role_arns" {
  command = plan

  variables {
    database_key_user_role_arns = ["*"]
  }

  expect_failures = [var.database_key_user_role_arns]
}

run "allow_list_cannot_be_empty" {
  command = plan

  variables {
    database_key_user_role_arns = []
  }

  expect_failures = [var.database_key_user_role_arns]
}

# Any fintechbankx.io/ key, in any case: platform policies (ADR-023 and others)
# key on this prefix, and var.tags reaches the database key too.
run "extra_tags_cannot_carry_any_platform_prefixed_tag" {
  command = plan

  variables {
    tags = { "FintechBankX.io/data-plane" = "true", "cost-centre" = "cus" }
  }

  expect_failures = [var.tags]
}

run "extra_tags_without_the_platform_prefix_are_accepted" {
  command = plan

  variables {
    tags = { "cost-centre" = "cus", "fintechbankx-io-team" = "customer-profile" }
  }
}
