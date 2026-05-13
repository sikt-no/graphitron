---
id: R156
title: "Payload-returning DELETE: carrier permits for PK-echo and PK-only RETURNING record"
status: Spec
bucket: architecture
priority: 6
theme: mutations-errors
depends-on: []
created: 2026-05-13
last-updated: 2026-05-13
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

The first rejection fires at `FieldBuilder.java:2689`: today's R75 / R141 carrier
path runs a PK-only RETURNING inside the tx and then a follow-up response SELECT
outside it (`FetcherEmitter.java:60-66`). For DELETE the follow-up SELECT can't
run because the row is gone, so the carrier path refuses DELETE unconditionally.
The second rejection fires at the role-permit classifier: today's only admitted
`DataChannel.DataElement` arms are `Table` and `Record`, neither of which fits
an `ID`-element echo.

Once R12 (`error-handling-parity`) lands, every mutation will want a payload
type to carry the `errors:` channel alongside the produced rows. The current
"use bare `ID` / `[ID!]` as the return type" escape hatch at
`FieldBuilder.java:2732-2741` cannot compose with R12, so DELETE will be the
only verb that can't participate in payload-carrier shapes. This item closes
that gap.

The design rule the user committed to in conversation: after a DELETE the only
data the response can honestly project is the deleted row's primary key. Any
non-PK column has been removed; any non-nullable SDL field that depends on a
non-PK column cannot resolve. RETURNING is narrowed to PK columns only;
non-PK-resolvable fields on the projected SDL type are either nullable (resolve
to null at runtime) or reject the carrier at classify time.

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
  type via a typed `PkResolution` projection and rejects on any
  `NonPkNonNullable` or `ServiceField` arm. The projection is carried through
  to the emitter (no re-derivation at emit time).
- Both bulk and single via the existing wrapper cardinality dispatch on the
  carrier's data field: `MutationBulkDmlRecordField` for list-shaped data
  fields (R141), `MutationDmlRecordField` for singletons (R75 Phase 1).
- Removes the unconditional DELETE-with-carrier rejection at
  `FieldBuilder.java:2689` entirely. The DELETE-admissibility decision moves
  into `tryResolveSingleRecordCarrier`, which now takes a `DmlKind` and
  produces a typed result. `FieldBuilder` consumes the typed result without
  re-branching on kind.

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
 * slot on the resolved carrier shape, the same slot every other NodeId-encoded
 * field uses (see {@code CallSiteCompaction.java}). Mixing the encoder
 * reference into the {@code DataElement.Id} record itself would conflate two
 * axes ; "what's the element shape" and "how to project it" ; and create a
 * parallel home for an encoder reference {@code CallSiteCompaction} already owns.
 */
record Id(String name, FieldWrapper wrapper) implements DataElement {}
```

The encoder is resolved once at carrier-classify time (same lookup the bare-`ID`
DELETE return path uses at `FieldBuilder.java:2732-2741`) and attached to the
carrier shape via the existing `CallSiteCompaction.NodeIdEncodeKeys` slot. The
data fetcher emission for `DataElement.Id` reads the compaction off the role,
not off the element. Carriers whose input `@table` is not `@node`-backed reject
with the same diagnostic as the bare-`ID` path.

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
   attaches to the carrier shape via `CallSiteCompaction.NodeIdEncodeKeys`.
4. The mutation kind is `DmlKind.DELETE`. INSERT / UPDATE / UPSERT carriers
   reject `DataElement.Id` at classify time with the message "PK-echo
   `DataElement.Id` is only valid on `@mutation(typeName: DELETE)` carriers;
   on other verbs the response SELECT runs normally — use a `@table` element
   type instead".

The cardinality matrix (bulk × {Id, Table}, single × {Id, Table}) drops out
of the existing carrier-data-field wrapper dispatch at `FieldBuilder.java:2707`:
a list-shaped element data field routes to `MutationBulkDmlRecordField`, a
singleton to `MutationDmlRecordField`. No new dispatch axis.

### Narrowed `DataElement.Table` for DELETE: typed per-field projection

For DELETE, the existing `DataElement.Table` arm continues to be admitted, but
only when the carrier walk's per-field classification over the element SDL type
produces an admissible projection. The classification is itself a typed,
builder-internal sealed result that the carrier shape carries through to the
emitter, rather than a binary admit/reject predicate that gets thrown away.

```java
/**
 * Per-field classification produced by the DELETE carrier walk over a
 * {@link DataElement.Table} element type. Each field on the element SDL type
 * classifies into exactly one arm; the carrier rejects on the presence of any
 * {@link NonPkNonNullable} or {@link ServiceField} arm and otherwise carries
 * the full per-field projection forward to the data-fetcher emitter, which
 * consumes the projection to decide which fields read from the source
 * RETURNING {@code Record} and which resolve to {@code null}.
 *
 * <p>Builder-internal sealed projection (per the
 * {@code rewrite-design-principles.adoc} "Builder-internal sealed hierarchies
 * for multi-target classification" rule): the classifier and the emitter
 * share one source of truth rather than re-walking the SDL type at each
 * consumer.
 */
public sealed interface PkResolution {
    String fieldName();

    /** Field maps directly to a PK column on the input @table. Resolves from the source Record. */
    record PkColumn(String fieldName, jOOQField<?> column) implements PkResolution {}

    /** Field is @nodeId over a composite PK column set. Resolves via the composite encoder. */
    record PkComposite(String fieldName, List<jOOQField<?>> columns, HelperRef.Encode encode) implements PkResolution {}

    /** Field is the SDL "id" alias on a @node-backed element type whose PK is the input @table's PK. */
    record NodePkAlias(String fieldName, HelperRef.Encode encode) implements PkResolution {}

    /** Field maps to a non-PK column. Nullable in SDL; runtime resolves to null. */
    record NonPkNullable(String fieldName) implements PkResolution {}

    /** Field maps to a non-PK column and is non-nullable in SDL. Carrier rejects. */
    record NonPkNonNullable(String fieldName) implements PkResolution {}

    /**
     * Field is @service-resolved. Carrier rejects unconditionally, nullable or
     * not. We do not support projecting the deleted SDL type through a service;
     * authors should use {@link DataElement.Id} to echo the deleted PK instead.
     */
    record ServiceField(String fieldName) implements PkResolution {}
}
```

The per-field arms classify directly from the element SDL type's existing
field-classifier output (`ColumnField`, `CompositeColumnField`, `NodeIdField`
variants, `ServiceField` from the field-builder). FK-traversing reference
fields classify into `NonPkNullable` or `NonPkNonNullable` based on whether the
FK column is part of the input `@table`'s PK and the SDL field's nullability.

**Carrier-admission rule.** The carrier walk computes the per-field
`PkResolution` list at classify time and rejects the carrier if any arm is
`NonPkNonNullable` or `ServiceField`. The rejection message names the offending
field(s) and points at `DataElement.Id` as the recommended pattern:

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

Today's `FieldBuilder.java:2689` re-asks "is this element kind admissible for
DELETE?" with a `kind == DmlKind.DELETE` branch, after the carrier walk has
already classified the element. Two consumers reading the same admissibility
predicate. The principles-architect rule "if two consumers evaluate the same
predicate over a model field, the branch belongs in the model" applies here.

The fix: `tryResolveSingleRecordCarrier` itself produces a kind-aware result.
`SingleRecordCarrierResolution.Ok` already carries the resolved shape; the walk
gains a `DmlKind` parameter (or routes through a per-kind helper) so that the
DELETE-admissibility check (element kind in `{Id, Table}`; the per-field
`PkResolution` projection clean) happens during resolution. The
`SingleRecordCarrierResolution` sealed type's existing `Rejected` arm carries
the diagnostic for inadmissible DELETE shapes.

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
        return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(rejected.message()));
    case SingleRecordCarrierResolution.NotACarrier nac -> {
        // existing bare-ID / bare-[ID!] DELETE return path stays
    }
}
```

The unconditional DELETE rejection at `FieldBuilder.java:2689` disappears
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

The data fetcher splits by `DataElement` arm:

- **`DataElement.Id`**: the data field's value is the encoded `ID` produced by
  running the source PK `Record`'s PK columns through the
  `CallSiteCompaction.NodeIdEncodeKeys` slot the carrier shape carries.
  Single-shaped element wrapper emits `($T env) -> encode(record.get(pkCol))`;
  composite PK uses the existing `CompositeColumnField` composite encoder
  call (`FetcherEmitter.java:114-130`). Bulk-shaped wrappers iterate the
  `Result<Record>` and map each row through the same encoder. No follow-up
  SELECT.
- **`DataElement.Table`**: the data field's value is the source PK `Record`
  itself, presented to graphql-java as the parent record for the projected
  element type's per-field traversal. Each field's resolution is decided by
  the typed `PkResolution` projection the carrier shape carries: `PkColumn`,
  `PkComposite`, and `NodePkAlias` arms emit normal column / encoder reads;
  `NonPkNullable` arms emit a constant-`null` fetcher (carried as a per-field
  flag on the source-key plumbing rather than re-derived from the SDL type).
  No follow-up SELECT.

The two arms share neither runtime mechanism, so the emit-site type forks
sealed-switch-style: a new `ChildField.SingleRecordTableFieldFromReturning`
holds the `DataElement.Table` DELETE emission, sibling to today's
`ChildField.SingleRecordTableField` at `FetcherEmitter.java:60`. The two
siblings encode genuinely different *invariants*, not different values of one
knob: today's `SingleRecordTableField` carries the "follow-up SELECT outside
the tx" invariant load-bearing for INSERT / UPDATE / UPSERT (jOOQ field errors
during the read or nested traversal cannot undo the DML); the new sibling
carries the opposite invariant ("no follow-up read at all; source IS the
RETURNING record") load-bearing for DELETE (the row is gone, there is no SELECT
to attempt). A discriminator on a single type would force every consumer to
branch on it; the sealed sibling pushes the certainty into the type system.
The existing `SingleRecordTableField` continues to handle INSERT / UPDATE /
UPSERT carriers unchanged.

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

## Tests

- **L1 (model).** Compact-constructor invariants on `DataElement.Id`
  (encoder non-null, wrapper accepted, name accepted). The existing
  `SingleRecordCarrierShape` compact constructor needs no change: the new
  arm composes through the sealed `DataElement` interface.
- **L3 (validator).** New parameterised case in
  `GraphitronSchemaBuilderTest.MutationDeletePayloadCarrierCase` covering the
  admission and rejection matrix:
  - bulk DELETE + `[ID!]` carrier field, implicit recognition: admits.
  - bulk DELETE + `[ID!] @nodeId` carrier field, explicit: admits.
  - single DELETE + `ID` carrier field, implicit: admits.
  - single DELETE + `ID @nodeId` carrier field, explicit: admits.
  - bulk DELETE + `[Foo!]` carrier field, element type has only PK-resolvable
    non-nullable fields and nullable non-PK fields: admits; `PkResolution`
    projection carries the per-field plan to the emitter.
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
  resolved NodeId encoder lives on the carrier shape via
  `CallSiteCompaction.NodeIdEncodeKeys`, not on the `DataElement.Id` record.
  All consumers exhaustively switch over the three arms; the
  `CarrierFieldRoleCoverageTest` build-time audit is green.
- `PkResolution` sealed interface added with arms `PkColumn`, `PkComposite`,
  `NodePkAlias`, `NonPkNullable`, `NonPkNonNullable`, `ServiceField` per the
  shape in §Narrowed `DataElement.Table` for DELETE. The
  `SingleRecordCarrierShape` for a DELETE + `DataElement.Table` carrier
  carries the per-field projection list as a slot on the shape.
- `BuildContext.tryResolveSingleRecordCarrier` is `DmlKind`-aware and produces
  a typed result that already encodes DELETE-admissibility. The resolution
  admits `DataElement.Id` on DELETE per §Carrier classifier admission and
  rejects on every non-DELETE verb. For `DataElement.Table` on DELETE, the
  resolution computes the `PkResolution` projection and rejects on any
  `NonPkNonNullable` or `ServiceField` arm.
- The unconditional DELETE-with-carrier rejection at
  `FieldBuilder.java:2689` is removed entirely. `FieldBuilder` consumes the
  typed `SingleRecordCarrierResolution` result without re-branching on
  `DmlKind`.
- `MutationDmlRecordField` and `MutationBulkDmlRecordField` accept
  `DmlKind.DELETE` once the upstream classifier admits it; both compact
  constructors update their kind-set invariant accordingly.
- A new `ChildField.SingleRecordTableFieldFromReturning` carries the "no
  follow-up SELECT; source IS the RETURNING record" *invariant* for
  DELETE + `DataElement.Table`, sealed-sibling to today's
  `ChildField.SingleRecordTableField` which carries the opposite "follow-up
  SELECT outside the tx" invariant for INSERT / UPDATE / UPSERT carriers.
  The two siblings encode different invariants, not different values of one
  knob; the existing `SingleRecordTableField` is unchanged.
- The data-fetcher emitter for `SingleRecordTableFieldFromReturning` consumes
  the per-field `PkResolution` projection off the carrier shape directly; it
  does not re-walk the element SDL type to decide which fields go to null.
- The DML emitter narrows RETURNING to PK columns on DELETE-with-carrier.
  For INSERT / UPDATE the existing RETURNING shape is unchanged.
- Pipeline-tier and execution-tier coverage per §Tests is in place and green.
- Full `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes on
  Java 25.
- User-facing doc: a new `docs/manual/reference/mutations/delete-payloads.adoc`
  (or co-located in the existing mutations doc) covers the two element arms,
  the non-null PK-resolvability rule on `DataElement.Table`, the silent-null
  semantics on nullable non-PK fields, and the recommendation to prefer
  `DataElement.Id`.

## Roadmap entries (siblings / dependencies / follow-ups)

- **Follows R75 / R141.** The single-record DML carrier
  (`MutationDmlRecordField` R75 Phase 1) and the bulk-input DML carrier
  (`MutationBulkDmlRecordField` R141) are the two emit-site classes this
  item lifts DELETE onto. Both compact constructors today reject
  `DmlKind.DELETE`; this item lifts that rejection in coordination with the
  upstream `FieldBuilder.java:2689` lift.
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
