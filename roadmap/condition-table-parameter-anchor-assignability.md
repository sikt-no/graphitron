---
id: R647
title: "Enforce @condition table-parameter assignability against the anchor table"
status: Backlog
bucket: architecture
priority: 4
theme: codegen-correctness
depends-on: [condition-method-overload-selection]
created: 2026-08-13
last-updated: 2026-08-19
---

# Enforce @condition table-parameter assignability against the anchor table

`ServiceCatalog.reflectTableMethod` claims the reserved `Table<?>` slot with
`org.jooq.Table.class.isAssignableFrom(p.getType())`, which admits *any* jOOQ table and is never
compared against the table the emit site will actually hand it. The emitted condition call passes a
concretely typed local (`InputFieldConditionFixtures.addressDistrictAlberta(table_fkt0_0, addressId)`
in the generated `QueryConditions`), so a `@condition` naming a helper typed on the wrong generated
table classifies clean and produces a javac error inside generated sources with no line back to the
SDL. Real fixtures already use both widened (`Condition c(Table<?> table, ...)`) and concrete
(`Condition c(Address address, ...)`, `Condition c(Customer table, ...)`) parameter types, so both
forms have to keep working.

This is the `@condition` analogue of the `@externalField` gap covered by
`roadmap/externalfield-parent-table-assignability.md`, and it is deliberately not folded into that
item: the invariant is harder to state here. A `@condition` attaches at several coordinates
(field-level, argument-level, through a `@reference` path), the reference-path form takes *two*
table parameters (`ReferencePathConditionFixtures.customerToAddress(Table<?> customerTable, Table<?> addressTable)`),
and the table a given slot receives depends on which arm the emit site takes. So "the anchor table"
needs defining per arm, and per slot within the two-table arm, before a check can exist. The
`@externalField` item introduces the comparison shape (erased assignability against the class the
emitted signature is rendered from, plus a parameterised `Table<R>` layer against the record type);
this item's work is deciding what each slot's expected table is, not inventing the comparison.

The anchor definition is now fixed by the R675 spec (`roadmap/condition-method-overload-selection.md`,
"Anchor definition handed to R647"): per emit-site arm and per slot, with the multitable arm anchoring
each participant's table per branch. Under R675's overload admission, the check here becomes per-anchor
applicability across the admitted declaration set (at least one declaration whose table slot accepts
that anchor), with most-specific selection left to javac.
