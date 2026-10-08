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
# beside <same>.source.json: the config's `source` block (url, file, revision,
# sha256). If the GGUF already exists, it skips straight to serving.
#
# The converter must come from the same llama.cpp commit as llama-server, so
# the commit in the file name is the one that made and serves it. Set
# LLAMA_CPP to the checkout (default ~/software/llama.cpp).
set -euo pipefail

[ $# -ge 1 ] || { echo "usage: $0 <hf-url> [extra llama-server args...]" >&2; exit 2; }
URL="${1%/}"; shift
REPO="${URL#https://huggingface.co/}"
case "$REPO" in
  */*) ;;
  *) echo "not a Hugging Face repository URL: $URL" >&2; exit 2 ;;
esac
NAME="${REPO#*/}"

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
SOURCE="${GGUF%.gguf}.source.json"

if [ -f "$GGUF" ] && [ -f "$SOURCE" ]; then
  echo "== $GGUF exists; skipping conversion"
else
  # Pin the revision first, so the download and the record agree.
  REVISION="$(curl -fsS "https://huggingface.co/api/models/$REPO" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["sha"])')"
  DOWNLOAD="$MODELS/.download-$NAME-$REVISION"
  BF16="$OUTDIR/.$NAME-BF16.gguf"
  mkdir -p "$OUTDIR"

  echo "== downloading $REPO at $REVISION"
  huggingface-cli download "$REPO" --revision "$REVISION" --local-dir "$DOWNLOAD"

  echo "== converting to BF16 (llama.cpp $COMMIT)"
  python3 "$LLAMA_CPP/convert_hf_to_gguf.py" "$DOWNLOAD" --outtype bf16 --outfile "$BF16"

  echo "== quantizing to Q8_0"
  llama-quantize "$BF16" "$GGUF.part" Q8_0
  mv "$GGUF.part" "$GGUF"

  echo "== sha256"
  SHA256="$(sha256sum "$GGUF" | cut -d' ' -f1)"
  python3 - "$URL" "$FILE" "$REVISION" "$SHA256" > "$SOURCE" <<'EOF'
import json, sys
url, file, revision, sha256 = sys.argv[1:]
print(json.dumps({"url": url, "file": file, "revision": revision, "sha256": sha256}, indent=2))
EOF

  echo "== deleting the download and the BF16 intermediate"
  rm -rf "$DOWNLOAD" "$BF16"
fi

echo "== source block for config/e1-rung0-*.json ($SOURCE):"
cat "$SOURCE"
echo "== serving on port $PORT; check: curl -s http://localhost:$PORT/slots"
exec llama-server -m "$GGUF" -c 65536 -np 1 --port "$PORT" -ngl all "$@"
