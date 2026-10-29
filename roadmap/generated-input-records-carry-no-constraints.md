---
id: R747
title: "Generated input records carry no constraint annotations, so the VALIDATION handler can never fire"
status: In Progress
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
takes, and gives the field's error channel (the set of `@error` types its payload's `errors` field
can hold) a `{handler: VALIDATION}` member, gets one typed error per violated constraint in that
`errors` list, with the service never called. Today the same schema and the same annotations produce
nothing: graphitron validates a copy of the argument it generated itself, which carries no
constraints, so the handler cannot fire. When this lands, graphitron validates the very instance it
is about to hand the service, so the annotations the author already writes on that class are the
ones that count.

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
> Only values bound to a class you wrote are validated. A scalar or enum argument, an input read
> field by field into scalar parameters, an input passed through as a `Map`, and a jOOQ record
> parameter are passed through unchecked; to constrain one, take the argument as an input bound to
> a record or bean. Validation runs after Graphitron has resolved the request's tenant and decoded
> any node ids in the arguments, so a client error from either of those is reported instead of the
> violations. (A `@mutation` field's character length check is separate and described below.)

The `Coming from Graphitron 9` line keeps its last sentence true: Graphitron 10 does not read
jOOQ's `<validationAnnotations>`, because it does not validate jOOQ record parameters.


## Implementation

*Model: where the instance comes from.* A `@service` parameter bound to a consumer class is a
`MappingEntry.FromArg` whose `ValueShape` is a `ValueShape.RecordInput` or
`ValueShape.JavaBeanInput`, directly or as the element of one `ValueShape.ListOf` (a bean parameter
binds at most one list: `InputBeanResolver.peelSdlListNonNull` peels one level and
`ServiceMethodCallWalker.isListType` matches only `List<Bean>`). Two facts are missing for
validating it:

- *The argument the class binds.* A bean always binds a whole top-level argument
  (`ServiceMethodCallEmitter.compositeHelperCall` emits `createX(env.getArgument(outerArg))`), but
  the composite carries no path, so the emitter recovers the argument name by walking the bindings
  (`ServiceMethodCallEmitter.outerArgOf`), which yields `""` for a class with no bound fields. The
  composite gains the argument's name as a component, set by the walker that builds it, as
  `ValueShape.JooqRecordInput` already carries its `sdlPath`; `outerArgOf` retires, and the
  `ValueShape` javadoc's "paths live on the leaves" sentence is corrected.
- *Which parameters are targets.* A sealed sub-interface `ValueShape.BoundClass` over `RecordInput`
  and `JavaBeanInput` names the class-bound composite once; the twin arms in `listExpression`,
  `compositeHelperCall` and `TypeFetcherGenerator.collectFromValueShape` collapse onto it. The
  targets are then a derivation over `methodArgs`, not a stored copy:
  `ServiceMethodCall.validationTargets()` is a `default` method returning one
  `ServiceMethodCall.ValidationTarget` per `FromArg` whose shape is a `BoundClass` (`Single`) or a
  `ListOf` of one (`PerElement`), carrying the entry's call-site local
  (`MappingEntry.FromArg.javaName()`) and the `BoundClass`. Parameters binding scalars, enums,
  `Map` pass-throughs or `ValueShape.JooqRecordInput` yield no target.

The stored `validatedArguments` component, its walker computation
(`ServiceMethodCallWalker.validatedArguments`) and the `ServiceMethodCall.ValidatedArgument` arms
retire.

*Emission: order.* The pre-step moves from the first statement inside the fetcher's `try` to just
before the `result` assignment in `ServiceMethodCallEmitter`'s output, so it validates the locals
the call is about to pass and nothing is built twice. `ServiceMethodCallEmitter` keeps owning
statement order and gains the seam (a pre-call block parameter); only the
`buildServiceFetcherCommon` call site, the one that emits the pre-step today, passes one. The
reorder is a behaviour change and is accepted: validation now runs after the tenant hand-down
(divination and the empty-answer short-circuit), the `DSLContext` prelude and node-id decoding, so a
tenant refusal or an undecodable id is reported instead of the violations, and a request whose
slots name no tenant returns the field's empty answer without validating. Validating first would
mean materialising the beans ahead of the tenant hand-down, which a divining field reads its
tenant from; the order the call already has is the simpler truth. The comment above the pre-step's
call site in `buildServiceFetcherCommon`, which promises the opposite, is rewritten.

*Emission: the walk.* Per target: skip a null local; for `PerElement`, loop over the list,
skipping null elements and appending the index to the argument path; call
`validator.validate(value)`. No `env.getArgument`, no `fromMap`. A field with no targets emits no
pre-step and no `getValidator` call.

*Path translation.* A Jakarta property path names Java members; the error `path` must name SDL
input fields. `ConstraintViolations.toGraphQLError` takes a translation built at codegen as a pure
projection of the target's `FieldBinding` tree, render-side, with no model slot: a property node
whose name equals a binding's `ValueShape.FieldBinding.javaFieldName` (already the decapitalised
property name for a bean, from `InputBeanResolver.indexJavaBeanSetters`) contributes that binding's
whole `accessPath`, more than one segment where the field flattened from a grouping input, and
descends into the binding's shape. An index or key is read from whichever node carries it: for a
cascaded `List<@Valid Bean>` member Bean Validation puts it on the next property node
(`isInIterable()`), not on a container-element node, and today's `toGraphQLError` drops it there,
which this fixes. A node the tree does not know (reached through the consumer's own `@Valid` on a
member graphitron did not bind) keeps its Java name, as does everything below it. The translation is
emitted once per distinct binding tree (record equality on the `BoundClass`), not once per
`ClassName`: one class bound from two SDL inputs with different grouping layouts needs two.
`sizeExceeded` and the shared `validationError` builder are untouched, so the column-length check
keeps its wire shape.

*Classification.* `FieldBuilder.unvalidatableArgumentReason`, its `GraphitronSchemaValidator`
mirror and the list-of-lists rejection retire. The reason is not a deeper walk: `[[Input]]` can only
bind as a `Map` pass-through (`TestServiceStub.runSakWithInputGrid`), which yields no target, so
such a field classifies under a VALIDATION channel and emits no pre-step.

*Fixtures and prose.* `FilmDraft.title` gains `@NotBlank`; `graphitron-sakila-service` gains
`jakarta.validation-api` (managed in the root pom; `provided` scope, since the example module
supplies the runtime). `FilmDraftService`, `FilmValidatedLookupService`, the `schema.graphqls`
comments on `countFilmDrafts`, `filmLookupValidated` and `FilmLookupValidationFailed`, and the
`validatorPreStep` and `ConstraintViolationsClassGenerator` javadoc are rewritten to the new fact;
`filmLookupValidated` now pins that a VALIDATION channel whose field binds no class still
dispatches. Docs: the draft above into `error-channel.adoc`, the `handler` row and `VALIDATION`
section of `docs/manual/reference/directives/error.adoc`, and any `docs/architecture/` page
describing the pre-step's target (grep `fromMap`, `validatorPreStep`).

## Neighbouring items

After this item nothing reads the generated `<outputPackage>.inputs` classes: the pre-step was
their only reader (by grep; `HasInputRecordShape`'s javadoc census of "two consumers" is wrong,
since `validatorPreStep` builds the class name itself rather than reading `recordShape()`). This
item does not delete them, because three open items are designed on them or on the reach fact
behind them, and their fate is one decision that belongs in one place:

- R1011 (`input-classes-emitted-without-a-reader`, Backlog) is that place. Its goal, emit an input
  class only where a validated field reads one, becomes "emit none" once this lands, and it is
  where the retirement inventory (`InputRecordGenerator`, `InputRecordPlanner`,
  `HasInputRecordShape`, `InputRecordShape`, the `TypeUnitRelation` and `EmitPlan` wiring, the
  `ModelEmitVocabularyGuardTest` exemption) belongs.
- R92 (`catalog-check-constraint-validation`, Spec) attaches CHECK-derived constraints through a
  `ConstraintMapping` on the `.inputs` classes and on jOOQ records, and states that the pre-step
  validates the `.inputs` instance. After this item neither is validated: its input-side mapping
  has to target the class the service binds, and its record-side mapping only takes effect if it
  adds `ValueShape.JooqRecordInput` targets with the partial-record caveat below.
- R98 (`multi-source-input-validation`, Backlog) attaches constraints per `InputRecordShape`
  component, with the same consequence.

R1099 (`input-navigation-through-output-fields`, Ready) dissolves `graphitron_argument_reachable_input`
on its own schedule; nothing here touches the view.

## Tests

- Execution tier (`GraphQLQueryTest`), the item's acceptance evidence: `countFilmDrafts` with one
  blank title among two elements returns `count: null` and exactly one `FilmWriteInvalid` with
  `path ["countFilmDrafts", "in", "1", "title"]` and the interpolated `@NotBlank` message. The
  service not being called is shown by the null `count`, which only the service sets. The existing
  `countFilmDrafts_*` cases hold unchanged.
- Execution tier, path translation: one new `@service` fixture whose input binds a consumer record
  through a grouping input that flattens onto it, with a constraint on the flattened member, a
  `List<@Valid ...>` member carrying a constraint on its element class, and a class-level
  constraint. Asserts the SDL path for each: grouping field kept, the element index present, the
  class-level path ending at the argument.
- Execution tier, no pre-step where there is no target: `filmLookupValidated` succeeds through an
  `ExecutionInput` that carries no `GraphitronContext`, the way
  `countFilmDrafts_failureInsideValidation_isHandledByTheFetchersCatchArm` provokes the opposite,
  showing no validator was asked for.
- Classification: `ErrorChannelClassificationTest`'s `INPUT_GRID_SDL` under a VALIDATION channel
  now classifies, replacing its rejection case.
- `FetcherPipelineTest.inputRecord_validatorPreStep_receivesTypedRecordNotMap` asserts on generated
  body text and pins the retired target; it is deleted, not rewritten. No new test reads a
  generated method body.

## Retired vocabulary

`ValidatedArgument`, `validatedArguments`, `InputObjectNestedList`, `unvalidatableArgumentReason`,
`outerArgOf`. Prose to sweep: "the generated input record" and "the graphitron-emitted" where they
describe what the pre-step validates, "no constraint annotations", "the walk produces zero
violations", "runs before tenant resolution".

## Other solutions we've considered

- *Constraints declared in SDL and emitted onto the generated record.* Closes the loop with no
  consumer Java, and would reach scalar arguments. It costs a new directive and a mapping from SDL
  to the constraint vocabulary, and duplicates what the class the service takes can already say.
  It stays open: the generated classes still exist after this item.
- *Jakarta method validation (`ExecutableValidator.validateParameters`).* Would reach scalar
  parameters (`lookup(@Positive Integer id)`), but the specification excludes static methods and
  most `@service` methods are static; it would split VALIDATION's behaviour by call shape.
- *A consumer-supplied `ValidatorFactory`.* Reopens programmatic `ConstraintMapping` against the
  generated classes; the smallest change and the one that pushes most onto the author, and it keeps
  graphitron validating an object the service never sees.
- *Walking jOOQ record parameters too.* jOOQ's `<validationAnnotations>` derives constraints from the
  database, which is the attraction. But a jOOQ record bound from an input carries only the columns
  the input names, and jOOQ annotates every non-nullable column without a default `@NotNull`, so a
  partial record (an update naming two columns) would fail on every column it leaves unset. Making
  that sound needs a notion of which columns a record was bound for, which is R92's territory, where
  database-derived constraints are the subject.
- *Validating before the tenant hand-down.* Keeps today's promise that violations win, at the cost
  of materialising bound classes ahead of tenant resolution, which a divining field reads its tenant
  from. Rejected; the order is stated in the manual instead.
- *Warning at build time on a VALIDATION channel whose field validates nothing.* A VALIDATION type
  shared by DML mutations and a `@service` mutation is a deliberate shape (`FilmWriteInvalid`), and
  the warning would fire on every `@service` field in that union with no way to silence it but
  splitting the union. The manual's rule carries the signal instead.

## Provenance

Found while writing execution-tier coverage for the `description:` override work, which wanted a
VALIDATION fixture asserting that a violation's interpolated message reaches the errors slot and
could not construct one. `Query.filmLookupValidated` and `Mutation.countFilmDrafts` landed then as
the VALIDATION channels that compile and run with nothing to report.
