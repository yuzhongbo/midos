#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

./mvnw -pl assistant-api -am spring-boot:run -Dspring-boot.run.profiles=solo

