---
id: R889
title: "The launcher-method census folds case, rejecting a valid deprecated-alias field pair"
status: In Review
bucket: bug
priority: 2
theme: diagnostics
depends-on: []
created: 2026-08-31
last-updated: 2026-09-01
---

# The launcher-method census folds case, rejecting a valid deprecated-alias field pair

A consumer schema that declares two sibling fields whose names differ only in the case of a letter after the first is rejected at generation, with a located error saying the two coordinates mint one launcher method. They do not. The two emitted methods are `rowsUndervisningStartterminIPeriode` and `rowsUndervisningStartterminIperiode`, which are two distinct and perfectly legal Java methods; nothing would collide at emission. They are rejected only because the census that guards launcher-method uniqueness lowercases its whole key before comparing.

A *launcher method* is the generated method that owns one root or batched-child coordinate's whole query composition, hosted on the coordinate's fetchers class. Its name is minted by a naming formula in `GeneratedUnits` (`rows<Field>`, `load<Field>`, `lookup<Field>`) and a *census* is the uniqueness check over those minted names, which exists because the formula is not injective: it upper-camels the field name, so the two fields `fooBar` and `FooBar` genuinely mint one method, `rowsFooBar`. The census lives at two sites that must agree, or validation and generation drift apart: `LauncherRelation`'s compact constructor (the relation's construction-time integrity check, whose failure is a hard throw) and `GraphitronSchemaValidator.validateLauncherMethodNames` reading `LauncherCommands.methodCollisions` (the authored-schema mirror, which is what an author sees, located at the colliding declarations).

The exact comparison is complete regardless of the formula, because the census compares names within one namespace: both sides are names the generator itself minted onto one owning class, and two coordinates that mint one method mint the *identical* string. A fold is minted only where two namespaces meet, which is the projection address census's situation (a generated class name becomes a file name, and a case-insensitive filesystem genuinely collides; the javadoc on `Rejection.InvalidSchema.CaseFoldCollision` carries that rationale). Method names are never files, so there is no boundary to bridge, and what the fold adds beyond exact comparison is only the rejection of pairs whose emitted names are distinct, which is by definition not a collision. The current formula corroborates this without carrying it: `GeneratedUnits.upperCamel` upper-cases the first character and nothing else, so the folded census's extra rejections are exactly the pairs differing after the first letter. The key's other half is the owner, and an owner fqcn *is* a file address, so unfolding that half wants its own reason rather than the method half's: `GraphitronSchemaBuilder.rejectCaseInsensitiveTypeCollisions` already rejects two type-name stems that are case-equivalent, so two fetchers owners minted as `<Parent>Fetchers` cannot differ only in case by the time a producer runs. That pass skips variants which do not implement `EmitsPerTypeFile`, so it is worth naming here rather than assuming: the owner half's protection is upstream, and this census neither restates it nor needs to. The tree already contains the exact reading of the invariant: `LauncherRelationClosureTest.noTwoRowsClaimTheSameEmittedMethod` calls itself the run-level pin of the relation census and keys on unfolded `fqcn#method`, disagreeing with the two folded sites; the fix makes all three agree.

The fold appears to have been inherited rather than reasoned. Its javadoc cites "the projection producer's address-census precedent", `ProjectionRelation`, which folds a fully-qualified class name; for class addresses the fold is justified as above, for method names it is not. The belief that the non-injective formula forces the fold is stated as live fact in two more places that must be swept with the fix: `RoutineWriteRelationTest.coordinatesDifferingOnlyInCaseMintDistinctMethodsAndAreBothAdmitted` pins the case-sensitive reading for the sibling relation whose entry-point name *is* the field's own, and asserts in its javadoc that the upper-camelling relations need the fold; and `RoutineWriteRelation`'s own class javadoc restates the same claim and closes with "Do not add either by analogy", which is the vector by which the fold's rationale spread. `LauncherRelation`'s class comment additionally carries an unguarded roster claim ("every relation therefore also passes a case-folded census") that is already false, since `RoutineWriteRelation` has no census and `ProjectionRelation` folds addresses, not methods.

The schema being rejected is a deprecated-alias pair, a typo'd field kept alive beside its corrected spelling with a `@deprecated` reason carrying a published removal date. Fixing it consumer-side would mean retiring a field ahead of a date promised to API clients, to work around a check that is rejecting a valid schema, and keep-the-typo-as-a-deprecated-alias is a pattern any consumer may reach for, so the rejection will recur.

## Plan

1. `LauncherRelation`'s compact constructor: key the census on the minted `UnitMethodRef` value itself. The ref is a record over `(owner, methodName)`, so its value equality *is* the exact key; a `Map<UnitMethodRef, LauncherCommand>` removes both the fold and the hand-spelled string concatenation whose "no fold" property two sites would otherwise have to hold in step. Render `owner#method` only in the throw message, which no longer says "case-folded".
2. `LauncherCommands.methodCollisions`: same key change (group by the ref), and `MethodCollision` carries `UnitMethodRef method` instead of the `foldedKey` string. This also repairs the author-facing text, which today splices a fully lowercased `fqcn#method`, a name that exists nowhere, into the rejection. Do not copy `ProjectionCommands.byFoldedName`, which is the same stringly shape on the sibling census.
3. `GraphitronSchemaValidator.validateLauncherMethodNames`: change the rejection arm to `Rejection.deferred` and update its text, dropping "(case-folded)" and naming the actual minted method. The arm is wrong today by `RejectionKind`'s own rule of thumb: `INVALID_SCHEMA` is reserved for "this combination cannot work, period", where "no rename or reference fix repairs it", and this rejection's message ends by telling the author to rename one of the colliding fields. `DEFERRED` is the fitting arm, on the reading `validateSiblingProjectionAgreement` both annotates *and emits*: the schema is legal and meaningful, the generator's non-injective formula cannot emit it, and an injective one would. Annotate the species in the comment as that sibling does, so the comment and the arm say one thing.

   What the arm change moves, each checked against the tree: `RejectionKind.messageLabel` renders `Deferred:` instead of `Invalid schema:` on the validator's gcc-style line, the maven watch formatter's per-error tag and its `"3 author-error, 1 deferred"` summary re-bucket, and the `kind` column `AuthoredClaimRejectionRows` writes into the rejection residue carries `DEFERRED`. What it does not move: the build still fails, because `GraphQLRewriteGenerator` aborts emission on a non-empty error list and reads no kind; the editor squiggle stays an error, because the diagnostics view spells every rejection arm error "including the deferred one, on the build's own finality"; and `RejectionSeverityCoverageTest` is keyed on sealed permits rather than on producing sites, so a site changing arms needs no companion update there. Nothing pins the current arm or text, the site having no coverage at all, which step 5 fixes. A typed collision arm carrying the colliding group as data (the shape `InvalidSchema.CaseFoldCollision` exemplifies, under whichever seal the species lands in) stays Backlog material, not this fix.
4. Javadoc sweep, stated at altitude rather than formula-bound (one namespace, no boundary a fold would bridge; where a filesystem rationale is wanted, point at `Rejection.InvalidSchema.CaseFoldCollision`): the class comment on `LauncherRelation` (including the false "every relation" roster claim, rewritten to speak about this relation only), the method comment on `methodCollisions`, the sentence in `RoutineWriteRelationTest`'s javadoc asserting the sibling relations need the fold, the paragraph in `RoutineWriteRelation`'s class javadoc that restates the fold as live fact, and the exactly-one bullet in `LauncherRelationClosureTest`'s class javadoc, which calls the constructor census case-folded in the same file as the unfolded assertion this item cites as the tree's already-exact third reading.
5. Tests, first coverage of `methodCollisions` at all. Admit half: one pipeline case in `LauncherCommandsPipelineTest` over the `undervisningStartterminIPeriode` / `undervisningStartterminIperiode` SDL asserting two rows with distinct `unit().methodName()` values; `LauncherCommands.produce` constructs the relation, so this one case is also the constructor-admission test, and the pipeline tier beats compilation here because the shape is assertable on the model. Reject half, two homes driven by one SDL fixture (`fooBar` / `FooBar`) so the mirror itself is what gets asserted rather than two independent facts: a validator unit test asserting the located rejection by `RejectionKind.DEFERRED` plus message substring (the arm assertion is what keeps step 3's species claim from being prose), and the constructor backstop driven through `produce` over the same SDL (shape precedent: `RoutineWriteRelationTest.theRelationIsKeyedByCoordinate`). After the key change the residual mirror risk lives in `mintedMethodOf`'s verdict reading, not the key; the shared fixture covers it at name grain, and the constructor backstop remains the enforcer either way.

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

**Response (2026-08-31).** Picked the arm: step 3 now switches the site to `Rejection.deferred`. The deciding evidence is `RejectionKind`'s own rule of thumb, which reserves `INVALID_SCHEMA` for "no rename or reference fix repairs it" while this rejection's message tells the author to rename a field, so the current arm contradicts the enum's contract independently of this item. Step 3 now names what the switch moves (`messageLabel`, the watch formatter's tag and summary bucket, the residue's `kind` column) and what it does not: `GraphQLRewriteGenerator` aborts emission on any non-empty error list without reading the kind, so the build still fails; the LSP squiggle stays an error by the diagnostics view's stated rule; and `RejectionSeverityCoverageTest` is permit-keyed, so no companion update is owed. Nothing pins the current arm or message text. Step 5's validator test now names `RejectionKind.DEFERRED`.

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

**Response (2026-08-31).** Added to the step 4 sweep list, phrased so the reason travels with it: it is the one site whose stale wording sits in the same file as the unfolded assertion the item argues from.

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

**Response (2026-08-31).** Added to the third paragraph, naming `GraphitronSchemaBuilder.rejectCaseInsensitiveTypeCollisions` and its `EmitsPerTypeFile` skip so the protection reads as located rather than assumed.

**Corrected in passing (does not change what gets built).** The verification section named
`TenantDslEmitter.loaderNameDeclaration` as the multi-tenant loader-name minting site. No such
type exists in the tree; the methods are minted by `ConnectionRuntimeClassGenerator.loaderName` /
`.tenantLoaderName` onto the generated `TenantConnections`. Repointed. The claim itself holds:
both bodies derive the name from the execution path, never from a launcher method name.

**Revision note (2026-08-31).** The three findings above were addressed by the reviewer session that
raised them, at the user's direction, rather than by a separate author session. The reviewer rule is
unaffected in substance: that session is now the artifact's last committer, so it is disqualified
from the next `Spec -> Ready` pass and a third session signs off.

### Round 2 (2026-09-01, Spec -> Ready, reviewer session 016KbtCNGhEdgEA9hNXHiBuz)

Verdict: sign off. Both round-1 architecture findings and the round-1 completeness finding are
closed, and the revised claims hold against the tree.

Question one. What changes for a consumer: a schema whose two sibling covered coordinates differ
only in the case of a letter after the first generates instead of being refused, and the refusal
that remains (a genuine `fooBar` / `FooBar` pair) names the method the generator actually mints
rather than a fully lowercased string that exists nowhere, under a `Deferred:` label instead of
`Invalid schema:`. Reachable: `GeneratedUnits.upperCamel` touches the first character only, so the
folded census's extra rejections are exactly the pairs the item describes.

Question two. The fix removes a mechanism and replaces a hand-spelled key with the record's own
value equality; `UnitMethodRef` and `UnitRef` are both records over their components, so the key
change is structural rather than a new comparison. Step 3's arm is now decided and its blast radius
is checked at each named surface: `RejectionKind.messageLabel` and `displayName` (whose javadoc
carries the summary-line form), `WatchErrorFormatter`'s per-error tag and summary, the residue's
`kind` column, `GraphQLRewriteGenerator`'s kind-blind abort on a non-empty error list, the
diagnostics view's "every rejection arm is an error, including the deferred one", and
`RejectionSeverityCoverageTest`'s permit-keyed round trip. `methodCollisions` has no test caller
today, so "first coverage at all" holds and nothing pins the current arm or text.

The step 4 sweep is now exhaustive: every launcher-fold mention in the tree falls under a step.
`GraphitronSchemaValidator`'s own `validateLauncherMethodNames` javadoc is the one launcher site not
on the step 4 list, and it is the site step 3 already rewrites. The two remaining `case-folded`
mentions in that file belong to the projection address census, which keeps its fold.

Finding 3's owner-half argument closes tighter than the text claims. `EmitsPerTypeFile` is
implemented by every `GraphitronType` arm except `ScalarType` and `UnclassifiedType`, neither of
which hosts a launcher coordinate, so the skip leaves no residual for launcher owners.
