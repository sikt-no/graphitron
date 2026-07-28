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
  cannot live in the declarative pairwise directive-conflict table, and
  the fold likewise retires the `@service` x `@externalField` `Conflict`
  pair from that table; one composition axis moves from SDL-only conflict
  checking to reflection-conditional checking.
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
   for the `@service` entry point). Pipeline-tier coverage in
   `GraphitronSchemaBuilderTest` for the accept arm and every rejection
   arm, reusing `TestExternalFieldStub`, plus one row asserting both
   spellings of the same method produce a structurally equal
   `ComputedField`, which is the fold's actual contract pinned at the tier
   that owns it.
2. **Deprecation surfaces.** Docstring `@deprecated` on the `@externalField`
   definition with a reason naming `@service`; the
   `NoDeprecatedDirectiveUsageVisitor` finding carries the two-token
   rewrite `LintFix`; lint tests (in the existing lint test family under
   `graphitron/src/test/.../lint/`) proving an `@externalField` call site
   is flagged and the fix is attached. `@externalField` classification
   behaviour is otherwise unchanged.
3. **Sakila proof.** Migrate one existing fixture (`Film.isEnglish`) to the
   `@service` spelling to prove the fold end-to-end at the execution tier;
   keep at least one `@externalField` fixture (the `Inventory` lift trio) in
   place to prove the migration window. Execution-tier tests updated
   accordingly, including one asserting both spellings coexist in a schema.
4. **LSP surfaces.** Hover text for the two `FieldClassification` shapes
   states the execution model ("embedded in the parent SELECT" vs
   "DataLoader-backed service call"). Completions stop discriminating by
   directive name (post-fold there is no name to discriminate on): the
   catalog carries the contract arm as a fact, and both hover and the
   `@service` method-reference completions project off that one
   classification, so `Field`-returning static methods surface at eligible
   coordinates without re-deriving the contract heuristically. The
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
   `classifier-mental-model.adoc` updated where they name the directive.
   Changelog entry carries the one-line migration rewrite.

## Tasks

In order:

1. Build the `ServiceCatalog` dispatch entry and the single contract
   enforcer (column-collision check moves in); delegate
   `ExternalFieldDirectiveResolver` to it; narrow
   `ChildField.ComputedField.method` to `MethodRef.StaticOnly`; verify
   `@externalField` behaviour is unchanged (existing tests stay green).
2. Add the `Resolved.Computed` arm and place it at all four `FieldBuilder`
   call sites; pipeline-tier accept coverage plus the
   structural-equality-across-spellings row.
3. Add the rejection arms and their pipeline-tier coverage: root and
   class-backed coordinates, `@splitQuery` composition, inert parameters,
   broken signatures; retire the `@service` x `@externalField` pair from
   the directive-conflict table.
4. Add the deprecation surfaces (docstring `@deprecated`, the `LintFix` on
   the visitor's finding) and lint-test coverage.
5. Migrate the `Film.isEnglish` Sakila fixture to `@service`; add the
   coexistence execution test; full `mvn install -Plocal-db` green.
6. LSP hover text, catalog-fact-driven completions, and the quick-fix code
   action carried by the `LintFix`; LSP tests including a rewrite
   round-trip (applying the quick-fix yields a site the classifier accepts
   unchanged).
7. Docs and changelog; draft the cutover follow-up item text (filed at the
   Done gate with the release-boundary trigger).

## Done means

- A `@service` reference to a `public static Field<X> m(ParentTable t)`
  method on a table-backed child field resolves end-to-end (Sakila
  execution test green), with generated code identical to what
  `@externalField` produces for the same method.
- Every pre-existing `@service` and `@externalField` test stays green;
  `@externalField` call sites now produce the located lint finding, in the
  build log and the LSP, with the rewrite fix attached.
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
