---
id: R203
title: Publish graphitron-tree-sitter-natives jar; retire vendored C build in graphitron-lsp
status: Spec
bucket: Backlog
theme: lsp
depends-on: []
created: 2026-05-20
last-updated: 2026-05-21
---

# Publish graphitron-tree-sitter-natives jar; retire vendored C build in graphitron-lsp

`graphitron-lsp` today compiles the vendored tree-sitter runtime (`v0.26.0`) plus the bkegley tree-sitter-graphql grammar (`5e66e96`, both upstreams dormant) into a per-platform shared library on every `mvn install`. Three host-activated Maven profiles in `graphitron-lsp/pom.xml:86-197` invoke `src/main/native/build-native.sh` to produce `target/classes/lib/<os>-<arch>/libtree-sitter-graphql.{so,dylib}`; the build script rejects non-Linux non-Darwin hosts, so Windows is unimplemented (`BundledLibraryLookup.java:91-100` throws `UnsupportedOperationException`). The shape costs every developer and CI a C toolchain plus a non-trivial recompile on each build, ships only the platform the build is currently running on (CI builds `linux-x86_64` only; macOS rides on hope), and leaves R89 ("macOS / Windows CI verification") open as the multi-platform gap.

The chosen shape is a new published Maven artifact `no.sikt:graphitron-tree-sitter-natives` whose jar carries prebuilt binaries for every supported platform under the `lib/<os>-<arch>/...` layout `BundledLibraryLookup` already resolves. The artifact has its own version stream tracking the bundled tree-sitter runtime (`<runtime-version>-<build-n>`, e.g. `0.26.0-1`); built by a GitHub Actions matrix on native runners that drive per-platform compile scripts producing one unified shared library per platform from the vendored tree-sitter runtime + tree-sitter-graphql grammar; published to Maven Central via `central-publishing-maven-plugin` configuration inlined into the module's standalone pom (matching the shape the rewrite parent's `release` profile uses at `graphitron-rewrite/pom.xml:262-337`, but not inherited from it). `graphitron-lsp` consumes the published jar as one `<dependency>`. The on-build C compile, the three `build-native-*` profiles, and the vendored `src/main/native/` tree all delete from graphitron-lsp; the same vendored sources move into the natives module, where the C build runs once per release rather than on every consumer's `mvn install`.

Design rationale was explored against three alternatives (committing binaries directly in graphitron-lsp; depending on bonede's prebuilt jars; standing up a separate repo). The bonede route was probed end-to-end with jtreesitter 0.26 and works on four of five platforms but the bonede Windows DLL exports only `Java_org_treesitter_*` JNI wrappers — zero bare `ts_*` runtime symbols — because MSVC defaults to no-export and the upstream tree-sitter C sources carry no `__declspec(dllexport)` annotations. Compiling ourselves (the shape graphitron-lsp's vendored `build-native.sh` already used on Linux/macOS, extended to Windows MSVC via a build-time-generated `.def` file enumerating the runtime's public symbols) gives us all five platforms with a single unified library exporting both `tree_sitter_graphql` and the full `ts_*` runtime ABI. (An earlier draft of this spec proposed delegating to the upstream `tree-sitter build` CLI; empirical check showed the 0.26.x CLI compiles parser-only and does not link the runtime, so the unity-build claim was wrong; the workflow's symbol-export assertion would have caught it before publishing.) Committing binaries directly in graphitron-lsp couples the artifact's version to graphitron's release cadence, which doesn't match how often the C sources actually change. A separate repo adds cross-repo PR coordination for source updates that happen roughly never. Same-repo module with detached `<version>` is the middle.

## Design decisions

- **Module location:** `graphitron-rewrite/graphitron-tree-sitter-natives/`.
- **Maven coordinates:** `no.sikt:graphitron-tree-sitter-natives`.
- **Version scheme:** `<runtime-version>-<build-n>`. First release `0.26.0-1`. Bump `<build-n>` for any change to the bundled binaries (grammar update, build flag change, recompiled artifact for any reason). Bump `<runtime-version>` when moving the bundled tree-sitter runtime to a new upstream version. Snapshots not used; this artifact ships releases only.
- **Standalone pom, not in parent reactor, no parent inheritance.** The module's pom is fully self-contained: explicit `<groupId>no.sikt</groupId>`, explicit `<version>0.26.0-1</version>`, no `<parent>` reference. The release plumbing (`maven-gpg-plugin`, `central-publishing-maven-plugin`, `maven-source-plugin`, `maven-javadoc-plugin`, `maven-deploy-plugin`) is inlined into the module's pom rather than picked up from `graphitron-rewrite-parent`'s `release` profile. Rationale: the rewrite parent's version on trunk is `10-SNAPSHOT`, which is not deployed to Maven Central (the parent declares no `<snapshotRepository>`); inheriting via `<parent>graphitron-rewrite-parent:10-SNAPSHOT</parent>` would publish a natives jar whose parent reference is unresolvable for any consumer, locking the natives release cadence behind the next 10.x rewrite release. Standalone shape costs ~60 lines of inlined plugin config (a near-empty sources jar + javadoc jar are still produced; Maven Central requires both for releases even when there are no Java sources) and removes the coupling. The parent pom's `<modules>` list also does *not* include the natives module, keeping `mvn install -f graphitron-rewrite/pom.xml -Plocal-db` zero-cost. `graphitron-lsp` depends on the published artifact via Maven Central (or local m2 cache), not on a reactor-built sibling.
- **Build tool:** per-platform compile scripts checked into the natives module under `src/main/native/`. `build-native.sh` for Linux + macOS calls `cc -shared -fPIC -O2 -std=c11` on the vendored grammar's `parser.c` together with the vendored tree-sitter runtime's amalgamation entry `lib/src/lib.c` (which `#includes` the rest of the runtime), producing one `.so`/`.dylib` per host that exports both `tree_sitter_graphql` and the full `ts_*` runtime ABI by default symbol visibility. This is the exact compile shape graphitron-lsp's existing `build-native.sh` already uses; the natives module adopts it verbatim. `build-native.ps1` for Windows calls `cl.exe` over the same two source files plus a build-time-generated `.def` file derived from the vendored runtime's `lib/include/tree_sitter/api.h` (enumerating the public `ts_*` symbols) with `tree_sitter_graphql` appended; the `.def` is passed to `link.exe` via `/DEF:` so MSVC's no-default-exports stance produces a DLL exporting the same surface as the POSIX variants. Both scripts take an output directory as their sole argument and write a single library file there. tree-sitter's CLI is not used (it builds parser-only and does not link the runtime).
- **Matrix entries:** five — `linux-x86_64`, `linux-aarch64`, `macos-x86_64`, `macos-aarch64`, `windows-x86_64`. Exact GitHub-hosted runner labels picked at implementation time (the available labels shift); use whatever native arm64 runners GitHub offers for public repos at release time. `linux-aarch64` is a gain over current state (we ship only `linux-x86_64` today).
- **Jar layout:** binaries at `lib/<os>-<arch>/libtree-sitter-graphql.{so,dylib,dll}` (Windows file: `tree-sitter-graphql.dll`, no `lib` prefix per platform convention). Matches `BundledLibraryLookup.resolveResourcePath`'s existing format with two new branches added (`linux-aarch64`, `windows-x86_64`). Jar also includes `META-INF/UPSTREAM.md` documenting the bundled tree-sitter runtime version + grammar commit.
- **Grammar source:** vendored at `graphitron-tree-sitter-natives/src/main/native/grammars/graphql/` (`parser.c` + `tree_sitter/parser.h` + `LICENSE` + `UPSTREAM.md`, moved verbatim from graphitron-lsp). Pinned to bkegley commit `5e66e96`.
- **Tree-sitter runtime source:** vendored at `graphitron-tree-sitter-natives/src/main/native/tree-sitter/` (the full upstream `lib/` subtree from tree-sitter v0.26.0 plus its license/notice files, moved verbatim from `graphitron-lsp/src/main/native/tree-sitter/`). Pinned to tree-sitter v0.26.0. Bumping `<runtime-version>` in the Maven coordinates is the same act as updating these sources in lock-step; the version mismatch would be caught by the workflow's post-deploy load+parse stage on the first runtime ABI change. The `lib/src/lib.c` amalgamation header is the single translation unit the build scripts compile into the unified library; the rest of the runtime is reachable through its `#include` chain.
- **Workflow trigger:** `workflow_dispatch` only (manual). No automatic builds on push. Cutting a release is a deliberate human-driven action — bump the version in the pom, push, trigger the workflow. Tagging is optional; the artifact is identified by Maven version, not git tag.
- **Bootstrap path:** the first release is published before any change to `graphitron-lsp` lands. Phase 1 lands the natives module + the workflow file without touching graphitron-lsp; phase 2 triggers the workflow and verifies the published `0.26.0-1` artifact resolves from Central; phase 3 swaps graphitron-lsp's pom to depend on the published version and deletes the vendored sources. Three phases, three observable seams.

## Implementation

A first Phase 1 attempt landed the natives module skeleton + workflow skeleton on `claude/graphitron-rewrite` but built parser-only via `tree-sitter build`; this spec was reverted to draft once that came to light. Nothing has been published to Maven Central: the workflow's symbol-export assertion catches the missing `ts_*` symbols and fails-closed at the build-matrix stage. The next implementer amends the existing files rather than re-creating them; the amendment list is in Phase 1 below.

### Phase 1 — natives module + release workflow

Land all of the following before triggering any release:

- Move the vendored tree-sitter runtime tree from `graphitron-lsp/src/main/native/tree-sitter/` to `graphitron-tree-sitter-natives/src/main/native/tree-sitter/` (copy for now; Phase 3 deletes the lsp-side copy). This is the full `lib/` subtree plus upstream license/notice files; pinned to v0.26.0.
- Add `graphitron-tree-sitter-natives/src/main/native/build-native.sh`: the verbatim shape of `graphitron-lsp/src/main/native/build-native.sh` (cc -shared -fPIC compile of `parser.c` + `lib/src/lib.c`), retargeted to the new grammar + runtime directories. Takes the output directory as its sole argument; writes one `.so` (Linux) or `.dylib` (macOS) there.
- Add `graphitron-tree-sitter-natives/src/main/native/build-native.ps1`: Windows MSVC equivalent. Compiles the same two source files with `cl.exe`, generates a `.def` file at build time by extracting `ts_*` function declarations from `lib/include/tree_sitter/api.h` and appending `tree_sitter_graphql`, links the resulting `.obj`s with `link.exe /DLL /DEF:<generated>.def`, writes one `tree-sitter-graphql.dll` to the supplied output directory.
- Rewrite the matrix "Build" step in `.github/workflows/tree-sitter-natives-release.yml` to invoke the appropriate platform script instead of `tree-sitter build`. Drop the workflow's `tree-sitter-cli-version` input and the CLI-download steps; the runtime version is now identified by the vendored sources and the Maven coordinate, not a CLI tag. The symbol-export assertion (stage 1) and the jar-layout + post-deploy-verify stages stay exactly as-is; they remain the correct gates.
- Update `BundledLibraryLookup.java`'s class docstring to reflect actual vendoring (currently claims the CLI does the unity build; the corrected story is that the natives module compiles the unity build itself from vendored sources).
- Update `graphitron-rewrite/docs/tree-sitter-natives-release.adoc` with the corrected build-tool story.

The standalone pom, the `no.sikt.graphitron.natives` placeholder Java package, and the existing release plumbing (gpg/source/javadoc/central-publishing) stay as-is; they were correct in the first attempt and are independent of the build-step error. The parent's `<modules>` list still omits the natives module; `mvn -f graphitron-rewrite/pom.xml ...` does not touch it.

### Phase 2 — first release

- Trigger the workflow manually. Verify the build matrix succeeds on all five platforms; the per-runner symbol-export assertion (stage 1) fails loudly if any binary lacks `tree_sitter_graphql` or the `ts_*` runtime symbols (the assertion that caught the parser-only build during the first Phase 1 attempt).
- Verify the package+deploy stage succeeds. Verify `no.sikt:graphitron-tree-sitter-natives:0.26.0-1` appears on Maven Central within the central-publishing-maven-plugin's typical sync window.
- Verify the post-deploy load+parse matrix (stage 3) succeeds on all five platforms — this is the authoritative "the jar works end-to-end on every supported platform" gate. If any platform's load+parse fails after a successful deploy, increment `<build-n>` to `0.26.0-2`, fix, and re-release; `0.26.0-1` stays on Central as a tombstone but `graphitron-lsp` pins to the working build.

### Phase 3 — graphitron-lsp cutover

- `graphitron-lsp/pom.xml`: add `<dependency>no.sikt:graphitron-tree-sitter-natives:0.26.0-1</dependency>`. Delete the three `build-native-*` profiles (lines `86-197`) and the `org.codehaus.mojo:exec-maven-plugin` plumbing.
- Delete `graphitron-lsp/src/main/native/` entirely (grammar already lives in the natives module after phase 1; the runtime sources never need to live in the LSP module again).
- `BundledLibraryLookup.resolveResourcePath` (`graphitron-lsp/src/main/java/.../parsing/BundledLibraryLookup.java:77-101`): add two new branches — `linux-aarch64` mapping to `lib/linux-aarch64/libtree-sitter-graphql.so`, `windows-x86_64` (matched on `os.name` containing `windows`, `os.arch` ∈ {`amd64`, `x86_64`}) mapping to `lib/windows-x86_64/tree-sitter-graphql.dll`. The merged-lib assumption holds — the natives module's build scripts compile one unified library per platform exporting both runtime + grammar symbols — so `BundledLibraryLookup.get(arena)` keeps its current shape (one `SymbolLookup.libraryLookup(extractedLibrary, arena)`).
- Update test docstrings: `IncrementalParseTest.java:15` ("proving it works through the bonede binding closes one of the biggest …") and `TreeSitterSmokeTest.java:11` ("Verifies that the bonede tree-sitter Java binding loads its native libraries") drop the bonede references; both now exercise the natives-jar path.
- `NativeBuildSmokeTest.java` (`graphitron-lsp/src/test/java/.../native_build/`): rename to something like `NativeLibraryBundleTest.java` (the test no longer asserts "the build produced a library"; it asserts "the bundled binary loads + parses"), and add a fifth `@EnabledOnOs(value = OS.WINDOWS, architectures = "amd64")` method. The existing three methods stay; the fourth (`linux-aarch64`) and fifth (`windows-x86_64`) get added.

### Documentation

- `graphitron-rewrite/docs/` gets one new short doc (`tree-sitter-natives-release.adoc` or fold into the existing LSP-internals doc): how to cut a new natives release, when to bump `<runtime-version>` vs `<build-n>`, what the smoke-test confirms, where the published artifact lives. Internal to the rewrite; not a user-facing page.
- The `UPSTREAM.md` files moved from `graphitron-lsp/src/main/native/` are the source-of-truth pin and update with each runtime/grammar version change.

## Tests

- Natives release workflow asserts jar layout in the package stage (exactly five `lib/<os>-<arch>/` entries, expected file names per platform). Each build-matrix runner asserts its own binary's exported symbols. Each post-deploy-matrix runner asserts the published jar load+parses on its platform.
- `NativeLibraryBundleTest.java` in graphitron-lsp covers each of the five platforms with `@EnabledOnOs`, loads the bundled library through `BundledLibraryLookup`, parses a trivial GraphQL document, asserts root node + no errors. In regular CI (`rewrite-build.yml`, currently `ubuntu-latest`) only the linux-x86_64 method executes per PR; the other four `@EnabledOnOs` methods run on their respective platforms in the natives release workflow's post-deploy matrix. `rewrite-build.yml` does not gain a multi-OS matrix as part of this item — the binaries don't change per PR (they're a published artifact), so per-PR multi-OS coverage would mostly re-verify a stable input.
- Windows verification has no pre-existing test fixture; the new fifth `NativeLibraryBundleTest` method establishes it, exercised at natives-release time.
- No pipeline-tier or execution-tier coverage changes — the natives jar is a build-time substitution; everything downstream sees identical behaviour.

## Roadmap entries

- **R89** (`lsp-native-build-multiplatform-ci`) is subsumed and deleted as part of this item's `In Review → Done`. Its core concern (verify macOS / Windows actually work) survives as the two additional smoke-test methods on `NativeLibraryBundleTest`; the "stand up a CI matrix on every build" framing is moot once binaries come from a separate release pipeline.

## Acceptance

- `no.sikt:graphitron-tree-sitter-natives:0.26.0-1` exists on Maven Central with binaries for all five platforms; the artifact resolves into a fresh local m2 without errors (no unresolvable-parent failures — the natives pom has no `<parent>` reference).
- `mvn install -f graphitron-rewrite/pom.xml -Plocal-db` from a clean checkout no longer invokes `cc` or any C toolchain. The build completes without `src/main/native/` existing in `graphitron-lsp/`.
- The natives release workflow's post-deploy load+parse matrix passes on all five platforms for the `0.26.0-1` release. Regular CI (`rewrite-build.yml`) on `ubuntu-latest` runs the `linux-x86_64` `@EnabledOnOs` method on every PR; the other four are gated to their respective hosts and run on the release workflow's matrix runners.
- R89 is deleted from `graphitron-rewrite/roadmap/`.

## Non-goals

- GraalVM native image support for graphitron-lsp.
- Runtime auto-download of native libs from Maven Central at first use (the jar dep ships them on the classpath; the standard pattern).
- Supporting tree-sitter grammars other than the LSP's current single GraphQL grammar.
- Consuming the natives jar from any module other than graphitron-lsp.
- Adding `linux-arm` (32-bit) or `powerpc64` despite the upstream CLI shipping for them — no consumer.
