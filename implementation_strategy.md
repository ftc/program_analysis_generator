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
| IR: source IR, `Step`, `Cfg` | human | Scala 3 | `engine/ir` | none |
| Analysis engine, certifier | human | Scala 3 | `engine/core` | none |
| Executor, probe runner, scoring | human | Scala 3 | `engine/harness` | none |
| Domain contract + domain vocabulary | human | **Java 21** | `engine/api` | compiled jar |
| **Abstract domains** | **generator agent** | **Java 21** | `domains/<d>/` | read-write |
| **Probe programs / reaching runs** | **adversary agent** | **Java 21** | `probes/<campaign>/` | read-write |
| Campaign driver | human | Scala 3 | `campaign/` — separate codebase | none |

Everything a domain compiles against is Java; everything else is Scala 3. The
engine is Scala because that is what a human maintains. The contract and domains
are Java because that is what a small model writes reliably — large training
corpus, regular syntax, actionable `javac` errors for the repair loop, and fast
compiles in a loop that runs thousands of times.

The line is drawn at what a domain *sees*, not at the IR as a whole. The IR is
Scala, in `engine/ir`, because the engine's trust-base code — the profile
check, lifting, lowering, the interpreter, the certifier — pattern-matches on
it constantly, and Scala 3.3 neither destructures Java records in patterns nor
checks a match over a Java sealed interface for missing cases (tried
2026-09-29: a match omitting `Step.Skip` compiled silently). Over Scala sealed
types it does, and the build turns that check from a warning into an error
(`-Wconf:id=E029:e`), so a match missing a case fails to compile. A domain sees only
the *domain vocabulary* — `Step` and the values inside it — as Java types in
`engine/api`, produced from the Scala IR by one converter just before
`transfer` is called (§5.4). *Decided — Shawn, 2026-09-29.*

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
contract pays — concretely, a second converter at the §5.4 seam, producing a
serialized domain vocabulary instead of Java objects.

### The trust base

A verdict rests on a small amount of human-written code that nothing else in
the loop checks for it (`misc.md` §1 has the argument):

| code | where | the claim it carries |
| --- | --- | --- |
| loading, profile check, lifting, lowering | `frontend-soot`, `core` | the `Cfg` a domain analyses means what the bytecode does |
| `reach` and its lowering | `probe-lib`, `core` | a marker prints exactly when the location of its `reach` call is reached |
| IR interpreter, JVM run and marker check | `harness` | the executors implement the intended semantics |
| certifier | `core` | only a certified map yields `Refuted` |
| domain-vocabulary converter | `core` | the Java `Step` a domain receives means what the Scala `Step` means |

A defect here does not show up as a bad domain; it shows up as a good domain
rejected or a bad one accepted, long after the defect was written, and it is
the most frustrating kind of bug this project can have. So this code is held to
a higher bar than the rest, as a standing rule:

- **Exhaustively unit tested.** Every row of every rule table — each lifting
  rewrite (§5.7), each lowering case (§5.3), each profile rule with a passing
  and a failing fixture (§5.2) — has its own test, written with the code.
- **Reviewed by Shawn before it merges.** Changes to these modules are called
  out as trust-base changes, not folded into larger diffs.
- **Cross-checked end to end.** The IR interpreter and the JVM run agreeing on a corpus of
  fixtures dense with `reach` calls (Phase 2b) is a standing test in
  `sbt test`.

*Decided — Shawn, 2026-09-26.* `probe-lib`'s `Rand` is deliberately *not* in
this table: it can break replay, but not a verdict (`misc.md` §1).

## 3. Repository layout

```
engine/                    sbt multi-project, human-only
  api/                     contract + domain vocabulary. Pure Java, no Scala
                           dependency. The only engine code an agent sees.
  ir/                      Scala 3. the IR: source IR, Step, Cfg, IrProvider
  probe-lib/               Pure Java, no dependencies. pag.probe.Rand (§5.6)
                           and pag.probe.Reach (§5.8), the only library a
                           probe may call
  frontend-soot/           Scala 3. the ONLY module allowed to import soot.*
  core/                    Scala 3. profile check, lifting, lowering,
                           worklist, invariant map, certifier
  harness/                 Scala 3. executor, probe runner, verdicts, scoring
  results/                 Scala 3. result ADTs + codecs, shared with campaign/ (§13)
  cli/                     Scala 3. config, domain loading, entry point
domains/                   every domain worth keeping, one directory each
  interval/                Java. the reference fixture, and later a target
    src/ ... build.sh      javac against api.jar. No sbt, no network.
    domain.json            metadata (below)
  mut-<name>/              a hand-written mutant; domain.json names its bug
  gen-<nnnn>/              a generated domain
    rejections/<r>/        one per reaching run that rejected it
      Probe.java           the program
      run.json             everything needed to replay it (below)
campaign/                  the outer loop. Separate codebase, drives pag by
                           subprocess; never linked against the engine (§12)
probes/
  <campaign>/              the adversary's working directory; not committed.
                           A probe that rejects a domain is copied to
                           domains/<id>/rejections/
corpora/
  scoring/                 programs used to measure proof count
  mutants.txt              the mutant corpus: a list of domain ids (below)
results/
  <campaign>/              durable per-attempt records, so a campaign resumes
config/                    run configurations
docker/                    compose files and mount definitions
```

### Domains and their records

A domain's source, its metadata and the counterexamples that rejected it live
together under `domains/<id>/`, checked into git as plain files. *Decided —
Shawn, 2026-09-26:* files in git for the first pass.

**`domain.json`** — the domain's identity and history, in the §13 envelope:

| field | contents |
| --- | --- |
| `id`, `status` | `reference`, `mutant`, `generated`, `surviving` or `rejected` |
| `origin` | hand-written, or the generator's model, config, campaign id and prompt hash |
| `api`, `profile` | the versions it was built and judged against |
| `plantedBug` | mutants only: what was broken, and where |
| `rejections` | ids under `rejections/` |
| `calibration` | each fresh-adversary attempt: adversary config, budget, rediscovered or not, cost |
| `proofCount` | once the scoring corpus exists (Phase 7) |

**`run.json`** — one rejection, replayable with a single `pag check`: the
`reach` id, the inputs, the analysis verdict, the markers printed, and the
engine, api and profile versions and adversary config that produced it.

**The mutant corpus is a list, not a copy.** `corpora/mutants.txt` names every
domain whose status is `mutant` or `rejected`. Hand-written mutants seed it;
every domain the adversary rejects joins it, carrying a realistic bug rather
than a planted one (Phase 8).

**What is committed and what is not.** Reference, mutant, surviving and
rejected domains are committed, since they are few and worth reviewing like
code. Domains that fail to compile or load, and full attempt logs, stay in
`results/<campaign>/` and are not committed: a campaign produces hundreds.
Moving to SQLite later changes the storage, not the schema, because both files
use the `engine/results` codecs (§13).

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
| `CmdWrapper` / `RVal` / `LVal` / `BinaryOperator` | **reused** as the Scala IR in `engine/ir`, cleaned, with a config profile gating what loads (§5.2) |
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
representable; §5.2 decides what is *loadable*. It is Scala, in `engine/ir`
(§2); a domain never sees it, only the Java projection of `Step` (§5.4).
Constructors reject contradictory data — a jump past the last command, a
`Static` invoke with a receiver, `body` and `lines` of different lengths.
Types whose constructors check something are sealed traits with case classes,
since a Scala 3 enum case cannot have a body; the rest are enums.

```scala
package pag.ir

/** Where a state lives. Mirrors Historia's Loc; the Internal prefix leaves room
    for Callback/Callin locations when framework modelling returns. */
sealed trait Loc
object Loc:
  case class InternalMethodEntry(method: MethodId)                 extends Loc  // Historia: InternalMethodInvoke
  case class AppLoc(method: MethodId, index: Int, isPre: Boolean)  extends Loc  // before/after command `index`
  case class InternalMethodExit(method: MethodId)                  extends Loc  // Historia: InternalMethodReturn

/** A method's fully qualified identity; printed as Probe.main(java.lang.String[]). */
final case class MethodId(declaringClass: String, name: String,
                          paramTypes: List[JType], returnType: JType):
  def qualifiedName: String   // java.math.BigInteger.add — class and name, ignoring overloads

/** A Java type. toString is its one printed form: int, java.math.BigInteger, java.lang.String[]. */
enum JType:
  case Void                    // return types only
  case Prim(kind: PrimKind)
  case Ref(className: String)
  case ArrayOf(elem: JType)

enum PrimKind:
  case Boolean, Byte, Char, Short, Int, Long, Float, Double

sealed trait Cmd
object Cmd:
  case class Assign(target: LVal, source: RVal)  extends Cmd
  case class Goto(cond: RVal, target: Int)       extends Cmd  // target: a body index
  case object Nop                                extends Cmd
  case class Return(value: Option[RVal])         extends Cmd
  case class InvokeStmt(call: RVal.Invoke)       extends Cmd  // result discarded
  case object Throw                              extends Cmd  // disabled in v1

sealed trait RVal
object RVal:
  case class IntConst(v: BigInt)                                  extends RVal  // int, long and lifted constants
  case class BoolConst(v: Boolean)                                extends RVal
  case class Binop(l: RVal, op: BinOp, r: RVal)                   extends RVal
  case class Invoke(kind: InvokeKind, callee: MethodId,
                    receiver: Option[RVal], args: List[RVal])     extends RVal  // v1: listed callees only
  case class Cast(tpe: JType, v: RVal)                            extends RVal  // disabled in v1
  case class NewObject(className: String)                         extends RVal  // disabled in v1
  case class StringConst(v: String)                               extends RVal  // disabled in v1
  case class InstanceOf(clazz: String, target: LVal.Local)        extends RVal  // disabled in v1
  case class ArrayLength(l: LVal.Local)                           extends RVal  // disabled in v1

sealed trait LVal extends RVal
object LVal:
  case class Local(name: String, tpe: JType)                      extends LVal
  case class Param(index: Int, tpe: JType)                        extends LVal  // v1: main's args binding only
  case class This(className: String)                              extends LVal  // disabled in v1
  case class Field(base: Local, declType: String, name: String)   extends LVal  // disabled in v1
  case class StaticField(declaringClass: String, name: String)    extends LVal  // v1: BigInteger constants only
  case class ArrayRef(base: RVal, index: RVal)                    extends LVal  // disabled in v1

enum InvokeKind:
  case Static, Virtual, Special, Interface

enum BinOp:
  case Mult, Add, Sub, Lt, Le, Gt, Ge, Eq, Ne

/** lines(i) is the source line of body(i), or -1 if unknown. */
final case class Method(id: MethodId, body: Vector[Cmd], lines: Vector[Int]):
  def lineOf(index: Int): Option[Int]
  /** The pre (isPre) or post locations of every command on a line, in body order. */
  def locationsOn(line: Int, isPre: Boolean): List[Loc.AppLoc]

final case class Program(sourceFile: String, methods: List[Method], entryMethod: MethodId)
```

**Methods are identified by `MethodId`**, the fully qualified signature, as
Historia does with `Signature`: declaring class, name, parameter types and
return type, so overloads and same-named methods in different classes are
distinct. It is a record rather than Soot's signature string
(`<Probe: void main(java.lang.String[])>`), so no front end's naming format
crosses the §5.5 boundary, and the profile check can ask for a method's class
and name without parsing. *Decided — Shawn, 2026-09-29.*

**Types are structured, not strings.** A type written as a string has more than
one spelling — `java.lang.String[]`, `[Ljava/lang/String;`, `String[]` — and
every comparison of `MethodId`s, and the profile's `types` rule, would silently
depend on everyone using the same one. `JType` has exactly one form of each
type; only the front end builds them, from its own type objects, and
`JType.toString` is the one printed form. `Void` is accepted only as a return
type. Class *names* (`declaringClass`, `StaticField`, …) stay strings: they name
classes, not types. *Decided — Shawn, 2026-09-29.*

**A command does not know its own location.** Its position is its index in
`Method.body`, and its locations are derived from that: `pre(i)` is
`AppLoc(m, i, true)`. Storing the position on the command as well, as
Historia's `CmdWrapper` does, would record it twice, and the two copies could
disagree — especially across lifting, which rewrites commands in place. So
`Goto` names its target by index, and fall-through is the next index. An
unconditional jump is `Goto(BoolConst(true), t)`. *Decided — Shawn,
2026-09-27.*

**Source lines live on `Method`**, one per command, recorded by the front end
at load. Lifting never adds or removes a command (§5.7), so the list stays
correct through it. A line maps to a *list* of locations, never one: a line can
hold several statements with no order between them (`if (a) x(); else y();`),
`javac` can emit one line's code in two places (a `for` header's initializer
and its condition), and Jimple splits one expression into several units that
share a line. Callers treat the list as a disjunction (§6). In v1, lines serve
error messages and `pag ir`; queries still name `reach` calls (§5.8).

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
  rvals     = ["Local", "IntConst", "BoolConst", "Binop", "Invoke", "StaticField"]
  operators = ["Lt", "Le", "Gt", "Ge", "Eq", "Ne"]      # only on compare temps (below)
  invokes   = ["Static", "Virtual"]
  callees   = ["java.math.BigInteger.valueOf",
               "java.math.BigInteger.add", "java.math.BigInteger.subtract",
               "java.math.BigInteger.multiply", "java.math.BigInteger.negate",
               "java.math.BigInteger.compareTo", "java.math.BigInteger.equals",
               "pag.probe.Rand.randInt",                # §5.6
               "pag.probe.Reach.reach"]                 # §5.8; any other call is a violation
  staticFields = ["java.math.BigInteger.ZERO", "java.math.BigInteger.ONE",
                  "java.math.BigInteger.TWO", "java.math.BigInteger.TEN"]
  mainArgs  = "unread"                                  # any read of main's args is a violation
  reach     = "literal-unique"                          # §5.8: literal ids, none repeated
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
  identity binding `args := @parameter0` that Jimple always emits. Any other
  occurrence of the local it binds — `args.length`, `args[0]`, passing it on —
  is a violation. The local is identified by that binding, not by its name
  (without `javac -g` it is `l0`). Programs get their inputs from `Rand`
  (§5.6), never from `args`.
- **`types`** applies to every local except that one `args` local and the
  *compare temps*: an `int` local assigned the result of `compareTo`, or a
  `boolean` local assigned the result of `equals`. Each compare temp must be
  used exactly once, by the `if` that immediately follows it, compared against
  `0` (a `compareTo` temp) or `false` (an `equals` temp). Any other use — `int c = a.compareTo(b); if (c < 0 && d)` — is a
  violation rather than something lifting tries to handle. This is the shape
  `javac` emits for `if (a.compareTo(b) < 0)`, and lifting (§5.7) depends on it.
- **`staticFields`** is read-only: a `StaticField` may appear only as the source
  of an `Assign`, and only for the listed constants.
- **`null`** is never accepted, and neither is `new BigInteger(...)`; the only
  ways to make a value are `valueOf`, the four constants, arithmetic, and
  `randInt`. Neither needs a rule of its own: the front end already refuses
  `null` as untranslatable (§5.5), and `new` fails the `rvals` list (no
  `NewObject`) and `invokes` (no `Special`).
- **`BoolConst`** is in `rvals` because every unconditional jump is
  `Goto(BoolConst(true), …)` and every `equals` test compares against `false`.
  *Decided — Shawn, 2026-09-30.*

**For now the v1 profile is a hard-coded Scala value** (`Profile.BigintMainV1`
in `core`); reading the `language` block from HOCON arrives with config
parsing in Phase 6. *Decided — Shawn, 2026-09-30.*

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

A `Goto(cond, t)` at index `i` lowers, in outline, to

```
  post(i) —assume(cond)→ pre(t)        post(i) —assume(¬cond)→ pre(i+1)
```

so branching never becomes a domain's problem. A domain sees three command
forms; a fourth, `Skip`, is handled by the engine alone:

```scala
package pag.ir

enum Step:
  case Assign(target: LVal, source: RVal)
  case Assume(cond: RVal)
  case Call(target: Option[LVal.Local], callee: MethodId, args: List[RVal])
  case Skip                                    // engine-only identity

final case class Transition(from: Loc, step: Step, to: Loc)
final case class Cfg(transitions: List[Transition], init: Loc, exit: Loc)
```

A domain receives the Java projection of `Step`, not this type (§5.4).

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
| `Assign(x, Invoke(_, f, recv, args))` | `pre(i) —call(x, f, recv ++ args)→ post(i) —skip→ pre(i+1)` |
| `InvokeStmt(Invoke(_, f, recv, args))` | `pre(i) —call(—, f, recv ++ args)→ post(i) —skip→ pre(i+1)` |
| `InvokeStmt(Invoke(Static, pag.probe.Reach.reach(int), —, [id]))` | `pre(i) —skip→ post(i) —skip→ pre(i+1)`; `pre(i)` is the target of `Reachable(id)` (§5.8) |
| `Assign(r0, Param(0, _))` in `main` | `pre(i) —skip→ post(i) —skip→ pre(i+1)` |
| `Nop` | `pre(i) —skip→ post(i) —skip→ pre(i+1)` |
| `Goto(true, t)` | `pre(i) —skip→ post(i) —skip→ pre(t)` |
| `Goto(c, t)` | `pre(i) —skip→ post(i)`, then `post(i) —assume(c)→ pre(t)` and `post(i) —assume(¬c)→ pre(i+1)`, `¬` flipping the operator |
| `Return` | `pre(i) —skip→ post(i) —skip→ InternalMethodExit(m)` |
| `Throw` (outside v1's profile) | `pre(i) —skip→ post(i)`, and no edge out of `post(i)`: execution ends |

Lowering produces the `Cfg` of the entry method and, alongside it, the `pre`
location of each `reach(id)` call, since lowering is where those calls
disappear (`Lowered.reachSites`). A branch whose condition is not a
comparison, a call assigned to anything but a local, and a command that falls
through past the last one are engine bugs under the profile, and fail loudly.

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

**Queries use `pre`.** A query names a `reach` call (§5.8), and its target is
that call's `pre` location. `post` locations and the method entry/exit are
addressable by the engine but not by a query in v1.

`Step.Call` is where the invoke kind disappears: the callee is its `MethodId`
with no dispatch kind, and a receiver, if any, becomes the first argument. Every callee a profile admits comes
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
  `S`, with `join` as its union, so the engine never learns about it.
  *Decided — Shawn, 2026-09-26:* fine for now; disjunction may later be pulled
  out of `S` so the engine can explore disjuncts in parallel.
- The obligations these methods must satisfy are real but unstated in code —
  Lemma 1 and its companions, w.r.t. a concretization each domain has and never
  writes down. The harness verifies their consequence, not them.
- Version the api jar and record its version, and the language profile, in every
  result.

**The domain vocabulary.** `transfer`'s `Step` is `pag.api.Step`, a Java type,
not the Scala IR's. It carries exactly what lowering can emit under the profile
settings that exist, and nothing more, so the generator never reads a type it
can never receive:

```java
package pag.api;

public sealed interface Step {
    record Assign(LVal.Local target, RVal source)                    implements Step {}
    record Assume(RVal cond)                                         implements Step {}
    record Call(Optional<LVal.Local> target, MethodId callee, List<RVal> args) implements Step {}
}
public sealed interface RVal  { IntConst(BigInteger v); Binop(RVal l, BinOp op, RVal r); LVal }
public sealed interface LVal extends RVal { Local(String name, String type);
                                            StaticField(String declaringClass, String name) }  // lift = false only
public enum BinOp { Mult, Add, Sub, Lt, Le, Gt, Ge, Eq, Ne }
public record MethodId(String declaringClass, String name, List<String> paramTypes, String returnType) {}
```

It keeps the Scala IR's names and shapes, so the conversion is one-to-one,
with one exception: types (`Local.type`, `MethodId`'s parameter and return
types) are strings in Java, produced only by `JType.toString`, so a domain
never sees two spellings of one type. A Java mirror of `JType` can come later,
the first time a domain needs to branch on a type rather than compare one.
`Skip` is absent (the engine handles it), as are `Invoke` (lowered to `Call`),
`Param` (lowered to `skip`), `BoolConst` (only `Goto(true, …)`, which lowers
to `skip`), and every construct v1 disables. It grows when a profile setting
starts emitting something new. *Decided — Shawn, 2026-09-29.*

**The converter** is the one place the Scala IR becomes the domain vocabulary:
a match over the Scala `Step` and `RVal`, so the compiler checks it covers every
case, producing the Java records. It sits in `core` beside the worklist, runs
immediately before each `transfer`, and is trust-base code (§2): a
mistranslated `Sub` makes a correct domain wrong. Handing the converter a Scala
value the vocabulary cannot express — `Skip`, `Invoke`, a disabled construct —
is an engine bug and throws. It is also the seam where an out-of-process
domain would plug in (§2): a second converter producing a serialized
vocabulary instead of Java objects. Built as the first change of Phase 4, with
its first caller.

### 5.5 The front end, and the Soot boundary

Every program the system analyzes is loaded through one interface,
`IrProvider`, which turns class files into the IR. No module downstream of it
may import `soot.*`.

```scala
package pag.ir

/** The only way a program enters the system. */
trait IrProvider:
  /** The program in the given compilation unit, as IR, with a source line for
    * each command. Translates everything the IR can represent; the profile
    * check (§5.2) decides what is accepted. Throws Untranslatable only for
    * bytecode the IR cannot represent at all (switch, monitors, handlers).
    */
  def load(classesOrJar: Path): Program
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
because Soot's `Scene` is a process-wide singleton. And the `jb` phase
`jb.uce` (unreachable-code elimination) is **off**: it deletes statements with
no path to them, and a statement with no path to it is exactly what a
reachability query may ask about.

`jb.dae` (dead-assignment elimination) is off too, conservatively. It was
first listed here as load-bearing on the belief that it deletes any
never-read assignment; reading its source (2026-09-29) showed that in the
`jb` pack it runs with `only-stack-locals` true, so it only removes never-read
stores to Soot's `$stack` temporaries whose right-hand side has no side
effects. That cannot remove a `reach` call, any call, or a store to a
programmer's variable, so it could not change a v1 query. It stays off because
off is never less faithful.

`setPhaseOption` returns false and logs only at debug level when a setting does
not take effect, so `SootIrProvider` treats a false return as an error: a
silently ignored setting would mean analysing different code without anyone
noticing.

What Soot 4.7.1 does to a never-read store, observed even with `jb.dae` off
(2026-09-29/30), depends on whether it thinks the right-hand side has a side
effect:

| never-read store | what reaches the IR |
| --- | --- |
| a call, `y = x.add(ONE)` | the call alone; the store to `y` is dropped |
| a static-field read, `z = BigInteger.TEN` | kept as written (class initialisation counts as a side effect) |
| side-effect-free, `q = n / 2` or `o = null` | **deleted outright**, along with its line's command |

Which phase does the deleting was not investigated. Nothing reachability
depends on changes: only stores nobody reads, with no side effects, go, so no
`reach` call and no value a later statement reads is lost. What does change is
the line mapping — a line can end up with no command at all — which matters
once queries name lines (§6), not while they name `reach` calls. A
consequence for fixtures: a construct under test must be *used*, or Soot may
delete it before translation sees it. `SootIrProviderSuite` pins the first two
rows.

Two more settings are for the humans reading `pag ir` and invariant maps, not
for correctness. **Probes and fixtures are compiled with `javac -g`**, and Soot
runs with `jb use-original-names:true` and `keep-line-number`: without the
debug information `-g` adds, locals come out as `l1` and `$stack7` instead of
`x` and `i`. The adversary's build uses `-g` too. *Decided — Shawn,
2026-09-29.*

**Soot may reuse a variable's name for an unrelated value.** In a probe whose
loop counter `i` is dead after the loop, Soot 4.7.1 produced
`i = valueOf(5L)` and `i = <BigInteger: TEN>` for two later comparison
constants. The Jimple is correct — `i` is dead, and the local now holds the
constant — but an invariant map showing `i ↦ [5,5]` after the loop is about a
different value than the loop counter. `javac` does not do this (the constant
stays on the operand stack), and it happens before names are applied (without
`-g`, the counter and the constant were both `$stack7`). Turning off
`jb.ulp`, `jb.lp`, `jb.cp` and `jb.a`, separately and together, did not change
it; the cause is probably in Soot's bytecode front end or local splitter and
was not investigated further. Accepted as a known readability caveat, not a
correctness issue: nothing in the analysis depends on names.
*Decided — Shawn, 2026-09-29:* document and move on; revisit if it confuses a
real debugging session, or when Soot is replaced.

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

`IrProvider` also owns one location service the engine needs, because it is a
question about bytecode:

```scala
/** Is this a loop head? Decides where the engine widens. */
def isLoopHead(loc: Loc): Boolean
```

Historia's `findLineInMethod` (line → locations) and a bytecode-rewriting
`instrumentReach` were both in earlier drafts. Neither is needed while targets
are `reach` calls (§5.8): the target is found in the IR, and the marker is
already in the program. Line-based queries can return later as a front-end
service, when there is code we did not write.

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

**Format.** A comma-separated list of integers of any size, each with at most
one leading sign; whitespace around a value is ignored, and an empty or missing
property means no inputs. An empty value (`1,,2`, `1,2,`) or a non-integer is
rejected with the offending token named. Running out throws
`IllegalStateException` naming how many values there were. The parser is
`pag.probe.Inputs`, and the IR interpreter uses the same class, so it and the
JVM run cannot disagree about what a list means. *Decided — Shawn,
2026-09-26.*

**Why our own class.** The analysis matches the call by signature, so the
signature should be one we own and never changes, and `probe-lib` has no
dependencies so the Phase 9 containers stay JDK-only. The name avoids
`Instrumentation`, which collides with `java.lang.instrument.Instrumentation`.

**The meaning a domain implements.** `call(x, pag.probe.Rand.randInt(), [])`
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
| `$z := a.equals(b)` then `if $z == false goto t` | `nop` then `if a ≠ b goto t` |
| `$z := a.equals(b)` then `if $z != false goto t` | `nop` then `if a = b goto t` |
| `r := Rand.randInt()` | unchanged; lowering makes it a `Call` |

(Soot writes the `equals` test against the boolean constant `false`, not `0`.
It also writes `javac`'s `ifeq` on an *`int`* as `$i == false`, so a Soot
`BooleanConstant` does not by itself mean a boolean: the front end gives a
constant in a comparison the type of the local it is compared with, so these
reach lifting as `$z == false` for an `equals` temp and `$i == 0` for a
`compareTo` temp. Observed on Soot 4.7.1, 2026-09-29/30.)

**Constant substitution.** Constants reach comparisons through temps —
Jimple call arguments must be locals or literals, so
`x.compareTo(BigInteger.ZERO) > 0` lifts to `$r := 0; … if x > $r`. Running
backward, a domain meets `x > $r` before `$r := 0`, so a *non-relational*
domain such as plain intervals can use neither and proves nothing about a
comparison with a literal — which is nearly every target in a v1 probe. So the
last step of lifting propagates constants **within a basic block**: a use of a
local `r` is replaced by `c` when the nearest earlier assignment to `r` in the
same block is `r := c`. Basic blocks start at jump targets and after jumps. The
assignment stays in place, so locations are unchanged, and any other use of `r`
still reads it; that is what makes the rule sound whatever else uses `r`, with
no counting of uses. Only value positions are rewritten, never assignment
targets.

History: decided 2026-09-26 in a single-use form; reversed 2026-09-30 to keep
complexity down, on the view that the domain should handle it; restored
2026-09-30 in the block-propagation form, once it was clear the cost of *not*
doing it falls on every domain — a generated one included — which would then
have to relate variables to prove anything. *Decided — Shawn, 2026-09-30:* start
the experiments with the simplest domains (`experiments.md`, E1) and add
relational domains as a later rung.

**Lifting never adds or removes a command.** A fused compare leaves a `Nop` where
the temp was assigned, so every `Loc` still names the same bytecode unit it did
after loading. `Method.lines`, `isLoopHead` and query resolution all key on
command indices, and error messages and the invariant map print through them.

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

### 5.8 Targets: `reach`

A probe marks the locations it asks about with calls to one method:

```java
package pag.probe;

public final class Reach {
    /** Prints REACHED-<id> and has no other effect. */
    public static void reach(int id) { ... }
}
```

It lives in `probe-lib` beside `Rand`. `reach` touches no local, no field and
no control flow; its only effect is one line on stdout. That makes it both
halves of the reachability check at once:

- **The analysis finds it** as an ordinary `InvokeStmt` with a literal
  argument. `Reachable(7)` targets the `pre` location of the `reach(7)` call,
  and lowering turns the call itself into a `skip` edge (§5.3), so a domain
  never sees it.
- **The run reports it**: `REACHED-7` on stdout is the marker, with no
  rewriting of the class file.

So the class file analysed and the class file executed are **the same file**,
not a copy with a `println` inserted. That removes a bytecode-rewriting pass
from the trust base, and with it the problem of mapping each `Loc` back to a
bytecode offset.

**Why no explicit flush.** `reach` is a single `System.out.println`, and that
line has reached the OS before `reach` returns: JDK 21 creates `System.out`
with `autoFlush` on (`System.newPrintStream`), and `println` flushes when it is
(`PrintStream.implWriteln`). So a marker survives an uncaught exception or a
`SIGKILL` immediately after it — both routine, since running out of inputs
throws and the runner kills probes that loop. `probe-lib`'s `ProbeRunTest`
checks both in a real JVM, and fails against a `reach` that writes through its
own buffered stream. Keep `reach` a single `System.out.println`.

Profile rules: `pag.probe.Reach.reach` is always an admitted callee; its
argument must be an `int` literal; each id appears at most once in a program.
*Decided — Shawn, 2026-09-26.*

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
reusable result: ask about a different `reach` call and you run a different
fixed point, from scratch.

The query form, adapted from Historia's `InitialQuery.Reachable(sig, line)`:

```scala
sealed trait Query
final case class Reachable(id: Int) extends Query   // the reach(id) call
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

Resolution looks up the one `reach(id)` call in the lowered `Cfg`, and seeds
`I(pre) = ⊤` there and `⊥` everywhere else. The query's abstract state is `⊤`
— Historia uses `State.topState` — which is what keeps `[inductive]` in §7
trivial.

A line-based query (`Reachable(method, line)`, Historia's form) is the natural
next form once targets are in code we did not write. A line then maps to
**several** locations and the query is their disjunction, because one line can
hold statements with no dominance relation between them (`if (a) x(); else
y();`); Historia's `makeReach` takes `locs.head` and would silently answer a
different question. Seeding `⊤` at every one of them handles it with no extra
machinery.

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

Because §5.5 reads the IR from a class file and the marker is the program's own
`reach` call (§5.8), the artifact analysed and the artifact executed are the
same class file, byte for byte. There is no translation and no rewriting
between them.

A probe can be executed two ways, on the same inputs. They are not steps in
sequence; they run different forms of the program, and comparing them is what
checks the translation between those forms. (Earlier drafts called them
Stage 1 and Stage 2; renamed 2026-09-30, *Decided — Shawn*, since "stage"
suggested an order.)

**The IR interpreter.** `engine/harness` interprets the lowered `Cfg` — the
form a domain analyses, which the JVM never sees — and records visited
locations and the `reach` ids it passes, answering each `randInt` call from
the same input list and stopping when it runs out, as `Rand` does. Fast,
deterministic, no process launch, and useful for debugging. It is our own
code, so a disagreement with the JVM run is a bug in it or in the translation
it runs.

**The JVM run — run the class file.** `java -Dpag.inputs=<values> -cp
<classes>:probe-lib.jar Probe`, and grep stdout for `REACHED-<id>`. The
executor is the JVM and the program is unmodified, so the evidence depends on
nothing this project wrote beyond `reach`'s one `println` and `Rand`.

`harness`'s `JvmRun` implements it: a fresh JVM per run, killed after a timeout
(10 s by default), and only lines of exactly the form `REACHED-<id>` count.

**The runner must never read stdout after killing a probe.** JDK 21's
`ProcessImpl.destroy` closes the parent's end of the child's stdout as soon as
it signals the child, so output not yet read is lost — possibly the marker,
which would silently lose a counterexample. Redirect stdout to a file (as
`ProbeRunTest` does) or drain it concurrently. The JVM run is the verdict of
record; the IR interpreter exists for checking the translation, for speed and
for inspection, not for adjudication.

The two agree by construction: the IR interpreter computes in `BigInt`, and
the program computes in `BigInteger`, so neither wraps.

The JVM run is what makes the README's claim ("does not depend on any component
of this project being correct") literally true. The **IR–JVM cross-check** —
both run on a corpus of fixtures, their `reach` sequences required to be equal —
is a cheap standing test of the translation (Phase 2b).

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
reaching run, so reading cannot substitute for evidence. *Decided — Shawn,
2026-09-26:* adversaries inspect the domain; we want them to succeed whenever
possible.

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
pag ir      <classes> [--cfg] [--no-lift] [--no-enforce]  what the front end produced
pag run     <classes> [--inputs 3,-7] [--trace] [--step-limit N]   run on the IR interpreter
pag analyze --domain <jar> --classes <dir> --reach ID   verdict and invariant map
pag check   --domain <jar> --classes <dir> --reach ID   analyze, then try to falsify
```

`--reach 7` is the `Reachable(7)` query: the `reach(7)` call (§5.8). `--json` works on all of
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
        reach(1);                                  // the target
    }
}
```

```
$ pag analyze --domain domains/interval/out/interval.jar \
              --classes probes/c07/out --reach 1

classes   probes/c07/out            26 locations · profile bigint-main-v1
domain    interval-ref 0.1.0        api 0.3.1
query     Reachable(1) → pre(10)

  entry                             ⊥
  pre(1)   x = Rand.randInt()       ⊥
  pre(4)   if x <= 0 goto 11        ⊥
  pre(6)   y = x + 1                x ↦ (-∞,-2]
  pre(9)   if y >= 0 goto 11        y ↦ (-∞,-1]
  pre(10)  reach(1)                 ⊤                  ← target
  pre(11)  return                   ⊥
  (post locations, nops and constant temps elided)

worklist    12 iterations · no widening · 4ms
certified   27/27 edges inductive
entry       I(entry) = ⊥

REFUTED
```

The constants in each comparison reach the domain as constants because lifting
substitutes them within a basic block (§5.7); temps are elided above.

`--record full` additionally writes the derivation graph (§8) and, on an
`Alarm`, renders a `CandidateTrace` from the entry to the query. On an
`Inconclusive` the report names the `Incomplete` case and how many locations
were left `Unexplored`.

### `check` is the reachability check

The whole of §9 in one invocation, and what Phase 5's done-when exercises:

```
$ pag check --domain domains/interval/out/gen-04.jar \
            --classes probes/c08/out --reach 1 --inputs 5

analysis    REFUTED            9 iterations · 3ms
execution   REACHED-1          inputs [5] · 12ms

UNSOUND — the domain refuted reach(1), but the program reaches it
reaching run   probes/c08/Probe.java  inputs [5]
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
failures back → repeat. Plus `mutants`: score an adversary against the mutant
corpus, which needs the adversary and therefore lives here too.

It is the only writer of `domains/` (§3): it creates `domains/<id>/` for each
generated domain worth keeping, records each rejection under
`rejections/<r>/` with its `run.json`, updates `domain.json`'s status, and adds
rejected domains to `corpora/mutants.txt`.

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

Each phase names a deliverable and a done-when that is a runnable check. Phase
numbers are names, not the order of work.

### Order of work

The project's real unknowns are whether a small model can write a sound,
non-trivial *backward* domain (Phase 10) and whether an adversary can break one
(Phases 8 and 11). Everything else is infrastructure we are fairly sure we can
build. So the order reaches a crude version of those first and returns for the
rest:

1. **Phases 0, 1, 2a, 2b, then constant substitution (§5.7), then 3, 4, 5** —
   a program loads, runs, is analysed with the interval reference domain, and a
   human-written reaching run rejects a broken domain.
2. **Crude Phase 10** — a generator loop in `campaign/` with no containers:
   the generated jar goes on `pag`'s classpath by path, and "the corpus" is a
   handful of hand-written probes. *Done when* a generated domain compiles,
   loads and refutes one target.
3. **Crude Phases 11 and 8** — an adversary proposing probes against three or
   four hand-written mutants and one generated domain. *Done when* a kill rate
   is reported, however rough.
4. **Phases 4.5, 6, 7, 9**, then the full versions of **8, 10, 11**.

What the crude and full Phases 8, 10 and 11 are *for* is set out in
`experiments.md`: a complexity ladder measuring where one-shot domain
generation breaks and how much feedback each rung needs (E1), the diminishing
returns of counterexamples (E2), and the same across several open-weight models
(E3). *Decided — Shawn, 2026-09-30.*

*Decided — Shawn, 2026-09-26.* One cost to be aware of: until Phase 9,
generated domain code runs unsandboxed on the development machine, with the
developer's permissions. It comes from a local model and runs only inside
`pag`, but it is arbitrary code; Phase 9 is what contains it.

### Phase 0 — skeleton
sbt multi-project for `engine`: `api` and `probe-lib` (pure Java), plus
`frontend-soot`, `core`, `harness`, `cli` in Scala 3, with `cli` depending on
`frontend-soot` at runtime only (§5.5). There is no CI: this is a one-person
project, and `sbt test` run locally before each review is the gate
(`CLAUDE.md`). *Decided — Shawn, 2026-09-26.*
*Done when:* every module builds, a placeholder test passes in each, and an sbt
check fails if Soot is on the compile classpath of any module but
`frontend-soot`.

### Phase 1 — the contract
Write the IR in `engine/ir` (§5.1, §5.3) and the domain contract and
vocabulary in `engine/api` (§5.4). Get them reviewed before building on them.
*Done when:* the api compiles separately (glossary): a stub domain compiles with
`javac` against `pag.api` alone, a control that uses anything else does not,
and `jdeps` reports no dependency outside `java.*` and `pag.api.*`. Checked by
`SeparateCompilationTest`.

### Phase 2a — load, check, lift, lower, print
The first milestone: load a simple Java program and inspect its CFG from the
command line. A cleaned IR in `engine/ir` extracted from Historia's `IRWrapper` (§5.1);
`SootIrProvider` with `load` only (§5.5); the profile check
(§5.2); lowering (§5.3); `pag ir`. Most of the work is separating IR translation
from the APK, callback and class-hierarchy machinery `SootWrapper` currently
mixes into it. `isLoopHead` waits for Phase 4, which uses it.
Lifting (§5.7) is in this phase too, since the CFG is unreadable without it.
*Done when:* a fixture `.java` using `Rand.randInt`, `reach`, `BigInteger`
arithmetic, a loop and a branch, compiled in the test with `javax.tools`, loads through
`SootIrProvider` and `pag ir --cfg` matches a golden file — **and** fixtures
that read `args`, call `divide`, call a non-`BigInteger` method, use `null`,
declare a second method, or use a field each exit with code 2 and a message
naming the construct, its line and the profile setting — **and** the `args`
fixture loads under `enforce = false` — **and** under `lift = false` the same
golden fixture prints `BigInteger` calls with its location numbering unchanged
— **and** no module but `frontend-soot` can compile against `soot.*`.

Preceded by four changes for Phases 0 and 1: (1) the multi-project build;
(2) `probe-lib`'s `Rand` and `Reach` — trust base, so on its own; (3) the
Scala IR in `engine/ir`, then the Java domain vocabulary in `api`; (4) the
`Domain` interface and the `jdeps` check. Phase 2a itself was
planned as the eight changes below; the trust-base rows (3–7) are held to about
100 lines each under `CLAUDE.md`, so expect them to split further, each split
proposed before it is written:

| # | change | tested by |
| --- | --- | --- |
| 1 | multi-project build, empty modules, `Greeter` removed; `probe-lib`'s `Rand` and `Reach` | `sbt test` green in every module; `Rand` and `Reach` unit tests |
| 2 | IR types in `engine/ir`; domain vocabulary in `api` | constructor-check tests; a `javac` stub compiles against `api.jar` |
| 3 | `SootIrProvider`: straight-line code and `Return`; in-test fixture compilation | fixture → expected `Cmd` list |
| 4 | translation of branches, calls, parameter binding; `Untranslatable` | one fixture per construct |
| 5 | profile check | a passing and a failing fixture per rule |
| 6 | lifting; constant substitution as a follow-up change (§5.7) | one test per table row |
| 7 | lowering to `Cfg` with `pre`/`post`/entry/exit | one test per table row |
| 8 | `pag ir --cfg` | golden file |

### Phase 2b — the IR interpreter
The IR interpreter (§9), a minimal JVM run, and `pag run`, which runs the IR
interpreter only; a `--jvm` option can come later. Query resolution for
`--reach` moves to Phase 4, where `pag analyze` is its first user. *Decided —
Shawn, 2026-09-30.*
*Done when:* `pag run --inputs …` on a fixture reproduces a visited-location
sequence recorded in a fixture file — **and** the **IR–JVM cross-check** is part
of `sbt test`: for every fixture, written with a `reach` call after nearly every
statement, run it under the IR interpreter and as a JVM run with the same
inputs, and require the `reach` ids the IR interpreter passes to equal the `REACHED` ids the JVM run
prints, in order. This one test checks trust-base claims A and B (`misc.md` §1)
at once.

### Phase 3 — the reference interval domain
Written by hand, in Java, as a fixture. Also the worked example shown to the
generator, so write it the way generated code should look. Includes the
`build.sh` for `domains/interval`, running `javac` against `api.jar` alone,
which must succeed with networking disabled.
*Done when:* the six transfer cases in `README.md` pass as unit tests.

### Phase 4 — the analysis engine
First, on its own, the domain-vocabulary converter (§5.4), with one test per
case, and resolution of `--reach` queries against `Lowered.reachSites` (§6,
moved from Phase 2b). Then the worklist, invariant map, widening, iteration limit, and the
certifier as a separate pass. The recorder interface (§8) with `NullRecorder` only — the graph comes
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
the reasoning required, so the score measures capability rather than luck: the
grades are the rungs R0–R6 of `experiments.md` E1.
*Done when:* the reference domain produces a stable proof count, and the graded
targets separate it from a deliberately weaker domain.

### Phase 8 — the mutant corpus and adversary calibration
Deliberately unsound domains: a transfer that narrows too hard, an `entails` that
is too permissive, an `isBottom` that fires on a satisfiable state, a `widen`
that drops a case. Measure what fraction any given adversary breaks, and at what
budget.

**Calibrate on rejected domains too.** Hand-written mutants are small edits
that an adversary reading the source may spot by pattern, so they can overstate
its strength against real bugs. Every domain the adversary rejects is a
known-unsound domain with a realistic bug; it joins the mutant corpus (§3), and
a *fresh* adversary session — shown the domain, never the earlier
counterexample — tries to rediscover a reaching run. Each attempt is recorded
in the domain's `calibration` history. The adversary never sees the reference
domain's source, so it cannot find a mutant by comparison.
*Done when:* the campaign driver reports kill rate per mutant on demand, with
hand-written and rejected mutants reported separately. This is the number
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
This is E1's first data point (`experiments.md`): rung R0–R1, one shot, one
model; the attempt records must already carry what E1 needs (prompt version,
sample, each feedback round's kind and outcome).

### Phase 11 — the adversary agent
In `campaign/`: read domain → `pag analyze` for refuted targets → propose probes
→ `pag check` → report. Also `mutants`, which scores an adversary against the
seeded corpus and needs the adversary, so it belongs here rather than in `pag`. Score against the Phase 8 mutants first, then turn it on generated
domains.
*Done when:* the adversary's kill rate on mutants is reported, and it finds at
least one genuine reaching run against a generated domain.

### Later
Real-language backends beyond Java; a domain exercising
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
   this empirically rather than by guess — `experiments.md` E2 is that
   measurement.
2. ~~**Does the adversary see the domain source?**~~ *Decided:* yes (§10).
3. **Reaching-run minimization.** A found run may be large. Shrinking it before
   it becomes generator feedback is probably worth it, and is standard
   delta-debugging.
4. ~~**`S` or `Set<S>` from `transfer`.**~~ *Decided:* `S` for now (§5.4);
   pulling disjunction out for parallelism is a possible later change.
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
    unchanged. *Decided 2026-09-26:* yes. *Reversed 2026-09-30:* no.
    *Restored 2026-09-30:* yes, as propagation within a basic block (§5.7), so
    the first experiments can use interval domains.
15. ~~**Method identity.**~~ *Decided:* methods are identified by `MethodId`,
    the fully qualified signature (§5.1). The profile's `callees` list still
    matches by class and name (`MethodId.qualifiedName`), which is exact while
    no admitted callee is overloaded; it moves to full signatures when one is.
16. **Domains that use libraries.** A domain may use a solver internally (§4),
    and the Phase 6 loader is child-first so domains can carry their own
    dependencies — but the Phase 9 container is JDK-only with no network, and
    the domain build is `javac -cp api.jar`, so an agent cannot actually obtain
    one. Allowing, say, Z3 means a curated read-only library set in the
    container, a build classpath and packaging that include it, and, for native
    libraries, accepting that a native crash takes down the `pag` JVM (the
    §12 process boundary still contains it). Running each domain in its own
    container is one way to give it such dependencies. Deferred: nothing before
    Phase 9 depends on it, and crude Phase 10 will show whether a small model
    reaches for a solver at all.
17. **Programs without a `main` of their own.** `IrProvider.load` accepts a
    directory holding exactly one class with `public static void main(String[])`
    (§5.5), and rejects anything else. Code written for a framework — Android,
    Temporal — never declares its own `main`, and spreads over many classes:
    the framework owns the entry point and calls into client code. Analysing
    it means relaxing both rules and supplying an entry, which is the
    framework-modelling problem `README.md`'s Future ideas raises and Historia
    addressed with callback and callin locations (the `Internal` prefix on
    `InternalMethodEntry` leaves room for them). Marked by a TODO in
    `SootIrProviderSuite`. Out of scope for v1.
18. **Using the IR interpreter adversarially.** The IR–JVM cross-check
    (Phase 2b) is differential testing on a fixed, hand-written corpus. Three
    extensions, in order of cost:
    - **Triage every rejection.** When a JVM run reaches a location a domain
      refuted, also run the IR interpreter on the same inputs. If it reaches
      the location too, the domain is unsound; if it does not, the `Cfg` the
      domain analysed does not behave like the program — a translation bug,
      not the domain's fault — and the domain should not be rejected for it.
      One cheap run per rejection (Phase 5, `pag check`).
    - **Cross-check every adversary probe.** Each probe the adversary writes
      (Phase 11) is a deliberately tricky program; running it through both
      executors turns the adversary's whole output into translation tests.
    - **A translation adversary.** Generate programs and inputs to find
      disagreements between the two executors, as Csmith did for C compilers.
      Needs no domain; finds bugs in loading, lifting and lowering.
    *Decided — Shawn, 2026-09-30:* likely very useful, but none of it before
    the first experiment.
19. **A relational reference domain.** The Phase 3 reference is an interval
    domain, which suffices for rungs R0–R4 of `experiments.md` E1. R5 and R6
    (inputs related to each other; nested loops) need a relational domain —
    zones (difference-bound matrices, Miné 2001) are the smallest that relates
    variables — and so will a reference for them. Write it when E1 reaches R5.
