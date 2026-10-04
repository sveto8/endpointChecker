#!/bin/sh
# Starts Endpoint Checker; the browser opens by itself. Keep this next to endpoint-checker.jar.
# Make it executable once:  chmod +x endpoint-checker.sh
cd "$(dirname "$0")" || exit 1
exec java -jar endpoint-checker.jar "$@"
