# Review minor (round 9, main.tf:89): the listed key users kept direct use of
# aws_kms_key.database and could create grants for anything. The key policy now
# limits the listed roles to use through RDS and Secrets Manager in this
# region (kms:ViaService), refuses any grant that is not for an AWS resource
# (kms:GrantIsForAWSResource), and the listed roles must be in this account.
# Offline: the AWS provider is mocked, so these checks prove the policy document
# and the precondition, not IAM evaluation on AWS.

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

run "listed_key_users_may_use_the_key_only_through_rds_and_secrets_manager" {
  command = apply

  assert {
    condition = length([for s in jsondecode(aws_kms_key.database.policy).Statement : s if try(
      s.Sid == "DenyListedKeyUsersOutsideRdsAndSecretsManager"
      && s.Effect == "Deny"
      && s.Principal.AWS == "*"
      && length(setsubtract(["kms:Decrypt", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:CreateGrant"], s.Action)) == 0
      && toset(s.Condition.ArnLike["aws:PrincipalArn"]) == toset(var.database_key_user_role_arns)
      && toset(s.Condition.StringNotEquals["kms:ViaService"]) == toset(["rds.me-central-1.amazonaws.com", "secretsmanager.me-central-1.amazonaws.com"])
    , false)]) == 1
    error_message = "the key policy must deny the listed key users any use of aws_kms_key.database outside RDS and Secrets Manager in this region"
  }
}

run "grants_on_the_database_key_must_be_for_aws_resources" {
  command = apply

  assert {
    condition = length([for s in jsondecode(aws_kms_key.database.policy).Statement : s if try(
      s.Sid == "DenyGrantsNotForAwsResources"
      && s.Effect == "Deny"
      && s.Principal.AWS == "*"
      && s.Action == "kms:CreateGrant"
      && s.Condition.Bool["kms:GrantIsForAWSResource"] == "false"
    , false)]) == 1
    error_message = "the key policy must deny kms:CreateGrant on aws_kms_key.database unless the grant is for an AWS resource"
  }
}

run "key_users_from_another_account_are_refused" {
  command = plan

  variables {
    database_key_user_role_arns = ["arn:aws:iam::444455556666:role/dev-terraform-deployer"]
  }

  expect_failures = [aws_kms_key.database]
}
