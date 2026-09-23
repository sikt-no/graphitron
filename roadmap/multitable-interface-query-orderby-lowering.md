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
`@orderBy` argument over an `@order` enum. Today neither does. Until R677 this SDL built and returned
primary-key order whatever the client asked; since R677 it fails the build instead, with a deferred
rejection naming the container and its participants, because an ordering the read cannot honour is
refused rather than dropped:

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
  # this field fails the build today. Both participant tables carry last_name, so the ordering is
  # expressible; it is unimplemented, not impossible.
  occupants: [AddressOccupant!]! @defaultOrder(fields: [{name: "last_name"}])

  # The runtime half, equally refused today: OccupantSort resolves per participant against each
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
lands, the schema builds again and `occupants` returns `last_name` order, `direction: DESC` on
`occupantsOrdered` returns the reverse of `direction: ASC`, and paging through `occupantsConnection`
visits every row exactly once under the author's ordering (for order columns that hold no `NULL`,
as on every other paginated path), with `Customer` and `Staff` rows interleaved by `last_name`
rather than segregated by participant.

Scope is list-shaped reads on the two root variants, `QueryField.QueryInterfaceField` and
`QueryField.QueryUnionField`. Three neighbouring shapes stay rejected at build time rather than
becoming quietly accepted, and keeping them rejected is a real obligation of this item rather than a
side note: the child multi-table fields (`ChildField.InterfaceField` / `ChildField.UnionField`); a
single-valued multitable root, where the ordering resolver returns nothing to lower; and a root read
that the classifier routes somewhere other than the generated `UNION ALL`, such as a root `@service`
returning a multitable interface list (`QueryField.QueryServicePolymorphicField`, which carries no
ordering). See "The rejection this item narrows".

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
list-shaped roots the classifier reads through the generated `UNION ALL`.

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
participant's catalog for free by the same route, and a missing index returns `Rejected` rather
than throwing (`OrderByResolver.resolveIndexColumns` returns null, which both the `@defaultOrder`
and `@order` paths turn into a `Rejected`). One wrinkle bears on the arity rule below:
`JooqCatalog.findIndexColumns` drops an index column it cannot resolve rather than returning empty,
as its javadoc says it does. So an index with an unresolvable column arrives short, and the arity
rejection can fire on that rather than on a genuine difference between two indexes. The message
should name the columns on each side so the author can tell which.

**The cursor codec is already generic, and is not PK-typed.**
`ConnectionHelperClassGenerator` emits `encodeCursor(Record, List<Field<?>>)`, which serialises each
value through `val.toString()`, and `decodeCursor(String, List<Field<?>>)`, which converts each token
back through `col.getDataType().convert(token)` with strict arity. `pageRequest(first, last, after,
before, defaultPageSize, orderBy, extraFields, selection)` takes the sort fields and the cursor
columns as runtime lists and derives the seek from them. What is PK-typed is the emitter's build-time
`Field<T> sortField` local in `buildRootConnectionFetcher` (the `pkColumnClass` computation), not the
codec. Handing the codec a longer column list is therefore a change at the emitter only. The one
constraint the codec does impose is that each cursor column arrive as a `Field<?>` carrying the
right `DataType`: `decodeCursor` converts through it and the seek binds through it. On the
single-table path that is the table's own column, converter included. A field built from a Java
class alone carries no converter, which for a forced-type column binds the user type against the
database type. That is the failure R413 fixed on the DataLoader VALUES cells (the `init.sql`
comment above `converter_org` records it), and it is why a slot below is typed by a participant
column's own `getDataType()` rather than by a class.

**Neither seam is root-only.** `branchProjection(participant, tableAlias)` is called from exactly
two places, `buildStage1Block` and `buildStage1ConnectionBlock`, and the batched child paths carry
their own separate stage-1 projection builders. But `buildStage1Block` is not the list root's alone:
it has two callers, the root list fetcher and the inline single-cardinality child fetcher behind
`ChildField.InterfaceField` / `ChildField.UnionField`, which passes the participants' join paths and
delivers `records.get(0)` of the stage-1 order. And the connection arm has a *third* projection
site: `buildPerTypenameSelect(..., includeSortKey = true)` re-projects `__sort__` off the
participant's own table, because the per-edge cursor is encoded from the **stage-2** record, and
that method is shared with the batched child connection arm (the `includeSortKey = true` call sites
are `emitRootConnectionMethods` and `emitBatchedConnectionMethods`). So the real shape of the seam
is three sites that must agree, two of them shared with a child path whose behaviour this item must
not change. That is the reason the ordering reaches both shared methods as a value the child call
sites pass empty, rather than as a second boolean beside `includeSortKey`.

**A primary-key order is already delivered.** `@order(primaryKey: true)` resolves to the
participant's PK columns, which is exactly what `__sort__` already projects. So do
`@defaultOrder(primaryKey: true)` and the implicit fallback that becomes `Argument.base` on a field
with `@orderBy` and no `@defaultOrder`. None of them needs a new slot; each selects the existing one.
How the fold tells them apart from an authored `fields:` order, since none carries provenance, is in
the model section below. Worth pinning as a test rather than discovering in flight.

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

So state the field-level half once and hang the per-participant resolution off the columns it
projects. That takes two levels, not one, because two different things are being named. A *slot* is
a projected column: one alias in every `UNION ALL` branch, one Java class, one column per
participant. An *order* is what the SDL declared: a named order, or the base, as a list of entries,
each entry pointing at a slot with a direction and a collation. Slots are shared between orders, so
the direction, the collation, the order's name and `uniformAsc` cannot live on the slot: a slot that
serves both an ascending `LAST_NAME` order and a descending order over the same column would need
two of each. `uniformAsc` is a per-order fact in the tree already, a component of
`OrderBySpec.Fixed`. In the register of:

```
record PolymorphicOrdering(
    OrderingSurface surface,              // argument name / sortField / directionField / list-ness,
                                          //   or the Fixed-only arm
    List<OrderingSlot> slots,             // ordered; the index IS the slot's identity
    List<SlotOrder> namedOrders,          // declaration order; empty on the Fixed-only arm
    SlotOrder base)                       // the @defaultOrder, or the primary-key fallback
record OrderingSlot(
    int ordinal,
    String slotClass,                     // the one bound Java type every participant agrees on
    SequencedMap<ParticipantRef.TableBound, ColumnRef> columnByParticipant)
                                          // first entry's column types the emitted slot field
sealed interface SlotOrder {
    String name();                        // null on the base
    boolean uniformAsc();
    record OnSlots(String name, List<SlotEntry> entries, boolean uniformAsc)
    record OnSyntheticKey(String name, SortDirection direction, boolean uniformAsc)
}
record SlotEntry(int slotOrdinal, SortDirection direction, String collation)
```

The exact carving is the implementer's. What is load-bearing:

* The field-level half appears once.
* Each slot carries its own ordinal, and each entry refers to a slot by that ordinal, rather than
  letting three emission sites recompute either.
* The per-participant column is keyed on the `ParticipantRef.TableBound` object, not on a typename
  string.
* The compact constructors require every slot to be total over the participant set and every entry's
  ordinal to name a slot that exists.
* Direction and collation are stated once per entry, so a per-participant disagreement on either is
  unrepresentable by type rather than checked. They cannot disagree in the tree anyway: both come
  from the SDL directive, never from the database index (`OrderByResolver`'s `index:` arm builds
  every entry with a null collation and the directive-level direction). So there is no agreement
  rule for them to fail.

Then the cross-participant agreement holds by construction, and the capability accessor returns one
value rather than a list every consumer has to reconcile.

**A slot's identity is its whole per-participant column map.** With `fields:` there is a column
name to compare, but with `index:` there is not: each participant's index resolves to its own
columns. So dedup compares maps, not names. Two entries, in the same order or in different ones,
share a slot exactly when they resolve to the same column on every participant. Two entries that
agree on one participant and differ on another are two slots.

**`OnSyntheticKey` is how every primary-key-shaped order reaches `__sort__`, and the fold decides it
from the columns.** Three declarations resolve to a participant's primary key:
`@order(primaryKey: true)`, `@defaultOrder(primaryKey: true)`, and the implicit fallback.
`OrderByResolver.resolveDefaultOrderSpec` hands the fallback up as `Argument.base` whenever a field
has an `@orderBy` argument and no `@defaultOrder`. None of the three carries provenance: each
arrives as a plain `Fixed`, indistinguishable from a `fields:` order that names the same columns. So
the fold recognises the shape instead:

* An order becomes `OnSyntheticKey` when, on every participant, its entries are exactly that
  participant's `primaryKeyColumns()` in key order, all with one direction.
* That rule is sound whatever the declaration, because ordering by the key columns and ordering by
  `__sort__` are the same order. `__sort__` is the key column itself at arity 1, and a JSONB array
  of the key at composite arity, whose lexicographic ordering the emitter already relies on.
* An order that matches on some participants but not all is ordinary slots, and the agreement rules
  apply to it.
* A participant with no primary key resolves its fallback base to `None`. The fold treats that as
  `OnSyntheticKey` too and mints nothing. The validator's existing "multi-table interface/union
  fetchers require a primary key on every participant" rejection already fails that field, and a
  second message about the same absence would only compete with it.

Recognising the key this way is what keeps a field with `@orderBy` and no `@defaultOrder` from
minting slots that duplicate `__sort__`. Without it, those duplicate slots would go through a
positional type check that today's `__sort__` never asks of primary keys (it is typed off the first
participant's key class), and the check could reject a schema over an ordering nobody wrote.

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
resolutions of one named order can disagree on more than presence. The fold pairs entries
positionally, entry *i* of an order on one participant against entry *i* of the same order on every
other, and rejects on two counts:

* **Arity.** Two participants' same-named indexes can project different numbers of columns. The
  fixed-slot projection assumes one slot count for the whole field, so unequal arity has to reject.
* **Positional type, on both sides of the binding.** `UNION ALL` requires the i-th projected column
  to be type-compatible across branches, and what the emitter needs is stricter than what
  PostgreSQL will unify. Each slot is emitted as one field typed by the first participant's column
  `DataType` (see "Emission"), `decodeCursor` converts each token through it, and the seek binds
  through it against every branch's rows. So every participant has to agree with that column on
  two facts, and `ColumnRef.columnClass` states only one of them. It is `Field.getType()`, the Java
  type *after* any converter, so a forced-type `bigint` exposed as `String` and a plain `varchar`
  read the same there while their `UNION ALL` fails in the database. The rule is therefore
  equality of the pair `JooqCatalog.columnFactsOf` reports per column, `sqlType` and
  `bindingType`, which are the values the catalog capture already writes to `sql_column.sql_type`
  and `sql_column.binding_type`; no new fact is minted. `sqlType` is `DataType.getTypeName()`,
  which carries no length or precision, so `varchar(45)` against `varchar(50)` passes;
  `VARCHAR` against `TEXT` rejects on `sqlType` even though both bind to `String`, and that is
  accepted as the price of a rule stated over captured values rather than over PostgreSQL's
  unification table; `INT` against `BIGINT` rejects, widening being a choice the generator should
  not guess on the author's behalf; converted `bigint` against plain `varchar` rejects on
  `sqlType`. The agreed binding type is what `OrderingSlot.slotClass` carries.

  One residual is disclosed rather than closed. Two participants whose columns share both
  `sqlType` and `bindingType` through *different* converters are not distinguishable from any
  captured fact, and a cursor encoded off one participant's row would decode through the other's
  converter. Closing it would mean capturing a column's converter as a catalog fact, a change to
  the catalog family this item does not make; the case needs two distinct converters over one SQL
  type to one Java type on columns an author orders a union by, which no known schema has.

Direction and collation are not on this list. With the two-level carrier they are stated once per
entry, and in the tree they cannot differ per participant anyway (see the model section above).
Orders that `OnSyntheticKey` absorbs are not on it either: they project no slot, so there is
nothing to agree on.

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

The census has a second spelling that moves with it. `OperationMembers` is the leaf-local crosswalk
(`membersOf`, whose `QueryInterfaceField` / `QueryUnionField` arms call `polymorphicRootRead`), and
`OperationMemberMintPinTest` compares the minted census against it. Its `DECLARED_SHAPES` admits only
`CONDITION` as an optional kind on these two leaves, and `membersOf` validates every produced set
against that image. So `ORDER_BY` joins the optional set of both entries, `polymorphicRootRead`
reads the same capability accessor the census does, and the two spellings change in one commit;
minting from `OperationMemberRelation` alone fails the image fence or the pin.

`Kind.PAGINATE` is deliberately left alone; see the next section for why pagination is not this
item's fact to widen.

If the implementer does seal `OperationMember.OrderBy`, `membersOf`'s single-table arms change with
it, and the single-arm record is retired vocabulary
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

* The slot set is the deduplicated, ordered union of the slots the field's named orders and its base
  reference, keyed on the whole per-participant column map (see the model section). That dedup is a
  decision made once, in the model, and carried as the slot list's ordinals. No emission site
  re-derives it.
* `branchProjection` gains the slots. Stage 1 stays a static code block in both root paths.
  `buildStage1Block` receives the slots and the outer order as values, and the inline
  single-cardinality child call passes no slots and today's `__sort__`-only order, so that child's
  generated body is unchanged.
* `buildPerTypenameSelect` gains the slots too, because the per-edge cursor is encoded from the
  stage-2 record; without them `record.get(slotField)` is null and every cursor is a sentinel token.
  It is shared with the batched child connection arm, so the slots arrive as a list that is empty on
  the child call sites rather than as a second boolean beside `includeSortKey`.
* An `OnSyntheticKey` order needs no slot; it selects the existing `__sort__`, in its own direction.
* **A slot field is typed by a column, never by a class.** It is emitted as
  `DSL.field(DSL.name(<slot alias>), <first participant's table>.<COLUMN>.getDataType())`, the
  first participant being the first entry of the slot's sequenced column map, which is the
  `table.COL.getDataType()` idiom `emitter-conventions.adoc` § "Column value binding" prescribes for
  every bind. The converter rides the `DataType`, so `decodeCursor` turns a token back into the user
  type and the seek binds it as the database type; a class-typed field would bind a converted
  column's user type against its database type. The agreement rule above is what makes one
  participant's `DataType` right for every branch.
* **`__sort__` keeps its class typing, and that is a disclosed pre-existing gap, not this item's.**
  `buildRootConnectionFetcher` types it off the first participant's key class, so a converter-backed
  primary key on a multitable root connection already binds the user type in its seek today, with or
  without an authored ordering. This item leaves that statement as it is; the fixtures below avoid a
  converter-backed key so that they test the slots and not that gap.
* The outer order-by and seek lists become runtime-chosen. A new per-field helper, sibling to
  `TypeFetcherGenerator.buildOrderByHelperMethod`, switches over the same named orders at build time
  and returns the generated `OrderByResult` over the slot fields rather than over an aliased
  table's columns. The `uniformAsc` fork that decides whether the runtime
  `direction:` flips the spec carries over unchanged, being a property of the declaration and not of
  the table.
* **`orderBy` and `extraFields` move together, always.** `pageRequest` derives `seekFields` from
  `extraFields` positionally, so if the page order becomes `__ord0__, __sort__, __typename` while the
  seek key stays `__sort__`, the seek predicate no longer matches the sort prefix and pages skip or
  duplicate rows at every boundary. This is the single easiest way to ship a worse bug than the one
  being fixed, and it is why the cursor work cannot be deferred behind the runtime-dispatch slice.
* `__sort__` and `__typename` are appended, after the author's columns, as the deterministic
  tiebreaker. They are what makes a cross-participant tie on every authored column page
  consistently; dropping them because the author supplied an ordering would reintroduce the
  tie-boundary double-count the existing comment on `tieField` describes. The two arms differ
  today, so "stay appended" is true of the connection arm only. `buildRootConnectionFetcher`
  orders by `__sort__, __typename`, but `buildStage1Block` orders the list arm by `__sort__` alone.
  The root list arm therefore *gains* `__typename`, projected already by `branchProjection`, and it
  gains it on the undeclared path too, so both root arms compose their order in one way. On an
  undeclared list read the only visible change is at a cross-participant primary-key tie, where an
  order the database chose becomes a defined one. The inline single-cardinality child that shares
  `buildStage1Block` does not gain it: it is a child coordinate, and this item leaves child
  behaviour alone rather than improving it in passing.
* **The tiebreakers take part in the runtime direction flip.** Where the runtime `direction:` flips
  a `uniformAsc` order, `__sort__` and `__typename` flip with it, so `direction: DESC` is the exact
  reverse of `direction: ASC`, ties included. Everywhere else, a `Fixed` order and a named order that
  is not `uniformAsc`, they stay ascending. Either choice pages correctly, the seek following the
  sort list whatever its directions; this one is picked because the goal promises the reverse and
  the tie fixture below shares its seed with the reversal case. A list-valued `@orderBy` argument
  (`OrderBySpec.Argument.list()`) carries one direction per element and so has no single direction
  to reverse; there the tiebreakers stay ascending, and the reversal promise is the single-valued
  argument's.
* **Nullable order columns take whatever jOOQ's `.seek()` gives them, as on every other path.**
  The seek is `.seek(page.seekFields())` with no `NULLS` handling anywhere in emission, and
  `encodeCursor` writes a `NULL` as a sentinel token, so paging over an order column holding `NULL`
  inherits jOOQ's row-value semantics. This item neither changes nor promises that. The
  every-row-once guarantee in the goal is stated for non-null order columns, which is also all the
  execution fixture can show (`last_name` and `first_name` are `NOT NULL` on both participants).
* The slot alias spelling belongs with `SORT_COLUMN` and `PK_COLUMN_PREFIX` as an emitter constant
  composed with the carried ordinal. A `__`-prefixed string literal is fine here: the generated-
  sources lint's dunder rule permits synthetic SQL column aliases as literals and names `__sort__` as
  its exemplar, so no argument is owed. Whether the family as a whole should move to
  `no.sikt.graphitron.command.ReservedAliases`, which already owns the "writer alias equals reader
  alias" invariant and which `TYPENAME_COLUMN` already delegates to, is a tidy-up this item may take
  or leave; it is not load-bearing.

**Collation is carried and not emitted, which ports the single-table path rather than fixing it.**
The reading in "Other solutions we've considered" is confirmed. `ColumnOrderEntry.collation` is
populated by `OrderByResolver` from a `fields:` entry's `collate` and read nowhere in main code, and
there is no `collate(` call in emission at all. The manual's "Collation pitfalls" section
nonetheless says the value is passed verbatim to the database. That defect lives on every path, so
it is not this coordinate's to fix, and fixing it here alone would make multitable ordering the one
place `collate:` works. So `SlotEntry` carries `collation` and the helper does not emit it. When the
cross-path defect is fixed, the value is already where the multitable helper can read it, and that
fix owes this helper the same one-line change it owes `OrderByFragments.fixedSortParts`.

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
  `graphql_root_operation`, the relation `intent_mutation_routine_seat` joins to put the
  `KEY_CAPTURE_SCATTER` arm on `MUTATION`.
* **And its read is list-shaped in the sense `OrderByResolver` uses**: a list, a connection type, or
  an `@asConnection` application.
* **And the classifier routes it to the multitable arm**, not to an earlier one. The
  `PARTICIPANT_TABLE` basis is structural: `intent_field_participant_scope_table` mints a row for any
  field whose navigated type is a multitable container, whatever reads it. `classifyQueryField`
  reaches `QueryInterfaceField` / `QueryUnionField` only after four earlier routes have declined the
  field, in this order: `@service` (a multitable interface return becomes
  `QueryServicePolymorphicField`, which carries no ordering and delivers the service's order), a
  named type of `Node` (`QueryNodesField` / `QueryNodeField`), `@lookupKey` on an argument, and
  `@routine`. The first two classify cleanly and would lose their rejection under the two conjuncts
  above; the last two reject a multitable return on their own today, but the conjunct names them
  anyway, because the rule it states is "reaches the multitable arm" and not "happens to fail
  elsewhere". Each is already a captured coordinate-keyed fact: `graphitron_service_entry`,
  `graphitron_field.named_type = 'Node'`, `graphitron_argument_lookup_key_entry` and
  `graphitron_routine_entry`, the same directive-entry stratum the view's
  `graphitron_default_order_entry` and `graphitron_order_by_entry` route arms already read.

  Two of those four spellings are exact transcriptions only for a stated reason. The `Node` route
  tests `baseTypeName(fieldDef)` over the type expression the classifier works with, which after
  macro expansion is the connection's name for `[Node] @asConnection` or an authored
  `NodeConnection`. So those roots reach the multitable arm and are lowered, and the clause has to
  read `graphitron_field.named_type`, which carries the rewritten expression, not
  `graphitron_field_navigation.navigated_type_name`, which strips the connection and would keep
  rejecting them. The `@lookupKey` route fires through `LookupFacts.triggersFor`, which also
  counts an argument whose input type carries `@lookupKey` on a field, transitively. That half is
  not transcribed, for two reasons that each suffice. A coordinate the trigger fires on takes the
  lookup route, and at a root `LookupKeyDirectiveResolver.resolveAtRoot` refuses any return that is
  not table-bound, so a multitable root there fails the build on that route whatever this clause
  says. And the input-field site is retired: `graphitron_field_lookup_key_entry` is documented as
  such, and input-field classification refuses the directive outright. The argument entry is the
  live site, and naming it keeps the clause's statement of the rule honest without a recursive
  closure over input types that could only ever exclude coordinates that do not build.

That second conjunct has a trap. It is **not** `graphitron_field.is_list` alone: that column says
whether the type expression is a list, and the `@asConnection` expansion rewrites the field's type
expression to the minted connection type (`MacroCapture.mintField` sets `is_list` from the rewritten
expression), so a connection root reads `is_list = FALSE`. A narrowing keyed on it alone would keep
rejecting exactly the reported coordinate.

The predicate needs no new fact, because the store already holds both halves in the `graphitron_`
family. A list-shaped read is `graphitron_field.is_list = TRUE` **or**
`graphitron_field_navigation.basis = 'CONNECTION_ELEMENT'`. The navigation rung is "the field's
named type is structurally a connection", which is the same structural test
`BuildContext.isConnectionType` applies (an object type with `edges`, whose element type has
`node`). It covers an authored connection type and an `@asConnection` expansion alike, the
expansion's rewritten type being a connection by construction. That is `OrderByResolver`'s
three-way test spelled over captured relations. Pin the agreement between the two spellings with
one pipeline case per form (list, authored connection type, `@asConnection`), so that if they ever
drift apart the build fails instead of a coordinate going silent. Hand the same predicate to R677
phase 3 (see "Roadmap entries").

**Where the narrowing lives, given that the view's family is being retired.**
`intent_field_unlowerable_ordering` is a view in the `intent_` family, whose header in
`graphitron-model.sql` reads "DEPRECATED, THE WHOLE FAMILY" and states the rule for anything landing
there: do not. The ownership section of `docs/architecture/explanation/fact-model.adoc` says why:
a fact that is missing belongs in the family whose corpus it comes from, as early as it can be
written, and an `intent_` relation is what a pipeline grows when nobody wrote it there. The same
rule is what sent R677 phase 3 back to `Spec` on 2026-09-22, over two new `intent_` relations.
This item stays on the right side of it as follows:

* **No relation lands in `intent_`, and no new fact is minted anywhere.** The predicate reads only
  captured relations: `graphql_root_operation` in `graphql_`, and in `graphitron_` the field and
  navigation relations plus the three directive entries the route conjunct names. Every fact it
  needs is already written in the
  family whose corpus it comes from, which is the state the ownership rule asks for. That is why
  the plan spells the predicate over existing relations rather than capturing a "list-shaped read"
  column. Such a column would be a third spelling of a fact the two relations already state.
* **The edit tightens an existing arm, and says so.** The change is one exclusion on the
  `PARTICIPANT_FAN_OUT` arm, per route. The header's "do not" is about a fact arriving late in a
  family with no owner. Narrowing the population of a rule that already exists adds no fact and no
  late derivation, and it leaves the view's own filing exactly as undecided as it was. What it must
  not do is grow the view a new crossing: the added clause reads nothing from `intent_`.
* **The view's future home is R677's call, not this item's.** R677's re-spec is deciding where the
  verdict's parts are written, and this view is one of them. Whichever item lands second carries
  the exclusion across (see "Roadmap entries"). R382 does not refile the view, and does not wait
  for it to be refiled.

In the register of (the exact SQL is the implementer's):

```sql
-- appended to the view's outer query; the IN list is ('DEFAULT_ORDER') after slice 1,
-- and ('DEFAULT_ORDER', 'ORDER_BY_ARGUMENT') after slice 2
WHERE NOT (shape.verdict = 'PARTICIPANT_FAN_OUT'
           AND route.available_via IN ('DEFAULT_ORDER', 'ORDER_BY_ARGUMENT')
           AND EXISTS (query-root: graphql_root_operation ro
                        WHERE ro.operation = 'QUERY' AND ro.type_name = shape.type_name)
           AND EXISTS (list-shaped: graphitron_field f.is_list
                        OR graphitron_field_navigation nav.basis = 'CONNECTION_ELEMENT')
           AND NOT EXISTS (earlier route, at the coordinate: graphitron_service_entry,
                            graphitron_argument_lookup_key_entry, graphitron_routine_entry,
                            or graphitron_field f.named_type = 'Node'))
```

The exclusion has a Java twin, and the two have to agree. The classifier lowers an ordering at
exactly the coordinates the view stops rejecting, and the route conjunct is what makes that true: the
first two conjuncts alone describe every list-shaped root whose type is a multitable container, and
the classifier lowers only the ones that reach its multitable arm. If the view excludes a coordinate
the classifier does not lower, the declaration is accepted and discarded, which is the defect this
item exists to remove. The route conjunct is a transcription of `classifyQueryField`'s precedence,
so a new route ahead of the multitable arm owes it a clause; the kept-row cases below (one per
earlier route that classifies cleanly, beside the single-valued root and the child) are what fail if
it is not written.

Which availability route to narrow is the third axis, and the view's own grain already carries it.
It is "one row per coordinate and availability route", and `UnlowerableOrderings.fanOutMessage`
already forks on `availableVia`, so the `DEFAULT_ORDER` route can leave the narrowed population in
slice 1 and the `ORDER_BY_ARGUMENT` route in slice 2. `PRIMARY_KEY_FALLBACK` is inert on this verdict
by construction and stays inert.

What comes out with the narrowing, per slice: the *declared list-shaped root* cases in
`FieldUnlowerableOrderingTest`, `UnlowerableOrderingsTest`, and
`UnlowerableOrderingRejectionPipelineTest`. The cases below stay, move, or arrive, each for its
own reason, and each becomes load-bearing rather than incidental once the arm has a predicate:

* `aMultitableChildFieldCarriesTheSameRow` stays and keeps failing the build. It was a guard against
  over-narrowing; it becomes the arm's primary case.
* `aMultitableRootWithNoDeclarationIsQuiet` stays unchanged and needs no inversion. It asserts the
  rule does not fire with nothing declared, which is still true after this item, and the property it
  rests on (what is available at an undeclared multitable read is what is delivered) is still the
  property. An earlier draft of this plan said to invert it; that was wrong.
* A new case owes the single-valued declared root: it resolves no ordering, so it keeps its row.
* New cases owe the list-shaped roots routed away from the multitable arm: a root `@service`
  returning a multitable interface list with `@defaultOrder`, and a `Node`-typed list root with one.
  Both classify cleanly and neither lowers an ordering, so both keep their rows. The `Node` case is
  a plain list of `Node`; a second case, `[Node] @asConnection` with
  `@defaultOrder(primaryKey: true)`, pins the other side of the named-type spelling above: it
  reaches the multitable arm, is lowered, and loses its row.
* The route-grain pins move rather than go. `bothDeclarationsAtOneCoordinateAreTwoRows` and
  `twoOrderByArgumentsOnOneCoordinateAreTwoRows` sit on a root today and would lose their rows with
  the other root cases, taking the only statement of the view's one-row-per-route grain with them.
  Re-seat both on the child multitable coordinate, where the rows survive.

## Documentation

Both paragraphs R677's rejection wrote become wrong when the lowering ships, and neither can be
simply deleted: each has to state the new, narrower truth.

* `docs/manual/how-to/sort-results.adoc`, "Sort across polymorphism". Currently: "That ordering is
  not configurable. `@defaultOrder` and `@orderBy` are not lowered onto the participant branches, and
  declaring either on such a field fails the build rather than being ignored." Replacement states
  that a root field lowers both, per participant, subject to every participant carrying the column
  at the same SQL type and the same bound Java type (so a converter-backed column orders a union
  only against columns bound the same way);
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
  an `Argument` one. Two entries resolving to the same column on every participant share one slot,
  across named orders with different directions; two that agree on one participant only are two
  slots. Each of the three primary-key shapes (`@order(primaryKey: true)`,
  `@defaultOrder(primaryKey: true)`, and the fallback base of a field with `@orderBy` and no
  `@defaultOrder`) comes out `OnSyntheticKey` and mints no slot. Each agreement rule rejects with a
  message naming the disagreeing participant: an absent column, unequal `index:` arity, and an
  unequal (`sqlType`, `bindingType`) pair at one position. `INT` against `BIGINT` rejects on both;
  the converter case rejects on `sqlType` alone, a converter-backed `org_code_domain` column bound
  to `String` against a plain `varchar` of the same name, which is the case a `columnClass`-only
  rule would have admitted. The catalog has no plain column sharing a name with a converted one
  today, so that fixture table is the implementer's to add to `init.sql`.
  Direction and collation have no case, the two-level carrier making their disagreement
  unrepresentable. Plus the single-valued declared multitable root, which lowers nothing and so
  keeps its rejection.
* **Pipeline tier, the narrowed rejection**, in `FieldUnlowerableOrderingTest` and
  `UnlowerableOrderingRejectionPipelineTest`: one declared-root case per list-shaped form (a list, an
  authored connection type, `@asConnection`) that loses its row; the single-valued declared root,
  the child multitable field, a root `@service` multitable interface list and a `Node`-typed list
  root, which keep theirs; `[Node] @asConnection`, which loses its row; the two route-grain cases
  re-seated on the child coordinate; and after slice 1 only, a field carrying both
  `@defaultOrder` and an `@orderBy` argument whose `ORDER_BY_ARGUMENT` row survives. These are
  what hold the view's exclusion and the classifier's lowering to the same population.
* **Membership**, on `ConditionMembershipTest`'s precedent: the operation-member census reports an
  `ORDER_BY` member at a lowered polymorphic root. Without this the census silently under-reports and
  nothing notices, since `OperationMemberMintPinTest` only compares the census against
  `OperationMembers.membersOf` and would agree with both spellings left unchanged.
* **Execution tier, new `MultiTableOrderingExecutionTest`** in `graphitron-sakila-example`, beside
  `MultiTableFilterExecutionTest`, over the existing `AddressOccupant = Customer | Staff` fixture
  whose participants both carry `first_name` and `last_name`:
  - `@defaultOrder` alone returns rows in the declared column's order, not PK order.
  - `direction: ASC` and `direction: DESC` return reversed sequences. This is the report's exact
    symptom and the one assertion that cannot pass vacuously.
  - Rows of both participants interleave by the sort column. A seed that happens to keep every
    `Customer` row before every `Staff` row passes under the bug, so the seed has to interleave. The
    current seed does, by `last_name`: Brown (customer), Hillyer (staff), Johnson, Jones, Smith
    (customers), Stephens (staff), Williams (customer).
  - A field with `@orderBy` and no `@defaultOrder`, queried with no `orderBy`, returns the
    primary-key order it returns today. This is the fallback-base case, and it is the one that
    fails if the fold mints slots for the key instead of selecting `__sort__`.
  - On the `@asConnection` form, paging the whole set in pages of N under a non-PK ordering visits
    every row exactly once, no skip and no duplicate, forward and backward. This is the assertion
    that catches a seek key composed out of step with the page order, which is the failure mode the
    slicing note above exists to prevent.
  - A cross-participant tie on every authored column still pages deterministically, which is what
    the appended synthetic key buys. The current seed has no such tie (no customer shares a staff
    member's `last_name`), so this case owes a seeded row. With that row in the seed, the
    `ASC`/`DESC` case above still returns exact reverses, because the tiebreakers flip with the
    runtime direction (see "Emission"); the two cases share the seed on purpose.
  - The inline single-cardinality child field over the same union returns the row it returns today.
    It shares `buildStage1Block` with the root list arm, and this is the case that fails if the
    root's slots or tiebreaker leak onto it.
* **Execution tier, a converter-backed order column.** A union of `ConverterCampus` and a second
  table keyed on a plain `serial` and carrying an `org_code_domain` column (new in `init.sql`; the
  existing `ConverterOrg` is keyed on the converted column itself, which would put the test on
  `__sort__`'s disclosed gap instead of on the slots), ordered by `org_code` through
  `@asConnection`, paged to the end in pages smaller than the set, forward and backward. The first
  page passes under a class-typed slot; the second is where the seek binds the cursor value, so
  this is the case that fails if a slot is typed by its class rather than by its column.
* **Composite-PK regression.** `Query.pagedItems` in the example schema is the composite-PK
  multitable root connection (`PagedA` on `paged_a` and `PagedB` on `paged_b`, both keyed on
  `(k1, k2)`), and it runs through `buildRootConnectionFetcher`'s JSONB `__sort__` path. That makes
  it the natural regression anchor for "the ordering slots compose with a JSONB `__sort__`
  tiebreaker". The first-PK truncation the validator stays silent about is on the batched child
  connection arm, which this item does not touch. The validator comment
  (`GraphitronSchemaValidator`, the "Not enforced here" note beside the uniform-arity check) names
  `pagedItems` as the coordinate a rejection would break, which reads it as batched when it is a
  root. That comment is not this item's to correct.
* **No code-string assertions on generated bodies.** R363 held that line on the same emitter and the
  same fields; the ordering change is larger in the emitted text and correspondingly worse to pin by
  string.

## Roadmap entries

R677 is `Spec` (phases 1 and 2 shipped; phase 3 reopened 2026-09-22) and owns the
ordering-invariant census. This item's landing removes root multitable fan-out from that census's
leak sites, and R677's body notes this coordinate leaks a step earlier
than the others (the model never resolves the ordering, so an invariant checked at the model-to-SQL
boundary would not see it). Once the slot exists, that note stops applying at the roots and starts
applying only at the children. Whoever lands either item second updates the other's body rather than
leaving both claiming the coordinate.

Two further things pass between the items, because both concern R677's re-spec of phase 3:

* **The list-shaped predicate.** R677's reopen section says phase 3's population should read
  `graphitron_field.is_list`. On an `@asConnection` root that column reads `FALSE` (see "The
  rejection this item narrows"), so a population keyed on it alone would drop every macro-built
  connection. The predicate this item spells, `is_list` or a `CONNECTION_ELEMENT` navigation rung, is
  the one to hand over, so the two items keep one spelling. The route conjunct goes with it: a
  population keyed on "list-shaped multitable root" alone also takes in the `@service` and `Node`
  roots, which read no ordering at all.
* **The exclusion on `intent_field_unlowerable_ordering`.** If R677's re-spec refiles or dissolves
  that view before this item lands, the exclusion goes wherever the `PARTICIPANT_FAN_OUT` arm goes.
  If this item lands first, R677's re-spec inherits an arm with the exclusion already in it.

## Other solutions we've considered

**Assemble the stage-1 projection at runtime.** Instead of projecting every candidate column, emit
code that builds the projection list at request time from the chosen enum value, projecting only the
column actually sorted on. `MultiTablePolymorphicEmitter.buildPerTypenameSelect` already does exactly
this (`fields.add(...)`), so the pattern is in the tree, and it is narrower on the wire.

It is not the precedent it looks like. That site assembles a *selection projection* off one table; a
`UNION ALL` branch list is a type contract between branches, and making it a function of the request
means the union's shape varies per request. The concrete consequence is that the per-slot `Field<T>`
types the codec converts through (`col.getDataType().convert(token)`) stop being build-time facts,
and a runtime-assembled projection would have to carry the chosen column's `DataType` out to the
outer level some other way. An earlier draft also argued that runtime assembly breaks `decodeCursor`'s
strict-arity contract for a client re-sending a cursor under a different `order` argument. That is
not a difference between the two designs: the seek list is chosen per request under fixed slots too
("`orderBy` and `extraFields` move together"), and the single-table path's `OrderByResult` columns
already vary with the chosen order, so such a cursor trips the arity or conversion check either way.

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

**A note on collation, which is adjacent and not this item's.** Confirmed at review, and decided in
"Emission" above as a port: carried, not emitted. The note is kept for the reasoning.
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

*Author response (2026-09-23):* Took the two-level split. "Model: one field-level ordering" now
carries `OrderingSlot` (ordinal, `slotClass`, per-participant column map) apart from a sealed
`SlotOrder` (named or base, `uniformAsc`, entries pointing at slots by ordinal with direction and
collation). Slot identity is stated as the whole per-participant column map, with a paragraph of its
own. The direction and collation agreement rule is gone from the rules section and from Tests, and
the model section says why it is unrepresentable and why it could not fire in the tree anyway.

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

*Author response (2026-09-23):* Chose the structural comparison over resolver provenance: an order is
`OnSyntheticKey` when on every participant its entries are exactly that participant's
`primaryKeyColumns()` in key order under one direction. That is sound whatever the declaration, and it
leaves `OrderBySpec`, which the single-table paths share, untouched. It covers all three shapes, and
a PK-less participant's `None` base is absorbed with the rejection left to the validator's existing
check. New paragraph in the model section; "What the tree already has" and the emission bullet
updated; pipeline and execution cases added to Tests.

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

*Author response (2026-09-23):* Took a narrower answer than the recommendation, and "The rejection
this item narrows" argues it. No new fact is needed, because the predicate is already stated in
`graphitron_`: `graphitron_field.is_list` or `graphitron_field_navigation.basis =
'CONNECTION_ELEMENT'`, the latter being the same structural test as `BuildContext.isConnectionType`.
A captured "list-shaped read" column would be a third spelling of it. The edit is one per-route
exclusion on the existing arm, reading only `graphql_` and `graphitron_` relations. It adds no
relation and no crossing to `intent_`, and it leaves the view's filing to R677's re-spec. A new
subsection states this, with the exclusion sketched. The `is_list` hand-over to R677 and the "whoever
lands second carries the exclusion" note are under "Roadmap entries". I did not edit R677's body,
which is mid-re-spec in another session.

**Finding 4 (question two, with a test consequence). "Type-compatible" is left undefined, and the
emitter needs one slot class.** Each slot becomes `DSL.field(DSL.name(<alias>), <slot class>)`,
and `decodeCursor` converts through that field's `DataType`. The agreement rule therefore has to
produce a single Java class per slot, not merely something PostgreSQL will unify. `INT` against
`BIGINT` unifies in SQL but gives `Integer` against `Long`. Name the predicate. The natural one is
equality of `ColumnRef.columnClass` across participants: `VARCHAR` / `TEXT` pass as `String`, and
widening is rejected rather than guessed. Also name the Tests case that pins it.

*Author response (2026-09-23):* Taken as recommended. The "Positional type" rule is now "Positional
class", equality of `ColumnRef.columnClass`, with the reason stated (one `DSL.field` class per slot,
and `decodeCursor` converting through its `DataType`). The agreed class is carried as
`OrderingSlot.slotClass`. The `MultiTableOrderingLoweringTest` bullet names the `INT`/`BIGINT` reject
and the `VARCHAR`/`TEXT` pass.

**Non-blocking.**

* **The collation fork is now answerable, so answer it.** The reading holds. `collation` is
  populated (`OrderByResolver.java:279,285`) and read nowhere in main code, and there is no
  `collate(` call anywhere. Meanwhile `sort-results.adoc` § "Collation pitfalls" says it "is passed
  verbatim to the database". Pick port-or-fix in the body. I would port (emit no collation, matching
  the single-table path) and file the documented-but-dropped `collate:` as its own Backlog item,
  since no item covers it today.

  *Author response (2026-09-23):* Ported. The Emission section now says `SlotEntry` carries
  `collation`, the helper does not emit it, and why fixing it here alone would be wrong. The
  cross-path defect is left for its own item, which this spec does not name because none is filed
  yet.
* **The list root has no `__typename` tiebreaker today.** `buildStage1Block` orders by `__sort__`
  alone (`MultiTablePolymorphicEmitter.java:1307`); only the connection arms append `__typename`.
  "`__sort__` and `__typename` stay appended" is therefore true of the connection arm only. Say
  whether the list arm gains it.

  *Author response (2026-09-23):* It gains it, on the undeclared path too, so both arms compose
  their order one way. The only visible change on an undeclared list read is at a cross-participant
  primary-key tie. Stated in the tiebreaker bullet under Emission.
* **The composite-PK anchor is right, but its stated reason is not.** The quoted validator comment
  (`GraphitronSchemaValidator.java:845-849`) calls `Query.pagedItems` the coordinate a rejection
  would break. But `pagedItems` is a root and goes through `buildRootConnectionFetcher`'s JSONB
  path; the first-PK truncation is on the batched arm. Anchor on "JSONB `__sort__` as tiebreaker
  after authored slots", which is what it exercises.

  *Author response (2026-09-23):* Rewritten on that basis in Tests, noting that the validator
  comment misreads `pagedItems` and is not this item's to correct.
* **Nullable sort columns are outside what the tests can see.** The seek is jOOQ's `.seek()` with
  no NULLS handling anywhere in emission, and both fixture columns are `NOT NULL`. So the "every row
  exactly once" assertion holds only for non-null order columns. That is inherited from the
  single-table path, not this item's, but the goal should not read as promising more.

  *Author response (2026-09-23):* The goal's paging sentence is now scoped to order columns that
  hold no `NULL`, and a new Emission bullet says what nullable columns inherit and that this item
  neither changes nor promises it.

### Round 2 (2026-09-23, Spec -> Ready, reviewer session 01EVHBH2qyMRGpXKWqZoBiqR)

Verdict: withhold. Two blocking findings on question two and one small one on question one; three
non-blocking. Round 1's four findings are closed: the two-level carrier, the structural
`OnSyntheticKey` recognition, the reading of the narrowing against the `intent_` retirement, and the
`columnClass` rule all hold up against the tree. The rest of the plan's claims about code check out
(see the commit message for the list), and the design itself (fixed slots chosen at runtime, seek
and order composed together, slice 1 carrying the cursor) is one I would hand to an implementer.

**Finding 1 (question two). The narrowing predicate is wider than the population the classifier
lowers, so it would silently accept a declaration at a root the item does not lower.** The
`PARTICIPANT_TABLE` basis is structural: `intent_field_participant_scope_table` mints a row for any
field whose navigated type is a multitable container, whatever reads it, excluding only a resolved
`@mutation(table:)`. A root `@service` field returning a multitable interface list satisfies both
conjuncts the spec names (it sits on `QUERY` and `is_list` is true), classifies as
`QueryField.QueryServicePolymorphicField`, and that record has no ordering component and
`emitServiceMethods` delivers the service's order. Today its `@defaultOrder` fails the build through
the fan-out arm. After this item's exclusion it would build and be ignored, which is the defect the
item exists to remove, at a neighbour the "keeping them rejected is a real obligation" paragraph does
not list. `Query.nodes` over a `Node` interface with no `@table` looks like the same shape through the
same relation; check it rather than take my reading. So the sentence "The classifier lowers an
ordering at exactly the coordinates the view stops rejecting" is false as the predicate is spelled.
What would satisfy it: a third conjunct stating that the read is the generated stage-1 union,
spelled over captured relations as the other two are (`graphitron_service_entry` at the coordinate
looks sufficient for `@service`), the same reasoning applied to every other root leaf that can
navigate to a multitable container, and a kept-row case for a root `@service` multitable list beside
the single-valued and child cases in "Tests". Hand the extra conjunct to R677 along with the
list-shaped predicate.

*Author response (2026-09-23):* Taken. "The rejection this item narrows" gains a third conjunct,
"the classifier routes it to the multitable arm", transcribing `classifyQueryField`'s precedence:
no `@service`, no `Node` named type, no argument `@lookupKey`, no `@routine` at the coordinate, each
read off an existing coordinate-keyed entry relation. `Query.nodes` does belong: the `Node` route is
keyed on the named type and classifies cleanly as `QueryNodesField`. The "exactly the coordinates"
sentence now says why the route conjunct is what makes it true, the SQL sketch carries the clause,
the scope paragraph in the Goal lists the routed-away root as a third kept neighbour, kept-row cases
for the `@service` and `Node` roots are in "Tests", and the conjunct is handed to R677 beside the
list-shaped predicate.

**Finding 2 (question two). The stage-1 seam is not clean: `buildStage1Block` is shared with a child
path.** "What the tree already has" says `branchProjection`'s two callers are `buildStage1Block`
(list root) and `buildStage1ConnectionBlock` (connection root), and that the child paths carry their
own stage-1 builders. The two callers are right, but `buildStage1Block` itself has two callers: the
root list fetcher, and the inline single-cardinality child fetcher for `ChildField.InterfaceField` /
`ChildField.UnionField` (the `buildStage1Block(participants, participantJoinPaths, Map.of(), ...)`
call in `MultiTablePolymorphicEmitter`, whose delivered record is `records.get(0)` of the stage-1
order). Two consequences for the plan. The slots have to reach `buildStage1Block` as a value that is
empty on the child call, which is the treatment the spec already gives `buildPerTypenameSelect`,
so the seam paragraph's "one of which this item must not change the behaviour of" covers two shared
sites, not one. And the Emission bullet's "the list arm gains `__typename` ... on the undeclared path
too" also changes the order the single-cardinality child picks its row from, at a cross-participant
primary-key tie. That is probably harmless, since it makes an arbitrary pick deterministic, but it is
a behaviour change on a child coordinate that the item says it leaves alone. Either scope the
tiebreaker to the root call or say that the child gains it and why that is acceptable.

*Author response (2026-09-23):* Scoped to the root. The seam paragraph is rewritten as "Neither seam
is root-only", naming both callers of `buildStage1Block`. The Emission section threads the slots and
the outer order into `buildStage1Block` as values, with the child call passing no slots and today's
`__sort__`-only order, so the child's generated body is unchanged; the tiebreaker bullet says the
child does not gain `__typename` and why. An execution case pins the child's delivered row.

**Finding 3 (question one, small). The Goal describes the behaviour before R677.** "Today neither
does, and neither is rejected as ignored, so this SDL builds and returns primary-key order whatever
the client asks", and the SDL comment "it is why these rows come back in primary-key order", both
contradict the Problem section and the tree: `UnlowerableOrderings.rejectionOf` mints a deferred
rejection on every `PARTICIPANT_FAN_OUT` row, and `UnlowerableOrderingRejectionPipelineTest`
pins that the reported schema fails the build. A consumer's change is therefore from a stopped
build to a sorted result, not from primary-key order to a sorted result. Restate the before-state in
the Goal. The after-state is well stated as it stands.

*Author response (2026-09-23):* The Goal now says the SDL built and returned primary-key order until
R677 and fails the build since, and that the schema builds again when this lands. The two SDL
comments that said "lost" and "primary-key order" say "refused" and "fails the build".

**Non-blocking.**

* **The census has a third spelling the plan does not name.** `OperationMembers` is the leaf-local
  projection that `OperationMemberMintPinTest` compares the minted census against, and its
  `DECLARED_SHAPES` admits only `CONDITION` as an optional kind on `QueryInterfaceField` and
  `QueryUnionField`, validated as an image fence inside `membersOf`. Minting `ORDER_BY` from
  `OperationMemberRelation` alone fails that fence or that pin. `polymorphicRootRead` and both
  shape entries move with it, and a sealed `OperationMember.OrderBy` would touch this switch too.
  The build catches it, so this is only a note on the census section's site list.

  *Author response (2026-09-23):* Named in the census section: `OperationMembers.membersOf` /
  `polymorphicRootRead` and both `DECLARED_SHAPES` entries change in the same commit as the census,
  and the membership test's reason now says why the mint pin cannot stand in for it.
* **Which direction the appended tiebreakers take is unstated.** The Goal says `direction: DESC`
  returns the reverse of `direction: ASC`. That holds only while the authored columns hold no tie,
  unless `__sort__` and `__typename` flip with the effective direction. The seeded tie row the
  Tests section adds shares its fixture with the reversal case, so the answer decides whether those
  two cases can both pass. Either direction pages correctly, so this is a decision to state, not a
  defect.

  *Author response (2026-09-23):* Decided: the tiebreakers join the runtime flip of a `uniformAsc`
  order and stay ascending everywhere else. New Emission bullet; the tie case in "Tests" now says it
  shares the seed with the reversal case on purpose.
* **One argument against runtime assembly applies to the chosen design too.** Under fixed slots the
  seek list is still chosen per request ("`orderBy` and `extraFields` move together"), and on the
  single-table path the `OrderByResult` columns already vary with the chosen order. So a cursor
  re-sent with a different `order` argument trips `decodeCursor`'s arity or conversion check under
  either design. The rejection of runtime assembly stands on the second consequence (the per-slot
  `DataType` as a build-time fact), which is enough.

  *Author response (2026-09-23):* Agreed. The cursor-arity argument is withdrawn in "Other solutions
  we've considered", with the reason it is no difference between the designs; the rejection rests on
  the per-slot `DataType`.

### Round 3 (2026-09-23, Spec -> Ready, reviewer session 01JB5Xxi1e9eeD6znsabL1H9)

Verdict: withhold. One blocking finding on question two; four non-blocking. Question one passes:
the change for a consumer is that a root `Query` list or connection over a multitable interface or
union carrying `@defaultOrder` or an `@orderBy` argument stops failing the build and returns rows
sorted by the declaration across participants, with cursor paging consistent under that order,
while child, single-valued and routed-away roots keep failing. Rounds 1 and 2 are closed, and the
plan's claims about the tree check out (see the commit message).

**Finding 1 (question two). The slot's type and the agreement rule are both stated over
`ColumnRef.columnClass`, which is the Java type after a converter, not the column's SQL type.**
`ColumnRef` documents `columnClass` as `Field.getType().getName()`, so a forced-type column reads as
its user type. The sakila catalog carries exactly that shape on purpose: `org_code_domain` is a
`bigint` domain exposed as `java.lang.String` through `OrgCodeStringConverter`, mirroring a
consumer's `kode_numerisk_domain`, and `converter_org` / `converter_campus` both carry it. Two
consequences, both at runtime and neither visible to the planned fixture, whose order columns are
plain `varchar`:

* The agreement rule admits a union the database refuses. A converted `bigint` column on one
  participant and a `varchar` column on another both read `java.lang.String`, so "equality of
  `ColumnRef.columnClass`" passes and the stage-1 `UNION ALL` fails in PostgreSQL (UNION types
  `bigint` and `character varying` cannot be matched). The rule is presented as the classifier
  guarantee every build-time-typed slot rests on; for these columns it guarantees nothing.
* With the same converter on every participant the union holds, but a slot emitted as
  `DSL.field(DSL.name(<slot alias>), <slot class>)` carries a DataType derived from `String.class`,
  with no converter. `decodeCursor` converts the token through that DataType and the seek binds the
  value as a string against the domain-typed column in `pages`, so the first page renders and any
  `after:` / `before:` page should fail with the "operator does not exist: org_code_domain =
  character varying" family that the `init.sql` comment above `converter_org` records. The in-tree
  convention is `emitter-conventions.adoc` § "Column value binding": bind through the column's own
  `getDataType()`, not through a class.

What would satisfy it: state what a slot is typed by in emitted code (a participant column's own
`getDataType()` is the in-tree idiom), and restate the agreement rule over what that choice needs
equal on every participant, which is at least the SQL type and the converter and not only the user
class, including where the build-time half reads that from, since `ColumnRef` carries names only by
design. Pin it with a pipeline case (converted against plain on one slot rejects) and an execution
case that pages past the first page under a converter-backed order column; a union over
`ConverterOrg` and `ConverterCampus` ordered by `org_code` is a ready fixture. The existing
`__sort__` is typed the same way, off the first participant's key class. Whether this item changes
that too is the author's call, but it should be a stated choice rather than inherited silently.

*Author response (2026-09-23):* Taken. The agreement rule is now "Positional type, on both sides of
the binding": equality of the (`sqlType`, `bindingType`) pair `JooqCatalog.columnFactsOf` reports,
the values `sql_column` already captures, so no fact is minted. It rejects converted-against-plain
on `sqlType`; `VARCHAR` against `TEXT` now rejects too, accepted as the price of a rule over
captured values. Emission gains a bullet typing every slot field by the first participant's column
`getDataType()`, citing the "Column value binding" convention, and "What the tree already has"
says why a class is not enough. `__sort__` stays as it is and is disclosed as a pre-existing gap,
so the fixtures avoid a converter-backed key. The one residual, distinct converters sharing both
facts, is disclosed rather than closed. Tests gain the converter rejection in
`MultiTableOrderingLoweringTest` and an execution case paging past the first page over a new
`org_code_domain` table beside `ConverterCampus`; the manual's replacement sentence names the
binding condition.

**Non-blocking.**

* **The `Node` route is spelled on the navigated type; the classifier tests the named type.**
  `classifyQueryField` routes on `baseTypeName(fieldDef).equals("Node")` over the type expression
  it works with, which for `[Node] @asConnection` or an authored `NodeConnection` is the connection's
  name. Those roots fall through to the multitable arm and are lowered, while
  `graphitron_field_navigation.navigated_type_name = 'Node'` keeps rejecting them. It fails closed
  (the build stops, no wrong data), but "exactly the coordinates the view stops rejecting" is false
  there. `graphitron_field.named_type`, which carries the rewritten expression, is the transcription.

  *Author response (2026-09-23):* Taken. The route conjunct and the SQL sketch read
  `graphitron_field.named_type = 'Node'`, with a paragraph saying why the navigated type is the
  wrong one, and a `[Node] @asConnection` case that loses its row pins the difference.
* **The `@lookupKey` route has an input-field half.** The classifier's trigger is
  `LookupFacts.triggersFor`: an annotated argument, or an argument whose input type carries
  `@lookupKey` transitively. The conjunct names only `graphitron_argument_lookup_key_entry`. Both
  shapes reject a multitable return on their own today, so nothing goes silent, but the conjunct
  claims to state the rule, and this is the half it omits.

  *Author response (2026-09-23):* Kept out of the clause, with the reason now in the body: a
  coordinate the trigger fires on takes the lookup route, where `resolveAtRoot` refuses a
  non-table-bound return, and the input-field site is retired and refused at input-field
  classification. A recursive closure over input types could only exclude coordinates that do not
  build.
* **Tiebreaker direction under a list-valued `@orderBy` argument is unstated.**
  `OrderBySpec.Argument.list()` admits `[OccupantOrderBy!]`, whose elements carry their own
  directions, and "the tiebreakers flip with a `uniformAsc` order" is defined for one element.
  Either choice pages correctly; state one.

  *Author response (2026-09-23):* Stated: under a list-valued argument the tiebreakers stay
  ascending, and the reversal promise is the single-valued argument's. Added to the tiebreaker
  bullet in "Emission".
* **Re-seat the route-grain pins rather than delete them.** The root cases that come out of
  `FieldUnlowerableOrderingTest` include `bothDeclarationsAtOneCoordinateAreTwoRows` and
  `twoOrderByArgumentsOnOneCoordinateAreTwoRows`, which pin the view's per-route grain. Moving them
  onto the child coordinate keeps that pin once the root rows are gone.

  *Author response (2026-09-23):* Taken, as a bullet in "The rejection this item narrows" and in
  the narrowed-rejection test list.
