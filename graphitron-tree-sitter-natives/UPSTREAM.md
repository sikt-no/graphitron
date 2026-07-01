# graphitron-tree-sitter-natives

Standalone Maven module that publishes a single jar containing per-platform
tree-sitter shared libraries for four host platforms. Each platform ships
**two** binaries: the GraphQL grammar and the tree-sitter runtime.

- `linux-x86_64` → `lib/linux-x86_64/libtree-sitter-graphql.so` + `lib/linux-x86_64/libtree-sitter.so`
- `linux-aarch64` → `lib/linux-aarch64/libtree-sitter-graphql.so` + `lib/linux-aarch64/libtree-sitter.so`
- `macos-aarch64` → `lib/macos-aarch64/libtree-sitter-graphql.dylib` + `lib/macos-aarch64/libtree-sitter.dylib`
- `windows-x86_64` → `lib/windows-x86_64/tree-sitter-graphql.dll` + `lib/windows-x86_64/tree-sitter.dll`

Apple-silicon-only on macOS: Sikt graphitron-lsp developers all run M1
or newer, the macos-13 (Intel) GitHub-hosted runner is the slowest queue
on the platform, and graphitron-lsp is internal-only per the spec's
"Out of scope" list, so Intel-Mac coverage costs significant CI time
without a known consumer.

The grammar binary (`tree-sitter-graphql.{so,dylib,dll}`) is the vendored
bkegley tree-sitter-graphql grammar, exposing the grammar's
`tree_sitter_graphql` entry point only; it imports no `ts_*` symbols and so
loads independently of the runtime. The runtime binary
(`libtree-sitter.{so,dylib}`, `tree-sitter.dll` on Windows) is the upstream
tree-sitter runtime built from the pinned `0.26.9` source tag, exporting the
`ts_*` symbols (including `ts_language_abi_version`, which jtreesitter 0.26
requires). graphitron-lsp extracts both at startup and composes
`grammar.or(runtime)` into jtreesitter's SPI lookup, so the LSP has **no**
native system dependency: nothing to `brew install`, `pacman -S`, `vcpkg
install`, or build from source. Bundling the runtime also retires the
Debian/Ubuntu trap where apt's `libtree-sitter0` (0.20.x) predates
`ts_language_abi_version` and fails at load.

## Coordinates

```
no.sikt:graphitron-tree-sitter-natives:<runtime-version>-<build-n>
```

First bundled-runtime release: `0.26.9-1`. The `<runtime-version>` portion
is the actual tree-sitter runtime version shipped in the jar (the source tag
both the grammar's parser ABI and the bundled `libtree-sitter` are built
from). It must match the `tree-sitter-cli-version` workflow input at release
time (tag `v0.26.9` → version `0.26.9-<build-n>`). Bump `<build-n>` for any
change to the binaries at the same upstream version (build-flag change,
recompile, security/bugfix rebuild). Bump `<runtime-version>` when we
retarget to a newer upstream tree-sitter tag. Snapshots are not used; this
artifact ships releases only.

Note: before `0.26.9-1`, `<runtime-version>` meant "the parser ABI the
grammar was built against" and the runtime was a system dependency. From
`0.26.9-1` on it means the runtime we actually ship.

## Cutting a new release

See `graphitron-rewrite/docs/tree-sitter-natives-release.adoc` for the
human-driven release procedure. The short version: bump the version in
`pom.xml`, push, manually trigger
`.github/workflows/tree-sitter-natives-release.yml` from the GitHub
Actions UI. The workflow builds both the grammar and the runtime on four
native runners, runs the release-gate assertions (exported symbols,
transitive-dep allowlist, eight-entry jar layout), packages and deploys via
the central-publishing-maven-plugin, then runs a post-deploy load+parse
matrix against the freshly-published jar on each platform **with no system
`libtree-sitter` installed**, proving the jar is self-sufficient.

## Runtime provenance

The bundled `libtree-sitter` is built in CI from the pinned upstream source
tag, to the same provenance bar as the grammar (committed recipe + pinned
tag + release-gate assertions; binaries are not committed).

- **Source.** `https://github.com/tree-sitter/tree-sitter`, tag matching the
  `tree-sitter-cli-version` workflow input (`v0.26.9` for `0.26.9-<build-n>`).
  Shallow clone of the tag; the runtime is built from `lib/`, not from the
  CLI binary.
- **Build invocation (POSIX, linux + macOS).** `make CFLAGS="-O3"`; the
  resulting unversioned shared-lib symlink is dereferenced and staged under
  the canonical `libtree-sitter.{so,dylib}` name.
- **Build invocation (Windows, MinGW-w64 under MSYS2).**
  `make CC=gcc AR=ar CFLAGS="-O3" LDFLAGS="-static -static-libgcc"`, staged as
  `tree-sitter.dll`. In the MSYS2 MINGW64 environment the unprefixed `gcc`/`ar`
  (from `mingw-w64-x86_64-gcc`, in `/mingw64/bin`) are the native x86_64
  mingw-w64 toolchain; the triplet-prefixed `x86_64-w64-mingw32-ar` alias is
  not installed, so the unprefixed names are used. The static-link flags keep
  the MinGW runtime (`libgcc_s_seh-1.dll`, `libwinpthread-1.dll`) out of the
  DLL's transitive deps. (Leading route;
  the fallback if these deps prove unshakeable is the MSVC/vcpkg-built
  `tree-sitter.dll`, at the cost of weaker provenance.)
- **Transitive-dep allowlist (release gate).**
  - Linux (`ldd`): glibc + the dynamic loader only (`libc`, `libm`, `libdl`,
    `libpthread`, `librt`, `libgcc_s`, `ld-linux`, `linux-vdso`).
  - macOS (`otool -L`): `/usr/lib/*` and `/System/Library/*` only.
  - Windows (`dumpbin /dependents`): the system CRT/kernel DLLs only
    (`kernel32`, `ucrtbase`, `vcruntime*`, `msvcrt`, `ntdll`, `advapi32`,
    `api-ms-win-*`). Any `libgcc_s_seh-1.dll` / `libwinpthread-1.dll` fails
    the release.
- **Exported-symbol gate.** The runtime must export `ts_language_abi_version`
  and the core `ts_parser_*` symbols.
- **Update procedure.** Bump the tag in the `tree-sitter-cli-version` input
  default and the `pom.xml` version in lockstep, then re-run the release
  workflow; the gates above re-validate provenance on every release.

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
- The per-platform binaries are *not* committed (neither grammar nor
  runtime). The release workflow produces both on four native runners
  (grammar via the `tree-sitter` CLI, runtime from the pinned source tag)
  and merges them into the jar during the package+deploy stage. See
  "Runtime provenance" above for the runtime build recipe and gates.
