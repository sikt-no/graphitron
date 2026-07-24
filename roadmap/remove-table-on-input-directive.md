---
id: R519
title: "Remove @table from input types; delete TableInputType (Phase 3)"
status: Spec
bucket: architecture
priority: 6
theme: classification-model
depends-on: []
created: 2026-07-24
last-updated: 2026-07-24
---

# Remove `@table` from input types; delete `TableInputType` (Phase 3)

Carved out of R97 (`consumer-derived-input-tables`) as the directive-removal
slice, the way R457/R514/R515 were carved off the mutation write-target axis.
**This item is the home for the general `@table`-on-input removal** (the mantle
R457's changelog and R327's fold assigned to R97 before the deprecation axis was
split into shippable slices).

The prerequisite work has landed: consumer-derived resolution is complete, so
`@table` on an input is now pure redundant metadata everywhere.

- **R97** made the last classification consumers consumer-derived: arg-level
  `@lookupKey` resolves through the consuming field's table, and the global
  input-classification machinery (`findReturnTablesForInput`,
  `isUsedWithOverrideCondition`) is gone. `buildInputType` is directive-driven:
  explicit `@table` → the deprecated `TableInputType` bridge; everything else →
  the plain path.
- **R457 / R515 / R97 Phase 2b** made every mutation verb's write target
  field-relative: DELETE via `@mutation(table:)`, INSERT and UPDATE via the
  return-derived > `@mutation(table:)` > input-`@table`-bridge ladder (UPDATE is
  a member of both `RETURN_DERIVED_TABLE_VERBS` and `TABLE_ARG_SUPPORTED_VERBS`,
  `MutationInputResolver.java:157/:169`), so no verb hard-requires `@table` on
  its input.

What remains is retiring the directive's input scope and deleting the bridge.

## Design decisions

Settled in Spec, with a principles-architect consultation on each fork.

**D1 — Retired-location rejection, not a parse error.** The Backlog framing
("`@table` on an input is a parse error, not a deprecation") is reversed. The
codebase has three live precedents for a removed directive/location —
`@notGenerated`, `@multitableReference`, and `@lookupKey` on
`INPUT_FIELD_DEFINITION` (`directives.graphqls:20-25`, `:192-200`, `:243-253`) —
and all three keep the SDL declaration so the parser does not fail with a
generic "unknown directive location" error, then reject at classify time with a
"no longer supported" message carrying migration guidance
(`FieldBuilder.java:2404-2419`, `BuildContext.java:2476-2481`). `@table` follows
that convention: `INPUT_OBJECT` stays in the declared `on` clause, the
doc-comment rewrites from "Deprecated on input types" to a "Retired on
`INPUT_OBJECT`" block in the house style, and any input application is a
classify-time typed rejection whose message inherits the per-verb migration
text the R332 deprecation warning carries today (`@mutation(table:)` for DELETE
and encoded-ID/scalar returns; return-derivation for INSERT/UPDATE; plain
removal for filters). A graphql-java grammar error has no stable LSP code and
no fix-it text; the classify-time surface does, and it keeps the migration
behavior pinnable at the pipeline tier.

**D2 — The `buildInputType` arm flips from bridge to rejection.** The `@table`
arm in `TypeBuilder.buildInputType` (`TypeBuilder.java:1621-1646`, the sole
production site of `TableInputType`) stops building the bridge and instead
produces the D1 rejection through the builder's existing classify-time error
channel. Everything else collapses to the plain path; the previously-`@table`
inputs classify like any other input (reflection against the consuming Java
parameter, `PojoInputType` when unbacked).

**D3 — Query-side `@table`-input args fold to the plain path.** The
`FieldBuilder.java:1416-1427` arm that builds
`ArgumentRef.InputTypeArg.TableInputArg` from the *input's own* table retires;
such args take the existing consumer-derived path (`PlainInputArg`,
`classifyPlainLookupKeyArg`) that resolves against the *consuming field's*
table. The `TableInputArg` carrier itself is **not** deleted — it is still
built field-relatively on the consumer-derived and write-target paths; only the
query-side construction from the input's own table goes. An input reused across
fields on different tables now resolves per-consumer, and a field that does not
resolve on a consumer's table is a classify-time `UnclassifiedField` naming
that table (previously: silent resolution against the input's declared table —
the cross-table miscompile).

**D4 — Write-target rung 3 retires everywhere, classifier and grounder in
lockstep.** DELETE's fallback rung (`FieldBuilder.resolveDeleteWriteTarget`,
`:5216-5290`) and INSERT/UPDATE's rung 3
(`FieldBuilder.resolveReturnCapableWriteTarget`, `:5470-5624`) go; the ladders
become `@mutation(table:)`-only for DELETE and return-derived >
`@mutation(table:)` for INSERT/UPDATE. `MutationInputResolver.singleTableInputType`
(the grounder's rung 3, `:291-301`) reads the raw directive rather than the
variant, so no compile error will point at it — it must be deleted
deliberately, or the grounder would honor a bridge the classifier rejects. The
pipeline-tier enforcer pinning grounded-table == classified-write-target
survives over the two remaining rungs.

**D5 — The `InputBeanResolver` D2 rule retires without re-sourcing.** This
reverses the seam note recorded during R97 (which said to re-source the check
from the consuming field's write-target fact before deleting the variant). The
check (`InputBeanResolver.java:227-236`) reads only type identity — "did this
input classify `TableInputType`", a proxy for "does it carry `@table`" — and
never consults the variant's fields. Once `@table` on an input is rejected at
classify time, the conjunction is unauthorable and the arm is dead code. Under
the fact model there is no whole-type ownership verdict left to contradict: the
same input consumed by a DML field (table = that field's fact) and by a
jOOQ-record `@service` param (construction = the service's fact) is
per-coordinate coherent. Re-sourcing would rebuild a global type-level
aggregate ("is this input any field's write target") of the exact shape R97
deleted (`findReturnTablesForInput`). Delete the arm and its comment block
(`:219-226`).

**D6 — LSP projection: drop the type-level `TableInput` arms, keep the
consumer-derived hover, re-scope R337 in-slice.** The four `CatalogBuilder`
seams lose their source when the variant goes:

- `projectTypeClassification`'s `TableInput` arm (`CatalogBuilder.java:798-799`):
  the previously-`@table` inputs now classify `PojoInput`, whose
  `resolvedTables` (added by R97, filled from each consuming field's classified
  target) already carries the per-consumer table list into the type-declaration
  hover — the type-level surface survives with more honest content.
  `TypeClassification.TableInput` retires (sealed permit at
  `TypeClassification.java:52`, record at `:179-182`), cascading dead-arm
  deletions into `DeclarationHovers.java:341`, `TypeContext.java:126-135`,
  `LspClassificationLabels.java:87`, `InferredDirectiveArgs.java:88`,
  `EdgeProducer.java:138/:316`, and `SchemaView.java:182-185`.
- `projectType`'s `TableBacking` arm for inputs (`:874`): drops; an input is
  not table-backed under the fact model.
- The per-input-field `FieldClassification` walk (`tit.inputFields()`,
  `:211-218`) and the `parentTableName` arm (`:646-656`): drop, so an input
  object's own field declarations lose hover/goto/inlay coordinates. R97
  already dropped these for the auto-promoted subset; this drops the last
  (explicit-`@table`) subset. Re-surfacing them per-coordinate is the residual
  R337 (`input-nesting-projection-classification`) exists to own; its tombstone
  guard condition has fired (R97 is Done), so **re-scoping R337 from redirect
  to a live Backlog item scoped to that residual is an action item of this
  slice**, not a prose handoff — otherwise the dropped coordinates have no
  enforced owner.

## Plan

Additive-then-cutover at commit granularity: Phase A is behavior-neutral while
the bridge still exists (R97's plain path already handles every `@table`-free
input), so every commit stays green and any cross-table-reuse flip surfaces
during migration with the bridge available as a diff-free comparison point.
Phase B is the isolated cutover with the exhaustive-switch cascade.

### Phase A — fixture migration (bridge still live)

1. **Sakila example schemas** (`graphitron-sakila-example/src/main/resources/graphql/`,
   not `graphitron-fixtures-codegen` as the Backlog body said — that module is
   Java-only): 40 `input` declarations carry `@table` (`schema.graphqls` 38,
   `federated-schema.graphqls` 1, `multitenant.graphqls` 1). Filters (23 of the
   38 + the federated `FilmOneOfFilter`) just drop the directive. INSERT/UPDATE
   inputs whose consuming field returns the `@table` type (e.g.
   `FilmCreateInput`/`FilmUpdateInput` → `Film`) return-derive. DELETE and
   encoded-ID/scalar-return mutations (`FilmDeleteInput` → `[ID!]!`,
   `CreateKeyedNodeInput` → `ID`, …) move to `@mutation(table:)` on the field.
   `FixtureWarningsGateTest` and `DmlBulkMutationsExecutionTest` expectations
   (which currently expect the deprecation warnings) update here.
2. **Inline SDL in tests**: ~383 `input … @table` declarations across 40 files
   in `graphitron/src/test/java` (largest: `GraphitronSchemaBuilderTest` ~156,
   `NodeIdPipelineTest` ~41, `MutationDmlNodeIdClassificationTest` ~35,
   `MutationTableArgClassificationTest` ~25, `FacetedConnectionPipelineTest`
   ~23; the Backlog body's "38 + a few" undercounted — its 29/23 figures were
   `TableInputType`-string counts, a different metric) plus inline SDL in
   `graphitron-lsp` tests (`FieldCompletionsTest`, `DiagnosticsTest`,
   `HoversTest`, 2 each; `ValidatorDiagnosticsTest`'s deprecation-diagnostic
   case). Same migration rules as (1). Tests that pin the *bridge itself*
   are not migrated here; they retire or flip in Phase B.
3. **Pipeline-tier pin for the headline behavior flip** (new test, lands while
   both paths exist): a directiveless input consumed by two fields on different
   tables resolves per-consumer, and a column absent on one consumer's table
   yields `UnclassifiedField` naming that consumer's table. This is the
   regression home for the fact-model outcome; fixture breakage alone pins
   nothing.

### Phase B — cutover

4. **Directive surface** (per D1): rewrite the `@table` doc-comment in
   `directives.graphqls:27-32` to the "Retired on `INPUT_OBJECT`" house style
   (the current prose's encoded-ID carve-out is already stale — the warning
   fires on that case since R515); keep the `on` clause as is. Flip
   `buildInputType`'s `@table` arm to the typed rejection (per D2). Retire
   `emitTableOnInputDeprecationWarnings` (`GraphitronSchemaBuilder.java:876`,
   called at `:303` — not in `GraphitronSchemaValidator` as the Backlog body
   said); its per-verb migration text moves into the rejection message. Retire
   the input half of the "Shadowed by `@table`" directive-ignored warning
   (`TypeBuilder.emitDirectiveIgnoredWarning`, `:651-658`; R96 took the
   `@record` half) — subsumed by the rejection.
5. **Delete the variant and bridge.** `GraphitronType.TableInputType`
   (`GraphitronType.java:429-436`), `TypeBuilder.buildTableInputType`
   (`:1705-1721`), `GraphitronSchemaValidator.validateTableInputType`
   (`:404-410` + the `:129` switch arm). Permits edits: `GraphitronType.java:19`
   and `HasInputRecordShape.java:14-19`. **Keep the shared machinery**:
   `TypeBuilder.resolveInputFields` + `InputFieldsResolution` (also feeds the
   field-derived DELETE/INSERT/UPDATE paths, `FieldBuilder.java:5275/:5611`),
   `GraphitronSchemaValidator.collectInputFieldRejections` /
   `validateInputFieldRecursive` (the validator-mirror obligation, same
   callers), `EnumMappingResolver.buildLookupBindings` (already takes a plain
   `List<InputField>`), and the `ArgumentRef.InputTypeArg.TableInputArg`
   carrier (per D3).
6. **Consumer-site cascade**, driven by the exhaustive-switch compile errors
   (~20 sites, `grep -rn TableInputType graphitron*/src/main`): mechanical
   twin-arm deletions (`CompileDependencyGraphBuilder.java:132`,
   `InputTypeGenerator.java:59`, `InputRecordGenerator.java:124`);
   `DmlWalkerInputArgResolution` collapses to `RawArg | Rejected` and the stale
   "`@mutation` fields only accept `@table` input arguments" message
   (`FieldBuilder.java:5060-5066`) rewrites; the write-target resolvers lose
   rung 3 (per D4); the `InputBeanResolver` arm deletes (per D5). Two sites the
   compiler will *not* flag, handled deliberately:
   `MutationInputResolver.singleTableInputType` (raw-directive rung 3, per D4)
   and the `TypeClassification.TableInput` cascade (per D6). Comment/javadoc
   sweep across the ~15 prose-only mentions; `ArgumentRef.java:290`'s
   `{@link TypeBuilder#buildTableInputType}` must be repointed or the `verify`
   javadoc gate fails.
7. **Test surface**: `TableOnInputDeprecationWarningTest` retires, replaced by
   pipeline tests pinning the D1 rejection (per-verb migration text; the filter
   case; rejection fires on the type not per-consumer). The input-carrier case
   of `RecordDirectiveIgnoredWarningTest` retires. Bridge-pinning arms of
   `MutationTableArgClassificationTest` (rung-3 precedence, outranking,
   cross-check cases) retire; the two-rung ladder cases survive. The
   grounded==classified enforcer re-pins over the remaining rungs.
8. **Re-scope R337** from Backlog tombstone to a live item scoped to the
   per-coordinate input-field surfacing residual (per D6), same commit series.
9. **Docs.**
   - `docs/manual/reference/directives/table.adoc` (the `on` listing note and
     the deprecation WARNING block become the retired-location statement),
     `deprecations.adoc` (row flips Deprecated → Removed),
     `mutation.adoc` (rung-3 mentions; `@mutation(table:)` / return-derivation
     become the only paths), `record.adoc` (the shadowed-by-`@table` bullet
     narrows to OBJECT).
   - `docs/manual/how-to/map-types-to-tables.adoc` and `condition-cascade.adoc`
     (both teach `@table`-input filters/mutations as current),
     `tutorial/05-mutations.adoc` (INSERT/UPDATE examples).
   - `docs/architecture/reference/code-generation-triggers.adoc` (the
     `TableInputType` row) and `argument-resolution.adoc` (~15 mentions of the
     `@table`-input classification narrative).
   - `docs/manual/_generated/supported-schema-shapes.adoc` is **generated**
     from sealed-leaf javadoc by the roadmap-tool `leaf-coverage` mode —
     regenerate after the leaf edits, never hand-edit.
   - Lighter-touch grep hits: `batch-lookups.adoc`, `migrating-from-legacy.adoc`,
     `add-custom-conditions.adoc`, `result-types.adoc`,
     `multi-hop-nodeid-filter.adoc`, `directives/{asFacet,field,condition}.adoc`.
   - `roadmap-tool/src/main/resources/legacy-directives.graphqls` (legacy
     generator reference copy) is untouched.

## Acceptance

- Any `@table` on an `input` is a classify-time typed rejection carrying the
  per-verb migration guidance (D1); the directive declaration retains
  `INPUT_OBJECT` per the retired-location convention (the Backlog body's
  parse-error framing is superseded by D1).
- `GraphitronType.TableInputType` and both bridge methods are gone;
  `grep -rn TableInputType graphitron*/src/main` returns nothing (prose swept
  along with the code).
- All fixture SDL migrated; the cross-table-reuse pipeline pin (Phase A step 3)
  is in place.
- The LSP drop is deliberate: type-level hover survives via
  `PojoInput.resolvedTables`, and R337 is re-scoped to the input-field
  coordinate residual in the same slice.
- Classifier and grounder agree (rung-3 deleted on both; enforcer re-pinned).
- Full reactor green under `mvn install -Plocal-db` (sakila Java-17 compile +
  PostgreSQL execution tier), including the `verify` javadoc reference gate.

## Retired vocabulary

- `TableInputType` (the `GraphitronType` variant; as prose, "table input type"
  as a classification concept)
- `TypeBuilder.buildTableInputType`, `GraphitronSchemaValidator.validateTableInputType`
- `TypeClassification.TableInput` (and the LSP label `TableInput`)
- `emitTableOnInputDeprecationWarnings` (the deprecation-warning mechanism;
  the *rejection* replaces it)
- "the deprecated input `@table` bridge" / "rung 3" as a live mechanism
  (historical mentions in `changelog.md` stay)

## Out of scope

- `@table` on `OBJECT` / `INTERFACE` (load-bearing `TableType` /
  `TableInterfaceType`), including the surviving directive declaration itself.
- Phase 4 housekeeping (R520): changelog migration note, dropping `@table` from
  the LSP's `INPUT_OBJECT` completion list (still real under D1 — the location
  stays declared, so completion must be suppressed deliberately), residual docs
  guidance.
- The `ArgumentRef.InputTypeArg.TableInputArg` carrier and the field-derived
  paths that build it.
- R337's residual itself (re-scoping it is in scope; implementing it is not).

## Rationale

The fact-model framing this removal rests on ("the input type is not an entity;
its table is a property of the consuming field's fact") lives in the permanent
concept explainer
[`concepts/consumer-derived-input-tables.html`](concepts/consumer-derived-input-tables.html),
which survives R97's Done deletion. In short: `@table` on an input duplicates a
signal already derivable from the consuming field's return-type table, the same
redundancy `@record` was (R96). With consumer-derived resolution complete (R97
Phase 2 + 2b, R457/R515), the directive drives nothing and can be removed.
