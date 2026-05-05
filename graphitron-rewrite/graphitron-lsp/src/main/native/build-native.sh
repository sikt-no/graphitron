#!/usr/bin/env bash
#
# Compiles the vendored tree-sitter runtime + tree-sitter-graphql grammar into
# a single per-platform shared library that exposes both the runtime's `ts_*`
# symbols and the grammar's `tree_sitter_graphql` entry point.
#
# Output path: $1 (parent directory). Library name follows the host's
# `System.mapLibraryName("tree-sitter-graphql")` convention so jtreesitter's
# default `SymbolLookup.libraryLookup` can find it without per-platform
# branching.
#
# Linux and macOS today; Windows builds through `build-native.bat` (a
# follow-up roadmap item; see `UPSTREAM.md`).

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <output-dir>" >&2
  exit 2
fi

out_dir="$1"
mkdir -p "$out_dir"

native_root="$(cd "$(dirname "$0")" && pwd)"
runtime_dir="$native_root/tree-sitter"
grammar_dir="$native_root/grammars/graphql"

uname_s="$(uname -s)"
case "$uname_s" in
  Linux)
    lib_name="libtree-sitter-graphql.so"
    extra_flags=()
    ;;
  Darwin)
    lib_name="libtree-sitter-graphql.dylib"
    # `-undefined dynamic_lookup` is the historic macOS flag for shared libs that
    # call back into the loader's symbol set; we don't use it because the lib is
    # self-contained. `-fvisibility=default` is the default on macOS too.
    extra_flags=()
    ;;
  *)
    echo "build-native.sh: unsupported host OS '$uname_s' (this script handles" \
         "Linux + macOS; Windows is a follow-up)" >&2
    exit 1
    ;;
esac

cc -shared -fPIC -O2 \
  -std=c11 \
  -D_POSIX_C_SOURCE=200112L \
  -D_DEFAULT_SOURCE \
  "${extra_flags[@]}" \
  -I "$runtime_dir/lib/include" \
  -I "$runtime_dir/lib/src" \
  -I "$grammar_dir" \
  "$grammar_dir/parser.c" \
  "$runtime_dir/lib/src/lib.c" \
  -o "$out_dir/$lib_name"

echo "build-native.sh: wrote $out_dir/$lib_name"
