# Implementation strategy

A plan for a prototype. The goal is a working generate-and-test loop over a
small language and a small domain, with a hard boundary between code a model
may write and code it may not.

This document is for review and iteration. Decisions I made on thin evidence
are marked **[decide]**, and the open questions are collected at the end.

## 1. The boundary

Two kinds of code, separated physically so the separation can be enforced by
the filesystem rather than by convention, and separated by language so each
side is written in what its author handles best.

| | who writes it | language | where | model access |
| --- | --- | --- | --- | --- |
| Fixed-point engine | human | Scala 3 | `engine/core` | none |
| Observation + checker | human | Scala 3 | `engine/harness` | none |
| Domain contract | human | **Java 21** | `engine/api` | compiled jar |
| Domain *spec* (state type, `contains`, `entails`, `excludesInit`, lattice) | human | **Java 21** | `domains/<d>/spec` | compiled jar |
| Domain *transfer functions* | model | **Java 21** | `domains/<d>/transfer` | read-write |
| Probe programs | model | IR / Java | `probes/` | read-write |

The language line falls at the domain contract: **everything at or below the
contract is Java; everything above it is Scala 3.** The engine is Scala because
that is what you want to read and maintain. The contract and the domains are
Java because that is what a small model writes reliably — a large training
corpus, regular syntax with few implicit decisions, actionable `javac` errors
for the repair loop, and fast compiles in a loop that runs thousands of times.

This costs you Java in exactly one place: the domain spec, roughly a hundred
lines per domain. It has to be Java rather than Scala because the model-written
transfer compiles against it, and a Scala spec would put the Scala library on
the domain classpath and end the hermetic `javac` build described in Phase 8.

The split inside a domain is the load-bearing part. Everything the engine
trusts is phrased in `contains`, so `contains` cannot itself be generated —
there would be nothing left to check it against. A domain therefore ships as
two jars: a human-written spec jar defining the state representation and the
concretization, and a model-written transfer jar depending on it.

Only `contains` and the state type stay human. Everything else a domain needs
— `alpha`, the lattice operations, `entails`, `excludesInit` — carries a
one-line obligation phrased in `contains`, so all of it is generated and tested
the same way `transfer` is. For intervals the human half is about twenty lines.

Generating `contains` itself is the remaining prize and is gated on a
measurement rather than a decision; see Phase 7.

### What is and is not under test

The property under test is **soundness**. Two things deliberately are not:

- **Precision.** Nothing requires `transfer` to be the best abstract
  transformer, `alpha` and `contains` to form a Galois connection, or `join` to
  be a least upper bound. A domain that returns top everywhere satisfies every
  obligation and proves nothing. That is a quality problem, measured by whether
  the analysis refutes anything, not a correctness problem.
- **Termination.** `widen` carries the same soundness obligation as `join` and
  no convergence obligation. A widening that never stabilizes is caught by the
  engine's step limit and reported as a resource failure, kept distinct from a
  soundness failure.

This is deliberate scope control. The abstract interpretation literature has a
large, well-developed theory of optimality, completeness, and convergence. None
of it is needed to state or check the soundness condition, and importing it
would enlarge the contract the model must satisfy without improving the
property we actually care about. For the first iteration the model is left to
find precision and convergence on its own; the harness only ever asks whether
the result is sound.

## 2. Repository layout

```
engine/                    sbt multi-project, human-only
  api/                     the domain contract. Pure Java, no Scala dependency.
  core/                    Scala 3. IR, CFG, reference interpreter, worklist,
                           invariant map, inductiveness and refutation checks
  harness/                 Scala 3. observation, candidate generation, soundness
                           checker, mutation corpus, counterexample reporting
  cli/                     Scala 3. config parsing, domain loading, entry point
domains/
  interval/
    spec/                  Java: state type, contains, entails, excludesInit, lattice
    transfer/              Java, model-written: transfer functions
    build.sh               javac against api.jar and spec.jar. No sbt, no network.
  <next domain>/
probes/                    model: probe programs, one directory per campaign
config/                    run configurations naming domains and jars
docker/                    compose files and mount definitions
```

`engine/api` is an sbt subproject with `crossPaths := false` and
`autoScalaLibrary := false`, so the published jar is plain Java with no Scala
coupling and no binary-compatibility constraint on domains.

**[decide]** You described domains as a sub-directory of the engine. I have
made them a sibling instead, because they build with a different toolchain,
they mount differently, and keeping them outside `engine/` means the read-only
mount is a single unqualified path. Nested mounts would work, but the intent is
less obvious to a reader and easier to get wrong in a compose file.
shawn's answer: this looks reasonable to me.

## 3. What to take from Historia, and what to drop

Take, in simplified form:

| Historia | here |
| --- | --- |
| `AbstractInterpreter.executeBackward` worklist | `core.Worklist`, same backward shape, no grouping heuristics |
| `Qry` = state + location | `WorkItem(loc, state)` |
| `TransferFunctions.cmdTransfer(cmd, state): Set[State]` | `Transfer<S>.transfer(Command, S): S`, one state, domain-typed |
| `StateSolver.canSubsume` driving merge | `DomainSpec<S>.entails`, no solver in the engine |
| `IRWrapper` abstracting the IR | `core.ir`, a fixed small IR, no wrapper needed yet |
| `ExecutorConfig` constructing the interpreter | `config/*.conf` plus `EngineConfig` |
| step limits and timeouts | kept, as the only termination guard besides widening |

Drop outright:

- Soot, APKs, Android, the framework model. The prototype analyzes IMP.
- Z3 and the SMT encoding. Entailment is a domain method. A domain may use a
  solver internally; the engine must not know.
- Message histories, CBCFTL, specifications, model synthesis.
- Path nodes, witness explanations, the results database, output modes.
- `ApproxMode` / `DropQryPolicy` and the subsumption-mode variants.
- Scala 2.13. The engine is Scala 3; the contract and domains are Java 21.

The simplification is drastic: Historia's `AbstractInterpreter` is ~1200 lines
and `TransferFunctions` ~1000, largely because the IR is real Java and the
domain is fixed and entangled with the solver. With IMP and an opaque domain
behind an interface, the engine core should be a few hundred lines.

## 4. The contract

`engine/api` is the most important artifact in the project. It is what the
model compiles against, it is the only engine code the model sees, and every
soundness obligation is stated in it. Pure Java 21 — sealed interfaces and
records give ADTs, and exhaustive `switch` gives the model a compiler check on
case coverage.

```java
package pag.api;

// ---------- concrete states, as the observer reports them ----------
public record Store(Map<String, BigInteger> vars) {}

// ---------- the IR: fixed and small on purpose ----------
public sealed interface Expr {
    record Lit(BigInteger n)      implements Expr {}
    record Var(String x)          implements Expr {}
    record Add(Expr l, Expr r)    implements Expr {}
    record Mul(Expr l, Expr r)    implements Expr {}
}

public sealed interface Cond {
    record Lt(Expr l, Expr r)  implements Cond {}
    record Eq(Expr l, Expr r)  implements Cond {}
    record Not(Cond b)         implements Cond {}
}

public sealed interface Command {
    record Assign(String x, Expr a) implements Command {}
    record Assume(Cond b)           implements Command {}
}
```

The human half is one interface with one real method. `S` is opaque to the
engine and to the harness.

```java
/** Written by hand. Defines what the domain means. */
public interface DomainSpec<S> {
    String name();

    /** sigma |= s. The trusted kernel; every obligation below is phrased in it. */
    boolean contains(Store sigma, S s);
}
```

The generated half is everything else. Each method carries exactly one
obligation, each obligation is checkable against observed concrete states, and
none of them mentions precision or convergence.

```java
/** Written by the model. Every method below is tested against DomainSpec.contains. */
public interface DomainOps<S> {

    /** contains(sigma, alpha(sigma)) */
    S alpha(Store sigma);

    /** !contains(sigma, bottom()) */
    S bottom();

    /** isBottom(s) ==> !contains(sigma, s) */
    boolean isBottom(S s);

    /** entails(a,b) && contains(sigma,a) ==> contains(sigma,b) */
    boolean entails(S a, S b);

    /** contains(sigma,a) ==> contains(sigma, join(a,b)), and symmetrically for b */
    S join(S a, S b);

    /** Same obligation as join. Convergence is NOT an obligation. */
    S widen(S a, S b);

    /** excludesInit(s, init) ==> !contains(init, s) */
    boolean excludesInit(S s, Store init);

    /**
     * sigma' --c--> sigma  &&  contains(sigma, post)
     *   ==>  contains(sigma', transfer(c, post))
     */
    S transfer(Command c, S post);
}
```

Notes on the shape:

- `S` as a generic parameter rather than an abstract type member. Clearer from
  Java, and the engine handles the existential with a Scala type capture on
  `DomainSpec[?]`.
- `transfer` returns one state, not a set. Intervals need no disjuncts. A
  domain that does carries the disjunction inside `S`, with `join` as its
  union, so the engine never learns about disjunction. **[decide]**
- **No `candidatesContaining`.** An earlier draft had the domain enumerate the
  post-conditions the checker would try. Generating that method would let a
  domain weaken its own test. Instead the harness synthesizes candidates from
  `alpha`, `join`, and `widen` over observed stores — domain-independent, and
  one fewer generated method.
- `entails` and `excludesInit` sit on the *certification* path: the
  `[inductive]` and `[refute]` checks call them directly, so a wrong one is as
  damaging as a wrong `transfer`. They get the same testing treatment and the
  same mutation corpus.
- No `DomainProvider`. The spec jar and the transfer jar each register through
  `META-INF/services`, and the engine pairs them by name.

Version the api jar and record its version in every result, so a campaign's
results can be tied to the contract they were produced under.

## 5. Dynamic loading

Each half of a domain builds to a jar. The engine loads them per run from a
config file:

```hocon
domains = [
  {
    name        = "interval"
    specJar     = "domains/interval/out/interval-spec.jar"
    transferJar = "domains/interval/out/interval-transfer.jar"
  }
]
engine {
  direction  = backward
  widenAfter = 3
  stepLimit  = 100000
}
```

Loading uses a `URLClassLoader` per domain over its two jars, with the engine's
loader as parent. Delegation must be **parent-first for `pag.api.*`** so the
engine and the domain agree on the contract types, and child-first for
everything else so domains can carry conflicting dependencies.
`ServiceLoader` finds the `DomainSpec` in the spec jar and the `Transfer` in
the transfer jar, and the engine pairs them by `name()`.

Erasure means the pairing is unchecked: a `Transfer<A>` wired to a
`DomainSpec<B>` compiles and loads, then throws `ClassCastException` on the
first call. So the loader runs a smoke test immediately after pairing —
`transfer` applied to `spec.bottom()`, then `contains` on a fixture store —
and refuses the domain on failure, rather than failing deep inside a run.

The other practical failure mode is `LinkageError` from two copies of the api
classes. Write the classloader test before the loader: load two domains that
each bundle a different version of a third-party library, confirm both work and
that `S` values do not leak across them.

## 6. Observation

Staged, because JDI is a large amount of work and blocks nothing early.

**Stage 1 — instrumented reference interpreter.** `engine/core` contains a
concrete interpreter for the IR. Instrument it to emit `(loc, cmd, pre, post)`
for every step. Deterministic, fast, no external process, and it is also the
oracle for IR semantics. This is enough to build and validate the entire loop.

**Stage 2 — JDI.** Compile probes to JVM bytecode, run under a debug agent,
breakpoint each transition, read `StackFrame.getValues`. Needed before the
technique can claim to test against real runtime behavior, and needed for any
language the engine does not interpret. Defer until the loop works.

Both stages produce the same record type, so the checker does not change:

```scala
final case class Observation(loc: Loc, cmd: Command, pre: Store, post: Store)
```

`Command` and `Store` here are the Java types from `pag.api`, used directly
from Scala. The engine wraps them in Scala types only where it needs pattern
matching or collections.

## 7. Phases

Each phase names a deliverable and a done-when that is a runnable check.

### Phase 0 — skeleton
sbt multi-project for `engine`: `api` (pure Java, `crossPaths := false`,
`autoScalaLibrary := false`), plus `core`, `harness`, `cli` in Scala 3. A
`build.sh` for `domains/interval` that runs `javac` against a fixed classpath.
CI runs `sbt test` and `build.sh`.
*Done when:* both build, a placeholder test passes in each, and `build.sh`
succeeds with the network disabled.

### Phase 1 — the contract
Write `engine/api` as above. Nothing else depends on decisions here being
wrong, so get it reviewed before building on it. Publish locally so the domain
build can put it on the classpath.
*Done when:* a stub domain compiles against `api.jar` alone with `javac`, no
Scala library on the classpath, and `jdeps` on the stub reports no dependency
outside `java.*` and `pag.api.*`.

### Phase 2 — IR and reference interpreter
CFG as a set of transitions `ℓ —c→ ℓ'` with an initial location. Concrete
interpreter over `Store`. Instrumentation emitting `Observation`.
*Done when:* a hand-written probe with a loop runs and emits the expected
sequence of observations, checked against a fixture.

### Phase 3 — the interval domain, by hand
Both halves written by a human in Java, end to end. This is the golden
reference: it tells you the contract is expressible and gives the checker
something known-good to validate against. It is also the worked example a model
will be shown, so write it the way you want generated code to look.
*Done when:* the five worked examples in `README.md` pass as unit tests, and
every `DomainOps` obligation passes a property test against random stores.

### Phase 4 — the fixed-point engine
Invariant map, backward worklist, join at merge points, widening at loop heads,
step limit. Then the checker for `[edge-inductive]`, `[inductive]`, `[refute]`
as a separate pass over the settled map.
*Done when:* a probe with an assertion that cannot fail is refuted, and one that
can fail is not. Crucially: the inductiveness check is re-run on the final map
and must pass independently of how the worklist reached it.

### Phase 5 — dynamic loading
Config parsing, classloader, `ServiceLoader` discovery.
*Done when:* the interval domain loads from a jar with the engine having no
compile-time dependency on it, and the Phase 4 tests still pass through the
loaded path.

### Phase 6 — the soundness checker
One checker per obligation, all driven by the same observation stream. For
`transfer`: synthesize post-conditions from `alpha`, `join`, and `widen` over
observed stores, apply `transfer`, assert `contains` on the pre-state. For
`entails`, `join`, `widen`, `bottom`, `isBottom`, `excludesInit`: check the
one-line implication in §4 directly against observed stores.

Report counterexamples as JSON naming the obligation, the inputs, the result,
and the concrete state that witnesses the violation.
*Done when:* running every checker over the hand-written interval domain and a
probe corpus reports zero violations.

### Phase 7 — mutation corpus, and the `contains` gate
This phase tests the *harness*, and it is the one that puts a number on the
testing-adequacy assumption. Maintain a corpus of deliberately unsound
implementations — off-by-one narrowing, a dropped operand constraint, a
forgotten intersection, an `entails` that is too permissive, an `excludesInit`
that fires too eagerly, a `join` that is not an upper bound. Measure what
fraction the current probe corpus catches, per mutant.
*Done when:* the kill rate is reported per mutant and the whole corpus runs in
CI. A surviving mutant is either a probe-corpus gap or an argument that the
command was not small enough.

**The `contains` gate.** Whether `contains` can also be generated — removing
the last human step in adding a domain — is decided here rather than by
argument. Generating it needs three grounded obligations, because a `contains`
that is always false makes every other obligation vacuous and refutes
everything:

| obligation | grounding |
| --- | --- |
| `contains(sigma, alpha(sigma))` | observed stores; forbids always-false |
| `excludesInit` checked against *observed* initial stores | observation, not `contains` |
| `p(sigma) ==> contains(sigma, abstractQuery(p))` for a concrete query predicate `p` | the query, stated outside the domain |

Build `contains` mutants specifically — always-true, always-false, dropped
bound check, off-by-one, true-except-at-initial-states — and measure the kill
rate under those three obligations. Generating `contains` is defensible only if
the corpus kills them on a modest probe budget. Note that it also strengthens
the adequacy assumption from per-command to per-path: a false refutation then
needs probes that exercise the witness path, not merely each command. Do not
adopt it on the strength of the argument alone.

### Phase 8 — isolation
Compose file with two services. The `codegen` service mounts:

| path | mode |
| --- | --- |
| `domains/<d>/transfer` | rw |
| `probes/<campaign>` | rw |
| `api.jar`, `<d>-spec.jar` | ro |
| everything else | absent |

The container holds a JDK and nothing else — no sbt, no coursier, no network.
That is the main practical dividend of the Java contract: the domain build is
`javac` against two fixed jars, so it is hermetic by construction rather than
by sandboxing a dependency resolver.

The engine service runs outside and rebuilds the transfer jar from the writable
sources in a clean container before trusting it, so a tampered jar is not
accepted.
*Done when:* a container started from the compose file can build the transfer
project with networking off, and cannot read `engine/core`.

### Phase 9 — the loop
Driver that runs: generate transfer → build → check → report counterexamples →
regenerate. Bounded retries, every attempt logged with the api version, the
probe corpus hash, and the result.
*Done when:* a small model produces a passing interval `transfer` for `Assign`
from the contract and the counterexample feedback alone.

### Phase 10 — model-generated probes
Move probe generation into the loop. Score a probe corpus by its kill rate
against the Phase 7 mutants, so probe quality is measured rather than assumed.
*Done when:* a generated corpus beats the hand-written one on kill rate.

### Later
JDI observation; a second domain exercising disjunction; the forward
flow-insensitive phase; richer IR.

## 8. What each phase de-risks

- 1 and 3 together answer: is the contract expressible and complete enough to
  write a real domain against?
- 4 answers: does certifying the settled map actually work, independent of the
  worklist?
- 5 answers: does the isolation boundary survive contact with the JVM's
  classloading rules?
- 7 answers: is the testing-adequacy assumption plausible for this command
  size? This is the phase most likely to change the design, and it is worth
  reaching early — consider pulling a crude version of it forward into Phase 6.
- 9 answers: is the contract legible enough for a small model to work from?

## 9. Open questions

Resolved: the domain language is Java, the engine is Scala 3, and the boundary
falls at the contract (§1).

1. **Who writes `contains`.** I have assumed human, with the spec/transfer split
   inside each domain. Confirm, since it shapes the directory layout and the
   mounts.
2. **Does the model see the spec source?** Reading `contains` would help it
   write a correct `transfer`; it also lets it infer what the checker will try.
   I lean toward showing it, since the checker's power comes from observed
   executions rather than from the model not knowing the test. Cheap either way
   now — the spec ships as a jar, so showing the source is a mount decision.
3. **Direction.** Backward only for the prototype, or both? Everything above
   assumes backward.
4. **`S` or `Set<S>` from `transfer`.** Pushing disjunction inside `S` keeps the
   engine simple but makes every domain implement its own disjunction. Now a
   plain signature choice in the Java contract, so it is cheap to change in
   Phase 1 and expensive to change after Phase 3.
5. **Probe corpus scope.** Per-domain, per-command, or global? Affects whether
   kill rate is a meaningful number to compare across domains.
6. **Java version floor.** Pinned at 21 for record patterns and
   pattern-matching-for-switch, which give the model exhaustiveness checking on
   the IR. Dropping to 17 loses that and is not worth it.
