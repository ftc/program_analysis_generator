#!/usr/bin/env bash
# The interval domain proves reach(1) unreachable in examples/Unreachable.java
# and prints the invariant map behind the proof: ⊥ at the entry means no input
# gets there.
source "$(dirname "$0")/common.sh"

CLASSES="$(compile_probe Unreachable)"
echo "== pag analyze: examples/Unreachable.java"
echo
pag analyze --domain "$DOMAIN_JAR" --classes "$CLASSES" --reach 1
