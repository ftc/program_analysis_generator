# Shared setup: builds what pag needs, defines `pag` and `compile_probe`, and
# writes demo_scripts/out/pag, a launcher the campaign uses. The demo scripts
# source it; run it directly (`bash demo_scripts/common.sh`) to set up the
# campaign. Stops at the first unexpected result.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/demo_scripts/out"
mkdir -p "$OUT"

echo "== building pag, the api jar and probe-lib (sbt)"
# One sbt call. `export` prints the runtime classpath as its last line.
PAG_CP="$(cd "$ROOT" && sbt -batch -error api/package probeLib/compile "export cli/Runtime/fullClasspath" | tail -n 1)"
case "$PAG_CP" in
  *pag-cli*|*/engine/cli/*) ;;
  *) echo "unexpected classpath from sbt: $PAG_CP" >&2; exit 1 ;;
esac
API_JAR="$(ls "$ROOT"/engine/api/target/pag-api-*.jar)"
PROBE_LIB="$ROOT/engine/probe-lib/target/classes"
[ -d "$PROBE_LIB/pag/probe" ] || { echo "probe-lib not compiled at $PROBE_LIB" >&2; exit 1; }

echo "== building the reference interval domain (Gradle template)"
"$ROOT/domains/build-template/gradlew" -q -p "$ROOT/domains/build-template" \
  -PdomainDir="$ROOT/domains/ref-interval" -PapiJar="$API_JAR" jar
DOMAIN_JAR="$ROOT/domains/ref-interval/build/libs/ref-interval.jar"
[ -f "$DOMAIN_JAR" ] || { echo "no domain jar at $DOMAIN_JAR" >&2; exit 1; }

# compile_probe NAME: compiles demo_scripts/examples/NAME.java into $OUT/NAME and prints that directory.
compile_probe() {
  local dir="$OUT/$1"
  mkdir -p "$dir"
  javac -g --release 21 -proc:none -cp "$PROBE_LIB" -d "$dir" "$ROOT/demo_scripts/examples/$1.java"
  echo "$dir"
}

# pag ARGS...: runs pag in this JVM, without sbt around it.
pag() { java -cp "$PAG_CP" pag.cli.Main "$@"; }

# The same as a launcher other programs can run: the campaign runs pag through it
# (implementation_strategy.md §12). Rewritten on every setup, so it never points
# at a stale classpath.
cat > "$OUT/pag" <<LAUNCHER
#!/usr/bin/env bash
# Written by demo_scripts/common.sh; rerun \`bash demo_scripts/common.sh\` to refresh.
exec java -cp "$PAG_CP" pag.cli.Main "\$@"
LAUNCHER
chmod +x "$OUT/pag"
