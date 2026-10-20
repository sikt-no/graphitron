---
id: R1069
title: "A single-row child whose projected values are all null resolves to null, as v9 resolved it"
status: Spec
bucket: bug
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-10-19
last-updated: 2026-10-20
---

# A single-row child whose projected values are all null resolves to null, as v9 resolved it

## Goal

A nullable field returning one object resolves to `null` when that object would hold nothing: every
column it reads is `null`, every single reference in it finds no value, and every list in it is
empty. This is how Graphitron 9 resolved it, so a client that reads `postadresse != null` as "has an
address" keeps working across the upgrade. The rule is the same for a child with its own table
(inline or `@splitQuery`) and for a nested object without one, and it is decided over every field of
the type, whatever the query selects. A field declared non-null always resolves to an object, as
today.

Three shapes from sis, the consumer whose Graphitron 9 against Graphitron 10 differential test
found this (2026-10-19), all selected by production operations:

```graphql
type PersonProfil @table(name: "PERSON") {
    postadresse: Postadresse @splitQuery            # a child with its own table
}
type Postadresse @table(name: "STUDENT") {
    # four fields over nullable *_SEMADR columns of STUDENT
}

type Termin @table(name: "TERMIN") {
    studiesemesterPeriode: StudiesemesterPeriode    # a nested object without a table
}
type StudiesemesterPeriode {
    fraDato: Date @field(name: "DATO_START_SEMESTER")
        @reference(path: {table: "SEMESTERREGISTRERINGSTERMIN"})
    tilDato: Date   # the same shape, over another column of the same table
}

type ProgramStudierett @table(name: "PROGRAMSTUDIERETT") {
    phd: ProgramStudierettPhdData                   # a nested object holding a list
}
```

| Shape | Data | Graphitron 9 | Today | After |
|---|---|---|---|---|
| `postadresse { __typename }` | the `STUDENT` row exists, all four columns `null` | `null` | `{__typename: "Postadresse"}` | `null` |
| `studiesemesterPeriode { fraDato tilDato }` | `TERMIN`'s join column is set, no `SEMESTERREGISTRERINGSTERMIN` row matches | `null` | `{fraDato: null, tilDato: null}` | `null` |
| `phd { beregnetSluttdato }` | a study right that is not a PhD: column `null`, references find nothing, list empty | `null` | `{beregnetSluttdato: null}` | `null` |

Measured in sis's local data: `postadresse` takes the object form for 1543 of 2048 students, and
`studiesemesterPeriode` differs on 46 of 50 rows of a production operation's shape.

## The decision: absence is judged by value, and nullability is the declaration

The Backlog question was whether "absent" is judged by the join (the row exists, so the object
exists: today's reading) or by the values (Graphitron 9's `nullOnAllNull`, which collapsed a row of
nulls to `null`), and whether that should be a default or a declared choice per field.

Absence is judged by value, by default, for every nullable single-row object. The reasons:

- **Graphitron 9 did it everywhere.** Its generator wrapped every object row, with or without
  `@table`, inline or `@splitQuery`, in `Functions.nullOnAllNull` (`FetchDBMethodGenerator.createMapping`
  on the `graphitron-9-head` branch), built from every field of the type rather than the selection.
  Every migrating consumer was written against that reading; a per-field opt-in would make each of
  them find and annotate every such field to get their old answers back.
- **The schema already has the declaration.** A non-null field (`postadresse: Postadresse!`) always
  resolves to an object; a nullable one may resolve to `null`. An author who wants "the row exists"
  to mean "an object" declares the field non-null. No new directive is needed, and none is added.
- **It conflates "no row" with "a row with no values", and that is the point.** To a client both
  are "nothing to show". A nested object without a table has no row of its own to be present,
  which is why R1026 already chose the value reading there; extending the same reading to a child with a
  table gives one rule rather than two.

The rule stays selection-independent, as R1026 made it: `postadresse { __typename }` and
a selection of its columns agree on whether `postadresse` is `null`. That is where it differs from
Graphitron 9, which skipped unselected subquery fields under its `optionalSelect` setting and then
counted them as `null`. The one place this shows is a list, covered below.

## The presence rule

A **presence leaf** is one value an object carries that can prove the object non-empty. An object
is absent when every leaf is absent; a nullable single field over an absent object resolves to
`null`. The leaves of a type, decided over every field of the type:

| Field in the type | Leaf | Absent when |
|---|---|---|
| A column (`@field`), a `@nodeId` | own-row columns | the column is `null` (for `@nodeId`, any key column is `null`) |
| A nested object without a table | its own leaves, merged | (recursive) |
| A single `@reference`, scalar or object, keyed or `condition:`-joined, inline or `@splitQuery` | a probe | no referenced row has a value (below) |
| A list of a `@table` type taking no arguments, inline or `@splitQuery` | a probe | the list is empty |
| A list taking arguments, a connection, an interface or union, an `@externalField`, a `@service` field, a `@pivot` child, a same-table child | none: blocks the collapse | (the object always resolves to an object) |

A **probe** is a correlated `EXISTS` over the field's own join path and filters, the ones its fetch
already applies, so a probe can never disagree with the value the field would resolve to. For a
single object reference the probe asks for a referenced row in which some column leaf of the target
type is non-null; when the target type has no column leaves, or holds probes or blockers of its
own, the row's existence alone counts. That last step errs toward keeping an object, never toward
dropping a value, and it stops the rule from recursing through the schema.

What changes against today's rule (`ChildField.NestingField.SubtreePresence.over`):

- A single reference now counts by its value, not by its first hop's join columns. Today a set join
  column whose target row is missing keeps the object (the `studiesemesterPeriode` shape); a probe
  answers it. A `condition:`-joined or lateral reference, which blocked, now probes too.
- A list taking no arguments, and a `@splitQuery` field, no longer block: they probe. A list
  counts as absent when it is empty. This is a deliberate rule rather than a restoration:
  Graphitron 9 gave a selected empty list as `[]`, which kept the object, and an unselected one as
  `null`, which did not. "Empty counts as nothing" is the selection-independent reading that agrees
  with Graphitron 9 wherever the list was not selected, which is the `phd` shape. A list with
  arguments still blocks, because its emptiness depends on the query.
- `@splitQuery` is a delivery choice and does not change the answer: a `@splitQuery` reference or
  list inside an object probes exactly as its inline form does. The probe runs in the parent's
  statement, guarded as below.
- A child with its own table now has a contract at all. Today it is `null` only when no row matches
  (or, for `@splitQuery`, when its key is `null`).

`@pivot` children keep their opposite convention (a record of null slots, never a null record,
pinned by `BatchedChildExecutionTest.pivotChild_eachParentGetsItsOwnRecord_rowLessParentGetsNullSlots`).
A non-null field keeps `AlwaysObject`.

A type with a `@nodeId` field, or any column that is never `null` on an existing row such as its
primary key mapped to a field, never collapses while its row exists. That matches Graphitron 9,
whose `nullOnAllNull` saw the same non-null value.

## User documentation (first-client check)

`docs/manual/how-to/map-types-to-tables.adoc` gains a short section, "When a single object resolves
to `null`", which the "Grouping columns into a nested object" and "Returning the parent's own row"
sections then point at instead of stating their own rule:

> A nullable field that returns one object resolves to `null` when the object would hold nothing:
> every column it reads is `null`, every single `@reference` in it finds no value, and every list in
> it is empty. A child with its own `@table` also resolves to `null` when no row matches. This is
> decided over all of the type's fields, whatever the query selects, so `address { __typename }`
> and `address { street }` agree on whether `address` is `null`. `@splitQuery` does not change the
> answer.
>
> ```graphql
> type Customer @table {
>     address: Address @splitQuery   # null when the row is missing or every column of it is null
>     billing: Address!              # always an object; an error if no row matches
> }
> ```
>
> Declare the field non-null to always get an object. A type holding a list that takes arguments, a
> connection, an interface or union, an `@externalField` or a `@service` field always resolves to an
> object, because whether it holds anything depends on the query or on code outside the database. A
> type with a `@nodeId` field, or a field reading a column that is never `null`, never resolves to
> `null` while its row exists.

`docs/manual/how-to/migrating-from-legacy.adoc` replaces the "Behavior divergence: nested objects
holding a fetched field" section and the matching "Things that didn't change" bullet:

> A nullable field returning one object resolves to `null` when the object would hold nothing, as in
> Graphitron 9, for a nested object without `@table` and for a child with its own table alike; see
> [Map types to tables] under "When a single object resolves to `null`". Two cases differ:
>
> - Graphitron 9 decided from the fields it fetched, so with `optionalSelect` an unselected list or
>   reference counted as `null`, while a selected empty list kept the object. The rewrite decides
>   over every field whatever the query selects, and counts an empty list as nothing.
> - A type holding a list that takes arguments, a connection, an interface or union, an
>   `@externalField` or a `@service` field always resolves to an object.
>
> A client that tested such a field for `null` should test the fields inside it instead.

The docs read simply with one rule stated once and two exceptions named, which is the check this
draft exists to pass.

## Implementation

**Model: one presence rule, lifted out of `NestingField`.** `PresenceLeaf`, `SubtreePresence` and
`NullContract` move from `ChildField.NestingField` to a top-level home in `rewrite.model` (a
`Presence` file holding the three), since they now serve three field kinds. `PresenceLeaf` becomes
sealed, because its kinds carry different data and evaluate differently:

- `Columns(List<ColumnRef>)`: own-row columns, absent when any is `null`.
- `Probe(ChildField field, List<JoinStep> joinPath, ...)`: carries the same join and filter values
  the field's fetch plan is built from, plus the target's column leaves for an object reference, so
  the probe renderer reuses the fetch's join rendering rather than rebuilding it.

`SubtreePresence.over` switches over the field kinds per the table above. `NullContract.of` keeps its
shape; `CollapsesWhenAbsent` holds the sealed leaves and exposes `presenceColumns()` (the `Columns`
leaves) and `probes()` separately. `NestingField.referenceLeaf` is retired.

**Where the contract is computed.** A nested object's leaves depend on the edge, since its fields
are classified against the host's table, so `NestingField.nullContract()` stays a method on the
field. A child with its own table has leaves that depend on its return type alone, so they are one
fold on the schema keyed by type: `GraphitronSchema.sameRowNullContract` generalises to a
`presenceOf(typeName)` fold, and the contract at a use site is `NullContract.of(wrapper, presence)`.
`TableField`, `BatchedTableField` and `SameRowTableField` gain no presence component. The projection
side (`plan/ProjectionCommands`) and the fetcher side (`FetcherEmitter`) both read the fold, as
`SameRowTableField` already does.

**Projection.** Per collapsing field, its `Columns` leaves are projected, plus one probe column when
it has probes, aliased so its fetcher can name it statically:

```sql
CASE WHEN c1 IS NULL AND c2 IS NULL
      AND (EXISTS (...probe 1...) OR EXISTS (...probe 2...)) THEN 1 END
```

rendered through jOOQ's `DSL.when(...)` so it is dialect-neutral (no boolean select column, which
Oracle before 23 lacks). The guard keeps the probes from running when a column already proves the
object present. Where each lands:

- A nested object without a table: as today, through `ParentRowDemand` and
  `CallWrap.Splice.callerDemand` into the anchor's projection, with the probe column added to the
  demand. The alias is derived from the nested type and field name, which is what a shared nested
  type's single fetcher can read.
- An inline single `@table` child (`TableField`): a presence slot on `CallWrap.Multiset`, rendered
  inside the correlated single-row multiset beside `<Child>.$project(...)`, as `CallWrap.SameRow`
  carries its presence columns today.
- A `@splitQuery` single `@table` child (`BatchedTableField`, `emitsSingleRecordPerKey()`): added to
  the rows method's `selectFields` in `BatchedRowsFragments`.

**Fetchers.** `FetcherEmitter.presenceCheck` grows to read the probe column besides the leaf columns
and stays plain `if` statements, since generated code targets Java 17:

- `nestingBody` and `sameRowBody`: unchanged apart from the probe.
- The single `TableField` arm of `FetcherEmitter.bindRaw`: after `r.get(0)`, run the check and return
  `null` when the object is absent.
- The `@splitQuery` single fetcher: the check runs in that field's own continuation (beside
  `TypeFetcherGenerator.asyncWrapTail`), reading the field's contract, not in the rows method or the
  shared `scatterSingleByIdx`, so a loader or rows method serving both a `T` and a `T!` site cannot
  collapse the non-null one.

**Validator.** `GraphitronSchemaValidator.comparePresenceColumns` extends to probes: a nested type
shared across parents must agree on its probes as it agrees on its columns, since one fetcher reads
one probe alias for every site.

**Docs.** The two manual edits drafted above; the `NullContract.Reason.BlockedBy` javadoc and the
`SameRowTableField` javadoc follow the new rule.

## Cost

A nullable single `@table` child now projects its own `Columns` leaves always, a few columns in a
statement that already reads its row. Probes are the real cost, and the guard is weaker than it
looks: it saves the `EXISTS` only when a column leaf is non-null, and a type made only of references
(`studiesemesterPeriode`) has no column to guard with, so every selection of it pays one `EXISTS` per
reference leaf, each a keyed lookup on the referenced table. Where the leaf is also selected, its
value is computed twice. That is the price of deciding over the whole type, and it buys the answer
Graphitron 9 gave, which also computed every reference of the object. Nothing in the build measures
this; the reviewer checks it against the generated SQL in the sakila example, and if a consumer
measures it as a problem, deduplicating a probe against a selected leaf is a follow-up item.

## Tests

- **Model** (`NestingFieldNullContractTest`, moving with the types): the leaf kind each field kind
  yields, per the table, including a `@splitQuery` reference and an argument-free list as `Probe`, a
  list with arguments and a connection as blockers, and an object-reference probe whose target has
  non-column leaves counting row existence alone.
- **Pipeline** (`NestingFieldPipelineTest`, `SameTableInlineChildPipelineTest`, and a new
  `TableChildNullContractPipelineTest`): the contract for an inline and a `@splitQuery` single
  `@table` child, a non-null one as `AlwaysObject`, and a shared nested type with differing probes
  rejected. Assertions are on the model, not on generated method bodies.
- **Execution** (`graphitron-sakila-example`, beside `NestingNullContractExecutionTest`), over a new
  fixture table whose rows exist with every mapped column `null`:
  - an inline and a `@splitQuery` single `@table` child with an all-null row resolves to `null`; one
    non-null column keeps the object; a missing row is `null` as today;
  - `{ __typename }` agrees with the full selection, for both;
  - a child type with a `@nodeId` field never collapses while its row exists;
  - a non-null field over an all-null row is an object;
  - a nested object of references whose join column is set but whose target row is missing resolves
    to `null` (the `studiesemesterPeriode` shape);
  - a nested object holding an argument-free list resolves to `null` when its columns are `null` and
    the list is empty, and to an object when the list is not empty (the `phd` shape);
  - the `@splitQuery` collapse adds no statement (counted, as existing batching tests count them).
- The existing `NestingNullContractExecutionTest`, `SameTableInlineChildPipelineTest` and
  `GraphQLQueryTest.splitTableField_singleCardinality_*` cases stay green unchanged; the R1026
  divergence assertions that a list or `@splitQuery` field keeps the object are rewritten to the new
  rule.

## Retired vocabulary

- `ChildField.NestingField.PresenceLeaf`, `ChildField.NestingField.SubtreePresence`,
  `ChildField.NestingField.NullContract` (moved, not deleted; the nested names go)
- `ChildField.NestingField.referenceLeaf`
- `GraphitronSchema.sameRowNullContract` (generalised to the per-type fold)
- In the manual: "always resolves to an object" for a nested type holding a list, a `@splitQuery`
  field or a `condition:`-joined reference; "counts as present whenever its join columns are set"

## Other solutions we've considered

- **A directive declaring the collapse per field.** Rejected: nullability already declares it, and
  an opt-in would make every migrating consumer re-annotate every field to recover Graphitron 9's
  answers.
- **Judging a single reference by its join columns, as R1026 does.** Rejected: it cannot see a
  missing target row, which is the `studiesemesterPeriode` shape a production operation selects.
- **Counting a `@splitQuery` field by its key columns, as Graphitron 9's split key row effectively
  did.** Cheaper, since it adds no subquery to the statement the author split the field out of, but
  it makes `@splitQuery` change the answer rather than only the cost. The guarded probe keeps the two
  forms equal.
- **Judging by the projected values only.** Rejected: the projection depends on the selection (a
  `{ __typename }`-only selection projects a sentinel and no column), so the answer would too.

## Consumer input (2026-10-20)

Relayed from the sis session as the user's decision on the Backlog question, recorded here for the
Spec review: collapse by value by default (this plan's choice), with a per-field way to keep "exists
but empty" distinguishable (this plan answers that with a non-null declaration rather than a
directive; the reviewer should confirm that suffices), and the list-holding wrapper (`phd`)
collapsing too unless the plan finds a reason not to.

One further case from sis's retest on trunk after R1045 landed: `Karakter.navnAlleSprak`, a
same-table child that R1045 now reads from the parent's row, returns `{nb: null, en: null}` for a
grade with no name where Graphitron 9 returns `null`. The plan's table lists a same-table child as
blocking the collapse when it sits *inside* an object; it should also say what happens when the
same-table child *is* the nullable object, which is this shape.
