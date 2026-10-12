# The Python environment scripts/serve_model.sh runs its tools in: a venv at
# scripts/out/venv holding scripts/requirements-serve.txt (the download tool)
# and llama.cpp's requirements for convert_hf_to_gguf.py, taken from the
# checkout so they match its commit. Made on first use; reinstalled when
# either requirements file changes, and skipped otherwise.
#
#   bash scripts/python_env.sh      # makes or updates it, prints its path
#
# Sourced, it sets VENV. Needs python3 with the venv module (on Ubuntu, the
# python3-venv package). Set LLAMA_CPP to the checkout (default
# ~/software/llama.cpp).
set -euo pipefail

ENV_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LLAMA_CPP="${LLAMA_CPP:-$HOME/software/llama.cpp}"
VENV="$ENV_ROOT/scripts/out/venv"
REQUIREMENTS=(
  "$ENV_ROOT/scripts/requirements-serve.txt"
  "$LLAMA_CPP/requirements/requirements-convert_hf_to_gguf.txt"
)
for r in "${REQUIREMENTS[@]}"; do
  [ -f "$r" ] || { echo "missing requirements file: $r" >&2; exit 1; }
done

# What the venv was installed from: the Python version and both files' contents.
# llama.cpp's file includes others relative to itself (-r ./...), so those are hashed too.
WANT="$( { python3 --version; cat "${REQUIREMENTS[@]}" "$LLAMA_CPP"/requirements/*.txt; } | sha256sum | cut -d' ' -f1)"
STAMP="$VENV/.installed-from"

if [ ! -f "$STAMP" ] || [ "$(cat "$STAMP")" != "$WANT" ]; then
  echo "== making the Python environment at $VENV" >&2
  rm -rf "$VENV"
  python3 -m venv "$VENV" || {
    echo "python3 could not make a venv; on Ubuntu: sudo apt install python3-venv" >&2
    exit 1
  }
  for r in "${REQUIREMENTS[@]}"; do
    # From the file's directory, so its relative includes (-r ./...) resolve wherever pip resolves them from.
    (cd "$(dirname "$r")" && "$VENV/bin/pip" install --quiet --disable-pip-version-check -r "$(basename "$r")") >&2
  done
  echo "$WANT" > "$STAMP"
fi

# Run directly rather than sourced: print the path.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  echo "$VENV"
fi
