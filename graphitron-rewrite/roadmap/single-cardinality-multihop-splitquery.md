---
id: R324
title: "Lift single-cardinality multi-hop @splitQuery restriction"
status: Spec
bucket: feature
priority: 6
depends-on: []
created: 2026-06-17
last-updated: 2026-06-17
---

# Lift single-cardinality multi-hop @splitQuery restriction

## Overview

A single-cardinality `@splitQuery` child field (returns `T`, not `[T!]`) whose `@reference(path:)`
has more than one hop is rejected at classification time. The list-cardinality and connection
emitters already support multi-hop paths; only the single-cardinality rows-method emitter never
grew the bridging loop its two siblings carry. This item completes that one emitter sibling, lifts
the classifier guard, and proves the shape end-to-end.

The motivating shape is a chain of to-one references where the parent holds the FK at the first
hop. Real-world demand from `sis-graphql-spec`:

```graphql
type Customer @table(name: "customer") {
    storeAddress: Address
        @splitQuery
        @reference(path: [{key: "customer_store_id_fkey"}, {key: "store_address_id_fkey"}])
}
```

`customer.store_id -> store`, then `store.address_id -> address`; the field resolves to a single
`Address` per customer, batched through one DataLoader query. Today this is rejected; the only
workaround is to collapse the chain into a single-hop FK (impossible when the schema legitimately
traverses two relationships) or restructure the SDL.

This is a pure emitter-completeness item: the classifier model already represents the shape
correctly. No model-shape change, no new directive, no wire-format change.

## Current state

`FieldBuilder.classifyObjectReturnChildField` rejects the shape with a `Rejection.deferred`
(known-not-yet-built, not a structural impossibility):

```java
if (hasSplitQuery) {
    if (returnType.wrapper() instanceof FieldWrapper.Single
            && referencePath.elements().size() != 1) {
        return new UnclassifiedField(parentTypeName, name, location, fieldDef,
            Rejection.deferred(
                "Single-cardinality @splitQuery requires a single-hop parent-holds-FK reference path; "
                + "multi-hop paths are not yet supported on single cardinality",
                "", ChildField.SplitTableField.class));
    }
    ...
}
```

The rejection fires *after* path parsing, component resolution, source-key derivation, and
parent-correlation building all succeed, so multi-hop single-cardinality schemas already parse
cleanly; only this cardinality+size check blocks them.

The supporting classification machinery is already hop-count agnostic:

- `FieldBuilder.deriveSplitQuerySource` keys single cardinality off `path.get(0)` only
  (`!isList && path.get(0) instanceof FkJoin fk ? fk.sourceSideColumns() : parentTable.primaryKeyColumns()`),
  so a multi-hop single path with an FK first hop already keys correctly by the parent's FK columns.
- `BuildContext.buildParentCorrelation` / `ParentCorrelation` pin only `firstHop`; the carrier
  invariant (`ParentCorrelation.checkCarrierInvariant`) is `firstStep() == joinPath.get(0)`, also
  hop-count agnostic. The correlation is built before the guard fires.

The gap is entirely in `SplitRowsMethodEmitter`. Three cardinality siblings consume the same
prelude (`emitParentInputAndFkChain`, which already builds the full `aliases` chain and emits one
table declaration per hop) and then fork:

- `buildListMethod` — multi-hop ready: bridging-hop loop (terminal back to step 0 via
  `.onKey(fk)` / `.on(condition)`), projects and FROMs off `terminalAlias`, applies per-hop WHERE
  with `(srcAlias = i==0 ? firstAlias : aliases.get(i-1), tgtAlias = aliases.get(i))`. Proven by
  the `film_actor` junction-table fixtures in `SplitTableFieldPipelineTest`.
- `buildConnectionMethod` — multi-hop ready: the same bridging-hop loop, `terminalAlias`
  throughout.
- `buildSingleMethod` — the holdout: projects and FROMs off `firstAlias`, has no bridging loop,
  and applies its single per-hop WHERE against `(firstAlias, firstAlias)` (a single-hop-only
  shortcut that would be wrong at hop 1+). Returns `scatterSingleByIdx` (1:1, null where no match),
  which is already hop-count independent.

`ChildField.SplitTableField.class` is in `TypeFetcherGenerator.IMPLEMENTED_LEAVES` (it ships
single-hop today), so the validator-mirror is already satisfied: deleting the classifier guard will
not surface a residual `STUBBED_VARIANTS` validate-time rejection. (Confirm this holds at
implementation time; it is the "every classifier decision that implies a generator branch must fail
at validate time if unimplemented" check, run in reverse.)

## Desired end state

`buildSingleMethod` emits a flat `SELECT terminal.* , parentInput.idx AS __idx__ FROM terminal
JOIN <bridging hops back to step 0> JOIN parentInput ON <step-0 correlation>` for any hop count,
identical in join topology to `buildListMethod` modulo the scatter call. For the motivating
`Customer.storeAddress` shape the generated rows method is:

```
SELECT address.*, parentInput.idx AS __idx__
FROM   address
JOIN   store ON <store_address_id_fkey>           -- bridge hop 2 (store -> address)
JOIN   parentInput ON store.store_id = parentInput."store_id"   -- step-0 correlation on parent FK value
```

keyed by the parent's `store_id` values, scattered 1:1 back to customers via `__idx__`.

The classifier guard is removed; multi-hop single-cardinality `@splitQuery` classifies as
`SplitTableField` with a multi-element `joinPath`, exactly as list cardinality already does.

### Null semantics (decided, not deferred)

The bridging hops emit inner joins, consistent with the list and connection siblings. A to-one
chain resolves to `null` when *any* hop in the chain is absent (intermediate FK null, or terminal
row missing); inner-join-drops-the-row plus `scatterSingleByIdx` filling `null` produces exactly
that. For the motivating case every hop is a NOT NULL FK, so the distinction is moot; where an
intermediate FK is nullable, inner join collapses "intermediate link absent" and "terminal absent"
into the same `null`. That is a defensible to-one semantic and it keeps single consistent with its
siblings. Distinguishing intermediate-null from terminal-null (which would require LEFT JOINs) is
explicitly **out of scope**: introducing it on single alone would reintroduce the very
sibling-divergence this item retires, for a distinction no consumer has asked for. If ever wanted,
it is a separate item spanning all three siblings.

## Design decision: extract a shared join-topology helper

Two of the three siblings (`buildListMethod`, `buildConnectionMethod`) already independently carry
a byte-for-byte copy of "FROM terminal + bridging-hop loop + OnConditionJoin parent JOIN +
parentInput correlation + per-hop WHERE." The minimal fix is to add a third copy to
`buildSingleMethod`. This item instead **extracts that block into one shared private helper** used
by all three siblings, because:

1. **This ticket is a realized drift.** The bug exists precisely because `buildSingleMethod`
   drifted from `buildListMethod` (it never grew the bridging loop). The join topology is uniformly
   true across the cardinality siblings; the genuine per-cardinality fork is only the projection /
   ROW_NUMBER envelope / scatter call. A third hand-maintained copy re-arms the same drift the
   ticket is fixing.
2. **`buildSingleMethod` is shared beyond `SplitTableField`.** `buildForRecordTable` routes
   `RecordTableField.emitsSingleRecordPerKey()` (the `AccessorKeyedMany` path) through
   `buildSingleMethod`. Widening that body by hand raises the audit cost; routing it through a
   helper already exercised by the list/connection multi-hop fixtures means the record path inherits
   proven topology emission rather than a freshly hand-widened single body.

The helper takes the `PreludeBindings`, the `joinPath`, the `parentCorrelation`, the field-level
`filters`, the `terminalAlias`, and the target `CodeBlock.Builder`; it emits FROM-terminal,
bridging, the OnConditionJoin parent JOIN, the parentInput `ON`, and the WHERE. Each sibling keeps
its cardinality-specific parts: `buildListMethod` adds the lookup-input JOIN (SplitLookupTableField)
and `scatterByIdx`; `buildSingleMethod` adds `scatterSingleByIdx`; `buildConnectionMethod` keeps its
windowed envelope (it already diverges enough that it may consume only the bridging sub-block; see
implementation note). Extracting forces reconciling the per-hop WHERE: `buildSingleMethod`'s current
`(firstAlias, firstAlias)` pairing is replaced by the list form
`(srcAlias = i==0 ? firstAlias : aliases.get(i-1), tgtAlias = aliases.get(i))`.

## What we are NOT doing

- **LEFT JOINs / distinguishing intermediate-null from terminal-null.** Inner joins throughout, per
  "Null semantics" above.
- **`@lookupKey` on single cardinality.** Still rejected (`SplitLookupTableField` single-cardinality
  rejection at `FieldBuilder`, exercised by
  `GraphitronSchemaBuilderTest.SPLIT_LOOKUP_TABLE_SINGLE_CARDINALITY_REJECTED`). Untouched.
- **Connection-method refactor for its own sake.** If `buildConnectionMethod`'s windowed structure
  makes full helper reuse awkward, share only the bridging sub-block with it (or leave it and share
  the helper between list + single). The decision point: do not contort the connection envelope to
  fit a helper shape; the goal is retiring the single/list duplication, the connection sibling is a
  bonus if it falls out cleanly.
- **Model-shape changes.** `SplitTableField`, `SourceKey`, `ParentCorrelation`, `JoinStep` are
  unchanged; this is emitter-only.

## Implementation approach

### 1. Extract the shared join-topology helper

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/SplitRowsMethodEmitter.java`

Factor the FROM-terminal + bridging-hop loop + OnConditionJoin parent JOIN + parentInput `ON` +
per-hop WHERE + field-level WHERE block out of `buildListMethod` into a private helper. Repoint
`buildListMethod` at it first and confirm the existing `film_actor` pipeline + execution fixtures
still pass unchanged (pure refactor, no behaviour delta). Then decide connection reuse per "What we
are NOT doing."

### 2. Complete `buildSingleMethod` via the helper

Replace `buildSingleMethod`'s hand-rolled single-hop SELECT with a call to the helper: project and
FROM off `terminalAlias` (not `firstAlias`), bridge multi-hop, correlate parentInput on `firstAlias`
(the `OnFkSlots` / `OnConditionJoin` prelude bindings already collapse that fork), and return
`scatterSingleByIdx`. Update the method javadoc, which currently asserts "Classifier guarantees
single-hop."

### 3. Remove the classifier guard

**File:** `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java`

Delete the `FieldWrapper.Single && referencePath.elements().size() != 1` rejection in
`classifyObjectReturnChildField`. Update the now-stale `deriveSplitQuerySource` javadoc ("multi-hop
single cardinality falls through to parent-PK, but the classifier rejects it upstream") and the
`SplitRowsMethodEmitter` "§1c rejects multi-hop single-cardinality" comment.

### 4. Audit the `RecordTableField` single path

Confirm the `emitsSingleRecordPerKey()` path that routes through the now-multi-hop-capable
`buildSingleMethod` (via `buildForRecordTable`) stays correct. The `AccessorKeyedMany` / lifter
paths are single-hop by construction (the prelude builds a `[liftedHop]` joinPath); the helper must
remain correct for a single-hop path (it is, by construction: the bridging loop is a no-op when
`path.size() == 1`). State the outcome of this audit in the implementation commit.

### 5. Tests

- **Classification (pipeline tier, `GraphitronSchemaBuilderTest`):** convert
  `SPLIT_TABLE_MULTI_HOP_SINGLE_CARDINALITY_REJECTED` into a positive case asserting the field
  classifies as `SplitTableField` with a 2-element `joinPath` of `FkJoin` steps (the `§1c` doc-block
  reference in `code-generation-triggers.adoc` is the corpus source; update it to the new verdict).
- **Execution (`graphitron-sakila-example`, PostgreSQL tier):** a two-hop to-one fixture mirroring
  `Customer.storeAddress` (`customer -> store -> address` exists in Sakila), asserting a query
  returns the correct `Address` per customer. Add a case where an intermediate link is absent (or a
  customer with a null `store_id` if the schema allows) yielding `null`, which doubles as the
  executable pin for the null semantic in "Desired end state." This is the primary behavioural
  proof.
- **Compile backstop:** the `graphitron-sakila-example` compile (Java 17 target) confirms the
  multi-hop single body is type-correct.
- No assertions on generated method-body strings (banned at every tier); the bridging JOIN's
  correctness is proven by execution against Postgres, not by inspecting the emitted source.

### 6. Documentation

- `docs/manual/how-to/split-vs-inline.adoc` "When the classifier requires split" -> "Single-
  cardinality multi-hop reference": multi-hop single cardinality is now supported via the split path;
  rewrite the bullet (it currently documents the rejection as a requirement).
- Update any `graphitron-rewrite/docs/` reference (e.g. `code-generation-triggers.adoc`) that
  describes the old rejection. The legacy `graphitron-codegen-parent/.../README.md` note is out of
  scope (legacy module, not AI-maintained).

## Success criteria

- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes, including the converted
  classification case and the new `graphitron-sakila-example` two-hop single-cardinality execution
  fixture. (`-Plocal-db` is required: see CLAUDE.md's catalog-jar clobber note.)
- The `Customer.storeAddress`-shaped schema classifies as `SplitTableField` and resolves the correct
  `Address` per parent in one batched query, with `null` where a hop in the chain is absent.
- `buildListMethod`, `buildSingleMethod` (and, if cleanly reusable, `buildConnectionMethod`) share a
  single join-topology helper; the previous list/single duplication is retired.
- Single-cardinality `@splitQuery @lookupKey` stays rejected with the existing message (out of
  scope).

## References

Identifier-level references (line numbers drift; refer to the named symbol):

- Classifier guard: `FieldBuilder.classifyObjectReturnChildField` (the
  `FieldWrapper.Single && elements().size() != 1` `Rejection.deferred`).
- Source-key keying: `FieldBuilder.deriveSplitQuerySource` (single cardinality off `path.get(0)`).
- Parent correlation: `BuildContext.buildParentCorrelation`, `ParentCorrelation.checkCarrierInvariant`.
- Emitter siblings: `SplitRowsMethodEmitter.emitParentInputAndFkChain` (shared prelude),
  `buildListMethod` (multi-hop reference), `buildConnectionMethod` (multi-hop reference),
  `buildSingleMethod` (the holdout), `buildForRecordTable` (routes `emitsSingleRecordPerKey` through
  `buildSingleMethod`).
- Validator-mirror: `TypeFetcherGenerator.IMPLEMENTED_LEAVES` (already contains `SplitTableField`),
  `STUBBED_VARIANTS`.
- Existing tests: `GraphitronSchemaBuilderTest.SPLIT_TABLE_MULTI_HOP_SINGLE_CARDINALITY_REJECTED`
  (to convert), `SplitTableFieldPipelineTest` (`film_actor` multi-hop list fixtures),
  `SplitTableFieldValidationTest` (single-cardinality emittable case).
- Docs: `docs/manual/how-to/split-vs-inline.adoc` (the rejection bullet),
  `graphitron-rewrite/docs/code-generation-triggers.adoc` (`§1c` corpus block).
