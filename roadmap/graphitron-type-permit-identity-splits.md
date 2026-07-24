---
id: R528
title: "Carry backed-vs-unbacked by permit identity across GraphitronType and ReturnTypeRef"
status: Backlog
bucket: cleanup
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-07-24
last-updated: 2026-07-24
---

# Carry backed-vs-unbacked by permit identity across GraphitronType and ReturnTypeRef

The backed-vs-unbacked axis is carried by permit identity on the result side (`GraphitronType.PojoResultType.Backed`, whose javadoc records that sites read the permit rather than a nullable `ResultType#fqClassName()`), but three other carriers still smuggle it through a nullable slot, the shape the sealed hierarchy exists to prevent ("Shape the type as precisely as the fact allows"):

- `JooqTableRecordType` is minted with a `null` `fqClassName` at three main-source sites (`FieldBuilder` twice, `TypeBuilder` once) as a stand-in for "the parent's runtime source is a projected table row"; nothing is reflected there, so the class slot is meaningless for that population. A distinct class-less `JooqRecordCarrier` arm (e.g. `TableRowType(name, location, table)`) makes `fqClassName()` non-null across every remaining arm with no `requireNonNull` needed.
- `PojoInputType` carries backing as a nullable class where `PojoResultType` split into permits; `CatalogBuilder.projectTypeBackingShapes` forks it on `fqClassName() == null` into a *result*-named `NoBacking.UnbackedResult()` carrier for an input type. Split into `Backed`/`Unbacked` mirroring the result side so the projection seam is an exhaustive switch, not a null check.
- `ReturnTypeRef.ResultReturnType#fqClassName` is the same fact copied into a second record with the opposite nullability contract ("taken directly from the corresponding `GraphitronType.ResultType` ... May be `null`"), with four `FieldBuilder` consumers forking on the null; a derived fact maintained apart from its source. Carry the resolved `ResultType` (or its arm identity) instead of a re-copied string so the forks become sealed switches.

Routed from the R526 investigation, which corrects the surrounding stale docs but is chartered for per-site corrections, not taxonomy changes; the three bullets are one design move and should land together.
