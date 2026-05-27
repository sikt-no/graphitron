---
id: R246
title: UpdateRows walker carrier (R222 UPDATE slice) with PK-or-UK identification
status: Spec
bucket: structural
priority: 4
theme: structural-refactor
depends-on: [methodcall-walker-carrier, simplify-update-mutations-drop-value]
created: 2026-05-27
last-updated: 2026-05-27
---

# UpdateRows walker carrier (R222 UPDATE slice) with PK-or-UK identification

The slice lands R222's walker-carrier pattern on `@mutation(typeName: UPDATE)`. One permit migrates: `MutationUpdateTableField`. It loses no record components in this slice (the existing `tableInputArg` stays for now as the substrate the walker reads from) and gains `UpdateRows updateRows`, populated by a producer (`UpdateRowsWalker`) that reads the field's SDL definition plus the input `@table`'s jOOQ catalog metadata directly. The fetcher emitter `buildMutationUpdateFetcher` reads the carrier through a new `UpdateRowsEmitter` for its WHERE clause and SET map; the bulk arm (`buildBulkUpdateFetcher`) reads the carrier through the same emitter. The PK-or-UK identification is the slice's load-bearing claim: the walker queries `org.jooq.Table.getPrimaryKey()` and `getKeys()`, finds the narrowest unique key the input's column set covers, and writes that key's identity into the carrier. No PK and no UK is covered ⇒ `WalkerResult.Err` with a typed `Structural.NoUniqueKeyCoverage` arm.

The slice covers only the non-`multiRow` UPDATE path. `multiRow: true` UPDATE fields stay on the legacy `TableInputArg.lookupKeyFields()` / `setFields()` path until a follow-up slice adds the `Broadcast` arm to `UpdateRows`. The walker only fires when `@mutation(multiRow:)` is absent or `false`; the carrier's slot on `MutationUpdateTableField` is `Optional<UpdateRows>` (present iff the walker fired) for the duration of this slice, collapsing to non-Optional once the `Broadcast` arm lands and the legacy path retires.

The slice inherits the foundation-slice plumbing R238 ships: `WalkerResult<C>`, the `AuthorError.Structural` sealed sub-arm pattern, the LSP `Diagnostic` wire conventions (code namespace `graphitron.update-rows.<leaf>`, `source: "graphitron"`, severity Error, primary `SourceLocation` on the field's own SDL location), and the orchestrator's collect-Err-exclude-field flow that writes typed walker errors into `ValidationReport.walkerDiagnostics`.

## Target emitted code

The reducer backtracks from this shape. The slice's emitted code is structurally identical to today's `buildMutationUpdateFetcher` output; the rewrite is in *where* the WHERE / SET partition and the matched-key identity come from (carrier instead of `tia.lookupKeyFields()` / `tia.setFields()`).

```java
// MutationUpdateTableField example, post-slice (single-row arm)
DSLContext dsl = graphitronContext(env).getDslContext(env);
// decode locals for NodeId-bound key columns (unchanged shape)
// ...
LinkedHashMap<Field<?>, Object> sets = new LinkedHashMap<>();
// SET puts driven by carrier.setFields()
sets.put(FILM.TITLE, in.get("title"));
sets.put(FILM.DESCRIPTION, in.get("description"));
if (sets.isEmpty()) {
    throw new IllegalArgumentException("@mutation(typeName: UPDATE) ...");
}
Record1<Long> result = dsl
    .update(FILM)
    .set(sets)
    .where(FILM.FILM_ID.eq(__filmIdDecoded))   // WHERE from carrier.matchedKey
    .returningResult(FILM.FILM_ID)
    .fetchOne();
```

The bulk-arm shape (`UPDATE t SET c = v.c FROM (VALUES …) AS v(k, c…) WHERE t.k = v.k`) is unchanged in emit, with the same carrier-driven substitution: per-row `v` cells and the join-on-key predicate read the carrier's `matchedKey` columns instead of `tia.fieldBindings()` projected by `@lookupKey` presence.

## Carrier shape

One sealed family lands in `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/`: `UpdateRows`.

```java
// model/UpdateRows.java
public sealed interface UpdateRows permits Identified {
    MatchedKey matchedKey();
    List<InputField.SetField> setFields();
    List<InputColumnBindingGroup> keyBindings();
}

public record Identified(
    MatchedKey matchedKey,
    List<InputField.SetField> setFields,
    List<InputColumnBindingGroup> keyBindings
) implements UpdateRows {
    public Identified {
        if (matchedKey == null) throw new IllegalArgumentException("matchedKey required");
        setFields = List.copyOf(setFields);
        keyBindings = List.copyOf(keyBindings);
        if (setFields.isEmpty()) {
            throw new IllegalArgumentException(
                "Identified.setFields cannot be empty; the walker rejects empty-SET inputs "
                + "with Structural.NoSetFields before constructing the carrier");
        }
    }
}
```

The `Identified` arm is the only valid one in this slice. `Broadcast` is a future-slice addition (gated by `multiRow: true`), kept off the sealed `permits` clause until then so consumers' exhaustive switches don't need a placeholder arm. The compact-ctor invariants make the slot's promises load-bearing on the type system, not on classifier conventions.

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

The split between `PrimaryKey` and `UniqueKey` is cosmetic for the WHERE-clause emitter (both produce the same equality conjunction), but the discriminator is load-bearing for the LSP diagnostic surface and for any future per-key-identity decision (e.g. RETURNING-column choice). `MatchedKey.keyName()` echoes jOOQ's `Key.getName()` so an `AmbiguousCoverage` diagnostic can name the candidates by their catalog identity.

`keyBindings` is the existing `InputColumnBindingGroup` shape lifted onto the carrier from today's `tia.fieldBindings()` — the slice does not redesign the bindings; it just relocates them. `InputField.SetField` likewise carries over from R144's partition. The walker's job is to compute the partition (which input fields go into `setFields`, which contribute to `keyBindings`) by checking each field's target columns against the catalog's PK / UK column sets, replacing today's `TableInputArg.of(...)` partition logic for the non-`multiRow` UPDATE case.

## Slot landing on `UpdateRowsField` (narrow interface)

A new interface declares the slot:

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/model/UpdateRowsField.java
public interface UpdateRowsField {
    /**
     * Present iff the walker fired on this field — i.e. {@code @mutation(typeName: UPDATE)} with
     * {@code multiRow: false} (the default). {@code multiRow: true} UPDATE fields stay on the
     * legacy {@link ArgumentRef.InputTypeArg.TableInputArg#lookupKeyFields()} /
     * {@link ArgumentRef.InputTypeArg.TableInputArg#setFields()} path until a follow-up slice
     * adds the {@code Broadcast} arm to {@link UpdateRows}. Slot collapses to non-Optional then.
     */
    Optional<UpdateRows> updateRows();
}
```

`MutationUpdateTableField` implements `UpdateRowsField` and gains an `Optional<UpdateRows> updateRows` record component; the auto-generated accessor satisfies the interface contract:

```java
record MutationUpdateTableField(
    String parentTypeName,
    String name,
    SourceLocation location,
    DmlReturnExpression returnExpression,
    ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
    Optional<UpdateRows> updateRows,
    Optional<ErrorChannel> errorChannel
) implements DmlTableField, UpdateRowsField { ... }
```

The slot is interface-required but `Optional` for the slice's lifetime. Consumers reading the carrier go through `UpdateRowsField.updateRows()` and branch on the `Optional`; the populated case routes through `UpdateRowsEmitter`, the empty case routes through today's `tia.lookupKeyFields()` / `tia.setFields()` walk.

## Producer (`UpdateRowsWalker`)

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/walker/UpdateRowsWalker.java
public final class UpdateRowsWalker {
    public WalkerResult<UpdateRows> walk(
        GraphQLFieldDefinition field,
        ArgumentRef.InputTypeArg.TableInputArg tableInputArg,
        JooqCatalog catalog
    );
}
```

Substrate: the field's SDL definition (`GraphQLFieldDefinition`), the resolved `TableInputArg` (for the per-field carrier list), and the jOOQ catalog (for PK / UK metadata). The walker does not re-resolve the input's `@table` — the existing classifier already produced `tableInputArg.inputTable()`.

Walker stages (one pass per non-`multiRow` UPDATE field):

1. **Collect candidate keys from the catalog.** Read `Table.getPrimaryKey()` and `Table.getKeys()` (jOOQ's `getKeys()` returns the table's unique keys, PK included). Project each to a `MatchedKey` candidate, deduplicated on column set (a `UniqueKey` whose columns equal the PK's is the PK itself; jOOQ returns the PK in `getKeys()` already). The slice walks them in jOOQ declaration order, preferring PK first, then unique keys.

2. **Compute each input field's target columns on the input's own table.** Mirrors R188's four-carrier rule (per field, collect `.column()` / `.columns()` / `.liftedSourceColumns()` per `ColumnField` / `CompositeColumnField` / `ColumnReferenceField` / `CompositeColumnReferenceField`). The result is a flat `Set<ColumnRef>` of input-covered columns.

3. **Find the narrowest matched key.** Iterate the candidate keys; the first whose column set is a subset of the input-covered columns is the match. "Narrowest" is the deterministic tiebreaker for the diagnostic case where two unique keys both match (e.g. PK + an alternate UK that's a subset of the input). PK wins ties by step 1's ordering. The selection rule is stable and total under that ordering.

4. **Partition the input fields into WHERE-contributing and SET.** For each input field, if its target columns are all members of the matched key's column set, it contributes to `keyBindings` (via the existing `EnumMappingResolver.buildLookupBindings` shape, restricted to the matched-key columns). Otherwise it lands in `setFields`. Mixed-membership fields (some columns in the matched key, some not) reject with `Structural.MixedCarrierKeyMembership` naming the field and per-column membership — only possible on `CompositeColumnReferenceField` per R188's analysis.

5. **Reject empty SET.** If `setFields` ends up empty after step 4, reject with `Structural.NoSetFields(table, matchedKey)` — UPDATE with nothing to set is structurally ill-formed regardless of whether the WHERE clause is well-pinned. (Today's runtime check at `TypeFetcherGenerator:2131-2135` lifts to classify-time.)

6. **Return the result.** `Ok(Identified(matchedKey, setFields, keyBindings), diagnostics)` on success; `Err(authorErrors, diagnostics)` when any stage produced typed errors. The walker collects across stages.

The walker is invoked from `FieldBuilder` at the `MutationUpdateTableField` construction site, gated on `tableInputArg.multiRow() == false`. The producer doesn't see fields where multiRow is true; those skip the walker and the slot remains `Optional.empty()`.

## Consumer migration

`TypeFetcherGenerator.buildMutationUpdateFetcher` branches on `field.updateRows()`:

```java
return field.updateRows()
    .map(carrier -> UpdateRowsEmitter.emit(carrier, ctx, field, ...))
    .orElseGet(() -> legacyBuildMutationUpdateFetcher(ctx, field, outputPackage));
```

Same shape in `buildBulkUpdateFetcher`. The legacy paths stay alive for `multiRow: true` UPDATE fields until a follow-up slice.

```java
// graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/UpdateRowsEmitter.java
public final class UpdateRowsEmitter {
    public static MethodSpec emit(UpdateRows carrier, /* ctx, field, ... */);
}
```

The emitter dispatches on the `UpdateRows` arm. For `Identified`, it produces today's single-row or bulk shape (gated on `tia.list()`), reading the WHERE column list from `carrier.matchedKey().columns()` instead of `tia.fieldBindings()`-projected-by-`@lookupKey`. The SET map walks `carrier.setFields()`. Decode locals (for NodeId-bound key columns) emit from `carrier.keyBindings()` via the existing `emitLookupKeyDecodeLocals` helper (the helper takes `List<InputColumnBindingGroup>`, which is exactly the carrier's `keyBindings` shape).

`buildLookupWhereSingleRow` and `buildUniformShapeGuard("UPDATE")` stay in `TypeFetcherGenerator` as shared utilities both the carrier path and the legacy path consume.

## Plumbing inherited from R238

`WalkerResult<C>`, `AuthorError.Structural` sealed sub-family, `Diagnostic` wire format, orchestrator collect-Err-exclude-field flow, `ValidationReport.walkerDiagnostics` — all reused without modification. The slice adds new `Structural.*` arms to the sealed sub-family:

| `AuthorError` arm | LSP `code` |
|---|---|
| `Structural.NoUniqueKeyCoverage` | `graphitron.update-rows.no-unique-key-coverage` |
| `Structural.NoSetFields` | `graphitron.update-rows.no-set-fields` |
| `Structural.MixedCarrierKeyMembership` | `graphitron.update-rows.mixed-carrier-key-membership` |

Arm payloads:

* `NoUniqueKeyCoverage(table, inputColumns, candidateKeys)` — `table` is the input's `@table` name, `inputColumns` is the set of target columns the input contributes, `candidateKeys` is the list of `MatchedKey` records the walker considered. Message names the table and lists the candidate keys with their column shortfall (e.g. "PK requires {film_id} but input contributes {title, description}; UK 'film_uk_title' requires {title, language_id} but input contributes {title, description}").
* `NoSetFields(table, matchedKey)` — `table` is the input's `@table` name, `matchedKey` is the key the walker matched. Message: "UPDATE input has nothing to set; every input field contributes to matched key '<keyName>'."
* `MixedCarrierKeyMembership(field, fieldColumnsInKey, fieldColumnsOutsideKey)` — `field` is the SDL field name, plus the per-column split.

## Producer-side failure modes

| Source | Arm |
|---|---|
| No PK and no UK has its column set covered by the input | `Structural.NoUniqueKeyCoverage` |
| Every input field contributes only to the matched key (empty SET) | `Structural.NoSetFields` |
| A `CompositeColumnReferenceField` lifts to columns split between the matched key and outside it | `Structural.MixedCarrierKeyMembership` |
| Input `@table` has no PK and no unique key at all in the catalog | `Structural.NoUniqueKeyCoverage` with `candidateKeys = []` (degenerate but well-formed; the message names the absent-keys case) |

R188's `mutation-input.table-has-no-pk` diagnostic shape collapses into the degenerate `NoUniqueKeyCoverage` case here.

## Tests

Three tiers.

**Unit (`@UnitTier`)**, one test class.

`UpdateRowsWalkerTest` parses a small SDL fragment, configures a fixture `JooqCatalog` with PK / UK metadata, calls `walk`, and asserts on the sealed result. Coverage: PK-only match (input covers PK exactly); PK match with extra columns (input covers PK plus non-key columns landing in SET); UK-only match (table has no PK or the PK isn't input-covered, but a UK is); narrowest-key tiebreaker (input covers both PK and a UK that's a subset; PK wins by declaration order); `NoUniqueKeyCoverage` rejection (input covers neither PK nor any UK); `NoSetFields` rejection (input covers PK exactly, no extra columns); `MixedCarrierKeyMembership` rejection (a `CompositeColumnReferenceField` with diagonal PK overlap); table-with-no-keys degenerate case (`getPrimaryKey() == null` and `getKeys().isEmpty()`); composite-PK match through `CompositeColumnField` (R130 NodeId decode); FK-reference carrier on a non-PK column lands in SET; FK-reference carrier on a PK column lands in `keyBindings`.

`UpdateRowsEmitterTest` is deferred to pipeline tier — emitter output is structural code, and pipeline-tier carrier assertions already pin the carrier shape.

**Pipeline (`@PipelineTier`)**: extend `GraphitronSchemaBuilderTest` with `R246_*` rows asserting the slot is populated for non-`multiRow` UPDATE fields and empty for `multiRow: true` UPDATE fields. Extend the existing UPDATE-classification cases with typed `Structural.*` arm assertions for the rejection cases (replacing today's stringly-typed rejection-prose assertions on the equivalent shapes from R144 / R188). The `@LoadBearingClassifierCheck` audit keys carry over: `mutation-input.where-columns-cover-pk` extends to "covers-pk-or-uk" with R246's slot as the structural witness.

**Compilation / Execution (`@CompilationTier` / `@ExecutionTier`)**: `graphitron-sakila-example` provides the regression net. The migration is structurally invariant on observable behaviour for the non-`multiRow` UPDATE path; existing `DmlMutationsExecutionTest` / `DmlBulkMutationsExecutionTest` round-trips are the safety net. A new execution case lands for the UK-driven UPDATE shape: a sakila fixture input keyed on a unique column other than the PK (candidate: `customer.email`, which has a unique index in canonical sakila), exercising the `MatchedKey.UniqueKey` arm end-to-end.

## What this absorbs

| Item | Absorption mode |
|---|---|
| **R146** (`mutation-cardinality-safety-unique-index`) | Subsumed. The PK-or-UK coverage check this slice files for is R146's design content, applied at the walker layer instead of bolted onto `TableInputArg`. File discarded on R246's Done landing |

## Dependencies and sequencing

* **R238** (foundation slice): provides `WalkerResult<C>`, `AuthorError.Structural` sealed sub-family, LSP wire conventions, orchestrator collect-Err flow. Hard prerequisite.
* **R188** (PK-default partition): drops `@value` and partitions by PK membership in the legacy carrier shape. Hard prerequisite — this slice's walker reads target columns through the same four-carrier rule R188 establishes, and the legacy path the slice leaves intact for `multiRow: true` must already be PK-driven so the two paths don't disagree on partition semantics. If R188's status changes after this slice ships, the changes thread through `UpdateRowsWalker`'s step 2.
* **R145** (UPSERT): untouched. UPSERT continues through `MutationInputResolver`'s R144 deferral until R145 ships.

## Out of scope

* **`multiRow: true` UPDATE.** The legacy `TableInputArg.lookupKeyFields()` / `setFields()` path handles these. A follow-up slice adds the `Broadcast` arm to `UpdateRows` and retires the legacy path.
* **DELETE.** Same PK-or-UK question applies, but DELETE has its own walker carrier slice (TBD) — keeping the slices vertical means one verb per slice.
* **INSERT / UPSERT.** Different walker carriers per R222's table (`InsertRows`, future UPSERT slot).
* **Per-row error correlation on the bulk arm.** R12's flat-error contract carries through unchanged.
* **`tableInputArg` retirement on `MutationUpdateTableField`.** The slice leaves the record component in place so the legacy path stays available; retirement is the follow-up slice's responsibility after the `Broadcast` arm lands.
