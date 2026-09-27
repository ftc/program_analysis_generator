# Implementation strategy

A plan for a prototype of the design in `README.md`: a generate-and-test loop
over IMP and the interval domain, with a hard boundary between code a model may
write and code it may not.

Regenerated 2026-09-23 against the reachability-check design. Decisions made on
thin evidence are marked **[decide]**; open questions are collected at the end.
Ideas considered and parked live in `misc.md`.

## 1. What the system does

One query is: *given a domain `D`, a program `p`, and a location `ℓ`, can `D`
prove `ℓ` unreachable?* Everything else is built around making that query fast,
making it falsifiable, and running two agents against it.

```
  generator ──writes──> domain D
                          │
                          ├── analyze(D, p, ℓ) ──> Refuted | Alarm | Inconclusive
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
| Campaign driver | human | Scala 3 | `campaign/` — separate codebase | none |

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

### The trust base

A verdict rests on a small amount of human-written code that nothing else in
the loop checks for it (`misc.md` §1 has the argument):

| code | where | the claim it carries |
| --- | --- | --- |
| loading, profile check, lifting, lowering | `frontend-soot`, `core` | the `Cfg` a domain analyses means what the bytecode does |
| `instrumentReach` | `frontend-soot` | a marker prints exactly when its `Loc` is reached |
| Stage 1 interpreter, run and marker check | `harness` | the executor implements the intended semantics |
| certifier | `core` | only a certified map yields `Refuted` |

A defect here does not show up as a bad domain; it shows up as a good domain
rejected or a bad one accepted, long after the defect was written, and it is
the most frustrating kind of bug this project can have. So this code is held to
a higher bar than the rest, as a standing rule:

- **Exhaustively unit tested.** Every row of every rule table — each lifting
  rewrite (§5.7), each lowering case (§5.3), each profile rule with a passing
  and a failing fixture (§5.2) — has its own test, written with the code.
- **Reviewed by Shawn before it merges.** Changes to these modules are called
  out as trust-base changes, not folded into larger diffs.
- **Cross-checked end to end.** Stage 1 and Stage 2 agreeing on a corpus, with
  markers at every location (Phase 2b), is a standing test that runs in CI.

*Decided — Shawn, 2026-09-26.* `probe-lib`'s `Rand` is deliberately *not* in
this table: it can break replay, but not a verdict (`misc.md` §1).

## 3. Repository layout

```
engine/                    sbt multi-project, human-only
  api/                     contract + IR. Pure Java, no Scala dependency.
  probe-lib/               Pure Java, no dependencies. pag.probe.Rand (§5.6),
                           the only library a probe may call
  frontend-soot/           Scala 3. the ONLY module allowed to import soot.*
  core/                    Scala 3. profile check, lowering, worklist,
                           invariant map, certifier
  harness/                 Scala 3. executor, probe runner, verdict, scoring,
                           mutation corpus, agent drivers
  results/                 Scala 3. result ADTs + codecs, shared with campaign/ (§13)
  cli/                     Scala 3. config, domain loading, entry point
domains/
  interval/                Java. the reference fixture, and later a target
    src/ ... build.sh      javac against api.jar. No sbt, no network.
  <generated domains>/
campaign/                  the outer loop. Separate codebase, drives pag by
                           subprocess; never linked against the engine (§12)
probes/
  <campaign>/              adversary output: programs, inputs, verdicts
corpora/
  scoring/                 programs used to measure proof count
  mutants/                 deliberately unsound domains, for calibration
results/
  <campaign>/              durable per-attempt records, so a campaign resumes
config/                    run configurations
docker/                    compose files and mount definitions
```

`engine/api` and `engine/probe-lib` are sbt subprojects with
`crossPaths := false` and `autoScalaLibrary := false`, so the published jars are
plain Java with no Scala coupling and no binary-compatibility constraint on
domains or probes.

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
| step limits and timeouts | kept as an **iteration limit**, surfaced as `Incomplete` |
| `ApproxMode` / nine `DropQryPolicy` variants | **dropped** — the engine never abandons a state, and weakening belongs to the domain (§7) |

Drop outright: APKs, Android, framework models and callback handling; Z3 and the SMT encoding
(entailment is a domain method — a domain may use a solver internally, the
engine must not know); message histories, CBCFTL, specifications, synthesis;
path nodes, witness explanations, the results database; the subsumption-mode variants; Scala 2.13.

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

A program passes through four stages, each its own pass:

```
  load (§5.5) ──> profile check (§5.2) ──> lifting (§5.7) ──> lowering (§5.3) ──> Cfg
  bytecode → IR    is it in the subset?     BigInteger calls     branches → assume,
                                            → arithmetic         calls → Call
```

The profile check and lifting can each be switched off in config; loading and
lowering cannot.

### 5.1 The source IR

Mirrors `ir/IRWrapper.scala`, trimmed of what we will not model for a long time
(no `SwitchCmd`, no `CaughtException`, no `ClassConst`). Everything here is
representable; §5.2 decides what is *loadable*.

```java
package pag.api.ir;

/** Where a state lives. Mirrors Historia's Loc; the Internal prefix leaves room
    for Callback*/Callin* locations when framework modelling returns. */
public sealed interface Loc {
    record InternalMethodEntry(String method)                implements Loc {}  // Historia: InternalMethodInvoke
    record AppLoc(String method, int index, boolean isPre)   implements Loc {}  // before/after command `index`
    record InternalMethodExit(String method)                 implements Loc {}  // Historia: InternalMethodReturn
}

public sealed interface Cmd {
    record Assign(LVal target, RVal source, Loc loc)  implements Cmd {}
    record Goto(RVal cond, Loc trueLoc, Loc loc)      implements Cmd {}  // conditional
    record Nop(Loc loc)                               implements Cmd {}
    record Return(Optional<RVal> value, Loc loc)      implements Cmd {}
    record InvokeStmt(Invoke call, Loc loc)           implements Cmd {}  // result discarded
    record Throw(Loc loc)                             implements Cmd {}  // disabled in v1
}

public sealed interface RVal {
    record IntConst(BigInteger v)                     implements RVal {}  // int, long and lifted constants
    record BoolConst(boolean v)                       implements RVal {}
    record Binop(RVal l, BinOp op, RVal r)            implements RVal {}
    record Invoke(InvokeKind kind, String declaringClass, String name,
                  Optional<RVal> receiver, List<RVal> args) implements RVal {}  // v1: listed callees only
    record Cast(String type, RVal v)                  implements RVal {}  // disabled in v1
    record NewObject(String className)                implements RVal {}  // disabled in v1
    record StringConst(String v)                      implements RVal {}  // disabled in v1
    record InstanceOf(String clazz, Local target)     implements RVal {}  // disabled in v1
    record ArrayLength(Local l)                       implements RVal {}  // disabled in v1
}

public enum InvokeKind { Static, Virtual, Special, Interface }

public sealed interface LVal extends RVal {
    record Local(String name, String type)                           implements LVal {}
    record Param(int index, String type)                             implements LVal {}  // v1: main's args binding only
    record This(String className)                                    implements LVal {}  // disabled in v1
    record Field(Local base, String declType, String name)           implements LVal {}  // disabled in v1
    record StaticField(String declaringClass, String name)           implements LVal {}  // v1: BigInteger constants only
    record ArrayRef(RVal base, RVal index)                           implements LVal {}  // disabled in v1
}

public enum BinOp { Mult, Add, Sub, Lt, Le, Gt, Ge, Eq, Ne }

public record Method(String name, List<Cmd> body) {}
public record Program(List<Method> methods, Loc entry) {}
```

`Goto` carries its own branch target, as in Historia. Fall-through is the next
index in the method body. An unconditional jump is `Goto(BoolConst(true), …)`.

`BinOp` has all six comparisons so that negating a branch condition during
lowering (§5.3) is an operator flip — `¬(a ≤ b)` is `a > b` — rather than an
operand swap that would make the printed CFG stop matching the source. Domains
see all six as well: `Step.Assume` conditions are never normalized to a smaller
set. *Decided — Shawn, 2026-09-26:* complexity goes to growing toward full Java,
not to shrinking what a domain sees.

`Invoke` is a faithful translation of a Jimple invoke expression, including its
`InvokeKind` and, for instance calls, its receiver. The kind is a source-IR fact only: lowering erases it (§5.3), so
the distinction between static, virtual and special dispatch never reaches a
domain. A call whose result is used is an `Assign` with an `Invoke` source; one
whose result is discarded is an `InvokeStmt`.

Jimple begins every method that takes parameters with an identity statement,
`r0 := @parameter0: java.lang.String[]` for `main`. It translates to
`Assign(Local r0, Param(0, "java.lang.String[]"))`.

### 5.2 The language profile

What the prototype accepts is declared in config and enforced by the **profile
check**, a pass of its own in `core` that runs between loading (§5.5) and
lowering (§5.3). Loading translates everything the IR can represent; the profile
check decides what is *accepted*. Anything outside the profile is a **profile
violation** naming the offending construct, its source line, and the setting
that would enable it — never a silent skip and never a `TopExpr`-style escape
hatch. The check reports every violation in a program, not just the first.

```hocon
language {
  enforce   = true                                      # false skips the profile check
  lift      = true                                      # false skips lifting (§5.7)
  name      = "bigint-main-v1"
  methods   = ["main"]                                  # a second method is a violation
  types     = ["java.math.BigInteger"]                  # plus compare temps (below)
  commands  = ["Assign", "Goto", "Nop", "Return", "InvokeStmt"]
  lvals     = ["Local"]
  rvals     = ["Local", "IntConst", "Binop", "Invoke", "StaticField"]
  operators = ["Lt", "Le", "Gt", "Ge", "Eq", "Ne"]      # only on compare temps (below)
  invokes   = ["Static", "Virtual"]
  callees   = ["java.math.BigInteger.valueOf",
               "java.math.BigInteger.add", "java.math.BigInteger.subtract",
               "java.math.BigInteger.multiply", "java.math.BigInteger.negate",
               "java.math.BigInteger.compareTo", "java.math.BigInteger.equals",
               "pag.probe.Rand.randInt"]               # §5.6; any other call is a violation
  staticFields = ["java.math.BigInteger.ZERO", "java.math.BigInteger.ONE",
                  "java.math.BigInteger.TWO", "java.math.BigInteger.TEN"]
  mainArgs  = "unread"                                  # any read of main's args is a violation
}
```

So v1 is *single-method, `BigInteger`-locals, conditional-goto Java with one
source of nondeterminism* — a genuine subset, not a lookalike. Every value is a
mathematical integer, so there is no overflow to model and no wraparound for an
adversary to exploit: `BigInteger` never wraps, and the JVM agrees with the
analysis's semantics by construction. `divide`, `mod`, `pow` and every other
`BigInteger` method are violations, which also keeps division by zero out.
Adding fields, more callees, or a second method later is a config change plus
whatever lifting, lowering and domain support it implies, not an IR rewrite.

*Decided — Shawn, 2026-09-24:* `BigInteger` only, replacing `int` and the
`intWidth` setting. Programs over Java `int` with 32-bit wraparound are a
possible later profile and a deliberate difficulty step — a generated interval
domain will very likely miss that `Integer.MAX_VALUE + 1` wraps — but they need
their own lifting and are out of scope for now.

Three rules need more than a list membership test:

- **`methods`** counts user methods. `javac` generates a default constructor
  `<init>` for every class; the check ignores it when it is the generated
  `super()` call and nothing else, and counts it otherwise.
- **`mainArgs = "unread"`** permits exactly one use of `main`'s parameter: the
  identity binding `r0 := @parameter0` that Jimple always emits. Any other
  occurrence of `r0` — `args.length`, `args[0]`, passing it on — is a
  violation. Programs get their inputs from `Rand` (§5.6), never from `args`.
- **`types`** applies to every local except that one `args` local and the
  *compare temps*: an `int` local assigned the result of `compareTo`, or a
  `boolean` local assigned the result of `equals`. Each compare temp must be
  used exactly once, by the `if` that immediately follows it, compared against
  `0`. Any other use — `int c = a.compareTo(b); if (c < 0 && d)` — is a
  violation rather than something lifting tries to handle. This is the shape
  `javac` emits for `if (a.compareTo(b) < 0)`, and lifting (§5.7) depends on it.
- **`staticFields`** is read-only: a `StaticField` may appear only as the source
  of an `Assign`, and only for the listed constants.
- **`null`** is never accepted, and neither is `new BigInteger(...)`; the only
  ways to make a value are `valueOf`, the four constants, arithmetic, and
  `randInt`.

**The lists are how the language grows.** A new construct is added by extending
the relevant list — a callee, an operator, a command — together with the
lifting, lowering and domain support it needs, with the check still on. Every
other construct stays rejected, so features arrive one at a time and each is
tested on its own. *Decided — Shawn, 2026-09-26.*

**`enforce = false` is for inspection only.** It skips the pass entirely, so
`pag ir` can show what the front end makes of a construct *before* it is added
— how `divide` looks in Jimple, say, and therefore how it should lift. Only
`pag ir` accepts it; `analyze` and `check` exit 1 when it is set, because a
verdict about a program outside the subset means nothing, and a `check` could
reject a correct domain over a construct it was never promised (a program that
reads `args` reaches a location the domain soundly refuted "for any input").
Starting strict is also the cheap direction: relaxing the refusal later is one
condition, while tightening it later would mean finding and discarding results
already stored. *Decided — Shawn, 2026-09-26.*

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

so branching never becomes a domain's problem. A domain sees three command
forms; a fourth, `Skip`, is handled by the engine alone:

```java
public sealed interface Step {
    record Assign(LVal target, RVal source)                       implements Step {}
    record Assume(RVal cond)                                      implements Step {}
    record Call(Optional<Local> target, String callee, List<RVal> args) implements Step {}
    record Skip()                                                 implements Step {}  // engine-only identity
}

public record Transition(Loc from, Step step, Loc to) {}
public record Cfg(List<Transition> transitions, Loc init, Loc exit) {}
```

Lowering runs after lifting (§5.7), so with lifting on it never sees a
`BigInteger` call — only arithmetic, comparisons, and `randInt`.

**Locations follow Historia.** Each command `i` in method `m` has two locations,
`pre(i) = AppLoc(m, i, true)` and `post(i) = AppLoc(m, i, false)`, and the method
has an `InternalMethodEntry(m)` and an `InternalMethodExit(m)`. A command's
effect is the edge `pre(i) → post(i)`; control flow between commands is an
unlabelled `skip` edge. `ℓ_init` is `InternalMethodEntry(main)` and `Cfg.exit`
is `InternalMethodExit(main)`.

| source IR at `i` | transitions |
| --- | --- |
| *(method entry)* | `InternalMethodEntry(m) —skip→ pre(0)` |
| `Assign(x, e)`, `e` not an `Invoke` | `pre(i) —x := e→ post(i) —skip→ pre(i+1)` |
| `Assign(x, Invoke(_, C, n, recv, args))` | `pre(i) —call(x, "C.n", recv ++ args)→ post(i) —skip→ pre(i+1)` |
| `InvokeStmt(Invoke(_, C, n, recv, args))` | `pre(i) —call(—, "C.n", recv ++ args)→ post(i) —skip→ pre(i+1)` |
| `Assign(r0, Param(0, _))` in `main` | `pre(i) —skip→ post(i) —skip→ pre(i+1)` |
| `Nop` | `pre(i) —skip→ post(i) —skip→ pre(i+1)` |
| `Goto(true, t)` | `pre(i) —skip→ post(i) —skip→ pre(t)` |
| `Goto(c, t)` | `pre(i) —skip→ post(i)`, then `post(i) —assume(c)→ pre(t)` and `post(i) —assume(¬c)→ pre(i+1)`, `¬` flipping the operator |
| `Return` | `pre(i) —skip→ post(i) —skip→ InternalMethodExit(m)` |

`skip` is `Step.Skip`, a fourth `Step` case that the engine handles itself as
the identity: it never calls `transfer` on it, so domains still implement three
forms. `[edge-inductive]` over a `skip` edge is `entails(I(ℓ'), I(ℓ))`. The
branch `assume`s sit on the edges *out of* `post(i)`, as in Historia, where
`resolveSuccessors` from the post-location of an `If` picks the target.

Why keep `isPre` when v1 could do with one location per command: it is what
lets calls slot in later without renumbering. A call at `i` will become
`pre(i) → InternalMethodEntry(callee)` and `InternalMethodExit(callee) → post(i)`,
so the call site's before and after are distinct locations with the callee
between them — the shape Historia's `ControlFlowResolver` already has. *Decided —
Shawn, 2026-09-24.*

**Queries and markers use `pre`.** `findLine` resolves a line to the `pre`
locations of its commands, and `instrumentReach` prints before the command's
first bytecode unit, which is the `pre` location. `post` locations and the
method entry/exit are addressable by the engine but not by `--at` in v1.

`Step.Call` is where the invoke kind disappears: the callee is a plain qualified
name with no dispatch kind, and a receiver, if any, becomes the first argument. Every callee a profile admits comes
with a stated meaning in the contract's documentation, and the domain implements
that meaning in `transfer`. With lifting on, v1 has one callee left by the time lowering runs,
`pag.probe.Rand.randInt`,
whose meaning is *any integer*: the backward transfer of `call(x, randInt)` frees
`x` and constrains nothing else (§5.6). Receivers, argument binding and dispatch
arrive with the profile setting that admits a second user method, and are a
change to lowering rather than to `Step`.

The `args` binding lowers to a no-op because `mainArgs = "unread"` guarantees
nothing reads the local it binds. With `enforce = false` that guarantee is gone
(§5.2).

Initial constraints also lower to an `assume` on the entry transition, which is
what makes `[refute]` in §7 a plain `isBottom` (README, "Programs, and why
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

- `transfer` handles all three `Step` forms. For `Call` it must recognise each
  callee the active profile admits; the smoke test (Phase 6) calls it once per
  admitted callee, so an unrecognised one fails at load.
- `transfer` returns one state. A domain needing disjunction carries it inside
  `S`, with `join` as its union, so the engine never learns about it. **[decide]** shawn: this is fine for now, later we may need to pull the disjunction out to improve parallelism though.
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
    /**
     * Methods found in the given compilation unit, already in api.ir form.
     * Translates everything api.ir can represent; the profile check (§5.2)
     * decides what is accepted. Throws only for bytecode api.ir cannot
     * represent at all (switch, monitors, exception handlers).
     */
    List<Method> load(Path classesOrJar) throws Untranslatable;

    /** Source file and line for a Loc, for error messages and tooling. */
    Optional<SourceRef> sourceOf(Loc loc);
}
```

`SootIrProvider` is the v1 implementation and lives alone in
`engine/frontend-soot`, which is the only module with Soot
(`org.soot-oss:soot:4.7.1`, the latest stable release as of 2026-09) on its
compile classpath. `cli` depends on it as `frontendSoot % "runtime->runtime"`
and obtains the `IrProvider` through `ServiceLoader`, so neither `soot.*` nor
`SootIrProvider` is visible to any other module at compile time. The rule is
enforced by the compiler rather than by a lint, and replacing Soot later means
writing a second `IrProvider` rather than auditing the analysis for leaks.

Two Soot settings are load-bearing, not tuning. `G.reset()` before every load,
because Soot's `Scene` is a process-wide singleton. And the `jb` phases
`jb.dae` (dead-assignment elimination) and `jb.uce` (unreachable-code
elimination) are **off**: the first deletes assignments to locals that are never
read, the second deletes statements with no path to them — and a statement with
no path to it is exactly what a reachability query may ask about. Either would
mean the IR analysed is not the code executed, which §9 depends on.

The cleanup that matters here is not cosmetic. Historia's `SootWrapper` is ~2150
lines because it carries APK loading, callback resolution, class-hierarchy
queries and framework modelling alongside the actual IR translation. For a
single-main-method Java subset the translation itself is small; the work in
Phase 2a is separating it from everything else rather than porting it.

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

### 5.6 Nondeterminism

A program with no input has exactly one execution, and a domain that tracks
constants exactly proves every unreachable location in it. The abstraction
collapses to concrete execution and nothing interesting is being tested. So v1
programs get their input from one call:

```java
package pag.probe;

public final class Rand {
    /** The next input. The analysis knows nothing about the value returned. */
    public static BigInteger randInt() { ... }
}
```

`engine/probe-lib` holds this class and nothing else. It is human-written, part
of the trust base, and small enough to read in one sitting.

**The adversary chooses every value.** `-Dpag.inputs=10,-3,99999999999`
supplies the values `randInt` returns, in order; they may be of any size. There
is no random generator and no seed: the name says what the analysis may assume
about the value, not how it is produced. A reaching run is therefore replayable
by construction — its inputs *are* the list. When the list is used up,
`randInt` throws, so a run never continues on a value nobody chose. An adversary
that wants randomness asks its shell for a number and puts it in the list.
*Decided — Shawn, 2026-09-26:* adversary-chosen values only; a seeded
generator was considered and dropped as unneeded.

**Why our own class.** The analysis matches the call by signature, so the
signature should be one we own and never changes, and `probe-lib` has no
dependencies so the Phase 9 containers stay JDK-only. The name avoids
`Instrumentation`, which collides with `java.lang.instrument.Instrumentation`
and with our own use of *instrument* for `instrumentReach`.

**The meaning a domain implements.** `call(x, "pag.probe.Rand.randInt", [])`
assigns `x` an arbitrary integer, so backward it frees `x` and leaves every
other variable's constraint untouched — `README.md`'s `x := randInt()`.

### 5.7 Lifting

`BigInteger` arithmetic is method calls in bytecode. Lifting is the pass that
turns them back into arithmetic, so a domain sees `y := x + 1` and
`assume(y < z)` rather than `y = x.add(ONE)` and a `compareTo` result. It is a
pass of its own in `core`, between the profile check and lowering, rewriting
source IR to source IR.

| source IR (from Jimple) | lifted |
| --- | --- |
| `r := BigInteger.valueOf(n)` | `r := n` |
| `r := BigInteger.ZERO` / `ONE` / `TWO` / `TEN` | `r := 0` / `1` / `2` / `10` |
| `r := a.add(b)` / `subtract` / `multiply` | `r := a + b` / `a - b` / `a * b` |
| `r := a.negate()` | `r := 0 - a` |
| `$i := a.compareTo(b)` then `if $i OP 0 goto t` | `nop` then `if a OP b goto t` |
| `$z := a.equals(b)` then `if $z == 0 goto t` | `nop` then `if a ≠ b goto t` |
| `$z := a.equals(b)` then `if $z != 0 goto t` | `nop` then `if a = b goto t` |
| `r := Rand.randInt()` | unchanged; lowering makes it a `Call` |

**Constant substitution.** After the rewrites above, a temp assigned a constant
and used exactly once has the constant substituted at its use:
`$r := 0; … if x <= $r` becomes `$r := 0; … if x <= 0`. The assignment stays in
place, dead, so locations are unchanged. This matters because the analysis runs
backward: without it a domain meets `x <= $r` before `$r := 0`, and a
non-relational domain can use neither. Jimple forces the temp — call arguments
must be locals or literals — so every `BigInteger` comparison with a literal
would otherwise be lost. Only single-use temps assigned in the same basic block
as their use are substituted; anything else is left alone. *Decided — Shawn,
2026-09-26.*

**Lifting never adds or removes a command.** A fused compare leaves a `Nop` where
the temp was assigned, so every `Loc` still names the same bytecode unit it did
after loading. `findLine`, `sourceOf` and `instrumentReach` all key on `Loc`,
and that correspondence is what lets a marker in the executed class mean the
location the analysis reasoned about.

Lifting relies on the profile check having passed: it assumes every compare
temp has exactly the one use the check enforces, and fails loudly — a bug, not
a violation — if it finds otherwise. Under `enforce = false` (inspection only,
§5.2) there is no such guarantee, so lifting rewrites only the shapes it
recognises and leaves everything else untouched.

**`lift = false`** skips the pass. Lowering then turns every `BigInteger` call
into a `Step.Call` with the receiver as first argument, static-field reads reach
the domain as `StaticField` sources, and compare temps stay `int`/`boolean`
locals. A domain for that setting must implement each `BigInteger` method as a
callee, which is a much harder contract; the switch exists so the lifted and
unlifted forms can be compared with `pag ir`, and so a later experiment can hand
a generator the unlifted form deliberately.

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
every query pays for its own fixed point, which is why the check in §9 is expensive
and §16 asks about adversary budget.

**This must be loud in the generator prompt.** A model's prior for
"the interval transfer function for `x := a`" is the *forward* one. The
signature `transfer(step, post) -> pre` does not prevent writing a forward
transfer by mistake — it just makes the argument names the only clue. Whatever
Phase 10 sends the generator has to state the direction, the reading of the
triple, and at least one worked backward example. The six cases in `README.md`
exist partly for this.

## 7. The engine

```scala
/** What the analysis learned. Never how it stopped. */
enum Verdict:
  case Refuted                        // certified unreachable
  case Alarm                          // searched fully, could not prove it
  case Inconclusive(why: Incomplete)  // did not search fully

/** Why a search was incomplete. All three stop it, so exactly one can fire. */
enum Incomplete:
  case IterationLimit(at: Int)
  case Deadline(afterMs: Long)
  case DomainFailure(op: String, error: Throwable)

/** The verdict plus what the search cost. Collected at every recording level. */
final case class AnalysisResult(
  verdict:    Verdict,
  iterations: Int,
  unexplored: Int,        // still in the worklist when the budget ran out
  elapsedMs:  Long,
  widenedAt:  Set[Loc]
)

def analyze[S](d: Domain[S], cfg: Cfg, q: Query, lim: Limits): AnalysisResult
```

Two clearly separated stages, because the README's soundness argument depends on
the separation:

**Compute.** Worklist. `I(ℓ) = d.top()` for every location `q` resolves to and
`d.bottom()` everywhere else. Pop a transition `ℓ —step→ ℓ'`, compute
`d.transfer(step, I(ℓ'))`, join it into `I(ℓ)` — or widen there, at a loop head
per `IrProvider.isLoopHead` — and re-enqueue predecessors of `ℓ` if `I(ℓ)` grew.
Stop on an empty worklist or the iteration limit. This stage may be arbitrarily
heuristic.

**Certify.** Independently re-check, against the settled map:

```
  [edge-inductive]   entails(transfer(step, I(ℓ')), I(ℓ))   for every transition
  [inductive]        I(ℓ) is top, for every target location ℓ
  [refute]           isBottom(I(ℓ_init))
```

Both stages call the recorder (§8) unconditionally — a failing
`[edge-inductive]` check records `StopReason.Uncertified` at the offending node,
which is otherwise very hard to locate. Only a map passing all three yields
`Refuted`. If certification fails the result is `Alarm` regardless of what the
worklist concluded. Keeping these apart means
widening, worklist order, and any future heuristic cannot affect soundness — a
property worth a test of its own (Phase 4).

Note that `[refute]` is `isBottom` rather than an `excludesInit` method: the
entry admits every store, because initial constraints lower to an `assume` on
the entry transition (§5.3).

**The engine never abandons a state.** Every state the worklist produces is
kept until it is joined, widened, or found already covered; nothing is skipped
to save time. Skipping a state would leave out executions that reach the
target, and a proof over an incomplete search is not a proof. When the budget
runs out the result is `Inconclusive`, never `Refuted`. *Decided — Shawn.*

**A hang is not a `DomainFailure`.** `Deadline` is checked between worklist
iterations, never inside a domain call, and a JVM thread cannot be safely
stopped from outside. So a generated `transfer` stuck in `while (true) {}` is
unkillable from inside `pag`; only the campaign driver killing the process
stops it (§12). `DomainFailure` covers what the engine *can* observe: a throw,
or a `null` return.

**Weakening is entirely the domain's.** The engine cannot look inside `S`, so
any loss of precision happens in the domain's `join` and `widen`, however the
domain chooses. The engine has no weakening policy of its own.

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

/** A location with an abstract state, as the analysis saw it. */
final case class DNode(id: NodeId, loc: Loc, state: Any /* S, opaque */)

/** Why one node follows from another. Recorded in analysis order: target → entry. */
enum Derivation:
  case Transfer(step: Step)          // pre computed from post across a transition
  case Join(withNode: NodeId)        // this state is a join of contributions
  case Widen(prevNode: NodeId)       // this state came from widening at a loop head

/** Why exploration stopped at a node. Recorded once, never part of identity. */
enum StopReason:
  case Subsumed(by: NodeId)          // absorbed into an existing state
  case Unexplored                    // still in the worklist when the budget ran out
  case Bottom                        // transfer produced ⊥; this branch is refuted
  case ReachedInit                   // reached ℓ_init — an alarm candidate
  case Uncertified(edge: Step)       // [edge-inductive] failed here
```

- **`Subsumed`, `Unexplored` and `Bottom` are distinct constructors**, not three
  readings of an empty set. That alone removes problem 1 and most of 7.
- **Edges are directed the way the analysis moved** (target → entry) and the
  reverse view is derived, not a second field. Reading a trace means walking the
  reverse view from an `Entry` node to the query. No `headOption` anywhere: a
  reader that wants one path asks for one and is told how many there were.
- **`DNode` is immutable and its identity is its `NodeId`.** `StopReason` lives
  in a side map keyed by id, so marking a node subsumed cannot change its
  identity.
- **`S` is untouched.** No provenance, no alternates, no flags. The graph stores
  the state the analysis had; the domain never learns the graph exists.
- **Recording is an interface with a no-op implementation**, not a mode that
  changes traversal semantics. `core` calls `recorder.transfer(...)` and friends
  unconditionally; `NullRecorder` discards. Certification never reads it.

### What a trace is, and is not

A `CandidateTrace` is a path from a `ReachedInit` node to the query. It is
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

The graph is O(worklist iterations), which will not be affordable in a campaign, so
recording has levels:

| level | keeps | cost | use |
| --- | --- | --- | --- |
| `Off` | nothing | none | campaign runs |
| `Terminal` | terminal nodes, one parent pointer each | O(terminals × depth) | alarm triage |
| `Full` | every node and edge kind | O(iterations) | debugging a specific query |

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

## 9. The reachability check

A reaching run is concrete and self-contained:

```scala
final case class ReachingRun(source: Path, classes: Path, inputs: List[BigInt], query: Reachable)
```

The analysis refutes "reachable on *any* input," so a reaching run names the
specific inputs `Rand.randInt` returns on the way there (§5.6). Verdict: run it; if `REACHED-<id>` appears on
stdout, the domain is unsound.

Because §5.5 reads the IR from a class file, the artifact analysed and the
artifact executed are the same class file up to an inserted `println`. There is
no source-to-source translation between them, which is what the previous draft's
two-stage executor was trying to work around.

**Stage 1 — reference interpreter.** `engine/harness` interprets the `Cfg` and
records visited locations, answering each `randInt` call from the same input list,
and stopping when it runs out, as `Rand` does. Fast, deterministic, no process launch, and useful
for debugging. The executor here is our own code, so a disagreement with Stage 2
is a bug in it.

**Stage 2 — run the class file.** `IrProvider.instrumentReach` writes a copy of
the classes that prints `REACHED-<id>` on arrival at the queried location; then
`java -Dpag.inputs=<values> -cp <copy>:probe-lib.jar Probe`, grep stdout for the marker. The instrumented copy
differs from the analysed one by a `println` that touches no local and no
control flow, so the two agree on whether the location is reached. The executor
is the JVM, so the evidence depends on nothing this project wrote beyond that
insertion. This is the verdict of record; Stage 1 exists for speed and
inspection, not for adjudication.

The two stages agree by construction: Stage 1 computes in `BigInt`, and the
program computes in `BigInteger`, so neither wraps.

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
reaching run, so reading cannot substitute for evidence. **[decide]** shawn: I agree that we should let the adversaries inspect the domain. We want the adversaries to succeed whenever possible.

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

## 11. The command line

One binary, `pag`, with subcommands. It is the only interface until the
dashboard is built, so it has to carry what the dashboard would have shown —
`dashboard.md` lists that correspondence.

```
pag ir      <classes> [--method M] [--cfg]            what the front end produced
pag run     <classes> [--inputs 3,-7,...]             execute, report locations visited
pag analyze --domain <jar> --classes <dir> --at M:L   verdict and invariant map
pag check   --domain <jar> --classes <dir> --at M:L   analyze, then try to falsify
```

`--at main:14` is the `Reachable(method, line)` query. `--json` works on all of
them; human-readable text is the default. `--config <file>` supplies domains and
limits in bulk instead of flags, for campaign use.

One later subcommand, `score` (proof count over the scoring corpus), once there
is a corpus. Nothing else: **`pag` does only what can be done without a network
or a credential.** Anything that talks to a model lives in the campaign driver
(§12), which drives `pag` through these exit codes rather than linking against
it.

### `analyze` prints the invariant map

With no dashboard this is the only way to see what a domain did, so the map is
the default output rather than something behind a flag:

For this probe (lines 10–15 of `Probe.java`):

```java
BigInteger x = Rand.randInt();
if (x.compareTo(BigInteger.ZERO) > 0) {
    BigInteger y = x.add(BigInteger.ONE);
    if (y.compareTo(BigInteger.ZERO) < 0) {
        x = BigInteger.ZERO;                       // line 14: the target
    }
}
```

```
$ pag analyze --domain domains/interval/out/interval.jar \
              --classes probes/c07/out --at main:14

classes   probes/c07/out            26 locations · profile bigint-main-v1
domain    interval-ref 0.1.0        api 0.3.1
query     Reachable(main, 14) → pre(10)

  entry                             ⊥
  pre(1)   x = Rand.randInt()       ⊥
  pre(4)   if x <= 0 goto 11        ⊥
  pre(6)   y = x + 1                x ↦ (-∞,-2]
  pre(9)   if y >= 0 goto 11        y ↦ (-∞,-1]
  pre(10)  x = 0                    ⊤                  ← target
  pre(11)  return                   ⊥
  (post locations, nops and constant temps elided)

worklist    12 iterations · no widening · 4ms
certified   27/27 edges inductive
entry       I(entry) = ⊥

REFUTED
```

The constants in each comparison reach the domain as constants because lifting
substitutes them (§5.7).

`--record full` additionally writes the derivation graph (§8) and, on an
`Alarm`, renders a `CandidateTrace` from the entry to the query. On an
`Inconclusive` the report names the `Incomplete` case and how many locations
were left `Unexplored`.

### `check` is the reachability check

The whole of §9 in one invocation, and what Phase 5's done-when exercises:

```
$ pag check --domain domains/interval/out/gen-04.jar \
            --classes probes/c07/out --at main:12 --inputs 5

analysis    REFUTED            9 iterations · 3ms
execution   REACHED-12         inputs [5] · instrumented copy · 12ms

UNSOUND — the domain refuted main:12, but the program reaches it
reaching run   probes/c07/Probe.java  inputs [5]
```

### Exit codes are the agent-facing contract

The generator and adversary drivers script these, so they must not have to parse
prose:

| code | meaning |
| --- | --- |
| 0 | completed; `Refuted` or `Alarm`, nothing contradicted |
| 1 | usage or I/O error |
| 2 | did not load — untranslatable bytecode (§5.5) or a profile violation (§5.2) |
| 3 | **unsound** — a reaching run contradicted a refutation |
| 4 | inconclusive — `IterationLimit` or `Deadline` |
| 5 | domain failure — generated code threw or returned null |

4 and 5 are separate because they call for opposite responses: 4 says raise the
budget, 5 says the domain is broken and the generator needs a stack trace. A
domain that *hangs* produces no exit code at all — `pag` never finishes, and the
campaign driver's wall-clock kill is what reports it (§7, §12).

## 12. The campaign driver — a separate codebase

The outer loop is **not part of the engine and not a `pag` subcommand.** It is a
separate codebase that drives `pag` through subprocesses, reading exit codes and
`--json`. Nothing is shared at the source level.

**Campaign** keeps its meaning as the unit of work: one run of the loop over a
set of domains with a fixed profile, api version, corpora and agent configs.
Everything a campaign produces records which campaign produced it, because
changing any of those inputs makes results incomparable.

### Why separate

- **Hangs.** A generated `transfer` in a tight loop cannot be killed from inside
  the engine's JVM (§7). Killing a process can. This alone decides it.
- **It is the crash-prone part.** Orchestration, HTTP clients, retries, rate
  limits, partial failures. That code churns and falls over; the engine should
  not inherit its blast radius.
- **The engine stays offline.** No API clients, no credentials, no network — so
  Phase 9's isolation is a property of the engine rather than something enforced
  around it, and `pag` stays a pure function of its inputs.
- **The trusted part stays small.** Engine correctness is what the project rests
  on. Keeping an LLM orchestrator out of it keeps the auditable surface honest.

### What it does

Generate a domain → build it → `pag analyze` over the scoring corpus → hand it
to the adversary → `pag check` each candidate → collect reaching runs → feed
failures back → repeat. Plus `mutants`: score an adversary against the seeded
mutant corpus, which needs the adversary and therefore lives here too.

### What it has to survive

| failure | response |
| --- | --- |
| `pag` exits non-zero | read the code (§11) and route: 2 is a bad probe, 3 a rejection, 5 a broken domain |
| `pag` hangs | kill the process after a wall-clock bound; that is the real timeout |
| model API fails or rate-limits | retry with backoff; do not lose the attempt |
| the driver itself crashes | resume — a campaign runs for hours over paid APIs, so progress must be durable, not in memory |

Durable progress is the requirement that shapes the rest of its design, and it
is the same need as the deferred dashboard's event store. A directory of JSON
per attempt is probably enough to start; SQLite behind the same interface is the
upgrade.

### Written in Scala 3

Decided: one language on the human-written side. The only source-level coupling
to the engine is `engine/results` (§13); everything else goes through
subprocesses and exit codes.

## 13. Serialization

Results cross a process boundary (§12) and are stored durably, so the format is
a real decision. Two requirements pull against each other: **readable, because
there is no dashboard and `cat` is how a campaign gets debugged**, and **fast,
because Historia's serialization got annoyingly slow.** Both are satisfiable.

### One codec set, two wire formats, chosen in config

```hocon
serialization {
  format = "json"      # or "cbor"
}
```

[Borer](https://github.com/sirthias/borer) is the fit: the same `Encoder` and
`Decoder` instances serve JSON and CBOR, with Scala 3 derivation for case
classes and sealed traits and no code generation. Switching is a different entry
point on the same codecs, not a different type hierarchy.

`pag dump <file>` prints any stored record as JSON whatever it was written as,
so choosing CBOR never costs inspectability — it only moves it behind a command.

### Why not protobuf

Protobuf does give both formats, since ScalaPB can project messages to JSON. The
objections are the other costs: a code-generation step, a second set of
generated types to convert to and from, and a `.proto` schema as the contract
when a small shared module (below) already serves. Its wins — cross-language,
strict long-lived schema evolution, streaming very large payloads — are not in
play. Revisit if a non-JVM component appears.

### What actually crosses, and where speed matters

The surface is small on purpose. **The IR never crosses the boundary:** the
driver passes paths and a query, and the engine loads the program itself.

| payload | size | format matters |
| --- | --- | --- |
| `AnalysisResult` | a verdict and four scalars | no |
| `CheckResult`, attempt records | small, one per query | no |
| derivation graph at `Full` | O(worklist iterations) | **yes** |

So the format switch earns its keep in exactly one place. That is also where
Historia's slowness lived — writing every path node — which is why recording
levels (§8) are the first lever and the format is the second.

**Default to JSON and switch on measurement, but build the seam now.** Designing
for two formats costs one indirection; retrofitting it means touching every call
site, the same argument as putting the recorder interface in at Phase 4.

### The shared contract

`engine/results` — a small module holding the result ADTs and their codecs,
depended on by both the engine and `campaign/`. One definition, no schema to
keep in sync. Deliberately *not* `engine/api`, which is what generated domains
compile against and should not accumulate result types.

Two rules learned from Historia, where 147 codec declarations were spread across
thirty files with **27 `RW.merge` sites enumerating sum types by hand**:

- **Codecs live in one object**, never scattered as implicits. Order-dependent
  resolution across files is most of what made that unpleasant.
- **Derive sums, never enumerate them.** Scala 3's `Mirror` derivation covers
  sealed hierarchies, so adding a case cannot silently break a codec.

Every record carries a versioned envelope — engine version, api version, profile
name, campaign id — which is the actual compatibility need and is independent of
format.

**One gotcha.** The IR types are Java records and sealed interfaces, which Scala
3 derivation does not reach. If the derivation graph is ever serialized rather
than rendered as text, `Loc` and `Step` need hand-written codecs. Two by hand is
fine; twenty-seven is what went wrong before.

## 14. Phases

Each phase names a deliverable and a done-when that is a runnable check.

### Phase 0 — skeleton
sbt multi-project for `engine`: `api` and `probe-lib` (pure Java), plus
`frontend-soot`, `core`, `harness`, `cli` in Scala 3, with `cli` depending on
`frontend-soot` at runtime only (§5.5). A `build.sh` for `domains/interval`
running `javac` against a fixed classpath. CI runs both.
*Done when:* both build, a placeholder test passes in each, and `build.sh`
succeeds with networking disabled.

### Phase 1 — the contract
Write `engine/api` as §5. Get it reviewed before building on it.
*Done when:* a stub domain compiles against `api.jar` alone with `javac`, and
`jdeps` reports no dependency outside `java.*` and `pag.api.*`.

### Phase 2a — load, check, lift, lower, print
The first milestone: load a simple Java program and inspect its CFG from the
command line. A cleaned `api.ir` extracted from Historia's `IRWrapper` (§5.1);
`SootIrProvider` with `load` and `sourceOf` only (§5.5); the profile check
(§5.2); lowering (§5.3); `pag ir`. Most of the work is separating IR translation
from the APK, callback and class-hierarchy machinery `SootWrapper` currently
mixes into it. `findLine`, `isLoopHead` and `instrumentReach` wait for the
phases that use them.
Lifting (§5.7) is in this phase too, since the CFG is unreadable without it.
*Done when:* a fixture `.java` using `Rand.randInt`, `BigInteger` arithmetic,
a loop and a branch, compiled in the test with `javax.tools`, loads through
`SootIrProvider` and `pag ir --cfg` matches a golden file — **and** fixtures
that read `args`, call `divide`, call a non-`BigInteger` method, use `null`,
declare a second method, or use a field each exit with code 2 and a message
naming the construct, its line and the profile setting — **and** the `args`
fixture loads under `enforce = false` — **and** under `lift = false` the same
golden fixture prints `BigInteger` calls with its location numbering unchanged
— **and** no module but `frontend-soot` can compile against `soot.*`.

Delivered as eight changes of about 200 lines each, reviewed one at a time
(`CLAUDE.md`):

| # | change | tested by |
| --- | --- | --- |
| 1 | multi-project build, empty modules, `Greeter` removed | `sbt test` green in every module |
| 2 | `api` IR types: `Loc`, `Cmd`, `RVal`, `Step`, `Cfg` | compiles; a `javac` stub compiles against the jar |
| 3 | `SootIrProvider`: straight-line code and `Return`; in-test fixture compilation | fixture → expected `Cmd` list |
| 4 | translation of branches, calls, parameter binding; `Untranslatable` | one fixture per construct |
| 5 | profile check | a passing and a failing fixture per rule |
| 6 | lifting, including constant substitution | one test per table row |
| 7 | lowering to `Cfg` with `pre`/`post`/entry/exit | one test per table row |
| 8 | `pag ir --cfg` | golden file |

### Phase 2b — the Stage 1 executor
`probe-lib`'s `Rand` (§5.6), the Stage 1 interpreter (§9), `pag run`, and
`findLine` for resolving `--at`.
*Done when:* `pag run --inputs …` on a fixture reproduces a visited-location
sequence recorded in a fixture file, and the same fixture run as a real class
file with `-Dpag.inputs` reaches the same markers — **and** the **all-locations
cross-check** runs in CI: for every fixture, instrument a marker at every `pre`
location, run Stage 2, and require that the markers printed equal the `pre`
locations Stage 1 visited, in order. This one test checks trust-base claims A
and B (`misc.md` §1) at once.

### Phase 3 — the reference interval domain
Written by hand, in Java, as a fixture. Also the worked example shown to the
generator, so write it the way generated code should look.
*Done when:* the six transfer cases in `README.md` pass as unit tests.

### Phase 4 — the analysis engine
Worklist, invariant map, widening, iteration limit, and the certifier as a separate
pass. The recorder interface (§8) with `NullRecorder` only — the graph comes
next, but the call sites go in now so they are never retrofitted.
*Done when:* `pag analyze` prints the map in §11's format; a program whose
target is unreachable gets `Refuted` and one whose target is reachable gets
`Alarm`; a domain that throws in `transfer` yields
`Inconclusive(DomainFailure(...))` rather than taking the run down; and a test
that deliberately corrupts the worklist result still cannot produce `Refuted`,
because certification is independent.

### Phase 4.5 — the derivation graph
`Full` recording, the reverse view, `CandidateTrace` extraction, and a text
renderer. `Terminal` level and reconstruct-from-`I` can wait until something is
slow.
*Done when:* an `Alarm` produces a trace from `ℓ_init` to the query that a human
can read; a `Refuted` query shows every branch ending in `Bottom` or `Subsumed`, with none
left `Unexplored`; and a deliberately broken `entails`
surfaces as `Uncertified` at the edge that failed.

### Phase 5 — the probe and the verdict
`pag check`, `ReachingRun`, the runner, and the rejection path with a
serialized counterexample. Exit codes as §11.
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
First code in `campaign/` (§12): generate → build → `pag analyze` on a smoke
corpus → report. Bounded retries, durable per-attempt records, every attempt
logged with api version, corpus hash and outcome.
*Done when:* a small model produces a domain that compiles, loads, and refutes at
least one target, working from the contract and the reference example alone.

### Phase 11 — the adversary agent
In `campaign/`: read domain → `pag analyze` for refuted targets → propose probes
→ `pag check` → report. Also `mutants`, which scores an adversary against the
seeded corpus and needs the adversary, so it belongs here rather than in `pag`. Score against the Phase 8 mutants first, then turn it on generated
domains.
*Done when:* the adversary's kill rate on mutants is reported, and it finds at
least one genuine reaching run against a generated domain.

### Later
Real-language backends beyond the Stage 2 Java emitter; a domain exercising
disjunction; the forward flow-insensitive phase; and the Future-ideas triggers in
`README.md` — cost, and behavior that cannot be driven because it runs through a
framework, library, or the OS.

## 15. What each phase de-risks

- 1 and 3: is the eight-method contract expressible enough to write a real
  domain against?
- 2a: does Soot stay behind `IrProvider`, and does the Jimple `javac` produces
  for the subset actually look like the source it came from?
- 4: does certifying the settled map actually work, independent of the worklist?
- 4.5: can we see why a query came out the way it did? Historia's experience is
  that this is where the debugging time goes, and retrofitting it is painful.
- 5: does the central idea hold end to end, with a human as adversary — and are
  the exit codes a contract an agent driver can actually script against?
- 6: does the isolation boundary survive JVM classloading?
- 8: is the adversary strong enough for its silence to mean anything? **This is
  the phase most likely to change the design, and a crude version of it is worth
  pulling forward into Phase 5.**
- 10 and 11: is the contract legible enough for a small model, in both roles —
  and does the subprocess boundary actually hold when a generated domain hangs?

## 16. Open questions

1. **Adversary budget and stopping rule.** How long does an adversary search
   before a domain is provisionally accepted? Phase 8's calibration should set
   this empirically rather than by guess.
2. **Does the adversary see the domain source?** Assumed yes (§10). It is a
   machine, and reading targets the search. Confirm.
3. **Reaching-run minimization.** A found run may be large. Shrinking it before
   it becomes generator feedback is probably worth it, and is standard
   delta-debugging.
4. **`S` or `Set<S>` from `transfer`.** Disjunction inside `S` keeps the engine
   simple; the alternative is a signature change that is cheap in Phase 1 and
   expensive after Phase 3.
5. **Scoring corpus scope.** Shared across domains, or per-domain? Only a shared
   corpus makes proof counts comparable.
6. **Aggregating `DomainFailure`.** One throw is a per-query `Inconclusive`, but
   a domain that throws on half the corpus is broken and should be rejected like
   an unsound one — with a stack trace instead of a reaching run. That is a
   campaign-level policy, not a verdict; where the threshold sits is open.
7. **Java version floor.** Pinned at 21 for record patterns and
   pattern-matching-for-switch, which give the model exhaustiveness checking on
   the IR.
8. ~~**When to flip `intWidth` to 32.**~~ *Superseded:* v1 is `BigInteger`
   only and has no `intWidth` (§5.2); `int` programs are a possible later profile.
9. **Front-end scope.** `SootIrProvider` v1 reads plain class files. Whether it
   should also accept a jar or a directory tree matters only for the scoring
   corpus, and can wait.
10. **The other query forms.** `Reachable` is deliberately the only one.
    Historia's `ReceiverNonNull`, `CallinReturnNonNull`, `MemoryLeak` and
    `InitialQueryWithStackTrace` had grown messy, and the plan is to re-engineer
    them rather than port them. Until then the reduction in `README.md` covers
    the gap: a question about state becomes a question about a location, by
    guarding a fresh location with an `assume`. Keeping `Query` a sealed trait
    with one case is the only concession made to that future.
11. **Campaign resumption granularity.** Per generated domain, per query, or per
    agent call? Finer means less lost work and more bookkeeping; a campaign runs
    for hours over paid APIs, so this is not cosmetic.
12. **Recording level defaults.** `Full` is right while building, `Off` right
    for campaigns, and it is not obvious which the adversary should use — it
    inspects refutations, and a trace may be exactly the hint it needs to
    construct a probe, or may just leak analysis noise into its prompt.
13. **Profile growth order.** Which construct comes second: a second method
    (needs call/return and a stack), fields (needs a heap domain), or arrays.
    Each is a different kind of work, and the choice determines what the second
    domain has to represent.
14. **Constants behind temps.** Jimple arguments must be locals or literals, so
    `x.compareTo(BigInteger.ZERO)` becomes `$r = ZERO; … x.compareTo($r)`, and
    lifting yields `$r := 0` then `assume(x > $r)`. A *backward* non-relational
    domain meets the `assume` first and `$r := 0` only afterwards, so it cannot
    use the constant — the interval domain would prove nothing about any
    comparison with a literal. Lifting could substitute a temp that is assigned
    a constant and used once, leaving its assignment in place so locations are
    unchanged. *Decided:* yes, in lifting (§5.7).
