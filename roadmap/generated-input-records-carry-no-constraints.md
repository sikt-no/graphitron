---
id: R747
title: "Generated input records carry no constraint annotations, so the VALIDATION handler can never fire"
status: Spec
bucket: architecture
priority: 4
theme: error-channel
depends-on: []
created: 2026-08-20
last-updated: 2026-10-29
---

# Generated input records carry no constraint annotations, so the VALIDATION handler can never fire

## Goal

An author who puts Jakarta Bean Validation constraints on the input class their `@service` method
takes, and gives the field's error channel a `{handler: VALIDATION}` member, gets one typed error per
violated constraint in the payload's `errors` list, with the service never called. Today the same
schema and the same annotations produce nothing: graphitron validates a copy of the argument it
generated itself, which carries no constraints, so the handler cannot fire. When this lands,
graphitron validates the very instance it is about to hand the service, so the annotations the
author already writes on that class are the ones that count. (An *error channel* is the set of
`@error` types a payload's `errors` field can hold; a `{handler: VALIDATION}` member is the one that
receives validation failures.)

The sakila example already has the shape. `Mutation.countFilmDrafts` takes a list of
`FilmDraftInput`, binds each element to the consumer record `FilmDraft`, and shares the
VALIDATION-handled `FilmWriteInvalid` with the DML mutations:

```graphql
input FilmDraftInput {
    title: String!
}

type FilmWriteInvalid @error(handlers: [{handler: VALIDATION}]) {
    path: [String!]!
    message: String!
}

union FilmDraftError = FilmWriteInvalid

type FilmDraftsCountedPayload {
    count: Int
    errors: [FilmDraftError]
}

type Mutation {
    countFilmDrafts(in: [FilmDraftInput!]!): FilmDraftsCountedPayload
        @service(service: {
            className: "no.sikt.graphitron.rewrite.test.services.FilmDraftService",
            method: "count", argMapping: "drafts: in"
        })
}
```

The minimal pair is one annotation on the consumer's class:

```java
public record FilmDraft(String title) {}              // before: nothing to validate
public record FilmDraft(@NotBlank String title) {}    // after: a blank title is a client error
```

With the annotation, `countFilmDrafts(in: [{title: "DRAFT ONE"}, {title: ""}]) { count errors {
__typename ... on FilmWriteInvalid { path message } } }` answers with `count: null` and one error,
`FilmWriteInvalid`, `path: ["countFilmDrafts", "in", "1", "title"]`, `message: "must not be blank"`;
`FilmDraftService.count` is not called. Before this item the same request returns `count: 2` and no
errors, whatever the annotation says.

Constraints on scalar arguments (`filmLookupValidated(id: Int!)` wanting `@Positive`) stay out of
reach: Jakarta places those on method parameters, method validation does not apply to static
methods, and the static call shape is the common one. The manual says so and names the remedy (take
the argument as an input bound to a class).

## User documentation (first-client check)

Replaces the `VALIDATION` paragraph of `docs/manual/how-to/error-channel.adoc` (the one starting
"`VALIDATION` is special-cased"); the `@error` reference's `handler` row and its `VALIDATION`
section point here.

> `VALIDATION` is special-cased. There is at most one `VALIDATION` handler per channel (`Rule 7`).
> It turns on Bean Validation for every `@service` field whose payload carries the channel: before
> the service method runs, Graphitron validates each argument value that a method parameter
> receives as a class you wrote (a Java record or a bean bound to an SDL `input`), using the
> `jakarta.validation` constraints declared on that class. A list parameter is validated element by
> element. Every violation becomes one entry in the errors list, typed as the `VALIDATION` member,
> with the constraint's own interpolated message, and the service is not called.
>
> ```java
> public record FilmDraft(@NotBlank String title) {}
> ```
>
> A violation's `path` names the SDL input fields, not the Java members: the field's path, the
> argument, the element index for a list, then the input fields down to the violated value
> (`["countFilmDrafts", "in", "1", "title"]`). Where an input field flattens onto the class from a
> grouping input, the path keeps the grouping field. A class-level constraint ends the path at the
> argument (or element). Nested classes are validated only where you ask for it with `@Valid`, as
> Bean Validation always does.
>
> Only values bound to a class are validated. A scalar or enum argument, an input read field by
> field into scalar parameters, and a jOOQ record parameter are passed through unchecked; to
> constrain one, take the argument as an input bound to a class. (A `@mutation` field's character
> length check is separate and described below.)

The `Coming from Graphitron 9` line gains: "Put the constraints Graphitron 9 read from jOOQ's
generated annotations on the class your `@service` method takes."

## Implementation

Two phases, each its own commit: phase 1 is the observable change, phase 2 deletes what phase 1
leaves without a reader.

### Phase 1: validate the bound instance

*Model.* `ServiceMethodCall.validatedArguments()` and the `ServiceMethodCall.ValidatedArgument`
arms (`Raw`, `InputObject`, `InputObjectList`, `InputObjectNestedList`) are replaced by
`validationTargets()`: one `ServiceMethodCall.ValidationTarget` per method-round `MappingEntry.FromArg`
whose `ValueShape`, under any number of `ValueShape.ListOf` wrappers, is a `ValueShape.RecordInput`
or `ValueShape.JavaBeanInput`. A target carries the call-site local it validates
(`MappingEntry.FromArg.javaName()`), the SDL path the parameter binds (argument name plus any
sub-path, read from the parameter's `ParamSource.Arg` in `ServiceMethodCallWalker`, which is where
`validatedArguments` is computed today), and the bound `ValueShape` itself, which carries the list
depth and the `ValueShape.FieldBinding` tree the path translation below reads. Parameters binding
scalars, enums or `ValueShape.JooqRecordInput` yield no target. Two parameters binding one argument
both get a target only if both bind a class; that is the honest reading, since each is a distinct
instance the service receives.

*Emission.* `TypeFetcherGenerator.validatorPreStep` moves from ahead of the service call's
statements to between `ServiceMethodCallEmitter`'s per-entry var-decls and its `result`
assignment, so it validates the locals the call is about to pass. `ServiceMethodCallEmitter` keeps
owning statement order: it gains the seam (a pre-call block parameter, or returning the bindings
and the call as two lists; implementer's choice), and the `buildServiceFetcherCommon` call
site, the one that emits the pre-step today, takes it; the polymorphic emitter's calls emit no
pre-step and stay as they are. The block stays inside the fetcher's `try`, as today. Per target: skip a
null value; walk each `ListOf` level with a loop, skipping null elements and appending the index to
the argument path; call `validator.validate(element)`. No `env.getArgument`, no `fromMap`. A field
with no targets emits no pre-step at all, so no `getValidator` call either.

*Path translation.* A Jakarta property path names Java members; the error `path` must name SDL
input fields. `ConstraintViolations.toGraphQLError` gains a translation argument built at codegen
from the target's `FieldBinding` tree: a property node whose name matches a binding's Java member
(decapitalised setter suffix for `JavaBeanInput`) contributes that binding's whole `accessPath`
(more than one segment where the field flattened from a grouping input) and descends into the
binding's shape, through `ListOf` for container element nodes, whose index or key passes through.
A node the tree does not know (reached through the consumer's own `@Valid` on a member graphitron
did not bind) keeps its Java name, as does everything below it. Emit the translation once per
distinct bound class, as a static constant on the `*Fetchers` class or in a generated helper,
deduplicated on `ClassName` as `collectBeanHelpersFromCarrier` already does for the instantiation
helpers. `sizeExceeded` and the shared `validationError` builder are untouched, so the column-length
check keeps its wire shape.

*Classification.* The walk recurses through any number of `ListOf` levels, so
`FieldBuilder.unvalidatableArgumentReason`, its `GraphitronSchemaValidator` mirror and the
list-of-lists rejection retire.

*Fixtures and prose.* `FilmDraft.title` gains `@NotBlank`; `graphitron-sakila-service` gains
`jakarta.validation-api` (managed in the root pom; `provided` scope suffices, since the example
module already supplies the runtime). `FilmDraftService`, `FilmValidatedLookupService`, the
`schema.graphqls` comments on `countFilmDrafts`, `filmLookupValidated` and `FilmLookupValidationFailed`,
and the `validatorPreStep` and `ConstraintViolationsClassGenerator` javadoc are rewritten to the new
fact: `filmLookupValidated` now pins that a VALIDATION channel whose field binds no class emits no
pre-step and still dispatches. Docs: the draft above into `error-channel.adoc`, the `handler` row
and `VALIDATION` section of `docs/manual/reference/directives/error.adoc`, and any
`docs/architecture/` page describing the pre-step's target (grep `fromMap`, `validatorPreStep`).

### Phase 2: retire the generated input classes

After phase 1 nothing reads the `<outputPackage>.inputs` classes: `HasInputRecordShape`'s own
javadoc names the generator and the validator pre-step as its only two consumers. Delete
`InputRecordGenerator`, `InputRecordPlanner`, `TypeUnitCommand.InputRecordUnit`,
`GeneratedUnits.inputRecord`, the `GraphQLRewriteGenerator` and `PlanCompileGraph` wiring,
`HasInputRecordShape`, `InputRecordShape` and the `recordShape` component on the four
`GraphitronType.InputType` records, with their tests (`InputRecordGeneratorTest`,
`InputRecordRenderTestSupport`, and the input-record rows of `TypeUnitMembershipTest`,
`FetcherEdgeRelationTest`, `FetcherPipelineTest`). The view `graphitron_argument_reachable_input`
in `graphitron-model.sql`, with its `meta_relation` row, goes too if `InputRecordPlanner` remains its
only reader (it is at the time of writing).

One behaviour hangs on the shape and must be decided, not dropped by accident:
`TypeBuilder.buildInputRecordShape` returns `null` for an input whose field type does not resolve,
which routes the type to `UnclassifiedType`. Check whether another classifier step rejects that
input on its own. If one does, the null arm goes with the shape. If this is the only rejection, it
stays as a plain classification check over the input's field types, no longer building a shape,
and `InputTypeValidationTest` pins it either way.

## Tests

- Execution tier (`GraphQLQueryTest`), the item's acceptance evidence:
  `countFilmDrafts` with one blank title among two elements returns `count: null` and exactly one
  `FilmWriteInvalid` with `path ["countFilmDrafts", "in", "1", "title"]` and the interpolated
  `@NotBlank` message, and the service is not called; with both titles non-blank the existing
  `countFilmDrafts_*` cases hold unchanged. `filmLookupValidated_*` hold unchanged.
- Execution tier, path translation: one new `@service` fixture whose input binds a consumer class
  through a grouping input that flattens onto it, with a constraint on the flattened member and a
  nested `@Valid` member; asserts the SDL path (grouping field kept) for both. A class-level
  constraint on the same class asserts the path ends at the argument.
- Pipeline tier: the generated wrapper for `countFilmDrafts` validates the bound local after the
  bindings and before the call, and the wrapper for `filmLookupValidated` contains no `getValidator`
  call.
- Classification: a list-of-lists bound-class argument under a VALIDATION channel now classifies,
  replacing the rejection case for `unvalidatableArgumentReason`.
- Phase 2: no file is emitted under `<outputPackage>.inputs` (assert on the emitted unit set), and
  the `UnclassifiedType` decision above is pinned in `InputTypeValidationTest`.

## Retired vocabulary

`ValidatedArgument`, `validatedArguments`, `InputObjectNestedList`, `unvalidatableArgumentReason`
(phase 1); `InputRecordGenerator`, `InputRecordPlanner`, `InputRecordUnit`, `HasInputRecordShape`,
`InputRecordShape`, `recordShape`, `fromMap`, `graphitron_argument_reachable_input`, the
`<outputPackage>.inputs` package (phase 2). Prose to sweep: "the generated input record",
"validates the graphitron-emitted", "no constraint annotations", "the walk produces zero violations".

## Other solutions we've considered

- *Constraints declared in SDL and emitted onto the generated record.* Closes the loop with no
  consumer Java, and would reach scalar arguments. It costs a new directive and a mapping from SDL
  to the constraint vocabulary, and duplicates what the class the service takes can already say.
  Nothing here forecloses it later; it would need the generated classes back, which phase 2 deletes
  as having no reader today.
- *Jakarta method validation (`ExecutableValidator.validateParameters`).* Would reach scalar
  parameters (`lookup(@Positive Integer id)`), but the specification excludes static methods and
  most `@service` methods are static; it would split VALIDATION's behaviour by call shape.
- *A consumer-supplied `ValidatorFactory`.* Reopens programmatic `ConstraintMapping` against the
  generated classes; the smallest change and the one that pushes most onto the author, and it keeps
  graphitron validating an object the service never sees.
- *Walking jOOQ record parameters too.* jOOQ can generate constraint annotations, but the
  `@mutation` column-length check already covers the length case, and walking both would report one
  overlong value twice.
- *Warning at build time on a VALIDATION channel whose field validates nothing.* The sakila schema
  shows why not: a VALIDATION type shared by DML mutations and a `@service` mutation is a
  deliberate shape, and the warning would fire on every `@service` field in that union with no way
  to silence it but splitting the union. Omitting the pre-step where there is no target, plus the
  manual's rule, carries the signal instead.

## Provenance

Found while writing execution-tier coverage for the `description:` override work, which wanted a
VALIDATION fixture asserting that a violation's interpolated message reaches the errors slot and
could not construct one. `Query.filmLookupValidated` and `Mutation.countFilmDrafts` landed then as
the VALIDATION channels that compile and run with nothing to report.
