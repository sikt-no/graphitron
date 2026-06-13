---
id: R302
title: "Rename ChildField to SourceField (carrier-named field hierarchy)"
status: Backlog
bucket: structural
priority: 4
theme: structural-refactor
depends-on: []
created: 2026-06-13
last-updated: 2026-06-13
---

# Rename ChildField to SourceField (carrier-named field hierarchy)

Split out of R290 (`datafetcher-field-dimensional-slots`). R222's refined field-side model names the
three carriers after their GraphQL parent-type category: `Query` -> `QueryField`, `Mutation` ->
`MutationField`, and **`Source` -> `SourceField`**. The first two already match their carrier; the
third is still called `ChildField`, a name that predates the carrier vocabulary and describes position
("a child of some parent") rather than the carrier it is ("the Source carrier"). The corpus already
asserts `carrier: Source` for every `ChildField` leaf (R299), so the rename closes the last gap between
the model's vocabulary and the code's.

This is **pure mechanical churn**: a type rename with no behavioural, classification, or emit change.
`ChildField` and its nested leaves (`ColumnField`, `TableField`, `NestingField`, ... the full sealed
set) rename to `SourceField`; the ~940 references across ~100 files (model, generators, validators,
catalog, and the test corpus) update with them. No leaf is added, removed, or reclassified, and no
`@classified` verdict changes, so the R281/R299 corpus stays byte-identical.

## Why split from R290

R290 materialises `carrier x intent x mapping` on the field, derives the fetcher mechanism, deletes
`LeafTupleAdapter`, and changes two leaves (dissolve `ConstructorField`, collapse
`SingleRecordTableField`). That is load-bearing architectural work whose reviewability depends on a
small, readable diff. Folding a ~940-reference rename into the same commit range would bury the
substantive change under mechanical noise, exactly the bundling R222's "vertical slices each ship their
own vocabulary" technique exists to avoid. The rename touches the same files R290 does but is
independent of it: neither blocks the other, and whichever lands second rebases trivially (a rename has
no semantic merge conflict). Landing this **first** lets R290's diff speak in the final `SourceField`
vocabulary from its opening commit; landing it **last** is equally fine. No ordering edge is declared
for that reason.

## Scope

- Rename the sealed type `ChildField` -> `SourceField` and update every reference (imports,
  `case` arms, javadoc, switch patterns, fixture references).
- Keep the nested leaf names as-is (`SourceField.ColumnField`, `SourceField.TableField`, ...); only the
  enclosing type renames. (If any leaf name independently reads as position-named rather than
  carrier/intent-named, flag it but do not rename it here, that is a separate judgement call.)
- Update prose references in `graphitron-rewrite/docs/` and the R290 appendix that still say
  `ChildField` where they mean the Source carrier.

## Acceptance

`mvn -f graphitron-rewrite/pom.xml install -Plocal-db` green with no other diff: the R281/R299 corpus
byte-identical (every `carrier: Source` verdict unchanged), `LeafCoverageReportTest` leaf count
unchanged, and no generated-output change. A rename that needs any test assertion edited beyond the
type name itself is out of scope, that signals an unintended behavioural change.
