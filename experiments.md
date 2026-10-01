# Experiments

The questions the first experiments are meant to answer, and how. The plan in
`implementation_strategy.md` builds the machinery; this file says what to do
with it. Added 2026-09-30 at Shawn's request; nothing here changes the engine
work before Phase 10.

The overall question: **at what point does a small open-weight model stop being
able to write a sound, useful abstract domain in one shot, and how much feedback
does it take to get past that point?** Find the exact step where one-shot
generation breaks, then measure what each kind of feedback buys.

---

## E1 — The complexity ladder

Raise the difficulty one rung at a time and record, for each rung and model,
whether the model can produce an acceptable domain with no feedback, and if
not, how much feedback it needs.

### The rungs

Each rung is a slice of the scoring corpus (Phase 7) whose targets need one
more piece of reasoning than the rung below. A domain is judged only on its own
rung and those below it.

| rung | programs | what a domain needs to prove the targets |
| --- | --- | --- |
| R0 | straight-line constants; targets guarded by comparisons of constants | constants, and `assume` on them |
| R1 | one input, one branch on a literal | intervals and backward `assume` (needs constant substitution, §5.7) |
| R2 | `+` and `-` on inputs before the branch | backward transfer through addition and subtraction |
| R3 | `*`, `negate` | sign reasoning; multiplication across zero |
| R4 | loops with a counter | widening, and a loop invariant at the head |
| R5 | two inputs related to each other (`x < y`, then `y < x`) | a relational domain (zones or better, §16) |
| R6 | nested loops, accumulators | relational reasoning and widening together |

R0–R4 are within reach of the interval reference domain; R5 and R6 are where a
relational domain becomes necessary, and where the reference fixture will need
to follow (§16).

### What counts as acceptable

A generated domain is **acceptable at a rung** when it compiles against
`api.jar` alone, loads, passes the smoke test (Phase 6), survives the adversary
within the calibrated budget (Phase 8), and proves at least a set fraction of
the rung's provable targets. Soundness is the gate; the proof fraction is the
bar for "useful", set per rung once the reference domain's own fraction is known.

### Feedback, in increasing cost

When a one-shot attempt is not acceptable, feedback is given in rounds, and the
kind is recorded, since which kind unblocks the model is part of the answer:

1. **Build** — `javac` errors.
2. **Load and smoke** — the smoke test's failures: which `Step` form or operator
   it could not handle.
3. **Unit cases** — the README's worked transfer cases, run against it.
4. **Counterexamples** — reaching runs from the adversary: a program, inputs,
   and the location the domain wrongly refuted.
5. **Precision** — targets it failed to prove that the reference domain proves.

### Also try: building the domain in pieces

One-shot means the whole domain at once. The same rungs can be run with the
domain generated piece by piece — state type, then `top`/`bottom`/`isBottom`,
then `entails`/`join`/`widen`, then `transfer` — to find *which part* fails
first, rather than only *that* the whole thing failed.

### What to record

For every (model, rung, attempt): the prompt and its version, temperature and
sample number, each round's feedback kind and size, the outcome after each
round (build, load, smoke, adversary verdict, proof count), wall-clock and token
cost. Several samples per cell, since one sample says little about a sampled
model. The records are `domain.json` and `run.json` (§3) plus the campaign's
attempt log.

### What the answer looks like

Per model: **the highest rung reached one-shot**, and a curve of **feedback
rounds needed against rung**, broken down by feedback kind. The step where the
one-shot rate falls off is the finding E1 exists for.

---

## E2 — Diminishing returns of counterexamples

Counterexamples are the most expensive feedback (each comes from an adversary
search and a JVM run) and the most informative. Two curves:

- **Adversary budget against kill rate.** On the mutant corpus (Phase 8) and on
  generated domains, how the kill rate grows with the adversary's budget
  (probes tried, rounds, tokens). Where the curve flattens is the stopping rule
  §16 Q1 asks for — set by measurement, not guessed.
- **Counterexamples against domain quality.** For a generator repairing a
  domain from reaching runs, how many counterexamples it takes before the domain
  stops being rejected, and whether later ones still change anything or the
  domain has stopped improving. Measured per rung, since the answer likely
  depends on how hard the rung is.

Both curves come from the same records as E1, so E2 needs no machinery of its
own beyond varying the budget.

---

## E3 — Which models

E1 run across several open-weight models, at a few sizes, to separate "small
models cannot do this" from "this model cannot do this". Candidates, by
family; **versions and sizes change quickly, so check what is current, and each
model's licence, before running**:

| family | candidates to consider | why |
| --- | --- | --- |
| Qwen | Qwen2.5-Coder (7B, 14B, 32B); Qwen3 and Qwen3-Coder, including the small mixture-of-experts sizes | the planned default; a size ladder within one family |
| DeepSeek | DeepSeek-Coder-V2-Lite; the R1 distilled models | strong code models; distills test whether reasoning-style training helps |
| OpenAI | gpt-oss-20b, gpt-oss-120b | open-weight reasoning models at two sizes |
| Mistral | Devstral, Codestral | code-specialised; check licences |
| Meta | Llama 3.x (8B, 70B) | widely used baseline |
| Google | Gemma 3 (up to 27B) | small, strong general models |
| Microsoft | Phi-4 | small, trained heavily on reasoning |

Run the generator and the adversary as **different** models (§10); E3 should
include a few cross-pairings, since a weak adversary would make every generator
look sound.

A frontier model as the generator, run once at the top rung, is a useful
ceiling: it says whether a rung is hard because the task is hard or because the
model is small.

---

## Order

E1 starts at crude Phase 10 with R0–R1 and a hand-written handful of probes per
rung, then grows with the scoring corpus (Phase 7). E2 needs the adversary
(crude Phases 8 and 11). E3 is E1 repeated per model once E1's procedure is
stable.
