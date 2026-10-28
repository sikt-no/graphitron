---
id: R1117
title: "A scalar @externalField counts as a presence leaf, so a wrapper of them collapses to null"
status: Backlog
bucket: feature
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-10-28
last-updated: 2026-10-28
---

# A scalar @externalField counts as a presence leaf, so a wrapper of them collapses to null

## Goal

A nullable field returning a nesting type (a wrapper: an object type with no table of its own, whose fields read the parent's row) resolves to `null` when every value in it is null, and this extends to wrappers holding scalar `@externalField` fields. A scalar `@externalField` counts as a *presence leaf* (a value the wrapper's null check reads), as a column does. A wrapper made only of `@externalField`s, or of columns and `@externalField`s together, then collapses to `null` when all its leaves are null, as in Graphitron 9. Today any `@externalField` in a wrapper makes it always resolve to an object, so it answers with an object whose fields are all null.

## The case from sis

Three experimental sis wrappers each hold four nullable `String` fields, `en`, `nb`, `nn` and `se`, and nothing else. Every one of them is an `@externalField` on the parent's table, not a column:

* `Resultat.navnAlleSprak: ResultatAlleSprak`
* `EkskludertEmneIResultatsammendrag.emnenavn: ResultatAlleSprak`
* `OppnaddKvalifikasjon.navnAlleSprak: OppnaddKvalifikasjonNavnAlleSprak`

The fields are `en: String @externalField(reference: {className: "...ResultatService"})` and so on. Each method returns a `Field<String>`. For example, `ResultatService.en(Studentvurdkombprotokoll)` calls the database function `fEmnenavn(...)` for one language. In Graphitron 9 the wrapper was `null` when all four were null. In Graphitron 10 it answers `{en: null, nb: null, nn: null, se: null}`. These are the three `@externalField` wrappers that stayed blocked when R1026's `@reference` amendment was sized against sis.

## Where it is blocked today

`Presence.SubtreePresence.over` collects a wrapper's presence leaves. Its javadoc lists `@externalField` under "Everything else blocks", and in the code a `ChildField.ComputedField` falls to the `default -> Blocked` arm. `NullContract.of` turns a `Blocked` subtree into `AlwaysObject`. The user manual gives the rule in `map-types-to-tables.adoc` § "Grouping columns into a nested object" ("A type holding … an `@externalField` … always resolves to an object, because whether it holds anything depends on the query, on code outside the table's rows, …"), and repeats it in `migrating-from-legacy.adoc`'s divergence list.

That rationale fits `@service`. It does not fit a scalar `@externalField`: the code runs at generation time and returns a SQL expression over the parent's row, so the value is decided in the same statement as the wrapper's columns, with no extra query (unlike a `@splitQuery` child).

## Proposed shape (from the sis request)

A scalar `@externalField` on the parent's own row (a `ComputedField` with an empty `joinPath` and a scalar return) becomes a presence leaf. A wrapper collapses when all its leaves are null, whether they are columns, reference probes or scalar `@externalField`s. A wrapper that mixes columns and `@externalField`s follows the same rule.

It is a third leaf shape, not a reuse of an existing one. `PresenceLeaf.Columns` reads columns off the table's catalog constant. `PresenceLeaf.Probe` is a correlated `EXISTS` read back under `ReservedAliases.presenceProbe`. A `ComputedField`'s value is an arbitrary `Field<X>`, read back under its own result-key alias (`ResultKeyAliasedField`). The touch points are:

* the `default -> Blocked` arm of `SubtreePresence.over`;
* `NullContract.CollapsesWhenAbsent`, whose split into `columnLeaves()` and `probes()` gains a third kind;
* `FetcherEmitter.presenceCheck` and `PresenceFragments.check`;
* the `CallWrap.Splice.callerDemand` splice that carries presence values into the anchor's projection.

## Open questions for Spec

* **Cost when not every leaf is selected.** R1026 decides presence over the whole subtree, whatever the query selects: `NestingNullContractExecutionTest` pins that a subset selection agrees with the full one. Today an `@externalField` is projected only when it is selected. As a presence leaf it has to be projected whenever the wrapper is selected, so `navnAlleSprak { en }` evaluates `fEmnenavn` four times per row instead of once. That is no extra query, but it is not free either. Spec decides whether to accept the cost or bound it. One option is to project a single boolean (`e1 IS NOT NULL OR …`) instead of four aliased values. Either form evaluates all four expressions.
* **Shared wrappers.** `GraphitronSchemaValidator.comparePresenceColumns` rejects a shared nested type whose inner collapsing field reads different columns per parent. sis's `ResultatAlleSprak` is shared by two parents, and an `@externalField` method takes the parent's table as its argument, so its expression differs per parent by construction. Spec has to say what "the same presence" means for an expression leaf. For example, is the same `MethodRef` enough?
* **Scope.** sis needs only the scalar, own-row case. A `ComputedField` with a non-empty `joinPath`, and a non-scalar `@externalField` whose `Field<...>` element type backs an object type, stay `Blocked` unless Spec finds them as cheap.

## Docs

Remove `@externalField` from the always-object list in `map-types-to-tables.adoc` § "Grouping columns into a nested object" and in the `migrating-from-legacy.adoc` divergence list, and say that a scalar `@externalField` counts like a column.

## Provenance

Requested 2026-10-28 by the sis Graphitron 10 port session, which tracks it as C23 in `GRAPHITRON10_STATUS.md`. It is not a port blocker: until this lands, sis accepts and announces the object-with-nulls answer.
