# Misc — unfiled ideas from 2026-09-22

Things that came out of discussion but are not in `README.md` or
`implementation_strategy.md`. Sections 2–5 would change the architecture, so
they are parked here rather than folded into the plan. Roughly in order of how
much they would change.

---

## 1. The trust base: what a refutation actually depends on

Generated code is checked against human code; human code is checked by proof,
review, and ordinary testing. So the human-written code is the trusted base,
and its size is the honest measure of what a refutation rests on.

| # | Claim | Who | How established |
| --- | --- | --- | --- |
| 1 | Observed steps are real executions | human (`core`) | interpreter correctness, or JDI |
| 2 | `contains` means what you think | human, **per domain** | review only |
| 3 | The api's stated obligations are the right ones | human, once | review; golden domain |
| 4 | The checkers implement those obligations | human, once | mutation kill rate |
| 5 | Probes covered enough behavior | — | the adequacy assumption |
| 6 | Generated ops satisfy their obligations | model | **the loop**, given 1–5 |
| 7 | `[inductive]`/`[refute]` compose correctly | human, once | hand proof |

Only row 6 is what the generate-and-test machinery establishes.

**Three fail silently.** A too-tight `contains` makes every other obligation
vacuous and refutes everything including real bugs — while a too-permissive one
is harmless, since `excludesInit(s) ⟹ !contains(init,s)` then forces
`excludesInit` never to fire and the analysis merely proves nothing. A
reference interpreter that diverges from real execution yields an analysis
sound about a language nobody runs. A broken certification emits refutations
the per-command conditions do not support, and the loop is blind to it by
construction.

**Consequences for the plan:** pull JDI earlier than Phase 10 (it is the only
thing that grounds row 1), and treat mutation kill rate as primary evidence
rather than a metric, since row 4 is otherwise unvalidated.

### Update 2026-09-23 — the table above describes the superseded design

Under the reachability check now in `README.md`, this collapses to two rows:

| # | Claim | How established |
| --- | --- | --- |
| 1 | The executor implements the intended semantics | Stage 1: our interpreter. Stage 2: `javac` + the JVM, i.e. the intended semantics *is* what Java does |
| 2 | The adversary is strong enough that its silence means something | mutant kill rate (plan Phase 8) |

Everything else is covered end to end. A broken certifier emits a refutation
that is wrong, and the adversary catches it. A domain whose implicit semantics
disagrees with the executor's is, by definition, wrong relative to the executor —
also caught. `contains`, the checkers and the api's obligation statements are
gone from the trusted base because they are gone from the system.

The old row 1 was the interpreter's fidelity to real execution; it survives, but
Stage 2 discharges it with a code generator and a print statement instead of JDI,
which is a much smaller thing to get right.

---

## 2. Reachability as the oracle, and the print-statement observer

Two halves, both from the dissertation:

1. **Any state question reduces to location reachability.** To ask whether `P`
   holds at `ℓ`, insert `if (P) { ℓ_fresh: }` and ask whether `ℓ_fresh` is
   reachable. Already relied on in `goaldirinclogic.tex:9` ("It is always
   possible to compile an assertion to the reachability of a location") and in
   the formalization of error conditions as `⊤` at a location
   (`chapter7.tex:40`).
2. **Reachability is observed by a print.** Unique id at the location, run the
   program, read stdout. No debugger, no JDI, no state inspection.

**Why it matters.** All of §6 of the plan exists to observe *states*. Under this
reduction it is unnecessary, and row 1 of the trust base collapses from "our
interpreter is faithful" to "the program printed the id" — about as close to
bedrock as evidence gets, and language- and toolchain-agnostic.

**The larger consequence.** A pure reachability oracle may remove `contains`,
`alpha` and `Store` from the contract entirely, since nothing needs to relate
abstract states to concrete ones if the only test is end-to-end. That would
leave no human-written domain artifact at all.

**Properties to keep in mind.**
- It is not a coarser test but a *universal encoding*. Granularity is the
  tester's choice via guard placement, so localization is recoverable by
  probing narrowly.
- Validity runs one way: printed ⟹ reachable ⟹ a refutation of it was unsound.
  Not printing proves nothing.
- Cost is the tradeoff — each query is a full backward fixed point, versus a
  nearly free per-obligation check.

**Resolved 2026-09-23.** I had flagged these as possibly mutually exclusive, on
the grounds that removing `contains` leaves the fast inner loop nothing to check
against. That was wrong. The model can generate `contains` as *its own scratch
oracle* for iteration while reachability stays the acceptance criterion — it
then never has to be trusted, only useful to the generator. If it is wrong, the
model's fast tests mislead it, the adversary breaks the result, and the feedback
is that `contains` was wrong too. `README.md` now adopts reachability alone,
with this kept under Future ideas.

Related dissertation claims: a framework model is unsound if a known reachable
location can be "proven" unreachable (`chapter6.tex:8`); the four-way candidate
verdict (`figenummodels.tex:88-91`); ~10 reachable locations sufficed, though
**hand selected to demonstrate the unsound behavior** rather than sampled
(`conclusionandfuture.tex:21`).

---

## 3. Adversarial agents

A dedicated agent, separate from the one writing the domain, searches for an
explicit program `p` and location `ℓ` where the domain proves `ℓ` unreachable
but running `p` reaches it. Combined with §2 that is a complete, self-contained
falsification — the analysis said no, execution said yes — needing no trust in
`contains`, the interpreter, or the checkers.

It is also a better-posed task for an LLM than "write probes that exercise the
transfer function." "Write a program that breaks this analyzer" is concrete,
verifiable, and creative in a way models handle well.

**Design notes.**
- The adversary produces only *positive* evidence of unsoundness. It can never
  certify soundness, which matches the project's premise.
- It needs a dual requirement or it is trivially satisfied: a domain that proves
  nothing survives every adversary. Mirror the synthesis acceptance in
  `chapter7.tex:40` — prove the target AND keep reachable locations reachable.
- **Collusion risk.** If one model writes both the domain and the adversary it
  may not attack hard. Separate agents, preferably separate models.
- **The mutation corpus changes role** — see the note now in Phase 7.

---

## 4. The safety side of the dual obligation

The two halves are epistemically asymmetric. Reachability has cheap positive
evidence: one print, one run, conclusive. Safety has *none* at any finite cost —
unreachability is universally quantified over executions, so ground truth on it
would require the proof we are trying to generate.

So the precision half cannot be "prove these known-safe locations." It has to be
**maximize proven locations subject to soundness** — maximize the count proven
in production software, constrained by surviving the adversary. Precision
becomes a score, soundness stays a constraint. Unconstrained it is degenerate:
prove everything, score perfectly, be maximally unsound.

**Partial ground truth that is obtainable:**
- *Synthetic infeasible guards.* `if (x > 0 && x < 0) print(ID)` is safe by
  construction. Gradeable by the reasoning required — interval intersection vs.
  relational vs. arithmetic — so proof count measures a domain's reasoning power
  against a known answer.
- *Fix commits.* Paired buggy/fixed programs where the crash location is
  reachable before and believed-unreachable after. Real software, and it mirrors
  the dissertation's own evaluation structure.
- *Bounded model checking.* "No execution of length ≤ k reaches ℓ" is genuine
  bounded evidence.

**The probabilistic angle.** The mutation corpus calibrates the adversary's
sensitivity (kill rate on seeded unsound domains), which lets the adversary's
*failure* carry quantified weight — a diagnostic test with measured sensitivity.
Without calibration, "the adversary didn't break it" is uninterpretable.
Caveat: calibration transfers only if seeded mutants resemble the unsoundness an
LLM actually produces, which is checkable by collecting real failures as they
occur.

**Confound to design around.** A domain may prove more locations because it is
more precise *or* because it is unsound in ways the adversary missed. Those are
not independent, so an outlier proof count should *raise* suspicion. Use it as
an active-learning signal: spend adversary budget preferentially on the domains
claiming the most.

---

## 5. Two kinds of consistency — terminology to fix

Both are load-bearing and the obvious words collide.

- **Internal.** The result is a genuine fixed point: the invariant map is
  inductive, the certification checks pass, and the domain's operations cohere
  with each other. Candidates: *inductive*, *certificate-valid*,
  *post-fixpoint*, *internally coherent*. May itself want splitting into
  operation-level coherence vs. the computed result being inductive, since those
  can fail independently.
- **External.** The domain does not contradict observed behavior: no
  known-reachable location is proven unreachable. Candidates: *observationally
  consistent*, *empirically consistent*.

**Hazard: the dissertation already claims both words for the external sense.**
"shows that a framework model is consistent" (`chapter6.tex:7`), "consistent
with the known reachable issues" (`chapter7.tex:150`), and "sound with respect
to known reachable locations" (`chapter7.tex:22`). So *consistent* is taken, and
*sound* is overloaded against the classic abstract-interpretation meaning, which
is what "soundness" means everywhere in this project. The internal sense needs a
word that is neither.

**Why both matter:**

| | externally consistent | externally inconsistent |
| --- | --- | --- |
| **internally consistent** | what we want | the dangerous case: everything coheres, reality disagrees (a too-tight `contains`) |
| **internally inconsistent** | passes the oracle only through a coverage gap | caught easily |

The off-diagonal cells are the argument. Neither notion detects the other's
failure.

---

## 6. Domain design vs. selection vs. semantics

"Choosing the abstract domain" is three separable things, with very different
soundness weight:

- **Design** — the state type `S`. Nearly free: a bad choice costs precision,
  not soundness. The one exception is representation invariants, e.g. an
  `Interval(lo, hi)` with `lo > hi` that `contains` mishandles. Make illegal
  states unrepresentable and `S` stops being a soundness surface.
- **Meaning** — `contains`. The only genuinely load-bearing human artifact.
- **Semantics** — transfer and the lattice ops. Generated and tested today.

Plus a fourth thing the plan leaves implicit: **selection**, i.e. who decides
which domains go in the config. Running several and reporting a refutation when
any one refutes is sound in the logic, since each refutation stands alone — so
precision becomes a search problem, which suits a model. But it is not free in
the trust base: N domains means N `contains` implementations trusted, so
exposure scales with N.

**Intermediate worth considering:** let the model propose `S` and whole
candidate domains while the human writes or certifies only `contains`. Gets most
of "the model chooses the domain" without touching the trusted base, and
accumulates a corpus of hand-written `contains` implementations to mutate
against later.

---

## 7. Smaller notes

- **Historia sizing, for the simplification claim.** `AbstractInterpreter` is
  ~1200 lines and `TransferFunctions` ~1000, mostly because the IR is real Java
  and the domain is entangled with the solver — `StateSolver.canSubsume` is
  ~1800 lines doing entailment via Z3. With IMP and an opaque domain behind an
  interface the engine core should be a few hundred lines.
- **Human-written size estimate.** ~1200 lines of engine written once, plus ~30
  lines per domain (state type + `contains`). The 30-line figure is
  interval-specific; for a separation-logic domain `contains` is a matching
  search with an injectivity condition, closer to 100 lines with real logic in
  it. Human cost scales with domain complexity — the wrong direction, and an
  argument for §2 and §6.
- **Separation-logic `contains` subtlety**, if that domain is ever built: the
  induced map on keys must be *injective*, since `v̂.f` and `v̂'.f` are separate
  cells and a valuation with `ν(v̂) = ν(v̂')` must not witness containment.
  Dropping that check lets unsound field-write transfers pass.
- **Nothing is built.** The repo is still the sbt "Hello, Scala!" skeleton, 31
  lines, none of it relevant.
