---
id: R75
title: "Plain payload types via identity passthrough"
status: Spec
bucket: architecture
priority: 7
theme: mutations-errors
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

## Independence from R12

R12 has shipped the authored-carrier path on trunk: canonical-ctor reflection,
`ErrorChannel`, `PayloadAssembly`, `ResultAssembly`, error-routing dispatch,
default-literal capture, `ErrorMappings`, `ErrorRouter`, `MappingsConstantNameDedup`.
That path continues to govern any payload declared with `@record(record: {className: ...})`.

R75 has no remaining dependency on R12. Phase 1 never reaches an error channel by
construction, so it doesn't touch `ErrorRouter.dispatch`, `payloadFactoryLambda`, or
canonical-ctor reflection. Phase 2 reuses R12's `ErrorMappings` mapping-table half
(*which throwables map to which `@error` types*), but every piece of that machinery
already lives on trunk. R12's remaining `Ready` work (rule-6 relaxation, accessor
reflection check, `extensions.constraint`, validator-integration fixture) is all
`@error`-side and gates none of R75's phases.

The two paths split cleanly: authored carriers traffic in canonical-ctor slot indices
(`PayloadAssembly.rowSlotIndex`, `ErrorChannel.errorsSlotIndex`); the no-authored-class
path has no Java class to reflect on, so none of those slot indices apply. R75 is
purely additive across all three phases.

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
out of `PlainObjectType` (or out of `PojoResultType(_, _, null)` for the
`@record`-without-`className` case, which `TypeBuilder.buildResultType` produces today
at lines 530, 533, 542) when *all* hold:

1. The SDL Object type has no `@table` directive and either no `@record` directive
   or `@record` with no `record.className` argument.
2. It is the return type of at least one `@mutation(typeName: …)` field.
3. It declares exactly one SDL field.
4. That field's element type satisfies the **passthrough rule** below.

The lift target is the same `PassthroughPayloadType` arm in both source cases (no
`@record`, or `@record` without `className`); the residual difference between the two
is purely SDL-level vocabulary, not a type-classification distinction worth carrying.

What `PassthroughPayloadType` carries that no `ResultType` arm can: a
pre-resolved single-data-field anchor for the field-classifier to dispatch on, with
no Java backing class and no `fqClassName`. The `ResultType` arms all carry an
`fqClassName` (possibly null but always nominally present) and route through R12's
authored-carrier reflection path; `PassthroughPayloadType` skips that path entirely.

Failing the trigger (multiple fields, off-shape return) leaves the type at its
pre-lift classification with today's behaviour: the mutation field's return type
falls through `validateReturnType` and rejects. Phase 2 admits the two-field "data
+ errors" shape; Phase 1 deliberately stays on the one-field case to isolate the
identity-passthrough mechanism from `localContext` plumbing.

Non-mutation reads of a lifted payload type (a query field or `@externalField`
declaring it as a return) are not addressed in Phase 1: the field-classifier sees
a `PassthroughPayloadType` parent on the data field and dispatches normally, but
the parent-side fetcher emission (the field returning `PassthroughPayloadType`)
has no admit at the query / external-field classifier. Such cases continue to
classify as `UnclassifiedField`. The lift's mutation-reachability gate is positive
("admitted at @mutation slots"), not exclusive.

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
applies the trigger, and produces `PassthroughPayloadType` for matches. Non-matches
keep their pre-lift classification (`PlainObjectType` for the no-`@record` case,
`PojoResultType(_, _, null)` for `@record`-without-`className`). The pass needs
SDL-level visibility into mutation return types, which `ctx.schema` already
provides; nothing crosses the reflection boundary.

The lift produces `PassthroughPayloadType(name, location, dataField)` where
`dataField` is an SDL-level descriptor (field name, element-type SDL name, wrapper)
rather than a fully-classified field. The full classification of the data field
(table resolution, etc.) happens at the existing field-classification stage,
producing the narrow-typed `IdentityPassthroughField` described next.

### Field classification

`PassthroughPayloadType` becomes a fifth dispatch arm in
`FieldBuilder.classifyField` (alongside `RootType`, `TableBackedType`, `ResultType`,
`ErrorType`). The single field on the type classifies as a new permit
`ChildField.IdentityPassthroughField(parentTypeName, name, location, returnType)`
where `returnType : ReturnTypeRef.TableBoundReturnType` directly (not the broad
`ReturnTypeRef`). The trigger's passthrough rule guarantees the table-bound shape;
narrowing the component type pushes that guarantee into the type system, so the
emitter reads the data field's table off `returnType.table()` without an
`instanceof` guard. Phase 3's `@record`-element extension introduces a sibling
permit (`IdentityPassthroughRecordField`) with a `ResultReturnType`-typed slot,
not a widening of this one.

The arm's only job is to declare itself; `FetcherEmitter.dataFetcherValue` emits
`($T env) -> env.getSource()` for it, identical to the existing `ConstructorField`
and `NestingField` cases at FetcherEmitter.java:56-60.

A new `ChildField` permit is preferred over reusing `ConstructorField` or
`NestingField`: `ConstructorField` is `@table` parent → `@record` child;
`NestingField` carries a `TableBoundReturnType` and a recursive `nestedFields` list.
Neither shape fits a payload-typed parent passing through to a `@table` child, and
overloading either would muddy `classifyChildFieldOnTableType`'s existing arms.

### Mutation return-type admission

A new `ReturnTypeRef.PassthroughPayloadReturnType(String payloadTypeName,
ReturnTypeRef.TableBoundReturnType dataReturn)` arm wraps the data field's
already-classified table-bound return. `BuildContext.resolveReturnType` produces it
when `ctx.types.get(name) instanceof PassthroughPayloadType`. The DML emitter (and
`validateReturnType`) reaches the data field's table via `dataReturn.table()` and
its element type name via `dataReturn.returnTypeName()`; no separate `dataFieldTable`
slot is needed, and there's only one source of truth for "the table this payload
projects from."

`MutationInputResolver.validateReturnType` adds a `PassthroughPayloadReturnType` arm
to the existing four-way switch. The arm admits the case (returns null) when the
data field's wrapper agrees with `validateReturnType`'s existing list/single rules
for the underlying `@table` type (Invariants #14, #15). Bulk-input + single-payload
combinations defer to the same rejection path as today's `TableBoundReturnType`.

The validator's *negative* messages also tighten: today's "return type 'X' is not
yet supported; use ID or a @table type" becomes a per-trigger-failure message when
the type lands as `PlainObjectType` or `PojoResultType(null)` from a `@mutation`
slot, naming the trigger's positive criteria ("must declare exactly one field
whose element is `@table`-mapped, or author a Java carrier via `@record(record:
{className: ...})`"). Failures of conditions #3 / #4 surface to the author with
diagnostics that mirror the trigger's admission rules, keeping
"validator-mirrors-classifier" honest.

### DML emitter

The existing `DmlReturnExpression.ProjectedSingle` and `ProjectedList` arms gain a
`TableRef table` slot. Today both arms carry only `String returnTypeName` (the SDL
type whose `<TypeName>Type.$fields` projection runs); the emitter recovers the
underlying table from a separate `tableRef` parameter the caller threads through.
That parameter today is always the DML target table, which equals the SDL field's
own `@table` type by Invariant #14; carrying `table` on the arm makes that fact
explicit and lets the same arm serve passthrough payloads, where the projection
target table happens to also equal the DML target (the data field's `@table` is
the same table the DML mutates; the classifier rejects misalignment).

The classifier picks the slot once:

- Direct `TableBoundReturnType` (today): `projectionTypeName` = SDL field's type
  name, `table` = SDL field's `@table`.
- `PassthroughPayloadReturnType` (new): `projectionTypeName` =
  `dataReturn.returnTypeName()`, `table` = `dataReturn.table()`.

Both feed the existing `INSERT/UPDATE/UPSERT/DELETE...RETURNING $fields(sel, table,
env).fetch()` (or `.fetchOne()`) emitter unchanged. The mutation field carrier
(`MutationInsertTableField`, etc.) already carries `DmlReturnExpression rex` via
the classifier; the new return-type arm flips at classify time, not emit time.

The single-vs-list carrier dispatch stays in the existing variant choice
(`ProjectedSingle` vs `ProjectedList`), per the existing principle in the
`DmlReturnExpression` Javadoc.

The data field's identity passthrough makes graphql-java traverse the resulting
`Result<Record>` through the existing `@table` per-field fetchers without further
intervention.

### Load-bearing classifier check

The collapsed `Projected*` arms now carry an emitter-side invariant: when the arm
is reached on a `PassthroughPayloadReturnType`, the `dataReturn.table()` must equal
the DML target table (the table being INSERT/UPDATE/etc.'d). Misalignment cannot
arise from a well-formed SDL, but a relaxation of the trigger that admitted a data
field anchored on a different table would silently break the
`INSERT...RETURNING $fields(...)` projection (jOOQ rejects RETURNING on columns
from a different table). This is the kind of guarantee `@LoadBearingClassifierCheck`
exists to pin: the classifier emits a `passthrough-payload.data-table-equals-dml-target`
check, the DML emitter's `Projected*` arm wears the matching `@DependsOnClassifierCheck`,
and `LoadBearingGuaranteeAuditTest` enforces the pairing.

### What does *not* change

- No new generator class, no synthesized package, no Java payload class on disk.
- `PayloadAssembly`, `ResultAssembly`, `ErrorChannel` keep their current shape and
  scope. They remain authored-carrier-only.
- `FieldBuilder.findCanonicalCtor`, `resolveDmlPayloadAssembly`,
  `resolveErrorChannel`, `resolveServiceResultAssembly` are untouched.
- `ErrorRouter.dispatch` and `redact`, `ErrorMappings`, `MappingsConstantNameDedup`
  are unused on the Phase 1 path (no error channel until Phase 2).
- The runtime traversal of the DML's `Result<Record>` is graphql-java's own list
  iteration through the existing `@table` per-field fetchers; no graphitron code
  reads the list itself.

### Out of scope for Phase 1

- **Service-backed mutations returning passthrough payloads.** A `@service`
  mutation whose return type is a `PassthroughPayloadType` is admitted by
  `validateReturnType` (the new arm makes no DML/service distinction) but the
  service-fetcher emitter has no passthrough arm. Phase 1 keeps such cases
  `UnclassifiedField` via the existing service-fetcher path. Phase 3 ("domain-record
  returns from `@service`") covers the service-side admission.

### Tests

**Pipeline-tier** (`graphitron/src/test/java/no/sikt/graphitron/rewrite/`),
new test class `PassthroughPayloadPipelineTest`:

- `payload_singleListField_classifiesAsPassthroughPayloadType`: trigger fires; type
  registry holds a `PassthroughPayloadType` with the data field resolved.
- `payload_singleListField_mutationFieldCarriesProjectedListWithDataTable`: the
  classified `MutationInsertTableField`'s `rex` is the enriched `ProjectedList`,
  with `table` equal to the data field's `@table`.
- `payload_singleListField_dataFieldClassifiesAsIdentityPassthrough`: the payload's
  one field lands as `IdentityPassthroughField` with a narrow
  `TableBoundReturnType` slot.
- `payload_atRecordWithNullClassName_alsoLifts`: the trigger admits both no-`@record`
  and `@record(record: {})`-without-`className` source shapes onto the same
  `PassthroughPayloadType` arm.
- `payload_withErrorsField_doesNotLift`: a two-field payload (data + errors) stays
  at its pre-lift classification; the mutation field's return type still rejects
  (Phase 2).
- `payload_withInterfaceField_doesNotLift`: off-shape element type rejects the lift.
- `payload_notReachableFromMutation_doesNotLift`: lift is conservative.
- `payload_withMultipleDataFields_doesNotLift`: arity rule.
- `payload_returnedFromQueryField_unclassifiedField`: non-mutation use of a lifted
  payload type rejects on the parent side.
- `validateReturnType_namesTriggerCriteria`: the rejection message for an un-lifted
  `PlainObjectType` mutation return enumerates the trigger's positive criteria
  (validator-mirrors-classifier).

**Execution-tier** (`graphitron/src/test/java/no/sikt/graphitron/rewrite/sakila/`),
new sakila fixture `PassthroughPayloadInsertTest`:

- SDL: `type ActorInsertPayload { actor: [Actor!] }` over the existing sakila
  `@table` Actor type.
- Mutation: `insertActor(input: ActorInsertInput!): ActorInsertPayload @mutation(...)`.
- Driver: invoke against the testcontainer; assert response shape; assert the
  inserted row is visible in the payload's `actor` list and queryable on follow-up
  reads.

**Audit-tier**: one new load-bearing key,
`passthrough-payload.data-table-equals-dml-target`. The classifier emits the
`@LoadBearingClassifierCheck` at the field-construction site that builds a
`PassthroughPayloadReturnType`-anchored `ProjectedList`/`ProjectedSingle`; the
emitter sites in `buildDmlFetcher` (and the four kind-specific entry points) wear
the matching `@DependsOnClassifierCheck`. `LoadBearingGuaranteeAuditTest` enforces
the pairing automatically; no new audit-test method is needed.

### Open questions for Phase 1

1. **Single-cardinality data slots.** The headline case is list-cardinality
   (`[KvotesporsmalPreutfylling!]`). Should Phase 1 also admit
   `kvotesporsmalPreutfylling: KvotesporsmalPreutfylling` (single)? Recommendation:
   yes; the `ProjectedSingle` arm with its new `TableRef` slot covers it as a
   one-line classifier variant, and excluding it forces consumers into list payloads
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
