---
id: R515
title: "INSERT derives write target from the payload's @table data field"
status: Ready
bucket: generator
theme: mutation-write
depends-on: [dml-emitted-mutation-table-grounding]
created: 2026-07-23
last-updated: 2026-07-23
---

# INSERT derives write target from the payload's @table data field

R457 gave DELETE a field-relative write target (`@mutation(typeName: DELETE, table: "...")`)
because a DELETE can never return the deleted row's `@table` type (R287), so the field was the
only place left to name the table. INSERT is the opposite case and got nothing: it still
classifies through `MutationInputResolver.resolveInput`, which hard-requires every argument to
be a `TableInputType` ("@mutation fields only accept @table input arguments; found 'input' of
type '...'"). So the R332 deprecation warning ("`@table` on input type 'X' is deprecated ...
the write target is derived from the consuming mutation field's resolved table. Remove `@table`
from this input.") is unactionable for INSERT: removing `@table` breaks the build, and
`@mutation(table:)` is documented DELETE-only (`TABLE_ARG_SUPPORTED_VERBS` is a one-element
`{DELETE}` set, with the unsupported-verb guard rejecting `table:` on INSERT loudly).

But an INSERT's write target is usually already named in the schema, on the return side. A
carrier payload's data field is a `@table`-backed element:

```graphql
type FilmInsertPayload { films: [Film!] }        # Film is @table(name: "film")
createFilms(input: [FilmInsertInput!]): FilmInsertPayload! @mutation(typeName: INSERT)
```

The structural scan (`BuildContext.scanStructuralDmlPayload`) already classifies that data
field as `DmlElementKind.Table(tableRef, elementTypeName)`; the `TableRef` is right there. The
same holds for a direct `@table` return (`createFilm(input: FilmInsertInput!): Film!`), which
`ReturnTypeRef.TableBoundReturnType` already carries. R457's spec dropped "rung 1"
(return-derived table) for DELETE specifically because R287 makes it unbuildable there; that
rationale does not apply to INSERT, where the inserted row's type is the natural return.

Motivating case: fs-plattform opptak-subgraph's
`opprettKvotesporsmalPreutfylling(input: [OpprettKvotesporsmalPreutfyllingInput]): KvotesporsmalPreutfyllingPayload! @mutation(typeName: INSERT)`.
The payload's data field is `[KvotesporsmalPreutfylling!]`, a `@table` type naming
`kvotesporsmal_preutfylling`, yet the input must still carry
`@table(name: "kvotesporsmal_preutfylling")` redundantly, and the lint warns about it.

## Today's mechanics (why the warning is unactionable)

Four coupled facts, each with its code seat:

1. **Input side.** INSERT is the lone verb still classifying through
   `MutationInputResolver.resolveInput` (`FieldBuilder` dispatches UPDATE and DELETE to their
   walker classifiers before the call; UPSERT is refused at the top of `resolveInput` as
   deferred). `resolveInput` rejects any non-`TableInputType` argument, so a plain input object
   never reaches classification. The `DmlWalkerInputArgResolution.RawArg` arm (R457) already
   models "the single input arg is not a `TableInputType`" as a normal outcome, but only the
   DELETE classifiers consume it; UPDATE translates it back to the legacy rejection verbatim,
   and INSERT never sees it because INSERT never calls `resolveDmlWalkerInputArg`.
2. **Write-target resolution.** `FieldBuilder.resolveDeleteWriteTarget` is the R457 precedent:
   precedence ladder (`@mutation(table:)` then input `@table`), input fields re-derived against
   the resolved table via `TypeBuilder.resolveInputFields` (factored out of
   `buildTableInputType` for exactly this dual use), and the validator-mirror obligation
   discharged by calling `GraphitronSchemaValidator.collectInputFieldRejections` at the
   field-derived call site. INSERT has no analogue.
3. **Binding side.** `RecordBindingResolver.groundDmlMutationField` grounds the
   `ProducerBinding.DmlEmitted` observation for a DML payload SDL type only via
   `findSingleTableInputArg`: no `@table`-bearing input argument, no grounding, and the payload
   type never classifies as a result-backed carrier. The DELETE half of this gap (the grounder
   never reads `@mutation(table:)`, so `@mutation(table:)` + payload DELETE rejects) is R514
   (`dml-emitted-mutation-table-grounding`), which this item depends on: R514 extracts the
   phase-portable write-target precedence helper (SDL directives + catalog only, callable from
   the binding walk) that this item's return-derived rung extends.
4. **The R332 carve-out.** `GraphitronSchemaBuilder.encodedWriteTargetInputTypes` suppresses
   the deprecation warning for inputs consumed by encoded-ID/scalar-return INSERT/UPSERT,
   precisely because for those shapes the input's `@table` is today the only signal naming the
   write target. Its javadoc already states the retirement condition: "This set is retired once
   the write target is field-relative."

## Design

An INSERT write-target precedence ladder, symmetric with DELETE's but with the return-derived
rung DELETE could not have:

1. **Rung 1 (preferred): the return's own table.** `TableBoundReturnType.table()` for a direct
   `@table` return; the carrier scan's `DmlElementKind.Table.table()` for a payload return
   (`ResultReturnType`). This is the derivation R332's warning text already promises.
2. **Rung 2: `@mutation(table:)` on the field.** Admit INSERT into `TABLE_ARG_SUPPORTED_VERBS`
   (designed as a single-edit generalisation point) for the shapes with no table in sight on
   the return side: the encoded-ID/scalar-return INSERT. This retires
   `encodedWriteTargetInputTypes` and lets the R332 warning fire on those inputs too, per that
   set's own retirement note.
3. **Rung 3 (deprecated bridge): the input's `@table`.** Kept for migration, same as DELETE.

"Ladder" is shorthand; the honest shape is a **must-agree lattice with single-source
fallback**: where rung 1 is present, other present rungs must agree (rejections below), and
the only genuine outranking is DELETE-style silent outranking of the deprecated bridge.

Cross-check semantics, decided per rung pair:

- **Rung 1 vs rung 3 (return table vs input `@table`):** keep today's
  `requireDmlDataTableMatchesInputTable` rejection on disagreement, byte-identical. Unlike
  DELETE's silent outranking of the bridge, this check already exists for payload INSERT, so
  keeping it accepts and rejects exactly the schemas today's build does; silently outranking
  would newly admit schemas that today reject.
- **Rung 1 vs rung 2 (return table vs `@mutation(table:)`):** reject on disagreement. The
  RETURNING projection that fills the data field reads from the write target's table, so a
  `table:` naming a different table than the return's `@table` cannot emit a coherent
  statement; silently ignoring an author-written directive argument is the
  green-build-wrong-intent failure mode. (Equal values are admitted and redundant.)
- **Rung 2 vs rung 3 (`@mutation(table:)` vs input `@table`, rung 1 absent):** silent
  outranking, rung 2 wins, byte-matching `resolveDeleteWriteTarget`'s documented treatment of
  the same directive pair. The bridge is the thing the deprecation path wants removed;
  promoting it into a build-breaking conflict participant would invert that, and the same two
  directives must not behave differently across verbs.
- **No rung resolves** (ID/scalar return, plain input, no `table:`): typed rejection leading
  with the preferred fix, mirroring DELETE's no-write-target message: name the table with
  `@mutation(table:)` or return the row's `@table` type; `@table` on the input is the
  deprecated bridge.

## Mechanism

1. **Intercept INSERT before `resolveInput`,** following the UPDATE/DELETE pattern: dispatch in
   `FieldBuilder`'s `@mutation` classifier to `classifyInsertTableField` (direct
   `@table`/ID-return) and `classifyInsertPayloadField` (`ResultReturnType`), both calling a
   shared `resolveInsertWriteTarget` built on `resolveDmlWalkerInputArg` (whose `RawArg` arm
   INSERT now consumes instead of never reaching). This makes `resolveInput` dead code except
   the UPSERT deferral: retire it, keeping `MutationInputResolver`'s shared statics
   (`validateReturnType`, `parseMutationTableArg`, `parseMultiRow`) and moving the UPSERT
   deferral rejection to the dispatch. The INSERT-specific admissibility rules `resolveInput`
   enforces today (multiRow rejection, per-field directive rules, nesting rules) move with it,
   preserved verbatim; the payload-shape logic currently inline after `resolveInput` (the
   `DmlElementKind` dispatch, table-mismatch check, error-channel detection, bulk-vs-single and
   the bulk-UPSERT rejection) moves into `classifyInsertPayloadField`, mirroring how
   `classifyDeletePayloadField` absorbed the shared-path DELETE arm.
2. **`resolveInsertWriteTarget`:** the lattice above, built on R514's phase-portable
   precedence helper (extended with the return-derived rung). On the field-derived path
   (rung 1 or 2, no `@table` on the input), re-derive the input fields through
   `TypeBuilder.resolveInputFields` against the resolved table and mirror
   `GraphitronSchemaValidator.collectInputFieldRejections` at the call site, as
   `resolveDeleteWriteTarget` does (a field-derived input never lands in the registry
   `validateTableInputType` walk). That mirror alone is **not** the full validator-mirror
   obligation for INSERT: unlike DELETE (whose per-field admission is the `DeleteRowsWalker`),
   INSERT's admission is `admitMutationInputFields`, `rejectPlainColumnCollision`, and
   `rejectInputFieldDirectives` (the composite-key carve-out, the plain-column-collision
   reject, the `@lookupKey`/`@condition` rejections at every nesting depth). The complete
   admission set runs in one destination, `resolveInsertWriteTarget`, over the resolved
   `InputField` list regardless of path, so both paths share one admission call rather than
   two loops that must agree.
3. **Keep the INSERT leaves on `TableInputArg`.** `MutationInsertTableField`,
   `MutationDmlRecordField`, and `MutationBulkDmlRecordField` carry
   `ArgumentRef.InputTypeArg.TableInputArg`; on the field-derived path, construct it from the
   re-derived fields (for INSERT the binding set is structurally empty and `setFields` is
   empty, so the construction is mechanical). Migrating INSERT to the slim `InputArgRef` +
   walker-carrier shape is a worthwhile follow-up but a different axis; byte-identical carriers
   on the `@table`-input path are the acceptance bar here.
4. **Grounding.** Extend R514's phase-portable precedence helper (which
   `RecordBindingResolver.groundDmlMutationField` and the write-target resolvers both call)
   with the return-derived rung: the payload's single data field whose element type carries
   `@table`, resolvable from SDL + catalog and therefore callable from the binding walk, ahead
   of R514's `@mutation(table:)` and input-`@table` rungs. The write target is one fact; the
   helper is its single producer across both phases, so the grounded `DmlEmitted.table()`
   cannot silently diverge from the classified write target. The enforcer is a pipeline-tier
   test pinning grounded-table == classified-write-target across all three rungs (not merely
   "grounding happens"). The grounder's verb gate reads `TABLE_ARG_SUPPORTED_VERBS` per R514
   position 2, so admitting INSERT there flows to the grounder automatically. Cardinality
   lifts from the input arg's list shape as today.
5. **R332 signal.** Delete `encodedWriteTargetInputTypes` (its retirement condition is met);
   extend the warning's replacement wording for INSERT-consumed inputs the way
   `deleteConsumedInputTypes` selects DELETE wording: name the return-derived fix ("the write
   target is derived from the field's return type; remove `@table` from this input") and
   `@mutation(table:)` for encoded returns.
6. **UPSERT** stays deferred (refused upstream); when it un-defers it inherits this ladder,
   since the dispatch and `resolveInsertWriteTarget` are the single edit points. Nothing here
   deepens the UPSERT deferral.

## Guard rails

- Ambiguous or absent derived table rejects with today's diagnostic quality: the no-rung
  rejection names all three fixes in preference order; unknown `table:` values reject through
  the existing `unknownTableRejection`; the payload scan's `Reject` arm passes through
  unchanged.
- A pure-ID payload (ID-element data field) stays rejected on INSERT (the PK-echo permit is
  DELETE-only); the rejection text is already in place and keeps firing before write-target
  resolution needs to care.
- `mvn graphitron:validate` and the LSP see the same behaviour for free (both run this
  classifier); the unsupported-verb rejection for `table:` narrows from {INSERT, UPDATE,
  UPSERT} to {UPDATE, UPSERT} via the one `TABLE_ARG_SUPPORTED_VERBS` edit.

## Tests

- Pipeline-tier classification (the `MutationTableArgClassificationTest` pattern): payload
  INSERT with `@table` dropped from the input classifies to a byte-identical carrier vs the
  `@table`-on-input form; same for direct `@table`-return INSERT; bulk (list input, list data
  field) variant included since the motivating case is bulk; mismatch rejections (rung 1 vs 3,
  rung 1 vs 2) with message assertions; rung 2 silently outranks rung 3; no-rung rejection
  message leads with the preferred fix; validator-mirror parity on the field-derived path
  spanning the full admission set, not just directive rejects: a composite-`@nodeId`
  carve-out case, a plain-column-collision case, and a `@lookupKey`/`@condition` case each
  reject identically on both paths; `table:` on UPDATE still rejects (guard narrowing is
  deliberate).
- Grounding: payload INSERT without `@table` input grounds `DmlEmitted` (the
  `SingleRecordPayloadPipelineTest` surface), and the grounded `DmlEmitted.table()` equals the
  classified leaf's write target on each of the three rungs (the divergence enforcer).
- Execution-tier: a sakila INSERT fixture drops `@table` from its input (payload-returning,
  bulk, mirroring the motivating case) and round-trips against PostgreSQL.
- `TableOnInputDeprecationWarningTest`: warning now fires on the previously carved-out
  encoded-INSERT inputs and carries the INSERT replacement wording; the carve-out set is gone.
- Drift guards stay green (`RejectionSeverityCoverageTest`, `SealedHierarchyDocCoverageTest`)
  for any new sealed rejection arms.

## Docs

`mutation.adoc` (INSERT write-target section, `table:` parameter table gains INSERT),
`table.adoc` (WARNING block covers INSERT), `deprecations.adoc` (the INSERT bridge),
`code-generation-triggers.adoc` (INSERT trigger row). The user manual's INSERT examples drop
`@table` from inputs.

## Relationship to other items

- **R97 (`consumer-derived-input-tables`)** is the home of the general `@table`-on-input
  removal; this item is the INSERT-scoped slice of its Phase 2/2b axis, exactly as R457 was the
  DELETE-scoped slice. It implements R97 Phase 2b's INSERT half (field-relative write target,
  `encodedWriteTargetInputTypes` retired) ahead of R97's query-side phases; R97's Phase 3
  (directive-scope narrowing) still waits on the query-side migration.
- **R514 (`dml-emitted-mutation-table-grounding`, Spec)** is the hard dependency: it fixes the
  DELETE half of the grounder gap and, decisively for this item, extracts the phase-portable
  write-target precedence helper (SDL + catalog only, shared by the grounder and the
  classify-phase resolvers) and routes the grounder's verb gate through
  `TABLE_ARG_SUPPORTED_VERBS`. This item extends that helper with the return-derived rung and
  adds INSERT to the verb set; landing order is R514 first.
- **R457** (shipped): the DELETE precedent whose factoring this reuses
  (`resolveDmlWalkerInputArg`'s `RawArg` arm, `resolveInputFields`, the validator mirror, the
  `TABLE_ARG_SUPPORTED_VERBS` single-edit point).
- **R332** (shipped): the deprecation signal this makes actionable for INSERT.

## Out of scope

- UPSERT un-deferral (inherits the ladder when it lands, no new design needed here).
- Migrating the INSERT leaves off `TableInputArg` onto the slim `InputArgRef` + walker-carrier
  shape (a follow-up refactor axis; file separately if wanted).
- R97's query-side consumer-derived resolution (filters, lookups, `argMapping` grouping) and
  Phase 3 directive-scope narrowing.
