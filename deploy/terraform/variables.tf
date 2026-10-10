variable "aws_region" {
  type        = string
  description = "AWS region of the workload cell."
}

variable "environment" {
  type        = string
  description = "Deployment environment (dev, staging, prod)."

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be dev, staging or prod."
  }
}

variable "vpc_id" {
  type        = string
  description = "VPC of the EKS cluster that runs the service."
}

variable "private_subnet_ids" {
  type        = list(string)
  description = "Private subnets in at least two Availability Zones for the Aurora cluster."

  validation {
    condition     = length(var.private_subnet_ids) >= 2
    error_message = "Aurora needs subnets in at least two Availability Zones."
  }
}

variable "workload_security_group_id" {
  type        = string
  description = "Security group of the EKS nodes or pods allowed to reach PostgreSQL."
}

variable "eks_oidc_provider_arn" {
  type        = string
  description = "IAM OIDC provider ARN of the EKS cluster (IRSA)."
}

variable "eks_oidc_provider_url" {
  type        = string
  description = "IAM OIDC provider URL of the EKS cluster without https:// (IRSA)."
}

variable "kubernetes_namespace" {
  type        = string
  description = "Namespace the Helm chart is installed in. Callers address the service as <name>.customer.svc.cluster.local."
  default     = "customer"
}

variable "kubernetes_service_account" {
  type        = string
  description = "Service account name from the Helm chart."
  default     = "customer-profile-kyc-service"
}

variable "aurora_engine_version" {
  type        = string
  description = "Aurora PostgreSQL engine version."
  default     = "16.4"
}

variable "aurora_instance_count" {
  type        = number
  description = "Writer plus readers. Two or more places a reader in a second AZ for failover."
  default     = 2

  validation {
    condition     = var.aurora_instance_count >= 1
    error_message = "At least one Aurora instance is required."
  }
}

variable "aurora_min_capacity" {
  type        = number
  description = "Serverless v2 minimum ACUs."
  default     = 0.5
}

variable "aurora_max_capacity" {
  type        = number
  description = "Serverless v2 maximum ACUs."
  default     = 8
}

variable "backup_retention_days" {
  type        = number
  description = "Automated backup retention (point-in-time recovery window)."
  default     = 35
}

variable "deletion_protection" {
  type        = bool
  description = "Protect the cluster from deletion."
  default     = true
}

variable "alarm_topic_arn" {
  type        = string
  description = "SNS topic for CloudWatch alarms; empty disables notifications."
  default     = ""
}

variable "identity_provider_url" {
  type        = string
  description = "OIDC issuer of the platform Keycloak realm."
}

variable "observability_endpoint" {
  type        = string
  description = "OTLP or metrics endpoint of the platform observability stack."
}

variable "tags" {
  type        = map(string)
  description = "Additional tags (cost centre, data classification). Merged into every resource, so never fintechbankx.io/secrets (ADR-023)."
  default     = {}

  validation {
    condition     = !contains([for k in keys(var.tags) : lower(k)], "fintechbankx.io/secrets")
    error_message = "var.tags must not carry fintechbankx.io/secrets: it would reach aws_kms_key.database, which External Secrets must never decrypt (ADR-023). Only aws_kms_key.secrets is tagged, in main.tf."
  }
}

variable "hpa_max_replicas" {
  type        = number
  description = "autoscaling.maxReplicas of the Helm values for this environment; sizes the connection alarm."
  default     = 12
}

variable "db_pool_max" {
  type        = number
  description = "DB_POOL_MAX of the Helm values; sizes the connection alarm."
  default     = 10
}

variable "msk_cluster_arn" {
  type        = string
  description = "ARN of the platform MSK cluster. When set, the workload role may connect with IAM auth and write the aggregate topic evt.cus.customer.v1. Empty disables Kafka access."
  default     = ""
}
