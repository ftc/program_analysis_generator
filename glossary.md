# Glossary

Every term of art used in `README.md`, `implementation_strategy.md`,
`dashboard.md` and `misc.md`, with the meaning it carries here. Terms inherited
from Shawn's dissertation or from Historia are marked, because several mean
something narrower here than there.

§13 below lists the collisions this pass turned up; they are the reason to read
it. Bare section references like §5.2 point at `implementation_strategy.md`;
references to this file and to others are named.

---

## 1. The analysis

**Abstract domain** (or just *domain*) — a state representation `S` plus the
operations over it (§5.4). Entirely generated; nothing in one is human-written.
Not to be confused with `dom(μ)`, a map's domain, which this project does not use.

**Invariant map**, written `I` — a map from location to abstract state. `I(ℓ)`
over-approximates the states at `ℓ` that **may reach the query**, not the states
reachable at `ℓ`. One map per query; there is no whole-program invariant.

**Goal-directed** — the analysis takes a target location as input and runs
backward from it. Opposed to standard forward abstract interpretation. (Ch. 4
§4.1.2, Ch. 5 §5.1.)

**Transfer function** — `transfer(step, post) -> pre`. Backward: given states
after a step, produce states before it. A model's prior is the forward version;
this has to be said explicitly in any generator prompt.

**Backward triple**, `⊢ {P'} c {P}` — read right-to-left: if an execution of `c`
reaches a post-state satisfying `P`, its pre-state satisfies `P'`.

**Entailment**, `⊑`, `entails(a, b)` — `a` is at least as strong as `b`. Used by
`[edge-inductive]`. Historia's `canSubsume`, without the solver.

**Join** `⊔`, **widen** `▽` — merge at control-flow joins and at loop heads
respectively. Widening has no convergence obligation here (plan §1, scope).

**Compute / Certify** — the engine's two stages (plan §7). Compute runs the worklist
and may be arbitrarily heuristic; Certify independently re-checks the settled
map. Only a map passing certification yields `Refuted`.

**`[edge-inductive]`, `[inductive]`, `[refute]`** — the three certification
checks. `entails(transfer(step, I(ℓ')), I(ℓ))` for every transition; `I(ℓ)` is
top at every target location; `isBottom(I(ℓ_init))`.

**Concretization**, `γ`, `σ ⊨ ŝ` — the meaning of an abstract state. Every domain
has one; **none writes it down**. It appears in Lemma 1 and in Future ideas, never
in code under the current design.

**Soundness** — every refutation is true. The requirement.

**Precision** — how many locations a domain proves. A score, not a requirement.
Measured by **proof count**.

---

## 2. The language and the IR

**Source IR** — `Cmd` / `RVal` / `LVal` / `BinOp` (§5.1), Historia's shapes,
Soot/Jimple-flavoured. What `IrProvider` produces.

**`Cfg`** — the lowered form: a set of `Transition(from, Step, to)` plus an entry.
What the analysis and the domain see.

**Lowering** — source IR → `Cfg`. Turns a conditional `Goto` into two
`assume`-guarded transitions so a domain never sees branching.

**`Step`** — `Assign` or `Assume`. The only two command forms a domain handles.

**`assume`** — a transition that blocks unless its condition holds. Carries all
branching, and also carries initial-state constraints on the entry transition.

**Language profile** — the config (§5.2) declaring which IR constructs load.
Anything outside it is a **loading failure**, never a silent skip.

**`intWidth`** — `32` (real Java, wrapping) or `unbounded` (mathematical
integers). Staged: `unbounded` first, `32` as a deliberate difficulty step.

**Location**, `Loc` — a position in a method, written `ℓ`. Because commands sit
on CFG edges, a location is a position *between* commands, and states live at
positions. A method with commands `0..n` has positions `0..n+1`.

Classically *program point* means a position and *location* means a statement's
address — Historia keeps both in `AppLoc` with an `isPre` flag. Edge-carried
commands dissolve that distinction here, so **use *location* for both** and
retire *program point*.

**`ℓ_init`** — the entry location. Admits every store, because initial
constraints lower to an `assume`.

---

## 3. Queries and verdicts

**Query** — the input naming what to ask about. Sealed, one case for now.

**`Reachable(method, line)`** — the only query form. Resolves through
`findLine` to **every** location on that line; reaching the line means reaching
any of them.

**Target location** — a location a query resolves to. Seeded `I(ℓ) = top()`.

**`Verdict`** — what the analysis learned, never how it stopped:
`Refuted` (certified unreachable), `Alarm` (searched fully, could not prove it),
`Inconclusive(why)` (did not search fully).

**`Incomplete`** — the single mechanism inside an `Inconclusive`:
`IterationLimit`, `Deadline`, `DomainFailure`. All three stop the search, so
exactly one can fire. There is no "we pruned" case; see *abandoning*.

**`AnalysisResult`** — the verdict plus what the search cost: iterations,
unexplored count, elapsed time, where it widened. Always collected, so a
campaign keeps these even at recording level `Off`.

**`DomainFailure`** — generated code threw, returned null, or hung. Every call
into a domain is wrapped; one exception must not end a campaign.

**Alarm** — the analysis could not prove the target unreachable. Not a claim that
it *is* reachable.

**Refutation** — a proof that a target is unreachable. The thing a reaching run
can contradict.

---

## 4. The derivation graph

**Derivation graph** — the DAG over (location, abstract state) pairs recorded
during the backward fixed point. An *observation* of the analysis, never part of
it (plan §8).

**`DNode`** — one node: `NodeId`, location, abstract state. Immutable; identity
is the id.

**`Derivation`** — an edge and why it exists: `Transfer`, `Join`, `Widen`.
Recorded in analysis order, target → entry; the reverse view is derived.

**`StopReason`** — why exploration stopped at a node: `Subsumed`, `Bottom`,
`ReachedInit`, `Uncertified`, `Unexplored`. Lives in a side map keyed by
`NodeId`, never in node identity.

**`CandidateTrace`** — a path through the graph from a `ReachedInit` node to
the query. **Admissible within the abstraction and nothing more**; joins mean it is
one selection among several, and it need not correspond to any concrete run.
Explains an alarm; proves nothing. Historia's `WitnessExplanation`.

**Recording level** — `Off`, `Terminal`, `Full`. Controls how much graph is kept.

**Reconstruct-from-`I`** — deriving a candidate path from the invariant map alone,
with no recording. Cheap, but cannot show subsumption, widening or drops.

**Subsumed** — a state absorbed into an existing one; the branch is genuinely
closed. **Bottom** — `transfer` produced `⊥`; the branch is refuted.
**Unexplored** — still in the worklist when the budget ran out. In Historia all
of these were marked through the one `subsumed` field.

**Unexplored (cause) vs Inconclusive (outcome)** — an unexplored node makes
certification fail at its edge; the *query* is then `Inconclusive` rather than
`Alarm`, because the failure was our budget and not the domain's imprecision.
Only `Alarm` counts against proof count; `Inconclusive` is excluded from scoring.

**Weakening vs abandoning** — *weakening* replaces a state with a weaker one and
is sound for proving; *abandoning* skips a path and is not. Historia's
`DropQryPolicy` interface does both — `shouldDropOrModify` returns `None` to
abandon and `Some(weakened)` to weaken — and its abandoning policies belong to
the under-approximate mode. This engine never abandons, and cannot weaken either
since `S` is opaque to it, so **all weakening lives in the domain's `join` and
`widen`**.

---

## 5. Domains and their obligations

**`S`** — a domain's state type. Opaque to the engine and to the harness.

**`top`, `bottom`, `isBottom`** — the extremes and the refutation test.

**Reference domain** — the hand-written interval domain. A **fixture**: a
known-good baseline to develop against and to seed mutants from, not something
the system requires.

**Mutant** — a deliberately unsound domain, used to calibrate the adversary.

**Smoke test** — checks at domain load that every construct the active profile
enables is handled, so failures surface at load rather than mid-run.

---

## 6. The reachability check and reaching runs

**The reachability check** — the mechanism: a refutation is falsifiable by
running a program. `pag check` performs it.

**Probe program**, or just *probe* — a Java program the adversary writes, in
`probes/<campaign>/`. The artifact, never the mechanism.

**`ReachingRun`** — a program, its arguments, and the location it gets to, where
the domain proved that location unreachable. **The only artifact in this project
that proves anything.** Formerly called a witness.

**`instrumentReach`** — writes a copy of the classes that prints
`REACHED-<id>` on arrival at a target location.

**Marker** — the `REACHED-<id>` string. Presence on stdout is the verdict.

**Executor** — what runs a program. Stage 1 is our interpreter; Stage 2 is
`javac` plus the JVM, and is the verdict of record.

---

## 7. The agents and the loop

**Generator** — the agent that writes domains. Scored on proof count.

**Adversary** — the agent that tries to break them. Scored on kill rate. Reads
the domain source deliberately; must still produce an executable reaching run.

**Kill rate** — fraction of seeded mutants an adversary breaks within a budget.
The number that makes "the adversary found nothing" mean anything.

**Adversarial adequacy** — the standing assumption: if a domain is unsound, an
adversary with a reasonable budget finds a reaching run. Not a theorem.

**Campaign** — one run of the outer loop over a set of domains, with a fixed
profile, api version, corpora and agent configs. The unit that makes results
comparable; everything produced records which campaign produced it.

**Campaign driver** — the codebase in `campaign/` that runs the loop. Separate
from the engine on purpose: it drives `pag` by subprocess and exit code, so a
generated domain that hangs can be killed, and so the engine never needs network
access or credentials. Plan §12.

---

## 8. Corpora

Four distinct collections, easy to confuse:

| name | contents | used for |
| --- | --- | --- |
| **probe corpus** | programs the adversary wrote | attacking a domain |
| **scoring corpus** | programs with many targets, some infeasible by construction | proof count |
| **mutant corpus** | deliberately unsound domains | calibrating the adversary |
| **reaching runs** | probes that actually broke a domain | rejection records, generator feedback |

---

## 9. The command line

**`pag`** — the engine binary. `ir` (what the front end produced), `run`
(execute, report locations visited), `analyze` (verdict and invariant map),
`check` (the reachability check), later `score`. It does only what needs no
network and no credential; anything that talks to a model is the campaign
driver's. Plan §11.

**`--at M:L`** — the query, i.e. `Reachable(method, line)`.

**Exit codes** — the agent-facing contract, so drivers never parse prose:
`0` completed, `1` usage/IO, `2` profile violation, `3` unsound (a reaching run
contradicted a refutation), `4` inconclusive, `5` domain failure. 4 and 5 differ
because one says raise the budget and the other says the domain is broken.

## 10. Serialization

**Wire format** — `serialization.format = "json" | "cbor"`, one codec set
serving both via Borer. JSON by default; CBOR when the derivation graph at
`Full` recording makes it worth it. Plan §13.

**`pag dump <file>`** — prints any stored record as JSON whatever it was written
as, so binary never costs inspectability.

**`engine/results`** — the small shared module holding result ADTs and codecs,
depended on by both the engine and `campaign/`. Deliberately not `engine/api`,
which generated domains compile against.

**Versioned envelope** — engine version, api version, profile name, campaign id
on every record. The actual compatibility need, independent of format.

## 11. Modules and artifacts

**`engine/api`** — the contract and the IR. Pure Java, no Scala dependency. The
only engine code an agent sees.

**`engine/frontend-soot`** — the only module allowed to import `soot.*`.

**`engine/core`** — worklist, invariant map, certifier.

**`engine/harness`** — executor, probe runner, verdict, scoring, mutation corpus,
agent drivers.

**`IrProvider`** — the one interface through which programs enter: `load`,
`findLine`, `isLoopHead`, `instrumentReach`.

---

## 12. Inherited terms, and what they mean here

| term | in the dissertation / Historia | here |
| --- | --- | --- |
| Lemma 1 | *hoare triple soundness*, Ch. 4 p. 89, with a framework-spec parameter | the per-step soundness condition, heap/spec parameter dropped |
| `IRWrapper` | Soot-coupled IR facade, ~2150 lines with APK and callback handling | `IrProvider`, translation only |
| `canSubsume` | entailment via Z3, ~1800 lines | `entails`, a domain method, no solver |
| `IPathNode` | path-node tree, mutable status, implicit `OutputMode` | derivation graph (plan §8), redesigned |
| `WitnessExplanation` | the printed path for an alarm | `CandidateTrace` |
| `WitnessedQry` | search state meaning the entry was reached | `Verdict.Alarm` plus a `CandidateTrace` |
| `InitialQuery` | `Reachable`, `ReceiverNonNull`, `CallinReturnNonNull`, … | `Reachable` only; the rest to be re-engineered |
| **consistent** | consistent with known reachable locations | *avoid* — see `misc.md` §5 |
| **witness** | an alarm that reached the initial state | *retired*, except **must-witness** |

---

## 13. Collisions and inconsistencies found

Listed in the order I would fix them.

**1. `Terminal` meant two things.** ✅ *Resolved.* The enum is now `StopReason`;
`Terminal` is only the middle recording level. `Terminal.Entry` also became
`StopReason.ReachedInit`, which fixes collision 4.

**2. *probe* meant two things.** ✅ *Resolved.* The mechanism is the
**reachability check**, matching `pag check`; *probe* now names only the
artifact.

**3. *step* meant two things.** ✅ *Resolved.* The limit is the **iteration
limit** and prose says "worklist iterations". `Step` remains the command type.

**4. `Entry` was a stop reason *and* a location.** ✅ *Resolved* by
`StopReason.ReachedInit`.

**5. *program point* vs *location*.** ✅ *Resolved.* **Location** everywhere;
§5.3 of the plan now states why the classical distinction does not arise here.

**6. *corpus* is unqualified in several places.** With four corpora (§8 above), bare
"the corpus" is ambiguous. Always qualify.

**7. *target* is doing two jobs.** A *target location* is where a query points; a
*target* in `intWidth`/profile prose means the language we aim at. Minor, but
worth watching as the profile grows.

**8. `Dropped` vs `Exhausted`.** ✅ *Resolved, and the concept was wrong.*
`Exhausted` is now `Inconclusive(why: Incomplete)` — verdicts name outcomes,
`Incomplete` cases name mechanisms. `Dropped` is gone entirely: abandoning a
path is under-approximate machinery and has no place in a verifier, so the
per-node case is `Unexplored` (the budget ran out first). Plan §7 has the
verdict table; plan §8 explains it against Historia's `ResultSummary.Timeout`, which
confusingly means *inconclusive* rather than *timed out* — the same conflation
this rename removes.
