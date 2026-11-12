#!/bin/sh
set -eu

repo_root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
jar="$repo_root/apps/server/target/server-0.1.0.jar"
if [ ! -f "$jar" ]; then
    printf '%s\n' 'Build the simulator from the repository root with ./mvnw verify' >&2
    exit 1
fi

exec java -Xmx512m -Dloader.main=io.aiexchange.simulation.SimulationCli -cp "$jar" \
    org.springframework.boot.loader.launch.PropertiesLauncher "$@"
