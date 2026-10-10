# ADR-019 s1: one Kafka topic per aggregate. The workload role may write only
# the customer aggregate topic evt.cus.customer.v1, not a per-event pattern.
# Offline: the AWS provider is mocked.

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
  msk_cluster_arn            = "arn:aws:kafka:me-central-1:111122223333:cluster/platform-msk/0a1b2c3d-4e5f-6789-abcd-ef0123456789-1"
}

run "workload_writes_only_the_customer_aggregate_topic" {
  command = plan

  assert {
    condition = toset(one([for s in data.aws_iam_policy_document.workload[0].statement : s.resources if s.sid == "WriteOwnEventNamespace"])) == toset([
      "arn:aws:kafka:me-central-1:111122223333:topic/platform-msk/0a1b2c3d-4e5f-6789-abcd-ef0123456789-1/evt.cus.customer.v1",
    ])
    error_message = "the workload role writes exactly the aggregate topic evt.cus.customer.v1 (ADR-019), no per-event wildcard"
  }
}
