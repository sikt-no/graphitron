# graphitron-tree-sitter-natives

Standalone Maven module that publishes a single jar containing per-platform
tree-sitter shared libraries for five host platforms:

- `linux-x86_64` → `lib/linux-x86_64/libtree-sitter-graphql.so`
- `linux-aarch64` → `lib/linux-aarch64/libtree-sitter-graphql.so`
- `macos-x86_64` → `lib/macos-x86_64/libtree-sitter-graphql.dylib`
- `macos-aarch64` → `lib/macos-aarch64/libtree-sitter-graphql.dylib`
- `windows-x86_64` → `lib/windows-x86_64/tree-sitter-graphql.dll`

Each binary is the upstream tree-sitter 0.26.0 runtime + the vendored
bkegley tree-sitter-graphql grammar compiled into one unified shared
library, exposing both the runtime's `ts_*` symbols and the grammar's
`tree_sitter_graphql` entry point. The merged-library shape lets
`BundledLibraryLookup` in graphitron-lsp register one
`SymbolLookup.libraryLookup` per platform instead of chaining separate
runtime / grammar lookups.

## Coordinates

```
no.sikt:graphitron-tree-sitter-natives:<runtime-version>-<build-n>
```

First release: `0.26.0-1`. Bump `<build-n>` for any binary change (grammar
update, build-flag change, recompiled artifact for any reason). Bump
`<runtime-version>` when the bundled tree-sitter runtime moves to a new
upstream release. Snapshots are not used; this artifact ships releases
only.

## Cutting a new release

See `graphitron-rewrite/docs/tree-sitter-natives-release.adoc` for the
human-driven release procedure. The short version: bump the version in
`pom.xml`, push, manually trigger
`.github/workflows/tree-sitter-natives-release.yml` from the GitHub
Actions UI. The workflow builds on five native runners, packages and
deploys via the central-publishing-maven-plugin, then runs a post-deploy
load+parse matrix against the freshly-published jar on each platform.

## Why a standalone pom

The module does not inherit from `graphitron-rewrite-parent`. Trunk's
parent runs at `10-SNAPSHOT` and declares no `<snapshotRepository>`;
inheriting would publish a natives jar whose parent reference is
unresolvable for Central consumers and would tie the natives release
cadence to the next 10.x rewrite release. The standalone shape costs the
inlined release plumbing (gpg, central-publishing, source, javadoc,
deploy) in `pom.xml` and decouples the cadence entirely. The parent's
`<modules>` list does not include this module either, so the regular
`mvn install -f graphitron-rewrite/pom.xml -Plocal-db` build does not
touch it.

## Layout

- `pom.xml` — the standalone module pom.
- `src/main/native/grammars/graphql/` — vendored bkegley grammar sources
  (`parser.c`, `tree_sitter/parser.h`, `LICENSE`, `UPSTREAM.md`).
- The per-platform binaries are *not* committed. The release workflow
  produces them on five native runners and merges them into the jar
  during the package+deploy stage.
