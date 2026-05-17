---
id: R96
title: "Drop output-DTO construction; traverse producer returns directly"
status: Spec
bucket: cleanup
priority: 6
theme: model-cleanup
depends-on: [emit-input-records]
---

# Drop output-DTO construction; traverse producer returns directly

`@record` (declared at `directives.graphqls:290` as
`directive @record(record: ExternalCodeReference) on OBJECT |
INPUT_OBJECT`) tells graphitron the developer-supplied Java class
backing an SDL type. The premise it carries, that graphitron needs
to *construct* an output DTO instance to feed into graphql-java's
traversal, is the fault line this item closes. graphql-java doesn't
need a constructed carrier; it traverses whatever the producer
(`@service` method, `@table` query, hand-rolled aggregator) returned,
by accessor name. The whole `*Backed` arm of the result-type
hierarchy exists to model a step graphitron shouldn't be doing.

R75, R141, R158, and R159 already proved the shape on the
carrier-payload data field. This item generalises that proof to all
non-polymorphic outputs, collapses the `*Backed` result-type
variants, and narrows `@record` to the one surface that still needs
a typed classifier signal (polymorphic / interface dispatch,
deferred to a sibling backlog item).

## Where we are now

The carrier-payload data field already lives in the
producer-traversal world:

- **R75 Phase 1** introduced `PojoResultType.NoBacking` and
  `ChildField.SingleRecordTableField`. DML payloads carry no
  constructed class; the data field's source is the DML's
  `Result<Record>` directly.
- **R75 Phase 2** added `DataElement.Record` and
  `ChildField.SingleRecordIdentityField`. `@service` mutations whose
  return IS the data element flow through without re-construction.
- **R141** keeps the PK-keyed-map walk on the DML side. The data
  field reads from the DML's `RETURNING` shape, not a constructed
  payload.
- **R158** widened the single-record carrier's producer permits to
  admit `@service` returning `XRecord` / `List<XRecord>`
  (`Wrap.TableRecord(target.recordClass())` on
  `SourceKey.Reader.ResultRowWalk`). The fetcher casts
  `env.getSource()` to the typed jOOQ record and reads PKs through
  `record.get(field)`; no graphitron-constructed DTO between service
  and graphql-java.
- **R159** added the `$source` sigil on `@field(name:)`: the
  explicit, name-decoupled binding between the carrier-payload data
  field and the producer's return.

Outside the carrier-payload data field, the world is still
DTO-constructive. `@record` declarations on `FilmCard`, `FilmDetails`,
`FilmLookupPayload`, `FilmReviewPayload`, `FilmCardWrapper`, etc.
drive `TypeBuilder.buildResultType` (`TypeBuilder.java:696`) to
classify them as `PojoResultType.Backed`, `JavaRecordType`,
`JooqRecordType`, or `JooqTableRecordType`. Each `*Backed` variant
encodes "graphitron knows the class so it can construct one." The
generator then emits fetchers that cast the parent source to that
class and call typed accessors.

The fault line: every one of those `*Backed` variants exists
*because* `@record` exists. Remove the directive and the variants
have no producer.

## Where we want to go

One shape for outputs: `PojoResultType.NoBacking` (or its successor;
see below), produced from any SDL Object that isn't a table-backed
type or a polymorphic-dispatch root. The generator's fetchers read
from `env.getSource()` without typing it, the same way
`FetcherEmitter.buildSingleRecordTableFetcherValue`'s
`Wrap.TableRecord` arm does today: by name, against whatever the
producer handed back. graphql-java's default `PropertyDataFetcher`
handles the trivial cases; graphitron's generated fetchers handle
the non-trivial cases (errors, projection, batching, nested-table
joins) without caring about the parent's declared class.

Concretely:

1. **`PojoResultType.Backed` retires**, along with `JavaRecordType`,
   `JooqRecordType`, and `JooqTableRecordType` on the result-type
   side. `PojoResultType` collapses to a single shape; the sealed
   split goes away. A rename is a candidate during implementation
   (since `NoBacking` ceases to be a distinguishing trait), but the
   spec is silent on which name wins.
2. **`@record` on `OBJECT` narrows to polymorphic-dispatch-only.**
   This is the one surface the producer's return type doesn't
   uniquely determine: a base-typed `@service` return whose runtime
   instance is one of several subtypes needs a `TypeResolver`-shaped
   signal. Deferred to a sibling backlog item.
3. **`@record` on `INPUT_OBJECT` retires** when R94 ships its
   per-input synthesised record (the input-side counterpart to
   `NoBacking`). Phase 1 here owns the directive-declaration
   narrowing and the validator rule.
4. **Misconfiguration class closes.** The "two sources of truth"
   shape (SDL declares one class, the producer's return is another)
   stops existing because graphitron stops reading the SDL-declared
   class on every surface except polymorphic dispatch.

## Per-category absorption

The eight surfaces the previous spec enumerated map onto the
existing producer-traversal pattern as follows. The
classifier-signal audit table from the previous spec is gone: there
is no replacement signal to wire up because the output
classification collapses to one shape that doesn't need a signal at
all.

|Surface today|Existing pattern that absorbs it|
|---|---|
|(1) DML payload type (`CreateFilmPayload`, `CreateFilmsPayload`)|R75 Phase 1 (`MutationDmlRecordField` + `SingleRecordTableField`). Already covered; the `@record` declarations on sakila fixtures are dead weight and just need removing.|
|(2) `@service`-returning payload (`FilmLookupPayload`, `FilmReviewPayload`, `SetterShapeFilmReviewPayload`)|R158-style: the `@service` method's reflected return IS the source. graphql-java traverses it by accessor name. No `*Backed` variant; no class introspected by graphitron.|
|(3) jOOQ-table-record wrap (`FilmCard`, `FilmDetails`, `FilmDetailsForMethod`)|Same R158 shape with the producer's return being a `JooqXRecord`. `record.get(field)` or property fetcher on the typed jOOQ record. The `@table` directive on the wrapped fields already says everything graphitron needs.|
|(4) Hand-rolled wrapper (`FilmCardWrapper`, `RecordExample`, `CustomerAddressSummary`)|Producer's accessor returns the wrapper instance; graphql-java traverses it via property fetcher. The wrapper's own fields point at sub-producers (`@externalField`, `@table`); no carrier class on graphitron's side.|
|(5) Polymorphic / interface dispatch|Deferred. `@record` declaration stays on this one surface; a sibling backlog item introduces a `TypeResolver`-keyed classifier signal and removes the declaration entirely.|
|(6) Cross-module backing|Vanishes with the rest. graphitron never had to introspect the class; it just routes `getSource()` through.|
|(7) Pojo / JavaRecord input classification|Covered by R94. `@record` on `INPUT_OBJECT` becomes dead weight when R94 emits its synthesised record.|
|(8) `@table + @record` shadow rule on inputs (`TypeBuilder.java:815-824`)|Vanishes when (7) vanishes.|

## Phasing

Two phases. Each is independently shippable.

### Phase 1: drop `@record` on input types (delivered by R94 Phase 2)

R94 emits a graphitron-internal record per SDL `input` type, which
makes `@record` on `INPUT_OBJECT` redundant. The input-side classifier
rework R94 needs touches the same `buildNonTableInputType` arm and
the same `@table + @record` shadow rule that a separate R96 Phase 1
would, so the input-side deprecation is bundled into R94 Phase 2
rather than maintained as a parallel work item. This section names
the surfaces and points at R94 for the canonical plan.

Surfaces R94 Phase 2 covers (see `emit-input-records.md` for the
authoritative line citations and acceptance criteria):

- The graphitron-internal record emitter and the seam it introduces.
- Removal of the `@record`-driven arm in
  `TypeBuilder.buildNonTableInputType` (currently line 887, body
  through ~line 922).
- Removal of `GraphitronType.JavaRecordInputType`,
  `JooqRecordInputType`, `JooqTableRecordInputType`, and the
  input-side `Pojo/Java-record/JavaPlain` classifier split.
- Narrowing the `@record` directive declaration at
  `directives.graphqls:290` from `on OBJECT | INPUT_OBJECT` to
  `on OBJECT`.
- Removal of the `@table + @record` shadow rule
  (`TypeBuilder.java:815-824` plus the warning and the changelog
  back-reference). The branch becomes unreachable once `INPUT_OBJECT`
  is off the directive's scope.
- A **validator rule** that rejects `@record` on `INPUT_OBJECT` at
  schema build time, fronted by a `@LoadBearingClassifierCheck` key
  so a future regression (someone re-adding the input scope) is
  caught by the validator that already mirrors classifier
  invariants.
- Migration of the input-side unit-test fixtures at
  `GraphitronSchemaBuilderTest.java:3443-3520` from happy-path
  classification to a rejection cluster.

R96 carries no independent Phase 1 work: R94 closes both items'
input-side surface in a single deliverable.

### Phase 2: output side (the main move)

Collapse `*Backed` result-type variants onto the
producer-traversal shape.

1. **Generator: emit fetchers without typing the parent.**
   `FetcherEmitter`'s arms that today cast `env.getSource()` to a
   `@record`-derived class switch to either graphql-java's default
   `PropertyDataFetcher` (for trivial property reads) or to typed
   reads against the *producer's* return shape, the same way the
   `Wrap.TableRecord` arm in `buildSingleRecordTableFetcherValue`
   reads through `record.get(field)`. Which read shape to emit
   comes from the producing field, not from the consumer type's
   `@record`.
2. **Classifier: collapse `TypeBuilder.buildResultType`.** The
   `DIR_RECORD` branch at `TypeBuilder.java:386` retires; every
   SDL Object that today picks `PojoResultType.Backed`,
   `JavaRecordType`, `JooqRecordType`, or `JooqTableRecordType`
   classifies as `PojoResultType.NoBacking`. The polymorphic case
   stays on whatever signal the sibling roadmap item lands.
3. **Model: retire dead variants.** `PojoResultType.Backed`,
   `JavaRecordType`, `JooqRecordType`, `JooqTableRecordType` lose
   all producers and delete. The sealed split on `PojoResultType`
   collapses.
4. **Direct consumers update.**
   - `BuildContext.passthroughCandidate` filter (around
     `BuildContext.java:480-533`): drops its
     `@record`-with-`className` exclusion. Either every
     `ResultType` becomes a passthrough candidate, or the filter
     retires entirely. Determined during implementation.
   - `ClassAccessorResolver` (`ClassAccessorResolver.java:86`):
     accessor-resolution assertions keyed on a now-deleted
     `*Backed` variant retire; the remaining accessor seams read
     producer return types, not consumer-type backing classes.
   - `ServiceDirectiveResolver` (`ServiceDirectiveResolver.java:332`):
     comment update only; the resolver was already on R75's
     `ResultType.Backed` derivation, which becomes the collapsed
     `PojoResultType` after step 3.
   - `SourceRowDirectiveResolver`
     (`SourceRowDirectiveResolver.java:33,448,465-470`):
     `@sourceRow` gating shifts from a `@record`-typed-parent test
     to a producer-shape test (the producer field's element class),
     the same source-of-truth shift the rest of this phase makes.
5. **Sakila SDL fixtures lose `@record`.** One commit per fixture,
   each verifying the fixture still compiles and executes against
   the producer-traversal generator:
   - `CreateFilmPayload` (`schema.graphqls:328`)
   - `CreateFilmsPayload` (`schema.graphqls:344`)
   - `CustomerAddressSummary` (`schema.graphqls:356`)
   - `FilmLookupPayload` (`schema.graphqls:391`)
   - `FilmReviewPayload` (`schema.graphqls:419`)
   - `SetterShapeFilmReviewPayload` (`schema.graphqls:427`)
   - `FilmCard` (`schema.graphqls:558`)
   - `FilmDetailsForMethod` (`schema.graphqls:571`)
   - `FilmCardWrapper` (`schema.graphqls:588`)
   - `RecordExample` (`schema.graphqls:602`)
   - `FilmDetails` (`schema.graphqls:632`)
6. **Directive narrows to polymorphic-dispatch-only.** Either a
   custom marker on the declaration or a docs note; exact mechanism
   deferred to the sibling polymorphic-classifier item.

Phase 2 lands as one item (not subdivided further). The fetcher
rewrite, classifier collapse, and variant deletion form one
coherent change; subdividing leaves the codebase in a "half the
outputs are constructed, half are traversed" state with no benefit.

### Housekeeping (folded into the relevant phase)

- LSP completion and diagnostics: `@record` is offered only on the
  polymorphic-dispatch surface (after Phase 2 step 6).
- `docs/manual/reference/directives/record.adoc` and any other
  user-facing pages naming `@record` as a general-purpose tool
  update to surface the narrowed scope.
- `changelog.md` carries one entry per phase, plus a reserved entry
  for the sibling polymorphic-classifier item's eventual removal of
  the declaration.

## Out of scope

- **Polymorphic / interface dispatch (category 5).** Removing
  `@record` from base-typed `@service` returns requires a typed
  classifier signal that doesn't exist today. R96 narrows the
  directive's scope but does not delete the declaration. The
  sibling backlog item *polymorphic-return classifier signal*
  introduces a `TypeResolver` registry (or equivalent) keyed by
  SDL type; once it ships, `@record` becomes a no-op on `OBJECT`
  and can be removed in a trailing follow-up.
- **Reintroducing "explicit type binding" under another name.** The
  whole point is that explicit type binding is unnecessary when the
  producer's return tells graphitron everything. Future cases that
  can't be served by the producer's return surface as
  `UnclassifiedField` and earn their own classifier signal, not a
  re-introduction of `@record`-shaped declarations.
- **Consumer API changes.** Developer-supplied payload classes
  (`FilmReviewPayload`, `FilmCard`, etc.) keep their shape and stay
  on disk; the directive goes away, not the classes. The service
  methods that return them are unchanged.

## Tests

- **Phase 1 (input side).** Owned by R94 Phase 2. The validator-rule
  test against the migrated `GraphitronSchemaBuilderTest.java:3443-3520`
  cluster (now positive-rejection cases) plus a negative control on
  `@record`-on-`OBJECT` ships with R94; see
  `emit-input-records.md` Tests.
- **Phase 2 (output side).** Per fixture:
  - Pipeline-tier: each migrated fixture classifies to
    `PojoResultType.NoBacking` (or its successor) and the
    producing field's `ChildField` permit is unchanged from
    pre-migration. Snapshot the pre/post classifier output for the
    eleven sakila fixtures.
  - Compile-tier (`graphitron-sakila-example`): generated fetchers
    still compile under `<release>17</release>` against the
    untyped-parent reads.
  - Execution-tier (`graphitron-sakila-service`): the affected
    queries return the same shape. Existing integration tests
    catch behaviour drift; no new fixtures needed for migrations
    that are behaviour-preserving.
  - Cross-cutting: a pipeline-tier test pinning "no SDL Object
    classifies as a `*Backed` variant once Phase 2 lands"
    prevents the deleted variants being reintroduced under a
    different producer.

## Risk

The bulk of Phase 2 is concentrated in `FetcherEmitter` and
`TypeBuilder.buildResultType`. Both have load-bearing keys today
(via R75 / R141 / R158); the existing
`source-key.result-row-walk-target-aligned-empty-path` invariant
already pins the wrap-permit dispatch on the carrier walk, and
extending the same dispatch shape to non-carrier output fields is
the work, not new machinery.

The polymorphic case stays on `@record`. The deprecation window
therefore has two phases of life:

1. *During and after R96.* `@record` exists on `OBJECT` for the
   polymorphic-dispatch surface only. The misconfiguration class
   that motivated this item (SDL declares one class, producer
   returns another) is no longer reachable on non-polymorphic
   payloads.
2. *After the sibling polymorphic-classifier item ships.*
   `@record` declaration deletes entirely.
