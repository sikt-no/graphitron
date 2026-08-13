---
id: R649
title: "Coordinate-level rejections outrank parameter-binding rejections on record-backed-parent @service"
status: Spec
bucket: bug
priority: 3
theme: diagnostics
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# Coordinate-level rejections outrank parameter-binding rejections on record-backed-parent @service

`ServiceDirectiveResolver.resolve` reflects the service method, and therefore binds its parameters, before it classifies the coordinate's return type. Parameter binding rejects first and short-circuits, so a problem that belongs to the coordinate is reported as a problem with the author's Java signature. On a record-backed parent the batch key is unavailable by construction, the SOURCES shape is discarded, and the surviving diagnostic is an argument-name mismatch that tells the author their parameter matched no GraphQL argument or context key. On a field that declares no arguments this reads as "available GraphQL arguments: (none); available context keys: (none)" followed by advice to add a GraphQL argument or register a context key, neither of which can ever apply. The honest rejection for the coordinate exists two arms below in the same classifier and is unreachable.

## Desired outcome

Coordinate-level rejections outrank parameter-binding rejections on this path. Whatever the classifier knows about the coordinate is decided, or at least consulted, before a parameter-name mismatch is allowed to become the reported failure.

## Why this survives the feature work

R648 makes the record-backed-parent coordinate legal when the author declares a key. It does not remove this path, it makes it load-bearing. The author who writes the field and has not yet declared a key is precisely the entry point to R648's decision tree, and under today's precedence their case is still swallowed by the argument-mismatch arm and answered with advice about GraphQL arguments. R648's diagnostic cannot surface until this ordering is fixed, so this is a prerequisite rather than a subset.

## Scope

Scoped to the precedence rule, not to any particular message. The replacement text for the record-backed-parent case belongs to R648, whose design determines what the right guidance is; writing it here would mean writing it twice. The interim patch (surfacing the existing deferred rejection by reordering inside today's loop) is dropped in favour of the real fix below, which is the phase split R648's coordinate section defers to this item. This item owns the ordering at the `@service` seat; R648 consumes it.

## The fix is a phase split, not a reorder patch

`ServiceDirectiveResolver.resolve` today runs one fused step: `ServiceCatalog.reflectServiceMethod` loads the class, checks the root return type, resolves the instance holder, and binds every parameter in declaration order, minting `MethodRef.Param.Sourced` as a side effect of the loop. Every coordinate-level fact the resolver family knows is either checked after that call returns (root invariants, the parent-table element check) or buried inside the loop where an earlier parameter's failure short-circuits it (`SourcesOnPkLessParent`, the batch-at-root rejection), or lives further downstream in the classify sites' switch arms where any binding failure masks it (the record-backed-parent deferral, the child polymorphic deferral).

Split the boundary into three phases, per the shape R648's "The coordinate answer is a value" section commits to:

* **Decode** (`ServiceCatalog`): reflect the method into a typed signature fact. No binding inputs, no coordinate inputs.
* **Classify** (`ServiceDirectiveResolver.resolve`, over the decoded fact): every rejection expressible from the signature fact, the coordinate, and the field's SDL shape, decided before any parameter binds.
* **Bind** (`ServiceCatalog`): the parameter loop, reduced to genuinely binding-level concerns.

This removes the "reflects before it classifies" defect structurally instead of working around it at one arm, and it is what lets R648 compute `ParentKeyResolution` as a value between decode and bind.

## The coordinate input

`resolve`'s coordinate is currently the pair (`parentPkColumns`, `pkLessParent`), and two of its three coordinates are indistinguishable: root sites and record-backed parents both pass `(List.of(), null)`. The record-parent coordinate answer cannot be decided by a resolver that cannot see the coordinate. Replace the pair at `resolve`'s surface with a sealed input:

```java
/** Which coordinate hosts the @service field. The question, not the answer:
 *  the answer fact (ParentKeyResolution) is R648's. */
sealed interface ParentContext {
    /** Query / Mutation root: no parent context. */
    record Root() implements ParentContext {}
    /** Child of a @table-backed parent; the table carries the PK columns and the name. */
    record TableParent(TableRef table) implements ParentContext {}
    /** Child of a class-backed (record / POJO) parent, carrying the parent's resolved type. */
    record RecordParent(GraphitronType.ResultType parentType) implements ParentContext {}
}
```

Why a sealed input and not "keep the pair, add a record-parent marker": the pair fuses two axes (coordinate identity and key columns) into one slot with "empty means root" signalling, and a third marker would preserve the fusion while adding a representable-but-illegal combination. The axis split is the argument, not mere distinguishability.

`RecordParent` carries the parent's resolved type rather than being a bare tag, so both arms answer "what does this coordinate carry" at the same grain: R648's record-parent key resolver needs the backing class and, for its held-record arm, `JooqTableRecordType.table()`, and an empty arm would force it to either re-derive those from `parentTypeName` through `ctx` (recompute at the consumer, when the producer had them; `classifyChildFieldOnResultType` holds both in scope) or cut this seam a second time. The exact component (the `ResultType`, or backing class plus optional `TableRef`) is the implementer's pick, narrowest that serves R648's two producer arms.

`ServiceCatalog.PkLessParent` dissolves: it existed to patch the third coordinate out of the empty-list overload, and `TableParent` whose `table.primaryKeyColumns()` is empty carries the same two facts (type name is already a `resolve` parameter, table name rides on the `TableRef`). The four call sites map one-to-one: the Query and Mutation root arms pass `Root`, `FieldBuilder.classifyChildFieldOnTableType` passes `TableParent`, `FieldBuilder.classifyChildFieldOnResultType` passes `RecordParent`.

**The validation-regime flip is deliberately NOT taken, and the preserved regime is a named axis, not a boolean.** Naming the record-parent coordinate makes it possible to stop treating those children as root, and R648's coordinate section enumerates four validations that change hands when that happens, plus the unvalidated residue the flip strands until R648's `Sources`-less classify-time rejection exists. That flip is R648's, gated on machinery R648 builds. But spelling the preserved collapse as `Root || RecordParent` with a comment would leave nothing to fail when R648's flip misses a site or a fourth coordinate arrives. So the regime is a derived axis with an exhaustive switch, e.g. `regimeOf(ParentContext)` returning a two-value regime (strict-root vs batched-child), with `RecordParent` mapping to strict-root today and every downstream read switching on the regime value. R648's flip is then a one-arm change at one seat, and a new coordinate is a compile error. The comment on the `RecordParent` arm states the fact ("record-backed parents currently share the root return-type regime"), not the item id, per the javadoc conventions. The regime is pinned by tests below: a record-parent child `@service` (no `Sources` parameter) keeps the strict return-type comparison and keeps the Connection rejection. R648's spec already plans tests "in both directions" for the flip; these pins are the "before" side it needs.

## The phases, precisely

**Decode.** Class load and `pickMethod` (unchanged rejections: `ClassNotLoaded`, unknown method, `AmbiguousMethod`), then per-parameter typed facts: name (null when compiled without `-parameters`; the proactive warning stays here), declared type as string and javapoet `TypeName`, a `DSLContext` flag, and the recognised `ServiceCatalog.SourcesShape` where `classifySourcesType` recognises one. Method-level facts: return `TypeName`, static-ness, declared exception FQNs, and the instance-holder constructor facts. Instance-holder resolution (`resolveInstanceHolder`) is pure over the class plus the declared context keys, no binding and no coordinate inputs, so it is decode by this item's own definition even though its rejection currently fires early; decode therefore takes the context-key set as an input and the signature fact carries the ctor-param facts or a typed unconstructible outcome. That closes the raw-reflection escape hatch entirely: the fact carries no `java.lang.reflect.Method`, and the containment invariant (`development-principles.adoc`: raw reflection types stay inside the parse-boundary classes) is structural rather than a comment.

**Claim reduction.** One named step (the tail of decode, or its own step between decode and classify) folds `ArgBindingMap.byJavaName`, `inferBindingsByType` (which already excludes SOURCES-shaped parameters from inference), and the context-key set into one sealed role per parameter: arg-bound (with its path), context-bound, SOURCES-candidate (carrying the recognised `SourcesShape`), or unclaimed. Membership only; no extraction runs here. Classify switches on the roles crossed with `ParentContext`; bind extracts and mints. Deciding candidacy once as a carried value, instead of a predicate two phases re-evaluate and must agree to spell the same way, is what actually removes the shared-skip-list smell, and it retires the triplicated shape recognition: `looksLikeSourcesShape` and `couldBeSourcesShape` are both re-spellings of `classifySourcesType` (presence, or presence with a `Row`/`Record` wrap) and collapse into the candidate role plus a switch on its wrap.

**Classify.** Runs in `ServiceDirectiveResolver.resolve` between decode and bind. The precedence rule, stated at altitude rather than as a list to memorise: the SDL is the contract and the Java signature is fitted to it, so a defect in the field's shape is reported before a defect in the coordinate's ability to host the signature, which is reported before a defect in the signature's fit, which is reported before a defect in name binding. In that order:

1. **Errors-lift probe.** `FieldBuilder.liftToErrorsField` is pure over SDL (field definition, parent type, resolved return type), so the probe moves ahead of binding together with the deferral it guards. A lifting polymorphic return proceeds to bind and projects as `ErrorsLifted`, unchanged in outcome.
2. **Child polymorphic deferral**, both parent kinds: the existing deferred text, hoisted out of the classify sites' switch arms into the phase that runs before binding.
3. **Root Connection rejection**, hoisted from `validateRootInvariants`. Field-shape outranks signature: a root `@service` returning a Connection gets the Connection rejection even when a parameter is also misnamed, and even when the method also declares a batch-shaped parameter (today the batch-at-root message wins that pairing; the flip is deliberate and pinned).
4. **SOURCES coordinate answers**, over the SOURCES-candidate roles. Per coordinate:
   * `Root`: a `RowN` / `RecordN` candidate is the batch-at-root rejection, now a single arm (the copy inside the loop and the dead `validateRootInvariants` arm both retire; see below). A `TableRecord` candidate is *not* a coordinate claim: `List<XRecord>` at root is the canonical `InputBeanResolver` shape, and binding owns it, preserving the pinned arg-mismatch fallback.
   * `TableParent` with empty `primaryKeyColumns()`: `SourcesOnPkLessParent`, no longer dependent on the candidate being declared before the first misbound parameter.
   * `TableParent` with a PK, `TableRecord` wrap, element class not the parent's backing record class: the element mismatch (`validateTableRecordSourceParentTable`'s check, hoisted; it reads only the decoded wrap and the parent record class, and today it sits after the whole binding loop where any other parameter's failure masks it).
   * `RecordParent`: the existing deferred rejection ("@service on a record-backed parent is not yet supported ..."), text unchanged, on either of two triggers. Any candidate is one, and it is the one this item exists for. A `Result` or `Scalar` resolved return type is the other, and it has to remain a trigger with no candidate in sight, because that pairing is what the `FieldBuilder` arms reject today, unconditionally and without consulting the signature. Gating the hoisted rejection on candidacy alone would let a record-parent child `@service` with no `Sources` parameter classify successfully: R648's feature arriving a phase early, without R648's `Sources`-less classify-time rejection to guard it, and R648's coordinate section names exactly that unvalidated residue as the hazard of the flip. It would also break this item's own "severity never changes" contract. Nothing in the tree pins the deferral today (the text appears in `RejectionRenderingTest` only as a rendering sample), so the regression would be silent; the Tests section adds the pin. A `TableBound` return with no candidate keeps classifying, as it does now. This is the arm R648 replaces with `ParentKeyResolution` computed over the decoded element class.
   * A nameless SOURCES-shaped parameter (compiled without `-parameters`) carries the candidate role: it cannot be name-claimed, and the coordinate answer does not depend on its name. At `Root` that flips the winner from `ParameterNamesMissing` to batch-at-root. Deliberate; a nameless parameter of any other shape still gets `ParameterNamesMissing` from bind.
5. **Strict return-type comparison** (the `expectedReturnType` check, gated on the regime axis), against the decoded return type; `validateRootListTableBoundReturnPair` moves beside it. They are two arms of one fact and are split today by accident of the fused step: the Single-cardinality arm fires before binding, the List arm after. Placing them *below* the SOURCES answers is what keeps the observable contract unqualified: a record-parent child whose field returns a single `@table` type and whose method has both a wrong return type and a batch-shaped parameter is answered about the coordinate, not the return type (today the return-type mismatch fires first at root-regime coordinates; the flip is deliberate and pinned, at root as well as at record parents).

**Bind.** The override typo guard and the per-parameter loop reduced to extraction and minting over the carried roles: `Arg` extraction for arg-bound roles, `Context`, `DtoSourcesUnsupported` for unclaimed DTO-shaped parameters (its gate, today `!parentPkColumns.isEmpty()`, becomes "key columns present" verbatim; R648 owns widening it), `ParameterNamesMissing`, `ArgumentParameterMismatch`, `UnrecognizedSourcesType`, and `Sourced` construction for SOURCES-candidate roles when key columns are present. Bind also surfaces the instance-holder unconstructible rejection decode captured. Bind's one coordinate input is the key-column list with today's `parentPkColumns` semantics (the name and the concept retire under R648, not here; only the "empty means root" signalling role retires now). Classify guarantees that candidates reaching bind with no key columns exist only at `Root` with `TableRecord` shape, where binding's diagnostics are the correct ones.

Post-bind, unchanged and deliberately not hoisted: `InputBeanResolver.enrich`, `validateChildServiceReturnType`, and `projectReturnType`. The child return-shape check is signature-level, not coordinate-level; the rule this item installs is "coordinate outranks binding", not "everything outranks binding".

## The observable contract

* A coordinate-level rejection wins over a parameter-binding rejection regardless of parameter declaration order. Today `SourcesOnPkLessParent` fires only when no earlier-declared parameter misbinds; the diagnostic is declaration-order-dependent.
* On a record-backed parent, a batch-shaped signature is answered about the coordinate (the deferral today, R648's decision tree later), never with "available GraphQL arguments: (none)", and never with a return-type mismatch.
* Field-shape rejections (Connection at root) win over coordinate rejections, which win over signature-fit rejections (the strict return-type checks), which win over binding rejections. Two winners flip relative to today, both deliberate and pinned: Connection now beats batch-at-root, and batch-at-root now beats the strict return-type mismatch.
* Name-claimed parameters keep binding precedence over SOURCES recognition: a `Set<XRecord>` parameter whose name matches a GraphQL argument still binds (and surfaces binding's diagnostics), and root `List<XRecord>` input beans are untouched.
* Severity never changes: everything here is rejected today and rejected after; only which rejection surfaces changes.

## Consequences for R648's Ready plan

R648 was signed off against today's surface, so the mapping is stated here rather than inherited: its "swap `parentPkColumns` + `pkLessParent` for the resolved `ParentKeyResolution` value" bullet lands on this item's `ParentContext` and key-columns seam instead (compute `ParentKeyResolution` in the classify phase, from `ParentContext` plus the decoded element class, and feed `Available.source().keyOwner().primaryKeyColumns()` to bind); its "Delete `ServiceCatalog.PkLessParent`" bullet is pre-done; its four-validations flip lands on the regime axis this item introduces, a one-arm change on the `RecordParent` mapping; its record-parent arm replaces this item's `RecordParent` deferral arm; its "Unit (`ServiceCatalogTest`): the three `ParentKeyResolution` arms through `reflectServiceMethod`" test bullet is superseded by the tier note below (after the split the catalog no longer decides coordinate precedence, so those pins are pipeline-shaped). One placement note R648 should inherit: `ParentKeyResolution` is resolver-internal gathering scaffolding (its `Rejected(Rejection)` arm is the tell), so it belongs beside `ParentContext` rather than under `model/`, and only `ServiceKeySource` reaches a leaf component; `ParentContext` sets that precedent here.

No design change on either side, so R648 is not reopened to Spec. But the mapping must not live only in this file while R648's bullets read as current: **updating R648's Implementation and Tests bullets against the post-split code is a deliverable of this item**, landed with the implementation or at its Done gate, not left as advice.

## Survey: where else the ordering masks coordinate verdicts

The resolver-family survey the Backlog scope note asked for found the same defect class at three seats outside `@service` and one residual group inside it. All are filed as a follow-up Backlog item (`roadmap/resolver-coordinate-verdict-precedence-sweep.md`) rather than widened into this one:

* `@externalField`: the `@reference`-path deferral lives in `GraphitronSchemaValidator.validateComputedField` and only runs on a successfully classified field, so every `reflectExternalField` signature rejection masks it.
* `@sourceRow`: `SourceRowDirectiveResolver.resolve` runs its reflection step before the derivation step, and the derivation carries coordinate-level rejections (leaf target table without a primary key, condition-join first hop).
* `@condition` + `@lookupKey`: `FieldBuilder.projectForFilter` returns on the `ConditionResolver` reflection rejection ahead of the "no argument resolved to a lookup column" verdict.
* Residual `@service` group, deliberately left at the FieldBuilder arms: the root polymorphic narrowing (union rejection, single-table-interface deferral) and the mutation payload checks (orphan carrier, `$source` sigil), which are entangled with root-arm payload semantics and are not what blocks R648.

`RoutineDirectiveResolver` already orders coordinate verdicts ahead of binding and is the reference shape. It does carry the same coordinate collapse one seat over (a bare `boolean isRoot`); nesting `ParentContext` inside `ServiceDirectiveResolver` rather than sharing it is deliberate (the routine seat has two coordinates and no masking defect), and the sweep item records that so it is not later rediscovered as accidental duplication.

## Implementation

* `ServiceCatalog`: new decode step producing the signature fact (name it against what it is, e.g. `decodeServiceMethod` returning a `ServiceSignature`), including the instance-holder ctor facts; the claim-reduction step producing the sealed per-parameter roles; `reflectServiceMethod`'s loop becomes the bind step consuming the roles. The three-overload stack collapses; `PkLessParent` deleted; `looksLikeSourcesShape` and `couldBeSourcesShape` retire into the candidate role's wrap.
* `ServiceDirectiveResolver`: `ParentContext` and the regime axis (both nested, like `Resolved`); `resolve` reordered to decode → claim reduction → classify → bind → post-bind as above. `validateRootInvariants` dissolves: the Connection arm moves into classify; the `ParamSource.Sources` arm is dead code (at root no `Sourced` is ever minted, `InputBeanResolver` never produces one, and its message text has already drifted from the live copy) and retires. `validateTableRecordSourceParentTable` re-expressed over the decoded candidate.
* `FieldBuilder`: four call sites pass `ParentContext`; the two record-parent switch arms (`Result`, `Scalar`) lose their inline deferral construction to the classify phase, and the child `Polymorphic` arms likewise. Classify then rejects those coordinate/shape pairings outright, so the arms are unreachable by construction and become invariant throws naming the classify arm that owns the verdict, the shape `ArgCallEmitter.buildMethodBackedCallArgs`'s `ParamSource.SourceTable` arm already uses; R648 replaces the record-parent pair with the real `ServiceRecordField` projection. The ownership rule, so a future check lands in the right place: the resolver owns coordinate and signature verdicts; the arms own payload and parent-arm semantics (the root arms keep the polymorphic narrowing and mutation payload checks, per the survey section).
* `ArgCallEmitter.buildMethodBackedCallArgs`: its `ParamSource.Sources` invariant throw names `ServiceDirectiveResolver.validateRootInvariants` as the classifier keeping that state unreachable, and that method dissolves here. Repoint the message at the classify-phase batch-at-root arm; the name is declared retired below so the Done-gate sweep has a grep query for it.
* R648 plan touch-up: update its Implementation and Tests bullets against the post-split code, per the consequences section above.
* Javadoc: `resolve`'s "Pass `List.of()` ... at root sites (Query / Mutation) and on class-backed parents" paragraph and the "empty `parentPkColumns` also gates two root-only concerns" list are wrong after this and are part of the change, as are the resolver's class-level bullets ("Root invariants ...", "Root vs child is signalled by `parentPkColumns`") and `reflectServiceMethod`'s `parentPkColumns` and `expectedReturnType` paragraphs, which describe responsibilities bind no longer holds.

## Tests

The precedence pins are SDL-shaped and land at the pipeline tier, in the `PkLessParentServiceSourcesRejectionTest` mould (after the split the catalog no longer decides coordinate precedence, so `ServiceCatalogTest` structurally cannot observe them). The unit tier keeps what the split actually creates: the signature fact's contents and the per-parameter role reduction.

Budget the re-anchor rather than discovering it. `ServiceCatalogTest` calls `reflectServiceMethod` directly at 47 sites, all against the fused entry point, and the strict comparison leaves the catalog with `expectedReturnType`: the return-type group (null-expected capture, the matching case, and the raw-class / inner-generic / cardinality mismatches) has no subject left there and re-anchors on decode plus classify. Most of the rest keep working against bind with the key-column input, and the `Sourced`-construction cases are unaffected. Mechanical, but not free.

Pipeline pins, one fixture per precedence pair:

* Record parent, batch-shaped `Set<XRecord>` signature, otherwise-clean binding: the deferral, not `ArgumentParameterMismatch`. The headline pin; today this asserts nothing because the case reads as an arg mismatch.
* Record parent, batch-shaped signature plus a misnamed extra parameter: still the deferral (coordinate outranks binding).
* Record parent whose field returns a single `@table` type, batch-shaped parameter plus a mismatched method return type: the deferral, not the strict return-type mismatch (coordinate outranks signature fit at a strict-regime coordinate).
* Record parent, no `Sources` parameter, `Result` and `Scalar` returns, otherwise clean: still the deferral. The over-fire guard for the arm's second trigger, and the one case the hoist could silently make legal. Its `TableBound` sibling (record parent, no candidate, table-bound return) still classifies, mirroring `PkLessParentServiceSourcesRejectionTest.noSourcesChildOnPkLessParent_stillClassifies` on the other coordinate.
* Root, List-cardinality table-bound return whose method returns the wrong type, plus a misnamed parameter: the return-pair mismatch, not the arg mismatch. Today the List arm runs after binding and loses this pairing while the Single arm already wins its equivalent; this pins the reunion of the two arms.
* Child polymorphic `@service` on either parent kind whose field also carries an unresolvable path: the deferral, not the path error. Hoisting the deferral moves it ahead of the `ctx.parsePath` call both child classify sites make between `resolve` and the switch. Deliberate, and the same reading: the field is unsupported at that coordinate whatever its path says.
* PK-less table parent, misnamed parameter declared *before* the `Sources` parameter: `SourcesOnPkLessParent` (kills the declaration-order dependence; the swapped-order sibling already passes today).
* Table parent, wrong `Sources` element class plus a misnamed other parameter: the element mismatch, not the arg mismatch.
* Root, `List<Row2<...>>` parameter plus a misnamed parameter: batch-at-root, not the arg mismatch. Root, `List<Row2<...>>` parameter plus a mismatched method return type: batch-at-root (flips today's winner; deliberate).
* Root, Connection return plus misnamed parameter: Connection rejection. Root, Connection return plus `List<Row2<...>>` parameter: Connection rejection (flips today's winner; deliberate).
* Name-claim precedence: a `Set<XRecord>` parameter named after a GraphQL argument on a table parent surfaces binding's diagnostic, not a silent `Sourced`; root `List<XRecord>` input-bean fixtures unchanged (existing coverage, re-run not rewrite).
* Regime pins for R648's flip: record-parent child `@service` with no `Sources` parameter still gets the strict return-type comparison on a mismatched method, and a Connection-returning one is still rejected; both marked as the "before" side of R648's four-validations flip.
* Single-defect regressions: every diagnostic named above still fires with today's text when it is the only defect (mostly existing coverage).

No compilation or execution tier: no emitted code changes shape; classification outcomes and rejection routing are fully observable at the unit/pipeline tier.

## Retired vocabulary

* `ServiceCatalog.PkLessParent` (and its javadoc's "The one coordinate shape an empty `parentPkColumns` does not describe")
* "Root vs child is signalled by `parentPkColumns`" and "Pass `List.of()` for `parentPkColumns` at root sites (Query / Mutation) and on class-backed parents" (`ServiceDirectiveResolver` javadoc)
* "An empty `parentPkColumns` also gates two root-only concerns"
* "@service at the root does not support `List<Row>`/`List<Record>`/`List<Object>` batch parameters" (the dead `validateRootInvariants` arm's drifted text; the reachable batch-at-root message survives, single-sourced in the classify phase)
* `ServiceDirectiveResolver.validateRootInvariants` (the method dissolves, and the name is cited outside its own file: `ArgCallEmitter.buildMethodBackedCallArgs`'s `ParamSource.Sources` invariant message, and the resolver's class-javadoc bullet "Root invariants (Connection wrapper rejection, `Sources` parameter rejection)")
* `looksLikeSourcesShape` and `couldBeSourcesShape` (both re-spellings of `classifySourcesType`; collapsed into the per-parameter candidate role)

Deliberately not retired: `parentPkColumns` as bind's key-column input name and the concept "the batch key is the parent's PK"; both are R648's to retire with the semantic change. The record-parent deferral text ("must be lifted through the parent chain") is also R648's, and after this item it is finally reachable for the batch-shaped signature that motivated both items.

## Out of scope

* The record-parent replacement message and the key feature (R648).
* The validation-regime flip for record-parent children (R648, gated on its classify-time `Sources`-less rejection).
* The non-`@service` seats and the root polymorphic / mutation-payload residue (the sweep item above).
* `validateChildServiceReturnType` ordering: signature-level, stays post-bind.
