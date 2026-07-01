---
id: R403
title: "Rethink and reintroduce @tableMethod"
status: Backlog
bucket: docs
theme: docs
depends-on: []
deferred: true
notes: "gated on @tableMethod re-entering the supported surface after a rethink; not a release priority"
created: 2026-06-30
last-updated: 2026-06-30
---

# Rethink and reintroduce @tableMethod

R400 withholds `@tableMethod` from the first-release advertised surface: it is implemented and
test-covered but used by no consumer schema, so v1 does not document it. The directive stays
declared and working; it is simply not advertised. This item is the parking ticket for bringing it
back, and the place to do the rethink the withholding buys us time for. **Not a release priority.**

This item **absorbs the parallel "Remove the @tableMethod directive" proposal** (the upstream R400
that this branch's R400 took over). That proposal argued for retiring the directive outright; the
decision instead is to keep the idea (it is good) and rethink it while it is unused, rather than
delete a real capability under release pressure.

## Rethink agenda (absorbed from the superseded remove-directive proposal)

`@tableMethod` lets a developer static Java method choose *which* jOOQ `Table<?>` backs a field's
`SELECT`, selected at request time from GraphQL argument values (documented use cases:
sharded-by-tenant tables, archived-vs-live history tables), with graphitron's selection-narrowing
(`$fields`) preserved. Nothing else delivers exactly that. It is wired across three model leaves:
`QueryField.QueryTableMethodTableField` (root), `ChildField.TableMethodField` (table-bound parent,
a per-row **synchronous** fetcher, i.e. an N+1 flagged by R288), and
`ChildField.RecordTableMethodField` (DTO/`@service` parent, DataLoader-batched).

Questions the rethink should settle before reintroducing:

* **Is the capability worth keeping at all, or do `@routine` + `@service` cover it?** `@routine`
  (R300) is the faithful target for the subset where the table choice is expressible as a
  parameterised DB routine, and it preserves projection. `@service` covers the general case but
  loses graphitron column projection (the developer owns the whole fetcher). If those two cover the
  real use cases, retirement (the absorbed proposal) becomes the answer after all.
* **Fix or drop the child-on-`@table`-parent N+1?** That shape (`ChildField.TableMethodField`) is
  the per-row synchronous fetcher R288 wants gone. A reintroduction should not bring the N+1 back
  unfixed.
* **Front-door shape if retired instead:** keep declared + typed `InvalidSchema.Structural`
  rejection (the `@notGenerated` / `@multitableReference` precedent), not parse-but-ignore (there is
  no reflection fallback for "which table backs this field", unlike `@record`).

## Recovery source (when reintroducing the docs)

R400 Stage 2 removes `@tableMethod`'s dedicated documentation. The prose is intact in git history,
so restore rather than re-author. The recovery is anchor-free (no hardcoded SHA to go stale):

```
git log --oneline --diff-filter=D -- docs/manual/reference/directives/tableMethod.adoc
git checkout <that-commit>^ -- docs/manual/reference/directives/tableMethod.adoc
```

Then re-thread its index entry (`reference/directives/index.adoc` alphabetical + *Querying*
category, and `reference/index.adoc`) and the `@tableMethod` teaching passages / `xref`s in the
recipes that framed it (`how-to/handle-services.adoc`, `result-types.adoc`, `external-code.adoc`,
`add-custom-conditions.adoc`, `condition-cascade.adoc`, and the others in R400 Stage 2's removal
diff), and remove `tableMethod` from the `WITHHELD_FROM_V1` set in `DirectiveSupportReport` so it
reappears under "Supported directives".

## Trigger

Pick this up only after the rethink concludes `@tableMethod` should re-enter the advertised surface
(a real consumer adopts it, or we decide to advertise it). Inverse of R400; only meaningful after
R400 ships. Deferred until then.
