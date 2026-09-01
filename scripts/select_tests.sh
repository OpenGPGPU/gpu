#!/usr/bin/env bash
# Maps changed source files to the ScalaTest packages that exercise them.
#
# Usage: select_tests.sh <changed-file>...
#
# Prints one of:
#   ALL  - a full test run is required (build files changed, or a source
#          package has no matching test package)
#   NONE - no Scala sources changed, nothing to test
#   otherwise a single line of space-separated sbt testOnly package globs
set -euo pipefail

main_root=src/main/scala/opengpu
test_root=src/test/scala/opengpu

pkgs=()
full=0
none=1

map_pkg() {
  # $1: file under src/main or src/test; walk up to the nearest directory
  # that has a matching directory in the test tree.
  local rel=${1#src/*/scala/opengpu/}
  local dir=${rel%/*}
  while [ -n "$dir" ]; do
    if [ -d "$test_root/$dir" ]; then
      printf 'opengpu.%s.*' "${dir//\//.}"
      return
    fi
    if [ "$dir" = "${dir%/*}" ]; then
      dir=""
    else
      dir=${dir%/*}
    fi
  done
  echo ALL_NEEDED
}

for f in "$@"; do
  case "$f" in
    "$main_root"/*|"$test_root"/*)
      none=0
      p=$(map_pkg "$f")
      if [ "$p" = "ALL_NEEDED" ]; then
        full=1
      else
        pkgs+=("$p")
      fi
      ;;
    build.sbt|project/*)
      none=0
      full=1
      ;;
    # docs/, scripts/, timing/, ... do not affect tests
  esac
done

if [ "$full" = 1 ]; then
  echo ALL
elif [ "$none" = 1 ]; then
  echo NONE
else
  printf '%s\n' "${pkgs[@]}" | sort -u | tr '\n' ' '
  echo
fi
