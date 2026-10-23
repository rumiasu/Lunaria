#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../backend"
# Java 17 must be installed by the project-specific environment. No host services or secrets.
java -version
bash gradlew --no-daemon testClasses bootJar
printf '%s\n' 'Backend prepared. Run targeted tests and isolated MySQL checks as documented in docs/云端后端接棒.md.'
