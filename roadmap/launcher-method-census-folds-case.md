---
id: R889
title: "The launcher-method census folds case, rejecting a valid deprecated-alias field pair"
status: Spec
bucket: bug
priority: 2
theme: diagnostics
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# The launcher-method census folds case, rejecting a valid deprecated-alias field pair

A consumer schema that declares two sibling fields whose names differ only in the case of a letter after the first is rejected at generation, with a located error saying the two coordinates mint one launcher method. They do not. The two emitted methods are `rowsUndervisningStartterminIPeriode` and `rowsUndervisningStartterminIperiode`, which are two distinct and perfectly legal Java methods; nothing would collide at emission. They are rejected only because the census that guards launcher-method uniqueness lowercases its whole key before comparing.

A *launcher method* is the generated method that owns one root or batched-child coordinate's whole query composition, hosted on the coordinate's fetchers class. Its name is minted by a naming formula in `GeneratedUnits` (`rows<Field>`, `load<Field>`, `lookup<Field>`) and a *census* is the uniqueness check over those minted names, which exists because the formula is not injective: it upper-camels the field name, so the two fields `fooBar` and `FooBar` genuinely mint one method, `rowsFooBar`. The census lives at two sites that must agree, or validation and generation drift apart: `LauncherRelation`'s compact constructor (the relation's construction-time integrity check, whose failure is a hard throw) and `GraphitronSchemaValidator.validateLauncherMethodNames` reading `LauncherCommands.methodCollisions` (the authored-schema mirror, which is what an author sees, located at the colliding declarations).

The exact comparison is complete regardless of the formula, because the census compares names within one namespace: both sides are names the generator itself minted onto one owning class, and two coordinates that mint one method mint the *identical* string. A fold is minted only where two namespaces meet, which is the projection address census's situation (a generated class name becomes a file name, and a case-insensitive filesystem genuinely collides; the javadoc on `Rejection.InvalidSchema.CaseFoldCollision` carries that rationale). Method names are never files, so there is no boundary to bridge, and what the fold adds beyond exact comparison is only the rejection of pairs whose emitted names are distinct, which is by definition not a collision. The current formula corroborates this without carrying it: `GeneratedUnits.upperCamel` upper-cases the first character and nothing else, so the folded census's extra rejections are exactly the pairs differing after the first letter. The tree already contains the exact reading of the invariant: `LauncherRelationClosureTest.noTwoRowsClaimTheSameEmittedMethod` calls itself the run-level pin of the relation census and keys on unfolded `fqcn#method`, disagreeing with the two folded sites; the fix makes all three agree.

The fold appears to have been inherited rather than reasoned. Its javadoc cites "the projection producer's address-census precedent", `ProjectionRelation`, which folds a fully-qualified class name; for class addresses the fold is justified as above, for method names it is not. The belief that the non-injective formula forces the fold is stated as live fact in two more places that must be swept with the fix: `RoutineWriteRelationTest.coordinatesDifferingOnlyInCaseMintDistinctMethodsAndAreBothAdmitted` pins the case-sensitive reading for the sibling relation whose entry-point name *is* the field's own, and asserts in its javadoc that the upper-camelling relations need the fold; and `RoutineWriteRelation`'s own class javadoc restates the same claim and closes with "Do not add either by analogy", which is the vector by which the fold's rationale spread. `LauncherRelation`'s class comment additionally carries an unguarded roster claim ("every relation therefore also passes a case-folded census") that is already false, since `RoutineWriteRelation` has no census and `ProjectionRelation` folds addresses, not methods.

The schema being rejected is a deprecated-alias pair, a typo'd field kept alive beside its corrected spelling with a `@deprecated` reason carrying a published removal date. Fixing it consumer-side would mean retiring a field ahead of a date promised to API clients, to work around a check that is rejecting a valid schema, and keep-the-typo-as-a-deprecated-alias is a pattern any consumer may reach for, so the rejection will recur.

## Plan

1. `LauncherRelation`'s compact constructor: key the census on the minted `UnitMethodRef` value itself. The ref is a record over `(owner, methodName)`, so its value equality *is* the exact key; a `Map<UnitMethodRef, LauncherCommand>` removes both the fold and the hand-spelled string concatenation whose "no fold" property two sites would otherwise have to hold in step. Render `owner#method` only in the throw message, which no longer says "case-folded".
2. `LauncherCommands.methodCollisions`: same key change (group by the ref), and `MethodCollision` carries `UnitMethodRef method` instead of the `foldedKey` string. This also repairs the author-facing text, which today splices a fully lowercased `fqcn#method`, a name that exists nowhere, into the rejection. Do not copy `ProjectionCommands.byFoldedName`, which is the same stringly shape on the sibling census.
3. `GraphitronSchemaValidator.validateLauncherMethodNames`: update the rejection text, dropping "(case-folded)" and naming the actual minted method. The surviving `fooBar` / `FooBar` rejection is the "deferred, not an author error" species (the schema is legal; the generator's non-injective formula cannot emit it), the same species `validateSiblingProjectionAgreement` annotates; note that in the comment. A typed collision arm carrying the group as data (the shape `InvalidSchema.CaseFoldCollision` exists for) is Backlog material, not this fix.
4. Javadoc sweep, stated at altitude rather than formula-bound (one namespace, no boundary a fold would bridge; where a filesystem rationale is wanted, point at `Rejection.InvalidSchema.CaseFoldCollision`): the class comment on `LauncherRelation` (including the false "every relation" roster claim, rewritten to speak about this relation only), the method comment on `methodCollisions`, the sentence in `RoutineWriteRelationTest`'s javadoc asserting the sibling relations need the fold, and the paragraph in `RoutineWriteRelation`'s class javadoc that restates the fold as live fact.
5. Tests, first coverage of `methodCollisions` at all. Admit half: one pipeline case in `LauncherCommandsPipelineTest` over the `undervisningStartterminIPeriode` / `undervisningStartterminIperiode` SDL asserting two rows with distinct `unit().methodName()` values; `LauncherCommands.produce` constructs the relation, so this one case is also the constructor-admission test, and the pipeline tier beats compilation here because the shape is assertable on the model. Reject half, two homes driven by one SDL fixture (`fooBar` / `FooBar`) so the mirror itself is what gets asserted rather than two independent facts: a validator unit test asserting the located rejection by `RejectionKind` plus message substring, and the constructor backstop driven through `produce` over the same SDL (shape precedent: `RoutineWriteRelationTest.theRelationIsKeyedByCoordinate`). After the key change the residual mirror risk lives in `mintedMethodOf`'s verdict reading, not the key; the shared fixture covers it at name grain, and the constructor backstop remains the enforcer either way.

## Verification that nothing downstream reads the name case-insensitively

Checked before writing the plan, because a second unstated reason for the fold would mean a narrower fix:

- **DataLoader names.** Derived from the GraphQL execution path, not from method names: `String.join("/", env.getExecutionStepInfo().getPath().getKeysOnly())`, inline at single-tenant registration sites and through the generated `TenantConnections.loaderName` / `tenantLoaderName` for multi-tenant ones (minted by `ConnectionRuntimeClassGenerator.loaderName` / `.tenantLoaderName`). Case-sensitive, and unrelated to the minted method name.
- **Readers of the minted name.** The proposition that matters is bounded: a case-insensitive reader would have to consume `LauncherCommand.unit()`, and its consumers are the launcher renderer, the fetcher generator's dispatch on `rowFor`, and the closure oracle in `LauncherRelationClosureTest`. Each reads the ref value; none folds. (An earlier draft instead enumerated every case-folding site in the module's main sources; that hand census was both incomplete and not the proposition the fix rests on, so it does not appear here.)
- **Per-method emitted artifacts.** None. Launcher methods are added to their owner's `TypeSpec` by name (`MethodSpec.methodBuilder(row.unit().methodName())`); emitted files are per class, and class addresses keep their own folded census.

## Retired vocabulary

- `MethodCollision.foldedKey` (the accessor is renamed; the record carries the minted `UnitMethodRef`).
- "case-folded" as a description of the launcher-method census, in code, javadoc, and rejection text. The phrase stays live for the projection address census, which keeps its fold.


## Reviewer findings

### Round 1 (2026-08-31, Spec -> Ready, reviewer session 01LXqAwL8CTcA3oK188ULUJo)

Verdict: withhold. Two findings on question two, one on question one. The goal is well
communicated and the diagnosis is right: I re-derived it against the tree and every load-bearing
claim holds. `LauncherRelation`'s compact constructor and `LauncherCommands.methodCollisions` are
the only two sites in the module that fold a *method* name (`ProjectionRelation` and
`ProjectionCommands` fold class addresses, which is the justified case); `GeneratedUnits.upperCamel`
touches the first character only; `LauncherRelationClosureTest.noTwoRowsClaimTheSameEmittedMethod`
does key on unfolded `fqcn#method`; `RoutineWriteRelation`'s javadoc does restate the fold's
rationale as live fact; the validator's rejection really does splice a fully lowercased
`fqcn#method` at the author. Keying on the `UnitMethodRef` value rather than a hand-spelled string
is the right shape and removes a two-site drift risk rather than adding a mechanism. What blocks
sign-off is one design fork the plan raises and then leaves half-answered, and one sweep site the
plan misses that is the sharpest instance of the drift it is fixing.

**Finding 1 (question two: architecture fit). Step 3 names the rejection a DEFERRED species but
changes only the comment, while step 5 asks a new test to pin `RejectionKind`.**

`validateLauncherMethodNames` emits `Rejection.invalidSchema(...)` today, which
`RejectionKind.of` projects to `INVALID_SCHEMA`. The precedent step 3 cites,
`validateSiblingProjectionAgreement`, does both halves: its javadoc carries the "Deferred, not an
author error" paragraph *and* its body emits `Rejection.deferred(...)`. Step 3 asks for the
annotation without the arm, so the implementer faces a fork the spec does not decide, and step 5
then requires a test that asserts a `RejectionKind` the spec has not named.

Neither arm is free. Switching to `Rejection.deferred` is a user-visible behaviour change beyond
"update the rejection text": `RejectionKind.messageLabel` renders `Deferred:` instead of
`Invalid schema:` in gcc-style validator output, the maven watch formatter's per-error tag and
summary line re-bucket, and the LSP severity mapping pinned by
`RejectionSeverityCoverageTest` reads the same projection. Keeping `invalidSchema` and adding a
comment that calls the rejection a deferred species leaves a javadoc claim contradicting the code
it annotates, which is precisely the failure mode this item exists to repair: it would land a
fresh instance of inherited-and-unchecked rationale in the same commit that removes an old one.

What would satisfy this: pick an arm and write it into step 3. If the arm changes, say so
explicitly, name the surfaces above as in scope, and say whether `RejectionSeverityCoverageTest`
or the watch-formatter coverage needs a companion update. If it stays `INVALID_SCHEMA`, say why
the annotation is about the *reason* the schema is unbuildable rather than the kind, so the
comment does not read as a mis-stated arm to the next reader. Either way step 5's "by
`RejectionKind`" then has a referent.

**Finding 2 (question two). The step 4 sweep misses `LauncherRelationClosureTest`'s class
javadoc, which is the site whose staleness this item's own argument turns on.**

Its class javadoc describes the exactly-one bullet as "no two rows claim the same emitted method
(enforced by the relation constructor's case-folded census; pinned here at the run level)". The
item body cites this same test as the tree's already-exact third reading, the one the two folded
sites disagree with. Leaving its javadoc calling the constructor census case-folded after the
constructor stops folding reproduces the exact drift the fix removes, one file away from the
assertion that proves the point. Add it to the sweep list in step 4.

The `Retired vocabulary` section would catch this at the Done gate's retirement sweep, which is
why this is a sweep-list omission rather than a correctness hole. It belongs in the list anyway:
the Done-gate sweep is a backstop for what the plan missed, not the plan's inventory.

**Finding 3 (question one: is the argument complete). The completeness argument defends dropping
the fold on the method half of a two-part key and says nothing about the owner half.**

The key is `(owner, method)` and today folds the whole concatenation, so the fix drops the fold on
the owner component too. "Method names are never files" is true and settles the method half; owner
`fqcn` *is* a file address, which is the one place the item's own namespace-boundary test would
license a fold. The fix is still safe, but for a reason living outside this item's argument:
`GraphitronSchemaBuilder.rejectCaseInsensitiveTypeCollisions` already rejects two type-name stems
that are case-equivalent, so two fetchers owners minted as `<Parent>Fetchers` cannot differ only in
case by the time production runs. That pass is not unconditional, it skips variants that do not
implement `EmitsPerTypeFile`, so the claim is worth stating rather than leaving for the next reader
to re-derive.

One sentence in the third paragraph closes it: the owner component's case-fold protection is
upstream, at the type-name-stem census, and this census does not need to restate it.

**Corrected in passing (does not change what gets built).** The verification section named
`TenantDslEmitter.loaderNameDeclaration` as the multi-tenant loader-name minting site. No such
type exists in the tree; the methods are minted by `ConnectionRuntimeClassGenerator.loaderName` /
`.tenantLoaderName` onto the generated `TenantConnections`. Repointed. The claim itself holds:
both bodies derive the name from the execution path, never from a launcher method name.
