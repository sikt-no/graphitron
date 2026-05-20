---
id: R160
title: LSP inlay hints and hover for inferred directives and field/type classification
status: Spec
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

R139 established the data-flow precedent: the LSP consumes **projections** of the post-classification model carried on `LspSchemaSnapshot.Built`, not the model itself. `TypeBackingShape` is the worked example: a sealed family purpose-built for the LSP's `@field(name:)` arm, projected from `GraphitronType` by `CatalogBuilder.projectType`. This item follows the same pattern: extend `LspSchemaSnapshot.Built` with two new classification projection axes, and have the inlay-hint and hover arms dispatch on those.

The inferred-directive arm needs no model surface and no separate projection. Provenance is a property of the SDL source (did the directive carry an argument?), not of the resolved identity, and the LSP module already has both halves of the answer in reach: the `Workspace.WorkspaceFile.tree()` per-document tree-sitter `Tree` carries the live SDL AST, and the classification projection on the snapshot carries the resolved name the inference produced. The inlay-hint provider asks the AST "was `name:` / `path:` written on this directive?"; when the answer is no, it pulls the resolved value off the classification projection and renders the hint. Nothing is lifted onto `TableRef` / `ColumnRef` / the `@reference`-permits; the inference rule is not duplicated, the LSP runs the same `directive.getArgument(arg) == null` predicate at request time over the AST it already keeps.

### Provenance reads off the tree-sitter AST

The `Directives.findContaining(root, pos)` helper in `graphitron-lsp` already does "cursor → `@directive` node" lookup; `directive.arguments()` exposes the argument list with `(key, value)` token handles. `Hovers.java` is the existing precedent ; it walks the same structure to read arg values for the hover popup. The inferred-directive arm reuses that walk:

1. For each `@table` / `@field` / `@reference` node in the LSP-requested range, ask `arguments()` whether the canonical argument (`name` for the first two, `path` for `@reference`) is present.
2. If absent, the value was inferred. Look up the resolved name on the classification projection (`FieldClassification` for fields, `TypeClassification` for types) and render the hint at the directive name's position.
3. If present, no hint; the user already sees what the value is.

The classification-projection lookup gives the LSP the same resolved value the classifier computed, with no re-resolution at request time. The AST walk is bounded by the visible viewport (lsp4j's range-scoped inlay-hint API) so the per-document cost stays proportional to what's on screen.

For `@reference(path:)` the projection's `ColumnReferenceField` permit carries the resolved FK chain (table and FK name per hop). The provider's inferred branch renders that chain in the hint text. No catalog query at request time ; the FK identity was already pinned at classification.

### The classification projection covers both inlay-hint arms and the hover

`FieldClassification` and `TypeClassification` (next section) carry the load-bearing payload the hover renders, which is the same payload the inferred-directive arm reads to fill in the omitted argument. One projection, two consumers. No separate `InferredDirectiveBindings` carrier ; the resolved identity already lives on the classification projection, and the "did the user write it?" half lives on the AST.

Honesty about freshness: the classification projection lives on `LspSchemaSnapshot.Built`, so a `Built.Previous` snapshot means stale labels and stale inferred-directive values. The inlay-hint provider does not gate on freshness (`Current` vs `Previous`); per principle the LSP prefers stale info over silence, mirroring how `userArgHover` and `columnHover` already behave. `Unavailable` → no hints. Buffer edits between snapshots can leave the AST and the projection out of step in either direction:

- **AST ahead, user added `name:`.** Provider sees AST-authored, short-circuits before the projection lookup, no hint. Harmless regardless of what the stale projection still says.
- **AST ahead, user just deleted `name:` (or `path:`).** Provider sees AST-inferred, looks up the still-stale projection's resolved value, renders a hint for it. The hint shows the value the *last successful build* inferred ; not necessarily what the next build will infer, since the deletion may also imply other edits to the type or its table. Same trade-off `userArgHover` accepts: prefer the last known good value over silence; the next regenerate cycle replaces stale with current.
- **Projection ahead, build ran on a buffer the user has since reverted.** Same shape as above ; projection holds the build-time inferred value, AST holds the current authored or absent state, and the AST-side check is what gates the hint.

The "prefer stale over silence" policy is the resolution in both directions, and the AST-side authored check is the structural short-circuit that keeps the policy from misfiring on the most common authoring edit (user explicitly writing the canonical argument).

### `FieldClassification` and `TypeClassification`

Sealed projection-families analogous to `TypeBackingShape`. The projection records are sized to **distinct hover-payload shapes**, not 1:1 with the generator-side permits. Permits that differ only in a label dimension ; DML verb (insert / update / delete / upsert), single/list multiplicity on `Query[Node|Nodes]Field` ; collapse onto one LSP record carrying that dimension as a discriminator field; permits whose hover-relevant payload genuinely diverges (split axis on `SplitTableField`, lookup key on `LookupTableField`, error-channel name on a service mutation) get their own record. Each record carries only LSP-renderable payload (table names, column names, FK names, target type names, error-channel names ; primitives, strings, and enums, never `TableRef` / `ColumnRef` / `graphql-java` types). No `displayLabel()` on the records; label rendering is an LSP-module switch (see "Label rendering" below).

The exhaustive switch on the generator-side `GraphitronField` permits in `CatalogBuilder.projectFieldClassification` enforces **coverage** (a new permit fails the switch to compile until mapped). What the projection collapses is the *LSP-side cardinality*, not the generator-side coverage; the label switch in `LspClassificationLabels` still dispatches over the full generator-side permit set. The "Sealed hierarchies over enums for typed information" principle pushes back on a 1:1 mirror with duplicate-shape records: each variant should carry exactly the fields it needs, not the union of fields its siblings need.

Worked example: the four `MutationField.DmlTableField` permits (`MutationInsertTableField` / `MutationUpdateTableField` / `MutationDeleteTableField` / `MutationUpsertTableField`) share `(tableName, inputTypeName?, errorChannelName?, dmlKind)`; one LSP record `DmlMutation` with a `DmlKind` discriminator captures the hover content, and `LspClassificationLabels` still emits "insert mutation" / "update mutation" / etc. via its own exhaustive switch on the generator-side permit. Counter-example: `ChildField.SplitTableField`'s split-axis is hover-distinct from `ChildField.TableField`'s plain table-bound payload, so the two stay separate LSP records.

The exact grouping is the C1 implementation pass against the generator-side permits enumerated in the "Label vocabulary" tables below; the principle-aligned default is to collapse where collapsing does not erase a load-bearing distinction.

```
sealed interface FieldClassification
    permits FieldClassification.ColumnField,
            FieldClassification.ColumnReferenceField,
            // ... payload-distinct leaves; the C1 grouping pass settles the exact set.
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

`TypeClassification` follows the same discipline: payload-distinct records over the `GraphitronType` permits. The type-side payload divergence is denser than the field side (record-backed / pojo-backed / jOOQ-table-backed / etc. all carry different load-bearing payload), so most type permits keep their own LSP record; the C1 pass identifies any genuine groupings.

### Label rendering lives in the LSP module

Label strings (`"column"`, `"joined column"`, `"node type"`, ...) are NOT on the projection record. They live in the LSP module as an exhaustive switch in `InlayHints` (or a sibling `LspClassificationLabels` utility class). This keeps presentation a sibling concern: the projection record carries the load-bearing identity-plus-payload, the LSP module chooses how to render it. The precedent `TypeBackingShape` followed is exactly this: `MemberSlot` carries `displayType` because the type-display string IS the load-bearing payload the hover renders, but it does not carry "this is a record component" as a label because that's already encoded in the permit identity (`RecordBacking` vs `PojoBacking`).

### Validator-mirrors-classifier alignment

`FieldClassification` is an LSP-display projection, not a generator branch; the "Validator mirrors classifier invariants" principle does not bite here in its primary form (no validate-time rejection is required). The discipline at play is "Sealed hierarchies over enums for typed information": the projector's exhaustive switch on `GraphitronField` permits (and similarly on `GraphitronType`) trips a compile error when a new permit lands without an LSP-side projection. That is the contract that keeps "new generator-side variant → LSP-side coverage in the same commit" honest.

Concretely: `CatalogBuilder.projectFieldClassification` is a `switch` expression over `GraphitronField` whose arms cover the full permits list. Adding a new permit to `ChildField` fails the projector to compile until the LSP-side mapping is added in the same commit ; whether that mapping is a new payload-distinct LSP record or an arm that returns a shared record with a different discriminator value is a per-permit judgment, but exhaustiveness is what fails to compile. No new validator hook in `GraphitronSchemaValidator` is needed.

### `BuildArtifacts` does not change shape

`BuildArtifacts` stays `(CompletionData catalog, LspSchemaSnapshot.Built.Current snapshot)`. The new projections live inside `LspSchemaSnapshot.Built` as additional record fields, with `Current` and `Previous` permits carrying them symmetrically. `Workspace.setBuildOutput` does not change; the LSP just consumes the new fields off `snapshot`.

### Load-bearing classifier check keys

The producer/consumer annotation pair (`@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck`) carries the binding contract for the new flow. Two new keys land with this item:

| Key | Producer | Consumers |
|---|---|---|
| `field-classification-payload-faithful` | `CatalogBuilder.projectFieldClassification` | `InlayHints` classification arm; classification-hover arm; `InlayHints` inferred-directive arm (reads the resolved column / FK chain off the field-classification projection) |
| `type-classification-payload-faithful` | `CatalogBuilder.projectTypeClassification` | `InlayHints` classification arm; classification-hover arm; `InlayHints` inferred-directive arm (reads the resolved table name off the type-classification projection) |

The cross-module-audit caveat that R139 already documented applies: until the audit scope widens to include `graphitron-lsp`, the annotations are find-usages markers rather than build-tier audit failures on orphaned consumers. The pair stays useful at code-review time and as the navigation aid `LoadBearingGuaranteeAuditTest` exists for.

The inferred-directive arm's *AST-read half* (does the buffer's directive node carry `name:` / `path:`?) is unannotated by design. The classifier produces no guarantee about the SDL source the user typed ; the source is the source, and the AST is the canonical reader of it. The `@LoadBearingClassifierCheck` family covers the producer-consumer contract between classifier output and downstream consumers; the AST read sits outside that contract and gets no key.

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

Central to the inferred-directive arm. The LSP module keeps a live tree-sitter `Tree` per open SDL file on `Workspace.WorkspaceFile`; `Directives.findContaining` and the `Directive.arguments()` walk in `graphitron-lsp` already serve the hover precedent. For each directive in the LSP-requested viewport range, the provider asks the AST whether the canonical argument is present; the AST is the only place that information lives by design, since the classifier resolves the value and the resolved value is what the rest of the pipeline consumes. Buffer position for hint anchoring comes from the same AST node ; no separate position carrier is needed.

The "if two consumers would evaluate the same predicate, the branch belongs in the model" principle does not bite for the *resolved-value* axis: the predicate is not "was the argument present?" (which the classifier and the LSP would both evaluate over the same SDL) but "rendering distinguishes authored from inferred at the hint site". Only the LSP's render path cares; the classifier deliberately throws the distinction away once it has the resolved value. The principle in play instead is "the LSP is downstream and reads, not lifts": no carrier shape change in service of a presentation distinction.

The principle *does* bite at a smaller table the inferred-directive arm needs: the canonical-argument-per-directive map (`@table` → `name`, `@field` → `name`, `@reference` → `path`, and the entries any future inference rule adds). That map lives on the classifier site that owns the inference rule (today the existing `ARG_NAME` / `ARG_PATH` constants in `graphitron`'s directive-vocabulary module; their declaration is the natural home for "this is the argument inference targets"). `InlayHints` reads from the same source ; not a switch literal copied into the LSP module ; so a future inference rule extension adds one entry in one place and the LSP picks it up.

The AST read is for SDL-source structure (did the token exist in the buffer), not for re-classifying meaning. The classifier already supplied the resolved meaning on the projection; the LSP only asks the AST "did the user type these characters". That framing closes the question "is this a second parse boundary?" ; structural-presence reads and semantic-resolution reads sit on different sides of the boundary, and only the semantic side is what "Classification belongs at the parse boundary" governs.

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

1. **C1 ; new projection types in the catalog package.** Land the sealed `FieldClassification` / `TypeClassification` families under `no.sikt.graphitron.rewrite.catalog`. Initially unwired. Unit-tier test asserts the sealed structure compiles and each permit's payload fields are reachable. Each LSP-side projection record carries the resolved identity payload the classification hover and the inferred-directive hint both consume (table name, column name, FK chain, target type, etc.) ; no provenance discriminator, since the LSP reads "was the user authoring this?" off the AST.
    
    **First artifact: the per-record collapse table.** Before producer code lands in C2, C1 publishes the full LSP-record list ; one record per distinct hover-payload shape ; alongside the per-generator-permit mapping. The label vocabulary tables below enumerate ~50 generator-side permits across `FieldClassification` and `TypeClassification`; the C1 deliverable is the collapse decision per permit (its own LSP record, or merged with sibling X under discriminator Y). Reviewers apply the payload-distinct rule per row: a permit gets its own LSP record when its hover renders fields siblings don't have, and merges with a sibling under a discriminator only when the merged record's fields would otherwise be the union of sibling-only fields filled with nulls. The four `MutationField.DmlTableField` permits collapse to one `DmlMutation(tableName, inputTypeName, errorChannelName, DmlKind)` record because every field is on every permit; `DmlKind` is an enum (not a sealed sub-type) precisely because no LSP consumer forks on the kind ; the label switch dispatches on the generator-side permit directly, and the hover renders one shape regardless of kind. If a future consumer needed to fork on DML verb, the discriminator graduates to a sealed sub-type then. The per-record collapse table ships as the C1 commit's primary review artifact; the empty-bodied records under it are the mechanical follow-on.
2. **C2 ; producer side in `CatalogBuilder`.** Two new methods: `projectFieldClassification(GraphitronField)`, `projectTypeClassification(GraphitronType)`. Exhaustive switches on the generator-side permits. Wear the two `*-classification-payload-faithful` `@LoadBearingClassifierCheck` annotations. The producer side reads `TableRef.tableName`, `ColumnRef.sqlName`, the resolved `joinPath` step `fkName`s ; everything the model already carries today, no new model surface.
3. **C3 ; `LspSchemaSnapshot.Built` extension.** Add the two new fields (`fieldClassificationsByCoord`, `typeClassificationsByName`) alongside the existing `directives`, `typesByName`, and `payloadDataFieldByType` carriers, update `Built.Current` and `Built.Previous` symmetrically, update `CatalogBuilder.buildSnapshot` overloads (the one-arg overload returns empty projections, matching the existing `typesByName == Map.of()` behaviour).
4. **C4 ; `InlayHintConfig` and config pull.** Record holding the three booleans, defaults all `false`. Workspace gets a `volatile InlayHintConfig config` and a `setConfig` swap path. `GraphitronTextDocumentService` requests `workspace/configuration` on initialisation; `didChangeConfiguration` calls `setConfig`.
5. **C5 ; `InlayHints` provider plus label switch.** Two arms gated by config booleans:
    - **Inferred-directive arm.** Walks the visible-range subtree of `WorkspaceFile.tree()` for `@table` / `@field` / `@reference` directive nodes via `Directives.findAll` / the existing `Directives` utility. For each directive, asks `arguments()` whether the canonical argument is present. When absent, resolves the SDL coordinate to a `FieldClassification` / `TypeClassification` projection entry and renders the inferred value as a hint anchored at the directive name node's range.
    - **Classification arm.** Walks the visible-range subtree for field and type declaration nodes; for each, looks up the matching projection entry and renders a compact label.
    
    Returns `List<InlayHint>` scoped to the requested range. Label rendering is an exhaustive switch in `LspClassificationLabels` (sibling utility class to `InlayHints`). Hover-side label reuse from the same switch. `GraphitronLanguageServer.initialize` advertises the capability; `GraphitronTextDocumentService.inlayHint` wires the request. Each LSP arm wears the matching `@DependsOnClassifierCheck`.
6. **C6 ; classification hover.** Hover at field-declaration and type-declaration coordinates ; not directive-arg coordinates, so the existing `LspVocabulary.coordinateAt` / `Behavior` infrastructure does not extend straightforwardly. Design choice: introduce a parallel hover dispatch keyed on SDL declaration positions (alongside the existing `Behavior`-keyed directive-arg dispatch), not a new `Behavior` arm. `Behavior`'s permits are uniformly directive-argument-binding shapes (every existing arm ; `CatalogTableBinding`, `CatalogColumnBinding`, `NodeTypeBinding`, etc. ; names a directive-argument binding); adding a field-declaration or type-declaration arm would widen the family in a carrier-shape direction, which is the parallel-axis smell "Capability vs. sealed-switch confusion" warns about. The parallel dispatch keeps `Behavior` tight and gives the new hover positions a sibling resolver to grow into. Renders the `FieldClassification` / `TypeClassification` payload as markdown, reusing `LspClassificationLabels` for the heading.
    
    **Dispatch shape: a sealed `DeclarationHover` family**, sibling to `Behavior`, permitting `FieldDeclarationHover` and `TypeDeclarationHover` at filing (further permits join only if new SDL declaration coordinates need hover content). `Hovers.compute` calls the `Behavior`-keyed path first (existing directive-arg coordinates) and falls through to the `DeclarationHover` resolver on a miss. The sealed family keeps the LSP module's exhaustive switch obligation explicit ; adding a third declaration coordinate fails the dispatch to compile until the permit and its hover content land together ; and pre-empts the corner-cut of an unstructured switch literal in `Hovers.compute`. If C6 grows scope materially (the SDL-declaration-coordinate resolver may need its own `LspVocabulary`-shaped helper), the split-out option below files it as a sibling roadmap item rather than expanding C6.
7. **C7 ; docs and config schema.** A new section in `manual/lsp.adoc` (or extension to an existing LSP doc) documents the three config keys, their effect, the stale-snapshot semantics, and the user-visible label vocabulary. The LSP advertises the keys via its initialisation handshake.

C1–C5 ship the first user-visible value (inferred-directive hints + classification inlay labels). C6 layers on rich hover. C7 is the docs handoff. Split-out option: if the parallel hover dispatch in C6 grows scope materially during In Progress (the SDL-declaration coordinate resolver is greenfield), the implementer flags this and we file C6 as a sibling roadmap item rather than holding the rest of the work. Sequencing note: C2 and C3 may land in one commit if C2's projection-producer output needs a place to live on `LspSchemaSnapshot.Built` ; "Initially unwired" in C1 already implies the carrier-then-producer-then-store ordering, and C2 standalone has no consumer until C3 wires the snapshot store.

## Test plan

Per the rewrite-design-principles test-tier guide.

**Pipeline-tier truth tables, co-located with the classifier truth tables.** `GraphitronSchemaBuilderTest` already organises classifier coverage as `// ===== <VariantName> =====` blocks (one block per `ChildField` / `RootField` / `MutationField` / `InputField` permit). C2 extends each of those blocks with a sibling assertion that runs the classified field through `CatalogBuilder.projectFieldClassification` and asserts the projected permit and payload. Same for type variants against `projectTypeClassification`. Co-location prevents the parallel-table drift the architect flagged: a future contributor adding a new `ChildField` permit cannot pass review without populating both the classifier-row and the projection-row in the same block.

**LSP-tier unit tests** for the inferred-directive arm (`graphitron-rewrite/graphitron-lsp/src/test/java/.../inlay/InferredDirectiveHintsTest.java`): given a paired SDL fixture (author wrote `@table(name: "...")` vs author wrote bare `@table`) and a fixed snapshot, assert that the bare site emits an inlay hint with the resolved name and the authored site emits none. Same paired-fixture shape for `@field(name:)` and `@reference(path:)`. The fixture tests the AST-walk-plus-projection-lookup contract, not the classifier's resolution logic (which is the pipeline-tier's job).

**LSP-tier unit tests** for the classification arm (`graphitron-rewrite/graphitron-lsp/src/test/java/.../inlay/ClassificationHintsTest.java`): given a fixed `LspSchemaSnapshot.Built.Current` and a `InlayHintConfig`, assert the right hints fire at the right ranges with the right text. Config off → no hints. Config on, projection empty → no hints. Config on, projection populated → hints in the expected order with the labels `LspClassificationLabels` emits. One test per LSP-side permit isn't required at this tier; the truth-table coverage at the pipeline tier already pins the projection identity, so the LSP-tier tests focus on the inlay-arm's dispatch logic (correct ranges, correct config-gating, correct stale-snapshot behaviour) rather than enumerating every permit.

**LSP-tier round-trip test** for stale-snapshot behaviour: build a snapshot, demote to `Built.Previous`, assert hints still render under both flags. Mirrors the existing `userArgHover` / `columnHover` stale-snapshot precedent. One sub-case in the inferred-directive suite: AST says the user just typed `name:` but the projection is stale → no hint (the AST-authored check short-circuits before the projection lookup).

**No code-string assertions** against the generated output; classification hints are LSP-display, not generation, so the test signal is "the LSP returns the hint" not "the generator emits a string". This sidesteps the body-assertion ban.

## Open questions, settled

- **One toggle or per-category toggles?** Per-category (three keys: inferred-directives inlay, classification inlay, classification hover). Different audiences, different visual density. See "Config keys and toggles".
- **Simple class name as label or friendly label?** Friendly label. The label vocabulary is owned by the LSP module ; `LspClassificationLabels` is an exhaustive switch in the LSP module ; not by the generator-side type names. Generator-side renames are free; user-visible names are deliberate.
- **Stale-snapshot behaviour?** Render unchanged from `Current` (no stale marker). Mirrors existing hover arms. `Unavailable` is no hints.
- **Should rich-hover classification split out?** Stay together. C6 is one step in the implementation plan. If the SDL-declaration coordinate resolver in C6 grows scope materially during In Progress, the implementer files a sibling item then.
- **Lift `Provenance` into the model, or read off the SDL AST at request time?** Read at request time, from the tree-sitter `Tree` the LSP already keeps per open file. The lifted-carrier alternative was attempted (commit `d23244b` and hotfix `2cdf7bd`, both reverted in `315891a`) and rolled back: the model touch reached `TableRef`, `ColumnRef`, the five `@reference`-permits, `ParticipantRef.CrossTableField`, and the resolver paths around them (~330 lines, 11 files, plus a silent `equals` regression that mistook same-identity refs for unequal because they carried different provenance). Provenance is a property of the SDL source (did the directive carry an arg?), not of the resolved identity, and the LSP module already has the AST in reach via `WorkspaceFile.tree()` and the `Directives.findContaining` helper the hover arm uses today. The classification projection on the snapshot carries the resolved value the inference produced; pairing the AST walk with the projection lookup at request time is the minimum-surface implementation. Future consumers that genuinely need provenance pinned at classification time (a build warning when a written `name:` matches the inferred default; a code action that inlines/extracts an inferred name) revisit this trade-off then ; today there are none.
- **One unified `Provenance` family, or one per carrier shape?** Moot under the AST-read design; no `Provenance` family lives on the model. If a future consumer revives the question, the split-by-carrier reasoning from the original spec stands (a unified family would type-permit `FromUniqueFk` on `TableRef` / `ColumnRef` where no inference rule produces it).
- **`FieldClassification` cardinality (1:1 with generator-side permits, or payload-distinct)?** Payload-distinct, with discriminator fields collapsing permits that differ only in a label dimension (DML verb, single/list multiplicity). The projector's exhaustive switch over the generator-side permits keeps coverage compile-checked; the LSP-side records carry one distinct hover-payload shape each. Worked example: the four DML mutation permits collapse to one `DmlMutation(tableName, inputTypeName, errorChannelName, DmlKind)` record; the label switch in `LspClassificationLabels` still emits a per-permit label via its own exhaustive switch on the generator-side permit. "Sealed hierarchies over enums for typed information" pushes back on a 1:1 mirror with duplicate-shape records.
- **C6 `Behavior`-extension vs. parallel hover dispatch?** Parallel hover dispatch (settled at Spec time). `Behavior`'s permits are uniformly directive-argument-binding shapes; the new hover positions are SDL declaration coordinates, not directive-arg coordinates, so they get their own resolver rather than widening `Behavior` in a carrier-shape direction.

## Label vocabulary

LSP-side classification labels rendered by `LspClassificationLabels`. **Labels are 1:1 with the generator-side permits** (each row in this table maps to one `case` in the label switch); the **LSP-side projection records** (see "`FieldClassification` and `TypeClassification`" above) may collapse multiple permits onto one record where the hover-payload shape is the same. The two switches dispatch independently: the projector picks the record-with-discriminator, the label switch picks the label, both exhaustive over the generator-side permits. This table is the LSP-module's label switch laid out as a review artifact; it enforces that every generator-side permit has a chosen label.

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
- Design principles: `graphitron-rewrite/docs/rewrite-design-principles.adoc` ; "Sealed hierarchies over enums for typed information" (each LSP-side projection record carries exactly the fields its hover shows; the projector's exhaustive switch over the generator-side permits enforces coverage without forcing a duplicate-shape 1:1 mirror), "Capability vs. sealed-switch confusion" (`Behavior` stays directive-argument-binding shaped; SDL declaration coordinates get a parallel hover dispatch rather than a carrier-shaped widening of `Behavior`), "Model metadata over parallel type systems" (labels live on a sibling switch in the LSP module, not on the projection records), "Classifier guarantees shape emitter assumptions" (the `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` pair documents the two `*-classification-payload-faithful` keys C2 introduces). The "Classification belongs at the parse boundary" principle is consistent with this design rather than against it: SDL-source provenance never enters the classified model, the LSP reads it from the AST at request time, and the parse-boundary stays closed in the direction that matters (no `GraphQLDirective` or AST type imported into the generator-side classification layer).
