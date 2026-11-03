# Zeitwide monorepo tasks. Recipes stay POSIX- and cmd.exe-compatible so `make` works
# from Git Bash, WSL, macOS, Linux and plain Windows shells.
#
# Requires: JDK 17+ as JAVA_HOME (Gradle runs on it; the build compiles with a Java 21
# toolchain), uv, Node 22+, pre-commit, gitleaks 8.30+, Docker.

ifeq ($(OS),Windows_NT)
# Absolute path: cmd.exe may be configured not to run programs from the current directory.
GRADLEW := "$(CURDIR)/api/gradlew.bat"
else
GRADLEW := ./gradlew
endif

# Run Python tools as modules: no console-script launchers, same behaviour on every OS.
PY := uv run --no-sync python -m

.PHONY: check check-hooks check-api check-pipeline check-lib-python check-web \
        fmt generate-clients sync-docs setup up down

check: check-hooks check-api check-pipeline check-lib-python check-web

check-hooks:
	pre-commit run --all-files --show-diff-on-failure

check-api:
	cd api && $(GRADLEW) --console=plain check

check-pipeline:
	cd pipeline && uv sync --locked && $(PY) ruff format --check . && $(PY) ruff check . && $(PY) mypy && $(PY) pytest --cov

check-lib-python:
	cd lib-python && uv sync --locked && $(PY) ruff format --check . && $(PY) ruff check . && $(PY) mypy && $(PY) pytest --cov

check-web:
	cd web && npm ci --no-audit --no-fund && npm run check

# Playwright (with axe) and Lighthouse against a production build, the fake API in
# web/e2e/mock-api, signing in with the app's test-only sign-in.
e2e-web:
	cd web && npx playwright install chromium
	cd web && node e2e/mock-api/write-release.mjs
	cd pipeline && $(PY) zeitwide_pipeline site-files --release ../web/e2e/.site-files/release.json \
		--out ../web/e2e/.site-files --site-url http://localhost:3000 --api-url http://127.0.0.1:8090
	cd web && npm run e2e:build
	cd web && npm run e2e && npm run lighthouse

fmt:
	cd api && $(GRADLEW) --console=plain spotlessApply
	cd pipeline && $(PY) ruff format . && $(PY) ruff check --fix .
	cd lib-python && $(PY) ruff format . && $(PY) ruff check --fix .
	cd web && npm run format && npm run lint -- --fix

# The typed clients, from api/openapi.yaml. Tests in web/ and lib-python/ fail when they are stale.
generate-clients:
	cd web && npm run generate:api
	cd lib-python && $(PY) datamodel_code_generator --input ../api/openapi.yaml --input-file-type openapi \
		--output src/zeitwide/api_types.py --output-model-type typing.TypedDict \
		--target-python-version 3.12 --disable-timestamp --use-standard-collections \
		--use-union-operator --use-schema-description --use-field-description \
		--no-use-closed-typed-dict --formatters builtin
	cd web && node scripts/sync-docs.mjs

# The public docs (docs/site/*.md) into web, lib-python and pipeline. Their tests fail when stale.
sync-docs:
	cd web && node scripts/sync-docs.mjs

setup:
	pre-commit install
	cd pipeline && uv sync
	cd lib-python && uv sync
	cd web && npm ci --no-audit --no-fund

up:
	docker compose up -d --wait

down:
	docker compose down
