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
