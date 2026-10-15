# Advisor intro notes — 2026-10-15

Notes from a session drafting how to describe Quoala Code to Brian, founder of
Pretxt. Brendan Walsh, Shawn's current advisor, is making the introduction. The
goal of the meeting is to sharpen Quoala Code's customer segments.

Sources read: `../README.md`, `Oct13_presentation_outline.md`, `../findings.md`,
`~/why_exercise/WhyBehindLenticular.txt`, `~/why_exercise/peak_valley_exercise.txt`.

## Brendan's ask

> "If you could give me a couple sentences as an introduction to Quoala Code for
> my note to Brian at Pretxt, that would be great. Keep it high level with the
> problem statement and thesis for the business."

## Final intro (for Brendan's note)

> Quoala Code helps engineering teams check AI-written code against the rules
> their software depends on but nothing enforces, such as replay determinism in
> durable execution or authentication on every API endpoint. Today, checking
> even one such rule takes a purpose-built analyzer from a team of specialists,
> so violations pass review and ship silently. Our bet is that these rules are
> all instances of one problem, so a single engine can generate a checker for
> each rule and give reproducible verdicts instead of an AI reviewer's best
> guess. Shawn is looking for help sharpening which customer segment to lead
> with.

To cut it to two sentences, drop sentence 2 and fold the problem into sentence
1: "…rules that today go unchecked because each needs its own expert-built
analyzer."

## Wording decisions

| use | for | instead of | why |
| --- | --- | --- | --- |
| **reproducible verdicts** | what the tool gives the customer | "deterministic rules" | avoids a clash with the Temporal property |
| **replay determinism** | the Temporal / durable-execution property | "deterministic" alone | keeps "deterministic" for one meaning |
| **rules nothing enforces** | the kind of rule we check | "implicit correctness rules" | plainer; names the gap the product fills |
| **our bet is that** | introducing the thesis | "our thesis is that" | shorter; honest that it isn't proven yet |
| **Quoala Code** | company name | "Lenticular" (in `why_exercise`) | the name Brendan uses |

Avoid "consistent" in customer-facing text, because it is already overloaded
in this project. Avoid claiming the tool "proves correct".

If Brian isn't from developer tools, "replay determinism in durable execution"
may still be jargon. A plainer alternative: "workflows that must replay
identically after a crash".

## Reusable pieces for the one-pager

**Customer segment (current hypothesis)**

> Engineering teams building on frameworks with implicit correctness rules,
> such as replay determinism in durable execution or authentication on every
> API endpoint. One missed case passes review, ships silently, and fails in
> production, and AI-written code makes those cases more common.

(This was written before "rules nothing enforces" was chosen. Consider swapping
that phrase in.)

**Reproducible verdicts (the honest limitation)**

> An LLM reviewer can answer differently each time you ask. Quoala Code turns
> each of your rules, such as replay determinism for a Temporal workflow, into
> a program analysis that gives the same verdict on the same code every time.
> When the analysis is wrong, whether it misses a violation or raises a false
> alarm, the mistake can be reproduced. You report it as a bug, and the next
> version is fixed and tested against it.

Optional fifth sentence for technical readers: "A missed violation comes with a
program that demonstrates it."

**The core argument (from the first draft and the Oct 13 outline)**

- The problem: AI made writing code cheap, but checking it is still
  expensive. The rules teams care about are specific to their business or
  framework. From interviews:
    - replay determinism
    - authentication on every endpoint
    - no use-before-create races
    - no shipping before payment
    - no blocking the UI thread
    - no hard-coded keys
- Why it's hard today: each rule needs a purpose-built checker built by
  experts. Google used a team of about ten for hard-coded keys and similar
  Android properties, and Temporal has its own lint team. The market is a long
  tail of niche rules.
- The insight: any rule can be rewritten as "can execution reach this line?"
  Choosing the abstraction is the expert work, and Quoala Code generates it
  with an LLM.
- Trust: generated analyses are attacked by a separate adversary that writes
  real programs to contradict them. Analyses that are contradicted are
  discarded. Among the survivors, the most precise one wins.
- Why Shawn: the Fortran-to-C satellite code story. A PhD on sound static
  analysis of Android callback order, where earlier published tools missed
  obviously reachable methods.
- Status:
    - The previous iteration was Temporal replay determinism.
    - The engine and the experiment harness exist.
    - Small open models (9B parameters and under) don't yet compile against
      the interface in one shot. Build feedback is the next experiment.

## Planned one-pager structure (for the meeting)

1. **What we do.** The one line plus the reproducible-verdicts paragraph.
2. **Current segment hypothesis.** The customer sentence, labeled as a
   hypothesis.
3. **What we've learned.** The six interview rules, plus the Temporal
   iteration.
4. **Where we're stuck.** The beachhead question below, asked directly.

## Open queue

1. Whether to mention Hau and Chi. Probably not in the intro, but maybe in the
   meeting.
2. Write the one-pager using the structure above.
3. Beachhead: framework rules or team-specific rules?
    - Framework rules (e.g. replay determinism) can be sold once to the vendor,
      or to every team on that framework.
    - Team-specific rules (e.g. authentication on every endpoint) are sold team
      by team.
    - This may stay open for Brian to weigh in on.
4. Find out Brian's background, so the one-pager's jargon level can be
   calibrated. A web search for "pretxt.ai" on 2026-10-15 found nothing.
