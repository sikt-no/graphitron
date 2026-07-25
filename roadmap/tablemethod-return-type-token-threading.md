---
id: R240
title: "Type-token threading on MethodRef.StaticOnly + ReturnTypeRef.TableBoundReturnType"
status: Backlog
bucket: architecture
priority: 7
theme: service
depends-on: []
created: 2026-05-25
last-updated: 2026-07-25
---

# Type-token threading on MethodRef.StaticOnly + ReturnTypeRef.TableBoundReturnType

Surfaced by R237 Phase 2 as a (b-relational) structural-lift candidate. `MethodRef.StaticOnly`
carries a reflected `returnType` token and `ReturnTypeRef.TableBoundReturnType` carries the field's
`TableRef`, but nothing structural ties the two: where a leaf pairs them, the emitters assume the
method's return token equals the field's table token and declare `<SpecificTable> table = ...`
without a cast. The contract is a *relationship* neither carrier encodes.

Lift: parameterise `MethodRef.StaticOnly` and `ReturnTypeRef.TableBoundReturnType` on a shared
`<T extends Table<?>>` type token; thread it through every site that constructs or reads either,
so the table-token equality is enforced at construction rather than left to emitter assumption.

Scope note (2026-07-25): the `@tableMethod` half of the original motivation is gone. The directive
was removed, taking with it the root fetcher that declared the uncast local and the strict
`ClassName.equals` return check in `ServiceCatalog.reflectTableMethod` that guarded it. What
survives is `MethodRef.StaticOnly` itself, minted on the `@condition` path
(`ServiceCatalog.java:577`) and the `@externalField` path (`:650`), plus every
`ReturnTypeRef.TableBoundReturnType` construction and read across the classifier and emitters. The
lift still has a subject; its blast radius is smaller and no longer retires a live runtime check.

Blast radius: every call site that constructs or reads `MethodRef.StaticOnly` or
`ReturnTypeRef.TableBoundReturnType` needs the type-token threaded through, and the threading hits
the boundaries jOOQ helpers accept type erasure at (per `emitter-conventions.adoc § "Selection-aware
queries"`). Some sites may need explicit `Table<?>` widening, which caps how far the bound carries.

The sibling service-catalog return-type contract (already structural because `MethodRef.Service`
carries a captured parameterised return type) benefits as a side effect: the type-token threading
lifts both halves of the service-catalog return-type contract under one structural mechanism.

Pre-conditions: none outstanding; R237 has shipped.

Out of scope: the (b-cheap) `ColumnBackedField.parentTable` lift (R239's territory). The two are orthogonal axes of the type-system gap R237 surfaced.
