---
id: R125
title: "@LoadBearingClassifierCheck on multi-schema TableRef.tableClass / ForeignKeyRef.keysClass"
status: Backlog
bucket: cleanup
priority: 6
theme: structural-refactor
depends-on: []
---

# @LoadBearingClassifierCheck on multi-schema TableRef.tableClass / ForeignKeyRef.keysClass

R83 added pipeline + compilation + execution coverage for the multi-schema fixture R78 introduced, but did not annotate the underlying invariants with the `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` pair (`rewrite-design-principles.adoc § Classifier guarantees shape emitter assumptions`). The fit is exact: `JooqCatalog.TableEntry.tableClass()` and the `keysClass` populated by `findForeignKeyByName` are classifier-side invariants the emitters consume directly into emitted FQN references with no defensive widening; a regression that re-derives a `ClassName` from the bare `jooqPackage` produces source that does not exist under multi-schema codegen, and the compile failure on `graphitron-sakila-example`'s `rewrite-generate-multischema` execution is exactly the safety net the principle describes. R83's pipeline tests pin the shape locally; the annotation pair pins it globally for `LoadBearingGuaranteeAuditTest`'s orphaned-consumer detection and gives find-usages navigation between producer (`JooqCatalog`) and the emitter sites that consume the typed FQN slot. Pipeline-test pinning and annotation pinning are not substitutes (per the principle's own framing). Producer plus a sweep over consumers — `JoinPathEmitter`, `InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`, `TypeClassGenerator`, `GeneratorUtils`, `SplitRowsMethodEmitter`, `MultiTablePolymorphicEmitter`, `ExternalFieldDirectiveResolver`, `TableMethodDirectiveResolver`, `SelectMethodBody` — to wear `@DependsOnClassifierCheck` against a single key like `table-ref-schema-segmented-fqn` and a sibling key for `foreign-key-ref-fk-holder-keys-class`.
