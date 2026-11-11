#!/bin/bash
# Apply the stand-alone regression replacement policy to this container's
# rocket-chip. Applies dcache-exact-fill first (it needs FillAwareReplacement).
# Safe to run more than once; run it at the start of every session.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CY=/workspace/chipyard
RC="$CY/generators/rocket-chip"
REPL="$RC/src/main/scala/util/Replacement.scala"
CFG_DIR="$CY/generators/chipyard/src/main/scala/config"

bash "$HERE/../dcache-exact-fill/apply.sh"

cp "$HERE/RegressionReplacement.scala" "$RC/src/main/scala/util/RegressionReplacement.scala"
echo "[regression] copied RegressionReplacement.scala"

if grep -q 'RegressionReplacement.fromString' "$REPL"; then
    echo "[regression] already registered in fromString"
else
    sed -i '/case "lru" *=> new TrueLRU(n_ways)/a\    case r if r.startsWith("regression") => RegressionReplacement.fromString(r, n_ways)' "$REPL"
    grep -q 'RegressionReplacement.fromString' "$REPL" || { echo "[regression] ERROR: could not edit $REPL" >&2; exit 1; }
    echo "[regression] registered \"regression\" in fromString"
fi

ln -sf "$HERE/RegressionConfigs.scala" "$CFG_DIR/RegressionConfigs.scala"
echo "[regression] linked RegressionConfigs.scala"

rm -f "$CY/.classpath_cache/chipyard.jar"
