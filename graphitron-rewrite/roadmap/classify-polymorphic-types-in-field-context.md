---
id: R278
title: "Classify polymorphic types (interface/union) and their participants in field context"
status: Backlog
bucket: structural-refactor
priority: 6
theme: structural-refactor
depends-on: [R276]
created: 2026-06-04
last-updated: 2026-06-04
---

# Classify polymorphic types (interface/union) and their participants in field context

R276 made type classification reflection-only and left directiveless objects unclassified at the
type pass (`TypeBuilder.classifyType` returns `null`; a `NestingType` is assigned post-field-pass
from the `NestingField` that embeds it). Working through R276 surfaced that the participant /
polymorphic-type model classifies types *in isolation* rather than *in the context of the field that
returns them*, and that this is the root of several smells. R276 kept the pre-existing behaviour
(adapted only for directiveless objects now being `null`); this item is the proper cleanup.

## What is wrong today

1. **Participant role is derived from the member type's standalone classification, not from the
   returning field / population strategy.** `TypeBuilder.buildParticipantList` maps each interface
   implementor / union member to a `ParticipantRef` purely from whether the member carries `@table`.
   Whether a non-`@table` member is *valid* actually depends on how the polymorphic type is
   populated:
   - generated SQL polymorphism (members read off a `__typename` column from a multi-table query):
     every member must be table-bound;
   - service / reflection population: members are dispatched by runtime source class, so a
     non-`@table` member is fine.
   The population strategy is a property of the *field* returning the type, not of the type alone.

2. **`ParticipantRef` has no `Error` permit.** `permits TableBound, Unbound`. `@error` members
   (e.g. an `@error`-only union) are forced into `Unbound`, overloading it with two unrelated
   meanings (`@error` member vs directiveless plain-interface implementor). `Unbound` currently only
   means "member has no `@table` directive", which is a structural fact, not "no SQL branch".

3. **A union whose members are all `@error` is conceptually an `ErrorType`.** A type with `@error`
   is an `ErrorType`; a union of `ErrorType` is also an `ErrorType`. Today it is classified as a
   `UnionType` with `@error` `Unbound` participants, and the emitter
   (`GraphitronSchemaClassGenerator`, the `isErrorUnion` fork + the source-class `TypeResolver`)
   special-cases it downstream. It would be cleaner to classify the union as an `ErrorType`
   (aggregating the members' handlers, keyed to each member type for the `TypeResolver` dispatch)
   and let the emitter read it from the `ErrorType`.

4. **Service / reflection-populated interface/union types are not classified at all.**
   `TypeBuilder.classifyType` never consults `bindings.resolveResult` for interfaces/unions (unlike
   objects), so a polymorphic type returned by an `@service` is just a plain `InterfaceType` /
   `UnionType`. The emitter has only three `TypeResolver` strategies (TableInterfaceType discriminator
   column, plain InterfaceType/UnionType `__typename` column from a SQL result, `@error`-only
   source-class). There is **no** strategy for a service/reflection-populated non-error polymorphic
   type, whose runtime objects carry no `__typename` column. This looks like an unhandled gap.

## Direction

- Classify polymorphic types and their participants **in the context of the returning field**:
  the field carries the population strategy (table-polymorphic vs service/reflection), which decides
  whether non-`@table` members are admissible and how the `TypeResolver` dispatches.
- Give `@error` its own representation: a union of all-`@error` members classifies as `ErrorType`;
  add a dedicated participant kind for `@error` rather than overloading `Unbound`.
- Specify and implement (or explicitly reject) service/reflection-populated polymorphic types.
- Retire the overloaded `ParticipantRef.Unbound` and the `allowNonTableMembers` flag on
  `buildParticipantList` once the above lands.

## R276 interim state (to undo here)

`TypeBuilder.buildParticipantList` keeps the pre-R276 behaviour: `@table` member -> `TableBound`;
non-`@table` member -> `Unbound` when `allowNonTableMembers` (plain interface) else an error; an
`@error` member rides the second `Unbound` arm. The `R278` markers in `TypeBuilder` and
`ParticipantRef` point here.
