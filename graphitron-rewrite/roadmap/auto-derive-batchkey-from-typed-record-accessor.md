---
id: R60
title: "Auto-derive BatchKey from typed TableRecord accessor on @record parents"
status: Backlog
bucket: architecture
priority: 6
theme: service
depends-on: []
---

# Auto-derive BatchKey from typed TableRecord accessor on @record parents

A child field on a `@record`-typed parent (`PojoResultType` / `JavaRecordType` with non-null `fqClassName`) returning a `@table`-bound type is rejected today (`FieldBuilder.classifyChildFieldOnResultType`, `:2542-2545`) when the catalog has no FK metadata to derive the batch key from, and is told to add `@batchKeyLifter`. The directive remains the right tool when the parent's batch-key value is a *synthetic* tuple the author computes; it is overkill when the parent's backing class already exposes a typed accessor returning the field's records directly (e.g. `LagreKvotesporsmalSvarPayload.getSvar(): List<SoknadKvotesporsmalSvarRecord>` pointing at `KvoteSporsmalSvar` `@table(table: "soknad_kvotesporsmal_svar")`). In that shape the classifier has every piece it needs at build time — the parent class is reflectable, the accessor's container axis (List/Set/single) and element class (a concrete `TableRecord` subtype) are visible, and the element's table's PK supplies the target key columns — so the lift can be auto-derived with no author surface, paralleling `ServiceCatalog.classifySourcesType`'s reflection of an `@service` method's typed `Sources` parameter (`:594-637`). The intended carrier is a new `BatchKey.AccessorRowKeyed` permit (sibling to `LifterRowKeyed`) holding a `JoinStep.LiftedHop` plus an `AccessorRef(parentClass, methodName, container, elementClass)`; routing into `RecordTableField` / `RecordLookupTableField` is unchanged, the column-keyed DataLoader path is reused, and `buildRecordParentKeyExtraction`'s sealed switch grows a third arm. Splitting `LifterRowKeyed` and `AccessorRowKeyed` rather than overloading the former keeps each variant's invariant tight (per *Sealed hierarchies over enums* and *Narrow component types*): `LifterRowKeyed` always traces back to the directive resolver; `AccessorRowKeyed` always traces back to the auto-derivation in the classifier. Closes the in-the-wild `LagreKvotesporsmalSvarPayload.svar` rejection without forcing the author to either author a no-op `@batchKeyLifter` or restructure the schema to declare the element type as `@record(record: {className: "...TableRecord"})` (which works as a workaround but gives up `@table`-driven projection of the element's own children — joins, paginated child connections, etc.).
