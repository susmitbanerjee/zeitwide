# SettleCal monorepo tasks. Recipes stay POSIX- and cmd.exe-compatible so `make` works
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
        fmt setup up down

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

fmt:
	cd api && $(GRADLEW) --console=plain spotlessApply
	cd pipeline && $(PY) ruff format . && $(PY) ruff check --fix .
	cd lib-python && $(PY) ruff format . && $(PY) ruff check --fix .
	cd web && npm run format && npm run lint -- --fix

setup:
	pre-commit install
	cd pipeline && uv sync
	cd lib-python && uv sync
	cd web && npm ci --no-audit --no-fund

up:
	docker compose up -d --wait

down:
	docker compose down
