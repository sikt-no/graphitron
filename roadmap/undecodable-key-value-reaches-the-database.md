---
id: R1067
title: "A node id or tenant value that does not fit its column is not caught at decode"
status: Spec
bucket: bug
priority: 3
theme: nodeid
depends-on: []
created: 2026-10-19
last-updated: 2026-10-19
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

**A server-minted value: the exact inverse of the encoder.** This covers every key slot of a node id,
the tenant slot included. `NodeIdEncoder.encode` writes each key value as `v.toString()`, so the
decoder accepts exactly the encoder's image: a slot decodes iff all four of these hold:

1. coercion does not throw;
2. the result is non-null;
3. `result.toString()` equals the slot text;
4. when the column's `DataType` declares a precision (`hasPrecision()` and `precision() > 0`) and the
   result is a `Number`, the result fits that precision and scale.

An id graphitron minted always passes, because every stored value fits its column. This rule refuses
`"abc"`, a wrapped number, `" 1234"`, `"01"` and `"1.5"`, none of which the encoder writes.

**An authored wire value: the documented coercion, minus the wrap.** This covers a tenant argument, a
`@tenantFanOut` narrowing value, and a key value in a non-node-id federation `@key` representation.
These are values a client typed, and `tenant-scoping.adoc` section 1 documents their coercion: `"1.5"`
binds as `1`, and on the converter-backed tenant `"0186"` routes to `186`. That coercion stays. A value
is refused when any of these holds:

- coercion throws or gives `null`;
- the column's `DataType` is numeric and the coerced value differs from the integral part of the text
  read as a `BigDecimal`. That is a wrap, and the check refuses it without disturbing the documented
  `"1.5"` truncation;
- the precision clause above fails.

### One primitive, value or absent

Both rules live in a new generated runtime class `ColumnValues` in the output's `util` package. Its
unit name is minted through `GeneratedUnits`, like `NodeIdEncoder` and `TenantConnections`. It exposes
two methods:

- `static <T> T exact(DataType<T> type, String text)`
- `static <T> T coerce(DataType<T> type, Object value)`

Each returns the typed value, or `null` when the value is not a value of the column, and neither
throws. `ColumnValues` holds the only decode-side coercion call, so R267's replacement of the
deprecated `DataType.convert` becomes a swap at this site plus `requireColumnAgreement`, which belongs
to R1044. Neither surface lives below the primitive: the lookups skip on `null`, the argument rails
and the tenant fold throw on `null`. The skip-or-throw choice belongs to the surface, not to the
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
  and `coerce` as specified above. It is emitted wherever `NodeIdEncoder` or `TenantConnections` is.
  Emitting it unconditionally is fine if that is simpler, since it is a few methods with no schema
  input. Register the unit in `GeneratedUnits` beside its siblings.
- **`NodeIdEncoderClassGenerator`**:
  - Emit `static Object[] decodeColumns(String expectedTypeId, String base64Id, Field<?>... columns)`.
    It calls `decodeValues`, checks the arity against `columns.length`, then runs `ColumnValues.exact`
    per slot. It returns the typed values, or `null` if any step fails.
  - `buildPerTypeDecode` builds `decode<Type>` on `decodeColumns`, so a `null` from `decode<Type>` now
    also means "a value does not fit". The typed `RecordN` return shape is kept, and every
    `decode<Type>` caller is untouched.
  - `decodeValues` stays: `reference/runtime-api.adoc` documents it as public runtime API returning
    raw strings. No generated typed decode calls it directly any more.
- **`RecordDecodeFragments.decodeHelper`** and **`JooqRecordInstantiationEmitter.emitKeyDecode`**:
  replace `decodeValues` plus `fromArray(String[], ...)` with `decodeColumns` plus
  `fromArray(Object[], ...)`, and keep the existing throw on `null`. Since `exact` refuses `null`,
  every value `fromArray` loads is non-null, so R534's null-flag erasure is not newly exposed.
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
  - New non-throwing core `tenantKeyOrNull(Object)`: `ColumnValues.coerce` on the tenant column's
    type, then the existing converter `roundTrip`.
  - `canonicalTenantKey` becomes the throwing layer over it. Its documented contract is unchanged; it
    now also refuses a wrap. `tenantKeyOf`, `agreeOnTenant` and `fanOutDomain` are unchanged and
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
- `ConvertedTenantRoutingExecutionTest`: `sites(ownerOrg: "99999999999999999999")` gives a client error
  with no connection opened, and the existing `"0186"` routing test stays green.
- A mutation-rail "never written" case for a non-tenant node: an update keyed by `Film:4294967297`
  errors, and film 1 is unchanged. Put it in `DmlBulkMutationsExecutionTest` or wherever the UPDATE
  rail's wrong-type cases live.
- The precision clause: no sakila key column is narrow, so call the generated `ColumnValues` directly
  over `SQLDataType.SMALLINT`, `NUMERIC(4)` and `NUMERIC(4,1)`. This covers both rules on the probe's
  rows plus the precision and scale refusals, and needs no DDL change.

## Neighbours, not absorbed

- **R1036**: the redacted message on the DML and lookup rails. That item makes those rails say
  "Invalid node id"; this item makes sure they are reached.
- **R267**: replace the deprecated `DataType.convert`. After this item the decode-side call is in
  `ColumnValues` alone.
- **R1044**: `requireColumnAgreement`'s converter-backed comparison. That is a different question, and
  its `convert` stays where it is.
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
- **Letting the database refuse it.** A wrap produces a valid value of the column, so the database
  never sees the overflow, and a read never refuses at all.
