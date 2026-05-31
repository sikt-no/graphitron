---
id: R260
title: Readable generated code for NodeId decode extraction (drop ternary/underscore style)
status: In Review
bucket: cleanup
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-05-29
last-updated: 2026-05-31
---

# Readable generated code for NodeId decode extraction (drop ternary/underscore style)

`ArgCallEmitter.buildNodeIdDecodeExtraction`
(`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/ArgCallEmitter.java:355-455`)
emits NodeId-decode logic as a single deeply-nested ternary expression with
underscore-prefixed pattern-binding locals (`_s`, `_r`, `_nl`). A representative
arity-1 throw-arm emission reads:

```java
(env.getArgument("x")) instanceof String _s
    ? (((Object) NodeIdEncoder.decodeFoo(_s)) instanceof Record1 _r
        ? (Long) _r.value1()
        : ((Supplier<?>) () -> { throw new GraphqlErrorException("..."); }).get())
    : null
```

This is hard to read and hard to debug: a developer stepping through a failing
mutation cannot set a breakpoint on the decode, inspect the decoded record, or
read a stack frame with a meaningful local name. The `Supplier`-lambda-throw
trick exists purely to keep the expression form. The underscore-prefixed names
are unsightly and carry no meaning, and `((Object) x) instanceof Raw _r` casts
obscure intent.

The expression form was a deliberate choice because the output is consumed as a
*method-call argument* (`<Conditions>.<method>(table, <thisExpr>)`), so it could
not be a statement block in place. The cleanup is to lift each such decode into a
named private helper method on the enclosing class (the
[Helper-locality](../docs/rewrite-design-principles.adoc) convention already
used for `<field>OrderBy` and the `create<Bean>` input helpers), so the call site
collapses to `decodeFooKey(env.getArgument("x"))` and the helper body is readable
statement form with explicit types and meaningful names:

```java
private static Long decodeFooKey(Object wire) {
    if (!(wire instanceof String nodeId)) {
        return null;
    }
    Record1<Long> key = NodeIdEncoder.decodeFoo(nodeId);
    if (key == null) {
        throw new GraphqlErrorException("Decoded NodeId did not match the expected type for this argument");
    }
    return key.value1();
}
```

(The list and arity-N arms get the analogous statement-form helpers; the
`(Object) ... instanceof Record1` cast stays only where a parameterised
`instanceof` pattern would otherwise require Java 21+, per the
generator-vs-output Java-version principle.)

## Scope

- `ArgCallEmitter.buildNodeIdDecodeExtraction` (scalar + list, throw + skip arms)
  and `CompositeDecodeHelperRegistry` (the arity > 1 helper already lifts to a
  named method and already emits statement form; it does *not* use
  underscore-prefixed locals, but its body still binds single-character locals
  `s`/`r`/`nl` (`CompositeDecodeHelperRegistry.java:94,106`). Rename these to
  meaningful names (`nodeId`/`key`) to match the readable shape; there is no
  underscore prefix to drop there).
- Audit the rest of the rewrite emitters for the same ternary/underscore style
  and the `Supplier`-lambda-throw trick; fold any siblings into this cleanup.
  Two concrete siblings are already known and in scope: the list-aware path
  walker `ArgCallEmitter.walkSegments`
  (`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/ArgCallEmitter.java:675-709`)
  and the matching map-traversal in `ServiceMethodCallEmitter` (~lines 230-247),
  both of which emit counter-suffixed pattern-binding locals (`_m1`, `_l2`,
  `_e3`) inside nested ternaries. Two near-neighbours are deliberately *not* in
  scope:
  - The lookup-WHERE decode local `__lookupKey<i>` (`recLocal` in
    `TypeFetcherGenerator.buildLookupWhereSingleRow` /
    `appendMapBindingValueExpr`, `TypeFetcherGenerator.java:2762,2793`) *is* an
    expression-local, but `appendDecodeLocal` already lifts it into a named
    statement-form local rather than a nested ternary, and its stem
    (`lookupKey`) is meaningful, so it does not exhibit the
    meaningless-underscore-local-in-a-ternary smell this principle targets. (The
    double-underscore prefix is a separate, lower-value naming nit; out of scope
    here.)
  - jOOQ *aliases* (`<field>_0`) in `TypeFetcherGenerator`/`FieldBuilder` name
    SQL aliases, not expression locals, and are out of scope.

## Out of scope

- Behaviour changes. This is a pure readability/debuggability refactor; the
  generated code must be behaviourally identical (pinned by the existing
  pipeline tests and the `graphitron-sakila-example` compile/execute tiers, plus
  any execution-tier test that exercises a NodeId-decoded argument).

## Tests

Behaviour is pinned by the compile/execute tiers above; the classification-tier
pipeline tests (`NodeIdPipelineTest`) assert the decode *model*
(`decodeMethod().methodName()`, arm variant) rather than emitted text, so they
are unaffected. Two unit-tier tests *do* pin the old emitted shape by string
match and must be updated to the new helper/statement form as part of this work:

- `ServiceMethodCallEmitterTest` (`generators/ServiceMethodCallEmitterTest.java:210-254`)
  asserts the exact `_m1`/`_m2`/`_l2`/`_e3` pattern-binding text via
  `.contains(...)`. Since the matching map-traversal is in scope, these
  assertions break; rewrite them against the new local names / statement shape.
- `QueryConditionsGeneratorLiftTest` carries the old `_m1` form in a javadoc
  example (`generators/QueryConditionsGeneratorLiftTest.java:19`); refresh it so
  the doc does not name a shape the emitter no longer produces.

A new arity-1 case is added to `CompositeDecodeHelperRegistryTest`
(`generators/CompositeDecodeHelperRegistryTest.java`): the registry now lifts
*every* arity (not just composite), so the arity-1 `decode<Type>Key`/`Keys`
naming, the bare-key return type, and the `Record1::value1` projection (skip and
throw bodies) are pinned there alongside the existing arity-N coverage.

Execution tier: `GraphQLQueryTest.films_filteredByArgNodeId_dropsWrongTypeIdViaSkipHelper`
drives the lifted arity-1 list helper (`QueryConditions.decodeFilmKeys`) end to
end: a wrong-`typeName` id decodes to `null` and is dropped by the helper's
`filter(nonNull)`, exercising the GraphQL→generated-helper→jOOQ path at runtime.

Note on the *throw* arm: in-scope investigation found that `ThrowOnMismatch`
NodeId decodes do not currently reach `buildNodeIdDecodeExtraction` /
`QueryConditions`. The classifier reserves `ThrowOnMismatch` for synthesised
lookup-key paths (`FieldBuilder.java:1045-1048`), which are consumed by the
separate `TypeFetcherGenerator.appendDecodeLocal` emitter (out of scope here),
not by the registry. Explicit same-table `@nodeId` args (scalar or list) classify
as `SkipMismatchedElement`. The registry's throw helper is therefore correct but
not generated by any current schema; its body is pinned at the unit tier
(`CompositeDecodeHelperRegistryTest`) rather than the execution tier, since no
reachable schema fixture produces it. An execution-tier throw test is deferred to
whenever the classifier wires a non-lookup-key `ThrowOnMismatch` condition arg.

## Forcing function

This item is the cleanup half of R195, which adds a *new* NodeId-decode consumer
(input-bean record fields) and must not copy the ternary/underscore style. The
new readability principle in `rewrite-design-principles.adoc` ("Generated code is
read and debugged") is the standard both this item and R195 emit against.

