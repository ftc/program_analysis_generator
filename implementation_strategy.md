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

Soundness is a hard rejection backed by a witness. Precision is a score. The
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
| **Probe programs / witnesses** | **adversary agent** | IR (JSON) | `probes/<campaign>/` | read-write |

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

**[decide]** Domains are a sibling of `engine/` rather than a subdirectory. They
build with a different toolchain and mount differently, and keeping them outside
means the read-only mount is a single unqualified path.

## 4. What to take from Historia, and what to drop

| Historia | here |
| --- | --- |
| `AbstractInterpreter.executeBackward` worklist | `core.Worklist`, same backward shape, no grouping heuristics |
| `Qry` = state + location | `WorkItem(loc, state)` |
| `TransferFunctions.cmdTransfer(cmd, state): Set[State]` | `Domain<S>.transfer(Command, S): S`, one state, domain-typed |
| `StateSolver.canSubsume` driving merge | `Domain<S>.entails`, no solver in the engine |
| `InitialQuery.Reachable(sig, line)` | the *only* query form here |
| `IRWrapper` abstracting the IR | `api.ir`, a fixed small IR, no wrapper yet |
| `ExecutorConfig` constructing the interpreter | `config/*.conf` plus `EngineConfig` |
| step limits and timeouts | kept, and reported as a distinct verdict |

Drop outright: Soot, APKs, Android, framework models; Z3 and the SMT encoding
(entailment is a domain method — a domain may use a solver internally, the
engine must not know); message histories, CBCFTL, specifications, synthesis;
path nodes, witness explanations, the results database; `ApproxMode` /
`DropQryPolicy` and the subsumption-mode variants; Scala 2.13.

Historia's `AbstractInterpreter` is ~1200 lines and `TransferFunctions` ~1000,
largely because the IR is real Java and the domain is entangled with the solver
(`StateSolver.canSubsume` alone is ~1800 lines). With IMP and an opaque domain
behind an interface, the engine core should be a few hundred lines.

## 5. The contract

Pure Java 21. Sealed interfaces and records give ADTs; exhaustive `switch` gives
the model a compiler check on case coverage.

```java
package pag.api;

// ---------- IR ----------
public sealed interface Expr {
    record Lit(BigInteger n)   implements Expr {}
    record Var(String x)       implements Expr {}
    record Add(Expr l, Expr r) implements Expr {}
    record Mul(Expr l, Expr r) implements Expr {}
}

public sealed interface Cond {
    record Lt(Expr l, Expr r) implements Cond {}
    record Eq(Expr l, Expr r) implements Cond {}
    record Not(Cond b)        implements Cond {}
}

public sealed interface Command {
    record Assign(String x, Expr a) implements Command {}
    record Assume(Cond b)           implements Command {}
}

public record Loc(int id) {}
public record Transition(Loc from, Command cmd, Loc to) {}
public record Program(List<Transition> transitions, Loc init) {}

// ---------- the domain: entirely generated ----------
public interface Domain<S> {
    String name();

    S top();                          // I(target) starts here
    S bottom();                       // I(everywhere else) starts here
    boolean isBottom(S s);            // the refutation test at ell_init

    boolean entails(S a, S b);        // used by [edge-inductive]
    S join(S a, S b);                 // control-flow joins
    S widen(S a, S b);                // loop heads; convergence is NOT required

    S transfer(Command c, S post);    // backward abstract semantics
}
```

Notes:

- **No `contains`, no `alpha`, no `Store`.** A domain never sees a concrete
  state. The only check on it is end to end.
- `transfer` returns one state. A domain needing disjunction carries it inside
  `S`, with `join` as its union, so the engine never learns about it. **[decide]**
- The obligations these methods must satisfy are real but unstated in code —
  they are Lemma 1 and its companions, w.r.t. a concretization each domain has
  and never writes down. The harness verifies their consequence, not them.
- Version the api jar and record its version in every result.

## 6. The engine

```scala
enum Verdict:
  case Refuted                      // certified unreachable
  case Alarm                        // fixed point reached, target not excluded
  case Exhausted(reason: String)    // step limit, or widening did not converge

def analyze[S](d: Domain[S], p: Program, target: Loc, lim: Limits): Verdict
```

Two clearly separated stages, because the README's soundness argument depends on
the separation:

**Compute.** Worklist. `I(target) = d.top()`, everything else `d.bottom()`. Pop a
transition `ℓ —c→ ℓ'`, compute `d.transfer(c, I(ℓ'))`, join (or widen at a loop
head) into `I(ℓ)`, re-enqueue predecessors of `ℓ` if `I(ℓ)` grew. Stop on an
empty worklist or the step limit. This stage may be arbitrarily heuristic.

**Certify.** Independently re-check, against the settled map:

```
  [edge-inductive]   entails(transfer(c, I(ℓ')), I(ℓ))   for every transition
  [inductive]        I(target) is top
  [refute]           isBottom(I(ℓ_init))
```

Only a map passing all three yields `Refuted`. If certification fails the result
is `Alarm` regardless of what the worklist concluded. Keeping these apart means
widening, worklist order, and any future heuristic cannot affect soundness — a
property worth a test of its own (Phase 4).

Note that `[refute]` is `isBottom` rather than an `excludesInit` method: `ℓ_init`
admits every store, because initial constraints compile to an `assume` on the
entry transition (README, "Programs, and why reachability is enough").

## 7. The probe: executing a witness

A witness is concrete and self-contained:

```scala
final case class Witness(program: Program, init: Store, target: Loc)
```

The analysis refutes "reachable from *any* initial store," so a witness names the
specific store that reaches it. Verdict: run it; if control visits `target`, the
domain is unsound.

Staged, and the staging is about who we trust to *execute*, not who observes:

**Stage 1 — reference interpreter.** `engine/harness` interprets the IR and
records visited locations. Fast, deterministic, no external process. The
executor is our own code, so it is trusted.

**Stage 2 — compile and run.** Emit Java source from the IR with
`System.out.println("REACHED-<id>")` at the target, `javac`, run, grep stdout.
Now the executor is `javac` plus the JVM, and the evidence depends on nothing
this project wrote. This is far cheaper than the JDI stage the previous plan
carried, because there is no debugger — just codegen and a print.

Stage 2 is what makes the README's claim ("does not depend on any component of
this project being correct") literally true, so it should not be deferred
indefinitely. A cross-check that both stages agree on a corpus is a cheap
standing test.

## 8. The two agents

**Generator.** Input: the contract, the reference domain as a worked example,
and any counterexample witnesses from previous rounds. Output: a domain in
`domains/<d>/`. Scored on proof count over the scoring corpus, among domains not
rejected.

**Adversary.** Input: the domain source (read-only), `analyze` as a service, and
the executor. Output: witnesses. Scored on kill rate against the mutant corpus.

Letting the adversary *read* the domain is a deliberate choice — it is a machine,
the prohibition on reading domains applies to humans, and reading is what lets it
target the search rather than fuzz blindly. It still has to produce an executable
witness, so reading cannot substitute for evidence. **[decide]**

The two must not be the same model instance, and preferably not the same model.
An agent asked to write a domain and then attack it has no reason to attack hard.

## 9. Phases

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

### Phase 2 — IR and executor
Program representation, a JSON codec for it (the adversary's output format), and
the Stage 1 interpreter recording visited locations.
*Done when:* a hand-written program with a loop runs, and its visited-location
sequence matches a fixture.

### Phase 3 — the reference interval domain
Written by hand, in Java, as a fixture. Also the worked example shown to the
generator, so write it the way generated code should look.
*Done when:* the five transfer cases in `README.md` pass as unit tests.

### Phase 4 — the analysis engine
Worklist, invariant map, widening, step limit, and the certifier as a separate
pass.
*Done when:* a program whose target is unreachable gets `Refuted` and one whose
target is reachable gets `Alarm` — **and** a test that deliberately corrupts the
worklist result still cannot produce `Refuted`, because certification is
independent.

### Phase 5 — the probe and the verdict
`Witness`, the runner, and the rejection path with a serialized counterexample.
*Done when:* an end-to-end test using a deliberately broken transfer function
produces a refutation, a hand-written witness, and a rejection. This is the first
point at which the whole idea is demonstrated, with a human standing in for the
adversary.

### Phase 6 — dynamic loading
Config parsing, `URLClassLoader` per domain, `ServiceLoader` discovery.
Delegation is **parent-first for `pag.api.*`** so engine and domain agree on the
contract types, child-first otherwise so domains may carry conflicting
dependencies. A smoke test runs immediately after loading — `transfer` on
`bottom()`, `entails` on `top()` — so a broken domain fails at load, not deep in
a run.
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
| generator | `domains/<d>/` | `api.jar`, reference domain, witnesses | everything else |
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
Driver: read domain → query `analyze` for refuted targets → propose witnesses →
run → report. Score against the Phase 8 mutants first, then turn it on generated
domains.
*Done when:* the adversary's kill rate on mutants is reported, and it finds at
least one genuine witness against a generated domain.

### Later
Real-language backends beyond the Stage 2 Java emitter; a domain exercising
disjunction; the forward flow-insensitive phase; and the Future-ideas triggers in
`README.md` — cost, and behavior that cannot be driven because it runs through a
framework, library, or the OS.

## 10. What each phase de-risks

- 1 and 3: is the eight-method contract expressible enough to write a real
  domain against?
- 4: does certifying the settled map actually work, independent of the worklist?
- 5: does the central idea hold end to end, with a human as adversary?
- 6: does the isolation boundary survive JVM classloading?
- 8: is the adversary strong enough for its silence to mean anything? **This is
  the phase most likely to change the design, and a crude version of it is worth
  pulling forward into Phase 5.**
- 10 and 11: is the contract legible enough for a small model, in both roles?

## 11. Open questions

1. **Adversary budget and stopping rule.** How long does an adversary search
   before a domain is provisionally accepted? Phase 8's calibration should set
   this empirically rather than by guess.
2. **Does the adversary see the domain source?** Assumed yes (§8). It is a
   machine, and reading targets the search. Confirm.
3. **Witness minimization.** A found witness may be large. Shrinking it before it
   becomes generator feedback is probably worth it, and is standard
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
