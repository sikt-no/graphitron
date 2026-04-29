---
id: R18
title: "Java LSP rewrite + introspect retirement + `dev` goal"
status: Ready
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
> **Remaining work: Phases 5-7.** Phase 5 brings `@service` /
> `@condition` + Javadoc (with the service-class enumeration question
> this surfaces); Phase 6 swaps the bonede tree-sitter binding for
> jtreesitter and vendors the grammar; Phase 7 retires the Rust LSP
> and the legacy `IntrospectMojo`.

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
  arrives with Phase 5's JavaParser adoption).

Test suite: 72 LSP + 37 graphitron-maven module tests, all green
against the fixture jOOQ catalog.

The remainder of this plan describes Phases 5-7. Sections below carry
forward only the design pieces those phases extend; the
already-shipped rationale lives in the commit messages.

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
- **Service-method enumeration source** (Phase 5): a JavaParser
  walk over the consumer's source roots; see the Phase 5
  description below for the rationale.

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
[`graphitron-rewrite/docs/getting-started.md`](../docs/getting-started.md)
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

## Scope boundaries (Phases 5-7)

**In scope**

- `@service` / `@condition` completion, diagnostics, and hover
  (Phase 5).
- Javadoc surfacing on table / column / scalar / method elements
  (Phase 5).
- Service-class enumeration source: a JavaParser walk over the
  consumer's source roots (Phase 5).
- jtreesitter binding swap and grammar vendoring (Phase 6; the
  Java 25 floor it depends on is now in place per
  [`bump-to-java-25.md`](bump-to-java-25.md)).
- Rust-LSP and `graphitron-maven-plugin:introspect` retirement
  (Phase 7).

**Out of scope**

- LSP server architecture revisits. Single-process lsp4j over a
  loopback socket is the target.
- Editor-side configuration tooling. Setup recipes ship as docs.
- Cross-language schema authoring features that the Rust LSP does
  not have today. New capabilities ship as separate plans once
  parity plus Phase 5 lands.
- GraalVM Native Image distribution. Tracked as a follow-up if
  cold-start UX feedback warrants it.
- Performance work beyond preserving the Rust LSP's per-keystroke
  responsiveness.

## Design (extension points for Phases 5-7)

The shipped architecture is documented in the source. This section
describes the seams Phases 5-7 plug into; for the current code map
walk `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/`
(per-directive providers under `completions/`, `diagnostics/`,
`hover/`, `definition/`; parsing helpers under `parsing/`; per-file
state under `state/`; lsp4j wiring under `server/`).

### Per-directive dispatch (Phase 5 extension)

`GraphitronTextDocumentService` switches on directive name and
delegates to `TableCompletions` / `FieldCompletions` /
`ReferenceCompletions` / `Hovers` / `Diagnostics` / `Definitions`.
Phase 5 adds `@service`, `@condition`, and `@externalField` cases
by writing the matching providers and registering them in the
same switches; no new parsing primitives are needed beyond the
existing `Directives`, `NestedArgs`, and `TypeContext` helpers.

`@externalField` completion (deliverable, R48 follow-up): the LSP
indexes every `public static Field<X> name(<Table> table)` method
on the consumer's source roots and offers them as
`reference: { className, method }` completions. `Parameter.source =
ParamSource.Table` already models the parameter shape; the
source-walk filter is the only new code. The classifier rejects
unmatched references at codegen with `AUTHOR_ERROR`, so authoring
without LSP support still works, but the completion path makes
discovery a one-keystroke operation matching the `@service` and
`@condition` flows.

### Catalog data shape

`CompletionData` (in the rewrite module's `catalog/` package)
already carries the `ExternalReference` / `Method` / `Parameter`
records Phase 5 will populate, along with the `MethodRef.ParamSource`
taxonomy on `Parameter.source` (`Arg`, `Context`, `Sources`,
`DslContext`, `Table`, `SourceTable`). The data shape is settled;
Phase 5 only needs an enumeration source plus a Javadoc reader,
both described in Phase 5 below.

`SourceLocation` already carries `(uri, line, column)` and Phase 4
populated it with file-level URIs for tables / columns / FK
references. Phase 5's JavaParser adoption refines `Column.definition`
and the Phase-5-populated `Method.definition` to per-line accuracy
as a side effect; the LSP `Definitions` provider needs no further
changes.

### Workspace recalculation queue

`Workspace.toRecalculate` is filled on every mutation and on
`setCatalog`; `GraphitronTextDocumentService` drains it after each
text-document notification and publishes diagnostics for the
touched files. Catalog-only swaps (the `.class` watcher firing
without a buffer change) currently rely on the next edit to
trigger a publish. If Phase 5 adds latency-sensitive
catalog-driven diagnostics (the `-parameters` warning is the
canonical case; see A3), an eager publisher hook can layer on by
routing `Workspace.markAllForRecalculation` through a listener;
the queue infrastructure is already in place.

### Diagnostics cancellation

Diagnostics today run synchronously in the notification handler.
`Diagnostics.compute` is a pure function so cancellation costs
nothing if a later request supersedes it. Phase 5's per-method
Javadoc lookups will be heavier; lsp4j's `CompletableFuture` +
`CancelChecker` integrates directly with virtual threads when we
need preemption. The Rust LSP's `unsafe`+`PhantomPinned` dance
to thread definitions through a held workspace lock has no Java
counterpart and never will: the GC keeps references alive across
the lock release.

### Cross-platform native sourcing

The bonede tree-sitter binding ships natives for Linux x86_64 /
macOS x86_64 / macOS arm64 / Windows x86_64. Phase 6's jtreesitter
migration takes over native sourcing for the same four platforms
(see A4); the platform matrix does not change.

## Phasing

Each phase is a coherent landing unit with its own commit set, tests,
and exit criteria. Consumers see the LSP improve incrementally; the
Rust LSP keeps running until Phase 7.

Phases 0-4 are on trunk (see Status above for commit anchors). The
remaining phases:

**Phase 5: `@service` / `@condition` / `@record` + Javadoc.** The
contract extensions that motivated this work, plus the
`@record(record: {className:})` autocomplete that we deferred from
Phase 3 along with the same enumeration question.

Two new capabilities:

1. **Service / record class enumeration.** `ServiceCatalog` is a
   stateless reflector that only knows what the schema explicitly
   references, so it cannot answer "what service classes /
   methods exist?" for autocomplete. Source: a JavaParser walk
   over the consumer's source roots. Two reinforcing reasons:

   - Phase 5 already adopts JavaParser for the Javadoc work
     below, so the marginal cost is mostly walking additional
     files.
   - Source roots are a naturally bounded set with no new config
     knob. The previous Maven plugin's `externalReferenceImports`
     element (a package search path for unqualified refs) was
     dropped in the rewrite plugin alongside the move to FQN-only
     `@service(class:)`; reintroducing a search-path config for
     the LSP would contradict that direction.

   Two alternatives were considered and ruled out:

   - A classpath scan for an `@RewriteService` annotation marker
     consumers attach to service classes. Adds an authoring step,
     and without an `externalReferenceImports` equivalent the
     scan must either walk the entire classpath or take a new
     config knob; neither is appealing. (The annotation marker
     can still serve as an opt-in filter inside the JavaParser
     walk if the unmarked surface turns out too noisy; that is a
     follow-up call, not a Phase 5 decision.)
   - The POM's `namedReferences` map. Covers only the legacy
     `@service(name:)` form, and we want our advanced tooling to
     push consumers off that form rather than entrench it.
     Modern `@service(class:)` and `@record(record: {className:})`
     schemas do not populate the map at all, so supporting it
     would also leave the autocomplete surface mostly empty for
     current schemas.

   The data shape (`ExternalReference` / `Method` / `Parameter`
   records on `CompletionData`, with `Parameter.source` matching
   the `MethodRef.ParamSource` taxonomy) is already defined and
   unchanged.

2. **Javadoc surfacing.** Method signatures populate
   `Method.parameters` from `MethodRef`; Javadoc for tables /
   columns / scalars / methods is read from aggregator source
   files via JavaParser
   (`com.github.javaparser:javaparser-symbol-solver-core`) and
   attached to the corresponding `description` slot. JavaParser
   is the chosen tool: it works against classpath sources (jars
   with source attachments included), does not depend on
   JDK-internal `com.sun.source` APIs, and the rewrite already
   has no stake in either; new dependency only. Side effect:
   per-line `Column.definition` / `Method.definition` ranges
   replace Phase 4's file-level URIs.

The `-parameters` warning that the rewrite generator emits at
build time (see A3) needs an LSP-side equivalent: a workspace-
level diagnostic / status message when a referenced service class
was compiled without `-parameters` so the parameter-name surface
in completion / hover is non-empty. Wire-up uses the existing
`Workspace.markAllForRecalculation` plumbing.

Exit criteria: schema author gets parameter completion on
`@service(method:)` / `@condition` and class-name completion on
`@record(record: {className:})`; hovers on a column show
`COMMENT ON COLUMN` text and on a service method show its
Javadoc; the `-parameters`-missing warning surfaces in the
editor; the legacy `file:///tables/<NAME>` Phase-4 URIs refine to
per-line ranges. A3 closes here.

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

- **Phase 5**: `@service` / `@condition` / `@record` end-to-end
  tests covering the chosen enumeration source; Javadoc
  extraction tests against fixture jOOQ + service classes; the
  `-parameters`-missing path (parameter names null, plus the
  workspace-level diagnostic the LSP raises); per-line
  refinement assertions on `Column.definition` /
  `Method.definition`.
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

The Rust LSP keeps running until Phase 7. Phases 0-4 are on trunk
but consumers do not migrate yet: feature parity is in place, but
the contract extensions they care about (`@service` / `@condition`
help and Javadoc) land in Phase 5. Once Phase 5 ships:

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

A3 is the only one still open; A1, A2, and A4 closed earlier.

A3. **`-parameters` propagation for service Javadoc.** The
   rewrite generator already emits a one-shot warning when it
   reads a service class compiled without `-parameters`
   (`ServiceCatalog.emitParametersWarning` in
   `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/ServiceCatalog.java`).
   The LSP needs the same behaviour but should surface it as an
   LSP diagnostic / status message rather than a build-time log.
   Closes in Phase 5.

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
