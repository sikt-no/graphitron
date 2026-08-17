---
id: R694
title: "Key the @service input-bean helper dedup on binding shape, the member-axis twin of R437"
status: Backlog
bucket: architecture
priority: 3
theme: service
depends-on: []
created: 2026-08-17
last-updated: 2026-08-17
---

# Key the @service input-bean helper dedup on binding shape, the member-axis twin of R437

The `create<Bean>` / `create<Bean>List` instantiation helpers emitted on a `<Type>Fetchers`
class are deduplicated by bean `ClassName` alone: `InputBeanInstantiationEmitter.collectTransitively`
and `TypeFetcherGenerator.registerBeanHelper` both `putIfAbsent(beanClass, ...)`, and the
javadoc on the former states the assumption outright, that "two top-level `InputBean`s
carrying the same bean class are assumed structurally equal (the resolver maps the same Java
class to the same SDL input-object type, by construction)". Nothing enforces that. One
consumer-authored bean class can back two different SDL input types at two `@service` fields
on one type, and if those input types bind their fields differently (divergent
`@field(name:)` values, or a field present in one and absent in the other) the second field's
call site routes to the first-seen helper and reads the wrong `Map` keys, populating members
as null with nothing in the build saying so.

This is the member-axis twin of the R437 correctness bug, which was a real production defect
on the jOOQ-record axis (`fs-plattform` silently wrote `DATO_FRA` as `1900-01-01`) and was
fixed there by re-keying dedup, naming, and call-site routing on the full binding *shape*
through a `JooqRecordHelperNames` resolver. The member axis never got the same treatment.
`FetchersHelperNames` already layers cross-class stem disambiguation under the jOOQ arm's
within-class shape contention, so the composition point for a member-axis shape arm exists;
the likely shape of the fix is a sibling resolver keyed on `InputBean`'s own structural
`equals`, with the uncontended case keeping today's bare `create<Bean>` name so no emitted
output churns.

Filed out of R693's spec pass, which touches this neighbourhood: flattening a nested grouping
input onto a bean adds access paths to the set of things two same-class beans can disagree
about. R693 neither fixes nor worsens the failure class and is independent of this item in
both directions.
