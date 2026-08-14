---
id: R671
title: "Producer domain types are placeholders, so a shared class-backed type read as a record component can never pass the multi-producer conflict check"
status: Spec
bucket: bug
priority: 3
theme: diagnostics
depends-on: []
created: 2026-08-14
last-updated: 2026-08-14
---

# Producer domain types are placeholders, so a shared class-backed type read as a record component can never pass the multi-producer conflict check

A shared value type that one field produces through a batched `@service` and another field reads as a component of a record-backed parent is rejected at build time, even though both producers put the very same Java object at `env.getSource()`. The rejection is `Rejection.AuthorError.MultiProducerDomainTypeDisagreement`, and it is spurious: the two producers do not report what they hand down, they report placeholders, and the placeholders can never be equal. There is no schema-side workaround, so the shape simply cannot be authored. Reported from a consumer subgraph where the type's shape is fixed by federation (another subgraph declares the field `@shareable`), which is what makes it a blocker rather than a naming inconvenience.

## The shape that fails

```graphql
type Kvotetype @table(name: "kvotetype") {
    navn: OversatteTekster @service(service: {className: "...", method: "navnByKvotetype"})
}
type OversatteTekster { nn: String  nb: String  se: String  en: String }
type SvarKvotepoeng {          # record-backed: the parent's backing class holds a navn() accessor
    navn: OversatteTekster
    poenggrense: BigDecimal
}
```

Here `navnByKvotetype` is a batch function with the signature `Map<KvotetypeRecord, OversatteTekster> navnByKvotetype(Set<KvotetypeRecord>)`, and `SvarKvotepoeng`'s backing class is a Java record whose `navn()` component returns an `OversatteTekster`. The build fails with:

```
type 'OversatteTekster' is produced with disagreeing env.getSource() Java domain types:
  - Kvotetype.navn → Plain(java.util.Map)
  - SvarKvotepoeng.navn → Plain(java.lang.Object)
```

## Why the check exists, and what it is really comparing

Any field returning an SDL object type is a *producer* for that type: at run time it puts a Java value at `env.getSource()`, and graphql-java hands that value to the datafetchers generated for the type's child fields. Graphitron generates each child fetcher once per coordinate and does not branch on the runtime source class, so two producers that hand down structurally different values would feed one of them into a fetcher compiled against the other's shape. `GraphitronSchemaBuilder.collectDomainReturnTypeConflicts` catches that by grouping the classified field registry by SDL return type and comparing each producer's `OutputField.domainReturnType()` by sealed-arm equality.

The `DomainReturnType` arms keep a sparse jOOQ `Record` projection, a typed jOOQ `TableRecord` and a plain domain object apart on purpose, because "same class" would paper over a projection difference. Inside the `Plain` arm, however, equality is exact `TypeName` equality, and that is where placeholder values become fatal.

## Root cause: neither participant reports its real source type

Two sites answer with something other than the class they hand down.

`ChildField.ServiceRecordField.domainReturnType()` answers `Plain(OutputField.peelToClassName(method.returnType()))`. `peelToClassName` unwraps `Optional`, `CompletableFuture`, `List`, `Set`, `Collection` and `org.jooq.Result`, but not `Map`, and otherwise falls through to the raw type. A batch function's `Map<K, V>` therefore collapses to `java.util.Map`, which is a type that never reaches `env.getSource()` at all: the DataLoader resolves the map to one `V` per key, and `V` is what graphql-java sees. The correct answer already exists one method above, as `ServiceRecordField.elementType()` (via `RowsMethodShape.strictPerKeyType`, which returns the leaf's backing class for a `ReturnTypeRef.ResultReturnType`).

`ChildField.RecordReadField.domainReturnType()` answers `Plain(Object)` for its `ValueLocator.JavaAccessor`, `ValueLocator.ByName` and `ValueLocator.DefaultRead` arms; only `ValueLocator.TypedColumn` reports a real type. For the accessor arm the true type is available and unused: the locator carries the resolved accessor, whose declared return type is the class the read produces.

The consequence is that three producers of one Java class report three different values, so any two of them collide:

| producer | reported arm |
|---|---|
| root `@service` returning the class directly | `Plain(...OversatteTekster)` |
| batched child `@service` returning `Map<K, OversatteTekster>` | `Plain(java.util.Map)` |
| record component read through the `navn()` accessor | `Plain(java.lang.Object)` |

## Evidence that the conflict is false

The generator's own reflection-based binding fold disagrees with the conflict check on the same schema. `RecordBindingResolver.peelReturnElement` peels `Map<K, V>` to `V` correctly, so both producers ground the *same* `Class`, the per-type fold agrees, and no `RecordBindingMultiProducer` rejection fires. The type then classifies as a single `JavaRecordType` whose children are ordinary `RecordReadField` reads. One binding, one emitted fetcher shape, one Java class at `env.getSource()`: the two producers agree in every sense the check claims to be protecting.

Measured against trunk at filing time with an inline-SDL pipeline fixture. The batched table-parent producer alone classifies cleanly with no diagnostics, and the record-component producer alone classifies cleanly; only the combination is rejected. A root producer paired with a record component read fires the same rejection with no batching involved, so this is not specific to `@service` batching. It follows that any class-backed type read as a record component anywhere and produced anywhere else is unauthorable today, which is precisely the shared-value-type pattern federation pushes consumers toward.

## Plan

Make every producer report what it actually hands down, give "cannot say" a structural home in the vocabulary, and let the grouping site compare only real claims. Emit is untouched; what changes is the metadata the conflict reduction compares, plus the admission of schema shapes the placeholders used to reject by accident.

### Vocabulary: split the sealed root into Claim and NoClaim

Restructure `DomainReturnType` as `sealed interface DomainReturnType permits Claim, NoClaim`, with `sealed interface Claim extends DomainReturnType permits Record, TableRecord, Plain` and `record NoClaim()` meaning "this producer cannot state what it puts at `env.getSource()`". The grouping and participant-comparison helpers in `collectDomainReturnTypeConflicts` are declared over `Claim`, so the invariant "a no-claim never participates in an agreement comparison" is enforced by the type system, not by a filter someone must remember. The three existing arms and their equality semantics are untouched; no exhaustive switch over `DomainReturnType` exists today (`TenantBindingIndex` uses `instanceof Record`, the builder groups by equality, `Rejection` renders `toString`), so the split is additive at every consumer.

`Optional<DomainReturnType>` at the `OutputField.domainReturnType()` signature was considered and rejected: it touches all ~35 leaf implementations and every consumer, and it removes the value from the diagnostic rendering, where a no-claim should still read as a statement ("makes no source-type claim").

### One mint for backing-class claims

Three spellings of "the backing class as a `TypeName`" exist today, and two of them disagree on nested classes: `ClassName.bestGuess` (used by `ChildField.RecordCompositeField.domainReturnType()`) keeps the binary `Outer$Nested` as one simple name, while `RowsMethodShape.fromBinaryName` splits it into `Outer.Nested`; the two `TypeName`s are unequal, which is a fresh instance of the exact bug this item retires. Introduce a single factory (`DomainReturnType.claimForBacking(ReturnTypeRef)` or similar, routing through the `fromBinaryName` splitting) and answer every class-backed producer through it: the sharpened `RecordReadField` and `ServiceRecordField` below, plus `RecordCompositeField` (migrating it off `bestGuess`) and `QueryField.QueryServiceRecordField` (migrating its object-return case off reflected `peelToClassName`, which stays only for the scalar returns that never enter the grouping).

### Producer answers

* `ChildField.ServiceRecordField.domainReturnType()` answers the per-key element, the same `V` the emitted `DataLoader<K, V>` is typed with: the factory over `returnType()` (which is `RowsMethodShape.strictPerKeyType`'s grounded backing class for a `ResultReturnType` leaf). `Map` never reaches `env.getSource()`; `V` does.
* `ChildField.RecordReadField.domainReturnType()` forks on `returnType()`, spelled out per arm:
  * `ResultReturnType` with non-null `fqClassName`: the factory's backing-class claim, regardless of locator arm. This is the consumer-level identity precedent set by `ServiceTableField.domainReturnType()` (which answers `Record(table)` although the method returns the typed record). Deliberately derived from the leaf's `ReturnTypeRef`, not the reflected accessor handle, so a covariant accessor declaration or a raw generic return cannot mint a new false conflict.
  * `ResultReturnType` with null `fqClassName` (the `JooqTableRecordType` stand-in population; the null is a stand-in marker, not a designed contract, and this consumer joins its reader set): `NoClaim`.
  * `ScalarReturnType` under a `ValueLocator.TypedColumn` locator: the column type, as today. Under the other locator arms: `NoClaim`.
  * `TableBoundReturnType` / `PolymorphicReturnType` do not reach this leaf.
* Migrate the remaining placeholder `Plain(Object)` answers to `NoClaim`: `ErrorsField` (its SDL return *is* an object type, so it does enter the grouping, and it genuinely hands down a heterogeneous list of developer exception classes), plus the never-grouped polymorphic and pivot answerers (`QueryNodeField`, `QueryNodesField`, `QueryInterfaceField`, `QueryUnionField`, `QueryServicePolymorphicField`, `QueryServiceTableInterfaceField`, their mutation analogues, `ChildField.InterfaceField` / `UnionField` / `BatchedInterfaceField` / `BatchedUnionField`, `PivotSlotField`). The never-grouped migrations are behaviour-neutral by construction (their SDL returns are interfaces, unions or scalars, which `sdlReturnTypeName` filters out); the only surface their answers reach is the rejection rendering, where `NoClaim` is honest and `Plain(java.lang.Object)` was a false statement. After this sweep, `Plain(Object)` as a placeholder is retired; `OutputField.OBJECT_CLASS` survives only as `peelToClassName`'s structural fallback.

### Grouping site

`collectDomainReturnTypeConflicts` compares claims only: group each SDL Object type's producers by their `Claim` values, no-claims excluded from the comparison. A group with fewer than two distinct claims is not a conflict. No-claim producers stay in the rejection's `Participant` list when a real conflict fires; the author's first question is "who else produces this type", and the placeholder value was the bug, not the participant row. `NoClaim.toString()` renders as a statement to that end.

### What the check still enforces, stated honestly

After this change, every class-backed producer in a group derives its `Plain` claim from the same type-grain fact (`GraphitronType.ResultType.fqClassName()`, copied onto each leaf's `ReturnTypeRef`), so the `Plain`-vs-`Plain` comparison over class-backed types agrees by construction. Class identity for class-backed SDL types is enforced upstream, by `RecordBindingResolver`'s per-type binding fold (`RecordBindingMultiProducer`); the reduction's remaining teeth are cross-arm (`Record` vs `TableRecord` vs `Plain`), which is exactly the projection-vs-typed-record-vs-domain-object axis the arms were built to separate. The reduction's javadoc must say so and carry a `{@link}` to `RecordBindingResolver`, so the reference gate pins the linkage instead of two sites asserting the same invariant with one real enforcer.

The change deliberately loosens accidental rejections: today `Plain(Object)` never equals a real class, so any placeholder-answering producer conflicted with any real claimant. Each newly-accepted population maps to the enforcer that still covers its genuinely-broken variant:

| newly accepted | still rejected when broken | enforcer |
|---|---|---|
| class-backed type produced by batched/root `@service` and read as a record component | producers grounding different Java classes for one SDL type | `RecordBindingResolver` binding fold (`RecordBindingMultiProducer`) |
| object type produced through a `ValueLocator.ByName` / `DefaultRead` read | locator arm incompatible with the parent's source-object shape | `GraphitronSchemaValidator`'s record-read arm-admissibility rule (see `ValueLocator` javadoc) |
| class-backed type also reached as a nesting projection | the unsupported jOOQ-record-carrier + nesting combination | the validator's shape-set rule over `MixedSourceReachIndex` (already exempted via `isSupportedMixedSourceReach`) |

What remains an honest gap: a `DefaultRead`-produced object type (a field on an `@error` parent returning a class-backed type) is statically unverifiable end to end; we accept it on the same trust as every other `DefaultRead`, and a wrong developer class surfaces at run time. That is the correct trade: unverifiable is not disagreeing, and the placeholder's accidental rejection of it was not an invariant.

## Tests

Unit tier (`DomainReturnTypeCoverageTest`): `NoClaim` structural equality and `toString`; the sealed-graph walk keeps covering every leaf; the `Claim`/`NoClaim` split's permits are pinned. The two producer-answer assertions do *not* live here: hand-built leaves would bypass the derivation under test.

Pipeline tier: the reported shape, asserted positively (the coordinate classifies to the expected leaf and the shared type lands as `JavaRecordType`; absence-of-diagnostic alone can hold vacuously on a differently-broken fixture):

* `@table` parent with a batched child `@service` (`Map<XRecord, Pojo>` via a new batch method on `TestFilmService` / `TestServiceStub`) plus a class-backed parent reading the same `Pojo` as a record component: both producers classify, no `MultiProducerDomainTypeDisagreement`, and the producers' `domainReturnType()` values are the identical backing-class claim.
* Root `@service` producer plus record-component read: same assertions, pinning that the fix is not batching-specific.
* A nested backing class produced through both `RecordCompositeField` and a record-component read: pins the single-mint factory (the `bestGuess`-vs-`fromBinaryName` divergence).
* Existing cross-arm conflict tests (`SingleRecordTableFieldServiceProducerPipelineTest`'s DML `Record(film)` vs service `TableRecord(FilmRecord)` pair) keep passing unchanged: the check keeps its cross-arm teeth.

Compile backstop: this flips a build-rejecting shape into a build-emitting one, so the newly-admitted schema reaches the emitters for the first time. Add the shared-type shape (batched `@service` producer + record-component read of one class-backed type) to the `graphitron-sakila-example` fixture surface so the emitted fetchers must compile at Java 17. No execution tier: both producers already put the same object at `env.getSource()` at run time, so runtime behaviour is not what changed.

Masked-conflict sweep: full `mvn install -Plocal-db`; any fixture newly firing the rejection was a real conflict the placeholders masked. Triage each, never suppress.

## Docs

`docs/architecture/explanation/typed-rejection.adoc` names `MultiProducerDomainTypeDisagreement` in the taxonomy tree; check whether any prose there describes the `Plain` placeholder semantics and adjust. `DomainReturnType`'s javadoc gains the `Claim`/`NoClaim` split description. The `QueryServiceTableInterfaceField` and `MutationServiceTableInterfaceField` javadoc sentences describing their answer as "`Plain` over `Object`" are rewritten with the migration.

## Retired vocabulary

* `Plain(Object)` / `Plain(java.lang.Object)` as a placeholder domain-return answer (the value survives only where it is a genuine `peelToClassName` structural fallback, never as "this producer doesn't know").

## Adjacent, not in scope

A batched child `@service` whose leaf is an object type, sitting on a record-backed parent, drops both the parent type and the leaf type out of the model even when the leaf is independently bound and the parent can produce a valid batch key, and the only diagnostic is the generic "did not classify" on the field that returns the parent. A scalar leaf in the same position classifies fine. That is a separate defect on the classify path, not a diagnostics one, and it deserves its own item.

