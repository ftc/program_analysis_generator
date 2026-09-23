# Dashboard — deferred

A web UI for inspecting the running system. **Not being built yet** — deferred
2026-09-23 to keep focus on the core engine. The CLI is the interface in the meantime.

A design mock-up exists, built around a real scenario (an interval domain that
misses 32-bit wraparound, caught by the adversary):
<https://claude.ai/artifact/VjuobWygm5i436hSXTgCHR>

## Requirements

I want to have a web page based dashboard that lets me visually inspect the running system.
I think scala play may be a reasonable way to do this but I will leave that decision to you.
The project should be structured in a way that I can attach the intellij community edition debugger and step through everything.
This dashboard should have the ability to inspect individual function CFGs, the abstract states at each program point, 
and anything else that seems useful for debugging and understanding.
Additionally it would be nice if this dashboard has the runtime configuration like a url and credentials for the domain
and adversary LLMs. Being able to configure those separately may be nice for experimentation.

## Proposed design

**Not Play. An embedded server in the engine's own JVM.** The stated requirement
is that IntelliJ Community's debugger can attach and step through everything, and
Play's dev-mode classloader reloading is precisely what makes that awkward —
breakpoints in reloaded classes detach, and the request thread is several Akka
hops from the code you care about. Instead `engine/dashboard` runs an embedded
HTTP server (Cask, or the JDK's own `HttpServer` if we want zero dependencies) in
the same process as the analysis. One `main`, one JVM, one debugger session:
setting a breakpoint in `Worklist.step` and clicking a button in the browser
lands you in it with the whole stack visible.

Structurally that means the dashboard **observes** the engine rather than driving
it. `core` and `harness` emit events to an in-memory store; the dashboard reads
that store and serves JSON; the page polls. Nothing in `core` imports anything
from `dashboard`, so the engine runs headless in CI unchanged.

What it shows:

| view | purpose |
| --- | --- |
| Campaign | generated domains, verdicts, proof counts, adversary kill rate |
| Query | one `analyze` run: verdict, step count, where it stopped |
| CFG | a method's control-flow graph, with the lowered `Cfg` edges |
| State | `I(ℓ)` at every program point, and the same map before certification |
| Certification | which `[edge-inductive]` check failed, if any |
| Reaching run | the adversary's `.java`, its output, and the refuted target |
| Config | model endpoints and credentials (see `implementation_strategy.md` §8) |

The State and CFG views are the ones that will actually earn their keep: a
domain that refutes something it should not is best understood by walking `I`
backwards from the entry, and that is painful in a log and easy in a graph.

## What the CLI has to carry until this exists

The views below are not optional information, only optional *presentation*. Each
has a text equivalent the CLI must provide, or the engine becomes undebuggable:

| view | CLI equivalent |
| --- | --- |
| CFG | `pag ir --cfg` — lowered transitions, one per line |
| State | `pag analyze --show-invariant` — `I(ℓ)` per program point |
| Certification | the failing `[edge-inductive]` edge, named, on failure |
| Reaching run | `pag check` output — probe path, stdout, verdict |
| Campaign | a results directory plus `--json` |

The graph and the side-by-side abstract-vs-actual comparison are the two things
that genuinely do not survive the translation to text. Those are the reason to
build this eventually.

## When to pick it up

After Phase 5 at the earliest — there is nothing to inspect before an analysis
runs end to end. The argument for doing it soon after is that every phase from 7
onward is debugged through the State view, and a graph beats a log for walking
`I` backwards from the entry.
