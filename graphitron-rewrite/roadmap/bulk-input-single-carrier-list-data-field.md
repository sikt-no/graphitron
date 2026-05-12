---
id: R141
title: Admit bulk-input mutations with a single payload carrier wrapping a list-shaped data field
status: In Review
bucket: feature
priority: 2
theme: mutations-errors
depends-on: [error-handling-parity]
last-updated: 2026-05-12
---

# Admit bulk-input mutations with a single payload carrier wrapping a list-shaped data field

## Implementation notes (In Review)

R141 lift as implemented:

- `CarrierFieldRole` sealed interface lives at `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/CarrierFieldRole.java` with permits `DataChannel` and `ErrorChannelRole`. The walk classifies only the `DataChannel` permit today; the `ErrorChannelRole` permit is reserved in the type system for R12 (`error-handling-parity`) to land the producer side when it ships. The carrier-side `resolveErrorChannel` call at `FieldBuilder.java` was dead code for `NoBacking` carriers (returns `NO_CHANNEL` when `fqClassName == null`), so removing it here is observationally a no-op until R12 lands.
- `SingleRecordCarrierShape` refactored to `(carrierTypeName, List<CarrierFieldRole> roles)` with compact-constructor invariants enforced (exactly-one `DataChannel`, at-most-one `ErrorChannelRole`, distinct field names) and helper accessors `data()` / `errorChannel()`.
- `BuildContext.tryResolveSingleRecordCarrier` rewritten as the unified per-field walk; any carrier field that does not classify into a `CarrierFieldRole` permit causes the walk to return `Rejected` with the descriptive "no CarrierFieldRole permit; file a roadmap item" message that names the offending field.
- `MutationField.MutationBulkDmlRecordField` sealed leaf added with compact-constructor rejections for `DELETE`, `UPSERT`, and `tableInputArg.list() == false`. `FieldBuilder.classifyMutationField` surfaces UPSERT as a deferred-to-R145 author-facing rejection (rather than letting the compact-constructor throw) until R144's upstream refusal lands.
- `MutationInputResolver.validateReturnType` lifts Invariant #15 into a cardinality-dispatch on the carrier's `DataChannel` wrapper: bulk-input + list-data-field admits, single-input + list-data-field rejects with Invariant #16, bulk-input + singleton-data-field falls through to R138's lifted Invariant #15, single-input + singleton-data-field stays the R75 Phase 1 admit.
- `TypeFetcherGenerator.buildMutationBulkDmlRecordFetcher` emits the per-row DML loop inside `dsl.transactionResult(...)`, accumulating PKs into a `Result<RecordN<...>>` in input order. The downstream data-field fetcher (`FetcherEmitter.buildSingleRecordTableFetcherValue` with `Cardinality.MANY`) reads that Result and runs the bulk response SELECT.
- New load-bearing classifier check `single-record-carrier-shape.roles-exhaustively-classified` (producer on `BuildContext.tryResolveSingleRecordCarrier`, consumers on `GraphitronSchemaBuilder.registerCarrierDataField` and `TypeFetcherGenerator.buildMutationBulkDmlRecordFetcher`); `LoadBearingGuaranteeAuditTest` picks the pairing up automatically.
- `CarrierFieldRoleCoverageTest` scans every consumer source file for an explicit dispatch arm against every `CarrierFieldRole` permit.
- Three classifier-tier truth-table rows in `GraphitronSchemaBuilderTest`: `MUTATION_BULK_DML_RECORD_FIELD` (admit), `DML_INSERT_SINGLE_LIST_DATA_REJECTED` (Invariant #16), `DML_INSERT_LIST_PAYLOAD_NO_CARRIER_FIELD_ROLE_REJECTED` (no-permit-match). The existing `MUTATION_DML_RECORD_FIELD` row was retargeted to the single-input + single-data-field shape; `DML_INSERT_LIST_PLAIN_PAYLOAD_REJECTED` was repointed at the singleton-data-field arm (the original list-data-field shape now admits under R141).
- Three execution-tier round-trips in `DmlBulkMutationsExecutionTest`: `bulkInsertWithThreeRowsInNonPkOrderPreservesInputOrderInResponse`, `bulkInsertWithSingleRowExercisesBulkLeafPath`, `bulkUpdateWithThreeRowsInNonPkOrderPreservesInputOrderInResponse`. The order-preservation invariant is the load-bearing runtime audit. The Sakila fixture grew `FilmsPayload { films: [Film!] }` + `createFilmsPayload` / `updateFilmsPayload` mutations.
- `SingleRecordCarrierPipelineTest` updated: the previously-admitted single-input + list-data-field shape now routes through Invariant #16 (or admits as `MutationBulkDmlRecordField` when paired with bulk input); rejection-text assertions follow the new "no CarrierFieldRole permit" diagnostic surface.

R141 deliberately ships `ErrorChannelRole` as a type-system reservation rather than a fully-resolved permit producer. The carrier-side error-channel resolution (full `ErrorChannel` binding from a payload class's canonical constructor) is forward-looking work R12 lands. Once R12 ships, `BuildContext.tryResolveSingleRecordCarrier` gains the `ErrorChannelRole` classifier rule, and the consumers (`GraphitronSchemaBuilder.registerCarrierDataField`'s `ErrorChannelRole` arm, `FieldBuilder.classifyMutationField`'s `shape.errorChannel().map(...)` reader, the catch-arm builder) pick up the producer-side annotations R12 lands alongside.

## Target shape

```graphql
extend type Mutation {
    opprettKvotesporsmalPreutfylling(
        input: [OpprettKvotesporsmalPreutfyllingInput!]!
    ): KvotesporsmalPreutfyllingPayload! @mutation(typeName: INSERT)
}
type KvotesporsmalPreutfyllingPayload {
    kvotesporsmalPreutfylling: [KvotesporsmalPreutfylling!]   # data channel
    errors: [SomeError!]!                                      # error channel (R12)
}
input OpprettKvotesporsmalPreutfyllingInput @table(name: "kvotesporsmal_preutfylling") {
    kvotesporsmalPreutfyllingKode: String! @field(name: "KVOTESPORSMAL_PREUTFYLLING_KODE")
}
type KvotesporsmalPreutfylling implements Node @key(fields: "id") @node(keyColumns: ["kvotesporsmal_preutfylling_kode"]) @table(name: "kvotesporsmal_preutfylling") {
    id: ID! @nodeId
}
```

Bulk `@table` input, single payload carrier, list-shaped `@table`-element data field paired to the same `@table` as the input. This is the **main shape consumers want from a mutation payload**: the carrier is the natural seam for sibling fields the platform exposes alongside the read-back. R141 admits **exactly two carrier-field roles** — the **data channel** (the list-shaped read-back) and the **error channel** (R12's existing `errors: [SomeError!]!` machinery) — and encodes the closed enumeration in the type system as a sealed `CarrierFieldRole` permitting `DataChannel` and `ErrorChannelRole`. Any field on the carrier that does not resolve to a `CarrierFieldRole` permit causes the carrier classifier to reject the carrier-returning mutation field as `UnclassifiedField`; there is no name-match branch and no tolerated-`UnclassifiedField` steady state on the carrier. Future Backlog items (`payload-carrier-affected-row-count`, `payload-carrier-client-mutation-id`) admit new sibling-field shapes by adding `CarrierFieldRole` permits and tightening the shape's compact-constructor invariant; R141 forecloses none of them and the seam is sealed-variant extension, not classifier-branch addition.

## Why R138's rejection doesn't apply

R138 rejects this shape via Invariant #15, naming `TooManyRowsException` as the runtime mechanism. That mechanism is real for the *singleton-data-field* carrier arm R138 closes (`Payload { film: Film }`): N inputs have no honest landing in a one-row slot, and `valuesOfRows(...).fetchOne()` throws. It is **not real here**: the data field is list-shaped (`kvotesporsmalPreutfylling: [KvotesporsmalPreutfylling!]`), so N inputs map to N rows inside the carrier's list. There is no "drop", no `TooManyRowsException`, and no contract violation.

The two arms differ on a single classifier predicate — `dataField.wrapper().isList()` — and the existing carrier-resolution machinery (`SingleRecordCarrierShape`, `DataElement.Table.wrapper`) already carries that bit. R141 routes the list-data-field arm to a new sealed leaf; R138's singleton arm and Invariant #15 are unchanged.

## Position in the model

R141 makes two model moves: a **carrier-shape lift** (a foundational refactor of `SingleRecordCarrierShape` to carry a sealed `CarrierFieldRole` enumeration, consolidating the two parallel carrier walks that exist today), and a **new sealed leaf** (`MutationField.MutationBulkDmlRecordField`, sibling to `MutationDmlRecordField`, that consumes the lifted shape). The lift is the principled answer to the carrier-sibling-field admission question; the new leaf is the principled answer to the bulk-input cardinality question. Both ship together because the leaf's classifier path consumes the lifted shape, and the lift's "exactly two roles admitted today" invariant is the type-system statement that makes the new leaf's carrier-resolution exhaustive.

**Why the lift is folded into R141 rather than a sibling Backlog item.** The natural smaller-PR shape would be a separate `single-record-carrier-shape-lift` item landing first, with R141 added on top as a thin leaf-introduction change. R141 deliberately rejects that split: the new leaf needs a principled carrier-sibling-field admission story (otherwise the no-permit-match rejection truth-table row has no type-system anchor to point at), and the principled story *is* the sealed `CarrierFieldRole` lift. Splitting them would (a) ship the new leaf against a carrier-shape that still has the data-channel-only walk plus a parallel carrier-side error-channel resolution, then immediately rewrite that ground under it, and (b) hide the unifying design rationale across two roadmap items where the second one's plan body would read "now do the principled version of what we just shipped". The lift's blast radius is contained to one model record (`SingleRecordCarrierShape`), one classifier method (`tryResolveSingleRecordCarrier`), the carrier-side call site of `resolveErrorChannel`, and a handful of mechanical call sites that today read `shape.dataFieldName()` / `shape.dataElement()` (compile errors surface them on rename: known sites include `GraphitronSchemaBuilder.java:230-260` and `FieldBuilder.java:2411-2414`). This is foundational work, and foundational work means changing the foundation in the same change that builds on it — not building on the old foundation, then retroactively swapping it out. The reviewer should evaluate R141 as one design move with two coordinated parts, not two design moves stapled together.

### Carrier-shape lift: sealed `CarrierFieldRole`

Today `SingleRecordCarrierShape` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/model/SingleRecordCarrierShape.java`) carries `(carrierTypeName, dataFieldName, dataElement)` — the data-channel-only resolution. The error channel is produced by a parallel walk (`FieldBuilder.resolveErrorChannel`, defined at `FieldBuilder.java:1661`, called from the carrier-resolution path at `FieldBuilder.java:2670`) and attached separately to the `Optional<ErrorChannel>` slot on `MutationDmlRecordField` (`MutationField.java:148`). The two walks classify the *same* carrier independently; their consistency is structural prose, not type-system.

R141 lifts these to a single carrier walk over a sealed role enumeration:

```java
/**
 * Sealed enumeration of the carrier field roles a single-record carrier admits. Today
 * R141 permits exactly DataChannel and ErrorChannelRole. Future Backlog items
 * ({@code payload-carrier-affected-row-count}, {@code payload-carrier-client-mutation-id})
 * add new permits and tighten {@link SingleRecordCarrierShape}'s compact-constructor
 * invariant. The "exactly two admitted today" invariant is a type-system statement: a
 * carrier field that does not resolve to a permit causes the carrier classifier to reject
 * the mutation field, not the carrier to resolve with the unknown field tolerated.
 */
public sealed interface CarrierFieldRole {
    /** The SDL field name on the carrier type. */
    String fieldName();

    /** The data-channel field: the list- or single-shaped read-back of the produced rows. */
    record DataChannel(String fieldName, DataElement element) implements CarrierFieldRole {}

    /**
     * The error-channel field: the carrier-side {@code errors: [SomeError!]!} field whose
     * binding is the R12-produced {@link ErrorChannel} record (payload class, errors slot,
     * defaulted slots, mappings constant). R141 wraps R12's existing record without
     * modifying it.
     */
    record ErrorChannelRole(String fieldName, ErrorChannel binding) implements CarrierFieldRole {}
}
```

`SingleRecordCarrierShape` refactors to carry the closed role list, with helper accessors for the two permits used pervasively by emitters:

```java
public record SingleRecordCarrierShape(
    String carrierTypeName,
    List<CarrierFieldRole> roles
) {
    public SingleRecordCarrierShape {
        roles = List.copyOf(roles);
        long dataChannels = roles.stream().filter(r -> r instanceof CarrierFieldRole.DataChannel).count();
        if (dataChannels != 1) {
            throw new IllegalArgumentException(
                "SingleRecordCarrierShape must carry exactly one DataChannel; got " + dataChannels);
        }
        long errorChannels = roles.stream().filter(r -> r instanceof CarrierFieldRole.ErrorChannelRole).count();
        if (errorChannels > 1) {
            throw new IllegalArgumentException(
                "SingleRecordCarrierShape must carry at most one ErrorChannelRole; got " + errorChannels);
        }
        long distinct = roles.stream().map(CarrierFieldRole::fieldName).distinct().count();
        if (distinct != roles.size()) {
            throw new IllegalArgumentException(
                "SingleRecordCarrierShape roles must have distinct field names");
        }
    }

    public CarrierFieldRole.DataChannel data() {
        return (CarrierFieldRole.DataChannel) roles.stream()
            .filter(r -> r instanceof CarrierFieldRole.DataChannel).findFirst().orElseThrow();
    }

    public Optional<CarrierFieldRole.ErrorChannelRole> errorChannel() {
        return roles.stream()
            .filter(r -> r instanceof CarrierFieldRole.ErrorChannelRole)
            .map(r -> (CarrierFieldRole.ErrorChannelRole) r)
            .findFirst();
    }
}
```

**Walk consolidation.** `BuildContext.tryResolveSingleRecordCarrier` becomes the single carrier walk: it iterates the carrier type's fields, classifies each into a `CarrierFieldRole` permit (or fails on the first unclassifiable field), and produces the closed-list shape. The **carrier-side** call to `FieldBuilder.resolveErrorChannel(returnType)` (at `FieldBuilder.java:2670`) is removed; that site reads `shape.errorChannel().map(role -> role.binding())` from the unified resolution instead. `FieldBuilder.resolveErrorChannel` itself **stays** as a standalone method, called from the four non-carrier sites that need to detect an errors slot on a `ResultReturnType` whose payload type is **not** a carrier: `buildDmlField` for direct-`@table` DML arms (`FieldBuilder.java:2205`), the `MutationServiceTableField` / `MutationServiceRecordField` arms (`FieldBuilder.java:2236`, `2276`), and the query-side service field arm (`FieldBuilder.java:2356`). Those return types are not `SingleRecordCarrierShape`s; they have no role permits to read. The carrier-classifier surface narrows from "two independent walks on the same carrier" to "one walk, one result, exhaustive over permits"; the four non-carrier paths keep their direct caller because there is no carrier walk on their side to consolidate with.

**Shared predicate body, two classifier surfaces.** The errors-field-detection-and-payload-class-reflection inner body of `resolveErrorChannel` (the predicate that produces a well-formed `ErrorChannel` from a `ResultReturnType` and its payload `Class<?>`) is extracted into a private helper that both the new `ErrorChannelRole` classifier rule inside the unified carrier walk and the four direct-return `resolveErrorChannel` callers consume. The two surfaces — carrier-field role classification, and non-carrier `WithErrorChannel` resolution — share the predicate body via that helper while staying on separate entry points. This is what reading "the standalone walk is removed" too literally would have broken: a non-carrier `ResultReturnType` has no carrier shape to project an `ErrorChannelRole` permit through, so unifying entry points would require fabricating a degenerate "carrier" for non-carrier returns, broadening `SingleRecordCarrierShape`'s meaning past its name. The helper extraction captures the genuine duplication (the predicate body) without inventing a carrier abstraction over non-carrier shapes.

**Load-bearing check on the unified walk.** The exhaustive-classification guarantee is anchored as a load-bearing classifier check key `single-record-carrier-shape.roles-exhaustively-classified`. The producer-side `@LoadBearingClassifierCheck` annotation lives on `tryResolveSingleRecordCarrier`, with description "every emitted `SingleRecordCarrierShape.roles` is exhaustively classified into `CarrierFieldRole` permits; emitters dispatch over the closed permit set via sealed switches". The consumer-side `@DependsOnClassifierCheck` annotation lives on every emitter that reads `shape.data()` or `shape.errorChannel()` (the data-channel projection and the catch-arm builder today). The compile-time safety net is the sealed switch in each emitter: a new permit landing without a corresponding consumer dispatch fails `mvn compile -pl :graphitron-sakila-example`. `LoadBearingGuaranteeAuditTest` enforces the producer/consumer pairing; `CarrierFieldRoleCoverageTest` (below) is the build-time sealed-coverage audit that catches a new permit landing without a corresponding consumer dispatch in the same run. (The compact-constructor invariants on `SingleRecordCarrierShape` — exactly one `DataChannel`, at most one `ErrorChannelRole`, distinct field names — are separately enforced at record construction; they sit alongside this check, not under it.)

**R12 surface stays.** R12's `ErrorChannel` record (`graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ErrorChannel.java`) is unchanged. The `Optional<ErrorChannel> errorChannel` slot on `MutationDmlRecordField` (`MutationField.java:148`) and on the new `MutationBulkDmlRecordField` keeps its current external shape; only the carrier-side production site moves. R12's plan body (`error-handling-parity.md`) describes the carrier-side resolver in §2c; R141 coordinates the relocation with R12's eventual shape — concretely, R12's planned carrier-side `resolveErrorChannel` walk is reframed in R12's plan body as "the `ErrorChannelRole` permit's classifier rule inside the unified carrier walk", consuming the shared helper that the standalone `resolveErrorChannel` method also calls. Same predicate body, called from two entry points (role-permit classifier rule for carrier-side, standalone method for the four non-carrier callers).

**Sealed-coverage discipline.** A new `CarrierFieldRoleCoverageTest` (sibling to `GeneratorCoverageTest` at `graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/GeneratorCoverageTest.java:43`) asserts that every emitter consuming a `SingleRecordCarrierShape` dispatches over every `CarrierFieldRole` permit, so that adding a permit in a future Backlog item is caught at build time if any consumer fails to handle it. The data-channel projection and the error-channel catch-arm wiring are the two consumers today; both already pattern-match the role they need.

### Sealed leaf: `MutationBulkDmlRecordField`

A new sealed leaf `MutationField.MutationBulkDmlRecordField`, sibling to `MutationDmlRecordField` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/model/MutationField.java:141-159`). Components mirror the existing leaf with two cardinality flips and one preserved invariant:

```java
/**
 * Bulk-input DML carrier with a list-shaped @table data field. The classifier admits exactly
 * (tableInputArg.list() == true, dataField.wrapper().isList() == true, kind ∈ {INSERT, UPDATE})
 * and pairs the input cardinality to the data field's element type via the existing
 * load-bearing classifier check
 * {@code mutation-dml-record-field.data-table-equals-input-table}
 * (R141 extends this key to cover the new leaf).
 *
 * <p>UPSERT is structurally compatible with this leaf but is refused upstream by
 * {@code MutationInputResolver} under R144's cardinality-safety regime. R145
 * ({@code mutation-cardinality-safety-upsert}) lifts the refusal with a designed
 * cardinality story; at that point this leaf's compact-constructor relaxes to admit
 * {@code DmlKind.UPSERT} and the parameterised emitter gains the UPSERT branch.
 *
 * <p><b>Order preservation invariant.</b> {@code output.data[i]} corresponds to
 * {@code input[i]} for all {@code i ∈ [0, N)}. The emitter satisfies the invariant
 * via batched per-row DML inside one transaction (N+1 statements), collecting PKs
 * in input order and iterating a PK-keyed map of the response-SELECT in that order
 * to build the response. The runtime audit is
 * {@code DmlBulkMutationsExecutionTest}'s N=3 deliberately-non-PK-ordered round-
 * trip. Any future single-statement emit refinement (e.g. an ordinal-preserving
 * Postgres contract) must preserve the same input-order assertion the round-trip
 * test makes. {@code @see} pointers between this record and
 * {@code TypeFetcherGenerator.buildMutationBulkDmlRecordFetcher} are the
 * find-usages anchor; the invariant is not encoded as a load-bearing classifier
 * check because the contract is a runtime claim about emit-order iteration with
 * no compile-time signal under any classifier relaxation, and overloading
 * {@code @LoadBearingClassifierCheck} for navigability-only contracts dilutes
 * the audit's classifier→emitter shape-contract signal.
 *
 * <p><b>Per-kind emit variation.</b> INSERT and UPDATE differ on the per-row
 * statement and the WHERE/SET clauses (see "Mutation-kind coverage" below);
 * future UPSERT lifts at R145 add a third shape with ON CONFLICT semantics. The
 * components of this record are the same across kinds today, but the emit
 * shapes are not; the principles-aligned target is sealed-on-kind permits
 * mirroring {@code DmlTableField}, tracked at
 * {@code dml-record-carrier-sealed-on-kind} as the joint lift over both record-
 * carrier leaves. Until that lift lands, the {@link DmlKind} enum field encodes
 * the per-emit-shape dispatch and the parameterised emitter switches on it.
 *
 * <p><b>DELETE rejection.</b> Mirrors {@link MutationDmlRecordField}: DELETE-with-payload-return
 * is incorrect by construction (the row is gone before the response SELECT can read it).
 * The compact-constructor invariant is belt-and-braces under the upstream classifier check
 * that already rejects DELETE before this record is constructed.
 */
record MutationBulkDmlRecordField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef.ResultReturnType returnType,        // carrier wrapper is single (non-null or nullable)
    ArgumentRef.InputTypeArg.TableInputArg tableInputArg,  // .list() == true (invariant)
    DmlKind kind,                                     // INSERT / UPDATE; DELETE rejected, UPSERT deferred to R145
    Optional<ErrorChannel> errorChannel               // R12 slot, unchanged
) implements MutationField {
    public MutationBulkDmlRecordField {
        if (kind == DmlKind.DELETE) {
            throw new IllegalArgumentException(
                "MutationBulkDmlRecordField cannot carry DmlKind.DELETE — "
                + "DELETE-with-payload-return is rejected at classify time "
                + "(returning pre-deletion state is incorrect by construction).");
        }
        if (kind == DmlKind.UPSERT) {
            throw new IllegalArgumentException(
                "MutationBulkDmlRecordField cannot carry DmlKind.UPSERT under R144's "
                + "cardinality-safety regime — UPSERT is refused at the upstream "
                + "MutationInputResolver and lifts via R145 "
                + "(mutation-cardinality-safety-upsert).");
        }
        if (!tableInputArg.list()) {
            throw new IllegalArgumentException(
                "MutationBulkDmlRecordField requires bulk @table input "
                + "(tableInputArg.list() == true); single-input belongs on MutationDmlRecordField.");
        }
    }
}
```

**Why `DmlKind` enum, not sealed-on-kind permits.** `MutationDmlRecordField` (the singleton sibling) currently carries `DmlKind kind` as an enum (`MutationField.java:147`). R141 mirrors that style for intra-pair consistency: making R141 sealed-on-kind while the sibling stays flat creates a structural asymmetry between two leaves that share every classifier predicate except data-field cardinality. The principles-aligned shape (sealed-on-kind, mirroring `DmlTableField`'s `MutationInsertTableField` / `MutationUpdateTableField` / `MutationUpsertTableField` permits — see `changelog.md` R22 entry) is the right long-term target for **both** record-carrier leaves; a sibling Backlog item `dml-record-carrier-sealed-on-kind` lifts both `MutationDmlRecordField` and `MutationBulkDmlRecordField` together once R141 lands. Splitting the refactor from R141 avoids divergence and keeps each item focused.

**Why a flat sibling of `MutationField`, not a `DmlRecordCarrierField` sub-taxonomy.** The principles-aligned shape would introduce sealed `DmlRecordCarrierField extends MutationField permits MutationDmlRecordField, MutationBulkDmlRecordField` with the shared `(returnType, tableInputArg, kind, errorChannel)` component bag — the same move `DmlTableField` made for direct-return arms. R141 leaves both leaves as flat siblings of `MutationField` for the same reason as the enum-vs-sealed-on-kind decision above: the sub-taxonomy lift is a structural refactor that R141 should not be coupled to. A sibling Backlog item `dml-record-carrier-sub-taxonomy` lifts both leaves under the new sealed parent post-R141. The two refactor items (sub-taxonomy + sealed-on-kind) are independent and can land in either order.

Add `MutationBulkDmlRecordField` to the sealed `permits` list on `MutationField` (line 18-19). It does **not** join `DmlTableField`; that sealed supertype permits the four direct-DML records and shares the `(returnType, encodeReturn)` shape, which `MutationBulkDmlRecordField` does not have (its return is the carrier, not the DML's direct return).

## Carrier admission rules

In `FieldBuilder` carrier-resolution (the R75 Phase 1 path at `FieldBuilder.java:2664-2685`, which `MutationDmlRecordField` already uses), extend the data-field cardinality switch:

| `tableInputArg.list()` | `DataElement.Table.wrapper().isList()` | Outcome |
|---|---|---|
| `false` | `false` | `MutationDmlRecordField` (R75 Phase 1, existing) |
| `false` | `true`  | rejected — single input, list data field has nothing to fill it from |
| `true`  | `false` | rejected via R138's lifted Invariant #15 |
| `true`  | `true`  | **`MutationBulkDmlRecordField` (R141, new) — for kind ∈ {INSERT, UPDATE}; UPSERT refused upstream by R144** |

The single-input + list-data-field rejection is a fresh rule (not currently covered by R138 or R75). It is **a new predicate, not an extension of R138's lifted Invariant #15**: R138's predicate is `listInput && !returnType.wrapper().isList()` (the carrier-wrapper arm); R141's new rejection predicate is `!listInput && carrier.dataField.wrapper().isList()` (the data-field cardinality arm). The two predicates together complete the input-cardinality / data-field-cardinality 2×2 matrix and warrant **a new Invariant family** in the same spirit. Name it Invariant #16 ("carrier data-field cardinality matches input cardinality") and surface the rejection message as: `"@mutation(typeName: <kind>) with a single @table input cannot return a list-shaped data field on the carrier ('<carrierName>.<dataField>'); list-shaped output requires bulk input (Invariant #16)"`.

The same-`@table` invariant on the data-channel element type is already enforced by the load-bearing classifier check `mutation-dml-record-field.data-table-equals-input-table`. R141 extends the check key (or files a sibling key with the same predicate body) to cover the new sealed leaf; the check itself is unchanged in shape.

**Carrier sibling-field admission: type-system-driven.** The carrier classifier (the unified `tryResolveSingleRecordCarrier` walk introduced by the carrier-shape lift above) iterates the carrier type's fields and classifies each into a `CarrierFieldRole` permit. The two permits R141 ships are `DataChannel` (the field whose shape matches the data-channel classifier rule: a `@table`-element field paired to the input `@table`, list-shaped for the bulk leaf, single-shaped for the singleton leaf) and `ErrorChannelRole` (the field whose shape matches R12's errors-channel classifier rule). A carrier field that resolves to **no permit** causes the walk to return `SingleRecordCarrierResolution.Rejected` with a descriptive reason naming the offending field; the upstream carrier-returning mutation field then classifies as `UnclassifiedField`. The "exactly the admitted roles, nothing else" invariant is the type-system statement; there is no name-match check, no tolerated-sibling shape, and no `UnclassifiedField` at the carrier-field level.

The rejection reason text: `"Carrier '<carrierName>' has unrecognised field '<fieldName>' that resolves to no CarrierFieldRole permit; only DataChannel (one @table data field paired to the input @table) and ErrorChannelRole (R12 errors-shaped field) are admitted today. Adding support for '<fieldName>' requires a new CarrierFieldRole permit; file a roadmap item for the field shape."`. The message names the type-system mechanism (`CarrierFieldRole` permit) rather than a string-match rule, so authors looking at the rejection reach the right extension point.

Future Backlog items (`payload-carrier-affected-row-count`, `payload-carrier-client-mutation-id`) admit new sibling-field shapes by **adding a `CarrierFieldRole` permit and tightening the shape's compact-constructor invariant**; each item is one permit + one classifier rule, no re-admission of the carrier and no edits to existing emitters that already pattern-match the roles they consume.

## Order preservation: invariant and emit strategy

**Contract**: `output.data[i]` corresponds to `input[i]` for all i ∈ [0, N). This is a critical correctness invariant. Consumers depend on it for cross-index correlation (e.g., reading `output.errors[i]` against `input[i]`, or pairing client-side state to server response state).

**Why single-statement `valuesOfRows(...).returning(PK).fetch()` doesn't satisfy this.** PostgreSQL's `INSERT ... RETURNING` does not guarantee row order against the input VALUES order — the SQL standard is silent, and `RETURNING` is documented as returning "the inserted, updated, or deleted rows" without an ordering claim. Empirically Postgres often preserves input order, but the planner is free to reorder, and parallelism (or RLS, triggers, or BEFORE-INSERT hooks rearranging rows) can break the pattern silently. Relying on it is an "it works on my machine" contract.

Single-statement workarounds are all sharp:

- **`unnest(...) WITH ORDINALITY` + writable CTE projecting ordinal back**: requires the target table to carry an ord column. Real tables don't.
- **Post-hoc correlation via input-data uniqueness**: brittle when input rows aren't unique on the columns RETURNING surfaces.
- **`array_position` over a captured array literal**: same uniqueness problem; quadratic.

**Emit strategy: batched per-row DML inside one transaction.** The fetcher loops the input list, executes one `INSERT ... RETURNING <pk>` / `UPDATE ... RETURNING <pk>` / `INSERT ... ON CONFLICT ... RETURNING <pk>` per row, collects results into a `List<RecordN<PK>>` whose iteration order is the input order (Java preserves this trivially). One follow-up `SELECT ... WHERE pk IN (...)` runs the response-projection for the data channel; the result is keyed back into a PK-indexed map, then iterated by the PK list in input order to build the response.

Cost: N+1 statements per mutation (N DML round-trips + 1 read-back SELECT), all inside one `dsl.transactionResult(...)`. For typical mutation N (≤ 50), this is acceptable; for N in the hundreds, this is still acceptable for the explicit-batch case GraphQL mutations represent. A future optimisation could lift to single-statement emit if Postgres adds a ordinal-preserving RETURNING contract; nothing in R141's design forecloses it.

This is a deliberate cost trade-off: correctness invariant first, throughput optimisation later if profiled.

**Why this is not a load-bearing classifier check.** The order-preservation contract is a runtime claim about how the emitter iterates its inputs; it has no compile-time signal under any classifier relaxation. Every existing `@LoadBearingClassifierCheck` key anchors a classifier-time *shape* fact a typed emitter relies on, with `mvn compile -pl :graphitron-sakila-example` as the safety net (e.g. `service-catalog-strict-tablemethod-return` lets the emitter write `<SpecificTable> table = method.x(...)` without a cast; `mutation-dml-record-field.data-table-equals-input-table` lets the response-SELECT emitter join the data field's table to the input table without a fallback). The order-preservation invariant fits neither of those shapes: there is no narrower type the emitter consumes because the invariant holds, and there is no compile failure of the emitted source if the loop iteration order is broken. Overloading `@LoadBearingClassifierCheck` for navigability-only contracts dilutes its single-question audit signal (classifier→emitter shape contract with compile-time safety net). The contract therefore lives in three places, none of which is the load-bearing-check audit: (1) the Javadoc paragraph on `MutationBulkDmlRecordField` (cited above) records the invariant in code; (2) `DmlBulkMutationsExecutionTest`'s N=3 deliberately-non-PK-ordered round-trip is the runtime audit; (3) bidirectional `@see` pointers between `MutationBulkDmlRecordField` and `TypeFetcherGenerator.buildMutationBulkDmlRecordFetcher` are the find-usages anchor a future emit refinement reaches first.

## Response-SELECT (data channel)

After the DML batch, run one `SELECT <projectedColumns> FROM <table> WHERE <pkColumn> IN (?, ?, ..., ?)` against the PKs collected from the DML batch. Project into the data field's element type via the existing single-carrier read-back machinery (the `SingleRecordCarrierShape.DataElement.Table` projection path R75 Phase 1 introduced); the only difference is the loop iterates `pkList` (built from the DML batch) instead of a single `pk`.

The map-by-PK indirection handles the case where the response-SELECT returns rows in any order; the input-ordered iteration over `pkList` produces an input-ordered output `List<Map<?,?>>` (the data field's emit type), which graphql-java's value mapper serialises into the list-shaped data field.

For UPDATE, the input rows already carry filter columns identifying the target row (sourced from `tableInputArg.fieldBindings()` regardless of which directive triggered binding production; pre-R144 the `@lookupKey` opt-in, post-R144 the default-filter walk). The DML emits `RETURNING <pk>` and the same response-SELECT runs. For INSERT, the PKs are generated by the DB; `RETURNING <pk>` surfaces them, the same response-SELECT runs. UPSERT lifts at R145 with an equivalent `RETURNING <pk>` shape.

## Error channel: R12 record reused, production site relocated

R12 (`error-handling-parity`, Ready) specs the carrier-side `errors: [SomeError!]!` field shape, the `ErrorChannel` record (`mappedErrorTypes`, `payloadClass`, `errorsSlotIndex`, `defaultedSlots`, `mappingsConstantName` — see `ErrorChannel.java`), the `PayloadAssembly` reflection over a developer-supplied payload class, and the catch-arm wiring through `catchArm(outputPackage, errorChannel)`. **R141 does not modify R12's `ErrorChannel` record**; it relocates the *carrier-side production site* from a direct `resolveErrorChannel(returnType)` call (at `FieldBuilder.java:2670`) into the unified carrier walk introduced by the carrier-shape lift. The same predicate body — match an SDL field on the carrier whose shape is `errors: [SomeError!]!` and whose mapped error types resolve cleanly, then reflect the payload class — runs inside the new walk via the shared helper and produces an `ErrorChannelRole` permit wrapping R12's record. The four non-carrier `resolveErrorChannel` callers (direct-`@table` DML, service-arm fields) keep their existing direct-call shape; only the carrier-side site moves into the role-permit classifier rule.

The `Optional<ErrorChannel> errorChannel` slot on `MutationDmlRecordField` (`MutationField.java:148`) and `MutationBulkDmlRecordField` keeps its current external API: it is populated from `shape.errorChannel().map(CarrierFieldRole.ErrorChannelRole::binding)` against the unified resolution, rather than from a separate `resolveErrorChannel` call. Emitters that read the slot are untouched. The slot is the *projection* of the unified walk's error-channel role through the field record; the role permit on the shape is the *type-system anchor* that says "this carrier admits at most one error channel, and if it has one this is its binding".

**R12 coordination.** R12 is `Ready` (not yet shipped). R141's relocation of the carrier-side production site requires a corresponding adjustment in R12's plan body — concretely, R12's §2c "carrier-side `resolveErrorChannel` walk" is reframed as "the `ErrorChannelRole` permit's classifier rule inside the unified carrier walk", consuming the shared helper that the standalone `resolveErrorChannel` method also calls. The standalone method itself stays for the four non-carrier `WithErrorChannel`-emitting paths. The classifier check that R12 ships as load-bearing (R12's `error-channel.payload-class-canonical-constructor-shape` or equivalent) attaches to the shared helper's predicate body, which is reached from both the role-permit classification site and the standalone method. R12's emitter-side consumers (the catch-arm builder) are unaffected.

This is a coordination point, not a temporal blocker: if R141 lands first against R12's `Ready` plan body, R12's spec adjusts to consume the role permit when R12 lands; if R12 lands first, R12 ships its enrichments on `resolveErrorChannel` and R141 routes the carrier-side caller through the shared helper as part of R141's commits. The acceptance criterion in either ordering is the same: one carrier walk, the `Optional<ErrorChannel>` slot on carrier-returning DML fields populated from `shape.errorChannel()`, the shared predicate body called from both the role-permit classifier rule and the four non-carrier `resolveErrorChannel` callers.

**R12 lands both producer and consumer annotations in one commit.** R141 deliberately does **not** land consumer-side `@DependsOnClassifierCheck` annotations against R12's planned `error-channel.*` classifier-check keys. Doing so would require new audit infrastructure (a pending-producer allow-list, a `PENDING_PRODUCER` warning state, a `DUPLICATE_TOLERATED` cleanup violation, plus a meta-test) whose only consumer is the R141 → R12 window — one-shot scaffolding that sits permanently empty after R12 clears it. The simpler convergence: R12 lands its producer-side `@LoadBearingClassifierCheck` annotations and the matching consumer-side `@DependsOnClassifierCheck` annotations in the same commit. R141's contractual record of what its emitters trust about the role permit lives in (a) the `ErrorChannelRole` permit's Javadoc, which names the `ErrorChannel` properties (`mappedErrorTypes`, `payloadClass`, `errorsSlotIndex`, `defaultedSlots`, `mappingsConstantName`) that the catch-arm builder reads, and (b) the role-permit classifier rule's Javadoc inside `tryResolveSingleRecordCarrier`, which describes the same predicate body the shared helper enforces. R12's landing checklist references this Javadoc as the consumer-side trust statement it needs to instrument with annotations.

**Semantics**: atomic-transaction, flat-error-list. If any row in the batched DML throws (constraint violation, type mismatch, RLS denial, etc.), the catch arm rolls back the entire transaction, maps the exception through `errorChannel`'s configured `ErrorRouter`, and emits a payload with the data channel empty (`[]`) and the error channel populated with the mapped error type(s). Partial application is **not** in scope: R12's flat error model and the transaction-rollback contract are load-bearing.

**Per-row error correlation is out of scope.** A future plan can extend the error channel to per-row error semantics (with index correlation matching the data-channel ordering); R141 explicitly defers this. The reasoning: per-row errors require either non-atomic application (rejected by R12's design) or in-flight validation before DML emission (a different kind of error path, also a separate plan). R141's contract is "all-N succeed (data channel) or none commit (error channel)".

The error-channel field on the carrier classifies via R12's existing machinery; R141 makes no changes to the carrier classifier's error-channel handling. The new sealed leaf is just another consumer of `Optional<ErrorChannel> errorChannel()`.

## Mutation-kind coverage

INSERT and UPDATE admitted in R141's initial landing. DELETE rejected at classify time via the compact-constructor (same reasoning as `MutationDmlRecordField`: returning pre-deletion state is incorrect by construction; the row is gone before the response SELECT can read it). UPSERT deferred to R145: R144's cardinality-safety regime refuses every `@mutation(typeName: UPSERT)` field at `MutationInputResolver` before carrier-resolution reaches this leaf, so the leaf cannot meaningfully admit UPSERT under R144. R145 (`mutation-cardinality-safety-upsert`) designs the UPSERT cardinality story and, when it lands, lifts both the upstream refusal *and* the compact-constructor rejection here. Two emit shapes, one shared response-SELECT:

- **INSERT**: per-row `dsl.insertInto(table, cols).values(vals).returningResult(pkCols).fetchOne()` inside a loop. PKs flow into the response-SELECT.
- **UPDATE**: per-row `dsl.update(table).set(cols).where(buildLookupWhere(tia, row)).returningResult(pkCols).fetchOne()`. Reuses the existing lookup-WHERE builder from `MutationUpdateTableField`; the filter-column source is unchanged across R144's polarity flip (the builder reads `InputColumnBindingGroup` rows from `tableInputArg.fieldBindings()`, which R144 populates from the default-filter walk rather than the legacy `@lookupKey` gate).

Two emitters in `TypeFetcherGenerator` — `buildMutationBulkDmlRecordInsertFetcher`, `...UpdateFetcher` — or one parameterised emitter dispatching on `DmlKind`. The parameterised shape is the natural choice (mirrors the R22 `buildDmlFetcher` skeleton at `TypeFetcherGenerator.java`); the two kind-specific helpers differ only in the per-row statement and the WHERE/SET clauses. R145 adds the UPSERT branch (`...UpsertFetcher` or the third arm of the parameterised emitter) when it lifts the deferral; the existing INSERT and UPDATE arms are unaffected.

## Classifier-tier truth-table coverage

Three rows total — one admitted, two rejections covering the two new mismatch cells. Mirror R138's `DML_INSERT_LIST_PLAIN_PAYLOAD_REJECTED` (lines 5524-5537 region) structure for each.

```java
DML_INSERT_LIST_PLAIN_PAYLOAD_LIST_DATA_ADMITTED(
    "DML INSERT with listed input + plain SDL Object payload return + list-shaped @table data field → MutationBulkDmlRecordField",
    """
    type Film @table(name: "film") { title: String }
    type FilmsPayload { films: [Film!] }
    input FilmInput @table(name: "film") { title: String }
    type Query { x: String }
    type Mutation {
        createFilmsPayload(in: [FilmInput!]!): FilmsPayload @mutation(typeName: INSERT)
    }
    """,
    schema -> {
        var f = (MutationField.MutationBulkDmlRecordField) schema.field("Mutation", "createFilmsPayload");
        assertThat(f.kind()).isEqualTo(DmlKind.INSERT);
        assertThat(f.tableInputArg().list()).isTrue();
    }),
```

One admitted row covers the mechanism — same one-row-per-mechanism precedent as R138. A UPDATE admitted-row is not added; the kind-switch shares the same classifier path and execution-tier coverage exercises the per-kind emit differences. UPSERT does not get an admitted row in R141's truth-table because R144 refuses UPSERT upstream; R145 adds the admitted UPSERT row when it lifts the deferral.

The second row covers the new Invariant #16 rejection (single input + list data field):

```java
DML_INSERT_SINGLE_LIST_DATA_REJECTED(
    "DML INSERT with single input + list-shaped @table data field on carrier → UnclassifiedField (Invariant #16)",
    """
    type Film @table(name: "film") { title: String }
    type FilmsPayload { films: [Film!] }
    input FilmInput @table(name: "film") { title: String }
    type Query { x: String }
    type Mutation { createFilmPayload(in: FilmInput!): FilmsPayload @mutation(typeName: INSERT) }
    """,
    schema -> {
        var f = (UnclassifiedField) schema.field("Mutation", "createFilmPayload");
        assertThat(f.reason())
            .contains("single @table input cannot return a list-shaped data field")
            .contains("Invariant #16");
    }),
```

The third row covers carrier-field-role rejection (any field that resolves to no `CarrierFieldRole` permit):

```java
DML_INSERT_LIST_PAYLOAD_NO_CARRIER_FIELD_ROLE_REJECTED(
    "DML INSERT with bulk input + carrier carrying a field that resolves to no CarrierFieldRole permit → UnclassifiedField",
    """
    type Film @table(name: "film") { title: String }
    type FilmsPayload { films: [Film!], affectedRowCount: Int }
    input FilmInput @table(name: "film") { title: String }
    type Query { x: String }
    type Mutation {
        createFilmsPayload(in: [FilmInput!]!): FilmsPayload @mutation(typeName: INSERT)
    }
    """,
    schema -> {
        var f = (UnclassifiedField) schema.field("Mutation", "createFilmsPayload");
        assertThat(f.reason())
            .contains("affectedRowCount")
            .contains("CarrierFieldRole permit")
            .contains("file a roadmap item");
    }),
```

## Execution-tier coverage

Three round-trip tests in `DmlBulkMutationsExecutionTest` (the same fixture file R134's empty-input regression lives in). The full test method names are enumerated in §"Tests → L4 execution"; the assertion structures are described here.

**`bulkInsertWithThreeRowsInNonPkOrderPreservesInputOrderInResponse`.**

1. Build a payload mutation against a real PostgreSQL via the Sakila harness.
2. Run with `N == 3` input rows whose primary keys / business keys are distinct and **deliberately not sorted** in the natural PK order (e.g., insert rows whose generated PKs would sort 1, 2, 3, but whose business-key columns sort `'c'`, `'a'`, `'b'`).
3. Assert the response's data-channel list is **in input order** (`'c'`, `'a'`, `'b'`), not PK order or natural-key order. This is the load-bearing assertion for the order-preservation contract.
4. Assert all N rows are present (no drops).

**`bulkInsertWithSingleRowExercisesBulkLeafPath`.** N=1 round-trip confirming the bulk emit path doesn't regress the single-input case (the bulk leaf must work for any `tia.list() == true && N >= 1`, including N=1, since the SDL admits it).

**`bulkUpdateWithThreeRowsInNonPkOrderPreservesInputOrderInResponse`.** UPDATE variant of the order-preservation test, exercising the per-row UPDATE emit path. Filter columns sourced via `tableInputArg.fieldBindings()` (R141's polarity-agnostic surface). Same input-order assertion as the INSERT variant.

An error-channel execution test is **not** added in R141 — R12 owns the error-channel execution coverage, and R141's carrier-shape lift relocates the carrier-side production site without changing the `ErrorChannel` record or the catch-arm runtime, so R12's L4 coverage stays load-bearing across the relocation. If R12 ships before R141, R141 inherits R12's coverage and routes the carrier-side caller through the shared helper in commit (1) of R141's sequence; if R141 lands first against R12's `Ready` plan body, the error-channel slot is plumbed via the `ErrorChannelRole` permit and the catch arm follows R12's eventual shape unchanged.

## R138 fixture status (post-landing)

R138 has shipped (see `changelog.md`). It converted four `SingleRecordCarrierPipelineTest` fixtures and one `GraphitronSchemaBuilderTest.MUTATION_DML_RECORD_FIELD` row from bulk input to single input, and lifted Invariant #15 to `MutationInputResolver.validateReturnType`. R141 does **not** revert those R138 changes — they keep their single-input shape and continue to pin the singleton-carrier path (`MutationDmlRecordField`). The list-data-field bulk case lands at a different sealed leaf (`MutationBulkDmlRecordField`) and gets its own fixtures; the two paths don't share test infrastructure.

R138's `DML_INSERT_LIST_PAYLOAD_REJECTED` row stays as the rejection coverage for the singleton-data-field bulk case (`Payload { film: Film }`). R141's three new rows (`DML_INSERT_LIST_PLAIN_PAYLOAD_LIST_DATA_ADMITTED` + `DML_INSERT_SINGLE_LIST_DATA_REJECTED` + `DML_INSERT_LIST_PAYLOAD_NO_CARRIER_FIELD_ROLE_REJECTED`) cover the complementary cells of the cardinality matrix and the no-permit-match rejection.

## Implementation order

R141 lands **after R138** (which has shipped). R138's lifted Invariant #15 predicate at `MutationInputResolver.validateReturnType` is the predicate R141 routes around: R141's new admitted arm must fire **before** the lifted check so that bulk-input + list-data-field cases are admitted rather than rejected. The classifier ordering is: (1) check whether the carrier shape resolves to `MutationBulkDmlRecordField` (R141); if yes, admit. (2) otherwise, fall through to the lifted Invariant #15 (R138), which rejects bulk-input + non-list-carrier-wrapper cases.

**Intra-R141 ordering.** The carrier-shape lift (sealed `CarrierFieldRole`, refactored `SingleRecordCarrierShape`, unified walk in `tryResolveSingleRecordCarrier`, shared predicate-body helper extracted from `resolveErrorChannel`) lands **before** the new sealed leaf in R141's commit sequence: the leaf's classifier path consumes the lifted shape, the leaf's `Optional<ErrorChannel>` slot populates from `shape.errorChannel()`, and the no-permit-match rejection truth-table row exercises the lifted shape's rejection path. Commit-level order is (1) introduce `CarrierFieldRole` + refactor `SingleRecordCarrierShape` + extract the shared predicate-body helper + replace the carrier-side `resolveErrorChannel` call with the role-permit classifier rule inside the unified walk (existing `MutationDmlRecordField` and any other `SingleRecordCarrierShape` consumers migrate to the lifted shape in this commit; the four non-carrier `resolveErrorChannel` callers are untouched), (2) add `MutationBulkDmlRecordField` + classifier routing + emitter, (3) add the truth-table rows and execution test.

**R12 coordination.** R141's carrier-shape lift relocates the carrier-side `resolveErrorChannel` call into the unified walk; the `ErrorChannel` record itself is unchanged, and `resolveErrorChannel` continues to exist for the four non-carrier callers (sharing its predicate body with the new role-permit classifier rule via an extracted helper). R12 is `Ready` (not yet shipped), which makes the coordination cheap: if R141 lands first, R12's plan body adjusts to consume the `ErrorChannelRole` permit when R12 lands (its catch-arm-emitter machinery is unaffected, only the carrier-side entry point); if R12 lands first, R12 ships enrichments on `resolveErrorChannel` and R141 routes the carrier-side caller through the shared helper in commit (1). Either ordering converges on the same end state: one carrier walk, R12's record produced inside the `ErrorChannelRole` classifier rule, the slot on the field record populated via `shape.errorChannel()`.

**R144 coordination: R141 lands first and absorbs subsequent R144 divergence.** R144 (`mutation-cardinality-safety-default`, Spec) inverts the cardinality-safety polarity for DELETE / UPDATE input fields and retires `@lookupKey` on `INPUT_FIELD_DEFINITION` in favour of a default-filter walk plus `@multiRow` opt-out and `@value` for UPDATE assignment partition. R141 and R144 are peers in the `mutations-errors` theme and have been coordinating fence-line edits in parallel, but **R141 is the lead item** and is expected to reach `In Review` first; R144 lands afterwards. The ordering choice is deliberate: R141's foundational moves (carrier-shape lift, new sealed leaf, classifier-tier coverage of the bulk-carrier matrix) need to be in trunk before R144's polarity flip rewrites the UPDATE filter-column source, so that R141's classifier and emitter paths exist for R144 to retarget rather than R144 having to forward-declare them. R141's design accommodates R144 by reading `tableInputArg.fieldBindings()` (R144's polarity-agnostic surface) rather than the legacy `@lookupKey` gate (the UPDATE bullet under §"Mutation-kind coverage" already notes this); when R144 lands, the binding production site changes but the consumer surface R141 reads does not.

**Picking up divergence.** R144's Spec body and R141's Spec body have evolved in parallel (see commits `7a149dd` for the joint UPSERT carve-out and `6291980` / `2ab7e67` for the cleanup passes). R141's `In Progress` phase will rebase on whatever shape R144 has reached at that point, and the R141 implementer is expected to pick up any divergence introduced by R144's continued Spec evolution between R141 reaching `Ready` and R141 reaching `Done`. Concretely: (a) if R144's Spec body changes the `@multiRow` / `@value` surface or the default-filter walk's output type in a way that affects `tableInputArg.fieldBindings()`'s shape, R141's emitter adjusts to the new surface; (b) if R144 narrows or widens the admitted-kind matrix beyond the current `{INSERT, UPDATE}` ∪ `{UPSERT deferred to R145}` carve-out, R141's compact-constructor and classifier truth-table rows update to match; (c) if R144 introduces a new rejection predicate that overlaps R141's Invariant #16 family, R141's rejection-message text and truth-table reasons reconcile with R144's during R141's `In Progress` rebase. The convergence point is: whichever of the two items lands at `In Review` later inherits the test-fixture migration sweep for the overlap; in the planned ordering (R141 first), that sweep falls to R144.

## Tests

Test methods are named so each acceptance-criterion bullet maps to a specific test; the mapping table follows the per-tier enumeration below.

### L3 — classifier tier (`GraphitronSchemaBuilderTest`)

Three new truth-table rows on the existing parameterised classifier test. The test method that consumes them (`GraphitronSchemaBuilderTest#classifierProducesExpectedShape(TruthTableRow)`) is unchanged; R141 only adds enum values and the per-row assertion lambda.

- `DML_INSERT_LIST_PLAIN_PAYLOAD_LIST_DATA_ADMITTED` — bulk `@table` input + plain payload + list-shaped data field → `MutationBulkDmlRecordField`. Assertions: `f instanceof MutationBulkDmlRecordField`, `f.kind() == INSERT`, `f.tableInputArg().list() == true`, `shape.roles()` contains exactly one `DataChannel` and no `ErrorChannelRole`.
- `DML_INSERT_SINGLE_LIST_DATA_REJECTED` — single `@table` input + list-shaped data field → `UnclassifiedField` with reason `"single @table input cannot return a list-shaped data field"` and `"Invariant #16"`.
- `DML_INSERT_LIST_PAYLOAD_NO_CARRIER_FIELD_ROLE_REJECTED` — bulk `@table` input + carrier carrying an unrecognised field (`affectedRowCount: Int`) → `UnclassifiedField` with reason naming the offending field, the `CarrierFieldRole` permit extension point, and `"file a roadmap item"`.

A fourth row covering UPDATE is **not** added at L3: the kind-switch shares the classifier path with INSERT and execution-tier coverage exercises the per-kind emit differences. R141's classifier coverage is intentionally one-row-per-mechanism, mirroring R138.

### L4 — execution tier (`DmlBulkMutationsExecutionTest`)

Three new test methods on the existing execution-tier fixture file. Method names use full descriptive sentences; the existing tests in this file (R134's empty-input regressions) use the same convention.

- `bulkInsertWithThreeRowsInNonPkOrderPreservesInputOrderInResponse()` — N=3 inputs whose business-key columns sort `'c'`, `'a'`, `'b'` while generated PKs sort `1, 2, 3`. Asserts `response.data` is in input order (`'c'`, `'a'`, `'b'`), not PK order, and `response.data.size() == 3`. This is the load-bearing assertion for the order-preservation contract.
- `bulkInsertWithSingleRowExercisesBulkLeafPath()` — N=1 sanity test confirming the bulk leaf admits and emits correctly when `tia.list() == true && N == 1`. Asserts `response.data.size() == 1` and the inserted row is readable via response projection.
- `bulkUpdateWithThreeRowsInNonPkOrderPreservesInputOrderInResponse()` — UPDATE variant of the order-preservation test; per-row UPDATE with filter columns from `tableInputArg.fieldBindings()`. Asserts the same input-order invariant for UPDATE.

The R134 empty-input test (`bulkInsertWithEmptyInputListReturnsEmptyData()` or whatever the existing name is) stays as the N=0 edge-case anchor; R141 does not modify it.

An error-channel execution test is **not** added in R141 — R12 owns the error-channel execution coverage. If R12 ships first, R141 inherits the existing tests; if R141 lands first against R12's `Ready` plan body, the catch-arm runtime follows R12's eventual shape unchanged.

### L4 — sealed-coverage tier

- `GeneratorCoverageTest` (existing, at `graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/GeneratorCoverageTest.java:43`): asserts coverage over `MutationField.class`. Picks up `MutationBulkDmlRecordField` automatically when the new leaf joins the `permits` clause; no test code changes needed.
- `VariantCoverageTest` (existing): same automatic pickup on the model-side variant enumeration.
- `CarrierFieldRoleCoverageTest` (**new**) — sibling to `GeneratorCoverageTest`. Single test method `everyEmitterConsumingSingleRecordCarrierShapeDispatchesOverEveryPermit()`. Scans emitters that take a `SingleRecordCarrierShape` parameter, asserts each emitter has a switch / pattern-match covering every `CarrierFieldRole` permit. Adding a permit in a future Backlog item fails this test if any consumer is left unhandled.

### Load-bearing-check audit (`LoadBearingGuaranteeAuditTest`)

The existing test method `productionAnnotationsAreConsistent()` (`LoadBearingGuaranteeAuditTest.java:58`) picks up new producer/consumer pairings automatically via classpath scan; R141 adds one new pairing:

- `single-record-carrier-shape.roles-exhaustively-classified` — producer `@LoadBearingClassifierCheck` on `BuildContext.tryResolveSingleRecordCarrier`; consumer `@DependsOnClassifierCheck` on every emitter reading `shape.data()` or `shape.errorChannel()` (data-channel projection, catch-arm builder). The load-bearing fact is that every emitted `SingleRecordCarrierShape.roles` is exhaustively classified into `CarrierFieldRole` permits; the compile-time safety net under classifier relaxation is the sealed switch in each emitter — a new permit landing without a corresponding consumer dispatch fails `mvn compile`. `CarrierFieldRoleCoverageTest` (below) is the build-time signal that the contract holds across the emitter surface today.

**Order-preservation contract: not in the audit.** The bulk-DML emitter's input-order-iteration invariant is captured via Javadoc on `MutationBulkDmlRecordField` plus bidirectional `@see` cross-references to `TypeFetcherGenerator.buildMutationBulkDmlRecordFetcher`, with `DmlBulkMutationsExecutionTest`'s deliberately-non-PK-ordered round-trip as the runtime audit. It is **not** anchored as a `@LoadBearingClassifierCheck` key because the contract has no compile-time signal under any classifier relaxation; see §"Why this is not a load-bearing classifier check" under §"Order preservation: invariant and emit strategy" above.

**R12 producer/consumer pairing: not in this commit.** R141 does not land consumer-side `@DependsOnClassifierCheck` annotations against R12's planned `error-channel.*` classifier-check keys; R12 lands both halves of those pairings in one commit when it ships. R141's `ErrorChannelRole` permit Javadoc records the trust statement R12 will instrument. See §"Error channel: R12 record reused, production site relocated" above.

`mutation-dml-record-field.data-table-equals-input-table` (or its R141-extended key covering both record-carrier leaves) inherits its existing producer/consumer pair without code changes here.

### Compact-constructor invariants (replace would-be classifier checks; type-system-carried)

DELETE-rejection, UPSERT-deferral, list-input-required on `MutationBulkDmlRecordField`; exactly-one-`DataChannel`, at-most-one-`ErrorChannelRole`, distinct-field-names on `SingleRecordCarrierShape`. These are not L1 unit tests; they fire at record-construction time and are exercised through the L3 truth-table rows and the L4 execution tests that drive every code path that constructs the records.

### Acceptance criterion → test mapping

| Acceptance criterion (paraphrased) | Test method |
|---|---|
| `CarrierFieldRole` sealed interface exists with two permits | `GraphitronSchemaBuilderTest#DML_INSERT_LIST_PLAIN_PAYLOAD_LIST_DATA_ADMITTED` (asserts `shape.roles()` contents); compile-time |
| `SingleRecordCarrierShape` lifted to roles + compact-ctor invariants | compact-ctor enforces at construction; covered transitively by every L3 admitted row |
| Unified carrier walk; carrier-side `resolveErrorChannel` call site removed; shared predicate body extracted to helper | `single-record-carrier-shape.roles-exhaustively-classified` audit pair; compile-time (removed call site, retained method) |
| `MutationBulkDmlRecordField` sealed leaf with compact-ctor rejections | compact-ctor enforces at construction; L3 admitted row sanity-checks construction |
| Classifier routes bulk + list-data → new leaf; single + list-data → Invariant #16 | `DML_INSERT_LIST_PLAIN_PAYLOAD_LIST_DATA_ADMITTED` + `DML_INSERT_SINGLE_LIST_DATA_REJECTED` |
| Same-`@table` invariant on the new leaf | `mutation-dml-record-field.data-table-equals-input-table` audit pair |
| `buildMutationBulkDmlRecordFetcher` emits per-row DML, preserves input order | `bulkInsertWithThreeRowsInNonPkOrderPreservesInputOrderInResponse` + `bulkUpdateWith...` (the runtime audit; Javadoc + `@see` cross-refs are the find-usages anchor) |
| Error-channel slot populated from `shape.errorChannel()` on both leaves | inherited from R12's coverage; compile-time pairing via `ErrorChannelRole` permit |
| Three classifier-tier truth-table rows | the three rows under L3 above |
| Two execution-tier round-trips + N=1 sanity | the three methods under L4 execution above |
| No-permit-match → `UnclassifiedField` on the mutation field | `DML_INSERT_LIST_PAYLOAD_NO_CARRIER_FIELD_ROLE_REJECTED` |
| `CarrierFieldRoleCoverageTest` ships | `CarrierFieldRoleCoverageTest#everyEmitterConsumingSingleRecordCarrierShapeDispatchesOverEveryPermit` |
| One new load-bearing-check pairing (`single-record-carrier-shape.roles-exhaustively-classified`) | `LoadBearingGuaranteeAuditTest#productionAnnotationsAreConsistent` picks up automatically |
| Full build passes | `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` |

## Acceptance criteria

- A new sealed interface `CarrierFieldRole` exists in the `model` package with permits `DataChannel(String fieldName, DataElement element)` and `ErrorChannelRole(String fieldName, ErrorChannel binding)`. R12's `ErrorChannel` record is unchanged.
- `SingleRecordCarrierShape` is refactored to `(String carrierTypeName, List<CarrierFieldRole> roles)` with a compact-constructor enforcing exactly one `DataChannel`, at most one `ErrorChannelRole`, and distinct field names across roles. Helper accessors `data()` and `errorChannel()` are provided for emitters.
- `BuildContext.tryResolveSingleRecordCarrier` is the single carrier walk: it iterates the carrier type's fields, classifies each into a `CarrierFieldRole` permit (or returns `SingleRecordCarrierResolution.Rejected` on the first unclassifiable field), and produces the lifted shape. The carrier-side call to `FieldBuilder.resolveErrorChannel(returnType)` at `FieldBuilder.java:2670` is removed; that site reads `shape.errorChannel().map(CarrierFieldRole.ErrorChannelRole::binding)` from the unified resolution instead. `FieldBuilder.resolveErrorChannel` itself **stays** as a method, called from the four non-carrier sites (`FieldBuilder.java:2205`, `2236`, `2276`, `2356`) whose return types are not carriers; the errors-field-detection-and-payload-class-reflection inner body is extracted into a private helper that both the new `ErrorChannelRole` classifier rule and `resolveErrorChannel`'s direct-return callers share.
- `MutationField` has a new sealed leaf `MutationBulkDmlRecordField` with components `(parentTypeName, name, location, returnType: ResultReturnType, tableInputArg: TableInputArg, kind: DmlKind, errorChannel: Optional<ErrorChannel>)`. Compact-constructor rejects `kind == DELETE`, `kind == UPSERT` (deferred to R145), and `tableInputArg.list() == false`.
- The carrier-resolution classifier in `FieldBuilder` routes `(tia.list() == true, dataField.wrapper().isList() == true, kind ∈ {INSERT, UPDATE})` to `MutationBulkDmlRecordField`. The single-input + list-data-field cell (`tia.list() == false, dataField.wrapper().isList() == true`) is rejected via Invariant #16. UPSERT is refused upstream by R144 and lifts at R145.
- The same-`@table` invariant on the data field's element type is enforced for the new leaf (via extension of `mutation-dml-record-field.data-table-equals-input-table` or a sibling key with the same predicate body).
- `TypeFetcherGenerator` has a `buildMutationBulkDmlRecordFetcher` (parameterised on `DmlKind`) that emits per-row DML inside `dsl.transactionResult(...)`, collects PKs in input order, runs one follow-up response-SELECT, and maps results back via a PK-keyed map iterated in input order.
- The error-channel slot is populated from the unified resolution (`shape.errorChannel()`) on both `MutationDmlRecordField` and the new `MutationBulkDmlRecordField`; the catch arm follows R12's existing shape unchanged.
- R141 adds three classifier-tier truth-table rows in `GraphitronSchemaBuilderTest`: `DML_INSERT_LIST_PLAIN_PAYLOAD_LIST_DATA_ADMITTED` (admit), `DML_INSERT_SINGLE_LIST_DATA_REJECTED` (Invariant #16), `DML_INSERT_LIST_PAYLOAD_NO_CARRIER_FIELD_ROLE_REJECTED` (no-permit-match).
- R141 adds three new tests in `DmlBulkMutationsExecutionTest`: `bulkInsertWithThreeRowsInNonPkOrderPreservesInputOrderInResponse` (load-bearing order-preservation assertion against deliberately-non-PK-ordered inputs), `bulkInsertWithSingleRowExercisesBulkLeafPath` (N=1 sanity for the bulk emit path), and `bulkUpdateWithThreeRowsInNonPkOrderPreservesInputOrderInResponse` (UPDATE variant of the order-preservation assertion).
- A field on the carrier that resolves to no `CarrierFieldRole` permit causes the **mutation field** to classify as `UnclassifiedField` and fail validation; the carrier itself does not resolve. (No tolerated-`UnclassifiedField` steady state on payload carriers.)
- R141 adds a new sealed-coverage test `CarrierFieldRoleCoverageTest` asserting that every emitter consuming a `SingleRecordCarrierShape` dispatches over every `CarrierFieldRole` permit; adding a permit in a future Backlog item fails the build if any consumer is left unhandled.
- R141 adds one new load-bearing classifier check key: `single-record-carrier-shape.roles-exhaustively-classified` (producer `@LoadBearingClassifierCheck` on `tryResolveSingleRecordCarrier`; consumer `@DependsOnClassifierCheck` on every emitter reading `shape.data()` or `shape.errorChannel()`). The pairing registers with `LoadBearingGuaranteeAuditTest`. The bulk-DML emitter's order-preservation invariant is **not** anchored as a load-bearing classifier check (it has no compile-time signal under any classifier relaxation, so overloading `@LoadBearingClassifierCheck` for it would dilute the audit's classifier→emitter shape-contract signal); it lives as a Javadoc statement on `MutationBulkDmlRecordField` plus bidirectional `@see` cross-references to `TypeFetcherGenerator.buildMutationBulkDmlRecordFetcher`, with `DmlBulkMutationsExecutionTest`'s deliberately-non-PK-ordered round-trip as the runtime audit.
- R141 does **not** land consumer-side `@DependsOnClassifierCheck` annotations against R12's planned `error-channel.*` classifier-check keys; R12 lands both producer-side and consumer-side annotations in one commit when it ships. R141's contractual record of what its emitters trust about the `ErrorChannelRole` permit lives in Javadoc on the permit record and on `tryResolveSingleRecordCarrier`'s role-permit classifier rule (the same predicate body the shared helper enforces). R12's landing checklist references this Javadoc as the consumer-side trust statement it needs to instrument with annotations.
- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes end-to-end with the new sealed type, the lifted shape, the new sealed leaf, the new truth-table rows, the new sealed-coverage test, the new load-bearing-check pairing, and the new execution test.

## Out of scope

- Per-row error correlation (the error channel stays flat-list under R12's contract; per-row errors are a future Backlog item).
- Carrier sibling fields beyond the data and error channels (the carrier classifier rejects fields that resolve to no `CarrierFieldRole` permit; each new sibling shape is a separate Backlog item adding one permit + one classifier rule, e.g. `payload-carrier-affected-row-count`, `payload-carrier-client-mutation-id`).
- Single-statement order-preserving emit (the N+1 statement count is acceptable for mutation N; revisit only if profiling shows real cost; the leaf's Javadoc order-preservation invariant plus `DmlBulkMutationsExecutionTest`'s non-PK-ordered round-trip are the contract any future emit refinement must satisfy).
- Cross-table read-back (data field's `@table` must match input's `@table`; the cross-table case is a separate plan if it surfaces).
- DELETE-with-list-data-field-payload (no read-back to surface; rejected at the compact-constructor).
- **UPSERT** admission (deferred to R145, `mutation-cardinality-safety-upsert`). R144 (`mutation-cardinality-safety-default`) refuses every `@mutation(typeName: UPSERT)` field at the upstream `MutationInputResolver` under the cardinality-safety regime; carrier-resolution never reaches this leaf for UPSERT. R141's compact-constructor independently rejects `DmlKind.UPSERT` to make the deferral type-system-enforced rather than a comment. R145 simultaneously lifts the upstream refusal and the compact-constructor rejection here, and adds the UPSERT branch to the parameterised emitter; R141's INSERT and UPDATE arms are unaffected by either landing.
- Bulk `@service` carrier with list-shaped data field (the R75 Phase 2 `@service` carrier path is the symmetric `@service` counterpart; if a real schema surfaces a need, file `bulk-input-single-carrier-list-data-field-service` as the sibling item — R141's design does not generalise to `@service` because the emit strategy is different, but the carrier-resolution shape is symmetric and could share an interface).
- Refactor of both `MutationDmlRecordField` and `MutationBulkDmlRecordField` to sealed-on-kind permits (mirroring `DmlTableField`'s `MutationInsertTableField` / `MutationUpdateTableField` / `MutationUpsertTableField` shape); file `dml-record-carrier-sealed-on-kind` as the follow-up. R141 mirrors `MutationDmlRecordField`'s current `DmlKind kind` enum field for intra-pair consistency.
- Refactor of both record-carrier leaves under a `DmlRecordCarrierField` sealed sub-taxonomy under `MutationField`; file `dml-record-carrier-sub-taxonomy` as the follow-up. R141 leaves both leaves as flat siblings of `MutationField`. The two refactor items are independent and can land in either order.

## Roadmap entries (siblings / dependencies)

- **Follow-up from** R138 (shipped, see `changelog.md`): R138 lifted Invariant #15 above the per-arm switch in `MutationInputResolver.validateReturnType` and shipped its sealed-coverage. R141's classifier ordering routes the new admitted arm *before* R138's lifted check so bulk-input + list-data-field cases land at `MutationBulkDmlRecordField` rather than the Invariant #15 rejection.
- **Coordinates with** [`error-handling-parity.md`](error-handling-parity.md) (R12, `Ready`): R141 reuses R12's `ErrorChannel` record unchanged but relocates the carrier-side `resolveErrorChannel` call into the unified carrier walk introduced by the carrier-shape lift. The standalone `resolveErrorChannel` method stays for the four non-carrier callers (direct-`@table` DML, service-arm fields) and shares its predicate body with the new role-permit classifier rule via an extracted helper. The `Optional<ErrorChannel>` slot on field records keeps its current external API. Either ordering converges on the same end state; R12's plan body adjusts to consume the `ErrorChannelRole` permit when it ships, or R141 routes the carrier-side caller through the shared helper if R12 ships first. See §"Implementation order" for the convergence detail.
- **Mirrors** R138's `DML_INSERT_LIST_PLAIN_PAYLOAD_REJECTED` with an admitted counterpart on the complementary cell of the cardinality matrix.
- **Defers** sibling-field classifiers (`affectedRowCount`, `clientMutationId`, per-row error correlation) to future Backlog items; each new sibling shape is one new `CarrierFieldRole` permit + one classifier rule, no re-admission of the carrier required and no edits to existing emitters that pattern-match the roles they consume.
- **Defers** `dml-record-carrier-sealed-on-kind` (sealed-on-kind refactor for both record-carrier leaves, mirroring `DmlTableField`).
- **Defers** `dml-record-carrier-sub-taxonomy` (sealed `DmlRecordCarrierField` sub-taxonomy under `MutationField`).
- **Defers** `bulk-input-single-carrier-list-data-field-service` (the symmetric `@service` carrier path, if needed; the carrier-resolution shape is symmetric but the emit strategy is `@service`-specific).
- **Sibling of** [`mutation-cardinality-safety-default.md`](mutation-cardinality-safety-default.md) (R144, Spec). R144 inverts the cardinality-safety polarity for DELETE / UPDATE input fields (default filter, `@multiRow` opt-out, `@value` for UPDATE assignment partition) and refuses UPSERT under the new regime pending R145. R141 and R144 are peers in the `mutations-errors` theme; **R141 is the lead item and lands first** (see §"Implementation order" → "R144 coordination" for the full reasoning: R141's classifier and emitter paths need to be in trunk for R144's polarity flip to retarget). R141's `In Progress` implementer is responsible for absorbing whatever divergence R144's Spec body has introduced between R141 reaching `Ready` and R141 reaching `Done` — concretely the `tableInputArg.fieldBindings()` surface, the admitted-kind matrix, and any new rejection predicates that overlap R141's Invariant #16 family. The two items share a single UPSERT carve-out deferred to R145; the test-fixture migration sweep for legacy `@lookupKey` UPDATE coverage falls to R144 in the planned ordering.
- **Shares deferral with** [`mutation-cardinality-safety-upsert.md`](mutation-cardinality-safety-upsert.md) (R145, Backlog). R145 designs the UPSERT cardinality story and, when it ships, simultaneously lifts R144's upstream UPSERT refusal and R141's compact-constructor UPSERT rejection. R141's parameterised emitter dispatch gains the UPSERT arm at that point. R145's plan body should reference R141 as one of the leaves whose admission widens at landing.
