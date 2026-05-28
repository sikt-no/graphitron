---
id: R246
title: UpdateRows walker carrier (R222 UPDATE slice) with PK-or-UK identification
status: Spec
bucket: structural
priority: 4
theme: structural-refactor
depends-on: [methodcall-walker-carrier, simplify-update-mutations-drop-value]
created: 2026-05-27
last-updated: 2026-05-28
---

# UpdateRows walker carrier (R222 UPDATE slice) with PK-or-UK identification

The slice lands R222's walker-carrier pattern on `@mutation(typeName: UPDATE)`. `MutationUpdateTableField` loses its `tableInputArg` record component — dissolved per R222's principle that input fields have no semantics independent of the consuming field — and gains two new slots: an `InputArgRef` carrying the slim arg-surface (SDL arg name, input type name, jOOQ table reference, list flag) and an `UpdateRows` carrier (non-Optional, single arm) holding the matched key plus the SET and WHERE partitions. Both slots are populated by `UpdateRowsWalker` (the carrier) and FieldBuilder (the arg-surface), each reading `GraphQLFieldDefinition` and the jOOQ catalog directly with no graphitron-internal substrate intermediating.

The PK-or-UK identification is the slice's load-bearing claim: the walker queries `org.jooq.Table.getPrimaryKey()` and `getKeys()`, finds the first key in jOOQ declaration order (PK preferred) whose column set is a subset of the input's covered columns, and writes that key's identity into the carrier. No matching key ⇒ `WalkerResult.Err` with a typed `Structural.NoUniqueKeyCoverage` arm; the field is excluded from the classified set and the build surfaces a typed validation error.

`multiRow: true` on UPDATE is rejected outright at FieldBuilder's UPDATE construction site, before the walker fires, via `Rejection.deferred("@mutation(typeName: UPDATE) with multiRow: true is not yet supported", "")` — empty slug, no follow-up roadmap item planned. The broadcast semantics that today's UPDATE supports via `multiRow: true` has no replacement path under R246: covering a PK or UK is *the* way to express an UPDATE. R146's PK-or-UK design content folds in as the walker's matched-key rule rather than as an alternative to a multiRow opt-out that no longer exists. List-input ("bulk") UPDATE remains fully supported; the emitter dispatches on `inputArg.list()` for the single-row vs `FROM (VALUES …)` shape.

The fetcher emitter `buildMutationUpdateFetcher` and its bulk arm `buildBulkUpdateFetcher` are replaced wholesale by `UpdateRowsEmitter`, which reads the `InputArgRef` and `UpdateRows` slots and emits both shapes from one entry point. Shared utilities (`buildLookupWhereSingleRow`, `buildUniformShapeGuard("UPDATE")`) stay in place and are called from the new emitter.

The slice inherits the foundation-slice plumbing R238 ships: `WalkerResult<C>`, the `AuthorError.Structural` sealed sub-arm pattern, the LSP `Diagnostic` wire conventions (code namespace `graphitron.update-rows.<leaf>`, `source: "graphitron"`, severity Error, primary `SourceLocation` on the field's own SDL location), and the orchestrator's collect-Err-exclude-field flow that writes typed walker errors into `ValidationReport.walkerDiagnostics`.

## Target emitted code

The reducer backtracks from this shape. The slice's emitted code is structurally identical to today's `buildMutationUpdateFetcher` output; the rewrite is in *where* the WHERE / SET partition and the matched-key identity come from — `updateRows.matchedKey()` / `updateRows.setColumns()` / `updateRows.keyColumns()` and `inputArg.table()` / `inputArg.list()` instead of `tia.lookupKeyFields()` / `tia.setFields()` / `tia.fieldBindings()` / `tia.inputTable()`. The empty-SET runtime guard at `TypeFetcherGenerator:2131-2135` is gone; the walker's `Structural.NoSetFields` arm rejects empty-SET inputs at classify-time, so the carrier's `Identified` constructor enforces non-empty `setColumns` and the emitter doesn't need a runtime check.

```java
// MutationUpdateTableField example, post-slice (single-row arm)
DSLContext dsl = graphitronContext(env).getDslContext(env);
// decode locals for NodeId-bound key columns (unchanged shape)
// ...
LinkedHashMap<Field<?>, Object> sets = new LinkedHashMap<>();
// SET puts driven by updateRows.setColumns()
sets.put(FILM.TITLE, in.get("title"));
sets.put(FILM.DESCRIPTION, in.get("description"));
Record1<Long> result = dsl
    .update(FILM)
    .set(sets)
    .where(FILM.FILM_ID.eq(__filmIdDecoded))   // WHERE from updateRows.matchedKey() + keyColumns()
    .returningResult(FILM.FILM_ID)
    .fetchOne();
```

The bulk-arm shape (`UPDATE t SET c = v.c FROM (VALUES …) AS v(k, c…) WHERE t.k = v.k`) is unchanged in emit, with the same carrier-driven substitution: per-row `v` cells read `updateRows.setColumns()`, the join-on-key predicate reads `updateRows.matchedKey().columns()`, and the iteration is over `env.getArgument(inputArg.name())` as a list.

## Carrier shape

Two new families land in `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/`: `InputArgRef` (the slim arg-surface) and `UpdateRows` (the UPDATE-shape carrier).

```java
// model/InputArgRef.java
public record InputArgRef(
    String name,           // SDL arg name (e.g. "film")
    String inputTypeName,  // SDL input type name (e.g. "FilmInput")
    TableRef table,        // jOOQ table reference resolved from @table on the input type
    boolean list           // single-row vs bulk dispatch (the arg's outer GraphQLList wrapper)
) {}
```

The `InputArgRef` carries the per-arg surface the emitter needs to read the input from `env` and reference the jOOQ table. It's intentionally a separate slot from `UpdateRows`: future DELETE / INSERT walker-carrier slices reuse the same shape, with each kind's verb-specific carrier (`DeleteRows`, `InsertRows`) sitting alongside on the field record.

```java
// model/UpdateRows.java
public sealed interface UpdateRows permits Identified {
    MatchedKey matchedKey();
    List<SetColumn> setColumns();
    List<KeyColumn> keyColumns();
}

public record Identified(
    MatchedKey matchedKey,
    List<SetColumn> setColumns,
    List<KeyColumn> keyColumns
) implements UpdateRows {
    public Identified {
        if (matchedKey == null) throw new IllegalArgumentException("matchedKey required");
        setColumns = List.copyOf(setColumns);
        keyColumns = List.copyOf(keyColumns);
        if (setColumns.isEmpty()) {
            throw new IllegalArgumentException(
                "Identified.setColumns cannot be empty; the walker rejects empty-SET inputs "
                + "with Structural.NoSetFields before constructing the carrier");
        }
    }
}
```

The sealed family has one arm today (`Identified`). Keeping it sealed rather than collapsing to a bare record leaves room for future arms if a different UPDATE shape lands later; the current scope deliberately rejects `multiRow: true` upstream, so no `Broadcast` arm is planned. The compact-constructor invariant makes the non-empty SET promise load-bearing on the type system.

`SetColumn` and `KeyColumn` are walker-local shapes carrying the (SDL field name, target column, value extraction) triple per column contribution. They're deliberately decoupled from `InputField` — R222's principle says input fields have no semantics independent of the consuming field, so the carrier names exactly what UPDATE needs and nothing else.

```java
// model/SetColumn.java
public record SetColumn(
    String sdlFieldName,        // GraphQL input field name (e.g. "title")
    ColumnRef targetColumn,     // the jOOQ column this contributes to
    ValueExtraction extraction  // how to read the input value at the call-site root
) {}

// model/KeyColumn.java
public record KeyColumn(
    String sdlFieldName,        // GraphQL input field name (e.g. "id")
    ColumnRef targetColumn,     // the jOOQ key column this fills
    ValueExtraction extraction  // Direct, NodeIdDecodeKeys arity-1, or NodeIdDecodeKeys arity-N
) {}
```

The composite-NodeId case (R130) maps to N entries with the same `sdlFieldName` but different `targetColumn`s — one SDL input field produces multiple `KeyColumn` rows, and the emitter groups by `sdlFieldName` to emit one decode local that all N columns reference. `ValueExtraction` reuses the existing `CallSiteExtraction` sealed family (`Direct`, `NodeIdDecodeKeys`) so the emitter-side decode helpers (R130's `emitLookupKeyDecodeLocals` and friends) stay unchanged; the per-`SetColumn` / `KeyColumn` projection to `InputColumnBindingGroup` happens once at the emitter call site and is a mechanical reshape.

`MatchedKey` carries the key identity from jOOQ:

```java
// model/MatchedKey.java
public sealed interface MatchedKey permits PrimaryKey, UniqueKey {
    List<ColumnRef> columns();   // ordered as declared on the key
    String keyName();             // jOOQ Key.getName(), for diagnostics
}

public record PrimaryKey(List<ColumnRef> columns, String keyName) implements MatchedKey {}
public record UniqueKey(List<ColumnRef> columns, String keyName) implements MatchedKey {}
```

The split between `PrimaryKey` and `UniqueKey` is cosmetic for the WHERE-clause emitter (both produce the same equality conjunction), but the discriminator is load-bearing for the LSP diagnostic surface and for any future per-key-identity decision (e.g. RETURNING-column choice). `MatchedKey.keyName()` echoes jOOQ's `Key.getName()` so a `NoUniqueKeyCoverage` diagnostic can name the candidates by their catalog identity.

## Slot landing on `UpdateRowsField` (narrow interface)

A new interface declares both slots:

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/model/UpdateRowsField.java
public interface UpdateRowsField {
    InputArgRef inputArg();
    UpdateRows updateRows();
}
```

`MutationUpdateTableField` implements `UpdateRowsField` and gains the two record components; the auto-generated accessors satisfy the interface contract:

```java
record MutationUpdateTableField(
    String parentTypeName,
    String name,
    SourceLocation location,
    DmlReturnExpression returnExpression,
    InputArgRef inputArg,
    UpdateRows updateRows,
    Optional<ErrorChannel> errorChannel
) implements DmlTableField, UpdateRowsField { ... }
```

The `tableInputArg` component is removed entirely; nothing on the field record references `ArgumentRef.InputTypeArg.TableInputArg`. Both new slots are non-Optional: every classified `MutationUpdateTableField` carries a populated `inputArg` and a populated `updateRows`. Fields that fail the FieldBuilder pre-checks (multiRow=true, @argCondition on the arg, no @table arg, multiple args) or that fail the walker (no PK/UK coverage, empty SET, mixed-membership, unsupported input field shape) never reach `MutationUpdateTableField` construction — they surface as typed rejections / `WalkerResult.Err` diagnostics with no carrier.

The narrow interface (`UpdateRowsField`) names the property and is the seam future DML walker-carrier slices branch into. When DELETE / INSERT land their own slices, they declare sibling narrow interfaces (`DeleteRowsField`, `InsertRowsField`) that surface the same `InputArgRef inputArg()` accessor alongside their verb-specific carrier; emit-time helpers reading the arg surface work uniformly across kinds.

## Pre-checks before the walker fires

`FieldBuilder`'s UPDATE construction site runs a whole-arg pre-check pass before invoking the walker. The pre-checks reject anything the walker is not the right layer to diagnose — directive-shape constraints on the arg, not per-field admissibility or partition rules. Each pre-check failure produces a `Rejection` and surfaces the field as an `UnclassifiedField` (the legacy channel) until R222's broader cleanup retires that path; the walker never sees fields that failed a pre-check.

| Pre-check | Rejection arm | Slug |
|---|---|---|
| `@mutation(typeName: UPDATE, multiRow: true)` | `Rejection.deferred(summary, "")` | empty (no follow-up planned) |
| `@argCondition` directive on the input arg | `Rejection.structural(reason)` | — |
| No `@table` input argument on the mutation field | `Rejection.structural(reason)` | — |
| Multiple `@table` input arguments | `Rejection.structural(reason)` | — |

The `@argCondition`-on-mutation-arg rejection migrates from `MutationInputResolver.resolveInput` (lines 438-440 today) to the FieldBuilder pre-check. The `multiRow: true` UPDATE rejection is new in R246 — today's `MutationInputResolver` rejects only `multiRow: true` on INSERT (lines 322-327), which is unaffected by this slice and stays where it is. The INSERT precedent at 322-327 is cited only as shape reference for what the new UPDATE rejection looks like. `MutationInputResolver`'s remaining UPDATE-specific checks (the value-driven partition rules at lines 509-541) dissolve under R188 + R246 together. `MutationInputResolver` itself stays alive for DELETE / INSERT / UPSERT until their own walker-carrier slices land.

## Producer (`UpdateRowsWalker`)

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/walker/UpdateRowsWalker.java
public final class UpdateRowsWalker {
    public WalkerResult<UpdateRows> walk(
        GraphQLFieldDefinition field,
        JooqCatalog catalog
    );
}
```

Substrate: the field's SDL definition (`GraphQLFieldDefinition`) and the jOOQ catalog. No `TableInputArg` parameter — the walker is a thin layer over SDL primitives per R222. The `InputArgRef` slot is built directly by FieldBuilder from the resolved single `@table` input argument and the jOOQ table lookup; the walker focuses on what could fail (per-field admissibility, key matching, partition rules).

Walker stages (one pass per UPDATE field that survived the pre-checks):

1. **Resolve the input arg's table.** Read `@table(name:)` off the input type and look up the jOOQ `Table` in the catalog. Failure here is a structural rejection upstream (resolved as part of building `InputArgRef`); the walker can assume the table resolves.

2. **Classify each input field.** For each `GraphQLInputObjectField` on the input type, call a shared classifier subroutine that returns either a typed admitted shape (`ColumnBinding`, `CompositeColumnBinding`, `ColumnReference`, `CompositeColumnReference` — each carrying SDL field name + target column(s) + extraction) or a typed admissibility rejection (`Structural.UnsupportedInputFieldShape` for `NestingField` / `UnboundField` / non-admitted carriers, per R130's admitted-carrier set). Collect rejections across the loop — do not short-circuit, so the LSP surfaces every per-field issue at once.

3. **Collect input-covered columns.** Union of every admitted field's target columns. The composite-NodeId case (R130) contributes N columns from one SDL field.

4. **Enumerate candidate keys.** Read `Table.getPrimaryKey()` and `Table.getKeys()` (jOOQ's `getKeys()` returns the table's unique keys, PK included). Deduplicate on column set; PK first in iteration order, then unique keys in jOOQ declaration order.

5. **Find the matched key.** Iterate the candidate keys; the first whose column set is a subset of the input-covered columns wins. PK preferred over UK by step 4's ordering. No matching key ⇒ `Structural.NoUniqueKeyCoverage(table, inputColumns, candidateKeys)` — names the table, the input-covered columns, and every candidate key the walker considered with its column shortfall.

6. **Partition the input fields into SET and WHERE.** For each admitted field, if every target column it contributes is a member of the matched key's column set, the field's columns project to `keyColumns`. If no target column is in the matched key's set, the columns project to `setColumns`. Mixed-membership fields (some columns in the matched key, some outside) reject with `Structural.MixedCarrierKeyMembership(fieldName, columnsInKey, columnsOutsideKey)` — only possible on composite reference shapes per R188's analysis.

7. **Reject empty SET.** If `setColumns` ends up empty after step 6, reject with `Structural.NoSetFields(table, matchedKey)` — UPDATE with nothing to set is structurally ill-formed regardless of whether the WHERE is well-pinned. Today's runtime check at `TypeFetcherGenerator:2131-2135` lifts to classify-time.

8. **Return the result.** `Ok(Identified(matchedKey, setColumns, keyColumns), diagnostics)` on success; `Err(authorErrors, diagnostics)` when any stage produced typed errors. The walker collects errors across stages 2, 5, 6, and 7.

The walker is invoked from `FieldBuilder` at the `MutationUpdateTableField` construction site after the pre-checks pass. Every UPDATE field that gets this far is non-multiRow, has a single `@table` input arg, has no `@argCondition` on that arg, and is the input the walker classifies into `UpdateRows`.

## Consumer migration

`TypeFetcherGenerator.buildMutationUpdateFetcher` and `buildBulkUpdateFetcher` are removed; a single new entry point `UpdateRowsEmitter.emit` reads both slots off the field and emits either shape:

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/UpdateRowsEmitter.java
public final class UpdateRowsEmitter {
    public static MethodSpec emit(UpdateRowsField field, /* ctx, ... */) {
        InputArgRef inputArg = field.inputArg();
        UpdateRows updateRows = field.updateRows();
        return inputArg.list()
            ? emitBulk(inputArg, updateRows, /* ctx, ... */)
            : emitSingleRow(inputArg, updateRows, /* ctx, ... */);
    }
}
```

The single-row arm produces today's `UPDATE … SET … WHERE …` shape; the bulk arm produces the `UPDATE t SET c = v.c FROM (VALUES …) AS v(k, c…) WHERE t.k = v.k` shape. WHERE columns come from `updateRows.matchedKey().columns()`; the SET map walks `updateRows.setColumns()`; decode locals (for NodeId-bound key columns) project `updateRows.keyColumns()` into `InputColumnBindingGroup` at the call site (grouping by `sdlFieldName`, one decode local per group) and feed the existing `emitLookupKeyDecodeLocals` helper. The projection is a one-line mechanical reshape; the carrier deliberately doesn't bake the `InputColumnBindingGroup` shape into the slot, keeping the carrier focused on UPDATE's semantic partition and leaving emit detail at the emit-time layer.

`buildLookupWhereSingleRow` and `buildUniformShapeGuard("UPDATE")` stay in `TypeFetcherGenerator` as shared utilities the new emitter consumes. There is no legacy fallback path — every classified `MutationUpdateTableField` flows through `UpdateRowsEmitter`.

## Plumbing inherited from R238

`WalkerResult<C>`, `AuthorError.Structural` sealed sub-family, `Diagnostic` wire format, orchestrator collect-Err-exclude-field flow, `ValidationReport.walkerDiagnostics` — all reused without modification. The slice adds new `Structural.*` arms to the sealed sub-family:

| `AuthorError` arm | LSP `code` |
|---|---|
| `Structural.NoUniqueKeyCoverage` | `graphitron.update-rows.no-unique-key-coverage` |
| `Structural.NoSetFields` | `graphitron.update-rows.no-set-fields` |
| `Structural.MixedCarrierKeyMembership` | `graphitron.update-rows.mixed-carrier-key-membership` |
| `Structural.UnsupportedInputFieldShape` | `graphitron.update-rows.unsupported-input-field-shape` |

Arm payloads:

* `NoUniqueKeyCoverage(table, inputColumns, candidateKeys)` — `table` is the input's `@table` name, `inputColumns` is the set of target columns the input contributes, `candidateKeys` is the list of `MatchedKey` records the walker considered. Message names the table and lists the candidate keys with their column shortfall (e.g. "PK requires {film_id} but input contributes {title, description}; UK 'film_uk_title' requires {title, language_id} but input contributes {title, description}").
* `NoSetFields(table, matchedKey)` — `table` is the input's `@table` name, `matchedKey` is the key the walker matched. Message: "UPDATE input has nothing to set; every input field contributes to matched key '<keyName>'."
* `MixedCarrierKeyMembership(fieldName, columnsInKey, columnsOutsideKey)` — `fieldName` is the SDL field name, plus the per-column split. Only possible on composite-reference shapes whose lifted source columns straddle the matched key.
* `UnsupportedInputFieldShape(fieldName, shape, reason)` — `fieldName` is the SDL field name, `shape` is the per-field classifier output (nested input, unbound, non-admitted carrier, etc.), `reason` is the human-readable description. Subsumes today's per-field rejection prose at `MutationInputResolver.resolveInput`'s field-shape loop (the `NestingField` / `UnboundField` rejections).

## Producer-side failure modes

| Source | Arm |
|---|---|
| Input field is `NestingField` (nested input), `UnboundField` (no column binding), or any non-admitted carrier shape | `Structural.UnsupportedInputFieldShape` |
| No PK and no UK has its column set covered by the input | `Structural.NoUniqueKeyCoverage` |
| Every input field contributes only to the matched key (empty SET) | `Structural.NoSetFields` |
| A composite-reference input field lifts to columns split between the matched key and outside it | `Structural.MixedCarrierKeyMembership` |
| Input `@table` has no PK and no unique key at all in the catalog | `Structural.NoUniqueKeyCoverage` with `candidateKeys = []` (degenerate but well-formed; the message names the absent-keys case) |

R188's `mutation-input.table-has-no-pk` diagnostic shape collapses into the degenerate `NoUniqueKeyCoverage` case here. The per-field admissibility rejections that today live in `MutationInputResolver.resolveInput`'s loop migrate into the walker's stage 2 as `UnsupportedInputFieldShape`.

## Tests

Three tiers.

**Unit (`@UnitTier`)**, one test class.

`UpdateRowsWalkerTest` parses a small SDL fragment, configures a fixture `JooqCatalog` with PK / UK metadata, calls `walk`, and asserts on the sealed result. Coverage:

* **PK-only match.** Input covers PK exactly; setColumns is empty under the rule above → rejects with `NoSetFields`. Variant: PK plus extra non-key columns → succeeds with extras landing in `setColumns`.
* **UK-only match.** Table has no PK (or the PK isn't input-covered), but a UK is covered → `Identified` with `MatchedKey.UniqueKey`.
* **PK-preferred tiebreaker.** Input covers both PK and an alternate UK that's a strict subset of the input columns → PK wins by step 4's ordering.
* **`NoUniqueKeyCoverage` rejection.** Input covers neither PK nor any UK.
* **`NoSetFields` rejection.** Input covers PK exactly, no extra columns.
* **`MixedCarrierKeyMembership` rejection.** Composite-reference input field whose lifted source columns straddle the matched key.
* **`UnsupportedInputFieldShape` rejection.** Nested-input field, unbound field, non-admitted carrier shape — each produces one typed arm per offending field, collected across the loop (no short-circuit).
* **Table-with-no-keys degenerate case.** `getPrimaryKey() == null` and `getKeys().isEmpty()` → `NoUniqueKeyCoverage` with `candidateKeys = []`.
* **Composite-PK match through composite NodeId input field** (R130 decode shape).
* **FK-reference admissibility.** Reference carrier on a non-PK column lands in `setColumns`; reference carrier on a PK column lands in `keyColumns`.

`UpdateRowsEmitterTest` is deferred to pipeline tier — emitter output is structural code, and pipeline-tier carrier assertions already pin the carrier shape.

**Pipeline (`@PipelineTier`)**: extend `GraphitronSchemaBuilderTest` with `R246_*` rows asserting:

* Both `inputArg` and `updateRows` slots populated on every classified `MutationUpdateTableField`.
* `multiRow: true` UPDATE fields surface as `UnclassifiedField` with `Rejection.deferred` (empty slug) — the field is excluded from the classified set entirely; no `MutationUpdateTableField` is constructed.
* `@argCondition` on a mutation input arg surfaces as `UnclassifiedField` with `Rejection.structural`.
* Typed `Structural.*` arm assertions for each walker rejection case (replacing today's stringly-typed rejection-prose assertions on the equivalent shapes from R144 / R188).

**Compilation / Execution (`@CompilationTier` / `@ExecutionTier`)**: `graphitron-sakila-example` provides the regression net. The migration is structurally invariant on observable behaviour for the surviving UPDATE shapes; existing `SingleRecordPayloadDmlTest` (single-row UPDATE, e.g. `updateFilmPayload_updatesRowAndReturnsPayloadWithSingleDataField` at line 179) / `DmlBulkMutationsExecutionTest` round-trips are the safety net. A new execution case lands for the UK-driven UPDATE shape using the existing `nodeidfixture.parent_node.alt_key` fixture (the only non-PK `UNIQUE` constraint in `graphitron-sakila-db/src/main/resources/init.sql`, line 402): an UPDATE input keyed on `alt_key` exercises the `MatchedKey.UniqueKey` arm end-to-end. The `multiRow: true` UPDATE rejection (no example currently uses `multiRow: true` on UPDATE in `graphitron-sakila-example`) is covered at the pipeline tier; no execution-tier coverage is added for it.

## What this absorbs

| Item | Absorption mode |
|---|---|
| **R146** (`mutation-cardinality-safety-unique-index`) | Subsumed. The PK-or-UK coverage check this slice files for is R146's design content, applied at the walker layer. With `multiRow: true` rejected on UPDATE outright, PK-or-UK coverage is not an *alternative* to a broadcast opt-out — it's *the* way to express a single-row UPDATE. R146's file is discarded on R246's Done landing. |

## Dependencies and sequencing

* **R238** (foundation slice): provides `WalkerResult<C>`, `AuthorError.Structural` sealed sub-family, LSP wire conventions, orchestrator collect-Err flow. Hard prerequisite.
* **R188** (PK-default partition): drops `@value` and provides the per-input-field admitted-carrier set the walker classifies into (`ColumnField` / `CompositeColumnField` / `ColumnReferenceField` / `CompositeColumnReferenceField`). Hard prerequisite — the walker reads target columns through the same four-carrier rule R188 establishes. With R188 + R246 both shipped, the `@value` directive and the legacy `TableInputArg.of`-driven partition retire together for UPDATE.
* **R145** (UPSERT): untouched. UPSERT continues through `MutationInputResolver`'s deferral until R145 ships.

## Out of scope

* **`multiRow: true` UPDATE.** Rejected outright at the FieldBuilder pre-check (`Rejection.deferred` with empty slug). No follow-up slice is planned; the broadcast semantics has no replacement in R246 or after. A schema author wanting that shape should write a custom `@service` mutation, or file a Backlog item making the case for re-introducing broadcast UPDATE.
* **DELETE.** Same PK-or-UK question applies, but DELETE has its own walker-carrier slice (TBD) — keeping the slices vertical means one verb per slice. The `InputArgRef` slot lands here and is reused by the DELETE slice without modification.
* **INSERT / UPSERT.** Different walker carriers per R222's table (`InsertRows`, future UPSERT slot). `InputArgRef` reused.
* **Per-row error correlation on the bulk arm.** R12's flat-error contract carries through unchanged.
* **`MutationInputResolver` retirement.** R246 stops calling `MutationInputResolver.resolveInput` from the UPDATE path; the resolver stays alive for DELETE / INSERT / UPSERT until their own walker-carrier slices land. Final retirement happens once every DML kind has migrated.
