---
id: R1066
title: "Directives applied to a custom scalar are dropped from the runtime schema"
status: Spec
bucket: bug
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-10-19
last-updated: 2026-10-19
---

# Directives applied to a custom scalar are dropped from the runtime schema

## Goal

A directive applied to a custom scalar in the source SDL survives into the schema the generated
`Graphitron.buildSchema(...)` returns, and so into the `_service { sdl }` a federated subgraph
serves. Apollo contract filtering reads `@tag` off that served SDL, so a scalar tagged `stable`
today drops out of every contract that filters on the tag, even though the schema author tagged it.

```graphql
extend schema @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key", "@tag"])

scalar BigDecimal @scalarType(scalar: "graphql.scalars.ExtendedScalars.GraphQLBigDecimal")
  @tag(name: "stable")
scalar LocalDate @scalarType(scalar: "graphql.scalars.ExtendedScalars.Date")
  @tag(name: "stable")
```

Served today: `scalar BigDecimal` and `scalar LocalDate`, bare. Served after: both carry
`@tag(name: "stable")`, the same line the generated `schema.graphqls` resource already prints.
`@specifiedBy(url:)` on a custom scalar rides along the same way, and the runtime scalar's
`specifiedByURL` in introspection agrees with it.

## Evidence

Surfaced by the sis v9 against v10 differential test (2026-10-19): the scalars `BigDecimal`,
`Date`, `DateTime`, `Time` and `Duration` lose `@tag(name: "stable")` in `_service { sdl }`. The
source declares it.

The two sides of the generator disagree. `SchemaSdlEmitter` prints the generated `schema.graphqls`
from the assembled schema (the `GraphQLSchema` graphql-java builds from the source SDL at codegen),
whose `GraphQLScalarType` carries every application; the runtime build drops them.
`FederationBuildSmokeTest.emittedSdlMatchesRuntimeSchema` exists to catch exactly that drift, and
misses it only because the federated fixture declares no scalar with an application.

## Cause

`GraphitronSchemaClassGenerator`'s scalar registration loop switches on the scalar's
`ScalarResolution` (the classifier's verdict on which `GraphQLScalarType` backs it):

- `Resolved`, a `public static final GraphQLScalarType` constant whose name matches the SDL name,
  is handed straight to `schemaBuilder.additionalType(Owner.FIELD)`. The constant is the library's
  object; nothing of the SDL declaration reaches it.
- `Synthesised`, a scalar whose SDL name differs from any constant's (aliasing `@scalarType`, or a
  federation-namespace name such as `federation__FieldSet`), is built by the factory
  `HelperMethodSink.addSynthesisedScalar` allocates, which sets only name and borrowed coercing.

Objects, interfaces, unions, enums and input types carry their applications through
`AppliedDirectiveEmitter.emitApplications`, which writes one `b.withAppliedDirective(<helper>())`
per survivor (an application `SchemaDirectiveRegistry.isSurvivor` keeps, that is anything but
Graphitron's own build-time directives) onto the type's builder. Scalars never pass through it.

## Implementation

Registration splits on how the rest of the runtime schema reaches a scalar, which is a fact about
the scalar's identity rather than about what its SDL declaration carries:

- *Identity-bound*: the spec built-ins (`Int`, `Float`, `String`, `Boolean`, `ID`). Introspection,
  federation-jvm and `AppliedDirectiveEmitter.emitInputType` reference `Scalars.GraphQLString` and
  its siblings as objects, so the registered scalar must be that object. A copy registered beside
  them fails `schemaBuilder.build()` with "You have redefined the type 'String'". These keep the
  direct `schemaBuilder.additionalType(Scalars.<field>)`.
- *Name-bound*: every other scalar. Generated code reaches these only through
  `GraphQLTypeReference.typeRef(name)` (`scalarConstantOwner` has no emitter consumer beyond the
  registration loop), so the registered object is free to be a copy. These always register through
  a factory that carries the SDL declaration.

The split is gated on `ScalarTypeResolver.isSpecBuiltIn`, the predicate `emitInputType` already
uses for the same distinction; no new `ScalarResolution` arm or model flag.

- `HelperMethodSink`: replace `addSynthesisedScalar(ScalarResolution.Synthesised)` with
  `addScalar(GraphitronType.ScalarType, GraphQLScalarType form)`, returning the helper name
  `scalar_<sanitised name>` as now. The factory body is statement-flattened like the other helpers
  (one local builder `b`, one statement per builder call), with a head that depends on the arm and a
  tail shared by both:
  - head, `Resolved`: `GraphQLScalarType.Builder b = GraphQLScalarType.newScalar(Owner.FIELD)`,
    graphql-java's copy builder, which keeps the constant's name, coercing and description;
  - head, `Synthesised`: `newScalar()`, `b.name(<sdlName>)`, `b.coercing(<owner>.<field>.getCoercing())`,
    as today;
  - tail: `b.specifiedByUrl(<url>)` when `form.getSpecifiedByUrl()` is non-null, then
    `AppliedDirectiveEmitter.emitApplications(body, "b", form, this)` (the survivor filter every
    other type's applications go through), then `return b.build()`.
- `GraphitronSchemaClassGenerator`, the scalar registration loop: a spec built-in emits
  `schemaBuilder.additionalType(Owner.FIELD)` as now (its resolution is always `Resolved`); every
  other scalar resolves its form and emits `schemaBuilder.additionalType(<addScalar(...)>())`. The
  form is `CarriesObjectForm.formOf(scalar, scalar.name(), assembled)` when that is a
  `GraphQLScalarType`, falling back to `scalar.schemaType()`, the same resolution
  `EnumTypeGenerator.generateFor` and the input emitter use. `formOf` puts the post-synthesis
  schema first, which is also the schema `SchemaSdlEmitter` prints, so the runtime factory and the
  emitted `schema.graphqls` read one object, configured additions included.
- Update the loop's comment and the class javadoc's registration paragraph to state the
  identity-bound / name-bound split and why.

The registered set is unchanged (same scalars, same names), so the reference-closure guard in
`GraphitronSchemaBuilder` that re-derives it needs no change.

### Consumer-facing consequence

Every name-bound scalar is now a distinct object from the constant it is copied from. A consumer
whose `buildSchema(...)` customizer still registers a constant Graphitron already registers (the
`@scalarType` migration already told consumers to remove those calls, and the custom-scalars page
says not to) was tolerated so far, because graphql-java accepts the identical instance twice. After
this change the duplicate fails schema build with graphql-java's "You have redefined the type"
assertion, on the first regeneration and independently of what the SDL carries. Two places say so:
one sentence in `docs/manual/how-to/custom-scalars.adoc`, where it says the constant is registered
automatically (the registered scalar is a copy carrying the SDL declaration's directives, and
registering the constant again from the customizer fails the build), and a migration note in the
item's `roadmap/changelog.md` entry at Done.

## Tests

All behavioural, at the execution tier in `graphitron-sakila-example`; nothing asserts on
generated Java text.

- Fixture: in `federated-schema.graphqls`, add `@tag` to the `@link` import list and declare
  `scalar BigDecimal @scalarType(scalar: "graphql.scalars.ExtendedScalars.GraphQLBigDecimal")
  @tag(name: "stable")` (the `Resolved` arm) and
  `scalar LocalDate @scalarType(scalar: "graphql.scalars.ExtendedScalars.Date") @tag(name: "stable")`
  (the aliasing `Synthesised` arm), plus `@specifiedBy(url: ...)` on one of them. If classification
  does not retain a scalar no field references, put each on a field over an existing column of the
  matching type.
- `FederationBuildSmokeTest`, new case, the acceptance evidence: run `{ _service { sdl } }` and
  assert the served SDL contains both `@tag(name: "stable")` scalar lines and the `@specifiedBy`
  application. Run it against the unfixed generator once to confirm it fails.
- `FederationBuildSmokeTest.emittedSdlMatchesRuntimeSchema`, unchanged: with the fixture above it
  fails before the fix and passes after, and from then on locks the drift class.
- `FederationBuildSmokeTest`, new case, the identity-bound arm: on the schema
  `Graphitron.buildSchema(b -> {}, fed -> {})` returns, `getType("String")` is the same object as
  `Scalars.GraphQLString` (and likewise for the other built-ins the fixture uses), and the scalar
  carrying `@specifiedBy` reports that URL from `getSpecifiedByUrl()`.

## Out of scope

- A `Resolved` scalar's SDL description. The copy builder keeps the constant's own description,
  which is today's behaviour; whether an SDL description should override a library constant's is a
  separate decision with no evidence behind it yet.
- Applications on spec built-ins (`extend scalar String @tag(...)`). graphql-java drops them while
  building the assembled schema (confirmed against 25.0: the built scalar is identical to
  `Scalars.GraphQLString` and carries no applications), so they never reach Graphitron, and
  carrying them would mean registering a second `String`, which graphql-java refuses.

## Retired vocabulary

- `HelperMethodSink.addSynthesisedScalar` (replaced by `addScalar`).

## Other solutions we've considered

- **Keep the constant when the SDL contributes nothing**, copying only scalars that carry a survivor
  application or a `@specifiedBy`. It leaves generated output unchanged for most scalars, but it is
  a generator branch on a predicate over the declaration's contents, and it makes the customizer
  duplicate above SDL-dependent: a consumer's build keeps working until someone adds `@tag` to a
  scalar, then fails far from that edit. Splitting on identity puts the one decision where the
  constraint actually is.
- **`Owner.FIELD.transform(b -> ...)` emitted inline at the registration site.** Equivalent at
  runtime, but puts a lambda with a per-application chain in the `build()` body, which the
  helper-method shape exists to keep at O(1) chain depth.
