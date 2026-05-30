---
id: R260
title: Readable generated code for NodeId decode extraction (drop ternary/underscore style)
status: Spec
bucket: cleanup
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-05-29
last-updated: 2026-05-30
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
  named method; align its body to the same readable shape and drop underscore
  locals there too).
- Audit the rest of the rewrite emitters for the same ternary/underscore style
  and the `Supplier`-lambda-throw trick; fold any siblings into this cleanup.
  Two concrete siblings are already known and in scope: the list-aware path
  walker `ArgCallEmitter.walkSegments`
  (`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/ArgCallEmitter.java:675-709`)
  and the matching map-traversal in `ServiceMethodCallEmitter` (~lines 230-247),
  both of which emit counter-suffixed pattern-binding locals (`_m1`, `_l2`,
  `_e3`) inside nested ternaries. Indexed *helper-method* names (`__lookupKey0`)
  and jOOQ *aliases* (`<field>_0`) in `TypeFetcherGenerator`/`FieldBuilder` are
  *not* in scope: they name methods and SQL aliases, not expression-local
  variables, and do not exhibit the meaningless-underscore-local smell this
  principle targets.

## Out of scope

- Behaviour changes. This is a pure readability/debuggability refactor; the
  generated code must be behaviourally identical (pinned by the existing
  pipeline tests and the `graphitron-sakila-example` compile/execute tiers, plus
  any execution-tier test that exercises a NodeId-decoded argument).

## Forcing function

This item is the cleanup half of R195, which adds a *new* NodeId-decode consumer
(input-bean record fields) and must not copy the ternary/underscore style. The
new readability principle in `rewrite-design-principles.adoc` ("Generated code is
read and debugged") is the standard both this item and R195 emit against.

