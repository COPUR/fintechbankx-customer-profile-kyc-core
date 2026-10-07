{{- define "customer.name" -}}
{{- .Chart.Name -}}
{{- end -}}

{{- define "customer.selectorLabels" -}}
app.kubernetes.io/name: {{ include "customer.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
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
