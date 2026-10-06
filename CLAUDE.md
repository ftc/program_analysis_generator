# Working agreement

How changes are made in this repository. Read `README.md` for the idea,
`implementation_strategy.md` for the plan, `experiments.md` for what the plan is
for, and `glossary.md` for terms before changing code.

## One change at a time

1. **One change adds one capability that is testable on its own.** It ends in
   something runnable: a passing test, or a command whose output can be
   inspected. Small means focused on one thing, not just few lines.
2. **Size limits, counted in non-test code.** Tests do not count, and for
   trust-base code they will often be larger than the code.
    - Ordinary code: about 200 lines. Never more than 400.
    - Trust-base code: about 100 lines.
    - If a change will not fit, stop and propose a split before writing it.
3. **Plan first when the change touches several files or unfamiliar code.**
   Write a short plan: files touched, the capability added, and the invariant
   it must preserve. Wait for approval, then implement. Skip the plan when the
   change fits in one sentence.
4. **Refactoring is its own change.** If new code needs existing code
   reshaped, do the reshape first as a separate change with no behaviour
   change. The existing tests must still pass unmodified.
5. **Tests ship in the same change.** Trust-base modules get one test per row
   of every rule table (`implementation_strategy.md` §2, *The trust base*).
6. **Check mechanically, not by assertion.** Before stopping, run `sbt test`
   and report the result. If a property of the change can be checked by a
   test, write the test instead of claiming the property in the summary. For
   trust-base code, prefer tests with independently known answers: small
   hand-worked examples, comparison against concrete execution, and algebraic
   laws where they apply.
7. **Never weaken a test to make it pass.** If an existing test seems wrong,
   stop and say so. Changing an existing test's expected result is a flagged
   change of its own.
8. **Every change leaves the build green.** No change depends on a later
   change to compile or pass.
9. **Stop after each change** and write a summary with these parts, in order:
    - **Trust base:** yes or no. If yes, name the parts touched: loading, the
      profile check, lifting, lowering, `reach`, the executor, the certifier,
      the domain-vocabulary converter.
    - **What it does,** in one or two sentences.
    - **Invariant:** the property the change preserves or establishes, and
      which test checks it.
    - **Non-obvious choices,** each with a one-line reason.
    - **Deliberately left out.**
    - **How to try it.**
    - **Test result** from `sbt test`.

   Then wait for review. Do not start the next change until the current one
   is approved.
10. **Shawn commits.** Do not commit, push or open a PR unless asked.
11. **Trust-base changes are reviewed on their own,** never folded into a
    larger diff.
12. **Ask, do not decide.** When a change hits a question the docs do not
    settle, stop and ask. When the docs turn out to be wrong, fixing them is its
    own small change, made before the code that depends on it.
13. **Do not guess library behaviour.** This domain is thin in training data.
    Before relying on how a Soot or other library API behaves, read its source
    or docs and cite what you found in the summary. If still unsure, ask.
14. **After two failed attempts at the same fix, stop.** Report what was tried
    and what happened rather than trying a third variation.

## Keeping the docs true

- Decisions go into `implementation_strategy.md` where they apply, marked
  *Decided — Shawn, <date>*. Open ones are marked **[decide]** or listed in §16.
- New or changed terms go into `glossary.md` in the same change.
- Section references (`§5.3`) point at `implementation_strategy.md` unless
  another file is named; keep them resolving when sections move.
- Dated design-session logs (`YYYY-MM-DD-design-session.md`) are history. Do
  not edit them to match later decisions.

## Build

Scala 3 (sbt) for the engine, including the IR in `engine/ir`; pure Java 21 for
`engine/api` (the domain contract and vocabulary), `engine/probe-lib` and
domains. `sbt test` runs everything. Only `engine/frontend-soot` may compile
against `soot.*`.

## Immutable by default

In Scala, use `val` and immutable collections. A `var`, a `mutable` collection
or a builder needs a reason that an immutable form would be clearly worse —
measured cost, or a loop whose immutable form is markedly harder to read — and
that reason goes in a comment beside it. Watch for mutation hidden by syntax:
`m(k) = v` on a mutable map is `m.update(k, v)`. Existing mutable code is
converted as its own refactoring change (rule 4), not in passing.
*Decided — Shawn, 2026-10-05.*

## Public members carry explicit types

In Scala, every public `def`, `val` and `given` — in classes, objects and
traits, test code included, and overrides in anonymous classes — states its
type: `def join(a: Flag, b: Flag): Flag`, not `def join(a: Flag, b: Flag)`.
Local `val`s inside a method body may leave it inferred. This keeps IntelliJ's
inspections quiet and makes a signature readable without its body.
*Decided — Shawn, 2026-10-05.*

## Inform the user if their terminology is inconsistent

The file `glossary.md` contains the current meanings of all non-standard vocabulary used in this project. If the user uses some of this vocabulary in a way that does not appear to be consistent, ask for clarification.
