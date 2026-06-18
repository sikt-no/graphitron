---
id: R329
title: "Re-admit @service carrier payloads with a record-composite data field (land R75 Phase 3)"
status: Backlog
bucket: architecture
priority: 7
theme: mutations-errors
depends-on: []
created: 2026-06-18
last-updated: 2026-06-18
---

# Re-admit @service carrier payloads with a record-composite data field (land R75 Phase 3)

An `@service` mutation whose method returns a list (or single) of a
consumer-authored composite record — a POJO that bundles several jOOQ
table records, e.g. one `UtdanningsspesifikasjonRecord` plus a
`List<UtdanningsmulighetRecord>` — cannot be expressed as a payload today.
The natural SDL is a two-level carrier: a payload whose data field is a
list of an intermediate result type, that result type's children being
`@table`-typed and `@field`-mapped onto the composite's components:

```graphql
type OpprettXOgYPayload {
    results: [OpprettXOgYResult]          # data field: a record composite, NOT @table
    errors: [OpprettXOgYError!]
}
type OpprettXOgYResult {                  # reflection-bound to the @service return element
    x: X! @field(name: "xRecord")         # X is @table-backed
    ys: [Y] @field(name: "yRecords")      # Y is @table-backed
}
type Mutation {
    opprettXOgY(input: [OpprettXOgYInput!]!): OpprettXOgYPayload
        @service(service: {className: "...", method: "opprettXOgY"})
}
```

This fails the build with a dangling-type-reference author error
(`GraphitronSchemaBuilder.rejectDanglingTypeReferences`): the payload
classifies on neither axis. The result-axis binding
(`RecordBindingResolver.groundProducerResult`) skips the wrapper under the
**cardinality-match guard** — a single-object SDL field produced by a
`List` return is a list carrier whose collection feeds an *inner* list
field (`results`), not the wrapper. The carrier axis
(`groundServicePayloadBinding`) then refuses to ground because it admits
only a payload whose single data field is a **`@table`-typed** object (or
an `[ID] @nodeId(typeName:)` field resolving to a `@table` type); a data
field whose element is a reflection-bound record composite is not
recognized. So the payload binds nowhere, is dropped, and the field
referencing it dangles. (`@record` on the result type is irrelevant — R96
made backing reflection-only; the directive is an ignored hint.)

This shape *was* briefly supported. R75 Phase 2 admitted record-element
data on `@service` mutations (`PojoResultType.NoBacking` carrier promotion
+ `ChildField.SingleRecordIdentityField` identity-passthrough), and R75
Phase 3 was scoped to cover compound `@service` passthrough payloads —
exactly this case — where the consumer constructs the result directly
(see `compound-entity-mutations.md`, R122, "Out of scope" → "Compound on
`@service` mutations"). Phase 3 was never implemented, and R276 then
retired the substrate it would have built on: `PojoResultType.NoBacking`
and `PlainObjectType` are deleted, and carriers now must bind to a
`JooqTableRecordType`. The result is an orphaned capability — R75 Phase 3
has no live tracking item, R122 explicitly defers `@service` compound
payloads to it, and R308 covers only list-*arrival* batching of a carrier
(`@service: [Payload]`), not a record-composite data field.

Note the half-open seam: `BuildContext.scanStructuralPayload` already
`Admit`s a `RecordElement` data field (the `lookAheadVerdict` →
`ResultType` arm), but no binding producer grounds a `tableRef` for that
admission in the `@service` family, so `carrierTableBinding` returns
`null` regardless. The fix lands R75 Phase 3 on top of the post-R276
reflection-driven binding model: ground a carrier (or producer-result)
binding for a payload whose data field is a producer-returned record
composite — single or list arrival — so the payload classifies and its
inner `@field`-mapped `@table` children resolve through the record-backed
path. Surfaced by the utdanningsregisteret Graphitron 10 upgrade
(`opprettUtdanningsspesifikasjonOgUtdanningsmulighet`).
