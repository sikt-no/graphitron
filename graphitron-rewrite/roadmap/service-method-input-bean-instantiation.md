---
id: R150
title: Instantiate service-layer input beans at the @service fetcher boundary
status: In Review
bucket: feature
priority: 1
theme: mutations-errors
depends-on: []
created: 2026-05-12
last-updated: 2026-05-12
---

# Instantiate service-layer input beans at the @service fetcher boundary

## Implementation status

Implemented. Helper-generation, classifier post-processing, and call-site routing all landed
in one commit. End-to-end coverage: an L4 execution test in `GraphQLQueryTest` populates a
nested-list-bearing bean from a real GraphQL mutation and asserts the service body sees typed
field values (`submitFilmReviewWithDetails`); an L3 compilation fixture in
`graphitron-sakila-example` proves the generated helper compiles against the consumer-authored
bean class; L2 pipeline cases in `GraphitronSchemaBuilderTest` pin the three classifier arms
(singular bean, list-of-bean, bean-Java-vs-scalar-SDL rejection); L1 unit tests in
`TypeFetcherGeneratorTest` lock the helper-method spec shape (record vs JavaBean target, plural
helper, dedup-by-class).

Model invariant: `CallSiteExtraction.Direct` is reserved for scalar/enum SDL arguments. Any
`@service` parameter whose SDL arg is an input-object is classified as `InputBean` or rejected
loudly at generation time. A `Map<String, Object>` Java parameter paired with an input-object
SDL slot is the dangerous pre-R150 silent-cast pattern and is now a hard rejection — there is
no "raw passthrough" escape hatch, because the only safe outcome is a populated bean.

Recursion is restricted to head-only paths in v1: a `@service` param whose `argMapping` is a
multi-segment dot-path stays on the legacy `Direct` arm even when the leaf SDL type is an
input object. The follow-up is straightforward (walk the SDL chain at the bean detection
site) but no concrete production case demanded it for v1.

## Problem

A `@service` method whose parameter is a consumer-authored Java class
mirroring an SDL `input` type (e.g. `List<OpprettRegelverksamlingInput>`
where `OpprettRegelverksamlingInput` lives in the consumer's service
package) is unrunnable today. The rewrite classifies the param via
`ServiceCatalog.argExtraction`
(`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/ServiceCatalog.java:690`),
which only recognises two cases — jOOQ enum (`EnumValueOf`) and
everything-else (`Direct`). The bean falls into `Direct`, and
`ArgCallEmitter.buildMethodBackedCallArgs`
(`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/ArgCallEmitter.java:113`)
emits a raw `env.getArgument(name)` straight into the call site. graphql-java
returns a `LinkedHashMap` (or `List<LinkedHashMap>` for `[Input!]!`), the
unchecked cast compiles silently, and the runtime `ClassCastException`
surfaces on first field access inside the service body. Observed
production stack trace:

```
java.lang.ClassCastException: class java.util.LinkedHashMap cannot be cast
  to class no.sikt.fs.opptak.service.OpprettRegelverksamlingInput
  at no.sikt.fs.opptak.service.RegelverksamlingMutations.opprettRegelverksamling(RegelverksamlingMutations.java:25)
  at no.sikt.fs.opptak.generated.graphitron.fetchers.MutationFetchers.opprettRegelverksamling(MutationFetchers.java:178)
```

Legacy `graphitron-codegen-parent` handles this case via `InputParser`
(`graphitron-codegen-parent/.../generators/context/InputParser.java`),
consumed by `OperationMethodGenerator`, `NestedFetchDBMethodGenerator`,
`FetchMultiTableDBMethodGenerator`, and the exception-mapping generators.
That machinery was never ported to the rewrite. No pipeline-tier,
compilation-tier, or execute-tier test currently exercises a `@service`
method with a non-enum, non-scalar Java param, so the gap is invisible to
CI and surfaces only at consumer runtime.

This blocks the consumer from writing service methods in the shape they
need: a single typed-bean parameter (or `List<TypedBean>`) carrying every
field of an SDL `input` together. Scalar destructure (the shape R94's
plan assumes for *its* internal carrier) is not a substitute — the
consumer's service signature is their own design decision in the service
layer, and graphitron's responsibility is to honour it, not to dictate
it.

## What R94 is and isn't

R94 (`emit-input-records.md`, status Spec) emits a *graphitron-internal*
record per SDL `input` type, used at the fetcher boundary as a Jakarta
validation target before the service call. Its load-bearing invariant —
"services never see emitted records" — is a **cycle-prevention rule**:
the generated layer already depends on the service layer (to call into
it), and if a service signature referenced a graphitron-emitted record,
the dependency would close. The constraint is *not* "service params must
be scalar-only"; it is "service params must reference types the service
layer owns".

The consumer-authored bean (`OpprettRegelverksamlingInput`) is exactly
such a type: it lives in the service package, not in graphitron-generated
code. Instantiating it from the fetcher is fully compatible with R94's
invariant — the generated code constructs an instance of a service-layer
class, it does not ask the service to import a generated class.

R94 changes only the *source* the instantiation code reads from. Today:
`Map<String, Object>` returned by `env.getArgument`. Post-R94: the
graphitron-internal validated record. The instantiation step itself
(walk SDL-field → bean-field by name, populate a fresh instance of the
service-layer bean) is the same pipeline either way. R150 builds that
pipeline; R94 retargets its input source.

R150 does not depend on R94 — the `Map`-source arm is the post-R94 fallback
for SDL inputs that have no validation handler attached.

## Target emit shape

For a mutation field like:

```graphql
extend type Mutation {
    opprettRegelverksamling(
        input: [OpprettRegelverksamlingInput!]!
    ): [Regelverksamling!]! @service(class: "RegelverksamlingMutations", method: "opprettRegelverksamling")
}
input OpprettRegelverksamlingInput {
    regelverksamlingKode: String!
    organisasjonskode: String!
    erAktiv: Boolean!
    navn: [LokalisertTekstInput!]
}
```

against a service signature:

```java
public List<RegelverksamlingRecord> opprettRegelverksamling(List<OpprettRegelverksamlingInput> inputs)
```

the fetcher emits an instantiation step before the call:

```java
public static DataFetcherResult<List<RegelverksamlingRecord>> opprettRegelverksamling(
        DataFetchingEnvironment env) {
    try {
        DSLContext dsl = graphitronContext(env).getDslContext(env);
        List<OpprettRegelverksamlingInput> inputs =
            createOpprettRegelverksamlingInputs(env.getArgument("input"));
        List<RegelverksamlingRecord> payload =
            new RegelverksamlingMutations(dsl).opprettRegelverksamling(inputs);
        return DataFetcherResult.<List<RegelverksamlingRecord>>newResult().data(payload).build();
    } catch (Exception e) {
        return ErrorRouter.redact(e, env);
    }
}
```

The `createOpprettRegelverksamlingInputs` (and the singular
`createOpprettRegelverksamlingInput`) helper is graphitron-emitted on the
enclosing `*Fetchers` class — same emission site `LookupValuesJoinEmitter`
uses for its `buildInputRowsMethod` helpers
(`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/LookupValuesJoinEmitter.java:198`).
The helper references only:

- `java.util.Map` / `java.util.List` (JDK)
- The consumer-authored bean class (service-layer type — already on the
  generator's classpath via `ServiceCatalog` reflection)

It never imports a graphitron-emitted record. The cycle-prevention rule
holds.

## Mechanism

### Bean-shape detection at classify-time

`ServiceCatalog.argExtraction` gains a third arm: when the Java type of
a service parameter (or the element type of a `List<X>` / `Set<X>`
parameter) is a class that the codegen loader can resolve **and** the
SDL argument type at the same path is a `GraphQLInputObjectType`, the
classifier records a new `CallSiteExtraction.InputBean` variant carrying
the resolved Java `ClassName`.

Detection is structural — the Java class is recognised as an input bean
because the SDL side is an input object, not because of a naming
convention. A bean-shape Java param paired with a scalar-shape SDL arg
is a consumer error and produces a classify-time diagnostic
(`UnclassifiedField`-flavoured, surfaced through the existing reflection-
failure channel in `ServiceCatalog.reflectServiceMethod`).

### `CallSiteExtraction.InputBean`

New sealed-variant arm alongside `Direct`, `EnumValueOf`, `TextMapLookup`,
`JooqConvert`, and `NestedInputField`. Carries:

- `beanClass: ClassName` — the consumer-authored target type.
- `fields: List<FieldBinding>` — per-SDL-field binding, in declaration
  order, with the SDL field name, the Java setter/component name on the
  bean, the leaf scalar/enum extraction (recurses through `Direct` /
  `EnumValueOf` / nested `InputBean` for nested input-object fields), and
  whether the field is list-shaped.

The recursive arm covers nested input-object fields (e.g. `navn:
[LokalisertTekstInput!]` above): each nested field gets its own
`InputBean` extraction, and the helper is emitted recursively at the same
`*Fetchers` host.

### Helper-method emission

`ArgCallEmitter` gains a path: when the param's extraction is `InputBean`,
it emits a call to a per-bean helper method on the `*Fetchers` class and
queues that helper for emission alongside the fetcher. Helper signatures:

```java
private static OpprettRegelverksamlingInput createOpprettRegelverksamlingInput(Map<String, Object> raw);
private static List<OpprettRegelverksamlingInput> createOpprettRegelverksamlingInputs(Object raw);
```

The singular form takes a `Map<String, Object>` (already narrowed by the
caller) and populates the bean via:

- **Java record target**: positional canonical-constructor call.
- **Java class target**: no-arg constructor + per-field setter, where the
  setter name follows JavaBeans convention (`setRegelverksamlingKode`).
  If no no-arg constructor exists, the classifier rejects the param with
  a diagnostic naming the constructor shapes the helper can populate.

The plural form (`createOpprettRegelverksamlingInputs`) accepts `Object`,
checks for `null`, and streams the cast `List<Map<String, Object>>`
through the singular form. Null input lists pass through as `null`; null
input maps inside a list are rejected with a clear error message (a
non-null SDL element type forbids it).

Nested bean fields recurse into the corresponding `create*` helper.
Scalar fields cast directly. Enum fields route through
`<EnumType>.valueOf(...)` (reusing `EnumValueOf`'s emit shape).

### Helper dedup

Two `@service` methods that take the same input bean produce one helper,
emitted once. Dedup keys on the resolved `beanClass`; the emitter
maintains a per-`*Fetchers`-class set.

## Out of scope

- **Map-of-input-object** SDL fields (`Map<String, SomeInput>`-shaped
  scalars don't exist in graphql-java's type system; not a real case).
- **Polymorphic input beans** (subtype dispatch from SDL `oneOf`). SDL
  `oneOf` is not yet admitted by the rewrite; if and when it is, the
  helper grows a discriminator branch as a follow-up item.
- **Builder-pattern target classes**. The first cut targets records and
  JavaBeans only; builders are common enough in service-layer code that
  they may want a follow-up, but adding them in the first cut expands
  the constructor-shape surface without a concrete consumer ask.
- **Customising helper names or visibility**. The slug-derived
  `create<TypeName>(s)` names and `private static` visibility are fixed
  — the helpers are an emission-internal detail, not a published API.

## Acceptance criteria

1. A service method with a bean-typed param (single or `List`-shaped, SDL
   `input` type on the schema side) compiles, executes, and the bean's
   fields are populated from the GraphQL variables.
2. Nested input-object fields (`input` types whose fields are themselves
   `input` types) populate recursively, one emitted helper per distinct
   bean class.
3. Enum-valued fields inside an input bean decode via the same
   `EnumValueOf` arm scalar enum args already use.
4. A service method whose param-type pairing is structurally
   unsupportable (bean-shape Java param vs scalar SDL arg, or bean class
   with no compatible constructor) is rejected at classify-time with a
   diagnostic that names the param and the missing capability — never
   silent emit followed by runtime cast.
5. The legacy runtime symptom (`ClassCastException: LinkedHashMap cannot
   be cast to <ConsumerBean>`) cannot recur for the admitted shapes.

## Tests

### L1 — unit (`ServiceCatalogTest`, `ArgCallEmitterTest`)

- `ServiceCatalog.argExtraction` returns `CallSiteExtraction.InputBean`
  for a bean-typed param paired with a `GraphQLInputObjectType` SDL arg;
  returns the existing `Direct` / `EnumValueOf` arms unchanged for the
  legacy cases.
- `ArgCallEmitter.buildMethodBackedCallArgs` emits the
  `create<TypeName>(env.getArgument(name))` (or `createN<TypeName>s(...)`)
  shape when given an `InputBean` extraction; emits no extra wrapper when
  the extraction is `Direct`.
- Helper-emission queue: two `@service` methods sharing a bean produce
  one helper.

### L2 — pipeline (`TypeFetcherGeneratorTest`)

- Service mutation with `[InputBean!]!` arg emits a fetcher that calls
  `createN<TypeName>s` exactly once before the service call.
- Nested bean: helper for outer bean calls helper for inner bean.
- Bean-shape param against scalar SDL arg produces a classifier
  diagnostic naming the param.

### L3 — compilation (`graphitron-sakila-example`)

- A `@service` mutation in the sakila fixture taking a consumer-authored
  bean parameter compiles. Required because the helper references the
  consumer's class directly, and only `graphitron-sakila-example`
  exercises the full classpath.

### L4 — execution (sakila-service test corpus)

- End-to-end: GraphQL mutation `opprettRegelverksamling(input: [...])`
  reaches the service body with a populated `List<OpprettRegelverksamlingInput>`
  whose every field round-trips through Postgres.
- Nested bean: a mutation whose input has a nested `[InnerInput!]`
  field reaches the service with the inner list populated.
- Enum field: a mutation whose input has an enum field reaches the
  service with the typed Java enum (not a String).
- Null-handling: nullable optional list field arrives as `null` on the
  bean; non-null required field absent from the request produces a
  classifier-tier failure before execute (this is SDL validation, not
  this item's contract, but pin the seam).

### Regression — the original bug

A test mirroring the production stack trace (`@service` with
`List<ConsumerBean>` param) pins the fix: passes after R150, fails
before.

## Implementation order

1. Add `CallSiteExtraction.InputBean` sealed-variant arm with a
   compact-constructor invariant rejecting bean-less / field-less
   instances.
2. Extend `ServiceCatalog.argExtraction` to detect bean-shape params and
   resolve their SDL `GraphQLInputObjectType` partner, returning the new
   variant. Classifier diagnostic for unsupportable pairings.
3. Helper-emission queue on `TypeFetcherGenerator`'s emission context;
   dedup keyed on `ClassName`.
4. `ArgCallEmitter.emitForParam` routes `InputBean` through the helper
   call.
5. Helper synthesis: record (positional constructor) and JavaBean (no-arg
   + setters) target shapes; recursive call into nested bean helpers;
   enum-field routing through `EnumValueOf`.
6. Regression-tier coverage in `graphitron-sakila-example` and the
   sakila-service execution corpus.

## Invariants (load-bearing)

- **Cycle-prevention.** Emitted helpers reference only JDK types and
  service-layer types. No helper, no fetcher arm introduced by this
  item, imports a graphitron-emitted record. R94's "services never see
  emitted records" rule is preserved at every emission site.
- **No silent runtime cast.** Any bean-shape param the helper cannot
  populate (missing constructor, SDL/Java field mismatch) is rejected
  at classify-time. The only `Map` → bean cast in emitted code is the
  one inside the helper, guarded by an explicit field-by-field walk.
- **Dedup is by class, not by usage site.** Two services that take the
  same bean share one helper; a helper is never re-emitted under a
  different name.

## Roadmap relationship

- **Independent of R94** (`emit-input-records`). R94 changes the source
  the helper reads from (raw `Map` → graphitron-internal record); R150
  builds the helper. Either lands first; the other follows by
  retargeting the source side of the helper's body without changing the
  emission seam.
- **Adjacent to R141** (`bulk-input-single-carrier-list-data-field`).
  R141 admits `[Input!]!` arg + list-shaped data-channel payload for
  *DML* mutations; R150 unblocks the same arg shape for *`@service`*
  mutations whose service signature takes the bean directly. Neither
  blocks the other; together they cover both arms of "bulk insert with a
  typed input list".
