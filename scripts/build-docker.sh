#!/usr/bin/env bash
# scripts/build-docker.sh — stage Maven + build the app image.
#
# Why this script exists:
# The Dockerfile expects `./.docker-maven/` to contain a full Maven
# install (bin/, boot/, lib/, conf/, ...). It COPYs that into the
# build container as /opt/maven because (a) the maven:* base image
# trips a proxy cert mismatch in CN, and (b) downloading Maven from
# a CN mirror inside the build container also fails (no network).
#
# This script:
#   1. Locates the host's Maven install (default $HOME/apache-maven-3.9.16)
#   2. Stages it into ./.docker-maven/ (gitignored)
#   3. Runs podman-compose build app
#
# Usage:
#   scripts/build-docker.sh                  # uses default Maven path
#   MAVEN_HOME=/opt/maven scripts/build-docker.sh
#   scripts/build-docker.sh --no-build       # just stage, don't build
#
# Pairs with docker-compose.yml — see comments there for `up -d` workflow.

set -euo pipefail

# ── arg parsing ─────────────────────────────────────────────────────────
NO_BUILD=0
for arg in "$@"; do
    case "$arg" in
        --no-build) NO_BUILD=1 ;;
        --help|-h)
            sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) echo "unknown arg: $arg" >&2; exit 2 ;;
    esac
done

# ── locate Maven ────────────────────────────────────────────────────────
MAVEN_HOME="${MAVEN_HOME:-$HOME/apache-maven-3.9.16}"
if [ ! -x "$MAVEN_HOME/bin/mvn" ]; then
    echo "❌ Maven not found at $MAVEN_HOME (set MAVEN_HOME=... or install Maven 3.9.x)" >&2
    exit 1
fi

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGE_DIR="$PROJECT_ROOT/.docker-maven"

# ── stage into build context ────────────────────────────────────────────
if [ -d "$STAGE_DIR" ] && [ -f "$STAGE_DIR/bin/mvn" ]; then
    echo "✅ $STAGE_DIR already staged"
else
    echo "→ staging $MAVEN_HOME → $STAGE_DIR"
    rm -rf "$STAGE_DIR"
    cp -r "$MAVEN_HOME" "$STAGE_DIR"
    echo "✅ staged $(du -sh "$STAGE_DIR" | cut -f1)"
fi

# ── build ───────────────────────────────────────────────────────────────
if [ "$NO_BUILD" -eq 1 ]; then
    echo "(skipping build per --no-build)"
    exit 0
fi

echo "→ podman-compose build app"
cd "$PROJECT_ROOT"
podman-compose build app