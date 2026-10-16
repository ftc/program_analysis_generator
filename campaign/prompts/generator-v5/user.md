# Task

Write an abstract domain for the contract below. The engine uses it to prove
that calls to `reach(id)` in small Java programs can never execute: the more
such calls your domain proves unreachable, the better. What your domain tracks
about the program's variables is up to you.

Requirements:

- Package `pag.domains.gen`. Exactly one public, concrete class implementing
  `pag.api.Domain`, with a public constructor taking no arguments.
- Java 21, using only `java.*` and `pag.api.*`.
- Include JUnit 5 tests (`org.junit.jupiter.api`) under `test/`. They are
  compiled and run when your domain is built.
- **Soundness comes first.** A domain that excludes a program state it cannot
  rule out is rejected outright, whatever else it proves. Losing precision is
  allowed; dropping a state that can reach the target is not.

# How the engine uses your domain

The analysis is goal-directed and runs backward. It starts at one `reach(id)`
call, the target, and keeps one state of your domain per program location. The
target starts at `top()` and every other location at `bottom()`. The engine
then works backward through the method: for each step from a location `A` to a
location `B`, it computes `transfer(step, state at B)` and joins the result
into the state at `A`, also widening at loop heads. It repeats this until no
state changes, so the state at each location over-approximates the program
states there from which execution can reach a state already admitted further
on, and so eventually the target.

If, at that fixed point, the state at the method's entry is still bottom
(`isBottom` returns true), no execution can reach the target: it is proved
unreachable. Before accepting the proof the engine re-checks the finished
states: for every step, `entails(transfer(step, state at B), state at A)` must
hold, and `entails(top(), state at the target)` must hold. So `entails` must
return true whenever its answer is true, or a proof is lost.

# The soundness condition

Each call `pre = transfer(step, post)` is a backward Hoare triple
`{pre} step {post}`, read right to left: if executing `step` from a program
state σ′ ends in a state σ, and `post` admits σ, then `pre` must admit σ′.

`pre` may admit more states than that; this loses precision and is allowed.
It must never admit fewer. The contract's comment on `transfer` gives what this
means for each step form.

A target is proved only when some `transfer` returns a state admitting fewer
states than before, until a state admitting none reaches the entry: when no
state before a step can end in a state `post` admits, the sound and precise
answer is a state for which `isBottom` is true. A domain whose `transfer`
never does this — returning `top()`, say — is sound but proves nothing.

# Writing it in Java

Start from this shape, replacing `<YourDomain>` with your class name and
filling in every method:

```java
package pag.domains.gen;

import java.math.BigInteger;
import java.util.*;
import pag.api.*;

public class <YourDomain> implements Domain<<YourDomain>.State> {

    /** Your abstract state. */
    public static final class State {
    }

    public <YourDomain>() {
    }

    @Override public String name() { throw new UnsupportedOperationException("write this"); }
    @Override public State top() { throw new UnsupportedOperationException("write this"); }
    @Override public State bottom() { throw new UnsupportedOperationException("write this"); }
    @Override public boolean isBottom(State s) { throw new UnsupportedOperationException("write this"); }
    @Override public boolean entails(State a, State b) { throw new UnsupportedOperationException("write this"); }
    @Override public State join(State a, State b) { throw new UnsupportedOperationException("write this"); }
    @Override public State widen(State a, State b) { throw new UnsupportedOperationException("write this"); }

    @Override
    public State transfer(Step step, State post) {
        switch (step) {
            case Step.Assign assign -> { /* assign.target(), assign.source() */ }
            case Step.Assume assume -> { /* assume.cond() */ }
            case Step.Call call -> { /* call.target() is an Optional; call.callee(), call.args() */ }
        }
        throw new UnsupportedOperationException("write this");
    }
}
```

Points that are easy to get wrong:

- A nested type is not in scope in its own class's header: write
  `Domain<<YourDomain>.State>`, not `Domain<State>`.
- The contract's records are nested in their interfaces: write `Step.Assign`,
  `RVal.IntConst`, `RVal.Binop`, `LVal.Local`, or import them by those names.
- Records are read through accessor methods, not fields: `assign.target()`,
  not `assign.target`. `LVal` itself has no `name()`; match `LVal.Local` first.
- A switch over an `RVal` covers `RVal.IntConst`, `RVal.Binop`, `LVal.Local`
  and `LVal.StaticField`.
- Values are `java.math.BigInteger`, not `int` or `long`: write
  `BigInteger.valueOf(5)`, not `5`, where a `BigInteger` is expected.
- `const` and `goto` are reserved words in Java and cannot name a variable.

# The contract

These are the engine's types, exactly as you compile against them.

{{contract}}

# How to work

- Your reply, thinking and answer together, is limited to 245,760 tokens. A
  reply cut off before its last code block closes is not built, so it proves
  nothing. A simple sound domain that you finish is worth more than an
  elaborate one that is cut off.
- Work out your design in your thinking, then write each file once, in your
  reply. Do not draft the code in your thinking first.
- Do not check every case by hand in your thinking. Put the cases in your
  JUnit tests; they are run when your domain is built.

# Your reply

The source files of your domain and its tests, each in its own fenced block as
described: nothing else is read.
