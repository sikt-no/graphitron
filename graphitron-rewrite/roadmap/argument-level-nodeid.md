---
id: R40
title: "Argument-level `@nodeId` support"
status: Spec
bucket: architecture
priority: 2
theme: nodeid
depends-on: []
---

# Argument-level `@nodeId` support

`@nodeId` is declared on `ARGUMENT_DEFINITION` in [`directives.graphqls`](../graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls) but `FieldBuilder.classifyArgument` ([line 767](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java)) never inspects it. The post-R50 scalar-`ID` block at lines 836-872 keys only on the parent table being a `NodeType`; it does not read `@nodeId` or its `typeName:`. A list-typed `[ID!] @nodeId(typeName: T)` filter arg therefore hits the explicit gap at line 852 ("List + arity-1 without @lookupKey is not yet wired"), falls through to the column-binding path at line 874, and surfaces as `column 'ider' could not be resolved in table 'kompetanseregelverk'`. A scalar `ID @nodeId(typeName: WrongType)` silently routes through the implicit-same-table arm (the directive is ignored), which is also wrong even when nothing crashes. Reproducer from opptak: `kompetanseregelverkGittIdV2(ider: [ID!]! @nodeId(typeName: "Kompetanseregelverk")): [Kompetanseregelverk!] @asConnection`.

## Foundation in place

R50 shipped `CallSiteExtraction.NodeIdDecodeKeys` (sealed into `SkipMismatchedElement` / `ThrowOnMismatch`), the column-shaped composite-key carriers (`ColumnArg`, `CompositeColumnArg`), the four-arm `BodyParam.ColumnPredicate` (`Eq` / `In` / `RowEq` / `RowIn`), and the per-NodeType `decode<TypeName>` helpers as `HelperRef.Decode`. The body emitter `TypeConditionsGenerator` already handles all four `ColumnPredicate` arms ([lines 122-155](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeConditionsGenerator.java)).

R40 wires the classifier and one missing projection arm onto that foundation. No new model variants, no new emitter arms, no new validator arms. The R50 changelog framed R40 as a "classifier-only follow-on"; the projection-arm extension came into view once we read the post-R50 `projectFilters` arm closely (see Projection below) and is small.

## Scope

Same-table only: `@nodeId` (with or without `typeName: T`) on a top-level argument where the resolved `T` is the field's own backing table. Three argument shapes ship together:

- `[ID!]` / `[ID!]!` (list filter): the primary opptak case. Produces `BodyParam.In` (single-PK) or `BodyParam.RowIn` (composite-PK).
- `ID` / `ID!` (scalar with explicit `@nodeId`): tightens today's "directive ignored" implicit-arm behaviour by routing declared `@nodeId(typeName:)` through the strict resolver. Produces `BodyParam.Eq` / `BodyParam.RowEq`.
- Bare `@nodeId` (no `typeName:`) on either shape: defaults `T` to the field's backing-table NodeType, mirroring `buildNodeIdOutputCarrier`'s implicit-same-table semantics on the output side.

FK-target args (declared `T` resolves to a different table; runtime emits an FK-mirror or join-with-projection predicate) are filed as a sibling Backlog item. The shape is "ColumnReferenceField-style argument with an FK path plus `NodeIdDecodeKeys.SkipMismatchedElement`"; the moving parts already exist on the output side via `buildNodeIdReferenceCarrier` ([FieldBuilder.java:2678-2705](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java)) and on R24's expanded scope.

The existing implicit scalar-same-table arm at lines 836-872 (no `@nodeId` declared, parent table is a NodeType) stays untouched: it covers the synthesised path that `buildLookupBindings` and the `id`-arg shorthand already rely on. R40's new arm fires only when `arg.hasAppliedDirective(DIR_NODE_ID)`.

## Classification

New arm in `FieldBuilder.classifyArgument`, sitting between the input-type arms (lines 791-824) and the existing scalar-`ID` block (line 836). Fires when `typeName == "ID"` and `arg.hasAppliedDirective(DIR_NODE_ID)`.

Steps:

1. Read `typeName:` via `argString(arg, DIR_NODE_ID, ARG_TYPE_NAME)`. Empty / absent → default to the field's backing-table NodeType (look it up via `findGraphQLTypeForTable(rt.tableName())`; if zero or multiple `@table` types map to the table, reject as `UnclassifiedArg` with a "specify `typeName:` explicitly" hint).
2. Resolve `T` via `ctx.types.get(T)`. `ctx.types` is fully populated at this point (`GraphitronSchemaBuilder.buildSchema` line 163 sets it before line 179 begins per-field classification). Failure modes mirror `buildNodeIdReferenceCarrier`'s gate at [FieldBuilder.java:2555-2581](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java):
   - `T` not in schema → `UnclassifiedArg("@nodeId(typeName: '" + T + "') does not exist in the schema")`.
   - `T` not a `NodeType` → `UnclassifiedArg("@nodeId(typeName: '" + T + "') does not have @node")`.
   - `T` is a NodeType but `nodeType.table().tableName()` is not `rt.tableName()` → `UnclassifiedArg` pointing at the FK-target follow-on item by name. (Single-source resolution; no path resolution. FK navigation is the sibling item's job.)
3. Build the carrier from the resolved NodeType:
   - `keyColumns = nodeType.nodeKeyColumns()`, `decodeMethod = nodeType.decodeMethod()`.
   - `extraction = new CallSiteExtraction.NodeIdDecodeKeys.SkipMismatchedElement(decodeMethod)`. Filter mode: a malformed id in the input list is dropped, so the predicate matches only the well-formed subset (and an all-malformed list collapses to `noCondition()` on the empty-list guard, returning every row, which is the intended filter semantics).
   - `isLookupKey = arg.hasAppliedDirective(DIR_LOOKUP_KEY)`. The `@lookupKey` policy below rejects the combination, so in practice this is always `false`; the slot is set faithfully so the lookup-vs-filter projection routing stays type-driven.
   - Arity-1 → `ArgumentRef.ScalarArg.ColumnArg(name, "ID", nonNull, list, keyColumns.get(0), extraction, argCondition, fieldOverride, isLookupKey)`.
   - Arity ≥ 2 → `ArgumentRef.ScalarArg.CompositeColumnArg(name, "ID", nonNull, list, keyColumns, extraction, argCondition, fieldOverride, isLookupKey)`.

The existing implicit scalar-`ID` block at lines 836-872 stays as-is and continues to handle the no-directive-declared case. The new arm is checked first so an explicit `@nodeId(typeName: T)` always takes the strict path (and rejects on T mismatch) rather than silently falling into the implicit-same-table arm.

## Projection

The `ColumnArg` arm in `projectFilters` ([FieldBuilder.java:1271-1283](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java)) already emits `BodyParam.In` (list) / `BodyParam.Eq` (scalar) and reads `ca.extraction()` directly. The new same-table arm produces a `ColumnArg` that satisfies that arm's contract; no projection change for arity-1.

The `CompositeColumnArg` arm at lines 1285-1292 currently only forwards `argCondition` and carries an explicit comment that "the classifier rejects non-lookup-key composite-PK args as `UnclassifiedArg`, so they never reach this branch with `isLookupKey == false`". R40 invalidates that comment. The arm extends to:

```
case ArgumentRef.ScalarArg.CompositeColumnArg cca -> {
    boolean autoSuppressed = cca.suppressedByFieldOverride()
        || (cca.argCondition().isPresent() && cca.argCondition().get().override());
    if (!autoSuppressed && !cca.isLookupKey()) {
        bodyParams.add(cca.list()
            ? new BodyParam.RowIn(cca.name(), cca.keyColumns(), "org.jooq.RowN", cca.nonNull(), cca.extraction())
            : new BodyParam.RowEq(cca.name(), cca.keyColumns(), "org.jooq.RowN", cca.nonNull(), cca.extraction()));
    }
    cca.argCondition().ifPresent(ac -> argConditions.add(ac.filter()));
}
```

Mirrors the `ColumnArg` arm's auto-suppression / `@lookupKey`-skip pattern. `javaType = "org.jooq.RowN"` matches the convention `compositeImplicitBodyParam` already uses for the input-field path (FieldBuilder.java:1437-1446), so `TypeConditionsGenerator`'s `RowEq` / `RowIn` arms emit the same `DSL.row(...).eq(...)` / `.in(...)` shape regardless of source.

Call-site extraction: the top-level extraction `NodeIdDecodeKeys.SkipMismatchedElement` is read directly into `CallParam.extraction` ([line 1302](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java)). It is not wrapped in `NestedInputField` (that wrapping is only for input-field leaves). The condition method receives a typed `List<KeyType>` (single-PK) or `List<RowN>` (composite-PK); the malformed-element skip happens in the per-element decode at the call site, before the list reaches the condition method.

## Composition with other directives

- **`@asConnection`.** `GraphitronSchemaBuilder.rewriteCarrierField` runs after classifyArguments (`buildSchema` line 179 → 181-182 → `SchemaTransformer.transformSchema` at line 248 → `rewriteCarrierField` at lines 266-280). User-declared filter args survive the carrier rewrite via `transform`'s builder-copy. Filter and `PaginationSpec` compose: filter narrows, seek paginates within. The opptak reproducer ships as an execution-tier case.
- **`@lookupKey` + R40 arm.** `@nodeId(typeName: T) @lookupKey` rejects at classify time as `UnclassifiedArg`. Reasoning: same-table primary-key matching is the filter shape; `@lookupKey` requests a per-key dispatch via `LookupMapping` and only makes sense when the keys live somewhere other than the field's own PK. R50's `LookupMapping`-over-PK collapse routes the existing `ID @lookupKey` shorthand without `@nodeId`; it stays untouched.
- **`@condition`.** Column-shaped, so `argCondition` composes via the same path as any other column-bound arg. The auto-suppression check (`suppressedByFieldOverride` / `argCondition.override`) carries over verbatim from the `ColumnArg` arm.
- **`@field(name:)`.** The new arm bypasses `@field(name:)` entirely (key columns come from `nodeType.nodeKeyColumns()`, not from the directive). `@nodeId` + `@field` together rejects at classify time as `UnclassifiedArg("@nodeId arg cannot also carry @field(name:)")`; the directives target different binding axes.

## Validator and load-bearing guarantees

No new validator arm. The `BodyParam.RowIn` and `BodyParam.In` variants ship with R50's emitter and are already covered. `validateVariantIsImplemented` keys on `GraphitronField` subtype, not on argument extraction, so `NodeIdDecodeKeys.SkipMismatchedElement` on a top-level filter arg does not need a separate dispatched-leaf entry.

No new `@LoadBearingClassifierCheck` key. The four keys on trunk today (`error-type.path-message-fields`, `column-field-requires-table-backed-parent`, `service-catalog-strict-service-return`, `service-catalog-strict-tablemethod-return`) are emitter-coupling guarantees of the form "classifier rejects X so emitter can assume non-X". R40's classifier produces `ColumnArg` / `CompositeColumnArg` shapes the emitter already handles for the input-field path; the SkipMismatchedElement-vs-ThrowOnMismatch distinction is read by the call-site decode emitter, which already exhaustively switches on both arms. The R50 changelog mentioned three nodeid-shaped keys (`nodeid.decode.failure-mode`, `columnpredicate.column-arity`, `compaction.encode-keys`); none of them landed as annotations on trunk. R40 inherits that decision rather than reopening it; if a load-bearing key proves warranted later it is a small follow-on, not a blocker.

`SCALAR_NODEID_NON_LOOKUP_COMPOSITE_PK_DEFERRED` in `NodeIdPipelineTest.LookupKeyCase` ([line 795](../graphitron/src/test/java/no/sikt/graphitron/rewrite/NodeIdPipelineTest.java)) currently pins the "composite-PK non-`@lookupKey` returns `UnclassifiedArg`" behaviour. R40 flips it: the assertion changes from "rejected" to "classified as `CompositeColumnArg` with `SkipMismatchedElement`".

## Tests

Pipeline-tier in `NodeIdPipelineTest`, new `ArgumentSameTableNodeIdCase` parallel to the existing `InputSameTableNodeIdCase`:

- `LIST_SINGLE_PK` — `bazByIds(ids: [ID!]! @nodeId(typeName: "Baz"))`. Asserts `ColumnArg` with `extraction = NodeIdDecodeKeys.SkipMismatchedElement`, `column = baz.id`, `list = true`. `WhereFilter` shape is `BodyParam.In`.
- `LIST_COMPOSITE_PK` — `barByIds(ids: [ID!]! @nodeId(typeName: "Bar"))`. Asserts `CompositeColumnArg`, `keyColumns = [bar.id_1, bar.id_2]`. `WhereFilter` shape is `BodyParam.RowIn`.
- `SCALAR_SINGLE_PK` — `bazById(id: ID! @nodeId(typeName: "Baz"))`. `ColumnArg`, `BodyParam.Eq`.
- `SCALAR_COMPOSITE_PK` — `barById(id: ID! @nodeId(typeName: "Bar"))`. `CompositeColumnArg`, `BodyParam.RowEq`.
- `BARE_NODEID_DEFAULTS_TO_BACKING_TABLE` — `bazByIds(ids: [ID!]! @nodeId)`. Asserts the typeName-omitted form resolves to `Baz` via `findGraphQLTypeForTable("baz")` and produces the same shape as `LIST_SINGLE_PK`.
- `T_MISSING` — `... @nodeId(typeName: "DoesNotExist")` → `UnclassifiedArg` with "does not exist in the schema".
- `T_NOT_NODE` — point at a non-`@node` `@table` type → `UnclassifiedArg` with "does not have @node".
- `T_DIFFERENT_TABLE` — point at a NodeType backed by a different table → `UnclassifiedArg` with the FK-target follow-on hint.
- `LOOKUP_KEY_REJECTED` — `[ID!]! @nodeId(typeName: "Baz") @lookupKey` → `UnclassifiedArg`.
- `FIELD_DIRECTIVE_REJECTED` — `[ID!]! @nodeId(typeName: "Baz") @field(name: "id")` → `UnclassifiedArg`.

Update `LookupKeyCase.SCALAR_NODEID_NON_LOOKUP_COMPOSITE_PK_DEFERRED` to expect the new classified shape rather than the deferred rejection.

Compilation-tier: `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` against the new query fields confirms the generated `*Conditions` class compiles against real jOOQ for both single-PK and composite-PK shapes.

Execution-tier in `NodeIdQueryTest` (or `GraphQLQueryTest` if the existing nodeidfixture-driven cases live there), against PostgreSQL via `-Plocal-db`:

- `bazByIds_filterByIds_returnsExactlyMatchingRows` — supply two of three ids, expect those two rows.
- `bazByIds_emptyList_returnsAllRows` — empty list → `noCondition()` short-circuit.
- `bazByIds_malformedIdSkipped` — mixed list with one malformed id; assert the row corresponding to the well-formed id returns and no exception surfaces.
- `barByIds_compositeFilterByIds_returnsExactlyMatchingRows` — composite-PK row-IN.
- `bazByIdsWithPagination_filterAndSeekCompose` — opptak reproducer shape with `first` / `after`. Asserts the paginated subset of the filtered set, single SELECT.
- `ExecuteListener` SQL inspection on each: predicate is `c.in(...)` / `(c1, c2) IN (...)` rather than `NodeIdEncoder.hasIds(...)` (which the post-R50 encoder no longer exposes).

`nodeidfixture` covers both shapes already (`baz` single-PK with `__NODE_TYPE_ID = "Baz"`, `bar` composite-PK with `__NODE_KEY_COLUMNS = {ID_1, ID_2}`). New schema fields land on `graphitron-test/src/main/resources/graphql/nodeid-schema.graphqls` (or wherever `bazByIds` peers live; pin the file in the implementation commit).

## Out of scope and follow-on items

- **FK-target args.** `@nodeId(typeName: T)` where T's table differs from `rt.tableName()`. Sibling Backlog item to file when R40 lands; the shape is "ColumnReferenceField-style argument with FK path plus `NodeIdDecodeKeys.SkipMismatchedElement`", reusing `buildNodeIdReferenceCarrier`'s FK-mirror collapse and join-with-projection logic from R24's expanded scope.
- **Mutation-key `@nodeId` args.** Mutation arguments classify through `classifyMutationArguments` (separate from `classifyArguments`) per the comment at line 2007. Filed separately if/when needed.
- **A load-bearing key for the filter-vs-lookup failure-mode split.** Mentioned for visibility only; the existing exhaustive switch on `NodeIdDecodeKeys` arms covers the contract today without an annotation pair.
