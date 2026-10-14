#!/usr/bin/env bash
# Pushes smoke-test screenshots and logs to the "smoke-results" branch and
# echoes the app's own log lines as a run annotation.
# Usage: bash scripts/publish-smoke-results.sh <dir> <run-number> [branch]
set -u
DIR="$1"
RUN="$2"
BRANCH="${3:-smoke-results}"
[ -d "$DIR" ] || { echo "No $DIR folder"; exit 0; }

LINES=$(grep -E "\[ota\]|\[native\]|FATAL|AndroidRuntime: " "$DIR/logcat-full.txt" 2>/dev/null | sed -E 's/^.*(\[ota\]|\[native\])/\1/' | tail -25)
[ -f "$DIR/package.txt" ] && LINES="$(tr -s ' ' < "$DIR/package.txt")
$LINES"
[ -f "$DIR/results.json" ] && LINES="$(python3 -c 'import json,sys;print(json.dumps(json.load(open(sys.argv[1])),separators=(",",":")))' "$DIR/results.json")
$LINES"
MSG=$(printf '%s' "$LINES" | sed ':a;N;$!ba;s/%/%25/g;s/\r/%0D/g;s/\n/%0A/g')
echo "::notice title=App log (run $RUN)::$MSG"

WORK=$(mktemp -d)
cp -r "$DIR"/. "$WORK"/
cd "$WORK"
git init -q -b "$BRANCH"
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
printf '# Smoke test, run %s\n\n![first launch](1-first-launch.png)\n![relaunch](2-relaunch.png)\n![landscape](3-landscape.png)\n' "$RUN" > README.md
git add -A
git commit -q -m "Smoke test results, run $RUN"
git push -q --force "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" "$BRANCH"
echo "Results: https://github.com/${GITHUB_REPOSITORY}/tree/$BRANCH"
