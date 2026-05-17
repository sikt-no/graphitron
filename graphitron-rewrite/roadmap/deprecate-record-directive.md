---
id: R96
title: "Deprecate @record (narrow scope; defer polymorphic-return case)"
status: Spec
bucket: cleanup
priority: 6
theme: model-cleanup
depends-on: [emit-input-records]
---

# Deprecate @record (narrow scope; defer polymorphic-return case)

The `@record` directive (declared at `directives.graphqls:252` as
`directive @record(record: ExternalCodeReference) on OBJECT |
INPUT_OBJECT`) tells graphitron the developer-supplied Java class
backing an SDL type. Today it drives accessor resolution, payload
construction in error/DML flows, type binding for `@externalField`
chains, and input-type classification. It carries weight in the
classifier (`TypeBuilder.buildResultType`,
`TypeBuilder.buildNonTableInputType`,
`MutationInputResolver.classifyDml`) and in seven `GraphitronType`
sealed variants (`PojoInputType`, `JavaRecordInputType`,
`JavaRecordType`, `JooqRecordType`, `JooqTableRecordType`,
`PojoResultType.Backed`, `JooqRecordInputType`).

This item argues `@record` adds no information graphitron can't get
from introspection or from existing directives for **seven of eight**
surfaces it covers today. The remaining surface — polymorphic /
interface dispatch where `@record` pins the concrete subtype of a
base-typed service return — has no replacement signal in the current
classifier and is **explicitly deferred** to a sibling roadmap item
(see [Out of scope](#out-of-scope)). R96 therefore narrows the
directive's scope and migrates the seven covered surfaces; it does
not delete the directive declaration.

## Where `@record` carries weight today

Six categories on output types (sakila + tests):

1. **DML payload types** — `CreateFilmPayload`, `CreateFilmsPayload`,
   and their counterparts in sakila + tests. The DML emitter needs
   a class to instantiate; `@record` provides it.
2. **`@service`-returning payloads** — `FilmLookupPayload`,
   `FilmReviewPayload`. The class graphitron's catch arm constructs
   from on dispatch; the type accessor resolution targets.
3. **jOOQ-table-record wrapping** — `FilmCard @record(...FilmRecord)`,
   `FilmDetails @record(...FilmRecord)`. Binds an SDL type to a
   jOOQ-generated record.
4. **Hand-rolled wrapper records** — `FilmCardWrapper
   @record(...FilmCardData)`. Binds an SDL type to a developer record
   that wraps other types.
5. **Polymorphic / interface dispatch** — base-typed service return,
   `@record` pinning the concrete subtype.
6. **Cross-module backing classes** — backing class the SDL author
   can't import.

Two on input types (the surface this item subsumes from R94):

7. **Pojo / Java-record input classification** —
   `JavaRecordInputType` vs. `PojoInputType` vs. `JavaPlainInputType`
   in the input-side hierarchy.
8. **`@table + @record` shadow rule on inputs** — see the comment at
   `TypeBuilder.java:652-657`.

## Why `@record` is redundant in each case

- **(1) DML payloads** — covered by R75
  (`synthesize-payload-carrier.md`, Spec), which admits plain payload
  types via identity passthrough: graphql-java traverses the DML's
  `Result<Record>` directly through generated identity fetchers, no
  Java carrier on disk. `@record` on DML payloads is already on a
  deprecation path written into another roadmap item.
- **(2) `@service`-returning payloads** — graphitron introspects the
  `@service` method's return type
  (`ServiceCatalog.reflectServiceMethod` already captures it). The
  return type *is* the backing class.
- **(3) jOOQ-table-record wrapping** — `@table` resolution already
  produces the jOOQ-generated record class
  (`Tables.FILM.recordType()`); the producing field's accessor
  return type chains the binding through. No `@record` needed.
- **(4) Wrapper records via field traversal** — the producing
  `@externalField(reference: {...})` carries the class via its own
  attribute; or the parent's accessor return type tells graphitron
  what the child is. `@record` duplicates that signal.
- **(5) Polymorphic dispatch** — *not redundant under the current
  classifier.* `@record` is the only signal that pins the concrete
  subtype of a base-typed service return; introspection of the
  service method only sees the base type, and
  `ReturnTypeRef.PolymorphicReturnType` (`BuildContext.java:426`,
  `FieldBuilder.java:649,669,2489,2565`) carries the wrapper but
  not a per-element class binding. The right shape is a typed
  classifier signal (a `TypeResolver` registry keyed by SDL type,
  or a runtime `__typename` extractor), but that signal does not
  exist today. R96 leaves this surface on `@record` and files a
  sibling backlog item for the polymorphic classifier; see
  [Out of scope](#out-of-scope).
- **(6) Cross-module backing** — introspection works across modules
  too. No special handling needed.
- **(7) Pojo/JavaRecord input classification** — covered by R94,
  which emits a uniform graphitron-internal record per SDL `input`
  type. `@record` on inputs becomes redundant the moment R94 ships.
- **(8) `@table + @record` shadow rule** — vanishes when (7)
  vanishes; no input has `@record` to shadow with.

## Misconfiguration risk

Beyond redundancy, `@record` is a configuration drift source. The
SDL declares a backing class string
(`@record(record: {className: "..."})`); the actual producer
(introspection, `@table`, `@externalField`) declares another. When
they disagree, graphitron has to either error, ignore one, or pick a
precedence rule — all real surface area. The existing
`@table + @record` shadow rule (`TypeBuilder.java:652-657, 657-664`)
is exactly this kind of "two sources of truth, here's how we picked
a winner" comment, and it exists to paper over a class of
misconfigurations the directive itself enabled.

Removing `@record` removes the class of misconfigurations entirely.

## Phasing

The deprecation has natural phase boundaries; each phase is
independently shippable.

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
  invariants. Without this, the deprecation window admits the
  "two sources of truth" misconfigurations the directive is
  supposed to remove (see [Misconfiguration risk](#misconfiguration-risk)).
- Migration of the input-side unit-test fixtures at
  `GraphitronSchemaBuilderTest.java:3443-3520` from happy-path
  classification to a rejection cluster.

R96 carries no independent Phase 1 work: R94 closes both items'
input-side surface in a single deliverable.

### Phase 2 (depends on R75): drop `@record` on DML payload types

R75 admits no-authored-class DML payloads via identity passthrough;
once that lands, `@record` on DML payloads with no custom state or
behaviour becomes literal dead weight. Migrate the category (1)
fixtures from the Phase 3 audit table (`CreateFilmPayload`,
`CreateFilmsPayload`, plus any additional DML-payload `@record`
declarations that emerge during R75 implementation), drop the
corresponding `@record(record: ...)` declarations, and drop the
developer-supplied payload classes whose fields are 1:1 with the
SDL. The category (1) rows live in this Phase 2 step rather than in
Phase 3 so the R75 dependency stays local.

### Phase 3 (this item's own work): drop `@record` on non-polymorphic output types

The remaining cases (excluding category 5, deferred above) all rely
on existing classifier signals (introspection, `@table`,
`@externalField` reference attributes). Phase 3 walks each model
variant that currently consumes `@record`, replaces the consumer
with the appropriate alternate signal, and narrows `@record` on
`OBJECT` to *polymorphic-return-only* (full removal awaits the
polymorphic classifier signal; see [Out of scope](#out-of-scope)).

#### Classifier-site → replacement-signal audit

Each row is the artifact Phase 3 changes; the new
`@LoadBearingClassifierCheck` key column names the validator rule
that pins the replacement signal as load-bearing.

|Classifier site|Current `@record` role|Replacement signal|Load-bearing key (new)|
|---|---|---|---|
|`TypeBuilder.buildResultType` (line 667–702), `DIR_RECORD` branch at line 357|Selects `JavaRecordType` / `JooqRecordType` / `JooqTableRecordType` / `PojoResultType.Backed` based on the reflected backing class|`@service` return-type introspection (`ServiceCatalog.reflectServiceMethod`) for `@service`-returning payloads; `@table` record-class derivation (`Tables.X.recordType()`) for jOOQ-record wrappers; parent-accessor return-type chain for hand-rolled wrappers|`result-type.backing-class-from-producer-signal`|
|`MutationInputResolver.classifyDml` (`MutationInputResolver.java:174,193–202`)|Payload-class lookup for DML `Result<Record>` carrier classification|R75's identity-passthrough payload (no Java carrier needed; the DML result flows directly through generated identity fetchers)|covered by R75's existing keys; no new key|
|`BuildContext.passthroughCandidate` filter (`BuildContext.java:480–533`)|Excludes `@record`-with-`className` from passthrough candidates|Same filter, keyed on `ResultType` having a non-`NoBacking` arm rather than directive presence|`passthrough.exclude-by-backing-class-not-directive`|
|`ClassAccessorResolver` (`ClassAccessorResolver.java:86`)|Accessor reflection asserts a `@record`-backed SDL field has a real accessor|Same assertion, keyed on the `ResultType.Backed`/`JavaRecord`/`Jooq*` model variants rather than the directive|`accessor-resolution.backed-from-model-variant`|
|`ServiceDirectiveResolver` (`ServiceDirectiveResolver.java:324`)|"`ResultReturnType` with a backing class is the `@record`-payload shape"|Same shape, derived from the classifier's `ResultType.Backed` (already done; just remove the `@record` reference from the comment)|n/a (doc-only)|
|`SourceRowDirectiveResolver` (`SourceRowDirectiveResolver.java:33,448,465–470`)|Gates `@sourceRow` on a `@record`-typed parent with a non-jOOQ backing class|`ResultType.Backed` (POJO) vs `JooqTableRecordType` model-variant test; the directive presence is incidental|`sourcerow.parent-shape-from-model-variant`|

#### Fixture migration (sakila)

The fixtures below drop `@record`; the producer signal stays. Each
is a separate commit-sized unit; Phase 3 lands them in order.

|Fixture|Line|Surface category|What replaces `@record`|
|---|---|---|---|
|`CreateFilmPayload`|`schema.graphqls:314`|(1) DML payload|R75 identity passthrough (Phase 2)|
|`CreateFilmsPayload`|`schema.graphqls:330`|(1) DML payload|R75 identity passthrough (Phase 2)|
|`CustomerAddressSummary`|`schema.graphqls:342`|(4) hand-rolled wrapper|parent-accessor return type|
|`FilmLookupPayload`|`schema.graphqls:377`|(2) `@service`-returning|`@service` method return-type introspection|
|`FilmReviewPayload`|`schema.graphqls:405`|(2) `@service`-returning|`@service` method return-type introspection|
|`FilmCard`|`schema.graphqls:505`|(3) jOOQ-table-record wrap|`@table` resolution + `Tables.FILM.recordType()`|
|`FilmCardWrapper`|`schema.graphqls:509`|(4) hand-rolled wrapper|`@externalField` reference attribute|
|`RecordExample`|`schema.graphqls:523`|(4) hand-rolled wrapper|parent-accessor return type|
|`FilmDetails`|`schema.graphqls:553`|(3) jOOQ-table-record wrap|`@table` resolution + `Tables.FILM.recordType()`|

#### Order

1. **Wire each replacement signal** with the load-bearing key in
   the audit table above. Land each pair (signal + validator key)
   as a self-contained change; the model variant continues to be
   produced by the legacy `@record`-driven arm in parallel until
   step 3.
2. **Drop the `@record` reference from each classifier arm** in
   favour of the new signal. The `buildResultType` branch becomes
   a fall-through to `PojoResultType.NoBacking` (or whichever model
   variant the producer signal yields); the directive is no longer
   read in that path.
3. **Migrate sakila SDL fixtures** in the order above; each commit
   removes one `@record` declaration and verifies the migrated
   fixture still compiles + executes.
4. **Narrow the directive declaration** at
   `directives.graphqls:252` from `on OBJECT` to whichever scope
   the deferred polymorphic case still needs (likely a custom
   `on OBJECT @scope(polymorphicReturnOnly: true)`-style marker, or
   simply a doc note that `OBJECT` now only covers polymorphic
   returns). Exact mechanism deferred to the sibling roadmap item.
5. **Remove the now-dead `GraphitronType` variants**: `JavaRecordType`,
   `JooqRecordType`, `JooqTableRecordType`, `PojoResultType.Backed`
   — whichever lose all producers after step 3.

### Phase 4: housekeeping

- Update `code-generation-triggers.adoc:112` to narrow the `@record`
  row to its remaining (polymorphic-return) scope, and cross-link
  the sibling roadmap item.
- Update LSP completion + diagnostics so `@record` is offered only
  on the polymorphic-return surface.
- Update `docs/README.adoc` and any other places that document
  `@record` as a directive consumers should reach for in the
  general case; surface the narrowed scope.
- Add a migration note in `changelog.md` naming the SHA where the
  directive narrows to polymorphic-return-only, and a second entry
  (reserved) for when the sibling polymorphic-classifier item
  removes the declaration entirely.

## Out of scope

- **Polymorphic / interface dispatch (category 5)** — removing
  `@record` from base-typed service returns requires a typed
  classifier signal that doesn't exist today. R96 narrows the
  directive's scope (Phase 3 step 4) but does not delete the
  declaration. A sibling backlog item — *polymorphic-return
  classifier signal* — owns the work to introduce a `TypeResolver`
  registry (or equivalent) keyed by SDL type; once it ships,
  `@record` becomes a no-op on `OBJECT` and can be removed in a
  trailing follow-up.
- Replacing `@record` with a different "explicit type binding"
  directive. The whole point is that explicit type binding is
  redundant with the signals graphitron already has. If a future
  case can't be covered by introspection or existing directives, it
  surfaces as `UnclassifiedField` and gets its own dedicated
  classifier signal (not a re-introduction of `@record`).
- Changing the consumer's API surface. Phase 3's migration of
  sakila SDL fixtures is fixture-only; the consumer-facing types
  (`FilmReviewPayload`, etc.) keep their shape. The directive goes
  away, not the developer-supplied classes.

## Tests

Per-phase test tiers (so a reviewer can evaluate coverage at the
Spec → Ready gate, without having to wait for the implementation
audit):

- **Phase 1 (input-side).** Owned by R94 Phase 2. The validator-rule
  test against the migrated `GraphitronSchemaBuilderTest.java:3443-3520`
  cluster (now positive-rejection cases) plus a negative control on
  `@record`-on-`OBJECT` ships with R94; see
  `emit-input-records.md` Tests.
- **Phase 2 (DML payloads).** Covered by R75's existing
  identity-passthrough test surface; this item adds no new tests in
  Phase 2 beyond the fixture migrations.
- **Phase 3 (output-side).** Per-row in the audit table:
  - Pipeline-tier (`graphitron-fixtures-codegen`): the migrated
    fixture classifies to the same `GraphitronType` variant via the
    replacement signal as it did via `@record`. Snapshot the
    pre-/post-migration classifier output for each of the seven
    output-side fixtures.
  - Compile-tier (`graphitron-sakila-example`): the generated
    fetchers + payload classes still compile under `<release>17</release>`.
  - Execution-tier (`graphitron-sakila-service`): the affected
    queries / mutations return the same shape (existing
    integration tests; no new fixtures needed if the migration is
    behaviour-preserving).
- **Phase 4 (housekeeping).** Doc-only; AsciiDoctor build catches
  broken xrefs. LSP completion change covered by the existing
  directive-registry tests in `graphitron-lsp`.

## Risk

The R75 dependency for Phase 2 means this item can't fully ship
until R75 ships. Phases 1 and 3 are independent: Phase 1 ships with
R94; Phase 3 can ship before R75 if Phase 2 is deferred. The
directive declaration does *not* go to zero scope at R96's
completion — full removal is gated on a sibling roadmap item for
the polymorphic-return classifier signal (see [Out of scope](#out-of-scope)).
The deprecation window therefore has two phases of life:

1. *During R96* — `@record` exists on `OBJECT | INPUT_OBJECT` while
   migration is in progress. The "two sources of truth"
   misconfiguration the directive enables is bounded by Phase 1's
   validator rule (input scope rejected at schema build time) and
   by the audit table's row-by-row migration order (each Phase 3
   row removes one classifier branch reading the directive).
2. *After R96* — `@record` exists only as the polymorphic-return
   signal, scoped narrowly enough that the misconfiguration class
   is no longer reachable by schema authors writing non-polymorphic
   payloads. Trailing follow-up under the sibling roadmap item
   removes the declaration entirely.
