# syntax=docker/dockerfile:1
# React dashboard: build with the public endpoint addresses (never secrets), serve as static files
# from an unprivileged nginx. The token is obtained at runtime with Authorization Code + PKCE.
#
# Build context is the REPOSITORY ROOT (the contract tests read contracts/ by relative path, and
# the root .dockerignore keeps .env, node_modules and build output out of the context):
#   docker build -f frontend/fintech-dashboard/Dockerfile .

FROM node:20-alpine AS build
WORKDIR /src/frontend/fintech-dashboard
COPY frontend/fintech-dashboard/package.json frontend/fintech-dashboard/package-lock.json ./
RUN npm ci --no-audit --no-fund
# The backend's canonical wire samples, at the same relative path the contract tests use.
COPY contracts/src/main/resources/contracts /src/contracts/src/main/resources/contracts
COPY frontend/fintech-dashboard/ ./
ARG VITE_GATEWAY_URL=http://localhost:8080
ARG VITE_KEYCLOAK_URL=http://localhost:8180
ARG VITE_KEYCLOAK_REALM=fintech
ARG VITE_KEYCLOAK_CLIENT_ID=fintech-web
ENV VITE_GATEWAY_URL=${VITE_GATEWAY_URL} \
    VITE_KEYCLOAK_URL=${VITE_KEYCLOAK_URL} \
    VITE_KEYCLOAK_REALM=${VITE_KEYCLOAK_REALM} \
    VITE_KEYCLOAK_CLIENT_ID=${VITE_KEYCLOAK_CLIENT_ID}
# Type-check, test and build: an image is only produced from code that passes.
RUN npm run typecheck && npm test && npx vite build

FROM nginxinc/nginx-unprivileged:1.29.3-alpine
COPY frontend/fintech-dashboard/nginx.conf /etc/nginx/conf.d/default.conf
COPY frontend/fintech-dashboard/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /src/frontend/fintech-dashboard/dist /usr/share/nginx/html
EXPOSE 8080
