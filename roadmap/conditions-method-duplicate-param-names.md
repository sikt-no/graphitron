---
id: R475
title: "Generated <Type>Conditions methods break on same-named filter fields across sibling arguments"
status: Backlog
bucket: generator
depends-on: []
created: 2026-07-14
last-updated: 2026-07-14
---

# Generated <Type>Conditions methods break on same-named filter fields across sibling arguments

A query field with two input-object filter arguments whose input types carry a same-named
field (e.g. `films(filter: FilmFilter, extra: FilmExtra)` where both inputs declare `length`)
emits an entity-scoped `<Type>Conditions.<field>Condition` method with one Java parameter per
filter field, keyed by the bare field name; the duplicate name makes the generated method fail
the consumer's javac ("variable length is already defined"). Discovered during the R13 rework
(the review's finding-2 fixture could not compile): the defect predates and is independent of
facets, which now reject the facet-name half of the collision at build time
(`GraphitronSchemaBuilder.rejectFacetMisuse`), while the general non-facet collision still
breaks. Either qualify the generated parameter names by argument (e.g. `filter_length`,
threading the qualified name through `BodyParam` / `CallParam`), or reject the collision at
classify time with a named diagnostic. Whichever way, the R13 facet-name rejection can then
narrow or fold into the general rule.
