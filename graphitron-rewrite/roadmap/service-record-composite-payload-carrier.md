---
id: R329
title: "Re-admit @service carrier payloads with a record-composite data field (land R75 Phase 3)"
status: Spec
bucket: architecture
priority: 7
theme: mutations-errors
depends-on: []
created: 2026-06-18
last-updated: 2026-06-22
---

# Re-admit @service carrier payloads with a record-composite data field (land R75 Phase 3)

An `@service` mutation whose method returns a list (or single) of a
consumer-authored composite record — a POJO that bundles several jOOQ
table records, e.g. one `UtdanningsspesifikasjonRecord` plus a
`List<UtdanningsmulighetRecord>` — cannot be expressed as a payload today.
The natural SDL is a two-level carrier: a payload whose data field is a
list of an intermediate result type, that result type's children being
`@table`-typed and `@field`-mapped onto the composite's components:

```graphql
type OpprettXOgYPayload {
    results: [OpprettXOgYResult]          # data field: a record composite, NOT @table
    errors: [OpprettXOgYError!]
}
type OpprettXOgYResult {                  # reflection-bound to the @service return element
    x: X! @field(name: "xRecord")         # X is @table-backed
    ys: [Y] @field(name: "yRecords")      # Y is @table-backed
}
type Mutation {
    opprettXOgY(input: [OpprettXOgYInput!]!): OpprettXOgYPayload
        @service(service: {className: "...", method: "opprettXOgY"})
}
```

This fails the build with a dangling-type-reference author error
(`GraphitronSchemaBuilder.rejectDanglingTypeReferences`): the payload
classifies on neither axis. The result-axis binding
(`RecordBindingResolver.groundProducerResult`) skips the wrapper under the
**cardinality-match guard** — a single-object SDL field produced by a
`List` return is a list carrier whose collection feeds an *inner* list
field (`results`), not the wrapper. The carrier axis
(`groundServicePayloadBinding`) then refuses to ground because it admits
only a payload whose single data field is a **`@table`-typed** object (or
an `[ID] @nodeId(typeName:)` field resolving to a `@table` type); a data
field whose element is a reflection-bound record composite is not
recognized. So the payload binds nowhere, is dropped, and the field
referencing it dangles. (`@record` on the result type is irrelevant — R96
made backing reflection-only; the directive is an ignored hint.)

This shape *was* briefly supported. R75 Phase 2 admitted record-element
data on `@service` mutations (`PojoResultType.NoBacking` carrier promotion
+ `ChildField.SingleRecordIdentityField` identity-passthrough), and R75
Phase 3 was scoped to cover compound `@service` passthrough payloads —
exactly this case — where the consumer constructs the result directly
(see `compound-entity-mutations.md`, R122, "Out of scope" → "Compound on
`@service` mutations"). Phase 3 was never implemented, and R276 then
retired the substrate it would have built on: `PojoResultType.NoBacking`
and `PlainObjectType` are deleted, and carriers now must bind to a
`JooqTableRecordType`. The result is an orphaned capability — R75 Phase 3
has no live tracking item, R122 explicitly defers `@service` compound
payloads to it, and R308 covers only list-*arrival* batching of a carrier
(`@service: [Payload]`), not a record-composite data field.

Note the half-open seam: `BuildContext.scanStructuralPayload` already
`Admit`s a `RecordElement` data field (the `lookAheadVerdict` →
`ResultType` arm), but no binding producer grounds a `tableRef` for that
admission in the `@service` family, so `carrierTableBinding` returns
`null` regardless. The fix lands R75 Phase 3 on top of the post-R276
reflection-driven binding model: ground a carrier (or producer-result)
binding for a payload whose data field is a producer-returned record
composite — single or list arrival — so the payload classifies and its
inner `@field`-mapped `@table` children resolve through the record-backed
path. Surfaced by the utdanningsregisteret Graphitron 10 upgrade
(`opprettUtdanningsspesifikasjonOgUtdanningsmulighet`).

---

## Design

R329 splits into a **binding side** and an **emit side** that pull in opposite
directions on how much new structure is warranted, and that asymmetry is the
spec's core claim. The producer-to-SDL binding is uniform (reflection observes
one class, so no new binding arm), while the payload's data-field emission may
fork on a dimensional verdict no existing leaf carries. The cleanest landing is
**zero new model types** (a pure binding plus cardinality change); the spec
commits to writing the data field's classification row first and adds a leaf
only if that row is genuinely distinct.

### Binding side: reuse the result-axis `RootService`; add no carrier binding

The composite already grounds on the result axis. `peelReturnElement(List<
CompositePojo>)` yields `CompositePojo` (`RecordBindingResolver.java:743`),
`groundServiceField` already feeds that element to `groundProducerResult`
(`:239-242`), and the per-type fold sets `resultMemo[OpprettXOgYResult] =
CompositePojo` (`:663-689`), classifying the intermediate result type as
`PojoResultType.Backed` / `JavaRecordType`. Its `@field`-mapped `@table`
children then resolve through the existing record-backed accessor path, with the
grandchildren as `JooqTableRecordType` via `RootTable`. No new `ProducerBinding`
arm is needed for the type binding.

The only blocker is the cardinality-match guard at `groundProducerResult`
(`:302-303`): `sdlIsList != reflectedIsMulti` rejects the binding because the
payload field is single while the method returns `List`. The guard compares the
wrong two levels for a two-level carrier. The mismatch is not between the payload
and the return; it is between the payload's data field (`results:
[OpprettXOgYResult]`, a list) and the return (`List<CompositePojo>`, multi),
which agree. The fix re-levels the comparison to the data-field element.

This settles the `ServiceEmitted`-sibling question the problem statement raises:
a new class-bound carrier binding is the weaker model. `ServiceEmitted` carries a
non-null `TableRef` and a compact-constructor invariant requiring
`reflectedClass.getName().equals(tableRef.recordClass().reflectionName())`
(`ProducerBinding.java:185-197`). A composite POJO has no jOOQ record identity
and no single `TableRef`, so expressing it through `ServiceEmitted` would mean
nulling the `TableRef` and deleting that load-bearing invariant: the exact "enum
value implies these fields are non-null" smell that *Sealed hierarchies over
enums* warns against. If a dedicated observation is later needed to carry the
payload-to-data-field-to-producer linkage for the emit leaf, it is a distinct
record carrying a bare `Class<?>` plus the data-field and return cardinalities,
named for what it carries that `ServiceEmitted` cannot (no `TableRef`, two
cardinality levels), not a generalization.

### Cardinality: one sealed verdict, one source of truth

The current guard's javadoc (`:285-288`) describes the two-level shape as a
reason to reject ("a single-object SDL field produced by a collection return is a
list carrier whose collection feeds an inner list field, not the wrapper"). R329
admits exactly that shape, so the comparison cannot be duplicated with the
opposite conclusion; that would leave two predicates that must stay complementary
by hope and desynchronize into either a dangle (today's bug) or a double-bind.
Lift the decision into one classifier step that, given a producer field, computes
which SDL level the element binds to and the cardinality match at that level,
returning a builder-internal sealed result:

```java
sealed interface ProducerBindLevel
    permits BindsWrapper, BindsDataFieldElement, NoBind {
    record BindsWrapper() implements ProducerBindLevel {}
    record BindsDataFieldElement(GraphQLFieldDefinition dataField)
        implements ProducerBindLevel {}
    record NoBind() implements ProducerBindLevel {}
}
```

Both the single-level path and the two-level path read this one value; the
result-axis observation and the carrier-recognition verdict project from it
rather than from two independent comparisons. This is the `ArgumentRef`-style
"classify the multi-target binding once, project into each consumer" pattern.

### Carrier recognition: close the half-open seam so the payload classifies

Distinct from the type binding, the payload itself must classify or it dangles
(today's failure). The seam is already half-open: `BuildContext.
scanStructuralPayload` already `Admit`s a `RecordElement` data field, but
`carrierTableBinding` returns `null` because no producer grounds a `tableRef` for
it (`TypeBuilder`, `BuildContext`). R329 closes it: recognize the two-level
`@service` carrier (a payload with a single non-`@table` object data field whose
element binds to the producer's return, plus an optional errors field) so the
payload classifies and its data field reaches the emit path below.

### Emit side: one new `ChildField` leaf only if its verdict is distinct

The two existing carrier data fields (DML and single-level `@service`) collapse
into `RecordTableField` via `buildPayloadCarrierRecordTableField` (a PK-keyed
re-fetch) because their data field is itself `@table`-typed
(`TableBoundReturnType`). The composite case differs on the axes the model
already separates. Before declaring a leaf, write its classification row next to
`RecordField`, `NestingField`, and the single-level carrier's `RecordTableField`:

```text
field             sourceShape  target                domainReturnType
RecordTableField  Record       wrap(Table)           Record(table)
RecordField       Record       listOrSingle(Field)   Plain(Object)
NestingField      Table        wrap(Table)           Plain(jOOQ Record)
R329 data field   Record       listOrSingle(Record)  Plain(composite class)
```

The composite data field's element is a record, so its `target()` is `Record`
(not `Table`), its `domainReturnType()` is `Plain(ClassName.bestGuess(
fqClassName))` for the composite class (not `Record(table)`, there being no
single table), and it carries no DataLoader. The leaf is justified iff this row
is distinct from all three; the present read is that it is (a Record-shaped,
list-or-single, producer-projected read with an `OUTCOME_SUCCESS` envelope). If
the row instead collapses onto `RecordField` or `NestingField` over the composite
parent, no leaf is added and R329 reduces to a pure binding plus cardinality
change.

The errors transport is not new. A data field projecting `Outcome.Success.
value()` reuses the existing `SourceEnvelope.OUTCOME_SUCCESS` axis (`SourceKey`)
and the existing `Transport.WrapperArm` (`ChildField.java:1020-1029`, selected by
`transportForParent`). The composite's own `@table` children stay plain
`RecordTableField`; the only candidate new leaf is the payload's data field.

### Validator mirror: name the near-miss rejections

Per *Validator mirrors classifier invariants*, each newly admitted shape needs a
matching validate-time rejection for its near-misses, or an unimplemented
sub-case throws at emit or silently dangles. R329 adds typed `Rejection`s at
classify time, restated in `GraphitronSchemaValidator` where they imply an
unimplemented generator arm:

- The composite element does not bind to the producer's reflected return element
  (mismatched producer; the analog of the `ServiceEmitted` table-mismatch message
  in `FieldBuilder`).
- The composite element binds, but a `@field`-mapped child is neither
  `@table`-backed nor a resolvable accessor on the composite.
- Cardinality mismatch at the re-leveled comparison (data-field list-ness versus
  return multi-ness).

The existing DML-path advice "Use a @service mutation for record-element
carriers" (`FieldBuilder`) becomes precise rather than a trap once the `@service`
path admits the shape.

## Tests

R281 corpus pins the dimensional verdict, not the emitted body:

- One `ClassifiedCorpus` entry pinning the payload's classification (it no longer
  dangles) and the data field's `(source, operation, target)` verdict.
- If a new leaf lands, a `@classified` coordinate on it so `VariantCoverageTest`
  covers it, plus its placement in `TypeFetcherGenerator`'s four-way dispatch
  partition (which arm is the implementer's call) so
  `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` stays
  exhaustive over the `GraphitronField` leaves.
- Validator-tier coverage, in the per-shape `*ValidationTest` style, for each of
  the three near-miss rejections the validator-mirror section adds: mismatched
  producer, a `@field`-mapped child that is neither `@table`-backed nor a
  resolvable accessor on the composite, and the re-leveled cardinality mismatch.
  The `ClassifiedCorpus` pins positive classification only; build-time rejections
  are asserted at the validator tier (as `ConstructorFieldValidationTest` does),
  so the admitted shape and its near-misses are pinned together rather than the
  rejections riding as unpinned prose.
- One execution-tier round-trip in `GraphQLQueryTest` for the `List<composite>`
  projection and the error arm rendering `data: null`.
- The sakila-example cross-module compile is the type-correctness backstop for
  the composite accessor chain against real jOOQ records.
- No generated-body string assertions; the `OUTCOME_SUCCESS` unwrap and the
  `loadMany` projection are routed to the compile and execute tiers.

The driving shape (`opprettUtdanningsspesifikasjonOgUtdanningsmulighet`) reduces
to a Sakila-catalog analog for the fixture, since the corpus and codegen fixtures
run against the test catalog. A natural analog: a payload bundling one
`FilmRecord` plus a `List<ActorRecord>`, returned by a fixture `@service` method.

## Implementation sites

- `RecordBindingResolver`: re-level the cardinality decision behind a single
  `ProducerBindLevel` verdict; ground the intermediate result type on the
  existing result axis at the data-field element level.
- `BuildContext.scanStructuralPayload` / `TypeBuilder.carrierTableBinding`: close
  the `RecordElement`-admits-but-grounds-nothing seam so the payload classifies.
- `FieldBuilder`: the data-field classification (collapse onto `RecordField` /
  `NestingField` if the row matches, else the new leaf) and the near-miss
  rejections.
- `model/ChildField.java`: the new leaf, only if its dimensional row is distinct,
  reusing `Transport.WrapperArm`.
- `GraphitronSchemaValidator`: restate the classify-time rejections that imply
  unimplemented generator arms.
- Corpus and fixtures: the Sakila analog and the `ClassifiedCorpus`, the
  validator-tier rejection fixtures, plus the execution entries above.

## Open question to settle in implementation

Whether the batch-keyed `@service` shape (`Map<Key, List<CompositePojo>>`)
composes: `peelReturnElement` peels the `Map` value then the `List` element to
`CompositePojo` (`:757-760`) and `isMultiCardinalityReturn` mirrors it
(`:798-806`), so it should fall out of the re-leveled cardinality decision. The
spec specifies the re-leveling against both the plain `List` and the `Map`-value
cases; if the batch shape proves out of scope it is explicitly deferred rather
than half-wired.

## Non-goals

- No new errors transport; the `OUTCOME_SUCCESS` / `WrapperArm` envelope is
  reused.
- No new `ProducerBinding` arm for the type binding; `RootService` on the result
  axis is the home.
- No generalization of `ServiceEmitted` to a nullable `TableRef`.
- Collapsing `JavaRecordType` and `PojoResultType.Backed` on the result axis is a
  separate question, not part of R329.
