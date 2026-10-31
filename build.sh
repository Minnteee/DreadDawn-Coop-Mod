#!/usr/bin/env bash
# Builds coop-mod.jar and (optionally) installs it into the game folder.
#   ./build.sh            build only
#   ./build.sh install    build + copy to "$GAME/coop"
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# Settings: set JDK and GAME as environment variables or in a file "local.env" next to this script
# (environment variables win).
#   JDK   a JDK 21 or newer (default: JAVA_HOME)
#   GAME  the Dread Dawn folder, the one that contains "Word"
ENV_JDK="${JDK:-}"
ENV_GAME="${GAME:-}"
if [ -f "$HERE/local.env" ]; then . "$HERE/local.env"; fi
JDK="${ENV_JDK:-${JDK:-${JAVA_HOME:-}}}"
GAME="${ENV_GAME:-${GAME:-}}"
EXE="$GAME/Word/Etc/doot/effc/MyPanel/Dread dawn.exe"
if [ -z "$JDK" ] || { [ ! -e "$JDK/bin/javac" ] && [ ! -e "$JDK/bin/javac.exe" ]; }; then
  echo "JDK 21+ not found: set JDK (or JAVA_HOME) to the JDK folder." >&2
  exit 1
fi
if [ ! -f "$EXE" ]; then
  echo "Dread Dawn not found: set GAME to the game folder (the one that contains \"Word\")." >&2
  exit 1
fi
B="$HERE/build"
ASM="$HERE/lib/asm.jar;$HERE/lib/asm-tree.jar;$HERE/lib/asm-commons.jar;$HERE/lib/asm-util.jar;$HERE/lib/asm-analysis.jar"
w() { cygpath -w "$1"; }

rm -rf "$B/patcher" "$B/mod" "$B/jar"
mkdir -p "$B/patcher" "$B/mod" "$B/jar"

# 1) patcher (ASM based)
"$JDK/bin/javac" -nowarn --release 21 -encoding UTF-8 -cp "$(w "$HERE/lib/asm.jar");$(w "$HERE/lib/asm-tree.jar")" \
  -d "$(w "$B/patcher")" $(find "$HERE/src/coop/patcher" -name '*.java' | while read f; do w "$f"; done)

# 2) widened game jar for compiling against (only when the exe changed)
if [ ! -f "$B/game-widened.jar" ] || [ "$EXE" -nt "$B/game-widened.jar" ] || [ "$HERE/src/coop/patcher/GamePatcher.java" -nt "$B/game-widened.jar" ]; then
  "$JDK/bin/java" -cp "$(w "$B/patcher");$(w "$HERE/lib/asm.jar");$(w "$HERE/lib/asm-tree.jar")" coop.patcher.Tool widen "$(w "$EXE")" "$(w "$B/game-widened.jar")"
fi

# 3) mod
"$JDK/bin/javac" -nowarn --release 21 -encoding UTF-8 -proc:none \
  -cp "$(w "$B/patcher");$(w "$B/game-widened.jar");$(w "$HERE/lib/asm.jar");$(w "$HERE/lib/asm-tree.jar")" \
  -d "$(w "$B/mod")" $(find "$HERE/src/coop" -name '*.java' -not -path '*/patcher/*' | while read f; do w "$f"; done)

# 4) package: mod + patcher + ASM (shaded)
cp -r "$B/patcher/." "$B/jar/"
cp -r "$B/mod/." "$B/jar/"
for j in asm asm-tree asm-commons asm-util asm-analysis; do
  (cd "$B/jar" && "$JDK/bin/jar" xf "$(w "$HERE/lib/$j.jar")")
done
rm -rf "$B/jar/META-INF" "$B/jar/module-info.class"
mkdir -p "$B/jar/META-INF"
printf 'Manifest-Version: 1.0\nMain-Class: coop.launcher.Launcher\n' > "$B/manifest.txt"
rm -f "$B/coop-mod.jar"
"$JDK/bin/jar" --create --file "$(w "$B/coop-mod.jar")" --manifest "$(w "$B/manifest.txt")" -C "$(w "$B/jar")" .
echo "built $(w "$B/coop-mod.jar") ($(stat -c %s "$B/coop-mod.jar") bytes)"

# 4b) German language pack launcher: patcher + launcher + coop.Lang/T + ASM, without the co-op mod
rm -rf "$B/jar-de"
mkdir -p "$B/jar-de/coop"
cp -r "$B/patcher/." "$B/jar-de/"
cp -r "$B/mod/coop/launcher" "$B/jar-de/coop/"
cp "$B/mod/coop/Lang.class" "$B/mod/coop/T.class" "$B/jar-de/coop/"
for j in asm asm-tree asm-commons asm-util asm-analysis; do
  (cd "$B/jar-de" && "$JDK/bin/jar" xf "$(w "$HERE/lib/$j.jar")")
done
rm -rf "$B/jar-de/META-INF" "$B/jar-de/module-info.class"
rm -f "$B/deutsch-mod.jar"
"$JDK/bin/jar" --create --file "$(w "$B/deutsch-mod.jar")" --manifest "$(w "$B/manifest.txt")" -C "$(w "$B/jar-de")" .
echo "built $(w "$B/deutsch-mod.jar") ($(stat -c %s "$B/deutsch-mod.jar") bytes)"

# 5) package for the friend: bat files (CRLF) + coop/coop-mod.jar + readme
rm -rf "$B/pkg"
mkdir -p "$B/pkg/coop"
for f in "$HERE/dist/"*.bat; do
  [ -e "$f" ] || continue
  sed 's/\r$//; s/$/\r/' "$f" > "$B/pkg/$(basename "$f")"
done
for f in "$HERE/dist/coop/"*; do
  [ -e "$f" ] || continue
  sed 's/\r$//; s/$/\r/' "$f" > "$B/pkg/coop/$(basename "$f")"
done
cp "$B/coop-mod.jar" "$B/pkg/coop/coop-mod.jar"
rm -f "$B/DreadDawn-Coop-Mod.zip"
(cd "$B/pkg" && "$JDK/bin/jar" --create --no-manifest --file "$(w "$B/DreadDawn-Coop-Mod.zip")" .)
# source snapshot (to change the mod later)
rm -f "$B/coop-mod-source.zip"
(cd "$HERE" && "$JDK/bin/jar" --create --no-manifest --file "$(w "$B/coop-mod-source.zip")" src tools dist build.sh README.md LICENSE)

# 5b) German language pack: "Dread Dawn DEUTSCH.bat" + deutsch/deutsch-mod.jar + deutsch/Word/... (texts)
rm -rf "$B/pkg-de"
mkdir -p "$B/pkg-de/deutsch"
for f in "$HERE/dist-de/"*.bat; do
  [ -e "$f" ] || continue
  sed 's/\r$//; s/$/\r/' "$f" > "$B/pkg-de/$(basename "$f")"
done
for f in "$HERE/dist-de/deutsch/"*.txt; do
  [ -e "$f" ] || continue
  sed 's/\r$//; s/$/\r/' "$f" > "$B/pkg-de/deutsch/$(basename "$f")"
done
cp "$B/deutsch-mod.jar" "$B/pkg-de/deutsch/deutsch-mod.jar"
if [ -d "$HERE/german/Word" ]; then
  cp -r "$HERE/german/Word" "$B/pkg-de/deutsch/"
fi
rm -f "$B/DreadDawn-Deutsch-Mod.zip"
(cd "$B/pkg-de" && "$JDK/bin/jar" --create --no-manifest --file "$(w "$B/DreadDawn-Deutsch-Mod.zip")" .)
echo "built $(w "$B/DreadDawn-Deutsch-Mod.zip") ($(find "$B/pkg-de/deutsch/Word" -type f 2>/dev/null | wc -l) text files)"

if [ "${1:-}" = "install" ]; then
  mkdir -p "$GAME/coop"
  cp "$B/coop-mod.jar" "$GAME/coop/coop-mod.jar"
  cp "$B/pkg/"*.bat "$GAME/"
  cp "$B/pkg/coop/"*.txt "$GAME/coop/"
  cp "$B/DreadDawn-Coop-Mod.zip" "$GAME/coop/"
  cp "$B/coop-mod-source.zip" "$GAME/coop/"
  echo "installed to $GAME/coop"
  if [ -d "$HERE/german/Word" ]; then
    # the German pack: a fresh copy of the texts (removed files must disappear too)
    rm -rf "$GAME/deutsch/Word"
    mkdir -p "$GAME/deutsch"
    cp -r "$B/pkg-de/deutsch/." "$GAME/deutsch/"
    cp "$B/pkg-de/"*.bat "$GAME/"
    echo "installed the German pack to $GAME/deutsch"
  fi
fi
