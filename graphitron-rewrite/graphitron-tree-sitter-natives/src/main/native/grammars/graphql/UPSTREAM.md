# tree-sitter-graphql grammar (vendored)

Source: https://github.com/bkegley/tree-sitter-graphql
Commit: `5e66e961eee421786bdda8495ed1db045e06b5fe` (last commit on `main`,
2021-05-10)

License: MIT (see `LICENSE`).

## Why this fork

`bkegley` is the upstream the rewrite LSP's existing tree-sitter queries were
written against: snake-case node names (`directive`, `arguments`,
`argument`, `name`, `value`, `comma`) match
`Directives.DIRECTIVES_QUERY_TEXT` and `TypeNames.DECLARATION_QUERY` exactly.

The alternate fork `dralletje/tree-sitter-graphql` uses PascalCase node
names (`Document`, `Definition`, `OperationDefinition`, `Name`); adopting
it would force a rewrite of every tree-sitter query in the LSP. Both
upstreams are dormant; bkegley is the more recent of the two.

## Layout

Only the files needed to build the parser into a shared library are
vendored:

- `parser.c`: generated parser, `LANGUAGE_VERSION 13` (within the
  `13..15` window jtreesitter 0.26 supports as
  `MIN_COMPATIBLE_LANGUAGE_VERSION..LANGUAGE_VERSION`).
- `tree_sitter/parser.h`: parser-side header (`TSStateId`, `TSSymbol`,
  parse-state structs). Distinct from the tree-sitter runtime's internal
  `lib/src/parser.h`, which the upstream `tree-sitter build` CLI bundles
  on its own per release-workflow invocation.

Other files in the upstream (`grammar.js`, `binding.gyp`, `bindings/`,
`queries/`, `corpus/`, `examples/`, `lua/`, `plugin/`) are not vendored:
they target the Node / Cargo / Lua tooling we don't use and don't drive
our build.

## Build

`parser.c` is compiled together with the tree-sitter runtime by the
upstream `tree-sitter build` CLI into a single per-platform shared library
exposing both the runtime's `ts_*` symbols and this grammar's
`tree_sitter_graphql` entry point. The runtime sources themselves are
*not* vendored here: the CLI inlines its own runtime per invocation, which
is the whole reason the natives module exists rather than the previous
`build-native.sh` + vendored runtime tree under graphitron-lsp.

See `.github/workflows/tree-sitter-natives-release.yml` for the per-platform
matrix and `../../../UPSTREAM.md` at the module root for the release cadence.

## Updating

The upstream is dormant. If a grammar change is needed:

1. Edit `grammar.js` in a fork and run `tree-sitter generate` to produce a
   new `parser.c`. Drop the regenerated `parser.c` here and refresh this
   file's commit pin to the new fork.
2. Verify the existing tree-sitter queries in
   `graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/parsing/` still
   match the produced node names.
3. Bump the natives module version `<build-n>` (or `<runtime-version>` if
   the tree-sitter runtime is also updated) and cut a new release per
   `graphitron-rewrite/docs/tree-sitter-natives-release.adoc`.
