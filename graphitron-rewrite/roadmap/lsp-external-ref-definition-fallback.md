---
id: R349
title: "LSP goto-definition silently fails for @service/@condition class refs when the source walk yields no index"
status: Backlog
bucket: bug
priority: 5
theme: lsp
depends-on: []
created: 2026-06-21
last-updated: 2026-06-21
---

# LSP goto-definition silently fails for @service/@condition class refs when the source walk yields no index

Goto-definition on a `@service` / `@condition` / `@externalField` class or method
reference returns no location in a live `graphitron:dev` session ("No definitions
found"), while jOOQ `@table` jumps and intra-schema type jumps still work. Confirmed
in an Emacs/eglot session: `eglot-xref-backend` is active and the
`textDocument/definition` round-trip succeeds, but the server returns `[]` for class
refs; class-name *completion* in the same position works, so the external references
themselves are built (the `ClasspathScanner` pass over the reactor's `target/classes`
is fine). The gap is that the reference's *source location* stays
`CompletionData.SourceLocation.UNKNOWN`: `CatalogBuilder.enrichExternalReferences`
sets a ref's location and Javadoc together from the source-walk `classDecl`, and when
`SourceWalker.walk(compileSourceRoots)` yields no index for that class the location is
left `UNKNOWN` and goto-def silently no-ops.

## Why it is invisible and total (not partial like tables)

Tables survive the same missing-walk condition because `CatalogBuilder.buildTable`
synthesizes a *file-level* fallback location (`0:0`) from the jOOQ output path on disk
whenever the generated `.java` exists; external references have **no equivalent
fallback**, so a missing walk degrades them from "jump to the class" all the way to
"do nothing". The degradation is also completely silent: `SourceWalker.parse` returns
an empty map when `ToolProvider.getSystemJavaCompiler()` is `null` (line ~209), with no
log line, so a consumer whose `graphitron:dev` JVM is a JRE (or whose `JAVA_HOME`
points at a JRE) loses all service-class navigation with no signal at all. This is the
leading suspected trigger for the field report and explains the "never worked"
framing.

## Candidate triggers (to confirm; see the debugging breakpoints below)

- **No system Java compiler** (JRE, not JDK): `getSystemJavaCompiler()` returns `null`
  → empty index → every external-ref location `UNKNOWN`. Prime suspect.
- **`compileSourceRoots` does not cover the service module's `src/main/java`**: e.g.
  the service classes are scannable as compiled `.class` in a reactor output dir but
  their source root is not surfaced to the dev session.
- **FQN-join mismatch**: `ClasspathScanner` keys refs by the bytecode FQN; `SourceWalker`
  builds the class FQN from package + nested simple names. A mismatch (e.g. nested
  declarations) would leave `sources.classes().get(ref.className())` null.

The single splitting breakpoint is `CatalogBuilder.enrichExternalReferences` (the
`sources.classes().get(ref.className())` lookup): inspect `ref.className()` against
`sources.classes().keySet()` to tell "walk produced nothing" from "key does not match";
`SourceWalker.parse`'s `compiler == null` check confirms/denies the JRE theory.

## Proposed fix (two parts, independent of which trigger applies)

1. **File-level fallback for external references**, mirroring tables: when the walk
   cannot pin a line, resolve the class to its source file (or, failing that, leave a
   deliberate no-op) so a class still jumps to the top of its file instead of nowhere.
2. **Make the source walk's failure non-silent**: when
   `ToolProvider.getSystemJavaCompiler()` is `null` (or no source roots resolve), emit
   a one-time warning through the dev goal so "running on a JRE" / "sources not on the
   build" surfaces instead of degrading invisibly.

Acceptance: a service-class goto-definition fixture at the pipeline tier that asserts a
non-`UNKNOWN` (at least file-level) location when the walk is unavailable, plus a test
that the compiler-null / no-source-roots path surfaces a signal rather than an empty
index. Out of scope for R347 (LSP-internal consolidation); this is the catalog feed.

