#!/usr/bin/env bash
# The interval domain cannot prove reach(1) unreachable in
# examples/Reachable.java — it is reachable — and prints the invariant map:
# the entry is not ⊥. Then a run with input 5 shows the program reaching it.
source "$(dirname "$0")/common.sh"

CLASSES="$(compile_probe Reachable)"
echo "== pag analyze: examples/Reachable.java"
echo
pag analyze --domain "$DOMAIN_JAR" --classes "$CLASSES" --reach 1
echo
echo "== pag check, input 5: does the program actually reach it?"
echo
pag check --domain "$DOMAIN_JAR" --classes "$CLASSES" --reach 1 --inputs 5
