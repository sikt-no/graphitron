---
id: R160
title: LSP inlay hints and hover for inferred directives and field/type classification
status: Spec
bucket: feature
theme: lsp
depends-on: []
created: 2026-05-13
last-updated: 2026-05-13
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

### Projection 1: `InferredDirectiveBindings`

Carried on `LspSchemaSnapshot.Built` alongside `directives` and `typesByName`. Sealed family or simple records, keyed by SDL coordinate, populated only for sites where (a) the directive is on a type/field the classifier resolved AND (b) the author omitted the canonical argument. The record carries the resolved value the LSP renders.

```
record InferredDirectiveBindings(
    Map<String, InferredTableName> tablesByTypeName,       // @table(name:) inferences
    Map<FieldCoordinate, InferredFieldName> fieldsByCoord, // @field(name:) inferences
    Map<FieldCoordinate, InferredReferencePath> referencesByCoord  // @reference(path:) inferences
)

sealed interface InferredReferencePath {
    record SingleHop(String fkName, String targetTable, boolean inverse) implements InferredReferencePath {}
    record MultiHop(List<Hop> hops) implements InferredReferencePath {}
    // ... etc, matching the cardinality of the actual inference outcomes
}
```

Population happens in `CatalogBuilder.buildSnapshot`'s walk over `bundle.model()` (parallel to `projectTypesByName`). The check "was the author argument present in the SDL?" lives at the producer side, comparing the original `GraphQLDirective` arguments against the classified model: if `@table` carries no `name:` and the model's `TableRef` resolved, that's an inferred-table-name entry; ditto for `@field` / `@reference`. The LSP-side inlay-hint provider consumes the entries directly, no tree-sitter cursor walk needed.

Honesty about freshness: `InferredDirectiveBindings` lives on `LspSchemaSnapshot.Built`, so a `Built.Previous` snapshot means stale inferences. The inlay-hint provider does not gate on freshness (`Current` vs `Previous`); per principle the LSP prefers stale info over silence, mirroring how `userArgHover` and `columnHover` already behave. `Unavailable` → no hints.

### Projection 2: `SchemaClassification`

Sealed projection-family analogous to `TypeBackingShape`, but the LSP-stable taxonomy for *classification display* rather than backing-shape dispatch. Two sub-families: `TypeClassification` for object/interface/input/union/enum/scalar types, `FieldClassification` for fields. Each permit carries:

1. A `displayLabel()` returning the user-visible compact name (used by inlay hints).
2. The load-bearing payload the hover renders.

```
sealed interface FieldClassification {
    String displayLabel();  // "joined column", "query field", "service mutation", ...

    record Column(String tableName, String columnName) implements FieldClassification { ... }
    record JoinedColumn(String tableName, String columnName, List<String> fkPath) implements FieldClassification { ... }
    record CompositeReference(String tableName, List<String> columnNames, List<String> fkPath) implements FieldClassification { ... }
    record QueryField(String returnType /* etc */) implements FieldClassification { ... }
    record ServiceMutation(String className, String methodName) implements FieldClassification { ... }
    record InputField(String parentBacking, String displayType) implements FieldClassification { ... }
    record Unclassified(String reason) implements FieldClassification { ... }
    // ... one permit per user-visible classification (NOT one per GraphitronField permit;
    //     see "Classification taxonomy mapping" below)
}

sealed interface TypeClassification {
    String displayLabel();

    record TableType(String tableName) implements TypeClassification { ... }
    record NodeType(String tableName, List<String> keyColumns) implements TypeClassification { ... }
    record RecordType(String className) implements TypeClassification { ... }
    // ...
}
```

The projection is keyed by `FieldCoordinate` and type name respectively; populated by an exhaustive switch in `CatalogBuilder.projectFieldClassification` / `projectTypeClassification` analogous to `projectType`. A new `GraphitronField` or `GraphitronType` permit landing without coverage in the projector trips a compile error in the projector switch; that is the validator-mirrors-classifier discipline in this layer (no separate validate-time rejection needed because the projection is a build-time switch).

### `BuildArtifacts` does not change shape

`BuildArtifacts` stays `(CompletionData catalog, LspSchemaSnapshot.Built.Current snapshot)`. The two new projections live inside `LspSchemaSnapshot.Built` as additional record fields, with `Current` and `Previous` permits carrying them symmetrically. `Workspace.setBuildOutput` does not change; the LSP just consumes the new fields off `snapshot`.

## Classification taxonomy mapping

The principle "Capability interfaces and sealed switches serve different roles" cautions against an enum-over-variants confusion: the LSP wants both (a) a label per classification and (b) a way to switch on the variant for hover. The proposal here is that `FieldClassification` / `TypeClassification` are sealed families with the LSP's chosen cardinality, not 1:1 with the generator-side sealed permits. Some generator-side variants collapse to one LSP-side classification (e.g., `ChildField.Column` and the inline projection variant render to the same `Column` classification); others split (e.g., `MutationField` permits with materially different payload may surface as distinct LSP-side classifications). The projector decides the mapping; the principle is "one user-facing classification per observably-different display outcome", same rule `TypeBackingShape` used for its five permits.

A consequence: the LSP-side label vocabulary is owned by the LSP module, not by the generator's type names. This is deliberate; the generator-internal `JoinedColumn` rename freedom is preserved (`displayLabel()` lives in the LSP). It also means the Spec must list the full label vocabulary so the projector's mapping is reviewable in one place; see the table at the bottom of this file.

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

Not actually needed in the runtime path: the producer side already knows whether the SDL author wrote the argument (it walks the parsed `GraphQLDirective` and sees its arguments). The LSP consumer reads the projection and renders. The tree-sitter cursor is still used for *positioning* the hint (where to anchor the ghost annotation in the buffer text), but not for the inference-vs-stated decision. That keeps the LSP arm purely a renderer of a pre-resolved fact.

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

1. **C1 ; new projection types in the catalog package.** Land `InferredDirectiveBindings` and `SchemaClassification.{FieldClassification, TypeClassification}` as sealed families in `no.sikt.graphitron.rewrite.catalog`. Initially unwired; pipeline-tier test asserts the sealed structure compiles and `displayLabel()` is non-empty for every permit.
2. **C2 ; producer side in `CatalogBuilder`.** Extend `buildSnapshot` to walk `bundle.model()` and populate the two new projections. Exhaustive switch on `GraphitronField` and `GraphitronType` permits. Pipeline-tier test feeds a curated Sakila-subset SDL through the full pipeline and asserts a target field's projected classification matches the expected permit and load-bearing payload; one such test per LSP-side classification permit (the projection arm is what we're asserting, not the classifier itself, but the test runs through the classifier to keep the assertion realistic).
3. **C3 ; `LspSchemaSnapshot.Built` extension.** Add the new fields, update `Built.Current` and `Built.Previous` symmetrically, update construction sites (`buildSnapshot` overloads; the one-arg overload returns empty projections, matching the existing `typesByName == Map.of()` behaviour).
4. **C4 ; `InlayHintConfig` and config pull.** Record holding the three booleans, defaults all `false`. Workspace gets a `volatile InlayHintConfig config` and a `setConfig` swap path. `GraphitronTextDocumentService` requests `workspace/configuration` on initialisation; `didChangeConfiguration` calls `setConfig`.
5. **C5 ; `InlayHints` provider.** Two arms (inferred-directives, classification), gated by config booleans, dispatching on the two new projections, returning `List<InlayHint>` scoped to the requested range. `GraphitronLanguageServer.initialize` advertises the capability; `GraphitronTextDocumentService.inlayHint` wires the request.
6. **C6 ; classification hover.** New `Behavior.ClassificationView` arm in `LspVocabulary` (or, if Behavior is the wrong fit, a parallel hover arm that triggers at type/field declaration coordinates rather than directive-arg coordinates; this is a design fork the implementer hits, deferred). Renders the `FieldClassification` / `TypeClassification` payload as markdown.
7. **C7 ; docs and config schema.** A new `manual/lsp.adoc` section (or extension to an existing LSP doc) documents the three keys, their effect, and the stale-snapshot semantics. A `directives.graphqls` -- equivalent for inlay-hint kinds is not needed; the LSP advertises the keys via its initialisation handshake.

C1–C5 ship the first user-visible value (inferred-directive hints + classification inlay labels). C6 layers on rich hover. C7 is the docs handoff. Split-out option: if C6 grows scope materially during In Progress (the design fork between "extend `Behavior`" and "introduce a parallel non-directive coordinate scheme" might not be small), the implementer flags this and we file C6 as a sibling roadmap item.

## Test plan

Per the rewrite-design-principles test-tier guide:

- **Pipeline-tier** (`graphitron-rewrite/graphitron/src/test/java/.../catalog/CatalogBuilderTest.java` extension): for each `FieldClassification` and `TypeClassification` permit, a fixture SDL that classifies into that permit, then assert the projected snapshot field carries the expected permit identity and payload. Same shape as existing `TypeBackingShape` tests. Covers C1+C2.
- **Pipeline-tier** for inferred-directive projection: one test per directive kind (`@table`, `@field`, `@reference`), each with two SDL variants (author wrote `name:` / author omitted `name:`), asserting the projection contains an entry only for the omitted variant.
- **LSP-tier unit tests** (`graphitron-rewrite/graphitron-lsp/src/test/java/.../inlay/InlayHintsTest.java`): given a fixed `LspSchemaSnapshot.Built.Current` and a config flag, assert the right hints fire at the right ranges with the right text. Config off → no hints. Config on, projection empty → no hints. Config on, projection populated → hints in the expected order.
- **LSP-tier round-trip test** for stale-snapshot behaviour: build a snapshot, demote to `Built.Previous`, assert hints still render under both flags.
- **No code-string assertions** against the generated output; classification hints are LSP-display, not generation, so the test signal is "the LSP returns the hint" not "the generator emits a string". This sidesteps the body-assertion ban.

## Open questions, settled

- **One toggle or per-category toggles?** Per-category (three keys: inferred-directives inlay, classification inlay, classification hover). Different audiences, different visual density. See "Config keys and toggles".
- **Simple class name as label or friendly label?** Friendly label. The label vocabulary is owned by the LSP module (`displayLabel()` on each permit), not by the generator-side type names. Generator-side renames are free; user-visible names are deliberate.
- **Stale-snapshot behaviour?** Render unchanged from `Current` (no stale marker). Mirrors existing hover arms. `Unavailable` is no hints.
- **Should rich-hover classification split out?** Stay together. C6 is one step in the implementation plan. If the implementer hits scope creep on the `Behavior` extension fork, they file a sibling item then.

## Label vocabulary

The full list of LSP-side classification labels with their generator-side mapping. Reviewed by the principles-architect during Spec → Ready; the projection arm in `CatalogBuilder` is the binding contract.

| LSP classification | Generator-side variant(s) | `displayLabel()` |
|---|---|---|
| `FieldClassification.Column` | `ChildField.Column`, `ChildField.AliasedColumn` | "column" |
| `FieldClassification.JoinedColumn` | `ChildField.JoinedColumn` | "joined column" |
| `FieldClassification.ReferenceColumn` | `ChildField.ReferenceColumn` | "reference column" |
| `FieldClassification.CompositeReference` | `ChildField.CompositeReference` | "composite reference" |
| `FieldClassification.NodeIdProjection` | `ChildField.NodeIdProjection` variants | "node-id projection" |
| `FieldClassification.QueryField` | `RootField.QueryField` permits | "query field" |
| `FieldClassification.MutationField` | `MutationField` permits | "mutation field" |
| `FieldClassification.MethodBacked` | `MethodBackedField` capability | "method-backed field" |
| `FieldClassification.InputField` | `InputField` permits | "input field" |
| `FieldClassification.Unclassified` | `UnclassifiedField` | "unclassified ({rejection})" |
| `TypeClassification.TableType` | `GraphitronType.TableType` | "table type" |
| `TypeClassification.NodeType` | `GraphitronType.NodeType` | "node type" |
| `TypeClassification.RecordType` | `JavaRecordType`, `PojoResultType.Backed`, etc. | "record type" |
| `TypeClassification.TableInterfaceType` | `GraphitronType.TableInterfaceType` | "table interface" |
| `TypeClassification.UnionType` | `GraphitronType.UnionType` | "union type" |
| `TypeClassification.ConnectionType` | `GraphitronType.ConnectionType` | "connection type" |
| `TypeClassification.InputType` | `PojoInputType`, `JooqRecordInputType`, `TableInputType`, etc. | "input type" |
| `TypeClassification.ErrorType` | `GraphitronType.ErrorType` | "error type" |
| `TypeClassification.EnumType` | `GraphitronType.EnumType` | "enum type" |
| `TypeClassification.ScalarType` | `GraphitronType.ScalarType` | "scalar type" |
| `TypeClassification.PlainObjectType` | `GraphitronType.PlainObjectType` | "plain object" |
| `TypeClassification.RootType` | `GraphitronType.RootType` | "root ({operation})" |
| `TypeClassification.Unclassified` | `GraphitronType.UnclassifiedType` | "unclassified ({rejection})" |

The mapping is exhaustive on the current `GraphitronField` and `GraphitronType` permits as of R160's filing; the projector switches enforce the exhaustiveness. New permits added downstream need a corresponding LSP-side mapping in the same commit, surfaced as a compile failure in the projector if missed.

## References

- R139 `lsp-schema-snapshot-side-channel` ; established the dev-pipeline → LSP projection-via-`LspSchemaSnapshot.Built` data flow this item extends.
- R152 `lsp-nodetype-hover-column-scoping` ; precedent for hover content that consumes snapshot projections.
- R121 `lsp-diagnostic-redundant-splitquery-on-record` ; precedent for an LSP arm that mirrors a build-tier classifier signal.
- Design principles: `graphitron-rewrite/docs/rewrite-design-principles.adoc` ; "Generation-thinking" (LSP consumes pre-resolved projections, doesn't recompute), "Sealed hierarchies over enums for typed information" (`FieldClassification` is sealed with payload, not an enum + opaque map), "Model metadata over parallel type systems" (`displayLabel()` derives from the projected permit, not a parallel name registry), "Classifier guarantees shape emitter assumptions" (exhaustive switch in the projector trips on new variants).
