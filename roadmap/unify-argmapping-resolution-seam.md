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

The only workaround today is flattening the mutation's arguments, which forces an SDL shape on the consumer to work around a plumbing gap. Worse, `pBrukernavn: input` *passes* validation (see the type-gate note under Out of scope) and emits `env.<String>getArgument("input")`, a runtime `ClassCastException` on a `LinkedHashMap`.

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

`columnMapping` stays outside the seam. It shares the *parser* (an entry list is an entry list) but its right-hand side names catalog columns, not GraphQL slots, so it resolves against `JooqCatalog.resolveColumn` and has its own type gate. That is a legitimate split rather than a smell, and it is the reason slice 1 must keep parse and resolve **separately callable** rather than fusing them irreversibly: `columnMapping` needs parse alone, and `BuildContext.readConditionDirective` parses at a different point in the flow than it resolves.

## Implementation

**Fuse the parse-then-resolve dance, without losing the parse-only entry point.** Five sites hand-roll the same sequence: `parseArgMapping`, `instanceof ParseError`, cast to `Ok`, `.overrides()`, `ArgBindingMap.of(slots, chains)`, then a three-arm `instanceof` chain. Only the site-context wording differs. Add `ArgBindingMap.resolve(Map<String, GraphQLInputType> slotTypes, String raw)` returning a sealed result whose arms are the union of both existing result types (`ParseError`, `UnknownArgRef`, `PathRejected`, `Ok`), so callers switch once and wrap their own context per arm. Keep `parseArgMapping` and `of` public for the two callers that genuinely need the halves separately (`columnMapping`, and the condition flow that parses in `readConditionDirective` and resolves later).

**Route `@routine` through it.** In `RoutineDirectiveResolver.bindArgs`, delete the single-segment guard and the `fieldArgumentNames` membership check, and resolve the argument-sourced parameters through the shared entry point with `FieldBuilder.argSlotTypes(fieldDef)` as the slot map. Two shape notes:

* The routine resolver currently iterates `fn.params()` and consults the override map per parameter, because a routine parameter with no override identity-binds. `ArgBindingMap.of` produces identity entries for unclaimed *slots*, which is the opposite direction: it cannot know the routine's parameter list. So the call is `of(slotTypes, overrides)` for the explicitly-mapped parameters, with the identity case still resolved per parameter against the slot map. Worth stating in the plan because the naive "just call `of` and read the result map" shape does not fit and an implementer will discover it three edits in.
* `columnMapping`-bound parameters must be excluded from the argument-side resolution, which the existing loop already does by `continue`-ing on a column override. The both-sources rejection above the loop stays as is.

**Teach the routine emitter to descend.** `RoutineCallEmitter.argExpression` reads `arg.graphqlArgName()` (that is, `path.headName()`) and emits a flat argument read. The descent it needs already exists in `ArgCallEmitter`: `nestedMapValueExpr(String mapLocal, List<String> path)` is public and already shared by the SET-value, WHERE-value, INSERT-cell and NodeId-decode reads, and the private `buildMapChain` behind it takes a leaf-cast `TypeName`. What the routine emitter needs beyond today's public surface is a caller-supplied *root* expression plus a leaf cast, because `buildNestedInputFieldExtraction` hardcodes its root as `env.getArgument(outer)` and the routine emitter has two roots: `env.getArgument("input")` for `ArgumentValueSource.Env`, and `<sfLocal>.getArguments().get("input")` for `ArgumentValueSource.FromSelectedField`. Generalising that root parameter is the whole change, and it keeps one walker rather than adding a third.

This is the point to revisit R84's recorded decision. Its changelog entry says a parallel walker was preferred over augmenting `CallSiteExtraction.NestedInputField` with per-segment `liftsList`, "to avoid threading an always-false flag through every R63 site". With a third consumer arriving, the reviewer should decide whether that still holds or whether the unified carrier is now cheaper than a third caller of the parallel walker. The plan's default is the cheap version (generalise the root, do not restructure the carrier), because it is reversible and the carrier question is separable.

**Delete `RecordBindingResolver.parseArgMapping`.** Route its producer-binding probe through the shared parse and call `headName()` explicitly where the walker genuinely only wants the head. The behaviour change is that a malformed entry now surfaces as a parse error instead of being skipped, which is the point: the private parser's silence is how a typo in an `argMapping` on a `@record`-producing `@service` currently becomes a missing input observation rather than a diagnostic. Check whether any existing fixture depends on the silent skip before deleting; if one does, that fixture is the bug.

**Extend the LSP overlay to `@routine`.** Add the `SchemaCoordinate.DirectiveArg("routine", "argMapping")` coordinate bound to `Behavior.ArgMappingBinding`. Three observations from reading the current code:

* `ArgMappingCompletions.leftCandidates` goes through `ArgMappingSupport.resolveMethod` and returns an empty list when no Java method resolves, so at a `@routine` coordinate it degrades to silence rather than to wrong answers. The slice forks it on coordinate: routine IN parameter names from the catalog instead of reflected method parameter names. This is the one place the left-hand-side asymmetry from Design becomes real work, and it is the concrete argument for keeping that half per-directive.
* `ArgMappingCompletions.rightCandidates` is already generic over `TypeContext.fieldArgumentNames`, so it needs nothing. Note though that it deliberately returns *nothing* once the token contains a `.`, with the comment "Dot-path expansion into nested input fields is not modelled; offer nothing rather than a misleading flat list". That limitation is uniform across directives today and stays uniform after this item; improving it needs a nested-input-field projection in the LSP snapshot, which is its own item, not this one.
* `Diagnostics.validateArgMappingGraphqlArg` already validates the head segment only and already flags just the head span. It becomes reachable at `@routine` for free once the overlay coordinate exists.

## Tests

The flat form is already pinned everywhere by every existing `argMapping` fixture; those pins are the regression floor and none of them may change. New coverage:

* **Unit tier.** `ArgBindingMapTest` (22 cases today) covers the schema walk already. It gains cases for whatever fused entry point slice 1 introduces, in particular that a parse failure and a resolution failure stay distinguishable arms rather than collapsing into one string.
* **Pipeline tier.** `RoutineMutationWritePipelineTest` and the routine block in `GraphitronSchemaBuilderTest` gain: a routine field whose `argMapping` is a dot-path classifies and lands the expected `PathExpr.Step` chain on its `RoutineRef.ArgBinding`; an unknown head segment and an unknown tail segment each reject with the shared wording plus routine site context, with the candidate hint present (this is the validator-mirrors-classifier check, since the old rejections had no hint at all). A pin that `RecordBindingResolver`'s producer-binding probe sees the head of a dot-path entry and no longer silently truncates a malformed one.
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

* **A type gate on the argument-sourced side.** There is none today at any directive: nothing checks the resolved leaf's GraphQL type against the Java or routine parameter type, which is why `pBrukernavn: input` reaches emit. `columnMapping` *does* gate (`RoutineDirectiveResolver.bindArgs` compares `columnClass()` against `param.type()`). Adding the argument-side counterpart is genuinely wanted, but a naive Java-type-equality check is wrong here: `roadmap/routine-chain-residue.md` records live enum and ID-as-String coercion residue where the GraphQL leaf type and the routine parameter type legitimately differ. Sizing that gate needs the coercion rules settled first, so it is filed as `roadmap/argmapping-leaf-type-gate.md` (R625) and this item leaves the hole exactly as wide as it already is. Nested paths do widen the surface where the mismatch can be *written*, which is the argument for prioritising R625, not for blocking this one. Landing this item first also gives R625 a single place to add the check instead of four.
* **The nested-object `argMapping` form** (`request: { customerId: input.customer.id, ... }`) is `roadmap/nested-argmapping-syntax.md`, which explicitly keeps the flat dot-path form as the common case. This item is about making the flat form uniform; that item adds a second form on top. They touch `parseArgMapping` from opposite directions, so whichever lands second rebases onto the other's seam.
* Removing or deprecating any currently-accepted spelling. Every schema that builds today must still build.

## Acceptance

* The consumer's original SDL builds: `@routine(argMapping: "pBrukernavn: input.brukernavn")` on a payload-carrier mutation whose only field argument is the wrapper input, with no SDL restructuring.
* Exactly one code path leads from an authored `argMapping` string to a resolved binding. Grepping for a second parser or a second slot-membership check finds nothing: `RecordBindingResolver.parseArgMapping` is gone, and `RoutineDirectiveResolver` no longer calls `fieldArgumentNames` for argument binding.
* A given malformed `argMapping` produces the same diagnostic wording at every directive that accepts one, modulo the site-context prefix.
* No currently-building schema stops building, and no existing `argMapping` fixture assertion changes except the two retired `@routine` rejection strings.

## Open questions for the reviewer

1. **The R84 carrier decision, with a third consumer.** Generalise `buildNestedInputFieldExtraction`'s root and keep the parallel walker (the plan's default, reversible), or take R84's originally-specced unified carrier now that `NestedInputField` has a third caller? The plan deliberately picks the cheap option; if the reviewer wants the carrier, that is a materially larger diff and should probably be its own item rather than a slice here.
2. **Should the two SDL `argMapping` descriptions be test-pinned to agree?** They cannot share a declaration. A test asserting equality of the two description strings prevents the drift that already happened, at the cost of a slightly odd-looking meta-test. Reviewer's call.
3. **Slice granularity.** The five implementation moves are written flat because each one has to land before the next compiles, per the workflow's guidance against decorative numbering. If the reviewer sees a genuine seam worth stopping at (most plausibly: the LSP slice, which is independently observable and touches a different module), say so and it splits.

## Cross-references

* `roadmap/argmapping-leaf-type-gate.md` (R625): the argument-side type gate this item deliberately leaves open, filed alongside it.
* `roadmap/nested-argmapping-syntax.md` (R249): the nested-object form, sibling surface on the same parser.
* `roadmap/routine-chain-residue.md` (R448): the enum / ID-as-String coercion residue that blocks the argument-side type gate, and the parameter-`DataType`-on-`ArgBinding` lift that a type gate would want.
* `roadmap/changelog.md` entry for `argmapping-path-expressions` (R84): landed `PathExpr`, `ArgBindingMap.of`'s schema walk, and the `buildListAwarePathExtraction` walker. Records the deliberate choice of a parallel walker over augmenting `NestedInputField`, which this item revisits with a third consumer in hand.
