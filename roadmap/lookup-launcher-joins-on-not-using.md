---
id: R1015
title: "The lookup launcher joins its input rows with ON, not USING"
status: In Review
bucket: bug
priority: 6
theme: codegen-correctness
depends-on: []
created: 2026-10-12
last-updated: 2026-10-13
---

# The lookup launcher joins its input rows with ON, not USING

## Goal

A root lookup field produces SQL that every supported dialect accepts, whatever its key columns are. A root lookup field is a `Query` field whose arguments carry `@lookupKey`. Its *launcher* (the generated method that issues the field's one statement) turns the argument list into a `VALUES` derived table of input rows, one row per key plus an `idx` position, and joins that table against the target table.

Today the launcher joins with `USING (<key columns>)`, which breaks in two ways:

- **Computed key columns, every dialect.** A key column whose jOOQ field renders as an expression (a client-side computed column) yields `using ((to_char("FODSELSDATO", :4) || to_char("PERSONNR", :5)))` or `using (case when ... end)`. Neither is valid SQL.
- **Any key column, Oracle.** The field's projection, rendered by the type's generated `$project` method, selects the key column table-qualified (`"STUDIEPROGRAM"."STUDIEPROGRAMKODE"`). Oracle forbids qualifying a `USING` column anywhere in the query (ORA-25154).

When this lands, the launcher joins with an explicit `ON` predicate that compares each target column with the matching input-table column. The computed expression stays on the table side, where jOOQ renders it like any other column. The launcher builds that predicate through the same helper as every other generated `VALUES` join, so the reason for `ON` lives in one place, and no generator path keeps a way to emit `USING`.

## Implementation

### The shared predicate

Add `ValuesJoinRowBuilder.onCondition`, next to `aliasArgs`, which writes the derived table's column labels. It takes the same `(List<S> slots, Function<S, ColumnRef> column)` pair as the builder's other methods, plus the table reference and the input-table local. Like `cellsCode`, it has a `String tableLocal` overload and a `CodeBlock tableExpr` overload, because `ReentryRowsFragments` joins against a constants-class reference rather than an aliased local. It returns, per slot:

```java
<table>.<COL>.eq(<input>.field("<sqlName>", <table>.<COL>.getDataType()))
```

The per-slot predicates are chained with `.and(...)`, the nesting the existing sites already emit. Each predicate is minted through `ColumnComparison.equalityAgainstField(<table>, col, col, <operand>)`, not a hand-written `.eq(`. `ColumnComparison` is the generator's single place for column equality. Here the receiver and the operand's type source are the same column, so it never coerces and the emitted text is the plain `.eq(...)` above. Routing through it means the helper cannot drift from the equality rule.

Two choices the helper fixes, with the reasons in its javadoc:

- **Input column by SQL name, not position.** `aliasArgs` writes each label from `ColumnRef.sqlName()`, and `onCondition` reads it back from the same projection, so the writer and the reader of a label sit in one class. That is the same arrangement `ReservedAliases` gives `idx`. A positional lookup (`field(i + 1, ...)`) silently depends on the slot order matching the cell order, which nothing checks.
- **Operand typed by `getDataType()`, not `T.class`.** The derived table's cells already bind at `<table>.<COL>.getDataType()` (through `cellsCode`), so typing the read-back the same way keeps a converter-backed key column's `DataType` faithful on both sides. This is the spelling `ReentryRowsFragments` and the batched parent-input predicate already use. The rendered SQL does not depend on the choice.

The helper's javadoc states why `ON` and not `USING`, at intent level: a `USING` column cannot be an expression (computed columns); Oracle forbids qualifying it, and `$project` qualifies every column it selects; and a join path through a junction table can carry a second column with the key's name, which makes an unqualified `USING` column ambiguous. The "Join syntax" paragraphs in `SelectMethodBody` and `MultiTablePolymorphicEmitter`, and the inline comment in `ProjectionUnitRenderer`, are reduced to a `{@link}` to the helper. The class javadoc's "join syntax stays caller-local" clause is rewritten: the join *call* stays with each caller, but the predicate is shared. The javadoc is not given a list of callers.

### The sites

Every consumer of `aliasArgs` reads its labels back through `onCondition`. That boundary is crisp: it is exactly the set of joins against a `VALUES` table whose labels the builder wrote over the same columns the predicate compares.

- **`RootLauncherRenderer.lookupBody`** (the fix). `.join(input).using(LookupRows.usingArgs(...))` becomes `.join(input).on(LookupRows.onCondition(mapping, tableLocal, "input", fieldName))`. `LookupRows.onCondition` is a one-line delegate shaped like its existing `aliasArgs` delegate. The method javadoc's "joins the anchor with `USING`" sentence is corrected.
- **`SelectMethodBody`** and **`MultiTablePolymorphicEmitter.buildPerTypenameSelect`**. Each has a hand-rolled `t.COL.eq(input.field("<sql>", T.class))` loop, verbatim copies of each other, that is replaced.
- **`ProjectionUnitRenderer`** (inline lookup multiset). Its `input.field(<alias>.<COL>)` loop is replaced.
- **`BatchedRowsFragments.lookupJoinCondition`** (positional `lookupInput.field(i + 1, T.class)`). It is deleted and its call site calls the helper.
- **`ReentryRowsFragments.valuesJoinOn`**. Already the target spelling. It is deleted and its call site calls the `CodeBlock tableExpr` overload.

`ValuesJoinRowBuilder.usingArgs` and `LookupRows.usingArgs` are deleted. Their only consumer is the launcher, and with them gone no generator path can emit `USING` without hand-writing it.

At the five sites that already use `ON`, only the emitted Java changes: `"t"."col" = "input"."col"` renders the same whichever lookup form picked the input field. Only the launcher's SQL moves.

**Out of scope.** The batched rows methods' parent-input predicates (`BatchedRowsFragments` around lines 223 and 713, `MultiTablePolymorphicEmitter` around line 2121) compare a *child-side* column against a *parent-key* label. That is a correlation, not a keyset narrowing. Their derived table is not labelled by `aliasArgs`, and they already go through `ColumnComparison`. They stay as they are.

### User manual

`docs/manual/how-to/batch-lookups.adoc` (line 33) tells the reader the generator builds `VALUES(idx, customer_id, store_id) JOIN customer USING (customer_id, store_id)`. That becomes the `ON` form (`JOIN customer ON customer.customer_id = input.customer_id AND customer.store_id = input.store_id`).

## Tests

- **Unit, `ValuesJoinRowBuilderTest`.** `usingArgs_javaNamesInOrderQualifiedByTableLocal` is replaced by exact-string tests of `onCondition`: one slot; two slots chained with `.and(...)`, in slot order; and the `CodeBlock tableExpr` overload.
- **Pipeline, `RootLauncherRendererTest.keyedLookupSource_valuesJoinAndTheEmptyInputShortCircuit`.** The `.join(input).using(filmTable.FILM_ID)` assertion becomes the `ON` form, `.join(input).on(filmTable.FILM_ID.eq(input.field("film_id", filmTable.FILM_ID.getDataType())))`. A negative pin `doesNotContain(".using(")` is added beside it, which is the structural form of the Oracle half: the launcher no longer emits the clause Oracle rejects.
- **Execution, the SQL baselines.** Thirteen pinned strings across four files carry the launcher's `using (...)` today. They are re-pinned to jOOQ's actual `on ...` rendering: `BatchedChildSqlBaselineTest` (6), `ConditionSqlBaselineTest` (5), `RootLauncherSqlBaselineTest` (1), and `CompositeKeyLookupQueryTest.compositeKeyLookup_emitsTwoColumnJoinOnFilmIdAndActorId`. In the last one, the assertion and its comment ("jOOQ renders it as a USING clause") change to require both `film_id` and `actor_id` equalities in the `ON`. These run against PostgreSQL and also check that every result is unchanged, so they are the evidence that the new join returns the same rows. The baselines for the inline, batched, dispatcher and reentry sites must **not** move. Moving one means the migration changed SQL it should only have re-spelled.
- **What the suite cannot show.** Neither failure reproduces in this repo's execution tier. The tests run on PostgreSQL, which accepts a qualified `USING` column. And the open-source jOOQ the fixtures build with declares client-side computed columns (`DataType.computedOnClientVirtual()`) but does not render their expressions; that substitution ships only in jOOQ's commercial editions. So the Sakila catalog cannot carry a key column that renders as an expression. The structural pin above covers both halves (no `USING` is emitted), and the sis run below is the acceptance evidence.
- **Acceptance, sis on Oracle.** Rerun the sis IT suite against Oracle with a jar built from the implementing commits, and record the run under `roadmap/audits/`, in the shape of `roadmap/audits/2026-10-05-r985-sis-acceptance.md`. The named cases are `studieprogramGittStudieprogramkoder` (ORA-25154), `studenterGittFodselsnumre` (ORA-03077) and `studenterGittFeideBrukere` (ORA-00907). The audit reports how many of the roughly 45 USING-attributed failures clear, and lists any that now fail for a different reason under the item that owns that reason.

## Implementation status

Shipped at `6f813aa87` as specified. One addition the spec did not name: a `CodeBlock`-table overload of `ColumnComparison.equalityAgainstField`, which the `ValuesJoinRowBuilder.onCondition` `CodeBlock tableExpr` overload needs to route its equality through the comparison mint. `CompositeKeyLookupQueryTest` pins jOOQ's parenthesised two-column form, `on (… and …)`.

Acceptance recorded in `roadmap/audits/2026-10-13-r1015-sis-acceptance.md`: ORA-25154, ORA-03077 and ORA-00907 go from 210, 24 and 12 to zero; 28 ITs clear and none regress. Four previously USING-masked outputs now fail on U4 (R1018, R1019), two of them at sites the sis status document had not listed.

## Retired vocabulary

- `ValuesJoinRowBuilder.usingArgs`, `LookupRows.usingArgs`, "USING-args".
- `BatchedRowsFragments.lookupJoinCondition`, `ReentryRowsFragments.valuesJoinOn` (absorbed into `onCondition`).
- Prose saying the lookup launcher, or the generator's lookup path, joins with `USING`: the `RootLauncherRenderer.lookupBody` javadoc, the user manual line above, and the `CompositeKeyLookupQueryTest` comment.

## Other solutions we've considered

- **Fix the launcher alone and copy `SelectMethodBody`'s loop into it.** This is the smallest diff. It would leave six hand-rolled spellings of one predicate across three operand forms. That drift is how the launcher kept `USING` after the dispatcher's javadoc had already explained why not to. The extra sites cost one call each and move no SQL.
- **Keep `USING` and stop `$project` qualifying key columns on Oracle.** This fixes only the Oracle half: a computed key column is still an expression inside `USING`. It would also make projection rendering depend on the join syntax of the statement it sits in.
- **Positional input-field lookup** (the current `BatchedRowsFragments` form). It renders the same SQL, but it couples the predicate to cell order with no check. The by-name form reads back the label `aliasArgs` wrote.

## Provenance

sis IT run against Oracle 18c on trunk `dd940c703` (sis branch `sis/upgrade-graphitron`, write-up in `sis/GRAPHITRON10_STATUS.md`, section "Second IT run"). This was the single largest failure cause there, about 45 of 218 ITs. Failing SQL, from `studieprogramGittStudieprogramkoder`:

```sql
from "STUDIEPROGRAM"
join (select 0 "idx", :1 "STUDIEPROGRAMKODE" from DUAL) "studieprogramGittStudieprogramkoderInput"
  using ("STUDIEPROGRAMKODE")
where "STUDIEPROGRAM"."INSTITUSJONSNR_EIER" = :2
```

Computed-key failures: `studenterGittFodselsnumre` (ORA-03077) and `studenterGittFeideBrukere` (ORA-00907).
