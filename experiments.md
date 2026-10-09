# Experiments

The questions the first experiments are meant to answer, and how. The plan in
`implementation_strategy.md` builds the machinery; this file says what to do
with it. Added 2026-09-30 at Shawn's request.

The overall question: **at what point does a small open-weight model stop being
able to write a sound, useful abstract domain in one shot, and how much feedback
does it take to get past that point?** Find the exact step where one-shot
generation breaks, then measure what each kind of feedback buys.

---

## E1 — The complexity ladder

Raise the difficulty one rung at a time and record, for each rung and model,
whether the model can produce an acceptable domain with no feedback, and if
not, how much feedback it needs. The first run is rung 0 of the information
ladder, one shot, on the smoke corpus: Table 1.

### Running E1, step by step

Shawn runs the experiments; these are the steps, once per model size. Start with
**one sample of a small size**, to see a real reply go through the whole
pipeline before spending hours on the 27B.

**Everything runs on the Linux GPU server** (*Decided — Shawn, 2026-10-08*): the
model server, `campaign`, the builds and the report, from a clone of this
repository there. Set it up once as in `README.md`, *Setup on a new machine*,
including steps 4 and 5 (git, tmux, LaTeX; llama.cpp and the model-download
tools). **Run campaigns inside `tmux`** (or
`screen`): a campaign takes hours, and an SSH disconnect would kill a plain
shell's sbt. Finished attempts survive that — a rerun fills in only what is
missing — but the attempt in progress would be lost.

1. **Make the model and serve it.** On the server, with the model's
   `source` URL from the table below:

   ```
   bash scripts/serve_model.sh https://huggingface.co/Qwen/Qwen3.5-<size>
   ```

   The script pins the repository's current **revision** (its commit on
   Hugging Face), downloads it, converts it with llama.cpp's converter to a
   BF16 GGUF and quantizes that to Q8_0, then deletes the download and the
   BF16 file. The result is
   `~/models/<model>-GGUF/<model>-Q8_0-llamacpp-<commit>.gguf`, named after
   the llama.cpp commit that made it. Beside it, `<same>.source.json` holds the
   config's `source` block: URL, file, revision and SHA-256. The script then
   runs

   ```
   llama-server -m <the Q8 GGUF> -c 65536 -np 1 --port 8933 -ngl all
   ```

   (`--jinja` is on by default and is needed for the thinking switch;
   `-ngl all` makes a model that does not fit fail instead of running partly
   on the CPU). Arguments after the URL go to `llama-server`. If the GGUF
   already exists, the script skips straight to serving. It refuses to run
   when the llama.cpp checkout (`LLAMA_CPP`, default `~/software/llama.cpp`)
   is not at the commit `llama-server --version` reports, so the converter
   and the server always come from the same llama.cpp. Upgrade llama.cpp
   before a ladder, not partway through it: every size should run on the
   same server.

   Check that `curl -s http://localhost:8933/slots` shows **one slot** with
   `n_ctx` of at least 65,536.
2. **Check its config.** Before serving, the script writes
   `config/e1-rung0-qwen3.5-<size>.json`, copied from
   `config/e1-rung0-qwen3.5.example.json` if it does not exist yet, with the
   model id (the GGUF's path, which llama-server reports at
   `curl -s http://localhost:8933/v1/models` when no `--alias` is given) and
   the `source` block from its `.source.json`. An existing config keeps its
   other fields; the script says so on stderr when it replaces a filled-in
   model or source. Check that the id matches `/v1/models`. `campaign`
   refuses a config that still has a placeholder.
3. **Run it**, in a `tmux` window (`tmux new -s e1`; detach with Ctrl-b d,
   reattach with `tmux attach -t e1`):

   ```
   bash scripts/common.sh
   sbt "campaign/run generate --config config/e1-rung0-qwen3.5-<size>.json --campaign e1-rung0-qwen3.5-<size> --samples 5"
   ```

   One campaign per model, named `e1-rung0-<model>`; `generate` uses the
   current prompt, `generator-v2`, unless `--prompt` says otherwise. The first
   run pins the campaign's inputs (settings, prompt, corpus, profile). Rerunning
   with the same inputs only fills in samples that have no `attempt.json`, and
   never rewrites one; rerunning with any input changed deletes
   `results/<campaign>/` and starts afresh, saying so — commit the old attempts
   first if they are worth keeping, since git history is then their only copy.
4. **Watch it**, in a second `tmux` window (Ctrl-b c):

   ```
   sbt "campaign/run status --campaign e1-rung0-qwen3.5-<size> --every 5"
   ```

   Progress, the live generation (tokens, rate, time before the timeout), the
   finished attempts, and warnings — above all, the same failure several times
   running, which points at the prompt or the reply format.
5. **Inspect** each attempt's `results/<campaign>/attempt-NNN/` — `attempt.json`
   (prompt, reply, reasoning, build log, results) and `domain/` (the model's
   code) — and record the judgment in `report/inspection.json`
   (*The inspection rubric*, below).
6. **Write it up.**

   ```
   sbt "campaign/run report"
   (cd report && latexmk -lualatex -outdir=build report.tex)
   ```

   Then commit `results/`, `report/inspection.json` and `report/tables/` on the
   server and push. Inspecting can happen anywhere after pulling, as long as
   `inspection.json` is committed back.

The first one-shot table runs without a container: the model's domain and tests
execute directly on the Linux machine — a desktop PC that only Shawn uses, which
also holds the models. *Risk accepted — Shawn, 2026-10-08* (first for his Mac,
then again for this machine); the container (a crude Phase 9) comes right
after.

### The first run: rung 0, one shot, the smoke corpus

**The models: a Qwen3.5 size ladder, plus Qwen3.8 27B.** *Decided — Shawn,
2026-10-07; Qwen3.8 27B added 2026-10-08.* One family for the ladder, so size is
separated from differences in training data and chat template; all on
llama.cpp; each a Q8 GGUF Shawn makes from the official `Qwen/` repository, so
the quantization is the same for every model and each keeps its own chat
template (which carries the thinking switch):

| model        | source                                   |
|--------------|------------------------------------------|
| Qwen3.5-0.8B | https://huggingface.co/Qwen/Qwen3.5-0.8B |
| Qwen3.5-2B   | https://huggingface.co/Qwen/Qwen3.5-2B   |
| Qwen3.5-4B   | https://huggingface.co/Qwen/Qwen3.5-4B   |
| Qwen3.5-9B   | https://huggingface.co/Qwen/Qwen3.5-9B   |
| Qwen3.5-27B  | https://huggingface.co/Qwen/Qwen3.5-27B  |
| Qwen3.8-27B  | https://huggingface.co/Qwen/Qwen3.8-27B  |

The Qwen3.5 sizes span about 34× in steps of 2–3×, so the size where one-shot
generation breaks can be located. The 0.8B and 2B will probably mostly fail
early; that is the floor, and they are cheap enough for extra samples.
**Qwen3.8 27B is part of the first run** as a second point at the top size: a
newer generation at the same size as Qwen3.5-27B, so the two 27B rows separate
a generation's gain from size. It too is a Q8 GGUF Shawn makes from the official
`Qwen/Qwen3.8-27B` — not the Unsloth `UD-Q6_K_XL` file the pipeline check used
(*Record*, below), so its results are not comparable with that run's.

**The run settings.** Chosen by Claude at Shawn's delegation, 2026-10-07, and
written into the example config. Expected to change: every attempt records the
settings it ran with, and a campaign pins them.

| setting                                                  | value                   | why                                                                                                                                                                                                                                         |
|----------------------------------------------------------|-------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `temperature`, `topP`, `topK`, `minP`, `presencePenalty` | 0.6, 0.95, 20, 0.0, 0.0 | the 27B card's recommendation for coding in thinking mode; the same for every size                                                                                                                                                          |
| `thinking`                                               | `true`, explicit        | how the models are meant to be used; thinking off is a later, cheaper variation                                                                                                                                                             |
| `maxTokens`                                              | 32,768, every size      | the card's "most queries" length; one budget for all sizes. The pipeline check passed 47,000 without finishing, so expect some "ran out of tokens" — data, and the first setting to revisit (the card suggests 81,920 for complex problems) |
| `timeoutSeconds`                                         | 2,700 (45 minutes)      | 32,768 tokens at about 28 tokens/s is about 20 minutes for the 27B, plus margin. A timeout is not retried                                                                                                                                   |
| `retries`                                                | 3                       | connection failures, 429 and 5xx only                                                                                                                                                                                                       |
| samples                                                  | 5 per size              | a first table quickly; more for the cheap small sizes if noisy                                                                                                                                                                              |
| seed                                                     | unset                   | samples should differ; GPU arithmetic is not bit-reproducible anyway                                                                                                                                                                        |
| llama.cpp `-c` (server)                                  | 65,536                  | covers prompt plus budget, with a far smaller KV cache than the full 262,144                                                                                                                                                                |
| llama.cpp `-np` (server)                                 | 1                       | runs are one request at a time; extra slots only let other requests share the GPUs (noise in timings), and may divide `-c` between them. Other users wait; give them a second server on another port                                        |
| llama.cpp `--reasoning-budget`                           | unset                   | `maxTokens` caps thinking and answer together; a separate cap is a later variation                                                                                                                                                          |

llama.cpp's server documentation confirms it accepts `chat_template_kwargs`
(`{"enable_thinking": ...}`), `top_p`, `top_k`, `min_p`, `presence_penalty`,
`max_tokens` and `seed` per request (read 2026-10-07).

**From the Qwen3.5-27B model card** (read 2026-10-07): post-trained, Apache 2.0;
thinking on by default, off per request with
`chat_template_kwargs: {"enable_thinking": false}`; sampling for coding in
thinking mode as above; output length 32,768 for most queries, 81,920 "for
benchmarking on highly complex problems"; 262,144 tokens of context. Its summary
mentions "Gated Delta Networks combined with sparse Mixture-of-Experts"; whether
the 27B itself is dense was not settled, and the smaller sizes' cards were not
read.

**The prompt** is rung 0 of the information ladder (below): the reply format, a
general task, and the contract — no domain named, no example, no worked case.

**The smoke corpus** (`corpora/smoke/`, plan Phase 10) judges what a domain does
and is never shown to a model: eight probes, five unreachable targets spread
across what different domains can prove (constants, signs, intervals, narrowing
through `+`, widening around a loop) and three reachable ones run with inputs
that reach them, so a domain refuting one is caught unsound at once.

**What a row means.** *Decided — Shawn, 2026-10-08.* A row is one attempt,
judged at the first stage it fails — no reply, timed out, ran out of tokens, no
files, did not compile, did not load — or evaluated, with its cells and the
number of unreachable targets it proved. **Acceptable has two bars, reported
separately:**

- **mechanical** — passes the smoke corpus: evaluated, no ✗ (not caught
  unsound), and proves at least one of the five unreachable targets;
- **inspection** — Shawn judges it acceptable (the rubric below).

**Unsound** is its own category, never folded into "failed". Per model, the
summary gives counts, never percentages, since five samples are few.

**Fairness across models.** *Decided — Shawn, 2026-10-08.* The same prompt,
settings, corpus and hardware for every model, guaranteed per campaign by its
pin. Each model's chat template and thinking behaviour are part of the model,
noted rather than controlled. Time is reported per attempt but never compared
across sizes as a measure of quality.

### The inspection rubric

*Decided — Shawn, 2026-10-08.* Per attempt, in `report/inspection.json`, keyed
by `"<campaign>/<attempt>"`, every field optional:

- **closest existing domain** — constants, signs, intervals, intervals with
  widening, another (named), or none coherent;
- **soundness by eye** — looks sound, suspicious (where), or clearly unsound;
- **quality** — whether its own tests are meaningful or trivial, and anything
  notable;
- **acceptable** — yes or no: the inspection bar;
- **notes** — free text.

```json
{ "attempts": {
    "e1-rung0-qwen3.5-27b/attempt-001": {
      "closestDomain": "intervals", "soundnessByEye": "looks sound",
      "quality": "tests meaningful", "acceptable": true, "notes": "..." } } }
```

The fixed fields are short lists, so they can be counted across the table. The
report reads this file and never writes it.

### The rungs

Each rung is a slice of the scoring corpus (Phase 7) whose targets need one
more piece of reasoning than the rung below. A domain is judged only on its own
rung and those below it.

| rung | programs                                                             | what a domain needs to prove the targets                            |
|------|----------------------------------------------------------------------|---------------------------------------------------------------------|
| R0   | straight-line constants; targets guarded by comparisons of constants | constants, and `assume` on them                                     |
| R1   | one input, one branch on a literal                                   | intervals and backward `assume` (needs constant substitution, §5.7) |
| R2   | `+` and `-` on inputs before the branch                              | backward transfer through addition and subtraction                  |
| R3   | `*`, `negate`                                                        | sign reasoning; multiplication across zero                          |
| R4   | loops with a counter                                                 | widening, and a loop invariant at the head                          |
| R5   | two inputs related to each other (`x < y`, then `y < x`)             | a relational domain (zones or better, §16)                          |
| R6   | nested loops, accumulators                                           | relational reasoning and widening together                          |

R0–R4 are within reach of the interval reference domain; R5 and R6 are where a
relational domain becomes necessary, and where the reference fixture will need
to follow (§16). The smoke corpus touches R0, R1, R2 and R4 with one probe or
two each.

**Acceptable, eventually.** Beyond the first table's two bars, a domain is
acceptable at a rung when it compiles against `api.jar` alone, loads, passes
the smoke test (Phase 6), survives the adversary within the calibrated budget
(Phase 8), and proves at least a set fraction of the rung's provable targets.
Soundness is the gate; the proof fraction is the bar for "useful", set per rung
once the reference domain's own fraction is known.

### The information ladder: context before an attempt

*Decided — Shawn, 2026-10-07.* The question is whether a model can come up with
a solution, not implement one it is described. So every model starts with the
least information that defines the task, and context is added only when it
fails — one rung at a time, recorded with the attempt, so the answer includes
*which* addition made the difference:

| rung | adds                                                                                                                               | prompt         |
|------|------------------------------------------------------------------------------------------------------------------------------------|----------------|
| 0    | the reply format, a general task (prove `reach` calls unreachable, soundly; what to track is the model's choice), and the contract | `generator-v2` |
| 1    | which step shapes actually occur (the profile: assignments of constants, locals and `+ − *`; the six comparisons; `randInt`)       |                |
| 2    | the domain named ("intervals")                                                                                                     |                |
| 3    | a worked example of another domain (`ref-sign` and its tests)                                                                      |                |
| 4    | worked transfer cases for the target domain (the README's six)                                                                     |                |

Rung 0 was first `generator-v1`, whose reply-format example named its file
`Example.java`; the first 0.8B campaign fixated on that name (see Record), so
`generator-v2` gives a placeholder, `<YourDomain>.java`, and is otherwise the
same. The reply parser also accepts the path on a block's first line (bare or
as a `//` comment) as well as after the fence: a reply in that form is a
format slip, not a failed task. *Decided — Shawn, 2026-10-08.*

Rungs 1–4 are written when rung 0's results call for them. Context added
*before* an attempt is a different thing from the feedback given *after* one
(below); both are recorded. Tools for the agents (`compile_and_test`, then
`analyze`) are a further condition, each its own campaign, after the first
table (plan §16, item 24).

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

### What is recorded

Every attempt writes `results/<campaign>/<attempt>/attempt.json`, committed with
the domain sources the model wrote: the envelope (commit, dirty flag, profile),
the settings and the model's `source`, the server's own report of its model,
**the full messages sent** and **the full reply** (with its reasoning and token
usage), the files read from it, the build and test logs, every target's result,
and the summary row. A campaign's `campaign.json` pins its inputs. Feedback
rounds, when they come, add each round's kind and outcome.

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

E1 run across models at several sizes, to separate "small models cannot do
this" from "this model cannot do this". The first choice is the **Qwen3.5 size
ladder** (E1, *The first run*); other families come later. Candidates, by family
— **versions and sizes change quickly, so check what is current, and each
model's licence, before running**:

| family    | candidates to consider                                                                       | why                                                                      |
|-----------|----------------------------------------------------------------------------------------------|--------------------------------------------------------------------------|
| Qwen      | the Qwen3.5 ladder and Qwen3.8 27B (chosen); Qwen2.5-Coder                                  | a size ladder within one family                                          |
| DeepSeek  | DeepSeek-Coder-V2-Lite; the R1 distilled models                                              | strong code models; distills test whether reasoning-style training helps |
| OpenAI    | gpt-oss-20b, gpt-oss-120b                                                                    | open-weight reasoning models at two sizes                                |
| Mistral   | Devstral, Codestral                                                                          | code-specialised; check licences                                         |
| Meta      | Llama 3.x (8B, 70B)                                                                          | widely used baseline                                                     |
| Google    | Gemma 3 (up to 27B)                                                                          | small, strong general models                                             |
| Microsoft | Phi-4                                                                                        | small, trained heavily on reasoning                                      |

**Identifying a model.** A Hugging Face repository URL is a pointer, not an
identity: one repository holds many quantization files, and repositories are
re-uploaded under the same name (for chat-template or tokenizer fixes, say). So
each model's config carries a `source` (plan §10): the URL and the **file** say
where to get it, the **revision** pins the upload, and the file's **SHA-256**
says which bytes it was — the one identifier that holds wherever the file lives
and whatever it is called. The model is not the whole configuration: the chat
template, context size, server defaults and server version also change
behaviour, which is why each attempt also records the request parameters and
the server's own report. A fuller provenance scheme is being worked on
separately by a coworker of Shawn's; these fields are a placeholder it can
replace.

Run the generator and the adversary as **different** models (§10); E3 should
include a few cross-pairings, since a weak adversary would make every generator
look sound. A frontier model as the generator, run once at the top rung, is a
useful ceiling: it says whether a rung is hard because the task is hard or
because the model is small — though frontier models would likely outpace the
setup quickly, which is why the ladder is of small models.

---

## The report

*Decided — Shawn, 2026-10-07.* Results are written up as they come, in a LaTeX
report kept in the repository (`report/report.tex`), with tables generated from
the attempt records rather than copied by hand. Its outline (*decided — Shawn,
2026-10-08*): setup (models, settings, corpus, the prompt in full in an
appendix), Table 1, Table 2, and observations written by hand from the
inspection notes. No figures yet; acceptable against model size is the first,
once the five sizes are run.

- **`campaign report [--prefix e1-rung0-]`** reads every campaign whose name
  starts with the prefix, smallest model first (the size is read from the name),
  and writes into `report/tables/`: `table1.tex`, `table2.tex`, `settings.tex`
  (the settings each campaign actually ran with, and its model's revision and
  SHA-256, from its records), `corpus.tex` (from the manifest), and
  `prompt.txt` (the prompt exactly as sent, from the records). So the setup
  section cannot disagree with what ran. Output is deterministic, so
  regenerating unchanged results changes nothing in git; an inspection naming no
  attempt read is warned of. The generated files hold only the tables; captions
  live in `report.tex`.
- **Building**: `cd report && latexmk -lualatex -outdir=build report.tex`
  (output in `report/build/`, not committed). LuaLaTeX and DejaVu Sans Mono
  because the prompt appendix quotes the contract, whose Javadoc has σ′, ⊥ and
  ↦. Missing tables show as a note saying how to make them, so the report builds
  before any results; each inclusion is logged (`pag-report: included …`), which
  the build test checks.

### Table 1 — one shot, rung 0, the smoke corpus

One row per attempt; models grouped, smallest first.

| column                         | meaning                                                                                                                                                                                          |
|--------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Model                          | the campaign's model, from its name                                                                                                                                                              |
| Sample                         | which of the N samples                                                                                                                                                                           |
| Stopped at                     | where it stopped: no reply, timed out, ran out of tokens, no files in the reply, did not compile, did not load — or evaluated                                                                    |
| Files                          | source files read from the reply                                                                                                                                                                 |
| Built                          | compiled by the Gradle template                                                                                                                                                                  |
| Tests                          | its own JUnit tests passing, out of those run                                                                                                                                                    |
| Loads                          | `pag` found exactly one domain class and constructed it                                                                                                                                          |
| one column per target          | R refuted, A alarm, × refuted a reachable target (unsound), I did not converge (iteration limit or deadline), E domain failure (threw or returned null), H hung (killed at the wall-clock bound) |
| Proved                         | refutations among the five unreachable targets                                                                                                                                                   |
| Tokens                         | completion tokens, thinking included                                                                                                                                                             |
| Time                           | the attempt's wall-clock time                                                                                                                                                                    |
| Closest domain, By eye, Accept | Shawn's inspection                                                                                                                                                                               |

### Table 2 — per model

Attempts; acceptable by each bar ("2/5" mechanical; accepted out of those
inspected); unsound attempts; the median *proved* among the mechanically
acceptable; and how many stopped at each stage.

---

## Record

Kept for the history of how the setup was reached.

**The pipeline check** (2026-10-07), before the models were chosen: campaign
`phase10-check-qwen3.8-27b`, Qwen3.8 27B served by llama.cpp at
`http://localhost:8933/v1`, one sample with default settings and no token cap.
It generated over 47,000 tokens in 30 minutes, about 28 tokens/s, and had not
finished when the client's 30-minute timeout fired; a bug then retried it from
scratch, and the run was stopped. Fixed since: a timeout is not retried. It also
showed that llama.cpp cancels a generation when its client disconnects. The
model, as the server reported it (2026-10-06, `/v1/models`): id
`/home/s/models/Qwen3.8-27B-GGUF/Qwen3.8-27B-UD-Q6_K_XL.gguf`; 27,320,697,856
parameters; 25,913,155,584 bytes; context 262,144; capabilities `completion`,
`multimodal`; quantization `UD-Q6_K_XL` by file name, though the server's
`ftype` field said `Q4_K - Small` — the size, about 7.6 bits per weight, fits the
file name. Source `https://huggingface.co/unsloth/Qwen3.8-27B-GGUF`, from
Shawn's shell history; revision and SHA-256 not recorded.

**The first 0.8B campaign** (2026-10-08): `e1-rung0-qwen3.5-0.8B`, prompt
`generator-v1`, the settings of the first run. All five samples ran out of
tokens (32,768, about 97 s each), each in a loop. Samples 1, 2 and 5 looped in
the reasoning, rereading the format instructions and returning to
`src/pag/domains/gen/Example.java`, the example's file name; samples 3 and 4
looped in the answer, repeating a line (`abstract boolean entails(S a, S b);`,
`}`). Sample 3 put its path on the line after the fence, which the parser then
refused. Two changes followed: `generator-v2` (a placeholder file name) and the
lenient parser (The information ladder). Rerunning the campaign under
`generator-v2` replaces it; the v1 attempts are in git history.

**Installed on Shawn's Mac** (Ollama, `localhost:11434`, 2026-10-06), considered
and dropped once every model moved to llama.cpp: `llama3.2:3b` (3.2B),
`gemma3:latest` (4.3B), `qwen2.5-coder:14b` (14.8B), all Q4_K_M. Open WebUI on
`localhost:3000` is a chat front end, not an endpoint the campaign uses.

---

## Order

E1 starts at crude Phase 10 with rung 0, one shot, on the smoke corpus (Table 1),
then grows with the scoring corpus (Phase 7) and the rungs. E2 needs the
adversary (crude Phases 8 and 11). E3 is E1 repeated per model once E1's
procedure is stable.
