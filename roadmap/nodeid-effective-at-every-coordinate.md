---
id: R728
title: "Four @nodeId sites where the directive binds nothing or its join shape cannot be emitted"
status: Spec
bucket: feature
priority: 3
theme: nodeid
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# Four @nodeId sites where the directive binds nothing or its join shape cannot be emitted

`@nodeId` is a *binding* directive. It names a wire format, the base64 `(typeId, key columns)`
node ID, and asks the generator to cross between that wire form and typed key columns: encode on
an output field, decode on an argument or an input field. Where the binding is wired, an author
writes the directive and never sees the base64. Where it is not, the author writes the same
directive, the build says nothing, and the raw string flows through to consumer code that takes
it apart by hand.

A field report against 10.0.0-RC32 names four sites where that second thing happens. Three of
them are the directive binding nothing: an argument of a `@service` field, a member of a
bean-backed `@service` input, and a field on an `@error` type. The fourth is different in kind
and shares the same consequence: two filter shapes whose predicate needs a correlated `EXISTS`
the emitter does not build, so the author decodes the node ID by hand inside a `@condition`
method. In each case the schema *reads* as though graphitron owns the wire format and it does
not.

This is filed as one item because the four share a subject rather than a mechanism. The subject
is the promise the directive makes at every site it may legally be written. Splitting them
produces four items each of which is individually easy to defer as a corner, and leaves nobody
owning the question the reporter is actually asking, which is whether `@nodeId` means the same
thing everywhere.

## The four sites

Each site below was reproduced on trunk. The evidence is the classified carrier or the generated
body, not a reading of the source.

### 1. A plain argument on a `@service` field

```graphql
publiserRundeV2(plasstildelingId: ID! @nodeId(typeName: "PlasstildelingV2")): PubliserRundeV2Payload
    @service(service: {className: "...", method: "publiserRundeV2"})
```

The generated fetcher passes the wire string straight to the service method, so the service
decodes by hand. `ServiceCatalog.argExtraction` is the whole story: it inspects wire coercion and
enum parity and never looks at `@nodeId`, so every scalar `@service` argument resolves to
`CallSiteExtraction.Direct`. The classified field carries
`leafTransform=Direct[]` on the argument's `ValueShape.Scalar`.

`ServiceMethodCallEmitter.scalarLeaf` does carry a `CallSiteExtraction.NodeIdDecodeKeys` arm, and
that arm emits the same raw cast as `Direct`. So even if the extraction were minted, the emitter
would drop it. Both halves are in scope.

### 2. A member of a bean-backed `@service` input

The same silence one level in. An `ID` field carrying `@nodeId(typeName:)` on an input type that
backs a consumer bean generates:

```java
java.lang.String title = (java.lang.String) raw.get("title");
```

inside the `create<Bean>` helper. This is the shape the reporter reached for as the workaround
for site 1 and found equally inert.

### 3. A field on an `@error` type

An `@error` type's extra fields are read off the matched exception through an accessor. An `ID`
field carrying `@nodeId(typeName:)` classifies as a plain
`GraphitronField.RecordReadField` with a `DefaultRead` locator: the accessor's value is emitted
verbatim, and the directive contributes nothing. The consumer's exception constructor therefore
takes an already-encoded string, and the encoding happens in hand-written code:

```java
new SoknadFeilSvarfristUtloptException(encodeOpptaksrunde(record), ...)
```

The ask is `@nodeId(typeName:)` on the error field plus a typed value on the exception, with the
encode emitted.

### 4. Two filter shapes whose predicate needs an EXISTS

Both have fully declared foreign-key paths. They differ in whether the emitter can build the
predicate.

**Reverse direction, filtering parents by their children's node IDs.** This one already works and
is reported as broken because nothing points the author at the spelling. The single reverse hop
classifies as `NodeIdLeafResolver.Resolved.FkTarget.TranslatedFk` and lowers to
`BodyParam.RemoteColumnPredicate`, the correlated `EXISTS`, on both the argument and the
input-field surface. What stops an author reaching it is that foreign-key auto-discovery searches
from the containing table outward only, so a reverse hop rejects with "no unique FK from X to Y"
unless the child's constraint is named explicitly:

```graphql
utdanningstilbudIds: [ID!] @nodeId(typeName: "UtdanningstilbudV2")
    @reference(path: [{key: "utdanningstilbud_opptak_fkey"}])
```

The remedy here is documentation and possibly a better rejection message, not an emitter. See
"Relationship to other items" for the manual page that currently tells the reader this shape
cannot exist.

**One-to-many through a junction table.** `Sak` filtered by `Tagg` IDs via `soknad_tagg`. Every
hop is a declared foreign key, but the chain is not *identity-carrying*: the second hop's
source-side columns are absent from the first hop's target-side columns, so no column tuple on
the parent's own row holds the decoded key. `NodeIdLeafResolver.validateLift` rejects it. The
message names the failing hop and is accurate about the cause, but the shape it describes is
ordinary, and the emitter it needs turns out to be already built. See "Site 4b" under Design: the
rejection is a discriminator stating one of its two conjuncts, not a missing emitter.

## What already ships, so the Spec does not rebuild it

* **The wire format is generated and public.** `NodeIdEncoder` lands in `<outputPackage>.schema`
  whenever any type carries `@node`, as a final class with a private constructor and public
  static `encode<TypeName>(k1, ..., kN)` / `decode<TypeName>(String)` per node type, plus
  `peekTypeId(String)`. Hand-rolled base64 in consumer code is never necessary, only inconvenient.
  Site 3's reporter can collapse their three hand-rolled encoders onto `encode<TypeName>` today,
  which is worth telling them whether or not this item ships.
* **The jOOQ `TableRecord` service parameter decodes.** When a `@service` method parameter is a
  generated `*Record`, an `@nodeId` field on the backing input type decodes into that record's key
  columns (same-table identity) or its foreign-key child columns (cross-table reference). This is
  the shape site 1's reporter wants, reachable today by giving the method a
  `PlasstildelingRecord` instead of a `String`. It is a workaround for the ask rather than an
  answer to it, because it forces an input type where the author wrote an argument.
* **Single-hop translated-FK filters emit the `EXISTS`.** Both read surfaces. The write and
  `@lookupKey` rails deliberately refuse the shape at their own gates.
* **Multi-hop identity-carrying chains lift to a single-table predicate.** No JOIN, no subquery,
  chain length is a classifier-time concept.

## Design

### What the four sites actually share

Sites 1 and 2 are one problem wearing two coordinates: **one Java slot, N decoded values**. A node
ID decodes into the node type's key columns, which is a tuple; a scalar `@service` parameter and a
scalar bean member each hold one value. The two shapes that already work are exactly the two whose
destination can hold a tuple: a `@service` parameter typed as a generated jOOQ `*Record`, and a
record-typed member of a consumer bean. `InputBeanResolver` demonstrates this directly. It already
reads `@nodeId` on a bean member and rejects a missing `typeName:` there
(`buildRecordKeyDecode`, and the record-typed-member gate below it); what it has no arm for is the
scalar member, because there is nowhere to put the values. So the silence at sites 1 and 2 is not
an oversight about the directive, it is an unanswered question about the destination.

Site 3 is the same question in the encode direction, with a different unknown: not where N values
go, but where they come from, since an exception exposes accessors rather than a row.

Site 4 is not about arity at all. It is about which table the predicate binds against, and the
work there is smaller than the report implies.

### Site 4b: the discriminator states one conjunct where it means two

**This was spiked and the result changes the shape of the item.** `BodyParam.RemoteColumnPredicate`
carries a whole `joinPath`; `ConditionCommands.narrowPath` narrows every step to an `FkHop`; and
`ConditionGlueRenderer.reachExists` walks the whole reach, selecting from the terminal alias,
bridging back through hops `n-1 .. 1`, and correlating hop 0 against the row's own table. The
`EXISTS` emitter is already hop-general. `FkTargetConditionFilter` says so in its own javadoc
("Single-hop for the common case; multi-hop walked inside the `EXISTS`").

What blocks the junction chain is upstream of all of it. `NodeIdLeafResolver` picks `DirectFk`
when `permutationToKeyColumns` succeeds on the terminal hop, and `DirectFk`'s meaning is "the
decoded keys lift to a tuple on the field's own table". Those are two facts, and the resolver
checks one: `validateLift` runs earlier and *rejects* rather than recording that no lift exists.
`TranslatedFk`, whose whole premise is "no own-table tuple, bind on the target inside an `EXISTS`",
is therefore unreachable for a multi-hop path.

The change is to make `DirectFk`'s precondition state both conjuncts, and to state them in the type
rather than in a comment. `JoinPathResult` today is a three-slot nullable bag whose `error` is prose
(it downgrades typed `Rejection`s to their message, only for `resolve` to re-wrap them as
`Rejection.structural`). Threading "lifted to nothing" through it as a second meaning of `null`
would put the new discriminator on a convention no compiler checks. So it seals:

```java
sealed interface JoinPathResult permits Lifted, Unlifted, Failed
    record Lifted(List<JoinStep> path, List<ColumnRef> liftedSourceColumns)
    record Unlifted(List<JoinStep> path)
    record Failed(Rejection rejection)
```

`resolveFkJoinPath` answers `Unlifted` where it used to reject, `resolve` takes the `DirectFk` arm
only on a `Lifted` result conjoined with a non-null key-column permutation, and everything else
falls through to the `TranslatedFk` arm already there. The typed `Rejection` stops round-tripping
through a string on the way.

Spiked with the nullable-slot shortcut rather than the sealed one (the discriminator is what the
spike was testing), `film -> film_category -> category` (the same shape as
`Sak -> soknad_tagg -> Tagg`) lowers to a two-hop `RemoteColumnPredicate` and renders:

```java
DSL.exists(DSL.selectOne()
    .from(table_fkt0_1)
    .join(table_fkt0_0).onKey(Keys.FILM_CATEGORY__FILM_CATEGORY_CATEGORY_ID_FKEY)
    .where(table_fkt0_0.FILM_ID.eq(table.FILM_ID).and(table_fkt0_1.CATEGORY_ID.in(categoryIds))))
```

which is the predicate the report asks for. The identity-carrying chain still resolves `DirectFk`
and still emits the local `IN`. Under the spike the full non-execution suite ran **3626 of 3627
green**, and the single failure was `NodeIdLeafResolverTest.multiHopLiftTranslationRejected`, the
test pinning the rejection the change deliberately removes. Nothing else in the tree depends on
the gate.

Two properties make the relaxation safe rather than merely cheap:

* **The write rails already refuse `Remote`.** INSERT (`MutationInputResolver`),
  `UpdateRowsWalker`, `DeleteRowsWalker` and `FieldBuilder.classifyPlainLookupKeyArg` each gate on
  the `FilterBinding` arm with the shared `FilterBinding.remoteBindingUnsupported` text. A junction
  chain reaching a write or `@lookupKey` coordinate rejects with a stated message rather than
  emitting a wrong statement, and it does so without this item touching those gates.
* **`EXISTS` is already the argued-for shape at non-unique cardinality.** R57's changelog entry
  settles it: no row multiplication when the path is non-unique, and a NULL foreign-key column
  fails the correlation instead of duplicating or dropping rows. A junction chain is the
  non-unique case that argument was written for.

The retired vocabulary is `LIFT_FAILURE_MARKER` and the rejection it anchors. Note that the
`@LoadBearingClassifierCheck` mechanism the original lift work paired with that marker no longer
exists (retired wholesale by R237); the surviving structural pin is the sealed `FkTarget` arm plus
the pipeline-tier carrier assertions, and this change is expressed there.

**Relaxing a producer obliges a consumer audit in the same commit.** That is a stated rule here, and
the spike's 3626/3627 is evidence about the *read* path only: the pipeline tier does not currently
pin a write-side multi-hop non-lifting `@nodeId`, so it says nothing about what those coordinates
do afterwards. Today such a path at a write or `@lookupKey` coordinate rejects with a message naming
the failing hop and its columns. Afterwards it classifies `TranslatedFk`, binds `Remote`, and meets
the rail's own refusal, whose text is about a Remote binding rather than about the author's chain.
The stage therefore names all four rails (INSERT via `MutationInputResolver`, `UpdateRowsWalker`,
`DeleteRowsWalker`, `FieldBuilder.classifyPlainLookupKeyArg`) and states, per rail, which message the
author now sees. Where the answer is "a worse message", that rail keeps a check of its own.
`DirectFk`'s javadoc states the multi-hop lift as a class invariant; it stays true, but the
rejection it justifies moves, so the prose moves with it.

**The two gates in `resolveFkJoinPath` are one predicate.** `CONDITION_STEP_MARKER` and
`validateLift` both mean "the decoded key does not land on a tuple of the parent's own row", stated
twice as two negations, and that is precisely the `DirectFk` precondition. R705 is queued to relax
the first while this item relaxes the second. Relaxed independently, the discriminator ends up as
two separately-maintained refusals whose agreement nothing binds, which is the implicit-coordination
smell the principles name. Stated once and positively, the lift walk *returns a tuple or nothing*
and a condition-join hop is simply a hop that cannot contribute one; both gates collapse into the
sealed result above and both items land the same emitter route. The earlier note that this item and
R705 "should be read together" understates it: they are one edit, and whichever lands first should
land the sealed result for both.

### Site 4a: the reverse hop needs a message and a page, not an emitter

Verified separately: a single reverse hop already classifies `TranslatedFk` and lowers to
`RemoteColumnPredicate` on both the argument and input-field surfaces. The only thing standing
between an author and it is that `JooqCatalog.findUniqueFkToTable` searches from the containing
table outward, so the reverse direction rejects with "no unique FK from X to Y; declare
`@reference(path: [{key: ...}])` to disambiguate". That message does name the spelling that works,
which is why this is a wording fix rather than a missing remedy: it frames the spelling as
*disambiguation among several candidates*, and an author whose problem is *zero* candidates in the
searched direction reads it as not applying to them. Two small changes:

* the rejection distinguishes its two causes. Several foreign keys is a disambiguation; none in the
  searched direction is a different fact, and the message should say that a foreign key declared on
  the *target* side is reachable by naming it explicitly;
* `docs/manual/how-to/multi-hop-nodeid-filter.adoc` stops asserting that a single direct foreign
  key never produces a subquery. That page correction is R691 and this item should either absorb
  it or depend on it rather than restate it.

### Sites 1 and 2: reject in v1, and let the projection arrive from R668

The destination question above has three candidate answers, and this item should pick the one that
does not fork a surface another item is mid-flight on.

* **Key-column projection.** The author names one key column as a trailing path segment, so the
  slot receives one value and the SDL says which. This is what R668 is building; its resolution
  views have shipped and its final stage lands the `@service` site along with `@routine` and
  output-field `@condition`.
* **Whole-record binding.** Give the parameter a generated `*Record`. This works today for the two
  record-shaped destinations, and R668's spec holds it out as a separate capability for a scalar
  `@service` parameter.
* **Reject.** Say at classify time that the directive cannot bind at this slot, naming the slot and
  the spellings that do work.

**Decision: reject, and inherit the projection.** The reason is keying, not deferral, and stating it
that way is what stops it reading as an abdication. `intent_resolved_node_key_projection` is a
*use-keyed reduction over the `argMapping` pair relation*. Minting key-column projection at a slot
with no pair row would give that reduction a second producer, and two spellings of one resolution
agree exactly until one of them changes. Option two is not a larger version of option one: it
changes what the author binds, which is why R668 classed it as its own capability. Declining both is
a re-sourcing decision.

The value the reporter asked for first is not the decode anyway, it is being told. Their words: they
hit this in production schemas twice before noticing.

**What the rejection is keyed on.** The same keying argument that makes R668's projection
unreachable here makes R668's *rejection* unreachable too: it is keyed on the pair, and a `@service`
argument with no authored `argMapping` produces no pair row. So this item has to say which relation
its own rejection keys on, and the key discipline answers: the authored `@nodeId` application,
definition-keyed at the slot's own coordinate. Those relations exist and are keyed exactly there,
`graphitron_argument_node_id` on `(graph, type, field, argument)` and `graphitron_field_node_id` on
`(graph, type, field)`, both carrying `node_type_ref` and a source position. That is where the
author's cursor sits, and it is the coordinate the message must name.

### The rejection is one derived detection, not a branch per site

Sites 1, 2 and 3 all need the same sentence said at three coordinates. The shape that says it once
is a derivation, not an `if` in each walk.

"An `@nodeId` application that no consuming population reads" is an agreement between two facts,
which the fact model names as a detection rather than a step. Landing it instead as a new branch in
`ServiceCatalog.argExtraction` and a second in `FieldBuilder.classifyChildFieldOnErrorType` would
extend the transitional walk during the strangler window, and would hand the LSP and the MCP
context nothing: the editor would still show green on a directive the build rejects.

So: a derived view anti-joining the two `@nodeId` relations against the consuming populations, read
by a small projector into located `ValidationError`s, the way `AuthoredClaimConflicts` reads
`intent_authored_claim_conflict` today. One rule, three surfaces. The later emission work at each
site then *shrinks the view's population* rather than requiring a branch to be deleted from two walk
classes, which is the property that makes this item's own stages compose instead of collide.

### Site 3: one per-field carrier on `ErrorType`, carrying the wire direction

An `@error` type's extra field is not projected by a generated fetcher at all. It is read at runtime
by graphql-java's `PropertyDataFetcher`, registered in
`GraphitronSchemaClassGenerator.buildErrorTypeFieldFetchers`. So the obvious move, hanging an encode
slot on the classified `RecordReadField`, puts the fact where the emitter never looks: that emitter
reads a *different* carrier, `GraphitronType.ErrorType.accessorOverrides`, plus two hardcoded names
for `path` and `message`. The classified field and the type-level override list are already two
spellings of one per-field read, and adding a third list beside them makes the divergence three-way.

So the design is a unification rather than an addition. `ErrorType` carries **one** per-field list
whose slot holds the read (an `accessorBase`, or the built-in `path` / `message` arm) *and* the wire
direction as a `CallSiteCompaction`. That is the vocabulary `ChildField.ColumnBackedField` already
uses, and its `NodeIdEncodeKeys(HelperRef.Encode)` arm carries no columns, so it ports to an
accessor-backed read unchanged. `buildErrorTypeFieldFetchers` becomes a fold over that one list, the
encode is the existing arm, and no private per-`@error`-type taxonomy appears. The classification
change is one arm in `FieldBuilder.classifyChildFieldOnErrorType`, which today ignores every
directive.

**Composite keys.** `encode<TypeName>` takes N key values positionally and an accessor returns one
Java value. The arity is already a fact in the model (`HelperRef.Encode.paramSignature.size()`), so
the branch belongs in the model rather than in the emitter. One accessor cannot supply N values, so
v1 rejects at validate time when the named node type's key arity exceeds one at an `@error` field.
That is the "validator mirrors classifier invariants" obligation the new classification arm incurs,
and it has a working precedent one screen away: `NodeIdLeafResolver` already rejects a node type
whose key arity exceeds jOOQ's `Row22` cap, with a message naming the type, the slot and the count.
The reporter's own case (`opptaksrundeId`) is single-key, so v1 serves it.

Widening to composite is a later item and wants a spelling that does not exist yet: either an
accessor returning a jOOQ `Record` of the node's key shape, unpacked positionally, or a way for the
SDL to name N accessors, which `@field(name:)` cannot express.

### Scope

In scope: the site-4b discriminator change; the site-4a message and page; the site-1/2 classify-time
rejection; the site-3 encode with whatever arity answer the Spec review settles.

Out of scope, each with a reason rather than a silence: minting the key-column projection at
`@service` (R668 owns it); whole-record binding of a decoded node ID to a scalar `@service`
parameter (R668 ruled it out as a separate capability and that reasoning holds here); condition-join
hops in filter paths (R705); write-side translation, which the four rails keep refusing.

## Stages

Ordered so each stage is separately verifiable, and so nothing ships a rejection ahead of its
replacement.

1. **The leaf-arm enforcer.** `ValueShape.Scalar.leafTransform` is typed at `CallSiteExtraction`'s
   nine-arm root, and the restriction to its four legal leaves is prose ("the walker enforces that
   restriction structurally"). Because the component is typed too wide,
   `ServiceMethodCallEmitter.scalarLeaf` needs a `default ->` arm, and that default is exactly why
   site 1 is silent: the `NodeIdDecodeKeys` case there emits a body byte-identical to `Direct`, and
   nothing failed when it was written that way. Lift the four arms into a sealed
   `CallSiteExtraction.Leaf permits Direct, EnumValueOf, JooqConvert, NodeIdDecodeKeys`, retype
   `Scalar.leafTransform` to `Leaf`, delete the `default`. Exit: minting a decode extraction without
   an emitter arm fails to compile. This is a prerequisite for any answer at sites 1 and 2, it is
   cheap, and without it the item's central bug can silently recur.
2. **Site 4b, the discriminator.** The sealed `JoinPathResult`, the two-conjunct `DirectFk`
   precondition, the retirement of `LIFT_FAILURE_MARKER`, the per-rail consumer audit, and coverage
   at the carrier, glue and execution tiers. Exit: a junction chain lowers to a multi-hop
   `RemoteColumnPredicate` and returns each parent once against PostgreSQL; the identity-carrying
   chain still binds `Local`; each of the four write rails has a stated message. Coordinated with
   R705 per the gate-collapse note, and with R676, whose Spec body cites `LIFT_FAILURE_MARKER` as a
   constraint its path grammar inherits.
3. **Site 4a, the message and the page.** The auto-discovery rejection separates its two causes; the
   manual page's single-hop claim is corrected; the reverse filter gets the execution-tier row-count
   pin it has never had. Exit: an author who writes the reverse filter without a `@reference` is
   told what to write. Independent of every other stage and the smallest thing in the item.
4. **The ineffective-`@nodeId` detection.** The derived view and its projector, covering sites 1, 2
   and 3 at once. Exit: the reported schemas fail the build with a message naming the slot and a
   spelling that works, and the same fact is available to the LSP and the MCP context rather than
   living inside two walk classes. Gated on R668's stage 3 wording being settled, which is the one
   cross-item dependency here.
5. **Site 3, the encode.** The `ErrorType` carrier unification, the classification arm, the
   registration swap, and the composite-arity rejection. Exit: an `@error` field carrying
   `@nodeId(typeName:)` returns an encoded node ID and the reporter's hand-written encoder call
   sites can go. Shrinks stage 4's view population at that site rather than deleting a branch.

Stages 1 through 3 are worth landing even if 4 and 5 slip: together they close the half of the
report that is a real emission gap, plus the enforcer that stops it recurring, and they carry no
open design question.

## Tests

Per the test-tier guide, with the primary behavioural weight at the pipeline tier and the row-
semantics claim at the execution tier.

* **Unit tier.** `NodeIdLeafResolverTest` gains the junction-chain case asserting `TranslatedFk` and
  the two-hop path. `multiHopLiftTranslationRejected` is *rewritten* from a rejection assertion to a
  `TranslatedFk` assertion rather than deleted, so the fixture that proved the old gate now proves
  the new routing. A sibling case pins that an identity-carrying chain still answers `DirectFk` with
  its lifted tuple, which is the regression this change could plausibly cause and the spike shows it
  does not.
* **Pipeline tier.** The junction chain lowering to `BodyParam.RemoteColumnPredicate` with
  `FilterBinding.Remote` and `joinPath.size() == 2`, on both the argument and input-field surfaces.
  Each of the four write rails refusing a `Remote`-bound junction carrier, asserting the text that
  rail actually produces (this is the consumer audit expressed as a test). The
  ineffective-`@nodeId` detection firing at each of the three slots and staying silent where the
  directive does bind. The site-3 classification arm and its composite-arity rejection.
* **Compilation tier.** Rides `graphitron-sakila-example`. `film_category` already exists in the
  sakila schema and is the natural junction fixture, so this may cost SDL only rather than
  `init.sql` changes.
* **Execution tier.** The tier that carries the actual claim. R57's argument for `EXISTS` is a
  row-semantics claim: a non-unique path multiplies no rows, and a NULL foreign key fails the
  correlation instead of duplicating or dropping. A junction table is the shape where that claim is
  load-bearing, and only PostgreSQL can check it. So the shipped assertion is a row count through a
  junction fixture, with a parent matching two children appearing exactly once, not the generated
  SQL text. Site 4a's reverse filter gets the same pin, which is the verification the Backlog notes
  flagged as outstanding before calling that shape shipped.

The spike's rendered SQL was the right evidence for a spike and is the wrong assertion to ship:
code-string matching on generated bodies is banned at every tier.

## Risks

* **The site-4b relaxation widens what classifies.** Schemas that fail the build today start
  generating. That is the point, but a schema whose author wrote a junction path expecting the
  rejection now silently gets an `EXISTS` over a fan-out. R723 is the item that says "this path
  multiplies" out loud; whether its rule fires on a junction `@nodeId` filter path is a question
  this Spec should answer before stage 2 lands, and if it does not fire, whether it should.
* **The write-side diagnostic gets worse before the audit fixes it.** Named above and handled by
  making the per-rail message an exit condition of stage 2 rather than a follow-up.
* **Two rejections, one condition, two wordings.** Stage 4 and R668's stage 3 partition rather than
  overlap: R668's fires on an authored `argMapping` pair binding a `@nodeId` leaf with no key-column
  segment, this item's on a slot with no pair row at all, so no single slot draws both. The risk is
  that an author moving between the two spellings meets two different messages for one condition,
  which is the same complaint R668's own plan raises about the two existing "cannot infer a node
  type here" texts. One vocabulary, minted once, is the mitigation, and it is why stage 4 is gated
  on R668's wording being settled rather than merely on its code landing.
* **A carrier named for the reporter's subject would inherit the question's shape.** The item is
  scoped by subject deliberately, and the corresponding hazard is producing a model type to match:
  a `NodeIdBinding` or `NodeIdEffective` spanning four sites would take its grain from "whatever the
  four sites needed". The check is the one the fact model prescribes: every stage must be able to
  say what it asserts without naming the reporter or this item. Nothing in the design above needs
  such a type, and the review should treat one appearing as a signal the grain slipped.
* **Site 3 stays single-key in v1.** Smaller than the item's framing promises. Acceptable because
  the composite spelling does not exist yet and inventing one here would be a second capability;
  the Spec review should confirm that rather than let it pass silently.

## User documentation (first-client check)

* `docs/manual/reference/directives/nodeId.adoc` gains the coordinate table this item is really
  about: where `@nodeId` binds, what it binds to, and what happens where it cannot.
* `docs/manual/how-to/multi-hop-nodeid-filter.adoc` loses the false single-hop claim (stage 2) and
  gains the junction shape as a worked example (stage 1).
* `docs/manual/reference/directives/error.adoc` gains the `@nodeId` extra-field case (stage 4).

## Retired vocabulary

* `NodeIdLeafResolver.LIFT_FAILURE_MARKER` and the "identity-carrying FKs" rejection text.
* The residual "identity-carrying-lift" phrasing in `NodeIdLeafResolver.resolveFkJoinPath`'s
  javadoc, which describes a gate this item removes.
* The `=== identity-carrying FKs` rejection section of
  `docs/manual/how-to/multi-hop-nodeid-filter.adoc`, which documents that gate to authors.
* `NodeIdLeafResolver.JoinPathResult`'s nullable-slot shape, replaced by the sealed result.
* `CONDITION_STEP_MARKER` too, if stage 2 lands the collapsed gate with R705 rather than beside it.

The retirement sweep at the Done gate has one coordination point beyond the tree:
`roadmap/nodeid-filter-per-participant-paths.md` (R676, Spec) names `LIFT_FAILURE_MARKER` as a
constraint its path grammar inherits, so its author has to be told the constraint moved rather than
disappeared. The `@LoadBearingClassifierCheck` mechanism that once paired with this marker no longer
exists (R237 retired the annotations and their audit wholesale), so there is no annotation
obligation here; the surviving structural pin is the sealed `FkTarget` arm plus the pipeline-tier
carrier assertions, and stage 2 expresses it there.

## Relationship to other items

* **R668** (`nodeid-key-projection-on-routine-params`, In Progress) is the nearest neighbour and
  overlaps sites 1 and 2 partially. It makes a node type's key columns nameable as a trailing
  `argMapping` path segment and its final stage lands the `@routine`, `@service` and output-field
  `@condition` sites together, and it already carries the bare-form rejection this item's second
  question asks about. The overlap is bounded in a way that matters: R668's surface is the
  `argMapping` right-hand side, and the store's pair relations record the mapping *as written*, so
  a `@service` argument with no authored `argMapping` produces no pair row and is reached by
  neither R668's projection nor R668's rejection. That is exactly the reported shape. R668 also
  rules out binding a whole decoded record to a `@service` parameter as a separate capability,
  which is the record-shaped half of this item's first Spec question. Whether this item depends on
  R668, absorbs its unreached cases, or is scoped to exclude them is a Spec decision that should
  be taken with R668's author.
* **R57** (Done, see `roadmap/changelog.md`) shipped the single-hop translated-FK `EXISTS` and
  filed multi-hop translated paths as deferred. Site 4's junction case is that deferral. Its
  reasoning that `EXISTS` is the semantically right shape rather than a convenient one, because a
  non-unique path multiplies no rows and a NULL foreign-key column fails the correlation instead
  of duplicating, is the argument site 4 inherits.
* **R705** (`condition-join-hops-in-reference-filter-paths`, Spec) is R57's sibling deferral for
  the other rejected hop kind, and it is closer than "read together". Its gate and this item's
  `validateLift` are two negations of one predicate, the `DirectFk` precondition, so relaxed
  independently they become two refusals whose agreement nothing binds. Whichever item lands first
  should land the sealed lift result for both; see the gate-collapse note under Design.
* **R676** (`nodeid-filter-per-participant-paths`, Spec) states that its path grammar inherits "the
  identity-carrying lift validation ... the `NodeIdLeafResolver` arms behind `LIFT_FAILURE_MARKER`".
  Stage 2 removes that gate, so R676's author needs to know the constraint moved rather than
  vanished. This is a notification, not a dependency in either direction.
* **R723** (`reference-path-fanout-verdict`, Spec) is the item that warns when a `@reference` path
  fans out. Stage 2 makes junction paths authorable on a `@nodeId` filter, which is a new population
  for R723's rule to have an opinion about. Whether the rule fires there today is a question for
  this Spec's review.
* **R691** (`multi-hop-nodeid-filter-single-fk-claim`, Backlog) is why site 4's reverse case reads
  as unsupported. `docs/manual/how-to/multi-hop-nodeid-filter.adoc` still tells the reader that a
  single direct foreign key never produces a subquery and that the translated emission "is not yet
  shipping", both of which R57 made false. An author checking the manual before filing concludes
  correctly from the page and incorrectly about the generator.
* **R262** (Done) rejects `@nodeId` on a non-`ID` coordinate at validate time: the precedent for
  the rejection half, and the reason its vocabulary is already established. This item extends the
  same judgement from the slot's *type* to the slot's *coordinate*.
