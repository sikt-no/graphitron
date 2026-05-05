---
id: R18
title: "Java LSP rewrite + introspect retirement + `dev` goal"
status: In Progress
priority: 12
theme: legacy-migration
depends-on: []
---

# Plan: Java LSP rewrite + introspect retirement + `dev` goal

> Replaces the Rust `graphitron-lsp` and the legacy
> `graphitron-maven-plugin:introspect` JSON producer with a Java LSP
> module under `graphitron-rewrite/graphitron-lsp`, served by the
> single `mvn graphitron:dev` goal. tree-sitter (`tree-sitter-graphql`)
> for parsing; the rewrite generator's `buildCatalog()` produces the
> catalog the LSP queries.
>
> **Phases 0-4 have shipped.** The LSP runs end-to-end against real
> jOOQ catalogs with completion, diagnostics, hover, and
> goto-definition for `@table`, `@field`, and `@reference`.
> **Phases 0-6 are on trunk.** Phase 5 (this commit) brings
> `@service` / `@condition` / `@record` completion, hover, and
> diagnostics off a JDK 25 `java.lang.classfile`-driven scan of
> `target/classes`. Phase 6 swapped the bonede tree-sitter binding for
> jtreesitter and vendored the grammar. **Remaining work: Phase 7.**
> Phase 7 retires the Rust LSP and the legacy `IntrospectMojo`.
>
> Javadoc surfacing (table / column / scalar / method descriptions
> shown in hover) and per-line `Column.definition` /
> `Method.definition` refinement were originally folded into Phase 5
> on top of a JavaParser dependency. We deferred both to a follow-up
> roadmap item: bytecode-only enumeration covers the high-value
> autocomplete path the user-facing motivation centres on, and the
> JavaParser dep is uncomfortably large for a single LSP improvement.
> The follow-up item lands once the trade-off is worth re-opening.

## Status

Done, on trunk:

- **Phase 0** (commit `21c5e57`): module wired, lsp4j scaffold,
  bonede tree-sitter binding, 5 spike tests.
- **Phase 1** (commits `c699979` → `f228556`): `dev` goal binding
  `127.0.0.1:8487`, schema and classpath watchers, UTF-8 ↔ UTF-16
  position conversion, retirement of the standalone `lsp` and `watch`
  mojos, `getting-started.md` "Dev loop" section.
- **Phase 2** (commit `2a86e5e`): `GraphQLRewriteGenerator.buildCatalog()`
  via `CatalogBuilder` returning tables / columns / FKs / scalars;
  `DevMojo` swaps the catalog atomically on `.class` change.
- **Phase 3** (commits `a76383b` → `2a7b3ba`): per-directive
  completion (`@field`, `@reference`), catalog-aware diagnostics with
  publish wiring, Markdown hover.
- **Phase 4** (commit `035ef2b`): goto-definition into the
  jOOQ-generated source tree (file-level URIs; per-line refinement
  was originally folded into Phase 5 on top of JavaParser; deferred
  with the JavaParser follow-up).
- **Phase 5** (commits `ed5ebf3` → `39ca34f` → this commit):
  `@service` / `@condition` / `@record` autocomplete, hover, and
  diagnostics. The classpath scanner walks `target/classes` via
  `java.lang.classfile` and surfaces public top-level classes plus
  their public methods with erased parameter types; the LSP offers
  class-FQN completion in `class:` slots, method-name completion in
  `method:` slots, and per-reference diagnostics for unknown class /
  unknown method / `-parameters`-missing. A3 closes here.
- **Phase 6** (commits `232f8e0` → `0bbd6f3` → `9f41cdc` → `5c9109d`):
  jtreesitter migration + grammar vendoring. macOS x86_64 / aarch64
  wired; Windows + CI matrix follow-up tracked under R89.

Test suite: 90+ LSP + 48 graphitron-maven module tests, all green
against the fixture jOOQ catalog.

The remainder of this plan describes Phase 7 plus the Phase 5 design
record. Sections below carry forward only the design pieces those
phases extend; already-shipped rationale lives in the commit
messages.

## References

- **Rust LSP being replaced**:
  [gitlab.sikt.no/fs/graphitron-lsp](https://gitlab.sikt.no/fs/graphitron-lsp).
  Phase 7 archives this repo; until then it stays the production
  LSP for consumers who have not migrated.
- **Legacy `IntrospectMojo`** (Phase 7 deletion target):
  `graphitron-maven-plugin/src/main/java/no/sikt/graphitron/mojo/IntrospectMojo.java`
  (327 LOC).
- **Shipped LSP module**: `graphitron-rewrite/graphitron-lsp/`.
  `CatalogBuilder` and the `CompletionData` data shape live in the
  rewrite module under
  `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/catalog/`.
- **Service / record class enumeration source** (Phase 5, shipped):
  a `java.lang.classfile` walk over `<basedir>/target/classes`. See
  `ClasspathScanner` in the rewrite catalog package.

## Goal

Replace the Rust `graphitron-lsp` with a Java LSP that reaches
feature parity (Phases 0-4, done) and then extends the contract to
surface `@service` / `@condition` method help, `@record` class
help, and Javadoc on tables / columns / scalars / methods (Phase 5).
Catalog data arrives in-process from the rewrite generator's
`buildCatalog()`; no JSON producer, no separate LSP binary. The
introspect Maven goal in the legacy plugin closes by deletion in
Phase 7.

The user-facing surface is the single `mvn graphitron:dev` goal,
already shipped: LSP on `127.0.0.1:8487` plus the schema-input
watch loop, all one JVM.

## Motivation

Sikt is a Java shop. The Rust LSP is a historical accident; every
new feature needs maintainers from a different ecosystem than the
generator. Folding the LSP into rewrite means one team, one
language, and direct reuse of the rewrite catalog. The contract
extensions Phase 5 brings (`@service` / `@condition` /
`@record` autocomplete + Javadoc) are awkward across a JSON
boundary; they become tractable once the catalog is in-process,
which is the position Phases 0-4 already put us in.

## User documentation

The "Dev loop" section in
[`graphitron-rewrite/docs/getting-started.md`](../docs/getting-started.adoc)
is the user-facing surface. It documents the three-step recipe
(run, connect, edit), the `graphitron.dev.port` override, and the
catalog-refresh-via-`mvn compile` flow. Phases 5-7 layer on the same
copy: any new feature whose user story does not read cleanly there
needs the docs revised before the phase lands.

Four audiences read that page, in priority order: IntelliJ, VS
Code, and terminal-editor (Neovim / Emacs / etc.) developers, plus
programmatic clients (Claude Code, future automation). The
loopback-socket transport serves all four through the same lsp4j
endpoint.

## Scope boundaries

**In scope (shipped)**

- `@service` / `@condition` / `@record` completion, diagnostics,
  and hover (Phase 5).
- Service / record / condition class enumeration: a
  `java.lang.classfile` walk over `<basedir>/target/classes`
  (Phase 5; replaced the original JavaParser-driven design).
- `-parameters`-missing per-reference Warning + hover hint
  (Phase 5c, closing A3).
- jtreesitter binding swap and grammar vendoring (Phase 6).

**In scope (remaining)**

- Rust-LSP and `graphitron-maven-plugin:introspect` retirement
  (Phase 7).

**Out of scope**

- Javadoc surfacing on table / column / scalar / method hovers,
  per-line `Column.definition` / `Method.definition` refinement,
  and `@externalField` source-walk completion. All deferred to a
  JavaParser-driven follow-up roadmap item.
- LSP server architecture revisits. Single-process lsp4j over a
  loopback socket is the target.
- Editor-side configuration tooling. Setup recipes ship as docs.
- Cross-language schema authoring features that the Rust LSP does
  not have today. New capabilities ship as separate plans.
- GraalVM Native Image distribution. Tracked as a follow-up if
  cold-start UX feedback warrants it.
- Performance work beyond preserving the Rust LSP's per-keystroke
  responsiveness.

## Design (record of shipped seams)

The shipped architecture is documented in the source. This section
records the seams Phases 5 and 6 plugged into; for the current code
map walk `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/`
(per-directive providers under `completions/`, `diagnostics/`,
`hover/`, `definition/`; parsing helpers under `parsing/`; per-file
state under `state/`; lsp4j wiring under `server/`).

### Per-directive dispatch

`GraphitronTextDocumentService` switches on directive name and
delegates to `TableCompletions` / `FieldCompletions` /
`ReferenceCompletions` / `ClassNameCompletions` /
`MethodCompletions` / `Hovers` / `Diagnostics` / `Definitions`.
Phase 5 added `@service`, `@condition`, and `@record` cases by
writing the matching providers and registering them in the same
switches; no new parsing primitives were needed beyond the existing
`Directives`, `NestedArgs`, and `TypeContext` helpers.

`@externalField` completion (deferred to a separate follow-up): the
LSP would index every `public static Field<X> name(<Table> table)`
method on the consumer's source roots and offer them as
`reference: { className, method }` completions. `Parameter.source =
ParamSource.Table` already models the parameter shape. The source
walk needs JavaParser (the same dependency the Phase 5 follow-up
delivers); deferred along with Javadoc.

### Catalog data shape

`CompletionData` (in the rewrite module's `catalog/` package)
carries the `ExternalReference` / `Method` / `Parameter` records
Phase 5 populates. `Parameter.source` (the `MethodRef.ParamSource`
taxonomy: `Arg`, `Context`, `Sources`, `DslContext`, `Table`,
`SourceTable`) stays empty in Phase 5 — the classifier-driven
population is generator-side work and the LSP does not need it for
the autocomplete / hover surface. `Parameter.name == null` carries
the `-parameters`-missing detection signal.

`SourceLocation` already carries `(uri, line, column)` and Phase 4
populated it with file-level URIs for tables / columns / FK
references. Phase 5 leaves the file-level URIs in place; per-line
refinement of `Column.definition` and `Method.definition` moves to
the JavaParser follow-up alongside Javadoc.

### Workspace recalculation queue

`Workspace.toRecalculate` is filled on every mutation and on
`setCatalog`; `GraphitronTextDocumentService` drains it after each
text-document notification and publishes diagnostics for the
touched files. Catalog-only swaps (the `.class` watcher firing
without a buffer change) currently rely on the next edit to
trigger a publish. The Phase 5c `-parameters`-missing diagnostic
follows the same per-edit drain path; an eager publisher hook for
catalog-only swaps stays a follow-up if it ever becomes painful.

### Diagnostics cancellation

Diagnostics run synchronously in the notification handler.
`Diagnostics.compute` is a pure function so cancellation costs
nothing if a later request supersedes it. Phase 5 stayed
synchronous; lsp4j's `CompletableFuture` + `CancelChecker`
integrates directly with virtual threads if a future heavier
provider needs preemption. The Rust LSP's `unsafe` +
`PhantomPinned` dance to thread definitions through a held
workspace lock has no Java counterpart and never will: the GC
keeps references alive across the lock release.

### Cross-platform native sourcing

The bonede tree-sitter binding ships natives for Linux x86_64 /
macOS x86_64 / macOS arm64 / Windows x86_64. Phase 6's jtreesitter
migration takes over native sourcing for the same four platforms
(see A4); the platform matrix does not change.

## Phasing

Each phase is a coherent landing unit with its own commit set, tests,
and exit criteria. Consumers see the LSP improve incrementally; the
Rust LSP keeps running until Phase 7.

Phases 0-6 are on trunk (see Status above for commit anchors). The
remaining work:

**Phase 5: `@service` / `@condition` / `@record` autocomplete (shipped).**

Phase 5 delivered the contract extension that motivated this work:
schema authors get class-name and method-name completion (plus hover
and per-reference diagnostics) on the three directives that take a
Java FQN. Class enumeration runs off a JDK 25
`java.lang.classfile`-driven walk of `<basedir>/target/classes`, no
new dependency. Method, return-type, and erased parameter-type
information come off the same .class read in one pass; parameter
names come off the `MethodParameters` attribute when the consumer
compiled with `-parameters`, and absence of that attribute surfaces
as a per-reference Warning (closing A3).

The original Phase 5 design folded JavaParser-driven Javadoc
surfacing in alongside the autocomplete work (Javadoc on table /
column / scalar / method hovers; per-line `Column.definition` /
`Method.definition` refinement of Phase 4's file-level URIs). That
was deferred to a follow-up phase: bytecode enumeration covers the
high-value autocomplete surface without taking on
`com.github.javaparser:javaparser-symbol-solver-core`. The follow-up
delivers Javadoc-on-hover and per-line definitions when the trade-off
is worth re-opening.

Class enumeration alternatives considered and rejected:

- **JavaParser walk over source roots.** The original plan; rejected
  per the trade-off above.
- **Classpath scan for an `@RewriteService` annotation marker
  consumers attach to service classes.** Adds an authoring step;
  surface stays empty until consumers migrate every service class.
- **The POM's `namedReferences` map.** Covers only the legacy
  `@service(name:)` form, and modern `@service(class:)` and
  `@record(record: {className:})` schemas do not populate the map
  at all; the autocomplete surface would be mostly empty.

The data shape on `CompletionData` (`ExternalReference` / `Method` /
`Parameter`) is unchanged; `Parameter.source` (the
`MethodRef.ParamSource` taxonomy slot) stays empty in Phase 5 — the
classifier-driven population is generator-side work and the LSP does
not need it for the autocomplete / hover surface that ships here.

Phase 5 exit criteria, all met: schema author gets class-FQN
completion on `@service(class:)` / `@condition(class:)` /
`@record(record: {className:})`; method-name completion on
`@service(method:)` / `@condition(method:)`; hover shows the method
signature and a `-parameters`-missing hint when applicable;
diagnostics flag unknown class / unknown method / parameters-missing
per reference. A3 closed.

The Phase-4 file-level URIs (the `Column.definition` /
`Method.definition` slots) stay file-level in Phase 5; per-line
refinement moves to the JavaParser follow-up alongside Javadoc.

**Phase 6: jtreesitter migration + grammar vendoring.** The Java
25 floor is in place per
[`bump-to-java-25.md`](bump-to-java-25.md); this phase consumes
it. Swap the `io.github.bonede:tree-sitter` runtime for
`io.github.tree-sitter:jtreesitter` (latest stable at the time
the swap lands; pin then). jtreesitter does not publish
per-language grammar artefacts; `Language.load` expects the
consumer to provision the native library via
`NativeLibraryLookup`, so this phase also vendors the
`tree-sitter-graphql` grammar source under
`graphitron-lsp/src/main/resources/grammars/` and produces a
per-platform native library at build time, shipped as a
classpath resource. Phase 1 deliberately did not vendor (the
bonede artefact handles native distribution today), so the
binding swap and the grammar work land together rather than
straddling two phases. Exit criteria: official FFM-based binding
active; grammar source vendored and built per platform; tests
pass on the four CI platforms (Linux x86_64, macOS x86_64,
macOS arm64, Windows x86_64).

**Phase 7: polish + retire.** Release the Java LSP through
graphitron-rewrite's normal release cycle. Archive
`graphitron-lsp-rust` (the existing repo). Delete
`graphitron-maven-plugin/src/main/java/no/sikt/graphitron/mojo/IntrospectMojo.java`
and the `LspConfig` records; the umbrella roadmap entry "Retire
`graphitron-maven-plugin` + `graphitron-schema-transform`" closes
this sub-item by deletion. Exit criteria: legacy plugin no longer
ships an `introspect` goal; consumer-side migration is documented.

## Tests

Test tiers in place from earlier phases:

- **Unit tier**: parsing / completion / diagnostic / hover /
  definition logic against in-memory schemas and `CompletionData`
  fixtures.
- **Integration tier**: real lsp4j `Launcher` pair over piped
  streams, sending synthetic LSP messages.
- **Catalog tier**: `CatalogBuilder` against the fixture jOOQ
  catalog under `graphitron-fixtures`.

Per-phase additions still ahead:

- **Phase 5** (shipped): `ClasspathScanner` filter and method
  extraction (`ClasspathScannerTest`); per-directive
  completion / hover / diagnostic coverage on `@service` /
  `@condition` / `@record` (`ClassNameCompletionsTest`,
  `MethodCompletionsTest`, plus extensions in `HoversTest` /
  `DiagnosticsTest`); the `-parameters`-missing path (parameter
  names null on the catalog record, plus the per-reference
  Warning the LSP raises). Javadoc-extraction and per-line
  refinement assertions on `Column.definition` /
  `Method.definition` move to the JavaParser follow-up.
- **Phase 6**: existing test suite must pass against the
  jtreesitter binding without test-side changes (validates the
  `GraphqlLanguage` swap is the only point that changed). Native
  artefact build verified on the four CI platforms.
- **Phase 7**: no new tests; the rollout is a deletion plus a
  release.

If any earlier-phase test breaks during Phase 5-7 implementation,
the breakage is the implementer's bug, not a test-fixture
rewrite.

## Rollout

The Rust LSP keeps running until Phase 7. Phases 0-6 are on trunk;
the autocomplete / hover / diagnostic surface for `@service` /
`@condition` / `@record` is live with Phase 5. Once consumers are
ready to switch:

1. Consumer runs `mvn graphitron:dev` in a terminal in their schema
   module. The LSP starts on `localhost:8487`; the watch loops
   start.
2. Consumer points their editor's LSP client at `localhost:8487`
   (one-time editor configuration; the recipes shipped alongside
   Phase 1's docs).
3. Consumer disables the Rust LSP integration in the same editor
   config: the two LSPs serve overlapping requests, and running
   both at once produces duplicated completions.
4. Consumer drops the `<execution>` for
   `graphitron-maven-plugin:introspect` from their POM.
5. Consumer drops the `graphitron-maven-plugin` dependency once
   nothing else uses it (separately tracked under the umbrella
   "Retire `graphitron-maven-plugin`").

Phase 7 then deletes the legacy `IntrospectMojo` from
`graphitron-maven-plugin` and archives the Rust LSP repo.

No breaking changes to the LSP protocol surface during the rollout:
completions / hover / diagnostics behave the same on the wire. The
editor-config change (stdio process spawn → TCP connect) is the
only consumer-visible difference and was settled by the
`dev`-goal design.

## Roadmap integration

This plan owns the umbrella sub-item "Java LSP rewrite + introspect
retirement + `dev` goal" under
[retire-maven-plugin.md](retire-maven-plugin.md). The umbrella's
body points back here for the phase status; once Phase 7 lands, the
umbrella entry collapses to a Done line citing the last commit and
the test location, and this plan is deleted per the delete-on-done
rule.

The standalone watch-goal sub-item closed implicitly: the
`graphitron-rewrite:watch` Mojo was deleted in Phase 1 and
absorbed by `dev`; the corresponding plan-watch-goal.md was
already removed from trunk on its own landing.

## Open questions

All A* questions are closed; see below.

### Closed

- **A1: Catalog classpath acquisition.** Resolved by the `dev`
  goal running in full Maven context
  (`requiresDependencyResolution = COMPILE`); the consumer's
  compiled jOOQ classes are on the same classloader
  `buildCatalog()` reads through.
- **A2: Port-file location and lifecycle.** Resolved by the
  fixed-default-port design. No port file; the dev goal binds
  `127.0.0.1:8487` and fails fast on conflict pointing at
  `-Dgraphitron.dev.port=N`. Crash handling collapses to the JVM
  shutdown hook.
- **A4: Grammar-binary sourcing for the jtreesitter migration.**
  Resolved by deferring vendoring to Phase 6. Research after the
  slice-2 landing turned up two facts that invalidated the
  original "vendor in Phase 1, swap binding in Phase 6" plan:
  (a) `io.github.tree-sitter` publishes only the `jtreesitter`
  binding on Maven Central with no per-grammar artefact, and
  `Language.load` expects the consumer to provision the native
  library; (b) the bonede `tree-sitter-graphql` coordinate
  (`master-a`) is published-and-immutable on Maven Central even
  though the label looks snapshot-y, and the bonede project is
  actively maintained. Conclusion: vendoring is needed exactly
  when jtreesitter goes live. Phase 6 vendors under
  `graphitron-lsp/src/main/resources/grammars/` and builds per
  platform.
- **A3: `-parameters` propagation for service hover.** Closed in
  Phase 5c. The rewrite generator's build-time warning
  (`ServiceCatalog.emitParametersWarning`) gets an LSP-side
  counterpart: when the classfile scanner reads a method whose
  `MethodParameters` attribute is absent, the resulting
  `Parameter` records carry `name == null`; `Diagnostics`
  emits a per-reference `Warning` on each `@service` /
  `@condition` directive that references such a method, and
  `Hovers` appends a "_Parameter names are unavailable;
  recompile with the `-parameters` flag&hellip;_" hint to the
  method signature.

## Appendix: spike learnings

The plan is grounded in two rounds of spike work that landed as
Phase 0. Findings worth carrying forward:

- **Tree-sitter queries port verbatim from Rust to Java.** The
  Rust LSP's `DIRECTIVES_QUERY` (`src/parsing/directives.rs`)
  works against `tree-sitter-graphql` from Java with zero textual
  changes; only the host-language wrapper (`TSQueryCursor`,
  `TSQueryMatch`, capture-name lookup) gets rewritten. This is
  the strongest signal that the Phase 3 directive ports are
  mechanical, not creative.
- **Incremental parse works through the bonede binding.**
  `tree.edit(TSInputEdit)` + `parser.parseString(oldTree, source)`
  is the same mechanism Rust uses (Rust counterpart in
  `src/state/file.rs::did_change`); `IncrementalParseTest` proves
  it produces error-free trees with the expected structural
  changes. Per-keystroke responsiveness is preserved.
- **lsp4j on JDK 21 is straightforward.** Capabilities
  advertisement, service registration, async / cancellation:
  no surprises. The lsp4j message loop simplifies the Rust LSP's
  hand-rolled `main_loop` (`src/main.rs`) significantly.
- **The Phase 0 Maven launcher was one short class** wrapping
  `Launcher.main` with stdio. Phase 1 swaps to a socket-based
  `DevMojo` extending `AbstractRewriteMojo` so the goal can share
  configuration with `generate` and run the watch loop alongside
  the LSP server in one JVM.
- **GC + virtual threads erase two pieces of Rust-side
  complexity.** The Rust LSP uses `unsafe { mem::transmute<&File, &File> }`
  + `PhantomPinned` to hold a workspace lock while iterating
  diagnostics (`src/state/diagnostic_handler.rs:55,111`), and a
  cooperative `HandlerState::Yielding` state machine for
  preemption (same file). Java has neither problem: the GC
  manages the reference, virtual threads make blocking-style
  code cheap.
- **Native-library bundling worked first try on Linux x86_64.**
  The bonede binding ships the runtime and the
  `tree-sitter-graphql` grammar's `.so` together; loading
  worked without manual setup. Phase 6's jtreesitter migration
  has to replicate this for the new binding (and now also for
  the vendored grammar; see A4).

## Appendix: alternatives rejected

Three other shapes were considered before D-tree-sitter was
locked. Brief tombstones for the record; git history (the
ancestors of this plan, slug `plan-introspect-goal.md` before
rename) carries the full discussion.

- **Port `introspect` to `graphitron-maven` (Java
  producer + existing Rust LSP).** Keeps the JSON contract and
  the producer-consumer split; doesn't fix on-demand refresh and
  doesn't enable in-process catalog reuse. Worse than D for a
  Java shop.
- **Reimplement catalog reading inside the Rust LSP.** Doubles
  down on Rust as the LSP's permanent home; conflicts with the
  Java-shop direction.
- **Hybrid (JVM helper invoked by the Rust LSP).** Two-language
  toolchain stays; doesn't collapse anything; didn't justify the
  added moving parts.

The Java-25-floor authorisation made these three uniformly worse
than D: D-tree-sitter gets all the in-process benefits without
the rewrite-the-LSP-in-Rust contortions.
