---
id: R649
title: "Coordinate-level rejections outrank parameter-binding rejections on record-backed-parent @service"
status: Backlog
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
    /** Child of a class-backed (record / POJO) parent. */
    record RecordParent() implements ParentContext {}
}
```

`ServiceCatalog.PkLessParent` dissolves: it existed to patch the third coordinate out of the empty-list overload, and `TableParent` whose `table.primaryKeyColumns()` is empty carries the same two facts (type name is already a `resolve` parameter, table name rides on the `TableRef`). The four call sites map one-to-one: the Query and Mutation root arms pass `Root`, `FieldBuilder.classifyChildFieldOnTableType` passes `TableParent`, `FieldBuilder.classifyChildFieldOnResultType` passes `RecordParent`.

**The validation-regime flip is deliberately NOT taken.** Naming the record-parent coordinate makes it possible to stop treating those children as root, and R648's coordinate section enumerates four validations that change hands when that happens, plus the unvalidated residue the flip strands until R648's `Sources`-less classify-time rejection exists. That flip is R648's, gated on machinery R648 builds. So under this item the root-regime predicate reads `Root` or `RecordParent` where today it reads `isRoot`, stated in a comment as awaiting R648's flip, and the regime is pinned by tests below: a record-parent child `@service` (no `Sources` parameter) keeps the strict return-type comparison and keeps the Connection rejection. R648's spec already plans tests "in both directions" for the flip; these pins are the "before" side it needs.

## The three phases, precisely

**Decode.** Class load and `pickMethod` (unchanged rejections: `ClassNotLoaded`, unknown method, `AmbiguousMethod`), then per-parameter typed facts: name (null when compiled without `-parameters`; the proactive warning stays here), declared type as string and javapoet `TypeName`, a `DSLContext` flag, and the recognised `ServiceCatalog.SourcesShape` where `classifySourcesType` recognises one. Method-level facts: return `TypeName`, static-ness, declared exception FQNs. The containment invariant (`development-principles.adoc`: raw reflection types stay inside the parse-boundary classes) holds: the fact's readable surface is typed; where bind needs the raw `java.lang.reflect.Method` (instance-holder resolution walks constructors), the fact may carry it as an opaque component only `ServiceCatalog` reads.

**Classify.** Runs in `ServiceDirectiveResolver.resolve` between decode and bind, in this order:

1. **Errors-lift probe.** `FieldBuilder.liftToErrorsField` is pure over SDL (field definition, parent type, resolved return type), so the probe moves ahead of binding together with the deferral it guards. A lifting polymorphic return proceeds to bind and projects as `ErrorsLifted`, unchanged in outcome.
2. **Child polymorphic deferral**, both parent kinds: the existing deferred text, hoisted out of the classify sites' switch arms into the phase that runs before binding.
3. **Root Connection rejection**, hoisted from `validateRootInvariants`. Field-shape outranks signature: a root `@service` returning a Connection gets the Connection rejection even when a parameter is also misnamed, and even when the method also declares a batch-shaped parameter (today the batch-at-root message wins that pairing; the flip is deliberate and pinned).
4. **Strict return-type comparison** (the `expectedReturnType` check, root regime), against the decoded return type; `validateRootListTableBoundReturnPair` moves beside it. They are two arms of one fact and are split today by accident of the fused step: the Single-cardinality arm fires before binding, the List arm after.
5. **SOURCES coordinate answers.** A *candidate* is a decoded parameter with a recognised `SourcesShape` that is not name-claimed (see below) and not context-claimed. Per coordinate:
   * `Root`: a `RowN` / `RecordN` candidate is the batch-at-root rejection, now a single arm (the copy inside the loop and the dead `validateRootInvariants` arm both retire; see below). A `TableRecord` candidate is *not* a coordinate claim: `List<XRecord>` at root is the canonical `InputBeanResolver` shape, and binding owns it, preserving the pinned arg-mismatch fallback.
   * `TableParent` with empty `primaryKeyColumns()`: `SourcesOnPkLessParent`, no longer dependent on the candidate being declared before the first misbound parameter.
   * `TableParent` with a PK, `TableRecord` wrap, element class not the parent's backing record class: the element mismatch (`validateTableRecordSourceParentTable`'s check, hoisted; it reads only the decoded wrap and the parent record class, and today it sits after the whole binding loop where any other parameter's failure masks it).
   * `RecordParent`, any candidate: the existing deferred rejection ("@service on a record-backed parent is not yet supported ..."), text unchanged. This is the arm R648 replaces with `ParentKeyResolution` computed over the decoded element class.

   The name-claim set is `ArgBindingMap.byJavaName` after `inferBindingsByType` (which already excludes `couldBeSourcesShape` parameters from inference, so the set is stable with respect to candidates), plus the declared context keys. Membership only; no extraction runs at classify. The candidate predicate is defined once and shared by classify and bind, so the two phases cannot disagree about which parameters are SOURCES.

**Bind.** Instance-holder resolution, the override typo guard, `inferBindingsByType`, and the per-parameter loop minus the hoisted arms: `Arg` extraction, `Context`, `DtoSourcesUnsupported` (its gate, today `!parentPkColumns.isEmpty()`, becomes "key columns present" verbatim; R648 owns widening it), `ParameterNamesMissing`, `ArgumentParameterMismatch`, `UnrecognizedSourcesType`, and `Sourced` construction for candidates when key columns are present. Bind's one coordinate input is the key-column list with today's `parentPkColumns` semantics (the name and the concept retire under R648, not here; only the "empty means root" signalling role retires now). Classify guarantees that candidates reaching bind with no key columns exist only at `Root` with `TableRecord` shape, where binding's diagnostics are the correct ones.

Post-bind, unchanged and deliberately not hoisted: `InputBeanResolver.enrich`, `validateChildServiceReturnType`, and `projectReturnType`. The child return-shape check is signature-level, not coordinate-level; the rule this item installs is "coordinate outranks binding", not "everything outranks binding".

## The observable contract

* A coordinate-level rejection wins over a parameter-binding rejection regardless of parameter declaration order. Today `SourcesOnPkLessParent` fires only when no earlier-declared parameter misbinds; the diagnostic is declaration-order-dependent.
* On a record-backed parent, a batch-shaped signature is answered about the coordinate (the deferral today, R648's decision tree later), never with "available GraphQL arguments: (none)".
* Field-shape rejections (Connection at root) win over signature rejections.
* Name-claimed parameters keep binding precedence over SOURCES recognition: a `Set<XRecord>` parameter whose name matches a GraphQL argument still binds (and surfaces binding's diagnostics), and root `List<XRecord>` input beans are untouched.
* Severity never changes: everything here is rejected today and rejected after; only which rejection surfaces changes.

## Consequences for R648's Ready plan

R648 was signed off against today's surface, so the mapping is stated here rather than inherited: its "swap `parentPkColumns` + `pkLessParent` for the resolved `ParentKeyResolution` value" bullet lands on this item's `ParentContext` and key-columns seam instead (compute `ParentKeyResolution` in the classify phase, from `ParentContext` plus the decoded element class, and feed `Available.source().keyOwner().primaryKeyColumns()` to bind); its "Delete `ServiceCatalog.PkLessParent`" bullet is pre-done; its four-validations flip lands on the regime predicate this item leaves reading `Root or RecordParent`; its record-parent arm replaces this item's `RecordParent` deferral arm. No design change on either side, but R648's implementer should re-read its ServiceCatalog bullet against the post-split code, and a plan touch-up commit on R648 at that point is cheaper than a surprised one.

## Survey: where else the ordering masks coordinate verdicts

The resolver-family survey the Backlog scope note asked for found the same defect class at three seats outside `@service` and one residual group inside it. All are filed as a follow-up Backlog item (`roadmap/resolver-coordinate-verdict-precedence-sweep.md`) rather than widened into this one:

* `@externalField`: the `@reference`-path deferral lives in `GraphitronSchemaValidator.validateComputedField` and only runs on a successfully classified field, so every `reflectExternalField` signature rejection masks it.
* `@sourceRow`: `SourceRowDirectiveResolver.resolve` runs its reflection step before the derivation step, and the derivation carries coordinate-level rejections (leaf target table without a primary key, condition-join first hop).
* `@condition` + `@lookupKey`: `FieldBuilder.projectForFilter` returns on the `ConditionResolver` reflection rejection ahead of the "no argument resolved to a lookup column" verdict.
* Residual `@service` group, deliberately left at the FieldBuilder arms: the root polymorphic narrowing (union rejection, single-table-interface deferral) and the mutation payload checks (orphan carrier, `$source` sigil), which are entangled with root-arm payload semantics and are not what blocks R648.

`RoutineDirectiveResolver` already orders coordinate verdicts ahead of binding and is the reference shape.

## Implementation

* `ServiceCatalog`: new decode step producing the signature fact (name it against what it is, e.g. `decodeServiceMethod` returning a `ServiceSignature`); `reflectServiceMethod`'s loop becomes the bind step consuming it. The three-overload stack collapses; `PkLessParent` deleted.
* `ServiceDirectiveResolver`: `ParentContext` (nested, like `Resolved`); `resolve` reordered to decode → classify → bind → post-bind as above. `validateRootInvariants` dissolves: the Connection arm moves into classify; the `ParamSource.Sources` arm is dead code (at root no `Sourced` is ever minted, `InputBeanResolver` never produces one, and its message text has already drifted from the live copy) and retires. `validateTableRecordSourceParentTable` re-expressed over the decoded candidate.
* `FieldBuilder`: four call sites pass `ParentContext`; the two record-parent switch arms (`Result`, `Scalar`) lose their inline deferral construction to the classify phase, and the child `Polymorphic` arms likewise; what remains in the arms is Success projection only.
* Javadoc: `resolve`'s "Pass `List.of()` ... at root sites (Query / Mutation) and on class-backed parents" paragraph and the "empty `parentPkColumns` also gates two root-only concerns" list are wrong after this and are part of the change.

## Tests

Unit tier (`ServiceCatalogTest` / builder pipeline tests, per existing convention):

* Record parent, batch-shaped `Set<XRecord>` signature, otherwise-clean binding: the deferral, not `ArgumentParameterMismatch`. The headline pin; today this asserts nothing because the case reads as an arg mismatch.
* Record parent, batch-shaped signature plus a misnamed extra parameter: still the deferral (coordinate outranks binding).
* PK-less table parent, misnamed parameter declared *before* the `Sources` parameter: `SourcesOnPkLessParent` (kills the declaration-order dependence; the swapped-order sibling already passes today).
* Table parent, wrong `Sources` element class plus a misnamed other parameter: the element mismatch, not the arg mismatch.
* Root, `List<Row2<...>>` parameter plus a misnamed parameter: batch-at-root, not the arg mismatch.
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

Deliberately not retired: `parentPkColumns` as bind's key-column input name and the concept "the batch key is the parent's PK"; both are R648's to retire with the semantic change. The record-parent deferral text ("must be lifted through the parent chain") is also R648's, and after this item it is finally reachable for the batch-shaped signature that motivated both items.

## Out of scope

* The record-parent replacement message and the key feature (R648).
* The validation-regime flip for record-parent children (R648, gated on its classify-time `Sources`-less rejection).
* The non-`@service` seats and the root polymorphic / mutation-payload residue (the sweep item above).
* `validateChildServiceReturnType` ordering: signature-level, stays post-bind.
