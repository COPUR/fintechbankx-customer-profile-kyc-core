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

{{- define "customer.migrationSecretName" -}}
{{ include "customer.name" . }}-db-migration
{{- end -}}

{{/*
Flyway migration Job (templates/migration-job.yaml, decision 0001). Its pods
carry app.kubernetes.io/name=<service account>, on which the mesh grants Aurora
egress, and app.kubernetes.io/component=db-migration (cicd-templates 335a345);
every selector of the app pods includes component=service, so none selects
them. The Job's ServiceAccount, Job and the db-migration ExternalSecret share
the name <service account>-db-migration.
*/}}
{{- define "customer.migrationName" -}}
{{ .Values.serviceAccount.name }}-db-migration
{{- end -}}

{{- define "customer.migrationSelectorLabels" -}}
app.kubernetes.io/name: {{ .Values.serviceAccount.name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: db-migration
{{- end -}}

{{- define "customer.migrationLabels" -}}
{{ include "customer.migrationSelectorLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{/*
Hook resources the Job needs on a first install, when no regular resource of
the release exists yet: created before the Job (lower weight) and deleted once
every hook has succeeded, so the schema owner's credential exists in the
namespace only while a migration runs. A failed run leaves them for
inspection; the next install or upgrade replaces them.
*/}}
{{- define "customer.migrationPrerequisiteHook" -}}
helm.sh/hook: pre-install,pre-upgrade
helm.sh/hook-weight: "-10"
helm.sh/hook-delete-policy: before-hook-creation,hook-succeeded
{{- end -}}

{{/* The migration Job reads the schema owner's credential; refuse a render without it, or outside <env>/<service account>/. */}}
{{- define "customer.requireMigrationSecret" -}}
{{- if not .Values.externalSecret.enabled -}}
{{- fail "externalSecret.enabled must be true: the migration Job reads the schema owner's credential (externalSecret.migrationSecretName)" -}}
{{- end -}}
{{- $key := required "externalSecret.migrationSecretName is required: the migration Job runs Flyway as the schema owner (Secrets Manager <env>/<service account>/db-migration)" .Values.externalSecret.migrationSecretName -}}
{{- if not (regexMatch (printf "^[a-z0-9-]+/%s/" .Values.serviceAccount.name) $key) -}}
{{- fail (printf "externalSecret.migrationSecretName must be <env>/%s/..., got %s" .Values.serviceAccount.name $key) -}}
{{- end -}}
{{- end -}}

{{/* Shared by the app pods and the migration Job pods. */}}
{{- define "customer.podSecurityContext" -}}
runAsNonRoot: true
runAsUser: 10001
runAsGroup: 10001
fsGroup: 10001
seccompProfile:
  type: RuntimeDefault
{{- end -}}

{{- define "customer.containerSecurityContext" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop: ["ALL"]
{{- end -}}

{{- define "customer.image" -}}
{{ required "image.repository is required" .Values.image.repository }}:{{ required "image.tag is required" .Values.image.tag }}
{{- end -}}

{{/* RDS CA bundle volume. Not optional: without the platform's bundle the pod does not start, rather than connect unverified. */}}
{{- define "customer.databaseCaVolume" -}}
- name: database-ca
  configMap:
    name: {{ required "databaseCa.configMapName is required" .Values.databaseCa.configMapName }}
    optional: false
    items:
      - key: {{ .Values.databaseCa.key }}
        path: {{ .Values.databaseCa.key }}
{{- end -}}

{{- define "customer.databaseCaMount" -}}
- name: database-ca
  mountPath: {{ .Values.databaseCa.mountPath }}
  readOnly: true
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
{{- include "customer.validateConfigKeys" . -}}
{{- end -}}

{{/*
Config keys the ConfigMap must never carry. Each key is normalised before
matching, the way Spring's relaxed binding reads it from the environment:
upper case, then every non-alphanumeric character dropped, so dash, dot,
underscore and index spellings (spring.config.import[0], SPRING_CONFIG_IMPORT_0,
SPRING-DATASOURCE-URL) all reach the same rule. Refused, each with its own
message:
- SPRINGDATASOURCE*, SPRINGFLYWAY*, SPRINGAPPLICATIONJSON: a second database
  URL or driver property would override DB_URL; the datasource is configured
  only through DB_URL, DB_USERNAME and DB_POOL_MAX.
- SPRINGCONFIG* (IMPORT, ADDITIONALLOCATION, LOCATION, also indexed): loads a
  file or configtree that can set the URL. The chart renders no Spring config
  import of its own; a configtree would be allowed only as a chart-rendered
  value on the fixed mount optional:configtree:/etc/fintechbankx/config/,
  never from a values key.
- JAVATOOLOPTIONS, JDKJAVAOPTIONS, JAVAOPTIONS (also _JAVA_OPTIONS), JAVAOPTS:
  set system properties or load an agent before application.yml is read; the
  image fixes them.
- LOGGINGLEVEL*: a log level is not an install-time value; a verbose level
  for the PostgreSQL driver would write the wire protocol, with row data, to
  the pod log.
- DBSSLROOTCERT: the only switch of the TLS startup assertion (DatabaseTlsGuard
  and KafkaTlsGuard run whenever DB_SSL_ROOT_CERT is set). The chart sets it
  from the mounted bundle on every container; a values key, empty or not, is
  an attempt to turn the assertion off and is refused.
- SPRINGPROFILES(ACTIVE|INCLUDE), also indexed: must not name the local
  profile (a developer machine with plain-text local services), in any case
  or position of the list.
There is no extraEnv or env list, so the ConfigMap is the only route for a
config key (the deploy/helm job checks values.yaml for one).
*/}}
{{- define "customer.validateConfigKeys" -}}
{{- range $key, $value := .Values.config -}}
{{- $name := upper (regexReplaceAll "[^A-Za-z0-9]" (toString $key) "") -}}
{{- if or (hasPrefix "SPRINGDATASOURCE" $name) (hasPrefix "SPRINGFLYWAY" $name) (hasPrefix "SPRINGAPPLICATIONJSON" $name) -}}
{{- fail (printf "config.%s must not be set: config.DB_URL is the only database URL (sslmode=verify-full); the datasource is configured only through DB_URL, DB_USERNAME and DB_POOL_MAX" $key) -}}
{{- end -}}
{{- if hasPrefix "SPRINGCONFIG" $name -}}
{{- fail (printf "config.%s must not be set: it loads configuration that can override config.DB_URL" $key) -}}
{{- end -}}
{{- if regexMatch "^(JAVATOOLOPTIONS|JDKJAVAOPTIONS|JAVAOPTIONS|JAVAOPTS)$" $name -}}
{{- fail (printf "config.%s must not be set: JVM options are fixed by the image" $key) -}}
{{- end -}}
{{- if hasPrefix "LOGGINGLEVEL" $name -}}
{{- fail (printf "config.%s must not be set: log levels are not install-time values" $key) -}}
{{- end -}}
{{- if eq $name "DBSSLROOTCERT" -}}
{{- fail (printf "config.%s must not be set: the TLS startup assertion has no off switch (the chart sets DB_SSL_ROOT_CERT from the mounted bundle)" $key) -}}
{{- end -}}
{{- if regexMatch "^SPRINGPROFILES(ACTIVE|INCLUDE)[0-9]*$" $name -}}
{{- range $profile := splitList "," (toString $value) -}}
{{- if eq (lower (trim $profile)) "local" -}}
{{- fail (printf "config.%s must not activate the local profile: it is for a developer machine, never for a cluster" $key) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
