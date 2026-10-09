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

# The contract

These are the engine's types, exactly as you compile against them.

{{contract}}

# Your reply

The source files of your domain and its tests, each in its own fenced block as
described: nothing else is read.
