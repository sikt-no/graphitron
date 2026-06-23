---
id: R357
title: "Case-insensitive @table-name match in record-composite carrier accessor resolution"
status: In Review
bucket: bug
priority: 2
theme: service
depends-on: []
created: 2026-06-23
last-updated: 2026-06-23
---

# Case-insensitive @table-name match in record-composite carrier accessor resolution

A `@service`-carrier mutation returning a payload whose data field is a **list of free-form compound DTOs** (each DTO exposing `@table`-typed fields backed by populated jOOQ `TableRecord` accessors) misclassifies every such field as `UnclassifiedField` with the `resolveRecordParentSource` three-option author error (`FieldBuilder.java:4942`, *"RecordTableField on a free-form DTO parent requires a typed accessor or @sourceRow …"*) — **but only when the SDL `@table(name:)` casing differs from the jOOQ table's `getName()` casing.** The driving `utdanningsregisteret` schema writes every `@table(name:)` in legacy Oracle-style UPPERCASE while the Postgres jOOQ tables are lowercase, so the bug fires there; the identical shape with matching casing (R329's fixtures) classifies cleanly.

This item was originally filed as a classification-coverage gap ("derive batch-key source for accessor-keyed fields"). Instrumentation against the real schema reframed it: the R329 record-composite carrier path **already** grounds the DTO and resolves its accessors correctly. A single case-sensitive string comparison drops the match. This is a one-line bug, not a missing classification path. Discovered during the `utdanningsregisteret` Graphitron 10 migration.

## Repro

`utdanningsregisteret-graphql-spec` `schema_beta.graphql` (`@table(name:)` values are UPPERCASE; the jOOQ catalog is lowercase Postgres):

```graphql
type Mutation {
  opprettUtdanningsspesifikasjonOgUtdanningsmulighet(input: [...]!): OpprettUtdanningsspesifikasjonMedUtdanningsmulighetPayload
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

- Service method: `List<OpprettUtdanningsspesifikasjonOgUtdanningsmulighetResultRecord> opprettUtdanningsspesifikasjonOgUtdanningsmulighet(...)`.
- The DTO is a plain POJO (no-arg ctor + all-fields ctor + bean accessors) exposing `UtdanningsspesifikasjonRecord getUtdanningsspesifikasjonRecord()` and `List<UtdanningsmulighetRecord> getUtdanningsmulighetRecords()` — both element types are real jOOQ `TableRecord`s, both fully populated by the service.

## Root cause (confirmed)

Verified end-to-end against the real schema and reproduced minimally on the Sakila catalog. The classification machinery works for this shape:

1. `RecordBindingResolver` grounds `…Result` → the DTO class via the R329 two-level carrier path (`BindsDataFieldElement(results)`); no fold rejection.
2. `…Result` classifies as `PojoResultType.Backed` with the correct `fqClassName`.
3. `collectAccessorMatches` (`FieldBuilder.java:5093`) finds `getUtdanningsspesifikasjonRecord` / `getUtdanningsmulighetRecords`, classifies cardinality (SINGLE / LIST), and resolves their element tables.

The single failing step is the element-table guard at **`FieldBuilder.java:5114`**:

```java
if (expectedSqlName != null && !elementTableRef.get().tableName().equals(expectedSqlName)) continue;
```

The two operands name the same table in different case, because `TableRef.tableName()` is **not case-canonical**: it carries whatever string was passed to `JooqCatalog.TableEntry.toTableRef(...)`, and the two `ServiceCatalog` resolution paths pass different things:

| operand | source | value (ureg) |
|---|---|---|
| `expectedSqlName` (= `tb.table().tableName()`, `FieldBuilder.java:5003`) | `resolveTable(@table-name)` → `toTableRef(sqlName)`, the **verbatim `@table(name:)` string** (`ServiceCatalog.java:57-58`) | `UTDANNINGSSPESIFIKASJON` |
| `elementTableRef.tableName()` | `resolveTableByRecordClass(…)` → `toTableRef(e.table().getName())`, the **jOOQ `Table.getName()`** (`ServiceCatalog.java:61-63`) | `utdanningsspesifikasjon` |

`JooqCatalog.findTable` is case-insensitive (`equalsIgnoreCase`, `JooqCatalog.java:142`), which is why the UPPERCASE `@table` resolves at all; only this one downstream comparison is case-sensitive, so the accessor is dropped, `collectAccessorMatches` returns `[]`, and the field falls through to the misleading three-option error. This construction pair (verbatim `resolveTable` vs jOOQ-canonical `resolveTableByRecordClass`) is the only one in the model that mixes the two casing sources, which is why it is the only site that bit.

This explains the two red herrings recorded on the original ticket: `@sourceRow` "works" only because it passes an explicit `className` and never reaches this comparison; and renaming the SDL field to match the getter exactly *also* failed because the name match is never reached — the table-name guard drops the accessor first (proving the original "fqClassName was never grounded" hypothesis wrong: the DTO **is** grounded; the accessor is discarded one step later).

## Implementation

**Landed (In Review).** The one-line `equals` → `equalsIgnoreCase` change shipped at `FieldBuilder.java:5116` (the planned `:5114` shifted by two lines after a javadoc clarification on `collectAccessorMatches` noting why the two operands disagree on case). The pipeline-tier test landed as a fourth method, `caseMismatchedTableName_classifiesCompositeChildrenAsRecordTableField`, in `ServiceRecordCompositeCarrierPipelineTest`. Verified the test fails pre-fix (both children fall to `UnclassifiedField` with the three-option `resolveRecordParentSource` author error) and passes post-fix; full reactor green (`mvn -f graphitron-rewrite/pom.xml install -Plocal-db`).

One-line change at `FieldBuilder.java:5114`, matching the case-insensitive idiom the codebase already uses for table-name comparison everywhere else (`TypeBuilder.java:799`, `GraphitronSchemaValidator.java:669`, `NodeIdLeafResolver.java:292/323`, `FieldBuilder.java:5720`, `BuildContext.java:2393`):

```java
if (expectedSqlName != null && !elementTableRef.get().tableName().equalsIgnoreCase(expectedSqlName)) continue;
```

Verified: with this change the minimal Sakila reproduction (below) flips both children from `UnclassifiedField` to `RecordTableField`, and `mvn -pl :utdanningsregisteret-graphql-spec graphitron:generate` on the real spec goes from the two author errors to BUILD SUCCESS, classifying via the intended `Reader.AccessorCall` path (no `@sourceRow`, no redundant re-fetch).

## Tests

**Pipeline tier (the load-bearing net).** A `@service` record-composite carrier whose result type's `@table` children declare `@table(name:)` in a case that differs from the lowercase jOOQ catalog name classifies both children as `RecordTableField` (to-one `ONE`, to-many `MANY`) with empty diagnostics — not `UnclassifiedField`. The minimal reproduction reuses R329's `TestFilmWithActorsDto` carrier (`TestServiceStub.createFilmsWithActors`) with the SDL types declared `@table(name: "FILM")` / `@table(name: "ACTOR")` (UPPERCASE) against the lowercase Sakila `film` / `actor` tables; pre-fix both children are `UnclassifiedField`, post-fix both `RecordTableField`. Sits alongside `ServiceRecordCompositeCarrierPipelineTest`. Assert the classification **verdict**, not the presence of `equalsIgnoreCase` in any generated/source string — the casing-sensitivity is an implementation detail; the verdict under a casing mismatch is the behaviour.

No new execution-tier fixture: the fix changes only whether classification succeeds, not emit, and `graphitron-sakila-example`'s R329 `GraphQLQueryTest` already round-trips the `AccessorCall` path end-to-end. Full reactor green (`mvn -f graphitron-rewrite/pom.xml install -Plocal-db`, incl. `graphitron-lsp`) is the smoke check.

## Design notes

- **Comparison-site fix, not canonicalization.** Making `TableRef.tableName()` case-canonical at construction would fix all sites at once but deletes a documented invariant: `TableRef`'s `tableName()` is deliberately case-preserved from the directive value (`TableRef.java:14-16`, `JooqCatalog.java:984-987`) so author-facing diagnostics echo what the user wrote (e.g. the `@table`-mismatch error at `FieldBuilder.java:4280-4286`). Canonicalizing trades a localized bug for a cross-cutting change to error text. The case-insensitive comparison idiom is already established at eight sites; `:5114` is an oversight against it, and the fix restores consistency.
- **The sibling `.equals` at `FieldBuilder.java:3105` is out of scope, and is not a live bug.** It is the only other `.tableName().equals(` in the builder, but both its operands (`targetNodeType.table().tableName()` and the `ServiceEmitted` binding's `tableRef().tableName()`) flow from the same verbatim `resolveTable(@table-name)` path for the same node type, so they cannot diverge in case. Aligning it to the idiom is left to the follow-up rather than touched here as an untested change.
- **Follow-up filed (see Relations).** The deeper shape is that `TableRef.tableName()` has two construction paths that disagree on case, and comparison correctness depends on every site remembering `equalsIgnoreCase`. The follow-up adds a unit-tier guard test that fails on any `.tableName().equals(` (converting `:3105` in the process) and optionally lifts a typed `TableRef`-owned same-table comparison. Out of scope here to keep this a tight bug-fix slice.

## Relations

- **R329** (shipped) — the `@service` record-composite payload carrier this rides on; it grounds `…Result` to the composite class and resolves the `@table` children through the `AccessorCall` path. Its fixtures (`ServiceRecordCompositeCarrierPipelineTest`) use lowercase `@table(name:)`, so they never reach this case-sensitivity guard; this item is the casing-mismatch case R329 did not cover. Same driving mutation (`opprettUtdanningsspesifikasjonOgUtdanningsmulighet`).
- **`table-name-comparison-case-guard`** (Backlog) — the structural follow-up for the `TableRef.tableName()` case-canonicality trap (guard test + optional typed same-table comparison); subsumes the `:3105` idiom-alignment.
- **R191 / R269 / R305 / R308** — the accessor-keyed record-parent and `@service`-carrier cluster the original framing referenced. The confirmed root cause is orthogonal (a case-sensitive string comparison, not a coverage gap), so they are background, not dependencies.

## Out of scope

- Canonicalizing `TableRef.tableName()` (would change diagnostic casing; see Design notes).
- The `:3105` idiom-alignment and the typed same-table comparison helper / guard test — the `table-name-comparison-case-guard` follow-up.
- The original "derive batch-key source" framing: grounding and accessor derivation already work; nothing to derive.
