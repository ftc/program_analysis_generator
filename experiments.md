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

### The information ladder: context before an attempt

*Decided — Shawn, 2026-10-07.* The question is whether a model can come up with
a solution, not implement one it is described. So every model starts with the
least information that defines the task, and context is added only when it
fails — one rung at a time, recorded with the attempt, so the answer includes
*which* addition made the difference:

| rung | adds | prompt |
| --- | --- | --- |
| 0 | the reply format, a general task (prove `reach` calls unreachable, soundly; what to track is the model's choice), and the contract | `generator-v1` |
| 1 | which step shapes actually occur (the profile: assignments of constants, locals and `+ − *`; the six comparisons; `randInt`) | |
| 2 | the domain named ("intervals") | |
| 3 | a worked example of another domain (`ref-sign` and its tests) | |
| 4 | worked transfer cases for the target domain (the README's six) | |

Rungs 1–4 are written when rung 0's results call for them. Context added
*before* an attempt is a different thing from the feedback given *after* one
(below); both are recorded.

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

**The models: a Qwen3.5 size ladder.** *Decided — Shawn, 2026-10-07.* Every
model is served by llama.cpp on a dual RTX 3090 machine, as a size ladder
within one family, which separates "smaller models cannot do this" from
differences in training data or chat template between families:

| model | source |
| --- | --- |
| Qwen3.5-0.8B | https://huggingface.co/Qwen/Qwen3.5-0.8B |
| Qwen3.5-2B | https://huggingface.co/Qwen/Qwen3.5-2B |
| Qwen3.5-4B | https://huggingface.co/Qwen/Qwen3.5-4B |
| Qwen3.5-9B | https://huggingface.co/Qwen/Qwen3.5-9B |
| Qwen3.5-27B | https://huggingface.co/Qwen/Qwen3.5-27B |

About a 34× range in steps of 2–3×, so the size where one-shot generation
breaks can be located. **Shawn makes his own Q8 GGUFs** of each, with llama.cpp's
converter and quantizer, from the official repositories: one quantization level
for every size, so size is the only thing that varies, and each model keeps its
own chat template (which carries the thinking switch). Each model's `source`
records the repository, its **revision** (commit), the GGUF file name, **the
SHA-256 of the GGUF produced**, and — until there is a field for it — the
llama.cpp commit used, in the file name. Qwen3.8 27B (below) may be added later
as a side point, a newer generation at the top size, depending on the results.
The 0.8B and 2B will probably mostly fail early (no usable files, or no
compile); that is the floor, and they are cheap enough for extra samples.

**From the Qwen3.5-27B model card** (read 2026-10-07): post-trained, Apache 2.0;
**thinking on by default**, turned off per request with
`chat_template_kwargs: {"enable_thinking": false}`; recommended sampling in
thinking mode for coding: temperature 0.6, top_p 0.95, top_k 20, min_p 0,
presence_penalty 0; recommended output length 32,768 tokens for most queries and
81,920 "for benchmarking on highly complex problems"; 262,144 tokens of context.
The card's summary also mentions "Gated Delta Networks combined with sparse
Mixture-of-Experts"; whether the 27B itself is dense was not settled. The
smaller sizes' cards were not read.

**The first model actually run** was Qwen3.8 27B, for the pipeline check
(campaign `phase10-check-qwen3.8-27b`), at a 6-bit quantization, served by
llama.cpp's OpenAI-compatible server at `http://localhost:8933/v1` (checked
2026-10-06 with `/v1/models`):

| field | as the server reports it |
| --- | --- |
| model id (send as `"model"`) | `/home/s/models/Qwen3.8-27B-GGUF/Qwen3.8-27B-UD-Q6_K_XL.gguf` |
| parameters | 27,320,697,856 |
| file size | 25,913,155,584 bytes |
| context | 262,144 tokens (`n_ctx`, equal to `n_ctx_train`) |
| quantization | `UD-Q6_K_XL` by file name (Unsloth dynamic); the server's `ftype` field says `Q4_K - Small` |
| capabilities | `completion`, `multimodal` |

Source: `https://huggingface.co/unsloth/Qwen3.8-27B-GGUF`, file
`Qwen3.8-27B-UD-Q6_K_XL.gguf` (from Shawn's shell history on the serving
machine). Revision and SHA-256 not yet recorded.

The two quantization labels disagree. The size — about 7.6 bits per weight —
fits the file name's 6-bit mix with some layers kept wider, not a 4-bit model,
so `ftype` is likely a nominal label from the file header; recorded as reported.
Each attempt records the model id, so a different file is a different model.

**Identifying a model.** A Hugging Face repository URL is a pointer, not an
identity: one repository holds many quantization files, and repositories are
re-uploaded under the same name (for chat-template or tokenizer fixes, say). So
each model's config carries a `source` (plan §10): the URL and the **file** say
where to get it, the **revision** (the repository commit) pins the upload, and
the file's **SHA-256** says which bytes it was — the one identifier that holds
wherever the file lives and whatever it is called, and which matches the hash
Hugging Face shows for the file. Compute it once on the serving machine
(`sha256sum <file>`). The model is not the whole configuration: the chat
template, context size, server sampling defaults and server version also change
behaviour, which is why each attempt also records the request parameters and
the server's own report. A fuller provenance scheme is being worked on
separately by a coworker of Shawn's; these fields are a placeholder it can
replace. The goal is to
see how the system behaves across levels of model capability — frontier models
would likely outpace the setup quickly — so a few smaller models should be
benchmarked beside it. **Open, to discuss before E3:** which smaller models,
and at what sizes and quantizations.

For the record, what was installed on Shawn's Mac (Ollama, `localhost:11434`,
checked 2026-10-06 with `/api/tags`) — no longer the plan, since every model now
runs on llama.cpp:

| model (exact Ollama name) | parameters | quantization |
| --- | --- | --- |
| `llama3.2:3b` | 3.2B | Q4_K_M |
| `gemma3:latest` | 4.3B | Q4_K_M |
| `qwen2.5-coder:14b` | 14.8B | Q4_K_M |

The 27B model is not among them; it is served by llama.cpp on port 8933
(above). Open WebUI on `localhost:3000` is a chat front end over these servers,
not an endpoint the campaign uses.

Run the generator and the adversary as **different** models (§10); E3 should
include a few cross-pairings, since a weak adversary would make every generator
look sound.

A frontier model as the generator, run once at the top rung, is a useful
ceiling: it says whether a rung is hard because the task is hard or because the
model is small.

---

## The report

*Decided — Shawn, 2026-10-07.* Results are written up as they come, in a LaTeX
report kept in the repository, with tables generated from the attempt records
rather than copied by hand.

- **`report/`** — the LaTeX source (`report.tex`), built with `latexmk`. It
  `\input`s generated tables from `report/tables/`.
- **`campaign report`** — reads `results/<campaign>/` and writes the tables.
  Scala, in the campaign module (one language, plan §12), reading the records
  with the same codecs that wrote them. Rerunnable at any time; it only ever
  overwrites `report/tables/`.
- **Manual inspection is a column the script never writes.** Shawn's judgment of
  each generated domain lives in a hand-edited file, `report/inspection.json`,
  keyed by attempt id; the script merges it in, so regenerating a table never
  loses a note, and an attempt not yet inspected shows a blank. Its shape, every
  field optional:

  ```json
  { "attempts": {
      "e1-rung0-qwen3.5-27b/attempt-001": {
        "closestDomain": "intervals", "soundnessByEye": "looks sound",
        "quality": "tests meaningful", "acceptable": true, "notes": "..." } } }
  ```

  `campaign report [--prefix e1-rung0-]` reads every campaign whose name starts
  with the prefix, smallest model first (the size is read from the name), and
  writes `report/tables/table1.tex`, `table2.tex`, and `prompt.txt` — the prompt
  exactly as sent, taken from the records, for the appendix. Output is
  deterministic, so regenerating unchanged results changes nothing in git; an
  inspection naming an attempt that is not among the campaigns read is warned
  of.

### Table 1 — one shot, rung 0, the smoke corpus

One row per attempt; models grouped, smallest first.

| column | meaning |
| --- | --- |
| model | as recorded: name, parameters, quantization |
| sample | which of the N samples for that model |
| files | source files read from the reply (0: no usable reply) |
| builds | compiled by the Gradle template |
| own tests | its own JUnit tests pass (how many) |
| loads | `pag` found exactly one domain class and constructed it |
| one column per target | R refuted, A alarm, ✗ refuted a reachable target (unsound), I inconclusive — did not converge (iteration limit or deadline), E domain failure (threw or returned null), H hung (killed at the wall-clock bound), – not run |
| proved | refutations among the four unreachable targets |
| caught | sound so far: no reachable target refuted |
| cost | prompt and completion tokens; wall-clock time |
| inspection | Shawn's: closest existing domain, soundness by eye, quality, acceptable, notes (below) |

The smoke corpus is never shown to a model; it is how a domain is judged, not
part of what it is told (plan Phase 10).

### The setup, to discuss before the first real run

An iteration or two on the experimental setup comes before rung 0 is run for
real. Open questions:

1. ~~**Models.**~~ *Decided:* the Qwen3.5 ladder, 0.8B to 27B, as Q8 GGUFs Shawn
   makes, on llama.cpp (E3).
   **The run settings** — chosen by Claude at Shawn's delegation, 2026-10-07,
   recorded here and in `config/e1-rung0-qwen3.5.example.json` (copy it once
   per size and fill in the placeholders; `campaign` refuses a config that still
   has any). Expected to change: every attempt records the settings it ran with,
   and a campaign pins them, so changing one means a new campaign.

   | setting | value | why |
   | --- | --- | --- |
   | `temperature`, `topP`, `topK`, `minP`, `presencePenalty` | 0.6, 0.95, 20, 0.0, 0.0 | the 27B card's recommendation for coding in thinking mode; the same for every size, so size is the only variable |
   | `thinking` | `true`, explicit | how the models are meant to be used; recorded per campaign; thinking off is a later, cheaper variation |
   | `maxTokens` | 32,768, every size | the card's "most queries" length; one budget for all sizes keeps them comparable. The pipeline check passed 47,000 without finishing, so expect some "ran out of tokens" at first — that is data, and the budget is the first setting to revisit (the card suggests 81,920 for complex problems) |
   | `timeoutSeconds` | 2,700 (45 minutes) | 32,768 tokens at about 28 tokens/s is about 20 minutes for the 27B; the rest is margin for a busy server. A timeout is not retried |
   | `retries` | 3 | for connection failures, 429 and 5xx only |
   | samples | 5 per size | a first table quickly; more for the cheap small sizes if their results are noisy |
   | seed | unset | sampling varies between samples by design; GPU arithmetic is not bit-reproducible anyway |
   | llama.cpp context (`-c`, a server setting) | 65,536 | covers prompt plus budget with room to spare, and a far smaller KV cache than the full 262,144; a server setting, so note it with the model |
   | llama.cpp parallel slots (`-np`, a server setting) | 1 | runs are one request at a time, so extra slots only let other requests share the GPUs with an attempt, adding noise to its timings; and with several slots llama.cpp may divide `-c` between them (unless `--kv-unified`), leaving too little context per request. Other users of the server wait instead; use a second server on another port for them. Check: `/slots` shows one slot with `n_ctx` ≥ 65,536 |
   | llama.cpp `--reasoning-budget` | unset | the request's `maxTokens` caps thinking and answer together; a separate thinking cap is a later variation |

   llama.cpp's server documentation confirms it accepts `chat_template_kwargs`
   (`{"enable_thinking": ...}`), `top_p`, `top_k`, `min_p`, `presence_penalty`,
   `max_tokens` and `seed` per request (read 2026-10-07). A reply stopped at the
   budget (`finish_reason: "length"`) is its own outcome, "ran out of tokens", in
   the record, `campaign status` and Table 1.
2. **Samples and sampling.** How many samples per model; temperature; whether to
   fix seeds where the server allows.
3. **Reasoning and budgets.** Qwen3.x thinks before answering
   (`reasoning_content`): on or off; the token budget, which counts the
   thinking; and the client's timeout. The first real sample (Qwen3.8 27B,
   2026-10-07) generated over 47,000 tokens in 30 minutes, about 28 tokens/s,
   with no cap, and had not finished when the client's 30-minute timeout fired;
   a bug then retried it from scratch, and the run was stopped. Fixed: a timeout
   is no longer retried.
4. ~~**What a row means.**~~ *Decided — Shawn, 2026-10-08.* A row is one
   attempt, judged at the first stage it fails: no reply, ran out of tokens, no
   files, did not compile, did not load — or evaluated, with its cells and
   *proved* count. **Acceptable has two bars, reported separately:**
   - **mechanical** — passes the smoke tests: evaluated, no ✗ (not caught
     unsound), and proves at least one of the five unreachable targets (Phase
     10's done-when, per attempt);
   - **inspection** — Shawn judges it acceptable (below).

   **Unsound** is its own category, never folded into "failed". Per model, the
   summary (Table 2) gives counts, never percentages, since five samples are few:
   acceptable by each bar ("2/5 mechanical, 1/5 inspection"), the median *proved*
   among mechanically acceptable attempts, and where the others stopped.
5. ~~**The inspection rubric.**~~ *Decided — Shawn, 2026-10-08.* Per attempt, in
   `report/inspection.json`, keyed by attempt id:
   - **closest existing domain** — constants, signs, intervals, intervals with
     widening, another (named), or none coherent;
   - **soundness by eye** — looks sound, suspicious (where), or clearly unsound;
   - **quality** — whether its own tests are meaningful or trivial, and anything
     notable;
   - **acceptable** — yes or no: the inspection bar;
   - **notes** — free text.

   The fixed fields are short lists, so they can be counted across the table.
6. ~~**Fairness across models.**~~ *Decided — Shawn, 2026-10-08.* The same
   prompt, settings, corpus and hardware for every model, guaranteed per
   campaign by its pin. Each model's own chat template and thinking behaviour are
   part of the model, noted rather than controlled. Time is reported per attempt
   but never compared across sizes as a measure of quality.
7. ~~**The report's shape.**~~ *Decided — Shawn, 2026-10-08.* (1) Setup: the
   models, the run settings, the rung-0 prompt in full in an appendix, the
   corpus. (2) Table 1: one row per attempt, grouped by model. (3) Table 2: per
   model, acceptable by each bar, median *proved*, where the rest stopped.
   (4) Observations, written by hand from the inspection notes. No figures yet;
   acceptable against model size is the first, once the five sizes are run.

## Order

E1 starts at crude Phase 10 with R0–R1 and a hand-written handful of probes per
rung, then grows with the scoring corpus (Phase 7). E2 needs the adversary
(crude Phases 8 and 11). E3 is E1 repeated per model once E1's procedure is
stable.
