---
id: R624
title: "Unify argMapping resolution on one seam across every directive"
status: In Review
bucket: architecture
priority: 2
theme: classification-model
depends-on: []
created: 2026-08-11
last-updated: 2026-08-11
---

# Unify argMapping resolution on one seam across every directive

`argMapping` looks like one authoring surface and behaves like four. An author who found the dot-path form working on `@service` (they cannot have read it anywhere, as the documentation section establishes) and reaches for it on `@routine` is told dot-paths do not exist; the same string handed to `@record`'s producer-binding probe goes through a second, file-private parser that accepts entries the shared one rejects and rejects none of its own. The right-hand side of an `argMapping` entry names a GraphQL slot in scope, and that namespace is the same at every directive site, so the divergence is in the plumbing rather than in the meaning. This item makes `ArgBindingMap` the only path from an authored `argMapping` string to a resolved binding, deletes the two bypasses, and lands nested-path support on `@routine` as the first thing that falls out.

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

The only workaround was flattening the mutation's arguments, which forced an SDL shape on the consumer to work around a plumbing gap. Worse, `pBrukernavn: input` *passes* validation and emits `env.<String>getArgument("input")`, a runtime `ClassCastException` on a `LinkedHashMap`. That third spelling is closed too, by the leaf gate.

## The four tiers

The pre-state this item removed, kept for the reviewer: three of the four tiers are now one. `@routine` moved into the full-resolution row, the private parser is gone, and only the structurally-inert row is unchanged.

| Tier | Sites | Behaviour |
|---|---|---|
| Full resolution | `ServiceDirectiveResolver.resolve`, `ConditionResolver.resolveArg`, `ConditionResolver.resolveField`, `BuildContext.resolveConditionRef`, `BuildContext.buildInputFieldCondition` (parsing for the last two happens upstream in `BuildContext.readConditionDirective`, which carries the parse failure as `ConditionDirective.argMappingError` prose) | Parse, then `ArgBindingMap.of` walks each tail segment against the schema and yields a `PathExpr` chain with `liftsList` per step. Dot-paths work, unknown segments get a candidate hint. |
| Parse-only, head-only | `RoutineDirectiveResolver.resolveNode` / `bindArgs` | Calls `ArgBindingMap.parseArgMapping`, so it *holds* the segment chains, then rejects any chain longer than one and membership-checks the head against `FieldBuilder.fieldArgumentNames`. Mints `PathExpr.Head` directly and never calls `ArgBindingMap.of`. |
| Private divergent parser | `RecordBindingResolver.parseArgMapping` (file-private, distinct from the shared one) | Its own `String.split(",")` loop returning `Map<String, String>`. Takes the head by string surgery (`tail.substring(0, dot)`), which for well-formed input is the same name the shared parser's `segments.get(0)` yields; it diverges on whitespace around the dot (`"a: input . field"` yields `"input "`, unstripped) and on a duplicate Java target (last-wins here, a hard error in the shared parser). Silently skips an entry missing its colon. No syntax errors ever surface from this site. |
| Structurally inert | `@externalField` (`FieldBuilder.parseExternalRef`), `@enum` (`TypeBuilder`) | Rejects a non-blank `argMapping` at parse time, correctly: neither directive has GraphQL-argument-bound parameters. |

The emit side mirrors the split. `RoutineCallEmitter.argExpression` reads `ParamSource.Arg.graphqlArgName()`, which is defined as `path.headName()`, and emits a flat `env.<T>getArgument("<head>")` (or the `SelectedField` equivalent at correlated child positions). Path descent lives only on the `@service` side, in `ArgCallEmitter`'s `CallSiteExtraction.NestedInputField` arm plus its `buildListAwarePathExtraction` walker.

Two more surfaces carry the same split:

* **SDL.** `argMapping` is declared twice in `directives.graphqls`: once as `ExternalCodeReference.argMapping` (shared by `@service` / `@condition` / `@tableMethod`), once as a bare `@routine` directive argument. Two declarations, two hand-maintained doc blocks. Both describe the flat form only ("Each entry is `javaParam: graphqlArg`"), and both of the ECR block's examples are flat renames. **The dot-path form is documented nowhere:** `grep -rn "dot-path" docs/manual/` returns nothing, and no `argMapping` value containing a `.` appears anywhere in the manual. The capability has been shipping since the `PathExpr` work with no author-facing description at all, which reframes the documentation slice below from convergence to first authoring.
* **LSP.** `LspVocabulary.CanonicalOverlay.overlay()` keys `Behavior.ArgMappingBinding` on the `SchemaCoordinate.InputField("ExternalCodeReference", "argMapping")` coordinate. `@routine`'s is a `SchemaCoordinate.DirectiveArg`, and the overlay names no `@routine` coordinate at all, so `@routine(argMapping:)` gets no completions and no diagnostics. An author gets editor help on `@service` and silence on `@routine`. That surface is `roadmap/lsp-argmapping-routine-coordinate.md` (R626), lifted out of this item at Spec review; it is named here because it is one of the four faces of the same divergence, not because this item closes it.

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
* `@routine` has that check for `columnMapping` (the loop over `columnOverrides.keySet()` against `fn.params()` at the top of `bindArgs`) and **nothing** for `argMapping`. An entry whose left side is misspelled is silently dropped: `argMapping: "pBrukernavnn: brukernavn"` leaves `pBrukernavn` to identity-bind, and the author is told something about the *right* side of an entry they did not write. When the mis-keyed parameter's own name happens to match a field argument, the identity bind succeeds and the typo produces no message at all, so the current diagnostic is not merely misdirected, it is conditional.

This matters for sequencing, not just tidiness. Today's misdirected message is the only thing that surfaces a left-side typo at all, and it exists as a side effect of `bindArgs` synthesising `graphqlArg = param.name()`. Routing through the shared resolution removes the side effect, so the checks have to be added in the same slice or the typo gets quieter than it is now. Two rejections are needed, mirroring what `columnMapping` and `@service` already do:

* an entry naming a parameter that is not an IN parameter of the routine, and
* a routine parameter left with no binding at all, listing the available GraphQL arguments.

`ArgBindingMap.Result` has no arm for the second, because on the `@service` side it lives after reflection in `ServiceCatalog`'s per-parameter loop rather than in the binding map. That is correct and should stay correct; the routine resolver owns its own version.

## What landed

All eight slices shipped in one cycle. The seam:

* **The shared read.** `ArgBindingMap.Result` gained a `Failure` sub-seal carrying `message()`, so the four sites that lift both arms into the same channel with the same site prefix write one arm instead of two. `BuildContext.resolveConditionRef` deliberately still matches the two records, and now carries a comment saying why: its slot map is empty, so `formatNameSet` renders `[]` and the clause it adds to the `UnknownArgRef` arm alone is the only prose that explains it. `parseArgMapping` and `of` stayed two functions, as planned.
* **`@routine` routes through `of`.** The single-segment guard and the `fieldArgumentNames` membership check are gone from `bindArgs`; argument-sourced parameters resolve against `FieldBuilder.argSlotTypes(fieldDef)`. The identity case still resolves per parameter against the slot map, for the direction-mismatch reason the plan gave. Both left-hand-side rejections landed in the same slice.
* **The leaf gate** calls `ServiceCatalog.argExtraction` (`resolvePathLeafType` opened to package visibility) with the three-way split intact, and a routine-specific rejection above it for a leaf that is neither scalar nor enum. One rejection the plan did not name was needed: an *intermediate* list segment resolves to a scalar leaf and so passes the gate, but the descent cannot walk it, so it reports as a deferral.
* **`graphqlArgName()` is deleted.** javac named exactly the twelve main-source sites and nine test assertions the plan counted.
* **The emitter descends** through `ArgPathHelperRegistry`, a per-host-class collector modelled on `CompositeDecodeHelperRegistry`: a dot-path binding registers a `private static <Leaf> arg<Head><Segment>(Object root)` helper whose body is a statement sequence, and the call site collapses to `arg...(env.getArgument("input"))` or the `SelectedField` equivalent. The root is a parameter, so the two `ArgumentValueSource` forks pass their own. Two hosts carry a registry: the `<Type>Fetchers` class (on `TypeFetcherEmissionContext`, which every routine call site already holds) and the projection unit class.
* **`RoutineParamSource`** carries the routine narrowing structurally; the five unreachable arms and `nonRoutineParamSource` are gone, and `ArgBinding`'s javadoc names both mintable arms.
* **`RecordBindingResolver.parseArgMapping`** is deleted; `headSlotOverrides` reads the head segment off the shared parse and swallows a parse failure at string scope, as decided.

Two things worth carrying forward:

* `PathExpr` had to join `PackageImportDirectionTest`'s borrowed-ref dial. It was already in the admitted component closure through `ParamSource.Arg`; the routine emitter is the first `render` consumer to name it directly. If a later item moves `PathExpr` into `no.sikt.graphitron.rewrite.model` where its carrier lives, that entry disappears.
* The plan's docs slice was one page short of the true surface. Three how-to pages restate the flat-only syntax, and `handle-services.adoc` said field-level rebinding inside an input type "is not part of `@service`'s surface", true before this item and wrong after. Fixed here rather than filed.

## Retired vocabulary

* `ParamSource.Arg#graphqlArgName` (the accessor; call sites read `path().headName()`)
* `RecordBindingResolver#parseArgMapping` (the file-private second parser)
* `RoutineCallEmitter#nonRoutineParamSource`
* `FieldBuilder#fieldArgumentNames` (its last caller was the flat-slot membership check this item deleted; package-private and static, so javac stayed silent about it. Do not confuse it with `graphitron-lsp`'s unrelated same-named `TypeContext.fieldArgumentNames`, which is live.)
* The rejection string `"must bind a single GraphQL argument; dot-path bindings are not supported"`
* The rejection string `"binds to GraphQL argument '...', which is not an argument of this field"`

## Tests

* **Unit.** `ArgBindingMapTest` gained the `Result.Failure` pins: both arms read through one accessor while staying distinct records, and the empty-slot-map rendering that makes the path-step `@condition` clause load-bearing.
* **Pipeline.** `GraphitronSchemaBuilderTest` gained six cases in a new "@routine argMapping resolves on the shared seam" block: the `PathExpr.Step` chain a dot-path lands, unknown head and unknown tail segments rejecting with the shared wording and the candidate hint, and one case per new rejection (non-parameter target, unbound parameter, input-object leaf). `RoutineMutationWritePipelineTest` pins the descent helper's shape off the `MethodSpec` (one helper per binding, private static, leaf-typed return, root as an `Object` parameter), which is the seam both `ArgumentValueSource` forks depend on. `R96RecordBindingPipelineTest` pins the string-level swallow against a well-formed control.
* **Compilation.** The sakila example schema gained `Mutation.rentFilmPayloadNested` and `Actor.filmsNested`, so both `ArgumentValueSource` forks compile their emitted descent at `release 17`.
* **Execution.** `GraphQLQueryTest.rentFilmPayloadNested_dotPathArgMappingReachesTheRoutine` proves the round trip through the post-commit re-read; `RoutineFieldExecutionTest.correlatedChildRoutineBindsArgumentThroughDotPath` covers the `FromSelectedField` fork against the same expected rows as its flat sibling.

## User documentation

The dot-path form was documented in zero places; it is now documented in one, with the rest pointing at it.

* `docs/manual/reference/directives/service.adoc` carries the canonical `[[arg-mapping]]` section: the entry grammar, the head-vs-tail rules, depth, null-safety, and a wrapper-input example.
* `routine.adoc` and `condition.adoc` `xref` it; `routine.adoc` also gained the wrapper-input example and folded the nested case plus the three routine-specific rejections into its Constraints.
* Both `directives.graphqls` description blocks gained one shared sentence and an example, and point at the canonical page. No agreement test, as decided.
* `diagnostics-glossary.adoc` gained an `[#arg-mapping-path]` entry for the shared unknown-segment diagnostic with the routine-side rejections listed under it.
* `handle-services.adoc`, `external-code.adoc` and `add-custom-conditions.adoc` `xref` the canonical section; `handle-services.adoc`'s "not part of `@service`'s surface" sentence was corrected.

The check the workflow asks for holds: `routine.adoc` explains where a routine's parameters come from, never what a dot-path is.

## Second pass (rework from the In Review gate)

The `In Review -> Done` review at `2322a09` accepted the seam and asked for two closures; both are done, neither touched the design.

1. **The delivered pipeline test asserted on generated method-body code strings**, which `development-principles.adoc` bans at every tier and enforces at test-review time. The behaviour it duplicated was already pinned: the descent's runtime result on both `ArgumentValueSource` forks by the two execution tests, the drain by the sakila compile (a dropped drain is a dangling reference, as `ArgPathHelperRegistry`'s javadoc says), the model shape by `routineDotPathArgMappingLandsPathExprChain`. The statement-form-not-ternary claim was implementation rather than behaviour and is dropped; what survives is asserted off the `MethodSpec`: one helper per dot-path binding, private static, leaf-typed return, and the root as an `Object` parameter, which is the seam both forks depend on.

   The reviewer's context note is recorded rather than acted on: the ban is unevenly held (the same file's pre-existing `newRecord(` assertion, and `CompositeDecodeHelperRegistryTest` throughout). Relaxing it for registry-shape pins would be an edit to `development-principles.adoc` argued on its own, so nothing else in the tree was touched here.

2. **`FieldBuilder.fieldArgumentNames` was dead** once the flat-slot membership check went. Deleted and declared under Retired vocabulary above.

The reviewer's non-blocking note stands unacted: `ArgBindingMap.of` skipping an empty segment list leaves `resolvedOverrides.get(param.name())` null for `leafTypeGate` to dereference. Unreachable while the parser holds its guarantee, and the pre-existing `@service` path has the same shape, so diverging here would buy a guard on one of two identical seams.

## Out of scope

* **The LSP surface.** `@routine(argMapping:)` still gets no completions and no diagnostics after this item; `roadmap/lsp-argmapping-routine-coordinate.md` (R626) carries that work, including the `Behavior.ArgMappingBinding(TargetSource)` design reviewed here. Lifted at Spec review to keep this item inside one module: it is the only slice with no coupling to the others, and it needs its own module, test tier and design decision.

* **The emitter half of the leaf-type gate.** The classify-time half is *in* scope (see Implementation): the coercion-aware gate already exists as `ServiceCatalog.argExtraction` and the work is calling it. What stays out is honouring a non-`Direct` extraction on a routine binding at emit time, meaning the `CallSiteExtraction.EnumValueOf` and `JooqConvert` arms, plus the parameter-`DataType` lift onto `RoutineRef.ArgBinding` that `roadmap/routine-chain-residue.md` describes and that a principled coercion would want. This item rejects those combinations as deferrals naming the follow-up; `roadmap/routine-coercing-arg-extractions.md` (R625) implements them. Splitting the emitter half is right; splitting the *gate* would not have been, because this item widens the surface where the mismatch is easy to author while leaving an existing check uncalled.
* **The nested-object `argMapping` form** (`request: { customerId: input.customer.id, ... }`) is `roadmap/nested-argmapping-syntax.md`, which explicitly keeps the flat dot-path form as the common case. This item is about making the flat form uniform; that item adds a second form on top. They touch `parseArgMapping` from opposite directions, so whichever lands second rebases onto the other's seam.
* **Two adjacent defects, verified during Spec review and filed rather than folded in.** A list-shaped argument leaf bound to a scalar IN parameter still emits a failing cast (`roadmap/routine-arg-leaf-cardinality-gate.md`, R627), and the producer-binding probe still grounds a dot-path leaf parameter against the outer input type (`roadmap/producer-probe-dotpath-misgrounding.md`, R628). Both are reachable today rather than opened by this item, and each needs a decision this item does not have to make.
* Removing or deprecating any currently-accepted spelling. Every schema that builds today must still build.

## Acceptance

All met; the pin for each is named under Tests above.

* The consumer's original SDL builds: `@routine(argMapping: "pBrukernavn: input.brukernavn")` on a payload-carrier mutation whose only field argument is the wrapper input, with no SDL restructuring.
* Exactly one code path leads from an authored `argMapping` string to a resolved binding. Grepping for a second parser or a second slot-membership check finds nothing: `RecordBindingResolver.parseArgMapping` is gone, and `RoutineDirectiveResolver` no longer calls `fieldArgumentNames` for argument binding.
* A given malformed `argMapping` produces the same diagnostic wording at every directive that accepts one, modulo the site-context prefix.
* `ParamSource.Arg.graphqlArgName()` no longer exists, so no consumer can read a nested path's head as if it were the whole binding.
* A left-hand-side typo on `@routine(argMapping:)` names the routine parameter it failed to find, rather than reporting something about the right-hand side.
* `pBrukernavn: input` is a build error naming the input-object leaf, not a runtime `ClassCastException`. (The list-shaped-leaf sibling is R627's, per Out of scope; this criterion is about the input-object case only.)
* The dot-path form is described in the manual, once, with the other pages `xref`ing it. Grepping the manual for the form finds a real explanation rather than the nothing it found before.
* No currently-building schema stops building, and no existing `argMapping` fixture assertion changes except the two retired `@routine` rejection strings and the `graphqlArgName()` call sites in `GraphitronSchemaBuilderTest` / `ServiceCatalogTest`, which become `path.headName()` reads.

## Cross-references

* `roadmap/routine-coercing-arg-extractions.md` (R625): the argument-side type gate this item deliberately leaves open, filed alongside it.
* `roadmap/lsp-argmapping-routine-coordinate.md` (R626): the editor surface, lifted out of this item at Spec review with its design intact.
* `roadmap/routine-arg-leaf-cardinality-gate.md` (R627): the cardinality sibling of this item's leaf rejection, which lands as one more condition at the site this item creates.
* `roadmap/producer-probe-dotpath-misgrounding.md` (R628): the producer-probe grounding defect this item's `RecordBindingResolver` slice leaves untouched.
* `roadmap/readable-condition-arg-extraction.md` (R334): the same nested-ternary readability problem at the `@condition` argument sites, and the reason the emitter slice's helper should render statements rather than relocate a ternary chain.
* `roadmap/nested-argmapping-syntax.md` (R249): the nested-object form, sibling surface on the same parser.
* `roadmap/routine-chain-residue.md` (R448): the enum / ID-as-String coercion residue that blocks the argument-side type gate, and the parameter-`DataType`-on-`ArgBinding` lift that a type gate would want.
* `roadmap/changelog.md` entry for `argmapping-path-expressions` (R84): landed `PathExpr`, `ArgBindingMap.of`'s schema walk, and the `buildListAwarePathExtraction` walker. Records the deliberate choice of a parallel walker over augmenting `NestedInputField`, which this item revisits with a third consumer in hand.
