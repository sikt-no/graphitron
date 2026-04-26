---
title: "Java LSP rewrite + introspect retirement + `dev` goal"
status: Ready
priority: 12
---

# Plan: Java LSP rewrite + introspect retirement + `dev` goal

> Replaces the Rust `graphitron-lsp` and the legacy
> `graphitron-maven-plugin:introspect` JSON producer with a Java LSP
> module under `graphitron-rewrite/graphitron-lsp`. Single user-facing
> entry point: `mvn graphitron-rewrite:dev`, which binds the LSP to a
> fixed default loopback port and watches `<schemaInputs>` for
> regeneration in the same JVM. The standalone `lsp` and `watch`
> Mojos disappear; nobody consumes them yet, so consolidation is free.
>
> tree-sitter (`tree-sitter-graphql`) for parsing; the rewrite
> generator gains a `buildCatalog()` entry point that returns a
> ready-made `CompletionData` to the LSP. The rewrite module owns
> assembly; the LSP module imports only the data shape. The
> introspect goal closes by deletion.
>
> Phase 0 (foundation module + interim stdio `lsp` Mojo) shipped as
> part of this plan's introduction (commit `21c5e57`). Phase 1
> introduces the `dev` goal, wires `Completion` into the service
> surface, and retires both `lsp` and the existing `watch` Mojo.
> Phases 2-7 below describe the remaining work; grammar vendoring
> moves into Phase 6 alongside the jtreesitter binding swap (see
> A4).

## References

Reviewers can verify the claims in this plan against the cited
sources:

- **Existing Rust LSP**:
  [gitlab.sikt.no/fs/graphitron-lsp](https://gitlab.sikt.no/fs/graphitron-lsp).
  Specific files cited inline below as
  `<path>:<line>` (line numbers as of the plan's drafting; the
  repo evolves, so reviewers should treat them as approximate).
- **Legacy `IntrospectMojo`**:
  `graphitron-maven-plugin/src/main/java/no/sikt/graphitron/mojo/IntrospectMojo.java`
  (327 LOC; deletion target in Phase 7).
- **Rewrite catalog entry point the LSP imports** (Phase 2): a new
  `buildCatalog()` method on
  `no.sikt.graphitron.rewrite.GraphQLRewriteGenerator` that
  assembles a `CompletionData` from `JooqCatalog` (already public,
  for tables / columns / FKs) and the parsed `GraphQLSchema` (for
  scalar definitions and source positions). No facade class, no
  new `ScalarCatalog`, no `ServiceCatalog` exposure: the
  `ExternalReference` slot of `CompletionData` stays empty in
  Phase 2 because `ServiceCatalog` is a stateless reflector that
  only knows what the schema explicitly references; service
  enumeration belongs in Phase 5 alongside the `@service` /
  `@condition` work it powers. The rewrite module produces what
  the LSP wants; the LSP module imports only `CompletionData`.
  The legacy `TableReflection` and `ScalarUtils` classes (under
  `graphitron-codegen-parent/graphitron-java-codegen/`) are not
  imported.
- **Phase 0 spike code**: under
  `graphitron-rewrite/graphitron-lsp/`, with 5 passing tests.
- **Existing watch goal that Phase 1 absorbs**:
  `graphitron-rewrite/graphitron-maven/src/main/java/no/sikt/graphitron/rewrite/maven/{WatchMojo.java,watch/}`.

## Goal

Stand up a Java LSP under `graphitron-rewrite/graphitron-lsp` that
replaces the Rust `graphitron-lsp` at feature parity, then extends
the contract to surface `@service` / `@condition` method help and
Javadoc on tables, columns, scalars, and methods. Catalog data
arrives in-process from the rewrite generator's catalog API; no
JSON producer, no separate LSP binary. The introspect Maven goal
in the legacy plugin closes by deletion.

User-facing surface: a single `mvn graphitron-rewrite:dev` goal
runs both the LSP (bound to a fixed default loopback port) and the
schema-input watch loop in one JVM. The editor's LSP client
connects to `localhost:<defaultPort>`; schema saves on disk
trigger regeneration in the same JVM. The standalone `lsp` and
`watch` goals are not exposed to users; they are implementation
details that get removed during Phase 1.

## Motivation

Sikt is a Java shop. The Rust LSP is a historical accident; every
new feature needs maintainers from a different ecosystem than the
generator. Folding the LSP into rewrite means one team, one
language, and direct reuse of the rewrite catalog: the rewrite
generator gains a `buildCatalog()` method that produces the
`CompletionData` the LSP consumes, sourced from the existing
`JooqCatalog` and the parsed `GraphQLSchema`. The contract
extensions the LSP roadmap wants next (`@service` /
`@condition` help, Javadoc) are awkward across a JSON boundary;
they become tractable once the rewrite catalog is in-process,
and Phase 5 picks them up alongside their own enumeration
question (see Phase 5).

## User documentation (first-client check)

The user-facing documentation is the design's first client. If it
does not read simply, the design is wrong and must change before
implementation. Below is the draft `## Dev loop` section that will
land in `graphitron-rewrite/docs/getting-started.md` when Phase 1
ships. Reviewers test the design against this draft; if a phase
adds something that the docs cannot explain plainly, the phase
needs rework, not the docs.

### Audiences

Four audiences for the dev loop, in priority order. All four are
LSP clients; the dev goal serves them through the same socket.

1. **IntelliJ developers** doing schema editing. Most of Sikt's
   graphitron users. Use IntelliJ's built-in or third-party LSP
   client.
2. **VS Code developers**. Excellent LSP support out of the box
   via the official LSP client API; one of the strongest LSP
   experiences in the ecosystem.
3. **Terminal editor users** (Neovim with `nvim-lsp` / `coc.nvim`,
   Emacs with `lsp-mode` / `eglot`, etc.). Same LSP services
   surfaced through their editor's LSP plumbing. They want
   completions, diagnostics, hover, and goto-definition without
   leaving the terminal.
4. **Agents** (Claude Code, future automation). Programmatic LSP
   clients reading and editing graphitron projects. The
   socket-based transport is agent-friendly: connect, query,
   disconnect; no subprocess management.

Schema-file regeneration is triggered by the dev goal's filesystem
watch loop, not by the LSP itself. Reason: regeneration must only
fire on completed, intentional edits, never on the
keystroke-by-keystroke unsaved buffer the LSP sees. The LSP serves
completions and diagnostics against the unsaved buffer as it
changes; a save (filesystem write) is the user's signal that the
edit is ready, and only that flushes through the generator. This
also catches edits agents make via filesystem APIs without going
through LSP `didChange`. The LSP receives the resulting
source-tree updates the same way it would for any other
consumer-side change.

### Draft user-facing copy

---

```markdown
## Dev loop

Start your dev session from the schema module:

    mvn graphitron-rewrite:dev

This runs the graphitron LSP on `localhost:8487` and watches your
`.graphqls` files. Saving a schema regenerates the affected Java
sources under `target/generated-sources/graphitron`; only changed
files touch disk.

Connect your editor or agent to the LSP by pointing its LSP client
at `localhost:8487` (TCP). Setup recipes for IntelliJ, VS Code,
and Claude Code are in [editor-setup.md].

If `8487` is already in use (another dev session, an unrelated
service), pass `-Dgraphitron.dev.port=N` to pick a different port,
and use the matching port in the editor config.

Stop with Ctrl+C.
```

---

That is the entire user-facing surface. Three steps: run, connect,
edit. The default port is the only number a developer ever sees,
and it stays the same across sessions, machines, and projects, so
each editor needs one one-time configuration line.

Tests for design / docs alignment:

- A new contributor (human or agent) can copy the snippet, run it,
  and have a working dev loop without reading anything else.
- The basic recipe uses no Mojo parameters at all. The
  `graphitron.dev.port` and `graphitron.dev.debounceMs` knobs are
  documented below the basic recipe for advanced users.
- The error path "I forgot to start `mvn graphitron-rewrite:dev`"
  produces an editor / agent message a non-graphitron developer
  can act on (typically "Connection refused on localhost:8487;
  check the dev session is running"). The fixed port makes the
  error message specific enough to act on without consulting any
  per-project file.
- The error path "port already in use" surfaces as a clear Mojo
  startup failure pointing at the override property; no silent
  rebind to a different port that the editor would not know about.

## Scope boundaries

**In scope**

- Java LSP module `graphitron-rewrite/graphitron-lsp`, lsp4j-based.
- `graphitron-maven:dev` Mojo as the single user-facing
  goal: LSP-on-fixed-port + schema-watch + classpath-watch +
  regeneration, all one JVM.
- Per-directive completions, diagnostics, hover, goto-definition for
  `@table`, `@field`, `@reference`, `@record`, `@connect`.
- `@service` / `@condition` directive support (new vs. the Rust
  LSP) including method-signature help.
- Javadoc surfacing on table / column / scalar / method elements.
- Workspace + file lifecycle with incremental parse and
  dependency-tracked diagnostic recalculation.
- Migration to the official FFM-based `tree-sitter/java-tree-sitter`
  binding once Java 25 is the floor.
- Retirement of the Rust LSP and the legacy
  `graphitron-maven-plugin:introspect` goal.
- Retirement of the standalone `graphitron-rewrite:lsp` Mojo
  (Phase 0 stub) and the existing `graphitron-rewrite:watch` Mojo
  (currently in trunk under the watch-goal plan); both subsumed by
  `dev`.

**Out of scope**

- LSP server architecture revisits (e.g. multi-process, plugin
  ecosystems). Single-process lsp4j over a loopback socket is the
  target.
- IntelliJ / VS Code / Neovim / Emacs / Claude Code client-side
  configuration tooling. Editor configuration is the consumer's
  responsibility; setup recipes for IntelliJ, VS Code, and Claude
  Code ship as documentation alongside Phase 1.
- Cross-language schema authoring features that do not exist in the
  current Rust LSP. New capabilities ship as separate plans once
  parity is reached.
- GraalVM Native Image distribution. Tracked as a follow-up if
  cold-start UX feedback warrants it; not on the critical path.
- Performance work beyond preserving what the Rust LSP already does.
  Per-keystroke responsiveness is the bar, not "faster than Rust".

## Design

### Module layout

Phase 0 lands with this shape. Subsequent phases add files; the
package layout stays. Items marked `(P0-interim)` are spike
artefacts replaced in Phase 1.

```
graphitron-rewrite/graphitron-lsp/
  pom.xml                        bonede tree-sitter + lsp4j deps
  src/main/java/no/sikt/graphitron/lsp/
    server/
      GraphitronLanguageServer    lsp4j initialize + capabilities
      GraphitronTextDocumentService
      GraphitronWorkspaceService
      Launcher                    (P0-interim) stdio main()
    state/
      WorkspaceFile               source bytes + tree-sitter tree
      Workspace                   per-aggregator file map (Phase 1)
    parsing/
      GraphqlLanguage             single binding swap point
      Directives                  @directive parsing
      Nodes                       byte-range / point helpers
    completions/
      TableCompletions            @table(name: "...") completions
                                  (per-directive files added in
                                  Phase 3)
    catalog/
      CompletionData              in-memory catalog the LSP queries
  src/test/java/no/sikt/graphitron/lsp/
    TreeSitterSmokeTest
    IncrementalParseTest
    TableCompletionsTest
```

`graphitron-maven` gains `LspMojo` (P0-interim, registered
as `mvn graphitron-rewrite:lsp`) that wraps `Launcher.main`. Phase 1
deletes both `LspMojo` and the existing `WatchMojo`, replacing
them with `DevMojo` (`mvn graphitron-rewrite:dev`) bound to a
fixed loopback port.

`GraphitronTextDocumentService` ships in Phase 0 as empty
notification stubs only (the spike validates the launcher and the
in-process `TableCompletions.generate(...)` call shape). Wiring
the `completion` request handler to dispatch into
`TableCompletions` is Phase 1's responsibility, alongside
workspace lifecycle.

### Catalog API

The LSP holds an immutable `CompletionData` populated from the
rewrite catalog API (no JSON serialisation, no producer). Records
mirror what the Rust LSP's `completion_data` module deserialises
today, so the consumer-side data surface is the same. Already in
tree under `catalog/CompletionData.java` (Phase 0):

- `Table { name, description, definition: SourceLocation, columns,
  references }`
- `Column { name, graphqlType, nullable, description }`
- `Reference { targetTable, keyName, inverse }` where `keyName`
  uses the legacy `<TABLE>__<FK>` format the Rust LSP already
  matches against.
- `TypeData { name, aliases, description, definition }` for scalars.
- `ExternalReference { name, className, description, methods }`.
- `Method { name, returnType, description, parameters }` and
  `Parameter { name, type, source, description }` where `source`
  matches the rewrite-side `MethodRef.ParamSource` taxonomy
  (`Arg`, `Context`, `Sources`, `DslContext`, `Table`,
  `SourceTable`).
- `SourceLocation { uri, line, column }` with an `UNKNOWN`
  sentinel for entries without a real position.

Phase 2 populates these through a new `buildCatalog()` method on
`GraphQLRewriteGenerator` that returns a fully-assembled
`CompletionData`. The rewrite module owns assembly; the LSP module
imports only the data shape. Source map:

- `Table` / `Column` / `Reference`: read from the already-public
  `JooqCatalog` (table lookups, column metadata, foreign-key
  edges).
- `TypeData` (scalars): walked from the parsed `GraphQLSchema`
  itself (`assembled.getAllTypesAsList()` filtered to
  `GraphQLScalarType`), pulling `getName()` / `getDescription()`
  / `getDefinition().getSourceLocation()`. No new
  `ScalarCatalog` class; the parsed schema already carries
  everything the LSP wants for hover and completion.
- `ExternalReference` / `Method` / `Parameter`: empty in Phase 2.
  `ServiceCatalog` is a stateless on-demand reflector populated
  per-field by the classifier; nothing in the rewrite enumerates
  the universe of available service classes today, and nothing
  needs to until `@service` / `@condition` autocomplete lands.
  Phase 5 picks this slot back up together with the enumeration
  source it requires.

Until Phase 2, `CompletionData.empty()` serves and tests pass
explicit fixtures.

### Workspace and file lifecycle

`Workspace` (Phase 1) holds a `Map<Path, WorkspaceFile>` plus the
shared `CompletionData` for the aggregator that opened it.
`WorkspaceFile` (already in tree, Phase 0) holds source bytes and
a tree-sitter tree, kept in lockstep through `applyEdit`. Compare
against the Rust LSP's `src/state/{workspace.rs,file.rs}`.

The Rust LSP's recalculation queue ports directly:

- `Workspace.toRecalculate: List<Path>` is filled on every
  `did_change`; diagnostic runs drain it.
  (Rust counterpart: `Workspace::to_recalculate` in
  `src/state/workspace.rs`.)
- `WorkspaceFile.dependsOnDeclarations: Set<String>` records which
  type names this file references but does not declare; when one
  of those types is touched in another file, the queue picks up
  the dependents too.
  (Rust counterpart: `File::depends_on_declaratations` (sic, typo
  preserved) in `src/state/file.rs`.)

Aggregator-root discovery happens at `dev`-goal startup, not on
every `did_open`: the Mojo runs in Maven context and already knows
the project root. The LSP receives the resolved root and the
catalog from the Mojo. This differs from the Rust LSP, which walks
parents from each opened file looking for `target/graphitron-lsp-config.json`
(`src/state/workspace.rs`).

### Catalog freshness

The catalog reads from the consumer's compiled jOOQ classes, so
catalog content can drift from reality when the consumer
recompiles their database schema mid-session (`mvn compile` after
a jOOQ regeneration). Two cheap mechanisms keep it in sync without
a session restart:

- The dev Mojo registers a second watcher (a `SchemaWatcher`
  reused with a different filename predicate) on the consumer's
  `target/classes/<jooqPackage>/` directory. A debounced
  `.class`-file event triggers a catalog rebuild: drop the old
  `CompletionData`, rerun the Phase 2 builder, swap atomically.
  Rebuild is cheap (one reflection pass over the jOOQ tables
  class).
- The reload runs on the dev-goal's executor, not on the LSP
  message loop, so in-flight LSP requests are never blocked by a
  catalog swap. The next request observes the new catalog through
  a `volatile` reference.

This closes the watch-mode caveat in `getting-started.md` (line
249-252) for the dev goal: the consumer can run `mvn compile` in
a side terminal and the LSP picks up new tables and columns
within the debounce window.

### Per-directive dispatch

Mirrors the Rust LSP's `src/completions/mod.rs` and
`src/diagnostics/mod.rs` shape: a `switch` on directive name to a
per-directive handler function. New directives are mechanical to
add. Phase 0 ships `@table` only; Phase 3 adds `@field`,
`@reference`, `@record`, `@connect`; Phase 5 adds `@service`,
`@condition`.

Tree-sitter queries port verbatim from the Rust source. The
`DIRECTIVES_QUERY` already in `parsing/Directives.java` is the
proof-of-concept: same string, same captures, same dispatch
logic, just rewrapped in lsp4j response types. Compare against
`src/parsing/directives.rs` in the Rust LSP.

### Diagnostics scheduling

The Rust LSP yields one definition at a time via `HandlerState`
(see `src/state/diagnostic_handler.rs`), preempting on each new
request. Java 21 virtual threads make this shape trivial: spawn
one virtual thread per recalculation task, have it call a
`cancellationToken.checkCancelled()` between definitions, and let
it block on the workspace lock. lsp4j's `CompletableFuture` +
`CancelChecker` integrates directly.

This avoids the Rust LSP's `unsafe { mem::transmute<&File, &File> }`
plus `PhantomPinned` dance (`src/state/diagnostic_handler.rs:55,111`),
which exists only because Rust's borrow checker rejects "hold a
workspace lock and iterate definitions one at a time". Java has no
equivalent constraint; the GC keeps the references alive.

### `dev` goal

`mvn graphitron-rewrite:dev` is the single user-facing entry point.
One JVM, one Maven session, one terminal:

- Binds an LSP server on `127.0.0.1:8487` by default. Override
  via `-Dgraphitron.dev.port=N` for the rare case of multiple
  concurrent sessions or a port conflict. If the chosen port is
  taken, the Mojo fails fast with a message naming the property,
  not a silent rebind that the editor would not know about.
- Watches `<schemaInputs>` directories for filesystem-level
  `.graphqls` writes. A save (not a `did_change` notification)
  is the regeneration trigger, so partial / broken edits in the
  editor never touch the generator. Edits propagate to the LSP
  through `did_change` and surface as live diagnostics; only
  saves flush through the generator pipeline.
- Watches `target/classes/<jooqPackage>/` for `.class` writes and
  rebuilds the in-process catalog on change (see "Catalog
  freshness" above). Removes the "restart `dev` after jOOQ
  schema changes" caveat that the watch goal had to document.
- Regenerates affected output on save (reusing the
  `graphitron-rewrite:generate` pipeline in-process). Idempotent
  writes already in trunk mean only changed files touch disk.
- Logs build progress, watcher events, regeneration outcomes,
  and catalog rebuilds to Maven's stdout/stderr.

Editor lifecycle: the editor connects to the socket on attach and
disconnects on close. The LSP outlives editor restarts; reattach
is sub-second because all state (workspace, parsed trees, catalog)
stays warm in the JVM.

Mojo lives in `graphitron-maven` as `DevMojo`, extending
`AbstractRewriteMojo` (so `<schemaInputs>` / `<jooqPackage>` /
`<outputPackage>` configuration is shared with `generate`). The
existing `WatchMojo` and the Phase 0 `LspMojo` are deleted as
part of Phase 1; the watch goal's `graphitron.watch.debounceMs`
property is renamed to `graphitron.dev.debounceMs` (existing CI
flags update in the same change).

Catalog and aggregator-root discovery happen in the Mojo, which
runs with full Maven context (effective POM, classpath, source
roots). The LSP receives a pre-built `RewriteContext` plus a
classloader for the consumer's compiled classes. This closes
OQ A1.

Cross-platform: lsp4j's `ServerSocket` path is platform-neutral.
The bonede tree-sitter binding ships native binaries for Linux
x86_64 / macOS x86_64 / macOS arm64 / Windows x86_64; Phase 1
gates the goal's CI matrix on those four targets so a non-Linux
developer is not surprised at first invocation. Phase 6's
jtreesitter migration takes over native sourcing for those four
platforms (see A4) and the platform list does not change.

## Phasing

Each phase is a coherent landing unit with its own commit set, tests,
and exit criteria. Consumers see the LSP improve incrementally; the
Rust LSP keeps running until Phase 7.

**Phase 0: foundation module.** Shipped in commit `21c5e57`.
`graphitron-rewrite/graphitron-lsp` module wired into the parent
reactor; bonede tree-sitter binding + lsp4j on the classpath;
`GraphqlLanguage`, `Directives`, `Nodes`, `WorkspaceFile`,
`CompletionData` (full shape), `TableCompletions`, lsp4j server
scaffold, P0-interim `LspMojo` registered. 5 passing tests
(`TreeSitterSmokeTest`, `IncrementalParseTest` x2,
`TableCompletionsTest` x2). Exit criteria: all of the above on
trunk; `mvn graphitron-rewrite:lsp` resolves; the
`TableCompletions.generate(...)` call returns the catalog table
set against in-memory `CompletionData` fixtures.
`GraphitronTextDocumentService` ships as empty notification
stubs; the `completion` request handler is wired into the
service surface in Phase 1. The `lsp` Mojo and stdio `Launcher`
are interim; Phase 1 removes both.

**Phase 1: `dev` goal + workspace and file lifecycle.** Bundled
in one phase because the goal needs the workspace to do anything
useful.

Workspace lifecycle:
- `Workspace` class mapping path → `WorkspaceFile`.
- `did_open` / `did_change` / `did_close` handlers in
  `GraphitronTextDocumentService` route through it.
- `dependsOnDeclarations` per file plus `toRecalculate` queue
  ported from Rust.
- `completion` request handler wired to dispatch into
  `TableCompletions.generate(...)`. Closes the Phase 0 gap where
  the service surface advertised completion capability without
  implementing it.

`dev` goal:
- New `DevMojo` in `graphitron-maven` extending
  `AbstractRewriteMojo`. Binds a `ServerSocket` on
  `127.0.0.1:8487` by default, configurable via
  `-Dgraphitron.dev.port=N`, accepts editor connections, hands
  each connection's streams to a fresh lsp4j `Launcher`. Bind
  failure ("port in use") is a Mojo error pointing at the
  override property.
- Reuses the existing `SchemaWatcher` / `DebounceExecutor` from
  the watch goal. The save callback re-runs
  `GraphQLRewriteGenerator.generate()` and then notifies the
  LSP that the generated source tree changed, which prompts a
  diagnostic refresh of any open files whose
  `dependsOnDeclarations` overlap the regenerated set. Save and
  generation are synchronous within the debounce thread; LSP
  notification fires after the generator returns (or after a
  caught `ValidationFailedException`, so diagnostics still
  update on a typo). Generator failure does not kill the LSP
  socket; the loop survives just as `WatchMojo` survives today.
- Adds a second `SchemaWatcher` instance over
  `target/classes/<jooqPackage>/` for `.class` events; the
  callback rebuilds the catalog (see "Catalog freshness").
- Deletes `LspMojo` (Phase 0 stub) and `WatchMojo` (existing
  trunk goal). No deprecation cycle: there are no users yet.
  `getting-started.md` `### Watch mode` rewrites to `### Dev
  loop` (matching the user-doc draft above);
  `graphitron.watch.*` properties rename to `graphitron.dev.*`.

Exit criteria: a single `mvn graphitron:dev` invocation from a
schema module starts the LSP on `localhost:8487`, watches
`.graphqls` files, regenerates on save, watches consumer
`.class` files (the trigger fires; the rebuild plugs in at
Phase 2), and an editor configured for `localhost:8487` connects
and gets table-name completions end-to-end against fixture
catalog data. Tests cover workspace lifecycle through a piped
lsp4j server and watch-trigger tests reusing the existing
`SchemaWatcherTest` patterns. End-to-end catalog-refresh
coverage waits for Phase 2.

**Phase 2: jOOQ-side catalog wiring.** Replace
`CompletionData.empty()` by adding a `buildCatalog()` method on
`GraphQLRewriteGenerator` that returns a `CompletionData`
populated with tables, columns, foreign-key references, and
scalar types. The rewrite module owns assembly; the LSP module
imports only the result. Source map: tables / columns /
references read from the existing public `JooqCatalog`; scalars
walked from the parsed `GraphQLSchema` directly (no new
`ScalarCatalog` class). The `ExternalReference` slot stays empty
until Phase 5 (services need their own enumeration source).
`DevMojo` calls `buildCatalog()` once at startup and on every
classpath-watcher trigger, swapping the result into `Workspace`
atomically. Resolves OQ A1 (how the LSP acquires the consumer's
classpath: through the same Maven-context that already builds the
`RewriteContext`). Exit criteria: an aggregator with a real jOOQ
catalog produces a non-empty `CompletionData`; the
table-completion path returns the actual table set; the
catalog-refresh hook swaps in new tables when the consumer
recompiles.

**Phase 3: directive ports.** Per-directive completions,
diagnostics, and hover for `@field`, `@reference`, `@record`,
`@connect`. Mirrors the Rust LSP's
`completions/{field,reference,record,connect}.rs` and
`diagnostics/{field,reference,record,connect}.rs`. Tree-sitter
queries port verbatim. Exit criteria: feature parity with the
Rust LSP minus goto-definition and `@service` / `@condition`.

**Phase 4: goto-definition.** Real source URIs for tables,
columns, methods. Resolves the legacy `file:///tables/<NAME>`
stub. Exit criteria: clicking through to a column declaration in
the jOOQ-generated source works in the editor; field- and
reference-level goto-def replace the Rust LSP's `todo!()` stubs.

**Phase 5: `@service` / `@condition` + Javadoc.** The contract
extensions that motivated this work. Method signatures populate
`Method.parameters` from `MethodRef`; Javadoc for tables /
columns / scalars / methods is read from aggregator source files
via JavaParser (`com.github.javaparser:javaparser-symbol-solver-core`)
and attached to the corresponding `description` slot. JavaParser
is the chosen tool: it works against classpath sources (jars
containing source attachments included), it does not depend on
JDK-internal `com.sun.source` APIs, and the rewrite already has
no stake in either; new dependency only.

Service enumeration is its own subproblem and lands here.
`ServiceCatalog` is a stateless reflector that only knows what
the schema explicitly references, so it cannot answer "what
service classes / methods exist?" for autocomplete. Three
candidate sources, to choose between when this phase opens:

- The POM's `<namedReferences>` map. Covers the legacy
  `@service(name:)` form only; modern `@service(class:)` schemas
  do not populate it.
- A classpath scan for an annotation marker (e.g. a new
  `@RewriteService` annotation consumers attach to service
  classes). Bounded, explicit, but adds an authoring step.
- A JavaParser walk over the consumer's source roots. Heavy in
  isolation, but Phase 5 already adopts JavaParser for the
  Javadoc work, so the marginal cost is mostly walking
  additional files.

Pick the source on phase open; the data shape (`ExternalReference`
/ `Method` / `Parameter` records on `CompletionData`) is already
defined and unchanged.

Exit criteria: schema author gets parameter completion on
`@service(method:)` / `@condition`, and hovers on a column show
the column's `COMMENT ON COLUMN` text.

**Phase 6: jtreesitter migration + grammar vendoring.** Bump
`<release>21</release>` to `<release>25</release>` in the parent
pom; swap the `io.github.bonede:tree-sitter` runtime for
`io.github.tree-sitter:jtreesitter` (latest stable at the time
the bump is authorised; pin then). jtreesitter does not publish
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

Per-phase, with the spike's pattern as the seed. The unit tier
covers parsing / completion / diagnostic / hover logic against
in-memory schemas and `CompletionData` fixtures; the spike's
`TableCompletionsTest` is the template. The integration tier
spins up a real lsp4j `Launcher` over paired
`PipedInputStream` / `PipedOutputStream` and sends synthetic
LSP messages; this tier lands in Phase 1 alongside the
workspace lifecycle.

Per-phase additions:

- **Phase 1:** `WorkspaceTest` covering `did_open` / `did_change`
  / `did_close` and the `dependsOnDeclarations` recalculation
  trigger. `TextDocumentServiceTest` with a piped lsp4j server,
  including a `completion` request that round-trips through
  `TableCompletions`. `DevServerTest` and `DevMojoTest` covering
  bind, port override, bind-failure-message-points-at-property,
  and warm-state across editor reconnects. End-to-end
  catalog-refresh coverage waits for Phase 2 (the wire is in
  place, but the rebuilder it feeds lands then).
- **Phase 2:** `BuildCatalogTest` driving
  `GraphQLRewriteGenerator.buildCatalog()` against a fixture jOOQ
  catalog (reuse `graphitron-fixtures` for the jOOQ classes) and
  asserting the returned `CompletionData` exposes the expected
  tables, columns, FK references, and scalars (walked from the
  parsed `GraphQLSchema`). Catalog-refresh coverage moves here
  from Phase 1 since it is what proves the swap is observable
  through the dev goal.
- **Phase 3:** one test per directive, mirroring the Rust LSP's
  `tests/mod.rs` cases. Cursor-marker fixture (`❌` like the
  Rust tests) is convenient.
- **Phase 4:** goto-definition tests with synthetic source URIs.
- **Phase 5:** `@service` / `@condition` end-to-end tests; Javadoc
  extraction tests against fixture jOOQ + service classes; one
  test for the `-parameters` absent path (parameter names null).
- **Phase 6:** existing test suite must pass against the
  jtreesitter binding without test-side changes (validates the
  `GraphqlLanguage` swap is the only point that changed).

The spike's 5 tests survive every phase; if any breaks during
implementation, the breakage is the implementer's bug, not a
test-fixture rewrite.

## Rollout

The Rust LSP keeps running until Phase 7. Consumers do not migrate
until the Java LSP reaches feature parity (end of Phase 4) plus the
contract extensions they wanted (end of Phase 5). At that point:

1. Consumer runs `mvn graphitron-rewrite:dev` in a terminal in their
   schema module. The LSP starts on `localhost:8487`; the watch
   loops start.
2. Consumer points their editor's LSP client at `localhost:8487`
   (one-time editor configuration; recipes for IntelliJ, VS Code,
   and Claude Code documented alongside Phase 1). The configured
   address is the same on every machine and every project, so
   recipes are copy-pasteable.
3. Consumer disables the Rust LSP integration in the same editor
   config: the two LSPs serve overlapping requests, and both
   connected at once produces duplicated / conflicting
   completions.
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
only consumer-visible difference, and it falls out of the design
choice we made up front.

## Roadmap integration

This plan owns the umbrella sub-item "Java LSP rewrite + introspect
retirement + `dev` goal" under [retire-maven-plugin.md](retire-maven-plugin.md).
The umbrella's body summarises Phase 0's landing and points back here for
Phases 1-7.

Implicitly retires the standalone watch-goal sub-item: the existing
`graphitron-rewrite:watch` Mojo is deleted in Phase 1 and absorbed
by `dev`. `plan-watch-goal.md` is already removed from trunk (the
watch goal landed under its own plan and the plan was deleted on
landing per the delete-on-done rule); no separate retirement step.

On Phase 7 landing, the umbrella entry collapses to a Done line
citing the last commit and the test location.

## Open questions

Numbered to match phase-of-resolution. A1, A2, and A4 are
resolved inline below; A3 closes when its phase reaches it.

A1. ~~**Catalog classpath acquisition.**~~ **Resolved by `dev`
   goal design.** The Mojo runs in full Maven context with
   `requiresDependencyResolution = COMPILE`, so the consumer's
   compiled jOOQ classes are on the same classloader the
   generator uses. Phase 2's `buildCatalog()` reads through that
   classloader; no separate discovery walk, no environment
   guessing.

A2. ~~**Port-file location and lifecycle.**~~ **Resolved by
   fixed-default-port design.** No port file. The dev goal binds
   `127.0.0.1:8487` by default and refuses to start if the port
   is taken (with an error pointing at `-Dgraphitron.dev.port=N`
   for the override case). Editors configure the same address on
   every machine; multi-session is the rare case and uses the
   override on both sides. Crash handling collapses to the JVM
   shutdown hook (already in `WatchMojo`); no stale-file
   detection, no heartbeat, no policy.

A3. **`-parameters` propagation for service Javadoc.** The
   rewrite generator already emits a one-shot warning when it
   reads a service class compiled without `-parameters`
   (`ServiceCatalog.emitParametersWarning` in
   `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/ServiceCatalog.java`).
   The LSP needs the same behaviour but should surface it as an
   LSP diagnostic / status message rather than a build-time log.
   Closes in Phase 5.

A4. ~~**Grammar-binary sourcing for the jtreesitter migration.**~~
   **Resolved by deferring vendoring to Phase 6.** Research after
   the slice-2 landing turned up two facts that invalidate the
   original "vendor in Phase 1, swap binding in Phase 6" plan:
   (a) `io.github.tree-sitter` publishes only the `jtreesitter`
   binding on Maven Central, with no per-grammar artefact,
   and `Language.load` expects the consumer to provision the
   native library via OS search path / `java.library.path` /
   `NativeLibraryLookup`; (b) the bonede `tree-sitter-graphql`
   coordinate (`master-a`) is published-and-immutable on Maven
   Central even though the label looks snapshot-y, and the
   bonede umbrella project is actively maintained (latest
   release 2026-03-01). Conclusion: vendoring is needed exactly
   when jtreesitter goes live, not before; staying on the
   bonede artefact in Phase 1 buys nothing back if we vendor
   now. Phase 6 lands the grammar source under
   `graphitron-lsp/src/main/resources/grammars/`, builds it per
   platform, and ships per-platform natives as classpath
   resources under
   `graphitron-lsp/src/main/resources/native/<platform>/`.

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
  needs to replicate this for the new binding.

Phase 0 landed in commit `21c5e57 feat(lsp): graphitron-lsp module
+ Maven launcher (Phase 0 foundation)`.

The Phase 0 `LspMojo` uses stdio transport. That was the cheapest
shape to validate the architecture; it is not the final transport.
Phase 1 replaces it with a TCP `dev` Mojo (loopback, fixed default
port `8487`, override via `-Dgraphitron.dev.port=N`) which lets
the LSP and the schema-watch loop coexist in one JVM and decouples
the LSP lifecycle from any individual editor / agent connection.
The decision to use sockets emerged from asking whether the user
docs read cleanly with two separate goals (`lsp` + `watch`); they
did not. The further decision to use a fixed default port instead
of a dynamic port + port-file emerged from asking whether real
editor LSP clients can read a port file and dial a socket without
a wrapper script; mostly they cannot. A static address composes
with stock LSP-client config.

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
