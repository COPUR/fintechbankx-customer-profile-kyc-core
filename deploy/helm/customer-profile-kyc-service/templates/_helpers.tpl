{{- define "customer.name" -}}
{{- .Chart.Name -}}
{{- end -}}

{{- define "customer.selectorLabels" -}}
app.kubernetes.io/name: {{ include "customer.name" . | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
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
app.kubernetes.io/name: {{ .Values.serviceAccount.name | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
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
{{- if not (regexMatch (printf "^[a-z0-9-]+/%s/" (regexQuoteMeta .Values.serviceAccount.name)) $key) -}}
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
    name: {{ required "databaseCa.configMapName is required" .Values.databaseCa.configMapName | quote }}
    optional: false
    items:
      - key: {{ .Values.databaseCa.key | quote }}
        path: {{ .Values.databaseCa.key | quote }}
{{- end -}}

{{- define "customer.databaseCaMount" -}}
- name: database-ca
  mountPath: {{ .Values.databaseCa.mountPath | quote }}
  readOnly: true
{{- end -}}

{{/*
Path of the RDS CA bundle file inside the pod (cicd-templates 4f0f266).
*/}}
{{- define "customer.databaseCaFile" -}}
{{- printf "%s/%s" (trimSuffix "/" .Values.databaseCa.mountPath) .Values.databaseCa.key -}}
{{- end -}}

{{/*
The datasource / TLS guard of the platform chart (templates/_fbx_helpers.tpl,
vendored unchanged from cicd-templates a4f0072; its README, "Vendoring the
guard"), called at the top of deployment.yaml and migration-job.yaml, then
this chart's stricter rules (customer.validate). fbx.guard reads only the
adapter dict below; a route left out is never checked, so every value of this
chart that reaches a pod's environment is mapped:
  config            .Values.config (ConfigMap, envFrom on the app pods;
                    DB_URL and DB_USERNAME also as env on the migration Job)
  extraEnv, envFrom, extraEnvFrom, javaToolOptions
                    .Values.<same name>: this chart renders none of them;
                    the guard refuses envFrom and extraEnvFrom when set and
                    customer.validate refuses the others when set
  databaseCa        enabled true (the chart always mounts the bundle and
                    renders DB_SSL_ROOT_CERT itself), mountPath, key
  kafka.runtime     .Values.kafka.runtime (renders SPRING_PROFILES_ACTIVE)
  externalSecret    enabled; data = the app secret's keys
                    (SPRING_DATASOURCE_PASSWORD from remoteSecretName,
                    IDENTITY_ADMIN_CLIENT_SECRET from oidcClientSecretName);
                    extraData = the migration secret's keys (DB_MIGRATION_*
                    from migrationSecretName); dataFrom = .Values.externalSecret.dataFrom
The chart's own env entries (DB_SSL_ROOT_CERT, SPRING_PROFILES_ACTIVE,
KAFKA_TLS_*, and the Job's DB_URL / DB_USERNAME copies of config) are not
passed as extraEnv: the guard refuses DB_URL and DB_SSL_ROOT_CERT there,
because only the chart may render them.
*/}}
{{- define "customer.guard" -}}
{{- $es := .Values.externalSecret | default dict -}}
{{- $data := list (dict "secretKey" "SPRING_DATASOURCE_PASSWORD" "property" "password" "remoteSecretName" $es.remoteSecretName) -}}
{{- with $es.oidcClientSecretName -}}
{{- $data = append $data (dict "secretKey" "IDENTITY_ADMIN_CLIENT_SECRET" "property" "client_secret" "remoteSecretName" .) -}}
{{- end -}}
{{- $extraData := list
      (dict "secretKey" "DB_MIGRATION_USERNAME" "property" "username" "remoteSecretName" $es.migrationSecretName)
      (dict "secretKey" "DB_MIGRATION_PASSWORD" "property" "password" "remoteSecretName" $es.migrationSecretName) -}}
{{- include "fbx.guard" (dict "Values" (dict
      "config" .Values.config
      "extraEnv" .Values.extraEnv
      "envFrom" .Values.envFrom
      "extraEnvFrom" .Values.extraEnvFrom
      "javaToolOptions" .Values.javaToolOptions
      "databaseCa" (dict "enabled" true "mountPath" .Values.databaseCa.mountPath "key" .Values.databaseCa.key)
      "kafka" (dict "runtime" (.Values.kafka | default dict).runtime)
      "externalSecret" (dict "enabled" $es.enabled "data" $data "extraData" $extraData "dataFrom" $es.dataFrom))) -}}
{{- include "customer.validate" . -}}
{{- end -}}

{{/*
This chart's rules on top of fbx.guard (each stricter than the guard, or
about this chart's own values):
- serviceAccount.name is a DNS-1123 label: it names the ServiceAccount, the
  migration Job and its secret, and labels the Job's pods.
- extraEnv, env and javaToolOptions are refused when set: the chart renders
  no env list and no JVM options (the image sets JAVA_TOOL_OPTIONS), so such
  a value would be silently ignored.
- kafka.runtime must be msk or strimzi (the guard also allows "", which
  renders no profile; this service publishes to Kafka, and without an auth
  profile KafkaTlsGuard refuses to start).
- config.DB_URL must start with jdbc:postgresql: (the guard also accepts a
  jdbc:<wrapper>:postgresql: URL; the service ships PgJDBC only, whose parser
  DatabaseTlsGuard uses). The guard parses the query.
- Config keys, normalised (upper case, every non-alphanumeric character
  dropped), are refused for
  - SPRINGDATASOURCEUSERNAME, SPRINGDATASOURCEPASSWORD (the guard allows
    them): the runtime role is DB_USERNAME and its password comes from the
    ExternalSecret; a config key could switch the pods to another role;
  - JAVATOOLOPTIONS, JDKJAVAOPTIONS, JAVAOPTIONS (also _JAVA_OPTIONS),
    JAVAOPTS: the guard checks what these hold; the chart refuses them
    whatever they hold (-javaagent, heap flags): the image fixes them;
  - LOGGINGLEVEL*: a log level is not an install-time value; a verbose
    PostgreSQL driver level would write the wire protocol, with row data, to
    the pod log.
*/}}
{{- define "customer.validate" -}}
{{- if not (regexMatch "^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$" (toString .Values.serviceAccount.name)) -}}
{{- fail (printf "serviceAccount.name must be a DNS-1123 label, got %q" (toString .Values.serviceAccount.name)) -}}
{{- end -}}
{{- range $k := list "extraEnv" "env" "javaToolOptions" -}}
{{- if index $.Values $k -}}
{{- fail (printf "%s is not supported by this chart: it renders no env list or JVM options of its own (the image sets JAVA_TOOL_OPTIONS); set runtime settings in config" $k) -}}
{{- end -}}
{{- end -}}
{{- if not (include "customer.kafkaProfile" .) -}}
{{- fail "kafka.runtime must be msk or strimzi: the service publishes to Kafka, and without the kafka-msk or kafka-strimzi profile its producer has no TLS (KafkaTlsGuard refuses to start)" -}}
{{- end -}}
{{- if not (hasPrefix "jdbc:postgresql:" (toString (default "" .Values.config.DB_URL))) -}}
{{- fail "config.DB_URL must be a jdbc:postgresql: URL (Terraform output jdbc_url) with sslmode=verify-full and sslrootcert=<databaseCa.mountPath>/<databaseCa.key>; the service connects through PgJDBC only" -}}
{{- end -}}
{{- range $key, $value := .Values.config -}}
{{- $name := upper (regexReplaceAll "[^A-Za-z0-9]" (toString $key) "") -}}
{{- if regexMatch "^SPRINGDATASOURCE(USERNAME|PASSWORD)$" $name -}}
{{- fail (printf "config.%s must not be set: the datasource credentials are DB_USERNAME and the ExternalSecret's SPRING_DATASOURCE_PASSWORD" $key) -}}
{{- end -}}
{{- if regexMatch "^(JAVATOOLOPTIONS|JDKJAVAOPTIONS|JAVAOPTIONS|JAVAOPTS)$" $name -}}
{{- fail (printf "config.%s must not be set: JVM options are fixed by the image" $key) -}}
{{- end -}}
{{- if hasPrefix "LOGGINGLEVEL" $name -}}
{{- fail (printf "config.%s must not be set: log levels are not install-time values" $key) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
The Spring profile, from kafka.runtime (msk -> kafka-msk, strimzi ->
kafka-strimzi; fbx.kafkaProfile). It is the only one: the guard refuses every
spring.profiles.* key.
*/}}
{{- define "customer.kafkaProfile" -}}
{{- include "fbx.kafkaProfile" (dict "Values" (dict "kafka" (dict "runtime" (.Values.kafka | default dict).runtime))) -}}
{{- end -}}

{{/*
An env[].value the templates render, quoted. Kubernetes expands $(VAR) in an
env value from earlier env entries and envFrom keys (the db-migration
secret's included) after the chart has checked the text, so a value with
'$(' is refused. Takes (dict "where" <values path> "value" <value>).
*/}}
{{- define "customer.envValue" -}}
{{- $v := toString (default "" .value) -}}
{{- if contains "$(" $v -}}
{{- fail (printf "%s must not contain '$(': Kubernetes expands $(VAR) in an env value from earlier env entries and envFrom keys after the chart has checked the text" .where) -}}
{{- end -}}
{{- $v | quote -}}
{{- end -}}
