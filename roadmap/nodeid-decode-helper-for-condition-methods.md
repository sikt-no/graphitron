---
id: R874
title: "A @condition that owns a @nodeId predicate must hand-roll the wire format, because NodeIdEncoder is generated downstream of it"
status: In Progress
bucket: dx
priority: 3
theme: nodeid
depends-on: [preserve-enum-extraction-through-condition-rewrap]
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

## Decision: decode in the glue, at every authored coordinate

The glue decodes, as it already does for its own generated predicates, and the authored method receives the decoded key. The runtime-codec alternative is rejected; see "Rejected alternative" below.

The contract, stated once at the slot rather than once per resolver arm: **a `@nodeId` slot's value is decoded before it leaves the generated glue.** An authored `@condition` parameter bound to such a slot receives the decoded key, typed by the node type's key columns: an arity-1 key arrives as the key column's Java type (`Integer` for the example's `LanguageNode`), an arity-N key as `Row<N><T1..TN>`, and a list slot wraps those in `List<>`. These are exactly the shapes `CompositeDecodeHelperRegistry.decodedType` already gives the generated implicit predicates, so the authored call and the implicit predicate read the same kind of local. Decode failures use ThrowOnMismatch semantics: a malformed id, or one whose embedded type id is not the leaf's node type, fails the request before the authored method runs. That is the decode-side error contract `global-id.adoc` already documents for arguments; today the override arm silently exempts itself from it by handing the author undecoded input (the shipped fixture answers a `NumberFormatException` with `falseCondition()`, which is that exemption made visible).

**Uniform scope is deliberate.** The raw-string handoff is not confined to the author-owned arm. On a routed leaf with an authored `@condition`, one wire value is extracted twice inside one emitted glue method under two contradictory extraction facts: `CallSiteExtraction.NodeIdDecodeKeys` on the carrier for the implicit predicate, and `CallSiteExtraction.Direct` (via `ServiceCatalog.legacyArgExtraction`) for the authored call. And on the routed FK-target arm with `override: true` (the manual's `iRegelverksamling(Regelverksamling rs, String id)` example), the author receives a wire string they equally cannot decode, since the same compile-ordering wall stands there. One rule at the slot replaces a per-arm table and collapses the two-consumers-one-question split; it applies at the argument coordinate and the input-field coordinate, routed or author-owned, `override` true or false.

**Why the typed key and not the other two carriers the Backlog body named.** The untyped `String[]` from `decodeValues` exists to serve `peekTypeId`-style dispatch, and that pattern is deliberately not supported in authored code: the type-id prefix is wire vocabulary, "which participant this branch is" is a classified fact the generator already holds, and the generator already dispatches per branch, calling the authored method once per participant with that branch's own table. Reading the wire format past the DataFetcher boundary is the parallel type system the architecture refuses; boundaries decode, the interior is typed. (`@service`'s own `String[]` use is internal to a generated helper that immediately does `rec.fromArray`, not an authored surface.) The node-*table* record commits the author to a table type when what a predicate consumes is key values; the shipped `argMapping` projection path already serves the column-read grain for authors who want one column of the key.

**Compatibility, stated honestly.** On the author-owned arm the documented contract is structurally un-followable, so no consumer can be following it; on the routed override arm the `String` parameter compiles today but is equally undecodable. Both populations are hand-rolling the wire format, and hand-rolled decoders are what this change exists to retire. A parameter's declared type has no dual-source shape, so the additive-then-cutover technique cannot apply; the classify-time rejection under "Implementation" is the cutover instrument in its place, converting what would otherwise be an unexplained javac error inside emitted glue into a named coordinate and a stated remedy.

## The manual documents the un-followable form at two coordinates

Both are in scope whichever resolution ships, and neither carries the compile-ordering caveat that the two fixture comments do:

- `docs/manual/reference/directives/condition.adoc`, the override rung: the method "receives the *resolving* table [...] plus the raw wire id, and decodes it with the generated `NodeIdEncoder` helpers".
- `docs/manual/how-to/global-id.adoc`, the same case spelled out further: the method "receives each branch's table and the raw ID, and the generated `NodeIdEncoder` decodes it", naming `peekTypeId(id)` and a `decode<Type>(id)` call as the pattern to write.

An author following either cannot compile. If the answer is that no mechanism ships soon, both passages should still stop naming a helper the coordinate cannot reach and state what the author actually has to write instead.

Three smaller corrections ride along, all statements about where the encoder is and what it offers, and therefore this item's own subject rather than separate work. `docs/manual/reference/runtime-api.adoc` says the encoder is emitted into `<outputPackage>.schema`; it is `<outputPackage>.util` (`NodeIdEncoderRef.of`, and the emitted artifact in the example's generated sources). The same page says the class is emitted "only when at least one type carries `@node`"; `EmitPlan` adds the unit unconditionally. And the page's API listing omits `decodeValues(String expectedTypeId, String base64Id)` and `requireColumnAgreement(...)`, both deliberately public.

## Implementation

Ordered by seam, not by commit.

**Resolver.** `NodeIdLeafResolver.Resolved.AuthorOwnedPredicate` gains the `HelperRef.Decode decodeMethod` its mint site already holds in scope (the arm is minted a few lines below the successful `resolveDecodeHelperForType` call; no new resolution work). One consequence is the point, not a second rule: `FieldBuilder.decodeTargetOf` becomes total over the seal, so `decodesOf` and `firstNestedDivergence` see the arm, and the currently-silent hole (an all-author-owned nested leaf with bare `@nodeId` whose participants infer different node types passes with nothing catching it) closes under the same divergence rule that already guards routed leaves.

One precision the arm's new vote needs: **an authored parameter rejects on divergence where a routed leaf may dispatch.** Feeding `verdictOf` unchanged would mint `NodeIdArgTarget.PerParticipant` for a divergent author-owned top-level argument and inherit `PruneOnMismatch`, contradicting the THROW contract above. The ground is the binding-shape rule, not a new policy: an authored declaration set cannot carry two decoded parameter types, because non-table positions must be identical in declared type across the set. The same ground applies to a routed leaf whose participants decode different key shapes when an authored `@condition` binds it. The refusal copies `firstMixedContract`'s form: name the leaf, the participants, what each resolved, and the remedy that exists (`@nodeId(typeName:)` pinning). The message names live symbols, never a roadmap id.

**Extraction.** The install sites produce a ThrowOnMismatch `NodeIdDecodeKeys` extraction instead of `Direct` for an authored parameter bound to a `@nodeId` slot, at both coordinates. `legacyArgExtraction` itself stays the declared-type rule for everything else; the decode override is installed where the slot is known, not by widening that shared static. The input-field coordinate rides through `ConditionResolver.rewrapForNested`, which today *replaces* the extraction with a two-arg `NestedInputField` whose leaf defaults to `Direct`; R862 owns that composition defect, hence the `depends-on`. If R862 has not landed at pickup, this item ships the composition (pass the parameter's own extraction as the leaf; the three-arg constructor already refuses the one non-composable shape) and R862 shrinks to its enum coverage.

**Renderer.** No new arm. `ConditionGlueRenderer.decodeCall` / `localType` / `emitsUncheckedLocalCast` already handle a `NodeIdDecodeKeys` behind a `NestedInputField`, and `CompositeDecodeHelperRegistry` dedups on `(encoderClass, methodName, mode, list)`, so on a routed leaf the implicit predicate and the authored call share one decode helper.

**Carrier.** `ArgumentRef.ScalarArg.ConditionOwnedArg` is `@nodeId`-only by construction and takes the decode component flat. `InputField.ConditionOwnedField` also carries plain column-miss overrides that have no node id, so it must not grow an `Optional` component every consumer probes for flavour: split the `@nodeId` flavour out as its own arm, mirroring the asymmetry the argument side already documents as deliberate. (If the store view below turns out to be the better read path for the renderer, the carrier component can be dropped entirely; the implementer decides, the constraint is only that no consumer re-derives "is this the nodeId flavour" from a component's emptiness.)

**Store.** `intent_condition_param_extraction`'s DDL comment states the `@condition` extraction is decided by the parameter's declared type alone, is method-keyed because the rule does not vary by site, and has a closed two-value vocabulary. All three claims become false and the comment is corrected in the same change. The decode override lands as a *use-keyed* relation beside it, in the override shape the fact model uses elsewhere: presence means "this bound parameter receives a decoded key, of this shape", absence means the declared-type rule stands. Its inputs are already captured (`intent_node_id_instruction`, `intent_resolved_node_key_column`), so it is a view. The validator's rejection below and the LSP read it, so the editor can tell an author the required declared type rather than leaving it to javac. Existing pins (`ConditionParamExtractionCaptureTest`, `ConditionParamExtractionTest`) update alongside.

**Rejection.** A `@condition` parameter bound to a `@nodeId` slot whose declared Java type does not match the decoded type is rejected at classify time, naming the coordinate, the declared type, and the required type. Without it the contract's only enforcer is the consumer's javac inside emitted glue, a failure with no line back to the SDL. This is the seam R411 owns for wire-coercion casts: this item threads its check through the same typed-rejection channel and grows no second assignability derivation beside `WireCoercionResolver.checkScalar`; whether the decoded-type check consumes that predicate with the expected side swapped, or stands as a sibling in the same home, is settled at implementation in R411's terms.

**Error-contract alignment, a bounded rider.** The `argMapping` projection path (`ProjectedKeyReads` / `RecordDecodeHelperRegistry`) is the other authored-parameter decode, at the column grain rather than the whole-key grain, and it stays a separate mechanism. But its mismatch failure (a bare `GraphqlErrorException`, "Decoded NodeId did not match the expected type for this argument") diverges from the two-branch malformed-vs-wrong-type `GraphitronClientException` the key helpers throw. One malformed id should fail identically at both grains; align the projection helper's failure onto the same message family. Both helper bodies host on the same conditions class, so the seam is one file.

## Tests

- Pipeline, in the `NodeIdParticipantRoutePipelineTest` neighborhood: the override escape emits a decode helper per participant and the authored call consumes the decoded local; the existing invariant that no `GeneratedConditionFilter` stands beside it is preserved.
- Rejections: divergent inferred node types on an all-author-owned leaf (the closed hole); a `String`-declared parameter on a decoded slot, with the message naming the required type.
- Execution, `MultiTableNodeIdRouteExecutionTest`: `stockByLanguageOverride` round-trips a *real* encoded id end to end. The test supplies `NodeIdEncoder.encode(...)` output (test code is downstream of generated code, so it can), the fixture receives `Integer`, and the old plain-integer string now fails the request with the mismatch error, which is itself asserted. This retires the tree's only escape-hatch fixture that cannot decode.
- Coverage for the routed `override: true` coordinate (the FK-target shape) receiving the decoded key, at whichever tier the testing rubric places it.
- Store: pins for the new view per the existing capture/intent pattern.

Fixture migration: `MultiTableConditionFixtures.stockByRawNodeId` renames (it no longer receives a raw id) and takes `Integer`; its javadoc and the example-schema comments stop documenting the un-followable pattern.

## Documentation

- `docs/manual/reference/directives/condition.adoc`: the override rung in the parameters table and the constraints list, and the FK-target example's signature (`iRegelverksamling(Regelverksamling rs, Integer regelverksamlingKey)` rather than a `String` id), rewrite to the decoded contract.
- `docs/manual/how-to/global-id.adoc`, "Multitable filter inputs": the `@condition(override: true)` paragraph stops naming `peekTypeId` / `decode<Type>` as the author's pattern and states the decoded handoff and its failure contract.
- `docs/manual/reference/runtime-api.adoc`: the three corrections listed above (`.util` not `.schema`, unconditional emission, `decodeValues` and `requireColumnAgreement` in the listing).

### User documentation draft (first-client check)

The override rung in `condition.adoc`, redrafted:

> On a xref:nodeId.adoc[`@nodeId`] leaf whose join route to the target table does not resolve, it also suppresses the route demand itself and hands the method the whole predicate: the method receives the resolving table (each participant's own table on a multi-table consumer) plus the decoded key of the leaf's node type. An arity-1 key arrives as the key column's Java type, a composite key as a jOOQ `Row`. Malformed ids, and ids of another node type, fail the request before your method runs; your method never sees the wire string.

The multitable how-to paragraph, redrafted:

> When no generated route fits, `@condition(override: true)` on the leaf hands the whole predicate to your method: it receives each branch's table and the decoded key of the leaf's node type (`Integer` for `Environment`'s single-column key). Ids that are malformed or of another node type fail the request before your method runs. `override: false` plus an unresolvable route still fails the build, because the implicit column predicate is then required to compose and there is nothing for it to bind.

Both read simpler than the passages they replace, which needed a helper class the reader cannot reference.

## Rejected alternative: a hand-written codec on the consumer classpath

The other half of the Backlog body's design space, rejected on four grounds. Placement: `graphitron-jakarta-rest` is HTTP transport, deliberately jOOQ-free, and the wrong cohesion for a wire codec; a new runtime module would exist for one class. Drift: `NodeIdEncoderClassGenerator`'s javadoc argues the encoder is `final` with a private constructor precisely so no corner of an app speaks a different dialect, and a second implementation is that dialect unless the generated class delegates to it or a round-trip test pins the pair. Principle: the manual sells the flat runtime dependency surface (jOOQ, graphql-java, your generated sources, nothing else), and a codec artifact cuts against it. Purpose: the API such a codec could carry without jOOQ (`peekTypeId`, `String[]` decode) serves exactly the authored-code wire-format read the Decision refuses, and once the glue decodes there is nothing left for it to do.

## Implementation notes

Five forks the plan left to the implementer, settled during the work.

**The carrier component landed on the argument and not on the input field.** The plan allowed dropping it entirely if nothing read it. Nothing on the input-field side does: every consumer of `InputField.ConditionOwnedField` reads `condition().filter()`, and the decode rides that filter's own bound parameter, so a component there would be dead storage and the arm split would make eight exhaustive switches grow a branch that answers nothing. The argument side has a real reader, which appeared once the scope question below was settled: a field-level `@condition` builds its filter from the *classified argument set*, and every other `@nodeId` argument carrier exposes its decoder through `extraction`. `ConditionOwnedArg` has no extraction, having no implicit predicate, so without a flat `decode` component that one reader would have to special-case the one carrier that declines to say what its slot decodes.

**The install is keyed on the slot, so a third directive site rides along.** The plan named two coordinates, the argument and the input field, meaning the two slots a value is bound from. A field-level `@condition` binds its field's own arguments, so a parameter bound to a `@nodeId` argument is at the argument coordinate however the directive was written, and it shares a glue method with that argument's implicit predicate: leaving it on the declared-type rule would have moved the two-contradictory-readings defect rather than closed it. It takes the same install, run in `projectForFilter` where the arguments are already classified.

One shape stays uncovered, and is stated here rather than left to be discovered: a field-level `argMapping` descending to a `@nodeId` **input field** (`"p: filter.languageId"`). The path is dotted, so it is not a whole-slot binding, and its last segment names an input field rather than a key column, so the projection rail does not claim it either. Such a parameter still receives the wire string. Closing it wants a descent-aware install neither rail has today.

**The declared-type refusal is a sibling arm in the wire-coercion family, not a consumer of its predicate.** The plan left that fork to R411's terms. `WireCoercionError.NodeIdDecodedType` joins `Assignability` and `EnumConstantDivergence` because it is that family's own comparison run one transform later: the declared type against what the generator hands the call site, rather than against what graphql-java delivers. Consuming `WireCoercionResolver.checkScalar` with the sides swapped was the other option and does not fit, that predicate taking an SDL leaf type and deriving graphql-java's coercion from it, which is exactly the derivation the decode replaces. No second assignability derivation grows: the check is exact type equality, the same discipline `checkScalar` already applies under a name that suggests otherwise.

**Type-based binding inference reads the slot's wire type, not the decoded key's.** `ServiceCatalog.inferBindingsByType` pairs an unbound parameter against the Java type the SDL slot coerces to, which for `ID` is `String`. A parameter on a `@nodeId` slot is declared as the decoded key, so that pairing no longer fires and the parameter must bind by name (the identity binding, which is the common case) or by `argMapping`. Making the inference node-id-aware would put slot-directive knowledge inside the catalog's declared-type rule, which is the widening the Decision refuses; the manual states the binding requirement instead.

**R862 had not landed at pickup, so this item shipped the composition.** `ConditionResolver.rewrapForNested` now passes the parameter's own extraction as the `NestedInputField` leaf rather than defaulting it to `Direct`. The composition alone would not have delivered the enum case, because `ConditionGlueRenderer.nestedExtraction` had no enum arm: an `EnumValueOf` leaf fell through to the same cast-to-declared-type the `Direct` default produced, so the arm ships here too, as the nested twin of the top-level one. What stays with R862 is the coverage: proving the enum leaf works wants a fixture at the compile or execution tier, the pipeline tier banning code-string body matching, and that fixture is R862's to add.

**The store relation is `intent_condition_param_decode`, keyed on the use site.** It states the shape as an arity and a list flag rather than a composed Java type, the type being the generator's composition of the key columns' own types (already a relation) with the wrapping this one names. Which of the method's parameters receives the decoded key is *not* a column: that is the binding question the fact model defers everywhere, and a reader that has resolved the binding for itself, which the validator and the LSP both have, needs only the shape.

## Retired vocabulary

- "raw wire id" / "the raw id" as the `@condition` handoff contract: `NodeIdLeafResolver.Resolved.AuthorOwnedPredicate` javadoc, the `overrideEscape` remedy string, `FieldBuilder.decodeTargetOf`'s inline comment, `ArgumentRef.ScalarArg.ConditionOwnedArg` and `InputField.ConditionOwnedField` javadoc, the fixture javadoc, the example-schema comments, both manual passages.
- "decodes it with the generated `NodeIdEncoder` helpers" as something an authored method does (same surfaces).
- `stockByRawNodeId` (the fixture renames with its contract).
