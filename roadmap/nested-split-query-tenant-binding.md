---
id: R1009
title: "A @splitQuery field on a nesting type routes on its tenant binding"
status: Backlog
bucket: architecture
priority: 6
theme: runtime-connection
depends-on: []
created: 2026-10-09
last-updated: 2026-10-09
---

# A @splitQuery field on a nesting type routes on its tenant binding

## Goal

Under a configured tenant column, a `@splitQuery` field on a nesting type (an object type without `@table` reached through a field on a `@table` parent, so it shares the parent's row) routes on the tenant binding it would get if it sat directly on that parent. In practice that is `Inherited` from a tenant-scoped parent: the fetcher reads through `dslFor` on the parent's tenant, and the DataLoader name is `tenantLoaderName(env)`, so a batch never mixes tenants. Today such a field has no binding at all. Its fetcher emits `getDslContext(env)`, which is the fixed default source under owned connections, and a path-keyed loader name, while `graphitron:validate` reports nothing. The sis port has 15 such sites in 10 wrapper types, all reading tenant tables, for example `EmneFagkoblinger.fag` under `Emne @table(name: "EMNE")`. When this lands, a null binding on a batched or tenant-reaching field under a configured tenant column is a build failure rather than a silent default-source read, so the next hole of this kind is caught at build time.

## Mechanism

Two independent causes, and either one alone produces the wrong fetcher:

1. *The binding index never sees the field.* `TenantBindingIndex.Fold.run()` arms only the coordinates in `schema.fields()`. `GraphitronSchemaBuilder` does not classify a directiveless nesting type's fields as top-level fields; they exist only in `ChildField.NestingField.nestedFields()`, classified against the enclosing `@table` parent. The coordinate therefore gets neither an arm nor the `noTenantBinding` rejection, which is why validation stays green.
2. *The nested fetcher class is generated without the schema.* `TypeFetcherGenerator` builds a nesting type's Fetchers class with a null `GraphitronSchema`, and `TenantDslEmitter.resolve` returns `singleTenant` when the schema is null, before it looks up a binding. The loader-name declaration goes through the same path.

On top of both, `TenantDslEmitter` falls back to `singleTenant` whenever `tenantBindingOf` returns null, under `TenantScopes.Configured` as well, in `resolve`, `dslExpression`, `handDownOnly` and `loaderNameDeclaration`.

## Plan sketch

- The fold descends `NestingField.nestedFields()` recursively and runs each child through `armOf`, keyed by (nesting type name, child name). `armOf` and its helpers look the field up in `fields` and in the operation-member rows, neither of which holds nested coordinates today, so the child is handed in directly or the relations learn about nested coordinates. A nesting type reached from several parents must yield one agreeing arm per coordinate, or a rejection.
- `TypeFetcherGenerator` passes the schema when it generates a nesting type's Fetchers class.
- Under `TenantScopes.Configured`, a null binding on a batched or tenant-reaching field throws as a generator invariant instead of emitting `singleTenant`.
- Tests: a classification case pinning `Inherited` on a nesting type's `@splitQuery` field (including one whose `@reference` carries a `@condition` join, and one declared in an `extend type`), a pipeline assertion on the emitted `dslFor` and `tenantLoaderName`, and an execution case in sakila-example if a tenant fixture accommodates a nesting type.

Open at Spec: whether a nesting type under a *global* parent should get `ParentRowBound` through the same path, and how this interacts with the `@splitQuery` marker sweep (R557), which covers the marker on the nesting field itself.

## Provenance

Reported by the sis Graphitron 10 port on 2026-10-09 against trunk 2653fcab4. Affected sis coordinates: `EmneFagkoblinger.fag`, `Sensurinformasjon.kommisjoner`, `KarakterResultat.regel`, `OppnaddKarakterEmnesamling.regel`, `EmneVurderingsperiode.forsteTermin`/`sisteTermin`, `EmneUndervisningsperiode.forsteTermin`/`sisteTermin`, `EmneUndervisningsoversikt.undervisningsterminer`/`undervisningStartterminIPeriode`/`undervisningStartterminIperiode`/`beskrivelseAvUndervisningsterminer`, `StudieoppbygningsdelVeivalg.veivalgperiodeForKull`, `StudieoppbygningsdelFrieEmnevalg.begrensninger`, `EmneVektingsinformasjon.vektingsreduksjonsregler`. They fail closed in sis today, since its default source refuses every connection, which blocks go-live but not the rest of the port.
