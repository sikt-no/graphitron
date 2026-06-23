---
id: R357
title: "Derive batch-key source for accessor-keyed @table record fields on a compound DTO in a @service payload list-element"
status: Backlog
bucket: bug
priority: 5
theme: service
depends-on: []
created: 2026-06-23
last-updated: 2026-06-23
---

# Derive batch-key source for accessor-keyed @table record fields on a compound DTO in a @service payload list-element

A `@service`-carrier mutation that returns a single payload whose data field is a **list of free-form compound DTOs** — each DTO exposing two or more `@table`-typed fields backed by populated jOOQ `TableRecord` accessors — fails classification: every such field is rejected with the `resolveRecordParentSource` three-option author error (`FieldBuilder.java:4942`, *"RecordTableField on a free-form DTO parent requires a typed accessor or @sourceRow to lift the batch key; the catalog has no FK metadata for the parent class …"*). The shape is structurally the `PojoResultType.Backed` + `Reader.AccessorCall` path R269 relies on and the `@field(name:)`-remapped accessor path R191 shipped, yet neither the FK-derivation nor the typed-accessor derivation fires here, so the only escape hatch is a hand-written `@sourceRow` `RowN` lifter per field (which forces a redundant key-driven re-fetch of rows the service already populated in memory). Discovered during the `utdanningsregisteret` Graphitron 10 migration.

## Repro

`utdanningsregisteret-graphql-spec` `schema_beta.graphql`:

```graphql
type Mutation {
  opprettUtdanningsspesifikasjonMedUtdanningsmulighet(input: [...]!): OpprettUtdanningsspesifikasjonMedUtdanningsmulighetPayload
    @service(service: {className: "no.utdanningsregisteret.utdanning.UtdanningsspesifikasjonOgUtdanningsmulighetService", method: "opprettUtdanningsspesifikasjonOgUtdanningsmulighet"})
}

type OpprettUtdanningsspesifikasjonMedUtdanningsmulighetPayload {
  results: [OpprettUtdanningsspesifikasjonMedUtdanningsmulighetResult]   # list of compound DTOs
  errors: [OpprettUtdanningsspesifikasjonMedUtdanningsmulighetError!]
}

type OpprettUtdanningsspesifikasjonMedUtdanningsmulighetResult {        # free-form compound DTO, not @table
  utdanningsspesifikasjon: Utdanningsspesifikasjon! @field(name: "utdanningsspesifikasjonRecord")   # ERROR :272
  utdanningsmuligheter: [Utdanningsmulighet]        @field(name: "utdanningsmulighetRecords")        # ERROR :274
}
```

- Service method signature: `List<OpprettUtdanningsspesifikasjonOgUtdanningsmulighetResultRecord> opprettUtdanningsspesifikasjonOgUtdanningsmulighet(...)` — i.e. the carrier produces a `List<ResultRecord>` that lands in the payload's single `results` list field.
- The DTO `OpprettUtdanningsspesifikasjonOgUtdanningsmulighetResultRecord` is a plain POJO (no-arg ctor + all-fields ctor + bean accessors) exposing exactly the typed accessors the error text asks for: `UtdanningsspesifikasjonRecord getUtdanningsspesifikasjonRecord()` and `List<UtdanningsmulighetRecord> getUtdanningsmulighetRecords()` — both element types are real jOOQ `TableRecord`s, and both are fully populated by the service (the spesifikasjon record carries its generated `utdanningsspesifikasjonsnr` after insert).

## What is proven

Reproduced against `graphitron-maven-plugin:10-SNAPSHOT` via `mvn graphitron:generate`:

1. **The DTO class is inferred and on the codegen classpath.** Pointing `@sourceRow(className: "…ResultRecord", method: "bogusLifterProbe")` at the field changes the error to *"no static method named 'bogusLifterProbe' on class 'no.utdanningsregisteret.records.OpprettUtdanningsspesifikasjonOgUtdanningsmulighetResultRecord'"* — so `Class.forName` against the DTO succeeds and `@sourceRow` is the recognised, working escape hatch.
2. **The failure is not the R191 name-remap axis.** Renaming the SDL fields to match the getters exactly (`utdanningsspesifikasjonRecord` / `utdanningsmulighetRecords`, dropping `@field`) produces the **identical** error. The accessor scan never gets as far as comparing names.
3. **`@sourceRow` is the only path that compiles** — and it mandates a hand-written `RowN` key lifter per field plus, for the to-many `utdanningsmuligheter`, a composing `@reference` to express the FK path; both then drive a key-keyed re-fetch of rows already in hand.

## Root cause (leading hypothesis; Spec to confirm the exact site)

`resolveRecordParentSource` (`FieldBuilder.java:4923`) emits the error at `:4942` only when both `deriveFkRecordParentSource` (`:4868`, catalog-FK arm — correctly null for a non-`@table` parent) and `deriveAccessorRecordParentSource` (`:4977`) decline. The accessor helper returns `AccessorDerivation.None` at `:4990` when the parent `ResultType`'s `fqClassName` is null (the case the class doc at `:4658` flags: *"PojoResultType with a null fqClassName falls through"*), before `collectAccessorMatches` (`:5093`) is ever reached. The renamed-field result (proof #2) is consistent with `collectAccessorMatches` not being reached at all — i.e. the **compound DTO in the payload's list-element position is classified as a `PojoResultType` whose backing `fqClassName` was never grounded**, so the accessor derivation has no class to reflect over even though the class exists (proof #1).

The provenance gap is the suspected origin: the carrier produces `List<ResultRecord>`, but that element backing is not propagated onto the `results` list field's element type (`OpprettUtdanningsspesifikasjonMedUtdanningsmulighetResult`) the way a direct single-payload data field is grounded (R276's `groundProducerResult` / cardinality-match path). Spec should confirm whether the fix lives in the grounding step (populate the element `PojoResultType.fqClassName` from the carrier's `List<X>` element) or in the list-element classification seat, and whether the to-one and to-many fields on the same DTO need distinct handling.

## Relations

- **R191** (shipped) — honors `@field(name:)` in `collectAccessorMatches` on free-form `@record` parents; its negative case `ACCESSOR_ROWKEYED_FIELD_NAME_REJECTS_WITHOUT_DIRECTIVE` falls through to this same three-option error. This item is the case where the accessor path fails *upstream of* the name match.
- **R269** (Spec) — the `PojoResultType.Backed` + `Reader.AccessorCall` to-one/to-many record-parent path this shape should classify into; R269 null-guards it. Confirms the target path exists and works for direct (`SingleRecordTableField*`) payload parents.
- **R305** (shipped) / **R308** (Spec) — the `@service`-carrier arrival cluster. R308 fixes the *list-of-payloads* carrier (`@service: [Payload]`); this item is the sibling *single-payload-of-list-of-compound-DTOs* shape, which R305 lists as out of scope and R308 does not cover.
- **`compound-entity-mutations`** — the compound-result mutation shape this exercises (one operation creating a spesifikasjon plus its muligheter, returned as one DTO).

## Out of scope

- Eliminating the redundant re-fetch for in-hand records (whether a record-source `@table` field on a DTO should serialize the embedded record instead of re-querying by key) — that is the broader source-arrival emit question (R305 / R314 territory), not this classification fix.
- The `@field(name:)` name-remap mechanics themselves (R191, shipped).

## Acceptance (sketch)

- **Pipeline tier**: a fixture with a `@service` carrier returning `List<CompoundDto>` into a `Payload { results: [Compound] }`, where `Compound` is a free-form POJO exposing a to-one `@table` accessor and a to-many `List<...Record>` accessor, classifies both fields as `RecordTableField` with `Reader.AccessorCall` (cardinality `ONE` / `MANY`) — no `UnclassifiedField`, structural assertions only.
- **Execution tier (`graphitron-sakila-example`)**: the compound payload round-trips both record fields through the batched key path end-to-end.
- Full reactor green (`mvn -f graphitron-rewrite/pom.xml install -Plocal-db`, incl. `graphitron-lsp`).
