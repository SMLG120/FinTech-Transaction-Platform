# Unprivileged variant of the official image: nginx runs as uid 101, not root, so a bug in the
# file server does not hand out a root shell in the container.
FROM nginxinc/nginx-unprivileged:1.29.3-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY app/ /usr/share/nginx/html/

EXPOSE 8080
