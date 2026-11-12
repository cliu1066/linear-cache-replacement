#!/bin/bash
# Apply the linreg replacement policy to this container's rocket-chip.
# Applies dcache-exact-fill first (it provides FillAwareReplacement).
# Safe to run more than once; run it at the start of every session, because
# /workspace/chipyard is reset when the container exits.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CY=/workspace/chipyard
RC="$CY/generators/rocket-chip"
REPL="$RC/src/main/scala/util/Replacement.scala"
CFG_DIR="$CY/generators/chipyard/src/main/scala/config"

bash "$HERE/../dcache-exact-fill/apply.sh"

cp "$HERE/LinRegReplacement.scala" "$HERE/LinRegWeights.scala" "$RC/src/main/scala/util/"
echo "[linreg] copied LinRegReplacement.scala, LinRegWeights.scala"

if grep -q 'LinRegReplacement.fromString' "$REPL"; then
    echo "[linreg] already registered in fromString"
else
    sed -i '/def fromString/,/case t =>/{/case "lru" *=> new TrueLRU(n_ways)/a\    case r if r.startsWith("linreg") => LinRegReplacement.fromString(r, n_ways)
}' "$REPL"
    grep -q 'LinRegReplacement.fromString' "$REPL" || { echo "[linreg] ERROR: could not edit $REPL" >&2; exit 1; }
    echo "[linreg] registered \"linreg*\" in fromString"
fi

ln -sf "$HERE/LinRegConfigs.scala" "$CFG_DIR/LinRegConfigs.scala"
echo "[linreg] linked LinRegConfigs.scala"

rm -f "$CY/.classpath_cache/chipyard.jar"
echo "[linreg] removed cached chipyard.jar; next make will recompile"
