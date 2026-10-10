# Platform ruling ADR-023: one KMS key for the Secrets Manager secrets External
# Secrets syncs (db-app), tagged fintechbankx.io/secrets=true (External Secrets
# decrypts only tagged keys, terraform-modules external-secrets-irsa), and a
# separate untagged key for Aurora storage, snapshots, Performance Insights and
# the RDS-managed master secret (as compliance 7d76e85 and risk 7776da9), which
# External Secrets can never decrypt. Offline: the AWS provider is mocked.

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
}

run "secrets_and_database_use_separate_keys" {
  # apply against the mocked provider: nothing is created, computed values become known.
  command = apply

  # Distinct ARNs and ids per key (review minor): with one shared mock value, an
  # assertion such as "the secret uses aws_kms_key.secrets" would also pass if
  # the secret were wired to aws_kms_key.database.
  override_resource {
    target = aws_kms_key.secrets
    values = {
      arn    = "arn:aws:kms:me-central-1:111122223333:key/secrets-mock"
      key_id = "secrets-mock"
    }
  }

  override_resource {
    target = aws_kms_key.database
    values = {
      arn    = "arn:aws:kms:me-central-1:111122223333:key/database-mock"
      key_id = "database-mock"
    }
  }

  assert {
    condition     = aws_kms_key.secrets.arn != aws_kms_key.database.arn && aws_kms_key.secrets.key_id != aws_kms_key.database.key_id
    error_message = "the test must mock distinct ARNs and ids for the two keys"
  }

  assert {
    condition     = try(aws_kms_key.secrets.tags["fintechbankx.io/secrets"], "") == "true"
    error_message = "aws_kms_key.secrets must be tagged fintechbankx.io/secrets=true"
  }

  assert {
    condition     = !contains(keys(coalesce(aws_kms_key.database.tags, {})), "fintechbankx.io/secrets")
    error_message = "aws_kms_key.database must not carry the fintechbankx.io/secrets tag"
  }

  assert {
    condition     = aws_secretsmanager_secret.app_database.kms_key_id == aws_kms_key.secrets.arn
    error_message = "the db-app secret must be encrypted with aws_kms_key.secrets"
  }

  assert {
    condition     = aws_rds_cluster.database.master_user_secret_kms_key_id == aws_kms_key.database.key_id
    error_message = "the RDS-managed master user secret stays on the untagged aws_kms_key.database (External Secrets never syncs it)"
  }

  assert {
    condition     = aws_rds_cluster.database.kms_key_id == aws_kms_key.database.arn && alltrue([for i in aws_rds_cluster_instance.database : i.performance_insights_kms_key_id == aws_kms_key.database.arn])
    error_message = "Aurora storage, snapshots and Performance Insights stay on aws_kms_key.database"
  }

  assert {
    condition     = strcontains(aws_kms_key.database.policy, "DenyExternalSecrets") && strcontains(aws_kms_key.database.policy, "external-secrets")
    error_message = "aws_kms_key.database's key policy must deny the External Secrets roles"
  }

  assert {
    condition     = !strcontains(aws_kms_key.secrets.policy == null ? "" : aws_kms_key.secrets.policy, "DenyExternalSecrets")
    error_message = "aws_kms_key.secrets must stay usable by External Secrets"
  }
}
