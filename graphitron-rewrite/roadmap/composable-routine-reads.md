---
id: R384
title: "Composable routine support: lift @routine beyond the root-only projected node"
status: Spec
bucket: feature
priority: 5
theme: structural-refactor
depends-on: []
created: 2026-06-26
last-updated: 2026-06-26
---

# Composable routine support: lift @routine beyond the root-only projected node

> `@routine` today binds a jOOQ table-valued function as a **root** Query field
> and nothing else; a child-positioned read routine is explicitly rejected
> (`FieldBuilder.java:1945-1950`, "a child-positioned read routine is not yet
> implemented"). The single shape it allows is "the routine result is the root
> projected node," with no parent correlation. This item ships the high-value,
> already-templated increment: **child-positioned routine reads**, modeled on the
> `@tableMethod` child path (`ChildField.RecordTableMethodField`), reusing the
> `@routine` directive (lift the root-only guard) rather than expanding the
> `@reference` grammar. It deliberately defers the full "routine as an arbitrary
> node in a `@reference` join path" vision to ride on R333's unbuilt `tableExpr`
> / `JoinStep` reshape, where that model is being decided holistically.

## What is missing today

`@routine(name: String!, argMapping: String)` resolves a jOOQ table-valued
function and emits `Routines.<method>(args)` then `.from(thatTable)`. The
classifier mints exactly one model: `QueryField.QueryRoutineTableField` carrying
a `RoutineRef` (`FieldBuilder.java:3528-3535`, `RoutineRef.java`). Three hard
limits, all enforced at classify time:

* **Root-only.** Any non-`RootType` parent carrying `@routine` is rejected as
  `UnclassifiedField` (`FieldBuilder.java:1945-1950`).
* **Bare projected node.** The result table is the FROM directly; the routine
  cannot correlate to a parent, cannot be a join target, cannot paginate.
* **GraphQL-args-only binding.** `RoutineRef.ArgBinding(routineParamName,
  paramType, graphqlArgName)` binds each routine IN parameter to a *field
  argument* (`RoutineDirectiveResolver.java:109-142`); there is no path for a
  parent value to reach the routine.

R333 ("The Graphitron data model", *The table expression* / *The join path*)
diagnoses this precisely: the existing code "is broken not because the routine
result is projected, but because that is the *only* shape it allows, with no
composition" (R333:1846-1848). The common real-world ask, "a child field whose
rows come from a table-valued function parameterized by the parent and/or the
field's arguments," has no expression.

## Should this be done, and is "expand `@reference`" the right vehicle?

The prompt that spawned this item mused that *expanding `@reference`* (per R333)
might be the way forward. Research into the actual code says: the **value** is
worth shipping now, but the literal "expand `@reference`" framing couples this
work to an unsettled model and concentrates the negative-work risk. Three options
were weighed (and the design-principles consultation backed B):

* **Option A — full R333 vision now.** Make `ReferenceElement` an `@oneOf` of
  table-element vs routine-element (routine `name` + `argMapping` + an `on`
  derivation) and reshape `JoinStep` from today's `FkJoin | ConditionJoin |
  LiftedHop` into `(target: tableExpr arm, on: ColumnPairs | Predicate)`, so a
  routine node can sit anywhere in a path. **Rejected for now.** This is exactly
  the surface R333 *defers* ("The `@oneOf` SDL surface for the path element ...
  is the deferred follow-on", R333:906-907; open residue (a)/(b) at
  R333:1843-1846), and R333 is `status: Spec`, mid-pivot, with that `JoinStep`
  reshape unbuilt. Building the grammar + reshape ahead of R333 either pre-empts
  an unsettled model or builds against today's flat `JoinStep` that R333 then
  tears out. The negative-work risk lives almost entirely here.

* **Option B — narrow increment, modeled on `@tableMethod`** *(chosen)*. Keep the
  `@routine` directive; lift the root-only guard; add child-positioned routine
  reads as the routine analogue of `ChildField.TableMethodField` /
  `RecordTableMethodField`. The routine still materializes a table; parent
  correlation reuses the existing child-fetcher machinery. No `ReferenceElement`
  grammar change, no `JoinStep` reshape. `@tableMethod` at a child position
  *already does* what `@routine` refuses to do, a method-returned table composed
  with a parent-correlation path, so B mirrors an already-shipped, already-blessed
  pattern. R333 itself frames routine support as additive: "New capability is a
  new *target* arm (`RoutineCall`) ... not a new step type" (R333:901), and names
  this as "likely its own Backlog item once the surface settles" (R333:1848). B
  gives R333 a sibling leaf to dissolve alongside `TableMethodField`, not debt to
  unwind.

* **Option C — do nothing / wait for R333.** Rejected: child routine reads are a
  recurring, concrete ask, and B unblocks them without waiting on the whole data
  model to land.

**Conclusion: yes, do it, as Option B.** Take the slice at the leaf + capability
seam that survives R333 unchanged (a new `ChildField` variant reusing
`MethodBackedField`-style capabilities and the existing `SplitRowsMethodEmitter`
path), and let the grammar / `tableExpr` unification ride on R333/R314.

## The design (Option B)

A child routine field is the `RecordTableMethodField` shape with `RoutineRef`
substituted for `MethodRef` at the terminal table-expression slot:

* **Directive surface — reuse `@routine`, widen the position.** Change
  `@routine` from `on FIELD_DEFINITION` (root-only by classifier guard) to permit
  a child position; the SDL author types nothing new. `RoutineDirectiveResolver
  .resolve(parentTypeName, fieldDef, isRoot)` is *already* parameterized on
  root-ness, so the child arm lands there with no new directive grammar. This
  honors "directives carry only what the SDL author needs to say": the flat
  `name` + `argMapping` is the preferred shape, and the `@oneOf ReferenceElement`
  of Option A is the input-wrapper-with-two-of-four-slots-filled smell that R333
  already deferred for the same reason.

* **Model — a new `ChildField` leaf.** Add a child routine variant (working name
  `RecordRoutineTableField`, sibling to `RecordTableMethodField` at
  `ChildField.java:564-587`) implementing the same capability set
  (`ChildField`, a `MethodBackedField`-analogue, `BatchKeyField`,
  `WithErrorChannel`) and carrying its parent-correlation `joinPath` /
  `parentCorrelation` / `sourceKey` / `loaderRegistration` on the *field* record,
  with `RoutineRef` as the table-expression carrier. **Do not** fold parent
  correlation into `RoutineRef`; it stays the routine-call-plus-arg-bindings
  carrier, mirroring how `RecordTableMethodField` carries `joinPath` separately
  from its `MethodRef`. (A `@table`-parent, per-row variant analogous to
  `TableMethodField` may also be in scope; see open questions.)

* **Emit — reuse the child fetcher path.** The routine call replaces the
  developer-method call at the FROM slot (`Routines.<method>(args)`), and the
  parent correlation reuses the same machinery the `@tableMethod` child fetcher
  emits today (`TypeFetcherGenerator.buildChildTableMethodFetcher`,
  `InlineTableFieldEmitter`). No `JoinStep` reshape; no new emitter.

## The one place this brushes against R333: parent↔routine correlation

This is the gating design question to settle **before Ready**. A routine result
is **FK-less**, so it cannot be FK-joined to the parent the way `@tableMethod`'s
result is. Two correlation mechanics are candidates, and today's `JoinStep` model
accommodates them unequally (verified against `JoinStep.java`):

* `JoinStep.FkJoin` mandates a non-null `ForeignKeyRef` and renders
  `.join(alias).onKey(Keys.FK)` (`JoinStep.java:172-191`,
  `InlineTableFieldEmitter.java:146-148`). A name-matched key has no FK constant,
  so an FK-less correlation **cannot** be an `FkJoin` as it stands.
* `JoinStep.ConditionJoin` carries an *author-written* condition method as the ON
  clause (`JoinStep.java:214-230`). A routine correlation *can* be expressed this
  way today, at the cost of making the author write a condition method.

The two mechanics:

1. **Name-matched correlation join** (R333:865-877). The parent correlates to the
   routine result by a key whose columns are matched **by name** against the
   routine's output columns (PK default, or a named UK), with a build-time
   integrity check ("the routine's result columns must expose the key's columns
   by name", R333:876). This is the genuinely new on-derivation: a column-pair
   with no FK constant and no author condition method. It has **no home** in
   today's `JoinStep` (not `FkJoin`, which needs an FK; only awkwardly
   `ConditionJoin`, which needs an authored method). The *minimal* model touch is
   the open question, see below.

2. **Parent value as a routine argument** (lateral / `CROSS APPLY` style). The
   parent's key is passed as an IN parameter to the routine call itself
   (`Routines.<method>(parentRow.key, args)`); there is no join, the routine is
   parameterized per parent. This is arguably the *more natural* TVF child shape,
   but it is **new binding surface**: today neither `@routine` nor `@tableMethod`
   lifts a parent column into a call argument (`@sourceRow` lifts a parent key
   into a *correlation tuple*, not into a parameter). It would add a
   `ParamSource`-like provenance to `RoutineRef.ArgBinding`.

**Leaning (to confirm before Ready):** scope the first slice to mechanic (1),
name-matched correlation, keeping `RoutineRef.ArgBinding` GraphQL-args-only to
match both the current `RoutineRef` and `@tableMethod`; let parent values reach
the routine only through the correlation predicate. Mechanic (2) is powerful but
is a distinct binding decision worth taking deliberately, not by default. This
leaning needs a sanity check against the actual downstream use cases (which shape
do real routines want?) before sign-off.

**The minimal-model-touch question.** Mechanic (1) needs an FK-less,
name-matched column-pair `on`. Decide in the Spec whether to (a) express it as a
`ConditionJoin` over a *generated* (not authored) name-match predicate, (b) relax
`FkJoin` to carry a non-FK column-pair (likely wrong: it is named `Fk` and keyed
on a `ForeignKeyRef`), or (c) add one small `JoinStep` arm for the name-matched
correlation. This is the single spot B touches structure R333 owns, so keep the
addition as small as possible and cross-reference R333 so the later `tableExpr`
reshape absorbs it cleanly. Do **not** reach for the full `(target, on)` reshape
here.

## Scope boundaries

In scope:

* Lift the root-only guard; classify a child-positioned `@routine` into the new
  `ChildField` leaf.
* Parent correlation via the chosen mechanic (leaning: name-matched key).
* Validator mirrors the new classifier arm (below).
* Pipeline + execution tests.

Deferred (explicitly *not* this item):

* **Routine as a mid-path *intermediate* join target** (a non-FK hop continuing
  *out* of a routine into a further table). This is R333's exotic case and
  genuinely wants the `tableExpr` / `JoinStep` reshape; it stays with R333.
* **`@oneOf ReferenceElement` grammar** (table | routine × key | condition).
  Deferred per R333:906-907.
* **Parent-column-into-routine-parameter binding** (mechanic 2), unless the
  use-case review flips the leaning.
* **Connection / pagination over a routine result.** Out of scope here.

## Validator mirrors the classifier

Per "validator mirrors classifier invariants": the child-routine rejection at
`FieldBuilder.java:1945-1950` does not disappear, it **narrows**. In the same
commit that lifts it:

* Add a build-time `Rejection.AuthorError` for the name-match integrity failure
  (routine result columns do not expose the correlation key by name), so the
  author gets a pointed diagnostic at classify time, not an emit-time
  `UnsupportedOperationException`.
* Add a rejection for the deferred mid-path-routine shape (R333's "open residue
  (b)", the routine-position invariant), so an author who writes the unsupported
  shape is told so, rather than silently mis-generating.

`GraphitronSchemaValidator` currently no-ops on `QueryRoutineTableField`
(R300 pinned its invariants at classify time); the new child variant follows the
same "classifier is the source of truth" discipline, with the two rejections
above expressed there.

## Tests

* **Pipeline-tier** (primary behavioural tier): a child `@routine` field
  classifies into the new leaf and emits a child fetcher whose FROM is the
  routine call and whose WHERE/ON is the parent correlation. Mirror
  `TableMethodFieldPipelineTest`.
* **Validation-tier**: the name-match integrity failure and the deferred mid-path
  shape each surface as `Rejection.AuthorError` (assert message content, not
  exact strings, per the R236/R259 convention). Mirror
  `TableMethodFieldValidationTest`.
* **Execution-tier** (`graphitron-sakila-example`): extend the existing
  `tilganger` TVF fixture (`init.sql:432-442`, schema
  `graphql/schema.graphqls:51-58`) or add a parent-correlated TVF so a child
  routine field returns the right rows against a live PostgreSQL. The existing
  root `RoutineFieldExecutionTest` is the template.
* **Classified corpus / `GraphitronSchemaBuilderTest`**: add a child-routine
  worked example alongside the existing `routine-table-valued-read` corpus entry
  and the `QueryRoutineTableField` projection test (`:7309-7329`), so the new
  classification is pinned by example.

## Relationship to R333 (and why this is not negative work)

R333 owns the eventual unification: `tableExpr` arms (`Catalog | MethodCall |
RoutineCall`) and the `JoinStep` reshape into `(target, on)`. This item ships the
*pre-R333 expression* of exactly the additive structure R333 argues for: one more
`ChildField` leaf carrying `RoutineRef` at the terminal table slot, reusing the
capability interfaces and the child-fetcher emitter. R333 already plans to
dissolve `TableMethodField` into a `MethodCall` arm; R384 gives it a `RoutineCall`
sibling to dissolve the same way, with no extra debt. The single structural
caution is the FK-less name-matched correlation `on` (above): keep that addition
minimal and R333-cross-referenced so the later reshape absorbs it.

`depends-on`: none hard. R333 is the model context, not a code prerequisite (B is
designed precisely so it does **not** wait on R333's reshape). Sequence after, or
in parallel with, the `@tableMethod` child work it mirrors.

## Open questions to settle before Ready

1. **Correlation mechanic** (name-matched join vs parent-arg/lateral), driven by a
   review of which shape real downstream routines want. Leaning: name-matched
   join first.
2. **Minimal `JoinStep` touch** for the FK-less name-matched `on` (generated
   `ConditionJoin` vs one new small arm), kept R333-compatible.
3. **`@table`-parent per-row variant** (`TableMethodField` analogue) in addition
   to the DTO-parent `RecordTableMethodField` analogue, or DTO-parent only for the
   first slice.
4. **DataLoader batching** of child routine calls: does the name-matched
   correlation batch through the existing `SplitRowsMethodEmitter` loader path, or
   does an FK-less per-parent routine call need a different batch strategy?
