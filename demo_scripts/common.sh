# Shared setup for the demo scripts; sourced, not run. Builds what pag needs,
# then defines `pag` and `compile_probe`. Stops at the first unexpected result.
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
