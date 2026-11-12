#!/bin/bash
# Apply the dcache-exact-fill patch to this container's rocket-chip.
# Safe to run more than once. /workspace/chipyard is reset whenever the container
# exits, so run this at the start of every session. linreg-replacement/apply.sh
# runs it for you.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CY=/workspace/chipyard
RC="$CY/generators/rocket-chip"
REPL="$RC/src/main/scala/util/Replacement.scala"
CFG_DST="$CY/generators/chipyard/src/main/scala/config/ExactFillConfigs.scala"

cd "$RC"
if git apply --reverse --check "$HERE/rocket-chip.patch" 2>/dev/null; then
    echo "[exact-fill] rocket-chip.patch already applied"
else
    git apply "$HERE/rocket-chip.patch"
    echo "[exact-fill] applied rocket-chip.patch (DCache.scala, FillAwareReplacement.scala)"
fi

# Register "lru-exact" in ReplacementPolicy.fromString. This is done with sed
# rather than in the patch so it does not conflict with linreg-replacement/apply.sh,
# which edits the same match statement.
if grep -q '"lru-exact"' "$REPL"; then
    echo "[exact-fill] lru-exact already registered"
else
    sed -i '/def fromString/,/case t =>/{/case "lru" *=> new TrueLRU(n_ways)/a\    case "lru-exact" => new ExactFillLRU(n_ways)
}' "$REPL"
    grep -q '"lru-exact"' "$REPL" || { echo "[exact-fill] ERROR: could not register lru-exact in $REPL" >&2; exit 1; }
    echo "[exact-fill] registered lru-exact"
fi

ln -sf "$HERE/ExactFillConfigs.scala" "$CFG_DST"
echo "[exact-fill] linked ExactFillConfigs.scala"

# Force a recompile, otherwise the cached generator jar is used.
rm -f "$CY/.classpath_cache/chipyard.jar"
echo "[exact-fill] removed cached chipyard.jar; next make will recompile"
