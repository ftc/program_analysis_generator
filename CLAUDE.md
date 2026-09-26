# Working agreement

How changes are made in this repository. Read `README.md` for the idea,
`implementation_strategy.md` for the plan, and `glossary.md` for terms before
changing code.

## One change at a time

1. **One change adds one capability that is testable on its own.** It ends in
   something runnable: a passing test, or a command whose output can be
   inspected.
2. **About 200 lines of non-test code.** Tests do not count toward the limit,
   and for trust-base code they will often be larger than the code.
3. **Tests ship in the same change.** Trust-base modules get one test per row
   of every rule table (`implementation_strategy.md` §2, *The trust base*).
4. **Stop after each change.** Summarise what it does, what it deliberately
   leaves out, and how to try it; then wait for review. Do not start the next
   change until the current one is approved.
5. **Shawn commits.** Do not commit, push or open a PR unless asked.
6. **Flag trust-base changes** at the top of the summary: loading, the profile
   check, lifting, lowering, `instrumentReach`, the executor, the certifier.
   They are reviewed on their own, never folded into a larger diff.
7. **Ask, do not decide.** When a change hits a question the docs do not
   settle, stop and ask. When the docs turn out to be wrong, fixing them is its
   own small change, made before the code that depends on it.

## Keeping the docs true

- Decisions go into `implementation_strategy.md` where they apply, marked
  *Decided — Shawn, <date>*. Open ones are marked **[decide]** or listed in §16.
- New or changed terms go into `glossary.md` in the same change.
- Section references (`§5.3`) point at `implementation_strategy.md` unless
  another file is named; keep them resolving when sections move.
- Dated design-session logs (`YYYY-MM-DD-design-session.md`) are history. Do
  not edit them to match later decisions.

## Build

Scala 3 (sbt) for the engine; pure Java 21 for `engine/api`, `engine/probe-lib`
and domains. `sbt test` runs everything. Only `engine/frontend-soot` may compile
against `soot.*`.
