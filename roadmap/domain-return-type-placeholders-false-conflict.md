---
id: R671
title: "Producer domain types are placeholders, so a shared class-backed type read as a record component can never pass the multi-producer conflict check"
status: Backlog
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

## Fix direction

Make both sites report what they actually hand down, and leave the arm vocabulary alone.

* `ServiceRecordField` answers `Plain(elementType())` instead of peeling the raw method return.
* `RecordReadField`'s `JavaAccessor` arm answers the resolved accessor's declared return type.
* `ByName` and `DefaultRead` genuinely do not know their type, so they need to be modelled as "makes no claim" rather than as an `Object` that can disagree with a real class. This is the one design decision in the item: either a fourth `DomainReturnType` arm or an `Optional` at the grouping site.

The check keeps its teeth. `Record`, `TableRecord` and `Plain` still separate a catalog projection from a typed record from a domain object, `isSupportedMixedSourceReach` still exempts the legitimate dual reach, and a genuine two-class conflict is still caught upstream by the binding fold. Worth checking at Spec time whether any currently passing fixture relies on two producers both answering `Object` to *avoid* firing; sharpening the values may surface real conflicts that are masked today.

## Adjacent, not in scope

A batched child `@service` whose leaf is an object type, sitting on a record-backed parent, drops both the parent type and the leaf type out of the model even when the leaf is independently bound and the parent can produce a valid batch key, and the only diagnostic is the generic "did not classify" on the field that returns the parent. A scalar leaf in the same position classifies fine. That is a separate defect on the classify path, not a diagnostics one, and it deserves its own item.

