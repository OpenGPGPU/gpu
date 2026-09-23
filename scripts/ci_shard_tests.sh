#!/usr/bin/env bash
# Partition ScalaTest suites across CI runners and intersect with select_tests.sh.
#
# Usage:
#   ci_shard_tests.sh shards-json <selected>
#     Print a JSON array of shard names that have work for <selected>.
#     <selected> is ALL, NONE, or space-separated sbt testOnly globs.
#
#   ci_shard_tests.sh suites <shard> <selected>
#     Print space-separated fully-qualified suite names for sbt testOnly,
#     or nothing if this shard has no work.
#
# Shard layout (balanced from local JUnit timing; each runner stays serial
# inside one JVM so Chisel elaboration cannot OOM the shared heap):
#   graphics  — opengpu/graphics/**
#   system    — opengpu/system/**
#   core-a    — opengpu/core/{execute,memory}/**
#   core-b    — remaining opengpu/core/** plus dispatch/dma/command/util
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
test_root="$root/src/test/scala"

shard_prefixes() {
  case "$1" in
    graphics) printf '%s\n' "opengpu/graphics" ;;
    system)   printf '%s\n' "opengpu/system" ;;
    core-a)   printf '%s\n' "opengpu/core/execute" "opengpu/core/memory" ;;
    core-b)
      printf '%s\n' \
        "opengpu/core/frontend" \
        "opengpu/core/backend" \
        "opengpu/core/vector" \
        "opengpu/core/simt" \
        "opengpu/core/trap" \
        "opengpu/core/system" \
        "opengpu/dispatch" \
        "opengpu/dma" \
        "opengpu/command" \
        "opengpu/util"
      ;;
    *)
      echo "unknown shard: $1" >&2
      exit 2
      ;;
  esac
}

all_shards=(graphics system core-a core-b)

# Top-level specs directly under opengpu/core/ (not in a subpackage).
core_b_toplevel() {
  find "$test_root/opengpu/core" -maxdepth 1 -name '*Spec.scala' -print
}

shard_spec_files() {
  local shard=$1 prefix
  while IFS= read -r prefix; do
    [ -d "$test_root/$prefix" ] || continue
    find "$test_root/$prefix" -name '*Spec.scala' -print
  done < <(shard_prefixes "$shard")
  if [ "$shard" = "core-b" ]; then
    core_b_toplevel
  fi
}

fqcn_of() {
  # $1: absolute path to *Spec.scala
  local rel=${1#"$test_root"/}
  rel=${rel%.scala}
  printf '%s\n' "${rel//\//.}"
}

matches_glob() {
  # $1 = fqcn, $2 = sbt-style glob (e.g. opengpu.core.*)
  local fqcn=$1 glob=$2 re
  re=$(printf '%s' "$glob" | sed 's/\./\\./g; s/\*/.*/g')
  [[ "$fqcn" =~ ^${re}$ ]]
}

suite_selected() {
  # $1 = fqcn, $2 = selected (ALL | NONE | globs)
  local fqcn=$1 selected=$2 glob
  case "$selected" in
    NONE) return 1 ;;
    ALL)  return 0 ;;
  esac
  for glob in $selected; do
    if matches_glob "$fqcn" "$glob"; then
      return 0
    fi
  done
  return 1
}

suites_for_shard() {
  local shard=$1 selected=$2 file fqcn
  local -a out=()
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    fqcn=$(fqcn_of "$file")
    if suite_selected "$fqcn" "$selected"; then
      out+=("$fqcn")
    fi
  done < <(shard_spec_files "$shard" | sort -u)
  if [ "${#out[@]}" -gt 0 ]; then
    printf '%s\n' "${out[*]}"
  fi
}

shards_json() {
  local selected=$1 shard suites
  local -a active=()
  if [ "$selected" = "NONE" ]; then
    printf '[]\n'
    return
  fi
  for shard in "${all_shards[@]}"; do
    suites=$(suites_for_shard "$shard" "$selected" || true)
    if [ -n "${suites:-}" ]; then
      active+=("\"$shard\"")
    fi
  done
  if [ "${#active[@]}" -eq 0 ]; then
    printf '[]\n'
  else
    local IFS=,
    printf '[%s]\n' "${active[*]}"
  fi
}

cmd=${1:-}
case "$cmd" in
  shards-json)
    [ $# -ge 2 ] || { echo "usage: $0 shards-json <selected>" >&2; exit 2; }
    shift
    shards_json "$*"
    ;;
  suites)
    [ $# -ge 3 ] || { echo "usage: $0 suites <shard> <selected>" >&2; exit 2; }
    shard=$2
    shift 2
    suites_for_shard "$shard" "$*"
    ;;
  *)
    echo "usage: $0 {shards-json|suites} ..." >&2
    exit 2
    ;;
esac
