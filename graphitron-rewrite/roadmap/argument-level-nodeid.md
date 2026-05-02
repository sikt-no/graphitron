---
id: R40
title: "Argument-level `@nodeId` support"
status: In Review
bucket: architecture
priority: 2
theme: nodeid
depends-on: []
---

# Argument-level `@nodeId` support

## Implementation notes (in review)

Shipped scope and deviations from the original spec, captured for the reviewer:

- **`NodeIdLeafResolver`** lifted (Step 1, commit `ebbdbfa`). Returns `Resolved.{SameTable, FkTarget, Rejected}`; the `decodeMethod` is exposed directly rather than pre-wrapped in a `CallSiteExtraction` arm so callers pick the failure mode (input-field side wraps in `SkipMismatchedElement`; argument side picks `ThrowOnMismatch` for lookups, `SkipMismatchedElement` for FK-target filters). This deviates from the spec's "extraction inside the Resolved arm" signature but matches the actual call-site asymmetry.
- **Argument-side classification + projection** (Step 2, commit `fbbe418`). Same-table arg `@nodeId` produces `ColumnArg` / `CompositeColumnArg` with `isLookupKey: true` and `extraction: ThrowOnMismatch` — matching the existing `@lookupKey` dispatch contract rather than the spec's `SkipMismatchedElement`. Reason: `LookupValuesJoinEmitter`'s per-row decode loop in `addRowBuildingCore` is hardcoded to throw on null. Adapting it to skip is a separate emitter change; deferred.
- **FK-target arg arm** (Step 2). Ships only for the simple direct-FK case (FK target columns positionally match NodeType key columns, so `BodyParam.In` / `Eq` / `RowIn` / `RowEq` can fire against `joinPath[0].sourceColumns()` directly). Pathological cases (`parent_node` + `child_ref` shape) reject at classify time with a deferred-emission hint pointing at R57.
- **Validator rejection** (Step 3, commit `fbbe418`). `@asConnection` + same-table `@nodeId` rejected symmetrically across argument-level and input-field leaves. Closes the latent R50 gap.
- **Lookup-promotion gate** (commit `c9722e0`). Added `FieldBuilder.hasSameTableNodeIdAnywhere` so query fields with same-table `@nodeId` args promote to `QueryLookupTableField` parallel to `@lookupKey`. Without this gate the carrier classifies as `ColumnArg` with `isLookupKey: true` but the field stays a regular `QueryTableField`, leaving the `LookupMapping` unused.
- **Java 17 emitter fix** (commit `14af235`). Surfaced an R50-era latent bug in `LookupValuesJoinEmitter` and `ArgCallEmitter`: parameterised `instanceof Record1<Integer> _r` patterns require Java 21+; cast to `Object` first to make the pattern conditional and emit raw `Record1` then cast `value1()` to the column type.
- **Pipeline-tier coverage**: 14 new cases under `ArgumentSameTableNodeIdCase` (8), `ArgumentFkTargetNodeIdCase` (3), `NodeIdConnectionRejectionCase` (3); see `NodeIdPipelineTest`.
- **Execution-tier coverage**: `films_filteredByArgNodeId_returnsRowsMatchingDecodedIds` round-trips a real PostgreSQL query.
- **Spec test flip not landed**: `LookupKeyCase.SCALAR_NODEID_NON_LOOKUP_COMPOSITE_PK_DEFERRED` stays unflipped because its SDL doesn't carry `@nodeId` (the case exercises the legacy implicit scalar-`ID` arm, which "stays untouched per scope"). The spec's claim that R40 flips it was inaccurate.
- **Follow-on items filed**: `R57 nodeid-fk-target-arg-join-translation` for the pathological FK-target case.

The original design-and-rationale text below is preserved as historical context. Reviewer should focus on whether the shipped subset is coherent (it does the spec's two-arm shape decision and validator rejection symmetrically) and whether the deferred subset is filed cleanly.

---

`@nodeId` is declared on `ARGUMENT_DEFINITION` in [`directives.graphqls`](../graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls) but `FieldBuilder.classifyArgument` never inspects it. Args with `@nodeId(typeName: T)` fall through to non-nodeid arms; a scalar `ID @nodeId(typeName: T)` silently routes through the implicit scalar-`ID` arm (directive ignored, even when `T` is wrong), and `[ID!] @nodeId(typeName: T)` surfaces as `column 'ider' could not be resolved in table '...'` from the column-binding path.

`@nodeId(typeName: T)` on an argument has two semantically distinct shapes, depending on `T`'s table relative to the field's backing table:

- **Same-table** (`T.table() == field.backingTable()`). The argument supplies encoded ids of the field's own rows. This is a *lookup by definition*: cardinality is bounded by the input list, ordering reflects input membership, and there is no result set to seek through. Implies `@lookupKey`.
- **FK-target** (`T.table()` reachable from the field's backing table via FK). The argument supplies encoded ids of a related table; the predicate is "row's FK column ∈ decoded keys". This is a *filter*. Composes with `@asConnection`.

`@asConnection` composes with the FK-target shape but is incoherent with same-table — you cannot seek-paginate a result whose membership is dictated by a caller-supplied id list. The opptak reproducer `kompetanseregelverkGittIdV2(ider: [ID!]! @nodeId(typeName: "Kompetanseregelverk")): [Kompetanseregelverk!] @asConnection` is the same-table-under-connection case and must be rejected at validate time.

## Foundation in place

R50 shipped, in addition to `CallSiteExtraction.NodeIdDecodeKeys` (sealed into `SkipMismatchedElement` / `ThrowOnMismatch`), `BodyParam.ColumnPredicate` (`Eq` / `In` / `RowEq` / `RowIn`), and per-NodeType `decode<TypeName>` helpers as `HelperRef.Decode`, the *complete input-field side* of `@nodeId` resolution at [`BuildContext.classifyInputField:916-1043`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java):

- **Same-table** (lines 916-965) → `InputField.ColumnField` (arity-1) or `InputField.CompositeColumnField` (arity ≥ 2), with `decodeMethod` and `extraction = NodeIdDecodeKeys.SkipMismatchedElement`.
- **FK-target** (lines 972-1043) → `InputField.ColumnReferenceField` (arity-1) or `InputField.CompositeColumnReferenceField` (arity ≥ 2), with the same decode/extraction *plus* a resolved `joinPath: List<JoinStep>` (single-hop FK auto-discovered or pinned by `@reference(path:)`).

The `TypeConditionsGenerator` body emitter handles all four `ColumnPredicate` arms; the input-side filter projection threads `joinPath` through the surrounding query context for FK-target leaves.

R6 also shipped: ten cross-cutting concerns lifted out of `FieldBuilder` into standalone resolvers returning sealed `Resolved` results (`InputFieldResolver`, `LookupMappingResolver`, `ConditionResolver`, `OrderByResolver`, ...) sibling to `ArgumentRef`'s sealed-variant precedent. R40 lands as an eleventh resolver in this pattern: a new `NodeIdLeafResolver` consumed by both `InputFieldResolver` (replacing the inline body at `classifyInputField:916-1043`) and `FieldBuilder.classifyArgument`. The `BuildContext.resolveDecodeHelperForTable` helper colocates with the resolver since both call sites converge on it (only callers today are `classifyInputField:946`, `:1181`, `:1241` and the existing scalar-`ID` arm at `FieldBuilder:533`; once the resolver owns them, `BuildContext` keeps only the lower-level decode-helper construction).

R40 plugs `classifyArgument` into the new resolver, mirrors the carrier shape on the arg side via new sealed `ArgumentRef.ScalarArg` variants for the FK-target case, and adds the symmetric `@asConnection` validator rejection that closes a latent R50 gap (input-field same-table `@nodeId` under `@asConnection` builds today and produces incoherent runtime semantics).

## Scope

Both same-table (lookup) and FK-target (filter) arms ship together, for both scalar and list arities, plus the symmetric `@asConnection` validator rule.

**Same-table arm.** `ID @nodeId(typeName: T)` / `[ID!] @nodeId(typeName: T)` where `T.table() == field.backingTable()`. Implies `@lookupKey`: routes through R50's existing `LookupMapping`-over-PK collapse without a separate filter projection. Single-PK and composite-PK both handled by R50.

**FK-target arm.** `ID @nodeId(typeName: T)` / `[ID!] @nodeId(typeName: T)` where `T.table()` is reachable from the field's backing table via single-hop FK (auto-discovered or pinned by `@reference(path:)`). Mirrors the input-field FK-target carrier on the arg side via two new sealed variants (`ColumnReferenceArg`, `CompositeColumnReferenceArg`) and feeds the same input-side filter projection that `InputField.ColumnReferenceField` / `CompositeColumnReferenceField` use today. Composes with `@asConnection`.

**Implementation note (shipped subset).** The FK-target arm ships in this iteration only for the simple direct-FK case where the FK's target columns positionally match the resolved NodeType key columns (so the predicate can fire against `joinPath[0].sourceColumns()` on the field's own table without a JOIN). Pathological cases where the FK target columns differ from the NodeType key columns (e.g. R50's `parent_node` + `child_ref` fixture: FK targets `parent.alt_key`, NodeType key is `parent.pk_id`) are rejected at classify time with a deferred-emission hint. JOIN-with-translation emission for the pathological case lands in R57, parallel to R24's output-side JOIN-with-projection arm.

**Bare `@nodeId`** (no `typeName:`) defaults to the field's backing-table NodeType via the same inference rule R50's `BARE_LIST_NODE_ID_INFERS_TYPE_NAME` case applies on input fields (rejecting on ambiguity / no-match).

**Out of scope.** Multi-hop FK-target / condition-join FK-target (parallel to R24's multi-hop arm on the output side; sibling Backlog item). FK-target where FK target columns ≠ NodeType keyColumns (R57). Mutation-key `@nodeId` args (separate `classifyMutationArguments` path; `NodeIdLeafResolver`'s signature is the natural extension point — the follow-on adds a `FailureMode` parameter selecting `ThrowOnMismatch` vs `SkipMismatchedElement` rather than re-resolving from scratch, but the carrier-side wiring on the mutation path is genuinely different per R50's `MutationField.DmlTableField.nodeIdMeta` shape).

The existing implicit scalar-`ID` arm in `classifyArgument` (no `@nodeId` declared, parent table is a NodeType) stays untouched: it covers synthesised paths that `buildLookupBindings` and the `id`-arg shorthand rely on. R40's new arm fires only when `arg.hasAppliedDirective(DIR_NODE_ID)`.

## Classification

### Step 1: Extract `NodeIdLeafResolver`

Extract the body of `BuildContext.classifyInputField:916-1043` into a new `NodeIdLeafResolver` standalone class, sibling to `InputFieldResolver` / `LookupMappingResolver` / `ConditionResolver` / etc. (R6 pattern: each resolver is its own file with its own focused test surface; sealed `Resolved` over enums per *Sealed hierarchies over enums for typed information*). `BuildContext.resolveDecodeHelperForTable` moves into the new resolver in the same commit since the resolver is the only consumer post-lift.

```
public final class NodeIdLeafResolver {
    public sealed interface Resolved {
        record SameTable(HelperRef.Decode decodeMethod,
                         List<ColumnRef> keyColumns,
                         CallSiteExtraction.NodeIdDecodeKeys extraction)
            implements Resolved {}
        record FkTarget(HelperRef.Decode decodeMethod,
                        List<ColumnRef> keyColumns,
                        List<JoinStep> joinPath,
                        CallSiteExtraction.NodeIdDecodeKeys extraction)
            implements Resolved {}
        record Rejected(String message) implements Resolved {}
    }

    Resolved resolve(String leafName,
                     GraphQLType leafType,
                     List<AppliedDirective> directives,
                     TableRef containingTable);
}
```

The signature consumes raw `GraphQLType` + `List<AppliedDirective>` (pre-carrier) deliberately: both call sites (`InputFieldResolver` for input-field leaves, `FieldBuilder.classifyArgument` for arg leaves) need the resolver *during* carrier construction, not over an already-classified shape. This is the inverse of R6's `cea16e0` tightening that flipped `OrderByResolver` to consume `ArgumentRef.OrderByArg` directly; the asymmetric reason is that `OrderByResolver` had one downstream consumer, where `NodeIdLeafResolver` has two and feeds carrier construction on both sides.

`InputFieldResolver`'s body is rewritten to call `NodeIdLeafResolver.resolve` and exhaustively switch on the result, wrapping into `InputField.ColumnField` / `CompositeColumnField` / `ColumnReferenceField` / `CompositeColumnReferenceField` exactly as today; the bare-`@nodeId` typeName inference ([`NodeIdPipelineTest.java:616` `BARE_LIST_NODE_ID_INFERS_TYPE_NAME`](../graphitron/src/test/java/no/sikt/graphitron/rewrite/NodeIdPipelineTest.java)) lifts with the body. This refactor lands first in its own commit; the existing `InputSameTableNodeIdCase` and FK-target test cases at [`NodeIdPipelineTest.java:558-677`](../graphitron/src/test/java/no/sikt/graphitron/rewrite/NodeIdPipelineTest.java) act as the behaviour gate. A focused `NodeIdLeafResolverTest` parallel to other resolver tests lands alongside, exercising the resolver against synthesised `GraphQLArgument` / `GraphQLInputObjectField` shapes directly.

### Step 2: Plug `classifyArgument` into the resolver

New arm in `FieldBuilder.classifyArgument` ([FieldBuilder.java:438-584](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java)), fires when `arg.getType()` unwraps to `ID` and `arg.hasAppliedDirective(DIR_NODE_ID)`. Sits *before* the existing implicit scalar-`ID` arm at lines 514-549 (which stays untouched per Scope). The arm calls `nodeIdLeafResolver.resolve(arg.getName(), arg.getType(), arg.getAppliedDirectives(), fieldReturnTable)` and switches on the result:

- `SameTable` → arity-1 `ArgumentRef.ScalarArg.ColumnArg(name, "ID", nonNull, list, keyColumns.get(0), extraction, argCondition, suppressedByFieldOverride, isLookupKey: true)`; arity ≥ 2 `CompositeColumnArg(..., isLookupKey: true)`. The classifier *synthesises* `isLookupKey = true` because same-table `@nodeId` is a lookup by definition. `argCondition` consumes `ConditionResolver.ArgConditionResult.Ok`'s `ArgConditionRef` payload; `Rejected` propagates as `UnclassifiedArg`. `suppressedByFieldOverride` carries field-level `@field` override status from the existing classify-arg pipeline (per `ColumnArg`'s "four-state projection table" javadoc).
- `FkTarget` → arity-1 `ArgumentRef.ScalarArg.ColumnReferenceArg(name, "ID", nonNull, list, keyColumns.get(0), joinPath, extraction, argCondition, suppressedByFieldOverride)`; arity ≥ 2 `CompositeColumnReferenceArg(..., joinPath, ...)`. These are *new sealed variants* added to `ArgumentRef.ScalarArg`, mirroring `InputField.ColumnReferenceField` / `CompositeColumnReferenceField` shape-for-shape (sealed-split per *Sealed hierarchies over enums for typed information*). No `isLookupKey` slot: FK-target is a filter, not a lookup.
- `Rejected(message)` → `UnclassifiedArg(message)`.

Composition rejections issued by `NodeIdLeafResolver.resolve`:

- `@nodeId @lookupKey` → `Rejected("@nodeId implies @lookupKey for same-table; the explicit directive is redundant")`.
- `@nodeId @field(name:)` → `Rejected("@nodeId arg cannot also carry @field(name:); the directives target different binding axes")`.
- Bare `@nodeId` with ambiguous `@table` mapping → `Rejected("Bare @nodeId is ambiguous: multiple @table-bound types match '<rt.tableName()>'; specify @nodeId(typeName: …) explicitly")`.
- `T` not in schema / not a NodeType / table not reachable via single-hop FK → `Rejected` with the corresponding hint, including the multi-hop follow-on item name when reachability fails past one hop.

## Projection

**Same-table arm.** `isLookupKey: true` routes the `ColumnArg` / `CompositeColumnArg` through `LookupMappingResolver.projectForLookup` ([LookupMappingResolver.java:62-79](../graphitron/src/main/java/no/sikt/graphitron/rewrite/LookupMappingResolver.java)) which already pattern-matches `ColumnArg when ca.isLookupKey()` → `ScalarLookupArg` and `CompositeColumnArg when cca.isLookupKey()` → `DecodedRecord`. The resolver does not read the SDL `@lookupKey` directive; it consumes the classifier-synthesised `isLookupKey` boolean. R40 needs zero extension to `LookupMappingResolver` — `@nodeId`-synthesised `isLookupKey: true` carriers are picked up identically to `@lookupKey`-synthesised ones. The `projectFilters` arms for these carriers skip when `isLookupKey == true` (existing comment at [FieldBuilder.java:762-767](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java) on `CompositeColumnArg` stays accurate). No projection change.

The `LookupMapping` projection consumes `extraction = NodeIdDecodeKeys.SkipMismatchedElement` and the `decodeMethod` directly; the per-element decode at the call site produces the typed key (single-PK) or `RowN` (composite-PK) that the lookup dispatcher consumes. Same as the input-field same-table path today.

**FK-target arm.** Two new arms in `projectFilters` for the new sealed variants `ColumnReferenceArg` and `CompositeColumnReferenceArg`. They mirror the input-field FK-target projection at the corresponding `compositeImplicitBodyParam`-equivalent for input-field FK leaves: emit `BodyParam.In` / `BodyParam.RowIn` (list) or `BodyParam.Eq` / `BodyParam.RowEq` (scalar) against the FK columns reachable through `joinPath`, threading `joinPath` into the surrounding query-context emitter so the JOIN materialises in the generated SQL.

This is the only genuinely-new emitter-adjacent code in R40. It mirrors the input-field FK-target projection line-for-line; if a shared projection helper falls out naturally during implementation, take it.

Call-site extraction for both arms: top-level `NodeIdDecodeKeys.SkipMismatchedElement` is read directly into `CallParam.extraction` (no `NestedInputField` wrapping; that's input-field-leaf-only). The condition method receives a typed `List<KeyType>` (arity-1) or `List<RowN>` (arity ≥ 2); the malformed-element skip happens in the per-element decode at the call site, before the list reaches the condition method.

## Composition with other directives

- **`@asConnection` + same-table.** Rejected at validate time (see Validator section). The opptak reproducer ships as a rejection-tier case.
- **`@asConnection` + FK-target.** Composes. Filter narrows, seek paginates within the filtered set. `GraphitronSchemaBuilder.rewriteCarrierField` runs after `classifyArguments`; user-declared FK-target filter args survive the carrier rewrite via `transform`'s builder-copy.
- **`@lookupKey`.** Rejected at classify time alongside `@nodeId` for both arms. Reasoning: `@nodeId` same-table *implies* `@lookupKey` (redundant); `@nodeId` FK-target is a filter, not a lookup (meaningless). R50's `ID @lookupKey` shorthand without `@nodeId` is untouched.
- **`@condition`.** Composes via `ConditionResolver.resolveArg` ([ConditionResolver.java:96-118](../graphitron/src/main/java/no/sikt/graphitron/rewrite/ConditionResolver.java)) returning `ArgConditionResult.{None, Ok, Rejected}`; `Ok.ref()` projects to the carrier's `argCondition: Optional<ArgConditionRef>` slot exactly as `ColumnArg` / `CompositeColumnArg` consume it today. `Rejected` propagates as `UnclassifiedArg`. Field-level override (`suppressedByFieldOverride`) carries over from the `ColumnArg` / `CompositeColumnArg` arms to the new `ColumnReferenceArg` / `CompositeColumnReferenceArg` arms identically.
- **`@field(name:)`.** Rejected at classify time. Key columns come from `nodeType.nodeKeyColumns()`, not from the directive; the two target different binding axes.
- **`@reference(path:)`.** Composes with the FK-target arm to pin the FK chain explicitly when auto-discovery is ambiguous (mirrors input-field FK-target behaviour at `BuildContext.classifyInputField:972-1043`). Combination with same-table is rejected (no FK to pin).

## Validator and load-bearing guarantees

**New rejection: `@asConnection` + same-table `@nodeId` leaf.** Placed as a small validation step in the post-`classifyArguments` pipeline (R6 framing: `classifyField` is "a thin orchestrator that calls a fixed pipeline of resolvers"; this rejection consumes `NodeIdLeafResolver`'s output uniformly across both arg leaves and input-field leaves rather than re-walking the schema inline). The step runs only when the field carries `@asConnection` (cheap early-out next to the existing `@asConnection on inline TableField` wrapper at [FieldBuilder.java:390-392](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java)) and walks the field's argument set — top-level args *and* nested input-field leaves under arg-input-types — calling `nodeIdLeafResolver.resolve` for each `@nodeId`-decorated leaf. If any resolves to `Resolved.SameTable`, return `UnclassifiedField` with `RejectionKind.INVALID_SCHEMA` and the hint:

> "@nodeId(typeName: '<T>') resolves to '<field-backing-table>', the field's own backing table, which makes this argument a lookup key. Lookups don't compose with @asConnection (the result cardinality is bounded by the input list, not paginatable). Drop @asConnection, or use a filter argument that resolves to a different table via FK."

This rejection is *symmetric*: it catches both R40-introduced argument-level same-table `@nodeId` AND R50-shipped input-field same-table `@nodeId`, closing a latent R50 gap where the combination silently builds and produces incoherent runtime semantics. FK-target leaves do not trigger the rejection — they are legitimate filter args.

**No new `@LoadBearingClassifierCheck` key.** `NodeIdLeafResolver` does not introduce new emitter-coupling invariants beyond what R50's input-field path already implies (matching PK arity, real FK chain, `decode<T>` helper exists). Lifting the resolver out doesn't relax those guarantees. Explicit annotation pairs are a sibling cleanup if wanted later.

**Existing test update.** `NodeIdPipelineTest.LookupKeyCase.SCALAR_NODEID_NON_LOOKUP_COMPOSITE_PK_DEFERRED` ([NodeIdPipelineTest.java:791](../graphitron/src/test/java/no/sikt/graphitron/rewrite/NodeIdPipelineTest.java)) currently pins the deferred-rejection behaviour for scalar `@nodeId` on composite-PK NodeType without `@lookupKey`. R40 flips it: the case now classifies as `CompositeColumnArg` with `isLookupKey: true` (lookup) rather than rejecting.

## Tests

Pipeline-tier in `NodeIdPipelineTest`. Two new case classes parallel to `InputSameTableNodeIdCase` ([NodeIdPipelineTest.java:558-677](../graphitron/src/test/java/no/sikt/graphitron/rewrite/NodeIdPipelineTest.java)):

**`ArgumentSameTableNodeIdCase`:**

- `SAME_TABLE_SCALAR_SINGLE_PK` — `bazById(id: ID! @nodeId(typeName: "Baz"))` → `ColumnArg`, `isLookupKey: true`, `extraction = SkipMismatchedElement`, `column = baz.id`.
- `SAME_TABLE_LIST_SINGLE_PK` — `bazByIds(ids: [ID!]! @nodeId(typeName: "Baz"))` → `ColumnArg`, `list: true`, `isLookupKey: true`.
- `SAME_TABLE_SCALAR_COMPOSITE_PK` — `barById(id: ID! @nodeId(typeName: "Bar"))` → `CompositeColumnArg`, `keyColumns = [bar.id_1, bar.id_2]`, `isLookupKey: true`.
- `SAME_TABLE_LIST_COMPOSITE_PK` — `barByIds(ids: [ID!]! @nodeId(typeName: "Bar"))` → `CompositeColumnArg`, `list: true`, `isLookupKey: true`.
- `BARE_NODEID_DEFAULTS_TO_BACKING_TABLE` — `bazByIds(ids: [ID!]! @nodeId)` → typeName-omitted resolves to `Baz` via the same inference as input-field's `BARE_LIST_NODE_ID_INFERS_TYPE_NAME`.
- `BARE_NODEID_AMBIGUOUS_REJECTED` — multiple `@table` types map to the backing table → `UnclassifiedArg` with ambiguity hint.
- `T_NOT_IN_SCHEMA` — `@nodeId(typeName: "DoesNotExist")` → `UnclassifiedArg`.
- `T_NOT_NODE` — point at a non-`@node` `@table` type → `UnclassifiedArg`.
- `LOOKUP_KEY_REDUNDANT_REJECTED` — `@nodeId @lookupKey` → `UnclassifiedArg("redundant")`.
- `FIELD_DIRECTIVE_REJECTED` — `@nodeId @field(name:)` → `UnclassifiedArg`.

**`ArgumentFkTargetNodeIdCase`** (uses R50 phase g-B's `parent_node` + `child_ref` fixture):

- `FK_TARGET_LIST_SINGLE_PK` — child arg with `@nodeId(typeName: ParentNodeType)` where `ParentNodeType` is FK-reachable. Produces `ColumnReferenceArg` with `joinPath` of length 1.
- `FK_TARGET_SCALAR_SINGLE_PK` — scalar variant.
- `FK_TARGET_LIST_COMPOSITE_PK` — composite-PK FK-target → `CompositeColumnReferenceArg`.
- `FK_TARGET_REFERENCE_PATH_PINS_FK` — `@nodeId(typeName: T) @reference(path: "...")` confirms explicit pinning works.
- `FK_TARGET_AMBIGUOUS_REJECTED` — two FKs to T's table → `UnclassifiedArg` requiring `@reference(path:)`.

**Validator-rejection cases** (in a new `NodeIdConnectionRejectionCase` or extending `LookupKeyCase`):

- `ASCONNECTION_PLUS_SAME_TABLE_ARG_NODEID_REJECTED` — opptak reproducer shape. Field-level `UnclassifiedField` with the new hint.
- `ASCONNECTION_PLUS_INPUT_FIELD_SAME_TABLE_NODEID_REJECTED` — symmetric R50 input-field case (closes the latent gap). Same rejection.
- `ASCONNECTION_PLUS_FK_TARGET_ARG_NODEID_ALLOWED` — confirms FK-target composes (NOT rejected).
- `ASCONNECTION_PLUS_FK_TARGET_INPUT_FIELD_NODEID_ALLOWED` — symmetric input-field FK-target case.

**Existing fixture sweep.** Walk `InputSameTableNodeIdCase` and any `nodeidfixture`-driven SDL for cases that pair `@asConnection` with same-table `@nodeId` on the input-field side. Each becomes a rejection-test fixture or has the `@asConnection` removed. The implementer should pin in the commit which files were touched and why.

**Update.** `LookupKeyCase.SCALAR_NODEID_NON_LOOKUP_COMPOSITE_PK_DEFERRED` flips from rejection to classified-as-lookup (see Validator section).

**Compilation-tier:** `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` against the new query fields confirms generated `*Conditions` and `*Fetchers` compile against real jOOQ for both shapes.

**Execution-tier in `NodeIdQueryTest` against PostgreSQL via `-Plocal-db`:**

- `bazByIds_lookupSemantics_returnsRowsForSuppliedIds` — bulk lookup, single SELECT with IN predicate, rows correspond to input ids.
- `bazById_scalarLookup_returnsSingleRow` — scalar lookup variant.
- `barByIds_compositeLookup_returnsRowsForSuppliedIds` — composite-PK row IN.
- `bazByIds_malformedIdSkipped` — `SkipMismatchedElement` drops malformed entries; well-formed subset returned, no exception.
- `bazByIds_emptyList_returnsNoRows` — empty input list → empty result (lookup semantics, *not* "returns all rows" filter semantics).
- `fkTargetFilter_listOfParentIds_filtersChildren` — FK-target filter case; supplies parent IDs via `@nodeId(typeName: Parent)`, expects children whose FK matches.
- `fkTargetFilter_composesWithAsConnection` — FK-target filter + `first`/`after` pagination; opptak-shape but with FK-reachable typeName.
- `asConnectionPlusSameTableNodeId_buildFails` — `mvn install -Plocal-db` itself fails with the new validator hint visible in the build output.

`nodeidfixture` covers same-table shapes (`baz` single-PK, `bar` composite-PK) and FK-target shapes (`parent_node` + `child_ref` from R50 phase g-B). New schema fields land on `graphitron-test/src/main/resources/graphql/nodeid-schema.graphqls` (pin the file in the implementation commit).

## Coordination with R24

R40 and R24 ([`nodeidreferencefield-join-projection-form.md`](nodeidreferencefield-join-projection-form.md)) are `theme: nodeid` siblings operating on opposite directions of the same FK navigation:

- **R24** — output-side encode emitter. Brings parent table into scope via JOIN (rooted-at-parent shapes) or correlated subquery (multi-hop / condition-join), projects `encode<TypeName>(parent.k1, ..., parent.kN)`. Carriers: `ChildField.ColumnReferenceField` / `CompositeColumnReferenceField` with `compaction = NodeIdEncodeKeys`, plus `joinPath`.
- **R40** — input-side decode + filter projection. Decodes argument-supplied keys via `NodeIdDecodeKeys.SkipMismatchedElement`, predicates against FK columns reachable through `joinPath`. Carriers (this spec): `ArgumentRef.ScalarArg.ColumnReferenceArg` / `CompositeColumnReferenceArg`, plus the existing `InputField.ColumnReferenceField` / `CompositeColumnReferenceField`.

Both rely on the `joinPath: List<JoinStep>` shape from R50 phase g-B. Both share the `parent_node` + `child_ref` fixture for execution coverage. They do *not* share emitter code (encode vs decode are different code paths). R40 does not block on R24, and vice versa.

When R24 lands the JOIN-with-projection emitter, the input-side filter projection won't gain any encode-side helpers, but the `joinPath` threading helper R24 introduces may be reusable from the input side; revisit at R24 implementation time. Multi-hop FK-target on the input side is a sibling Backlog item to file alongside R40, parallel to R24's multi-hop arm.

## Out of scope and follow-on items

- **Multi-hop FK-target args** (input side). Parallel to R24's multi-hop arm. Same `joinPath` model applies but needs correlated-subquery emission analogous to R24. File as sibling Backlog item when R40 lands.
- **Condition-join FK-target args** (input side). Same justification.
- **Mutation-key `@nodeId` args.** Mutation arguments classify through `classifyMutationArguments` (separate from `classifyArguments`). Filed separately if/when needed; `NodeIdLeafResolver` is the seam.
- **Explicit `@LoadBearingClassifierCheck` annotation pairs.** Mentioned for visibility only; the existing exhaustive switches cover the contract.

## Implementer latitude

Per the R6 review-tightening precedent (`cea16e0`, "five contained shape fixes surfaced by reviewing the lifts"): the resolver lift will surface adjacent shape questions in `BuildContext` and the surrounding classify-arg pipeline. Take such fixes in the same commit train rather than filing them as follow-ons, provided each is contained and motivated by what the lift made visible. Examples likely to surface: `BuildContext.resolveDecodeHelperForTable`'s remaining call-site count after the lift (one or zero?), whether the existing scalar-`ID` arm at `FieldBuilder:514-549` still needs its own decode path or can route through the resolver too, the exact public surface of `NodeIdLeafResolver` (does the `@asConnection` validator reach for the same `resolve` method or a narrower variant?). Out-of-scope for this latitude: anything that drags in mutation-key args or multi-hop reachability (those are filed separately).
