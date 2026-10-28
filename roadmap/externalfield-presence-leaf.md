---
id: R1117
title: "A scalar @externalField counts as a presence leaf, so a wrapper of them collapses to null"
status: Spec
bucket: feature
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-10-28
last-updated: 2026-10-28
---

# A scalar @externalField counts as a presence leaf, so a wrapper of them collapses to null

## Goal

A nullable field returning one object resolves to `null` when the object would hold nothing, and a scalar `@externalField` (a field whose value is a SQL expression a developer method builds over the parent's row) counts toward that like a column does: a wrapper made only of `@externalField`s, or of columns and `@externalField`s together, resolves to `null` when every one of them is `null`, as in Graphitron 9. Today any `@externalField` in the object makes it always resolve to an object, so it answers with an object whose fields are all `null`.

The shape, from sis:

```graphql
type Resultat @table(name: "studentvurdkombprotokoll") {
    navnAlleSprak: ResultatAlleSprak
}
type ResultatAlleSprak {
    en: String @externalField(reference: {className: "...ResultatService"})
    nb: String @externalField(reference: {className: "...ResultatService"})
    nn: String @externalField(reference: {className: "...ResultatService"})
    se: String @externalField(reference: {className: "...ResultatService"})
}
```

`resultat { navnAlleSprak { en nb } }` answers `navnAlleSprak: null` for a row whose four names are all `null`, where today it answers `{en: null, nb: null}`. The same rule reaches every object the null rule decides: a nesting type (an object type with no table of its own, whose fields read the parent's row), a child with its own `@table`, and a single read on `Query`.

## The case from sis

Three experimental sis wrappers each hold four nullable `String` fields, `en`, `nb`, `nn` and `se`, and nothing else, every one an `@externalField` on the parent's table:

* `Resultat.navnAlleSprak: ResultatAlleSprak`
* `EkskludertEmneIResultatsammendrag.emnenavn: ResultatAlleSprak`
* `OppnaddKvalifikasjon.navnAlleSprak: OppnaddKvalifikasjonNavnAlleSprak`

Each method returns a `Field<String>`; `ResultatService.en(Studentvurdkombprotokoll)`, for one, calls the database function `fEmnenavn(...)` for one language. `ResultatAlleSprak` is shared by two parents. These are the three `@externalField` wrappers that stayed blocked when R1026's `@reference` amendment was sized against sis.

## Why it is sound

The always-object rule exists for children whose value is decided outside the parent's statement: a `@service` call, a list whose emptiness depends on its arguments, a database the parent's statement cannot reach. A scalar `@externalField` is none of those. Its method runs at generation time and returns a SQL expression over the parent's own row (`ServiceCatalog.reflectExternalField` admits exactly one `Table<?>` parameter and a `Field<X>` return), so its value is decided in the same statement as the wrapper's columns, with no extra query.

## Design

### A third presence leaf, carried by the probe column

A *presence leaf* is a value the object's null check reads; the object is absent when every leaf is. Two kinds exist today, both in `Presence.PresenceLeaf`: `Columns` (own-row columns, read by the fetcher off the table's catalog constant) and `Probe` (a reference or argument-free list, rendered as a correlated `EXISTS`). The probes do not reach the fetcher one by one: `PresenceProbes.of` folds every `Probe` leaf of a contract into one `PresenceProbe`, a single projected column aliased `ReservedAliases.presenceProbe(parentType, field)` and rendered by `PresenceProbeFragments.declare` as `CASE WHEN <every column leaf absent> AND (EXISTS(...) OR ...) THEN 1 END`. The fetcher checks the column leaves first and then whether that one column is non-null.

A scalar `@externalField` becomes a third leaf, `PresenceLeaf.Computed(ChildField.ComputedField field)`, and rides in the same probe column as one more disjunct, `<Owner>.<method>(<alias>).isNotNull()`. For sis's wrapper the probe renders as:

```sql
CASE WHEN en(t) IS NOT NULL OR nb(t) IS NOT NULL OR nn(t) IS NOT NULL OR se(t) IS NOT NULL THEN 1 END
```

This is the whole reason for folding rather than adding a value-per-leaf read. The computed value has no column identity to read back by: in the selection it lands under the client's result key (`ResultKeyAliasedField`), which an unselected field does not have. The probe column already has a reserved alias, already rides every carrier that serves a null contract, and already sits behind the column-leaf guard. So the change needs no new projected slot, no new carrier field and no new fetcher read.

Every carrier picks it up through `PresenceProbes.of` with no change of its own: the nesting splice (`CallWrap.Splice.probe`), the same-row arm (`CallWrap.SameRow.probe`), the single inline multiset (`CallWrap.Multiset.probe`), the batched rows method (`LauncherCommands.ChainPresence`), and the root single read (`NullChecks.of` into `NullCheck.Collapses.probe`). In each the probe is declared over a table alias local typed as the generated table class (`table`, the multiset's terminal hop alias, `BatchedRowsFragments`' terminal alias, the root launcher's correlated alias), which is what the `@externalField` method's parameter accepts, as it does at the `SelectTerm.HelperCall` site that projects the field's own value (`ProjectionUnitRenderer`, `Owner.method(table)`).

### What counts as a computed leaf

`SubtreePresence.over` gains a `ComputedField` arm: a leaf when its `joinPath` is empty and its `returnType()` is a `ReturnTypeRef.ScalarReturnType` (scalar or enum, any wrapper; a list-valued one counts when non-null, as a list column does), `Blocked` otherwise. The leaf's compact constructor rejects a non-empty `joinPath`, as `Probe`'s rejects an empty one. In today's tree that arm's `Blocked` branch is reached only by a non-scalar `@externalField` (a `Field<FilmRecord>` lift returning an object type, such as `Inventory.filmRef`): `validateComputedField` already defers every non-empty `joinPath`. Both stay blocked; a non-scalar lift's presence is its own object's question, and a joined computed field is not emitted at all yet.

`targetColumnLeaves` (the column leaves a reference probe looks for on the row it reaches) keeps its `default` arm: a probe target type holding a computed field still counts the reached row's existence alone. Carrying computed expressions into a probe's terminal row is possible (call the method over the terminal alias) but no consumer has asked for it, and existence alone errs toward keeping an object.

### One predicate for "this contract has a probe"

Today two sites decide independently whether a contract has a probe: `PresenceProbes.of` (`contract.probes().isEmpty()`) and `FetcherEmitter.presenceCheck` (the same test, to decide whether to read the probe alias). With a third leaf kind that duplication becomes a drift point: a contract of columns plus computed leaves has no `Probe` leaf but does have a probe column. Replace both with one source:

* `NullContract.probed()` returns the collapsing contract's leaves that the probe column carries, every leaf that is not `Columns`, in leaf order; empty otherwise. `probes()` stays as the typed view of the `Probe` leaves alone, which the pipeline and unit tests assert on, but no production site decides from it any more.
* `PresenceProbes.of` maps each of them to a term by an exhaustive switch over `PresenceLeaf`, so a fourth leaf kind fails to compile here rather than silently dropping out of the probe.
* `FetcherEmitter.presenceCheck` reads the probe alias exactly when `probed()` is non-empty.

### The probe term becomes a seal

`PresenceProbe.ProbeTerm` is a record of a correlated reach today. It becomes a sealed interface of two arms:

* `ProbeTerm.Exists(ReachPath reach, GlueCall filter, List<ColumnRef> targetColumns)`, the current record unchanged in content.
* `ProbeTerm.NotNull(MethodRef method)`, rendered as `<Owner>.<method>(<correlatedAlias>).isNotNull()`. `MethodRef` already crosses into the command layer on `SelectTerm.HelperCall`, so this is not a new dependency.

`PresenceProbeFragments.declare` switches on the arm: `Exists` keeps its hop-alias locals and `reachExists`; `NotNull` declares no locals. The `CASE WHEN <column leaves absent> AND OR(<terms>) THEN 1 END` shape, the never-empty `terms` invariant and the alias are unchanged.

### Shared wrappers

A nesting type shared by several `@table` parents gets one fetchers class, so `GraphitronSchemaValidator.comparePresenceColumns` requires every site to collapse over the same leaves: the same column names per column leaf, and the same probed fields. The rule it encodes is that the shared fetcher reads one thing per leaf, and a probe leaf satisfies it by field name, because the probe expression is minted per anchor (each anchor's own projection unit, `ProjectionCommands.mintNestedUnit`) and read back under one alias keyed on the field.

A computed leaf is in the same position as a probe, so it compares the same way: by the field's name, `presenceLeafNames` rendering it as `value of <field>` beside `probe of <field>`. That answers whether the same `MethodRef` is required: no comparison of the method is needed, because two sites that hold a field of one name already hold the same declaration. `compareNestedFieldsShape` rejects a class mismatch per field name before presence is compared, both sites read the one `@externalField` declaration on the shared type, and `reflectExternalField` has already checked the method against each anchor's table, which is what forces a shared type's helper to take `Table<?>` (`OccupantExtensions.addressId`). The per-anchor difference in the rendered expression is the point, not a conflict: each anchor's probe calls the method over its own row.

### Cost

R1026 decides presence over the whole subtree whatever the query selects, so the probe is projected whenever the object is selected, and each computed leaf is evaluated inside it. `navnAlleSprak { en }` evaluates `en` for its own value and up to four expressions in the probe, per row. The `CASE` guard skips them entirely when a column leaf already proves the object present; with no column leaves, as in sis, they run on every row. SQL does not promise that `OR` stops at the first true disjunct, so the bound is "up to N per row", not "one".

The plan accepts that cost rather than bounding it. It is no extra query or round trip; it is the price of the answer not depending on the selection, which the rule already pays for references with an `EXISTS` per probe; and the only way to avoid it is to let the selection decide again. The user docs say so, next to the advice they already give: declare the field non-null to always get an object, which also skips the probe.

## User documentation (first-client check)

`map-types-to-tables.adoc` § "When a single object resolves to `null`", the first paragraph becomes:

> A nullable field returning one object, on a `@table` type, on an object nested in one, or on `Query` returning a `@table` type, resolves to `null` when the object would hold nothing: every column it reads is `null`, every xref:../reference/directives/externalField.adoc[`@externalField`] returning a scalar or enum is `null`, every single xref:../reference/directives/reference.adoc[`@reference`] in it finds no value, and every list in it is empty. [rest unchanged]

The always-object paragraph drops `@externalField` from its list and gains one sentence after it:

> Declare the field non-null to always get an object. A type holding a list that takes arguments, a connection, an interface or union, an `@externalField` returning an object type, a `@service` field, a field reached through a database routine, or, in a multi-tenant build, a `@splitQuery` field read from another tenant's or the global database always resolves to an object, because [unchanged]. Deciding is not free: every `@externalField` in the object is evaluated in the check, whichever fields the query selects, so a non-null field is also the cheaper one when its expressions are costly.

`migrating-from-legacy.adoc`'s divergence list replaces "an `@externalField`" with "an `@externalField` returning an object type". `reference/directives/externalField.adoc` gains one line under its behaviour: a scalar `@externalField` counts toward whether the object holding it is `null`, linking the anchor above.

## Implementation

* `rewrite/model/Presence.java`: `PresenceLeaf.Computed`; the `ComputedField` arm in `SubtreePresence.over`; `NullContract.probed()` beside `probes()`; javadoc on the class, `PresenceLeaf`, `over` and `Reason.BlockedBy` naming a scalar `@externalField` as a leaf and an object-returning one as a blocker.
* `command/PresenceProbe.java`: `ProbeTerm` sealed into `Exists` and `NotNull`; the record javadoc's `EXISTS(<term>)` wording widened to "a term".
* `plan/PresenceProbes.java`: map `probed()` by exhaustive switch.
* `render/PresenceProbeFragments.java`: switch on the term arm.
* `rewrite/generators/FetcherEmitter.presenceCheck`: read the probe alias on `probed()`.
* `rewrite/GraphitronSchemaValidator.presenceLeafNames`: the `Computed` arm.
* The docs above.

No carrier record, renderer slot or fetcher shape changes.

## Tests

* `PresenceTest` (unit): a wrapper of computed fields only collapses with one `Computed` leaf each; a mix of a column and a computed field collapses with both; an object-returning `@externalField` blocks naming itself; `otherChild_blocksTheWrapperNamingThatChild` uses `computed("label")` as its blocker today and moves to a child that still blocks; a computed field in a reference probe's target type leaves the probe at existence alone; `probed()` holds the probe and computed leaves in leaf order and no column leaf.
* Pipeline tier: in `NestingFieldPipelineTest`, a nesting wrapper of computed fields renders a probe of `NotNull` terms only, and its fetcher reads the probe alias with no column checks; in `TableChildNullContractPipelineTest`, a `@table` child holding a column and a computed field carries both in its inline multiset and its batched rows method; in `RootNullContractPipelineTest`, a root single read of such a type carries the probe in its `NullCheck.Collapses`.
* Validation tier (`NestingFieldValidationTest`): a nesting type of `Table<?>` computed fields shared by two parents validates and collapses at both sites.
* Execution tier, on `pivot_nesting_host` (host 1 carries `nn` and `nb`, host 2 nothing, host 3 only `nb`), with a new `HostLabelsComputed` nesting type whose `nn` and `nb` are `@externalField`s over those columns (helpers on a new `PivotNestingHostExtensions` in `graphitron-sakila-service`), on `PivotNestingHost.labelsComputed`, plus a `HostLabelsComputedRow @table(name: "pivot_nesting_host")` twin reached as a same-row child. In `NestingNullContractExecutionTest`: host 2 resolves to `null`, host 3 to `{nn: null, nb: "nb-only"}`; a selection of `{ nn }` alone answers `null` on host 2 and an object on host 3, agreeing with the full selection; the same-row twin agrees. A wrapper of one column and one computed field (`nn` a column, `nb` computed) agrees with `labels` on all three hosts.
* `OccupantLocation` (shared by `Customer` and `Store`) held `occupantAddressId` and was always an object; it now collapses. Every sakila customer and store has an address, so no answer changes; the existing execution tests over it are the check.

## Other solutions we've considered

* **Project each computed value under a reserved alias and test it in Java.** One more column per leaf in every carrier, a new reserved-alias family, and a carrier slot to hold the methods. It evaluates exactly the same expressions as the probe does and buys nothing the probe disjunct does not.
* **Reuse the selected value when the field is selected.** Saves one evaluation for a selected leaf, but the selected value sits under the client's result key, which differs per occurrence, and the probe must still cover the unselected ones. Not worth the coupling between the null check and the selection.
* **Accept the always-object answer.** sis does so until this lands, but it is a Graphitron 9 divergence with no reason behind it: the rationale the docs give (code outside the table's rows decides) is true of `@service` and false of a SQL expression over the row.

## Provenance

Requested 2026-10-28 by the sis Graphitron 10 port session, which tracks it as C23 in `GRAPHITRON10_STATUS.md`. It is not a port blocker: until this lands, sis accepts and announces the object-with-nulls answer.
