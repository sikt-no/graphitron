# tree-sitter runtime (vendored)

Source: https://github.com/tree-sitter/tree-sitter
Tag: `v0.26.0`
Commit: `9be3e2bdd8e1e62ad259e3c8ee7de7ebaed0553f`

License: MIT (see `LICENSE`).

## Layout

Only files needed for a non-WASM static build of the runtime are vendored:

- `lib/include/tree_sitter/api.h`: public C API.
- `lib/src/`: implementation files (`alloc`, `language`, `lexer`, `node`,
  `parser`, `query`, `stack`, `subtree`, `tree`, `tree_cursor`,
  `get_changed_ranges`, plus a unity entry `lib.c`).
- `lib/src/portable/endian.h`, `lib/src/unicode/`: portability shims.

Files outside this set (`wasm_store.c`, `lib/binding_web/`, `lib/src/wasm/`)
are intentionally omitted. `wasm_store.c` is gated by
`-DTREE_SITTER_FEATURE_WASM` and we never set it, so dropping it produces no
build artefacts and no runtime symbol differences.

## Build

Compiled together with each grammar's `parser.c` into a per-platform shared
library. See `graphitron-lsp/pom.xml` for the invocation.

## Updating

1. Clone the runtime at the new tag.
2. Replace `lib/include/tree_sitter/api.h` and the contents of `lib/src/`
   from upstream, preserving the file selection above.
3. Refresh this file's commit/tag pin and bump if `LANGUAGE_VERSION` /
   `MIN_COMPATIBLE_LANGUAGE_VERSION` changed in `api.h`.
4. Rebuild with `mvn install -Plocal-db -pl :graphitron-lsp` and confirm the
   72 LSP tests still pass.
