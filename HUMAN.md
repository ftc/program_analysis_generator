## For the human reviewer

Claude does not act on this section. It is here so the whole process lives in
one place.

- **Keep review sessions under an hour.** Defect detection falls off after
  60 to 90 minutes. Take a break between changes.
- **Go slowly on trust-base code.** Aim for under 300 lines an hour.
  Reviewing faster than about 500 lines an hour misses defects.
- **Read the invariant first, then the tests, then the code.** Check that a
  test actually exercises the invariant before reading the implementation.
- **Look at test diffs closely.** A changed expected value is the most likely
  place for a wrong result to hide.
- **Watch for plausible but wrong.** In this domain the model's errors tend to
  look correct. Ask whether each claimed property is checked by a test.
- **Send oversized changes back.** If a change is too big to review
  carefully, ask for a split rather than skimming it.
- **Approve when it improves the code,** even if it is not perfect. Record
  follow-ups as open items instead of holding the change.
- **Write the core semantics yourself, or specify them tightly.** Lattices,
  transfer functions and soundness conditions are where the model is weakest.
  Let the agent do plumbing, parsing, traversal and tests around them.

## Basis

- Change size, review speed, session length, author annotations:
  SmartBear/Cisco code review study (2,500 reviews, 3.2M lines).
- Focused changes, tests in the same change, build stays green, separate
  refactoring, splitting large changes, approving on improvement: Google
  engineering practices, *Small CLs* and *The Standard of Code Review*.
- Plan before implementing, self-verification through tests, stopping after
  repeated corrections: Anthropic, *Claude Code best practices*.
- Lower accuracy on long-tail and domain-specific code, and the gain from
  giving the model domain knowledge: Kandpal et al. (ICML 2023); *On the
  Effectiveness of LLMs in Domain-Specific Code Generation* (TOSEM).
- Mechanical feedback reducing defects in generated code: *Static Analysis as
  a Feedback Loop* (arXiv 2508.14419).