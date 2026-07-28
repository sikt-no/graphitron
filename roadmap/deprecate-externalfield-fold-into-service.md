---
id: R555
title: "Deprecate @externalField: fold the computed-field shape into @service"
status: Spec
bucket: cleanup
priority: 5
theme: service
depends-on: []
created: 2026-07-28
last-updated: 2026-07-28
---

# Deprecate `@externalField`: fold the computed-field shape into `@service`

## Proposal

Deprecate `@externalField` and extend `@service` to cover its use case. Both
directives mean "this field is resolved by external Java code" and both carry
the same `ExternalCodeReference` input; the split forces schema authors to
learn two directive names for one concept. The disambiguator becomes the
reflected Java method signature. Stated honestly, that is a new dispatch
mode, not an extension of an existing one: today `@service` projects its
return shape (table-bound / scalar / class-backed payload / polymorphic)
from the schema-declared type and uses reflection to validate against it,
so this fold introduces the first place where a Java signature selects a
field's execution model rather than being checked against a schema-stated
one. The resulting loss of SDL visibility is the item's central trade,
accepted deliberately and mitigated through the LSP (see Decisions). A
referenced method that takes a single jOOQ `Table<>`-subtype parameter and
returns a parameterised `Field<X>` classifies to `ChildField.ComputedField`
exactly as `@externalField` does today, while every existing `@service`
signature keeps its current classification unchanged.

## Why the shapes do not collide

The two contracts are structurally disjoint in `ServiceCatalog`:

- `reflectServiceMethod` accepts parameters classified as `DSLContext`,
  argument-bound (`ParamSource.Arg` via name or `argMapping`), context-bound
  (`contextArguments`), or `List`/`Set` batch-key containers
  (`SourceKey.Wrap`). It has no `Table<?>`-parameter arm.
- `reflectExternalField` requires `public static`, exactly one parameter
  assignable from `org.jooq.Table`, and a return type of exactly
  parameterised `org.jooq.Field`. `@service` return classification has no
  `Field<X>` arm.

So a return-type dispatch between the computed-field shape and the service
shapes is unambiguous today. The risk is not collision but
diagnostics: a user who writes a broken signature must get a message that
names the contract they were aiming for, not a confusing rejection from the
other contract's validator. `pickMethod` already rejects same-name overloads
outright, which keeps the branch deterministic.

## Design

Dispatch on the reflected return type, decided inside `ServiceCatalog` and
carried out of it as a sealed value. Reflection and jOOQ types stay behind
the catalog boundary: `ServiceCatalog` gains one entry that loads the class
and picks the method once (`pickMethod` already rejects same-name overloads
outright, so the pick is deterministic), reads the raw return type, and
returns a sealed contract classification. `ServiceDirectiveResolver`
switches on that value and never sees a `java.lang.reflect.Method` or an
`org.jooq` class.

Obtaining the dispatch key comes first, and it is not free: reading a return
type requires having picked a method, and the method-name default is itself
arm-dependent. `parseExternalRef` hands back a null `methodName` when
`method:` is omitted and does no defaulting; today the `@externalField` arm
defaults it to the GraphQL field name while the `@service` path passes the
null into `reflectServiceMethod`, which rejects with "service reference is
incomplete". The dispatch entry therefore defaults before it knows the arm;
see the omitted-`method:` Decision for the rule that keeps that from changing
`@service` behaviour. Threading matters too: `reflectServiceMethod` runs its
own `pickMethod`, so "picks the method once" means the dispatch entry passes
its pick down rather than letting the service path re-reflect. Passing the
picked method into the existing service reflection is the intended shape; a
second independent pick would be a silent double-reflection on every
`@service` field in the schema.

- Raw return type `org.jooq.Field` at a child coordinate on a
  `@table`-backed parent: the computed-field arm. The full contract
  (public static, exactly one parameter assignable from `org.jooq.Table`,
  parameterised `Field<X>` return, and the field-name vs real-column
  collision check) validates in one enforcer whose signature takes the
  parent `TableRef` and the catalog handle, so the collision check moves
  in with the reflection checks instead of staying behind in
  `ExternalFieldDirectiveResolver` and splitting the contract across two
  homes. Success mints `MethodRef.StaticOnly` and classifies to
  `ChildField.ComputedField`. Every rejection on this arm names the
  computed-field contract, not the service contract.
- Any other return type: the existing service arms, byte-for-byte unchanged
  behaviour.

The resolver surface is a new sealed arm, not a helper call:
`ServiceDirectiveResolver.Resolved` gains a `Computed` arm, kept outside
`Success` (whose `method()` is the sealed root `MethodRef`) so the narrower
carrier survives in the signature. All four `serviceResolver.resolve` call
sites in `FieldBuilder` then fail to compile until each coordinate places
the arm deliberately: root query, root mutation, and class-backed
coordinates map it to their dedicated rejections; the table-backed child
coordinate maps it to `ComputedField`. Riding along:
`ChildField.ComputedField.method` narrows from `MethodRef` to
`MethodRef.StaticOnly`, which both producers already mint, binding the two
entry points by type instead of by comment.

`ExternalFieldDirectiveResolver` delegates to the same catalog entry and
enforcer during the migration window, so the two spellings cannot drift
apart while both are alive.

Second dispatch site: `RecordBindingResolver`. The producer-grounding pass
runs *ahead of* classification, does its own reflection, and gates on the
directive name at two entry points: `groundServiceField` on `@service` and
`groundComputedField` on `@externalField`. It cannot read the classifier's
sealed verdict, so it needs the same return-type fork independently, and the
two entries are not interchangeable on three axes:

- Return-element peel. `groundComputedField` uses `jooqFieldElement`
  (`Field<X>` to `X`); `groundServiceField` uses `peelReturnElement`, whose
  container list has no `org.jooq.Field` arm, so `Field<FilmRecord>` grounds
  the raw `org.jooq.Field` as the SDL type's backing class instead of
  `FilmRecord`. This misgrounds silently; nothing rejects.
- Method-name default. `groundComputedField` defaults an omitted `method:`
  to the GraphQL field name, matching the convention
  `ExternalFieldDirectiveResolver` documents and the existing pipeline
  fixtures rely on; `groundServiceField` returns early when `method:` is
  absent, so grounding would be skipped for a field that classifies fine.
- Carrier-only side effects. `serviceCarrierProducerArrivalMemo` and
  `groundServicePayloadBinding` are service-carrier facts and must not run
  on a computed field.

The fork belongs at the top of grounding: read the picked method's raw
return type once, route `org.jooq.Field` to `groundComputedField`'s logic
(including its method-name default) and everything else to
`groundServiceField`. Factor the shared reflection so the two passes cannot
disagree about which method a reference names.

Join paths: on the computed arm a `@reference` path parses from the parent
table exactly as `@externalField` does today, and the existing validator
rejection for a `ComputedField` carrying a join path continues to fire;
the `@service` reconnect path, which starts from the service return type's
table rather than the parent, applies only to the service arms. The
ordering invariant recorded on `ExternalFieldDirectiveResolver` (a path
error surfaces ahead of any reflection failure) survives only on the
legacy entry; on the `@service` entry, dispatch must precede path parsing
because the arm is unknown until the return type is read. Revise that
javadoc to scope the invariant accordingly.

## Decisions

- **Execution-model visibility moves to the LSP.** `@externalField` inlines
  the returned `Field<X>` into the parent's SELECT projection at query-build
  time (via `$fields`, read back by result-key alias); the service shapes
  call the method at request time. After the fold only the Java signature
  tells them apart in SDL. Agreed mitigation: the hover/classification
  catalog already separates the two shapes (`FieldClassification.Computed`
  vs `FieldClassification.ServiceBacked`), so LSP hover states the execution
  model explicitly, "embedded in the parent SELECT" vs "DataLoader-backed
  service call", and that hover text is a deliverable of this item rather
  than a follow-up.
- **`@splitQuery` composition is rejected on the computed-field arm.** The
  manual states that `@service` on a non-root field requires `@splitQuery`,
  but no classifier arm reads the directive on the `@service` paths today;
  the enforced invariant is the validator's "a table-bound service field
  requires a Sources parameter". This item does not adopt the unenforced
  prose claim. The new arm-specific rule: a `Field<X>`-returning method
  with `@splitQuery` present is an author error ("the embedded
  computed-field shape rides the parent SELECT; remove @splitQuery").
  Named cost, accepted: the rule is conditional on a reflected fact, so it
  cannot live in the declarative pairwise directive-conflict table; one
  composition axis moves from SDL-only conflict checking to
  reflection-conditional checking.
- **`@service` with `@externalField` on one field keeps rejecting.** Both
  stay classification-claiming directives for the whole migration window, so
  co-occurrence stays a conflict, and nothing here retires it. The pair has
  no entry in `pairVerdict` (it takes the default `Conflict` verdict); what
  names the two directives is the `present` list in
  `detectChildFieldConflict`. Dropping either would let `@service` win
  silently, since `classifyChildFieldOnTableType` tests `@service` ahead of
  `@externalField` and would discard the second reference with no
  diagnostic. The `@externalField` slot leaves that list at the cutover,
  when the directive itself goes.
- **An omitted `method:` defaults for the pick, then the arm decides whether
  the default was legal.** The computed shape's field-name convention has to
  reach the `@service` entry (a migrated site that omitted `method:` must keep
  working), but the arm is unknown until a method is picked, so the default
  cannot be gated on the arm. Rule: the dispatch entry defaults an absent
  `method:` to the GraphQL field name for the pick only. If the picked method
  returns `org.jooq.Field`, the computed arm accepts and the default stands.
  If it returns anything else, the entry restores today's "service reference
  is incomplete" rejection verbatim, so no service-shaped method becomes newly
  reachable through an omitted `method:`. If nothing matches the field name at
  all, the same incomplete-reference rejection fires rather than a
  method-not-found from either contract: with no `method:` and no arm to
  attribute the failure to, the omission is the actionable diagnosis. Named
  consequence, accepted: on a class that happens to hold a `Field`-returning
  method named after the field, an omitted `method:` now classifies where it
  previously rejected. That is the new capability, not a regression, and it is
  the one cell where "every existing `@service` signature keeps its current
  classification unchanged" needs this qualification to stay true. Pipeline
  coverage: all three cells (`Field`-returning default accepts, service-shaped
  default rejects as incomplete, no-match rejects as incomplete).
- **Inert directive parameters are author errors.** `contextArguments` and
  the reference's `argMapping` are meaningful for service methods but can
  never bind on the computed-field arm (the method's only parameter is the
  parent table). Reject rather than warn or ignore, matching how
  `@externalField` treats `argMapping` today.
- **Static-only stays.** The computed-field arm keeps the `public static`
  requirement: the generated projection code calls the method during query
  construction, so the instance-holder shape (`InstanceWithDslHolder`) does
  not transfer.
- **Root and class-backed coordinates reject.** The computed-field shape
  needs a table-backed parent whose SELECT it can join; a `Field`-returning
  method referenced from a root `@service` or from a class-backed parent
  gets a dedicated rejection naming the constraint, instead of falling
  through to a service-arm mismatch message.
- **Deprecation is one declared fact rendered through the lint channel.**
  The `@externalField` definition in `directives.graphqls` gains the
  docstring `@deprecated` token with a reason naming `@service`.
  `NoDeprecatedDirectiveUsageVisitor` already fires off that marker; its
  finding ships as a located `BuildWarning.LintFinding` carrying a
  `LintFix` with the two-token rewrite (`@externalField` to `@service`,
  `reference:` to `service:`), the channel the codebase built after the
  `IdReferenceField` shim precedent. One fact renders into the build log
  and the LSP alike, and the LSP quick-fix code action is that `LintFix`
  surfaced by the existing machinery rather than new tooling. No separate
  hand-maintained classifier WARN string ships; the unlocated logger-WARN
  precedent is the older mechanism and is not extended.
- **Migration window and tooling.** Additive-then-cutover, with the
  cutover committed rather than discretionary: this item ships the
  additive half, and its Done gate files the cutover follow-up carrying a
  named trigger, the next major release boundary (from graphitron `11`,
  `@externalField` keeps its declaration so the parser does not choke and
  rejects at classify time with a migration message, matching the house
  retirement pattern). The migration rewrite is mechanical and total:
  `@externalField(reference: {...})` becomes `@service(service: {...})`,
  directive name and argument name swap, the inner `ExternalCodeReference`
  carries over verbatim. Two tooling surfaces ship in this item: the
  documented one-line rewrite (changelog entry and the manual's migration
  note, grep finds the sites), and the LSP quick-fix code action carried
  by the deprecation `LintFix` above. No migration mojo ships; the
  quick-fix plus grep covers Sikt's ~49 known call sites.

## Deliverables

1. **Classifier fold.** The `ServiceCatalog` dispatch entry returning the
   sealed contract classification; the single contract enforcer (including
   the column-collision check, moved in from
   `ExternalFieldDirectiveResolver`); the `Resolved.Computed` arm on
   `ServiceDirectiveResolver.Resolved` placed at all four `FieldBuilder`
   call sites; the `ChildField.ComputedField.method` narrowing to
   `MethodRef.StaticOnly`; the rejection arms (root and class-backed
   coordinates, `@splitQuery` composition, `contextArguments` /
   `argMapping` presence, and the existing signature rejections respelled
   for the `@service` entry point). The return-type fork at the head of
   `RecordBindingResolver`'s grounding pass, routing a `Field`-returning
   reference to the computed grounding logic whichever directive spelled it.
   Two directive-specific strings respelled so neither misnames the spelling
   the author used: the join-path rejection in
   `GraphitronSchemaValidator.validateComputedField` ("@externalField with a
   @reference path ...") and the javadoc on `FieldClassification.Computed`
   ("A child field using `@externalField`"). Pipeline-tier coverage for the
   accept arm and every rejection arm, reusing `TestExternalFieldStub`, plus
   one row asserting both spellings of the same method produce a
   structurally equal `ComputedField`, which is the fold's actual contract
   pinned at the tier that owns it. Where that coverage lands is an
   implementation choice between the `GraphitronSchemaBuilderTest` enum
   table and the spec-by-example corpus: `ClassifiedCorpus` already carries
   an `@externalField` classification example and is the source of truth
   `VariantCoverageTest` reads for output-field leaves, so the
   both-spellings-converge row plausibly belongs there and renders into the
   docs for free. Decide once and keep the whole set in one place.
2. **Deprecation surfaces.** Docstring `@deprecated` on the `@externalField`
   definition with a reason naming `@service`; the
   `NoDeprecatedDirectiveUsageVisitor` finding carries the two-token
   rewrite `LintFix`; lint tests (in the existing lint test family under
   `graphitron/src/test/.../lint/`) proving an `@externalField` call site
   is flagged and the fix is attached. `@externalField` classification
   behaviour is otherwise unchanged.
   Two seams carry the whole-directive deprecation and neither fails on its
   own, so both must be done deliberately. `DeprecationsDocCoverageTest` is the
   bidirectional drift seam for exactly this change, and its whole-directive
   half iterates a hardcoded `WHOLE_DIRECTIVE_DEPRECATIONS` allow-list
   (`index`, `record`, `table`) rather than detecting the docstring marker:
   `externalField` joins that list. Its counterpart is a row in
   `docs/manual/reference/deprecations.adoc`, in the whole-directive table
   alongside `@table` and `@index`, with `@service` named as the migration.
   Adding the docstring marker without both leaves the deprecation invisible to
   the seam built to catch it and absent from the index authors read, with a
   green build either way. Reassurance in the other direction:
   `no-deprecated-directive-usage` is a `LintRule.Source.ENGINE` rule and
   `FixtureWarningsGateTest` filters `ENGINE` findings out, so the
   `@externalField` sites Deliverable 3 deliberately retains do not trip the
   sakila warnings-as-errors gate.
3. **Sakila proof.** Migrate two existing fixtures to the `@service`
   spelling: `Film.isEnglish` (`Field<Boolean>`, the scalar element) and one
   of the `Inventory` lift trio (`Field<XRecord>`, the class-backed element);
   `filmRef` is the sharpest of the three, since its `Field<FilmRecord>`
   grounds `FilmCard`'s backing class directly, whereas `filmCardData` and
   `filmCardDataMaybeMissing` reach `FilmRecord` through a custom-record
   accessor hop that could mask a wrong-branch grounding.
   Both elements are needed, because the grounding fork above is only
   observable on the record-returning shape; a scalar-only proof passes with
   the pass still on the wrong branch. Keep the rest of the `Inventory` trio
   on `@externalField` to prove the migration window. Execution-tier tests
   updated accordingly, including one asserting both spellings coexist in a
   schema, and one omitting `method:` on a `@service`-spelled computed field
   so the field-name default is pinned on the new entry point too.
4. **LSP surfaces.** Hover text for the two `FieldClassification` shapes
   states the execution model ("embedded in the parent SELECT" vs
   "DataLoader-backed service call"). Completions stop discriminating by
   directive name (post-fold there is no name to discriminate on).
   Hover and completions read different carriers and both need work; they do
   not share one projection. Hover switches on `FieldClassification`, a
   post-classify per-field fact. Method-name completions filter *candidate*
   methods before any field classifies, off `CompletionData.Method`, so the
   contract fact has to land there: carry the parameter's `ParamSource.Table`
   resolution on the method entry, which is exactly the projection
   `ExternalFieldCompletions` names as missing and approximates today with a
   one-parameter-plus-`Field`-return shape filter. With that carried,
   `Field`-returning static methods surface at eligible `@service`
   coordinates off a resolved fact instead of the heuristic.
   `CompletionData.Parameter` already declares a `source` component documented
   against the `ParamSource` taxonomy including `Table`, so no new carrier is
   needed; the work is populating it, and that is where the constraint bites.
   `ClasspathScanner` is the sole producer of `CompletionData.Method`, it
   passes `null` for every `source` today, and it is deliberately parse-only:
   its own comment on the `returnsCondition` field records "Exact descriptor
   compare, not assignability: the parse-only scan resolves no type
   hierarchy". `Table`-ness is an assignability question, and a parent table
   class is consumer-generated under an arbitrary name, so neither the
   `returnsCondition` trick (a known FQN to compare against) nor a simple-name
   match settles it. Resolution: compare the parameter descriptor by exact FQN
   against the set of generated table classes the jOOQ catalog already
   enumerates generator-side, which keeps the scanner's exact-descriptor
   discipline intact and needs no hierarchy walk. Do not widen the scanner to
   load and walk supertypes; that trades the invariant plus LSP-hot-path
   classloading for a fact the catalog can already answer. The
   quick-fix code action on every `@externalField` site is the deprecation
   `LintFix` rendered through the existing lint-to-code-action machinery,
   rewriting to the `@service(service: {...})` spelling with the inner
   `ExternalCodeReference` fields verbatim. `ExternalFieldCompletions`
   stays alive for the window.
5. **Docs.** `service.adoc` gains the embedded computed-field shape
   (signature, worked example, constraints); `externalField.adoc` gains a
   deprecation banner pointing at it; `computed-fields.adoc` respells its
   recipes to `@service` and names `@externalField` as the deprecated
   spelling; `handle-services.adoc` adds the embedded shape to its
   response-shape overview; `external-code.adoc` and
   `classifier-mental-model.adoc` updated where they name the directive;
   `deprecations.adoc` gains the whole-directive row per Deliverable 2.
   Changelog entry carries the one-line migration rewrite. Four sentences
   asserting non-root `@service` *requires* `@splitQuery` become wrong the
   moment the computed arm exists (it is a non-root `@service` that rejects
   `@splitQuery`) and must be qualified rather than left standing:
   `service.adoc`'s "On non-root fields, `@service` requires `@splitQuery`"
   bullet, and `handle-services.adoc`'s "`@service` on a non-root field is
   allowed *only* under `@splitQuery`", its "Non-root `@service` requires
   `@splitQuery`" gotcha bullet, and its see-also line calling `@splitQuery`
   "the per-parent batch wrapper non-root services require".
   `directives.graphqls` is a doc surface too, and two of its description
   blocks go stale: `ExternalCodeReference.argMapping`'s "Use on `@service` and
   every `@condition` site" plus its "Structurally inert on `@externalField`
   and `@enum` (rejected at parse time)" (inert on the `@service` computed arm
   as well, and rejected there post-dispatch rather than at parse time, since
   the parse-time gate in `parseExternalRef` keys on the `@externalField`
   directive name), and `@service`'s own docstring, whose "The signature of the
   method must match the inputs of the mutation or query" describes only the
   service shapes.

## Tasks

In order:

1. Build the `ServiceCatalog` dispatch entry and the single contract
   enforcer (column-collision check moves in), threading one `pickMethod`
   result into both arms and applying the omitted-`method:` default rule;
   delegate
   `ExternalFieldDirectiveResolver` to it; narrow
   `ChildField.ComputedField.method` to `MethodRef.StaticOnly`; verify
   `@externalField` behaviour is unchanged (existing tests stay green).
2. Add the `Resolved.Computed` arm and place it at all four `FieldBuilder`
   call sites; add the `RecordBindingResolver` grounding fork; pipeline-tier
   accept coverage plus the structural-equality-across-spellings row.
3. Add the rejection arms and their pipeline-tier coverage: root and
   class-backed coordinates, `@splitQuery` composition, inert parameters,
   broken signatures, and the three omitted-`method:` cells. Respell the two
   directive-specific strings
   (`validateComputedField`'s join-path rejection, the
   `FieldClassification.Computed` javadoc).
4. Add the deprecation surfaces (docstring `@deprecated`, the `LintFix` on
   the visitor's finding, the `WHOLE_DIRECTIVE_DEPRECATIONS` allow-list entry,
   the `deprecations.adoc` row) and lint-test coverage.
5. Migrate the `Film.isEnglish` and one `Inventory`-trio Sakila fixture to
   `@service`; add the coexistence and method-name-default execution tests;
   full `mvn install -Plocal-db` green.
6. LSP hover text, catalog-fact-driven completions (populate
   `CompletionData.Parameter.source` from the catalog's table-class set, no
   scanner hierarchy walk), and the quick-fix code
   action carried by the `LintFix`; LSP tests including a rewrite
   round-trip (applying the quick-fix yields a site the classifier accepts
   unchanged).
7. Docs and changelog; draft the cutover follow-up item text (filed at the
   Done gate with the release-boundary trigger).

## Done means

- A `@service` reference to a `public static Field<X> m(ParentTable t)`
  method on a table-backed child field resolves end-to-end (Sakila
  execution test green), with generated code identical to what
  `@externalField` produces for the same method. Proven for both a scalar
  `X` and a record `X`, so the grounding fork is covered.
- `@service` with `@externalField` on one field still rejects as a directive
  conflict, with a pipeline-tier test pinning it for the migration window.
- Every pre-existing `@service` and `@externalField` test stays green;
  `@externalField` call sites now produce the located lint finding, in the
  build log and the LSP, with the rewrite fix attached. The deprecation is
  visible on both drift seams: `WHOLE_DIRECTIVE_DEPRECATIONS` names
  `externalField` and `deprecations.adoc` carries its row.
- An omitted `method:` on a `@service`-spelled computed field resolves by
  field-name default, while an omitted `method:` on a service-shaped method
  still rejects as an incomplete reference.
- Each rejection arm has a pipeline-tier test asserting its message names
  the computed-field contract, and the structural-equality row proves both
  spellings converge on the same `ComputedField`.
- LSP hover distinguishes embedded from DataLoader-backed `@service`
  fields, and the quick-fix rewrites an `@externalField` site to an
  equivalent `@service` site the classifier accepts unchanged.
- Docs render cleanly (`mvn install -Plocal-db` without `-P!docs`); the
  manual nowhere recommends `@externalField` as the primary spelling.
- On Done, discard R54 (`rename-externalfield-directive`) per the mutual
  cross-link, and file the cutover follow-up item for `@externalField`
  removal with its release-boundary trigger named.

## Relationship to existing items

- **Competes with R54** (`rename-externalfield-directive`), which resolves
  the same deprecation by renaming instead (`@computed` / `@calculated`
  candidates); this item answers the successor-name question with "no new
  name, the successor surface is `@service`". The two items are mutually
  exclusive resolutions of the same problem: whichever completes first
  discards the other (R54 carries the matching back-pointer). R54's open
  questions (deprecation-warning channel, parallel-support window length,
  migration tooling) are settled by the Decisions section above.
- **R109** (`list-valued-external-field-multiset`, Spec) authors docs and
  fixtures that name `@externalField`; if both land, whichever lands second
  updates the directive spelling in the other's surfaces.
- **R240** (`tablemethod-return-type-token-threading`) cites the
  `@externalField` path as one of two remaining `MethodRef.StaticOnly` mint
  sites; the fold moves that mint site into the `@service` resolver but does
  not change the carrier.

## Out of scope

- Renaming `ChildField.ComputedField` or restructuring the classified model;
  both directives already converge on the same variant, so the fold is a
  parse/classify-surface change.
- Removing `@externalField` outright. It stays accepted for the migration
  window with a deprecation warning; removal is a follow-up gated on the
  window decision inherited from R54.
