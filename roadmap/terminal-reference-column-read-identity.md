---
id: R444
title: "Scalar @reference terminal column read must resolve terminal table by class identity, not bare SQL name"
status: Backlog
bucket: bug
priority: 3
theme: interface-union
depends-on: []
created: 2026-07-08
last-updated: 2026-07-08
---

# Scalar @reference terminal column read must resolve terminal table by class identity, not bare SQL name

## Problem

This is the sixth site of the schema-qualified `@table` bug class (siblings R396, R440, R441, R442, R422, all Done). R422's changelog claims it "closes the schema-qualified `@table` bug class," but R422 only fixed the object-return-type terminal verdict (`BuildContext.computeTerminalTargetVerdict`). The scalar `@reference` terminal column read is a separate, unaudited path with the identical bare-name-vs-identity defect.

## Mechanism

For a scalar field with `@reference(path:)`, `FieldBuilder` (`FieldBuilder.java:6105`) calls `ServiceCatalog.resolveColumnForReference`, which delegates to `ServiceCatalog.terminalTableSqlName` (`ServiceCatalog.java:92`-100). That method walks the FK path and returns `hop.targetTable().tableName()` (`ServiceCatalog.java:97`), collapsing the FK-pinned `TableRef` (which carries a `tableClass` `ClassName` identity, per R441) down to a bare SQL name string. `resolveColumnInTable` then calls `JooqCatalog.findColumn(String tableSqlName, …)` (`JooqCatalog.java:1064`), which does `findTable(tableSqlName)` → `TableResolution.Ambiguous` when the terminal table name exists in two generated schemas → `.asEntry()` returns empty → `FieldBuilder` (`FieldBuilder.java:6112`) emits `Author error: column '<c>' could not be resolved in the jOOQ table`. The column genuinely exists on both schemas' copies of the terminal table; the lookup just can't pick one by bare name, and there is no author-side workaround (the `@reference` key is `TABLE__CONSTRAINT` on the source table, so there's no syntax to qualify the FK terminal, same as gap A in `opptak/docs/graphitron-qualified-names-gaps.md`).

## Live repro

On the opptak branch `feature/SHIIT-767-opptak-v2-skjema`: type `Opptakshendelsestype @table(name: "opptakshendelsestype_opptakstype")` with two scalar fields, `navn: String` and `kategori: String! @field(name: "opptakshendelsekategori")`, both `@reference(path: [{key: "opptakshendelsestype_opptakstype__opptakshendelsestype_opptakstype_opptakshendelsestype_fk"}])`. That FK's source is the join table `opptakshendelsestype_opptakstype` (opptak-only); its target is `opptakshendelsestype`, which exists in both `opptak` and `opptak_v2`, each carrying `navn` and `opptakshendelsekategori`. These are the last 2 of the original 31 author-errors; R440/R441/R442/R422 cleared the other 29.

## Suggested fix

Same pattern as R440/R441/R422 ("decide once, carry the decision as a type"). Resolve the terminal column against the last hop's `targetTable()` `tableClass` identity via `JooqCatalog.findTableByClass` (`JooqCatalog.java:159`), not the bare `tableName()`. Empty-path (no hops) falls back to the source type's already-resolved `@table` `TableRef`. Reuse the identity plumbing already in hand (the `TableRef.tableClass` is populated on catalog-derived refs) rather than re-resolving a bare string.

## Suggested coverage

Pipeline-tier test over the existing multischema jOOQ fixture (`multischema_a`/`multischema_b`, both containing a bare-name-colliding table), sibling to R422's `QualifiedReturnTypeReferencePipelineTest` and R396's `QualifiedSourceReferencePipelineTest`: a scalar `@reference` field whose single FK hop lands on the colliding terminal table classifies green as a `ColumnReferenceField` and reads the column from the schema pinned by the FK, paired with a genuine unknown-column case that still rejects. No new DDL should be needed; check whether the fixture already has a scalar column on the colliding table to read, and add one additively (bump the jOOQ schema version) only if not.
