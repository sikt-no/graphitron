# graphitron-tree-sitter-natives

Standalone Maven module that publishes a single jar containing per-platform
tree-sitter shared libraries for four host platforms:

- `linux-x86_64` → `lib/linux-x86_64/libtree-sitter-graphql.so`
- `linux-aarch64` → `lib/linux-aarch64/libtree-sitter-graphql.so`
- `macos-aarch64` → `lib/macos-aarch64/libtree-sitter-graphql.dylib`
- `windows-x86_64` → `lib/windows-x86_64/tree-sitter-graphql.dll`

Apple-silicon-only on macOS: Sikt graphitron-lsp developers all run M1
or newer, the macos-13 (Intel) GitHub-hosted runner is the slowest queue
on the platform, and graphitron-lsp is internal-only per the spec's
"Out of scope" list, so Intel-Mac coverage costs significant CI time
without a known consumer.

Each binary is the vendored bkegley tree-sitter-graphql grammar compiled
against the upstream tree-sitter `0.26.0` parser ABI, exposing the
grammar's `tree_sitter_graphql` entry point only. The tree-sitter runtime
itself (`libtree-sitter`) is **not** bundled. graphitron-lsp relies on
jtreesitter's `ChainedLibraryLookup` to find an OS-installed
`libtree-sitter` (`brew install tree-sitter` on macOS,
`apt install libtree-sitter0` on Linux, vcpkg or upstream build on
Windows). The runtime is a system dependency of graphitron-lsp, not of
this jar.

## Coordinates

```
no.sikt:graphitron-tree-sitter-natives:<runtime-version>-<build-n>
```

First release: `0.26.0-1`. The `<runtime-version>` portion is the
tree-sitter parser ABI the grammar was compiled against (currently
`0.26.0`); a consumer with an OS-installed `libtree-sitter` older than
this fails at `Language.load` with a clear ABI-mismatch error. Bump
`<build-n>` for any change to the grammar binaries (grammar source
update, build-flag change, recompile). Bump `<runtime-version>` when we
retarget the grammar to a newer tree-sitter ABI (which is when the
`tree-sitter` CLI's bundled runtime moves under us). Snapshots are not
used; this artifact ships releases only.

## Cutting a new release

See `graphitron-rewrite/docs/tree-sitter-natives-release.adoc` for the
human-driven release procedure. The short version: bump the version in
`pom.xml`, push, manually trigger
`.github/workflows/tree-sitter-natives-release.yml` from the GitHub
Actions UI. The workflow builds on four native runners, packages and
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
  under the `src/` layout `tree-sitter build` expects: `src/grammar.json`,
  `src/node-types.json`, `src/parser.c`, `src/tree_sitter/parser.h`, plus
  `LICENSE` and `UPSTREAM.md` at the top of the grammar dir.
- The per-platform binaries are *not* committed. The release workflow
  produces them on four native runners and merges them into the jar
  during the package+deploy stage.
