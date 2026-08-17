---
id: R697
title: "Case-folded name columns on the base relations, so matching views stop repeating UPPER()"
status: Spec
bucket: architecture
theme: classification-model
depends-on: []
created: 2026-08-17
last-updated: 2026-08-17
---

# Case-folded name columns on the base relations, so matching views stop repeating UPPER()

## Problem

Every view that matches an authored name against a catalog or classpath name folds case inline, and
the folding is repeated per comparison rather than owned by the column being folded. Nineteen lines
of the DDL carry an inline `UPPER(` or `LOWER(`. `intent_column_match_claim` alone writes
`UPPER(COALESCE(fb.name_ref, f.field_name))` four times, in the `matched_by` CASE, in the
`ROW_NUMBER` ordering, and twice in the join predicate, alongside `UPPER(c.jooq_name)` and
`UPPER(c.column_name)`. `intent_spelled_table` and the constraint-resolution views fold the same way
over their own name columns.

The folding is a function of one base relation's own column, so it belongs on that base relation. The
`COALESCE` beside it is not: an effective name needs the join to `graphitron_field_binding`, so it
stays the view's own product. That split is the point of the item, and it is the case where the
existing earning test in `intent_column_match_claim.matched_name`'s comment ("the classifier's own
product rather than a projection of either input") points in a definite direction.

Not a single computed column exists in 4,492 lines of DDL, so this is an unused tool rather than an
inconsistently used one.

## Why it is worth doing rather than tolerating

Three reasons, in order of weight. The fold gets stated once per column instead of once per
comparison; on its own that shortens each predicate spelling without reducing the count, so the plan
below pairs it with the one restructuring that does close the drift hazard in the four-repeat view
(the predicate collapse in step 3). A folded column is indexable where the inline fold is not, and in
this engine that is absolute rather than comparative: H2 2.4.240 rejects `CREATE INDEX ... ON t
(UPPER(n))` as a syntax error, so it has no expression indexes at all and a stored column is the only
route to an indexed folded name. The DDL declares no indexes today, so this is an enabling property
rather than a taken benefit, and no DDL comment may claim it (`intent_column_match_claim`'s comment
already records a measured seventy-times regression from getting that view's evaluation shape wrong,
which is what makes the enablement worth having). And every future matching view stops restating the
rule.

## The fork, settled

The generated column wins. The jOOQ check the Backlog body demanded has been run, not reasoned
about: a standalone rehearsal against jOOQ 3.20.11 OSS and H2 2.4.240, replicating
`ModelCodegenDriver`'s exact configuration (live H2 metadata, same `Generate` flags) and
`FactSink.flush()`'s exact insert shape. Five results, each load-bearing:

1. **Codegen accepts the column silently.** jOOQ OSS emits a `GENERATED ALWAYS AS` column as a
   plain `TableField` with no readonly or computed marking; readonly-column awareness is a jOOQ
   commercial-edition feature. So `Table.fields()` includes the folded column, and nothing in the
   generated surface distinguishes it from a writable one.
2. **The current flush shape breaks.** `insertInto(table).columns(table.fields())` with
   `record.intoArray()` binds fails at H2 with "Generated column ... cannot be assigned", in both
   the plain-insert arm and the `onDuplicateKeyIgnore` shared-family arm.
3. **The record path is safe.** `record.insert()` and `batchInsert` render from the changed-field
   set, skip the never-set folded column, and the engine computes the folded value.
4. **Filtering works.** Excluding the folded columns from the insert column list and the bound
   value array succeeds, and the folded values compute correctly.
5. **The engine names its own generated columns.** H2's `INFORMATION_SCHEMA.COLUMNS` reports
   `IS_GENERATED = 'ALWAYS'` and the `GENERATION_EXPRESSION`, so a writer can discover what not to
   write from engine metadata rather than from a naming convention or a hand-kept list.

`FactSink.flush()` is the writer that needs the change; the other write paths (`insertInto` with
explicit column lists in `FactCapture`, `ClasspathSources`, `JavaSourceFacts`; `batchInsert` in
`JavaSourceFacts` and `BuildWarningFacts`) either never name the folded columns or go through the
changed-set path result 3 covers. That census is an observation, not the guarantee: the enforcer
is H2 itself, which rejects the failing shape loudly ("Generated column ... cannot be assigned")
the first time it executes, so a future writer that regresses into it fails the build rather than
writing something wrong.

Doctrinally the generated column needs no materialization argument. It is a derivation whose
enforcer is structural rather than procedural, the same shape as the rendered-coordinate rule
`graphql_duplicate_declaration.coordinate` already blesses (a derived value on a base relation
that cannot drift from the columns it renders), with the engine rather than capture doing the
rendering. The DDL states that argument once, at the first folded column, and the other comments
point at it instead of each re-arguing.

## The cheaper alternative, and why it loses facts

H2 has a case-insensitive string type, `VARCHAR_IGNORECASE`, and on the surface it dominates the
generated column: no second column, no writer change at all, and the fold moves into the type rather
than into any predicate. It was tested on the same rehearsal and every mechanical part of it works.
`=` and `LIKE` fold with no expression anywhere. The type survives projection through a view, so a
downstream comparison keeps folding. A join between an `IGNORECASE` column and a plain `VARCHAR`
column folds in both directions, which is exactly the authored-meets-catalog shape. And jOOQ OSS
codegen maps it to a plain `String` field, so nothing downstream sees it.

It is still the wrong answer, because the fold reaches the key. A primary key over an `IGNORECASE`
column folds with it: inserting `('s', 'film', 'title')` and then `('s', 'film', 'TITLE')` is a
primary-key violation, and `COUNT(DISTINCT table_name)` answers 1 for `film` beside `FILM`. On the
catalog relations that is silent fact loss rather than a caught error. A quoted-identifier
PostgreSQL table may legitimately declare both `"Title"` and `"title"`; `FactSink.claim` dedups on a
Java `HashSet` and so passes both through case-sensitively; and `sql_` is a shared family whose
insert carries `onDuplicateKeyIgnore`, so the second column would be discarded without a word and
the store would under-report a real catalog. The type would be safe only on the non-key name columns,
which is worse than uniformity: whether `=` folds would become an invisible per-column property, and
a reader could no longer tell by looking at a predicate what it compares. Rejected, and recorded here
so the next reader of this item does not re-derive it as an improvement.

## Plan

Four moves, one DDL pass and one writer pass, then the views and the verification:

1. **DDL: folded columns.** Add `<column>_folded VARCHAR GENERATED ALWAYS AS (UPPER(<column>))`
   beside each name-bearing column a matching view folds today: `sql_table` (`table_schema`,
   `table_name`), `sql_constraint` (`table_schema`, `constraint_name`, `jooq_name`), `sql_column`
   (`jooq_name`, `column_name`), `graphql_field` (`field_name`, the one folded column landing on a
   hot, wide, foreign-key-targeted relation), `graphitron_field_binding` (`name_ref`),
   `graphitron_table` (`table_ref`, `type_name`), `graphitron_field_reference_step` (`table_ref`,
   `key_ref`), `graphitron_argument_reference_step` (`table_ref`), `graphitron_reference_for_step`
   (`table_ref`), `graphitron_mutation` (`table_ref`). The exact census is whatever the folding
   lines actually reference once the rewrite starts; the list above is read off the nineteen lines
   today. Each column gets a `COMMENT ON COLUMN` in the same pass (the relation-census gate fails
   an uncommented column): the comment states it is the engine-computed case-folded mirror of its
   sibling and exists for name matching, with the doctrinal argument stated once at the first
   folded column per the fork section above.
2. **Writer: FactSink filters engine-owned columns.** `flush()` learns which columns the engine
   computes by reading `INFORMATION_SCHEMA.COLUMNS` once per store (engine truth, the same move
   `parentsFirst` already makes with the generated foreign keys, so a future folded column costs
   no writer edit). The kept positions are computed once per table as a single index array from
   which both the insert column list and each row's bound array derive, so the two sequences
   cannot disagree positionally; a filter computed twice would be a silent wrong write when the
   copies drift. No claim-key change: folded columns are never part of a natural key.
3. **Views: fold-free matching.** Each of the nineteen `UPPER(`/`LOWER(` matching lines becomes an
   equality over folded columns. The effective-name `COALESCE` stays the view's product, now over
   folded inputs: `COALESCE(fb.name_ref_folded, f.field_name_folded)` in
   `intent_column_match_claim`, `COALESCE(t.table_ref_folded, t.type_name_folded)` in the spelling
   union. Qualified-spelling splits take both the split position and the substring over the folded
   column (`SUBSTRING(x_folded FROM POSITION('.' IN x_folded) + 1)`), so no commutation claim
   about H2's casing is carried in prose; folding distributes over concatenation regardless of
   whether casing preserves length. And `intent_column_match_claim` gets the one restructuring the
   item's lead motive actually needs: an inner select emits `matched_by` and the effective folded
   name as columns, the outer select orders and filters over them, collapsing the thrice-written
   match predicate (join arm, `matched_by` CASE, `ROW_NUMBER` ordering) to a single spelling.
4. **Verification.** The existing pipeline and capture-agreement tests are the harness: they load
   real SDL and a real catalog through the changed writer and read through the changed views, so a
   wrong filter or a missed rewrite fails loudly. Two additions earn their place. A capture test
   asserting a folded column carries the engine-computed value after a real load, pinning result 3
   against a future writer regression. And a `FactSchemaGateTest` sibling that reads
   `INFORMATION_SCHEMA.COLUMNS` where `IS_GENERATED = 'ALWAYS'` and asserts every
   `GENERATION_EXPRESSION` is exactly `UPPER("<c>")` over a non-generated column of the same
   relation and every such column is named `<c>_folded`, which turns the one-column-per-fold rule
   and the naming convention from review-only doctrine into a gate (H2 would happily accept
   `UPPER(COALESCE(...))`, so nothing else stops the next author from crossing that line).

Out of scope, stated so the rewrite does not creep: the single-character accessor-prefix fold
(`LOWER(SUBSTRING(method_name, ...))` in the accessor views) is prefix grammar, not name matching;
the meta census views fold `INFORMATION_SCHEMA.TABLES` columns, which are not our base relations.
Both keep their inline folds. Known re-spellings of the same matching rule outside SQL's reach,
named here so silence does not read as a claim: the LSP's `CatalogColumns.isNamed`
(`equalsIgnoreCase` over jOOQ and SQL names) and its `CatalogKeys` sibling restate the two-tier
case-insensitive match in Java against fetched rows, where a folded column cannot reach. They
belong to the LSP re-sourcing work the Care section already sequences against, not to this item.

## Care

Broad and mechanical: it touches name-bearing base relations across several families and every view
that matches on a name, so it conflicts with anything else editing those views. Sequencing agreed:
land it early, before the language-server recomposition writes new joins against these predicates,
rather than after, when the two would fight over the same view bodies.

The schema-identifier and family gates read the relation census, so new columns need their comments
in the same pass; a column without a comment fails the build rather than shipping undocumented.
