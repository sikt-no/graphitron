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
reflected Java method signature, which `@service` classification already
leans on for its return-shape projection (table-bound / scalar / class-backed
payload / polymorphic): a referenced method that takes a single jOOQ
`Table<>`-subtype parameter and returns a parameterised `Field<X>` classifies
to `ChildField.ComputedField` exactly as `@externalField` does today, while
every existing `@service` signature keeps its current classification
unchanged.

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

So a "try the computed-field shape, else the service shapes" branch inside
the `@service` resolver is unambiguous today. The risk is not collision but
diagnostics: a user who writes a broken signature must get a message that
names the contract they were aiming for, not a confusing rejection from the
other contract's validator. `pickMethod` already rejects same-name overloads
outright, which keeps the branch deterministic.

## Design

Dispatch on the reflected return type, once, before either contract's
validator runs. At every `@service` coordinate the resolver already loads
the class and picks the method by name (`ServiceCatalog.pickMethod`, which
rejects same-name overloads outright, so the pick is deterministic). The
fold adds one discrimination step on the picked `java.lang.reflect.Method`:

- Raw return type `org.jooq.Field` at a child coordinate on a
  `@table`-backed parent: the computed-field arm. Validate the full
  `@externalField` contract (public static, exactly one parameter assignable
  from `org.jooq.Table`, parameterised `Field<X>` return, field-name vs
  real-column collision check) and classify to `ChildField.ComputedField`
  with a `MethodRef.StaticOnly`, exactly as `ExternalFieldDirectiveResolver`
  does today. Every rejection on this arm names the computed-field contract,
  not the service contract.
- Any other return type: the existing service arms, byte-for-byte unchanged
  behaviour.

Mechanically this means extracting the contract validation out of
`ServiceCatalog.reflectExternalField` into a directive-agnostic helper that
both `ExternalFieldDirectiveResolver` (unchanged behaviour during the
migration window) and the `@service` path call, so the two spellings cannot
drift apart while both are alive.

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
- **`@splitQuery` composition is rejected on the computed-field arm.**
  `@service` on a non-root field currently hard-requires `@splitQuery`; the
  computed-field shape rides the parent SELECT, so `@splitQuery` does not
  apply to it. Dispatch runs first, then the arm-specific rule: a
  `Field<X>`-returning method with `@splitQuery` present is an author error
  ("the embedded computed-field shape rides the parent SELECT; remove
  @splitQuery"), and the existing requires-`@splitQuery` rejection fires
  only on the service arms. Both messages stay signature-aware.
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
- **Deprecation channel, two-pronged, both mechanisms already exist.**
  (1) The `@externalField` definition in `directives.graphqls` gains the
  docstring `@deprecated` token with a reason pointing at `@service`, so the
  lint engine (`DeprecationRecognizer` +
  `NoDeprecatedDirectiveUsageVisitor`) flags every call site warn-not-fail.
  (2) The classifier emits a per-site build-log WARN in the parseable
  `parentTypeName.fieldName` + canonical-replacement format the
  `IdReferenceField` synthesis shim established, so consumers who do not run
  lint still see it and migration tooling can grep it.
- **Migration window and tooling.** The window is open-ended: removal is a
  follow-up Backlog item filed when this ships, prioritised on observed
  remaining call sites. Tooling is documentation plus grep (the changelog
  entry and the manual's migration note carry the exact one-line rewrite:
  `@externalField(reference: {...})` becomes `@service(service: {...})`);
  Sikt's ~49 known call sites are tractable by hand, so no mojo or LSP
  quick-fix ships in this item.

## Deliverables

1. **Classifier fold.** The directive-agnostic contract helper extracted
   from `ServiceCatalog.reflectExternalField`; the return-type dispatch in
   the `@service` resolution path at table-backed child coordinates
   producing `ChildField.ComputedField`; the rejection arms (root and
   class-backed coordinates, `@splitQuery` composition, `contextArguments` /
   `argMapping` presence, and the existing signature rejections respelled
   for the `@service` entry point). Unit-tier coverage in
   `GraphitronSchemaBuilderTest` for the accept arm and every rejection arm,
   reusing `TestExternalFieldStub`.
2. **Deprecation surfaces.** Docstring `@deprecated` on the `@externalField`
   definition with a reason naming `@service`; per-site classifier WARN in
   the parseable format; lint-tier test proving
   `NoDeprecatedDirectiveUsageVisitor` flags an `@externalField` call site.
   `@externalField` classification behaviour is otherwise unchanged.
3. **Sakila proof.** Migrate one existing fixture (`Film.isEnglish`) to the
   `@service` spelling to prove the fold end-to-end at the execution tier;
   keep at least one `@externalField` fixture (the `Inventory` lift trio) in
   place to prove the migration window. Execution-tier tests updated
   accordingly, including one asserting both spellings coexist in a schema.
4. **LSP surfaces.** Hover text for the two `FieldClassification` shapes
   states the execution model ("embedded in the parent SELECT" vs
   "DataLoader-backed service call"); `@service` method-reference
   completions include `Field`-returning static methods at eligible
   coordinates. `ExternalFieldCompletions` stays alive for the window.
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

1. Extract the shared contract helper from `reflectExternalField`; verify
   `@externalField` behaviour is unchanged (existing tests stay green).
2. Add the return-type dispatch and the `ComputedField` accept arm at
   table-backed child coordinates; unit-tier accept coverage.
3. Add the rejection arms and their unit-tier coverage: root and
   class-backed coordinates, `@splitQuery` composition, inert parameters,
   broken signatures.
4. Add the deprecation surfaces (docstring `@deprecated`, per-site WARN)
   and lint-tier coverage.
5. Migrate the `Film.isEnglish` Sakila fixture to `@service`; add the
   coexistence execution test; full `mvn install -Plocal-db` green.
6. LSP hover text and completions; LSP-tier tests.
7. Docs and changelog.

## Done means

- A `@service` reference to a `public static Field<X> m(ParentTable t)`
  method on a table-backed child field resolves end-to-end (Sakila
  execution test green), with generated code identical to what
  `@externalField` produces for the same method.
- Every pre-existing `@service` and `@externalField` test stays green;
  `@externalField` call sites now produce the lint deprecation and the
  build-log WARN.
- Each rejection arm has a unit-tier test asserting its message names the
  computed-field contract.
- LSP hover distinguishes embedded from DataLoader-backed `@service` fields.
- Docs render cleanly (`mvn install -Plocal-db` without `-P!docs`); the
  manual nowhere recommends `@externalField` as the primary spelling.
- On Done, discard R54 (`rename-externalfield-directive`) per the mutual
  cross-link, and file the follow-up Backlog item for eventual
  `@externalField` removal.

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
