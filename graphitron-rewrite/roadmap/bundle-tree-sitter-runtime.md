---
id: R401
title: "Bundle the tree-sitter runtime in the natives jar (zero system deps)"
status: In Progress
bucket: feature
theme: lsp
depends-on: []
created: 2026-06-30
last-updated: 2026-06-30
---

# Bundle the tree-sitter runtime in the natives jar (zero system deps)

## Problem

`mvn graphitron:dev` runs an LSP whose GraphQL parser needs two native pieces: the
`tree-sitter-graphql` grammar (exports `tree_sitter_graphql`) and the tree-sitter runtime
`libtree-sitter` (exports the `ts_*` symbols). Today only the grammar ships, bundled
per-platform in `no.sikt:graphitron-tree-sitter-natives` and extracted to a temp file by
`graphitron-lsp`'s `BundledLibraryLookup`. The runtime is treated as a system dependency.

That system-dependency model has a real cost for our developers, and we have Windows
developers. The supported Windows path is `vcpkg install tree-sitter:x64-windows`, which means
bootstrapping vcpkg and making the DLL discoverable; in practice the recipe is too hard for
most developers. The default Debian/Ubuntu path is worse: apt's `libtree-sitter0` is pinned to
`0.20.x`, predates the `ts_language_abi_version` symbol jtreesitter 0.26 needs, and fails at LSP
startup, forcing a from-source build. Only Arch (`pacman`), Fedora (`dnf`), and macOS Homebrew
are genuine one-liners. The goal of this item is to delete the system dependency entirely so the
LSP has nothing to install.

## Background

How loading works today (`graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/parsing/`):

- `BundledLibraryLookup` is the registered jtreesitter `NativeLibraryLookup` SPI. Its `get(Arena)`
  extracts the grammar binary from the classpath to a temp file, opens it with
  `SymbolLookup.libraryLookup`, and composes a best-effort system-probed `libtree-sitter`
  (`probeSystemTreeSitter` / `candidateRuntimePaths`) via `SymbolLookup.or`.
- `GraphqlLanguage.loadOrExplain()` calls `Language.load(lookup, "tree_sitter_graphql")` and, on
  failure, runs `classifyInstalledRuntime()` (a `RuntimeStatus` probe of well-known paths checking
  for `ts_language_abi_version`) to choose between `missingRuntimeMessage()` and
  `tooOldRuntimeMessage()`. `DOCS_URL` (`GraphqlLanguage.java:235`) points operators at the
  getting-started native-runtime section.
- The grammar binary is self-contained: it exports only `tree_sitter_graphql` and does not import
  `ts_*`. The release workflow proves this (the `Verify exported symbols` step and the verifier
  harness load the grammar standalone). So grammar and runtime have no load-order constraint, and
  composing a bundled runtime is mechanically the same as the existing grammar+system composition.

The natives module is deliberately standalone: it does not inherit `graphitron-rewrite-parent`, is
omitted from the parent's `<modules>` list (so `mvn install -Plocal-db` pays zero cost), and ships
via a manual `workflow_dispatch` release to Maven Central (`tree-sitter-natives-release.yml`).
Version scheme is `<runtime-version>-<build-n>` (currently `0.26.0-1`), where `<runtime-version>`
today means "the parser ABI the grammar was built against," not a runtime we ship. The grammar's
provenance bar is set by `src/main/native/grammars/graphql/UPSTREAM.md`: a pinned upstream source,
recorded license, and a reproduce/update procedure. The runtime binaries are produced on four
native runners in CI and merged into the jar; they are not committed to the repo.

**Threat model (records the pinning judgment).** This is dev-time-only tooling. The runtime parses
trusted local `.graphqls` files inside the developer's `mvn graphitron:dev` JVM. It never ships in
a consumer's generated application, never crosses the DataFetcher boundary, and never reaches a
deployed service. Security/bugfix cadence is handled by bumping `<build-n>` and cutting a natives
release. Pinning the runtime is therefore acceptable and is the simpler-fails-understandably shape:
one resolved artifact instead of five per-platform install recipes plus a too-old trap.

## Design alternatives considered

1. **Bundle all four platforms (chosen).** Ship `libtree-sitter` for `linux-x86_64`,
   `linux-aarch64`, `macos-aarch64`, `windows-x86_64`, giving a single uniform invariant: "the LSP
   has no native system dependency." Composes the bundled runtime into the SPI lookup; the grammar
   is unchanged.

2. **Bundle-where-it-hurts (Windows + a Debian/Ubuntu story), system dep elsewhere.** The
   `principles-architect` review raised this on the supply-chain axis: Arch/Fedora/Homebrew are
   already one-liners, so bundling them converts three working "the OS owns provenance" paths into
   three binaries we own the provenance, signing, and CVE-response for in a published artifact.
   Rejected in favor of (1) because a uniform "always bundled" rule is itself a simplicity win and
   avoids a confusing "bundled on these two, system on those two" split; the added provenance surface
   is handled by the release-gate assertions below. Recorded because it is a genuine trade-off, not a
   clear loser, and a reviewer may want to re-weigh it.

3. **Keep the full system-probe as a standing fallback under the bundled runtime.** Rejected: two
   mechanisms for one job. Once all four supported platforms ship a runtime, the system-search +
   ABI-classification + too-old-diagnostic apparatus is dead code on every supported platform
   (exercised only on the out-of-scope Intel Mac, which has no bundled grammar either and fails
   earlier at `resolveResourcePath`). See the diagnostics collapse in Direction.

4. **Auto-download the runtime on first `dev` run, or ship a drop-in DLL + install script.**
   Rejected: both add a network or manual step. Bundling into a jar that is already on the LSP's
   classpath and already extracted-to-temp dominates, and works offline.

**Windows DLL sourcing (sub-fork, leading option recorded; final pick is an early-implementation
spike).** Native MSVC does not directly build `libtree-sitter` as a DLL; two viable routes:

- **(a) Build from pinned upstream source with MinGW-w64 via the Makefile.** Under MSYS2 on the
  `windows-latest` runner: `make CC=x86_64-w64-mingw32-gcc AR=x86_64-w64-mingw32-ar
  STRIP=x86_64-w64-mingw32-strip`. This path was merged upstream (tree-sitter PR #4201, Feb 2025,
  in 0.26.x), exports all non-static `ts_*` symbols, and the resulting DLL is consumable via the
  FFM `SymbolLookup.libraryLookup`. Risk: a MinGW-built DLL may carry transitive deps
  (`libgcc_s_seh-1.dll`, `libwinpthread-1.dll`); mitigate by static-linking (`-static-libgcc
  -static`) and proving it with the transitive-dep release gate below.
- **(b) Reuse the vcpkg-built `tree-sitter.dll`.** CI already installs it in `post-deploy-verify`.
  MSVC-built, depends only on the universal CRT (no MinGW runtime), so lower transitive-dep risk.
  Weaker provenance: the artifact is "vcpkg's build," not "our build from a pinned source tag."

  Lead with **(a)** for provenance parity with the grammar (built from pinned upstream source on our
  own runners), with the transitive-dep allowlist enforced as a release gate; fall back to (b) only
  if the MinGW runtime deps prove unshakeable.

## Direction

### CI / release workflow (the bulk of the work)

Extend `.github/workflows/tree-sitter-natives-release.yml`:

- **Build the runtime per matrix platform** from the pinned source tag (reuse the existing
  `tree-sitter-cli-version` input, default `v0.26.9`; clone that tag and build the `lib/`, do not
  rely on the CLI binary for the runtime). `linux-x86_64` / `linux-aarch64`: `make` -> `.so`.
  `macos-aarch64`: `make` -> `.dylib`. `windows-x86_64`: MSYS2 + MinGW-w64 `make CC=...` -> `.dll`
  (leading option a). Stage each under `lib/<os>-<arch>/` next to the grammar.
- **New release-gate assertions** (mirror the existing `Verify exported symbols` step), per
  platform, failing the release on violation:
  - the runtime exports `ts_language_abi_version` and the core `ts_parser_*` symbols;
  - the runtime links only against a per-platform allowlist (POSIX: `ldd` / `otool -L`; Windows:
    `dumpbin /dependents` permitting only `kernel32` / `ucrtbase` / `vcruntime` style system DLLs).
    Anything outside the allowlist fails the build. This is what keeps "we control what we ship"
    enforced rather than asserted in prose.
- **Update the `Assert jar layout` step**: the expected set goes from four grammar files to exactly
  eight (four grammar + four runtime), with the runtime file names pinned.
- **`post-deploy-verify`**: delete the per-OS `Install libtree-sitter` steps; the verifier composes
  the bundled runtime (`grammar.or(bundledRuntime)`) and must load+parse with **no** system
  `libtree-sitter` present, proving the jar is self-sufficient on all four platforms.

### Provenance (close the gap the architect flagged)

Add a runtime provenance record matching the grammar's `UPSTREAM.md` bar (a `lib/UPSTREAM.md` or an
extension of the module `UPSTREAM.md`): the exact tree-sitter source tag, the per-platform build
invocation and flags (including the MinGW static-link flags), the expected transitive-dep allowlist,
and the update procedure. Binaries remain un-committed (built in CI on native runners, same as the
grammar); provenance = committed recipe + pinned tag + release-gate assertions.

### Version scheme

`<runtime-version>` stops meaning "ABI the grammar was built against" and becomes the actual shipped
runtime version. First bundled release bumps accordingly (e.g. `0.26.9-1`; exact value set at
release time). The `tooOld` failure mode ceases to exist because we ship the runtime.

### LSP code (small, in `graphitron-lsp`)

- `BundledLibraryLookup`: extract the bundled runtime with the same extract-to-temp pattern already
  used for the grammar (a second resource path `lib/<os>-<arch>/libtree-sitter.{so,dylib,dll}`), and
  return `grammar.or(bundledRuntime)`. Delete `probeSystemTreeSitter` and `candidateRuntimePaths`.
- `GraphqlLanguage`: collapse the dual mechanism per the architect's altitude finding. Delete
  `classifyInstalledRuntime`, `RuntimeStatus`, `runtimeProbePaths`, `tooOldRuntimeMessage`,
  `missingRuntimeMessage`, and the `ABI_VERSION_SYMBOL` probe. Keep exactly **one** diagnostic: a
  "bundled tree-sitter runtime failed to load from `<temp path>`" message wrapping the
  `UnsatisfiedLinkError`, covering the corrupt-extract / `noexec` tmpdir / missing-CRT cases.
  Unsupported arches continue to fail fast at `resolveResourcePath`'s `UnsupportedOperationException`.
- `DOCS_URL`: update in lockstep with the doc collapse below. (Note: it currently also has the
  `/architecture/` path bug spotted in the earlier docs work, `/getting-started.html` should be
  `/architecture/getting-started.html`; this item either fixes or repoints it to the reference page.)

## User documentation (first-client check)

Per `workflow.adoc`, the user docs are the first client of this design; if they do not read as
"nothing to install," the design is wrong. The win is that the entire "Native runtime dependency"
install matrix collapses.

**Drafted replacement for `docs/manual/reference/lsp-requirements.adoc`** (the page shrinks to):

> The Graphitron LSP has no system dependencies. Both native pieces of its GraphQL parser, the
> tree-sitter grammar and the tree-sitter runtime, ship in the `no.sikt:graphitron-tree-sitter-natives`
> jar and are extracted at startup; you do not install anything.
>
> Supported host architectures: `linux-x86_64`, `linux-aarch64`, `macos-aarch64`, `windows-x86_64`.
> Intel Mac (`macos-x86_64`) is not shipped. On an unsupported architecture the LSP fails fast at
> startup naming the `os.name` / `os.arch` it saw and the supported set.

The getting-started `#native-runtime-dependency` section collapses the same way: the per-platform
install table and the NixOS `shell.nix` `LD_LIBRARY_PATH` snippet are removed (NixOS no longer needs
any wiring). The Windows/WSL discussion from the earlier docs thread becomes moot for the bundled
case. This reads as "nothing to install," which is the design's success condition.

## Acceptance / verification

- Release-gate assertions (workflow, per platform): exported-symbol check includes
  `ts_language_abi_version`; transitive-dep allowlist holds; jar layout is exactly the eight pinned
  entries.
- `post-deploy-verify` load+parse harness passes on all four platforms with **no** system
  `libtree-sitter` installed (the single-load-path proof).
- `graphitron-lsp` tests (the natives module has no Java tests): the existing parsing/smoke tests
  (`TreeSitterSmokeTest` and peers) exercise the bundled runtime path and pass with the system
  `libtree-sitter` absent.
- A test pinning the collapsed-diagnostic behavior: a bundled-runtime load failure surfaces the
  single "failed to load from `<temp path>`" message (simulate by pointing the lookup at a bogus
  extracted path).

## Out of scope

- Intel Mac (`macos-x86_64`): still not shipped; behavior unchanged (fails at `resolveResourcePath`).
- Additional architectures (e.g. `linux-armv7`).
- Any change to the grammar vendoring or its build.
- The natives module's standalone-pom / `workflow_dispatch` shape stays; the runtime build lives in
  the release workflow, out of the normal reactor.

## Implementation status / rollout sequencing

This item spans a Maven Central release boundary and cannot land in one
trunk-green commit. The two halves and their ordering:

**Half A (landed, trunk-safe now).** The release workflow, provenance, and
version scheme. None of this runs in per-PR CI (the natives workflow is
`workflow_dispatch`-only and the module is outside the reactor), and
`graphitron-lsp` still consumes the existing `0.26.0-1` grammar-only jar, so
the rewrite build stays green.

- `tree-sitter-natives-release.yml`: per-platform runtime build from the
  pinned source tag (POSIX `make`; Windows MinGW-w64 leading route, route (a)
  in "Design alternatives"); release-gate assertions (exported `ts_*`
  symbols + transitive-dep allowlist); jar-layout assertion raised to eight
  entries; `post-deploy-verify` drops the `Install libtree-sitter` steps and
  composes the bundled runtime with no system runtime present.
- `graphitron-tree-sitter-natives/pom.xml`: version `0.26.0-1` -> `0.26.9-1`;
  description updated.
- `UPSTREAM.md` (module): runtime provenance record; version-scheme prose.
- `docs/tree-sitter-natives-release.adoc`: operator doc updated to the
  bundled-runtime workflow (two binaries, eight-entry jar, no-system-runtime
  verify).

**Gate between A and B (human action, cannot be done from an agent
session).** A maintainer dispatches `tree-sitter-natives-release.yml` with
`tree-sitter-cli-version=v0.26.9`. The first dispatch validates the runtime
build empirically; the Windows MinGW transitive-dep allowlist is the spec's
acknowledged spike (fall back to route (b), the vcpkg-built DLL, only if the
MinGW runtime deps prove unshakeable). On success, `0.26.9-1` is on Central
with all eight entries.

**Half B (blocked on the gate; do NOT ship to trunk until `0.26.9-1` is
published).** Until the new jar exists, these changes break every
`graphitron-lsp` parsing test (extraction of `lib/<os>-<arch>/libtree-sitter.*`
from a jar that does not contain it):

- `graphitron-lsp/pom.xml`: bump the natives dependency to `0.26.9-1`.
- `BundledLibraryLookup`: extract the bundled runtime, return
  `grammar.or(bundledRuntime)`; delete `probeSystemTreeSitter` /
  `candidateRuntimePaths`.
- `GraphqlLanguage`: collapse the dual mechanism (delete
  `classifyInstalledRuntime`, `RuntimeStatus`, `runtimeProbePaths`,
  `tooOldRuntimeMessage`, `missingRuntimeMessage`, `ABI_VERSION_SYMBOL`); keep
  one bundled-load-failure diagnostic; fix/repoint `DOCS_URL`.
- Tests: rework `GraphqlLanguageErrorTranslationTest` to the single
  diagnostic + add the bogus-extracted-path failure test;
  `NativeLibraryBundleTest` / `TreeSitterSmokeTest` exercise the bundled
  runtime with no system `libtree-sitter`.
- Docs collapse: `docs/manual/reference/lsp-requirements.adoc` and
  `getting-started.adoc#native-runtime-dependency` to "nothing to install"
  (the first-client check). These are deliberately held with Half B: shipping
  them before the LSP consumes the bundled jar would tell users "nothing to
  install" while the released LSP still needs a system runtime.

## References

- `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/parsing/BundledLibraryLookup.java`
  - the SPI, the extract-to-temp pattern, the system-probe to delete.
- `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/parsing/GraphqlLanguage.java`
  - the diagnostics to collapse; `DOCS_URL` at `:235`.
- `.github/workflows/tree-sitter-natives-release.yml` - build matrix, `Verify exported symbols`,
  `Assert jar layout`, `post-deploy-verify`.
- `graphitron-rewrite/graphitron-tree-sitter-natives/UPSTREAM.md` and `pom.xml` - version scheme and
  the standalone-pom rationale.
- `graphitron-rewrite/graphitron-tree-sitter-natives/src/main/native/grammars/graphql/UPSTREAM.md`
  - the provenance bar the runtime record must match.
- `docs/manual/reference/lsp-requirements.adoc` and `graphitron-rewrite/docs/getting-started.adoc`
  `#native-runtime-dependency` - the docs that collapse.
- tree-sitter PR #4201 (MinGW-w64 Windows runtime build via Makefile, merged Feb 2025).
- `principles-architect` review (this session): pin runtime provenance like the grammar; collapse the
  dual load mechanism to a single bundled-load-failure diagnostic.
