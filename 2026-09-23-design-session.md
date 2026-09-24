# Design session — 2026-09-22 / 2026-09-23

A record of the conversation that produced `README.md`,
`implementation_strategy.md`, `glossary.md`, `misc.md` and `dashboard.md`.
Written at the end of the session so a fresh one can pick up implementation
without re-deriving the reasoning. Sections 9–11 were added after the first
draft, covering the CLI, the campaign driver and serialization.

It is organised chronologically by topic rather than verbatim. Where I got
something wrong and Shawn corrected it, both are recorded — the corrections
carry most of the design.

---

## 1. Starting point

The repo was an sbt "Hello, Scala!" skeleton (31 lines) plus a `README.md`
containing a placeholder note asking for a worked example of a separation-logic
abstract domain, its soundness condition, and how a debugger could test a
transfer function against it, following the backward semantics of Historia.

Reference material: Shawn's dissertation at `/Users/shawnmeier/source/thesis_backup/`
(`.tex` sources) and Historia at `/Users/shawnmeier/source/Historia/`.

Dissertation chapter numbering differs from the filenames — `chapter5.tex` is
Chapter 4 (Historia / MHPL), `chapter6.tex` is Chapter 5 (incorrectness logic),
`chapter7.tex` is Chapter 6 (model synthesis). All citations in the docs use the
built-PDF numbers from `shawnthesis.aux`.

## 2. The worked example, and three rewrites of it

**First version** — separation logic, following Ch. 5 §5.2's heap fragment with
Ch. 4's Lemma 1 as the soundness condition. Shawn asked for it to be distilled
for checkability, then reframed so the transfer function was the thing under
test rather than the judgment.

**Key distinction that emerged:** a judgment is a *relation* (many `P'` stand in
it for a given `c` and `P`); a transfer function is a *function* (it must return
one). Rules underdetermine an implementation in four ways — mode, search,
totality, representation — which is why the project tests the *semantic*
condition rather than derivability.

**Second version** — the inference rule was removed entirely. Shawn: *"Since we
have the assumption that sufficient testing of the transfer function means the
soundness condition holds, the judgment has no purpose here."* The spec became
the triple plus its soundness condition.

**Third version** — separation logic was too much for an explainer, so the
example became IMP plus the interval domain. Shawn also asked for the
fixed-point judgments to be included, since those are the human-written layer
that composes tested transfer functions into a sound analysis.

## 3. The reachability pivot — the largest change

Shawn raised two ideas from his dissertation:

1. **Reachable locations reject unsound models.** A framework model is unsound
   if it lets a known-reachable location be "proven" unreachable
   (`chapter6.tex:8`, `figenummodels.tex:88-91`, `chapter7.tex:40`).
2. **Any state question reduces to location reachability**, halting-problem
   style, and reachability is observed by a print statement plus a run.
   Already relied on in `goaldirinclogic.tex:9` and in error conditions being
   `⊤` at a location (`chapter7.tex:40`).

Consequences worked out in conversation:

- The observation infrastructure (instrumented interpreter, JDI,
  `StackFrame.getValues`) exists only to observe *states*. Under the reduction
  it is unnecessary.
- `contains` **never runs during analysis**. It appears only in the obligations,
  so if the acceptance test is end-to-end reachability it has no job.
- Therefore `contains`, `alpha` and `Store` leave the domain interface entirely,
  and **nothing in a domain is human-written**.
- The trust base collapses from seven claims to two: the executor implements the
  intended semantics, and the adversary is strong enough for its silence to mean
  something.

Shawn also added the **adversarial agent**: a separate agent whose job is to
find programs that break a domain. Its deliverable is a corpus of reaching runs
— automating the ~10 locations that `conclusionandfuture.tex:21` notes were
*"hand selected to demonstrate the unsound behavior."*

**The safety asymmetry.** Shawn: safety has no ground truth — unreachability is
universally quantified over executions. So precision cannot be a test; it is
*maximize proven locations subject to surviving the adversary*. Partial ground
truth is available from synthetic infeasible guards, fix commits, and bounded
model checking. Calibrating the adversary against seeded mutants is what makes
"found nothing" mean anything.

`contains` and per-obligation testing were preserved in a **Future ideas**
section, with Shawn's two triggers for bringing them back: the reachability
oracle proving too expensive, and behaviour that cannot be driven because it
runs through a framework, library, or OS — the Historia situation exactly.

## 4. Architecture decisions

**Engine / domain split.** `engine/` is human-only; `domains/` is model-written.
Sibling directories, confirmed by Shawn for clean bind mounts.

**Language boundary.** Engine in Scala 3; **contract and domains in Java 21**.
Reasoning: Scala 3 is thin in training data and models emit 2.13-isms; Java is
regular, has actionable `javac` errors for a repair loop, and compiles fast in a
loop that runs thousands of times. Out-of-process Python domains were rejected —
`S` is opaque to the engine, and across a process boundary that leaves only bad
options (serialize and lose opacity, or hold handles and give the domain process
the worklist's memory), with `entails` becoming an RPC on the hot path.

Dividend: the codegen container needs a JDK and nothing else. `javac` against
two jars is hermetic, so "no network" is true by construction rather than by
sandboxing a dependency resolver.

**IR.** Shawn asked for Historia's Soot-shaped IR restricted by a **config
profile** rather than a smaller purpose-built IR, so growth is a config change.
v1 profile: single `main`, `int` locals, conditional `Goto`, no calls or fields.
Anything outside it is a loading failure. `intWidth` is `unbounded` or `32`;
32-bit wraparound is staged as a deliberate difficulty step and is expected to
be the adversary's first realistic target.

**Soot boundary.** Shawn wanted a cleaned `IRWrapper` with a crisp boundary so
Soot can be replaced. Result: `IrProvider` (`load`, `findLine`, `isLoopHead`,
`instrumentReach`), with `SootIrProvider` alone in `engine/frontend-soot` and a
build rule failing any other module that imports `soot.*`.

**The adversary writes Java.** Since programs are read from class files, the
adversary writes source, `javac` compiles it, and the same class file is both
translated and executed. This removed the JSON program codec and the two-stage
executor divergence.

**Dashboard deferred.** Designed, mocked up
(<https://claude.ai/artifact/VjuobWygm5i436hSXTgCHR>), then moved to
`dashboard.md` at Shawn's request to keep focus on the engine. Recommendation
recorded: an embedded server in the engine's own JVM, **not Play**, because
Play's dev-mode reloading defeats the stated requirement that IntelliJ CE
attach and step through everything.

## 5. Goal-directed backward analysis, made explicit

Shawn asked whether the plan was set up for Historia-style backward analysis —
it was mechanically, but never said so. Now §6 of the plan, with a table
contrasting it against standard abstract interpretation: starts at a target with
`⊤`, `I(ℓ)` means *states that may reach the target*, one fixed point per query,
answer read at the entry.

**Query form.** `Reachable(method, line)`, following `InitialQuery.Reachable`.
Reading `Qry.makeReach` settled three things: `findLineInMethod` returns several
locations, the query state is `State.topState`, and line resolution belongs on
the IR provider.

Shawn chose **any location on the line** rather than Historia's `locs.head`,
because one line can hold statements with no dominance relation between them.
The disjunction needs no machinery — seed `I(ℓ) = ⊤` at every resolved location
and the existing join does it.

Other query forms (`ReceiverNonNull`, `CallinReturnNonNull`, `MemoryLeak`,
`InitialQueryWithStackTrace`) are deliberately **not ported** — Shawn wants them
re-engineered. Nothing is lost meanwhile: the README's reduction makes them a
convenience layer over `Reachable`.

## 6. The derivation graph

Shawn asked for Historia's witness trees, noting they "always seemed to behave a
little off" and that dropping intermediate states would matter for performance.

**Seven concrete problems found in `IPathNode`:**

1. `subsumed` means three things — real subsumption, *dropped* (empty set, per
   the code comment), and a liveness test that reads dropped nodes as live.
2. `succ` is a DAG edge set with a tree's readers. `mergeEquiv` concatenates
   successors, but `methodsInPath` follows `succ.headOption` and
   `PrettyPrinting` narrows it to an `Option` — so the printed trace is one
   arbitrary derivation with no sign that others existed.
3. `addAlternate` writes provenance into `qry.state.alternateCmd`, so debugging
   metadata participates in state equality, hashing and subsumption.
4. `hashCode` includes `subsumedV`, and `setSubsumed` returns a copy — node
   identity changes after the node is in a set.
5. A mutable `var error` on an otherwise-immutable case class.
6. Traversal reads through an implicit `OutputMode`, so the same code returns
   different graphs depending on config several layers away.
7. Finding an alarm drains the frontier into the result set.

**The redesign** (plan §8): the graph observes the analysis rather than
participating; every edge says why it exists; nothing about provenance touches
`S`. `StopReason` lives in a side map keyed by `NodeId`. Recording levels
`Off / Terminal / Full`, with a note that `Terminal` degenerates the DAG to a
forest — which *is* Historia's behaviour, now named with that caveat attached.
Plus reconstruct-from-`I` as a zero-memory way to get a candidate path.

## 7. Naming

A glossary pass (`glossary.md`) turned up eight collisions. Resolved:

| was | now | why |
| --- | --- | --- |
| `Witness` (executed program) | **`ReachingRun`** | Shawn: *"witness is kinda ambiguous"* — overloaded three ways |
| `WitnessExplanation` | **`CandidateTrace`** | a hypothesis in the abstraction, not evidence |
| `Terminal` (enum) | **`StopReason`** | collided with the recording level |
| `Terminal.Entry` | **`StopReason.ReachedInit`** | borrowed the noun from `ℓ_init` |
| *probe* (mechanism) | **reachability check** | *probe* now names only the artifact |
| *step limit* | **iteration limit** | `Step` is the command type |
| *program point* | **location** | see below |
| `Exhausted` | **`Inconclusive(why)`** | Shawn: verdicts should name outcomes, not mechanisms |

**Program point vs location.** Classically a program point is a position between
statements and a location is a statement's address; Historia keeps both in
`AppLoc.isPre`. Putting commands on CFG edges dissolves the distinction here.
This surfaced a latent bug: the source IR and the `Cfg` were using `Loc` to mean
different things. Resolved by defining a `Loc` as a position, with a method of
`n` commands having `n+1` positions.

## 8. Verdicts, and the drop question

`Inconclusive` was initially given `why: List[Incomplete]`. Shawn asked why a
list — shouldn't the search terminate at the first one? Correct: three causes
terminate, and the fourth (`PathsDropped`) was mis-filed, since pruning does not
stop the worklist. Counts moved to `AnalysisResult`; `why` became singular.

Then Shawn corrected something larger: **drop policies belong to
under-approximate mode**. A verifier cannot abandon a path — that
under-approximates the states reaching the target, so a proof over a pruned
search is not a proof. What a verifier may do is *weaken*, e.g. drop a frame
from a separation-logic formula.

Confirmed in the code: `Driver.scala:121` defaults to
`LimitMaterializationApproxMode()` (weakening) for verification, while
`IncorrectnessInterpreterTest` uses `PreciseApproxMode`, which is what carries
`dropStatePolicy`. And `DropQryPolicy.shouldDropOrModify` genuinely does both —
`None` abandons, `Some(weakened)` weakens, which is why two of the nine
"policies" are actually sound.

**Result:** `Incomplete.PathsDropped` removed; `StopReason.Dropped` became
`StopReason.Unexplored` (still in the worklist when the budget ran out). The
engine has **no approximation move at all** — it cannot abandon (unsound) and
cannot weaken (`S` is opaque to it). All weakening lives in the domain's `join`
and `widen`.

## 9. The command line

Sketched in conversation, then written into the plan as §11 once the rest had
settled. Four subcommands aligned to the early phases:

```
pag ir      <classes> [--method M] [--cfg]            what the front end produced
pag run     <classes> [-- args...]                    execute, report locations visited
pag analyze --domain <jar> --classes <dir> --at M:L   verdict and invariant map
pag check   --domain <jar> --classes <dir> --at M:L   analyze, then try to falsify
```

Two decisions worth remembering:

**`analyze` prints the invariant map by default**, not behind a flag. With the
dashboard deferred it is the only way to see what a domain did, so it should not
need asking for.

**Exit codes are the agent-facing contract**, so drivers never parse prose:
`0` completed, `1` usage/IO, `2` profile violation, `3` unsound (a reaching run
contradicted a refutation), `4` inconclusive, `5` domain failure. 4 and 5 are
separate because they call for opposite responses — raise the budget versus the
domain is broken. That fifth code exists only because of the
`Exhausted` → `Inconclusive` split.

Writing the sample output caught a stale field: `AnalysisResult` still had
`dropped: Int` from before the engine stopped abandoning paths. It is now
`unexplored: Int`, matching `StopReason.Unexplored`.

## 10. The campaign driver — a separate codebase

I had listed `campaign` as a future `pag` subcommand without designing it.
Shawn's call: *"the loop of running the corpus of examples and managing the
processes to generate the abstract domain should be a separate code base
entirely. My experience is that this kind of code gets crash-ey quick and having
process isolation would be useful."* **Campaign** survives as the name of the
outer loop and as the unit that makes results comparable.

Working through it produced a technical argument stronger than the
maintainability one, and corrected something I had written:

**A hang cannot be handled inside the engine's JVM.** `Thread.stop` is unsafe
and interrupts need cooperation, so a generated `transfer` sitting in
`while (true) {}` is unkillable from inside. §7 had claimed a non-terminating
call was bounded by the search deadline; it is not. The deadline fires between
worklist iterations, never inside one, and **the only reliable timeout is the
driver killing the `pag` process.** That alone decides the split.

Consequences: `pag` does only what needs no network and no credential, so
`mutants` moved to the driver while `score` stayed; the engine never gets API
clients or credentials, which makes Phase 9's isolation a property of the engine
rather than something enforced around it; and durable progress became a
first-class requirement, since a campaign runs for hours over paid APIs.

The driver is Scala 3 — Shawn: *"scala is probably the right call for the outer
loop as well since it is what I am familiar with."*

## 11. Serialization

Shawn raised protobuf, noting *"Scala pickle got messy in historia so I'm
inclined to drop that."*

**The evidence, measured:** Historia has **147 codec declarations across thirty
files**, including **27 `RW.merge` sites that enumerate sum types by hand** —
`BinaryOperator` lists all nine cases manually, and several are `implicit var`
rather than `val`. Add a tenth operator, forget the merge, and it breaks at
runtime. That is a diagnosis of *manual codec registration for sealed
hierarchies*, not of JSON; Scala 3's `Mirror` derivation removes all 27 sites by
construction.

**Recommended against protobuf**, for a reason specific to this design: **the IR
never crosses the process boundary.** The driver passes paths and a query; the
engine loads the program itself. What gets serialized is a verdict and four
scalars. Protobuf's compactness buys nothing at that size, its cross-language
advantage vanished when the driver became Scala, and it costs code generation, a
second set of types, and binary output in a project whose debugging is `cat`.

**Then Shawn asked for a config switch** between a fast binary format and JSON.
[Borer](https://github.com/sirthias/borer) does exactly that — one set of
`Encoder`/`Decoder` instances serving both JSON and CBOR, Scala 3 derivation,
no codegen. `serialization.format = "json" | "cbor"`.

The tension — you want JSON to inspect and binary for speed, on the same large
payload — is dissolved by `pag dump <file>`, which prints any stored record as
JSON whatever it was written as.

Where speed actually matters: only the derivation graph at `Full` recording is
O(worklist iterations). That is also where Historia's slowness lived, writing
every path node — so **recording level is the first lever and format the
second.** Default JSON, switch on measurement, but build the seam now, since two
formats cost one indirection designed in and a rewrite retrofitted.

Also settled: `engine/results`, a small shared module holding the result ADTs
and codecs, depended on by both the engine and `campaign/` — one definition, no
schema to sync, and deliberately not `engine/api`, which generated domains
compile against.

## 12. Where to start

`implementation_strategy.md` §14 has thirteen phases. The spine is Phases 0–5,
ending at the first end-to-end demonstration: a deliberately broken transfer
function, a hand-written reaching run, and a rejection, with a human standing in
for the adversary. Everything before Phase 10 is engine work; `campaign/` does
not need to exist until then.

Two things flagged as worth pulling earlier than their phase number:

- **Phase 4.5, the derivation graph.** Every phase after it is debugged through
  the State view, and retrofitting it is painful.
- **Phase 8, adversary calibration.** The kill rate is what makes "the adversary
  found nothing" mean anything, so it gates trusting any later result.

A third worth considering: **the serialization seam** (§11 above). It costs one
indirection now and a rewrite later, and the same argument applies to the
recorder interface, which Phase 4 already installs as a no-op.

## 13. Open threads

From `implementation_strategy.md` §16, thirteen of them: adversary budget and
stopping rule; whether the adversary sees domain source (assumed yes);
reaching-run minimization; `S` vs `Set<S>` from `transfer`; scoring corpus
scope; aggregating `DomainFailure` into a broken-domain rejection; Java version
floor; when to flip `intWidth` to 32; front-end scope; the other query forms;
campaign resumption granularity; recording level defaults; profile growth order.

The two added late are both about the driver: **resumption granularity** — per
domain, per query, or per agent call, which is not cosmetic when an hour of
paid API time is at stake — and **recording level defaults**, where it is
genuinely unclear what the adversary should use, since a trace may be the hint
it needs or may just be noise in its prompt.

From `misc.md`: whether `contains` can be generated (Phase 7 gate); domain
design vs selection vs semantics; the two kinds of consistency and the
terminology collision with the dissertation's own usage.
