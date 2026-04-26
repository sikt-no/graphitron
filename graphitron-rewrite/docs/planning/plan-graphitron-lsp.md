# Plan: Java LSP rewrite + introspect retirement + `dev` goal

> **Status:** Spec
>
> Replaces the Rust `graphitron-lsp` and the legacy
> `graphitron-maven-plugin:introspect` JSON producer with a Java LSP
> module under `graphitron-rewrite/graphitron-lsp`. Single user-facing
> entry point: `mvn graphitron-rewrite:dev`, which runs the LSP on a
> loopback socket and watches `<schemaInputs>` for regeneration in the
> same JVM. The standalone `lsp` and `watch` Mojos disappear; nobody
> consumes them yet, so consolidation is free.
>
> tree-sitter (`tree-sitter-graphql`) for parsing; `MethodRef` /
> `ServiceCatalog` / `TableReflection` / `ScalarUtils` imported
> in-process for catalog data. The introspect goal closes by deletion.
>
> Phase 0 (foundation module + interim stdio `lsp` Mojo) shipped as
> part of this plan's introduction. Phase 1 introduces the `dev` goal
> and retires both `lsp` and the existing `watch` Mojo. Phases 2-7
> below describe the remaining work.

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
  (~280 LOC; deletion target in Phase 7).
- **Generator catalog API the LSP imports** (Phase 2): `MethodRef`
  / `ParamSource` /  `ServiceCatalog` /  `TableReflection` /
  `ScalarUtils` under
  `graphitron-rewrite/graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/`.
- **Phase 0 spike code**: under
  `graphitron-rewrite/graphitron-lsp/`, with 5 passing tests.
- **Existing watch goal that Phase 1 absorbs**:
  `graphitron-rewrite/graphitron-rewrite-maven/src/main/java/no/sikt/graphitron/rewrite/maven/{WatchMojo.java,watch/}`.

## Goal

Stand up a Java LSP under `graphitron-rewrite/graphitron-lsp` that
replaces the Rust `graphitron-lsp` at feature parity, then extends
the contract to surface `@service` / `@condition` method help and
Javadoc on tables, columns, scalars, and methods. Catalog data
arrives in-process from the generator's reflection layer; no JSON
producer, no separate LSP binary. The introspect Maven goal in the
legacy plugin closes by deletion.

User-facing surface: a single `mvn graphitron-rewrite:dev` goal
runs both the LSP (on a loopback socket) and the schema-input watch
loop in one JVM. The editor's LSP client connects to the socket;
schema saves trigger regeneration in the same JVM. The standalone
`lsp` and `watch` goals are not exposed to users; they are
implementation details that get removed during Phase 1.

## Motivation

Sikt is a Java shop. The Rust LSP is a historical accident; every
new feature needs maintainers from a different ecosystem than the
generator. Folding the LSP into rewrite means one team, one
language, and direct reuse of the generator's catalog API
(`MethodRef` / `ServiceCatalog` / `TableReflection` /
`ScalarUtils`). The contract extensions the LSP roadmap wants next
(`@service` / `@condition` help, Javadoc) are mechanical once the
rewrite catalog is in-process; they are awkward across a JSON
boundary. The spike has validated that tree-sitter ports cleanly
to Java, that incremental parsing works through the bonede binding,
and that the Rust LSP's responsiveness patterns survive the
language swap.

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
watch loop, not by the LSP itself. Reason: agents often edit files
through filesystem APIs, not LSP `didChange` notifications; a
filesystem watch catches both LSP-mediated saves and direct file
writes. The LSP receives the resulting source-tree updates the
same way it would for any other consumer-side change.

### Draft user-facing copy

---

```markdown
## Dev loop

Start your dev session from the schema module:

    mvn graphitron-rewrite:dev

This runs the graphitron LSP and watches your `.graphqls` files.
Saving a schema regenerates the affected Java sources under
`target/generated-sources/graphitron`; only changed files touch
disk.

Connect your editor or agent to the LSP by pointing its LSP client
at the socket address written to `target/graphitron-lsp.port`.
Setup recipes for IntelliJ, VS Code, and Claude Code are in
[editor-setup.md].

Stop with Ctrl+C.
```

---

That is the entire user-facing surface. Three steps: run, connect,
edit. No flags, no toggles, no choice between `lsp` and `watch`,
no stdio piping. Anything the docs cannot fit into that shape
without qualification is a design failure.

Tests for design / docs alignment:

- A new contributor (human or agent) can copy the snippet, run it,
  and have a working dev loop without reading anything else.
- The plan introduces no Mojo parameter that getting-started.md
  has to describe to make the basic case work. System-property
  knobs for advanced users are fine, like the watch goal's
  `graphitron.watch.debounceMs`; they live below the basic recipe.
- The error path "I forgot to start `mvn graphitron-rewrite:dev`"
  produces an editor / agent message a non-graphitron developer
  can act on (typically "Connection refused; check the dev session
  is running"). The port-file absence is itself a hint.

## Scope boundaries

**In scope**

- Java LSP module `graphitron-rewrite/graphitron-lsp`, lsp4j-based.
- `graphitron-rewrite-maven:dev` Mojo as the single user-facing
  goal: LSP-on-socket + schema-watch + regeneration, all one JVM.
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

`graphitron-rewrite-maven` gains `LspMojo` (P0-interim, registered
as `mvn graphitron-rewrite:lsp`) that wraps `Launcher.main`. Phase 1
deletes both `LspMojo` and the existing `WatchMojo`, replacing
them with `DevMojo` (`mvn graphitron-rewrite:dev`) over a loopback
socket.

### Catalog API

The LSP holds an immutable `CompletionData` populated from the
generator's reflection layer (no JSON serialisation, no producer).
Records mirror what the Rust LSP's `completion_data` module
deserialises today, so the consumer-side surface is the same.
Already in tree under `catalog/CompletionData.java` (Phase 0):

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

Phase 2 wires these to `MethodRef` / `ServiceCatalog` /
`TableReflection` / `ScalarUtils` directly. Until then,
`CompletionData.empty()` serves and tests pass it explicit fixtures.

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

- Binds an LSP server on `127.0.0.1:NNNN` (random free port).
- Writes the chosen port to `target/graphitron-lsp.port` so the
  editor's LSP client can find it without configuration.
- Watches `<schemaInputs>` directories for `.graphqls` changes.
- Regenerates affected output on save (reusing the
  `graphitron-rewrite:generate` pipeline in-process). Idempotent
  writes already in trunk mean only changed files touch disk.
- Logs build progress, watcher events, and regeneration outcomes
  to Maven's stdout/stderr.

Editor lifecycle: the editor connects to the socket on attach and
disconnects on close. The LSP outlives editor restarts; reattach
is sub-second because all state (workspace, parsed trees, catalog)
stays warm in the JVM.

Mojo lives in `graphitron-rewrite-maven` as `DevMojo`, extending
`AbstractRewriteMojo` (so `<schemaInputs>` / `<jooqPackage>` /
`<outputPackage>` configuration is shared with `generate`). The
existing `WatchMojo` and the Phase 0 `LspMojo` are deleted as
part of Phase 1.

Catalog and aggregator-root discovery happen in the Mojo, which
runs with full Maven context (effective POM, classpath, source
roots). The LSP receives a pre-built `RewriteContext` plus a
classloader for the consumer's compiled classes. This closes
OQ A1.

## Phasing

Each phase is a coherent landing unit with its own commit set, tests,
and exit criteria. Consumers see the LSP improve incrementally; the
Rust LSP keeps running until Phase 7.

**Phase 0: foundation module.** Shipped at landing of this plan.
`graphitron-rewrite/graphitron-lsp` module wired into the parent
reactor; bonede tree-sitter binding + lsp4j on the classpath;
`GraphqlLanguage`, `Directives`, `Nodes`, `WorkspaceFile`,
`CompletionData` (full shape), `TableCompletions`, lsp4j server
scaffold, P0-interim `LspMojo` registered. 5 passing tests
(`TreeSitterSmokeTest`, `IncrementalParseTest` x2,
`TableCompletionsTest` x2). Exit criteria: all of the above on
trunk; `mvn graphitron-rewrite:lsp` resolves; the spike's
end-to-end completion path works against an in-memory catalog.
The `lsp` Mojo and stdio `Launcher` are interim; Phase 1 removes
both.

**Phase 1: `dev` goal + workspace and file lifecycle.** Two
landings, coherent as one phase because the goal needs the
workspace to do anything useful.

Workspace lifecycle:
- `Workspace` class mapping path → `WorkspaceFile`.
- `did_open` / `did_change` / `did_close` handlers in
  `GraphitronTextDocumentService` route through it.
- `dependsOnDeclarations` per file plus `toRecalculate` queue
  ported from Rust.

`dev` goal:
- New `DevMojo` in `graphitron-rewrite-maven` extending
  `AbstractRewriteMojo`. Binds a `ServerSocket` on
  `127.0.0.1:0` (kernel-chosen free port), writes
  `target/graphitron-lsp.port`, accepts one connection, hands
  the streams to lsp4j's `Launcher`.
- Reuses the existing `SchemaWatcher` / `DebounceExecutor` from
  the watch goal. The trigger callback now does both: notify
  the LSP of a regeneration event (so it can refresh diagnostics
  for the new generated context) and re-run
  `GraphQLRewriteGenerator.generate()`.
- Deletes `LspMojo` (Phase 0 stub) and `WatchMojo` (existing
  trunk goal). No deprecation cycle: there are no users yet.
- Plan-watch-goal.md retires; its content is absorbed here.

Exit criteria: a single `mvn graphitron-rewrite:dev` invocation
from a schema module starts the LSP on a known port, watches
`.graphqls` files, regenerates on save, and an editor configured
to read the port file connects and gets table-name completions
end-to-end. Tests cover both halves: workspace lifecycle through
a piped lsp4j server, plus a watch-trigger test reusing the
existing `SchemaWatcherTest` patterns.

**Phase 2: catalog wiring.** Replace `CompletionData.empty()` with
a builder that imports `MethodRef` / `ServiceCatalog` /
`TableReflection` / `ScalarUtils` directly and populates the full
record set. Resolves OQ A1 (how the LSP acquires the consumer's
classpath). Exit criteria: an aggregator with a real jOOQ catalog
produces a non-empty `CompletionData`; the table completion path
returns the actual table set.

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
via JavaParser or `com.sun.source` and attached to the
corresponding `description` slot. Exit criteria: schema author
gets parameter completion on `@service(method:)` / `@condition`,
and hovers on a column show the column's `COMMENT ON COLUMN`
text.

**Phase 6: jtreesitter migration.** Bump `<release>21</release>`
to `<release>25</release>` in the parent pom; swap
`io.github.bonede:tree-sitter` /
`io.github.bonede:tree-sitter-graphql` for
`io.github.tree-sitter:jtreesitter:0.26.0` plus a separately
packaged `tree-sitter-graphql` grammar; vendor the grammar's
native libraries under
`graphitron-lsp/src/main/resources/native/`. Resolves OQ A2
(grammar-binary sourcing). Exit criteria: official FFM-based
binding active; `GraphqlLanguage` is the only file that changed
on the LSP side; tests pass.

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
spins up a real lsp4j server against a `Connection.PIPE` and
sends synthetic LSP messages; this tier lands in Phase 1
alongside the workspace lifecycle.

Per-phase additions:

- **Phase 1:** `WorkspaceTest` covering `did_open` / `did_change`
  / `did_close` and the `dependsOnDeclarations` recalculation
  trigger. `TextDocumentServiceTest` with a piped lsp4j server.
- **Phase 2:** `CatalogBuilderTest` with a fixture jOOQ catalog
  (reuse `graphitron-rewrite-fixtures` for the jOOQ classes).
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
   schema module. The LSP starts; the watch loop starts; the port
   file lands at `target/graphitron-lsp.port`.
2. Consumer points their editor's LSP client at the socket address
   in the port file (one-time editor configuration; pattern
   documented in getting-started).
3. Consumer drops the `<execution>` for
   `graphitron-maven-plugin:introspect` from their POM.
4. Consumer drops the `graphitron-maven-plugin` dependency once
   nothing else uses it (separately tracked under the umbrella
   "Retire `graphitron-maven-plugin`").

Phase 7 then deletes the legacy `IntrospectMojo` from
`graphitron-maven-plugin` and archives the Rust LSP repo.

No breaking changes to the LSP protocol surface during the rollout:
completions / hover / diagnostics behave the same on the wire. The
editor-config change (stdio process spawn → socket connect) is the
only consumer-visible difference, and it falls out of the design
choice we made up front.

## Roadmap integration

This plan owns the umbrella sub-item "Java LSP rewrite + introspect
retirement + `dev` goal" under "Retire `graphitron-maven-plugin` +
`graphitron-schema-transform`" in `rewrite-roadmap.md`. The roadmap
entry summarises Phase 0's landing and lists Phases 1-7 as remaining.

Implicitly retires the standalone watch-goal sub-item: the existing
`graphitron-rewrite:watch` Mojo is deleted in Phase 1 and absorbed
by `dev`. `plan-watch-goal.md` is already removed from trunk (the
watch goal landed under its own plan and the plan was deleted on
landing); no separate retirement step.

On Phase 7 landing, the umbrella entry collapses to a Done line
citing the last commit and the test location.

## Open questions

Numbered to match phase-of-resolution. A1 is the only resolved item
kept inline; future phases close A2-A4 as they reach them.

A1. ~~**Catalog classpath acquisition.**~~ **Resolved by `dev`
   goal design.** The Mojo runs in full Maven context, builds the
   classpath, and constructs the LSP-side reflection layer in the
   same JVM. No discovery walk, no environment guessing.

A2. **Port-file location and lifecycle.** The `dev` goal writes
   the chosen port to `target/graphitron-lsp.port` on startup and
   removes it on shutdown. Decide:
   - File contents: just the port number, or an LSP `tcp://...`
     URI? Recommend port number alone; clients can build the URI.
   - Stale-file handling on crash: if `dev` dies hard, the file
     stays. Recommend the file write also include a heartbeat
     timestamp; clients treat files older than N minutes as stale.
   - Multiple `dev` sessions in the same aggregator: prevented by
     port-file lock or allowed with overwrite semantics? Recommend
     prevented (one session at a time per aggregator); second
     invocation fails with an actionable message pointing at the
     existing port file.
   Closes in Phase 1 when the goal is implemented.

A3. **`-parameters` propagation for service Javadoc.** The
   rewrite generator already emits a one-shot warning when it
   reads a service class compiled without `-parameters`
   (`ServiceCatalog.emitParametersWarning` in
   `graphitron-rewrite/graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/ServiceCatalog.java`).
   The LSP needs the same behaviour but should surface it as an
   LSP diagnostic / status message rather than a build-time log.
   Closes in Phase 5.

A4. **Grammar-binary sourcing for the jtreesitter migration.**
   Three options: build `tree-sitter-graphql` from source in CI
   per platform, vendor pre-built binaries from upstream, or
   publish our own Maven artefact mirroring the bonede shape.
   Closes in Phase 6 when the migration starts. Recommend the
   build-from-source-in-CI path: the grammar source lives at a
   stable upstream, our build owns the per-platform compilation,
   and the binaries land in `src/main/resources/native/`.

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

Phase 0 commits on trunk:
`feat(lsp): foundation module + Maven launcher`,
`feat(lsp): WorkspaceFile + incremental parse`,
`refactor(lsp): centralise GraphQL binding; expand CompletionData
shape`.

The Phase 0 `LspMojo` uses stdio transport. That was the cheapest
shape to validate the architecture; it is not the final transport.
Phase 1 replaces it with a socket-based `dev` Mojo (loopback,
random port, port-file at `target/graphitron-lsp.port`) which lets
the LSP and the schema-watch loop coexist in one JVM and decouples
the LSP lifecycle from any individual editor / agent connection.
The decision to use sockets emerged from asking whether the user
docs read cleanly with two separate goals (`lsp` + `watch`); they
did not.

## Appendix: alternatives rejected

Three other shapes were considered before D-tree-sitter was
locked. Brief tombstones for the record; git history (the
ancestors of this plan, slug `plan-introspect-goal.md` before
rename) carries the full discussion.

- **Port `introspect` to `graphitron-rewrite-maven` (Java
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
