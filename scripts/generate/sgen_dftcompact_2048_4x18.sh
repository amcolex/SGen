#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

"$repo_root/sgen.bat" \
  -n 11 \
  -k 2 \
  -dualramcontrol \
  -hw complex signed 18 \
  -o "$repo_root/designs/generated/sgen/dftcompact_2048_4x18.v" \
  dftcompact
