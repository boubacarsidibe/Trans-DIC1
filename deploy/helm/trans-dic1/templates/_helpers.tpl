{{- define "trans-dic1.labels" -}}
app.kubernetes.io/name: trans-dic1
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Values.image.tag | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "trans-dic1.image" -}}
{{ .root.Values.image.registry }}/{{ .component }}:{{ .root.Values.image.tag }}
{{- end -}}

{{- define "trans-dic1.dbHost" -}}
{{- if .Values.postgres.enabled -}}{{ .Release.Name }}-postgres{{- else -}}{{ required "database.host est obligatoire sans postgres.enabled" .Values.database.host }}{{- end -}}
{{- end -}}

{{- define "trans-dic1.securityContext" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
runAsNonRoot: true
capabilities:
  drop: [ALL]
{{- end -}}
