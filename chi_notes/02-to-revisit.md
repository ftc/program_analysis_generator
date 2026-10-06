# To revisit

Concepts that did not click yet during the walkthrough. Come back to these.

## False alarms and imprecise domains (step 2, 2026-10-06)

What was said:

- **ALARM** means "could not prove the target unreachable". There are two
  causes:
  1. The target really is reachable (`Reachable.java`, step 2).
  2. The target is unreachable, but the domain cannot express the reason. This
     is a **false alarm**, and "imprecise" means exactly this.
- Example of cause 2:

  ```java
  BigInteger x = Rand.randInt();
  BigInteger y = Rand.randInt();
  if (x.compareTo(y) < 0) {          // x < y
      if (y.compareTo(x) < 0) {      // y < x
          reach(1);                  // impossible
      }
  }
  ```

  The interval domain keeps one range per variable and cannot record "x is
  less than y". Backward from the target the state stays ⊤ the whole way, so
  the result is ALARM. (Worked out from the code, not run.)
- A **relational** domain (facts like `x − y ≤ −1`) would combine the two tests
  into `0 ≤ −2`, reach ⊥, and refute it. This is rung R5 in `experiments.md`.
- False alarms are accepted because they are safe (they only lose proofs), they
  cannot be avoided (reachability is undecidable), and they are measured by
  **proof count** rather than rejected.
- `pag analyze` output alone cannot tell cause 1 from cause 2. A run that
  reaches the target shows cause 1; runs that miss show nothing.

Commands to try it are in the step 2 follow-up (create
`demo_scripts/out/src/Related.java`, compile with `javac` against
`engine/probe-lib/target/classes`, then `pag analyze --reach 1`).

Where to read more: `README.md` "The property under test" and "The interval
domain"; `glossary.md` *Alarm*, *Precision*; `experiments.md` E1.
