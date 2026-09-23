# Implementation strategy

A plan for a prototype of the design in `README.md`: a generate-and-test loop
over IMP and the interval domain, with a hard boundary between code a model may
write and code it may not.

Regenerated 2026-09-23 against the reachability-probe design. Decisions made on
thin evidence are marked **[decide]**; open questions are collected at the end.
Ideas considered and parked live in `misc.md`.

## 1. What the system does

One query is: *given a domain `D`, a program `p`, and a location `ℓ`, can `D`
prove `ℓ` unreachable?* Everything else is built around making that query fast,
making it falsifiable, and running two agents against it.

```
  generator ──writes──> domain D
                          │
                          ├── analyze(D, p, ℓ) ──> Refuted | Alarm | Exhausted
                          │        │
  adversary ──searches──> │        └── Refuted?  ──> run p from σ_init
                          │                             │
                          │                   reaches ℓ ├─> D is UNSOUND, rejected
                          │                             └─> no verdict
                          └── scored on how many ℓ it Refutes across a corpus
```

Soundness is a hard rejection backed by a reaching run. Precision is a score. The
two pressures are carried by the two agents.

## 2. The boundary

Separated physically so it can be enforced by the filesystem, and by language so
each side is written in what its author handles best.

| | who writes it | language | where | model access |
| --- | --- | --- | --- | --- |
| Analysis engine, certifier | human | Scala 3 | `engine/core` | none |
| Executor, probe runner, scoring | human | Scala 3 | `engine/harness` | none |
| Domain contract + IR | human | **Java 21** | `engine/api` | compiled jar |
| **Abstract domains** | **generator agent** | **Java 21** | `domains/<d>/` | read-write |
| **Probe programs / reaching runs** | **adversary agent** | **Java 21** | `probes/<campaign>/` | read-write |

Everything at or below the contract is Java; everything above it is Scala 3. The
engine is Scala because that is what a human maintains. The contract and domains
are Java because that is what a small model writes reliably — large training
corpus, regular syntax, actionable `javac` errors for the repair loop, and fast
compiles in a loop that runs thousands of times.

Nothing in a domain is human-written, so unlike the previous draft there is no
spec/transfer split and no per-domain human artifact at all. A reference domain
is still written by hand (Phase 3), but as a *fixture* — a known-good baseline to
develop the engine against and to seed mutants from — not as something the system
requires.

**Out-of-process domains were considered and rejected.** Letting the model write
Python behind a JSON protocol costs a round trip on `entails`, which is the hot
path during merging, and complicates ownership of the worklist's memory.
In-process Java keeps `S` a plain JVM object passed by reference. If a Python
domain is ever wanted it becomes one bridge implementation, not a cost the whole
contract pays.

## 3. Repository layout

```
engine/                    sbt multi-project, human-only
  api/                     contract + IR. Pure Java, no Scala dependency.
  frontend-soot/           Scala 3. the ONLY module allowed to import soot.*
  core/                    Scala 3. worklist, invariant map, certifier
  harness/                 Scala 3. executor, probe runner, verdict, scoring,
                           mutation corpus, agent drivers
  cli/                     Scala 3. config, domain loading, entry point
domains/
  interval/                Java. the reference fixture, and later a target
    src/ ... build.sh      javac against api.jar. No sbt, no network.
  <generated domains>/
probes/
  <campaign>/              adversary output: programs, initial stores, verdicts
corpora/
  scoring/                 programs used to measure proof count
  mutants/                 deliberately unsound domains, for calibration
config/                    run configurations
docker/                    compose files and mount definitions
```

`engine/api` is an sbt subproject with `crossPaths := false` and
`autoScalaLibrary := false`, so the published jar is plain Java with no Scala
coupling and no binary-compatibility constraint on domains.

Domains are a sibling of `engine/` rather than a subdirectory: they build with a
different toolchain, mount differently, and keeping them outside means the
read-only mount is a single unqualified path. *Decided — Shawn: the sibling
directory is fine here and I like that they can cleanly be separated for a bind
mount later.*

A web dashboard is **deferred**; the design and Shawn's requirements for it are
parked in `dashboard.md`. The CLI is the interface for now.

## 4. What to take from Historia, and what to drop

| Historia | here |
| --- | --- |
| `AbstractInterpreter.executeBackward` worklist | `core.Worklist`, same backward shape, no grouping heuristics |
| `Qry` = state + location | `WorkItem(loc, state)` |
| `TransferFunctions.cmdTransfer(cmd, state): Set[State]` | `Domain<S>.transfer(Step, S): S`, one state, domain-typed |
| `StateSolver.canSubsume` driving merge | `Domain<S>.entails`, no solver in the engine |
| `InitialQuery.Reachable(sig, line)` | the *only* query form here |
| `CmdWrapper` / `RVal` / `LVal` / `BinaryOperator` | **reused** as `api.ir`, cleaned, with a config profile gating what loads (§5.2) |
| `IRWrapper` abstracting the IR source | **reused and tightened** into `IrProvider` (§5.5); Soot confined behind it |
| `SootWrapper` (~2150 lines) | one implementation of `IrProvider`, replaceable |
| `IPathNode` / `MemoryPathNode` / `DBPathNode` | **redesigned** as the derivation graph (§8), not ported |
| `WitnessExplanation` | `CandidateTrace` (§8) |
| `WitnessedQry` | folded into `Verdict.Alarm` plus a `CandidateTrace` |
| `ExecutorConfig` constructing the interpreter | `config/*.conf` plus `EngineConfig` |
| step limits and timeouts | kept, and reported as a distinct verdict |

Drop outright: APKs, Android, framework models and callback handling; Z3 and the SMT encoding
(entailment is a domain method — a domain may use a solver internally, the
engine must not know); message histories, CBCFTL, specifications, synthesis;
path nodes, witness explanations, the results database; `ApproxMode` /
`DropQryPolicy` and the subsumption-mode variants; Scala 2.13.

Historia's `AbstractInterpreter` is ~1200 lines and `TransferFunctions` ~1000,
largely because the IR is real Java and the domain is entangled with the solver
(`StateSolver.canSubsume` alone is ~1800 lines). With a single-main-method Java
subset and an opaque domain behind an interface, the engine core should be a few
hundred lines.

Soot stays for v1, but not as it is used in Historia, where analysis code reaches
into Soot directly in many places. Here it sits behind `IrProvider` (§5.5) and
nothing above that interface may import `soot.*` — enforced by a build rule, not
by discipline.

Shawn's additions:
We should create a cleaned version of IRWrapper first. As much as possible, please remove dead code or messiness.
The first target of this new implementation will be standard java programs with a single main method.
I would like to postpone adding too much of the complexity behind handling callback systems right now
(but we may add that later).
I want to have a crisp boundary between the IRWrapper and the rest of the analysis.
Historia itself directly accesses soot a lot and I want to be able to replace soot.
Making this first version using soot is fine but I want to be able to replace it later if needed.

## 5. The contract

Pure Java 21. Sealed interfaces and records give ADTs; exhaustive `switch` gives
the model a compiler check on case coverage.

The IR is Historia's, which is Soot/Jimple-shaped, so that growing the supported
language later is a matter of enabling constructs rather than migrating the IR.
What v1 actually accepts is a *profile* — a config setting — not a property of
the types.

### 5.1 The source IR

Mirrors `ir/IRWrapper.scala`, trimmed of what we will not model for a long time
(no `SwitchCmd`, no `CaughtException`, no `ClassConst`). Everything here is
representable; §5.2 decides what is *loadable*.

```java
package pag.api.ir;

public record Loc(String method, int index) {}

public sealed interface Cmd {
    record Assign(LVal target, RVal source, Loc loc)  implements Cmd {}
    record Goto(RVal cond, Loc trueLoc, Loc loc)      implements Cmd {}  // conditional
    record Nop(Loc loc)                               implements Cmd {}
    record Return(Optional<RVal> value, Loc loc)      implements Cmd {}
    record Invoke_(Invoke call, Loc loc)              implements Cmd {}  // disabled in v1
    record Throw(Loc loc)                             implements Cmd {}  // disabled in v1
}

public sealed interface RVal {
    record IntConst(int v)                            implements RVal {}
    record BoolConst(boolean v)                       implements RVal {}
    record Binop(RVal l, BinOp op, RVal r)            implements RVal {}
    record Cast(String type, RVal v)                  implements RVal {}  // disabled in v1
    record NewObject(String className)                implements RVal {}  // disabled in v1
    record StringConst(String v)                      implements RVal {}  // disabled in v1
    record InstanceOf(String clazz, Local target)     implements RVal {}  // disabled in v1
    record ArrayLength(Local l)                       implements RVal {}  // disabled in v1
}

public sealed interface LVal extends RVal {
    record Local(String name, String type)                           implements LVal {}
    record Param(String name, String type)                           implements LVal {}  // disabled in v1
    record This(String className)                                    implements LVal {}  // disabled in v1
    record Field(Local base, String declType, String name)           implements LVal {}  // disabled in v1
    record StaticField(String declaringClass, String name)           implements LVal {}  // disabled in v1
    record ArrayRef(RVal base, RVal index)                           implements LVal {}  // disabled in v1
}

public enum BinOp { Mult, Div, Add, Sub, Lt, Le, Eq, Ne, Ge }

public record Method(String name, List<Cmd> body) {}
public record Program(List<Method> methods, Loc entry) {}
```

`Goto` carries its own branch target, as in Historia. Fall-through is the next
index in the method body.

### 5.2 The language profile

What the prototype accepts is declared in config and enforced at program load.
Anything outside the profile is a **loading failure** naming the offending
construct and the setting that would enable it — never a silent skip and never a
`TopExpr`-style escape hatch.

```hocon
language {
  name      = "int-main-v1"
  methods   = ["main"]                                  # a second method fails to load
  types     = ["int"]
  commands  = ["Assign", "Goto", "Nop", "Return"]
  lvals     = ["Local"]
  rvals     = ["Local", "IntConst", "BoolConst", "Binop"]
  operators = ["Add", "Sub", "Mult", "Div", "Lt", "Le", "Eq", "Ne", "Ge"]
  intWidth  = "unbounded"                               # or 32 for real Java semantics
}
```

So v1 is *single-method, int-locals, conditional-goto Java* — a genuine subset,
not a lookalike. Adding fields, calls, or a second method later is a config
change plus whatever lowering and domain support it implies, not an IR rewrite.

`intWidth` settles the wraparound question raised against the previous draft.
Start at `unbounded` so Phases 0–5 are about whether the loop works at all, then
flip to `32` as a deliberate difficulty step. That flip is worth its own
milestone: a generated interval domain will very likely treat `x + 1` as
monotone and miss that `Integer.MAX_VALUE + 1` wraps, which is a realistic,
short-to-trigger unsoundness and therefore an excellent first genuine target for
the adversary. **[decide]** whether to reach it before or after Phase 10.

Domains are checked against the profile too: the load-time smoke test (Phase 6)
exercises every enabled command and operator, so enabling a construct that an
already-generated domain cannot handle fails at load rather than mid-run.

### 5.3 What the domain sees

The engine lowers the source IR to a transition relation before analysis, which
is how both the dissertation and Historia's analysis treat control flow: the
formal language in Ch. 5 §5.2 has `assume`, while the Soot-facing IR has `Goto`.

A `Goto(cond, trueLoc)` at `ℓ` with fall-through `ℓ_next` lowers to

```
  ℓ —assume(cond)→ trueLoc        ℓ —assume(!cond)→ ℓ_next
```

so a domain only ever sees two command forms, and branching never becomes its
problem:

```java
public sealed interface Step {
    record Assign(LVal target, RVal source) implements Step {}
    record Assume(RVal cond)                implements Step {}
}

public record Transition(Loc from, Step step, Loc to) {}
public record Cfg(List<Transition> transitions, Loc init) {}
```

Initial constraints also lower to an `assume` on the entry transition, which is
what makes `[refute]` in §6 a plain `isBottom` (README, "Programs, and why
reachability is enough").

### 5.4 The domain interface

Entirely generated. Note what is absent: no `contains`, no `alpha`, no concrete
state of any kind. A domain never sees a `Store`.

```java
package pag.api;

public interface Domain<S> {
    String name();

    S top();                          // I(target) starts here
    S bottom();                       // I(everywhere else) starts here
    boolean isBottom(S s);            // the refutation test at the entry

    boolean entails(S a, S b);        // used by [edge-inductive]
    S join(S a, S b);                 // control-flow joins
    S widen(S a, S b);                // loop heads; convergence is NOT required

    S transfer(Step step, S post);    // backward abstract semantics
}
```

- `transfer` returns one state. A domain needing disjunction carries it inside
  `S`, with `join` as its union, so the engine never learns about it. **[decide]**
- The obligations these methods must satisfy are real but unstated in code —
  Lemma 1 and its companions, w.r.t. a concretization each domain has and never
  writes down. The harness verifies their consequence, not them.
- Version the api jar and record its version, and the language profile, in every
  result.

### 5.5 The front end, and the Soot boundary

Programs enter through one narrow interface. Nothing above it may import
`soot.*`.

```java
package pag.api.ir;

/** The only way a program enters the system. */
public interface IrProvider {
    /** Methods found in the given compilation unit, already in api.ir form. */
    List<Method> load(Path classesOrJar) throws ProfileViolation;

    /** Source file and line for a Loc, for error messages and tooling. */
    Optional<SourceRef> sourceOf(Loc loc);
}
```

`SootIrProvider` is the v1 implementation and lives alone in
`engine/frontend-soot`, which is the only module with Soot on its classpath. The
build fails if any other module references it, so replacing Soot later means
writing a second `IrProvider` rather than auditing the analysis for leaks.

The cleanup that matters here is not cosmetic. Historia's `SootWrapper` is ~2150
lines because it carries APK loading, callback resolution, class-hierarchy
queries and framework modelling alongside the actual IR translation. For a
single-main-method Java subset the translation itself is small; the work in
Phase 2 is separating it from everything else rather than porting it.

**The adversary's output format falls out of this.** Since programs are read from
class files, the adversary writes *Java source*, `javac` compiles it, and the
same class file is both translated by `IrProvider` and executed to check for a
reaching run. Analyzing exactly the artifact that runs removes an entire class of
disagreement, and removes the JSON program codec the previous draft needed.

`IrProvider` also owns the two location services the rest of the system needs,
because both are questions about bytecode and both belong in the one module that
knows about it:

```java
/** Every location on a source line. Historia's findLineInMethod. */
List<Loc> findLine(String method, int line);

/** Is this a loop head? Decides where the engine widens. */
boolean isLoopHead(Loc loc);

/** Write a copy of the classes printing REACHED-<id> on arrival at any of locs. */
Path instrumentReach(Path classes, List<Loc> locs, Path outDir);
```

`instrumentReach` is how a reaching run is observed (§9). Inserting a `println` before
the first unit of each target location cannot change whether those locations are
reached, so the instrumented copy and the original agree on the only question
being asked. Several prints may fire; the runner only asks whether the marker
appeared at all. Doing it in bytecode rather than in source is what lets the same
mechanism work later on code we did not write.

## 6. Direction: goal-directed backward analysis

This is worth stating plainly, because it is **not** how abstract interpretation
is usually set up and a reader — or a generator agent — will assume otherwise.

| | standard abstract interpretation | here, and in Historia |
| --- | --- | --- |
| starts at | the entry, with the initial state | a **target location**, with `⊤` |
| direction | forward | backward |
| `I(ℓ)` means | states reachable at `ℓ` | states at `ℓ` that **may reach the target** |
| fixed points | one per program | **one per query** |
| the answer is read at | the property site | the **entry** |
| a property is | checked against the invariant | the query itself |

So the analysis takes a **syntactic location in the program as an input**, and
answers one question about it. There is no whole-program invariant here and no
reusable result: ask about a different line and you run a different fixed point,
from scratch.

The query form, following Historia's `InitialQuery.Reachable(sig, line)`:

```scala
sealed trait Query
final case class Reachable(method: String, line: Int) extends Query
```

`Reachable` is deliberately the only form. Historia's others —
`ReceiverNonNull`, `CallinReturnNonNull`, `MemoryLeak`,
`InitialQueryWithStackTrace` — had grown messy, and the intent is to
re-engineer that set later rather than port it. Nothing is lost meanwhile: the
reduction in `README.md` turns a question about state into a question about a
location by guarding a fresh location with an `assume`, so those query forms are
a convenience layer over `Reachable` rather than additional power. `Query` stays
a sealed trait with one case, which is the whole concession made to that
future.

Resolution is `IrProvider.findLine(method, line)` (§5.5), which mirrors
Historia's `findLineInMethod`. A line maps to **several** locations, and the
query is their disjunction: *reaching the line* means reaching any one of them.
Historia's `makeReach` takes `locs.head` instead; we do not, because one source
line can hold several statements with no dominance relation between them
(`if (a) x(); else y();`), and taking the first would silently answer a
different question.

The disjunction needs no machinery. Seed `I(ℓ) = ⊤` at *every* location the line
resolves to and run the same backward fixed point: join at merge points does the
disjunction, and `I(entry)` ends up over-approximating the states that reach any
of them. The query's abstract state is `⊤` throughout — Historia uses
`State.topState` — which is what keeps `[inductive]` in §7 trivial.

**Why backward.** The goal constrains the search: program fragments irrelevant to
reaching the target never enter the invariant map at all. That is the whole
reason the domain can start from `⊤` and stay small, and it is the property the
separation-logic work in the dissertation leans on hardest. The cost is that
every query pays for its own fixed point, which is why §7's probe is expensive
and §11 asks about adversary budget.

**This must be loud in the generator prompt.** A model's prior for
"the interval transfer function for `x := a`" is the *forward* one. The
signature `transfer(step, post) -> pre` does not prevent writing a forward
transfer by mistake — it just makes the argument names the only clue. Whatever
Phase 10 sends the generator has to state the direction, the reading of the
triple, and at least one worked backward example. The five cases in `README.md`
exist partly for this.

## 7. The engine

```scala
enum Verdict:
  case Refuted                      // certified unreachable
  case Alarm                        // fixed point reached, target not excluded
  case Exhausted(reason: String)    // step limit, or widening did not converge

def analyze[S](d: Domain[S], cfg: Cfg, q: Query, lim: Limits): Verdict
```

Two clearly separated stages, because the README's soundness argument depends on
the separation:

**Compute.** Worklist. `I(ℓ) = d.top()` for every location `q` resolves to and
`d.bottom()` everywhere else. Pop a transition `ℓ —step→ ℓ'`, compute
`d.transfer(step, I(ℓ'))`, join it into `I(ℓ)` — or widen there, at a loop head
per `IrProvider.isLoopHead` — and re-enqueue predecessors of `ℓ` if `I(ℓ)` grew.
Stop on an empty worklist or the step limit. This stage may be arbitrarily
heuristic.

**Certify.** Independently re-check, against the settled map:

```
  [edge-inductive]   entails(transfer(step, I(ℓ')), I(ℓ))   for every transition
  [inductive]        I(ℓ) is top, for every target location ℓ
  [refute]           isBottom(I(ℓ_init))
```

Both stages call the recorder (§8) unconditionally — a failing
`[edge-inductive]` check records `Terminal.Uncertified` at the offending node,
which is otherwise very hard to locate. Only a map passing all three yields
`Refuted`. If certification fails the result is `Alarm` regardless of what the
worklist concluded. Keeping these apart means
widening, worklist order, and any future heuristic cannot affect soundness — a
property worth a test of its own (Phase 4).

Note that `[refute]` is `isBottom` rather than an `excludesInit` method: the
entry admits every store, because initial constraints lower to an `assume` on
the entry transition (§5.3).

## 8. The derivation graph

The analysis should record *why* it concluded what it concluded: a DAG over
(location, abstract state) pairs, built during the backward fixed point, from
which a path from the entry to the query can be read. When a query is not
refuted, that path is the explanation of the alarm.

This is Historia's path-node tree, rebuilt. Historia's version works but has
several structural problems worth naming, because they are the reason it behaves
unpredictably.

### What goes wrong in Historia's version

1. **`subsumed` means three things.** `setSubsumed(Set(rep))` is genuine
   subsumption; `setSubsumed(Set())` marks a *dropped* state (the code says so:
   *"empty subsume used for dropped"*); and `subsumed.isEmpty` is used elsewhere
   as a liveness test, which therefore reads dropped nodes as live.
2. **`succ` is a DAG edge set with a tree's name and a tree's readers.**
   `mergeEquiv` concatenates `succV ++ other.succV`, so nodes genuinely have
   several successors — but `methodsInPath` follows `succ.headOption`, and
   `PrettyPrinting` narrows `succ` to an `Option`. The printed trace is one
   arbitrary derivation with no indication that others existed.
3. **Provenance is written into the abstract state.** `addAlternate` stores
   alternate commands in `qry.state.alternateCmd`, so debugging metadata
   participates in state equality, hashing and subsumption. Two states that mean
   the same thing can fail to compare equal because they were reached
   differently.
4. **Node identity depends on mutable status.** `hashCode` excludes `succ` but
   includes `subsumedV`, and `setSubsumed` returns a copy — so a node's identity
   changes when it is subsumed, after it has been put in sets.
5. **A mutable `var error` on an otherwise immutable case class**, set through
   `setError`.
6. **Traversal semantics depend on an implicit `OutputMode`.** `succ` reads
   through it; `NoOutputMode` records nothing. The same traversal code returns
   different graphs depending on a config value several layers away, which is
   most of why the structure seems to behave differently run to run.
7. **Finding an alarm drains the frontier into the result set**, mixing
   fully-explored nodes with abandoned live ones and leaving only `searchState`
   to tell them apart.

### The design

Keep the idea, fix the structure. Three rules do most of the work: the graph is
an *observation* of the analysis rather than part of it; every edge says exactly
why it exists; and nothing about provenance touches `S`.

```scala
opaque type NodeId = Long

/** A program point with an abstract state, as the analysis saw it. */
final case class DNode(id: NodeId, loc: Loc, state: Any /* S, opaque */)

/** Why one node follows from another. Recorded in analysis order: target → entry. */
enum Derivation:
  case Transfer(step: Step)          // pre computed from post across a transition
  case Join(withNode: NodeId)        // this state is a join of contributions
  case Widen(prevNode: NodeId)       // this state came from widening at a loop head

/** Why exploration stopped at a node. Recorded once, never part of identity. */
enum Terminal:
  case Subsumed(by: NodeId)          // absorbed into an existing state
  case Dropped(reason: String)       // step limit, or an approximation policy
  case Bottom                        // transfer produced ⊥; this branch is refuted
  case Entry                         // reached ℓ_init — an alarm candidate
  case Uncertified(edge: Step)       // [edge-inductive] failed here
```

- **`Subsumed`, `Dropped` and `Bottom` are distinct constructors**, not three
  readings of an empty set. That alone removes problem 1 and most of 7.
- **Edges are directed the way the analysis moved** (target → entry) and the
  reverse view is derived, not a second field. Reading a trace means walking the
  reverse view from an `Entry` node to the query. No `headOption` anywhere: a
  reader that wants one path asks for one and is told how many there were.
- **`DNode` is immutable and its identity is its `NodeId`.** `Terminal` lives in
  a side map keyed by id, so marking a node subsumed cannot change its identity.
- **`S` is untouched.** No provenance, no alternates, no flags. The graph stores
  the state the analysis had; the domain never learns the graph exists.
- **Recording is an interface with a no-op implementation**, not a mode that
  changes traversal semantics. `core` calls `recorder.transfer(...)` and friends
  unconditionally; `NullRecorder` discards. Certification never reads it.

### What a trace is, and is not

A `CandidateTrace` is a path from an `Entry` node to the query. It is
**admissible within the abstraction and nothing more.** Joins mean the state at
a location was assembled from several predecessors, so the reconstructed path is
one selection through the DAG; it need not correspond to any concrete execution.

That distinction has to stay visible in the output, because it is the difference
between the two things this project calls evidence. A `CandidateTrace` explains
an alarm. A `ReachingRun` (§9) is an executed program and is the only thing
that proves anything.

Historia called both of these a witness, which is a large part of why its
results were hard to read. The two words here are deliberately unrelated:
*candidate* says the trace is a hypothesis inside the abstraction, *run* says
the other thing executed.

### Dropping intermediate states

The graph is O(worklist steps), which will not be affordable in a campaign, so
recording has levels:

| level | keeps | cost | use |
| --- | --- | --- | --- |
| `Off` | nothing | none | campaign runs |
| `Terminal` | terminal nodes, one parent pointer each | O(terminals × depth) | alarm triage |
| `Full` | every node and edge kind | O(steps) | debugging a specific query |

Under `Terminal` the DAG degenerates to a forest and a reconstructed path is one
arbitrary derivation — *which is exactly Historia's behaviour*. The difference
is that here it is a named level with that caveat attached, rather than the only
behaviour.

There is also a cheaper option worth building before `Full` gets slow.
`I` is retained anyway, and a candidate path can be **reconstructed from `I`
alone**: start at `ℓ_init`, and at each location follow any outgoing transition
whose `transfer(step, I(ℓ'))` is not `⊥`, to a depth bound with a visited set.
Certification already guarantees `entails(transfer(step, I(ℓ')), I(ℓ))`, so every
step taken is consistent with the invariant. Memory cost beyond `I` is zero.
What it cannot show is *why* the analysis behaved as it did — subsumption,
widening, drops — which is the part that actually matters when debugging. So:
reconstruct-from-`I` for reporting an alarm, `Full` recording for understanding
one.

## 9. The probe: executing a reaching run

A reaching run is concrete and self-contained:

```scala
final case class ReachingRun(source: Path, classes: Path, args: List[String], query: Reachable)
```

The analysis refutes "reachable on *any* input," so a reaching run names the
specific arguments that get there. Verdict: run it; if `REACHED-<id>` appears on
stdout, the domain is unsound.

Because §5.5 reads the IR from a class file, the artifact analysed and the
artifact executed are the same class file up to an inserted `println`. There is
no source-to-source translation between them, which is what the previous draft's
two-stage executor was trying to work around.

**Stage 1 — reference interpreter.** `engine/harness` interprets the `Cfg` and
records visited locations. Fast, deterministic, no process launch, and useful
for debugging. The executor here is our own code, so a disagreement with Stage 2
is a bug in it.

**Stage 2 — run the class file.** `IrProvider.instrumentReach` writes a copy of
the classes that prints `REACHED-<id>` on arrival at the queried location; then
`java -cp <copy> Probe <args>`, grep stdout for the marker. The instrumented copy
differs from the analysed one by a `println` that touches no local and no
control flow, so the two agree on whether the location is reached. The executor
is the JVM, so the evidence depends on nothing this project wrote beyond that
insertion. This is the verdict of record; Stage 1 exists for speed and
inspection, not for adjudication.

With `intWidth = 32` the two stages agree by construction. With
`intWidth = unbounded` they do not, so the Stage 1 interpreter must also use
unbounded integers and the cross-check below only holds within `int` range.

Stage 2 is what makes the README's claim ("does not depend on any component of
this project being correct") literally true, so it should not be deferred
indefinitely. A cross-check that both stages agree on a corpus is a cheap
standing test.

## 10. The two agents

**Generator.** Input: the contract, the reference domain as a worked example,
and any reaching runs found against previous rounds. Output: a domain in
`domains/<d>/`. Scored on proof count over the scoring corpus, among domains not
rejected.

**Adversary.** Input: the domain source (read-only), `analyze` as a service, and
the executor. Output: reaching runs. Scored on kill rate against the mutant corpus.

Letting the adversary *read* the domain is a deliberate choice — it is a machine,
the prohibition on reading domains applies to humans, and reading is what lets it
target the search rather than fuzz blindly. It still has to produce an executable
reaching run, so reading cannot substitute for evidence. **[decide]**

The two must not be the same model instance, and preferably not the same model.
An agent asked to write a domain and then attack it has no reason to attack hard.

### Configuring them

The generator and adversary are configured separately so they can be different
models — which the paragraph above requires anyway.

```hocon
agents {
  generator { baseUrl = "http://localhost:11434/v1", model = "qwen2.5-coder:32b",
               apiKeyEnv = "PAG_GENERATOR_KEY", temperature = 0.2 }
  adversary { baseUrl = "https://api.example.com/v1", model = "...",
               apiKeyEnv = "PAG_ADVERSARY_KEY", temperature = 0.9 }
}
```

Credentials are named by environment variable, never written to the config file
and never to the repo. Every result records which agent config produced
it, so a campaign can be attributed to the models that ran it.

## 11. Phases

Each phase names a deliverable and a done-when that is a runnable check.

### Phase 0 — skeleton
sbt multi-project for `engine`: `api` (pure Java), plus `core`, `harness`, `cli`
in Scala 3. A `build.sh` for `domains/interval` running `javac` against a fixed
classpath. CI runs both.
*Done when:* both build, a placeholder test passes in each, and `build.sh`
succeeds with networking disabled.

### Phase 1 — the contract
Write `engine/api` as §5. Get it reviewed before building on it.
*Done when:* a stub domain compiles against `api.jar` alone with `javac`, and
`jdeps` reports no dependency outside `java.*` and `pag.api.*`.

### Phase 2 — IR, profile, lowering, executor
A cleaned `api.ir` extracted from Historia's `IRWrapper`; `SootIrProvider` in its
own module with the no-`soot.*`-elsewhere build rule; the language profile and
its validator; lowering from source IR to `Cfg`; and the Stage 1 interpreter.
Most of the work is separating IR translation from the APK, callback and
class-hierarchy machinery `SootWrapper` currently mixes into it.
*Done when:* a hand-written `.java` probe compiles, loads through
`SootIrProvider`, and its visited-location sequence matches a fixture — **and** a
probe containing a second method, a field reference, or a method call is
rejected at load with a message naming the construct and the profile setting
that would enable it — **and** the build fails if any module
outside `engine/frontend-soot` imports `soot.*`.

### Phase 3 — the reference interval domain
Written by hand, in Java, as a fixture. Also the worked example shown to the
generator, so write it the way generated code should look.
*Done when:* the five transfer cases in `README.md` pass as unit tests.

### Phase 4 — the analysis engine
Worklist, invariant map, widening, step limit, and the certifier as a separate
pass. The recorder interface (§8) with `NullRecorder` only — the graph comes
next, but the call sites go in now so they are never retrofitted.
*Done when:* a program whose target is unreachable gets `Refuted` and one whose
target is reachable gets `Alarm` — **and** a test that deliberately corrupts the
worklist result still cannot produce `Refuted`, because certification is
independent.

### Phase 4.5 — the derivation graph
`Full` recording, the reverse view, `CandidateTrace` extraction, and a text
renderer. `Terminal` level and reconstruct-from-`I` can wait until something is
slow.
*Done when:* an `Alarm` produces a trace from `ℓ_init` to the query that a human
can read; a `Refuted` query shows every branch ending in `Bottom`, `Subsumed` or
`Dropped` with no branch unaccounted for; and a deliberately broken `entails`
surfaces as `Uncertified` at the edge that failed.

### Phase 5 — the probe and the verdict
`ReachingRun`, the runner, and the rejection path with a serialized counterexample.
*Done when:* an end-to-end test using a deliberately broken transfer function
produces a refutation, a hand-written reaching run, and a rejection. This is the first
point at which the whole idea is demonstrated, with a human standing in for the
adversary.

### Phase 6 — dynamic loading
Config parsing, `URLClassLoader` per domain, `ServiceLoader` discovery.
Delegation is **parent-first for `pag.api.*`** so engine and domain agree on the
contract types, child-first otherwise so domains may carry conflicting
dependencies. A smoke test runs immediately after loading, exercising every command form and
operator the active profile enables, so a domain that cannot handle an enabled
construct fails at load rather than deep in a run.
*Done when:* the interval domain loads from a jar with the engine having no
compile-time dependency on it, and Phase 4's tests pass through the loaded path.

### Phase 7 — the scoring corpus
Programs with many targets, plus synthetic targets that are infeasible by
construction (`assume x > 0; assume x < 0`) where the answer is known. Graded by
the reasoning required, so the score measures capability rather than luck.
*Done when:* the reference domain produces a stable proof count, and the graded
targets separate it from a deliberately weaker domain.

### Phase 8 — the mutant corpus and adversary calibration
Deliberately unsound domains: a transfer that narrows too hard, an `entails` that
is too permissive, an `isBottom` that fires on a satisfiable state, a `widen`
that drops a case. Measure what fraction any given adversary breaks, and at what
budget.
*Done when:* kill rate is reported per mutant and runs in CI. This is the number
that makes "the adversary found nothing" mean anything, so it gates trusting any
later result.

### Phase 9 — isolation
Compose file with services for the engine, the generator, and the adversary.

| container | rw | ro | absent |
| --- | --- | --- | --- |
| generator | `domains/<d>/` | `api.jar`, reference domain, reaching runs | everything else |
| adversary | `probes/<campaign>/` | `api.jar`, domain under attack | everything else |

Each holds a JDK and nothing else — no sbt, no coursier, no network. That is the
dividend of the Java contract: the domain build is `javac` against one jar, so it
is hermetic by construction rather than by sandboxing a dependency resolver. The
engine runs outside and rebuilds any domain jar from the writable sources in a
clean container before trusting it.
*Done when:* a generator container can build a domain with networking off and
cannot read `engine/core`; an adversary container can read a domain but not
write it.

### Phase 10 — the generator agent
Driver: generate → build → analyze on a smoke corpus → report. Bounded retries,
every attempt logged with api version, corpus hash, and outcome.
*Done when:* a small model produces a domain that compiles, loads, and refutes at
least one target, working from the contract and the reference example alone.

### Phase 11 — the adversary agent
Driver: read domain → query `analyze` for refuted targets → propose probes →
run → report. Score against the Phase 8 mutants first, then turn it on generated
domains.
*Done when:* the adversary's kill rate on mutants is reported, and it finds at
least one genuine reaching run against a generated domain.

### Later
Real-language backends beyond the Stage 2 Java emitter; a domain exercising
disjunction; the forward flow-insensitive phase; and the Future-ideas triggers in
`README.md` — cost, and behavior that cannot be driven because it runs through a
framework, library, or the OS.

## 12. What each phase de-risks

- 1 and 3: is the eight-method contract expressible enough to write a real
  domain against?
- 4: does certifying the settled map actually work, independent of the worklist?
- 4.5: can we see why a query came out the way it did? Historia's experience is
  that this is where the debugging time goes, and retrofitting it is painful.
- 5: does the central idea hold end to end, with a human as adversary?
- 6: does the isolation boundary survive JVM classloading?
- 8: is the adversary strong enough for its silence to mean anything? **This is
  the phase most likely to change the design, and a crude version of it is worth
  pulling forward into Phase 5.**
- 10 and 11: is the contract legible enough for a small model, in both roles?

## 13. Open questions

1. **Adversary budget and stopping rule.** How long does an adversary search
   before a domain is provisionally accepted? Phase 8's calibration should set
   this empirically rather than by guess.
2. **Does the adversary see the domain source?** Assumed yes (§8). It is a
   machine, and reading targets the search. Confirm.
3. **Reaching-run minimization.** A found run may be large. Shrinking it before
   it becomes generator feedback is probably worth it, and is standard
   delta-debugging.
4. **`S` or `Set<S>` from `transfer`.** Disjunction inside `S` keeps the engine
   simple; the alternative is a signature change that is cheap in Phase 1 and
   expensive after Phase 3.
5. **Scoring corpus scope.** Shared across domains, or per-domain? Only a shared
   corpus makes proof counts comparable.
6. **Alarm vs. Exhausted in scoring.** A domain that times out is not the same as
   one that finishes and cannot prove. Decide whether both count against it.
7. **Java version floor.** Pinned at 21 for record patterns and
   pattern-matching-for-switch, which give the model exhaustiveness checking on
   the IR.
8. **When to flip `intWidth` to 32.** Before Phase 10 makes wraparound the
   adversary's first realistic target and tests the loop on a genuine bug; after
   Phase 10 keeps the first generated domain easier to get right. Flagged
   **[decide]** in §5.2.
9. **Front-end scope.** `SootIrProvider` v1 reads plain class files. Whether it
   should also accept a jar or a directory tree matters only for the scoring
   corpus, and can wait.
11. **The other query forms.** `Reachable` is deliberately the only one.
    Historia's `ReceiverNonNull`, `CallinReturnNonNull`, `MemoryLeak` and
    `InitialQueryWithStackTrace` had grown messy, and the plan is to re-engineer
    them rather than port them. Until then the reduction in `README.md` covers
    the gap: a question about state becomes a question about a location, by
    guarding a fresh location with an `assume`. Keeping `Query` a sealed trait
    with one case is the only concession made to that future.
12. **Recording level defaults.** `Full` is right while building, `Off` right
    for campaigns, and it is not obvious which the adversary should use — it
    inspects refutations, and a trace may be exactly the hint it needs to
    construct a probe, or may just leak analysis noise into its prompt.
13. **Profile growth order.** Which construct comes second: a second method
    (needs call/return and a stack), fields (needs a heap domain), or arrays.
    Each is a different kind of work, and the choice determines what the second
    domain has to represent.
