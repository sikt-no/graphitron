---
id: R659
title: "@defaultOrder on a root routine chain is silently dropped"
status: Backlog
bucket: bug
theme: routine
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# @defaultOrder on a root routine chain is silently dropped

Field report (Sikt tilgangsstyring). A root `@routine` + `@reference` chain terminating on a
catalog table, carrying `@defaultOrder(primaryKey: true)`, classifies clean and reports nothing:

```graphql
mineApplikasjonsAdminOrganisasjoner: [Organisasjon]
    @routine(name: "mine_applikasjons_admin_organisasjoner")
    @reference(path: [{table: "organisasjon"}])
    @defaultOrder(primaryKey: true)
```

The generated SQL carries no `ORDER BY` and rows arrive in hash order. The schema declares a
sorting contract the runtime does not honour, and nothing warns the author. The consumer found
it only because a test happened to assert list order; the workaround was deleting the directive
and documenting "order is undefined, the client sorts".

## Diagnosis

`RoutineDirectiveResolver.orderOrConditionDeferral` is the deferral that fires for the
composition surfaces, and it checks exactly three things: `@condition` on the field,
`@condition` on an argument, `@orderBy` on an argument. `@defaultOrder` is absent from that
set, so it passes through.

The two chain classifiers then diverge on what they do with it:

* `FieldBuilder.classifyChildRoutineChain` calls `OrderByResolver.resolve` against the chain's
  terminus table and carries the resulting `OrderBySpec`. `@defaultOrder` on a child routine
  list works, and the manual's `recentFilms` example is a child field, so the documented shape
  is the one that happens to be honoured.
* `FieldBuilder.classifyRootRoutineChain` never consults `OrderByResolver` at all; it passes a
  literal `new OrderBySpec.None()` into `QueryField.QueryTableField`, whose compact constructor
  pins the `RoutineResolution.Chain` read surface empty. The directive is discarded between
  parse and model with no diagnostic.

`GraphitronSchemaValidator.validateListRequiresOrdering` cannot catch the fallout either: it
exempts the `Chain` arm outright, so the list-shaped-plus-`None` signal that protects every
other list field is switched off precisely where the drop happens.

Two documentation surfaces overstate what ships today and should be corrected by whatever
slice lands the diagnostic:

* `docs/manual/reference/directives/routine.adoc` lists `@orderBy` and `@condition` on
  routine-backed fields as reported deferred, and says nothing about `@defaultOrder`.
* `validateListRequiresOrdering`'s javadoc claims "`@orderBy` / `@defaultOrder` on `@routine`
  is a classify-time typed deferral". The `@defaultOrder` half of that sentence is false.

## Shape of the fix

Priority order is the reporter's, and the two halves slice independently.

1. **Stop the silent no-op.** A sorting directive on a routine chain must produce a localised
   diagnostic at the coordinate. Widening `orderOrConditionDeferral` to include
   `@defaultOrder` is the small move, but it must not regress the child position, which
   honours the directive today and *requires* it for a routine terminus (a TVF result table
   has no primary key, so the PK fallback lands `None` and the deterministic-order validator
   fires). So the deferral is root-position-only, or is keyed on terminus kind rather than on
   directive presence. Worth checking whether the same silent path exists for the Mutation
   chain and carrier seats.
2. **Support ordering on catalog-terminus root chains.** When the chain ends on a catalog
   table the ordering contract exists (primary key or named columns), so `ORDER BY` is
   well defined there and the child classifier already proves the resolution works. This is
   R448's "root ordering reconciliation" bullet, which reconciles the root chain carrying no
   ordering surface against a child routine list requiring `@defaultOrder`; landing it means
   relaxing the `QueryTableField` constructor pin and the `validateListRequiresOrdering`
   exemption from "the whole `Chain` arm" to "routine terminus only". A pure routine terminus
   can stay deferred.

Related: `roadmap/routine-chain-residue.md` (R448) holds the root-ordering reconciliation as
non-gating residue; this item is the field-reported bug that makes the near half urgent.

