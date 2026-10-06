#!/usr/bin/env bash
# Walkthrough step 5: plant a bug in a copy of the interval domain, then catch
# it with a real run. Run from anywhere:  bash chi_notes/step5_break_a_domain.sh
# Everything it creates goes under demo_scripts/out/, which git ignores.
source "$(dirname "$0")/../demo_scripts/common.sh"   # builds pag and ref-interval; defines pag

echo "== writing the program: only the input x = 1 reaches reach(1)"
mkdir -p "$OUT/src" "$OUT/Boundary"
cat > "$OUT/src/Boundary.java" <<'EOF'
import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

public class Boundary {
    public static void main(String[] args) {
        BigInteger x = Rand.randInt();
        if (x.compareTo(BigInteger.ZERO) > 0) {
            if (x.compareTo(BigInteger.TWO) < 0) {
                reach(1);
            }
        }
    }
}
EOF
CLASSES="$OUT/Boundary"
javac -g --release 21 -proc:none -cp "$PROBE_LIB" -d "$CLASSES" "$OUT/src/Boundary.java"

echo "== making the buggy domain: a copy of ref-interval where x > 0 wrongly means x >= 2"
MUT_DIR="$OUT/mut-tutorial"
mkdir -p "$MUT_DIR"
cp -r "$ROOT/domains/ref-interval/src" "$MUT_DIR/"
F="$MUT_DIR/src/pag/domains/ref/interval/IntervalDomain.java"
sed -i -e 's/case Gt -> below(r, l, one, env)/case Gt -> below(r, l, BigInteger.TWO, env)/' \
       -e 's/"ref-interval"/"mut-tutorial"/' "$F"
grep -q 'case Gt -> below(r, l, BigInteger.TWO, env)' "$F" || { echo "the bug was not planted in $F" >&2; exit 1; }
"$ROOT/domains/build-template/gradlew" -q -p "$ROOT/domains/build-template" \
  -PdomainDir="$MUT_DIR" -PapiJar="$API_JAR" jar
MUT_JAR="$MUT_DIR/build/libs/mut-tutorial.jar"

echo
echo "== the real domain: expect ALARM (it correctly cannot rule out x = 1)"
echo
pag analyze --domain "$DOMAIN_JAR" --classes "$CLASSES" --reach 1
echo
echo "== the buggy domain: expect REFUTED (it wrongly claims reach(1) never runs)"
echo
pag analyze --domain "$MUT_JAR" --classes "$CLASSES" --reach 1
echo
echo "== pag check with input 1: run the program for real and see who is right"
echo
set +e   # check exits 3 when it catches the domain; that is the point here
pag check --domain "$MUT_JAR" --classes "$CLASSES" --reach 1 --inputs 1
echo "exit code $?   (3 means UNSOUND: the buggy domain was caught)"
