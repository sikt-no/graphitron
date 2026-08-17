---
id: R57
title: "FK-target @nodeId JOIN-with-translation filter emission (argument + input field)"
status: Ready
bucket: architecture
priority: 5
theme: nodeid
depends-on: []
last-updated: 2026-08-17
---

# FK-target @nodeId JOIN-with-translation filter emission

## Review feedback (In Review -> Ready, second pass)

The delivery at `6a7b22d` is complete against the contract below and the full reactor is green under
`-Plocal-db`. One introduced defect blocks the gate; everything else in this section is a note, not a
requirement.

**Blocking: the argument-side plain-`@reference` arm asserts `Remote` on a path it has not checked.**
`FieldBuilder.classifyArgument`'s `DIR_REFERENCE` arm passes `new FilterBinding.Remote()`
unconditionally, under a comment claiming "this arm is reached only for a path that leaves the field's
own table". That precondition does not hold. `@reference(path: [])` is legal SDL (`path` is
`[ReferenceElement!]!`, and an empty list is the documented "infer the FK" spelling on a field), and on
an *argument* `parsePath` is called with a null `targetSqlTableName`, so the FK-inference block is
skipped and the path stays empty with no error. `resolveColumnForReference` then resolves the column
against the field's own table, and `ColumnBackedArg`'s new compact constructor throws.

Reproducer, verified against `6a7b22d`:

```graphql
type Film @table(name: "film") { title: String }
type Query { films(title: String @reference(path: [])): [Film!]! }
```

yields `IllegalArgumentException: ColumnBackedArg 'title' binds Remote but carries an empty joinPath;
a remote predicate has no terminal table to reach`, thrown out of classification. Before this item the
same schema classified fine and emitted a bare local `Eq` on `film.title` (the retired
`ca.joinPath().isEmpty() ? inner : RemoteColumnPredicate(...)` fork handled it), so this is a
regression from "works" to "the generator crashes", and it crashes as an untyped exception rather than
the named build-time diagnostic the "Rejections: validator mirrors classifier invariants" principle
asks for.

The compact-constructor invariant is right; the call site is what is wrong. The sibling input-field
site in `BuildContext` already has the correct shape (`path.elements().isEmpty() ? Local(...) :
Remote()`), and that asymmetry is the tell. Two ways to close it, and the choice is the implementer's:

- Mirror `BuildContext`: bind `Local(List.of(refColumnRef))` when `refPath.elements()` is empty. Keeps
  the pre-item behavior exactly, at the cost of an arm that emits a predicate for a directive that
  said nothing.
- Reject an element-less `@reference` on an argument as a structural author error, beside the existing
  repeated-`@reference` rejection a few lines above. Arguably the better answer, since the directive is
  inert in that position, but it is a user-visible behavior change and wants a sentence in the spec.

Either way the case wants a pipeline-tier test; there is none today, which is why the delivery's own
green build did not catch it. This also retires the spec's "of the four construction sites in
`FieldBuilder`, three pass an empty `joinPath` and the fourth ... is always remote" claim, which the
implementation faithfully encoded and which is simply false.

**Non-blocking, file separately if worth it.** `docs/manual/how-to/multi-hop-nodeid-filter.adoc` says
"With a single direct FK, that property is automatic: the FK source columns are on the parent's row, so
the predicate is `WHERE parent.fk_columns IN (decoded_keys)`. No JOIN, no subquery". That was already
imprecise (the translated single-hop shape never had the property) and this item makes it wrong in a new
way: the shape now emits an `EXISTS`. The page is scoped to multi-hop chains and was not in this item's
doc scope, so it is a follow-up, not rework.

**Non-blocking.** `NodeIdPipelineTest`'s rewritten translated-FK argument case reaches for
`no.sikt.graphitron.rewrite.model.QueryField.QueryTableField` and `GeneratedConditionFilter` by fully
qualified name inline where the file imports its other model types. Cosmetic.

Everything else checks out: all four rail gates present and exhaustive with the shared message text,
`FilterBinding` collapsing all three spellings of the axis, the `public`-schema fixture pairs and their
`METADATA` entries, the widened `code-generation-triggers.adoc` row, and a retirement sweep that comes
back clean (the surviving `JOIN-with-projection` hits are all the encode-side deferral this item
explicitly left alone, and `liftedSourceColumns` survives only on `DirectFk` and the resolver internals
as the spec allowed). No code-string assertions on generated method bodies anywhere in the new tests;
the execution tier's SQL-token checks are the sanctioned `ExecuteListener` structural approach.

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
  compact-constructor invariants.
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
  (`NodeIdFixtureGenerator`'s `parent_node` metadata comment).
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
