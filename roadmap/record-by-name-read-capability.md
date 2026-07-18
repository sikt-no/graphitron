---
id: R502
title: "JooqRecordCarrier: reify the jOOQ-Record carrier partition of ResultType"
status: Ready
bucket: architecture
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-07-18
last-updated: 2026-07-18
---

# JooqRecordCarrier: reify the jOOQ-Record carrier partition of ResultType

The fact "this type's runtime carrier is a generic jOOQ `Record`" is restated as the same
`instanceof JooqTableRecordType || instanceof JooqRecordType` disjunction (or the equivalent
grouped switch arm, or its `JavaRecordType || PojoResultType` complement) at six code sites and
two javadoc sites (census below). Today nothing binds those lists to each other: a future variant
with the same carrier would have to find and grow every one, and a missed site diverges silently
(the drifted-allow-list smell). This item reifies the partition once, in the model, and makes
every restating site consult it.

## Design

**New sealed intermediate `GraphitronType.JooqRecordCarrier` under `ResultType`:**

```java
sealed interface JooqRecordCarrier extends ResultType permits JooqRecordType, JooqTableRecordType {}
```

`ResultType`'s permits become `JavaRecordType, PojoResultType, JooqRecordCarrier`; the two jOOQ
variants move down one level. Existing exhaustive switches over `ResultType` that name the four
leaves stay valid (leaf cases cover the tree); grouping sites may collapse two arms into one
`case JooqRecordCarrier`.

*Why an intermediate, not a root-level capability marker.* "Runtime carrier is `org.jooq.Record`"
is not an axis orthogonal to the `ResultType` backing axis; it is a partition of that axis's
members ({jOOQ record, jOOQ table record} vs {Java record, POJO}), the sub-taxonomy case of
"Shape the type as precisely as the fact allows". A root-level marker would serve the
`instanceof` sites but cannot appear as an arm in the exhaustive sealed switches that group the
two variants today (`FieldBuilder.deriveAccessorRecordParentSource`,
`FieldBuilder.derivePolymorphicHubSource`); re-entering those as `instanceof` guards would degrade
sealed switches back to predicates. The intermediate serves both consumer shapes and keeps
exhaustiveness compiler-checked. Its one-line sub-taxonomy justification, per the principle: it
carries closed membership of the carrier partition, which no field on a sibling can.

*The javadoc states the carrier fact, not "read by name".* What is uniformly true across both
members, and what every consuming site actually relies on, is: the runtime object a fetcher
receives for this type (`env.getSource()` or `Outcome.Success.value()`) is a generic
`org.jooq.Record`; the `(Record) source` cast is always valid; reads are source-only and never
env-injecting; no reflected accessor exists. The *read strategy* is deliberately not part of the
contract: `JooqTableRecordType` with a resolved `ColumnRef` reads by typed `Tables.X.COL`
constant, and only the fallback reads by `DSL.field(name)`; that distinction is per-variant and
stays in the identity forks (census below).

*No methods, no meta-test.* The by-name fallback read consumes only the field's `columnName`; the
type contributes the carrier fact and no data, so there is nothing for an accessor to expose, and
forcing one would leak the per-variant read strategy up into the shared type. The
membership-drift caveat ("a hand-declared marker nothing binds to the base facts") is discharged
by sealing: the permits clause is the single-sourced, compiler-closed membership, adjacent to the
two variants in `GraphitronType.java`. A membership meta-test would be redundant against it.

*Membership requires a consumer; placement follows the same rule.* R501's `PivotProjection` does
**not** join here nor automatically in R501: as respecified, pivot slots read through a dedicated
`PivotSlotField` leaf arm that never consults the parent's `GraphitronType`, so PivotProjection
would be a member with no consuming site, exactly the drift risk the caveat names. For the same
reason the interface is scoped under `ResultType`, where every current member and every consuming
signature lives, rather than provisioned at the `GraphitronType` root for a member that does not
exist. If a later item introduces a non-`ResultType` carrier *with a consuming read site*, that
item lifts the declaration to the root then, at a compile error, which is the right moment.

## Site census

Derived from a grep for co-occurring `JooqRecordType` / `JooqTableRecordType` references across
main sources at spec time. **The implementer re-runs that grep at pickup**; any site it surfaces
beyond this list either consults the partition or joins the fenced-off list below with a stated
reason, never a silent skip.

Converting sites, each keeping its surrounding guards and emission unchanged:

- `FetcherEmitter.propertyOrRecordBinding`: the by-name fallback arm emitting
  `return ((Record) source).get(DSL.field(columnName));` tests
  `instanceof GraphitronType.JooqRecordCarrier` instead of the two-arm disjunction. The preceding
  typed-constant arm (`JooqTableRecordType` with a resolved `ColumnRef` and `TableRef`) is an
  identity fork and is untouched.
- `FetcherEmitter.inlineSuccessRead`: the same two arms on the Outcome arm-switch path, inlined
  onto `success.value()`. Same treatment.
- `FetcherEmitter.isEnvDependentAccessorRead`: the fact consumed negatively, a jOOQ-carrier read
  is source-only and never injects the environment.
- `SourceRowDirectiveResolver.rejectByParentShape`: builder-side, the disjunction selects the
  "`@sourceRow` is not supported on jOOQ-backed parents" rejection message. Consuming the
  partition here keeps the validator's prose keyed to the same fact the emitters act on.
- `FieldBuilder.deriveAccessorRecordParentSource` and `FieldBuilder.derivePolymorphicHubSource`:
  exhaustive `ResultType` switches whose grouped arm
  `case JooqRecordType _, JooqTableRecordType _ -> null` collapses to
  `case JooqRecordCarrier _ -> null`.
- `FieldBuilder.resolveRecordAccessor`: tests the complement form
  (`!(JavaRecordType || PojoResultType)` implies no reflective accessor resolution); with the
  partition reified, the guard reads as the direct fact, a `JooqRecordCarrier` parent resolves no
  accessor.

Javadoc-only groupings that repoint to the new symbol via `{@link}` (liveness enforced by the
javadoc reference gate): `FieldBuilder.resolveRecordAccessor`'s javadoc and the
`ChildField.PropertyField` / `ChildField.RecordField` `accessor` component javadoc, both of which
currently name the two variants in prose.

**Fenced off (identity forks and broader groupings, deliberately untouched):**

- `GeneratorUtils.recordColumnReadArgs` and the `KeyLift.FkColumns` key-lift emission it serves:
  four-way identity forks whose two jOOQ arms genuinely differ (typed `Tables.X.COL` constant
  read vs `sql_name` string read). The sealed-switch half of the capabilities principle; any site
  needing the *which-jOOQ-variant* fact keeps switching on identity.
- `CatalogBuilder`'s projection switches and `TypeBuilder`'s variant mint: per-identity by
  design (the view-projection seam and the classifier's decide-once site).
- `EntityResolutionBuilder.kindLabel`: groups **all four** `ResultType` leaves under one
  "a record-backed type" label, so its natural collapse is `case ResultType`, not the carrier
  partition; adjacent cleanup a passing implementer may take, but not a consumer of this fact.

## Implementation

- `GraphitronType.java`: declare the sealed intermediate with the carrier-fact javadoc; move the
  two jOOQ variants' membership under it; adjust `ResultType`'s permits.
- Swap the six converting sites onto the partition; repoint the two javadoc sites. No parameter
  type widens; no emission changes.
- If `SealedHierarchyDocCoverageTest`'s scope covers `GraphitronType` sub-hierarchies, add the
  permit-to-doc mapping it requires (verify at implementation).

## Tests

Pure refactor with byte-identical emitted output; no classifier decision changes, so no new
validator arm and no new pipeline pin. Acceptance is the existing tiers green on
`mvn install -Plocal-db` (pipeline, `graphitron-sakila-example` compile, PostgreSQL execution),
which pin the read behaviour end to end, plus the compiler itself: every exhaustive `ResultType`
switch must still compile against the restructured permits, which is the exhaustiveness guarantee
the intermediate exists to preserve. The javadoc reference gate covers the new `{@link}`s.

## Out of scope

- Membership for any non-`ResultType` variant, and lifting the declaration to the
  `GraphitronType` root (both wait for a carrier variant with a consuming read site; R501's
  `PivotProjection` currently has none).
- Reworking the identity forks and broader groupings fenced off in the census.
- Any change to emitted code or to the `ResultType` accessor contract.

## Roadmap entries

Single slice: one sealed-hierarchy restructure plus six guard/arm swaps and two javadoc repoints.
