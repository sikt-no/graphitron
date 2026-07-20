---
id: R504
title: "Remove factual errors and confusion from ChildField leaf-taxonomy comments and javadoc"
status: Spec
bucket: cleanup
priority: 9
theme: docs
depends-on: []
created: 2026-07-20
last-updated: 2026-07-20
---

# Remove factual errors and confusion from ChildField leaf-taxonomy comments and javadoc

Sibling of R126. R126 scrubbed the deleted *classification-verdict* vocabulary (`BatchKey` / `Reader` arms / `SourceKey.Cardinality` / `Mapped*Keyed`) from prose. This item does the same for the `ChildField` **leaf taxonomy**, surfaced while verifying stale capability claims during R126's In Review pass.

**The goal is correctness and clarity, not a mechanical rename.** Get rid of factual errors and other sources of confusion in the comments *and* javadoc around the leaf taxonomy. The deleted leaf vocabulary is the concrete seed: the leaf-collapse refactor merged the table-sourced and record-sourced table-field leaves into `BatchedTableField` (and the lookup pair into `BatchedLookupTableField`), yet the old names still read present-tense across fixture prose, SDL comments, main-source and test javadoc, and a handful of test method names, describing leaves the classifier no longer produces. A reader, or a code search, still finds the retired taxonomy as if it were live. But the sweep is not confined to the dead names: wherever a comment you are touching states something no longer true, over-explains, or restates the code, fix or remove it in the same pass.

**Less is more.** Documentation drifts out of sync with the code and becomes its own source of errors, so the default fix is to delete, not to rewrite longer. Keep (and repoint) only a comment or javadoc line that carries load-bearing information a reader cannot recover from the code itself. A comment that merely names an internal type, restates the signature, or narrates mechanics the reader can already see is better deleted than maintained. When the choice is between a careful rewrite and a deletion, prefer deletion.

Scoped as its own item, not folded into R126, because it is a different vocabulary, ~80 whole-word sites across nine dead names (sweep below, roadmap prose excluded), and it sits next to confusably-similar live names that a blanket replace would corrupt.

## Dead leaf vocabulary and its live successors

Verified against the live `ChildField` permit set (`model/ChildField.java`). None of these dead names is a declared type in main sources:

* `SingleRecordTableField`, `RecordTableField`, `SplitTableField` -> `ChildField.BatchedTableField`. The record-sourced and table-sourced table-field leaves collapsed into the one batched leaf; the validator counterpart merged from `RecordTableFieldValidationTest` + `SplitTableFieldValidationTest` into `BatchedTableFieldValidationTest`.
* `RecordLookupTableField`, `SplitLookupTableField` -> `ChildField.BatchedLookupTableField` (the lookup-keyed parallel of the above).
* `LifterLeafKeyed` -> `KeyLift.Lifter` (the leaf-PK `@sourceRow` lift; the undotted sibling of `LifterPathKeyed`, which R126's sweep pattern missed).
* `RecordTableMethodField`, `SingleAccessorOnListField`, `ConstructorField` -> **unverified**; the implementer confirms each successor (or that the mention is purely historical) before rewriting. `TableMethodField` is the live `@tableMethod` leaf; `ConstructorField` was dissolved (most mentions are already historical "was dissolved" narrative and stay).

## Confusable LIVE names, out of scope, do not touch

Word boundaries protect these, but a careless replace would corrupt them: `RecordField`, `RecordCompositeField`, `SingleRecordIdField`, `ServiceRecordField`, `RecordTableMethodField`'s live cousin `TableMethodField`, and `BatchedLookupTableField` / `BatchedTableField` themselves. Match each **dead** name as a whole word (`\b<name>\b`) mapping to its own successor; never a substring or camelCase-lookbehind replace (that hits `SingleRecordTableField` inside `SingleRecordTableFieldServiceProducerPipelineTest` and `RecordField` inside `RecordCompositeField`).

## Surfaces, sites, and method

At filing the sweep below finds 70 whole-word mentions (66 lines) of the confirmed-dead names, plus 12 mentions of the three unverified names, across `graphitron-sakila-example` (SDL comments + `querydb`/`internal` tests), `graphitron/src/test`, `graphitron` main-source javadoc, and one `graphitron-sakila-service` fixture comment. Implementer runs the authoritative sweep and confirms only historical residue remains at the end:

```
grep -rnE '\b(SingleRecordTableField|RecordTableField|SplitTableField|RecordLookupTableField|SplitLookupTableField|LifterLeafKeyed)\b' \
  --include=*.java --include=*.graphqls --include=*.md . | grep -v /target/ | grep -v '^\./roadmap/'
```

The `roadmap/` exclusion is deliberate, see the scope bullet below; without it the same grep matches ~108 further lines of roadmap prose this item does not own.

Per-site discipline (same as R126):

* **Present-tense capability/classification claims** ("classifies as `SingleRecordTableField`", "is a `RecordTableField` DataLoader", "`RecordTableField.emitsSingleRecordPerKey()`", where the method now lives on `BatchedTableField` / `BatchKeyField`) are corrected, in main-source javadoc as well as test and fixture prose: delete the line when it adds nothing over the code the reader can see, otherwise repoint it to the live successor. Deletion is the default.
* **Historical narrative stays**: "the former `SingleRecordTableField`", "collapsed into `BatchedTableField`", "`ConstructorField` was dissolved", "merged from the former `SplitTableFieldValidationTest` and `RecordTableFieldValidationTest`" are correct provenance and are left intact. A per-line `former|collapsed|dissolved|merged|pre-merge|used to` guard catches most; multi-line historical sentences (e.g. `BatchedTableFieldValidationTest`'s class javadoc) need reading, not a blind guard.
* **Test-class names** describing the feature scenario (`SingleRecordTableFieldServiceProducer{Pipeline,Execution}Test`) are a bigger, separate rename with cross-file ripples; the implementer decides whether to include them or leave them (they name a scenario, not the leaf verdict). Whole-word matching leaves them untouched by default.
* **Test method names** that assert a dead leaf while their body asserts `BatchedTableField` (e.g. `..._dataFieldClassifiesAsRecordTableField`, `..._admitsAsRecordTableField`) are themselves stale claims and rename in lockstep with the body; none are referenced outside their own file.

## Scope, non-goals, coverage

* Comment, javadoc, and identifier scrub only: no production-model, generated-output, or test-behaviour change. Test bodies keep asserting exactly what they assert today; deleting a comment or javadoc line is not a behaviour change.
* R126's classification-verdict vocabulary is out of scope here (already shipped); this item is the leaf taxonomy and the confusion around it.
* **`roadmap/` prose is out of scope** and excluded from the sweep. `changelog.md` is permanent historical provenance and legitimately narrates the retired names; other live item bodies are re-anchored by their own items and by the rolling staleness audit under `roadmap/audits/` (the current one already flags the stale `RecordTableField` anchors); this item's own file names the dead vocabulary by necessity. Editing other items' spec bodies from this sweep would collide with those owners.
* **Co-located factual errors are in scope, that is the goal.** If a comment you are already touching also makes a claim that is stale or misleading (for example an "Invariant #N rejects X until the single-arm ships" line that a later change made false), verify it against the code and then fix or delete it in the same pass; do not repoint only the dead name and leave the falsehood standing. What stays out of scope is a *repo-wide* claim-currency audit of files this sweep does not otherwise touch; that is a separate concern.
* No roadmap-id citation replaces any comment (`RoadmapReferenceGuardTest`); `.adoc` edits use no em dashes.
