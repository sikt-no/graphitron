---
id: R406
title: "Support single-table discriminated interface as a DML @mutation return type"
status: In Review
bucket: feature
priority: 4
theme: mutations-errors
depends-on: []
created: 2026-07-01
last-updated: 2026-07-01
---

# Support single-table discriminated interface as a DML @mutation return type

## Implementation (reworked onto R405 trunk, pending review)

Reworked from the first implementation pass (`claude/r406-implementation-gjfkxe`,
commit `1b6971d`) to consume R405's now-landed shared helper instead of
re-extracting it. All five rework items from the previous review outcome are
addressed. Summary of the diff for the reviewer:

- **Model** (`DmlReturnExpression.java`): added the sibling `DiscriminatedSingle` /
  `DiscriminatedList` arms carrying `interfaceName`, `discriminatorColumn`,
  `knownDiscriminatorValues`, `participants`. `MutationField.dmlDomainReturnType` and
  `OutputField.dmlTarget` map both arms to the same `Record` / `Table` shape as `Projected*`
  (they re-project the shared table).
- **Classify** (`FieldBuilder.java`): the single DML chokepoint `buildDmlField` resolves the
  return's look-ahead verdict once; when it is a `TableInterfaceType`, the (still-`static`)
  `buildDmlReturnExpression` builds the `Discriminated*` arm instead of `Projected*`. No new
  `MutationField` leaf.
- **Validate** (`GraphitronSchemaValidator.java`): `dispatchPerformsReFetch` now recognises the
  `Discriminated*` arms as re-fetching (they run a follow-up SELECT), keeping the emitter and the
  `requiresReFetch` derivation in agreement. The DELETE / Connection floors in
  `MutationInputResolver.validateReturnType` already fire for the interface case (it is a
  `TableBackedType`); no change needed there.
- **Emit** (`TypeFetcherGenerator.java`): added `emitDiscriminated`, which reuses step 1 (PK-only
  `RETURNING` in a transaction) verbatim and swaps step 2 for **R405's** shared re-projection
  helper `buildTableInterfaceReprojection`, passing `List.of()` for its `alwaysProject` param
  (the DML path keys the follow-up SELECT by a PK-IN `Condition` in the `WHERE` off the
  `RETURNING` keys, so it needs no extra always-projected PK columns; the service path passes the
  PK columns because it re-maps fetched rows to input positions by PK). The now-deleted
  `buildDiscriminatedReprojection` and the two read fetchers' re-projection calls are gone; both
  read fetchers already call R405's helper on trunk. `emitKeysTransaction` and the composite-safe
  PK-IN condition builder `buildPkKeysCondition` stay R406-owned (the DML write half R405 has no
  equivalent of): both key off the `RETURNING` `keys` local and are shared only between
  `emitProjected` and `emitDiscriminated`. They do not duplicate R405's private
  `MultiTablePolymorphicEmitter.buildPkInCondition`, which keys off the service `records` list.

Rework items addressed: (1) rebased onto trunk carrying R405; (2) deleted
`buildDiscriminatedReprojection`, `emitDiscriminated` now calls
`buildTableInterfaceReprojection`; (3) confirmed the PK-IN keying composes with R405's helper and
that passing `List.of()` for `alwaysProject` projects no extra PK columns (the DML `Record` the
`TypeResolver` consumes needs only `__discriminator__`); (4) `buildPkKeysCondition` /
`emitKeysTransaction` verified non-duplicating of R405's `records`-keyed builder; (5) full reactor
re-run under `-Plocal-db`.

Two minor findings from the previous review: the Connection-wrapped-interface rejection relies on
the general `@asConnection` rejection tests (a `TableInterfaceType` routes through the same
`TableBoundReturnType` Connection floor); the `FetcherPipelineTest` cases are carried as-is per
the file's existing DML-fetcher convention, with behaviour proven at the execution tier and the
model shape pinned in `GraphitronSchemaBuilderTest` (R380 carried-debt precedent).

Tests: classification + regression + DELETE-floor pins in `GraphitronSchemaBuilderTest`
(`MutationDmlCase`); INSERT/UPDATE pipeline shape assertions in `FetcherPipelineTest`; execution
proof (`DmlTableInterfaceReturnExecutionTest`) over the `content` fixture + a new
`ContentInput` / `createContent` / `updateContent` schema fixture, covering per-`__typename`
routing off the live discriminator, the cross-table `rating` join, same-table `description`
isolation, and the unknown-discriminator write/read asymmetry (row commits, return is `null`).
Full reactor green under `mvn -f graphitron-rewrite/pom.xml install -Plocal-db`.

**On approval (In Review → Done):** delete this file and add a `changelog.md` entry citing the
landing SHA and `R405` (whose `buildTableInterfaceReprojection` R406 consumes; R405 extracted the
shared read-side re-projection helper, R406 consumes it).

## Problem

A DML `@mutation(typeName: INSERT|UPDATE)` returning a single-table
discriminated interface (`@table @discriminate(on:)`, implementers pinned
by `@discriminator(value:)`, all sharing one jOOQ table, e.g. `Content`
over `content`) does not work today. **It is not rejected; it is silently
mis-accepted and emits code that does not compile.** This corrects the
Backlog stub's framing on two counts, both falsified by a probe (a
`@mutation(typeName: INSERT)(in: ContentInput!): Content` fed through the
real classifier + `TypeFetcherGenerator`):

1. **The stub's claimed rejection does not fire.** The stub said
   `MutationInputResolver.validateReturnType` rejects the interface at the
   `PolymorphicReturnType` arm (`MutationInputResolver.java:242`,
   "(interface/union) is not yet supported"). It does not. A
   `TableInterfaceType` **is** a `TableBackedType`
   (`GraphitronType.java:33-34`), so `BuildContext.resolveReturnType`
   (`BuildContext.java:505`) classifies the return as a
   **`TableBoundReturnType`**, not a `PolymorphicReturnType`. The
   `TableBoundReturnType` arm of `validateReturnType`
   (`MutationInputResolver.java:216-227`) returns `null` (accept) for any
   non-DELETE, non-Connection table return. The interface never reaches
   the `PolymorphicReturnType` arm. The probe classified with no rejection
   to `MutationInsertTableField` carrying
   `DmlReturnExpression.ProjectedSingle[returnTypeName=Content]`.

2. **The input side is not a new design problem.** The stub called the
   input side "the crux". The probe shows it is a plain single-`@table`
   write: `input ContentInput @table(name: "content") { title, contentType
   }` resolves through `MutationInputResolver.resolveInput` verbatim, with
   the discriminator column `CONTENT_TYPE` carried as an **ordinary**
   `@field(name: "CONTENT_TYPE")` input column the client sets. There is
   exactly one target table (the shared `@table`), so the "which
   implementer's columns / where does the discriminator come from" fork the
   stub feared never materialises: every writable column (`TITLE`,
   `CONTENT_TYPE`, `SHORT_DESCRIPTION`) lives on the one shared table, and
   the subtype-selective input is just which of those the author populates.
   Cross-table subtype fields (`FilmContent.rating`, on the joined `film`
   row) are correctly **not** writable through a `content` INSERT; they are
   read-side projections only. **`resolveInput` needs no change.**

The genuinely new work is entirely on the **return** half. The emitted
fetcher runs the INSERT into `content` with a PK-only `RETURNING
CONTENT_ID` inside a transaction (correct), then re-projects by PK:

```java
Record payload = dsl.select(<pkg>.types.Content.$fields(env.getSelectionSet(), contentTable, env))
    .from(contentTable)
    .where(CONTENT.CONTENT_ID.eq(keys.value1()));
```

`<pkg>.types.Content.$fields(...)` **is never generated**:
`TypeClassGenerator.generate` (`TypeClassGenerator.java:53-54`) emits a
`$fields` projection class only for `TableType` / `NodeType`, not for
`TableInterfaceType`. So the generated sources fail to compile. Even if
that projection existed, it would flatten to the interface's own declared
fields, project no `__discriminator__`, set no per-row `__typename`, and
could never route subtypes.

## How discrimination works (reused, not reinvented)

Identical to R405's read side. `@discriminate(on: "CONTENT_TYPE")` names a
real column on the shared `@table`; each implementer's
`@discriminator(value: "FILM")` pins that implementer to a literal value.
The read-side fetcher (`TypeFetcherGenerator.buildInterfaceFieldsList`)
projects that column aliased to the synthetic `__discriminator__`
(`MultiTablePolymorphicEmitter.DISCRIMINATOR_COLUMN`), qualified off the
FROM table's own jOOQ instance, alongside the unified participant field set
and discriminator-gated cross-table `LEFT JOIN`s for subtype-specific
fields. One `TypeResolver` per `TableInterfaceType` is generated
**unconditionally** (`GraphitronSchemaClassGenerator.java:126-158`): it
reads `record.get(DSL.field(DSL.name("__discriminator__")), String.class)`
and `switch`es to the implementer object type. The `Content` fixture is
already in the schema (read-side `Query.allContent`), so its `TypeResolver`
already exists; **no new resolver is generated**. The DML row reaching the
resolver only has to carry `__discriminator__`; its Java class is
irrelevant, so the single-table case works where a runtime-class dispatch
would not.

## Relationship to R405 (no hard dependency)

R405 (`@service` single-table interface return, `theme: interface-union`,
currently Spec) solves the mirror-image problem on the `@service` surface:
its return half feeds a **by-PK discriminator SELECT** from the service's
returned PKs. R406's return half feeds the **same** by-PK discriminator
SELECT from the DML write's `RETURNING` keys. Both need one shared read-side
helper:

> **Re-projection helper contract.** A package-private
> `TypeFetcherGenerator` helper that, given a `tableLocal` and a
> caller-supplied `Condition`, assembles `select(buildInterfaceFieldsList(...))
> .from(tableLocal).where(condition)` with `buildCrossTableAliasDeclarations`
> + `buildCrossTableJoinChain` (projecting `__discriminator__` + the unified
> participant field set + discriminator-gated cross-table `LEFT JOIN`s) and
> knows nothing about services or DML. The two existing read fetchers
> (`buildQueryTableInterfaceFieldFetcher` / `buildTableInterfaceFieldFetcher`)
> call it with their FK/discriminator condition; R405's service fetcher calls
> it with a PK-IN condition built from service PKs; R406's DML fetcher calls
> it with a PK-IN condition built from the `RETURNING` keys.

This is exactly the helper R405's "Emit location" section extracts.
**Whichever of R405 / R406 lands first extracts it** (from the shared body
of the two read fetchers); the second consumes it. R406 carries **no**
`depends-on: [R405]` so the two can land in either order, and states the
helper as a *contract* rather than naming an unlanded signature: if R405's
review moves the seam, R406's stated contract is what flags the drift. Same
for the PK-IN row-value condition builder (`DSL.row(<pkCols>).in(<rows>)`,
composite-safe): whichever item lands first introduces it.

## Design

The write half is unchanged; the return half swaps the concrete-type
`$fields` re-projection for the discriminator re-projection. The fork is a
**return-shape**, lifted into `DmlReturnExpression` (not a new
`MutationField` leaf, and not a runtime branch in the emitter).

### Model (new `DmlReturnExpression` arms)

`DmlReturnExpression` (`DmlReturnExpression.java`) already **is** the sealed
"pre-resolved return-shape dispatch" the four `Mutation*TableField`
variants carry: `EncodedSingle/List` (ID) and `ProjectedSingle/List`
(`@table`), single-vs-list encoded in the variant. A single-table interface
return is another return-shape, so add a sibling pair:

```
record DiscriminatedSingle(String interfaceName, String discriminatorColumn,
    List<String> knownDiscriminatorValues, List<ParticipantRef> participants) implements DmlReturnExpression {}
record DiscriminatedList(String interfaceName, String discriminatorColumn,
    List<String> knownDiscriminatorValues, List<ParticipantRef> participants) implements DmlReturnExpression {}
```

They carry the read-side single-table discrimination data (the same fields
R405's `*ServiceTableInterfaceField` carries), sourced verbatim from the
`TableInterfaceType` verdict (`tit.discriminatorColumn()`,
`knownDiscriminatorValues(tit)` at `FieldBuilder.java:6222`,
`tit.participants()`). The existing
`MutationInsertTableField` / `MutationUpdateTableField` variants stay
unchanged; only the emitter's return-expression `switch` gains an arm.

**A new leaf variant is the wrong axis.** R405 needs new *leaf* variants
because the service `TableBound` arm otherwise builds a plain service-table
field (a different operation shape). Here the write half is uniform across
verbs and the model already carries the return-shape seam; a per-verb leaf
would fork the emitter on write-verb identity (three leaves) for a
distinction that is not per-verb.

### Classify (single chokepoint)

`buildDmlReturnExpression` (`FieldBuilder.java:3511`) is the single point
where every non-DELETE DML return-shape is chosen: INSERT and UPSERT reach
it via `buildDmlField` (`:4028` / `:4041`), UPDATE via
`classifyUpdateTableField` → `buildDmlField` (`:4106` / `:4320`). Lift the
"is this a `TableInterfaceType` return?" predicate into that one place:
when the `TableBoundReturnType`'s look-ahead verdict is a
`TableInterfaceType`, build the `Discriminated*` arm instead of
`Projected*`. Evaluating the predicate once at the chokepoint (rather than
per verb) is the correct application of "lift the fork into the model".

`buildDmlReturnExpression` is `static` and has no `typeBuilder`; thread the
resolved verdict in from the caller (`buildDmlField` is an instance method
with `typeBuilder` in scope) rather than making the method non-static, so
the verdict is resolved once at the call site.

### Emit (reuse the DML write, swap the re-projection)

`emitProjected` (`TypeFetcherGenerator.java:4507`) already does the
two-step: PK-only `RETURNING` inside `dsl.transactionResult(...)` yielding
`keys`, then `dsl.select(Type.$fields(...)).from(tableLocal).where(<pk =
keys>)`. Add a sibling `emitDiscriminated` arm to `emitDmlReturnExpression`
(`:4438`) that reuses step 1 verbatim and replaces step 2's
`Type.$fields(...)` SELECT with a call to the shared re-projection helper,
passing `tableLocal` and a **PK-IN `Condition`** built from `keys` (single
→ `.eq(keys.value1())`, list → row-value `.in(...)`, exactly as
`emitProjected` already builds). The generated row carries
`__discriminator__`; the existing `TableInterfaceType` `TypeResolver` sets
`__typename` per row. No new resolver, no per-typename UNION.

Emit reads only from the arm and the `TableRef`, re-deriving nothing: the
PK-IN keys come from `tableRef.primaryKeyColumns()` (as `emitProjected`
does), the discriminator data from the arm, and the single-table
vs. joined-table participant distinction from the `ParticipantRef` subtype
(`TableBound` vs `JoinedTableBound`) already resolved on
`tit.participants()`, not re-tested at emit.

Wire the two arms into `emitDmlReturnExpression`'s `switch`. No
`IMPLEMENTED_LEAVES` change is needed (the `MutationField` leaf set is
unchanged; the coverage partition is over leaves, not
`DmlReturnExpression` arms), which is a further reason the new-arm choice is
lighter than R405's new-leaf choice.

### Discriminator-write contract

The client writes the discriminator via the ordinary input column
(`contentType: String @field(name: "CONTENT_TYPE")`). Graphitron does
**not** validate the written value against `knownDiscriminatorValues`:
enforcing the discriminator column's domain is the database's job (a `CHECK`
constraint or a foreign key), the "database is your ally" principle, not a
graphitron pre-screen. A value matching no participant drops on the
read-side discriminator filter (`buildDiscriminatorFilter`), reusing R405's
drop contract.

**Name the write/read asymmetry** (a reviewer will ask): on the read side
the drop is a filter over rows that already exist; on the write side the
INSERT has **already committed** in its transaction before the follow-up
SELECT runs. So a client who writes an unknown discriminator gets a
committed row **and** a `null` return (surfacing as a graphql-java non-null
violation if the field is non-null). The write is authoritative; graphitron
simply cannot name the subtype of a row whose discriminator is outside the
known set. This is defensible and consistent with R405; a `CHECK`
constraint is the author's tool to forbid it at the source.

### Drop / null contract (aligned with R405)

A `RETURNING` key whose live row's discriminator is not a known participant
value drops from a list return (the payload is shorter) and yields `null`
for a single return. Re-mapping by PK preserves order among survivors. Same
semantics as R405's service path.

## Validation (mirror the new classifier acceptance)

The current state is a *silent accept → fails to compile*: the
`TableBoundReturnType` arm cannot today distinguish a concrete `@table`
return from an `@table @discriminate` interface return; both yield `null`
(accept). Adding the `Discriminated*` arm converts a latent compile failure
into a supported feature, so the validator must mirror the new acceptance
("validator mirrors classifier invariants"):

- Confirm (and pin) that the **DELETE** and **Connection** floors in
  `validateReturnType`'s `TableBoundReturnType` arm still fire for the
  interface case. They do (`TableInterfaceType` is `TableBackedType`, routes
  through the same arm), but this is load-bearing for "DELETE / Connection
  stay excluded", so the spec names it.
- Add a **coverage pin** that an interface-returning DML classifies to a
  `Discriminated*` arm, so the acceptance is pinned rather than prose. If a
  future change breaks the interface emit path, this pin (not a compile
  failure) catches it.
- The single-table invariants the emitter assumes (single-hop FK per
  cross-table participant, a PK-bearing shared table, resolvable
  discriminator column) are enforced **upstream** in `TypeBuilder`
  (`buildParticipantList` / `extractCrossTableFields`) when the
  `TableInterfaceType` and its `ParticipantRef.TableBound` participants are
  built, and R406 reuses `tit.participants()` verbatim. They are inherited,
  not re-mirrored; the spec names this so the reviewer sees the floor is
  shared with the read-side path, not dropped.

## Scope

- **INSERT** and **UPDATE** only. Both reach `buildDmlReturnExpression` and
  both silently mis-accept today; both are fixed by the one arm.
- **DELETE out.** It returns an encoded ID (the row is gone; `RETURNING`
  carries only the PK), so there is nothing to discriminate. Its own
  classifier already rejects projected `@table` returns.
- **UPSERT out.** `MutationInputResolver.resolveInput` refuses UPSERT
  outright pending R144/R145 (`MutationInputResolver.java:351-356`), so it
  never reaches the chokepoint. The `Discriminated*` arm would ride the same
  `buildDmlReturnExpression` path for free once UPSERT's own
  cardinality-safety gate lifts; no R406 work is needed then.
- **`@asConnection` / Connection out of MVP**, matching R405 and the
  existing `validateReturnType` Connection rejection.
- **Union returns out**, permanently (DML `@mutation` returns follow the
  `@table`/interface surface; a union is not `@table`-backed).
- Bulk single-table-interface INSERT (`in: [ContentInput!]!` →
  `[Content!]!`) rides `MutationBulkDmlRecordField` / the bulk chokepoint;
  the per-row subtype is selected by each input row's discriminator column,
  identical to the single case. In MVP if the bulk path's return-shape
  dispatch also funnels through `buildDmlReturnExpression`; otherwise a
  follow-up. (Confirm at implementation which bulk return arm the walker
  produces.)

## Tests

Behaviour pinned at the pipeline and execution tiers; code-string
assertions on generated bodies are banned (design principles).

### Unit / classification (`GraphitronSchemaBuilderTest`)

- Add a case asserting a DML `@mutation(typeName: INSERT)` returning a
  single-table discriminated interface classifies to
  `MutationInsertTableField` whose `returnExpression` is
  `DmlReturnExpression.DiscriminatedSingle` carrying the discriminator
  column, the known discriminator values, and the participant set (not
  `ProjectedSingle`). Add an UPDATE twin asserting `MutationUpdateTableField`
  + `DiscriminatedSingle`, and a list twin (`[Content!]!`) asserting
  `DiscriminatedList`.
- Add a regression pin that the same shape no longer classifies to
  `ProjectedSingle`/`ProjectedList` (the pre-R406 silent-accept), so the
  fix is pinned against reversion.

### Unit / validation

Pin that DELETE (`@mutation(typeName: DELETE): Content`) and a
Connection-wrapped interface return still reject through the existing
`TableBoundReturnType` floors, and that the coverage pin (interface DML ⇒
`Discriminated*` arm) holds.

### Pipeline tier

SDL → classified model → generated `TypeSpec`: a DML INSERT returning a
single-table discriminated interface (interface + two `@discriminator`
implementers, one with a cross-table `@reference` field) produces a
`MutationInsertTableField` + `DiscriminatedSingle`, and a fetcher whose
follow-up SELECT projects `__discriminator__` and a discriminator-gated
cross-table `LEFT JOIN` with a **PK-IN** source keyed off the `RETURNING`
keys (shape assertions on the model + `TypeSpec`, not on method-body
strings). Add the UPDATE twin.

### Execution tier (`graphitron-sakila-example`, real PostgreSQL)

Reuse the existing `Content` / `FilmContent` / `ShortContent` fixture
(`schema.graphqls:1751-1845`; shared `content` table, `CONTENT_TYPE`
discriminator, `FilmContent.rating` cross-table `@reference` to `film`,
`ShortContent.description` same-table-but-SHORT-only). Add:

- An `input ContentInput @table(name: "content")` carrying `title` +
  `contentType` (`@field(name: "CONTENT_TYPE")`) + the FILM/SHORT-specific
  same-table columns, and a `createContent(in: ContentInput!): Content
  @mutation(typeName: INSERT)` mutation next to the existing DML fixtures
  (`schema.graphqls:2263+`). Optionally an UPDATE twin.
- A `DmlTableInterfaceReturnExecutionTest` modelled on the R405 execution
  test: INSERT a `CONTENT_TYPE = 'FILM'` row and assert the return routes to
  `FilmContent` by `__typename` (off the live discriminator, the observable
  proof a class-dispatch could not produce), populates `FilmContent.rating`
  through the discriminator-gated cross-table join, and leaves
  `ShortContent.description` `null` on the FILM row; INSERT a `'SHORT'` row
  and assert it routes to `ShortContent` with `description` populated and no
  `rating` join fired; assert an unknown-discriminator INSERT commits the row
  yet returns `null` (the documented write/read asymmetry).

Full reactor green under `mvn -f graphitron-rewrite/pom.xml install
-Plocal-db` (the `-Plocal-db` catalog-jar footgun applies).

## Roadmap entries

On implementation: trim this file to any residual, flip `status:` to
`In Review`, regenerate the README. On approval: delete the file and add a
one-line `changelog.md` entry citing the landing SHA, `R405` (shared
read-side re-projection helper), and whether R406 or R405 extracted that
helper. If R406 lands first, its changelog entry records the helper
extraction so R405's implementer consumes rather than re-extracts.

## Notes

- The `Discriminated*` arms are the DML siblings of R405's
  `*ServiceTableInterfaceField`: same participant model
  (`ParticipantRef.TableBound`, non-null `discriminatorValue`, populated
  `crossTableFields`), same drop contract, differing only in what feeds the
  by-PK SELECT (DML `RETURNING` keys vs service-returned PKs).
- Reuses `MultiTablePolymorphicEmitter.DISCRIMINATOR_COLUMN`,
  `buildInterfaceFieldsList`, `buildCrossTableAliasDeclarations`,
  `buildCrossTableJoinChain`, `buildDiscriminatorFilter`, and the
  per-`TableInterfaceType` `TypeResolver` verbatim.
- The joined-table (R389, class-table `Party`/`Subject`) participant shape is
  not exercised by the `content` fixture (single-table), but the shared
  field-list builder tolerates it; a joined-table DML interface return would
  fall out of the same arm if a fixture were added.
