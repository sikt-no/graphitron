---
id: R422
title: "@reference terminal-target verdict must compare return-type identity, not the verbatim @table echo"
status: In Review
bucket: bug
priority: 3
theme: interface-union
depends-on: []
created: 2026-07-02
last-updated: 2026-07-08
---

# @reference terminal-target verdict must compare return-type identity, not the verbatim @table echo

## Problem

Split out of R396 (which fixed the FK source-side predicate for schema-qualified / case-mismatched
*source* `@table` names). This is the same bug class on a *different* author shape: a schema-qualified
**return-type** `@table`.

`BuildContext.computeTerminalTargetVerdict` (around `BuildContext.java:1307`) decides whether an
`@reference` path's terminal hop lands on the field's return-type table by comparing the canonical
resolved target against the **return type's** verbatim `@table` echo via `TableRef.sameTable`
(`tableName.equalsIgnoreCase(other)`). When the return type's `@table(name:)` carries a schema prefix
(e.g. `@table(name: "multischema_a.widget")` while the hop resolves to the canonical unqualified
`widget`), the bare-string compare returns false and the terminal verdict spuriously reports
`Mismatch` even though the hop lands correctly. R396 fixed the parent/source side by adopting jOOQ
table-class identity comparison on `JooqCatalog`; this item closes the return-type side by the same
principle.

## Root cause

`TableRef.sameTable` is a verbatim-echo `equalsIgnoreCase`. R396 deliberately scoped itself to the
source `@table` shape (one author shape across all three `@reference` directive forms, sharing one
catalog primitive) and left the return-type comparison point for a focused follow-up, because
`parsePath` threads return names as `String` across ~12 call sites, so an identity-based terminal
check touches that signature or adds a catalog resolve at the one comparison point.

## Design (settled at Spec)

The Backlog stub framed two shapes: (1) re-resolve `returnSqlTableName` through the catalog at the
comparison point, or (2) migrate the whole `parsePath` String-name plumbing to identity. That is a
false binary (principles-architect consult, 2026-07-08). The chosen design is a scoped parameter
add: **thread the already-resolved return-type `TableRef` into the verdict as its own axis**, and
leave the name plumbing alone.

1. **New nullable parameter, not a String migration.** `parsePath` gains a nullable
   `TableRef returnTableRef` parameter alongside the existing `targetSqlTableName` String. Name and
   identity are two orthogonal projections of the return table: the String stays the input to the
   name-based plumbing that legitimately wants a name (empty-path FK inference via
   `findForeignKeysBetweenTables`, condition-join terminal-target build in `parsePathElement`); the
   `TableRef` is consumed only by `computeTerminalTargetVerdict`. The call sites that pass a
   non-null target all already hold the `TableRef` and lossily project it via `.tableName()`
   (`FieldBuilder.java:863` `returnType.table()`, `:937` `tableInterfaceType.table()`, `:5080`
   `tbt.table()`, `:6069` `targetNodeType.table()`, `:6337` `tb.table()`, `:4896`/`:6008`
   `tb.returnType().table()`); those six pass the ref they hold. This is "decide once, carry the
   decision as a type": the identity was materialized one frame earlier, so no catalog re-query.
   The seventh non-null site, `NodeIdLeafResolver:429`, is the exception: at that `parsePath` call
   only the raw `@table` echo String is in hand (`NodeIdLeafResolver:256`), and the target
   `TableRef` is built downstream at `:305` (`findTable` + `toTableRef`), *after* `resolveFkJoinPath`
   returns at `:297`. The implementer must hoist that resolution above the call (or thread the ref
   through `resolveFkJoinPath`'s signature) so this site passes ref and name together like the rest;
   miswiring it (non-null name, null ref) silently flips the verdict to `NotApplicable`, which
   R381's LSP layer reads off `ParsedPath`. Null-target sites pass null and are untouched.
2. **Compare with R441's predicate.** `computeTerminalTargetVerdict` replaces
   `hop.targetTable().sameTable(returnSqlTableName)` with
   `hop.targetTable().denotesSameTableAs(returnTableRef)`. Both sides are catalog-constructed
   (`JooqCatalog.TableEntry.toTableRef` always populates `tableClass`), so the compare is
   identity-vs-identity; the predicate's intrinsic name-compare fallback exists only for
   fixture-built classless refs and never fires in production. This is the model-side identity home
   R441's javadoc prescribes.
3. **No R440-style resolve-or-fall-back contract.** Deliberately rejected: an unresolvable return
   name is unreachable at the consuming callers (the return `@table` already resolved to a
   `TableRef` upstream; an unresolvable one is `UnclassifiedType` and never reaches this site), and
   re-resolving the lossy echo could yield `Ambiguous` on a bare cross-schema name collision, whose
   bare-compare fallback would re-introduce the very bug this item closes.
4. **Verdict gating and message unchanged.** `NotApplicable` keeps firing on null start / null
   return / empty path; with the new parameter the return-side null check moves to
   `returnTableRef == null` (callers pass ref and name together, so the gate is equivalent). The
   `Mismatch` message keeps rendering the hop's canonical name against the author's verbatim
   `@table` echo (`returnSqlTableName`), which stays available for the message.

**Scoping note (empty-path inference sibling).** The same schema-qualified return echo also flows
into the empty-path FK inference at `BuildContext.java:1257`. That path is already covered: R440
made `findForeignKeysBetweenTables` resolve both arguments by class identity (schema-aware
`findTable`) with the bare-compare fallback only for genuinely unresolvable names
(`JooqCatalog.java:566-581`). No surviving sibling there; R422 closes the last member of the
schema-qualified `@table` bug class.

## Tests

- Pipeline tier, sibling of R396's `QualifiedSourceReferencePipelineTest` over the existing
  multischema jOOQ fixture (no new DDL): the cross-schema FK `multischema_b.gadget ->
  multischema_a.widget` gives the shape directly. A `@reference` field on a parent bound to
  `multischema_b.gadget` whose **return** type carries the schema-qualified
  `@table(name: "multischema_a.widget")` and whose terminal hop lands there must classify green
  (`ChildField.TableField`, not `UnclassifiedField`; terminal verdict `Match`), today a spurious
  `Mismatch` rejection. Assert at the classifier/model surface, no code-string assertions.
- Pair with a genuine mismatch over the same fixture (terminal hop lands on a table other than the
  qualified return type, e.g. return type bound to `multischema_b.event` while the hop lands on
  `widget`) that still rejects with `Mismatch`, so the fix tightens the compare without disabling
  it.
- Unit tier: `TableRefSameTablePredicateTest` (R441) already pins all three `denotesSameTableAs`
  arms; no new predicate-level coverage needed unless the verdict gains its own branching.

## Notes

- Same bug class and consumer wave as R395 / R396 (schema-qualified `@table` echoes vs. jOOQ's
  always-unqualified canonical names). Reachable only when the *return* type of an `@reference` field
  is schema-qualified, which is why R396 could ship its source-side fix without it.
- Siblings all Done as of 2026-07-08: R396 (source-side FK predicate), R440 (FK-join endpoint/FK
  identity, including `findForeignKeysBetweenTables`), R441 (typed-accessor match, landed
  `TableRef.denotesSameTableAs`), R442 (condition-param match, reused the predicate). This item is
  the last open member and reuses R441's predicate verbatim.
