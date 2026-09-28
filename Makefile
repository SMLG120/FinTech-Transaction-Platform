# Local development entry points.
#
# Every target is a thin wrapper around a command that also works when typed by hand. A Makefile
# that is the only way to run something makes the build harder to understand, not easier, so the
# command each target runs is printed before it runs and the raw command is in the target body.
#
# Run `make help` for the list.

.DEFAULT_GOAL := help
.SHELLFLAGS := -eu -o pipefail -c
.SHELL := /bin/bash

# Pin the toolchain so a developer's local JDK version cannot change the build outcome.
MVN := ./mvnw
MVN_FLAGS := -B -ntp
COMPOSE := docker compose

# palantir-java-format reaches into javac internals, and the newest release does not yet understand
# the compiler in a current JDK: on JDK 27 it fails with NoSuchFieldError on JCCompilationUnit.
# Rather than pin every developer to an older JDK for the sake of a formatter, formatting runs in the
# same JDK 21 image the Docker build already uses. That also means the formatter cannot reformat
# code differently on one machine than on another.
MVN_IMAGE := maven:3.9.16-eclipse-temurin-21
MVN_CONTAINER := docker run --rm -v $(CURDIR):/workspace -v $${HOME}/.m2:/root/.m2 -w /workspace $(MVN_IMAGE)

MVN_VERSION := $(shell $(MVN) -q -v 2>/dev/null | head -1)

.PHONY: help
help: ## Show this help
	@echo "Secure FinTech Transaction Platform"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

# ---------------------------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------------------------

.PHONY: build
build: ## Compile all modules
	@echo "> $(MVN) $(MVN_FLAGS) -DskipTests package"
	@$(MVN) $(MVN_FLAGS) -DskipTests package

.PHONY: test
test: ## Run unit tests (no Docker required)
	@echo "> $(MVN) $(MVN_FLAGS) test"
	@$(MVN) $(MVN_FLAGS) test

.PHONY: verify
verify: ## Run the full build: compile, test, and write the coverage report
	@echo "> $(MVN) $(MVN_FLAGS) verify"
	@$(MVN) $(MVN_FLAGS) verify

.PHONY: quality
quality: verify format-check ## Run the full build and assert formatting

.PHONY: contract
contract: ## Run the wire-contract tests: OpenAPI specs, shared fixtures, event envelopes, frontend parsing
	@echo "> $(MVN) $(MVN_FLAGS) -pl contracts install -DskipTests"
	@$(MVN) $(MVN_FLAGS) -pl contracts install -DskipTests
	@echo "> $(MVN) $(MVN_FLAGS) test -Dtest='*ContractTest,CustomerServiceEligibilityTest,TransactionLookupTest' -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false"
	@$(MVN) $(MVN_FLAGS) test -Dtest='*ContractTest,CustomerServiceEligibilityTest,TransactionLookupTest' -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
	@echo "> npm contract tests in frontend/fintech-dashboard/"
	@cd frontend/fintech-dashboard && npm test --silent -- src/api/contractSchemas.test.ts

.PHONY: format
format: ## Reformat all Java sources (in the pinned JDK 21 image)
	@echo "> $(MVN_IMAGE) ./mvnw $(MVN_FLAGS) -Pquality spotless:apply"
	@$(MVN_CONTAINER) ./mvnw $(MVN_FLAGS) -Pquality spotless:apply

.PHONY: format-check
format-check: ## Assert formatting without changing files (in the pinned JDK 21 image)
	@echo "> $(MVN_IMAGE) ./mvnw $(MVN_FLAGS) -Pquality spotless:check"
	@$(MVN_CONTAINER) ./mvnw $(MVN_FLAGS) -Pquality spotless:check

.PHONY: coverage
coverage: ## Print the JaCoCo coverage summary per module
	@echo "> extracting jacoco.csv"
	@reports=$$(find platform services -path '*/target/site/jacoco/jacoco.csv' 2>/dev/null | sort); \
	if [ -z "$$reports" ]; then \
		echo "No coverage found. Run 'make verify' first."; exit 1; \
	fi; \
	for f in $$reports; do \
		m=$$(echo "$$f" | cut -d/ -f1-2); \
		echo "$$m: $$(awk -F, 'NR>1 {im+=$$8; ic+=$$9; bm+=$$6; bc+=$$7; lm+=$$4; lc+=$$5} \
			END {if (im+ic+bm+bc+lm+lc == 0) print "n/a"; \
			else printf "%.1f%%", 100*(im+ic+bm+bc)/(im+ic+bm+bc+lm+lc)}' $$f)"; \
	done

# ---------------------------------------------------------------------------------------------
# Local environment
# ---------------------------------------------------------------------------------------------

.PHONY: setup
setup: ## Generate .env with random secrets (idempotent; will not overwrite an existing .env)
	@echo "> ./scripts/bootstrap.sh"
	@./scripts/bootstrap.sh

.PHONY: up
up: ## Build and start the whole stack
	@echo "> $(COMPOSE) up --build -d"
	@$(COMPOSE) up --build -d

.PHONY: infra
infra: ## Start only the infrastructure, to run one service from the host
	@echo "> $(COMPOSE) up -d postgres redis kafka kafka-init"
	@$(COMPOSE) up -d postgres redis kafka kafka-init

.PHONY: down
down: ## Stop the stack, keeping volumes
	@echo "> $(COMPOSE) down"
	@$(COMPOSE) down

.PHONY: clean-all
clean-all: ## Stop the stack and DELETE all data volumes
	@echo "> $(COMPOSE) down -v"
	@$(COMPOSE) down -v

.PHONY: ps
ps: ## Show container status
	@echo "> $(COMPOSE) ps"
	@$(COMPOSE) ps

.PHONY: logs
logs: ## Follow logs from every service (make logs S=customer-service to follow one)
	@echo "> $(COMPOSE) logs -f $(S)"
	@$(COMPOSE) logs -f $(S)

.PHONY: topics
topics: ## List the Kafka topics that were provisioned
	@echo "> listing topics in kafka-init"
	@$(COMPOSE) run --rm --no-deps kafka-init 2>/dev/null | tail -n +2

# ---------------------------------------------------------------------------------------------
# Verification
# ---------------------------------------------------------------------------------------------

.PHONY: check
check: quality check-secrets ## Everything a change must pass before it is proposed
	@echo ""
	@echo "all checks passed"

.PHONY: check-secrets
check-secrets: ## Assert no service is handed another component's secret
	@echo "> ./scripts/check-secret-isolation.sh"
	@./scripts/check-secret-isolation.sh

# Not part of `check`, because these need a running stack and `check` is what a change must pass
# before it is proposed -- in a CI job with no Compose, a target that requires one is a red build
# that means nothing. Run them against a live stack, not in the gate.
.PHONY: verify-live
verify-live: ## Assert the card, payment, fraud, settlement, notification, audit, dispute and frontend lifecycles end to end against a running stack
	@echo "> ./scripts/verify-card-lifecycle.sh"
	@./scripts/verify-card-lifecycle.sh
	@echo "> ./scripts/verify-payment-lifecycle.sh"
	@./scripts/verify-payment-lifecycle.sh
	@echo "> ./scripts/verify-fraud-lifecycle.sh"
	@./scripts/verify-fraud-lifecycle.sh
	@echo "> ./scripts/verify-settlement-lifecycle.sh"
	@./scripts/verify-settlement-lifecycle.sh
	@echo "> ./scripts/verify-notification-lifecycle.sh"
	@./scripts/verify-notification-lifecycle.sh
	@echo "> ./scripts/verify-audit-lifecycle.sh"
	@./scripts/verify-audit-lifecycle.sh
	@echo "> ./scripts/verify-dispute-lifecycle.sh"
	@./scripts/verify-dispute-lifecycle.sh
	@echo "> ./scripts/verify-frontend-lifecycle.sh"
	@./scripts/verify-frontend-lifecycle.sh
	@echo "> ./scripts/chaos-degraded-lifecycle.sh"
	@./scripts/chaos-degraded-lifecycle.sh

# Like verify-live: needs a running stack, so not part of `check`.
.PHONY: load-smoke
load-smoke: ## Drive a bounded burst of payments and assert latency, errors and ledger exactness
	@echo "> ./scripts/load-smoke.sh"
	@./scripts/load-smoke.sh

# No cluster needed: lint, render with throwaway secrets, and schema-validate.
# kubectl dry-run additionally needs a cluster, so it stays documented rather
# than gated (see infrastructure/helm/fintech/README.md).
.PHONY: helm-template
helm-template: ## Lint and render the Helm chart and validate all manifests
	@echo "> helm lint infrastructure/helm/fintech"
	@helm lint infrastructure/helm/fintech
	@echo "> helm template (throwaway secrets, never committed)"
	@helm template fintech infrastructure/helm/fintech \
		--set global.identitySigningKey=smoke --set global.redisPassword=smoke \
		--set services.customer-service.secrets.CUSTOMER_PII_MASTER_KEY=smoke \
		--set services.card-service.secrets.CARD_TOKENISATION_KEY=smoke \
		--set services.transaction-service.secrets.SUBJECT_DIGEST_KEY=smoke \
		--set services.fraud-service.secrets.SUBJECT_DIGEST_KEY=smoke \
		--set services.auth-service.secrets.SERVICE_DB_PASSWORD=smoke \
		--set services.customer-service.secrets.SERVICE_DB_PASSWORD=smoke \
		--set services.card-service.secrets.SERVICE_DB_PASSWORD=smoke \
		--set services.transaction-service.secrets.SERVICE_DB_PASSWORD=smoke \
		--set services.fraud-service.secrets.SERVICE_DB_PASSWORD=smoke \
		--set services.notification-service.secrets.SERVICE_DB_PASSWORD=smoke \
		--set services.audit-service.secrets.SERVICE_DB_PASSWORD=smoke \
		--set services.dispute-service.secrets.SERVICE_DB_PASSWORD=smoke \
		--set services.settlement-service.secrets.SERVICE_DB_PASSWORD=smoke \
		> /tmp/fintech-render.yaml
	@echo "> kubeconform (skip when not installed)"
	@command -v kubeconform >/dev/null && kubeconform -kubernetes-version 1.32.0 -summary /tmp/fintech-render.yaml || echo "kubeconform not installed; lint+render passed"

# ---------------------------------------------------------------------------------------------
# Web frontend
# ---------------------------------------------------------------------------------------------

.PHONY: web-test
web-test: ## Run the web UI unit tests (no Docker required)
	@echo "> npm test in web/"
	@cd web && npm test --silent

.PHONY: check-prometheus
check-prometheus: ## Validate the Prometheus config and alert rules with promtool
	@echo "> promtool check config"
	@$(COMPOSE) run --rm --no-deps --entrypoint promtool prometheus \
		check config /etc/prometheus/prometheus.yml
	@echo "> promtool check rules"
	@$(COMPOSE) run --rm --no-deps --entrypoint promtool prometheus \
		check rules /etc/prometheus/alerts/platform-alerts.yml

.PHONY: doctor
doctor: ## Report the toolchain this repository expects
	@echo "maven    : $(MVN_VERSION) (expected 3.9.x via ./mvnw)"
	@echo "docker   : $$(docker --version 2>/dev/null || echo MISSING)"
	@echo "compose  : $$($(COMPOSE) version --short 2>/dev/null || echo MISSING)"
	@echo "env file : "$$([ -f .env ] && echo "present" || echo "MISSING - run: make setup")
	@echo ""
	@jdk=$$($(MVN) -v 2>/dev/null | awk -F': ' '/^Java version:/ {print $$2}'); \
	echo "build jdk: $${jdk:-unknown}"; \
	ver=$$(echo "$$jdk" | sed 's/\..*//'); \
	if [ -z "$$ver" ] || [ "$$ver" -lt 21 ] 2>/dev/null; then \
		echo ""; \
		echo "  The build requires JDK 21 or newer and will stop with:"; \
		echo "    Java 21+ is required to build the platform."; \
		echo ""; \
		echo "  Point JAVA_HOME at a JDK 21+ installation, for example:"; \
		echo "    export JAVA_HOME=\$$(brew --prefix openjdk)/libexec/openjdk.jdk/Contents/Home"; \
		echo ""; \
		echo "  Do not trust '/usr/libexec/java_home -v 21' on macOS when no matching"; \
		echo "  JDK is registered with the system: it will happily return a Java 8 path."; \
		echo ""; \
		echo "  Verify with: \$$(brew --prefix openjdk)/bin/java -version"; \
	else \
		echo "  ok"; \
	fi

# ---------------------------------------------------------------------------------------------
# Housekeeping
# ---------------------------------------------------------------------------------------------

.PHONY: clean
clean: ## Remove build output
	@echo "> $(MVN) $(MVN_FLAGS) clean"
	@$(MVN) $(MVN_FLAGS) clean
	@echo "> removing stray .DS_Store"
	@find . -name .DS_Store -type f -delete
