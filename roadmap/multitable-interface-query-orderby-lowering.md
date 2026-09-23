---
id: R382
title: "Lower orderBy onto multitable-interface/union queries"
status: Spec
bucket: bug
priority: 3
theme: interface-union
depends-on: []
created: 2026-06-25
last-updated: 2026-09-23
---

# Lower orderBy onto multitable-interface/union queries

## Goal

A root query field returning a multi-table interface or union sorts by what its author and its
clients asked for, instead of by the participants' primary keys. A *participant* is one concrete
`@table` implementation behind the interface or union, and such a field is read as one SQL statement
per participant combined with `UNION ALL`, which is why the ordering is not simply the single-table
case again.

Both halves of the sort surface reach the branches: a field-level `@defaultOrder`, and a runtime
`@orderBy` argument over an `@order` enum. Today neither does, and neither is rejected as ignored,
so this SDL builds and returns primary-key order whatever the client asks:

```graphql
type Customer @table(name: "customer") {
  firstName: String @field(name: "first_name")
  lastName: String @field(name: "last_name")
}
type Staff @table(name: "staff") {
  firstName: String @field(name: "first_name")
  lastName: String @field(name: "last_name")
}
union AddressOccupant = Customer | Staff

enum OccupantSort {
  OCCUPANT_ID @order(primaryKey: true)
  LAST_NAME   @order(fields: [{name: "last_name"}])
}
input OccupantOrderBy { field: OccupantSort!, direction: SortDirection }

extend type Query {
  # The single-table twin, for contrast: one statement, so the ordering is honoured today.
  customers: [Customer!]! @defaultOrder(fields: [{name: "last_name"}])

  # Same declaration, multi-table return type. That is the only thing that varies, and it is why
  # these rows come back in primary-key order. Both participant tables carry last_name, so the
  # ordering is expressible; it is unimplemented, not impossible.
  occupants: [AddressOccupant!]! @defaultOrder(fields: [{name: "last_name"}])

  # The runtime half, equally lost today: OccupantSort resolves per participant against each
  # participant's own table.
  occupantsOrdered(orderBy: OccupantOrderBy @orderBy): [AddressOccupant!]!
    @defaultOrder(fields: [{name: "last_name"}])

  # The reported form. @asConnection makes the sort key the Relay cursor too, so paging has to
  # stay consistent across requests, not just within one page.
  occupantsConnection(orderBy: OccupantOrderBy @orderBy): [AddressOccupant!]!
    @asConnection(defaultFirstValue: 100)
    @defaultOrder(fields: [{name: "last_name"}])
}
```

The only thing that varies between `customers` and `occupants` is the return type. When this item
lands, `occupants` returns `last_name` order rather than primary-key order, `direction: DESC` on
`occupantsOrdered` returns the reverse of `direction: ASC`, and paging through `occupantsConnection`
visits every row exactly once under the author's ordering, with `Customer` and `Staff` rows
interleaved by `last_name` rather than segregated by participant.

Scope is list-shaped reads on the two root variants, `QueryField.QueryInterfaceField` and
`QueryField.QueryUnionField`. Two neighbouring shapes stay rejected at build time rather than
becoming quietly accepted, and keeping them rejected is a real obligation of this item rather than a
side note: the child multi-table fields (`ChildField.InterfaceField` / `ChildField.UnionField`), and
a single-valued multitable root, where the ordering resolver returns nothing to lower. See "The
rejection this item narrows".

## Problem

Neither root record declares an `orderBy` component, so neither implements `SqlGeneratingField`, and
`MultiTablePolymorphicEmitter` orders results solely by the synthetic `__sort__` key (the participant
PK). A consumer asking for a specific order gets PK order regardless, and so does an author who
declared one with `@defaultOrder`. The ordering is *resolved* per participant already and then
discarded; see "What the tree already has" below.

The declaration is not silently accepted any more. R677 shipped a fact-derived rejection over read
shape that fails the build on a declared ordering at any participant-fan-out coordinate, so today the
reported schema stops the build instead of returning wrong pages. This item delivers the lowering
that rejection defers to, and narrows it at exactly the coordinates the lowering reaches: the
list-shaped roots.

Split off from R363, which deliberately scoped its day-one work to `@field` filter lowering (the
reported data-correctness bug) and left ordering to this item. The two are siblings: both lower a
per-field surface onto a polymorphic UNION, both must hold the "column present on every participant"
rule (lowered per participant against each participant's own table, so an absent or
type-incompatible column on one participant becomes that participant's classifier rejection), and
both thread their result into each UNION branch in `MultiTablePolymorphicEmitter`.

## Field report

Reported on 10.0.0-RC30 at https://github.com/sikt-no/graphitron/issues/523, filed 2026-08-12,
which is the headline half of that issue. The item predates the report by seven weeks; the report
adds four things it did not have.

**The authored half fails too, not just the argument.** Their field is
`Query.applikasjoner: [Applikasjon]` carrying `@asConnection(defaultFirstValue: 100)`,
`@defaultOrder(fields: [{name: "NAVN"}])`, and an `orderBy:` argument with `@orderBy` over an
`@order` enum. `Applikasjon` is a multitable interface with three `@table` implementations, and all
three carry `NAVN`, so the "column present on every participant" rule this item has to hold is
satisfied by their schema. At runtime `direction: ASC` and `direction: DESC` return byte-identical
result pages in `subjekt_id` order. So the fix has to cover both availability routes, not just the
argument: a `Fixed` ordering resolved from `@defaultOrder` and an `Argument` ordering dispatched at
runtime. Those are the two arms of `OrderBySpec` that carry columns, and the item's slices below are
cut along them.

**A build-time rejection was an acceptable outcome to the reporter, and it shipped.** They asked for
ordering to work, "or, if multitable ordering is unsupported, an author-error at generate time like
the one `@condition`-overloads produce, so the schema author finds out at build time rather than the
client at runtime." R677 shipped exactly that, stated generically over read shape rather than as a
coordinate-local check in `validateQueryInterfaceField` / `validateQueryUnionField`, so this item
inherits a live rejection to narrow rather than a silence to fill. What this item owed R677 was the
capability answer it rejects against, namely that these arms could not lower an ordering; delivering
the lowering is what retires that answer at the root coordinates.

**The report landed on the connection arm, which is the harder one.** `@asConnection` means
`__sort__` is the Relay cursor seek key, so their coordinate needs the cursor half of the design
below, not just the sort.

**Filters on the same field work, which isolates the failure.** They confirm `@condition` filters
on this query behave correctly, so the input reaches the generated SQL and only the ordering is
lost. That corroborates R363's landed filter lowering on the same fields and rules out a general
argument-binding fault on the arm.

One docs consequence: they went looking for a stated limitation first, and at the time neither the
Sorting nor the Polymorphic queries page said ordering was unwired. Both pages now state it, because
R677's rejection wrote the statement; this item makes both statements wrong again and owes their
replacement (see "Documentation" below).

The same issue's follow-up comment reports a second, distinct coordinate, owned by
R663 (`split-query-child-list-drops-default-order`, shipped; entry in `roadmap/changelog.md`).
The reporter reads the two as one bug. They are not the same defect, but they share a consumer and a
schema, so fixing either alone leaves that schema unordered at the other end.

## What the tree already has

Four facts shrink the item from its Backlog framing. Each is worth re-checking at pickup, but each
was read off the tree while this spec was written.

**The per-participant ordering is already resolved, and then dropped on the floor.**
`FieldBuilder.lowerParticipantFilters` calls `resolveTableFieldComponents` once per table-bound
participant, handing it that participant's own table, and `TableFieldComponents.Ok` carries an
`OrderBySpec orderBy` component alongside `filters`. Only `tfc.filters()` reaches
`new ParticipantFilters(tb, tfc.filters())`. So the classification half of this item is largely
plumbing an existing value through, not writing a new resolver, and the "column present on every
participant" rule is enforced by the same call that already enforces it for filters: a participant
whose table lacks the column comes back `TableFieldComponents.Rejected` and joins the existing
`mintParticipantFailures` path. The `index:` column source resolves per participant against that
participant's catalog for free by the same route; whether a missing index there returns a `Rejected`
or throws is the one thing to verify before relying on it.

**The cursor codec is already generic, and is not PK-typed.**
`ConnectionHelperClassGenerator` emits `encodeCursor(Record, List<Field<?>>)`, which serialises each
value through `val.toString()`, and `decodeCursor(String, List<Field<?>>)`, which converts each token
back through `col.getDataType().convert(token)` with strict arity. `pageRequest(first, last, after,
before, defaultPageSize, orderBy, extraFields, selection)` takes the sort fields and the cursor
columns as runtime lists and derives the seek from them. What is PK-typed is the emitter's build-time
`Field<T> sortField` local in `buildRootConnectionFetcher` (the `pkColumnClass` computation), not the
codec. Handing the codec a longer column list is therefore a change at the emitter only. The one
constraint the codec does impose is that each cursor column arrive as a `Field<?>` carrying a
`DataType`, which is what makes a build-time-typed slot (below) preferable to an untyped one.

**The stage-1 seam is clean; the stage-2 seam is not.**
`branchProjection(participant, tableAlias)` is called from exactly two places, `buildStage1Block`
(list root) and `buildStage1ConnectionBlock` (connection root), and the batched child paths carry
their own separate stage-1 projection builders. But the connection arm has a *third* projection
site: `buildPerTypenameSelect(..., includeSortKey = true)` re-projects `__sort__` off the
participant's own table, because the per-edge cursor is encoded from the **stage-2** record, and
that method is shared with the batched child connection arm (the `includeSortKey = true` call sites
are `emitRootConnectionMethods` and `emitBatchedConnectionMethods`). So "three sites must agree,
one of which this item must not change the behaviour of" is the real shape of the seam, and it is
the reason the slot list is threaded as a value that is empty on the child paths rather than as a
second boolean beside `includeSortKey`.

**A `primaryKey: true` named order is already delivered.** `@order(primaryKey: true)` resolves to the
participant's PK columns, which is exactly what `__sort__` already projects. That enum value needs no
new slot; it selects the existing one. Worth pinning as a test rather than discovering in flight.

## Implementation

### Model: one field-level ordering, with per-participant columns hanging off each slot

The tempting move is to mirror R363 exactly: a `ParticipantOrdering(participant, OrderBySpec)` list
beside the existing `ParticipantFilters` list. Do not. The two axes are not the same shape, and the
difference is what the model has to express.

Filters are genuinely per participant all the way down: the generated condition method is named per
participant and the extraction differs per table, so there is no field-level filter fact to state.
An ordering is almost entirely a field-level fact. The argument name, the input type name,
`sortFieldName`, `directionFieldName`, list-ness and nullability, the set *and order* of named
orders, each entry's direction and collation, and `uniformAsc` are all one decision written once in
the SDL. Only the resolved `ColumnRef` per entry is per participant. A `List<ParticipantOrdering>`
carrying N whole `OrderBySpec`s therefore stores N copies of one decision, with nothing binding the
copies together, and the invariant the whole emission design rests on (same slots, same order,
compatible types, on every branch) degrades into an agreement between N independently resolved
copies that no type enforces.

So state the field-level half once and hang the per-participant resolution off each slot. In the
register of:

```
record PolymorphicOrdering(
    OrderingSurface surface,              // argument name / sortField / directionField / list-ness,
                                          //   or the Fixed-only arm
    List<OrderingSlot> slots)             // ordered; the index IS the slot's identity
record OrderingSlot(
    int ordinal,
    String namedOrderName,                // null on the Fixed / base arm
    SortDirection direction, String collation, boolean uniformAsc,
    SequencedMap<ParticipantRef.TableBound, ColumnRef> columnByParticipant)
```

The exact carving is the implementer's; what is load-bearing is that the field-level half appears
once, that each slot carries its own ordinal rather than letting three emission sites recompute it,
that the per-participant column is keyed on the `ParticipantRef.TableBound` object rather than on a
typename string, and that the compact constructor requires every slot to be total over the
participant set. Then the cross-participant agreement holds by construction, and the capability
accessor returns one value rather than a list every consumer has to reconcile.

Whether this earns a sealed sub-taxonomy or sits as a record beside `OrderBySpec` is open. It cannot
*be* an `OrderBySpec` arm: no arm of that type can carry a per-participant column map, which is the
one-line justification the "shape the type as precisely as the fact allows" corollary asks for.

`FieldBuilder.lowerParticipantFilters` already resolves each participant's `OrderBySpec` and
discards it. It keeps resolving them, and folds the N specs into the one carrier above, which is
where the agreement rules below are checked. `ParticipantFilters` and `ParticipantFilterField` are
left alone: nothing about them becomes inaccurate, because the ordering does not ride them.

### Model: the cross-participant agreement rule

"The column exists on every participant and the types are compatible" is necessary and not
sufficient. `@order` resolves through the same entry resolution as `@defaultOrder`, which admits
`index:`, `primaryKey: true`, and a multi-entry `fields:` with per-entry `direction` and `collate`.
A named order is therefore a *list* of `ColumnOrderEntry`, not one column, and the per-participant
resolutions of one named order can disagree on more than type:

* **Arity.** Two participants' same-named indexes can project different numbers of columns. The
  fixed-slot projection assumes one slot count for the whole field, so unequal arity has to reject.
* **Positional type.** `UNION ALL` requires the i-th projected column to be type-compatible across
  branches. `VARCHAR` against `TEXT` unifies in PostgreSQL; `INT` against `VARCHAR` does not, and
  would surface as a SQL error from generated code at a consumer rather than as a build-time
  rejection here.
* **Direction and collation.** The outer `ORDER BY` runs over the slot alias, once, so a
  per-participant direction or collation disagreement is not merely unchecked, it is
  unrepresentable in the design as drawn.

State these per slot: in the carrier's compact constructor for the invariant, and as a located
rejection at the field coordinate for the author-facing half, naming which participant disagreed and
on what. A named order whose `index:` resolves two columns on one participant and three on another
is an author error and the message should say so. This is the acceptance every build-time-typed slot
in the emitter rests on, which is the classifier-guarantees-emitter-assumptions floor doing exactly
its job.

The simpler rule, "every slot resolves on every participant", is inherited rather than written:
`resolveTableFieldComponents` resolves the ordering against the table it is handed, so a participant
whose table lacks the column already comes back `Rejected` and already reaches
`mintParticipantFailures`, which mints one located diagnostic per failing participant plus one
consequence rejection at the field. That shape is right; reuse it.

### Model: the operation-member census reads the same capability

`OperationMemberRelation` is a second consumer of the ordering fact, and it evaluates the same
capability predicate the validators do: `Kind.ORDER_BY` fires on
`leaf instanceof SqlGeneratingField && !(sgf.orderBy() instanceof OrderBySpec.None)`, and
`payloadsFor` casts to `SqlGeneratingField` to build `OperationMember.OrderBy(OrderBySpec)`. Once
these two roots carry an ordering, the census silently under-reports their members unless it reads
the new capability too, and `OperationMember.OrderBy(OrderBySpec)` has no room for a per-participant
resolution.

`OperationMember.Condition` is the precedent and its javadoc already argues the case: it is sealed
into `OnReturnTable` / `OnParticipant` so that "a polymorphic coordinate carries one condition member
per table-bound participant instead of a fallback beside a one-arm summary". `OrderBy` takes the same
treatment, either sealed the same way or as a single arm carrying the `PolymorphicOrdering` above,
which the field-level-fact argument favours. Membership reads the capability accessor, not
`instanceof SqlGeneratingField`. `ConditionMembershipTest` is the precedent for pinning the
membership half.

`Kind.PAGINATE` is deliberately left alone; see the next section for why pagination is not this
item's fact to widen.

If the implementer does seal `OperationMember.OrderBy`, the single-arm record is retired vocabulary
and the item owes a `## Retired vocabulary` section for the Done gate's sweep. Nothing else in this
plan retires a name: the ordering does not ride `ParticipantFilters`, so that carrier and its
capability interface keep both their shape and their accurate names.

### Validation: nothing to widen, and the reason is R677's

The Backlog framing said the missing `orderBy` slot also left a rejection gap, because
`GraphitronSchemaValidator.validatePaginationRequiresOrdering` and `validateListRequiresOrdering`
are gated on `field instanceof SqlGeneratingField` and so never see these two arms. The gating is
real; the conclusion does not follow, on three counts.

**The shape those checks exist to reject does not occur here.**
`validatePaginationRequiresOrdering` rejects a paginated field whose ordering is `OrderBySpec.None`,
because a keyset cursor encodes ORDER BY column values and there have to be columns to encode. A
multitable root always projects `__sort__` from the participant PK and always seeks on it, so it
always has a column to encode and always returns a deterministic order.
`FieldUnlowerableOrderingTest.aMultitableRootWithNoDeclarationIsQuiet` states this as the property
the whole rejection rests on: an undeclared multitable read is quiet because "what is available is
exactly what is delivered and the never-unsorted invariant holds."

**Widening would not even typecheck cleanly.** `validatePaginationRequiresOrdering` reads
`pagination() != null`, and neither multitable root carries a `PaginationSpec` at all; the pagination
fact rides `FieldWrapper.Connection` on the return type, and `PolymorphicReturnType` carries a
`wrapper`. So a widened capability would owe a `pagination()` these leaves have nothing to answer
with, while "is this coordinate paginated" is already answerable off the wrapper at both leaves.

**Both checks are R677's to retire, by name.** They are listed in that item's own
`## Retired vocabulary`. R677's diagnosis is that the checks key on "this read resolves against one
table" while the question is "does this read return a list", and its phase 3 population deliberately
needs no capability membership. Reaching the same rule through a second capability interface would
make it two populations with two spellings, which is the drift R677 was filed against, and it would
be unwound weeks later.

So this item touches neither check and does not extend `SqlGeneratingField` to these records (it
could not: their `returnType()` is a `PolymorphicReturnType`, a sibling of the
`TableBoundReturnType` that interface requires, which is what R363 established when it kept these
two fields off it). What R382 owes the validator surface is only its own new classifier invariants:
the per-slot agreement rule above, minted per failing participant.

One thing to hand R677's implementer in return: after this item, the `PARTICIPANT_KEY` fallback's
*meaning* narrows from "the only ordering available at a fan-out read" to "the fallback where no
declaration was lowered". That is a precedence-order statement, not a population change.

### Emission: fixed slots, chosen at runtime

This is the load-bearing half, and the structural problem is a mismatch of when things are known. A
`UNION ALL` branch projection list must be fixed in arity and per-slot type at build time, because
that is what makes the branches unionable and what gives the cursor codec a `Field<?>` carrying a
`DataType`. An `@orderBy` argument picks its columns at request time.

The design: **project every candidate order column into every branch under a deterministic slot
alias, and choose among the slots at runtime.**

* The slot set is the deduplicated, ordered union of the columns named across the field's named
  orders plus its `Fixed` / base columns. That dedup is a decision made once, in the model, and
  carried as the slot list's ordinals. No emission site re-derives it.
* `branchProjection` gains the slots. Stage 1 stays a static code block in both root paths.
* `buildPerTypenameSelect` gains the slots too, because the per-edge cursor is encoded from the
  stage-2 record; without them `record.get(slotField)` is null and every cursor is a sentinel token.
  It is shared with the batched child connection arm, so the slots arrive as a list that is empty on
  the child call sites rather than as a second boolean beside `includeSortKey`.
* A `@order(primaryKey: true)` value needs no slot; it selects the existing `__sort__`.
* The outer order-by and seek lists become runtime-chosen. A new per-field helper, sibling to
  `TypeFetcherGenerator.buildOrderByHelperMethod`, switches over the same named orders at build time
  and returns the generated `OrderByResult` over `DSL.field(DSL.name(<slot alias>), <slot class>)`
  rather than over an aliased table's columns. The `uniformAsc` fork that decides whether the runtime
  `direction:` flips the spec carries over unchanged, being a property of the declaration and not of
  the table.
* **`orderBy` and `extraFields` move together, always.** `pageRequest` derives `seekFields` from
  `extraFields` positionally, so if the page order becomes `__ord0__, __sort__, __typename` while the
  seek key stays `__sort__`, the seek predicate no longer matches the sort prefix and pages skip or
  duplicate rows at every boundary. This is the single easiest way to ship a worse bug than the one
  being fixed, and it is why the cursor work cannot be deferred behind the runtime-dispatch slice.
* `__sort__` and `__typename` stay appended, after the author's columns, as the deterministic
  tiebreaker. They are what makes a cross-participant tie on every authored column page
  consistently; dropping them because the author supplied an ordering would reintroduce the
  tie-boundary double-count the existing comment on `tieField` describes.
* The slot alias spelling belongs with `SORT_COLUMN` and `PK_COLUMN_PREFIX` as an emitter constant
  composed with the carried ordinal. A `__`-prefixed string literal is fine here: the generated-
  sources lint's dunder rule permits synthetic SQL column aliases as literals and names `__sort__` as
  its exemplar, so no argument is owed. Whether the family as a whole should move to
  `no.sikt.graphitron.command.ReservedAliases`, which already owns the "writer alias equals reader
  alias" invariant and which `TYPENAME_COLUMN` already delegates to, is a tidy-up this item may take
  or leave; it is not load-bearing.

`ColumnOrderEntry.collation` has to reach the slot's `ORDER BY` through jOOQ's `Field.collate`. See
the note in "Other solutions we've considered" on the state of collation on the single-table path,
which decides whether that is a port or a fix.

### Slicing

Cut on **runtime dispatch only**, not on the cursor.

**Slice 1: the static shape.** The slot set, the branch projections in both root stage-1 paths, the
stage-2 slot projection, the static outer order-by, and the seek and `extraFields` composition. This
covers `@defaultOrder` on both the list and the `@asConnection` arms, which is the reported
coordinate's own shape, and it is where paging correctness is established once with build-time-known
columns.

**Slice 2: runtime dispatch.** The per-field helper that chooses among the slots slice 1 already
projects, and the `uniformAsc` direction fork. `Fixed` is `Argument`'s own `base`, so this builds on
slice 1's output rather than beside it.

An earlier draft of this plan put the cursor half in slice 2. It cannot go there: the reported
schema is `@asConnection` + `@defaultOrder`, so it *is* slice 1 on the connection arm, and a slice 1
that changed the page order without composing the seek key would ship a pagination bug on exactly
the coordinate the field report is about.

One interaction to get right, because a reviewer will look for it. A field carrying both
`@defaultOrder` and an `@orderBy` argument resolves to `OrderBySpec.Argument` with the
`@defaultOrder` as its `base`, so slice 1 cannot lower it even though slice 1 narrows the
`DEFAULT_ORDER` route away from its coordinate. That is safe rather than a hole: the rejection view
is keyed per route, the coordinate's `ORDER_BY_ARGUMENT` row survives slice 1, and a surviving row
fails the build. No wrong data ships in the gap; the field keeps failing until slice 2, on the route
that is genuinely still unlowered. The narrowing predicate cannot key on the resolved `OrderBySpec`
arm in any case, the view seeing declarations rather than resolutions, so per-route is both what is
available and what is correct.

The reported schema carries both routes, so it builds again only after slice 2. Say that plainly when
slice 1 ships rather than letting the field report look answered.

## The rejection this item narrows

A declared ordering at this coordinate fails the build today rather than being accepted in silence.
`intent_field_unlowerable_ordering` mints a `PARTICIPANT_FAN_OUT` row for every coordinate whose read
fans out per participant and which makes an ordering available, and `UnlowerableOrderings` turns each
into a deferred `ValidationError` naming the container, its participants and both remedies. That is
the fallback the field report asked for, it is a breaking change that already shipped (a schema
carrying `@defaultOrder` or an `@orderBy` argument on a multitable field no longer builds), and the
schemas it broke are the ones that were already getting wrong results.

**It narrows; it is not deleted.** The Backlog framing of this item said retiring the rejection was
"nothing but deleting the arm", and that is wrong in a way that would ship a regression. The arm is
`SELECT DISTINCT ... FROM graphitron_field_scope_table st WHERE st.basis = 'PARTICIPANT_TABLE'`, with no
root restriction and no cardinality restriction, so it covers *child* multitable coordinates as well
as roots, and `FieldUnlowerableOrderingTest.aMultitableChildFieldCarriesTheSameRow` pins that
deliberately: "a rule narrowed to roots would have left the child silent." This item lowers roots
only. Deleting the arm would return every child multitable coordinate to accepting a declaration and
discarding it, which is the exact defect the item exists to remove.

**And "not a root" is not the right predicate either.** `OrderByResolver.resolve` returns
`Ok(None)` unless the field is a list, a connection type, or carries `@asConnection`, while
`@defaultOrder` is `on FIELD_DEFINITION` with no cardinality restriction. So
`Query.thing: Applikasjon @defaultOrder(...)`, a single-valued multitable root, resolves `None` per
participant and lowers nothing, and a narrowing keyed on root-ness alone would stop rejecting it and
leave it silently accepted. Narrow on the conjunction the item actually lowers:

* **The coordinate sits on the `QUERY` root operation type**, reachable through
  `graphql_root_operation`, the same relation the `KEY_CAPTURE_SCATTER` arm uses to reach `MUTATION`.
* **And its read is list-shaped in the sense `OrderByResolver` uses**: a list, a connection type, or
  an `@asConnection` application.

That second conjunct has a trap. It is **not** `graphitron_field.is_list`: that column says whether
the type expression is a list, and the `@asConnection` expansion rewrites the field's type expression
to the minted connection type, so a connection root reads `is_list = FALSE`. A narrowing keyed on it
would keep rejecting exactly the reported coordinate. If no single fact spells "list-shaped read"
today, say so in the implementation commit and name it rather than open-coding a second spelling:
R677 phase 3's population needs the same predicate, and two spellings of it is the drift this whole
family keeps producing.

Which availability route to narrow is the third axis, and the view's own grain already carries it.
It is "one row per coordinate and availability route", and `UnlowerableOrderings.fanOutMessage`
already forks on `availableVia`, so the `DEFAULT_ORDER` route can leave the narrowed population in
slice 1 and the `ORDER_BY_ARGUMENT` route in slice 2. `PRIMARY_KEY_FALLBACK` is inert on this verdict
by construction and stays inert.

What comes out with the narrowing, per slice: the *declared list-shaped root* cases in
`FieldUnlowerableOrderingTest`, `UnlowerableOrderingsTest`, and
`UnlowerableOrderingRejectionPipelineTest`. Three cases stay, each for its own reason, and each
becomes load-bearing rather than incidental once the arm has a predicate:

* `aMultitableChildFieldCarriesTheSameRow` stays and keeps failing the build. It was a guard against
  over-narrowing; it becomes the arm's primary case.
* `aMultitableRootWithNoDeclarationIsQuiet` stays unchanged and needs no inversion. It asserts the
  rule does not fire with nothing declared, which is still true after this item, and the property it
  rests on (what is available at an undeclared multitable read is what is delivered) is still the
  property. An earlier draft of this plan said to invert it; that was wrong.
* A new case owes the single-valued declared root: it resolves no ordering, so it keeps its row.

## Documentation

Both paragraphs R677's rejection wrote become wrong when the lowering ships, and neither can be
simply deleted: each has to state the new, narrower truth.

* `docs/manual/how-to/sort-results.adoc`, "Sort across polymorphism". Currently: "That ordering is
  not configurable. `@defaultOrder` and `@orderBy` are not lowered onto the participant branches, and
  declaring either on such a field fails the build rather than being ignored." Replacement states
  that a root field lowers both, per participant, subject to every participant carrying the column;
  that a child multitable field still rejects; and that the synthetic key stays appended after the
  author's columns as a tiebreaker. That last point is a real deviation from this page's own rule two
  sections up ("The rewrite does *not* auto-append a PK tie-breaker; it is a schema-author
  responsibility"), and the deviation is load-bearing here, because two participants can hold equal
  values on every authored column and the cursor has to break the tie somewhere. Say so explicitly
  rather than leaving a reader to reconcile the two statements.
* `docs/manual/how-to/polymorphic-types.adoc`, "Constraints", the last bullet ("Declaring an ordering
  on a multi-table interface or union field is rejected at build time as a deferred capability").
  Becomes the root/child split.

The `@defaultOrder` and `@orderBy` reference pages need a check for statements that assume a single
backing table; whether they need an edit is for the implementer to read, not for this spec to assert.

## Tests

Named surfaces, so the Done gate has something to check the goal against rather than a green build.
The reported symptom is byte-identical pages under `ASC` and `DESC`, and no pipeline-tier assertion
can see that, so the execution tier is where this item is either delivered or not.

* **Pipeline tier, new `MultiTableOrderingLoweringTest`**, sibling to R363's
  `MultiTableFilterLoweringTest`: the classified shape rather than the SQL. The slot list is total
  over the participant set and in slot order, for interface and union, for a `Fixed` ordering and for
  an `Argument` one; `@order(primaryKey: true)` selects the existing synthetic key rather than
  minting a slot; and each agreement rule rejects with a message naming the disagreeing participant
  (absent column, positionally incompatible type, unequal `index:` arity, disagreeing direction or
  collation). Plus the single-valued declared multitable root, which lowers nothing and so keeps its
  rejection.
* **Membership**, on `ConditionMembershipTest`'s precedent: the operation-member census reports an
  `ORDER_BY` member at a lowered polymorphic root. Without this the census silently under-reports and
  nothing notices.
* **Execution tier, new `MultiTableOrderingExecutionTest`** in `graphitron-sakila-example`, beside
  `MultiTableFilterExecutionTest`, over the existing `AddressOccupant = Customer | Staff` fixture
  whose participants both carry `first_name` and `last_name`:
  - `@defaultOrder` alone returns rows in the declared column's order, not PK order.
  - `direction: ASC` and `direction: DESC` return reversed sequences. This is the report's exact
    symptom and the one assertion that cannot pass vacuously.
  - Rows of both participants interleave by the sort column. A seed that happens to keep every
    `Customer` row before every `Staff` row passes under the bug, so the seed has to interleave.
  - On the `@asConnection` form, paging the whole set in pages of N under a non-PK ordering visits
    every row exactly once, no skip and no duplicate, forward and backward. This is the assertion
    that catches a seek key composed out of step with the page order, which is the failure mode the
    slicing note above exists to prevent.
  - A cross-participant tie on every authored column still pages deterministically, which is what
    the appended synthetic key buys.
* **Composite-PK regression.** `Query.pagedItems` in the example schema is the composite-PK
  multitable connection coordinate, and the validator deliberately stays silent about the batched
  arm typing its sort field by the first PK column only, because promoting that truncation to a
  rejection would break the coordinate. It is therefore the natural regression anchor for "the
  ordering slots compose with a JSONB `__sort__` tiebreaker" rather than quietly interacting with the
  truncation.
* **No code-string assertions on generated bodies.** R363 held that line on the same emitter and the
  same fields; the ordering change is larger in the emitted text and correspondingly worse to pin by
  string.

## Roadmap entries

R677 is `Spec` (phases 1 and 2 shipped; phase 3 reopened 2026-09-22) and owns the ordering-invariant census. This item's landing removes root multitable
fan-out from that census's leak sites, and R677's body notes this coordinate leaks a step earlier
than the others (the model never resolves the ordering, so an invariant checked at the model-to-SQL
boundary would not see it). Once the slot exists, that note stops applying at the roots and starts
applying only at the children. Whoever lands either item second updates the other's body rather than
leaving both claiming the coordinate.

## Other solutions we've considered

**Assemble the stage-1 projection at runtime.** Instead of projecting every candidate column, emit
code that builds the projection list at request time from the chosen enum value, projecting only the
column actually sorted on. `MultiTablePolymorphicEmitter.buildPerTypenameSelect` already does exactly
this (`fields.add(...)`), so the pattern is in the tree, and it is narrower on the wire.

It is not the precedent it looks like. That site assembles a *selection projection* off one table; a
`UNION ALL` branch list is a type contract between branches, and making it a function of the request
means the union's shape varies per request. Two concrete consequences. First, `decodeCursor`'s
strict-arity blame contract becomes false: it documents that "any other token count is a forged,
corrupted, or stale-across-schema-change cursor this generator never emitted", and under runtime
assembly a legitimate client re-sending a cursor alongside a different `order` argument trips it, so
a client mistake and a forged cursor stop being distinguishable. Second, the per-slot `Field<T>` types
the codec converts through (`col.getDataType().convert(token)`) stop being build-time facts, and a
runtime-assembled projection would have to carry the chosen column's `DataType` out to the outer level
some other way.

The cost of the fixed form, stated honestly because a reviewer should see it weighed: every candidate
order column is projected in every branch on every request, so the stage-1 row widens with the size
of the `@order` enum. For a single-digit enum over columns of the same row that is cheap, and it is
the same family of trade-off the project already accepts when it drives SELECT lists off the
selection set on wide tables. Worth revisiting if projection width ever measures as a cost.

**Sort in the application after fetching.** What the reporter is doing as a workaround, and it is
wrong across pagination for the reason they observed: a page boundary is decided by the database
before the application sees the rows. Not viable for a connection at all.

**Reject at the coordinate instead of lowering.** Already shipped, by R677, and stated over read shape
rather than at these two coordinates. This item is what retires it at the roots.

**A note on collation, which is adjacent and not this item's.**
`OrderBySpec.ColumnOrderEntry` carries a `collation`, and `render/OrderByFragments.fixedSortParts`
emits `$L.$L.$L()` (alias, column, direction) without reading it. On that reading the `collate:`
argument documented on `@defaultOrder` resolves into the model and never reaches the emitted SQL, on
the single-table path too, which is R677's class of defect rather than this item's coordinate. This
spec does not claim it as scope. It matters here only because the slot design has to decide what to
do with `collation`, and "port what the single-table path does" and "implement what the directive
documents" are different instructions. Confirm the reading before picking one.

## Reviewer findings

### Round 1 (2026-09-23, Spec -> Ready, reviewer session 01JsnDyRt7sAyc7hLbtPxXXW)

Verdict: withhold. Four blocking findings, three on question two and one on question four's test
plan; four non-blocking. Question one passes: the goal reads on its own, the SDL minimal pair states
it precisely, and the outcome is reachable. The "What the tree already has" section checks out
nearly line for line (the per-participant `OrderBySpec` is resolved at `FieldBuilder.java:1208` and
dropped at `:1224`; the cursor codec is column-driven; `branchProjection` has exactly the two
stage-1 callers). Three in-passing corrections are in this commit: the fan-out arm reads
`graphitron_field_scope_table` (no `intent_field_scope_table` exists), R677 is at `Spec` rather than
`Ready`, and the `includeSortKey = true` callers are the two connection entry points.

**Finding 1 (question two). The carrier merges a projection slot with an order entry, and the
dedup the emission section asks for cannot be expressed in it.** `OrderingSlot` carries
`namedOrderName`, `direction`, `collation` and `uniformAsc` next to the per-participant column map,
yet "The slot set is the deduplicated, ordered union of the columns named across the field's named
orders". Once deduplicated, a slot serving `LAST_NAME` (ASC) and a `NAME_DESC` order over the same
column has two names and two directions and cannot hold either. `uniformAsc` is not a per-column
fact in the tree either: it is a component of `OrderBySpec.Fixed`, one per named order
(`OrderBySpec.java:64-67`). The model wants two levels: slots (ordinal, slot class, total
per-participant column map) and orders (per named order and for the base, a list of
slot-ref + direction + collation, plus `uniformAsc`). The spec also has to say what a slot's
identity is for dedup. With `index:` there is no SDL column name to compare, so the identity has to
be the whole per-participant column map. Two orders share a slot only when they agree on every
participant.

A consequence for the agreement rules: direction and collation cannot differ between participants.
Both come from the SDL directive, never from the database index (`OrderByResolver.java:236-241`,
`:268-287`), and `index:` / `primaryKey:` always carry a null collation. With the split above,
direction and collation live once on the order entry, so the third rule is unrepresentable by type.
The Tests bullet "disagreeing direction or collation" names a case no SDL can construct. Drop it,
or restate it as a compact-constructor invariant with no author-facing rejection.

**Finding 2 (question two). The primary-key fallback base is not addressed, and the fold cannot
recognise it.** A field with an `@orderBy` argument and no `@defaultOrder` resolves
`Argument.base` per participant to that participant's PK as a plain `Fixed` with `uniformAsc = true`
(`OrderByResolver.resolveDefaultOrderSpec`, `:103-113`), which is indistinguishable from an
authored `fields:` order. Both runtime paths reach it: a request that omits `orderBy`, and the goal's
own `occupantsOrdered` once its `@defaultOrder` is removed. As drawn, the fold would mint slots for
`customer_id` / `staff_id` that duplicate `__sort__`. It would then run them through positional type
agreement, which today's `__sort__` never asks of PKs: it is typed off `participants.get(0)`'s PK
class (`MultiTablePolymorphicEmitter.java:1075-1081`). So participants whose PK classes differ would
be rejected over an ordering nobody wrote. The "`primaryKey: true` is already delivered" paragraph
covers only the `@order` enum value. Say how the fold recognises every PK-shaped ordering (that
value, `@defaultOrder(primaryKey: true)`, and the implicit fallback base) and maps it to `__sort__`.
Either the resolver hands up provenance, or the fold compares against the participant's
`primaryKeyColumns()`. Pin with an execution case: `@orderBy` without `@defaultOrder`, request with
no `orderBy`.

**Finding 3 (question two). The narrowing edits an `intent_` view, and the spec does not engage
with that family's retirement.** `intent_field_unlowerable_ordering` is a view in the family whose
header in `graphitron-model.sql` (`:6554` onward) reads "DEPRECATED, THE WHOLE FAMILY" and states
the rule for anything landing there: do not. The narrowing adds two reads to that view: the `QUERY`
root through `graphql_root_operation`, and a list-shaped-read predicate. The spec's own note
anticipates minting that predicate as a new fact. That is exactly the filing question that sent
R677 phase 3 back to `Spec` on 2026-09-22, and R382 inherits it without mentioning it. State where
each part lives under the ownership section of `docs/architecture/explanation/fact-model.adoc`. My
recommendation: capture "list-shaped read" (list, connection type, or `@asConnection`) as a
`graphitron_` fact. The connection macro is the writer that knows it, and `graphitron_field` already
carries `is_list` / `item_non_null` beside it. Then argue explicitly whether tightening an existing
arm's `WHERE` counts as "landing here", or refile the arm. Coordinate with R677's re-spec, which is
deciding the same view's future. A cross-item note to hand over under "Roadmap entries": R677's
reopen section says phase 3's population should read `graphitron_field.is_list`. That is the column
this spec shows reads FALSE on an `@asConnection` root (`MacroCapture.mintField`, `:525-527`), so at
least one of the two items is wrong about it.

**Finding 4 (question two, with a test consequence). "Type-compatible" is left undefined, and the
emitter needs one slot class.** Each slot becomes `DSL.field(DSL.name(<alias>), <slot class>)`,
and `decodeCursor` converts through that field's `DataType`. The agreement rule therefore has to
produce a single Java class per slot, not merely something PostgreSQL will unify. `INT` against
`BIGINT` unifies in SQL but gives `Integer` against `Long`. Name the predicate. The natural one is
equality of `ColumnRef.columnClass` across participants: `VARCHAR` / `TEXT` pass as `String`, and
widening is rejected rather than guessed. Also name the Tests case that pins it.

**Non-blocking.**

* **The collation fork is now answerable, so answer it.** The reading holds. `collation` is
  populated (`OrderByResolver.java:279,285`) and read nowhere in main code, and there is no
  `collate(` call anywhere. Meanwhile `sort-results.adoc` § "Collation pitfalls" says it "is passed
  verbatim to the database". Pick port-or-fix in the body. I would port (emit no collation, matching
  the single-table path) and file the documented-but-dropped `collate:` as its own Backlog item,
  since no item covers it today.
* **The list root has no `__typename` tiebreaker today.** `buildStage1Block` orders by `__sort__`
  alone (`MultiTablePolymorphicEmitter.java:1307`); only the connection arms append `__typename`.
  "`__sort__` and `__typename` stay appended" is therefore true of the connection arm only. Say
  whether the list arm gains it.
* **The composite-PK anchor is right, but its stated reason is not.** The quoted validator comment
  (`GraphitronSchemaValidator.java:845-849`) calls `Query.pagedItems` the coordinate a rejection
  would break. But `pagedItems` is a root and goes through `buildRootConnectionFetcher`'s JSONB
  path; the first-PK truncation is on the batched arm. Anchor on "JSONB `__sort__` as tiebreaker
  after authored slots", which is what it exercises.
* **Nullable sort columns are outside what the tests can see.** The seek is jOOQ's `.seek()` with
  no NULLS handling anywhere in emission, and both fixture columns are `NOT NULL`. So the "every row
  exactly once" assertion holds only for non-null order columns. That is inherited from the
  single-table path, not this item's, but the goal should not read as promising more.
