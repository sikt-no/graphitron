---
id: R965
title: "Column-bound argument slots divine the tenant under a field-level @condition override"
status: Backlog
bucket: bug
priority: 2
theme: classification-model
depends-on: []
created: 2026-09-22
last-updated: 2026-09-22
---

# Column-bound argument slots divine the tenant under a field-level @condition override

## Goal

A query field whose argument is bound to the tenant column keeps its tenant binding when the field carries `@condition(override: true)`. Today it loses it: the field is rejected at build time with "no argument or input field maps to tenant column 'X'", although the argument names exactly that column, so a schema that is correct by the author's reading does not generate. *Tenant binding* is how a generated fetcher knows which tenant database to route a query to; a *tenant-scoped* table is one carrying the column named by the Mojo's `<tenantColumn>`, and a field reaching such a table is accepted only when something in scope can *divine* that column, meaning supply one value for it. Whether a predicate is emitted for an argument is an emission decision; where the tenant value comes from is a classification fact, and the two should not be tied together.

The discriminating pair, both real sis fields (2026-09-22 spike, `<tenantColumn>INSTITUSJONSNR_EIER</tenantColumn>`):

```graphql
type Query {
  # classifies today: the override sits on the argument
  personProfilerGittFodselsnumre(...): [PersonProfil]

  # rejected today: the same column binding, but the override sits on the field
  personProfilerGittFeideBrukere(
    eierOrganisasjonskode: String! @field(name: "INSTITUSJONSNR_EIER"),
    feideBrukere: [String!]! @field(name: "FEIDE_BRUKER") @lookupKey
  ): [PersonProfil]
    @condition(condition: {...}, contextArguments: ["feideDomene"], override: true)
}
```

`eierOrganisasjonskode` binds `INSTITUSJONSNR_EIER`, which is the tenant column. The field is rejected anyway.

## Mechanism (verified against the tree, 2026-09-22)

`TenantBindingIndex.Fold.directSlots` discovers an operation's tenant slots per member. For a condition member it delegates to `slotsFromFilters`, which walks `GeneratedConditionFilter.bodyParams()` and mints a `TenantBinding.BoundSlot` for every `BodyParam` whose column matches the tenant column. The slot therefore exists only where a predicate was emitted.

`FieldBuilder.classifyArguments` drops that predicate under an override; the comment at the site reads "Either flag flipping true suppresses the implicit predicate for every classified field" (`FieldBuilder.java:2148` at the time of writing). With the argument-level flag, only that argument's predicate goes, and the tenant argument keeps its own; with the field-level flag, every classified field's predicate goes, so the fold finds no body param naming the tenant column and falls through to the `noTenantBinding` rejection.

The column binding survives either way. `ConditionResolver.resolveArg` documents the condition axis as coexisting with the column-binding axis on the same argument, and `ArgumentRef` still carries the column mapping. The fold reads the wrong axis.

## Direction (to be developed at Spec)

Mint the tenant slot off the argument and input-field column-binding axis (the `ArgumentRef` column mapping) rather than off the emitted predicate, so a field-level override cannot take the binding away. Keep the existing `slotsFromFilters` path or subsume it, whichever leaves one place that answers "which column does this argument name"; a slot minted twice for one argument is already deduped by name in `directSlots`.

## Tests

The discriminating pair above is the shape to pin: argument-level override classifies, field-level override classifies too, and a field binding nothing to the tenant column still rejects. A second real form worth a cell is `@condition` `argMapping` binding through a filter input (sis `Query.emner` maps `eierInstitusjonsnummer: filter.eierInstitusjonsnummer`). `graphitron-sakila-example/src/main/resources/graphql/multitenant.graphqls` is the fixture home.

## Provenance

A sis spike on 2026-09-22 configured `<tenantColumn>INSTITUSJONSNR_EIER</tenantColumn>` against the sis schema and counted 875 `Rejection.AuthorError.NoTenantBinding` errors, against 0 on the same tree and database without the setting. 121 of those are root fields; the remaining 750 are children cascaded by the every-path fold in `TenantBindingIndex.tenantContextOf`, which demands a tenant context on every path into a tenant-scoped type. 11 of the 121 roots are this item: `brukere`, `personProfiler`, `studenter`, `emner`, `emnerV2`, `esiKandidater`, `evuKurs`, `studieoppbygninger`, `utvekslingsavtaler`, `personProfilerGittFeideBrukere`, `studenterGittFeideBrukere`. The count understates the reach, because `Query.personProfiler` is among them and its failure keeps every `PersonProfil` child red through the every-path fold.

105 more of the 121 are R966, the sibling classifier gap for write inputs keyed by a decoded node id. The two are independent; this one is the smaller change and unblocks the larger cascade, so it is worth taking first.

Every one of the 875 rejections printed without file:line coordinates, because the fold's rejections carry `SourceLocation.EMPTY`. That is R523; sis is its motivating multi-file case.
