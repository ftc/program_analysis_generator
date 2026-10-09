# Findings

What the experiments in `experiments.md` have shown so far, with the evidence
for each claim. Added 2026-10-09 at Shawn's request, so that results survive the
next campaign rewriting `results/` or the report.

Entries are dated and kept as written. When a later run changes a conclusion,
add a new dated entry that says which earlier claim it revises; do not edit the
old one. Each entry names the campaigns it rests on, so its numbers can be
checked against `results/<campaign>/*/attempt.json` or the commit that holds
them.

---

## 2026-10-09 — E1, rung 0, one shot: Qwen3.5-0.8B and Qwen3.5-9B

**Claim.** At 9B and below, rung 0 one shot does not reach the stage where a
domain's analysis can be judged: 10 of 10 attempts stopped before evaluation.
The 9B attempts all reached the compiler and failed there, mostly on Java scoping
mistakes rather than on the domain's design. So without build feedback, the
one-shot table for these sizes measures Java fluency against our contract, not
whether the model can design a domain. Build feedback is the cheapest step that
makes the later stages observable. That is why the next campaign is rung 0 with
build feedback (`implementation_strategy.md` §16, item 25).

**Status of the evidence.** 2 of the 6 models in the first run. The LaTeX report
has not been run. The rows below were read from each `attempt.json` by hand, and
the report should reproduce them. The larger models (Qwen3.5-2B, 4B, 27B,
Qwen3.8-27B) are untested, so this entry says nothing about them.

### Setup, as pinned

Both campaigns' `campaign.json` pin the same inputs:

| input           | value                                                                                   |
|-----------------|-----------------------------------------------------------------------------------------|
| prompt          | `generator-v2`, sha256 `1ed8c02d…2990e`                                                 |
| corpus          | smoke, sha256 `0b6fff6e…1b869`                                                          |
| profile         | `bigint-main-v1`                                                                        |
| settings        | the first run's (`experiments.md`, *The run settings*): thinking on, 32,768 tokens, T 0.6 |
| samples         | 5 per model                                                                             |
| 0.8B            | `Qwen/Qwen3.5-0.8B` revision `2fc06364…`, Q8_0 GGUF sha256 `460b97e0…`                  |
| 9B              | `Qwen/Qwen3.5-9B` revision `c2022362…`, Q8_0 GGUF sha256 `2f055709…`                    |
| harness commit  | 0.8B at `411981b`, 9B at `cf284b1`; both recorded as **dirty**                           |

The prompt was 2,230 tokens for every attempt.

### Results

| model | sample | stopped at          | files | javac errors | completion tokens | reply time |
|-------|--------|---------------------|-------|--------------|-------------------|------------|
| 0.8B  | 1      | did not compile     | 2     | 22           | 1,933             | 5 s        |
| 0.8B  | 2      | ran out of tokens   | 0     | —            | 32,768            | 97 s       |
| 0.8B  | 3      | did not compile     | 2     | 26           | 1,544             | 5 s        |
| 0.8B  | 4      | did not compile     | 3     | 14           | 2,858             | 8 s        |
| 0.8B  | 5      | ran out of tokens   | 0     | —            | 32,768            | 97 s       |
| 9B    | 1      | did not compile     | 2     | 11           | 13,672            | 169 s      |
| 9B    | 2      | did not compile     | 2     | 5            | 4,640             | 57 s       |
| 9B    | 3      | did not compile     | 2     | 20           | 6,351             | 78 s       |
| 9B    | 4      | did not compile     | 3     | 4            | 6,111             | 75 s       |
| 9B    | 5      | did not compile     | 2     | 15           | 5,256             | 64 s       |

Per model: 0 of 5 mechanically acceptable, 0 unsound, 0 inspected. "javac
errors" is javac's own count. Gradle prints each error twice, so a raw count of
`error:` lines in `compileLog` is double.

### Qwen3.5-0.8B: fails at the task, not only at Java

- **Two of five looped in the answer** until the token cap (samples 2 and 5).
  Sample 2 repeated `entails`/`join`/`widen`/`transfer` overrides. Sample 5
  repeated one `@Test` method. Neither closed its code block, so no files were
  read. (Under `generator-v1` all five looped; see `experiments.md`, *Record*.)
- **The three that finished misread the contract,** in ways that are not
  scoping slips:
  - sample 1 wrote `extends Domain<Integer>` on an interface and used the
    contract's type variable `S` as though it were a class;
  - sample 3 named its class `Domain`, made it `extends RVal`, and returned
    `long` where `BigInteger` is required;
  - sample 4 re-declared the contract's own types (`Step`, `LVal`, `MethodId`)
    in `pag.domains.gen` and gave three other files twice.
- Replies that finished were short: 1,500–2,900 tokens in 5–8 s.

Reading: build feedback may not be enough at 0.8B. Its errors come from not
using the contract as given, and the feedback campaign will show whether it can
recover from them.

### Qwen3.5-9B: understands the task, fails on Java

All five finished normally (`finishReason: stop`). Each wrote a domain class
and a JUnit test. The domains named were `IntervalDomain` (samples 1, 2, 5),
`SimpleIntDomain` over intervals (4), and `ReachabilityDomain` tracking
constants (3). That is, 4 of 5 chose intervals without being told to. The
errors, per sample:

| error                                                                      | samples       |
|----------------------------------------------------------------------------|---------------|
| state class declared inside the domain class, named in `implements Domain<…>`, where it is out of scope | 1, 2, 3, 4, 5 |
| `Assign`, `Assume`, `Call` used unqualified after `import pag.api.*`, which does not import types nested in `Step` | 1, 2, 3, 4, 5 |
| record components read as fields (`target.name`, `ic.v`, `binop.l`) instead of accessors | 1, 5          |
| `Local` used unqualified (nested in `LVal`)                                | 3             |
| an `enum` used as a sum type (`EQ(BigInteger)` beside `ANY`, `BOTTOM`)     | 3             |
| type mix-ups between `Interval[]` and `BigInteger[]`; an invalid method reference | 5             |

So the first two rows, both scoping, are in every sample. Samples 2 and 4 have
nothing else. Samples 3 and 5 also have real type errors.

Two further observations:

- Samples 1 and 4 wrote their sources under `src/pag/domains.gen/`, a directory
  with a dot in its name, instead of `src/pag/domains/gen/`. The reply parser
  accepted the path. javac reported no error about it, but those builds failed
  for other reasons. Whether a domain built from there would load was not
  checked.
- Sample 1 used 13,672 tokens, 44,918 characters of them reasoning. The others
  used 4,600–6,400.

**Checked in the 2026-10-09 session** (recorded in item 25, not re-run here):
the two scoping errors reproduce with plain `javac` 21, so the Gradle template
is not the cause. A hand-fixed copy of sample 4's domain compiled after four
small edits. That copy was not committed, and it was not run on the smoke
corpus.

### Caveats

- **Counts are lower bounds.** Gradle runs `compileJava` before
  `compileTestJava`, and no `compileLog` mentions `compileTestJava`, so no
  attempt's test sources were compiled at all. javac may also hold back later
  errors, such as flow analysis, while earlier ones remain; this was not
  checked against javac's source. Fixing the reported errors may reveal more,
  and the feedback campaign's round count has to allow for this.
- **The prompt may share the blame.** The contract shows `Step.java` with
  `Assign`, `Assume` and `Call` as nested records, with no example of how to
  refer to them. A prompt line or a rung-0 example could remove the most
  common error without any feedback. That would be a change of prompt, which
  makes it a new rung-0 version (`experiments.md`, *The information ladder*),
  not feedback. It is a competing explanation that the feedback campaign alone
  cannot rule out.
- **Five samples per model,** two models, both of one family.
- **Both harness commits were dirty** when the attempts ran, so the recorded
  commit does not pin the harness exactly.
- **"Necessary" holds for 9B and below only.** Whether a 27B compiles one shot
  is the open question the remaining one-shot rows answer.

### What follows

- Run rung 0 with build feedback (item 25) before filling the one-shot table
  for every size. Its open questions are still to be decided.
- Keep the one-shot campaigns for the remaining sizes. They are the baseline the
  feedback campaign is measured against.

---

## 2026-10-09 (later) — E1, rung 0, one shot: Qwen3.5-27B

**Revises** the caveat in the entry above that "necessary" holds only for 9B and
below. It also holds for Qwen3.5-27B: 4 of 5 attempts did not compile. The one
that did was evaluated, was sound on the smoke corpus, and proved nothing.

**Claim.** At 27B, the model uses the contract much better than 9B. It
qualified `Step.Assign`, `Step.Assume` and `Step.Call` in all five samples,
which 9B never did. Its build errors are fewer (1–9 per attempt, against 4–20
at 9B) and each has one or two causes. One shot still almost never builds, so
build feedback is needed here too. The first evaluated domain shows that
getting past the build is not the end of it: compiling is not proving.

**Status of the evidence.** Campaign `e1-rung0-qwen3.5-27B`, committed in
`92580fb`. Same prompt, corpus, profile and settings as the entry above (the
`campaign.json` pins match). Model `Qwen/Qwen3.5-27B` revision `fc05daec…`,
Q8_0 GGUF sha256 `d084885a…`. Harness commit `42fe7e2`, recorded as **dirty**.
The report has not been run; the rows were read from each `attempt.json`.

### Results

| sample | stopped at      | domain                      | files | javac errors | completion tokens | reply time |
|--------|-----------------|-----------------------------|-------|--------------|-------------------|------------|
| 1      | did not compile | intervals                   | 2     | 1            | 9,506             | 344 s      |
| 2      | did not compile | linear constraints          | 2     | 3            | 4,506             | 163 s      |
| 3      | evaluated       | constants and equalities    | 2     | 0 (2 in its test) | 4,850        | 175 s      |
| 4      | did not compile | intervals                   | 2     | 6            | 11,919            | 431 s      |
| 5      | did not compile | intervals                   | 2     | 9            | 9,559             | 346 s      |

All five finished normally (`finishReason: stop`). 0 of 5 mechanically
acceptable, 0 unsound, 0 inspected. "domain" is read from the class name and
its state, not from Shawn's inspection.

### The build errors

| error                                                                       | samples |
|-----------------------------------------------------------------------------|---------|
| state class declared inside the domain class, named unqualified in `implements Domain<…>` | 1, 5    |
| `name()` called on `LVal` instead of `LVal.Local`, after `instanceof LVal`  | 5       |
| record components read as fields (`assign.target`, `assign.source`)         | 2       |
| its own record constructed with the wrong arguments                         | 2       |
| `const`, a reserved word, used as a pattern variable name; one mistake, two places, 6 errors | 4       |
| `Map` used in the test without importing it                                 | 3 (test only) |

Sample 1's only error is the state-class scoping mistake. Samples 2, 3 and 4
qualified their state class (`Domain<LinearConstraintDomain.State>`, and so
on), which avoids it.

### The evaluated domain (sample 3)

`EqualityDomain` tracks variables known to equal a constant, and variables
known to equal each other. Its main source compiled and loaded. Its test file
did not compile because of the missing `java.util.Map` import, so none of its
tests ran.

On the smoke corpus it raised an alarm on all eight targets: 0 of 5
unreachable targets proved, no reachable target refuted, every run
`Consistent`. So it is sound here and useless here. A constants domain should
be able to prove `Const1`, and this one did not. Why was not investigated.

### What this changes in the entry above

- **The prompt explanation is weaker.** The entry above suggested the prompt's
  nested `Step` types might explain 9B's most common error. 27B read the same
  prompt and got them right five times out of five, so that error depends on
  the model at least as much as on the prompt. A prompt fix could still help
  the smaller sizes.
- **The scoping of the domain's own state class persists at 27B** (2 of 5). It
  is the one error common to 9B and 27B.

### A gap in what is recorded

Sample 3's `summary` says `builds: true, testsRun: 0, testsFailed: 0`. The test
compile failure is only in `build.testLog`. So in Table 1 its *Tests* cell will
read as no tests run rather than as tests that did not compile, and the
summary cannot tell those apart. Not fixed here; whether to record it is a
question for Shawn.

### What follows

- Unchanged from the entry above: build feedback (item 25) next. 27B is now
  a third data point for it, and the most likely size to get past the build in
  a round or two.
- The evaluated domain is the first one that `pag` judged. That it proved
  nothing, though sound, is the first sign of the second question: what it
  takes for a domain that builds to prove anything (feedback kinds 2–5 in
  `experiments.md`).
