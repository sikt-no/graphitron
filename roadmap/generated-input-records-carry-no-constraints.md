---
id: R747
title: "Generated input records carry no constraint annotations, so the VALIDATION handler can never fire"
status: Backlog
bucket: architecture
priority: 4
theme: error-channel
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# Generated input records carry no constraint annotations, so the VALIDATION handler can never fire

## Problem

An author writes `{handler: VALIDATION}` on an `@error` type and gets the whole pipeline: the
generated wrapper acquires a `jakarta.validation.Validator`, walks every `@service` argument
through it, turns each `ConstraintViolation` into a `GraphQLError` through the generated
`ConstraintViolations` helper, and short-circuits into the payload's errors slot. All of it
compiles, all of it runs, and it can never produce a violation.

The reason is that nothing puts a constraint on the object being validated. For an input-typed SDL
argument, `TypeFetcherGenerator.resolveInputArgClass` resolves the *graphitron-emitted* input record
and the pre-step validates that (`<Input>.fromMap(rawMap)`, then
`validator.validate(typedInstance)`); for a scalar or enum argument the pre-step validates the raw
value. Graphitron emits no `jakarta.validation.constraints` annotations onto the generated record,
and a boxed `Integer` carries none either, so the walk is empty by construction. The one remaining
route a consumer might take, a programmatic `ConstraintMapping` on their own `ValidatorFactory`, is
closed too: `GraphitronContext` is a sealed interface and `getValidator` is a `default` method, so
there is nothing to override.

Two things make this worse than a plain missing feature. It is silent: a schema declaring
`{handler: VALIDATION}` builds clean, runs clean, and the author discovers the handler never fires
only by never seeing an error. And it is undiscoverable from the docs, which describe the VALIDATION
path as working (`docs/manual/how-to/error-channel.adoc`'s dispatch section, and the
`@error` reference page's `handler` row) without stating that no constraint reaches the validator.

## Why this is filed rather than folded into the item that found it

Found while writing the execution-tier coverage for the `description:` override work, which needed a
VALIDATION fixture asserting that a violation's interpolated message arrives in the errors slot.
That assertion is the one thing that item could not construct, for the reason above. What landed
instead is `Query.filmLookupValidated` in the sakila example: the suite's only `{handler: VALIDATION}`
channel, which does compile and execute the pre-step, the `ConstraintViolations` helper and the
emitted `GraphQLError` arm of `<ErrorType>Fetchers.message`, and whose query tests assert the channel
still dispatches and still returns its happy path. That fixture is the natural client for whatever
this item lands: adding one constraint should turn it into the missing assertion.

## Shape of the work, not yet a plan

Three routes, in rough order of how much they ask of the author:

1. *Carry the constraints from SDL onto the generated record.* Some SDL-side surface (a directive, or
   a reuse of an existing one) names the constraint and graphitron emits the matching annotation onto
   the input record's component. Closes the loop with no consumer Java, at the cost of a new
   authoring surface and a mapping from SDL to a constraint-annotation vocabulary.
2. *Validate the consumer's own bound class instead of the generated record.* Where a `@service`
   parameter binds a consumer-authored bean or record (the `ValueShape.JavaBeanInput` /
   `RecordInput` arms), the consumer already annotates that class; the pre-step could walk the bound
   instance rather than the generated one. No new authoring surface, but it only covers arguments
   that bind a consumer class.
3. *Open a validator hook.* Let the consumer supply the `ValidatorFactory`, which reopens the
   programmatic-`ConstraintMapping` route. Smallest change, and the one that pushes the most work
   onto the author.

Route 2 is the most promising first cut: it needs no new vocabulary and it makes the annotations
authors have already written load-bearing. Whichever lands, the honest interim move if none does is
to say in the manual that the VALIDATION handler validates a record carrying no constraints, so the
docs stop implying otherwise.
