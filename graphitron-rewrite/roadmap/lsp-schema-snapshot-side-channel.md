---
id: R139
title: "Dev-pipeline to LSP schema-snapshot side-channel; first client unknown-directive validator"
status: Spec
bucket: architecture
theme: lsp
depends-on: []
---

# Dev-pipeline to LSP schema-snapshot side-channel; first client unknown-directive validator

> Extend the existing dev-pipeline-to-LSP data flow (today: `CompletionData` shipped via `Workspace.setCatalog`) with a second side-channel that carries a projection of the parsed user schema. The first client is the unknown-directive validator in `Diagnostics`, which today warns on every directive not declared in graphitron's bundled `directives.graphqls` and flags user-declared directives (`@key`, `@external`, `@auth`-style guards, anything else) as if they were typos. With the snapshot in hand, the validator silences when the name resolves in the user's schema (snapshot `Built` + name found), keeps warning when the name resolves nowhere (snapshot `Built` + name absent), and degrades to silence when the snapshot is `Unavailable` (pre-build or parse-broken). Same shape as the existing `externalReferences().isEmpty()` gate. The plumbing also unlocks hover prose, unknown-arg validation, and type-name completion on user-declared types as follow-on items, without revisiting the data flow.

This item is the directive-warning case from the design conversation that produced branch `claude/fix-graphql-directive-warnings-3hCFB`. The narrow framing (silence the warning) was rejected in favour of the broader plumbing: the LSP's current "only graphitron's bundled SDL exists" assumption is the root cause of the false positive, not the warning itself, and the cure should remove that assumption rather than paper over one symptom.

---

## Motivation

`Diagnostics.compute` (`graphitron-lsp/.../diagnostics/Diagnostics.java:87-91`) emits an LSP `Warning` for every directive name that is neither in the parsed bundled `directives.graphqls` registry (`LspVocabulary.registry()`) nor in the hard-coded `SPEC_BUILTIN_DIRECTIVES` set (`skip`, `include`, `deprecated`, `specifiedBy`, `oneOf`). User-authored directives fall through this arm: a schema carrying `@key(fields: "id")` or `@requiresAuthentication` gets pelted with one warning per use. The pinned test `unknownDirectiveProducesWarning` (`DiagnosticsTest.java:586`) currently locks this behaviour in for the typo case (`@tabel`), with no concession to user-declared shapes.

The data the validator needs to make a better decision already exists. The dev pipeline parses the user's full schema (graphitron directives plus user directives plus user types) to build the model that drives generation; the LSP's tree-sitter view sees only the open buffer's bytes. The pre-existing `CompletionData` shipped via `Workspace.setCatalog` (`Workspace.java:129`, called from `DevMojo.regenerate` at `:192`) shows the wedge: build-pipeline data flows into the LSP as a narrow projection on `volatile` refs; pre-`mvn compile` consumers degrade silently (`Diagnostics.java:325` `externalReferences().isEmpty()`). The unknown-directive validator should consume an equivalent projection of the parsed schema.

The narrow alternative (parse user-declared `directive` definitions from open workspace buffers via tree-sitter) was rejected in conversation as duplication: the build pipeline already handles `extend type`, multi-file aggregation, and the bundled-directives overlay merge; reimplementing any of that against tree-sitter drifts. Tree-sitter stays the primary parse for buffer-instant feedback, but the structural snapshot lives where the build pipeline already produces it.

---

## Design

### The wedge: a second `volatile` ref on `Workspace`

`Workspace` today carries `volatile CompletionData catalog`. R139 adds `volatile LspSchemaSnapshot snapshot` next to it, with the same lifecycle: built by the dev pipeline, swapped in on each successful generator pass, read on every diagnostics / completion / hover request without taking the file lock. `setSnapshot(...)` marks every open file for recalculation, identical to `setCatalog` at `Workspace.java:129-132`.

```java
public final class Workspace {
    private volatile CompletionData catalog;
    private volatile LspSchemaSnapshot snapshot;   // new

    public LspSchemaSnapshot snapshot() { return snapshot; }
    public void setSnapshot(LspSchemaSnapshot snapshot) {
        this.snapshot = snapshot;
        markAllForRecalculation();
    }
}
```

`LspSchemaSnapshot.Unavailable` is the pre-build / parse-broken state. Consumers switch through the sealed variant the way `Diagnostics.validateClassName` gates on `catalog.externalReferences().isEmpty()` at `Diagnostics.java:325`: an unavailable snapshot means "do not punish the user for what we cannot see yet", and the build-tier validation surfaces the precise rejection arm when the pipeline runs.

### The projection: `LspSchemaSnapshot`, sealed over availability

Per *Wire-format encoding is a boundary concern* and the catalog precedent (`CompletionData` projects `JooqCatalog` + `GraphQLSchema` rather than re-exporting either), the LSP module never sees `graphql.schema.idl.TypeDefinitionRegistry` directly. A new sealed interface in `graphitron`'s `catalog` package (sibling to `CompletionData`) carries the structural data the LSP needs, with variants per availability state so consumers switch once rather than re-checking a boolean plus an empty list at every site (per *Sealed hierarchies over enums for typed information*):

```java
public sealed interface LspSchemaSnapshot
        permits LspSchemaSnapshot.Unavailable, LspSchemaSnapshot.Built {

    record Unavailable() implements LspSchemaSnapshot {}

    record Built(List<DirectiveShape> directives) implements LspSchemaSnapshot {
        public Optional<DirectiveShape> directive(String name) {
            return directives.stream().filter(d -> d.name().equals(name)).findFirst();
        }
    }

    static LspSchemaSnapshot unavailable() {
        return new Unavailable();
    }
}

public record DirectiveShape(
    String name,
    List<InputValueShape> args,
    Optional<String> description
) {}

public record InputValueShape(
    String name,
    TypeShape type,
    Optional<String> description
) {}

public sealed interface TypeShape permits TypeShape.Named, TypeShape.List {
    boolean nonNull();

    record Named(String typeName, boolean nonNull) implements TypeShape {}
    record List(TypeShape inner, boolean nonNull) implements TypeShape {}
}
```

`Unavailable` covers both the pre-build state (the pipeline has not run) and the parse-broken state (the pipeline ran but failed to produce a coherent registry). Phase 1 consumers do not need to distinguish the two; phase 2 may, in which case `Unavailable` splits into permits (`NoBuildYet`, `ParseFailed`) under the same sealed root without changing call sites that switch on the umbrella `Unavailable` arm. The split is held in reserve, not shipped speculatively.

`TypeShape` is sealed rather than the rendered SDL string the first draft of this spec carried: phase 2's hover / arg-completion / unknown-arg-validation consumers all need to discriminate list vs. named and nullable vs. non-null, and the spec already commits to those as the same projection's clients. A `String typeRendering` field would force every phase 2 consumer to re-parse the rendering, the precise "switches on a raw string or recomputes a derived name" smell the principles call out. Rendering the sealed shape back to SDL for hover labels is a one-line walk where needed.

`Built` carrying only `directives` is the deliberate phase 1 shape. `declaredTypeNames` was held in the first draft against phase 2 type-name completion, but per *Pipeline tests are the primary behavioural tier* a projection field without a consumer ships without behavioural coverage. The field lands when phase 2's first type-name client lands, and a snapshot-version migration is not real cost for an internal side-channel under one team's control.

### The producer: extend `CatalogBuilder`, not a parallel builder

`CatalogBuilder` (`graphitron/.../rewrite/catalog/CatalogBuilder.java`, the existing producer of `CompletionData`) gains a `buildSnapshot(GraphQLSchema schema, TypeDefinitionRegistry registry)` overload returning `LspSchemaSnapshot`. The dev mojo's `regenerate` calls both `buildCatalog(...)` and `buildSnapshot(...)` on the same pass and pushes both via the workspace's two setters. The two projections share a producer because they share a parse, but they are independent enough (the catalog reads from jOOQ + external references; the snapshot reads from the registry) that no shared internal state is needed.

`GraphQLRewriteGenerator` already constructs the `TypeDefinitionRegistry` during its parse step; `DevMojo.regenerate` at `:192` calls `new GraphQLRewriteGenerator(ctx).buildCatalog()`. The diff is a sibling method that returns both projections, or an extra setter call on the workspace, depending on which lands cleaner; the latter keeps `buildCatalog`'s signature unchanged and is the smaller diff.

### Load-bearing producer invariants

The `Built` arm carries an implicit contract: the registry parse completed without errors and every user directive in the registry round-trips into `DirectiveShape`. The unknown-directive validator relies on this; if `buildSnapshot` ever ships `Built(...)` from a partial parse, the snapshot silently authorises false negatives (a real typo gets a green light). Per *Validator mirrors classifier invariants* and *Classifier guarantees shape emitter assumptions*, this is an `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` pair across the two modules:

| Key                                              | Producer site                                          | Consumer site                                        |
|--------------------------------------------------|--------------------------------------------------------|------------------------------------------------------|
| `snapshot-built-implies-clean-parse`             | `CatalogBuilder.buildSnapshot` (returns `Unavailable` on any parse error, never partial `Built`) | `Diagnostics`' unknown-directive arm (silences on `Unavailable`, warns on `Built` + name-not-found) |
| `snapshot-directive-roundtrip-faithful`          | `CatalogBuilder.buildSnapshot` (every `DirectiveDefinition` in the registry produces exactly one `DirectiveShape` with the same name, args, description) | `Workspace.resolveDirective` (returns the snapshot's shape when bundled SDL lacks the name) |

`LoadBearingGuaranteeAuditTest` covers both keys automatically once the annotations land. A future relaxation (admitting a partial parse, say, to surface "the schema doesn't parse but here's what we got") surfaces as an orphaned consumer site rather than as silent false negatives in IDE diagnostics.

### The boundary: snapshot lives on the catalog side, not in `LspVocabulary`

`LspVocabulary` is the parsed bundled SDL plus the hand-coded overlay; it is immutable per session and ships with the LSP jar. R139 keeps it that way. Composing the snapshot into the vocabulary's `TypeDefinitionRegistry` would mix two lifecycles (immutable session-lifetime vs. volatile build-driven) on one record, and the structural-invariant check in `LspVocabulary`'s constructor (every overlay coordinate resolves against the registry) only makes sense against the bundled SDL: user directives must not be allowed to satisfy graphitron-overlay coordinates.

Consumers that want the union of bundled + snapshot directives call a single new accessor on `Workspace`:

```java
public sealed interface DirectiveResolution {
    record Bundled(DirectiveDefinition def) implements DirectiveResolution {}
    record User(DirectiveShape shape) implements DirectiveResolution {}
    record Unknown() implements DirectiveResolution {}
}

public DirectiveResolution resolveDirective(String name) {
    var bundled = vocabulary.registry().getDirectiveDefinition(name);
    if (bundled.isPresent()) return new DirectiveResolution.Bundled(bundled.get());
    return switch (snapshot) {
        case LspSchemaSnapshot.Unavailable u -> new DirectiveResolution.Unknown();
        case LspSchemaSnapshot.Built b -> b.directive(name)
            .<DirectiveResolution>map(DirectiveResolution.User::new)
            .orElseGet(DirectiveResolution.Unknown::new);
    };
}
```

`resolveDirective` is the *only* path callers use to query the union; the bundled-vs-snapshot precedence (bundled shadows snapshot) is encoded once on the workspace, not duplicated at every site. Phase 2's hover and arg-validation arms switch on the same sealed result. The unknown-directive arm in `Diagnostics` becomes a one-liner over `DirectiveResolution.Unknown`; see the next section.

The collision case (bundled shadows snapshot) covers the user accidentally declaring `directive @table(name: String)` in their own SDL: graphitron's overlay binds the directive's `name:` slot to a `CatalogTableBinding`, and the LSP must use graphitron's shape, not the user's. The collision is also a candidate for a separate diagnostic (warn the user that their directive shadows a graphitron one), but that is out of scope here.

Pre-build snapshot state collapses to `Unavailable` end-to-end: `resolveDirective` returns `Unknown` for *every* unbundled name when the snapshot is `Unavailable`, which would re-introduce the false-positive problem if the unknown-directive arm warned on `Unknown` blindly. The arm instead checks the snapshot variant directly for the silence-on-`Unavailable` policy, and only treats `Built + Unknown` as a typo. The next section pins this.

---

## First client: the unknown-directive arm in `Diagnostics`

The change at `Diagnostics.java:86-92`:

```java
if (workspace.resolveDirective(directiveName) instanceof DirectiveResolution.Bundled bundled) {
    // existing arg / required / leaf-coordinate validation path
    validateAgainst(bundled.def(), ...);
    continue;
}
if (workspace.snapshot() instanceof LspSchemaSnapshot.Unavailable) continue;  // pre-build silence
if (workspace.resolveDirective(directiveName) instanceof DirectiveResolution.User) continue;
out.add(diagnostic(file, directive.nameNode(), DiagnosticSeverity.Warning,
    "Unknown directive '@" + directiveName + "'. Not declared in any "
    + "directive definition reachable from the parsed schema."));
```

The two `resolveDirective` calls collapse to one in the implementation (read once, switch on the result); the two-call shape above is for spec clarity. The arm reads the snapshot variant directly only for the silence policy, which is the one place "what state is the build in" is the decision rather than "what does the workspace know about this name".

Observable behaviours:

| Snapshot variant               | Bundled has it | Snapshot has it | Outcome                                |
|--------------------------------|----------------|-----------------|----------------------------------------|
| `Unavailable` (pre-build)      | no             | (n/a)           | silence                                |
| `Unavailable` (parse-broken)   | no             | (n/a)           | silence                                |
| `Built`                        | no             | yes             | silence                                |
| `Built`                        | no             | no              | warn (genuine typo, recognised by nobody) |
| any                            | yes            | (n/a)           | proceed to arg validation (current path) |

The bundled-yes path is the existing behaviour; the snapshot extension only narrows the warn arm.

The `SPEC_BUILTIN_DIRECTIVES` set at `Diagnostics.java:68-70` stays in place: spec built-ins do not necessarily appear in user schemas (graphql-java ships them implicitly) and a snapshot built from a user schema that does not redeclare them must not flag them as unknown. The list is closed (five names) and the check is a cheap short-circuit; the more general snapshot mechanism handles everything else.

### Test updates

- `unknownDirectiveProducesWarning` (`DiagnosticsTest.java:586`): the test grows an explicit `LspSchemaSnapshot.Built(List.of())` argument (the post-build state with no user directives), since the typo case requires `Built` for the warning to fire. The `@tabel` typo still warns under `Built` when the snapshot lacks `@tabel`.
- New: `unknownDirectiveSilencedByUnavailableSnapshot` (pre-build state, currently `unknownDirectiveProducesWarning`'s implicit shape): the test passes `LspSchemaSnapshot.unavailable()` and asserts the warning does not fire.
- New: `userDeclaredDirectiveSilencedBySnapshot` (the canonical case in this item's motivation): the test passes `LspSchemaSnapshot.Built(List.of(directiveShape("key", ...)))` and asserts `type Film @key(fields: "id") { ... }` produces no diagnostics.
- New: `userDeclaredDirectiveShadowedByBundledStillValidates` (the collision case): the test passes a `Built` snapshot containing `@table` (the user accidentally redeclared); the bundled shape wins, so `@table(name: "missing_table")` still flags `missing_table` as an unknown table per the existing arm.

---

## Implementation sites

The file-by-file diff:

- New file `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/catalog/LspSchemaSnapshot.java`: the sealed interface plus `Unavailable` / `Built` permits and the `DirectiveShape`, `InputValueShape`, `TypeShape` projections (or one file per record under the same package, whichever lands cleaner).
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/catalog/CatalogBuilder.java`: add `buildSnapshot(GraphQLSchema schema, TypeDefinitionRegistry registry)`. Walks `registry.getDirectiveDefinitions().values()`, projects each into `DirectiveShape`, returns `Built(...)` on a clean parse and `Unavailable` on any parse error (per the `snapshot-built-implies-clean-parse` load-bearing key above). Skips graphitron's bundled directives if they appear in the registry: the LSP already has them via `LspVocabulary`.
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphQLRewriteGenerator.java`: surface the `TypeDefinitionRegistry` to the catalog builder (it is already parsed; the diff is to keep the reference past the existing call site rather than discard it).
- `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/state/Workspace.java`: add `volatile LspSchemaSnapshot snapshot` field defaulting to `LspSchemaSnapshot.unavailable()`, `setSnapshot(...)` setter mirroring `setCatalog`, `snapshot()` accessor mirroring `catalog()`, plus the `resolveDirective(String)` union accessor that returns the sealed `DirectiveResolution` shape.
- `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/state/DirectiveResolution.java`: the sealed result type for `resolveDirective`, with `Bundled` / `User` / `Unknown` permits as described in the Boundary section.
- `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/diagnostics/Diagnostics.java`: the unknown-directive arm reads through `workspace.resolveDirective(name)` and checks `workspace.snapshot() instanceof LspSchemaSnapshot.Unavailable` for the pre-build silence policy, as described above. `Diagnostics.compute`'s static shape extends to take the workspace (or its two volatile reads) rather than just `CompletionData`; the test-only callsite gets a free `LspSchemaSnapshot.unavailable()` argument when the test does not care.
- `graphitron-rewrite/graphitron-maven-plugin/src/main/java/no/sikt/graphitron/rewrite/maven/DevMojo.java`: the `regenerate` and `rebuildCatalog` methods at `:176-211` push both projections in lockstep. Either expose a combined `buildBoth(...)` on `GraphQLRewriteGenerator` or call `setSnapshot` immediately after `setCatalog`; the inline-pair shape is the smaller diff and matches how the two volatile refs read independently downstream.

The change is purely additive in the legacy-comparison sense: `LspVocabulary`, `CompletionData`, and the existing `Workspace.setCatalog` paths stay; the new path joins them. No deprecation, no shim.

---

## Tests

Four tiers, structurally identical to the pattern in R92's spec body.

### Unit-tier

- `LspSchemaSnapshotTest`: invariant pinning. `unavailable()` returns an `Unavailable` instance; `Built.directive(name)` is case-sensitive; `Built.directives()` is unmodifiable. `TypeShape` sealed-switch exhaustiveness is `javac`-checked, not test-checked.
- `CatalogBuilderSnapshotTest`: build a snapshot from a hand-crafted `TypeDefinitionRegistry` (via `SchemaParser`), assert directive names round-trip, assert bundled-directive exclusion (a registry containing `@table` does not surface it in the snapshot's `directives` list), assert that a parse error on the input produces `Unavailable` rather than a partial `Built` (the `snapshot-built-implies-clean-parse` invariant from the producer-invariant table above).

### Pipeline-tier

- `DiagnosticsTest` adds the four new cases listed in "Test updates" above. The existing `unknownDirectiveProducesWarning` test grows an explicit `Built(List.of())` snapshot so the typo case keeps firing under realistic post-build conditions.
- The existing `compute(WorkspaceFile, CompletionData)` test fixture grows an `LspSchemaSnapshot` parameter; all current call sites get `LspSchemaSnapshot.unavailable()`, except the unknown-directive cases that exercise the new arm.

### Compilation-tier

- `graphitron-sakila-example` adds a fixture `schema.graphqls` carrying one user-declared directive (e.g. `directive @auth(role: String!) on FIELD_DEFINITION` plus a single field application). The `mvn compile -pl :graphitron-sakila-example -Plocal-db` pass exercises that the snapshot includes the user directive end-to-end; the fixture asserts the build does not regress in the presence of user-authored directive declarations the bundled SDL knows nothing about.

### Execution-tier

Not applicable: the LSP's behaviour is observable in the snapshot itself (pipeline tier) and in the diagnostics output (pipeline tier). There is no runtime path exercised by this change.

---

## Phasing

Two independent landings.

### Phase 1: snapshot plumbing plus unknown-directive client

Everything described above. The snapshot ships with `Built.directives` populated (each directive carrying its full `DirectiveShape` including args and descriptions) so phase 2's hover and arg-validation surfaces consume the same projection without a version migration. Only the unknown-directive arm reads it in phase 1. Pipeline tests for the four new cases under `DiagnosticsTest`. Compilation-tier sakila fixture demonstrates end-to-end pickup.

Acceptance: user schemas carrying federation directives, `@auth`-style guards, or any other user-declared directive produce no LSP warnings when the build pipeline has run; the typo case (`@tabel`) still warns in the post-build state; the pre-build state silences everything.

### Phase 2: hovers, completion, arg validation against the snapshot

`Hovers` extends to read snapshot directive descriptions via `Workspace.resolveDirective(...)`. `Diagnostics`' unknown-arg and required-arg validators (`Diagnostics.java:113-181`) consult snapshot directive shapes when the bundled SDL does not declare the directive. `ArgNameCompletions` surfaces user-directive arg names alongside bundled ones.

Phase 2 ships as its own roadmap item once phase 1 lands. The split exists because the structural plumbing is the conservative landing: it changes one diagnostic arm and a setter, no new completion / hover surface. Phase 2's user-visible surface is bigger and is best scoped against an in-tree phase 1 (so reviewers can see the snapshot's actual shape, not the spec's projection of it).

---

## Open question for the reviewer

*Should `LspSchemaSnapshot.Unavailable` split into `NoBuildYet` and `ParseFailed` permits in phase 1?* The first draft of this spec held both under one `Unavailable` arm on the grounds that phase 1 consumers do not need to distinguish them. Architect-review surfaced the parallel question for `fromSuccessfulBuild`-the-boolean, which is now resolved (sealed wins), but the analogous sub-split question for `Unavailable` is genuinely open: phase 2's hover surface might want to surface "the schema has a parse error at line N, hover unavailable" differently from "the build pipeline has not run yet". Spec-author preference is to defer the split until phase 2's first hover client lands and the distinction has a behavioural consumer, but a reviewer can argue the seal is cheaper to ship now than to retrofit later.

---

## Settled design notes

These were called out during the design conversation; each is resolved here so the implementer does not relitigate.

1. *Projection, not raw registry.* The LSP module never imports `graphql.schema.idl.TypeDefinitionRegistry` through the side-channel. The catalog precedent (`CompletionData` does not re-export `JooqCatalog`) and *Wire-format encoding is a boundary concern* both point the same direction. The cost is one extra record per directive at projection time; the benefit is the LSP's contract surface stays narrow and test fixtures stay cheap. `InputValueShape.type` is sealed (`TypeShape.Named | TypeShape.List`) rather than rendered SDL string, so phase 2's arg-validation consumers do not parse strings back.
2. *Bundled-SDL and snapshot have distinct lifecycles; do not merge them.* `LspVocabulary`'s structural invariant (every overlay coordinate resolves) is only meaningful against the bundled SDL. Composing the snapshot into the vocabulary's registry would either break that invariant (user directives could falsely satisfy overlay coordinates) or force the invariant check to discriminate, which is more complexity than the union-accessor on `Workspace` carries.
3. *Tree-sitter stays the primary parse.* The snapshot is project-wide and built-on-success; tree-sitter is per-buffer and instant. Replacing tree-sitter with the snapshot would lose buffer-instant feedback on every edit. The two coexist for the same reason the catalog coexists with `LspVocabulary`: different lifecycles, different consumers.
4. *`Unavailable` snapshot must silence, not warn.* The current behaviour warns on every user directive; the natural reaction to "silence on `Unavailable`" is "but then a typo pre-build goes unflagged". That regression is acceptable: the typo signal is small, the false-positive cost on every user-directive use is large, and the post-build state (when the snapshot transitions to `Built`) restores the typo signal. Pre-build also already silences class-name warnings (`Diagnostics.java:325`); the snapshot rule is the same shape.
5. *Bundled directives shadow snapshot directives, via `resolveDirective`.* The collision case (user redeclares `@table`) is rare but real. Graphitron's overlay must win because the LSP's overlay-driven behaviour (catalog-table binding on `@table(name:)`) is keyed to graphitron's shape. The precedence is encoded once, on `Workspace.resolveDirective`'s sealed `DirectiveResolution` return; consumers switch on the result, never re-check the precedence inline. A future enhancement can warn the user about the shadow; out of scope here.

---

## Future evolution (out of scope)

- *Hover and completion on user directives* (phase 2 above). The plumbing R139 ships is sufficient; the follow-on item is purely additive in `Hovers` / `ArgNameCompletions` / `Diagnostics`' unknown-arg arm.
- *Shadow-warning for user directives that redeclare bundled names.* A separate diagnostic on the bundled-vs-snapshot collision case. Cheap once the snapshot ships; deferred because it is a distinct user-visible behaviour, not a consequence of the plumbing.
- *Add a `declaredTypeNames` set to the `Built` arm.* Useful for type-name completion against user-declared types (including synthesised ones like `FilmConnection`, error-handler input shapes that the build pipeline produces). The build pipeline knows them; the snapshot can carry them. Deferred until phase 2 lands a type-name-completion consumer in the same commit. The seal makes additive widening cheap: a new field on `Built`, not a new permit, not a snapshot-version migration.
- *Lift the snapshot into `LspVocabulary` after a second consumer lands.* If three or more consumers all want the bundled-plus-snapshot union, the indirection through `Workspace.resolveDirective` starts to feel like the wrong seam. Revisit then; until then, the two lifecycles' separation is the load-bearing constraint.
- *Server-mode LSP (no dev mojo).* The LSP today is only usable under `mvn graphitron-rewrite:dev`; a future standalone server would need a different snapshot source (probably parsing the schema directly on the LSP's own classpath). The contract surface (`Workspace.setSnapshot`) does not change; only the producer differs.

---

## Non-goals

- *Replacing the tree-sitter parse.* See settled design note 3.
- *Validating user directives' arg shapes against their declarations.* That is phase 2; phase 1 only silences the unknown-directive warn arm.
- *A pluggable snapshot producer.* The snapshot ships from `CatalogBuilder` alone; consumers cannot inject their own. Symmetric with the catalog.
- *Real-time pickup of user-directive edits via tree-sitter.* The snapshot updates only when the build pipeline runs (debounced via the existing `SchemaWatcher`). A directive declared in an unsaved buffer is invisible to the snapshot until the buffer is saved and the pipeline re-runs. This is the same trade-off the catalog already makes for jOOQ table edits, and it is acceptable here for the same reason: schema changes that introduce new directives are rare, and the warn-on-bundled-typo case (the only signal phase 1 keeps) still fires off tree-sitter regardless.
