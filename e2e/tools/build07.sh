#!/bin/sh
# Builds the MineQTT 0.7.0 jars (the release before Milestone 4) for the upgrade checks of items 32-40: the upgrade
# from 0.7.0 needs a world and a config that 0.7.0 itself wrote.
#   build07.sh [--loader fabric|neoforge|all] [OUT_DIR]
# The 0.7.0 tree is exported with git archive into a temporary folder (read-only: no worktree, no checkout, nothing in
# this working tree changes), built there with its own Gradle wrapper, and the bundled jars are copied to OUT_DIR
# (default: build/mineqtt-0.7.0 in this checkout). The temporary folder is removed afterwards.
# The commit is the one that set mod_version=0.7.0 in gradle.properties; MINEQTT_07_REF names another one.
# A lane runs the Fabric jar: MINEQTT_LANE_JAR=<OUT_DIR>/mineqtt-fabric-0.7.0.jar sh e2e/lanes/lane.sh up <id>
# (or reload <id>); a server log line "mineqtt 0.7.0" shows it loaded.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
loader=all
out=""
while [ $# -gt 0 ]; do
    case "$1" in
        --loader) loader=${2:-}; shift 2 ;;
        -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
        -*) echo "build07: unknown option $1" >&2; exit 2 ;;
        *) out=$1; shift ;;
    esac
done
case "$loader" in
    fabric) tasks=":fabric:assemble" ;;
    neoforge) tasks=":neoforge:assemble" ;;
    all) tasks=":fabric:assemble :neoforge:assemble" ;;
    *) echo "build07: --loader takes fabric, neoforge or all" >&2; exit 2 ;;
esac
out=${out:-$REPO/build/mineqtt-0.7.0}

ref=${MINEQTT_07_REF:-$(git -C "$REPO" log --format=%H -S'mod_version=0.7.0' --reverse -- gradle.properties | head -n 1)}
[ -n "$ref" ] || { echo "build07: no commit sets mod_version=0.7.0 in this history; set MINEQTT_07_REF" >&2; exit 1; }
version=$(git -C "$REPO" show "$ref:gradle.properties" | tr -d '\r' | sed -n 's/^mod_version=//p')
[ "$version" = "0.7.0" ] || { echo "build07: $ref has mod_version=$version, not 0.7.0" >&2; exit 1; }
archives=$(git -C "$REPO" show "$ref:gradle.properties" | tr -d '\r' | sed -n 's/^archives_name=//p')

tree=$(mktemp -d "${TMPDIR:-/tmp}/mineqtt-0.7.0.XXXXXX")
# On Windows the ending build JVM can hold a log file for a moment, so the removal is retried.
cleanup() {
    for _ in 1 2 3 4 5; do
        rm -rf "$tree" 2>/dev/null && return 0
        sleep 2
    done
    echo "build07: could not remove $tree" >&2
}
trap cleanup EXIT
echo "build07: exporting $(git -C "$REPO" log -1 --format='%h %s' "$ref") to $tree"
git -C "$REPO" archive --format=tar "$ref" | tar -x -C "$tree"
# Its own wrapper and build, no daemon left behind that would keep the temporary folder busy.
# shellcheck disable=SC2086
(cd "$tree" && sh ./gradlew --no-daemon --console=plain -q $tasks)

mkdir -p "$out"
for name in fabric neoforge; do
    case "$tasks" in *":$name:"*) ;; *) continue ;; esac
    jar="$tree/$name/build/libs/$archives-$name-$version.jar"
    [ -f "$jar" ] || { echo "build07: the build left no $jar" >&2; exit 1; }
    cp "$jar" "$out/"
    echo "build07: $out/$(basename "$jar")"
done
