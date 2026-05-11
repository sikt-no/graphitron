---
id: R137
title: "@service method return-type wrapper composition (Optional / CompletableFuture / Mono / DataFetcherResult)"
status: Backlog
bucket: architecture
priority: 6
theme: service
depends-on: [synthesize-payload-carrier]
---

# @service method return-type wrapper composition

Today's `@service` substrate matches the method's declared return type strictly against the SDL return shape (a single ground type, optionally wrapped in `List<T>` or `Set<T>` on sources). Wrapper layers like `Optional<T>`, `CompletableFuture<T>`, `Mono<T>`, and `DataFetcherResult<T>` are not admitted on the return side; `ServiceCatalog.peelContainer` only handles `List`/`Set` and only for sources parameters. R75's Phase 2 spec describes an 8-case wrapper-composition matrix (4 wrappers × 2 element kinds — Table and Record — under the single-record carrier trigger) at the execution tier, but that matrix cannot be exercised end-to-end until the `@service` substrate admits the four wrapper layers on the method's reflected return type. This item carves the wrapper-composition work out of R75 (which lands the structural carrier surface only) and tracks it as a focused `@service` extension.

In scope: extend `ServiceDirectiveResolver` / `ServiceCatalog` to peel `Optional<T>`, `CompletableFuture<T>`, `Mono<T>`, `DataFetcherResult<T>` from `@service` method return types when matching against the SDL return; align the generator emit (call shape, await/unwrap, `DataFetcherResult` carriage) for each wrapper; introduce strict service-return-shape validation against R75's `DataElement` (the data element's class for `DataElement.Record`, the input table's PK row shape for `DataElement.Table`); ship the 8-case execution matrix `SingleRecordCarrierServiceTest` parameterising over `{T, Optional, CompletableFuture, Mono, DataFetcherResult} × {Table, Record}` against the sakila Postgres testcontainer.

Out of scope: any change to R75's carrier-admission classifier surface (R75 Phase 2 lands `DataElement` + `SingleRecordIdentityField` + DML rejection of record-element carriers); reactive end-to-end propagation beyond `Mono`-unwrap-at-fetcher (the rest of graphql-java's async story is its own concern); multi-field carriers (R128).

Depends on R75 (Phase 2 shipped — the carrier surface is the substrate this builds on; the wrapper-composition matrix slots in at the `MutationServiceRecordField` + `SingleRecordIdentityField` / `SingleRecordTableField` seam R75 establishes).
