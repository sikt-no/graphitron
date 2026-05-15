---
id: R156
title: "Payload-returning DELETE: carrier permits for PK-echo and PK-only RETURNING record"
status: In Review
bucket: architecture
priority: 6
theme: mutations-errors
depends-on: []
created: 2026-05-13
last-updated: 2026-05-15
---

# Payload-returning DELETE: carrier permits for PK-echo and PK-only RETURNING record

`@mutation(typeName: DELETE)` today has no payload-returning story. Two natural
shapes both reject at classify time:

```graphql
type SlettRegelverksamlingPayload {
    regelverksamling: [Regelverksamling!]   # rejected: SELECT-after-DELETE can't read the row
}
type SlettRegelverksamlingPayload {
    regelverksamlingId: [ID!] @nodeId       # rejected: ID is not an admitted CarrierFieldRole permit
}
```

The first rejection fires at `FieldBuilder.java:2960` (the
`if (kind == DmlKind.DELETE)` branch inside the carrier path): today's R75 / R141
carrier path runs a PK-only RETURNING inside the tx and then a follow-up response
SELECT outside it (`FetcherEmitter.java:60-66`). For DELETE the follow-up SELECT
can't run because the row is gone, so the carrier path refuses DELETE
unconditionally.
The second rejection fires at the role-permit classifier: today's only admitted
`DataChannel.DataElement` arms are `Table` and `Record`, neither of which fits
an `ID`-element echo.

Once R12 (`error-handling-parity`) lands, every mutation will want a payload
type to carry the `errors:` channel alongside the produced rows. The current
"use bare `ID` / `[ID!]` as the return type" escape hatch (encoder lookup at
`FieldBuilder.java:3002-3013`, emission at `FieldBuilder.java:2729-2733` via
`buildDmlReturnExpression`) cannot compose with R12, so DELETE will be the only
verb that can't participate in payload-carrier shapes. This item closes that
gap.

The design rule the user committed to in conversation: after a DELETE the only
data the response can honestly project is the deleted row's primary key. Any
non-PK column has been removed; any non-nullable SDL field that depends on a
non-PK column cannot resolve. RETURNING is narrowed to PK columns only;
non-PK-resolvable fields on the projected SDL type are either nullable (resolve
to null at runtime) or reject the carrier at classify time.

## Shipped (as of 2026-05-15)

- **Model.** `DataElement.Id`, `PkResolution` (two arms), package-private
  `PerFieldOutcome` (five arms), `ChildField.SingleRecordIdFieldFromReturning`,
  `ChildField.SingleRecordTableFieldFromReturning` ; `ba4697f`.
- **Carrier walk.** Verb-aware
  `BuildContext.tryResolveSingleRecordCarrier(String, DmlKind)` overload with
  the `DataElement.Id` permit-verb rule and the `classifyDeleteTableProjection`
  step wearing `@LoadBearingClassifierCheck(key =
  "mutation-delete-carrier.pk-resolution-projection-clean")`. Verbless overload
  preserved for the four non-DML callers ; `cbe4634`.
- **FieldBuilder rewire.** Unconditional DELETE-with-carrier rejection at
  `FieldBuilder.java:2960-2965` removed; DELETE admission decision now lives in
  the carrier walk. `requireDataTableMatchesInputTable` extended to admit the
  Id arm (table linkage via encoder NodeType). Per-field `ChildField` carrier
  registration via `registerDeleteCarrierDataField` + new
  `FieldRegistry.reclassify` exception-method ; `61ce2c8`.
- **MutationField admission.** `MutationDmlRecordField` /
  `MutationBulkDmlRecordField` compact constructors lift the DELETE
  rejection ; `61ce2c8`.
- **DML emission.** `TypeFetcherGenerator.buildRecordDeleteChain` (single) and
  `buildBulkRecordPerRowDeleteBody` (bulk) emit the DELETE chain with PK-only
  RETURNING projection ; `8d88bb5`.
- **Per-field fetcher emission.** `FetcherEmitter` adds
  `buildSingleRecordIdFromReturningFetcherValue` (PK column read + encoder
  call) and `buildSingleRecordTableFromReturningFetcherValue` (PK-only Record
  synthesis via `Tables.<TABLE>.newRecord()`; see §Runtime caveats below) with
  a matching `@DependsOnClassifierCheck` pinning the producer-consumer
  contract ; `8d88bb5`.
- **Sakila L5 fixtures + L6 execution-tier proof.** `DeletedFilmsIdPayload`,
  `DeletedFilmsTablePayload`, `DeletedFilmInfo` SDL types + `deleteFilmsIdCarrier`
  / `deleteFilmsTableCarrier` mutations; `DmlBulkMutationsExecutionTest`'s two
  new tests run end-to-end against PostgreSQL ; `a869716`, `160f102`.
- **L1 invariants + L3 admission/rejection matrix (partial first pass).** ;
  `fe676bf`.
- **L3 audit `PkResolutionEmitterReachabilityTest`.** Four-test reflective
  scan over `PkResolution` (emitter case per arm + dead-entry detection),
  `PerFieldOutcome` rejection-arm sealedness, record-component symmetry across
  the two sealed roots, and `@LoadBearingClassifierCheck` pin on
  `classifyDeleteTableProjection` ; `b5209a9` (R156 In Review rework
  pass).
- **L3 admission-matrix expansion.** Four new
  `MutationDeletePayloadCarrierCase` rows: UPDATE / UPSERT + `[ID!]` rejection
  via the permit-verb rule, DELETE + `[Foo!]` with `@service`-resolved field
  rejection ; `b5209a9`.
- **L4 pipeline rows on `MutationDmlNodeIdClassificationTest`.** Six new
  tests covering the four `DataElement.Id` admission cells (bulk/single ×
  implicit/explicit @nodeId, using the `nodeidfixture` catalog's composite-PK
  `Bar` and single-PK `Baz` tables) plus the wrong-encoder-table and
  no-@node-backed-input-table rejection paths ; `b5209a9`.
- **User documentation.** `docs/manual/reference/mutations.adoc`
  Payload-returning DELETE sub-section ; `e08c439`.

## Runtime caveats (post-implementation)

- **PK-only Record synthesis.** The `DataElement.Table` data-fetcher
  (`buildSingleRecordTableFromReturningFetcherValue`) does not pass the
  RETURNING `Record` to the per-field `ColumnFetcher` directly; it synthesizes
  a fresh Record via `Tables.<TABLE>.newRecord()` and copies the PK columns
  from the source. Non-PK column slots remain null, which is what the
  per-field `ColumnFetcher` consumes for `PkResolution.NonPkNullable` arms.
  Load-bearing assumption: source and target Records resolve to the same
  generated table class, so `__r.set(Tables.X.COL, __src.get(Tables.X.COL))`
  uses one `Field<T>` instance on both ends and compiles without a cast.
  A future change that synthesizes the Record over a different jOOQ-generated
  class than the source RETURNING reads from would need to revisit the copy
  shape. Documented inline in the emitter; named here so the contract is
  visible alongside the rest of the design.

## Scope

In scope:

- New `DataElement.Id` arm: the carrier's data field's element type is `ID`,
  with the `@nodeId` encoder resolved against the input `@table`'s `@node`
  registration. Recognised both implicitly (any `ID` / `[ID!]` carrier field
  whose element resolves to the input `@table`'s `@node` encoder) and explicitly
  (a carrier field carrying the `@nodeId` directive that pins the encoder to the
  same table).
- Narrowed `DataElement.Table` arm for DELETE: the existing `Table` arm continues
  to carry a SDL `@table`-backed element type, but the response data fetcher
  resolves directly off the PK-only RETURNING `Record` rather than running a
  follow-up SELECT. The carrier walk classifies each field on the element SDL
  type into a builder-internal sealed `PerFieldOutcome` (five arms: `PkRead`,
  `NonPkNullable`, `NonPkNonNullable`, `ServiceField`, `UnsupportedField`) and
  either rejects (any `NonPkNonNullable`, `ServiceField`, or `UnsupportedField`
  arm) or projects the surviving outcomes to a narrow model-facing `PkResolution`
  (two arms: `PkRead`, `NonPkNullable`).
  The `List<PkResolution>` rides on the new per-field
  `ChildField.SingleRecordTableFieldFromReturning` carrier (no re-derivation at
  emit time).
- Both bulk and single via the existing wrapper cardinality dispatch on the
  carrier's data field: `MutationBulkDmlRecordField` for list-shaped data
  fields (R141), `MutationDmlRecordField` for singletons (R75 Phase 1).
- Two new `ChildField` sealed siblings ; `SingleRecordIdFieldFromReturning`
  (owns the `CallSiteCompaction.NodeIdEncodeKeys` for `DataElement.Id`) and
  `SingleRecordTableFieldFromReturning` (owns the `List<PkResolution>` for
  `DataElement.Table`) ; carry the DELETE-specific emission story per-field;
  no DELETE-specific plumbing rides on `SingleRecordCarrierShape` or on
  `DataElement.Table` / `DataElement.Record`.
- Removes the unconditional DELETE-with-carrier rejection at
  `FieldBuilder.java:2960-2965` entirely. The DELETE-admissibility decision
  moves into a new `tryResolveSingleRecordCarrier(String, DmlKind)` overload on
  `BuildContext`; the existing zero-`DmlKind` overload at
  `BuildContext.java:505` is preserved for the four non-DML callers
  (`GraphitronSchemaBuilder.java:227`, `TypeBuilder.promoteSingleRecordCarriers`
  at `TypeBuilder.java:198`, `MutationInputResolver.java:178/210/233`) so this
  change is additive at the call sites that don't classify a verb.
  `FieldBuilder` consumes the typed result without re-branching on kind.

Explicitly out of scope (follow-up R-items filed alongside this Spec or already
on the roadmap):

- Affected-row count payload field (`count: Int!` etc.). Filed as a separate
  Backlog stub; the role permit is structurally different (no @table linkage,
  no per-row data), so it doesn't fit `DataChannel.DataElement`.
- Error-channel composition (R12 `error-handling-parity`). The carrier walk's
  `ErrorChannelRole` permit already composes with the existing `DataChannel`;
  the `DataElement.Id` and narrowed `DataElement.Table` arms inherit that
  composition without additional work. R12 is the upstream producer; this item
  is independent.
- `RETURNING *` or projection-aware RETURNING for arbitrary non-PK columns.
  Rejected in §Alternatives.
- Dialect-capability gating on DELETE-RETURNING. The existing
  `dml-dialect-requirement-on-model` (R-something on the dialect roadmap)
  covers the cross-cutting RETURNING capability check; this item assumes
  PostgreSQL (the dialect the rewrite ships against today).
- Soft warnings on silent-null non-PK nullable fields. The classify-time rule
  is binary: non-nullable non-PK-resolvable → reject; nullable non-PK-resolvable
  → admit and document. No warning channel is introduced.

## Design

### `DataElement.Id` arm

`DataElement` (today a sealed interface with `Table` and `Record` arms,
`DataElement.java:26-47`) gains a third arm, axis-symmetric with its siblings:

```java
/**
 * Element type is the GraphQL {@code ID} scalar. Used by payload-returning
 * DELETE carriers (R156) where the response data is the encoded primary key of
 * each deleted row.
 *
 * <p>The arm describes the element SDL shape only. The projection plan ; how
 * to turn the source {@code Record}'s PK columns into an encoded {@code ID}
 * value at runtime ; lives in a sibling {@link CallSiteCompaction.NodeIdEncodeKeys}
 * slot on the per-field carrier (the new {@link ChildField.SingleRecordIdFieldFromReturning}
 * sibling, §Runtime), the same slot every other NodeId-encoded field uses (see
 * {@code CallSiteCompaction.java}). Mixing the encoder reference into the
 * {@code DataElement.Id} record itself would conflate two axes ; "what's the
 * element shape" and "how to project it" ; and create a parallel home for an
 * encoder reference {@code CallSiteCompaction} already owns.
 *
 * <p>The compact constructor pins the admitted wrapper set to the two SDL
 * shapes the carrier walk admits ({@code ID} / {@code ID!} singleton, or
 * {@code [ID!]} / {@code [ID!]!} list-of-non-null). The classifier produces
 * the SDL-level rejection on bad authoring; the compact constructor produces
 * the same rejection on a programming-error construction (e.g. a future
 * caller passing a list-of-nullable wrapper).
 */
record Id(String name, FieldWrapper wrapper) implements DataElement {
    public Id {
        if (!(wrapper.isSingleton() || (wrapper.isList() && wrapper.elementNonNull()))) {
            throw new IllegalArgumentException(
                "DataElement.Id wrapper must be singleton ID/ID! or list-of-non-null"
                + " [ID!]/[ID!]!; got " + wrapper);
        }
    }
}
```

The encoder is resolved once at carrier-classify time (same lookup the bare-`ID`
DELETE return path uses at `FieldBuilder.java:3002-3013`) and stored on the
per-field `ChildField.SingleRecordIdFieldFromReturning` carrier that the
classifier produces for the data field. The data fetcher emission for
`DataElement.Id` reads the compaction off that per-field carrier, not off the
element record and not off `SingleRecordCarrierShape`. Carriers whose input
`@table` is not `@node`-backed reject with the same diagnostic as the
bare-`ID` path.

### Carrier classifier admission for `DataElement.Id`

The single-record carrier walk (in `BuildContext.tryResolveSingleRecordCarrier`,
following the role-permit classifier rule, now `DmlKind`-aware per §Push the
gate into the carrier walk) admits a `DataChannel` whose `DataElement` is `Id`
when:

1. The carrier field's element type is the GraphQL `ID` scalar.
2. The field's wrapper is either single (`ID` / `ID!`) or list
   (`[ID!]` / `[ID!]!`); list-of-nullable (`[ID]`) rejects.
3. Either (implicit) the carrier-owning mutation's input `@table` is
   `@node`-backed and the recognised encoder pins to that table; or (explicit)
   the carrier field carries `@nodeId` and the directive's resolved encoder
   pins to the same input `@table`. In both cases the resolved encoder
   attaches to the per-field `ChildField.SingleRecordIdFieldFromReturning`
   carrier via `CallSiteCompaction.NodeIdEncodeKeys`.
4. The mutation kind is `DmlKind.DELETE`. `DataElement.Id` is the PK-echo
   permit and commits the carrier to PK as the *entire* post-image; the only
   verb whose post-image is the PK is DELETE. INSERT / UPDATE / UPSERT
   carriers reject `DataElement.Id` at classify time with the message
   "`DataElement.Id` is the PK-echo permit (post-image == primary key) and is
   admitted only on `@mutation(typeName: DELETE)` carriers. On INSERT /
   UPDATE / UPSERT the post-image is richer; use `DataElement.Table` or
   `DataElement.Record` instead." The justification is permit-verb structural,
   not emitter-mechanism: the rule lives in the type system because PK-echo
   commits to a narrower post-image than the other verbs make available.

The cardinality matrix (bulk × {Id, Table}, single × {Id, Table}) drops out
of the existing carrier-data-field wrapper dispatch at
`FieldBuilder.java:2978-2999` (the `boolean dataFieldIsList = ...` lookup
followed by the `if (tia.list() && dataFieldIsList)` branch): a list-shaped
element data field routes to `MutationBulkDmlRecordField`, a singleton to
`MutationDmlRecordField`. No new dispatch axis.

### Narrowed `DataElement.Table` for DELETE: typed per-field projection

For DELETE, the existing `DataElement.Table` arm continues to be admitted, but
only when the carrier walk's per-field classification over the element SDL type
produces an admissible projection. Two sealed hierarchies cooperate here: a
builder-internal `PerFieldOutcome` (four arms; ephemeral; consumed only by the
projection step) and a narrow model-facing `PkResolution` (two arms; carried by
`ChildField.SingleRecordTableFieldFromReturning` to the emitter). The split is
the "Builder-internal sealed hierarchies for multi-target classification"
principle: the five-arm classifier outcome lives inside the classifier; the
two-arm projected result is what consumers downstream of the classifier see.

```java
/**
 * Builder-internal per-field outcome produced by the DELETE carrier walk over a
 * {@link DataElement.Table} element type. Each field on the element SDL type
 * classifies into exactly one arm. The {@code classifyDeleteTableProjection}
 * step on {@link BuildContext} consumes the {@code List<PerFieldOutcome>}, and
 * either rejects the whole carrier (any {@link NonPkNonNullable},
 * {@link ServiceField}, or {@link UnsupportedField} arm present, with a
 * diagnostic naming the offending fields) or projects the surviving outcomes to
 * the narrow model-facing {@link PkResolution}.
 *
 * <p>This type does NOT escape the classifier. The downstream
 * {@link ChildField.SingleRecordTableFieldFromReturning} carrier holds the
 * narrow {@link PkResolution}, not this type ; the emitter cannot observe the
 * rejection arms because they cannot reach it through the type system.
 */
sealed interface PerFieldOutcome {
    String fieldName();

    record PkRead(String fieldName, List<ColumnRef> columns, Optional<HelperRef.Encode> encode) implements PerFieldOutcome {}
    record NonPkNullable(String fieldName) implements PerFieldOutcome {}
    record NonPkNonNullable(String fieldName) implements PerFieldOutcome {}
    record ServiceField(String fieldName) implements PerFieldOutcome {}
    /** Catch-all over GraphitronField leaves that can't resolve from a PK-only synthesized Record
     *  (e.g. ColumnReferenceField over a joined column, ChildField.TableField child collection,
     *  ChildField.ComputedField). Hard-rejects the carrier with a diagnostic naming the leaf kind. */
    record UnsupportedField(String fieldName, String leafKind) implements PerFieldOutcome {}
}

/**
 * Model-facing per-field projection carried by
 * {@link ChildField.SingleRecordTableFieldFromReturning} to the emitter. Two
 * arms ; one for each emission case the emitter has to handle. By construction,
 * the only producer of {@code List<PkResolution>} is
 * {@code BuildContext.classifyDeleteTableProjection}, which rejects the carrier
 * before constructing any {@link PkResolution} when any element-type field
 * classifies into a {@link PerFieldOutcome.NonPkNonNullable},
 * {@link PerFieldOutcome.ServiceField}, or {@link PerFieldOutcome.UnsupportedField}
 * arm. The emitter's sealed switch on {@link PkResolution} is therefore
 * exhaustive over its two arms with no "unreachable arm" defensive default ;
 * the type system carries the certainty that the rejection arms cannot appear here.
 *
 * <p>The projection list rides on the per-field {@link ChildField} permit ; not on
 * {@link SingleRecordCarrierShape} and not on {@link DataElement.Table} ; because
 * it is data only the new DELETE-specific child carrier consumes. Placing it on the
 * shape or on the element record would force every non-DELETE consumer of those
 * sealed types to ignore a slot it has no story for, exactly the "narrow component
 * types over broad interfaces" smell.
 */
public sealed interface PkResolution {
    String fieldName();

    /**
     * Field resolves to a PK column set on the input @table; the resolver reads
     * the column(s) off the source {@code Record}. Single-column PK: {@code columns}
     * has size 1, {@code encode} is empty. Composite PK or {@code @nodeId}-over-PK
     * (including the SDL {@code id} alias on a {@code @node}-backed element type
     * whose backing PK is the input @table's PK): {@code columns} carries the PK
     * column set in declaration order, {@code encode} carries the NodeId encoder.
     *
     * <p>The classifier produces this arm for plain {@code ColumnField} over PK,
     * for {@code CompositeColumnField} via {@code @nodeId} over PK, and for the
     * SDL {@code id}-alias case. The three SDL shapes share the same emitter
     * dispatch (read column(s), optionally encode), so they share one arm. A
     * future emitter that genuinely needs to split (e.g. a faster path for the
     * single-column no-encode case) lifts at that point.
     */
    record PkRead(String fieldName, List<ColumnRef> columns, Optional<HelperRef.Encode> encode) implements PkResolution {}

    /** Field maps to a non-PK column. Nullable in SDL; emitter emits a constant-null fetcher. */
    record NonPkNullable(String fieldName) implements PkResolution {}
}
```

The `PerFieldOutcome` arms classify directly from the element SDL type's
existing field-classifier output (`ColumnField`, `CompositeColumnField`,
`NodeIdField` variants, `ServiceField` from the field-builder). FK-traversing
reference fields classify into `NonPkNullable` or `NonPkNonNullable` based on
whether the FK column is part of the input `@table`'s PK and the SDL field's
nullability. The projection from `PerFieldOutcome.PkRead/NonPkNullable` to
`PkResolution.PkRead/NonPkNullable` is a one-to-one mapping (same record
components, different sealed root); the projection step's only judgment is
"reject if any disallowed arm appears, otherwise project". The duplication of
two record names across two sealed roots is the cost of the narrowing, and it
is the right cost: the emitter's exhaustive switch is now compiler-enforced
without a defensive default, and downstream consumers cannot observe the
rejection arms by type accident.

**Carrier-admission rule.** `BuildContext.classifyDeleteTableProjection`
computes the per-field `List<PerFieldOutcome>` at classify time and rejects the
carrier if any arm is `NonPkNonNullable`, `ServiceField`, or `UnsupportedField`.
The rejection
message names the offending field(s) and points at `DataElement.Id` as the
recommended pattern:

> "`@mutation(typeName: DELETE)` carrier `<CarrierType>`: data field
> `<dataFieldName>` element type `<ElementType>` has field(s):
> - `<fields>` resolving to non-primary-key columns and declared non-nullable
>   (after DELETE only the table's primary key can carry data; either make
>   them nullable or move them off the type), and/or
> - `<fields>` are `@service`-resolved (projecting a deleted SDL type through
>   a `@service` field is not supported on DELETE carriers).
>
> Prefer a `DataElement.Id` shape: a carrier field of type `ID` or `[ID!]`
> (with `@nodeId` either implicit by the input `@table`'s `@node` registration
> or explicit on the field), which echoes the deleted primary keys without
> requiring a `@table`-element projection."

**Why strict on `@service`.** Admitting a `@service` field on the element type
would create an unverifiable invariant: the classifier admits on "the service
can produce a value from PK alone," but nothing checks the service's `Sourced`
params resolve to PK columns. At runtime the service receives a PK-only
`Record` and any non-PK source param silently produces `null`, which is exactly
the silent-null hole the rest of this design eliminates. The strict path
(reject any `@service` field on the element type, regardless of nullability)
pushes authors to the `DataElement.Id` pattern, which is the canonical shape
the rest of the design is built around. The user committed to this position
explicitly: "we don't really want to support the pattern of having the result
of a delete refer to the deleted type, so let's be strict."

`PkResolution.NonPkNullable` arms admit; the data-fetcher emitter consumes the
typed projection and emits `null` for each at runtime. No re-derivation of
"which fields go to null" at the emit site.

### Push the gate into the carrier walk

Today's `FieldBuilder.java:2960-2965` re-asks "is this element kind admissible
for DELETE?" with a `kind == DmlKind.DELETE` branch, after the carrier walk has
already classified the element. Two consumers reading the same admissibility
predicate. The principles-architect rule "if two consumers evaluate the same
predicate over a model field, the branch belongs in the model" applies here.

The fix: a new `BuildContext.tryResolveSingleRecordCarrier(String, DmlKind)`
overload alongside the existing `tryResolveSingleRecordCarrier(String)` overload
at `BuildContext.java:505`. The shape is two overloads (not a single
`DmlKind`-threaded signature with `null`/sentinel for the verbless callers)
because of the structural split between consumers. Of the five existing call
sites, four are shape-only ; SDL-shape resolution with no DML verb in scope:
`GraphitronSchemaBuilder.java:227` (post-classifier audit),
`TypeBuilder.promoteSingleRecordCarriers` at `TypeBuilder.java:198` (registry
promotion to `PojoResultType.NoBacking`), and `MutationInputResolver.java:178/210/233`
(input-side single-record-carrier recognition). The fifth, `FieldBuilder.java:2958`,
classifies a mutation field and has the verb in scope. The verb-aware overload is
a strict extension of the carrier resolution, not a parameter every shape-only
caller has to ignore ; the same "narrow component types over broad interfaces"
reasoning the `DataElement.Table` arm uses to keep the projection off
`SingleRecordCarrierShape`. The new overload shares the existing walk's
positive-criterion checks and adds the DELETE-admissibility arm (element kind
in `{Id, Table}`; for `Table`, the `classifyDeleteTableProjection` step's
projection from `List<PerFieldOutcome>` to `List<PkResolution>` succeeds rather
than rejecting) before returning. The `SingleRecordCarrierResolution` sealed type's existing
`Rejected` arm carries the diagnostic for inadmissible DELETE shapes; the
existing `NotCandidate` arm continues to mean "not a single-record-carrier
shape at all" and is unchanged.

The validator-mirrors-classifier story stays clean because the verb-aware
overload is the *one* call site that classifies DELETE admissibility, and the
validator (when surfacing the diagnostic for SDL authors) reads through that
same overload ; not through a parallel kind-checking path.

`FieldBuilder` then consumes the typed result without re-asking:

```java
case MutationInputResolver.Resolved.Ok ok -> tia = ok.tia();
...
switch (ctx.tryResolveSingleRecordCarrier(rawReturn, kind)) {
    case SingleRecordCarrierResolution.Ok carrierOk -> {
        // Carrier resolved; the resolution already encodes DELETE-admissibility.
        // No re-branching on kind here.
        String mismatch = requireDataTableMatchesInputTable(tia.inputTable(), carrierOk.shape(), kind, name);
        if (mismatch != null) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(mismatch));
        }
        // ... (existing per-cardinality MutationDmlRecordField / MutationBulkDmlRecordField dispatch)
    }
    case SingleRecordCarrierResolution.Rejected rejected ->
        return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(rejected.reason()));
    case SingleRecordCarrierResolution.NotCandidate nc -> {
        // existing bare-ID / bare-[ID!] DELETE return path stays
    }
}
```

The unconditional DELETE rejection at `FieldBuilder.java:2960-2965` disappears
entirely. Any future `DataElement` arm that adds DELETE-admissibility lands in
the carrier walk and `FieldBuilder` doesn't change.

The follow-on `requireDataTableMatchesInputTable` call stays: it pins the
table linkage for `DataElement.Table`. For `DataElement.Id`, the encoder
resolution against the input `@table`'s `@node` registration plays the same
role (the encoder's table IS the input table, structurally).

### Runtime: RETURNING shape and data-fetcher source

The DML emitter narrows RETURNING to the input `@table`'s primary-key columns
on DELETE-with-carrier. This is the same column set the existing R75 / R141
carrier path uses (`FetcherEmitter.java:60-66` describes it as "PK-only
RETURNING inside a tight transaction"). No change to the RETURNING projection
machinery is required: the carrier walk's data-channel element kind drives the
post-RETURNING fetcher emission, not the RETURNING clause itself.

The carrier walk produces two new sibling `ChildField` permits for the
DELETE-with-carrier data field, one per `DataElement` arm. Each owns the data
its emitter consumes; no DELETE-specific plumbing rides on
`SingleRecordCarrierShape` or on `DataElement.Table`/`DataElement.Record`.

- **`ChildField.SingleRecordIdFieldFromReturning(name, encode, wrapper)`** ;
  produced when the resolved `DataElement` is `Id`. Carries the
  `CallSiteCompaction.NodeIdEncodeKeys` (the `encode` slot) and the SDL
  wrapper. Emitter: read PK column(s) off the source `Record`, run them
  through `encode`. Single-shaped wrapper emits
  `($T env) -> encode(record.get(pkCol))`; composite PK reuses the existing
  composite encoder call shape at `FetcherEmitter.java:114-130`. Bulk-shaped
  wrappers iterate `Result<Record>` and map each row through the same encoder.
  No follow-up SELECT.

- **`ChildField.SingleRecordTableFieldFromReturning(name, projection, wrapper)`** ;
  produced when the resolved `DataElement` is `Table`. Carries the per-field
  `List<PkResolution> projection` ; the narrow two-arm sealed result of the
  §Narrowed `DataElement.Table` walk's projection step. The emitter switches
  exhaustively on the two arms with no defensive default; the rejection arms
  (`NonPkNonNullable`, `ServiceField`, `UnsupportedField`) cannot appear here by type:
  - `PkRead` ; read column(s) off the source `Record`; if `encode` is present,
    run the values through it (the SDL `id`-alias and composite-PK cases);
    otherwise return the column value directly.
  - `NonPkNullable` ; emit `($T env) -> null`. The constant-null fetcher is
    not a parallel boolean; it is the direct emitter case for this arm of the
    sealed switch.

  The carrier walk's projection step (`BuildContext.classifyDeleteTableProjection`)
  is the single producer of `List<PkResolution>`; the type system carries the
  guarantee that disallowed arms cannot reach the emitter. The behavioural
  contract ; "if any element-type field classifies into
  `PerFieldOutcome.NonPkNonNullable`, `ServiceField`, or `UnsupportedField`,
  the projection step MUST reject the carrier rather than silently dropping the
  field" ; is pinned by a `@LoadBearingClassifierCheck(key =
  "mutation-delete-carrier.pk-resolution-projection-clean", description =
  "BuildContext.classifyDeleteTableProjection rejects DELETE @table-element
  projections on any PerFieldOutcome.NonPkNonNullable, ServiceField, or
  UnsupportedField arm, naming the offending field(s) in the diagnostic, before
  projecting to List<PkResolution>; consumers of the projection rely on the
  rejection arms being a hard reject and not a silent drop.")` on
  `classifyDeleteTableProjection`, with a matching `@DependsOnClassifierCheck`
  on the `SingleRecordTableFieldFromReturning` emitter case in `FetcherEmitter`.
  `LoadBearingGuaranteeAuditTest`'s scan picks the pair up and fails the build
  on orphaned consumers or duplicate producers ; e.g. a future producer that
  hand-builds a `List<PkResolution>` without going through the projection step
  and so bypasses the rejection rule.

  No follow-up SELECT.

The two new permits are sealed siblings of today's `ChildField.SingleRecordTableField`
(at `FetcherEmitter.java:60`), not discriminator variants of it. The trio
encodes three genuinely different invariants:

- `SingleRecordTableField` ; "follow-up SELECT outside the tx," load-bearing for
  INSERT / UPDATE / UPSERT carriers (jOOQ field errors during the read or
  nested traversal cannot undo the DML).
- `SingleRecordTableFieldFromReturning` ; "no follow-up read at all; source IS
  the RETURNING record; per-field projection comes from the typed `PkResolution`
  list," load-bearing for DELETE + `DataElement.Table` (the row is gone, there
  is no SELECT to attempt).
- `SingleRecordIdFieldFromReturning` ; "no follow-up read; element is the
  encoded PK ID via `CallSiteCompaction.NodeIdEncodeKeys`," load-bearing for
  DELETE + `DataElement.Id` (the entire post-image is the PK, the response
  shape is the encoded scalar).

A discriminator on a single type would force every consumer to branch on
it; the sealed siblings push the certainty into the type system. Each per-field
carrier owns the data its emitter consumes (`encode` on the `Id` sibling,
`projection` on the `Table` sibling), mirroring the principle of parallel
ownership the architecture review called for. The existing
`SingleRecordTableField` is unchanged.

The cardinality dispatch in the runtime mirrors the classify-time matrix:

| Kind        | Element       | Runtime source                                 |
| ----------- | ------------- | ---------------------------------------------- |
| Single DELETE | `Id`        | `Record` (single PK row) → encoder → `ID`      |
| Single DELETE | `Table`     | `Record` (single PK row) → per-field fetcher   |
| Bulk DELETE   | `Id`        | `Result<Record>` → encoder per row → `[ID!]`   |
| Bulk DELETE   | `Table`     | `Result<Record>` → per-row per-field fetcher   |

### Alternatives considered

(A) **`RETURNING *` for the `Table` element arm.** Return every column on the
deleted row so the response data fetcher can project arbitrary non-PK
selections. *Rejected.* Wastes bandwidth on narrow selections, loses the
"PK-only RETURNING" invariant that the existing R75 / R141 path relies on, and
the silent-null story for non-PK fields is no better than the current design.
The user's stated rule ("only PK columns can carry data after a DELETE") makes
this the wrong tradeoff: an SDL author who wants the deleted row's full data
should snapshot before delete (a different mechanism), not pretend RETURNING can
read post-delete state.

(B) **CTE: `WITH d AS (DELETE … RETURNING *) SELECT proj FROM d`.** Single SQL
statement, preserves "response SELECT decides columns" property of the existing
R75 path. *Rejected.* The user's rule limits the projected columns to PK
anyway, so the CTE rewrite buys nothing: the SELECT-from-CTE would project the
same PK columns the simpler `RETURNING pk_cols` path already returns. The CTE
shape is the right answer for a future "snapshot-before-delete with arbitrary
projection" feature, but that feature is not this item.

(C) **Snapshot-before-delete (`SELECT … FOR UPDATE` then `DELETE`).** Two SQL
statements per mutation row, in the same tx. *Rejected.* Same reasoning as
(A) / (B): the user's rule narrows the projection to PK only, so a snapshot
buys nothing.

(D) **Explicit-only author surface for `DataElement.Id`.** Require `@nodeId`
on the carrier field; reject the implicit recognition. *Rejected* per the
user's preference in conversation. The implicit shape composes naturally with
today's bare-`ID` DELETE return path (which already does implicit encoder
lookup against the input `@table`'s `@node` registration), and the explicit
form coexists for authors who prefer the directive's grep-ability.

(E) **Reject any non-PK-resolvable field on the element SDL type, nullable or
not.** Stricter than the user's rule; would force authors to define a
dedicated `DeletedFoo` SDL type for every entity. *Rejected.* The user
explicitly carved out nullable non-PK fields: they admit, runtime returns null,
documentation steers authors at `DataElement.Id` as the canonical pattern.

(F) **Surface a classify-time warning for nullable non-PK fields on the
element SDL type.** Author-facing soft signal that the field will always be
null at runtime. *Deferred.* Would require introducing a warning channel
(graphitron-rewrite's classifier is binary today: admit or reject), which is
its own design decision. The user explicitly chose to document the behaviour
rather than introduce a warning channel as part of this item.

(G) **Verify the `@service` exemption with a PK-coverage check on service
sourced params.** Per the principles-architect feedback, admit
`@service` fields on the element SDL type *only* when every `Sourced` param
of the service resolves to a PK column of the input `@table`; pin the check
with a `@LoadBearingClassifierCheck` key the field-builder's service-classifier
depends on. *Rejected.* Two reasons. First, the user's stated preference:
"we don't really want to support the pattern of having the result of a
delete refer to the deleted type, so let's be strict." `DataElement.Id` is
the canonical recommended pattern; allowing a service-bearing element SDL
type opens a second axis the docs would have to teach. Second, the check
itself is non-trivial: the service's `Sourced` params resolve through the
field-builder's service-classifier, which lives downstream of the carrier
walk; threading the PK-set back into the service-classifier crosses a layer
boundary that the strict-reject path avoids entirely. The cost of the check
outweighs the benefit on this carrier path. The principles-architect's
underlying concern (the silent-null hole at runtime) is fully addressed by
the strict-reject decision.

## User documentation (first-client check)

This item adds a new SDL author surface: payload-returning DELETE. The chapter
draft below ships co-located with the existing mutations documentation at
`docs/manual/reference/mutations.adoc` (sub-section `Payload-returning DELETE`)
when the implementation lands; it is reproduced in the plan so the design is
reviewed against the prose its first reader will actually see.

> ### Payload-returning DELETE
>
> Graphitron supports two payload shapes on `@mutation(typeName: DELETE)`. Both
> echo information about the rows the DML actually removed; neither projects
> non-primary-key columns from the deleted rows (the row is gone before the
> response can read it).
>
> #### Echoing the deleted primary key (recommended)
>
> The simplest payload returns the encoded NodeId of each deleted row:
>
> ```graphql
> type SlettRegelverksamlingPayload {
>     deletedIds: [ID!]   # implicit @nodeId; encoder resolves against the input @table's @node
> }
>
> extend type Mutation {
>     slettRegelverksamling(input: [RegelverksamlingDeleteInput!]!): SlettRegelverksamlingPayload
>       @mutation(typeName: DELETE, table: "regelverksamling")
> }
> ```
>
> The carrier field's element type must be `ID` (single DELETE) or `[ID!]`
> (bulk DELETE). The list wrapper must be list-of-non-null: `[ID]` (list-of-
> nullable) is rejected, because every element of a successful DELETE response
> is the encoded PK of an actually-deleted row ; the slot cannot be null. The
> encoder is recognised implicitly when the input `@table` registers a `@node`.
> If the input `@table` is not `@node`-backed, the carrier is rejected with the
> same diagnostic as today's bare-`ID` DELETE path; register the input type as
> `@node` first. To pin the encoder explicitly (recommended when grep-ability
> matters), attach `@nodeId` to the carrier field; the directive's encoder must
> resolve to the same `@table` as the mutation's input. An `@nodeId` whose
> encoder resolves to a different table is rejected (you would be returning IDs
> of a different entity than the one the DML acted on).
>
> The response contains exactly the IDs of rows the DML actually removed.
> Input IDs that don't match any row are absent from the response (subset
> semantics, not error semantics; combine with `errors:` from `@errorChannel`
> if you need per-row failure reporting).
>
> #### Projecting the deleted row's primary key onto an SDL type
>
> The carrier may also return the `@table`-backed SDL type, but only when every
> non-nullable field on the type resolves to a primary-key column:
>
> ```graphql
> type Regelverksamling @table(name: "regelverksamling") @node {
>     id: ID!                              # PK; admits
>     navn: String                         # non-PK, nullable; admits, runtime returns null
>     beskrivelse: String!                 # non-PK, non-nullable; REJECTS the carrier
> }
> ```
>
> The classifier inspects every field on the element type and rejects the
> carrier if any non-nullable field maps to a non-PK column. Nullable non-PK
> fields admit and always resolve to `null` at runtime; this is by design ;
> after a DELETE there is no row left to read those columns from. If your SDL
> type carries non-nullable non-PK fields, prefer the `[ID!]` shape above, or
> define a dedicated `DeletedRegelverksamling` SDL type whose non-nullable
> fields are PK-only.
>
> `@service`-resolved fields are not admitted on the element type, nullable or
> not. The service would receive a PK-only row at runtime and any non-PK source
> parameter would silently produce `null`. Use the `[ID!]` shape and resolve
> service-backed data on the deleted entity through a sibling lookup if needed.
>
> #### Single vs bulk
>
> Both shapes work with single and bulk DELETE; cardinality follows the carrier
> field's wrapper (`ID`/`Foo!` for single, `[ID!]`/`[Foo!]` for bulk).
>
> #### What you can't return
>
> - Arbitrary non-PK columns of the deleted row. The row is gone; `RETURNING`
>   is narrowed to primary-key columns. If you need the full pre-delete state,
>   snapshot it in your application code before issuing the mutation.
> - A bare `@record`-backed type. Use a NodeId echo or a `@table` projection.

If any prose above stops reading cleanly to an author who arrives from search,
the design (not just the docs) needs revisiting before implementation.

## Tests

- **L1 (model).**
  - `DataElement.Id` compact-constructor rejects bad wrappers
    (list-of-nullable, list-of-list, scalar non-`ID`-typed wrapper). Admits
    singleton `ID` / `ID!` and `[ID!]` / `[ID!]!`.
  - `PerFieldOutcome` (builder-internal) sealed root admits its four arms and
    no others (compiler-enforced); `PkResolution` (model-facing) sealed root
    admits its two arms and no others (compiler-enforced). The split is the
    invariant that makes the emitter switch exhaustive without a defensive
    default; a future change that adds a rejection arm goes on
    `PerFieldOutcome` only, not on `PkResolution`.
  - `ChildField.SingleRecordIdFieldFromReturning` and
    `SingleRecordTableFieldFromReturning` compact-constructor invariants:
    `encode` non-null on the `Id` sibling; `projection` non-null on the `Table`
    sibling. The `projection` slot's compile-time type is `List<PkResolution>`,
    so the rejection arms cannot reach the carrier by type ; no runtime
    compact-constructor check is needed beyond non-null.
- **L3 (validator).** New parameterised case in
  `GraphitronSchemaBuilderTest.MutationDeletePayloadCarrierCase` covering the
  admission and rejection matrix:
  - bulk DELETE + `[ID!]` carrier field, implicit recognition: admits.
  - bulk DELETE + `[ID!] @nodeId` carrier field, explicit: admits.
  - single DELETE + `ID` carrier field, implicit: admits.
  - single DELETE + `ID @nodeId` carrier field, explicit: admits.
  - bulk DELETE + `[Foo!]` carrier field, element type has only PK-resolvable
    non-nullable fields and nullable non-PK fields: admits; `PkResolution`
    projection rides on the produced
    `ChildField.SingleRecordTableFieldFromReturning` and the assertion
    inspects it.
  - bulk DELETE + `[Foo!]` carrier field, element type has a non-null non-PK
    column field: rejects with the documented diagnostic naming the field.
  - DELETE + `[Foo!]` carrier field, element type has a non-null FK reference
    whose FK column is non-PK: rejects.
  - DELETE + `[Foo!]` carrier field, element type has any `@service`-resolved
    field (nullable or non-nullable): rejects with the documented diagnostic
    pointing at `DataElement.Id`.
  - INSERT / UPDATE / UPSERT + `[ID!]` carrier field: rejects with the
    "PK-echo only on DELETE" message.
  - DELETE + `[ID]` (list of nullable) carrier field: rejects (wrapper shape).
  - DELETE + `[ID!]` carrier field, input `@table` not `@node`-backed:
    rejects with the same diagnostic as today's bare-`ID` DELETE path.
  - DELETE + `[ID!] @nodeId` carrier field, `@nodeId` encoder pins to a
    different table than the input `@table`: rejects.
- **L3 (audit), generator-coverage tier.** A new `PkResolutionEmitterReachabilityTest`
  alongside the existing
  `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`
  (same precedent ; sealed-root enumeration scanned against emitter dispatch).
  The test reflects over the model-facing `PkResolution` sealed root and fails
  if any arm lacks an emitter case in the `SingleRecordTableFieldFromReturning`
  dispatch. With the two-arm narrowing in place, the scan's primary value is
  forward-protection: a future change that adds a third model-facing arm
  surfaces as a test failure, not a silent generator hole. Paired with a
  fixture that constructs a `PerFieldOutcome.ServiceField` classification and
  asserts `BuildContext.classifyDeleteTableProjection` rejects before any
  `ChildField.SingleRecordTableFieldFromReturning` is constructed; the two
  together close the classifier-emitter contract loop named by
  `mutation-delete-carrier.pk-resolution-projection-clean`.
- **L4 (pipeline).** `MutationDmlNodeIdClassificationTest` adds rows for
  the four admission cells of the cardinality × element matrix.
  Composite-PK admission row uses the R130 reproducer fixture (the
  `slettRegelverksamling` shape that motivated this item).
- **L5 (compile spec).** `graphitron-sakila-example` adds at least one
  DELETE-payload-carrier fixture for each of `DataElement.Id` and
  `DataElement.Table`; `mvn -f graphitron-rewrite/pom.xml install -Plocal-db`
  passes end-to-end.
- **L6 (execution).** `DmlBulkMutationsExecutionTest` (or a sibling DELETE-
  specific class) adds end-to-end proofs against real PostgreSQL via
  Testcontainers:
  - bulk DELETE with `DataElement.Id` returns the list of encoded IDs of
    actually-deleted rows (subset semantics: input IDs that don't match a
    row are absent from the response).
  - bulk DELETE with `DataElement.Table` selects `{ id, optionalNonPkField }`
    and observes the FK / nullable fields resolving to null.
  - single DELETE variants of both.

## Acceptance criteria

- `DataElement.Id(String name, FieldWrapper wrapper)` is added to the sealed
  `DataElement` interface, axis-symmetric with `Table` and `Record`. The
  record's compact constructor pins the admitted wrapper set (singleton
  `ID`/`ID!` or list-of-non-null `[ID!]`/`[ID!]!`); the resolved NodeId
  encoder lives on the per-field `ChildField.SingleRecordIdFieldFromReturning`
  carrier via `CallSiteCompaction.NodeIdEncodeKeys`, not on the
  `DataElement.Id` record and not on `SingleRecordCarrierShape`. All
  consumers exhaustively switch over the three `DataElement` arms; the
  `CarrierFieldRoleCoverageTest` build-time audit is green.
- Builder-internal `PerFieldOutcome` sealed interface added with five arms
  (`PkRead`, `NonPkNullable`, `NonPkNonNullable`, `ServiceField`,
  `UnsupportedField`); model-facing `PkResolution` sealed interface added with
  two arms (`PkRead`, `NonPkNullable`),
  per §Narrowed `DataElement.Table` for DELETE. `PerFieldOutcome` is package-
  private to the carrier-walk site and does not escape the classifier;
  `PkResolution` is the public, narrow type carried by
  `ChildField.SingleRecordTableFieldFromReturning` to the emitter (not on
  `SingleRecordCarrierShape`, not on `DataElement.Table`). The classifier
  produces `PerFieldOutcome.PkRead` for plain `ColumnField`-over-PK, for
  `CompositeColumnField` via `@nodeId` over PK, and for the SDL `id`-alias case
  (one arm because the three share emitter dispatch); the projection step maps
  each `PerFieldOutcome.PkRead/NonPkNullable` to the matching `PkResolution`
  arm with identical components.
- A new `BuildContext.tryResolveSingleRecordCarrier(String, DmlKind)` overload
  is `DmlKind`-aware and produces a typed result that already encodes
  DELETE-admissibility. The resolution admits `DataElement.Id` on DELETE per
  §Carrier classifier admission and rejects on every non-DELETE verb with the
  permit-verb invariant message. For `DataElement.Table` on DELETE, the
  resolution calls `BuildContext.classifyDeleteTableProjection`, which produces
  a `List<PerFieldOutcome>` and either rejects (any `NonPkNonNullable`,
  `ServiceField`, or `UnsupportedField` arm, diagnostic naming the offending
  fields) or projects to a `List<PkResolution>` carried on the resulting carrier.
  `classifyDeleteTableProjection` wears `@LoadBearingClassifierCheck(key =
  "mutation-delete-carrier.pk-resolution-projection-clean", ...)`; the matching
  `@DependsOnClassifierCheck` lands on the
  `SingleRecordTableFieldFromReturning` emitter case in `FetcherEmitter`.
- The unconditional DELETE-with-carrier rejection at
  `FieldBuilder.java:2960-2965` is removed entirely. `FieldBuilder` consumes
  the typed `SingleRecordCarrierResolution` result from the new
  `tryResolveSingleRecordCarrier(String, DmlKind)` overload without
  re-branching on `DmlKind`. The pre-existing single-arg overload at
  `BuildContext.java:505` is unchanged; the four non-DML call sites
  (`GraphitronSchemaBuilder.java:227`, `TypeBuilder.java:198`,
  `MutationInputResolver.java:178/210/233`) continue to use it.
- `PkResolutionEmitterReachabilityTest` reflects over `PkResolution`'s two
  arms and fails on additions without an emitter case in the
  `SingleRecordTableFieldFromReturning` dispatch; companion fixture asserts
  `PerFieldOutcome.ServiceField` classifications are caught at
  `classifyDeleteTableProjection` before any `ChildField` carrier is
  constructed. Both sit alongside
  `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` in
  the generator-coverage tier.
- `MutationDmlRecordField` and `MutationBulkDmlRecordField` accept
  `DmlKind.DELETE` once the upstream classifier admits it; both compact
  constructors update their kind-set invariant accordingly.
- Two new `ChildField` sealed siblings of today's `SingleRecordTableField`:
  - `SingleRecordIdFieldFromReturning(name, encode, wrapper)` ; "no follow-up
    read; element is the encoded PK ID via `CallSiteCompaction.NodeIdEncodeKeys`,"
    load-bearing for DELETE + `DataElement.Id`.
  - `SingleRecordTableFieldFromReturning(name, projection, wrapper)` ; "no
    follow-up read; source IS the RETURNING record; per-field projection comes
    from the typed `PkResolution` list," load-bearing for DELETE +
    `DataElement.Table`.
  The two siblings encode genuinely different invariants from today's
  `SingleRecordTableField` ("follow-up SELECT outside the tx," load-bearing
  for INSERT / UPDATE / UPSERT), not different values of one knob. The
  existing `SingleRecordTableField` is unchanged. Each per-field carrier
  owns the data its emitter consumes (`encode` on the `Id` sibling,
  `projection` on the `Table` sibling).
- The data-fetcher emitter for `SingleRecordTableFieldFromReturning` consumes
  the per-field `PkResolution` projection off its own carrier directly; it
  does not re-walk the element SDL type and does not consult a parallel boolean
  to decide which fields emit `null` ; the `NonPkNullable` arm of the sealed
  switch is the direct emitter case.
- The DML emitter narrows RETURNING to PK columns on DELETE-with-carrier.
  For INSERT / UPDATE the existing RETURNING shape is unchanged.
- Pipeline-tier and execution-tier coverage per §Tests is in place and green.
- Full `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes on
  Java 25.
- User-facing doc: the `Payload-returning DELETE` sub-section drafted in
  §"User documentation (first-client check)" lands in
  `docs/manual/reference/mutations.adoc` (co-located with the existing
  mutations content) covering the two element arms, the non-null
  PK-resolvability rule on `DataElement.Table`, the silent-null semantics on
  nullable non-PK fields, the strict `@service` reject, and the
  recommendation to prefer `DataElement.Id`.

## Roadmap entries (siblings / dependencies / follow-ups)

- **Follows R75 / R141.** The single-record DML carrier
  (`MutationDmlRecordField` R75 Phase 1) and the bulk-input DML carrier
  (`MutationBulkDmlRecordField` R141) are the two emit-site classes this
  item lifts DELETE onto. Both compact constructors today reject
  `DmlKind.DELETE`; this item lifts that rejection in coordination with the
  upstream `FieldBuilder.java:2960-2965` lift.
- **Sibling to R12 (`error-handling-parity`).** R12's `ErrorChannelRole`
  permit composes with the existing `DataChannel`; the `DataElement.Id` and
  narrowed `DataElement.Table` arms inherit that composition without
  additional work. Either item can ship first; no implementation dependency.
- **Sibling to R144 (`mutation-cardinality-safety-default`).** R144's
  PK-coverage check on DELETE filter columns is the upstream guarantee that
  every input row matches at most one database row, which makes the
  "RETURNING gives back exactly the deleted rows' PKs" subset semantics
  predictable. This item assumes R144 has shipped; the in-review R144
  artefact is the operative regime.
- **Follow-up: affected-row count carrier permit.** A separate Backlog stub
  filed alongside this Spec covers `count: Int!` payload fields. Different
  role permit (no @table linkage, no per-row data), structurally distinct
  from `DataChannel.DataElement`.
- **Follow-up: snapshot-before-delete with arbitrary projection.** If a
  future need surfaces for DELETE payloads that project non-PK columns, the
  CTE shape in §Alternatives (B) is the natural mechanism. Not filed
  pre-emptively; the user's rule for this item ("only PK columns can carry
  data") rules it out cleanly.
- **Independent from R94 (`emit-input-records`).** R94 is about input-side
  validation seam; this item is about DELETE payload carrier shapes.
  No implementation dependency.

## Review history

### In Review → Ready (2026-05-14): first review pass

Reviewer flagged sound implementation but five gaps: missing
`PkResolutionEmitterReachabilityTest`, missing `MutationDmlNodeIdClassificationTest`
admission rows, thin L3 admission matrix (especially zero `DataElement.Id`
classifier-level coverage), no shipped-at housekeeping in the spec body, and
the Record-synthesis assumption in the emitter was not surfaced in the spec.

### Ready → In Progress → In Review (2026-05-15): rework pass

Each of the five review gaps is now closed; the §Shipped panel above lists
the landing commits. Summary:

1. `PkResolutionEmitterReachabilityTest` shipped as a four-test reflective
   audit alongside `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`.
   The audit scans `PkResolution` arms against a `HANDLED_BY_EMITTER` set
   (catches both forgotten-emitter-case and stale-allowlist drift), asserts
   `PerFieldOutcome`'s rejection arms exist and do NOT leak into
   `PkResolution`, asserts record-component symmetry across the two sealed
   roots, and pins `BuildContext.classifyDeleteTableProjection`'s
   `@LoadBearingClassifierCheck` key by reflection.
2. `MutationDmlNodeIdClassificationTest` gained six new tests covering the
   four cardinality × element-arm admission cells plus two rejection paths
   (wrong-encoder-table, no-@node-backed-input-table). The composite-PK
   admission cells use the R130 reproducer fixture (`Bar` with `id_1`,
   `id_2`).
3. `MutationDeletePayloadCarrierCase` gained UPDATE / UPSERT + `[ID!]`
   permit-verb rejection rows and the DELETE + `[Foo!]` with @service-field
   rejection row. Together with the L4 admission cells above, the
   `DataElement.Id` and rejection paths are now exercised at both pipeline
   tiers; the gaps the first review flagged (FK-reference rejection and
   no-@node-backed-input-table rejection) are covered through the L4
   pipeline tests where the nodeidfixture catalog makes the SDL shape
   realisable.
4. Spec body collapsed: a §Shipped panel up front lists the per-phase
   landing commits; the design body stays as historical reference; the
   review-history section captures both passes.
5. A §Runtime caveats section names the PK-only Record-synthesis approach
   and its load-bearing same-Field-instance assumption.

`VariantCoverageTest`'s `SingleRecordIdFieldFromReturning` allowlist entry
was rewritten: the entry stays (because the GraphitronSchemaBuilderTest
ClassificationCase enum runs against the default Sakila catalog and can't
swap to `nodeidfixture` per-case), but it now points at the
`MutationDmlNodeIdClassificationTest` admission cells and the
`DmlBulkMutationsExecutionTest` execution proof as the structural and
end-to-end coverage sources.

`mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes;
`mvn test -pl :graphitron` reports 1714 tests, 0 failures, 0 errors,
0 skipped.

Implementation shipped at: `ba4697f` (Phase A model), `cbe4634` (Phase B
verb-aware walk), `61ce2c8` (Phase C/D classifier rewire + MutationField
admission), `8d88bb5` (Phase E/F emitters), `fe676bf` (Phase G partial L1/L3),
`a869716` (Phase G L5 sakila), `160f102` (Phase G L6 execution-tier),
`e08c439` (Phase H user docs), `424bc42` (doc sweep). Review-rework pass:
`b5209a9`.
