---
id: R1072
title: "A node id over a DATE key column with a time part decodes to a different row"
status: Ready
bucket: bug
priority: 5
theme: nodeid
depends-on: [undecodable-key-value-reaches-the-database]
created: 2026-10-19
last-updated: 2026-10-20
---

# A node id over a DATE key column with a time part decodes to a different row

## Goal

A node id whose key includes a column behind a jOOQ converter decodes to the value the id was
minted from, so `node(id:)`, `nodes(ids:)`, `_entities` and every `@nodeId` argument reach the row
the id names. The case that surfaced it is an Oracle `DATE` key column holding a time of day,
mapped by the consumer's catalog to `LocalDateTime` through a converter: today two rows on the same
day get distinct ids, but the id of the 02:24 row decodes to the 00:00 row and `node(id:)` returns
the wrong person's record. After this item each id returns its own row. The wire format is
unchanged, so ids clients already hold keep working.

A node id is the Relay global id graphitron mints for a `@node` type: base64 of the type id and the
row's key values, written and read by the generated `NodeIdEncoder`. A converter is the jOOQ
`Converter<T, U>` a catalog attaches with `<forcedType>`: the database exchanges `T`, the generated
column, and every value graphitron reads from it, has type `U`.

```graphql
# The sis shape: a person's national-id-number changes, keyed on (person, when the change was made).
type Fodselsnummerendring implements Node @table(name: "FODSELSNUMMERENDRING") @node {
    id: ID!
    # DATO_FORETATT is an Oracle DATE (date plus time of day), mapped to LocalDateTime.
}

# Two rows for one person, DATO_FORETATT 2007-08-22 00:00 and 2007-08-22 02:24.
{ node(id: "<id minted for the 02:24 row>") { ... on Fodselsnummerendring { id } } }
# today:  the 00:00 row, whose id differs from the one asked for
# after:  the 02:24 row
```

## Evidence

Surfaced by the sis v9 against v10 differential test on Oracle (2026-10-19). `Fodselsnummerendring`
for one person has two rows whose key column `DATO_FORETATT` holds 2007-08-22 00:00 and 02:24. v10
encodes them as distinct ids (v9 gave both the same id, which was worse), but `node(id: <the 02:24 id>)`
returns the 00:00 row.

### Mechanism

The encoder and the decoder read the column through different types.

- **Encode** writes the value graphitron read from the record: `encode<Type>(r.get(T.COL))`, which
  is the converter's user type `U` (`LocalDateTime`), rendered by `NodeIdEncoder.encode` as
  `v.toString()`: `"2007-08-22T02:24"`. The distinct ids show the time reaches the encoder.
- **Decode** coerces the slot text with the column's `DataType.convert(Object)`
  (`NodeIdEncoderClassGenerator.buildPerTypeDecode`), or with `Record.fromArray` / `DSL.val(text,
  col.getDataType())` on the other decode paths. On a converted data type all three parse the text
  as the converter's *database* type `T` and then apply `converter.from`. When `T` is date-only, the
  time is dropped before the converter ever sees it.

A probe against jOOQ 3.20.11 (default dialect) reproduces it with the text the encoder writes,
`"2007-08-22T02:24"`:

| Column data type | Coerced value | Round trip |
|---|---|---|
| `LOCALDATETIME`, no converter | `2007-08-22T02:24` | yes |
| `TIMESTAMP`, no converter | `2007-08-22 02:24:00.0` | yes |
| `TIMESTAMP` converted to `LocalDateTime` | `2007-08-22T02:24` | yes |
| `DATE` (`java.sql.Date`) converted to `LocalDateTime` | `2007-08-22T00:00` | no, truncated |
| `LOCALDATE` converted to `LocalDateTime` | `2007-08-22T00:00` | no, truncated |
| `OFFSETDATETIME` converted to `LocalDateTime` | `null` | no, lost |

On the converted `DATE` row, `Record.fromArray` and `DSL.val(text, type)` give the same value as
`convert`. A value that is already of the user type passes through unchanged: `DSL.val(LocalDateTime 02:24,
convertedType)` keeps 02:24. So the loss is in parsing the text, and only on a converted column.

What the probe settles: on an unconverted temporal column encode and decode agree, which points to
the sis column being converter-backed with a date-only database type (`DATE` or `LOCALDATE`). What
it cannot settle from here is whether the consumer's converter also loses the time when binding (`converter.to` producing a
`java.sql.Date` the Oracle driver truncates). That side is the consumer's binding, the same one their
own `WHERE` on that column uses, and not graphitron's to change; the implementer confirms against
the sis catalog at pickup (the column's `getDataType()`, `getConverter().fromType()` and
`toType()`), and if the bind also truncates, says so on the item rather than working around the
consumer's converter.

## Design

### Parse a minted slot as the type it was encoded from

The encoder writes the user type's `toString()`, so the decoder's inverse starts with a parse into
the user type, not into the database type. R1067 decodes every server-minted slot (every key slot of
a node id, tenant slot included) with a three-step *column trip* in `ColumnValues.exact`: the user
value `type.convert(text)`, the database value `converter.to(user value)`, the canonical value
`converter.from(database value)`, accepted only when the canonical value's `toString()` equals the
slot text. On a converted column, step 1's `type.convert(text)` parses through the database type,
which is this bug: on the sis column the trip runs on 00:00, and R1067 refuses the id rather than
answering the 02:24 row. This item changes step 1 of `exact` only, so that the user value is the slot
text parsed as the type it was encoded from. Steps 2 and 3, the acceptance check and the `coerce`
rule are R1067's and stay as it specifies them.

The parse type of a key slot is a fact about its column alone. It is decided at generation time from
two names: the column's user type (`ColumnRef.columnClass`) and the converter's database type, its
`fromType()`. The live catalog reads the second already (`JooqCatalog.ColumnEntry.databaseType`), but
neither `ColumnRef` nor the fact store carries it yet, and this item adds it to both (see
Implementation). The arms:

- **`ColumnType`**: no converter (`columnClass` equals `databaseType`). Parse with the column's own
  data type, exactly as today; nothing changes for any unconverted column.
- **`UserType`**: a converter whose user type jOOQ has a built-in text form for. Parse the text as
  the user type, with that type's converter-free jOOQ data type; the trip then runs `converter.to`
  on the typed value.
- **`DatabaseType`**: a converter to any other class (a consumer's value class, a Java enum behind
  an `EnumConverter`). jOOQ has no text form for such a class, so parse as the database type and
  apply `converter.from`, which is today's path; the trip then decides whether the result is the
  encoded value.

"Has a built-in text form" is derived from jOOQ itself, not kept by hand: the set of `getType()`
classes of `org.jooq.impl.SQLDataType`'s public static `DataType` constants, computed once in the
generator, less `java.lang.Object` (the `OTHER` type), whose "parse" would accept any text. Probed
on 3.20.11, the set covers every temporal, numeric, string and `UUID` type a key column plausibly
uses, plus jOOQ's own (`JSON`, `XML`, unsigned and interval types); a member whose text form does not
invert `toString()` is refused by R1067's trip, never misrouted. Asking jOOQ per class (`DefaultDataType.getDataType`) answers an unknown class by
throwing and logging a warning, which is why the derivation reads the constants rather than probing.

The arm is a small sealed `SlotParse` (`ColumnType | UserType | DatabaseType`), computed by one
generation-time function of a column, `SlotParse.of(ColumnRef)`. It belongs to the column, not to any
decode carrier, because each decode path already holds its key columns as `ColumnRef`s on a carrier
of its own:

- `HelperRef.Decode.outputColumnShape`, for the per-type `decode<Type>`;
- `KeyAlternative.NodeId.columns`, for the `_entities` lookup;
- `CallSiteExtraction.RecordKeyDecode`, for the jOOQ-record writer rails;
- `CallSiteExtraction.NodeIdDecodeRecord.keyColumns`, for the record decode helpers. One producer
  of this carrier is `TypeFetcherGenerator.collectProjectionDecoders`, which builds it from
  store-derived `KeyProjection` rows.

A per-decode list would have to be threaded through all four carriers and every site that builds
them, and one of those sites is an emitter with no catalog. A column fact reaches every carrier
unchanged.

Emitters hand each slot's parse class to `ColumnValues.exact` as a class literal, and `ColumnValues`
takes no schema input. The parse class is read off the same column whose `Field` R1067 hands the trip.
On the record-writer rails that is the record's own target column, so the parse and the trip read
one column. A target column whose converter disagrees with the node key column's is the
column-agreement question R1044 owns, not this item's. The captured fact is `ColumnRef.databaseType`.
The arm is a pure function of it, pinned per column by the pipeline test below, so the arm a key
takes is decided and tested at build time, not discovered when a request fails.

How the parse and R1067's trip settle the cases in play:

| Column | Slot text | Parse | Trip | Outcome |
|---|---|---|---|---|
| sis `DATO_FORETATT`, `DATE` to `LocalDateTime` | `2007-08-22T02:24` | `UserType`: 02:24 | to and from keep 02:24 | the 02:24 row |
| same column today | `2007-08-22T02:24` | database type: 00:00 | n/a | the 00:00 row |
| `ConverterCampusTerm.org_code`, `bigint` to `String` | `186` (minted) | `UserType`: `"186"` | `186`, back to `"186"` | decodes |
| same | `abc` / `0186` | `UserType` | `to` throws / back to `"186"` | refused, as R1067 specifies |
| a converter whose database type cannot hold the time (`LOCALDATE` to `LocalDateTime`) | `2007-08-22T02:24` | `UserType`: 02:24 | back to 00:00 | refused, never another row |

The last row cannot arise from an id graphitron minted, since such a column never shows the encoder
a time; it is there to show the composition never names a different row.

### What does not change

- The encoder and the wire format. Ids minted before this item decode to the value they always
  should have.
- The authored-value rule (`ColumnValues.coerce`, for tenant arguments and `@key` representation
  values). Those are values a client typed against the database type's documented coercion
  (`tenant-scoping.adoc` section 1), not text graphitron wrote from the user type.
- `requireColumnAgreement`, which compares already-typed values; R1044 owns its converter question.
- No build-time rejection or warning for the `DatabaseType` arm: a converter to a consumer class
  whose database-side text agrees with its `toString()` decodes correctly, and one that does not is
  refused as malformed, never misrouted. The documentation bullet below states the condition.

## Implementation

- **`ColumnRef`** (`graphitron-model`): a fourth component, `databaseType`, the converter's
  `fromType()` name, equal to `columnClass` on an unconverted column. It is another name, so the
  class javadoc's "names and no types" rule holds, and a ref built from the live catalog still equals
  one read back out of the store. Every main-source construction passes it. Each of those sites
  (eighteen at the time of writing) reads a `JooqCatalog.ColumnEntry`
  (`ColumnEntry.databaseType()`), a live `Field` (`col.getConverter().fromType().getName()`), or a
  store row (the column below). A three-argument constructor stays for test fixtures and means "an
  unconverted column"; its javadoc says so. A main-source site that falls back to it on a converted
  key column fails the pipeline test below.
- **The fact store**: `sql_column` gains `database_type VARCHAR NOT NULL`, with its
  `COMMENT ON COLUMN`.
  - `JooqFactCapture` captures it beside `binding_type`, from the live `Field`'s converter.
    `JooqCatalog.ColumnFacts` gains `databaseType`, which makes its javadoc's claim that it "carries
    every fact `ColumnEntry` does" true again.
  - Every statement that writes `sql_column` writes it: both `JooqFactCapture` sites and
    `FactWrites.sqlColumn`.
  - `StoreNodeTables.columnOf` reads it back. Its three column selects add the column, so node key
    columns and the `KeyProjection` rows built from them carry it.

  This is a capture from the jOOQ catalog corpus into a relation that already has an owner, a grain,
  and a mark and sweep, so the modeling discipline asks for nothing beyond the column and its comment.
- **`SlotParse`** (new, `no.sikt.graphitron.render`, beside `CatalogRefs`): the sealed type,
  `SlotParse.of(ColumnRef)`, and the built-in text-form set as one static derivation over
  `SQLDataType`'s constants. `UserType` carries the user class name the emitters write as a literal.
  `TenantScopeClassifier` already compares `columnClass` with `databaseType` the same way.
- **`ColumnValuesClassGenerator`** (from R1067): `exact(DataType<T> type, Class<?> parseClass, String
  text)`. A `null` parse class (the `ColumnType` and `DatabaseType` arms) keeps `type.convert(text)`.
  A `UserType` slot's user value is `DSL.val(text, parseClass).getValue()`, the public
  non-deprecated route. Steps 2 and 3 run on the user value unchanged.
- **The decode emitters**: R1067 routes eight emitted call sites through `decodeColumns`:
  - `NodeIdEncoderClassGenerator.buildPerTypeDecode`;
  - `HandleMethodBody.emitDecodeAndGroup`;
  - `RecordDecodeFragments.decodeHelper`;
  - the two arms of `JooqRecordInstantiationEmitter.emitKeyDecode`;
  - the three arms of `JooqRecordInstantiationEmitter.emitPrepare`.

  `decodeColumns(String expectedTypeId, String base64Id, Class<?>[] parseClasses, Field<?>...
  columns)` takes the parse classes beside the columns. Each site emits them from the `ColumnRef`s it
  already holds for the columns it passes: a class literal for a `UserType` slot, `null` otherwise.
  No carrier gains a component, and `HelperRef.Decode` is unchanged.
- **`NodeIdEncoderClassGenerator`**: its class javadoc's wire-format paragraph gains one sentence:
  each value is written as its column's Java type's `toString()` and parsed back as that type, so a
  converter-backed key round-trips through its user type.

If R1067 changes its column trip before it lands, this item restates its step 1 against what
landed before implementation starts; the parse and the trip stay one rule in one primitive.

## Documentation

- `docs/manual/how-to/global-id.adoc`, "Constraints and pitfalls": one bullet. A key column behind a
  jOOQ converter round-trips through its Java type: for the built-in value types (dates, times,
  numbers, strings, UUIDs) the id decodes to exactly the value it was minted from, including a time
  of day on an Oracle `DATE` mapped to `LocalDateTime`. For a converter to your own class, the id
  decodes only if your converter's database-side text matches the class's `toString()`; otherwise
  the id is refused as malformed.

## Tests

Write the tests first so they fail on the tree with R1067 landed and this item not.

- **Execution tier, `graphitron-sakila-example`**: a new converter-backed temporal key fixture beside
  `converter_campus_term` in `graphitron-sakila-db`'s `init.sql`. Postgres cannot store a time in a
  `date`, so the fixture models the same mechanism with a database type whose text parse is lossy
  for the user type's `toString()`: a domain over `timestamptz`, forced to `LocalDateTime` with a new
  `OffsetDateTime`/`LocalDateTime` converter in `graphitron-fixtures-codegen` (both directions at
  UTC, so the read and the write agree whatever the session time zone). Seed two rows for one
  person on the same day, 00:00 and 02:24, and expose a `@node` type over them in `schema.graphqls`.
  In `GraphQLQueryTest` (or a sibling execution test for converter keys):
  - `node` with each row's id returns that row (on today's tree both ids answer `null`, per the
    probe's last row, since neither slot text carries the offset `OffsetDateTime` parses; with R1067
    alone both are refused, so also `null`);
  - `nodes` over both ids returns both rows, in order;
  - a `@nodeId` filter argument over the type selects the 02:24 row only.
  The sis symptom itself (truncation to a different row rather than to `null`) needs a date-only
  database type that stores time, which Postgres does not have; the unit test below pins that row
  of the probe.
- **Unit tier, against the generated `ColumnValues`** (R1067 already calls it directly over
  `SQLDataType` types): `exact` over `SQLDataType.DATE` and `SQLDataType.LOCALDATE` converted to
  `LocalDateTime`, text `"2007-08-22T02:24"`, parse class `LocalDateTime`: 02:24 for the `DATE`
  converter that keeps milliseconds, `null` for the `LOCALDATE` one. A `DatabaseType` slot over a
  converter to a fixture value class returns the converter's result when the texts agree and `null`
  when they do not. A round-trip case per user type the sakila catalog and the new fixture use.
- **Pipeline tier, `graphitron`**: `SlotParse.of` over each key column, read from every carrier the
  emitters read. The carriers are `NodeType.decodeMethod().outputColumnShape()`, the type's
  `KeyAlternative.NodeId`, a `RecordKeyDecode`, and a `NodeIdDecodeRecord` built from a
  `KeyProjection` row, which covers the store round trip of `database_type`. The cases:
  - an unconverted node, `Customer`: `ColumnType`;
  - `ConverterCampusTerm`, `org_code`: `UserType` `String`;
  - the new temporal fixture: `UserType` `LocalDateTime`;
  - a converter to a non-jOOQ class: `DatabaseType`.

  Use the carriers and converter the sakila and pipeline fixtures already reach, and add the missing
  ones to the pipeline test's own fixture.
- **Store round trip**: `StoreNodeTablesTest` (in `graphitron`) asserts a converted key column's
  `databaseType` survives the store (`java.lang.Long` for `org_code`), beside
  `graphitron-model`'s `JooqCatalogLayoutTest` and its existing live-catalog assertion.
- **Regression guard**: `ConverterCampusTerm`'s converter-backed node tests
  (`ConverterFilterExecutionTest`) stay green: minted ids decode, as the table above shows.

## Neighbours, not absorbed

- **R1067** (depends-on): the decode primitive and its converter trip. This item adds only the
  parse step in front of the trip.
- **Cursor decode**: `ConnectionHelper.decodeCursor` coerces cursor tokens with
  `col.getDataType().convert(token)`, so paging ordered by such a column resumes at the truncated
  value. R1067 names routing it through `exact` as a follow-up; it inherits this rule when it does.
- **R267**: the deprecated `DataType.convert`. The `UserType` arm uses `DSL.val(text, parseClass)`
  rather than `convert`, so it adds no new call to retire.

## Other solutions we've considered

- **Encode the database-side value** (`converter.to(v).toString()`) so both sides use `T`. It
  changes the wire format of every converter-backed key, breaking ids clients hold, and it does not
  even work here: `java.sql.Date.toString()` prints no time, so the two rows would collide again,
  which is v9's behaviour.
- **Carry a `List<SlotParse>` on each decode carrier.** Four carriers hold key columns
  (`HelperRef.Decode`, `KeyAlternative.NodeId`, `RecordKeyDecode`, `NodeIdDecodeRecord`). Each would
  need the list, every site that builds them would have to compute it, and the `KeyProjection` path
  would still need the database type in the store. The column fact gives every carrier the answer
  for one capture.
- **Decide the parse type at runtime**, from `type.getConverter().fromType()` and a hand-kept set
  of value classes in the emitted `ColumnValues`. It re-derives per call a fact the catalog has
  already captured, keeps a roster nothing checks against jOOQ, and leaves which arm a key takes
  invisible until a request fails.
- **Specify the converter trip here too.** R1067's column trip already decides it; deciding it in
  two items would decide one rule twice.
- **Fix it in the generated decode helpers directly**, without waiting for R1067. Every one of the
  four decode paths R1067 rewrites would need the same branch, and the two items would collide on
  each of them.

## Reviewer findings

### Round 1 (Spec → Ready): request revisions, session_01KyQUTS8fJEtUzfRkhf4Afs, 2026-10-19

The goal is clear and viable. Every symbol, test class, fixture and doc section the spec names exists
as named, and the jOOQ claims reproduce on 3.20.11: `SQLDataType`'s constants yield 39 distinct
`getType()` classes, including `LocalDateTime`, `String` and `Object`; `DSL.val("2007-08-22T02:24",
LocalDateTime.class)` keeps 02:24 and a `java.sql.Date` converter that keeps milliseconds carries it
through `to` and `from`; the converted `DATE` type's `convert` truncates to 00:00; `OFFSETDATETIME`
gives `null`; and `DefaultDataType.getDataType` on an unknown class logs a warning and throws
`SQLDialectNotSupportedException`. One finding blocks.

**F1 (question 2, fit): the per-slot fact sits on a carrier that three of the four decode paths do
not hold.** The Implementation says the decode emitters "pass each slot's parse class from the
`HelperRef.Decode` they already hold", and puts `List<SlotParse>` on `HelperRef.Decode` alone, built
"where `HelperRef.Decode` is built (the node type's `decodeMethod` and the catalog decodes)". In the
tree, only the `NodeIdEncoder` per-type decodes hold one. The other paths carry key columns as their
own `List<ColumnRef>`:

- `HandleMethodBody.emitDecodeAndGroup` reads `KeyAlternative.NodeId(expectedTypeId, columns, ...)`,
  built in `EntityResolutionBuilder`, not a node type's `decodeMethod()`.
- `JooqRecordInstantiationEmitter.emitKeyDecode` reads `CallSiteExtraction.RecordKeyDecode`, built in
  `InputBeanResolver`.
- `RecordDecodeFragments.decodeHelper` is handed a `HelperRef.Decode` that
  `InputBeanInstantiationEmitter.buildRecordDecodeHelper` constructs at emit time from
  `CallSiteExtraction.NodeIdDecodeRecord`. That record is built in `ServiceCatalog`, in
  `InputBeanResolver`, and in `TypeFetcherGenerator.collectProjectionDecoders` from `KeyProjection`
  rows. That last site is an emitter, with no catalog to read `databaseType` from.

So a third `HelperRef.Decode` construction site exists that the plan does not list, and the
size-checked compact constructor breaks there. More to the point, an implementer has to choose, while
building, between threading `SlotParse` through four more carriers (`KeyAlternative.NodeId`,
`RecordKeyDecode`, `NodeIdDecodeRecord`, `KeyProjection`) and their build sites, or moving the fact to
where the carriers already meet. That choice bears on the spec's own premise, "the slot fact is the
decode's, not the column's". The fact is computed from the column alone (`columnClass`,
`databaseType`, and the jOOQ text-form set), and `ColumnRef` is the one thing every one of these
carriers holds. A column fact parked on one decode carrier is the parallel-carrier shape the
principles warn against.

What would satisfy it: name the carrier the parse fact lives on so that every decode path R1067
routes through `exact` reaches it, list every site that builds that carrier (catalog access
included, or say how a site without it gets the fact), and restate "ColumnRef stays as it is" or its
rationale against that choice. The pipeline test should then pin the fact on every carrier the
emitters read, not only on the node type's `decodeMethod`.

> **Author (round 1):** I addressed this in Design and Implementation by moving the fact to the column. `ColumnRef` gains
> `databaseType`. `sql_column` gains `database_type`, captured by `JooqFactCapture`, written by
> `FactWrites.sqlColumn` and read back by `StoreNodeTables`, so the store-derived `KeyProjection` path
> carries it. `SlotParse.of(ColumnRef)` is a generation-time function each emitter applies to the
> columns it already holds. No carrier gains a component and `HelperRef.Decode` is unchanged, so the
> unlisted construction in `InputBeanInstantiationEmitter` needs nothing. The emitter list is now
> R1067's eight `decodeColumns` sites. The parse class is read off the column the trip runs over, and
> a key/target converter disagreement is left to R1044. The pipeline test pins `SlotParse.of` on all
> four carriers, and `StoreNodeTablesTest` pins the store round trip. Per-carrier threading is recorded
> under Other solutions.

Non-blocking:

- R1067 is still in Spec, and this plan is written against its `exact(DataType<T>, String)` and
  `decodeColumns(String, String, Field<?>...)`. The contingency paragraph covers it. Signing off this
  item before R1067 is Ready leaves the dependency's shape unsettled.
- Execution-tier note: on the `timestamptz` fixture both seeded ids answer `null` on today's tree,
  not only the 02:24 one, because neither slot text carries an offset. The test still fails first as
  intended.

> **Author (round 1):** I corrected the execution-tier note. The R1067 dependency stands: its current
> revision still names `exact(DataType<T>, String)` and the eight `decodeColumns` sites this plan now
> restates, and the contingency paragraph covers any later change.
