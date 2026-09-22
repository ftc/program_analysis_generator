# program-analysis-generator

The goal of this project is to build a structure by which large language models may be used to generate the difficult to write pieces of a program analysis and rely on strict correctness tests to avoid unsoundness.
The fundamental principle of this project is that every step of a program analysis must adhere to the soundness criteria.
Implementations of each small scale piece of a program analysis must have direct ways of testing against observed runtime behavior.

The analysis works in two phases:
1. A forward, over-approximate, flow-insensitive analysis (e.g. an anderson's analysis or steensgaard analysis).  This pass computes a rough approximation of the call graph and the aliasing relationships between variables, pointers, etc.
2. A backwards analysis that starts at the location of a defect capturing the failure condition and works backwards through the execution of the application to reach a proof if the initial state of the program can be excluded from a fixed point or an alarm if the initial point is reached.  The backward analysis can be over-approximate to produce a proof or under-approximate to produce a must-witness. In either case it is guided by the forward over-approximate analysis.

**Humans never write transfer functions**
A key part of this project is that (ideally) humans should never directly look at the abstract domain implementations.
The key insight that should make this possible is that the soundness of the abstract domain defines a way to test every step of the transfer function.
Each abstract domain must implement a "contains" method that checks an observed state for containment in an abstraction.
Abstract domains are tested by running sample programs and observing the pre and post states of commands then testing the transfer function against the soundness condition.

## Worked example: IMP and the interval domain

An analysis here is assembled from two layers.

- A **fixed-point layer**: domain-independent judgments that build an invariant
  map backwards from an error condition and check that it is inductive. Written
  once, by hand, and proved once.
- A **transfer layer**: one function per command per abstract domain. Generated,
  and accepted only once it passes its soundness condition against observed
  executions.

The interface between the layers is three conditions on the domain, all of them
stated in terms of a single concretization test. This example works through
both layers for IMP and the interval domain.

References are to Shawn Meier's dissertation: Chapter 5 §5.1 for the program
representation, Chapter 4, Lemma 1 for the transfer soundness condition, and
Chapter 4, Fig. 4.2 for the fixed-point judgments.

### Programs (Ch. 5 §5.1, Fig. 5.1)

A program `p` is a set of transitions over an unstructured control-flow graph.
Each transition `ℓ —c→ ℓ'` carries a source location, a command, and a target
location; execution starts at `ℓ_init`.

```
programs     p ::= ∅ | p, (ℓ —c→ ℓ')
commands     c ::= x := a | assume b
expressions  a ::= n | x | a + a | a * a
conditions   b ::= a < a | a = a | ¬b
```

Structured control flow compiles into this shape: `if` and `while` become
branching transitions guarded by `assume`. An assertion compiles to the
reachability of an error location `ℓ_err`.

```scala
enum Expr:
  case Lit(n: BigInt)
  case Var(x: String)
  case Add(l: Expr, r: Expr)
  case Mul(l: Expr, r: Expr)

enum Cond:
  case Lt(l: Expr, r: Expr)
  case Eq(l: Expr, r: Expr)
  case Not(b: Cond)

enum Command:
  case Assign(x: String, a: Expr)
  case Assume(b: Cond)
```

### Concrete states and what the debugger reports

A concrete state `σ` maps variables to integers, so a snapshot is just the
frame's locals — `StackFrame.getValues` under JDI.

```scala
type Store = Map[String, BigInt]     // σ
```

The concrete semantics `σ --c--> σ'` is the expected one: `x := a` rebinds `x`
to the value of `a` in `σ` and leaves every other variable alone; `assume b`
steps only when `b` holds in `σ`, leaving `σ` unchanged.

### The interval domain

```
intervals        ι ::= [l, u]        l ∈ ℤ ∪ {-∞}, u ∈ ℤ ∪ {+∞}, l ≤ u
abstract states  σ̂ ::= ⊥ | (x ↦ ι, ...)
```

```scala
enum Bound:
  case NegInf, PosInf
  case Fin(n: BigInt)

/** [lo, hi] with lo ≤ hi. */
final case class Interval(lo: Bound, hi: Bound):
  def holds(n: BigInt): Boolean = lo <= Bound.Fin(n) && Bound.Fin(n) <= hi

enum AbsState:
  case Bottom
  case Env(at: Map[String, Interval])   // a variable absent from `at` is [-∞, +∞]
```

A variable absent from the map is unconstrained, so `Env(Map.empty)` is `⊤` and
needs no separate constructor.

### `contains` — the concretization test

`σ ⊨ σ̂` holds when `σ̂` is not `⊥` and every variable's value lies in its
interval. Small enough to state in full:

(Shawn's note: there may be something we can do later where the contains can be considered consistent with sufficient testing as well, but this is a reasonable assumption for now)

```scala
/** σ ⊨ σ̂ */
def contains(sigma: Store, sigmaHat: AbsState): Boolean = sigmaHat match
  case AbsState.Bottom  => false
  case AbsState.Env(at) => at.forall((x, i) => i.holds(sigma(x)))
```

This is the trusted kernel. It is the one component a human reads, because
every other component is checked against it, and it is short enough that
reading it is cheap: no fixed point, no case analysis, no search.

### The triple and its soundness condition

A backward triple `⊢ {P'} c {P}` reads right-to-left: *if an execution of `c`
reaches a post-state satisfying `P`, then the pre-state of that execution
satisfies `P'`.* A transfer function computes `P'` from `c` and `P`:

```scala
def transfer(c: Command, post: AbsState): AbsState
```

Its soundness condition is that no concrete step escapes the computed
pre-condition:

```
  σ' --c--> σ   and   σ ⊨ post   ⟹   σ' ⊨ transfer(c, post)
```

This is Ch. 4, Lemma 1 (*hoare triple soundness*, p. 89), with the state
specialized to a variable store. Lemma 1 as stated there also carries a
specification parameter for framework behavior, which this domain does not use:

> If `⊢ {P'} c {P}` and `σ' --c--> σ` such that `σ ⊨ P`, then `σ' ⊨ P'`.

That condition is the complete specification of a transfer function. It states
exactly what the fixed-point layer below consumes, and it is phrased in terms
of concrete steps and `contains`, so it is directly executable as a test. Those
two properties are why the transfer layer is specified by a condition: the same
statement serves as the contract the proof relies on and as the check the
harness runs.

### The transfer function

Backward over `x := a`: after the command `x` holds the value of `a` evaluated
*before* it, and every other variable is unchanged. So the post-condition's
interval for `x` becomes a constraint on the operands of `a`, and `x` itself is
unconstrained in the pre-state. Backward over `assume b`: the store is
unchanged, so the pre-condition is the post-condition refined by `b`.

```scala
/** Backward transfer. Must satisfy the soundness condition above. */
def transfer(c: Command, post: AbsState): AbsState = (c, post) match
  case (_, AbsState.Bottom) =>
    AbsState.Bottom
  case (Command.Assign(x, a), AbsState.Env(at)) =>
    // Constrain a's operands to land in x's post-interval, with x itself freed.
    narrow(a, at.getOrElse(x, Interval.Top), AbsState.Env(at - x))
  case (Command.Assume(b), env) =>
    // assume does not write the store; it only blocks.
    refine(b, env)

/** Shrink `env` so every store it contains evaluates `a` within `target`. */
def narrow(a: Expr, target: Interval, env: AbsState): AbsState = a match
  case Expr.Lit(n)  => if target.holds(n) then env else AbsState.Bottom
  case Expr.Var(y)  => env.meet(y, target)
  case Expr.Add(l, r) => ...   // subtract each side's range from `target`
  case Expr.Mul(l, r) => ...   // sign cases; division by an interval spanning 0
```

Five cases worth checking by hand, and the ones a generated implementation
tends to get wrong:

| command | post | correct pre |
| --- | --- | --- |
| `x := 5` | `x ↦ [0,10]` | `⊤` |
| `x := 5` | `x ↦ [6,10]` | `⊥` |
| `x := x + 1` | `x ↦ [0,10]` | `x ↦ [-1,9]` |
| `x := y` | `x ↦ [0,10], y ↦ [5,20]` | `y ↦ [5,10]` |
| `assume x < 10` | `x ↦ [0,10]` | `x ↦ [0,9]` |

The fourth row is the instructive one: `x`'s post-interval has to be
intersected into `y`, because `y` is what the assignment read. The two natural
ways to get it wrong behave differently under the test. Returning
`y ↦ [5,20]` — forgetting the intersection — is weaker than the correct answer
and therefore still sound, so the test passes it; that is imprecision, measured
separately. Returning `y ↦ [5,9]` — narrowing too far — is unsound, and a run
with `y = 10` exhibits it: the post-state `x = 10, y = 10` satisfies the
post-condition, while the pre-state `y = 10` falls outside the computed
pre-condition.

### The fixed-point judgments (Ch. 4, Fig. 4.2)

The transfer function is applied repeatedly, backwards, until an invariant map
`I` from locations to abstract states stops changing. These judgments say when
that map is a proof.

An edge is inductive when the pre-condition it computes is already covered by
the invariant at its source location:

```
        transfer(c, I(ℓ')) ⊑ I(ℓ)
  ─────────────────────────────────────  [edge-inductive]
          I ⊢ (ℓ —c→ ℓ') ok
```

Ch. 4's corresponding rule, `a-app-step`, wraps this in a rule of consequence
on both sides. Having a transfer function collapses that: it already produces
the pre-condition, so only the one entailment remains.

The map is an inductive invariant for an error condition `P` at `ℓ_err` when it
covers `P` and every transition is inductive:

```
  P ⊑ I(ℓ_err)      I ⊢ t ok  for all t ∈ p
  ──────────────────────────────────────────  [inductive]
            p ⊢ I  may-witness  P
```

And the error condition is refuted when the invariant at the initial location
excludes the initial state:

```
  p ⊢ I may-witness P      σ_init ⊭ I(ℓ_init)
  ───────────────────────────────────────────  [refute]
          p ⊢ P  unreachable
```

`I` is computed by the standard worklist (Ch. 4 §4.1.2): initialize `I(ℓ_err)`
to the error condition and every other location to `⊥`, then repeatedly pop a
transition, apply `transfer` to the state at its target, and join the result
into the state at its source, re-enqueuing predecessors on change. Intervals
have infinite ascending chains, so loop heads need widening.

Widening and the worklist order affect termination and precision, not
soundness. Soundness comes from re-checking `[inductive]` against the map the
algorithm settles on, so the search may be as heuristic as it likes while the
result stays certified.

This layer is sound for any domain that supplies three things:

| | condition | who writes it |
| --- | --- | --- |
| `transfer` | `σ' --c--> σ` and `σ ⊨ post` ⟹ `σ' ⊨ transfer(c, post)` | generated |
| `⊑` | `A ⊑ B` and `σ ⊨ A` ⟹ `σ ⊨ B` | hand-written |
| `excludesInit` | `excludesInit(A)` ⟹ `σ_init ⊭ A` | hand-written |

All three are phrased in `contains` and all three are testable against observed
states. Only the first is generated, and only the first varies per command.

### The generate-and-test loop

The soundness condition quantifies over concrete steps, which is what a
debugger enumerates:

1. **Implement.** The model writes `transfer` for one command against the
   condition.
2. **Attack.** The model writes probe programs aimed at breaking it — an
   assignment whose source variable is also constrained in the post-condition,
   arithmetic that overflows an interval bound, a loop whose guard narrows a
   range, expressions mixing signs under multiplication.
3. **Observe.** Run each probe under JDI with breakpoints on both sides of the
   command, recording `(σ', σ)` at every hit.
4. **Check.** For post-conditions that contain `σ`, assert that
   `transfer(c, post)` contains `σ'`.
5. **Refine or reject.** A failure returns the command, the post-condition, the
   computed pre-condition, and the excluded concrete pre-state.

```scala
class AssignSoundness extends munit.FunSuite:
  test("x := a transfer is sound on observed steps") {
    for
      probe          <- probes                       // step 2
      (c, pre, post) <- Debugger.observeSteps(probe) // step 3
      postAbs        <- Abstraction.containing(post) // any σ̂ with post ⊨ σ̂
    do
      val preAbs = transfer(c, postAbs)              // step 4
      assert(contains(pre, preAbs),
             s"unsound: $c\n  post ⊨ $postAbs\n  pre ∉ γ($preAbs)")
  }
```

### The assumption this rests on

> **Testing adequacy.** For a transfer function small enough to handle one
> command in one domain, a reasonable set of generated probes exercises enough
> concrete steps that any violation of the soundness condition appears in at
> least one observed step.

This is an assumption of the technique, not a theorem, and it is what stands in
for a proof of each transfer function. It is the reason the command language is
kept small and the reason transfer functions are written one command at a time:
the assumption is credible exactly to the degree that a modest probe set can
cover the function's behavior. The fixed-point layer is proved once and for
all, so this assumption is the whole exposed surface.

Two consequences. The loop catches unsoundness only — a transfer returning `⊤`
passes every probe and proves nothing — so precision is measured separately, by
whether the fixed point reaches an `I(ℓ_init)` that excludes `σ_init`. And a
transfer unsound on a path no probe takes passes, which makes step 2 as
important as step 1.

Scala 3 (LTS 3.3.6) project built with sbt 1.11.7.

## Layout

```
build.sbt                     build definition
project/build.properties      sbt version
src/main/scala/pag/Main.scala entry point (pag.Main)
src/test/scala/pag/           MUnit test suites
```

## Setup on a new machine

> Windows is not supported. Use macOS or Linux (WSL2 counts as Linux).

You only need a JDK and sbt. The Scala compiler, the sbt version pinned in
`project/build.properties`, and every library dependency are downloaded
automatically on the first build.

### 1. Install a JDK (21 LTS recommended)

```sh
# macOS (Homebrew)
brew install openjdk@21

# Debian / Ubuntu
sudo apt install openjdk-21-jdk
```

Check that it is on the `PATH`:

```sh
java -version   # expect 21.x (17+ also works)
```

If you manage several JDKs with `jenv`, `sdkman`, or `asdf`, select 21 for this
directory before building — for example `jenv local 21`.

### 2. Install sbt

```sh
# macOS (Homebrew)
brew install sbt

# Debian / Ubuntu
sudo apt install sbt        # or: https://www.scala-sbt.org/download
```

An alternative that installs sbt, scala, and scala-cli together is
[Coursier](https://get-coursier.io/docs/cli-installation): `cs setup`.

The `sbt` command is only a launcher — it reads `project/build.properties` and
fetches sbt 1.11.7 itself, so a different launcher version is fine.

### 3. Get the code and build

```sh
git clone <repository-url> program_analysis_generator
cd program_analysis_generator
sbt test
```

The first run downloads sbt, the Scala 3.3.6 compiler, and MUnit into
`~/.cache/coursier` (`~/Library/Caches/Coursier` on macOS) and takes a few
minutes. Later builds start in seconds. A successful setup ends with:

```
[info] Passed: Total 3, Failed 0, Errors 0, Passed 3
[success] Total time: ...
```

Then confirm the entry point runs:

```sh
sbt "run Scala"   # prints: Hello, Scala!
```

### 4. Editor setup (optional)

- **VS Code**: install the [Metals](https://marketplace.visualstudio.com/items?itemName=scalameta.metals)
  extension, open this directory, and accept the "Import build" prompt.
- **IntelliJ IDEA**: install the Scala plugin, then *File → Open* this directory
  and choose the sbt project when prompted.
- **Formatting**: `.scalafmt.conf` pins scalafmt 3.8.3. Metals and IntelliJ pick
  it up automatically; from the command line, run `scalafmt` via
  [Coursier](https://scalameta.org/scalafmt/docs/installation.html).

### Offline / air-gapped machines

Dependencies resolve from Maven Central over HTTPS. Behind a proxy, set
`JAVA_OPTS="-Dhttps.proxyHost=... -Dhttps.proxyPort=..."`, or copy a warm
Coursier cache from a machine that has already built the project.

## Common commands

```sh
sbt compile          # compile
sbt test             # run the unit tests
sbt run              # run Main with no arguments
sbt "run Scala"      # run Main with arguments
sbt console          # Scala REPL with the project on the classpath
sbt ~test            # re-run tests on every file change
sbt clean            # delete build output under target/
```
