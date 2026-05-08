---
id: R75
title: "Plain payload types via identity passthrough"
status: Spec
bucket: architecture
priority: 7
theme: mutations-errors
depends-on: [error-handling-parity]
---

# Plain payload types via identity passthrough

A passthrough `@mutation` whose return type is a plain SDL Object (no `@record(className)`,
no `@table`) should compile and serve correctly without the consumer authoring a Java
carrier. Today the rewrite forces an authored class on every payload that isn't a bare
`ID` or `@table`, which inverts legacy graphitron's default and (more importantly) drags
graphql-java's mapping concerns into the consumer's source tree.

The fix is not to synthesize a Java class. Generating transport DTOs is the legacy
mistake the rewrite already disowns under the "no DTOs, no TypeMappers" emitter
convention (`graphitron-rewrite/docs/rewrite-design-principles.adoc`, §"Return types").
Java payload classes earn their place only when the consumer needs to capture state or
behaviour on the carrier. For everything else, graphql-java's mapping plus graphitron's
existing identity-passthrough fetcher pattern (`FetcherEmitter.dataFetcherValue` lines
56-60: `($T env) -> env.getSource()`) does the work.

## Concrete friction

```graphql
input OpprettKvotesporsmalPreutfyllingInput @table(name: "kvotesporsmal_preutfylling") {
    kvotesporsmalPreutfyllingKode: String! @field(name: "KVOTESPORSMAL_PREUTFYLLING_KODE")
}

type KvotesporsmalPreutfyllingPayload {
    kvotesporsmalPreutfylling: [KvotesporsmalPreutfylling!]
}

type Mutation {
    opprettKvotesporsmalPreutfylling(
        input: OpprettKvotesporsmalPreutfyllingInput!
    ): KvotesporsmalPreutfyllingPayload @mutation(typeName: INSERT)
}
```

Today this fails at `MutationInputResolver.validateReturnType` (the `ScalarReturnType`
arm rejecting non-`ID` returns) with *"return type 'KvotesporsmalPreutfyllingPayload'
is not yet supported; use ID or a @table type"*. The plain Object lands as
`PlainObjectType` in the type classifier (`TypeBuilder.buildTypes`, the no-domain-directive
fall-through), and `GraphitronSchemaBuilder.buildSchema` skips field classification for
`PlainObjectType` parents entirely. There is no machinery downstream that would route
the DML round-trip's `Result<Record>` to graphql-java for traversal.

## What R12 already provides

R12 has shipped the authored-carrier path: canonical-ctor reflection, `ErrorChannel`,
`PayloadAssembly`, `ResultAssembly`, error-routing dispatch, default-literal capture.
That path stays intact and continues to govern any payload whose SDL declares
`@record(record: {className: ...})`. R75 is purely additive: it admits the
*no-authored-class* case alongside R12's existing path, with no shared machinery.

The two paths split cleanly. Authored carriers traffic in canonical-ctor slot indices
(`PayloadAssembly.rowSlotIndex`, `ErrorChannel.errorsSlotIndex`); the synthesized path
has no Java class to reflect on, so none of those slot indices apply. R12's
`MappingsConstantNameDedup` and `ErrorMappings` constants are about *which throwables
map to which `@error` types*, not about Java-class layout, and Phase 2 reuses them
unchanged.

## Phasing

R75 ships in three phases ordered by user-visible value. Each phase is a discrete
implementation track; downstream phases bind structurally to upstream ones but are
not schedule-coupled.

- **Phase 1: single-data-slot payloads, no errors.** Closes the legacy-parity gap on
  `@mutation` returning a plain Object payload with one data field. Specified in
  implementation-ready detail below.
- **Phase 2: errors-slot integration via `localContext` (sketch).** Lights up when the
  SDL payload adds an `errors:` field; the DML/service emitter wraps its result in
  `DataFetcherResult` with `localContext(errors)`, and the errors field's emitted
  fetcher reads from `env.getLocalContext()`.
- **Phase 3: domain-record returns from `@service` (sketch).** Allows a `@service`
  method to return its domain object directly; graphitron's existing per-field
  fetchers (record accessors) read off the source unchanged.

This Spec body specifies Phase 1. Promoting R75 from Spec → Ready means signing off
on Phase 1 only; Phases 2 and 3 each return to Spec for sharpening before Ready.

## Phase 1: single-data-slot payloads, no errors

### Trigger

A new `GraphitronType` arm `PassthroughPayloadType(name, location, dataField)` lifts
out of `PlainObjectType` when *all* hold:

1. The SDL Object type has no `@record` directive and no `@table` directive.
2. It is the return type of at least one `@mutation(typeName: …)` field.
3. It declares exactly one SDL field.
4. That field's element type satisfies the **passthrough rule** below.

Failing the trigger (multiple fields, off-shape return) leaves the type as
`PlainObjectType` with today's behaviour: the mutation field's return type still lands
as `ScalarReturnType` and `validateReturnType` still rejects. Phase 2 admits the
two-field "data + errors" shape; Phase 1 deliberately stays on the one-field case to
isolate the identity-passthrough mechanism from `localContext` plumbing.

### Passthrough rule

The single SDL field's element type is a `@table`-mapped Object (the only shape Phase
1 admits). The field's wrapper carries the SDL list cardinality. Off-shape element
types (interface, union, `@record`, scalar, enum) reject the lift; the type stays
`PlainObjectType` and the existing reject path applies.

`@table` is the right Phase 1 boundary because the existing `DmlReturnExpression`
arms (`ProjectedSingle`, `ProjectedList`) already emit
`INSERT...RETURNING $fields(...)` against an `@table` target. Phase 1 reuses both
arms by anchoring the projection on the data field's table, not the payload type.

### Type classification

`TypeBuilder.buildTypes` adds a pre-classification pass that walks SDL Object types,
applies the trigger, and produces `PassthroughPayloadType` for matches. The existing
`PlainObjectType` branch (`TypeBuilder.buildTypes`, the post-`@record` fall-through)
runs unchanged on non-matches. The pass needs SDL-level visibility into mutation
return types, which `ctx.schema` already provides; nothing crosses the reflection
boundary.

### Field classification

`PassthroughPayloadType` becomes a third dispatch arm in
`FieldBuilder.classifyField` (alongside `RootType`, `TableBackedType`, `ResultType`,
`ErrorType`). The single field on the type classifies as a new
`ChildField.IdentityPassthroughField(parentTypeName, name, location, returnType)`
arm. The arm's only job is to declare itself; `FetcherEmitter.dataFetcherValue`
emits `($T env) -> env.getSource()` for it, identical to the existing
`ConstructorField` and `NestingField` cases at FetcherEmitter.java:56-60.

A new `ChildField` permit is preferred over reusing `ConstructorField` or
`NestingField`: `ConstructorField` is `@table` parent → `@record` child;
`NestingField` carries a `TableBoundReturnType` and a recursive `nestedFields` list.
Neither shape fits a payload-typed parent passing through to a `@table` child, and
overloading either would muddy `classifyChildFieldOnTableType`'s existing arms.

### Mutation return-type admission

`MutationInputResolver.validateReturnType` adds a `PassthroughPayloadReturnType` arm
to the existing four-way switch. The arm admits the case (returns null) when the
data field's wrapper agrees with `validateReturnType`'s existing list/single rules
for the underlying `@table` type (Invariant #14, #15). Bulk-input + single-payload
combinations defer to the same rejection path as today's `TableBoundReturnType`.

A new `ReturnTypeRef.PassthroughPayloadReturnType(payloadTypeName, dataFieldTable,
wrapper)` carrier delivers the resolved data-field table to the DML emitter without
a second lookup at emit time. `BuildContext.resolveReturnType` produces it when
`ctx.types.get(name) instanceof PassthroughPayloadType`.

### DML emitter

Each of the four DML kinds (`MutationInsertTableField`, `MutationUpdateTableField`,
`MutationUpsertTableField`, `MutationDeleteTableField`) gains one new
`DmlReturnExpression` arm: `PassthroughProjectedList(payloadTypeName, dataFieldName,
dataFieldElementTableTypeName)`. The emitter behaviour is identical to
`ProjectedList`: project `$fields(sel, table, env)` for the data field's `@table`
type, run `INSERT/UPDATE/UPSERT/DELETE...RETURNING(<projection>).fetch()`, return
`DataFetcherResult.<List<Record>>newResult().data(rows).build()`. The data field's
identity passthrough makes graphql-java traverse `Result<Record>` through the
existing `@table` per-field fetchers without further intervention.

If the data field is single-cardinality (Phase 1 may restrict to list cardinality;
see open question below), a `PassthroughProjectedSingle` mirror arm follows the same
pattern with `.fetchOne()`.

The mutation field carrier (`MutationInsertTableField`, etc.) already carries
`DmlReturnExpression rex` via the classifier; the arm flips at classify time, not
emit time.

### What does *not* change

- No new generator class, no synthesized package, no Java payload class on disk.
- `PayloadAssembly`, `ResultAssembly`, `ErrorChannel` keep their current shape and
  scope. They remain authored-carrier-only.
- `FieldBuilder.findCanonicalCtor`, `resolveDmlPayloadAssembly`,
  `resolveErrorChannel`, `resolveServiceResultAssembly` are untouched.
- `ErrorRouter.dispatch` and `redact`, `ErrorMappings`, `MappingsConstantNameDedup`
  are unused on the Phase 1 path (no error channel until Phase 2).
- The synthesized payload's `kvotesporsmalPreutfylling` field on the runtime side is
  graphql-java's own list traversal over `Result<Record>`; no graphitron code reads
  the list itself.

### Tests

**Pipeline-tier** (`graphitron/src/test/java/no/sikt/graphitron/rewrite/`),
new test class `PassthroughPayloadPipelineTest`:

- `payload_singleListField_classifiesAsPassthroughPayloadType`: trigger fires; type
  registry holds a `PassthroughPayloadType` with the data field resolved.
- `payload_singleListField_mutationFieldCarriesPassthroughProjectedListArm`: the
  classified `MutationInsertTableField`'s `rex` is `PassthroughProjectedList`.
- `payload_singleListField_dataFieldClassifiesAsIdentityPassthrough`: the payload's
  one field lands as `IdentityPassthroughField`.
- `payload_withErrorsField_doesNotLift`: a two-field payload (data + errors) stays
  as `PlainObjectType`; the mutation field's return type still rejects (Phase 2).
- `payload_withInterfaceField_doesNotLift`: off-shape field rejects the lift.
- `payload_notReachableFromMutation_doesNotLift`: lift is conservative.
- `payload_withMultipleDataFields_doesNotLift`: arity rule.

**Execution-tier** (`graphitron/src/test/java/no/sikt/graphitron/rewrite/sakila/`),
new sakila fixture `PassthroughPayloadInsertTest`:

- SDL: `type ActorInsertPayload { actor: [Actor!] }` over the existing sakila
  `@table` Actor type.
- Mutation: `insertActor(input: ActorInsertInput!): ActorInsertPayload @mutation(...)`.
- Driver: invoke against the testcontainer; assert response shape; assert the
  inserted row is visible in the payload's `actor` list and queryable on follow-up
  reads.

**Audit-tier**: no new keys; `@LoadBearingClassifierCheck` /
`@DependsOnClassifierCheck` plumbing is unchanged. The Phase 1 path has no
emitter-side invariant that depends on a classifier guarantee beyond what the
existing `ProjectedList` arm already documents.

### Open questions for Phase 1

1. **Single-cardinality data slots.** The headline case is list-cardinality
   (`[KvotesporsmalPreutfylling!]`). Should Phase 1 also admit
   `kvotesporsmalPreutfylling: KvotesporsmalPreutfylling` (single)? Recommendation:
   yes; the `PassthroughProjectedSingle` arm is a one-line variant of
   `PassthroughProjectedList` and excluding it forces consumers into list payloads
   for INSERT-returning-one-row cases. Confirm.
2. **DML kind coverage.** INSERT is the user's blocker; UPDATE / DELETE / UPSERT all
   compose with the same emitter shape. Recommendation: include all four in Phase 1
   so the matrix doesn't ship piecemeal. Confirm.

## Phase 2: errors-slot integration via localContext (sketch)

When the SDL payload declares two fields (one data, one errors-shaped), the trigger
admits it, and the type carries both `dataField` and `errorsField`. The DML/service
emitter wraps its result in
`DataFetcherResult.<List<Record>>newResult().data(rows).localContext(errors).build()`
on the success arm and the catch-arm equivalent on failure. The errors field's
emitted fetcher reads `env.getLocalContext()` directly, replacing today's
`PropertyDataFetcher.fetching(name)` form at FetcherEmitter.java:62-69 with the
lightweight `($T env) -> env.getLocalContext()` shape. No graphitron-runtime envelope
class is needed; graphql-java's `DataFetcherResult.localContext` propagates to child
environments.

`ErrorMappings` and `ErrorRouter`'s mapping-table half (which throwables map to
which `@error` types, dedup of mapping-list constants) are reused unchanged. The
factory-lambda half (`payloadFactoryLambda`, which constructs an authored
`PayloadClass` with an errors slot at a canonical-ctor index) does not apply on the
synthesized path; the catch arm produces a `DataFetcherResult` with empty data and
populated `localContext` directly.

Spec questions deferred to Phase 2:

- Errors-slot element type (Object vs typed union) and its surface implications.
- Catch-arm `data(...)` value shape: typed-empty `Result<Record>` vs null.
- Whether the `@error` types' dispatch table is rebuilt or the existing
  authored-carrier dispatch table is reused as-is.

## Phase 3: domain-record returns from `@service` (sketch)

For `@service` mutations, allow the service method to return its domain object
directly (e.g. `LagreOgBeregnResultat(List<KvoteSporsmalSvar> svar)`) and have
graphitron emit identity passthrough on the SDL data field. The existing
`ChildField.RecordField` arm already reads property accessors off
`env.getSource()`; classifying the SDL data field on a passthrough payload as
`RecordField` against the service's reflected return type is enough.

Spec questions deferred to Phase 3:

- Component-to-field name matching rules and rejection messages.
- Wrapper conventions when the domain record itself is shared across multiple
  payload types.
- Whether `ResultAssembly` collapses entirely (Phase 3 obsoletes its single-slot
  walk) or stays for authored carriers.

## Non-goals

- **Synthesizing Java payload classes.** Explicitly rejected: the redirect away
  from this is the entire premise of R75. No `<outputPackage>.synthesized` package,
  no `SynthesizedPayloadClassGenerator`, no per-payload-type Java emission.
- **Setter-based errors injection.** Same rejection as before: violates immutability
  and would have graphitron mutate consumer-produced objects. Legacy's
  `payload.setErrors(...)` shape is not coming back.
- **Authored carriers with custom fields.** Anything beyond the trigger's admitted
  shape stays in the consumer's source tree as `@record(record: {className: ...})`,
  governed by R12's existing path.
- **Removing `@record` entirely from payload types.** The directive still has a job
  (declaring that the SDL type is record-shaped, distinguishing it from `@table`).
  Only the `className` argument becomes optional in the sense that omitting it is
  no longer a footgun: a `@record` without `className` lifts to
  `PassthroughPayloadType` on the same trigger as a no-`@record` Object.

## Success criteria

**Phase 1 (this Spec body):**

- The reproduction case at the top compiles and serves correctly through
  `mvn -f graphitron-rewrite/pom.xml install -Plocal-db`, with no `@record(className)`
  declaration on the SDL payload type and no synthesized Java class on disk.
- The `PassthroughPayloadPipelineTest` cases pass; the
  `PassthroughPayloadInsertTest` execute-tier driver passes against the sakila
  testcontainer.
- Authored payloads with `@record(record: {className: ...})` are unaffected: same
  generation, same rejection messages, same fixture coverage as today (regression
  pin via the existing R12 fixtures).
- A SDL fixture taken verbatim from a legacy graphitron consumer (no
  `@record(className)` on the payload, single data slot, `@mutation(typeName: …)`)
  compiles cleanly on the rewrite.

**Phases 2 and 3:** success criteria sharpened in their respective Spec revision
passes.
