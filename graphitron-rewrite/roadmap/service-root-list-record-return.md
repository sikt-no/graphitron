---
id: R127
title: "Accept List<XRecord> as well as Result<XRecord> at root @service"
status: In Review
bucket: architecture
priority: 5
depends-on: []
---

# Accept List<XRecord> as well as Result<XRecord> at root @service

Root `@service` fields with a `@table`-bound list return type currently require the developer's method to declare exactly `org.jooq.Result<XRecord>`. A real schema in the wild (`opptak-subgraph` / `RegelverksamlingMutations.opprettRegelverksamling`) declares `java.util.List<RegelverksamlingRecord>` instead, which is a strictly looser shape (`Result<R>` extends `List<R>`). The classifier rejects it with `service method could not be resolved — method '...' must return 'Result<RegelverksamlingRecord>' to match the field's declared return type — got 'List<RegelverksamlingRecord>'`, even though graphql-java would handle the value identically. This plan loosens the root strict-return check to accept either shape on the list arm only, leaves the single arm strict, and keeps the existing classifier guarantee that downstream emitters can declare a typed local (`Result<XRecord>` or `List<XRecord>`) rather than `Object`.

## Workflow note

This item was opened directly in `In Progress`: implementation began before the gate was filed (the inbound was framed as an operational bug report, and the agent did not pause to file a Backlog entry per CLAUDE.md). Filing in `In Progress` rather than retroactively staging through Backlog → Spec → Ready is the honest representation of the artifact's state. The reviewer-rule still applies at `In Review → Done`. The CLAUDE.md gate was tightened in the same branch so the next inbound of this shape won't slip past.

## Implementation

* `ServiceDirectiveResolver.computeExpectedServiceReturnType` — for `TableBoundReturnType` + List, return `null` (skip the strict catalog-side `TypeName.equals` check). Single cardinality keeps returning the specific `XRecord`. Other arms unchanged.
* `ServiceDirectiveResolver.validateRootInvariants` — add §3: when `returnType` is `TableBoundReturnType` + List, accept iff the method's reflected return type equals `org.jooq.Result<XRecord>` or `java.util.List<XRecord>`. Reject with a paired-shape message (`must return 'Result<XRecord>' or 'List<XRecord>'`). Child `@service` is not in scope (the child-rows shape is `Map<K, V>` / `List<List<V>>` / `List<V>`; the user-facing pair only makes sense at the root).
* `TypeFetcherGenerator.buildQueryServiceTableFetcher` and `.buildMutationServiceTableFetcher` — for the List arm, read `field.method().returnType()` (captured by `ServiceCatalog` from reflection) instead of constructing `ParameterizedTypeName.get(RESULT, recordClass)`. Generated assignment compiles whether the developer chose `Result<X>` or `List<X>`. Single arm unchanged.
* `ServiceCatalog`'s `service-catalog-strict-service-return` `LoadBearingClassifierCheck` docstring — narrow the description: the catalog-side strict check now covers only the Single arm of `@table`-bound returns (plus the existing `ResultReturnType` paths); the List arm of `@table`-bound is validated post-reflection in `ServiceDirectiveResolver.validateRootInvariants` §3. Both `DependsOnClassifierCheck` annotations on the two emitters get the matching update.

## Tests

* `ServiceCatalogTest` — no change required. The catalog continues to receive a strict `TypeName` (or null) and behaves exactly as before; the existing strict-return tests (`reflectServiceMethod_mismatchedCardinality_failsListVsSingle`, `reflectServiceMethod_mismatchedInnerGeneric_failsStructurally`, `reflectServiceMethod_matchingParameterizedExpected_succeeds`) all stay green.
* `TestServiceStub.getFilmsAsList` — add a stub returning `java.util.List<FilmRecord>` so a pipeline-tier test can exercise the new acceptance.
* Pipeline-tier test (in `ServiceRootFetcherPipelineTest`, or the closest existing test that exercises root `@service` + `[Film!]!` against `getFilms`): add a parallel case pointing the same field at `getFilmsAsList`, assert classification succeeds and the emitted fetcher declares `List<FilmRecord>` as the local type. The existing `getFilms` case verifies the `Result<FilmRecord>` arm.
* Negative case: root `@service` + `[Film!]!` pointed at a method returning `Result<LanguageRecord>` or `List<LanguageRecord>` (wrong record class) — assert the new §3 reject message names both `Result<FilmRecord>` and `List<FilmRecord>` as the accepted pair, plus the actual mismatched class.
* Compilation-tier coverage falls out of the existing pipeline harness: the generated fetcher must `mvn compile` against a service class that returns `List<XRecord>`.

## Roadmap entries

This is a one-shot loosening; no follow-up items expected. If a similar case surfaces for child `@service` (DataLoader rows-method shape) or for `ResultReturnType` (`@record` payloads), file separately.
