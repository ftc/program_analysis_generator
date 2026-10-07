# Cheat sheet

Quick reference from the walkthrough. Project terms are in `glossary.md`; this
is the plain-words version.

## ALARM vs REFUTED: the smoke detector

The "fire" is the marked line actually running.

- **ALARM**: the detector goes off. "It *might* run; I couldn't rule it out."
  A warning, not a claim that it runs.
- **REFUTED**: **R**uled out. "I *proved* it can never run."

|                                  | line really can run (fire)                       | line can't run (no fire)         |
|----------------------------------|--------------------------------------------------|----------------------------------|
| **ALARM** (detector sounds)      | real alarm: correct                              | **false alarm**: annoying, safe  |
| **REFUTED** (detector is quiet)  | **broken detector: dangerous** → UNSOUND, thrown out | correct: a real proof         |

Runs from the walkthrough:

| program, rulebook                     | verdict | cell                         |
|---------------------------------------|---------|------------------------------|
| `Unreachable.java`, real              | REFUTED | correct proof                |
| `Reachable.java`, real                | ALARM   | real alarm                   |
| `Boundary.java`, real                 | ALARM   | real alarm                   |
| `Boundary.java`, buggy                | REFUTED | broken detector → UNSOUND    |
| `Related.java` (`02-to-revisit.md`)   | ALARM   | false alarm                  |

The goal: as many REFUTEDs as possible (fewer false alarms), never the
dangerous cell.

Third, rarer verdict: **INCONCLUSIVE**, meaning it ran out of time or steps and
gives no answer.

## Reading a `pag analyze` map

- Read **bottom-up**: the rulebook starts at the marked line and works backward.
- Each row: what must be true *just before* this line to still reach the marked
  line.
- `⊤` = anything works. `⊥` = impossible. `x ↦ [1,1]` = x is exactly 1.
  `x ↦ (-∞,1]` = x ≤ 1.
- **Top row decides:** `⊥` → REFUTED, anything else → ALARM.
- `certified N/N edges` = every row follows from the one below *by the
  rulebook's own rules*. It does not mean the rules are right.

## `pag check`: the claim vs. a real run

|                       | run reaches the line            | run doesn't                     |
|-----------------------|---------------------------------|---------------------------------|
| **REFUTED** ("never") | **UNSOUND**, exit 3: claim false | CONSISTENT, exit 0              |
| **ALARM** ("maybe")   | CONSISTENT, exit 0: alarm real   | CONSISTENT, exit 0: proves nothing |

Other exit codes: 1 = usage mistake, 2 = program outside the allowed Java
subset, 4 = inconclusive, 5 = the rulebook crashed.

## Plain words ↔ project terms

| plain words                         | project term            |
|-------------------------------------|-------------------------|
| rulebook                            | domain                  |
| AI that writes rulebooks            | generator               |
| AI that tries to break them         | adversary               |
| deliberately broken rulebook        | mutant                  |
| the trusted human-written referee   | trust base / engine     |
| program written to test a rulebook  | probe                   |
| the marked line, `reach(1)`         | target location         |
| the program's input, `Rand.randInt()` | nondeterminism / inputs |
| a run that catches a rulebook       | reaching run            |

## Commands

```sh
# once per terminal (zsh), from the repo root
PAG_CP="$(sbt -batch -error api/package probeLib/compile 'export cli/Runtime/fullClasspath' | tail -n 1)"
pag() { java -cp "$PAG_CP" pag.cli.Main "$@"; }

REF=domains/ref-interval/build/libs/ref-interval.jar
pag analyze --domain $REF --classes demo_scripts/out/Boundary --reach 1              # verdict + map
pag check   --domain $REF --classes demo_scripts/out/Boundary --reach 1 --inputs 1   # + a real run
pag ir      demo_scripts/out/Boundary --cfg                                          # what the analysis sees
pag run     demo_scripts/out/Boundary --inputs 1 --trace                             # run on the IR interpreter
```

Demo scripts: `./demo_scripts/unreachable.sh`, `./demo_scripts/reachable.sh`,
`bash chi_notes/step5_break_a_domain.sh`.
