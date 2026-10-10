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
DB_URL must verify the server certificate and host name (cicd-templates 4f0f266;
review 5478458791). The query after the first "?" is parsed, not substring-
matched: exactly one sslmode, equal to verify-full; exactly one sslrootcert,
equal to the mounted bundle; no sslfactory, sslhostnameverifier or
sslpasswordcallback. Keys are compared lower-case, and a key with "%" is
refused, so no spelling the driver might decode slips through. No config key
may replace the datasource or Flyway URL or set JVM or Spring properties around
it. The app checks the same at startup (DatabaseTlsGuard).
*/}}
{{- define "customer.validateDatabaseTls" -}}
{{- $url := toString (default "" .Values.config.DB_URL) -}}
{{- $ca := include "customer.databaseCaFile" . -}}
{{- $want := printf "config.DB_URL must be jdbc:postgresql: with exactly one sslmode=verify-full and exactly one sslrootcert=%s, and no sslfactory, sslhostnameverifier or sslpasswordcallback" $ca -}}
{{- if not (hasPrefix "jdbc:postgresql:" $url) -}}
{{- fail $want -}}
{{- end -}}
{{- $query := "" -}}
{{- if contains "?" $url -}}
{{- $query = (splitn "?" 2 $url)._1 -}}
{{- end -}}
{{- $sslmode := list -}}
{{- $rootcert := list -}}
{{- range $param := splitList "&" $query -}}
{{- if $param -}}
{{- $kv := splitn "=" 2 $param -}}
{{- $key := lower $kv._0 -}}
{{- $value := toString (default "" $kv._1) -}}
{{- if contains "%" $key -}}
{{- fail $want -}}
{{- end -}}
{{- if eq $key "sslmode" -}}
{{- $sslmode = append $sslmode $value -}}
{{- else if eq $key "sslrootcert" -}}
{{- $rootcert = append $rootcert $value -}}
{{- else if has $key (list "sslfactory" "sslfactoryarg" "sslhostnameverifier" "sslpasswordcallback") -}}
{{- fail $want -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- if or (ne (len $sslmode) 1) (ne (first (concat $sslmode (list ""))) "verify-full") -}}
{{- fail $want -}}
{{- end -}}
{{- if or (ne (len $rootcert) 1) (ne (first (concat $rootcert (list ""))) $ca) -}}
{{- fail $want -}}
{{- end -}}
{{- range $key, $value := .Values.config -}}
{{- $k := upper $key -}}
{{- if or (hasPrefix "SPRING_DATASOURCE_" $k) (hasPrefix "SPRING_FLYWAY_" $k) (hasPrefix "SPRING_APPLICATION_JSON" $k) (hasPrefix "SPRING_CONFIG_" $k) (has $k (list "JAVA_TOOL_OPTIONS" "JDK_JAVA_OPTIONS" "_JAVA_OPTIONS" "JAVA_OPTS")) -}}
{{- fail (printf "config.%s is not allowed: the datasource is configured only through DB_URL, DB_USERNAME and DB_POOL_MAX" $key) -}}
{{- end -}}
{{- end -}}
{{- end -}}
