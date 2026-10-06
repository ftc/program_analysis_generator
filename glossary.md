# Glossary

Every term of art used in `README.md`, `implementation_strategy.md`,
`experiments.md`, `dashboard.md` and `misc.md`, with the meaning it carries here. Terms inherited
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
respectively. Widening has no convergence obligation here (`README.md`, "The property under test").

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
Soot/Jimple-flavoured, written in Scala in `engine/ir`. What `IrProvider` produces. Represents more than any
profile accepts.

**`JType`** — a Java type in the Scala IR: `Void`, `Prim`, `Ref`, `ArrayOf`
(§5.1). Structured so each type has one form; `toString` is its one printed
form (`java.lang.String[]`), and the only source of the type strings a domain
sees. `Void` only as a return type.

**`MethodId`** — a method's fully qualified identity: declaring class, name,
parameter types, return type. Printed as `Probe.main(java.lang.String[])`. Used
in every location, `Method`, `Invoke` and `Call`. The profile's `callees` list
matches its `qualifiedName` (`java.math.BigInteger.add`).

**`Invoke`**, **`InvokeKind`** — a call in the source IR, with its dispatch kind
(`Static`, `Virtual`, `Special`, `Interface`). The kind exists only in the
source IR; lowering erases it.

**`Cfg`** — the lowered form: a set of `Transition(from, Step, to)` plus an entry.
What the analysis and the domain see.

**Lowering** — source IR → `Cfg`. Turns a conditional `Goto` into two
`assume`-guarded transitions so a domain never sees branching, and an `Invoke`
into a `Call` so a domain never sees dispatch. Table in §5.3.

**Domain vocabulary** — the Java projection of `Step` in `pag.api` that
`transfer` receives: exactly what lowering can emit, with the Scala IR's names
and shapes (§5.4). Grows when a profile setting emits something new.

**Converter** — the one place the Scala `Step` becomes the domain vocabulary,
just before each `transfer`. Trust base. The seam where an out-of-process
(e.g. Python) domain would plug in.

**`Step`** — `Assign`, `Assume`, `Call` or `Skip`. A domain handles the first
three; the engine handles `Skip` as the identity.

**`Call`** — the `Step` form of an invocation: an optional target local, the
callee's `MethodId`, and arguments. No dispatch kind, no receiver. Each callee
a profile admits has a documented meaning the domain implements; in v1 the only
one is `randInt`.

**`assume`** — a transition that blocks unless its condition holds. Carries all
branching, and also carries initial-state constraints on the entry transition.

**Language profile** — the config (§5.2) declaring which source-IR constructs
are accepted, which callees and static fields may be used, and whether `main`
may read `args`. v1's is `bigint-main-v1`.

**Profile check** — the pass in `core`, between loading and lowering, that
enforces the language profile. Reports every **profile violation** with the
construct, its line, and the setting that would allow it — never a silent skip.
The language grows by extending the profile's lists with the check on.
`language.enforce = false` turns the pass off for inspection only: `pag ir`
accepts it, `analyze` and `check` refuse it.

**Untranslatable** — bytecode the source IR cannot represent at all (switch,
monitors, exception handlers). Distinct from a profile violation: one is a limit
of the IR, the other a limit of what we choose to accept. Both exit with code 2.

**`Rand.randInt()`**, **nondeterminism** — `pag.probe.Rand.randInt()` returns a
`BigInteger` and is the only source of input in a v1 program; the README writes
it `x := randInt()`. Lives in `engine/probe-lib`. Backward, it frees its target.
Without it every program has one execution and the abstraction is trivial
(§5.6). Despite the name, nothing is random: the adversary chooses every value.

**Inputs** — the list of values `randInt` returns in one run, in order,
supplied as `-Dpag.inputs=…` (JVM) or `--inputs` (`pag run`), and parsed by
`pag.probe.Inputs` by both the IR interpreter and the JVM run. Chosen by the
adversary; a run that asks for more than the list holds stops. What makes a run
replayable.

**`BigInteger` subset** — v1 programs use `java.math.BigInteger` for every
value, so all arithmetic is over mathematical integers and nothing wraps. There
is no `intWidth` setting; `int` programs with wraparound are a possible later
profile (§5.2).

**Lifting** — the pass (§5.7), between the profile check and lowering, that
rewrites `BigInteger` calls into arithmetic and comparisons, so a domain sees
`y := x + 1` rather than `y = x.add(ONE)`. Never adds or removes a command, so
locations survive it. `language.lift = false` turns it off.

**Compare temp** — the `int` or `boolean` local `javac` introduces for
`a.compareTo(b)` or `a.equals(b)` in a condition. Must be used exactly once, by
the next `if`; lifting fuses the pair into one comparison.

**Location**, `Loc` — where a state lives, written `ℓ`. Mirrors Historia: an
`AppLoc(method, index, isPre)` just before (`pre`) or just after (`post`) command
`index`, plus `InternalMethodEntry(m)` and `InternalMethodExit(m)` per method
(§5.3). A command is the edge from its `pre` to its `post`.

Classically *program point* means a position and *location* means a statement's
address — Historia keeps both in `AppLoc` with an `isPre` flag, and so do we.
**Use *location* for all of them** and retire *program point*; say `pre` or
`post` when the side matters.

**`skip`** — `Step.Skip`, the unlabelled edge between locations (`post(i)` to
`pre(i+1)`, entry to `pre(0)`, a return to the exit). The engine treats it as
the identity and never passes it to a domain.

**`ℓ_init`** — `InternalMethodEntry(main)`. Admits every store, because initial
constraints lower to an `assume` on its outgoing edge.

**`InternalMethodEntry`**, **`InternalMethodExit`** — a method's entry and exit
locations; Historia's `InternalMethodInvoke` / `InternalMethodReturn`. The
*Internal* prefix is kept so `Callback*` and `Callin*` locations can return
later without a clash.

---

## 3. Queries and verdicts

**Query** — the input naming what to ask about. Sealed, one case for now; in
`engine/results`. `QueryResolver.resolve` (in `core`) turns one into the
locations seeded ⊤ (plan §6).

**`reach(id)`** — `pag.probe.Reach.reach(int)`, the call a probe places at each
location it asks about (§5.8). Prints `REACHED-<id>` and has no other effect;
lowering makes it a `skip`. Its argument is an `int` literal, unique in the
program. The marker is in the program itself, so nothing is rewritten.

**`Reachable(id)`** — the only query form: is the `reach(id)` call reachable?
Its target is that call's `pre` location. A line-based form,
`Reachable(method, line)`, is deferred; it would resolve to **every** location
on the line, and reaching the line would mean reaching
any of them.

**Target location** — a location a query resolves to. Seeded `I(ℓ) = top()`.

**`Verdict`** — what the analysis learned, never how it stopped:
`Refuted` (certified unreachable), `Alarm` (searched fully, could not prove it),
`Inconclusive(why)` (did not search fully).

**`Incomplete`** — the single mechanism inside an `Inconclusive`:
`IterationLimit`, `Deadline`, `DomainFailure`. All three stop the search, so
exactly one can fire. There is no "we pruned" case; see *abandoning*.

**`AnalysisResult`** — the verdict, the invariant map (`states`), and what the
search cost: iterations, unexplored count, elapsed time, where it widened. Always collected, so a
campaign keeps these even at recording level `Off`.

**`Certification`** — the certifier's findings: how many transitions it
checked, which failed `[edge-inductive]`, and whether `[inductive]` and
`[refute]` held. Only all three passing is `Refuted` (plan §7). Absent from an
`AnalysisResult` whose search stopped early or whose domain failed.

**`Computed`** — the compute stage's result: the invariant map, how the search
stopped (`None` when the worklist emptied, else one `Incomplete`), and its cost.
Not a verdict: only the certifier turns it into one (plan §7).

**iteration** — one transition processed by the worklist. The iteration limit
(`Limits.iterations`, default 10,000) counts these, about two per command.

**`Recorder`**, **`NullRecorder`** — the interface through which the analysis is
observed (plan §8), called unconditionally; `NullRecorder` discards. Minimal
until Phase 4.5 builds the derivation graph.

**`DomainFailure`** — generated code threw (any `Throwable`, out-of-memory and
stack overflow included) or returned null. Carries the op and an **`ErrorInfo`**:
the `Throwable` as data — class name, message, stack trace — so it can be
printed, stored and fed back without keeping the exception. A hang is not one:
`pag` cannot stop it, so the campaign driver kills the process instead (§7). Every call
into a domain is wrapped; one exception must not end a campaign.

**Alarm** — the analysis could not prove the target unreachable. Not a claim that
it *is* reachable. Two kinds, told apart by `Certification`: every edge
inductive but `I(ℓ_init)` not ⊥ (could not prove it), or some edges
**uncertified** (the proof attempt was broken; plan §7, *What a failed edge
means*).

**uncertified edge** — a transition failing `[edge-inductive]`: the map claims
states at its source cannot reach the target, though they step into states the
map says can. A fact about the map, not the program. Listed in
`Certification.uncertified`, reported to `Recorder.uncertified`, and later
`StopReason.Uncertified` in the derivation graph.

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

**Abandoning** — skipping a state so it is never explored. **The engine never
does this** (§7): a proof over an incomplete search is not a proof. Historia's
`DropQryPolicy` could; none of it is carried over.

**Weakening** — replacing a state with a less precise one. Entirely the
domain's business, inside `join` and `widen`; the engine has no weakening
policy (§7).

---

## 5. Domains and their obligations

**`S`** — a domain's state type. Opaque to the engine and to the harness.

**`top`, `bottom`, `isBottom`** — the extremes and the refutation test.

**Reference domain** — the hand-written interval domain, `ref-interval` (directory, `domain.json` id, `name()`, and package `pag.domains.ref.interval`); the `ref-` prefix marks hand-written reference domains as `mut-` marks mutants and `gen-` generated ones. A **fixture**: a
known-good baseline to develop against and to seed mutants from, not something
the system requires.

**Mutant** — a domain known to be unsound, used to calibrate the adversary:
either hand-written with a planted bug, or a generated domain the adversary
rejected.

**Smoke test** — checks at domain load that every construct the active profile
enables is handled, so failures surface at load rather than mid-run.

---

## 6. The reachability check and reaching runs

**The reachability check** — the mechanism: a refutation is falsifiable by
running a program. `pag check` performs it.

**Probe program**, or just *probe* — a Java program the adversary writes, in
`probes/<campaign>/`. The artifact, never the mechanism.

**`ReachingRun`** — a program, its inputs, and the location it gets to, where
the domain proved that location unreachable. **The only artifact in this project
that proves anything.** Formerly called a witness.


**Marker** — the `REACHED-<id>` string `reach(id)` prints. Presence on stdout is
the verdict.

**Executor** — what runs a program: the **IR interpreter** (ours, running the
lowered `Cfg` a domain analyses) or the **JVM run** (the class file itself; formerly
Stage 1 and Stage 2). The JVM run is
`javac` plus the JVM, and is the verdict of record. Both take the same inputs.

---

## 7. The agents and the loop

**Generator** — the agent that writes domains. Scored on proof count.

**Adversary** — the agent that tries to break them. Scored on kill rate. Reads
the domain source deliberately; must still produce an executable reaching run.

**Complexity ladder**, **rung** — `experiments.md` E1: scoring-corpus slices
R0–R6, each needing one more piece of reasoning than the last (constants,
intervals, arithmetic, multiplication, loops, relations between inputs, nested
loops). Used to find where a model stops generating an acceptable domain in one
shot, and how much feedback each rung needs.

**One-shot** — a domain generated with no feedback at all: the prompt in, a
domain out, judged as is. The baseline E1 measures feedback against.

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

| name               | contents                                                                                                     | used for                              |
|--------------------|--------------------------------------------------------------------------------------------------------------|---------------------------------------|
| **probe corpus**   | programs the adversary wrote                                                                                 | attacking a domain                    |
| **scoring corpus** | programs with many targets, some infeasible by construction                                                  | proof count                           |
| **mutant corpus**  | domains known to be unsound: hand-written mutants and every rejected domain, listed in `corpora/mutants.txt` | calibrating the adversary             |
| **reaching runs**  | probes that actually broke a domain, under `domains/<id>/rejections/`                                        | rejection records, generator feedback |

**`domain.json`**, **`run.json`** — the metadata kept beside each domain and each
rejection in `domains/` (plan §3). Plain files in git for now.

---

## 9. The command line

**`pag`** — the engine binary. `ir` (what the front end produced), `run`
(execute, report locations visited), `analyze` (verdict and invariant map),
`check` (the reachability check), later `score`. It does only what needs no
network and no credential; anything that talks to a model is the campaign
driver's. Plan §11.

**`--reach ID`** — the query, i.e. `Reachable(ID)`.

**Exit codes** — the agent-facing contract, so drivers never parse prose:
`0` completed, `1` usage/IO, `2` did not load (untranslatable or a profile
violation), `3` unsound (a reaching run
contradicted a refutation), `4` inconclusive, `5` domain failure. 4 and 5 differ
because one says raise the budget and the other says the domain is broken.

## 10. Serialization

**Wire format** — `serialization.format = "json" | "cbor"`, one codec set
serving both via Borer. JSON by default; CBOR when the derivation graph at
`Full` recording makes it worth it. Plan §13.

**`pag dump <file>`** — prints any stored record as JSON whatever it was written
as, so binary never costs inspectability.

**`engine/results`** — the small shared module holding result ADTs and codecs,
depended on by both the engine and `campaign/`, and depending on nothing else in
the engine. Holds `Query`/`Reachable`, `Verdict`, `Incomplete`, `Outcome` and
`ErrorInfo` (plan §13). Deliberately not `engine/api`, which generated domains
compile against.

**Versioned envelope** — engine version, api version, profile name, campaign id
on every record. The actual compatibility need, independent of format.

## 11. Modules and artifacts

**The api compiles separately** — a domain needs nothing but the JDK and
`pag.api` to compile, and `pag.api` refers to nothing outside `java.*` and
itself. What lets the domain build (§3) need nothing but `api.jar`. Named after
Ali and Lhoták's *separate compilation assumption* (Application-only Call Graph
Construction, ECOOP 2012), under which a library is compiled without the
application; here `api` is the library, and we check the property rather than
assume it (`SeparateCompilationTest`).

**Build template** — the one fixed, human-written Gradle build every domain is
built with (`domains/build-template/`, plan §3). A domain holds only `src/` and
`test/`; the generator never writes a build file. Runs offline from a pinned
Gradle distribution and dependency cache.

**`engine/ir`** — the IR in Scala: source IR, `Step`, `Cfg`, `IrProvider`.
Shared by `frontend-soot`, `core` and `harness`; never seen by a domain.

**`engine/api`** — the domain contract and the domain vocabulary. Pure Java, no Scala dependency. The
only engine code an agent sees.

**`engine/probe-lib`** — pure Java, no dependencies, holds `pag.probe.Rand`.
The only library a probe may call.

**`engine/frontend-soot`** — the only module with Soot (4.7.1) on its compile
classpath. `cli` sees it at runtime only, through `ServiceLoader`.

**`engine/core`** — profile check, lifting, lowering, `ControlFlowResolver`,
worklist, invariant map, certifier.

**`engine/harness`** — executor, probe runner, verdicts, scoring. Nothing that
talks to a model: agent drivers and the mutant corpus belong to `campaign/` and
`domains/`.

**Trust base** — the human-written code a verdict rests on unchecked: the
front end (loading through lowering), `reach` and its lowering, the executor, the
certifier, and the domain-vocabulary converter (§2). Exhaustively unit tested and reviewed before merge, as a
standing rule. `Rand` is outside it: a bug there breaks replay, not verdicts.

**IR–JVM cross-check** — the standing test in `sbt test` that runs each fixture, written
with a `reach` call after nearly every statement, under the IR interpreter and as a JVM run and
requires the same `reach` ids in the same order (Phase 2b).

**`IrProvider`** — the one interface through which programs enter: `load`
only. Source lines are not a service: they live on `Method` (§5.1).

**`ControlFlowResolver`** — the one class in `core` that answers "what comes
before here": predecessors and loop heads from the `Cfg`, and later, call
targets through an interface the front end implements (plan §7). Named after
Historia's class with the same job; see §12. Loop heads and predecessors are
outside the trust base because the certifier never consults it; call targets
will be inside it, since they decide which transitions exist.

---

## 12. Inherited terms, and what they mean here

| term                  | in the dissertation / Historia                                             | here                                                                                                                                     |
|-----------------------|----------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| Lemma 1               | *hoare triple soundness*, Ch. 4 p. 89, with a framework-spec parameter     | the per-step soundness condition, heap/spec parameter dropped                                                                            |
| `IRWrapper`           | Soot-coupled IR facade, ~2150 lines with APK and callback handling         | `IrProvider`, translation only                                                                                                           |
| `ControlFlowResolver` | every "what comes before here" query, including callbacks and call targets | same name and job, smaller: predecessors and loop heads from the `Cfg`; call targets through a front-end interface, once there are calls |
| `canSubsume`          | entailment via Z3, ~1800 lines                                             | `entails`, a domain method, no solver                                                                                                    |
| `IPathNode`           | path-node tree, mutable status, implicit `OutputMode`                      | derivation graph (plan §8), redesigned                                                                                                   |
| `WitnessExplanation`  | the printed path for an alarm                                              | `CandidateTrace`                                                                                                                         |
| `WitnessedQry`        | search state meaning the entry was reached                                 | `Verdict.Alarm` plus a `CandidateTrace`                                                                                                  |
| `InitialQuery`        | `Reachable`, `ReceiverNonNull`, `CallinReturnNonNull`, …                   | `Reachable` only; the rest to be re-engineered                                                                                           |
| **consistent**        | consistent with known reachable locations                                  | *avoid* — see `misc.md` §5                                                                                                               |
| **witness**           | an alarm that reached the initial state                                    | *retired*                                                                                                                                |

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

**5. *program point* vs *location*.** ✅ *Resolved.* **Location** everywhere,
with `pre`/`post` when the side matters; §5.3 of the plan defines them.

**6. *corpus* is unqualified in several places.** With four corpora (§8 above), bare
"the corpus" is ambiguous. Always qualify.

**7. *target* is doing two jobs.** A *target location* is where a query points; a
*target* in profile prose means the language we aim at. Minor, but
worth watching as the profile grows.

**8. `Dropped` vs `Exhausted`.** ✅ *Resolved, and the concept was wrong.*
`Exhausted` is now `Inconclusive(why: Incomplete)` — verdicts name outcomes,
`Incomplete` cases name mechanisms. `Dropped` is gone entirely: abandoning a
path is under-approximate machinery and has no place in a verifier, so the
per-node case is `Unexplored` (the budget ran out first). Plan §7 has the
verdict table and the rule that the engine never abandons a state.
