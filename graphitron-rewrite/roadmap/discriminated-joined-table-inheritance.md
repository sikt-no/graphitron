---
id: R389
title: "First-class discriminated joined-table inheritance (participants on their own tables)"
status: Ready
bucket: feature
priority: 6
theme: interface-union
depends-on: []
created: 2026-06-26
last-updated: 2026-06-30
---

# First-class discriminated joined-table inheritance (participants on their own tables)

graphitron supports two polymorphic shapes: single-table discriminated inheritance
(`TableInterfaceType`: `@table @discriminate`, all participants share one table, distinguished by a
discriminator column) and multi-table polymorphism (`MultiTablePolymorphicEmitter`: wholly independent
PK-bearing participant tables UNION'd together). It does **not** support **joined-table (class-table)
inheritance**: a discriminated base table plus a per-concrete-type detail table joined by FK, where the
participant's distinguishing columns live on its own table.

Today an author who has this model is forced to contort it into the single-table path: declare every
participant `@table(name: "<base>")` and put `@reference(path: [<FK to detail table>])` on every
detail-table field, because the discriminated fetcher is hardwired single-table. Specifically
`TypeFetcherGenerator.buildQueryTableInterfaceFieldFetcher` selects `FROM tableLocal` (the interface
table) and projects **every** participant's `$fields(sel, tableLocal, env)` against that one shared table
(line ~1005); `$fields`'s `table` parameter is typed as the participant's own jOOQ table class
(`TypeClassGenerator` line ~220) and a plain column emits `fields.add(table.<COLUMN>)` (line ~277). So a
participant on its own detail table fails to compile two ways: the `$fields` parameter type
(`<DetailTable>`) cannot accept the `Subjekt` argument, and a detail column projected against the base
table (`subjektTable.NAVN`) names a column the base table does not have. The `@reference`-per-field
workaround is the escape hatch out of that wrong column path. The visible symptom is
`fields.addAll(FeideApplikasjon.$fields(env.getSelectionSet(), subjektTable, env))` for a subtype whose
data does not live on `subjekt`.

Proper support means a participant declares its own `@table` for its detail data, and declares its
inherited (base-resident) fields with `@reference` back to the discriminated base. The discriminated
emitter then joins each participant's detail table to the base and projects each field against the table
the author named: shared fields off the base, the participant's own fields off its detail alias. This is
a model-level change: a distinct `ParticipantRef` variant carries the participant's detail table and the
resolved child&rarr;parent hop, the emitter threads per-participant detail aliases, the validator mirrors
the new invariants, and pipeline tests pin the shape. It deserves its own Spec rather than being bolted
onto a bug fix.

**Why `@reference` on the inherited fields, rather than inferring residence from the catalog.** The
child&rarr;parent hop is a PK=FK relationship: single-valued, total (every detail row has exactly one base
row), and direction-canonical, so it is *always correct* to resolve a base-resident field from the detail
row by traversing it. Declaring that hop with `@reference` does three things for free, all by reusing
machinery that already exists: (1) it tells `FieldBuilder` to resolve the column on the base table via the
existing `resolveColumnForReference` path (`FieldBuilder.java:5974`) rather than failing to find it on the
detail table; (2) the annotation *is* the residence marker (a field with a parent-`@reference` is
base-resident, a field without is detail-resident) so residence is declared, never inferred; and (3) it
names the FK, so there is nothing to infer and no ambiguous-FK case to defer. The earlier "declare only
`@table`, infer everything" framing is what created two hard problems this version avoids: a new
residence-aware column-resolution path in `FieldBuilder`, and a concrete type that could not be used
outside its interface (see "Standalone use" below). Reusing the cross-table reference vocabulary dissolves
both.

Near-term correctness of the workaround is handled by R388 (qualify the discriminator column across the
three emission sites + reject the discriminator-column-with-`@reference` contradiction + add the missing
execution fixture). R388 is Done; R389 is the deeper feature that removes the need for the workaround.
R392 (Done) further reworked this same emission site, routing the discriminated `TypeResolver` off a
synthetic discriminator alias (`MultiTablePolymorphicEmitter.DISCRIMINATOR_COLUMN`, projected near
`buildInterfaceFieldsList`) to disambiguate the double-projection; the joined-table emitter inherits that
aliasing alongside R388's qualified-column emission, so the "Reuse R388's discriminator-gated ON-clause"
note below reads against the post-R392 shape on trunk.

## Review feedback (In Review &rarr; Ready, 2026-06-30)

Independent-session In Review review. The implementation is architecturally sound and a **clean**
build is fully green (every module, including the execution-tier `graphitron-sakila-example` and the
docs render). Spec&rarr;diff alignment, the classifier invariants, the emitter projections, both
fixtures, and the test tiers (execution / pipeline / rejection / corpus + drift guards) all deliver
what the spec promised, with no code-string assertions on generated method bodies. One blocking
build-correctness defect must be fixed before Done:

- **Bump `jooq.codegen.schema.version` (still `2.2`) in `graphitron-rewrite/graphitron-sakila-db/pom.xml:19`.**
  R389 changed `init.sql` (added the `party` / `party_individual` / `party_company` tables, and added
  the composite primary keys to `jti_app_account` / `jti_person`) but left the schema version at `2.2`.
  jOOQ's `schemaVersionProvider` then **skips regeneration** on any incremental `-Plocal-db` build that
  already has a `2.2` catalog: the codegen logs `Existing version 2.2 is up to date with 2.2 for schema
  public. Ignoring schema.` and the stale catalog never gains the `party` tables (nor the new composite
  PKs). The result is an `UnclassifiedType` cascade: `Party` / `Query.allParties` fail to classify and
  **11 tests** break (`JoinedTableInheritancePipelineTest` x3, plus the corpus-coverage drift guards
  `SourceShapeProjectionTest`, `VariantCoverageTest`, `WrapperAlgebraTest`, `ClassifiedDslTest`). This
  reproduced here on an incremental build before a clean regeneration. R388 explicitly bumped this same
  property for its `init.sql` change (commit `b6b629d`), and the pom comment documents the requirement
  ("Increment when init.sql changes to force jOOQ regeneration on incremental builds"). Fix: bump to
  `2.3`. This is the only change needed; the next pass should be quick.

  (Note: a fresh-clone / CI build, and any `mvn clean install -Plocal-db`, regenerate from scratch and
  pass; the breakage is specific to the team's standard incremental local loop, which is exactly the
  failure mode the documented convention exists to prevent.)

## Implementation status

Landed on trunk (both shared-key shapes, execution-verified end to end):

- **Model.** `ParticipantRef.JoinedTableBound` reshaped to the approved design (dropped
  `detailResidentFields`; carries the resolved child&rarr;parent `JoinStep.FkJoin`).
- **Classifier.** `TypeBuilder.buildParticipantList` detects a participant whose `@table` differs from
  the base, resolves the child&rarr;parent hop from the inherited `@reference`, skips the cross-table
  pass, and builds a `JoinedTableBound`. PK=FK, same-base, and no-nameable-join invariants surface as
  `INVALID_SCHEMA` diagnostics with candidate-FK hints.
- **Emitter.** `TypeFetcherGenerator` projects each joined participant's base-resident
  `ColumnReferenceField` off the base (aliased as the field name), its shared-key `ColumnField` off the
  base (natural), and its detail-exclusive `ColumnField`s off a discriminator-gated `LEFT JOIN` to the
  detail alias. The shared-key partition derives from the hop columns the variant carries, so no
  residence list and no catalog read leak into the emitter. Standalone use rides the existing
  `ColumnReferenceField` path unchanged.
- **Single-column fixture + tests.** New `party` / `party_individual` / `party_company` tables (with a
  base row lacking a detail row); `Party` / `Individual` / `Company` in `graphitron-sakila-example`
  authored the R389 way; `@ExecutionTier` `allParties` (routing + per-participant projection +
  NULL-through) and `allIndividuals` (standalone via the parent reference) tests; a `@PipelineTier`
  classification test (positive shape, mixed discriminator-only + joined participant, no-nameable-join
  rejection).
- **Composite fixture (subsumes the R388 workaround).** `jti_app_account` / `jti_person` carry the
  composite PK `(jti_subject_id, subject_kind)` (= the composite FK to the base); `Subject` re-authored
  to the R389 shape (shared key columns need no `@reference`, only the base-only `displayName` does);
  the former R388 workaround `allSubjects` execution tests converted in place to the R389 composite
  case, preserving the discriminator-qualification stress (`subject_kind` re-declared on the detail
  via the composite key). No new emitter logic was needed: the join chain AND-chains the composite hop
  slots and the base/detail projections iterate fields, both composite-agnostic.

- **Validation tests (all invariants).** Rejection pipeline tests for PK=FK violation
  (`city` &rarr; `country`, FK is not the detail PK), non-base parent-reference (`address` &rarr;
  `city`, not the base), and no-nameable-join, plus the positive mixed case; the diagnostics are
  implemented and every classifier rejection now has a build-time test.
- **Docs.** A `joined-table-interface` R281 corpus example alongside `table-interface`, plus prose in
  `code-generation-triggers.adoc` (scrubbed of `R<n>` / phase vocabulary; `check-adoc-tables` and the
  AsciiDoctor render are green).

Optional doc follow-up (not blocking): there is no user-manual chapter for discriminated interfaces
today (the single-table case is undocumented for users too), so the spec's user-doc draft has no
existing `getting-started.adoc` home to move into; authoring a discriminated-interface authoring
chapter (covering single-table and joined-table together) is a separate doc effort.

## Design

**Query shape (confirmed): one discriminated base query plus a per-participant discriminator-gated
LEFT JOIN to each detail table.** Joined-table participants share the discriminated base (one PK space,
one discriminator column, FK-correlated details), so the database can do one indexed join per
participant from a single `FROM base`. This is the grain the existing `TableInterfaceType` fetcher
already speaks, and the join machinery R388 hardened (qualified discriminator column, discriminator-gated
ON-clause, NULL-for-non-matching rows) is exactly what carries the detail joins. We do **not** generalize
the two-stage `MultiTablePolymorphicEmitter` topology: that emitter pays a UNION + per-typename second
pass precisely because multi-table participants share no common table to filter on. Modelling the
base→detail correlation the schema guarantees as if it were absent would work around the database rather
than with it. What we borrow from `MultiTablePolymorphicEmitter` is its *per-typename `$fields` threading*
(`$fields(PolymorphicSelectionSet.restrictTo(sel, typeName), t, env)` against the participant's own table),
not its query topology.

**Projection shape: each field projects against the table its `@reference` (or absence of one) names.**
A joined-table participant's fields split across two tables: a field with a parent-`@reference` is
base-resident and its column lives on the base; a field without one is detail-resident and its column lives
on the participant's own detail table. Because residence is *declared* by the annotation, the classifier
hands the emitter fields already typed by residence: base-resident fields are `ChildField.ColumnReferenceField`
(the existing cross-table read), detail-resident fields are plain `ChildField.ColumnField` on the detail
table. The emitter never re-derives residence; it reads the field variant.

The two query topologies are mirror images, and the declared hop is correct in both:

- **Interface (discriminated) query** is `FROM base`, with a per-participant discriminator-gated LEFT JOIN
  to each detail table. The interface's shared fields are projected directly off the base; each
  participant contributes only its **detail-resident** fields, projected against its detail alias. The
  participant's base-resident fields are *not* re-projected here, the base row is already in hand, so the
  parent-`@reference` is simply not traversed on this path.
- **Standalone query** (the concrete type used as a return type outside the interface) is `FROM detail`.
  Here the participant's **base-resident** fields traverse the parent-`@reference` (the existing
  `ColumnReferenceField` join to the base), and its detail-resident fields read directly off the detail
  table.

So the participant's full `$fields` (every field, parent-references traversed) is what serves standalone
use, and a **detail-only projection** (detail-resident fields against the detail alias) is what the
interface fetcher uses. Critically these are *two distinct projections*, not one mutated method: the
earlier draft's "restrict the participant's `$fields` to detail-resident fields" globally corrupts the
standalone projection (it silently drops the inherited fields). The interface path must therefore use a
detail-scoped projection while the type's full `$fields` stays intact. Implementation may realise the
detail-scoped projection as a second generated method on the participant class or as a residence-filtered
emission at the interface call site; the requirement is that the interface path does not re-traverse
parent-references and the standalone path does. The cross-module `graphitron-sakila-example` compile is
the backstop that both projections line up against real jOOQ classes.

**Standalone use is in scope.** Because the parent hop is declared, a joined-table participant is a
first-class return type on its own, not only reachable through its interface. This is a deliberate gain
over the inference-only framing, where the inherited columns lived on a table the standalone query never
joined. The acceptance check that gates it is **verified satisfied on trunk**: the single-hop scalar
`ColumnReferenceField` shape we lean on is in `TypeFetcherGenerator.PROJECTED_LEAVES`, an implemented
projected leaf, not one of the deferred variants `validateColumnReferenceField` gates. The only deferred
sibling is `CompositeColumnReferenceField` (a rooted-at-parent NodeId reference), which is **not** the
shape this feature uses: the composite shared-key fixture's inherited field (`displayName`) is a single
scalar reached through a multi-slot `FkJoin`, still a plain `ColumnReferenceField`, so both fixtures land
on the implemented path. No deferral needs lifting.

## Model changes

- **A distinct `ParticipantRef` variant for joined-table participants.** Today every `TableInterfaceType`
  participant is a `ParticipantRef.TableBound` whose table equals the base. A joined-table participant gets
  its own sealed variant (`ParticipantRef.JoinedTableBound`) carrying its detail table, its discriminator
  value, and the resolved child&rarr;parent hop. A distinct variant (not `TableBound` carrying an optional
  hop) lets the emitter switch on identity and never inspect "is the hop null". The single-table case
  (participant table == base, no detail) stays `TableBound`, so a `TableInterfaceType` may freely mix
  discriminator-only participants with joined-table participants. (Both share a `TableBacked` capability for
  the type-name / table / discriminator-value reads the TypeResolver-routing and discriminator-collection
  sites need.)
- **Residence is carried by field classification, declared by `@reference`.** Because the author declares
  the parent hop, residence is not a predicate anyone recomputes: a base-resident field classifies as
  `ChildField.ColumnReferenceField` (resolved on the base via the existing reference path), a detail-resident
  field as `ChildField.ColumnField` on the participant's own table. The emitter and validator read the field
  variant; they never ask the catalog "which table does this column live on". This is the Generation-thinking
  win the inference framing only approximated, achieved by reusing the classifier path that already exists
  rather than adding a residence-aware resolver to `FieldBuilder`.
- **The child&rarr;parent hop arrives as a resolved `JoinStep`, never a `ForeignKey`.** The hop the author
  names in `@reference` resolves at the parse boundary into the existing `JoinStep.FkJoin` / `JoinSlot`
  vocabulary and is stored on the `JoinedTableBound` variant. Per "Classification belongs at the parse
  boundary", raw `ForeignKey<?,?>` stays inside the parse-boundary holders (`JooqCatalog` canonical, with
  `BuildContext` and the catalog builder `CatalogBuilder`); `TypeBuilder` / `FieldBuilder` consume the
  classified hop. The hop is direction-blind (`slot.sourceSide()` / `slot.targetSide()`), so the interface
  fetcher reads it as `base.pk = detail.pk` for the detail LEFT JOIN while the standalone reference path
  reads it as `detail -> base`, both off the same resolved slot pair. Because the FK is named by the author,
  there is no inference and no ambiguous-FK case (contrast the inference framing, which had to defer the
  ambiguous case to R393).

> Note on the in-flight model code. The inert `JoinedTableBound` already on trunk (commit `073443e`) was
> shaped for the inference framing (it carries a `detailResidentFields` list and a `baseToDetail` hop named
> as an inferred FK). Under this design residence is implicit in field classification and the hop is the
> author-declared child&rarr;parent reference, so that record will be reshaped during implementation (drop
> `detailResidentFields`; the hop is the parent reference). Its current javadoc describes the rejected
> "restrict the participant's `$fields` to `detailResidentFields`" projection (decision 4 below supersedes
> this with two distinct projections); the reshape must rewrite that javadoc in the same commit, so it does
> not survive as a false invariant per "Documentation names only live tests/code". The `TableBacked`
> capability and the routing-site edits carry over unchanged.

## Implementation

- `ParticipantRef`: the `JoinedTableBound` variant + `TableBacked` capability (foundation already on trunk
  at `073443e`; reshape per the model-changes note). Carries detail table, discriminator, and the resolved
  child&rarr;parent `JoinStep.FkJoin`.
- `TypeBuilder.buildParticipantList` / `buildTableInterfaceType`: detect a participant whose `@table`
  differs from the base, resolve its child&rarr;parent hop from the parent-`@reference` it declares, and
  build a `JoinedTableBound`. Stop forcing the single-table assumption (participant table == base). **Do not
  run the participant cross-table pass (`extractCrossTableFields`) for joined-table participants.** That pass
  exists for the *workaround* shape (participant table == base, fields referencing *out* to detail tables);
  a joined-table participant inverts that (table == detail, inherited fields referencing *back* to the
  base), so the pass does not apply. With it skipped, no `CrossTableField` entries are registered for the
  participant, `lookupParticipantCrossTableField` returns null for its fields, and field classification
  takes its normal course (below). The workaround-era R388 base-column rejection in that pass therefore
  never runs for these participants, so there is nothing to "scope".
- `FieldBuilder`: **no new arm, no new resolver.** Once the participant cross-table pass is skipped, a
  joined-table participant's fields classify through the paths that already exist: an inherited field with a
  parent-`@reference` falls through (`FieldBuilder.java:5964` &rarr; `:5970`) to the standard
  `ColumnReferenceField` classification, parsing from the participant's own table and resolving the column on
  the base via `resolveColumnForReference` (`:5974`); a detail field with no `@reference` is a plain
  `ColumnField` on the participant's table. This is the whole point of using `@reference`: the inherited
  read *is* the cross-table read we already generate. (The single-hop-FK scalar `ColumnReferenceField` shape
  must be an implemented `PROJECTED_LEAVES` shape, not one of the deferred variants
  `validateColumnReferenceField` gates; that is the one acceptance check, see "Standalone use".)
- `TypeClassGenerator`: the interface fetcher needs a **detail-only projection** of each joined-table
  participant (detail-resident fields against the detail alias) that does *not* re-traverse parent-references.
  Realise it as a second generated projection on the participant class, or as a residence-filtered emission
  at the interface call site; do not mutate the participant's full `$fields` (that one stays whole for
  standalone use). The interface's own shared fields project off the base, either via a small interface-level
  `$fields` (there is none today: `$fields` is generated per object type only, `TypeClassGenerator` line
  ~216) or by reading them directly in the fetcher.
- `TypeFetcherGenerator.buildQueryTableInterfaceFieldFetcher` (and the child
  `buildTableInterfaceFieldFetcher`): project the interface's shared fields off the base, and for each
  joined-table participant declare its detail alias, emit the discriminator-gated LEFT JOIN on the resolved
  child&rarr;parent hop (`base.pk = detail.pk AND base.discriminator = '<value>'`), and project that
  participant's detail-resident fields via the detail-only projection against the detail alias. Reuse R388's
  discriminator-gated ON-clause and qualified-column emission (and R392's synthetic discriminator alias for
  TypeResolver routing).

## Validation

The validator mirrors every accept/reject the joined-table classifier makes, each routed through the
existing `drainBuildDiagnostics` / `Rejection` machinery (the R204/R279/R317/R388 pattern), surfaced as an
`INVALID_SCHEMA` author error with file:line:

- The child&rarr;parent hop must be PK=FK (the join assumption the interface fetcher bakes in): the detail
  table's FK columns to the base **are** the detail table's primary key, referencing the base's primary key
  or a unique key. This holds for both shapes in scope: a single-column shared PK (detail PK `= ` FK to the
  base PK), and a composite shared key (the detail's composite PK is the FK to a base unique key, as in the
  `jti_*` fixture where `(jti_subject_id, subject_kind)` is both). Reject a detail table whose join to the
  base is not its primary key (e.g. a separate surrogate PK plus an independent FK column to the base), since
  the base&rarr;detail join would not be single-valued. The emitter handles the composite case with no extra
  logic: `JoinStep.FkJoin` carries one slot per key column and the join ON-clause chains them with `.and(...)`
  (already exercised by `buildCrossTableJoinChain`).
- A participant's parent-references must all resolve to the same base, the discriminated interface's table.
  A parent-reference pointing at some other table is not a base bridge; reject it.
- The joined-table participant's field leaves must land in the four-way dispatch partition
  (`TypeFetcherGenerator.IMPLEMENTED_LEAVES` / `PROJECTED_LEAVES` / `NOT_DISPATCHED_LEAVES` /
  `STUBBED_VARIANTS`); base-resident fields are `ColumnReferenceField` and detail-resident fields are
  `ColumnField`, both `PROJECTED_LEAVES`, so `GeneratorCoverageTest`'s
  `everyGraphitronFieldLeafHasAKnownDispatchStatus` covers them by construction. The one caveat is the
  acceptance check noted under "Standalone use": the single-hop-FK scalar `ColumnReferenceField` shape must
  be implemented, not deferred.

- The base&rarr;detail join must be unambiguously pinned. R389 derives it from the participant's declared
  `@reference` in the unambiguous shape: exactly one FK connects the detail table and the base, so the
  reference (whether it names the FK by key or resolves the unique FK) fixes both the inherited-field
  resolution and the base&rarr;detail join the interface fetcher emits. When that does not hold (more than
  one FK between detail and base, or a participant with detail-only fields but no base-only inherited field
  whose `@reference` could name the join), reject at validate time with a candidate-FK hint. The mechanism
  for *declaring* the join in those cases (how `@reference` disambiguates the interface&rarr;implementer
  path) is **R393**, which lifts these from rejected to supported.

**Write the execution test first; it is the acceptance gate.** This feature's correctness is a runtime
property, not a shape: join-column orientation, discriminator gating, NULL-through on non-matching rows,
TypeResolver routing, and standalone resolution. A pipeline test pins that *a* LEFT JOIN is emitted; it
cannot prove the query returns the right rows. So the `@ExecutionTier` test against real PostgreSQL
(`graphitron-sakila-example`) is the definition-of-done and is authored first; the pipeline and validation
tiers below are the fast regression net layered under it, not the primary proof for this item. (This
refines, for a runtime-correctness feature, the usual "pipeline-tier is primary" default in
`rewrite-design-principles.adoc`.)

### Fixtures (both shapes in scope: single-column and composite shared key)

Both shared-key shapes are in scope, so both get execution coverage.

**Single-column shared PK (new `party` fixture).** A clean, textbook class-table inheritance fixture that
matches the PK=FK invariant in its simplest form:

```sql
-- party (base): discriminated by party_kind; display_name is a base-only shared column.
CREATE TABLE party (
    party_id     serial       PRIMARY KEY,
    party_kind   varchar(20)  NOT NULL,   -- discriminator: 'INDIVIDUAL' | 'COMPANY'
    display_name varchar(255) NOT NULL
);
-- detail tables: their own PK *is* the FK to party(party_id), shared single-column PK.
CREATE TABLE party_individual (
    party_id   int  PRIMARY KEY REFERENCES party(party_id),
    birth_date date
);
CREATE TABLE party_company (
    party_id   int          PRIMARY KEY REFERENCES party(party_id),
    org_number varchar(64)
);
```

Authored the R389 way (note: `party_id` is on the detail tables too, as the shared PK, so it does not need a
parent-`@reference`; only the base-only `displayName` does, which is what exercises the parent-reference
path):

```graphql
interface Party @table(name: "party") @discriminate(on: "party_kind") {
  partyId:     Int!    @field(name: "party_id")
  displayName: String! @field(name: "display_name")
}
type Individual implements Party @table(name: "party_individual") @discriminator(value: "INDIVIDUAL") {
  partyId:     Int!    @field(name: "party_id")                                   # shared PK, on the detail too
  displayName: String! @reference(path: [{key: "<party_individual -> party FK>"}]) @field(name: "display_name")  # base-only
  birthDate:   Date    @field(name: "birth_date")                                 # own, on party_individual
}
type Company implements Party @table(name: "party_company") @discriminator(value: "COMPANY") {
  partyId:     Int!    @field(name: "party_id")
  displayName: String! @reference(path: [{key: "<party_company -> party FK>"}]) @field(name: "display_name")
  orgNumber:   String  @field(name: "org_number")                                # own, on party_company
}
```

**Composite shared key (the `jti_*` fixture, re-authored to R389).** The repo already has the composite
joined-inheritance fixture `jti_subject` + `jti_app_account` + `jti_person`
(`graphitron-sakila-db/.../init.sql`), today carrying the **R388 workaround** authoring (every participant
`@table("jti_subject")` with per-field `@reference` pointing *out*; composite FK `(jti_subject_id,
subject_kind)`; the discriminator re-declared on the detail tables). Two changes make it the R389 composite
fixture:

1. Add the composite primary key `(jti_subject_id, subject_kind)` to `jti_app_account` / `jti_person` (they
   have none today) so the detail's join columns to the base **are** its PK, the composite-key form of the
   invariant.
2. Re-author `Subject` so each participant declares its own detail `@table` (`jti_app_account` /
   `jti_person`) with the base-only inherited field (`displayName`) carrying `@reference` back to the base;
   `subjectId` and `subjectKind` are on the detail too (the composite key), so they need none; `clientId` /
   `fullName` are the own detail fields.

This converts the workaround example into the R389 example, which is the most honest proof that R389 replaces
the workaround, and it keeps R388's stress dimension (the discriminator column `subject_kind` is present on
both base and detail, so the discriminator qualification R388 hardened is still exercised under a participant
join). The cross-table participant-field path (`ParticipantColumnReferenceField`) that the workaround `Subject`
incidentally covered is independently exercised by the `content` / `FilmContent.rating` fixture, so converting
`Subject` loses no coverage; the R388 execution test that asserted the workaround `Subject` query becomes the
R389 composite-case test (update it in place).

Seed data (each fixture) must cover at least one row of each concrete kind **and** a base row whose detail
row is absent (or whose kind has no detail table), so the LEFT JOIN's NULL-through is asserted, not assumed.

### The execution assertions (run against both fixtures)

- **Polymorphic interface query** (`parties { partyId displayName ... on Individual { birthDate } ... on Company { orgNumber } }`):
  each row routes to the right concrete type; the matching participant's own column is populated and the
  sibling's own column is absent/NULL; `displayName` is populated for every row off the base.
- **NULL-through:** the base `party` row with no matching detail row returns `partyId` / `displayName` and
  routes correctly, with the detail columns NULL.
- **Standalone concrete-type query** (`individual(id) { partyId displayName birthDate }`, the type used
  outside the interface): `FROM party_individual`, `displayName` resolved via the parent-reference join to
  `party`, `partyId` / `birthDate` read directly off the detail. This is the regression guard for the
  property that motivated the whole redesign.

### Regression net under the execution test

- **Pipeline tier:** the same SDL generates the expected `TypeSpec` shape (one base query, per-participant
  discriminator-gated LEFT JOIN on the child&rarr;parent hop, shared fields off the base, own fields off the
  detail alias; and the standalone `FROM detail` fetcher resolving inherited fields via the parent
  reference). Pin the shape, not method-body strings.
- **Validation pipeline tests:** one rejection test per invariant (detail FK to base that is not the
  detail's PK; parent-reference to a non-base table), plus a positive mixed case (a discriminator-only
  participant alongside a joined-table participant).
- **Compile backstop:** the full reactor compile under `-Plocal-db` (including the Java-17
  `graphitron-sakila-example`) backstops the detail-alias projection typing.

### Docs

A corpus example alongside the existing `table-interface` example (`code-generation-triggers.adoc`),
authored the R389 way, scrubbed of `R<n>` / phase vocabulary per the user-facing-doc check.

## User documentation (first-client check)

This changes the authoring surface, so the docs draft is the first client of the design. The author writes,
on a discriminated base interface:

```graphql
interface Subject @table(name: "subject") @discriminate(on: "subject_type") {
  id: ID!
  name: String!
}

type Person implements Subject @table(name: "person") @discriminator(value: "PERSON") {
  id: ID!
  name: String! @reference(...)   # inherited: lives on subject, reached via the person -> subject PK=FK
  birthDate: Date!                # own: lives on person (detail)
}

type Organisation implements Subject @table(name: "organisation") @discriminator(value: "ORG") {
  id: ID!
  name: String! @reference(...)   # inherited
  orgNumber: String!              # own: lives on organisation (detail)
}
```

The promise: a concrete type declares the table its own data lives on via `@table`, and declares its
inherited fields with `@reference` back to the base. The annotation says exactly what is true, the
inherited value lives on the parent and is reached by the PK=FK hop, and the generator does the rest: the
discriminated join for interface queries, the parent join for standalone queries. The cost relative to the
discarded "infer everything" sketch is one `@reference` per inherited field (typically few, since
class-table inheritance keeps the shared columns small); the gain is that the concrete type is usable on
its own, residence is explicit rather than magic, and there is no ambiguous-FK case to reason about. If
this does not read simply, the design is wrong and must change before implementation. The draft moves into
`getting-started.adoc` / the interface-union chapter when the feature ships, scrubbed of `R<n>` / phase
vocabulary per the user-facing-doc check.

> The exact `@reference` argument spelling for the parent hop (PK=FK, single hop, scalar projection of the
> named base column) should be pinned during the docs draft against the existing `@reference` syntax; this
> sketch elides it. A behavior delta worth stating in the chapter: the old `@table(base)` + per-detail-field
> `@reference` workaround let you query the concrete type standalone because every column physically sat on
> the base. This model splits the data across two tables, so standalone querying now works *because* the
> inherited fields carry the parent hop, not because everything lives on one table.

## Out of scope

- **Detail tables that do not share the base's key.** The child&rarr;parent hop is required to be PK=FK (the
  detail table's join columns to the base are its own primary key, single-column or composite). A detail
  table with a separate surrogate PK plus an independent FK column to the base is rejected (Validation), not
  silently joined on a guessed column.
- **Multi-level joined-table chains (base &rarr; mid &rarr; leaf).** Each level would reference its
  immediate parent; the hops compose, and the PK=FK invariant holds at each. The mechanism extends to it
  cleanly, but v1 ships and tests the single base &rarr; detail level; deeper chains are a follow-up if a
  schema needs them.

## Bearing on R393

R393 is **not** moot under this design; it is repurposed and stays a live follow-up. R389 handles only the
unambiguous base&rarr;detail join (one FK between detail and base, pinned by the participant's `@reference`),
and rejects the cases it cannot express. R393 owns the **disambiguation**: how `@reference` declares and
disambiguates the base&rarr;detail (interface&rarr;implementer) join path when more than one FK connects
detail and base, or when a participant has detail-only fields but no base-only inherited field whose
`@reference` could name the join. R393 lifts those from rejected to supported, and must settle the directive
surface (inherited-field `@reference(key:)` serving double duty, vs. a participant-level declaration). It
depends on R389 (the unambiguous path) landing first.

## Resolved design decisions

1. **Authoring mechanism:** inherited (base-resident) fields carry a parent-`@reference`; own fields do not.
   Residence is declared, not inferred, and the parent hop reuses the existing cross-table reference path.
   This supersedes the first draft's "declare only `@table`, infer residence and FK" sketch, which forced a
   new residence resolver into `FieldBuilder` and left the concrete type unusable outside its interface.
2. **Joined-table participant shape:** a distinct `ParticipantRef.JoinedTableBound` sub-variant (not
   `TableBound` carrying an optional hop), so the emitter switches on identity.
3. **Standalone use:** in scope, and a first-class motivation rather than a deferred follow-up. The concrete
   type is queryable on its own via the declared parent hop.
4. **Projection mechanism:** the participant's full `$fields` stays whole (serves standalone); the interface
   path uses a separate detail-only projection. The two are distinct, never one globally-restricted method.
5. **Test order:** the `@ExecutionTier` test is authored first as the acceptance gate (runtime correctness),
   with pipeline / validation / compile as the regression net under it.
6. **Shared-key shapes:** both single-column and composite shared keys are in scope (composite is **not**
   deferred). The PK=FK invariant is "the detail's FK to the base is the detail's PK", single-column or
   composite. Two execution fixtures: the new single-column `party` tables, and the composite `jti_*` tables
   re-authored to R389 (with the detail composite PK added), which also subsumes the R388 workaround. The
   composite case needs no new emitter logic (multi-column joins and discriminator qualification already
   exist).
