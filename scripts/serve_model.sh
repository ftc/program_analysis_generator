# Makes a Q8_0 GGUF from an official Hugging Face repository and serves it with
# llama-server, as in experiments.md, "Running E1, step by step", step 1.
#
#   bash scripts/serve_model.sh <hf-url> [extra llama-server args...]
#   bash scripts/serve_model.sh https://huggingface.co/Qwen/Qwen3.5-0.8B
#
# The repository is pinned to its current revision, downloaded, converted to a
# BF16 GGUF with llama.cpp's converter, quantized to Q8_0 with llama-quantize,
# and the download and BF16 intermediate deleted. The result lands in
#   ~/models/<name>-GGUF/<name>-Q8_0-llamacpp-<commit>.gguf
# and its provenance record, the file a campaign is run with, in the repository
# at config/models/<same>.json, to be committed (implementation_strategy.md
# §10): url, file, revision, sha256, and path, the GGUF's absolute path. If the
# GGUF and its record already exist, it skips straight to serving. If the GGUF exists but its record does not (a run stopped while
# hashing), it hashes the GGUF and writes the record without reconverting,
# taking the revision from the pin file written before the download, so the
# record never names a revision the GGUF was not made from.
#
# The converter must come from the same llama.cpp commit as llama-server, so
# the commit in the file name is the one that made and serves it. Set
# LLAMA_CPP to the checkout (default ~/software/llama.cpp).
#
# The download and the conversion run in the script's own Python environment,
# scripts/out/venv, made on first use by scripts/python_env.sh. It needs only
# python3 with the venv module (on Ubuntu, the python3-venv package).
set -euo pipefail

[ $# -ge 1 ] || { echo "usage: $0 <hf-url> [extra llama-server args...]" >&2; exit 2; }
URL="${1%/}"; shift
REPO="${URL#https://huggingface.co/}"
case "$REPO" in
  */*) ;;
  *) echo "not a Hugging Face repository URL: $URL" >&2; exit 2 ;;
esac
NAME="${REPO#*/}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODELS="$HOME/models"
LLAMA_CPP="${LLAMA_CPP:-$HOME/software/llama.cpp}"
PORT=8933

# llama-server --version prints e.g. "version: 0.3.0-dev (build 10726, commit 85c55223c)".
COMMIT="$(llama-server --version 2>&1 | sed -n 's/.*commit \([0-9a-f]*\).*/\1/p')"
[ -n "$COMMIT" ] || { echo "could not read llama-server's commit" >&2; exit 1; }
CHECKOUT="$(git -C "$LLAMA_CPP" rev-parse HEAD)"
case "$CHECKOUT" in
  "$COMMIT"*) ;;
  *) echo "llama.cpp checkout $LLAMA_CPP is at $CHECKOUT, but llama-server is $COMMIT" >&2; exit 1 ;;
esac

OUTDIR="$MODELS/$NAME-GGUF"
FILE="$NAME-Q8_0-llamacpp-$COMMIT.gguf"
GGUF="$OUTDIR/$FILE"
RECORD="$ROOT/config/models/${FILE%.gguf}.json"
PIN="$OUTDIR/.${FILE%.gguf}.revision" # the revision being converted, written before downloading
BF16="$OUTDIR/.$NAME-BF16.gguf"

# Hashes the GGUF and writes its record; written whole, then renamed, so a
# partial record never counts as done.
write_record() {
  echo "== sha256"
  local sha256
  sha256="$(sha256sum "$GGUF" | cut -d' ' -f1)"
  mkdir -p "$(dirname "$RECORD")"
  python3 - "$URL" "$FILE" "$1" "$sha256" "$GGUF" > "$RECORD.part" <<'EOF'
import json, sys
url, file, revision, sha256, path = sys.argv[1:]
print(json.dumps({"url": url, "file": file, "revision": revision, "sha256": sha256, "path": path}, indent=2))
EOF
  mv "$RECORD.part" "$RECORD"
}

if [ -f "$GGUF" ] && [ -f "$RECORD" ]; then
  echo "== $GGUF exists; skipping conversion"
elif [ -f "$GGUF" ]; then
  # Converted, but stopped before its record was written: hash it, do not reconvert.
  if [ -f "$PIN" ]; then
    REVISION="$(cat "$PIN")"
  else
    # A GGUF from before the pin file existed: the leftover download folder's name holds the revision.
    shopt -s nullglob
    LEFT=("$MODELS"/.download-"$NAME"-*/)
    shopt -u nullglob
    if [ "${#LEFT[@]}" -eq 1 ]; then
      REVISION="${LEFT[0]%/}"; REVISION="${REVISION##*-}"
    else
      echo "$GGUF exists without its record, and the revision it was made from is unknown;" >&2
      echo "delete it and rerun to convert afresh" >&2
      exit 1
    fi
  fi
  echo "== $GGUF exists without its record; recording it (revision $REVISION)"
  write_record "$REVISION"
  echo "== deleting the download and the BF16 intermediate"
  rm -rf "$MODELS"/.download-"$NAME"-* "$BF16" "$PIN"
else
  source "$ROOT/scripts/python_env.sh" # sets VENV

  # Pin the revision first, so the download and the record agree.
  REVISION="$(curl -fsS "https://huggingface.co/api/models/$REPO" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["sha"])')"
  DOWNLOAD="$MODELS/.download-$NAME-$REVISION"
  mkdir -p "$OUTDIR"
  echo "$REVISION" > "$PIN"

  echo "== downloading $REPO at $REVISION"
  "$VENV/bin/huggingface-cli" download "$REPO" --revision "$REVISION" --local-dir "$DOWNLOAD"

  echo "== converting to BF16 (llama.cpp $COMMIT)"
  "$VENV/bin/python" "$LLAMA_CPP/convert_hf_to_gguf.py" "$DOWNLOAD" --outtype bf16 --outfile "$BF16"

  echo "== quantizing to Q8_0"
  llama-quantize "$BF16" "$GGUF.part" Q8_0
  mv "$GGUF.part" "$GGUF"

  write_record "$REVISION"

  echo "== deleting the download and the BF16 intermediate"
  rm -rf "$DOWNLOAD" "$BF16" "$PIN"
fi

echo "== provenance record:"
cat "$RECORD"

echo "== serving on port $PORT; check: curl -s http://localhost:$PORT/slots"
# In the background, so the record's path can be printed once the server is up,
# below its loading log. A non-interactive shell starts background jobs with
# Ctrl-C ignored, so the trap passes it on; it also stops the server if this
# script exits.
llama-server -m "$GGUF" -c 262144 -np 1 --port "$PORT" -ngl all "$@" --host 0.0.0.0 &
SERVER=$!
trap 'kill "$SERVER" 2>/dev/null || true' EXIT INT TERM HUP

# /health answers 503 while the model loads and 200 once it is ready.
until curl -fsS -o /dev/null "http://localhost:$PORT/health" 2>/dev/null; do
  kill -0 "$SERVER" 2>/dev/null || { wait "$SERVER" || exit $?; exit 1; } # it stopped before it was ready
  sleep 2
done
echo "== server ready; provenance record, for campaign generate --model (commit it):"
echo "   $RECORD"
wait "$SERVER"
