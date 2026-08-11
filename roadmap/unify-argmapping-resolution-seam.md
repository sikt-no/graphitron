---
id: R624
title: "Unify argMapping resolution on one seam across every directive"
status: Spec
bucket: architecture
priority: 2
theme: classification-model
depends-on: []
created: 2026-08-11
last-updated: 2026-08-11
---

# Unify argMapping resolution on one seam across every directive

`argMapping` looks like one authoring surface and behaves like four. An author who learned the dot-path form on `@service` and reaches for it on `@routine` is told dot-paths do not exist; an author who writes one on `@record`'s producer-binding probe has the tail silently discarded. The right-hand side of an `argMapping` entry names a GraphQL slot in scope, and that namespace is the same at every directive site, so the divergence is in the plumbing rather than in the meaning. This item makes `ArgBindingMap` the only path from an authored `argMapping` string to a resolved binding, deletes the two bypasses, and lands nested-path support on `@routine` as the first thing that falls out.

## The consumer report that opened this

A payload-carrier mutation whose routine parameters live inside a wrapper input type:

```graphql
extend type Mutation {
  tildelTilgangerTilMaskinbruker(
    input: TildelTilgangerTilMaskinbrukerInput!
  ): TildelTilgangerTilMaskinbrukerPayload
  @routine(
    name: "tildel_tilganger_til_maskinbruker",
    argMapping: "pBrukernavn: brukernavn"
  )
}
```

Both spellings are rejected, and neither rejection points anywhere useful:

* `pBrukernavn: brukernavn` fails the flat-slot membership check in `RoutineDirectiveResolver.bindArgs` (`fieldArgumentNames` does not contain a nested input field), reported as `@routine parameter 'pBrukernavn' binds to GraphQL argument 'brukernavn', which is not an argument of this field`.
* `pBrukernavn: input.brukernavn` fails the single-segment guard immediately above it: `@routine argMapping for parameter 'pBrukernavn' must bind a single GraphQL argument; dot-path bindings are not supported`.

The only workaround today is flattening the mutation's arguments, which forces an SDL shape on the consumer to work around a plumbing gap. Worse, `pBrukernavn: input` *passes* validation and emits `env.<String>getArgument("input")`, a runtime `ClassCastException` on a `LinkedHashMap`. That third spelling is the one this item must also close, and the Implementation section's gate slice is where it happens.

## The four tiers

| Tier | Sites | Behaviour |
|---|---|---|
| Full resolution | `ServiceDirectiveResolver.resolve`, `ConditionResolver.resolveArg`, `ConditionResolver.resolveField`, `BuildContext.resolveConditionRef`, `BuildContext.buildInputFieldCondition` (parsing for the last two happens upstream in `BuildContext.readConditionDirective`, which carries the parse failure as `ConditionDirective.argMappingError` prose) | Parse, then `ArgBindingMap.of` walks each tail segment against the schema and yields a `PathExpr` chain with `liftsList` per step. Dot-paths work, unknown segments get a candidate hint. |
| Parse-only, head-only | `RoutineDirectiveResolver.resolveNode` / `bindArgs` | Calls `ArgBindingMap.parseArgMapping`, so it *holds* the segment chains, then rejects any chain longer than one and membership-checks the head against `FieldBuilder.fieldArgumentNames`. Mints `PathExpr.Head` directly and never calls `ArgBindingMap.of`. |
| Private divergent parser | `RecordBindingResolver.parseArgMapping` (file-private, distinct from the shared one) | Its own `String.split(",")` loop returning `Map<String, String>`. Silently truncates any dot-path at the first `.`, and silently skips an entry missing its colon. No syntax errors ever surface from this site. |
| Structurally inert | `@externalField` (`FieldBuilder.parseExternalRef`), `@enum` (`TypeBuilder`) | Rejects a non-blank `argMapping` at parse time, correctly: neither directive has GraphQL-argument-bound parameters. |

The emit side mirrors the split. `RoutineCallEmitter.argExpression` reads `ParamSource.Arg.graphqlArgName()`, which is defined as `path.headName()`, and emits a flat `env.<T>getArgument("<head>")` (or the `SelectedField` equivalent at correlated child positions). Path descent lives only on the `@service` side, in `ArgCallEmitter`'s `CallSiteExtraction.NestedInputField` arm plus its `buildListAwarePathExtraction` walker.

Two more surfaces carry the same split:

* **SDL.** `argMapping` is declared twice in `directives.graphqls`: once as `ExternalCodeReference.argMapping` (shared by `@service` / `@condition` / `@tableMethod`), whose description documents the dot-path form with examples, and once as a bare `@routine` directive argument, whose description says only "Maps routine IN parameters to GraphQL argument names". Two declarations, two doc blocks, already drifted.
* **LSP.** `LspVocabulary.CanonicalOverlay.overlay()` keys `Behavior.ArgMappingBinding` on the `SchemaCoordinate.InputField("ExternalCodeReference", "argMapping")` coordinate. `@routine`'s is a `SchemaCoordinate.DirectiveArg`, and the overlay names no `@routine` coordinate at all, so `@routine(argMapping:)` gets no completions and no diagnostics. An author gets editor help on `@service` and silence on `@routine`.

## Design

The seam is the **right-hand side**, and only the right-hand side. An `argMapping` entry is `<target>: <path>`, and the two halves have different amounts of shared meaning:

* The **path** names a GraphQL slot in scope, then walks input-object fields. That namespace and those walk rules are identical at every site, and `ArgBindingMap.of` already implements them completely, including the `liftsList` computation and the candidate-hint-on-unknown-segment behaviour. Nothing about it is directive-specific. This is what gets unified.
* The **target** names a reflected Java method parameter for `@service` / `@condition` / `@tableMethod`, and a jOOQ routine IN parameter for `@routine`. These are genuinely different target sets resolved from different places (bytecode reflection versus the catalog's `Routines` call surface), and each site's "you named a target that does not exist" rejection wants its own vocabulary. Abstracting over "named target set" would buy a shared `Set<String>` membership check and cost the specific wording; not worth it. `ArgBindingMap.of` already takes the slot map as a parameter and returns a target-keyed map, so it is already agnostic here and needs no change.

That split is what makes the item tractable: the shared half is already written and already correct, and the work is deleting the code that goes around it.

`columnMapping` stays outside the seam. It shares the *parser* (an entry list is an entry list) but its right-hand side names catalog columns, not GraphQL slots, so it resolves against `JooqCatalog.resolveColumn` and has its own type gate. That is a legitimate split rather than a smell, and it dictates the shape below: parse and resolve stay **two separately callable functions**. Fusing them into a single `resolve(slotTypes, raw)` would leave `columnMapping` calling a function whose signature no longer fits its need and push it back toward hand-rolling, which is how this divergence started. `BuildContext.readConditionDirective` is the second caller that needs the halves apart, since it parses at one point in the flow and resolves at another.

Note in passing that the two "dot-path bindings are not supported" rejections in `RoutineDirectiveResolver` read identically today but are not the same kind of statement: the `columnMapping` one is a **permanent** invariant (a column has no sub-path) while the `argMapping` one is the capability gap this item deletes. After the deletion the survivor should say why it is permanent, or the next reader removes it too.

### The left-hand side does not unify, and both of its checks must exist

The target set is per-directive for a reason worth stating, because it is the answer to "why not abstract this too": `@routine` knows its targets up front from the catalog (`fn.params()`), while `@service` learns them from reflection *after* the binding map already exists. The two discover the same kind of fact in opposite directions and at different times, so a shared "named target set" type would carry a `Set<String>` and a noun for the message, and nothing else.

What that asymmetry hides is that only one of the two directives currently checks its left-hand side at all:

* `@service` has `ServiceCatalog.checkOverrideTargets`, which rejects an entry naming a parameter the method does not have.
* `@routine` has that check for `columnMapping` (the loop over `columnOverrides.keySet()` against `fn.params()` at the top of `bindArgs`) and **nothing** for `argMapping`. An entry whose left side is misspelled is silently dropped: `argMapping: "pBrukernavnn: brukernavn"` leaves `pBrukernavn` to identity-bind, and the author is told something about the *right* side of an entry they did not write.

This matters for sequencing, not just tidiness. Today's misdirected message is the only thing that surfaces a left-side typo at all, and it exists as a side effect of `bindArgs` synthesising `graphqlArg = param.name()`. Routing through the shared resolution removes the side effect, so the checks have to be added in the same slice or the typo gets quieter than it is now. Two rejections are needed, mirroring what `columnMapping` and `@service` already do:

* an entry naming a parameter that is not an IN parameter of the routine, and
* a routine parameter left with no binding at all, listing the available GraphQL arguments.

`ArgBindingMap.Result` has no arm for the second, because on the `@service` side it lives after reflection in `ServiceCatalog`'s per-parameter loop rather than in the binding map. That is correct and should stay correct; the routine resolver owns its own version.

## Implementation

**Lift the repeated site-context wording, keeping parse and resolve as two functions.** Five sites hand-roll the same sequence: `parseArgMapping`, `instanceof ParseError`, cast to `Ok`, `.overrides()`, `ArgBindingMap.of(slots, chains)`, then a three-arm `instanceof` chain, with only their context prefix differing. Collapse the *duplication* without fusing the *functions*: `parseArgMapping(raw)` stays as-is (three consumers, one of which is `columnMapping`), `of` stays as the slot-resolution half, and the shared work is a small helper that takes the site prefix and renders each failure arm uniformly. A fused `resolve(slotTypes, raw)` convenience is worth adding only if every consumer wants it, and two of the four do not.

**Route `@routine` through it.** In `RoutineDirectiveResolver.bindArgs`, delete the single-segment guard and the `fieldArgumentNames` membership check, and resolve the argument-sourced parameters through the shared entry point with `FieldBuilder.argSlotTypes(fieldDef)` as the slot map. Two shape notes:

* The routine resolver currently iterates `fn.params()` and consults the override map per parameter, because a routine parameter with no override identity-binds. `ArgBindingMap.of` produces identity entries for unclaimed *slots*, which is the opposite direction: it cannot know the routine's parameter list. So the call is `of(slotTypes, overrides)` for the explicitly-mapped parameters, with the identity case still resolved per parameter against the slot map. Worth stating in the plan because the naive "just call `of` and read the result map" shape does not fit and an implementer will discover it three edits in.
* `columnMapping`-bound parameters must be excluded from the argument-side resolution, which the existing loop already does by `continue`-ing on a column override. The both-sources rejection above the loop stays as is.
* The two left-hand-side rejections from the Design section land here, in the same slice, for the sequencing reason given there.

**Delete `ParamSource.Arg.graphqlArgName()` in the same slice that lets `@routine` mint `Step` paths.** This is the enforcer, and without it the emitter work is an unaided hand-audit. The accessor returns `path.headName()`, which is the entire binding for a `Head` and merely the *first segment* for a `Step`. It is safe today only because the sole producer of `Step` paths feeds consumers that route through `extractionForArg`. The moment `@routine` can mint a `Step`, every remaining call site becomes a place where a nested binding silently degrades to "read the outer input map and cast it", which is precisely the `ClassCastException` in the consumer report. There are eleven main-source call sites (`RoutineCallEmitter`, `MethodRef.callParamName`, `ArgCallEmitter`, `InputBeanResolver` in six places, `ServiceMethodCallWalker` in two) plus ten test assertions. Deleting the accessor turns the audit into a compile-error-driven migration: the emitters switch on `PathExpr` or call the shared descent, while the sites that genuinely want *the slot* (`InputBeanResolver`, `MethodRef.callParamName`) say `path.headName()` at the call site with a one-line note saying why the head is the right read there.

**Gate the leaf type by calling the gate that already exists.** This is the correction to the original scoping instinct, which was to defer the whole type check to a sibling item on the grounds that a naive type-equality check would trip over the enum and ID-as-String coercion residue. That reasoning was sound but the premise was wrong: the coercion-aware gate is already written and `@routine` simply never calls it. `ServiceCatalog.argExtraction(typeName, sdlLeafType, site)` checks enum-constant parity via `EnumMappingResolver.checkEnumConstants` and routes scalars through `WireCoercionResolver.checkScalar`, which compares against graphql-java's *coercion output* type rather than Java identity, and `ServiceCatalog.resolvePathLeafType(path, slotTypes)` resolves the leaf type of a `PathExpr`. Slice 2 already has both inputs in hand.

One thing that call does **not** cover, and the plan must not claim it does: `WireCoercionResolver.checkScalar` returns `PASS_THROUGH` when the peeled SDL leaf is not a `GraphQLScalarType`. So an input-object leaf bound to a `String` parameter, the exact `pBrukernavn: input` case, passes the existing gate untouched. On the `@service` side that case is not a hole because `InputBeanResolver` intercepts input-object arguments and either instantiates a consumer bean or rejects loudly; a routine IN parameter has no bean concept, so for `@routine` a non-scalar, non-enum leaf is *always* an authoring error. That makes the routine-side gate two things:

* the shared `argExtraction` call, with a non-`Direct` result rejected as a deferral naming the unimplemented emitter arm, and
* a routine-specific rejection for a leaf that is neither scalar nor enum, which is the one that actually closes the reported footgun.

**Teach the routine emitter to descend.** `RoutineCallEmitter.argExpression` reads `arg.graphqlArgName()` (that is, `path.headName()`) and emits a flat argument read. The descent it needs already exists in `ArgCallEmitter`: `nestedMapValueExpr(String mapLocal, List<String> path)` is public and already shared by the SET-value, WHERE-value, INSERT-cell and NodeId-decode reads, and the private `buildMapChain` behind it takes a leaf-cast `TypeName`. What the routine emitter needs beyond today's public surface is a caller-supplied *root* expression plus a leaf cast, because `buildNestedInputFieldExtraction` hardcodes its root as `env.getArgument(outer)` and the routine emitter has two roots: `env.getArgument("input")` for `ArgumentValueSource.Env`, and `<sfLocal>.getArguments().get("input")` for `ArgumentValueSource.FromSelectedField`. So the descent needs its root parameterised whatever else happens, and no third walker is needed.

R84's recorded decision still holds and does not need reopening: its stated reason for the parallel walker was avoiding an always-false `liftsList` flag threaded through every `NestedInputField` site, and a third consumer does not change that. What the third consumer *does* expose is a generated-output problem. Both existing walkers build a chain of `instanceof Map<?, ?>` ternaries, and the routine call site would nest that chain inside `DSL.val(...)` inside a `Routines.<method>(...)` argument list. `docs/architecture/explanation/development-principles.adoc` bans exactly this ("no deeply-nested ternaries ... a developer cannot breakpoint a ternary arm"), and prescribes the fix: lift the body into a named private helper so the call site stays an expression and the body is readable statements.

So the descent emits a helper on the enclosing generated class rather than an inline chain:

```java
private static String argPBrukernavn(java.util.Map<String, Object> input) { ... }
```

and the emitted call stays `Routines.tildelTilgangerTilMaskinbruker(DSL.val(argPBrukernavn(input)))`, breakpointable and legible. Taking the root map as a *parameter* rather than hardcoding `env.getArgument(...)` inside the helper is the same reasoning as `docs/architecture/reference/emitter-conventions.adoc#helper-locality` gives for passing the aliased `Table` in: the two `ArgumentValueSource` forks are two call paths that must pass their own root, and a locally-declared root forces the wrong one on whichever fork the helper was not written for. That parameterisation is what reuse needed anyway, so this is a better shape than either R84 alternative rather than a detour around them.

**Delete `RecordBindingResolver.parseArgMapping`, and say who owns the rejection.** Route its producer-binding probe through the shared parse and call `headName()` explicitly, since the walker genuinely only wants the head slot. The thing to decide, because it is the only way this slice goes wrong: the shared parse returns a `ParseError` that the probe has no channel for. The probe runs before any classified verdict exists, and the *same* authored string is also parsed by the `ExternalCodeReference` consumer, which does reject properly.

The plan's answer is that the probe keeps swallowing: it treats a parse failure as "no overrides observed" and carries a one-line note that the ECR consumer owns the diagnostic for this string. The alternative gives the author two diagnostics for one typo, which is worse than the status quo. What changes is the silent *truncation*, not the silent failure: a well-formed dot-path entry now yields its real head instead of a string cut at the first `.`, which is the actual bug in the private parser.

**Extend the LSP overlay to `@routine`.** Add the `SchemaCoordinate.DirectiveArg("routine", "argMapping")` coordinate bound to `Behavior.ArgMappingBinding`. Three observations from reading the current code:

* `ArgMappingCompletions.leftCandidates` goes through `ArgMappingSupport.resolveMethod` and returns an empty list when no Java method resolves, so at a `@routine` coordinate it degrades to silence rather than to wrong answers. It needs the routine's IN parameter names from the catalog instead. **Do not do this by branching on the coordinate.** `Behavior.ArgMappingBinding` is a data-free marker, so a coordinate branch would re-derive, at two sites that must agree and nothing binds (`leftCandidates` and `Diagnostics.resolveParameterNames`), a fact the overlay already knows when it registers the entry. Instead give the behaviour the fact: `ArgMappingBinding(TargetSource source)` with a sealed `TargetSource.{ReflectedMethod(SchemaCoordinate classNameCoord), RoutineParams(SchemaCoordinate nameCoord)}`. Both consumers switch on the carried variant and a third target set becomes a compile error at both. The one-line-above exemplar is `Behavior.MethodNameBinding(ecrClassName)`, which already carries the coordinate its resolution depends on.

  Worth noticing that this is where the "named target set" abstraction rejected under Design *does* pay. In the LSP both sides genuinely are "a name set resolved from a sibling directive argument", one axis with two arms. In the classifier they are not, because the two sides are discovered at different times by different mechanisms. Same words, different fact, so the same abstraction is right in one module and wrong in the other.
* `ArgMappingCompletions.rightCandidates` is already generic over `TypeContext.fieldArgumentNames`, so it needs nothing. Note though that it deliberately returns *nothing* once the token contains a `.`, with the comment "Dot-path expansion into nested input fields is not modelled; offer nothing rather than a misleading flat list". That limitation is uniform across directives today and stays uniform after this item; improving it needs a nested-input-field projection in the LSP snapshot, which is its own item, not this one.
* `Diagnostics.validateArgMappingGraphqlArg` already validates the head segment only and already flags just the head span. It becomes reachable at `@routine` for free once the overlay coordinate exists.

**Narrow `RoutineRef.ArgBinding.source` while the switch is open.** The record declares the sealed root `ParamSource`, and its javadoc states the narrowing in prose ("the other `ParamSource` arms are never minted for routine bindings"). The emitter pays for that prose with five unreachable `throw` arms plus a bespoke `nonRoutineParamSource` factory. Since the emitter slice is already editing this exact switch, carry the contract structurally instead: `sealed interface RoutineParamSource extends ParamSource permits Arg, SourceColumn`, with `ParamSource permits RoutineParamSource, Context, Sources, DslContext, Table, SourceTable`, and `ArgBinding.source` declared as `RoutineParamSource`. Exhaustive switches elsewhere still enumerate the leaves and keep compiling; the five arms and the factory delete. Fix the same javadoc while there: it currently names only `ParamSource.Arg` as mintable and omits `SourceColumn`, which `columnMapping` has minted since the correlated-call surface landed, so the prose contract it states is already stale in a second way.

This is separable if it would bloat the slice, but it is cheapest while the switch is already being rewritten.

## Tests

The flat form is already pinned everywhere by every existing `argMapping` fixture; those pins are the regression floor and none of them may change. New coverage:

* **Unit tier.** `ArgBindingMapTest` (22 cases today) covers the schema walk already. It gains cases for whatever fused entry point slice 1 introduces, in particular that a parse failure and a resolution failure stay distinguishable arms rather than collapsing into one string.
* **Pipeline tier.** `RoutineMutationWritePipelineTest` and the routine block in `GraphitronSchemaBuilderTest` gain: a routine field whose `argMapping` is a dot-path classifies and lands the expected `PathExpr.Step` chain on its `RoutineRef.ArgBinding`; an unknown head segment and an unknown tail segment each reject with the shared wording plus routine site context, with the candidate hint present (this is the validator-mirrors-classifier check, since the old rejections had no hint at all). Then one case per newly-added rejection, because each is a diagnostic that did not exist before and an untested rejection is a guess: a left-side entry naming a non-parameter, a routine parameter left unbound, and an input-object leaf bound to a scalar parameter (the reported footgun, which must now name the leaf type). A pin that `RecordBindingResolver`'s producer-binding probe reads the head of a dot-path entry rather than a string truncated at the first `.`, and still tolerates a malformed entry silently per the ownership decision above.
* **Compilation tier.** The sakila example schema gains the nested-input routine mutation below, so `graphitron-sakila-example` compiles the emitted fetcher at `release 17`. This is the tier that catches an emitter that walks the path wrongly: a bad descent is usually a javac error on the generated source, not a runtime surprise.
* **Execution tier.** `RoutineFieldExecutionTest` gains the round trip. `rentFilmPayload(inventoryId: Int!, customerId: Int!)` already exists as the payload-carrier routine fixture and is structurally identical to the consumer's report, so the new fixture is that field with its arguments moved inside a wrapper input:

  ```graphql
  rentFilmPayloadNested(input: RentFilmInput!): RentFilmPayload
      @routine(
          name:       "rent_film"
          argMapping: "pInventoryId: input.inventoryId, pCustomerId: input.customerId"
      )
  ```

  The assertion is the committed rental read back through the payload's data field, which proves the descent end to end: parse, schema walk, emit, and the post-commit re-read all agree. A correlated child-position variant covers the `FromSelectedField` fork, which is the one the `Env` fork's coverage would otherwise miss entirely.
* **LSP tier.** `ArgMappingCompletionsTest` and `ArgMappingDiagnosticsTest` gain `@routine` cases: left-hand completions offer the routine's IN parameter names, and a typo'd head segment underlines the head span only, matching the `@service` behaviour those tests already pin.

## User documentation (first-client check)

The surface is author-facing, so the docs are the design's first client.

* `docs/manual/reference/directives/routine.adoc`: the `argMapping` row in the parameter table currently reads "Maps routine IN parameters to GraphQL argument names". It becomes the dot-path-aware wording, and the page gains the wrapper-input example above, which is the shape a Relay-style mutation actually has. The Constraints section's "Binding a parameter to an argument the field does not declare ... is a build error" sentence needs the nested case folded in.
* `directives.graphqls`: the two `argMapping` descriptions converge. They cannot share a declaration (one is an input field on `ExternalCodeReference`, the other a directive argument on `@routine`), so the fix is to make `@routine`'s description carry the same dot-path sentence and examples rather than a truncated paraphrase. If the descriptions are worth keeping identical, a test that asserts they agree is cheaper than trusting future editors; that call belongs to the reviewer.
* `docs/manual/reference/diagnostics-glossary.adoc` mentions `argMapping` only as a remedy in the external-code entry, and neither retired `@routine` rejection string appears there or in any test assertion (checked: the two strings occur nowhere outside `RoutineDirectiveResolver`). So retiring them costs nothing, and the glossary gets an entry for the shared unknown-segment diagnostic only if the reviewer judges the surface worth naming there.

The check the workflow asks for: if the routine page still needs its own separate explanation of what a dot-path is after this lands, the unification did not happen and the design is wrong.

## Out of scope

* **The emitter half of the leaf-type gate.** The classify-time half is *in* scope (see Implementation): the coercion-aware gate already exists as `ServiceCatalog.argExtraction` and the work is calling it. What stays out is honouring a non-`Direct` extraction on a routine binding at emit time, meaning the `CallSiteExtraction.EnumValueOf` and `JooqConvert` arms, plus the parameter-`DataType` lift onto `RoutineRef.ArgBinding` that `roadmap/routine-chain-residue.md` describes and that a principled coercion would want. This item rejects those combinations as deferrals naming the follow-up; `roadmap/routine-coercing-arg-extractions.md` (R625) implements them. Splitting the emitter half is right; splitting the *gate* would not have been, because this item widens the surface where the mismatch is easy to author while leaving an existing check uncalled.
* **The nested-object `argMapping` form** (`request: { customerId: input.customer.id, ... }`) is `roadmap/nested-argmapping-syntax.md`, which explicitly keeps the flat dot-path form as the common case. This item is about making the flat form uniform; that item adds a second form on top. They touch `parseArgMapping` from opposite directions, so whichever lands second rebases onto the other's seam.
* Removing or deprecating any currently-accepted spelling. Every schema that builds today must still build.

## Acceptance

* The consumer's original SDL builds: `@routine(argMapping: "pBrukernavn: input.brukernavn")` on a payload-carrier mutation whose only field argument is the wrapper input, with no SDL restructuring.
* Exactly one code path leads from an authored `argMapping` string to a resolved binding. Grepping for a second parser or a second slot-membership check finds nothing: `RecordBindingResolver.parseArgMapping` is gone, and `RoutineDirectiveResolver` no longer calls `fieldArgumentNames` for argument binding.
* A given malformed `argMapping` produces the same diagnostic wording at every directive that accepts one, modulo the site-context prefix.
* `ParamSource.Arg.graphqlArgName()` no longer exists, so no consumer can read a nested path's head as if it were the whole binding.
* A left-hand-side typo on `@routine(argMapping:)` names the routine parameter it failed to find, rather than reporting something about the right-hand side.
* `pBrukernavn: input` is a build error naming the input-object leaf, not a runtime `ClassCastException`.
* No currently-building schema stops building, and no existing `argMapping` fixture assertion changes except the two retired `@routine` rejection strings and the `graphqlArgName()` call sites in `GraphitronSchemaBuilderTest` / `ServiceCatalogTest`, which become `path.headName()` reads.

## Open questions for the reviewer

1. **Does the item carry too much?** After the architect pass it holds seven moves: the wording lift, the routine routing, the two left-side rejections, the `graphqlArgName()` deletion, the classify-time gate, the emitter helper, the `RecordBindingResolver` cleanup, the LSP `TargetSource`, and the `RoutineParamSource` narrowing. Several are load-bearing for the others (the accessor deletion is the enforcer for the emitter work; the left-side rejections must land with the routing or a diagnostic regresses), but two are genuinely liftable: the LSP slice is independently observable and lives in another module, and the `RoutineParamSource` narrowing is pure type-shaping. If the reviewer wants a smaller item, those two are the seams.
2. **Where does the canonical `argMapping` statement live?** The two SDL declarations should *not* be unified: `directives.graphqls` documents `ExternalCodeReference` as the case new directives should not lean on, and `@routine` has no `className`/`method` to justify a wrapper. So the duplication to fix is the hand-maintained prose, which exists in four places (the two SDL description blocks, plus `routine.adoc`, `service.adoc` and `condition.adoc`). If the semantics become identical, one page should state the dot-path contract and the others `xref` it. Which page is canonical is the reviewer's call; my inclination is a section on the `@service` page, since that is where authors meet the form first.
3. **Is deferring the emitter arms the right shape for the gate?** Rejecting a non-`Direct` extraction as a deferral is honest but means an enum-typed routine parameter that works today via the identity path could start reporting as deferred. Worth a check during implementation that no existing fixture binds an enum argument to a routine parameter; if one does, the deferral has to become an emitter arm in this item after all.

## Cross-references

* `roadmap/routine-coercing-arg-extractions.md` (R625): the argument-side type gate this item deliberately leaves open, filed alongside it.
* `roadmap/nested-argmapping-syntax.md` (R249): the nested-object form, sibling surface on the same parser.
* `roadmap/routine-chain-residue.md` (R448): the enum / ID-as-String coercion residue that blocks the argument-side type gate, and the parameter-`DataType`-on-`ArgBinding` lift that a type gate would want.
* `roadmap/changelog.md` entry for `argmapping-path-expressions` (R84): landed `PathExpr`, `ArgBindingMap.of`'s schema walk, and the `buildListAwarePathExtraction` walker. Records the deliberate choice of a parallel walker over augmenting `NestedInputField`, which this item revisits with a third consumer in hand.
