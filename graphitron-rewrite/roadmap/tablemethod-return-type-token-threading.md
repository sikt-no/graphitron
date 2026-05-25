---
id: R240
title: "Type-token threading on MethodRef.StaticOnly + ReturnTypeRef.TableBoundReturnType"
status: Backlog
bucket: architecture
priority: 7
theme: structural-refactor
depends-on: []
created: 2026-05-25
last-updated: 2026-05-25
---

# Type-token threading on MethodRef.StaticOnly + ReturnTypeRef.TableBoundReturnType

Surfaced by R237 Phase 2 as the (b-relational) candidate keyed `service-catalog-strict-tablemethod-return`. `ServiceCatalog.reflectTableMethod` rejects developer methods whose return type is wider than the generated jOOQ table class via a strict `ClassName.equals` comparison, and `TypeFetcherGenerator.buildQueryTableMethodFetcher` (`generators/TypeFetcherGenerator.java:1035`, `:1114`) declares `<SpecificTable> table = Method.x(...)` with no cast and feeds the local directly into `<SpecificTable>Type.$fields(...)`. The contract is a *relationship*: the field's table token equals the method's return token at runtime, but neither `MethodRef.StaticOnly` nor `ReturnTypeRef.TableBoundReturnType` carries this relationship structurally.

Lift: parameterise `MethodRef.StaticOnly` and `ReturnTypeRef.TableBoundReturnType` on a shared `<T extends Table<?>>` type token; thread it through every site that constructs or reads either. The strict-`ClassName.equals` runtime check at `ServiceCatalog.java:498` becomes redundant once the type-token enforces the equality at construction.

Higher blast radius than the single-record narrow lift in R239: every call site that constructs or reads `MethodRef.StaticOnly` or `ReturnTypeRef.TableBoundReturnType` needs the type-token threaded through, and the threading hits the boundaries jOOQ helpers accept type erasure at (per `rewrite-design-principles.adoc § "Selection-aware queries"`). Some sites may need explicit `Table<?>` widening, which caps how far the bound carries.

The sibling key `service-catalog-strict-service-return` (classified (a) in R237's Phase 2 table because `MethodRef.Service` already carries a captured parameterised return type) benefits as a side effect: the type-token threading lifts both halves of the service-catalog return-type contract under one structural mechanism.

Pre-conditions: R237 Phase 1 has shipped. The lift can proceed independently of R237's Phase 4 timing — the annotation retires with R237 regardless of whether this lift has shipped (the pre-lift signature is fine; once the lift lands, the contract is mechanically enforced).

Out of scope: the (b-cheap) `ColumnField.parentTable` lift (R239's territory). The two are orthogonal axes of the type-system gap R237 surfaced.
