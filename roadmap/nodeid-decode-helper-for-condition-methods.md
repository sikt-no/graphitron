---
id: R874
title: "A @condition that owns a @nodeId predicate must hand-roll the wire format, because NodeIdEncoder is generated downstream of it"
status: Backlog
bucket: dx
priority: 3
theme: nodeid
depends-on: []
created: 2026-08-28
last-updated: 2026-08-28
---

# A @condition that owns a @nodeId predicate must hand-roll the wire format, because NodeIdEncoder is generated downstream of it

Where a `@nodeId` leaf's route to the target table does not resolve, `@condition(override: true)` hands the author the whole `WHERE` contribution: the method receives the resolving table plus the **raw wire id**, and is expected to decode that id itself. It has nothing to decode it with. The generated `NodeIdEncoder` is emitted into `<outputPackage>.util` by the generation run, while a `@condition` class must already be compiled when that run starts, so no arrangement of modules lets the method reference the helper two manual pages tell it to use.

Reported as the second half of https://github.com/sikt-no/graphitron/issues/525, whose two headline limitations shipped as R675 and R676. The reporter's workaround was to re-implement the wire format by hand in the condition method, and the ask attached to it ("a runtime-accessible decode helper, or letting `@condition` methods receive the decoded record, as `@service` inputs do") is what nothing owns.

## The circularity is structural, not a layout accident

`ServiceCatalog.reflectTableMethod` resolves a `@condition` through `Class.forName(className, false, ctx.codegenLoader())`, and the plugin builds that loader from the consumer's compile classpath (`AbstractRewriteMojo.buildCodegenLoader`), so the author's class has to be compiled before the generator runs. The `generate` goal binds to `generate-sources`, strictly before `compile`, and `NodeIdEncoderClassGenerator` emits `NodeIdEncoder` as part of that run. Put the two in one module and the class the generator must load does not exist yet at the phase that must load it; split them and the `@condition` module is upstream of the generated one, so a dependency on it is a cycle. There is no third arrangement.

The reactor shows the split form: `graphitron-sakila-example` runs the generator and compile-depends on `graphitron-sakila-service`, where the hand-written `@condition` fixtures live. The consequence is already recorded in the tree, in two fixture comments rather than in the manual: `MultiTableConditionFixtures.stockByRawNodeId`'s javadoc states that a `@condition` class "compiles upstream of the code the generator emits", and the `stockByLanguageOverride` field in the example schema repeats it. The fixture's own escape is to treat the id as a plain integer, so no test in the tree decodes a real node id from a condition method.

## The gap is narrower than "condition methods cannot see decoded ids"

Most of the reporter's contrast with `@service` is already closed, which is worth stating so the item is not scoped to work that is done. Where the route *does* resolve, `ConditionGlueRenderer` emits the decode into the generated glue ahead of the authored call (`CallSiteExtraction.NodeIdDecodeKeys`, drained through `CompositeDecodeHelperRegistry`), so the method receives typed key values and never sees a wire string. The gap is precisely the arm where the generator deliberately owes no route: `NodeIdLeafResolver.Resolved.AuthorOwnedPredicate`, whose whole contract is that the author has taken the predicate. That arm is what a multitable filter input reaches when no per-participant route resolves, which is why the reporter hit it and a single-table author generally does not.

The two arms sit next to each other in one emitted file, which is the clearest statement of the gap. In the example's generated `QueryConditions`, the routed participant condition binds `Integer languageId = decodeLanguageNodeKeyOrThrow(...)` and compares a typed column, while the override participant condition one method below binds `String languageId` straight off the args map and hands it to the author. Same file, same import of `NodeIdEncoder`, one decode performed and one declined.

So the question this item owns is not "can the glue decode" but "what does an author decode *with* on the one arm where the glue deliberately does not", and whether that arm should decline at all.

## What has to be decided

Two shapes, and the choice between them is the item's real content.

**Decode in the glue, as `@service` already does.** The symmetric fix, and the one the reporter actually asked for. A `@service` input never names `NodeIdEncoder` either: `JooqRecordInstantiationEmitter` emits a `create<Record>` helper onto the *generated* fetcher, which decodes there and passes the constructed record as an ordinary argument, so the upstream hand-written service compiles against nothing generated. The override arm could pass a decoded value the same way and the compile-ordering problem would simply not arise. What it has to answer: the arm exists *because* no route resolved, so on a multitable leaf the branch may not know which node type applies, and a typed `RecordN` parameter commits to one node type, which is the commitment `peekTypeId` exists to let an author avoid. So the contract has to pick between a typed record (and say what happens on a branch whose type differs), the untyped `String[]` that `decodeValues` already returns, or a per-participant decode. It also revisits what `override: true` means, since today "the author has taken the predicate" is exactly why the generator hands over a raw string.

**Ship a hand-written codec on the consumer classpath.** The other half of the design space, and the one that also serves an author who wants `peekTypeId` for dispatch rather than a decoded key. Its problems are placement and drift. There is no rewrite-side home for the wire format outside generated output today: `graphitron-jakarta-rest` is the only hand-written artifact consumers put on their runtime classpath, it is HTTP transport only with nothing node-id related in it, and it carries no jOOQ dependency, so a helper living there could offer the `String[]` and `peekTypeId` half of the API but not a typed `RecordN` without dragging jOOQ into a module that has deliberately stayed clear of it. The legacy `no.sikt.graphql.NodeIdStrategy` the wire format is documented as matching is the *other* generator's artifact and is not in this repo, so it is a compatibility target rather than a home. Drift is the sharper objection: `NodeIdEncoderClassGenerator`'s javadoc argues the encoder is `final` with a private constructor precisely so no corner of an app can speak a different dialect, and a second implementation of the format is that dialect unless the generated class delegates to the runtime one or a round-trip test pins the two together.

## The manual documents the un-followable form at two coordinates

Both are in scope whichever resolution ships, and neither carries the compile-ordering caveat that the two fixture comments do:

- `docs/manual/reference/directives/condition.adoc`, the override rung: the method "receives the *resolving* table [...] plus the raw wire id, and decodes it with the generated `NodeIdEncoder` helpers".
- `docs/manual/how-to/global-id.adoc`, the same case spelled out further: the method "receives each branch's table and the raw ID, and the generated `NodeIdEncoder` decodes it", naming `peekTypeId(id)` and a `decode<Type>(id)` call as the pattern to write.

An author following either cannot compile. If the answer is that no mechanism ships soon, both passages should still stop naming a helper the coordinate cannot reach and state what the author actually has to write instead.

Two smaller corrections ride along, both statements about where the encoder is and therefore this item's own subject rather than separate work. `docs/manual/reference/runtime-api.adoc` says the encoder is emitted into `<outputPackage>.schema`; it is `<outputPackage>.util` (`NodeIdEncoderRef.of`, and the emitted artifact in the example's generated sources). The same page's API listing omits `decodeValues(String expectedTypeId, String base64Id)`, which is the untyped decode a hand-written codec would most likely mirror and the one a `@service` record helper actually calls.
