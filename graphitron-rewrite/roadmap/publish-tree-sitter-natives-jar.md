---
id: R203
title: Publish graphitron-tree-sitter-natives jar; retire vendored C build in graphitron-lsp
status: Spec
bucket: Backlog
theme: lsp
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# Publish graphitron-tree-sitter-natives jar; retire vendored C build in graphitron-lsp

`graphitron-lsp` today compiles the vendored tree-sitter runtime (`v0.26.0`) plus the bkegley tree-sitter-graphql grammar (`5e66e96`, both upstreams dormant) into a per-platform shared library on every `mvn install`. Three host-activated Maven profiles in `graphitron-lsp/pom.xml:86-197` invoke `src/main/native/build-native.sh` to produce `target/classes/lib/<os>-<arch>/libtree-sitter-graphql.{so,dylib}`; the build script rejects non-Linux non-Darwin hosts, so Windows is unimplemented (`BundledLibraryLookup.java:91-100` throws `UnsupportedOperationException`). The shape costs every developer and CI a C toolchain plus a non-trivial recompile on each build, ships only the platform the build is currently running on (CI builds `linux-x86_64` only; macOS rides on hope), and leaves R89 ("macOS / Windows CI verification") open as the multi-platform gap.

The chosen shape is a new published Maven artifact `no.sikt:graphitron-tree-sitter-natives` whose jar carries prebuilt binaries for every supported platform under the `lib/<os>-<arch>/...` layout `BundledLibraryLookup` already resolves. The artifact has its own version stream tracking the bundled tree-sitter runtime (`<runtime-version>-<build-n>`, e.g. `0.26.0-1`); built by a GitHub Actions matrix on native runners using the upstream `tree-sitter build` CLI; published to Maven Central via the existing Sonatype central-publishing-maven-plugin configuration (`graphitron-rewrite/pom.xml:262-337`). `graphitron-lsp` consumes the published jar as one `<dependency>`. The on-build C compile, the three `build-native-*` profiles, and the vendored `src/main/native/` tree all delete.

Design rationale was explored against three alternatives (committing binaries directly in graphitron-lsp; depending on bonede's prebuilt jars; standing up a separate repo). The bonede route was probed end-to-end with jtreesitter 0.26 and works on four of five platforms but the bonede Windows DLL exports only `Java_org_treesitter_*` JNI wrappers — zero bare `ts_*` runtime symbols — because MSVC defaults to no-export and the upstream tree-sitter C sources carry no `__declspec(dllexport)` annotations. Building ourselves with `tree-sitter build` (which knows how to do MSVC exports) gives us Windows. Committing binaries directly in graphitron-lsp couples the artifact's version to graphitron's release cadence, which doesn't match how often the C sources actually change. A separate repo adds cross-repo PR coordination for source updates that happen roughly never. Same-repo module with detached `<version>` is the middle.

## Design decisions

- **Module location:** `graphitron-rewrite/graphitron-tree-sitter-natives/`.
- **Maven coordinates:** `no.sikt:graphitron-tree-sitter-natives`.
- **Version scheme:** `<runtime-version>-<build-n>`. First release `0.26.0-1`. Bump `<build-n>` for any change to the bundled binaries (grammar update, build flag change, recompiled artifact for any reason). Bump `<runtime-version>` when moving the bundled tree-sitter runtime to a new upstream version. Snapshots not used; this artifact ships releases only.
- **Detached version, not in parent reactor.** The module sets its own `<version>0.26.0-1</version>` explicitly (inherits `<groupId>` and pluginManagement from `graphitron-rewrite-parent` via `<parent>`, but the version is decoupled). The parent pom's `<modules>` list does *not* include the natives module. This keeps `mvn install -f graphitron-rewrite/pom.xml -Plocal-db` zero-cost (the natives module isn't part of the reactor) and forces release builds to invoke the module's pom directly. `graphitron-lsp` depends on the published artifact via Maven Central (or local m2 cache), not on a reactor-built sibling.
- **Build tool:** the upstream `tree-sitter` CLI's `tree-sitter build` subcommand, downloaded fresh per CI run from `https://github.com/tree-sitter/tree-sitter/releases/`. The CLI is pinned to a tag (initially `v0.26.9` or current latest at first-release time). `tree-sitter build` compiles the grammar's `parser.c` together with its runtime into one unified `.so`/`.dylib`/`.dll` with the runtime API correctly exported on every platform, including Windows. No custom `cc`/`cl.exe` flags.
- **Matrix entries:** five — `linux-x86_64`, `linux-aarch64`, `macos-x86_64`, `macos-aarch64`, `windows-x86_64`. Exact GitHub-hosted runner labels picked at implementation time (the available labels shift); use whatever native arm64 runners GitHub offers for public repos at release time. `linux-aarch64` is a gain over current state (we ship only `linux-x86_64` today).
- **Jar layout:** binaries at `lib/<os>-<arch>/libtree-sitter-graphql.{so,dylib,dll}` (Windows file: `tree-sitter-graphql.dll`, no `lib` prefix per platform convention). Matches `BundledLibraryLookup.resolveResourcePath`'s existing format with two new branches added (`linux-aarch64`, `windows-x86_64`). Jar also includes `META-INF/UPSTREAM.md` documenting the bundled tree-sitter runtime version + grammar commit.
- **Grammar source:** vendored at `graphitron-tree-sitter-natives/src/main/native/grammars/graphql/` (`parser.c` + `tree_sitter/parser.h` + `LICENSE` + `UPSTREAM.md`, moved verbatim from graphitron-lsp). Pinned to bkegley commit `5e66e96`. Tree-sitter runtime sources are *not* vendored: `tree-sitter build` includes its own runtime per invocation.
- **Workflow trigger:** `workflow_dispatch` only (manual). No automatic builds on push. Cutting a release is a deliberate human-driven action — bump the version in the pom, push, trigger the workflow. Tagging is optional; the artifact is identified by Maven version, not git tag.
- **Bootstrap path:** the first release is published before any change to `graphitron-lsp` lands. Phase 1 lands the natives module + the workflow file without touching graphitron-lsp; phase 2 triggers the workflow and verifies the published `0.26.0-1` artifact resolves from Central; phase 3 swaps graphitron-lsp's pom to depend on the published version and deletes the vendored sources. Three phases, three observable seams.

## Implementation

### Phase 1 — natives module + release workflow

- Create `graphitron-rewrite/graphitron-tree-sitter-natives/pom.xml`. `<packaging>jar</packaging>`, no Java sources, no test sources. `<parent>` points at `graphitron-rewrite-parent` (inherits groupId + plugin config), but the module's own `<version>0.26.0-1</version>` is explicit and not in the parent's `<modules>` list.
- Move grammar sources from `graphitron-lsp/src/main/native/grammars/graphql/` to `graphitron-tree-sitter-natives/src/main/native/grammars/graphql/` (`parser.c`, `tree_sitter/parser.h`, `LICENSE`, `UPSTREAM.md`). Leave them in graphitron-lsp until phase 3 deletes them; in phase 1 they exist in both places so the current LSP build keeps working.
- Add `graphitron-tree-sitter-natives/UPSTREAM.md` describing the bundled tree-sitter runtime version + grammar commit + how to cut a new release (link to the workflow file).
- Add `.github/workflows/tree-sitter-natives-release.yml`. `on: workflow_dispatch`. Matrix job runs on five native runners: each downloads the pinned `tree-sitter` CLI release from `github.com/tree-sitter/tree-sitter/releases`, runs `tree-sitter build --output libtree-sitter-graphql.<ext> <grammar-dir>` against the vendored grammar, uploads the resulting binary as a workflow artifact named `native-<os>-<arch>`. Package job downloads all five artifacts, places them under `target/classes/lib/<os>-<arch>/`, runs `mvn -f graphitron-rewrite/graphitron-tree-sitter-natives/pom.xml deploy -Prelease` to push to Central. The existing `release` profile (parent pom `:262-337`) handles GPG signing + central-publishing.
- Smoke test inside the natives module: a tiny `mvn package` invocation in CI (before the release job) that asserts the jar contains exactly the expected five `lib/<os>-<arch>/...` entries and no others. Catches missing matrix entries before they get to Central.

### Phase 2 — first release

- Trigger the workflow manually. Verify the matrix succeeds on all five platforms. Inspect the resulting jar (download from the workflow's package job before deploy) — confirm layout, sizes, that each binary contains both `tree_sitter_graphql` and the `ts_*` runtime symbols (for Windows, confirm exports via `dumpbin /exports` in the CI logs).
- Run the deploy step. Verify `no.sikt:graphitron-tree-sitter-natives:0.26.0-1` appears on Maven Central within the central-publishing-maven-plugin's typical sync window.
- Resolve the published artifact from a clean local m2 to confirm it works for downstream consumers (the central-publishing-maven-plugin sometimes has staging quirks; verify before phase 3).

### Phase 3 — graphitron-lsp cutover

- `graphitron-lsp/pom.xml`: add `<dependency>no.sikt:graphitron-tree-sitter-natives:0.26.0-1</dependency>`. Delete the three `build-native-*` profiles (lines `86-197`) and the `org.codehaus.mojo:exec-maven-plugin` plumbing.
- Delete `graphitron-lsp/src/main/native/` entirely (grammar already lives in the natives module after phase 1; the runtime sources never need to live in the LSP module again).
- `BundledLibraryLookup.resolveResourcePath` (`graphitron-lsp/src/main/java/.../parsing/BundledLibraryLookup.java:77-101`): add two new branches — `linux-aarch64` mapping to `lib/linux-aarch64/libtree-sitter-graphql.so`, `windows-x86_64` (matched on `os.name` containing `windows`, `os.arch` ∈ {`amd64`, `x86_64`}) mapping to `lib/windows-x86_64/tree-sitter-graphql.dll`. The merged-lib assumption holds — `tree-sitter build` produces one unified library exporting both runtime + grammar symbols — so `BundledLibraryLookup.get(arena)` keeps its current shape (one `SymbolLookup.libraryLookup(extractedLibrary, arena)`).
- Update test docstrings: `IncrementalParseTest.java:15` ("proving it works through the bonede binding closes one of the biggest …") and `TreeSitterSmokeTest.java:11` ("Verifies that the bonede tree-sitter Java binding loads its native libraries") drop the bonede references; both now exercise the natives-jar path.
- `NativeBuildSmokeTest.java` (`graphitron-lsp/src/test/java/.../native_build/`): rename to something like `NativeLibraryBundleTest.java` (the test no longer asserts "the build produced a library"; it asserts "the bundled binary loads + parses"), and add a fifth `@EnabledOnOs(value = OS.WINDOWS, architectures = "amd64")` method. The existing three methods stay; the fourth (`linux-aarch64`) and fifth (`windows-x86_64`) get added.

### Documentation

- `graphitron-rewrite/docs/` gets one new short doc (`tree-sitter-natives-release.adoc` or fold into the existing LSP-internals doc): how to cut a new natives release, when to bump `<runtime-version>` vs `<build-n>`, what the smoke-test confirms, where the published artifact lives. Internal to the rewrite; not a user-facing page.
- The `UPSTREAM.md` files moved from `graphitron-lsp/src/main/native/` are the source-of-truth pin and update with each runtime/grammar version change.

## Tests

- Natives module's `mvn package` smoke test asserts jar layout (exactly five `lib/<os>-<arch>/` entries, expected file names per platform).
- `NativeLibraryBundleTest.java` in graphitron-lsp covers each of the five platforms with `@EnabledOnOs`, loads the bundled library through `BundledLibraryLookup`, parses a trivial GraphQL document, asserts root node + no errors.
- Windows verification has no pre-existing test fixture; the new fifth method establishes it.
- No pipeline-tier or execution-tier coverage changes — the natives jar is a build-time substitution; everything downstream sees identical behaviour.

## Roadmap entries

- **R89** (`lsp-native-build-multiplatform-ci`) is subsumed and deleted as part of this item's `In Review → Done`. Its core concern (verify macOS / Windows actually work) survives as the two additional smoke-test methods on `NativeLibraryBundleTest`; the "stand up a CI matrix on every build" framing is moot once binaries come from a separate release pipeline.

## Acceptance

- `no.sikt:graphitron-tree-sitter-natives:0.26.0-1` exists on Maven Central with binaries for all five platforms; the artifact resolves into a fresh local m2 without errors.
- `mvn install -f graphitron-rewrite/pom.xml -Plocal-db` from a clean checkout no longer invokes `cc` or any C toolchain. The build completes without `src/main/native/` existing in `graphitron-lsp/`.
- `NativeLibraryBundleTest` passes on all five host platforms in CI (one platform per matrix entry; each enabled-on-os method runs only on its host).
- R89 is deleted from `graphitron-rewrite/roadmap/`.

## Non-goals

- GraalVM native image support for graphitron-lsp.
- Runtime auto-download of native libs from Maven Central at first use (the jar dep ships them on the classpath; the standard pattern).
- Supporting tree-sitter grammars other than the LSP's current single GraphQL grammar.
- Consuming the natives jar from any module other than graphitron-lsp.
- Adding `linux-arm` (32-bit) or `powerpc64` despite the upstream CLI shipping for them — no consumer.
