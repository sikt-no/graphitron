---
id: R89
title: "macOS / Windows CI verification for graphitron-lsp native build"
status: Backlog
bucket: Backlog
priority: 16
theme: legacy-migration
depends-on: []
---

# macOS / Windows CI verification for graphitron-lsp native build

R18 Phase 6 swapped the LSP's tree-sitter binding from bonede (multi-platform
native binaries shipped in the jar) to jtreesitter, with our own per-platform
shared library compiled at `mvn install` time from the vendored
tree-sitter-graphql grammar + tree-sitter runtime sources under
`graphitron-rewrite/graphitron-lsp/src/main/native/`. Phase 6 landed the
infrastructure for three platforms but only verified one of them:

- **Linux x86_64**: build script, Maven profile, and `BundledLibraryLookup`
  SPI entry are exercised by every CI run today.
- **macOS x86_64 / aarch64**: build script and Maven profiles are wired (the
  same `build-native.sh` handles `Darwin`; SPI maps `os.arch` ∈
  {`x86_64`, `aarch64`} to the matching `lib/macos-*/`); the
  `NativeBuildSmokeTest` has `@EnabledOnOs` cases for both. None of this is
  CI-verified because `.github/workflows/rewrite-build.yml` still runs
  `ubuntu-latest` only.
- **Windows x86_64**: not started. `build-native.sh` rejects non-Linux,
  non-Darwin hosts; no `build-native.bat` companion exists; the SPI throws
  a pointed `UnsupportedOperationException` on Windows. The MSVC invocation
  for the unity build (`cl.exe /LD ...`) plus the Maven profile activation
  are the missing pieces.

What this item should deliver:

1. A `build-native.bat` that invokes `cl.exe` against the same vendored
   sources `build-native.sh` does, producing
   `lib/windows-x86_64/tree-sitter-graphql.dll`. Match the same flag set
   semantically (`-O2`, no WASM, POSIX feature-test macros where they
   apply on MSVC). Update `BundledLibraryLookup` to map
   `os.name=windows`, `os.arch=amd64` to that resource.
2. A matching `<profile>` in `graphitron-lsp/pom.xml` activating on
   `family=windows`, `arch=amd64`, calling `build-native.bat` from
   `exec-maven-plugin`. The MSVC environment (`vcvars64.bat`) needs to be
   available; document the developer-laptop story.
3. CI matrix in `.github/workflows/rewrite-build.yml`. Three OS jobs at
   minimum: `ubuntu-latest`, one of `macos-13` / `macos-14` (whichever the
   project standardises on), and `windows-latest`. For non-ubuntu hosts,
   either start the local PostgreSQL the runner ships preinstalled (the
   `local-db` profile expects `127.0.0.1:5432`, user `postgres`, password
   `postgres`, database `rewrite_test` seeded from `init.sql`) or scope the
   matrix job to a smaller `mvn -pl graphitron-lsp -am test` slice that
   doesn't need the database.
4. Add a `NativeBuildSmokeTest` method gated on Windows once the binary
   ships. The pattern matches the three platform methods already there.

Out of scope: dropping the `BundledLibraryLookup` indirection entirely (any
move toward consumers loading the library themselves), GraalVM native
image, Linux aarch64 (no current consumer; the bonede artefact bundled
that platform but no one asked for it).

