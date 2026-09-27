# Design session, 2026-09-23 to 2026-09-26

A record of the consistency pass before implementation began. It is history:
the decisions below live in `implementation_strategy.md`, `README.md` and
`glossary.md`, and those files win if they disagree with this one. Work ran as
a queue, one item per exchange.

## Starting point

Asked for a first set of steps to load a simple Java program and print its CFG.
That became Phase 2a (§14), later cut into eight ~200-line changes.

## Decisions, in the order they were made

1. **`main`'s `args` may not be read**, enforced by a separate *profile check*
   pass that can be switched off (`language.enforce`). *Why:* the subset needs
   a gate, and inspecting programs outside it is still useful.
2. **Soot 4.7.1**, the latest stable release, behind `IrProvider`; `cli` sees
   `frontend-soot` at runtime only, so the compiler enforces the Soot boundary.
   `jb.dae` and `jb.uce` off, because they delete statements a reachability
   query may ask about.
3. **Nondeterminism is required.** Without input every program has one
   execution and the abstraction collapses. Input comes from one static call.
4. **`BigInteger` only.** First proposed: `int` with exact arithmetic to stop
   the JVM's wraparound refuting sound unbounded domains. Shawn chose instead
   to make the subset `BigInteger`-only, which removes overflow entirely and
   drops `intWidth`. Division removed.
5. **Lifting** is its own switchable pass (`language.lift`) turning
   `BigInteger` calls into arithmetic, so a domain sees `y := x + 1`. It never
   adds or removes a command, so locations survive it. Static constants
   `ZERO/ONE/TWO/TEN` allowed; compare temps must have exactly `javac`'s shape.
6. **Locations mirror Historia:** `InternalMethodEntry`, `AppLoc(m, i, isPre)`,
   `InternalMethodExit`. `isPre` kept so methods can be added after intervals
   work; *Internal* prefix kept so callbacks and callins can return without a
   name clash. Inter-command edges are `Step.Skip`, handled by the engine.
7. **`pag.probe.Rand.randInt()`**, returning `BigInteger`. The adversary
   chooses every value (`-Dpag.inputs`); no seed, no generator. Not named
   `Instrumentation` (JDK clash, and clash with `instrumentReach`).
8. **README grammar** uses `x := randInt()` and all six comparisons; the
   under-approximate mode is out of scope and removed from the docs.
9. **All six comparisons reach the domain**, unnormalised. *Why (Shawn):*
   spend complexity on growing toward full Java, not on lowering.
10. **The engine never abandons a state; weakening is entirely the domain's.**
    The glossary had cited strategy text for this that was never in the
    strategy (it was in the 2026-09-23 log); now stated in §7.
11. **A hang is not a `DomainFailure`.** `pag` cannot stop one; the campaign
    driver kills the process. Exit code 5 no longer claims otherwise.
12. **Constant substitution in lifting.** Found by working the §11 example
    backward: Jimple forces constants into temps, and a backward
    non-relational domain meets the comparison before the constant, so the
    interval domain would prove nothing about comparisons with literals.
13. **Trust base** (§2): front end, `instrumentReach`, executor, certifier.
    `Rand` is outside it — a bug breaks replay, never a verdict. Shawn's rule:
    exhaustively unit tested and reviewed before merge, with an all-locations
    Stage 1/Stage 2 cross-check in CI (Phase 2b).
14. **`enforce` stays on; the profile's lists are how the language grows.**
    `enforce = false` is inspection only — `pag ir` accepts it, `analyze` and
    `check` refuse it — and lifting is lenient under it. *Why:* Shawn wants
    features added one at a time; a blanket bypass is the opposite, and
    starting strict is the cheap direction to change later.
15. **Working agreement** in `CLAUDE.md`: ~200-line self-contained changes,
    tests included, review between each, Shawn commits.

## Still open

- §5.1: `Cmd` records carry `Loc loc` and `Goto` a `Loc trueLoc`, but `Loc` is
  now a sealed interface whose command positions are `AppLoc(m, i, isPre)`. A
  command's own position is an index, and a jump target is a `pre` location.
  To settle in change 2, before the types are written.
- §5.4 and §10 **[decide]** markers and §16 are unchanged by this session.

## Next

Phase 2a, change 1: the multi-project build.
