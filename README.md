# program-analysis-generator

The goal of this project is to build a structure by which large language models may be used to generate the difficult to write pieces of a program analysis and rely on strict correctness tests to avoid unsoundness.
The fundamental principle is that every generated artifact is under pressure
from both sides. Any unsound decision must be rejectable by observed behavior —
an execution that contradicts it. Any imprecise decision must show up as a false
alarm. The two pressures work differently: a witnessing execution makes
unsoundness a hard rejection, while nothing witnesses safety, so precision is
only ever a score. Both are needed, because proving nothing is sound and proving
everything is precise.

Soundness is therefore a requirement and precision an objective to maximize.
Perfect precision is not available — reachability is undecidable, so any sound
analysis must raise false alarms on some programs. The goal is to drive the
alarm rate down, not to zero.

The analysis works in two phases:
1. A forward, over-approximate, flow-insensitive analysis (e.g. an anderson's analysis or steensgaard analysis).  This pass computes a rough approximation of the call graph and the aliasing relationships between variables, pointers, etc.
2. A backwards analysis that starts at the location of a defect capturing the failure condition and works backwards through the execution of the application to reach a proof if the initial state of the program can be excluded from a fixed point or an alarm if the initial point is reached.  The backward analysis can be over-approximate to produce a proof or under-approximate to produce a must-witness. In either case it is guided by the forward over-approximate analysis.

**Humans never write abstract domains**
A key part of this project is that humans never write or read any part of an
abstract domain — not the transfer functions, not the state representation, not
the operations over it.

The key insight that makes this possible is that any question about program
state reduces to the reachability of a program location, and reachability is
observed by putting a print statement with a unique id at that location and
running the program. So an abstract domain can be rejected without inspecting
it and without any notion of what its states mean: if the domain proves a
location unreachable and the program prints that location's id, the domain is
unsound. Domains are tested by searching for such programs.

## Worked example: IMP and the interval domain

An analysis here is assembled from two layers.

- A **fixed-point layer**: domain-independent judgments that build an invariant
  map backwards from a target location and check that it is inductive. Written
  once, by hand, and proved once.
- A **domain**: a state representation plus the operations over it. Generated
  in full, and accepted only once an adversary has failed to break it.

Nothing in the domain is written by a human, and nothing in the domain is
trusted. The only soundness probe is whether a location the analysis proved
unreachable can be made to execute.

The property under test is **soundness**. Precision and termination are
deliberately outside the contract: a domain that proves nothing is sound and
useless, and a widening that never converges is caught by a step limit rather
than by a soundness check. Both are quality problems, measured separately.
Leaving them out keeps the contract small enough to be worth generating
against.

References are to Shawn Meier's dissertation: Chapter 5 §5.1 for the program
representation and the reduction of assertions to location reachability,
Chapter 4, Lemma 1 for the per-command soundness condition, and Chapter 4,
Fig. 4.2 for the fixed-point judgments.

Code below is Scala for readability. In the planned implementation the engine
is Scala 3 but the domain interface and the domains themselves are Java 21, so
a model writes Java; see `implementation_strategy.md`.

### Programs, and why reachability is enough

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
branching transitions guarded by `assume`. So does everything else we need.

- **An assertion** becomes the reachability of a location. "It is always
  possible to compile an assertion to the reachability of a location"
  (`goaldirinclogic.tex:9`), which is why error conditions are formalized as an
  *arbitrary* state at a location — `⊤` at `ℓ` in `chapter7.tex:40`.
- **A question about state** becomes the same thing. To ask whether `P` holds
  at `ℓ`, insert `ℓ —assume P→ ℓ_fresh` and ask whether `ℓ_fresh` is reachable.
- **A constrained initial state** becomes an `assume` on the entry transition,
  so `ℓ_init` can be taken to admit every store.

This is why the analysis needs no notion of an abstract state's meaning. Every
query is "is `ℓ` reachable," and every answer of "no" is falsifiable by a single
program that reaches `ℓ`.

### Concrete semantics

A concrete state `σ` maps variables to integers. `x := a` rebinds `x` to the
value of `a` in `σ` and leaves every other variable alone; `assume b` steps only
when `b` holds in `σ`, leaving `σ` unchanged.

```scala
type Store = Map[String, BigInt]     // σ
```

Note that `Store` appears nowhere in what a domain must supply. The domain never
sees a concrete state.

### The interval domain

```
intervals        ι ::= [l, u]        l ∈ ℤ ∪ {-∞}, u ∈ ℤ ∪ {+∞}, l ≤ u
abstract states  σ̂ ::= ⊥ | (x ↦ ι, ...)
```

```scala
enum Bound:
  case NegInf, PosInf
  case Fin(n: BigInt)

/** [lo, hi] with lo ≤ hi, under the obvious ordering on Bound. */
final case class Interval(lo: Bound, hi: Bound):
  def holds(n: BigInt): Boolean = Bound.leq(lo, Bound.Fin(n)) && Bound.leq(Bound.Fin(n), hi)

enum AbsState:
  case Bottom
  case Env(at: Map[String, Interval])   // a variable absent from `at` is [-∞, +∞]
```

A variable absent from the map is unconstrained, so `Env(Map.empty)` is `⊤` and
needs no separate constructor. The model chose this representation; a different
one — octagons, congruences, a disjunction of intervals — is a different domain,
and choosing among them is part of what is being generated.

### The transfer function

A backward triple `⊢ {P'} c {P}` reads right-to-left: *if an execution of `c`
reaches a post-state satisfying `P`, then the pre-state of that execution
satisfies `P'`.* A transfer function computes `P'` from `c` and `P`:

```scala
def transfer(c: Command, post: AbsState): AbsState
```

The condition that makes the fixed-point layer below sound is Ch. 4, Lemma 1
(*hoare triple soundness*, p. 89):

> If `⊢ {P'} c {P}` and `σ' --c--> σ` such that `σ ⊨ P`, then `σ' ⊨ P'`.

That `⊨` is a concretization relation the domain *has* — every domain has one —
but which is never written down in code and never checked directly. Lemma 1 is
the mathematical reason the layers compose; it is not the thing we test. What we
test is its consequence, below.

Backward over `x := a`: after the command `x` holds the value of `a` evaluated
*before* it, and every other variable is unchanged, so the post-condition's
interval for `x` becomes a constraint on the operands of `a` and `x` itself is
unconstrained in the pre-state. Backward over `assume b`: the store is
unchanged, so the pre-condition is the post-condition refined by `b`.

```scala
/** Backward transfer. */
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

Five cases worth working by hand to see what "correct" means here. Nothing
checks them directly — they are for the reader, and they are the kind of unit
test a model would reasonably write for itself while iterating:

| command | post | correct pre |
| --- | --- | --- |
| `x := 5` | `x ↦ [0,10]` | `⊤` |
| `x := 5` | `x ↦ [6,10]` | `⊥` |
| `x := x + 1` | `x ↦ [0,10]` | `x ↦ [-1,9]` |
| `x := y` | `x ↦ [0,10], y ↦ [5,20]` | `y ↦ [5,10]` |
| `assume x < 10` | `x ↦ [0,10]` | `x ↦ [0,9]` |

The fourth row is the instructive one: `x`'s post-interval has to be intersected
into `y`, because `y` is what the assignment read. The two natural ways to get
it wrong behave very differently. Returning `y ↦ [5,20]` — forgetting the
intersection — is weaker than the correct answer and therefore still sound; the
analysis just proves less. Returning `y ↦ [5,9]` — narrowing too far — is
unsound, and a program that reaches the target location with `y = 10` exhibits
it.

### The fixed-point judgments (Ch. 4, Fig. 4.2)

The transfer function is applied repeatedly, backwards, until an invariant map
`I` from locations to abstract states stops changing. These judgments say when
that map is a proof. This layer is the human-written part of the system.

An edge is inductive when the pre-condition it computes is already covered by
the invariant at its source location:

```
        transfer(c, I(ℓ')) ⊑ I(ℓ)
  ─────────────────────────────────────  [edge-inductive]
          I ⊢ (ℓ —c→ ℓ') ok
```

Ch. 4's corresponding rule, `a-app-step`, wraps this in a rule of consequence on
both sides. Having a transfer function collapses that: it already produces the
pre-condition, so only the one entailment remains.

Because every query is location reachability, the target carries no abstract
state — `I(ℓ_target)` starts at `⊤`, matching `chapter7.tex:40`:

```
  I(ℓ_target) = ⊤      I ⊢ t ok  for all t ∈ p
  ──────────────────────────────────────────────  [inductive]
            p ⊢ I  covers  ℓ_target
```

And the target is refuted when the invariant at the entry admits no store at
all. Since `ℓ_init` admits every store — initial constraints having been
compiled into an `assume` — "excludes the initial state" is exactly `⊥`:

```
  p ⊢ I covers ℓ_target       isBottom(I(ℓ_init))
  ───────────────────────────────────────────────  [refute]
          p ⊢ ℓ_target  unreachable
```

`I` is computed by the standard worklist (Ch. 4 §4.1.2): initialize
`I(ℓ_target)` to `⊤` and every other location to `⊥`, then repeatedly pop a
transition, apply `transfer` to the state at its target, and join the result
into the state at its source, re-enqueuing predecessors on change. Intervals
have infinite ascending chains, so loop heads need widening.

Widening and the worklist order affect termination and precision, not soundness.
Soundness comes from re-checking `[inductive]` against the map the algorithm
settles on, so the search may be as heuristic as it likes while the result stays
certified.

This layer works for any domain supplying the following. All of it is generated,
and none of it mentions a concrete state:

| | |
| --- | --- |
| `S` | the state representation |
| `top`, `bottom`, `isBottom` | the extremes, and the refutation test |
| `entails(a, b)` | `⊑`, used by `[edge-inductive]` |
| `join`, `widen` | merging at control-flow joins and loop heads |
| `transfer(c, post)` | the backward abstract semantics |

### The soundness probe

A refutation is a falsifiable claim about the real world: *no execution reaches
`ℓ_target`*. One program that reaches it refutes the refutation.

```
  p ⊢ ℓ_target unreachable       some run of p reaches ℓ_target
  ─────────────────────────────────────────────────────────────
                    the domain is unsound
```

Observing the right-hand premise costs a print statement:

```
ℓ_target:  print("REACHED-7f3a9c");
```

Compile, run, grep stdout. No debugger, no state inspection, no instrumented
interpreter, and no notion of what the domain's states mean. The evidence is
that the program printed the id, which is about as close to bedrock as evidence
gets and does not depend on any component of this project being correct.

Two properties of this probe are worth being explicit about.

**It runs one way.** Printed ⟹ reachable ⟹ the refutation was unsound. *Not*
printing proves nothing — the input may simply not have triggered it. So a
witness has to be exhibited, never argued.

**It is not a coarse test, it is a universal encoding.** Granularity is the
tester's choice. Guarding the print with `assume x == 5 && y == 3` immediately
after a command turns it into a question about that command, and narrows blame
to the backward path from that point. What is lost relative to inspecting the
domain is not power but *locality of feedback* — see Future ideas.

### The generate-and-test loop

Two agents with opposed objectives, and no human in either.

1. **Generate.** A model writes the whole domain — representation and all
   operations — against the interface above.
2. **Attack.** A separate adversary agent searches for a program `p` and a
   location `ℓ` such that the domain proves `ℓ` unreachable. This is a
   well-posed and rather natural task: *write a program that breaks this
   analyzer*.
3. **Run.** Compile `p` with a print at `ℓ` and execute it.
4. **Verdict.** If it prints, the domain is unsound and is rejected, with the
   witness program as the counterexample.
5. **Score.** Among domains no adversary has broken, count how many locations
   each one proves.

Step 5 is not optional. A domain that proves nothing survives every adversary
forever, so soundness alone selects for uselessness. The dual requirement
mirrors the synthesis acceptance in `chapter7.tex:40` — prove the targets *and*
leave the reachable locations reachable.

The two agents should not be the same model. If one writes both the domain and
its attacker, there is no reason to expect it to attack hard.

```scala
class DomainSoundness extends munit.FunSuite:
  test("no refuted location is reachable") {
    for
      (p, target) <- adversary.candidates(domain)   // step 2
      if analysis(domain, p, target).refuted        // domain claims unreachable
    do
      val out = Runner.compileAndRun(p.withPrintAt(target))
      assert(!out.contains(target.id),              // step 4
             s"unsound: domain refuted $target but this program reaches it:\n$p")
  }
```

### The assumption this rests on

> **Adversarial adequacy.** If a domain is unsound, an adversary with a
> reasonable budget finds a program and a location that exhibit it.

This is an assumption of the technique, not a theorem, and it is what stands in
for a proof of the domain. It is strictly harder to satisfy than a per-command
testing assumption would be: the adversary has to construct a program that both
triggers the flaw *and* drives execution to the location, rather than merely
touching the command. Against that, it needs no trust in anything the model
wrote, and it is the only assumption on the list.

Calibrating it is a measurement, not an argument. Seed known-unsound domains and
score the adversary on how many it breaks; that kill rate is what makes "the
adversary found nothing" mean something.

Two consequences. The probe catches unsoundness only, so precision is a separate
score — how many locations a surviving domain proves. And a domain unsound only
on behavior no adversary constructs will pass, which makes step 2 the part of
the loop most worth investing in.

## Future ideas

### A concretization test, and per-obligation checking

The design above discards a lot of signal. An alternative is to have each domain
also supply

```scala
def contains(sigma: Store, s: S): Boolean     // σ ⊨ ŝ
def alpha(sigma: Store): S                    // abstraction of one state
```

and then check every operation directly against observed executions rather than
end to end:

| | obligation |
| --- | --- |
| `transfer` | `σ' --c--> σ` and `σ ⊨ post` ⟹ `σ' ⊨ transfer(c, post)` |
| `alpha` | `σ ⊨ alpha(σ)` |
| `entails` | `A ⊑ B` and `σ ⊨ A` ⟹ `σ ⊨ B` |
| `join` / `widen` | `σ ⊨ A` ⟹ `σ ⊨ A ⊔ B`, and symmetrically |
| `bottom` | `σ ⊭ ⊥` |

This is the Lemma 1 condition tested directly instead of through its
consequence. It needs the pre- and post-state of each command, which means an
instrumented interpreter or a JDI debugger reading `StackFrame.getValues`.

*(Shawn's note: there may be something we can do later where the contains can be
considered consistent with sufficient testing as well, but this is a reasonable
assumption for now)*

**What it buys.** Signal density — thousands of checks per program run, versus
one bit per (program, location) pair each costing a full fixed-point
computation. And locality: "your `Add` case in `narrow` is wrong, here is the
counterexample" instead of "this domain is unsound somewhere." For a repair
loop, that difference is most of the value.

**What it costs.** `contains` becomes the thing every other obligation is
checked against, so it cannot itself be checked the same way. If it is written
by a human it is the one human artifact per domain. If it is generated it can be
*wrong in a way that makes every other check pass* — a `contains` whose
concretization is too small makes every obligation vacuous and refutes
everything. A fast oracle that can lie is worse than no fast oracle.

**The resolution, if we want both.** Let the model generate `contains` and
`alpha` as its own scratch oracle for iteration, and keep reachability as the
acceptance criterion. Then `contains` never has to be trusted — only useful to
the generator. If it is wrong, the model's own fast tests mislead it, the
adversary breaks the result, and the feedback is that `contains` was wrong too.

### When we would need this

Two triggers, either of which would bring it back:

- **The reachability oracle proves too expensive.** Each query is a full
  backward fixed point, and the adversary's search is over programs rather than
  inputs. If that turns out to dominate the cost of a campaign, dense per-command
  checks become the inner loop and reachability the acceptance gate.
- **We cannot drive the behavior.** The probe requires the adversary to make
  execution *arrive* at a location. That is free in IMP, where the adversary
  writes the whole program. It is not free once behavior runs through a
  framework, a library, or the OS: in an Android app the framework decides
  whether a callback fires at all, so a witness may be unconstructible even
  though the location is genuinely reachable. This is exactly the situation that
  made framework models necessary in the first place (Ch. 4), and it is where a
  probe based on observed states would keep working when a probe based on
  constructed executions stops.

Scala 3 (LTS 3.3.6) project built with sbt 1.11.7.

## Layout

Nothing described above is built yet. This is the current skeleton; the target
layout — `engine/{api,core,harness,cli}` plus `domains/` — is in
`implementation_strategy.md` §2.

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
