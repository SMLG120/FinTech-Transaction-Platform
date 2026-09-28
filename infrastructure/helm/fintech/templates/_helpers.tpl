{{/*
Resource names are release-qualified so two releases can share a namespace
without colliding, and inter-service URLs in values point at these exact
names (http://<release>-customer-service:8082). Change the pattern here and
the URLs must follow.
*/}}
{{- define "fintech.name" -}}
{{- printf "%s-%s" .Release.Name .Service -}}
{{- end -}}

{{- define "fintech.labels" -}}
app.kubernetes.io/name: {{ .Service | quote }}
app.kubernetes.io/instance: {{ .Release.Name | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service | quote }}
app.kubernetes.io/part-of: secure-fintech-platform
{{- end -}}
