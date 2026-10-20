---
id: R1067
title: "A node id or tenant value that does not fit its column is not caught at decode"
status: In Progress
bucket: bug
priority: 3
theme: nodeid
depends-on: []
created: 2026-10-19
last-updated: 2026-10-20
---

# A node id or tenant value that does not fit its column is not caught at decode

## Goal

A node id, federation entity representation or tenant argument whose key value cannot be a value of
its column (non-numeric text for a numeric column, a number outside the column's range or precision)
is refused when it is decoded. It then gets the outcome the manual already promises for a malformed
id: `null` with no error on the lookups `node`, `nodes` and `_entities`, with the rest of the batch
resolving, and a client error naming the id on every argument. A value that does not decode is never
bound into SQL, so it can never be written.

A node id is the Relay global id graphitron mints for a `@node` type: base64 of the type id and the
row's key values, written by the generated `NodeIdEncoder`. Today its decode coerces each key value
through jOOQ's lenient conversion, which turns `"abc"` into `null` and silently *wraps* a number that
overflows the column's Java type. A forged id can therefore name one row and reach another:

```graphql
# Customer (sakila schema.graphqls) is a @node keyed on customer_id, an int4.
# The id below is base64("Customer:4294967297"); 4294967297 is 2^32 + 1.
{ node(id: "Q3VzdG9tZXI6NDI5NDk2NzI5Nw") { ... on Customer { customerId } } }

# today:  { "node": { "customerId": 1 } }    the key wraps to 1
# after:  { "node": null }                    no error, like any malformed id
```

The same wrap on a mutation input updates or deletes row 1. That is the urgent half of the goal:
whatever the per-surface outcome, an undecodable value never reaches the database.

## Evidence

Surfaced by the sis v9 against v10 differential test (2026-10-19):

- In a mutation, the `Land` node id `62:abc` decoded to `LANDNR = -32768` and reached the database
  (ORA-01438). A conversion failure became a sentinel value that was written. `62:99999` returned the
  raw Java text "Value out of range..." instead of "Invalid node id...".
- In `node(id:)` and `_entities`, a key value that overflows its column gives a redacted error, and
  on `_entities` the whole `data` becomes null.
- A non-numeric tenant value (`eierOrganisasjonskode: "abc"`, `""`, `" 1234"`, `"99999999999"`, or a
  node id `20:abc,...` whose tenant slot does not parse) gives the error
  "Tenant value 'abc' is not a value of the tenant column."; on `_entities` the whole `data` becomes
  null.

The manual sets a different expectation. `how-to/global-id.adoc` ("Decode-side errors") says
`node(id:)` and `nodes(ids:)` answer malformed ids with `null` (the opacity contract: a client cannot
probe for valid ids by mining errors), and that an argument fails with a client error before any SQL
runs. `how-to/tenant-scoping.adoc` section 2 says the lookups answer an id whose tenant is outside the
request's set with `null`, "exactly as they answer an id that does not exist". An id whose key or
tenant slot does not decode fits neither promise today.

### Mechanism

A probe against jOOQ 3.20.11 (`SQLDataType`, default dialect) shows that `DataType.convert(Object)`,
`Record.fromArray` and `DSL.val(text, dataType)` agree on every input. All three are lenient, and none
of them throws:

| Text | `SMALLINT` | `INTEGER` | `BIGINT` | `NUMERIC(4)` |
|---|---|---|---|---|
| `"abc"`, `""` | `null` | `null` | `null` | `null` |
| `"99999"` | `-31073` (wrapped) | `99999` | `99999` | `99999` (precision not checked) |
| `"99999999999"` | `-6145` (wrapped) | `1215752191` (wrapped) | `99999999999` | `99999999999` |
| `" 1234"` | `1234` | `1234` | `1234` | `1234` |
| `"1.5"` | `1` | `1` | `1` | `1.5` |

A column a jOOQ converter maps to another Java type hides a second gap. Its `DataType` is a converted
type whose `convert` returns the *user* type, and an input already of the user type passes through
untouched. Over `SQLDataType.BIGINT.asConvertedDataType(OrgCodeStringConverter)`, the sakila fixture
of the sis shape, `convert("abc")` gives `"abc"` and `convert("0186")` gives `"0186"`. The value
fails only when it is bound, where the converter's `to` (`Long.valueOf`) throws inside the statement.
So no check on the result of `convert` alone can tell whether a value fits a converter-backed column:
the check has to follow the value through the converter to its database value.

The generated decode paths add no check of their own on top of that:

- `NodeIdEncoder.decodeValues` returns the raw `String[]` and checks only the base64, the type prefix
  and (at its callers) the arity.
- `NodeIdEncoder.decode<Type>` (`NodeIdEncoderClassGenerator.buildPerTypeDecode`) does
  `rec.set(col, col.getDataType().convert(values[i]))` with no check on the result. The argument
  rails use it: filter helpers from `CompositeDecodeHelperRegistry`, `LookupRows.addRowBuildingCore`,
  and the DML decode locals in `TypeFetcherGenerator.appendDecodeLocal` / `appendSetDecodeLocal`.
- `RecordDecodeFragments.decodeHelper` and `JooqRecordInstantiationEmitter.emitKeyDecode` load the raw
  strings with `Record.fromArray`, again with no check on the result.
- The lookups (`HandleMethodBody.emitDecodeAndGroup`) bind the raw strings, or for a non-node-id
  `@key` alternative the raw `rep.get(field)` values, through `DSL.val(value, col.getDataType())` in
  the VALUES table.

The one guard that exists is `TenantConnections.canonicalTenantKey`. It turns a `null` conversion
into an `IllegalArgumentException`, but it does not detect a wrap. Its client-facing wrapper is
`tenantKeyOf`, which throws `GraphitronClientException`. On the lookups it runs per tenant group, at
`TenantConnections.divinedTenant(tenantEntry.getKey())` in `HandleMethodBody.emitGroupDispatch`.
`EntityFetcherDispatch.fetchEntities` has no catch and `_entities` is `[_Entity]!`, so that throw nulls
the whole response.

The `-32768` sentinel does not reproduce on `SMALLINT` under the default dialect, where `"abc"` gives
`null`; it presumably comes from the consumer's Oracle binding or a converter on `LANDNR`. The same
holds for the raw "Value out of range" text, which is the `NumberFormatException` message of
`Short.parseShort`. The plan does not depend on either mechanism: the acceptance rule below compares
the decoded value against the text it came from, so whatever the coercion does, a value that is not
the text is refused.

## Design

### Two acceptance rules, by provenance

Both rules run on the same three-step **column trip**, read off the column's `DataType` and its
`getConverter()` (jOOQ's identity converter when the column has none, so there is one emitted
shape):

1. the *user value*: `type.convert(input)`;
2. the *database value*: `converter.to(user value)`;
3. the *canonical value*: `converter.from(database value)`.

The trip fails when any step gives `null` or throws `IllegalArgumentException` or
`DataTypeException`; `NumberFormatException` is an `IllegalArgumentException`, so a converter's
`Long.valueOf("abc")` fails the trip. Any other exception propagates: it is a fault in the
consumer's converter, not a fact about the value, which is the contract `canonicalTenantKey`
documents today. The trip's last two steps are the `roundTrip` `TenantConnections` already performs
for the tenant key, now applied to every column.

The clauses below that inspect a number read the **numeric value** of the trip: the canonical value
when it is a `Number`, else the database value when that is a `Number`, else there is none and those
clauses do not apply. On an unconverted integer column that is the coerced value; on the
`String`-over-`bigint` tenant it is the database `Long`; on a converter whose user type is itself
numeric it is the user-side number. The precision and scale are the ones the column's `DataType`
reports, which a converted type carries over from its SQL type (`NUMERIC(4,1)` converted reports
`hasPrecision()`, precision 4, scale 1).

**A server-minted value: the exact inverse of the encoder.** This covers every key slot of a node id,
the tenant slot included. `NodeIdEncoder.encode` writes each key value as `v.toString()`, where `v`
is the record's user-type value, so the decoder accepts exactly the encoder's image. A slot decodes
iff all three of these hold:

1. the column trip succeeds;
2. the canonical value's `toString()` equals the slot text;
3. when the column's `DataType` declares a precision (`hasPrecision()` and `precision() > 0`) and the
   trip has a numeric value, that value fits the precision and scale: at most `precision - scale`
   integer digits and at most `scale` fractional digits.

The decoded value is the canonical value. An id graphitron minted always passes, because every stored
value fits its column and the encoder wrote the canonical value's text. On an integer column this
rule refuses `"abc"`, a wrapped number, `" 1234"`, `"01"` and `"1.5"`, none of which the encoder
writes; on a converter-backed column it also refuses `"0186"` (the canonical value is `"186"`) and
any text the converter cannot take to the database. A converter that wraps or substitutes a
sentinel in `to` is refused too, whenever its `from` does not give the slot text back.

**An authored wire value: the documented coercion, minus the wrap.** This covers a tenant argument, a
`@tenantFanOut` narrowing value, and a key value in a non-node-id federation `@key` representation.
These are values a client typed, and `tenant-scoping.adoc` section 1 documents their coercion: `"1.5"`
binds as `1`, and on the converter-backed tenant `"0186"` routes to `186`. That coercion stays. The
input is a `String` or a JSON-sourced `Number`. A value is refused when any of these holds:

- the column trip fails;
- the input *reads as a number* and the trip's numeric value equals neither that number nor its
  integral part. An input reads as a number when it is a `Number`, or a `String` that
  `new BigDecimal(text.trim())` accepts; the trim matches jOOQ, which trims before it parses, so
  `" 1234"` keeps coercing to `1234` as it does today. A mismatch is a wrap. Equality is
  `BigDecimal.compareTo`, so `"1.5"` is accepted at `1` on an integer column and at `1.5` on a
  `NUMERIC`, and `"0186"` is accepted at the database value `186`. An input that does not read as a
  number has no number to wrap, and this clause does not apply: a converter from a code such as
  `"NORWAY"` to a database number is not second-guessed;
- the precision clause above fails on the trip's numeric value.

The accepted value is the canonical value, which is what `canonicalTenantKey` returns today.

### One primitive, value or absent

Both rules live in a new generated runtime class `ColumnValues` in the output's `util` package. Its
unit name is minted through `GeneratedUnits`, like `NodeIdEncoder` and `TenantConnections`. It exposes
two methods:

- `static <T> T exact(DataType<T> type, String text)`
- `static <T> T coerce(DataType<T> type, Object value)`

Each returns the canonical value, or `null` when the value is not a value of the column. Neither
throws for such a value; only a converter fault (an exception outside the two the column trip reads
as failure) propagates. `ColumnValues` holds the column trip, so it is the only decode-side
coercion call and the only decode-side converter round trip: `TenantConnections.roundTrip` moves into
it rather than being kept beside it. R267's replacement of the deprecated `DataType.convert` becomes
a swap at this site plus `requireColumnAgreement`, which belongs to R1044. R1044 asks whether the
converter round trip should become one emitted primitive both `NodeIdEncoder` and `TenantConnections`
call; this item builds that primitive, and R1044 is left to decide whether `requireColumnAgreement`
compares through it. Neither surface lives below the primitive: the lookups skip on `null`, the
argument rails and the tenant fold throw on `null`. The skip-or-throw choice belongs to the surface, not to the
helper. The throw that `canonicalTenantKey` performs today inside the shared helper is why `_entities`
nulls the whole response.

### Outcome per surface

| Surface | Today, for `"abc"` / a wrapping number | After |
|---|---|---|
| `node(id:)` | `null` (abc binds null, matches nothing) / the wrapped row | `null`, no error |
| `nodes(ids:)` | as `node`, per slot; a tenant-slot throw fails every id in the batch | that slot `null`, the rest resolve, no error |
| `_entities`, node-id representation | as `node`; a tenant slot that does not parse nulls all of `data` | that slot `null`, the rest resolve, no error |
| `_entities`, `@key` representation | as `node` | that slot `null`, the rest resolve, no error |
| `@nodeId` argument or input field on a query | `null` predicate / filters on the wrapped key | the existing `Invalid node id "<id>" for this argument: not a valid <Type> id` client error, before SQL |
| `@nodeId` on a mutation input (key, `SET`, reference) | binds `null` / writes or deletes the wrapped row | the rail's existing decode failure, nothing written |
| `@service` record input, `argMapping` key projection | coerces silently | the rail's existing decode failure |
| Tenant argument, `@tenantFanOut` narrowing | client error / routes to the wrapped tenant | client error for both, message unchanged |
| Tenant slot of a node id on an argument | "Tenant value ... is not a value of the tenant column." | the malformed-id message, since the slot is part of the id |

The mutation row says "the rail's existing decode failure" on purpose. Several of those rails throw a
plain `GraphqlErrorException`, which `ErrorRouter.surfaceClientErrorOrRedact` redacts. Making them say
"Invalid node id" is R1036's goal, not this item's. This item's acceptance on those rails is "never
written; the same error the rail gives a malformed id". The `62:99999` raw-text evidence is closed
fully only when both items land.

The tenant-argument client error is already a field error. When the field is a non-null root such as
`films: [Film!]!`, `data` becomes null through GraphQL's ordinary non-null propagation. That behaviour
is unchanged and is not part of this item. On `_entities` the whole-response null goes away for decode
failures because they no longer throw. Any other throw out of `fetchEntities` still nulls `data`,
which is what the `[_Entity]!` shape means.

## Implementation

- **`ColumnValuesClassGenerator`** (new, `rewrite/generators/util`) emits `ColumnValues` with `exact`
  and `coerce` as specified above, both over one private column-trip helper that captures the
  converter's wildcard database type the way `roundTrip` does today, so the trip type-checks at Java
  17. It carries the `deprecation`/`removal` suppression `NodeIdEncoder` carries, since it holds the
  `convert` call. It is emitted wherever `NodeIdEncoder` or `TenantConnections` is. Emitting it
  unconditionally is fine if that is simpler, since it is a few methods with no schema input.
  Register the unit in `GeneratedUnits` beside its siblings.
- **`NodeIdEncoderClassGenerator`**:
  - Emit `static Object[] decodeColumns(String expectedTypeId, String base64Id, Field<?>... columns)`.
    It calls `decodeValues`, checks the arity against `columns.length`, then runs `ColumnValues.exact`
    per slot over `columns[i].getDataType()`, which is the converted type on a converter-backed
    column. It returns the canonical values, or `null` if any step fails.
  - `buildPerTypeDecode` builds `decode<Type>` on `decodeColumns`, so a `null` from `decode<Type>` now
    also means "a value does not fit". The typed `RecordN` return shape is kept, and every
    `decode<Type>` caller is untouched.
  - `decodeValues` stays: `reference/runtime-api.adoc` documents it as public runtime API returning
    raw strings. After this item no generated code calls it except `decodeColumns`. Today the
    generator emits it in eight places: `buildPerTypeDecode`, `HandleMethodBody.emitDecodeAndGroup`,
    `RecordDecodeFragments.decodeHelper`, the two arms of `JooqRecordInstantiationEmitter.emitKeyDecode`
    and the three arms of `JooqRecordInstantiationEmitter.emitPrepare`. This item rewrites all eight.
- **`RecordDecodeFragments.decodeHelper`** and **`JooqRecordInstantiationEmitter.emitKeyDecode`**:
  replace `decodeValues` plus `fromArray(String[], ...)` with `decodeColumns` plus
  `fromArray(Object[], ...)`, and keep the existing throw on `null`. Since `exact` refuses `null`,
  every value `fromArray` loads is non-null, so R534's null-flag erasure is not newly exposed.
- **`JooqRecordInstantiationEmitter.emitPrepare`**, the prepare phase a jOOQ-record writer takes
  when more than one writer lands on a column: the same swap in all three arms (top-level non-null,
  nested non-null, nullable). The `<base>Keys` local becomes the `Object[]` from `decodeColumns`, and
  the existing arity throw (`emitArityThrow`) also covers its `null`. `emitLoadPrepared` loads it with
  `fromArray(Object[], ...)` unchanged. `emitAgreement` reads `<base>Keys[slot]` through
  `agreeValueExpr` and needs no edit either. `requireColumnAgreement` now receives the canonical value
  from a node-id writer and still the raw wire value from a plain writer. Both still pass through
  `type.convert`, and `convert` leaves a value already of the user type unchanged. So for an id that
  decodes, the comparison gives today's answer. A plain `"01"` still agrees with the id of bin 1.
  What changes is that an id that does not decode fails at the arity throw before agreement runs. It
  can no longer agree with the plain writer of the row it wrapped onto.
- **`HandleMethodBody.emitDecodeAndGroup`**:
  - Node-id arm: `decodeColumns` replaces `decodeValues`, and `null` takes the existing `continue`.
  - `@key` (`Direct`) arm: `cols[i] = ColumnValues.coerce(<col>.getDataType(), rep.get(<field>))`,
    with a `null` result taking `continue`.
  - The tenant group key is canonicalised here, before grouping, with
    `TenantConnections.tenantKeyOrNull`. A `null` takes `continue`.
  - `emitGroupDispatch` then takes the group key as the canonical tenant. Its
    `divinedTenant(tenantEntry.getKey())` call goes away; the `permits` check and the "outside the set
    answers null" `continue` are unchanged.
  - Where the `@defaultTenant` value is already canonical, it skips the call.
- **`ConnectionRuntimeClassGenerator`**:
  - New non-throwing `tenantKeyOrNull(Object)`: `ColumnValues.coerce(TENANT_COLUMN_TYPE, value)`, and
    nothing else, since the column trip already ends at the canonical value.
  - `roundTrip` and the `TENANT_COLUMN_CONVERTER` field are deleted: the trip reads the converter off
    the `DataType`, and `TENANT_COLUMN_TYPE` is that column's `DataType`.
  - `canonicalTenantKey` becomes the throwing layer over `tenantKeyOrNull`, keeping its message. Its
    documented contract is unchanged, the converter-fault propagation included; it now also refuses a
    wrap. `tenantKeyOf`, `agreeOnTenant` and `fanOutDomain` are unchanged and
    inherit the wrap refusal.
  - Configured keys (`RequestTenants`, `sourcesByTenant`) also pass through `canonicalTenantKey`, so a
    configured key that would wrap is now refused at construction with the existing
    `IllegalArgumentException`. That is intended, since such a key is a misconfiguration; say so in its
    javadoc.
- **The argument rails need no edit.** `CompositeDecodeHelperRegistry`, `LookupRows`,
  `TypeFetcherGenerator`'s decode locals and `ServiceMethodCallEmitter` already turn a `null` from
  `decode<Type>` into their decode failure. The implementer confirms each of them has the null check.
  A rail found without one gets it in this item, because "never written" is the acceptance.

## Documentation

- `how-to/global-id.adoc`, "Decode-side errors": state the rule in reader terms. An id decodes only if
  every key value is one graphitron could have written for a value of its column. Non-numeric text, a
  number outside the column's range or precision, or a padded or reformatted number makes the id
  malformed: `null` on `node`, `nodes` and `_entities`, a client error on an argument or input. Mirror
  that in "Constraints and pitfalls".
- `how-to/tenant-scoping.adoc` section 1: after the `"abc"` sentence, add that a number outside the
  column's range is a client error too, not wrapped onto another tenant. Section 2: an id whose tenant
  slot does not decode answers `null` on the lookups, like any malformed id.
- `reference/runtime-api.adoc`: `decode<TypeName>` returns `null` when a key value is not a value of
  its column; add `decodeColumns` beside `decodeValues`.
- `reference/directives/defaultTenant.adoc` and `how-to/apollo-federation.adoc` need no change unless
  the implementer finds a sentence the new rule contradicts.

## Tests

All tests are execution tier in `graphitron-sakila-example`, pinning outcomes and not generated text.
Write the wrap cases first, as tests that fail on today's tree, so the goal's "today" column is pinned
before the fix.

- `GraphQLQueryTest`:
  - `node` with `Customer:4294967297`: `null`, no errors. Today this answers customer 1.
  - `node` with `Customer:abc`, and with `Customer: 1` (padded): `null`, no errors.
  - `nodes` over `[<customer 1>, Customer:4294967297, Customer:abc]`: `[row, null, null]`, no errors.
  - `filmsByNodeIdArg` with `Film:4294967297`: the client error naming the id and "not a valid Film id".
- `FederationEntitiesDispatchTest`: its `execute` helper already asserts no errors, which is half the
  assertion.
  - A `Customer` node-id representation with `4294967297`, and one with `abc`, each beside a valid rep:
    a `null` slot, and the valid rep resolves.
  - The `City` `@key(fields: "cityId")` representation with `cityId: 4294967297`: a `null` slot, not
    city 1.
- `TenantDivinedRoutingExecutionTest`:
  - A `FilmActor` id whose tenant slot is `abc`, on `node`, `nodes` and `_entities`: `null` with no
    errors, the rest of the batch resolves, and no tenant connection opens.
  - `filmsByWireId(filmId: "4294967297")`: a client error and zero connections opened. Today this
    routes to tenant 1.
  - `deleteFilmActorByNodeId` with a `FilmActor` id whose key wraps onto an existing row: an error, and
    that row is still present.
  - The existing `"abc"` tenant tests stay green unchanged.
  - `filmsByWireId(filmId: " 1")` keeps today's coercion and answers tenant 1's films, pinning the
    trim in the authored rule.
- `ConverterFilterExecutionTest`, the converter-backed key, which fails on today's tree at bind time:
  - `node` with a `ConverterCampusTerm` id whose `org_code` slot is `abc`, and one whose slot is
    `99999999999999999999`: `null`, no errors. Today `Long.valueOf` throws inside the statement.
  - `node` with a `ConverterCampusTerm` id whose `org_code` slot is `0186` beside the real `186` term:
    `null` for the alias, the row for the real id.
  - `converterCampusTermByNodeId` with the `abc` id: the malformed-id client error, before SQL.
- `ConvertedTenantRoutingExecutionTest`: `sites(ownerOrg: "99999999999999999999")` gives a client error
  with no connection opened. This one passes on today's tree, because `OrgCodeStringConverter.to`
  throws `NumberFormatException` and `canonicalTenantKey` already maps it; it is a regression pin
  for the move of `roundTrip` into `ColumnValues`, not a wrap case. The existing `"0186"`,
  `sitesIn(["0186", "186"])` and `"abc"` tests stay green unchanged.
- A mutation-rail "never written" case for a non-tenant node: an update keyed by `Film:4294967297`
  errors, and film 1 is unchanged. Put it in `DmlBulkMutationsExecutionTest` or wherever the UPDATE
  rail's wrong-type cases live.
- `NodeIdValueAgreementExecutionTest`, the `emitPrepare` rail over the `agreeStorageBin` fixture.
  Both cases fail on today's tree, which answers `bin_id=1`:
  - `agreeStorageBin(in: {binId: <StorageBin:4294967297>})` alone: the rail's decode failure, and the
    service never runs. Assert that the way `serviceRecord_twoWritersDisagree_throwsAndServiceNeverRuns`
    already does.
  - The same id beside `binIdText: "1"`: the same failure. Today the wrapped key agrees with the
    plain writer.
  - The existing format-variant case (`binIdText: "01"` beside the bin 1 id) stays green unchanged.
- The precision clause and the numeric-value choice: no sakila key column is narrow, so call the
  generated `ColumnValues` directly over `SQLDataType.SMALLINT`, `NUMERIC(4)`, `NUMERIC(4,1)`, and
  `BIGINT` converted through `OrgCodeStringConverter`. This covers both rules on the probe's rows,
  the precision and scale refusals, `"0186"` (refused by `exact`, accepted at `186` by `coerce`), and
  needs no DDL change. Add one converter whose `to` truncates silently, defined in the test, to pin
  that `exact` refuses a converter-side wrap that `from` does not give back.

## Neighbours, not absorbed

- **R1036**: the redacted message on the DML and lookup rails. That item makes those rails say
  "Invalid node id"; this item makes sure they are reached.
- **R267**: replace the deprecated `DataType.convert`. After this item the decode-side call is in
  `ColumnValues` alone.
- **R1044**: `requireColumnAgreement`'s converter-backed comparison. This item builds the emitted
  round-trip primitive R1044's notes ask about, but `requireColumnAgreement` and its `convert` stay
  where they are; whether agreement compares through `ColumnValues` is R1044's to decide. On the
  `emitPrepare` rail the node-id side of the comparison now arrives canonical. That leaves R1044's
  question unchanged: a plain writer's raw value is still compared through `convert` alone.
- **R534**: the null-flag erasure from `fromArray`, which is adjacent but unchanged here.
- **Cursor decode and `ID` arguments bound by coercion.** Two more sites share this bug class and
  belong to a follow-up that routes them through `ColumnValues`. The first is
  `ConnectionHelper.decodeCursor`, which is server-minted and so takes the `exact` rule; today it
  checks only for `null`. The second is `CallSiteExtraction.JooqConvert` arguments bound through
  `DSL.val(...).getValue()`, which are authored and so take the `coerce` rule.

## Other solutions we've considered

- **One lenient rule everywhere**, accepting `"1.5"` and `" 1234"` as aliases of a node id. This
  rule has to detect each lossy coercion by name, and the `-32768` case shows the coercion varies by
  dialect and binding. The exact rule needs no such catalogue, because ids have an exact image.
- **The exact rule for tenant arguments too.** It breaks the documented `"0186"` and `"1.5"` coercion
  for no gain: a coerced tenant still routes to the tenant the SQL reads, which is the principle
  section 1 states.
- **Catching at the fetcher**, by wrapping `fetchEntities` in `ErrorRouter`. `[_Entity]!` still nulls
  `data`, and the documented outcome for a malformed id on a lookup is a `null` slot, not an error.
- **Checking the result of `convert` alone, with the converter round trip kept in the tenant path.**
  This was the first draft. On a converter-backed column `convert` passes a user-type input through,
  so the check accepts `"abc"` and the converter throws at bind, which is the defect the goal closes.
  It would also leave two decode-side coercions, one per runtime class.
- **Letting the database refuse it.** A wrap produces a valid value of the column, so the database
  never sees the overflow, and a read never refuses at all.

## Implementation notes

Landed as specified, with these choices the spec left open:

- `ColumnValues` is a new global unit kind, `COLUMN_VALUES`, minted by `GeneratedUnits.columnValues()`
  and emitted on every run. It is schema-independent, so it joins the frozen-scaffold kinds in
  `PlanCompileGraph`. Its inbound edges are precise from `NodeIdEncoder`, and declared from
  `TenantConnections` and `EntityFetcherDispatch`, since only a multi-tenant build or a `@key`
  alternative references it there.
- The precision clause also requires `DataType.isNumeric()`. A temporal type reports a precision
  that counts fractional seconds, and a converter from such a column to a `Long` would otherwise be
  read against it.
- `decode<Type>` loads the canonical values with `rec.set(col, col.getType().cast(values[i]))`,
  keeping the changed flags the old `rec.set` produced.
- The jOOQ-record decode rails pass the record's *target* columns to `decodeColumns`, which are
  the columns those rails write.
- The argument rails were confirmed: every `decode<Type>` caller throws on `null` (or, on the
  multitable pruning path, turns it into a false condition behind a guard that throws when no
  branch decodes the id). None needed an edit.
- The direct `ColumnValues` coverage is `ColumnValuesTest`, at the unit tier beside
  `ConstraintViolationsWireShapeTest`, since it needs no database. The "never written" UPDATE case
  is `NodeIdValueAgreementExecutionTest.mutationUpdate_setReferenceWhoseKeyWrapsItsColumn_writesNothing`,
  a wrapping `Film` reference on a SET.
- `TenantRoutedFetcherPipelineTest` pins no part of the new decode or grouping text. The emitted
  `handle<Type>` body changed, and the matches on it this item first rewrote were retired in the
  Done round 4 rework. What they stood for is pinned at the execution tier, by
  `TenantDivinedRoutingExecutionTest`'s tenant-slot cases and `FederationEntitiesDispatchTest`'s
  `@key` cases. No execution fixture has a `@key` alternative over the tenant key column (every
  tenant-routed `@key` entity in the sakila schemas reads `@defaultTenant`), so that arm's tenant
  canonicalisation is pinned only through the node-id arm, which shares it.

## Retired vocabulary

- `TenantConnections.roundTrip` and the `TENANT_COLUMN_CONVERTER` field: the converter round trip
  now lives in `ColumnValues`, which reads the converter off `TENANT_COLUMN_TYPE`.
- `HandleMethodBody`'s `divinedTenant(tenantEntry.getKey())` group-key call: the group key is the
  canonical tenant from `tenantKeyOrNull`.

## Reviewer findings

### Round 1 (Spec → Ready): request revisions, session_01EUZJP9WzXawm9B85Tt8mq2, 2026-10-19

The goal is clear and the diagnosis checks out. Every symbol, test class and schema field the spec
names exists as named, the mechanism table reproduces exactly against jOOQ 3.20.11 (`hasPrecision()`
is false for `SMALLINT`/`INTEGER`/`BIGINT`, so the integer types rely on clause 3 and the precision
clause bites only on `NUMERIC`), and the per-surface outcomes match the emitters. One finding blocks.

**F1 (question 1, viability; question 2, fit): the primitive stops before the converter, so on a
converter-backed column an undecodable value still reaches SQL.** `ColumnValues.exact` and `coerce`
test the value `DataType.convert` returns. On a column whose `DataType` is a converted type, that is
the *user* type, and when the user type is `String` (`OrgCodeStringConverter`, the sis shape the
fixture mirrors) `convert` is the identity on a `String` input: probed, `convert("abc")` gives `"abc"`
and `convert("0186")` gives `"0186"`, with `isNumeric()` true and `hasPrecision()` false. So under the
`exact` rule as written, a `ConverterCampusTerm` id whose `org_code` slot is `abc` (or
`99999999999999999999`) passes all four clauses: non-null, `toString()` equals the slot text, and the
result is not a `Number`. It is then bound, and `OrgCodeStringConverter.to` throws
`NumberFormatException` inside the statement: a redacted error on an argument, and out of
`fetchEntities` on `_entities`, where it nulls all of `data`. That is the defect the goal says is
closed ("a value that does not decode is never bound into SQL"), on the column shape the Evidence
itself suspects behind `LANDNR`. The same gap makes `"0186"` an accepted alias of an id the encoder
writes as `"186"`, contradicting the claim that `exact` refuses `"01"`. On the `coerce` side the wrap
clause compares "the coerced value" with a `BigDecimal`, which on this column is a `String`
(`"0186"`) against `186`; the spec does not say which side of the converter the comparison reads, and
the literal reading breaks the `"0186"` test it promises stays green.

The tenant path hides this only because `tenantKeyOrNull` runs `roundTrip` after `coerce`. That
leaves the converter round trip in `TenantConnections` alone while every non-tenant key slot goes
without it, which is two decode-side coercions rather than the one the design section claims
`ColumnValues` holds. R1044 (`column-agreement-follows-the-database-value.md`) already names the same
need and asks whether "the round trip becomes one emitted primitive that `NodeIdEncoder` and
`TenantConnections` both call".

What would satisfy it: the spec states where the converter sits in both rules, so that a value is
"a value of the column" only if it survives the trip to the database type (and, for `exact`, back to
the slot text), with a converter `IllegalArgumentException`/`DataTypeException` read as absent, and
says which value the wrap and precision clauses inspect on a converted column. Say too whether
`tenantKeyOrNull` then reduces to `ColumnValues` (and `roundTrip` moves into it) or keeps its own
trip, and how that relates to R1044. Add an execution case that fails today on a converter-backed key:
`node(id:)` over a `ConverterCampusTerm` id whose `org_code` slot is `abc`, answering `null` with no
error, plus the same id on the `ConverterCampusTerm` `@nodeId` filter root giving the malformed-id
client error.

> **Author (round 1):** Addressed in Design. Both rules now run on one *column trip* (`convert`, then
> the converter's `to` and `from`), so a converter-backed value is a value of the column only if it
> reaches the database type; `exact` compares the canonical value's text with the slot, which also
> refuses `"0186"`. The wrap and precision clauses read the trip's numeric value (canonical if a
> `Number`, else database), and the wrap clause accepts either the input number or its integral part,
> so `"0186"` passes `coerce` at `186`. `roundTrip` and `TENANT_COLUMN_CONVERTER` move into
> `ColumnValues`, `tenantKeyOrNull` is `coerce` alone, and R1044's note is answered in Neighbours.
> `ConverterFilterExecutionTest` gains the `ConverterCampusTerm` `abc`, overflow and `0186` cases on
> `node` and the `@nodeId` filter root.

### Non-blocking

- `ConvertedTenantRoutingExecutionTest`'s proposed `sites(ownerOrg: "99999999999999999999")` case
  passes on today's tree: `Long.valueOf` in `OrgCodeStringConverter.to` throws
  `NumberFormatException`, an `IllegalArgumentException`, which `canonicalTenantKey` already maps to
  the client error. It is a regression pin, not one of the "fail on today's tree" wrap cases; label it
  so.

  > **Author (round 1):** Relabelled in Tests as a regression pin for the `roundTrip` move.

- The `coerce` wrap clause reads "the text as a `BigDecimal`", but jOOQ trims and `BigDecimal` does
  not: `" 1234"` coerces to `1234` today and `new BigDecimal(" 1234")` throws. Say whether such a
  value is refused or compared after trimming, since the authored rule promises the documented
  coercion stays.

  > **Author (round 1):** Settled in the authored rule: the input is read with
  > `new BigDecimal(text.trim())`, matching jOOQ's trim, so `" 1234"` keeps coercing; a
  > `filmsByWireId(filmId: " 1")` test pins it. `exact` still refuses it.

### Round 2 (Spec → Ready): request revisions, session_01Fv45gxYwZiArbtYEgLe29C, 2026-10-19

Round 1's finding is resolved. The column trip puts the converter in both rules, and it holds
against jOOQ 3.20.11. `getConverter()` gives `IdentityConverter` on an unconverted type. A
`NUMERIC(4,1)` converted type reports precision 4 and scale 1. `OrgCodeStringConverter.from` gives
`"186"`. `exact`'s "an id graphitron minted always passes" also holds beyond the numeric types:
`toString` survives `convert` for `LocalDate`, `LocalDateTime`, `OffsetDateTime`, `Timestamp`,
`LocalTime`, `UUID`, `NUMERIC(10,2)` `1.50`, `double`, `real` and `boolean`. Every symbol, test
class, schema field and fixture row the spec names exists as named. One finding blocks.

**F1 (question 1, viability): the inventory of raw decode sites misses
`JooqRecordInstantiationEmitter.emitPrepare`, so one write rail still loads lenient strings.** The
generator emits `decodeValues` in eight places. The spec covers `NodeIdEncoderClassGenerator`
(`buildPerTypeDecode`), `HandleMethodBody`, `RecordDecodeFragments` and the two in `emitKeyDecode`.
It does not cover the three in `emitPrepare`. That is the prepare phase a jOOQ-record writer takes
when more than one writer lands on a column. It declares `String[] <base>Keys = decodeValues(...)`
and checks only the arity. `emitLoadPrepared` then loads those keys with `rec.fromArray`, and
`emitAgreement` feeds them to `requireColumnAgreement`. A wrapping key there is written as the
wrapped row. It also *agrees* with a plain writer that names that row: a `StorageBin` id carrying
`4294967297` beside `binId: 1` passes the check and writes bin 1. That breaks "a value that does not
decode is never bound into SQL", the half of the goal the spec calls urgent. The spec also says "no
generated typed decode calls it directly any more" and that the argument rails need no edit. Read
together, those tell the implementer the listed sites are the whole set. The backstop ("a rail found
without one gets it") only covers `decode<Type>` callers, and `emitPrepare` is not one.

What would satisfy it: add `emitPrepare` to the `RecordDecodeFragments` / `emitKeyDecode` bullet.
Say that `<base>Keys` becomes the `Object[]` from `decodeColumns`. Say what `requireColumnAgreement`
then receives on this path: canonical values from the node-id writer, raw values from a plain
writer. Then confirm the boundary with R1044 still holds as the Neighbours section states it. Add an
execution case that fails on today's tree, for example in `NodeIdValueAgreementExecutionTest` over
the `agreeStorageBin` fixture: a `StorageBin` id whose key wraps onto an existing bin, with or
without a plain writer, gives the rail's decode failure and nothing is written.

> **Author (round 2):** Addressed in Implementation and Tests. The `decodeValues` bullet now lists
> all eight emitted call sites. A new `emitPrepare` bullet gives it the same `decodeColumns` swap in
> all three arms, with `<base>Keys` as `Object[]` under the existing arity throw. It also says what
> `requireColumnAgreement` receives: a canonical node-id value beside a plain writer's raw value,
> both through `convert`, so an id that decodes compares as it does today. The R1044 neighbour bullet
> says its question is unchanged. `NodeIdValueAgreementExecutionTest` gains two `agreeStorageBin`
> cases that fail on today's tree: a wrapping `StorageBin` id alone, and the same id beside
> `binIdText: "1"`.

### Round 3 (Spec → Ready): sign off, session_01MrgQ7xqp1TGSjwbLJ7op9G, 2026-10-20

Round 2's finding is resolved: the generator emits `decodeValues` in exactly the eight places the
Implementation section lists, and the `emitPrepare` bullet holds against the code
(`requireColumnAgreement` takes `Object`, so `Object[]` keys need no edit to `emitAgreement`). Both
questions are answered, and the spec is Ready.

Non-blocking, for the implementer:

- `schema.graphqls` has no UPDATE keyed by a `Film` id (`FilmUpdateInput` keys on `filmId: Int`).
  The "never written" DML case can use a `Film` reference on a SET (`UpdateBookmarkOverlapInput.filmRef`)
  or the non-tenant `deleteFilmActorByNodeId`; the spec's "or wherever" already allows either.
- "`"1.5"` is accepted at `1.5` on a `NUMERIC`" holds only where the scale admits a fractional
  digit; on `NUMERIC(4)` the precision clause refuses it, which is what the direct `ColumnValues`
  test over that type should pin.
- `TenantConnections` is emitted in the `schema` sub-package and `ColumnValues` in `util`, so
  `ColumnValues` and its two methods are public.

### Round 4 (In Review → Ready): request rework, session_01Th9aVUVqAvRX94isXsEQnJ, 2026-10-20

The runtime change is the one the spec approved, and it works. `ColumnValues` holds the column trip
with `exact` and `coerce` as specified. `decodeColumns` replaces `decodeValues` at all eight emitted
sites (the generator now emits `decodeValues` only from inside `decodeColumns`). The `@key` arm
coerces, and the tenant group key is canonicalised before grouping. `roundTrip`,
`TENANT_COLUMN_CONVERTER` and the `divinedTenant(tenantEntry.getKey())` call are gone from the source
tree, so the retirement sweep is clean. The user docs say what the Documentation section asked for.
Every test the Tests section names exists and passes, and together they demonstrate the goal: the
`Customer:4294967297` lookup answers `null`, the wrapping `StorageBin` and `Film`-reference writes
fail with nothing written, the tenant-slot `abc` on `_entities` leaves `data` intact, and
`ColumnValuesTest` pins the precision, scale and converter-side refusals. The choices listed under
Implementation notes (unit tier for `ColumnValuesTest`, `isNumeric()` on the precision clause,
target columns on the jOOQ-record rails) are recorded in the plan body, so they are not findings.

Verification: `mvnd install -Plocal-db` on bad00d4 (the delivery plus incoming trunk). After a
re-seed for R1062's `init.sql` change, which this rebase brought in, every R1067 test passed. The one
failure left, `GraphQLQueryTest.filmsByTitleBean_absentInput_buildsNoBeanAndLeavesTheFieldUnfiltered`,
saw a film row another test had inserted while the suites ran concurrently. It passes when
`GraphQLQueryTest` runs alone (482 run, 0 failures), and it is outside this item's surface.

**F1 (question 1: the delivery adds assertions on generated Java text).** The Tests section says these
tests pin "outcomes and not generated text", and `docs/architecture/how-to/testing.adoc` bans
code-string body matching at the pipeline tier. `TenantRoutedFetcherPipelineTest` renders the
generated `handle<Type>` body with `MethodSpec::toString`. Two of its tests match that text. The
emitted shape changed, and the delivery rewrote those matches to pin the new body instead of retiring
them:

- `tenantScopedDispatchGroupsPerTenantAtTheDecodedPosition` adds
  `contains("TenantConnections.tenantKeyOrNull(cols[1])")`, `contains("if (tenantOfRep == null) continue;")`,
  `contains(".computeIfAbsent(tenantOfRep, k -> new java.util.ArrayList<>())")` and
  `doesNotContain("divinedTenant(")`;
- `directKeyEntityDispatchGroupsPerTenantAtTheRepFieldPosition` adds `contains("ColumnValues.coerce(")`,
  `contains("rep.get(\"filmId\"))")`, `contains("if (cols[1] == null) continue;")`,
  `contains("TenantConnections.tenantKeyOrNull(cols[1])")` and `contains(".computeIfAbsent(tenantOfRep, ")`.

This is the pattern R1066's Done round sent back. The execution tier already pins the node-id arm's
behaviour, in `TenantDivinedRoutingExecutionTest`'s three tenant-slot `abc` cases on `node`, `nodes`
and `_entities`. The `@key` arm's coercion is pinned by
`FederationEntitiesDispatchTest.entities_directKeyValueWrapsItsColumn_yieldsNullSlotNotTheWrappedRow`.

What would satisfy it: remove the text assertions this delivery added or rewrote in those two tests.
Where a test is left with no behavioural assertion, delete the test, or replace it with a structural
check on the `TypeSpec` (method names and signatures). Do not pin the body again. If the `@key` arm
over a tenant-scoped key column is judged to need its own outcome pin once the text goes, add it at
the execution tier. The spec does not demand that case, so it is the implementer's call. Nothing else
in the delivery needs to change.

> **Author (round 4):** Addressed in `TenantRoutedFetcherPipelineTest`. Every match the delivery
> added or rewrote on the generated `handle<Type>` body is gone from both tests. The lines that
> predate this item stay as they were. The comments now point at the execution cases that pin the
> behaviour. No execution case was added for a `@key` alternative over the tenant key column. Such
> a case needs a new multitenant fixture, and the spec does not ask for one; Implementation notes
> records the gap.
