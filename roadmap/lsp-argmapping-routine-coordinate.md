---
id: R626
title: "LSP argMapping completions and diagnostics at the @routine coordinate"
status: Backlog
bucket: architecture
priority: 2
theme: lsp
depends-on: []
created: 2026-08-11
last-updated: 2026-08-13
---

# LSP argMapping completions and diagnostics at the @routine coordinate

`LspVocabulary.CanonicalOverlay.overlay()` keys `Behavior.ArgMappingBinding` on the `SchemaCoordinate.InputField("ExternalCodeReference", "argMapping")` coordinate only. `@routine`'s `argMapping` is a `SchemaCoordinate.DirectiveArg`, and the overlay names no `@routine` coordinate at all, so `@routine(argMapping:)` gets no completions and no diagnostics: an author gets editor help on `@service` and silence on `@routine`. Add the `SchemaCoordinate.DirectiveArg("routine", "argMapping")` coordinate bound to `Behavior.ArgMappingBinding`.

The design was worked out and reviewed as part of `roadmap/unify-argmapping-resolution-seam.md`, and lifted out of it at that item's Spec review to keep the seam work inside one module. It is independently observable and depends on nothing that item lands, so it can proceed in either order. Three observations from the current code:

* `ArgMappingCompletions.leftCandidates` goes through `ArgMappingSupport.resolveMethod` and returns an empty list when no Java method resolves, so at a `@routine` coordinate it degrades to silence rather than to wrong answers. It needs the routine's IN parameter names from the catalog instead. **Do not do this by branching on the coordinate.** `Behavior.ArgMappingBinding` is a data-free marker, so a coordinate branch would re-derive, at two sites that must agree and nothing binds (`leftCandidates` and `Diagnostics.resolveParameterNames`), a fact the overlay already knows when it registers the entry. Instead give the behaviour the fact: `ArgMappingBinding(TargetSource source)` with a sealed `TargetSource.{ReflectedMethod(SchemaCoordinate classNameCoord), RoutineParams(SchemaCoordinate nameCoord)}`. Both consumers switch on the carried variant and a third target set becomes a compile error at both. The exemplar is the sibling overlay entry `Behavior.MethodNameBinding(ecrClassName)`, which already carries the coordinate its resolution depends on. The data-free `case Behavior.ArgMappingBinding ignored ->` arms in `Completions`, `Definitions` and `Hovers` keep compiling unchanged.
* Note that this is where the "named target set" abstraction that the seam item rejects for the classifier *does* pay. In the LSP both sides genuinely are "a name set resolved from a sibling directive argument", one axis with two arms. In the classifier they are not, because the two sides are discovered at different times by different mechanisms. Same words, different fact, so the same abstraction is right in one module and wrong in the other.
* `ArgMappingCompletions.rightCandidates` is already generic over `TypeContext.fieldArgumentNames`, so it needs nothing. It deliberately returns *nothing* once the token contains a `.`, with the comment "Dot-path expansion into nested input fields is not modelled; offer nothing rather than a misleading flat list". That "offer nothing" note is now narrower than it reads, and the narrowing is worth stating because the two arms after a dot have different answers available:
  * *The input-object arm* (a dot opening an input object into its fields) is still unanswerable and stays uniform across directives; improving it needs a nested-input-field projection in the LSP snapshot, which is its own item.
  * *The node-id arm* (a dot opening an `ID` carrying `@nodeId(typeName:)` into that node type's key columns, the shape the key-column projection shipped) is answerable from `intent_resolved_node_key_column` today, and needs no new capture: `graphitron_argument_node_id` and `graphitron_field_node_id` already carry `node_type_ref` at both coordinates. What is missing is only the consumer, `rightCandidates` returning nothing the moment a dot appears. So the editor is silent on a spelling the build now both accepts and rejects with a candidate list, which is the asymmetry to close.
* `Diagnostics.validateArgMappingGraphqlArg` already validates the head segment only and already flags just the head span. It becomes reachable at `@routine` for free once the overlay coordinate exists.

Tests: `ArgMappingCompletionsTest` and `ArgMappingDiagnosticsTest` gain `@routine` cases, mirroring the `@service` behaviour those files already pin: left-hand completions offer the routine's IN parameter names, and a typo'd head segment underlines the head span only.

A small adjacent follow-up rides the same completions surface: `ArgMappingSigil` admits `$session` as an argMapping RHS at the `@service` site (binding the parameter to the `<sessionState>` mount's returned handle), but right-hand completions never offer it. Offering the admitted sigil literal alongside the field argument names, at admitted sites only, is one more entry in an existing list rather than a new mechanism; the sigil owner (`ArgMappingSigil`) is where the admitted-site predicate already lives, so the completions arm should consult it rather than re-declare it. Named here because the session-identity spec that deferred it deletes at Done.
