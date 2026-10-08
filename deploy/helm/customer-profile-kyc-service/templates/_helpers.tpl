{{- define "customer.name" -}}
{{- .Chart.Name -}}
{{- end -}}

{{- define "customer.selectorLabels" -}}
app.kubernetes.io/name: {{ include "customer.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* App pods and every selector that picks them (platform convention, cicd-templates 335a345). */}}
{{- define "customer.podSelectorLabels" -}}
{{ include "customer.selectorLabels" . }}
app.kubernetes.io/component: service
{{- end -}}

{{- define "customer.labels" -}}
{{ include "customer.selectorLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "customer.secretName" -}}
{{ include "customer.name" . }}-db
{{- end -}}

{{/*
Path of the RDS CA bundle file inside the pod (cicd-templates 4f0f266).
*/}}
{{- define "customer.databaseCaFile" -}}
{{- printf "%s/%s" (trimSuffix "/" .Values.databaseCa.mountPath) .Values.databaseCa.key -}}
{{- end -}}

{{/*
A PostgreSQL DB_URL must verify the server certificate and host name:
sslmode=require encrypts but trusts any certificate (cicd-templates 4f0f266).
*/}}
{{- define "customer.validateDatabaseTls" -}}
{{- $url := toString (default "" .Values.config.DB_URL) -}}
{{- if and (hasPrefix "jdbc:postgresql:" $url) (not (contains "sslmode=verify-full" $url)) -}}
{{- fail (printf "config.DB_URL must use sslmode=verify-full with sslrootcert=%s" (include "customer.databaseCaFile" .)) -}}
{{- end -}}
{{- end -}}
