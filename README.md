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

## Worked example: a separation-logic heap domain

What is under test here is an *implementation* — a Scala transfer function for
one command in one domain. It is specified by a backward triple and a soundness
condition on that triple, and nothing else.

References are to Shawn Meier's dissertation, Chapter 5 §5.2, *Abstracting the
Relevant Heap for Goal-Directed Reasoning*, which gives the concrete semantics
and the abstract domain used below. The soundness condition comes from
Chapter 4, Lemma 1.

### Concrete states (Ch. 5, Fig. 5.2) and what the debugger reports

```
commands         c ::= x = y.f | x.f = y | x = null | assume x = y | assume x != y
values           v ::= null | a                 (a an address)
memory locations l ::= x ↦ v | a.f ↦ v
memories         μ ::= ∅ | l | μ ⊎ μ'           (⊎ requires dom(μ) ∩ dom(μ') = ∅)
```

```scala
type Addr = Long                                  // ObjectReference.uniqueID

enum Value:
  case Null
  case Ref(a: Addr)

enum CKey:                                        // the domain of μ
  case Local(x: String)                           // StackFrame.getValues
  case Field(a: Addr, f: String)                  // ObjectReference.getValues

final case class Memory(cells: Map[CKey, Value])
```

Keying by `CKey` makes `⊎`'s disjointness a property of the representation
rather than a condition to check.

### The abstract domain (Ch. 5, Fig. 5.3)

```
abstract memories        μ̂ ::= irrelevant | μ̂ * μ̂' | l̂
abstract memory location l̂ ::= x ↦ v̂ | v̂.f ↦ v̂'
pure constraints         π ::= v̂ ≠ v̂' | π ∧ π'
valuations               ν ::= ∅ | ν[v̂ ↦ v]
```

```scala
enum SymValue:
  case Null
  case Sym(id: Int)

enum AKey:
  case Local(x: String)
  case Field(base: SymValue, f: String)

/** μ̂ ∧ π. Any key absent from `cells` is covered by `irrelevant`. */
final case class AbsMemory(cells: Map[AKey, SymValue], pure: Set[(SymValue, SymValue)]):
  def rel: Set[AKey] = cells.keySet                // rel(irrelevant) = ∅

type Valuation = Map[SymValue.Sym, Value]
```

`irrelevant` needs no constructor: it is whatever the map does not mention.
Keying by `AKey` also makes separating conjunction structural — a key cannot
appear twice in a `Map`, so the unsatisfiable `x ↦ â * x ↦ â'` is not
representable.

### `contains` — the concretization test (Ch. 5, Fig. 5.3)

```
(μ,    ν) ⊨ irrelevant ∧ π                 iff  ν ⊨ π
(μ⊎μ', ν) ⊨ (μ̂ * μ̂') ∧ π                   iff  (μ, ν) ⊨ μ̂ ∧ π and (μ', ν) ⊨ μ̂' ∧ π
(x ↦ v, ν[v̂↦v]) ⊨ (x ↦ v̂) ∧ π              iff  ν[v̂↦v] ⊨ π
(a.f ↦ v, ν[v̂↦a][v̂'↦v]) ⊨ (v̂.f ↦ v̂') ∧ π  iff  ν[v̂↦a][v̂'↦v] ⊨ π
```

```scala
/** Some(ν) if the observed memory is in the concretization, None otherwise. */
def contains(mu: Memory, muHat: AbsMemory): Option[Valuation]
```

Search for a `ν` that sends each `AKey` in `muHat.cells` to a `CKey` present in
`mu.cells` with a matching value, then check `muHat.pure` under it. The induced
map on keys must be **injective**: `v̂.f` and `v̂'.f` are separate cells, so a `ν`
with `ν(v̂) = ν(v̂')` does not witness containment. Dropping that check would let
unsound field-write transfers pass.

`contains` is the trusted kernel — the one component a human reads, since every
other component is tested against it. It has no fixed point and no aliasing
case split; it is a matcher.

### The triple and its soundness condition

A backward triple `⊢ {P'} c {P}` reads right-to-left: *if an execution of `c`
reaches a post-state satisfying `P`, then the pre-state of that execution
satisfies `P'`.*

The transfer function computes `P'` from `c` and `P`. It returns a set of
disjuncts, because materialization has to guess aliasing and the
over-approximate direction keeps every guess. Its soundness condition:

```
  μ' --c--> μ   and   (μ, ν) ⊨ μ̂ ∧ π
  ⟹  ∃ d ∈ transfer(c, μ̂ ∧ π). ∃ν'. (μ', ν') ⊨ d
```

This is Ch. 4, Lemma 1 (*hoare triple soundness*, p. 89), restricted to the
heap component of the program state and to a transfer function returning
disjuncts. Lemma 1 as stated there also carries a specification parameter for
framework behavior, which this domain does not use:

> If `⊢ {P'} c {P}` and `σ' --c--> σ` such that `σ ⊨ P`, then `σ' ⊨ P'`.

Ch. 5 §5.2 states the dual under-approximate condition over this same heap
domain, where the implication instead runs forward from pre-state to post.

That condition is the complete specification of the transfer function.
Entailment between abstract memories and includes-initial (both Ch. 5 §5.2) are
separate components with their own conditions, tested the same way.

### Judgments are not transfer functions

The dissertation specifies these transfers with inference rules. This project
does not, and the reason is worth stating.

A judgment `⊢ {P'} c {P}` is a relation: for a fixed `c` and `P`, many `P'`
stand in it. A transfer function is a function — it must return one. The rules
therefore underdetermine the implementation in at least four ways:

- **Mode.** A judgment has no inputs or outputs. `transfer(c, post)` commits to
  reading the post-condition and producing a pre-condition.
- **Search.** Existential premises become enumeration. A materialization rule
  says *there exists* a suitably materialized state; the implementation must
  case split on aliasing to find it and keep every case.
- **Totality.** A judgment may be partial — no rule applies, no derivation
  exists. A transfer function must return something for every input; its
  fallback is real code that appears in no rule.
- **Representation.** Rules work modulo commutativity of `*`, α-renaming of
  symbolic variables, and semantic entailment. Data structures, normal forms,
  and freshness are unconstrained by them.

Since the implementation is tested against the soundness condition rather than
against derivability, carrying the rules would add a second specification that
nothing checks. A transfer function matching no derivation is acceptable if it
satisfies the condition — which is what makes generating these functions rather
than deriving them plausible.

### The transfer function

For `x = y.f`, the pre-condition must: drop any constraint on `x`, whose prior
value is dead; require the cells the command reads, `y ↦ ŷ` and `ŷ.f ↦ v̂`,
where `v̂` is whatever the post-condition says `x` holds; and add `ŷ ≠ null`,
since otherwise the read would have thrown rather than reaching the post-state.
Cells the post-condition left in `irrelevant` must first be materialized, which
is where the aliasing case split arises.

```scala
/** Backward transfer for `x = y.f`. Must satisfy the soundness condition above. */
def transfer(c: FieldRead, post: AbsMemory): Set[AbsMemory] =
  val FieldRead(x, y, f) = c
  // The value read flows to x; if the post leaves x in `irrelevant`, it is fresh.
  val vHat = post.cells.getOrElse(AKey.Local(x), fresh())
  for
    // Materialize y ↦ ŷ out of `irrelevant`, one disjunct per aliasing guess.
    (p1, yHat) <- materializeLocal(post, y)
    // Materialize ŷ.f ↦ v̂, again splitting on whether ŷ aliases a base in rel(μ̂).
    p2         <- materializeField(p1, yHat, f, vHat)
  yield
    p2.remove(AKey.Local(x))                  // x's prior value is dead
      .withDisequality(yHat, SymValue.Null)   // the read did not throw
```

Three things the condition governs and the test must police: `fresh()`'s
freshness, the completeness of the aliasing enumeration, and whether
`materializeField` propagates the disequalities that keep distinct bases
distinct.

### The generate-and-test loop

The soundness condition quantifies over concrete steps, which is what a
debugger enumerates. Neither the model nor a human inspects the abstract domain
implementation:

1. **Implement.** The model writes `transfer` for one command against the
   soundness condition.
2. **Attack.** The model writes probe programs aimed at breaking it — aliased
   receivers, null fields, a cell written then re-read, reads of cells the
   post-condition leaves in `irrelevant`.
3. **Observe.** Run each probe under JDI with breakpoints on both sides of the
   command, recording `(μ', μ)` at every hit.
4. **Check.** For post-conditions `μ̂ ∧ π` that contain `μ`, assert that some
   disjunct of `transfer(c, μ̂ ∧ π)` contains `μ'`.
5. **Refine or reject.** A failure returns the command, the post-condition, the
   computed pre-condition, and the excluded concrete pre-state.

```scala
class FieldReadSoundness extends munit.FunSuite:
  test("x = y.f transfer is sound on observed steps") {
    for
      probe          <- probes                       // step 2
      (c, pre, post) <- Debugger.observeSteps(probe) // step 3
      postAbs        <- Abstraction.containing(post) // any μ̂ ∧ π with post ⊨ μ̂ ∧ π
    do
      val preAbs = transfer(c, postAbs)              // step 4
      assert(preAbs.exists(d => contains(pre, d).isDefined),
             s"unsound: $c\n  post ⊨ $postAbs\n  pre ∉ γ($preAbs)")
  }
```

### The assumption this rests on

> **Testing adequacy.** For a transfer function small enough to handle one
> command in one domain, a reasonable set of generated probes exercises enough
> concrete steps that any violation of the soundness condition appears in at
> least one observed step.

This is an assumption of the overall technique, not a theorem, and it is what
replaces the derivation a human would otherwise write. It is the reason the
command language is kept small: the assumption is only credible for transfer
functions whose behavior a modest probe set can cover. Everything downstream is
sound *given* that each transfer function satisfies its condition, so this
assumption is where the technique is most exposed.

Two consequences. The loop catches unsoundness only: a transfer returning the
weakest pre-condition passes every probe and is useless. Precision must be
measured separately — for this domain, by whether the backward fixed point
keeps at least one materialized cell, since a state that is entirely
`irrelevant` concretizes to the empty initial memory (Ch. 5 §5.2). And a
transfer unsound on a path no probe takes passes, which makes step 2 —
adversarial probe generation — as important as step 1.

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
