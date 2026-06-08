---
id: R283
title: "Lift-back projection for child @service fields returning a table-bound type (ServiceTableField)"
status: Backlog
bucket: bug
priority: 2
theme: service
depends-on: []
created: 2026-06-08
last-updated: 2026-06-08
---

# Lift-back projection for child @service fields returning a table-bound type (ServiceTableField)

A child field carrying `@service` whose method returns a jOOQ table `Record` (a `ChildField.ServiceTableField`) is emitted as a terminal record producer: the generated rows method returns the service result verbatim and never re-projects it through a Graphitron query. Scalar `@field` sub-fields resolve, because a `ColumnFetcher` reads them straight off the returned `XRecord`; but any non-column sub-field on the returned type fails at query time. The first such sub-field to ship is a `@reference` relation.

## Symptom

In `opptak-subgraph`, `Sak.saksdokumenter` is a list field with `@service(... SaksdokumenterPaSakService.saksdokumenterForSak)` returning `List<SaksdokumentRecord>`, and the `Saksdokument` type has `dokument: Dokument @reference(...)`. A query selecting `saksdokumenter { dokument { id } }` fails:

```
ExceptionWhileDataFetching path=[sakerV2, nodes, 0, saksdokumenter, 0, dokument]
java.lang.IllegalArgumentException: Field "dokument" is not contained in row type
  (organisasjonskode_saksbehandler, soker_id, opptakstype_kode, opptak_kode, dokument_lopenummer, er_lest)
```

Reproduced by `SakSaksdokumenterShapeTest.sakMedEksisterendeSaksdokumentResolvesViaGraphQL` and `.soknadsdokumentSyntetiseresViaGraphQL`.

## Root cause

The model has two sibling batch-key field shapes that should both project the returned type, but only one does:

* `ChildField.SplitTableField` (`TypeFetcherGenerator` dispatch, around line 387) emits `buildSplitQueryDataFetcher` plus `SplitRowsMethodEmitter`: a VALUES-join SELECT that projects `Type.$fields(selectionSet, table, env)`. References materialize as correlated `DSL.multiset(...).as("name")` columns on the row.
* `ChildField.ServiceTableField` (around line 378) emits `buildServiceDataFetcher` plus `buildServiceRowsMethod`, whose body is `return Service.method(keys);`. There is no `$fields` call and no SELECT.

A `@reference` sub-field is not a stored column; it is a correlated multiset that exists only when the row was built by `Type.$fields(...)`. The service path bypasses `$fields`, so the multiset is never on the record, and the reference's registered datafetcher (`((Record) src).get("dokument", Result.class)`) throws.

The assumption is explicit in `buildServiceRowsMethod`'s Javadoc (`TypeFetcherGenerator.java` around line 4566): "graphql-java resolves columns off whatever records or values the developer returns, so no per-record projection step is needed." That holds for stored columns only. The model treats a service's table-bound return type as a carrier of values to read, when it should treat it as a key source to re-project.

## Why it ships silently

`validateServiceTableField` (`GraphitronSchemaValidator.java` around line 852) validates the join path, the presence of a `Sources` parameter, and the parent table's primary key. It never inspects the return type's sub-fields, so a `ServiceTableField` whose returned type has a `@reference` (or any non-column) sub-field passes validation and fails only at execution.

## Why it surfaced now

Every `ServiceTableField` exercised before this selected only stored-column sub-fields. The one place projection-over-service-records already works is the mutation source-record-carrier path (R268 and R275): there the data field is classified as a `SplitTableField` on the payload, not a `ServiceTableField`, so it routes through `buildSplitQueryDataFetcher` and does run `$fields`; the service merely deposits a record into the payload and a separate split field re-projects it. `Sak.saksdokumenter` is the first field that is itself a `ServiceTableField` whose element type has a non-column sub-field, so it walks straight into the verbatim-return arm with no projection in front of it.

## Fix direction

Bring `ServiceTableField` to `SplitTableField` parity: treat the service result as a key source and lift it back through a `$fields`-projecting query in the same rows method.

1. Call the service to obtain its records, preserving the `MAPPED_SET` parent association or `POSITIONAL_LIST` ordering already encoded by `LoaderRegistration`.
2. Extract each returned record's primary key; build a VALUES table of those keys with an `idx` for parent and order association.
3. JOIN the bound table and project `Type.$fields(selectionSet, table, env)`, reusing the `SplitRowsMethodEmitter` machinery.
4. Scatter back to parents.

This is reuse, not new invention; the VALUES-join, `$fields`, scatter shape already exists for Split fields and for the R268 payload data field.

Two properties fall out, both desired:

* Re-querying only the returned primary keys preserves any filtering or authorization the service applied; Graphitron re-projects exactly the rows the service selected, it does not re-select the whole table.
* A returned key with no matching row drops out of the join. This is the agreed semantics for opptak after the schema is corrected to stop synthesizing rows for entities that do not exist (virtual nodes are being removed): the synthesized saksdokument simply does not appear, with no special-casing.

## Scope and edge cases

* Order preservation and parent re-association across the lift, via the `idx` carry the Split emitter already does.
* Both loader containers: `MAPPED_SET` (`Map<key, List<record>>`) and `POSITIONAL_LIST`.
* `ServiceRecordField` returning a non-table `@record` type is out of scope: there is no bound table to re-project and no multiset sub-fields, so the verbatim return is correct for that shape.
* Single-cardinality `ServiceTableField`, if any, follows the same lift with single-row scatter.

## Validator follow-up

Add a guard so this cannot regress silently. Until the lift-back lands, a `ServiceTableField` (or any service field over a table-bound return type) whose return type carries a non-column sub-field should be a generation-time error rather than a runtime `IllegalArgumentException`. Once the lift-back lands, the guard becomes a safety net for future shapes rather than a rejection.

## References

* R275 `source-record-carrier-service-error-channel`: sibling projection defect on the mutation source-record-carrier shape; the `SplitTableField`-on-payload route is the prior art for the lift.
* R268: the `@table`-bound data-field arm-switch inside Outcome payloads.
* R52 `lift-operation-taxonomy`: the abstract lookup-vs-query lift axis that this projection step is an instance of.
