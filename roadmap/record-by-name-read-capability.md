---
id: R502
title: "Reads-by-name-off-Record capability for jOOQ-record-backed types"
status: Backlog
bucket: architecture
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-07-18
last-updated: 2026-07-18
---

# Reads-by-name-off-Record capability for jOOQ-record-backed types

The fact "this type's runtime carrier is a generic jOOQ `Record`, so child fields read it by
name" is restated as the same `instanceof JooqTableRecordType || instanceof JooqRecordType`
disjunction at four sites (census below). Per "Capabilities reify an orthogonal axis", a recurring
cross-variant fact should be a capability interface the member variants implement, consulted by
the read sites, instead of an `instanceof` OR-list restated per site: today nothing binds the four
lists to each other, and a future variant with the same carrier (R501's `PivotProjection` is the
live candidate) would have to find and grow every one.

## Design

**New capability `GraphitronType.JooqRecordCarrier`**, a sealed marker interface at the
`GraphitronType` root level (deliberately not under `ResultType`, so a future non-`ResultType`
variant with the same carrier can join without touching the `ResultType` contract):

```java
sealed interface JooqRecordCarrier permits JooqRecordType, JooqTableRecordType {}
```

The membership criterion is structural and stated in the interface javadoc: the runtime object a
fetcher receives for this type (via `env.getSource()` or `Outcome.Success.value()`) is a generic
`org.jooq.Record`, so a child field carrying only a column name reads it as
`((Record) source).get(DSL.field(name))`, cast to `Record`, never through a reflected accessor,
and never env-dependent.

*Why a marker with no accessor.* The by-name read consumes only the field's `columnName`; the type
contributes the carrier fact and no data, so there is nothing for an accessor to expose. The
membership-drift caveat ("a hand-declared marker nothing binds to the base facts") is answered
structurally rather than by test: the interface is `sealed`, so the member list is closed,
single-sourced at the declaration, and adjacent to the two variants in `GraphitronType.java`; the
consuming sites carry `{@link GraphitronType.JooqRecordCarrier}` javadoc (liveness enforced by the
javadoc reference gate). A membership meta-test is not proportionate for a two-member capability
whose members live in the same file as the declaration.

*Sealed, and membership requires a consumer.* Sealing means a joining variant edits the permits
clause; that cost is the point, since joining is a semantic claim every consulting site starts
acting on. R501's `PivotProjection` does **not** join in this item nor automatically in R501: as
respecified, pivot slots read through a dedicated `PivotSlotField` leaf arm that never consults
the parent's `GraphitronType`, so PivotProjection would be a member with no consuming site, which
is exactly the drift risk the caveat names. It joins if and when a read site actually consults the
capability for pivot parents.

## Site census

The four sites whose OR-list the capability replaces, each keeping its surrounding guards and
emission unchanged (`instanceof GraphitronType.JooqRecordCarrier` substitutes for the two-arm
disjunction; the sites' `ResultType`-typed parameters stay narrowed, the intersection test
compiles as-is):

- `FetcherEmitter.propertyOrRecordBinding`: the by-name arm emitting
  `return ((Record) source).get(DSL.field(columnName));`. The preceding typed-constant arm
  (`JooqTableRecordType` with a resolved `ColumnRef` and `TableRef`) is an identity fork and is
  untouched.
- `FetcherEmitter.inlineSuccessRead`: the same by-name read inlined onto `success.value()` (same
  two arms, Outcome arm-switch path). Same treatment: typed-constant arm untouched.
- `FetcherEmitter.isEnvDependentAccessorRead`: the same fact consumed negatively, a jOOQ-carrier
  read is source-only and never injects the environment.
- `SourceRowDirectiveResolver.rejectByParentShape`: builder-side, the disjunction selects the
  "`@sourceRow` is not supported on jOOQ-backed parents" rejection message. Consuming the model
  capability here keeps validator prose keyed to the same fact the emitters act on.

**Kept as identity forks (out of the capability's reach):** `GeneratorUtils.recordColumnReadArgs`
and the `KeyLift.FkColumns` key-lift emission it serves fork four-way on `ResultType` identity,
and their two jOOQ arms genuinely differ (typed `Tables.X.COL` constant read vs `sql_name` string
read). That is the sealed-switch half of the capabilities principle; replacing it with the marker
would erase a real distinction. Any site that needs the *which-jOOQ-variant* fact keeps switching
on identity.

## Implementation

- `GraphitronType.java`: declare the sealed marker, javadoc carrying the membership criterion;
  `JooqRecordType` and `JooqTableRecordType` implement it.
- The four census sites swap their disjunction for the capability test. No parameter type widens;
  no emission changes.
- Javadoc at each consuming site links the capability; if `SealedHierarchyDocCoverageTest`'s scope
  covers `GraphitronType` sub-hierarchies, add the permit-to-doc mapping it requires (verify at
  implementation).

## Tests

Pure refactor with byte-identical emitted output; no classifier decision changes, so no new
validator arm and no new pipeline pin. Acceptance is the existing tiers green on
`mvn install -Plocal-db` (pipeline, `graphitron-sakila-example` compile, PostgreSQL execution),
which pin the by-name read behaviour end to end. The javadoc reference gate covers the new
`{@link}`s.

## Out of scope

- `PivotProjection` membership (follows R501, and only once a consulting read site exists).
- Reworking the four-way identity forks (`GeneratorUtils.recordColumnReadArgs` and kin).
- Any change to emitted code or to the `ResultType` contract.

## Roadmap entries

Single slice, purely additive plus four one-line guard swaps.
