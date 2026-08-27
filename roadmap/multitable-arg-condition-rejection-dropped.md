---
id: R869
title: "An argument-level @condition that fails to resolve is dropped silently on a multitable field"
status: Backlog
bucket: architecture
priority: 3
theme: interface-union
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# An argument-level @condition that fails to resolve is dropped silently on a multitable field

An argument-level `@condition` on a field returning a multitable interface or union, whose reference does not resolve, produces no diagnostic and no unclassified field. The directive is silently ignored and the field classifies green with the predicate missing, so a query that the author believes is filtered runs unfiltered.

Reproduced against a two-participant union with `method:` naming a method that does not exist on the class:

```graphql
type Customer @table(name: "customer") { firstName: String @field(name: "first_name") }
type Staff @table(name: "staff") { firstName: String @field(name: "first_name") }
union Occupant = Customer | Staff
type Query {
    occupants(firstName: String @field(name: "first_name") @condition(condition: {
        className: "...TestConditionStub", method: "noSuchMethodAtAll"})): [Occupant!]!
}
```

The field classifies as a `QueryUnionField` and the schema carries zero diagnostics. The same unresolvable reference at the field-level and input-field-level coordinates on the same union rejects normally, so this is specific to the argument coordinate on the per-participant lowering path.

Where it goes wrong is visible without a fix being obvious. `FieldBuilder.classifyArgument` handles `ConditionResolver.ArgConditionResult.Rejected` by adding the message to its local `errors` accumulator and yielding an empty condition, which is the right shape at a single-table coordinate. The per-participant path reaches that classification through `lowerParticipantFilters`, and what it inspects afterwards is the returned `TableFieldComponents` and the participant's own rejection, not that accumulator, so a rejection recorded there has no reader.

Discovered while implementing binding-shape admission for `@condition` overload sets. Filed rather than fixed there: the admission item is about which declarations one name may denote, and this is about where an argument-coordinate rejection is read on a path that lowers once per participant. A fix needs to decide whether the accumulator is threaded out of argument classification or whether the participant loop stops reading a lossy return value, which is a question about that path rather than about `@condition`.
