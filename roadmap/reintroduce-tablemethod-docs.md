---
id: R403
title: "Rethink and reintroduce @tableMethod"
status: Backlog
bucket: docs
theme: docs
priority: 30
depends-on: []
deferred: true
notes: "the directive and its machinery are removed; reintroduction is a fresh design, not a re-advertising edit. Not a release priority"
created: 2026-06-30
last-updated: 2026-07-25
---

# Rethink and reintroduce @tableMethod

R400 withheld `@tableMethod` from the first-release advertised surface: implemented and test-covered
but used by no consumer schema, so v1 did not document it. **The directive has since been removed
outright (2026-07-25).** The declaration is gone from `directives.graphqls`, and so is every piece of
machinery behind it: the directive resolver, both `GraphitronField` leaves, both fetcher builders, the
`TableExpr.MethodCall` node, the `FieldClassification.TableMethod` projection, and the LSP/MCP arms.
A rewrite schema applying it now fails schema validation as an unknown directive; a legacy schema gets
the migration report's "Legacy-only directives" drop-before-migrating message.

This item is the parking ticket, and the place to do the rethink. **Not a release priority** (priority
deliberately set low).

**Reintroduction is a fresh design, not a re-advertising edit.** The earlier decision (2026-07-15) kept
the directive declared and merely unadvertised, so bringing it back was a one-line edit to
`WITHHELD_FROM_V1`. That is no longer true. Whoever picks this up rebuilds from the rethink agenda
below; git history is the recovery source for the prose and for the shape the removed machinery took,
but nothing survives in tree to re-enable.

Decision recorded 2026-07-15: the team confirmed `@tableMethod` stays out with no near-term
reintroduction, on the basis that `@routine` covers the pressing need. The active support work was
discarded (R277, `tablemethod-under-nested-type`, was closed rather than built; the R288 N+1 fix was
narrowed to the polymorphic-interface case). The 2026-07-25 removal follows through on that decision.
This item is kept, at low priority, specifically to carry the design history and the rethink agenda
below so a future feature request starts from context rather than scratch. It is not scheduled work.

This item previously absorbed a parallel "Remove the `@tableMethod` directive" proposal, on the
grounds that the idea was good enough to rethink rather than delete under release pressure. The
2026-07-25 user decision went the other way: with no consumer using it and `@routine` covering the
need, keeping an unadvertised directive live was a standing liability. The agenda below survives that
reversal unchanged; it is now the input to a rebuild rather than to a re-advertisement.

## Rethink agenda (absorbed from the superseded remove-directive proposal)

`@tableMethod` lets a developer static Java method choose *which* jOOQ `Table<?>` backs a field's
`SELECT`, selected at request time from GraphQL argument values (documented use cases:
sharded-by-tenant tables, archived-vs-live history tables), with graphitron's selection-narrowing
(`$fields`) preserved. Nothing else delivers exactly that. It is wired across three model leaves:
`QueryField.QueryTableMethodTableField` (root), `ChildField.TableMethodField` (table-bound parent,
a per-row **synchronous** fetcher, i.e. an N+1 flagged by R288), and a DTO/`@service`-parent shape that
had dissolved onto the DataLoader-batched record-sourced `BatchedTableField`. All three are deleted;
the names are recorded here as design history, recoverable from git.

Questions the rethink should settle before reintroducing:

* **Is the capability worth keeping at all, or do `@routine` + `@service` cover it?** `@routine`
  (R300) is the faithful target for the subset where the table choice is expressible as a
  parameterised DB routine, and it preserves projection. `@service` covers the general case but
  loses graphitron column projection (the developer owns the whole fetcher). If those two cover the
  real use cases, retirement (the absorbed proposal) becomes the answer after all.
* **Fix or drop the child-on-`@table`-parent N+1?** That shape (`ChildField.TableMethodField`) is
  the per-row synchronous fetcher R288 wants gone. A reintroduction should not bring the N+1 back
  unfixed.
* **Front-door shape, decided 2026-07-25:** neither the declared-and-rejected front door
  (`@notGenerated` / `@multitableReference`) nor parse-but-ignore. The declaration is deleted, so an
  application fails graphql-java schema validation as an unknown directive, and a migrating legacy
  consumer is told to drop it by the report's Legacy-only section. That is the correct signal for a
  directive that was never advertised.

## Recovery source (when reintroducing the docs)

R400 Stage 2 removed `@tableMethod`'s dedicated documentation, and the 2026-07-25 removal took the
declaration and machinery. Both are intact in git history, so restore rather than re-author. The
recovery is anchor-free (no hardcoded SHA to go stale):

```
git log --oneline --diff-filter=D -- docs/manual/reference/directives/tableMethod.adoc
git checkout <that-commit>^ -- docs/manual/reference/directives/tableMethod.adoc
```

Then re-thread its index entry (`reference/directives/index.adoc` alphabetical + *Querying*
category, and `reference/index.adoc`) and the teaching passages / `xref`s in the recipes that framed
it (`how-to/handle-services.adoc`, `result-types.adoc`, `external-code.adoc`,
`add-custom-conditions.adoc`, `condition-cascade.adoc`, and the others in R400 Stage 2's removal
diff). The machinery comes back from the removal commit's diff, redesigned per the agenda above; it
no longer suffices to edit `WITHHELD_FROM_V1`, since the directive is not declared at all.

## Trigger

Pick this up only after the rethink concludes `@tableMethod` should re-enter the surface (a real
consumer adopts it, or we decide to advertise it). Deferred until then.
