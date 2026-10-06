# Tutorial and overview (first pass)

Saved 2026-10-06 from a Claude Code session, so it can be walked through one
section at a time. Not part of the project's docs: `README.md`,
`implementation_strategy.md`, `experiments.md` and `glossary.md` are the source
of truth. Commands were not run when this was written; expected outputs come
from the repo's golden files and tests, and step 5's from reading the domain
code, so it is unverified.

## Part 1: Tutorial

### 0. Prerequisites

- **JDK 21.** The README says 17+ works, but the demos compile with
  `--release 21`, which needs JDK 21.
- **sbt**, plus network access on the first run. sbt fetches Scala and Soot,
  and the Gradle wrapper fetches Gradle 9.8.0 to build domains.

```sh
cd ~/src/QuoalaCode/program_analysis_generator
java -version && javac -version     # both 21.x
sbt test                             # first run takes minutes; should end with [success]
```

### 1. Watch a proof: `./demo_scripts/unreachable.sh`

This analyses [Unreachable.java](../demo_scripts/examples/Unreachable.java):

```java
BigInteger x = Rand.randInt();                 // an input; the tester picks it
if (x.compareTo(BigInteger.ZERO) > 0) {
    BigInteger y = x.add(BigInteger.ONE);
    if (y.compareTo(BigInteger.ZERO) < 0) { reach(1); }   // can we ever get here?
}
```

The output should match the golden file
[AnalyzeRefute-analyze.txt](../engine/cli/src/test/resources/golden/AnalyzeRefute-analyze.txt),
which is for the same program:

```
  entry                                  ⊥
  pre(1)    x := Rand.randInt()          ⊥
  pre(4)    if x <= 0 goto 11            ⊥
  pre(6)    y := x + 1                   x ↦ (-∞,-2]
  pre(9)    if y >= 0 goto 11            y ↦ (-∞,-1]
  pre(10)   Reach.reach(1)               ⊤   ← target
certified   27/27 edges inductive
REFUTED
```

Read it **bottom-up**, because the analysis runs backward from the target:

- At the target, any state counts (⊤).
- To fall into the target, `y` must be ≤ -1.
- Pushed back through `y := x + 1`, that means `x` must be ≤ -2.
- To enter the outer `if`, `x` must also be > 0. That's a contradiction, so the
  state is ⊥.
- The entry is ⊥, so no input reaches `reach(1)`: **REFUTED**.

The "certified" line means a separate checker re-verified every edge of the map.

### 2. Watch an alarm: `./demo_scripts/reachable.sh`

The same shape, but the target is reachable. Expect **ALARM**: the entry isn't
⊥. Then `pag check --inputs 5` runs the real class file on the JVM, which prints
`REACHED-1` and
`CONSISTENT — an alarm, and this run reaches reach(1): the alarm is real`.

### 3. Get a fast `pag` command (zsh)

`sbt cli/run` is slow to start, so do what
[common.sh](../demo_scripts/common.sh) does:

```sh
PAG_CP="$(sbt -batch -error api/package probeLib/compile 'export cli/Runtime/fullClasspath' | tail -n 1)"
pag() { java -cp "$PAG_CP" pag.cli.Main "$@"; }
```

### 4. Look at each stage

```sh
pag ir  demo_scripts/out/Reachable --no-lift   # what javac+Soot produced: BigInteger method calls
pag ir  demo_scripts/out/Reachable --cfg       # lifted to arithmetic, lowered to assume/assign edges
pag run demo_scripts/out/Reachable --inputs 5 --trace   # run on the IR interpreter: reaches 1
pag run demo_scripts/out/Reachable --inputs -3          # doesn't
```

### 5. Break a domain and catch it (the core idea)

Plant a bug in a copy of the interval domain that makes `x > 0` mean `x ≥ 2`,
which narrows too far. Everything goes under `demo_scripts/out/`, which is
gitignored.

```sh
# a probe where exactly one input (x = 1) reaches the target
mkdir -p demo_scripts/out/src demo_scripts/out/Boundary
cat > demo_scripts/out/src/Boundary.java <<'EOF'
import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

public class Boundary {
    public static void main(String[] args) {
        BigInteger x = Rand.randInt();
        if (x.compareTo(BigInteger.ZERO) > 0) {
            if (x.compareTo(BigInteger.TWO) < 0) { reach(1); }
        }
    }
}
EOF
javac -g --release 21 -proc:none -cp engine/probe-lib/target/classes \
      -d demo_scripts/out/Boundary demo_scripts/out/src/Boundary.java

# the mutant domain: a copy with a one-line bug
mkdir -p demo_scripts/out/mut-tutorial
cp -r domains/ref-interval/src demo_scripts/out/mut-tutorial/
F=demo_scripts/out/mut-tutorial/src/pag/domains/ref/interval/IntervalDomain.java
sed -i -e 's/case Gt -> below(r, l, one, env)/case Gt -> below(r, l, BigInteger.TWO, env)/' \
       -e 's/"ref-interval"/"mut-tutorial"/' "$F"
grep -n 'case Gt' "$F"     # confirm the edit took
domains/build-template/gradlew -q -p domains/build-template \
  -PdomainDir="$PWD/demo_scripts/out/mut-tutorial" \
  -PapiJar="$(ls $PWD/engine/api/target/pag-api-*.jar)" jar

REF=domains/ref-interval/build/libs/ref-interval.jar
MUT=demo_scripts/out/mut-tutorial/build/libs/mut-tutorial.jar
pag analyze --domain $REF --classes demo_scripts/out/Boundary --reach 1   # ALARM (correct)
pag analyze --domain $MUT --classes demo_scripts/out/Boundary --reach 1   # REFUTED (a false claim)
pag check   --domain $MUT --classes demo_scripts/out/Boundary --reach 1 --inputs 1; echo "exit $?"
```

Expected from the last command:

```
UNSOUND — the domain refuted reach(1), but the program reaches it
reaching run   demo_scripts/out/Boundary  inputs [1]
exit 3
```

Compare the two `analyze` maps at the `if x <= 0` line: the real domain shows
`x ↦ [1,1]`, the mutant shows ⊥. The certifier passed the mutant's map, because
it checks the map using the domain's own `transfer`. That gap is why a separate
adversary exists; in this step you played the adversary by hand.

## Part 2: What it all means

### What problem it solves

A static analyzer proves things like "this assertion never fails." To do that
it needs an **abstract domain**: a way to represent sets of program states, plus
the operations `join`, `widen`, `entails` and a backward `transfer`. Domains are
hard to write, and a subtle bug makes the analyzer **unsound**: it claims
safety when there isn't any.

### What "program analysis generator" means here

Not a generator in the parser-generator sense. The analysis is split in two:

- **A small hand-written engine**, the "trust base": front end, lowering,
  worklist, certifier and executors. Written once, tested heavily.
- **The domain**, which an LLM writes and nobody trusts. It goes through a loop:
  1. A **generator** agent writes a domain against
     [Domain.java](../engine/api/src/main/java/pag/api/Domain.java).
  2. An **adversary** agent tries to break it: a probe program plus inputs
     that reach a location the domain claimed was unreachable (step 5).
  3. A run that prints `REACHED-<id>` rejects the domain. Survivors are scored
     by how many locations they prove unreachable.

Soundness is a hard pass/fail; precision is a score. A domain that proves
nothing is trivially sound, which is why the score is needed.

The key trick: every question reduces to "is this location reachable?", and a
claim of "no" can be refuted by a print statement, without anyone knowing what
the domain's states mean. The design comes from Shawn Meier's dissertation and
the Historia analyzer, which work backward from a goal.

### Which language: a slimmed-down Java

It analyses **compiled class files**: `javac` → Soot (Jimple) → the project's
Scala IR → lifting (BigInteger calls become `+ - *`) → lowering (a CFG of
`assign`, `assume` and `call` edges).

The v1 language profile, `bigint-main-v1` (`implementation_strategy.md` §5.2),
allows:

- one class with only `main`
- every local a `BigInteger`, so integers are mathematical and nothing overflows
- `add`, `subtract`, `multiply`, `negate`, `valueOf`, `compareTo`, `equals`
- the constants `ZERO`, `ONE`, `TWO`, `TEN`
- `if` and loops
- input only through `Rand.randInt()`; targets marked with `reach(id)`

Anything else (fields, other methods, `divide`, `null`, `new`, arrays,
`switch`, `try`) fails with exit code 2 and a message naming the construct and
line. A domain therefore sees essentially the README's toy language IMP.

Real Java is used so that the verdict comes from the real JVM running the same
class file, not from the project's own interpreter. The language grows by
extending the profile's lists one construct at a time.

Two languages in the repo, easy to mix up: the engine is **Scala 3**; domains
and probes are **Java 21**, because small models write Java reliably.

### What you can do with it now

Phases 0–5 appear built: `pag ir`, `run`, `analyze` and `check`, plus one
hand-written reference domain. A human plays both agents. Today you can:

- watch backward abstract interpretation step by step
- hand-write a domain against `pag.api.Domain` and test it
- hand-write probes that try to break domains

### What's planned

Not built yet (`implementation_strategy.md` §14):

- the `campaign/` driver that runs the LLM generator and adversary (Phases 10–11)
- mutant domains and a measured adversary kill rate (Phase 8)
- a scoring corpus and `pag score` (Phase 7)
- the derivation graph for explaining alarms (Phase 4.5)
- sandboxing for generated code (Phase 9)

The research goal is in [experiments.md](../experiments.md): a ladder of rungs
R0–R6, from constants up to loops and relations between variables, to find
where small open-weight models stop producing a sound domain in one shot, and
how much feedback gets them past that point. Further out: relational domains,
`int` with wraparound, multiple methods, the forward pointer-analysis phase,
and Android-style framework code.

The README's "Layout" section still says "nothing described above is
implemented yet", which is out of date.
