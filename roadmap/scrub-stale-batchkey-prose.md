---
id: R126
title: "Scrub residual BatchKey.X references from sakila-service / sakila-example prose"
status: Backlog
bucket: cleanup
priority: 9
theme: model-cleanup
depends-on: []
---

# Scrub residual BatchKey.X references from sakila-service / sakila-example prose

R38's Phase 3 follow-up ("Stale-prose scrub", commit `5d82380`) claimed 118
BatchKey references in Javadoc / comment prose were cleaned up, but several
sites in the sakila-service test fixtures, sakila-example execution test, and
the example `schema.graphqls` description comments still mention deleted
`BatchKey.X` permits (some as dead `{@link no.sikt.graphitron.rewrite.model.BatchKey.X}`
Javadoc, some as plain prose). Build is green (Javadoc lookups are best-effort),
but the references are noise for readers and trip code search.

Concrete sites (grep `BatchKey\.` in `graphitron-rewrite/`):

- `graphitron-sakila-service/.../FilmService.java`
  (BatchKey.MappedRecordKeyed, BatchKey.MappedTableRecordKeyed)
- `graphitron-sakila-service/.../FilmCardData.java`
  (BatchKey.AccessorKeyedSingle)
- `graphitron-sakila-service/.../InventoryExtensions.java`
  (BatchKey.AccessorKeyedSingle)
- `graphitron-sakila-service/.../CreateFilmsPayload.java`
- `graphitron-sakila-service/.../CreateFilmsPayloadService.java`
  (BatchKey.AccessorKeyedMany)
- `graphitron-sakila-example/.../AccessorDerivedBatchKeyTest.java`
  (class name + Javadoc with dead `{@link}` to BatchKey.AccessorKeyedMany)
- `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`
  (lines 274, 328, 340, 497, 512, 708; prose `# ...` SDL comments)

Replace each with the equivalent post-R38 vocabulary:
`BatchKey.MappedRecordKeyed` →
`Reader.ServiceUntypedRecord` + `LoaderRegistration.Container.MAPPED_SET`;
`BatchKey.MappedTableRecordKeyed` →
`Reader.ServiceTableRecord(...)` + `Container.MAPPED_SET`;
`BatchKey.AccessorKeyedSingle` →
`Reader.AccessorCall(...)` + `Cardinality.ONE`;
`BatchKey.AccessorKeyedMany` →
`Reader.AccessorCall(...)` + `Cardinality.MANY` + `Dispatch.LOAD_MANY`.

The `AccessorDerivedBatchKeyTest` class name is a public test fixture name;
consider renaming to `AccessorDerivedSourceTest` if a rename is acceptable, or
leave the name and just refresh the Javadoc body.

Pure doc-rot; no production-model or test-coverage impact.
