---
id: R160
title: LSP inlay hints and hover for inferred directives and field/type classification
status: In Progress
bucket: feature
theme: lsp
depends-on: []
created: 2026-05-13
last-updated: 2026-05-20
---

# LSP inlay hints and hover for inferred directives and field/type classification

Graphitron interprets SDL through two layers the user never sees: inference fills in directive arguments the author omitted (`@table(name:)` from the type name, `@field(name:)` from the field name, `@reference(path:)` from a unique single-hop FK), and classification assigns each type and field to a sealed variant in `GraphitronType` / `GraphitronField` that fully determines how the generator will emit code. Both layers run silently at build time. A schema author has to mentally re-run inference to know what column a field will hit; anyone debugging "why did graphitron emit X for this field?" has to read the classifier or the generator to find the variant assignment that drove the decision. The LSP already receives `BuildArtifacts(catalog, snapshot)` on every successful generator pass via `Workspace.setBuildOutput`; the same plumbing can surface both layers to the editor.

## Goals

1. An LSP **inlay-hint provider**, gated by editor-side config, emitting two categories of hints:
   1. **Inferred-directive hints** at `@table` / `@field` / `@reference` sites where the author omitted the canonical argument: the hint shows the resolved value (`name: "customer"`, `path: [{table: "address", key: "address_city_id_fk"}]`) as a ghost annotation.
   2. **Classification hints** at every field declaration and every object/interface/input/union type declaration: a compact user-facing label naming the classified variant (e.g. `joined column`, `query field`, `node type`).
2. **Rich classification hover** on the same coordinates: where the inlay hint shows a compact label, the hover popup unpacks the variant's load-bearing payload (table, column, FK path, compaction mode, target type, error channel, etc.) as markdown.
3. Both inlay-hint categories and the classification-hover arm gated by separate editor-side config keys, fetched via `workspace/configuration` and refreshed on `workspace/didChangeConfiguration`. Default off for both (no behaviour change for users who don't opt in).

## Non-goals

- Inlay hints for inferred arguments on directives other than `@table`, `@field`, `@reference`. Other inferred arguments (`@nodeId(typeName:)`, `@reference(key:)` inference, future cases) are explicit follow-ups; the projection shape this item lands should accommodate them, but the first pass renders the three.
- Inlay hints for the inferred *return* shape of root fetchers, the inferred join key on `@nestingField`, or any other "inference" beyond directive-argument defaulting. Different audience, different mechanism.
- Editor-side UI to toggle the hints. The LSP exposes config keys; the editor (or the user's `settings.json`) flips them. No graphitron-shipped editor extension in this item.
- Inlay hints reflecting unsaved buffer state without a successful generator pass. Hints derive from the snapshot, full stop; pre-build (`Unavailable`) means no hints.

## Architectural shape

R139 established the data-flow precedent: the LSP consumes **projections** of the post-classification model carried on `LspSchemaSnapshot.Built`, not the model itself. `TypeBackingShape` is the worked example: a sealed family purpose-built for the LSP's `@field(name:)` arm, projected from `GraphitronType` by `CatalogBuilder.projectType`. This item follows the same pattern: rather than threading `GraphitronSchema` through `BuildArtifacts`, extend `LspSchemaSnapshot.Built` with two new projection axes, and have the inlay-hint and hover arms dispatch on those.

There is one shape change in the model layer that this item depends on, lifted up out of the projector: a pair of carrier-shaped provenance axes (`NameProvenance` on `TableRef` and `ColumnRef`; `PathProvenance` on the `@reference(path:)`-consuming `ChildField` permits). The rationale lives in the next section; without these axes the projector would re-evaluate the "was the author argument present?" predicate by re-reading the raw `GraphQLDirective` against the classified model, reaching back across the parse boundary that classification exists to close.

### Provenance lifted onto the model

The alternative for the LSP arm is to re-evaluate "was the author argument present?" at projection time by reading the raw `GraphQLDirective` against the classified model. That route reaches back across the parse boundary that classification exists to close (per "Classification belongs at the parse boundary"): the LSP module is downstream of classification, and pulling `GraphQLDirective` into the projector reintroduces the dependency the snapshot's pre-resolved projections are designed to eliminate. The classifier already has both the raw directive arguments and the resolution rule in one place; it records the resolved name on `TableRef.tableName` / `ColumnRef.sqlName` today and discards the directive-vs-SDL provenance. Lifting the answer onto the model carries the boundary forward to the LSP and incidentally enables future consumers (a code action that inlines/extracts an inferred name, a build warning when a written `name:` matches the inference) for free.

Two carrier-shaped sealed families, not one. A unified `Provenance` would let `FromUniqueFk` be statically permitted on `TableRef` and `ColumnRef`, where no inference rule can ever produce it; the type system would accept values the classifier can never construct. Splitting by carrier shape keeps the certainty the classifier already has:

```
sealed interface NameProvenance {                                     // axis on @table(name:) / @field(name:) carriers
    record Authored() implements NameProvenance {}                    // user wrote @table(name: "X") / @field(name: "x")
    sealed interface Inferred extends NameProvenance permits FromSdlName {}
    record FromSdlName() implements Inferred {}                       // @table on TypeX -> "typex"; @field on fieldX -> "fieldx"
}

sealed interface PathProvenance {                                     // axis on @reference(path:) carriers
    record Authored() implements PathProvenance {}                    // user wrote @reference(path: [...])
    sealed interface Inferred extends PathProvenance permits FromUniqueFk {}
    record FromUniqueFk(String fkName) implements Inferred {}         // path inferred from the only FK between two tables
}
// Each family's Inferred permits-clause widens independently as inference rules grow
// (e.g. a future @nodeId(typeName:) rule lands on NameProvenance, not PathProvenance).
```

Three model-shape additions in C1:

- `TableRef` gains a `NameProvenance` field. Populated by the classifier; today the only inference rule is "table name from SDL type name" (`FromSdlName`), but the sealed family accommodates future rules without revisiting consumer sites.
- `ColumnRef` gains a `NameProvenance` field. The `@field(name:)` case follows the same shape: written → `Authored`, omitted → `FromSdlName`.
- The `@reference(path:)`-consuming permits (today `ChildField.ColumnReferenceField`, `ChildField.CompositeColumnReferenceField`, `ChildField.ParticipantColumnReferenceField`, and the equivalent input-side permits) gain a `PathProvenance` as a sibling record component alongside the existing `joinPath` (mirroring how `TableRef` and `ColumnRef` gain a sibling field; no new path-wrapper record). The single-hop inference path through `BuildContext.parsePath` is the only `Inferred` arm at filing; the multi-hop path is always `Authored`. The construction-site signal: at each `ctx.parsePath(...)` call site (in `FieldBuilder`, `NodeIdLeafResolver`, `TypeBuilder`, and `SourceRowDirectiveResolver`), check `container.getAppliedDirective(DIR_REFERENCE)` for path-argument presence (`appliedDirective != null && appliedDirective.getArgument(ARG_PATH) != null` ⇒ authored; otherwise inferred-eligible) before/around the call, the same shape `BuildContext.parsePath` itself uses at the directive-arg lookup at the head of the method; when the path argument is absent and `parsePath` returned a non-empty `elements` list, the synthesized single-hop step is at `parsed.elements().get(0)` as a `JoinStep.FkJoin` whose `fkName()` (or equivalent accessor) populates `PathProvenance.Inferred.FromUniqueFk.fkName`. When the path argument is present, `PathProvenance.Authored`. When `parsePath` errored, the containing field is already routed to `UnclassifiedField` and no `PathProvenance` is constructed (the permit isn't either).

Adding `NameProvenance` / `PathProvenance` to these carriers does not change behaviour at any existing consumer ; they read the resolved name without caring about the axis. The compiler enforces the new field at construction time, so the classifier-side population is a small mechanical addition the C1 step lands in lock-step with the model edit.

### Projection 1: `InferredDirectiveBindings`

Carried on `LspSchemaSnapshot.Built` alongside `directives` and `typesByName`. Records keyed by SDL coordinate, populated only when the corresponding model carrier's provenance axis is `Inferred`. The producer reads the model bit directly:

```
record InferredDirectiveBindings(
    Map<String, String> tableNameByTypeName,
    Map<FieldCoordinates, String> fieldNameByCoord,
    Map<FieldCoordinates, InferredReferencePath> referencePathByCoord
)

sealed interface InferredReferencePath {
    record SingleHop(String fkName, String targetTable, boolean inverse) implements InferredReferencePath {}
    // ... permits widen if/when the inference rule set widens
}
```

Coordinate keys use graphql-java's `graphql.schema.FieldCoordinates` (the existing key on `GraphitronSchema.fields`), not a new type. Population happens in a new `CatalogBuilder.projectInferredBindings` walking `bundle.model()`, parallel to `projectTypesByName`. The "author omitted" check is no longer at the projector ; the model already carries the answer; the projector just reads `provenance instanceof NameProvenance.Inferred` (for `@table` / `@field` sites) or `instanceof PathProvenance.Inferred` (for `@reference` sites) and copies the resolved name.

Honesty about freshness: the projection lives on `LspSchemaSnapshot.Built`, so a `Built.Previous` snapshot means stale inferences. The inlay-hint provider does not gate on freshness (`Current` vs `Previous`); per principle the LSP prefers stale info over silence, mirroring how `userArgHover` and `columnHover` already behave. `Unavailable` → no hints.

### Projection 2: `FieldClassification` and `TypeClassification`

Sealed projection-families analogous to `TypeBackingShape`. The projection records are sized to **distinct hover-payload shapes**, not 1:1 with the generator-side permits. Permits that differ only in a label dimension ; DML verb (insert / update / delete / upsert), single/list multiplicity on `Query[Node|Nodes]Field` ; collapse onto one LSP record carrying that dimension as a discriminator field; permits whose hover-relevant payload genuinely diverges (split axis on `SplitTableField`, lookup key on `LookupTableField`, error-channel name on a service mutation) get their own record. Each record carries only LSP-renderable payload (table names, column names, FK names, target type names, error-channel names ; primitives, strings, and enums, never `TableRef` / `ColumnRef` / `graphql-java` types). No `displayLabel()` on the records; label rendering is an LSP-module switch (see "Label rendering" below).

The exhaustive switch on the generator-side `GraphitronField` permits in `CatalogBuilder.projectFieldClassification` enforces **coverage** (a new permit fails the switch to compile until mapped). What the projection collapses is the *LSP-side cardinality*, not the generator-side coverage; the label switch in `LspClassificationLabels` still dispatches over the full generator-side permit set. The "Sealed hierarchies over enums for typed information" principle pushes back on a 1:1 mirror with duplicate-shape records: each variant should carry exactly the fields it needs, not the union of fields its siblings need.

Worked example: the four `MutationField.DmlTableField` permits (`MutationInsertTableField` / `MutationUpdateTableField` / `MutationDeleteTableField` / `MutationUpsertTableField`) share `(tableName, inputTypeName?, errorChannelName?, dmlKind)`; one LSP record `DmlMutation` with a `DmlKind` discriminator captures the hover content, and `LspClassificationLabels` still emits "insert mutation" / "update mutation" / etc. via its own exhaustive switch on the generator-side permit. Counter-example: `ChildField.SplitTableField`'s split-axis is hover-distinct from `ChildField.TableField`'s plain table-bound payload, so the two stay separate LSP records.

The exact grouping is the C3 implementation pass against the generator-side permits enumerated in the "Label vocabulary" tables below; the principle-aligned default is to collapse where collapsing does not erase a load-bearing distinction.

```
sealed interface FieldClassification
    permits FieldClassification.ColumnField,
            FieldClassification.ColumnReferenceField,
            // ... payload-distinct leaves; the C3 grouping pass settles the exact set.
            FieldClassification.DmlMutation,                    // covers Insert/Update/Delete/Upsert via DmlKind discriminator
            FieldClassification.QueryNode,                      // covers QueryNodeField/QueryNodesField via boolean isList
            FieldClassification.Unclassified {

    record ColumnField(String tableName, String columnName) implements FieldClassification {}
    record ColumnReferenceField(String tableName, String columnName, String fkName, boolean fkInverse) implements FieldClassification {}
    record DmlMutation(String tableName, String inputTypeName, String errorChannelName, DmlKind kind) implements FieldClassification {}
    record QueryNode(String nodeTypeName, boolean isList) implements FieldClassification {}
    // ... per-record payload = the load-bearing fields the hover renders.
}
```

`TypeClassification` follows the same discipline: payload-distinct records over the `GraphitronType` permits. The type-side payload divergence is denser than the field side (record-backed / pojo-backed / jOOQ-table-backed / etc. all carry different load-bearing payload), so most type permits keep their own LSP record; the C3 pass identifies any genuine groupings.

### Label rendering lives in the LSP module

Label strings (`"column"`, `"joined column"`, `"node type"`, ...) are NOT on the projection record. They live in the LSP module as an exhaustive switch in `InlayHints` (or a sibling `LspClassificationLabels` utility class). This keeps presentation a sibling concern: the projection record carries the load-bearing identity-plus-payload, the LSP module chooses how to render it. The precedent `TypeBackingShape` followed is exactly this: `MemberSlot` carries `displayType` because the type-display string IS the load-bearing payload the hover renders, but it does not carry "this is a record component" as a label because that's already encoded in the permit identity (`RecordBacking` vs `PojoBacking`).

### Validator-mirrors-classifier alignment

`FieldClassification` is an LSP-display projection, not a generator branch; the "Validator mirrors classifier invariants" principle does not bite here in its primary form (no validate-time rejection is required). The discipline at play is "Sealed hierarchies over enums for typed information": the projector's exhaustive switch on `GraphitronField` permits (and similarly on `GraphitronType`) trips a compile error when a new permit lands without an LSP-side projection. That is the contract that keeps "new generator-side variant → LSP-side coverage in the same commit" honest.

Concretely: `CatalogBuilder.projectFieldClassification` is a `switch` expression over `GraphitronField` whose arms cover the full permits list. Adding a new permit to `ChildField` fails the projector to compile until the LSP-side mapping is added in the same commit ; whether that mapping is a new payload-distinct LSP record or an arm that returns a shared record with a different discriminator value is a per-permit judgment, but exhaustiveness is what fails to compile. No new validator hook in `GraphitronSchemaValidator` is needed.

### `BuildArtifacts` does not change shape

`BuildArtifacts` stays `(CompletionData catalog, LspSchemaSnapshot.Built.Current snapshot)`. The new projections live inside `LspSchemaSnapshot.Built` as additional record fields, with `Current` and `Previous` permits carrying them symmetrically. `Workspace.setBuildOutput` does not change; the LSP just consumes the new fields off `snapshot`.

### Load-bearing classifier check keys

The producer/consumer annotation pair (`@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck`) carries the binding contract for the new flow. Three new keys land with this item:

| Key | Producer | Consumers |
|---|---|---|
| `provenance-axis-faithful` | classifier (the `TableRef` / `ColumnRef` / `@reference`-permit constructors) | `CatalogBuilder.projectInferredBindings`; `InlayHints` inferred-directive arm |
| `field-classification-payload-faithful` | `CatalogBuilder.projectFieldClassification` | `InlayHints` classification arm; classification-hover arm |
| `type-classification-payload-faithful` | `CatalogBuilder.projectTypeClassification` | `InlayHints` classification arm; classification-hover arm |

The cross-module-audit caveat that R139 already documented applies: until the audit scope widens to include `graphitron-lsp`, the annotations are find-usages markers rather than build-tier audit failures on orphaned consumers. The pair stays useful at code-review time and as the navigation aid `LoadBearingGuaranteeAuditTest` exists for.

## Inlay-hint provider mechanics

A new `InlayHints` class under `graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/inlay/` mirroring `Hovers` and `Diagnostics` in shape. Entry point:

```
public static List<InlayHint> compute(
    InlayHintConfig config, LspVocabulary vocabulary,
    WorkspaceFile file, LspSchemaSnapshot snapshot, Range visibleRange
)
```

Returns the list of `InlayHint`s in `visibleRange` (lsp4j supports range-scoped requests; the editor only fetches hints for the visible viewport). For each directive site under the cursor's range, the provider:

1. Resolves the SDL coordinate via the existing `LspVocabulary.coordinateAt` / `TypeContext.enclosingTypeDefinition` infrastructure.
2. For each enabled category, looks up the projected binding/classification on the snapshot.
3. Synthesises an `InlayHint` with the appropriate label, kind (`Type` for both categories, no custom kind), and tooltip (markdown matching the hover content).

`GraphitronLanguageServer.initialize` adds `capabilities.setInlayHintProvider(new InlayHintRegistrationOptions(...))`. `GraphitronTextDocumentService` wires `inlayHint(InlayHintParams)`.

### Tree-sitter cursor for "was `name:` written"

Not actually needed in the runtime path: the model's `NameProvenance` / `PathProvenance` axes answer the question at classification time, the projector copies the answer onto `InferredDirectiveBindings`, and the LSP consumer reads the projection and renders. The tree-sitter cursor is still used for *positioning* the hint (where to anchor the ghost annotation in the buffer text), but not for the inference-vs-stated decision. That keeps the LSP arm purely a renderer of a pre-resolved fact and respects "if two consumers would evaluate the same predicate, the branch belongs in the model".

## Config keys and toggles

Three boolean config keys, all under the `graphitron.inlayHints` namespace, all default `false`:

| Key | Effect |
|-----|--------|
| `graphitron.inlayHints.inferredDirectives` | Enables inferred-directive inlay hints at `@table` / `@field` / `@reference` sites. |
| `graphitron.inlayHints.classification` | Enables classification inlay hints on fields and type declarations. |
| `graphitron.hover.classification` | Enables classification-rich hover content on coordinates not already served by an existing `Hovers` arm. |

Three keys, not one. Reasoning: the audiences are distinct (schema author vs. graphitron developer), the visual density is very different (every field gets a classification hint, only a subset gets an inferred-directive hint), and editor-side users who want one but not the other should be able to express that. The cost of three booleans is trivial compared to forcing a single-toggle user to either flood their editor or get nothing.

Config is fetched from the client via `workspace/configuration` on initialisation and refreshed on `workspace/didChangeConfiguration`. The current state lives on `Workspace` as a `volatile InlayHintConfig`, swapped in the same shape as the catalog and snapshot. Failure path (client does not support `workspace/configuration`, common in early-stage LSP clients): defaults stay in effect (off), no hints emitted. No error surfacing required, this is the expected fall-back.

## Stale-snapshot behaviour

Mirroring the validator and the existing hover arms: hints render under `Built.Current` and `Built.Previous` indistinguishably (no stale marker in the hint text). The bet is that the same conservative principle that already gates `userArgHover` and `columnHover` ("prefer stale info over silence") applies here. The risk: a user mid-edit sees a stale inferred name and trusts it. The mitigation is the same one those hover arms accept: the next successful generator pass replaces stale with current, and the gap is bounded by the dev-pipeline's regenerate cadence (typically a save away).

Under `Unavailable`: no hints. No stale-marker indication; the editor simply does not show the inlay-hint kind.

## Implementation plan

1. **C1 ; `NameProvenance` and `PathProvenance` axes on model carriers.** Add the `NameProvenance` and `PathProvenance` sealed families under `no.sikt.graphitron.rewrite.model`; add a `NameProvenance` field to `TableRef` and `ColumnRef`, and a `PathProvenance` field to each of the `@reference(path:)`-consuming `ChildField` permits (`ColumnReferenceField`, `CompositeColumnReferenceField`, `ParticipantColumnReferenceField`, and the input-side equivalents under `InputField`) as a sibling record component alongside the existing fields. Populate from the existing classifier paths: `TableRef` construction in `BuildContext` / `TypeBuilder` reads the raw directive-arg presence on `@table(name:)`; `ColumnRef` likewise on `@field(name:)`. For the `@reference`-permit constructors, the same directive-arg-presence check applies: at each `ctx.parsePath(...)` call site, sample `directive.getArgument(ARG_PATH) == null` *before* the call, then on the inferred branch (path arg absent, `parsePath` returned non-empty elements) read the FK name from the synthesized single-hop step at `parsed.elements().get(0)` (a `JoinStep.FkJoin`) into `PathProvenance.Inferred.FromUniqueFk.fkName`. Read-the-directive-state-at-the-call-site, not `parsed.elements().isEmpty()`: the latter signals the error branch in `parsePath` (no synthesized step), not the successful inference branch (where the synthesized `FkJoin` has been appended to `resolvedElements`). Wear the `provenance-axis-faithful` `@LoadBearingClassifierCheck` at each producer site. No behaviour change at any existing consumer (they keep reading the resolved name).
2. **C2 ; new projection types in the catalog package.** Land `InferredDirectiveBindings` and the sealed `FieldClassification` / `TypeClassification` families under `no.sikt.graphitron.rewrite.catalog`. Initially unwired. Unit-tier test asserts the sealed structure compiles and each permit's payload fields are reachable.
3. **C3 ; producer side in `CatalogBuilder`.** Three new methods: `projectInferredBindings(GraphitronSchema)`, `projectFieldClassification(GraphitronField)`, `projectTypeClassification(GraphitronType)`. Exhaustive switches on the generator-side permits. Wear the two `*-classification-payload-faithful` `@LoadBearingClassifierCheck` annotations.
4. **C4 ; `LspSchemaSnapshot.Built` extension.** Add the three new fields (`inferredBindings`, `fieldClassificationsByCoord`, `typeClassificationsByName`) alongside the existing `directives`, `typesByName`, and `payloadDataFieldByType` carriers, update `Built.Current` and `Built.Previous` symmetrically, update `CatalogBuilder.buildSnapshot` overloads (the one-arg overload returns empty projections, matching the existing `typesByName == Map.of()` behaviour).
5. **C5 ; `InlayHintConfig` and config pull.** Record holding the three booleans, defaults all `false`. Workspace gets a `volatile InlayHintConfig config` and a `setConfig` swap path. `GraphitronTextDocumentService` requests `workspace/configuration` on initialisation; `didChangeConfiguration` calls `setConfig`.
6. **C6 ; `InlayHints` provider plus label switch.** Two arms (inferred-directives, classification), gated by config booleans, dispatching on the projections, returning `List<InlayHint>` scoped to the requested range. Label rendering is an exhaustive switch in `LspClassificationLabels` (sibling utility class to `InlayHints`). Hover-side label reuse from the same switch. `GraphitronLanguageServer.initialize` advertises the capability; `GraphitronTextDocumentService.inlayHint` wires the request. Each LSP arm wears the matching `@DependsOnClassifierCheck`.
7. **C7 ; classification hover.** Hover at field-declaration and type-declaration coordinates ; not directive-arg coordinates, so the existing `LspVocabulary.coordinateAt` / `Behavior` infrastructure does not extend straightforwardly. Design choice: introduce a parallel hover dispatch keyed on SDL declaration positions (alongside the existing `Behavior`-keyed directive-arg dispatch), not a new `Behavior` arm. `Behavior`'s permits are uniformly directive-argument-binding shapes (every existing arm ; `CatalogTableBinding`, `CatalogColumnBinding`, `NodeTypeBinding`, etc. ; names a directive-argument binding); adding a field-declaration or type-declaration arm would widen the family in a carrier-shape direction, which is the parallel-axis smell "Capability vs. sealed-switch confusion" warns about. The parallel dispatch keeps `Behavior` tight and gives the new hover positions a sibling resolver to grow into. Renders the `FieldClassification` / `TypeClassification` payload as markdown, reusing `LspClassificationLabels` for the heading.
8. **C8 ; docs and config schema.** A new section in `manual/lsp.adoc` (or extension to an existing LSP doc) documents the three config keys, their effect, the stale-snapshot semantics, and the user-visible label vocabulary. The LSP advertises the keys via its initialisation handshake.

C1–C6 ship the first user-visible value (inferred-directive hints + classification inlay labels). C7 layers on rich hover. C8 is the docs handoff. Split-out option: if the parallel hover dispatch in C7 grows scope materially during In Progress (the SDL-declaration coordinate resolver is greenfield), the implementer flags this and we file C7 as a sibling roadmap item rather than holding the rest of the work.

## Test plan

Per the rewrite-design-principles test-tier guide.

**Pipeline-tier truth tables, co-located with the classifier truth tables.** `GraphitronSchemaBuilderTest` already organises classifier coverage as `// ===== <VariantName> =====` blocks (one block per `ChildField` / `RootField` / `MutationField` / `InputField` permit). C3 extends each of those blocks with a sibling assertion that runs the classified field through `CatalogBuilder.projectFieldClassification` and asserts the projected permit and payload. Same for type variants against `projectTypeClassification`. Co-location prevents the parallel-table drift the architect flagged: a future contributor adding a new `ChildField` permit cannot pass review without populating both the classifier-row and the projection-row in the same block.

**Pipeline-tier provenance assertions.** Three new sections in `GraphitronSchemaBuilderTest` (or sibling subsections under the existing blocks), one per inference-touched carrier (`TableRef`, `ColumnRef`, `@reference`-path). Each carries paired SDL fixtures: author wrote the argument → `NameProvenance.Authored` / `PathProvenance.Authored`; author omitted it → `NameProvenance.Inferred.FromSdlName` (for `TableRef` / `ColumnRef`) or `PathProvenance.Inferred.FromUniqueFk` (for the `@reference`-permits). The same fixtures double as input for the inferred-directive projection assertions.

**LSP-tier unit tests** (`graphitron-rewrite/graphitron-lsp/src/test/java/.../inlay/InlayHintsTest.java`): given a fixed `LspSchemaSnapshot.Built.Current` and a `InlayHintConfig`, assert the right hints fire at the right ranges with the right text. Config off → no hints. Config on, projection empty → no hints. Config on, projection populated → hints in the expected order with the labels `LspClassificationLabels` emits. One test per LSP-side permit isn't required at this tier; the truth-table coverage at the pipeline tier already pins the projection identity, so the LSP-tier tests focus on the inlay-arm's dispatch logic (correct ranges, correct config-gating, correct stale-snapshot behaviour) rather than enumerating every permit.

**LSP-tier round-trip test** for stale-snapshot behaviour: build a snapshot, demote to `Built.Previous`, assert hints still render under both flags. Mirrors the existing `userArgHover` / `columnHover` stale-snapshot precedent.

**No code-string assertions** against the generated output; classification hints are LSP-display, not generation, so the test signal is "the LSP returns the hint" not "the generator emits a string". This sidesteps the body-assertion ban.

## Open questions, settled

- **One toggle or per-category toggles?** Per-category (three keys: inferred-directives inlay, classification inlay, classification hover). Different audiences, different visual density. See "Config keys and toggles".
- **Simple class name as label or friendly label?** Friendly label. The label vocabulary is owned by the LSP module ; `LspClassificationLabels` is an exhaustive switch in the LSP module ; not by the generator-side type names. Generator-side renames are free; user-visible names are deliberate.
- **Stale-snapshot behaviour?** Render unchanged from `Current` (no stale marker). Mirrors existing hover arms. `Unavailable` is no hints.
- **Should rich-hover classification split out?** Stay together. C7 is one step in the implementation plan. If the SDL-declaration coordinate resolver in C7 grows scope materially during In Progress, the implementer files a sibling item then.
- **Lift `Provenance` into the model, or re-derive at projection time?** Lift. Re-deriving at projection time would require importing `GraphQLDirective` into the LSP projector, reaching back across the parse boundary that classification exists to close (per "Classification belongs at the parse boundary"). The lift carries the boundary forward; future code-actions / build-warnings get the information for free.
- **One unified `Provenance` family, or one per carrier shape?** Two families: `NameProvenance` on `TableRef` / `ColumnRef`, `PathProvenance` on the `@reference`-permits. A unified family with `FromUniqueFk` permitted on all carriers would let the type system accept values the classifier can never construct (a `TableRef` carrying a non-existent inference rule); two families keep the certainty the classifier already has. Per-family `Inferred` permits-clauses widen independently as further inference rules join.
- **`FieldClassification` cardinality (1:1 with generator-side permits, or payload-distinct)?** Payload-distinct, with discriminator fields collapsing permits that differ only in a label dimension (DML verb, single/list multiplicity). The projector's exhaustive switch over the generator-side permits keeps coverage compile-checked; the LSP-side records carry one distinct hover-payload shape each. Worked example: the four DML mutation permits collapse to one `DmlMutation(tableName, inputTypeName, errorChannelName, DmlKind)` record; the label switch in `LspClassificationLabels` still emits a per-permit label via its own exhaustive switch on the generator-side permit. "Sealed hierarchies over enums for typed information" pushes back on a 1:1 mirror with duplicate-shape records.
- **C7 `Behavior`-extension vs. parallel hover dispatch?** Parallel hover dispatch (settled at Spec time). `Behavior`'s permits are uniformly directive-argument-binding shapes; the new hover positions are SDL declaration coordinates, not directive-arg coordinates, so they get their own resolver rather than widening `Behavior` in a carrier-shape direction.

## Label vocabulary

LSP-side classification labels rendered by `LspClassificationLabels`. **Labels are 1:1 with the generator-side permits** (each row in this table maps to one `case` in the label switch); the **LSP-side projection records** (see "Projection 2") may collapse multiple permits onto one record where the hover-payload shape is the same. The two switches dispatch independently: the projector picks the record-with-discriminator, the label switch picks the label, both exhaustive over the generator-side permits. This table is the LSP-module's label switch laid out as a review artifact; it enforces that every generator-side permit has a chosen label.

Field-side labels (mapped from the `GraphitronField` leaves as enumerated under `ChildField`, `QueryField` (`RootField.QueryField`'s permits), `MutationField`, `InputField`, and `GraphitronField.UnclassifiedField`):

| Generator-side permit | Label |
|---|---|
| `ChildField.ColumnField` | "column" |
| `ChildField.ColumnReferenceField` | "reference column" |
| `ChildField.ParticipantColumnReferenceField` | "discriminated reference column" |
| `ChildField.CompositeColumnField` | "composite column" |
| `ChildField.CompositeColumnReferenceField` | "composite reference column" |
| `ChildField.SingleRecordTableField` | "single record table field" |
| `ChildField.SingleRecordIdFieldFromReturning` | "single record id field (RETURNING)" |
| `ChildField.SingleRecordTableFieldFromReturning` | "single record table field (RETURNING)" |
| `ChildField.TableField` | "table field" |
| `ChildField.SplitTableField` | "split table field" |
| `ChildField.LookupTableField` | "lookup table field" |
| `ChildField.SplitLookupTableField` | "split lookup table field" |
| `ChildField.TableMethodField` | "table method field" |
| `ChildField.RecordTableMethodField` | "record table method field" |
| `ChildField.TableInterfaceField` | "table interface field" |
| `ChildField.InterfaceField` | "interface field" |
| `ChildField.UnionField` | "union field" |
| `ChildField.NestingField` | "nesting field" |
| `ChildField.ConstructorField` | "constructor field" |
| `ChildField.ServiceTableField` | "service table field" |
| `ChildField.ServiceRecordField` | "service record field" |
| `ChildField.RecordTableField` | "record table field" |
| `ChildField.RecordLookupTableField` | "record lookup table field" |
| `ChildField.RecordField` | "record field" |
| `ChildField.ComputedField` | "computed field" |
| `ChildField.PropertyField` | "property field" |
| `ChildField.ErrorsField` | "errors field" |
| `QueryField.QueryTableField` | "query table field" |
| `QueryField.QueryLookupTableField` | "query lookup table field" |
| `QueryField.QueryTableMethodTableField` | "query table-method field" |
| `QueryField.QueryNodeField` | "query node field" |
| `QueryField.QueryNodesField` | "query nodes field" |
| `QueryField.QueryTableInterfaceField` | "query table-interface field" |
| `QueryField.QueryInterfaceField` | "query interface field" |
| `QueryField.QueryUnionField` | "query union field" |
| `QueryField.QueryServiceTableField` | "query service-table field" |
| `QueryField.QueryServiceRecordField` | "query service-record field" |
| `MutationField.MutationInsertTableField` | "insert mutation" |
| `MutationField.MutationUpdateTableField` | "update mutation" |
| `MutationField.MutationDeleteTableField` | "delete mutation" |
| `MutationField.MutationUpsertTableField` | "upsert mutation" |
| `MutationField.MutationServiceTableField` | "service table mutation" |
| `MutationField.MutationServiceRecordField` | "service record mutation" |
| `MutationField.MutationDmlRecordField` | "DML record mutation" |
| `MutationField.MutationBulkDmlRecordField` | "bulk DML record mutation" |
| `InputField.ColumnField` | "input column" |
| `InputField.ColumnReferenceField` | "input reference column" |
| `InputField.CompositeColumnField` | "input composite column" |
| `InputField.CompositeColumnReferenceField` | "input composite reference column" |
| `InputField.NestingField` | "input nesting field" |
| `GraphitronField.UnclassifiedField` | "unclassified ({rejection})" |

Type-side labels (mapped from `GraphitronType` permits):

| Generator-side permit | Label |
|---|---|
| `GraphitronType.TableType` | "table type" |
| `GraphitronType.NodeType` | "node type" |
| `GraphitronType.TableInterfaceType` | "table interface" |
| `GraphitronType.InterfaceType` | "interface" |
| `GraphitronType.UnionType` | "union" |
| `GraphitronType.JavaRecordType` | "java record type" |
| `GraphitronType.JavaRecordInputType` | "java record input" |
| `GraphitronType.JooqRecordType` | "jooq record type" |
| `GraphitronType.JooqRecordInputType` | "jooq record input" |
| `GraphitronType.JooqTableRecordType` | "jooq table-record type" |
| `GraphitronType.JooqTableRecordInputType` | "jooq table-record input" |
| `GraphitronType.PojoResultType.Backed` | "pojo result type" |
| `GraphitronType.PojoResultType.NoBacking` | "unbacked pojo result type" |
| `GraphitronType.PojoInputType` | "pojo input" |
| `GraphitronType.TableInputType` | "table input" |
| `GraphitronType.RootType` | "root ({operation})" |
| `GraphitronType.ConnectionType` | "connection type" |
| `GraphitronType.EdgeType` | "edge type" |
| `GraphitronType.PageInfoType` | "page info" |
| `GraphitronType.ErrorType` | "error type" |
| `GraphitronType.EnumType` | "enum" |
| `GraphitronType.ScalarType` | "scalar" |
| `GraphitronType.PlainObjectType` | "plain object" |
| `GraphitronType.UnclassifiedType` | "unclassified ({rejection})" |

Tables are exhaustive on the current `GraphitronField` and `GraphitronType` permits as of R160's filing (cross-checked against `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ChildField.java`, `RootField.java`, `QueryField.java`, `MutationField.java`, `InputField.java`, and `GraphitronType.java`). The projector's exhaustive switches enforce that new permits added downstream get an LSP-side mapping in the same commit. Labels are the LSP module's call; later refinement of phrasing does not affect the projection.

## References

- R139 `lsp-schema-snapshot-side-channel` ; established the dev-pipeline → LSP projection-via-`LspSchemaSnapshot.Built` data flow this item extends.
- R152 `lsp-nodetype-hover-column-scoping` ; precedent for hover content that consumes snapshot projections.
- R121 `lsp-diagnostic-redundant-splitquery-on-record` ; precedent for an LSP arm that mirrors a build-tier classifier signal.
- Design principles: `graphitron-rewrite/docs/rewrite-design-principles.adoc` ; "Classification belongs at the parse boundary" (the `NameProvenance` / `PathProvenance` lift onto `TableRef`/`ColumnRef`/`@reference`-permits keeps the parse-boundary closed; the LSP projector reads pre-resolved bits, never `GraphQLDirective`), "Sealed hierarchies over enums for typed information" (each LSP-side projection record carries exactly the fields its hover shows; the projector's exhaustive switch over the generator-side permits enforces coverage without forcing a duplicate-shape 1:1 mirror), "Capability vs. sealed-switch confusion" (`Behavior` stays directive-argument-binding shaped; SDL declaration coordinates get a parallel hover dispatch rather than a carrier-shaped widening of `Behavior`), "Model metadata over parallel type systems" (labels live on a sibling switch in the LSP module, not on the projection records), "Classifier guarantees shape emitter assumptions" (the `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` pair documents the three new keys C1–C3 introduce).
