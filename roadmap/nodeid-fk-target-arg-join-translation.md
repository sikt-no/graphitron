---
id: R57
title: "FK-target @nodeId JOIN-with-translation filter emission (argument + input field)"
status: In Review
bucket: architecture
priority: 5
theme: nodeid
depends-on: []
last-updated: 2026-08-17
---

# FK-target @nodeId JOIN-with-translation filter emission

## Second-pass rework (the blocking defect, closed)

The second-pass review passed the delivery at `6a7b22d` against the contract below but blocked the gate
on one introduced defect: `FieldBuilder.classifyArgument`'s plain-`@reference` arm passed
`new FilterBinding.Remote()` unconditionally, on a precondition it had not checked.
`@reference(path: [])` is legal SDL (`path` is `[ReferenceElement!]!`), and on an *argument*
`parsePath` runs with a null `targetSqlTableName`, so the FK-inference block is skipped and the path
stays empty with no error. `resolveColumnForReference` then resolves the column against the field's own
table, and `ColumnBackedArg`'s compact constructor throws
`IllegalArgumentException: ColumnBackedArg 'title' binds Remote but carries an empty joinPath` out of
classification. Before this item the same schema classified fine and emitted a bare local `Eq`, so it
was a regression from "works" to "the generator crashes", surfacing as an untyped exception rather than
a named build-time diagnostic.

The invariant was right and the call site was wrong. Of the two closes the reviewer offered, this took
the first: bind `Local(List.of(refColumnRef))` when `refPath.elements()` is empty, mirroring the
input-field sibling in `BuildContext.classifyInputField` whose `path.elements().isEmpty() ? Local(...)
: Remote()` fork was the tell. Reasoning for that choice over rejecting an element-less `@reference` as
a structural author error:

- It restores the pre-item behavior exactly, so the item stays a decode-side emission change and adds
  no user-visible SDL rejection of its own.
- The asymmetry the reviewer pointed at is the actual defect, and this removes it. Rejecting on the
  argument side alone would have re-created it pointing the other way, because an element-less
  `@reference` is equally inert on an input field (that site also passes a null target, so its empty
  path also falls back to the own table).
- "The directive is inert here, reject it" is therefore not an argument-arm question but one decision
  over both positions, with a validator mirror and a manual note. That is its own item, filed as
  `inert-element-less-reference-rejection`; the cost of deferring it is one arm that emits the same
  predicate the directive-less arm would.

Both surfaces are now pinned in `ReferenceFilterRemoteColumnPipelineTest`
(`surface2_scalarArg_elementLessPath_bindsLocal`, `surface1_inputFilterField_elementLessPath_bindsLocal`),
in the file that already owns the local-vs-remote discrimination matrix for plain `@reference` filters
rather than in the `@nodeId`-scoped `NodeIdPipelineTest`. The argument case was confirmed to fail with
exactly the reviewer's exception before the fix and to pass after it.

The reviewer's two non-blocking notes: the fully-qualified `QueryField.QueryTableField` /
`GeneratedConditionFilter` references in `NodeIdPipelineTest`'s rewritten translated-FK argument case
now use the file's imports, and that file's `parent_node` fixture bullet no longer describes the
decode-side shape as the deferred JOIN-with-projection path (a sweep miss: the phrase legitimately
survives on the encode-side hits, which is why a bare grep read clean). The `multi-hop-nodeid-filter.adoc`
imprecision the reviewer flagged is filed as `multi-hop-nodeid-filter-single-fk-claim` rather than
folded in, since that page was out of this item's doc scope.

Everything else the second pass checked out stays as delivered: all four rail gates present and
exhaustive with the shared message text, `FilterBinding` collapsing all three spellings of the axis,
the `public`-schema fixture pairs and their `METADATA` entries, the widened
`code-generation-triggers.adoc` row, and a retirement sweep that comes back clean.

## What shipped

`@nodeId(typeName: X)` on an argument or filter input field whose containing table reaches
`X.table()` through a foreign key that targets columns *other than* `X`'s key columns
(`NodeIdLeafResolver.Resolved.FkTarget.TranslatedFk`) now emits a read-side filter instead of a
rejection. The decoded key has no column on the field's own table to bind against, so the predicate
binds `X`'s key columns on `X.table()` inside a correlated `EXISTS` over the FK: the same
`BodyParam.RemoteColumnPredicate` machinery a plain joined `@reference` filter already used. The
write and `@lookupKey` rails keep a deferral, now stated in their own words.

The local-vs-remote axis had three implicit spellings (an empty-`joinPath` sentinel on
`ColumnBackedArg`, a `Direct`-vs-`NodeIdDecodeKeys` extraction test in `remoteIfReferenceJoin`, and a
column slot whose referent depended on which case produced it). All three collapsed onto one sealed
component, `FilterBinding`, with arms `Local(List<ColumnRef> ownTableColumns)` and a payload-free
`Remote`. It replaced `liftedSourceColumns` on both reference carriers and went onto `ColumnBackedArg`
alongside its `joinPath`; `remoteIfReferenceJoin` and `translatedFkRejection` are gone.

Four rails gate on the binding with an exhaustive switch, sharing one message text minted by
`FilterBinding.remoteBindingUnsupported`: `MutationInputResolver.admitMutationInputFields` (INSERT),
`UpdateRowsWalker.classifyInto` and `DeleteRowsWalker.classifyInto` (UPDATE / DELETE, through their
own `UnsupportedInputFieldShape` arms, since a `Rejection.Deferred` does not type-check against their
`Rejection.AuthorError` channel), and `FieldBuilder.classifyPlainLookupKeyArg` (the query-side
lookup). Write-side emitters read their columns through a single `Local`-destructuring accessor that
throws on `Remote`, so a bypassed gate fails loudly instead of emitting a wrong statement.

Execution-tier fixtures landed in the `public` schema as planned (`xlat_parent` / `xlat_child` and the
composite `xlat_comp_parent` / `xlat_comp_child`), which needed `CREATE TABLE`s plus seed rows in
`init.sql`, two `NodeIdFixtureGenerator.METADATA` entries, and SDL in
`graphitron-sakila-example`'s existing `schema.graphqls`: no new Maven execution, `jooqPackage` or
`.graphqls`.

Landing notes worth keeping:

- The `ColumnBackedArg` unification held at one compact-constructor line per invariant, so the
  spec's split-the-leg trigger never fired. `Local`'s tuple is restated from `columns()` on that
  carrier and the constructor checks the arity agrees.
- `FkTargetConditionFilter` took the binding rather than a placeholder tuple; its old
  `liftedSourceColumns` slot had no reader.
- The `Remote`-requires-a-non-empty-`joinPath` invariant lives on the carriers, not on the
  payload-free arm, which is the only place that can see the path.
- `FilterBinding` needed a `HierarchyKindRegistryTest` entry (`WALKED_FACT`: the binding is decided
  by the same walk that produces the carrier).
- Naming the axis put every construction site on the hook for answering it, and one site answered
  from a stale precondition rather than from the path (see the rework section above). The lesson is
  that a `Remote` construction is only ever safe next to a non-empty-path check, so every one of them
  reads the path locally; none infers it from which arm it sits in.

## Out of scope (file separately if a real schema reaches them)

- Write-target translation (scalar-subquery SET / INSERT emission), which is what the four rail
  gates defer.
- Multi-hop translated paths and condition-join hops. The rejection surface for condition-join
  `@nodeId` paths is `NodeIdLeafResolver.resolveFkJoinPath`'s condition-step gate, upstream at
  classify time; `FkHop.narrow` is only the plan-time backstop and the validator mirrors in
  `GraphitronSchemaValidator.validateInputColumnBackedReferenceField` stay as-is.
- The output-side (encode-direction) JOIN-with-projection emitter, filed on its own as
  `nodeidreferencefield-join-projection-form`. This item is the decode direction and never depended
  on it; the false dependency in the old rejection message and its paraphrases is retired.

## Test surface

- `NodeIdLeafResolverTest`: `TranslatedFk` classification assertions unchanged.
- Pipeline tier: `NodeIdPipelineTest`'s `FK_TARGET_TRANSLATED_KEY_MISMATCH_BINDS_REMOTE` (argument)
  and `..._INPUT` (input field) assert the carrier shape and the one-hop `RemoteColumnPredicate`
  over `parent_node.pk_id` where they previously asserted a rejection.
  `TranslatedFkTargetRailGatesPipelineTest` covers all four rail gates plus a direct-FK carrier that
  must still be admitted, and asserts the shared message text rather than four substrings.
  `ColumnBackedArgInvariantTest` / `InputColumnBackedFieldInvariantTest` cover the new
  compact-constructor invariants. `ReferenceFilterRemoteColumnPipelineTest` gains the element-less
  `@reference(path: [])` cell on both surfaces, beside the direct-FK-stays-local guard that already
  lived there: the two together pin that the binding follows the path, in both directions.
- Execution tier: `TranslatedFkTargetFilterExecutionTest` pins semantics over the new `public` pairs:
  list and scalar argument forms, the input-field form, empty and omitted lists contributing no
  conjunct, a childless parent, the composite twin, and malformed / wrong-type ids throwing.

## Retired vocabulary

Grep targets for the sweep at the Done gate.

- The phrase "deferred until output-side JOIN-with-projection emission ships", and every paraphrase
  pairing this decode-side deferral with output-side projection ("parallel to the still-deferred
  output-side JOIN-with-projection"). The *encode*-side deferral keeps its own wording and is not a
  sweep target, so the sweep needs the decode-side coordinates, not a bare grep for
  "JOIN-with-projection".
- "which no emitter supports" and "This is the only shape the projection arms emit"
  (`NodeIdLeafResolver`'s `TranslatedFk` arm javadoc and `FkTarget` doc bullet).
- "rejected at classify time with a deferred-emission hint" and the bare "deferred-emission hint".
- "lifts to local FK columns (no join)" and the same claim restated in `FieldBuilder`'s `@reference`
  comment and in `MutationInputResolver.admitMutationInputFields`' reference-carrier arm ("no JOIN at
  the emit site"). No single string finds all of these, and the obvious candidate is a trap: `"no
  JOIN at the emit site"` greps to zero hits because `MutationInputResolver` line-wrapped it and the
  `LookupKeyField` javadoc spelled it "no JOIN context at the emit site". Grep `emit site` and `no
  join` case-insensitively and read the hits.
- "reference carriers stay outside the permits set" (`TableInputArg`'s javadoc), which was already
  false before this item.
- "the test surface for the rooted-at-parent JOIN-with-projection emission path"
  (`NodeIdFixtureGenerator`'s `parent_node` metadata comment) and, from the rework pass, the same
  fixture described as "forcing the rooted-at-parent JOIN-with-projection path" in
  `NodeIdPipelineTest`'s class javadoc. Both named a decode-side shape by the deferred encode-side
  emitter; the encode-side hits on that phrase are legitimate, which is what let this one read clean
  under a bare grep.
- "This arm is reached only for a path that leaves the field's own table" (`FieldBuilder`'s
  plain-`@reference` argument arm), which was false for `@reference(path: [])`.
- `translatedFkRejection` and `remoteIfReferenceJoin`, including the latter's two-meanings framing
  ("the two `liftedSourceColumns` meanings stay un-conflated", "The `Direct`-vs-`NodeIdDecodeKeys`
  extraction split is the discriminator").
- `liftedSourceColumns` as a slot and accessor name on
  `ArgumentRef.ScalarArg.ColumnBackedReferenceArg` and `InputField.ColumnBackedReferenceField`,
  together with prose calling it "the lifted tuple" as a slot that exists unconditionally. The name
  survives on `NodeIdLeafResolver.Resolved.FkTarget.DirectFk`, which still computes the lift.
- The `_DEFERRED` suffix on the two `NodeIdPipelineTest` case constants (renamed).
- "pathological" as a synonym for "unsupported" on this shape. The word is fine as a description of
  the schema and stays in fixture prose; what retires is its use to mean "the case we reject".
