---
id: R504
title: "Scrub deleted ChildField leaf-taxonomy vocabulary from prose (Single/Record/Split*TableField -> Batched*)"
status: Backlog
bucket: cleanup
priority: 9
theme: docs
depends-on: []
created: 2026-07-20
last-updated: 2026-07-20
---

# Scrub deleted ChildField leaf-taxonomy vocabulary from prose (Single/Record/Split*TableField -> Batched*)

Sibling of R126. R126 scrubbed the deleted *classification-verdict* vocabulary (`BatchKey` / `Reader` arms / `SourceKey.Cardinality` / `Mapped*Keyed`). While verifying stale capability claims during R126's In Review pass, a second, distinct deleted vocabulary surfaced: the `ChildField` **leaf taxonomy**. The leaf-collapse refactor merged the table-sourced and record-sourced table-field leaves into `BatchedTableField` (and the lookup pair into `BatchedLookupTableField`), but the old leaf names still appear present-tense in fixture prose, SDL comments, test comments, and a handful of test method names, describing leaves the classifier no longer produces. A reader (or code-search) still finds the retired taxonomy as if it were live.

This is scoped as its own item, not folded into R126, because it is a different vocabulary, ~123 sites across nine dead names, and it sits next to confusably-similar live names that a blanket replace would corrupt.

## Dead leaf vocabulary and its live successors

Verified against the live `ChildField` permit set (`model/ChildField.java`). None of these dead names is a declared type in main sources:

* `SingleRecordTableField`, `RecordTableField`, `SplitTableField` -> `ChildField.BatchedTableField`. The record-sourced and table-sourced table-field leaves collapsed into the one batched leaf; the validator counterpart merged from `RecordTableFieldValidationTest` + `SplitTableFieldValidationTest` into `BatchedTableFieldValidationTest`.
* `RecordLookupTableField`, `SplitLookupTableField` -> `ChildField.BatchedLookupTableField` (the lookup-keyed parallel of the above).
* `LifterLeafKeyed` -> `KeyLift.Lifter` (the leaf-PK `@sourceRow` lift; the undotted sibling of `LifterPathKeyed`, which R126's sweep pattern missed).
* `RecordTableMethodField`, `SingleAccessorOnListField`, `ConstructorField` -> **unverified**; the implementer confirms each successor (or that the mention is purely historical) before rewriting. `TableMethodField` is the live `@tableMethod` leaf; `ConstructorField` was dissolved (most mentions are already historical "was dissolved" narrative and stay).

## Confusable LIVE names, out of scope, do not touch

Word boundaries protect these, but a careless replace would corrupt them: `RecordField`, `RecordCompositeField`, `SingleRecordIdField`, `ServiceRecordField`, `RecordTableMethodField`'s live cousin `TableMethodField`, and `BatchedLookupTableField` / `BatchedTableField` themselves. Match each **dead** name as a whole word (`\b<name>\b`) mapping to its own successor; never a substring or camelCase-lookbehind replace (that hits `SingleRecordTableField` inside `SingleRecordTableFieldServiceProducerPipelineTest` and `RecordField` inside `RecordCompositeField`).

## Surfaces, sites, and method

At filing there are ~123 whole-word mentions of the confirmed-dead names across `graphitron-sakila-example` (SDL comments + `querydb`/`internal` tests) and `graphitron/src/test`, plus a few `graphitron` main-source javadoc mentions that are already correct history. Implementer runs the authoritative sweep and confirms only historical residue remains at the end:

```
grep -rnE '\b(SingleRecordTableField|RecordTableField|SplitTableField|RecordLookupTableField|SplitLookupTableField|LifterLeafKeyed)\b' \
  --include=*.java --include=*.graphqls --include=*.md . | grep -v /target/
```

Per-site discipline (same as R126):

* **Present-tense capability/classification claims** ("classifies as `SingleRecordTableField`", "is a `RecordTableField` DataLoader", "`RecordTableField.emitsSingleRecordPerKey()`" -> the method now lives on `BatchedTableField` / `BatchKeyField`) are rewritten onto the live successor.
* **Historical narrative stays**: "the former `SingleRecordTableField`", "collapsed into `BatchedTableField`", "`ConstructorField` was dissolved", "merged from the former `SplitTableFieldValidationTest` and `RecordTableFieldValidationTest`" are correct provenance and are left intact. A per-line `former|collapsed|dissolved|merged|pre-merge|used to` guard catches most; multi-line historical sentences (e.g. `BatchedTableFieldValidationTest`'s class javadoc) need reading, not a blind guard.
* **Test-class names** describing the feature scenario (`SingleRecordTableFieldServiceProducer{Pipeline,Execution}Test`) are a bigger, separate rename with cross-file ripples; the implementer decides whether to include them or leave them (they name a scenario, not the leaf verdict). Whole-word matching leaves them untouched by default.
* **Test method names** that assert a dead leaf while their body asserts `BatchedTableField` (e.g. `..._dataFieldClassifiesAsRecordTableField`, `..._admitsAsRecordTableField`) are themselves stale claims and rename in lockstep with the body; none are referenced outside their own file.

## Scope, non-goals, coverage

* Pure doc / identifier scrub: no production-model, generated-output, or test-behaviour change. Test bodies keep asserting exactly what they assert today.
* R126's classification-verdict vocabulary is out of scope here (already shipped); this item is only the leaf taxonomy.
* Entangled *other* stale claims noticed in the same comments (e.g. whether "Invariant #10 rejects ... until the single-arm ships" is still current after R61 lifted it) are noted but not chased here unless trivially co-located; a claim-currency audit is a separate concern.
* No roadmap-id citation replaces any comment (`RoadmapReferenceGuardTest`); `.adoc` edits use no em dashes.
