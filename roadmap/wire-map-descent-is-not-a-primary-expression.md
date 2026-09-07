---
id: R932
title: "Nested wire-map descent is spliced into operand slots, emitting Java that cannot compile"
status: Spec
bucket: bug
priority: 1
theme: codegen-correctness
depends-on: []
created: 2026-09-07
last-updated: 2026-09-07
---

# Nested wire-map descent is spliced into operand slots, emitting Java that cannot compile

## Goal

A mutation whose input object nests another input object, with a `@nodeId` field on the inner one,
generates Java that does not compile. `@nodeId` is the directive that makes a field carry an opaque
global id which the generator decodes back into the table key it stands for. When this lands, such a
mutation generates code that compiles, and no consumer has to flatten its published schema to
upgrade.

The trigger is ordinary rather than exotic. Any schema of this shape breaks today:

```graphql
input EndreStudieprogramInput {
  tilbysIPeriode: TilbysIPeriodeInput
}

input TilbysIPeriodeInput {
  fraTerminId: ID! @nodeId(typeName: "Termin")
}

type Mutation {
  endreStudieprogram(input: EndreStudieprogramInput!): Studieprogram
}
```

The minimal pair is depth: move `fraTerminId` up onto `EndreStudieprogramInput` and the same field
generates fine. It is the second level of nesting that breaks it, and the failure is a javac error at
the consumer, two per affected field:

```
[1938,72]  Type mismatch: cannot convert from java.lang.Object to boolean
[1938,239] _saksaS15 cannot be resolved to a variable
```

Found while migrating a consumer schema to Graphitron 10, where three ordinary mutations hit it
across five fields; it is a hard blocker for that upgrade with no consumer-side workaround short of
flattening the input objects in the published schema. The defect can only ever fail to compile,
never generate code that runs and silently does the wrong thing, so it is loud rather than
dangerous.

## The defect

`WireMapChain` is the single home for the *wire-map descent*: the expression that reads a value out
of the nested `Map` graphql-java hands an argument, yielding `null` at any level that is absent or is
not a `Map`. `WireMapChain.of` returns that descent as one Java **conditional expression** and does
not parenthesise it. `ArgCallEmitter.nestedMapValueExpr` short-circuits a single-segment path to
`mapLocal.get(key)`, a postfix expression, and delegates everything deeper to `WireMapChain.of`.

So the block a caller receives is a *postfix* expression at depth 1 and a *bare ternary* at depth 2
and beyond. A caller splicing it into the operand slot of any operator that binds tighter than `?:`
gets Java that reparses wrongly. The NodeId-decode local is the reported instance: the emit format
is

```java
"$T $L = ($L instanceof $T _s$L) ? $T.$L(_s$L) : null"
```

and `instanceof` binds tighter than `?:`, so the descent's own `: null` arm is swallowed into the
`instanceof` operand. The parenthesised group then has type `Object` where a `boolean` is required,
and the pattern variable is not definitely-matched on the true path, which is exactly the two errors
above.

Confirmed hazardous splice sites, all in `TypeFetcherGenerator`, all reached only at path depth >= 2:

* `buildInsertDecodeLocals` (the INSERT decode-locals walk), splicing `nestedMapValueExpr` straight
  into the `instanceof` operand.
* `emitAgreementDecodeLocal`, the same format string with an `_sa` salt.
* `emitBulkSetDecodeLocals`, in its `OnExplicitNull.CannotArrive` arm.
* The `appendDecodeLocal(..., CodeBlock wireValueExpr, ...)` overload, whose format string is the
  same and which is fed a descent from six call sites (the single-row and bulk lookup-key walks and
  their `MapGroup` / `DecodedRecordGroup` arms).
* Two control-flow guards the original report did not name, where `==` is the tighter operator
  rather than `instanceof`: the `if ($L == null)` null-agreement guard in the SET-list append and
  the `if ($L && $L == null)` refused-clear guard in `emitBulkSetDecodeLocals`. Both reparse as
  `... ? ... : (null == null)`, whose `Object` type then fails the enclosing `&&`.

The safe sites splice the descent into argument position (`DSL.val(<descent>, <col>.getDataType())`,
the `DSL.row(...)` lookup-key tuple), where any expression is legal. That split is the tell: whether
a given splice compiles depends on an operator the producer cannot see and the caller does not think
about.

`ConditionGlueRenderer` already hand-wraps two of its own splices as `($L) instanceof ...` with a
comment naming both operators, so one consumer discovered the hazard independently and fixed it
locally. `appendSetDecodeLocal` reaches the same end by a different route: it first binds the
descent to a named `Object` local and splices the local. Three consumers, three answers, one of
which is "nothing".

`nestedContainsKeyExpr` is the same shape one operator away. It returns an `&&`-chain, also not a
primary expression, safe at its current call sites only because `&&` binds looser than `==` and
tighter than `?:`. A future `!<presence>` or `<presence> instanceof` splice breaks it identically.

## Implementation

Make the descent a *primary expression* at the producer, so it is safe in every operand slot and no
caller has to know the precedence of the operator it is being spliced into:

* `WireMapChain.of` wraps its result in one paren pair at the `of` boundary, not inside the
  recursive `at`. Wrapping at the boundary is what makes the invariant structural: no future arm
  added inside `at` can drop the parens, because it does not own the wrap. Its javadoc states the
  contract (the result is a primary expression, safe to splice anywhere) and drops the caller-beware
  silence it has now.
* `nestedContainsKeyExpr` gets the same wrap on its deeper-than-one-segment arm, for the same
  reason and before it costs anything.
* Delete the now-redundant hand-wraps in `ConditionGlueRenderer`'s enum and `JooqConvert` arms, so
  the tree carries one answer rather than three. Leave `appendSetDecodeLocal`'s named `Object` local
  alone: it exists to be read twice (the refusal guard and the mismatch guard both test it), not to
  dodge precedence.

The single-segment arms of both producers stay unparenthesised. `mapLocal.get(key)` and
`mapLocal.containsKey(key)` are already primary expressions, and wrapping them would change emitted
output at every existing call site for no gain.

The cost is a redundant paren pair at the argument-position consumers, `DSL.val((<descent>), ...)`.
Accepted deliberately: generated code is a consumer artifact and this is a real debit against that,
but a paren pair is not what a reader of that line notices, and the alternative is a fact restated
at nine emit sites with nothing failing when one of them disagrees.

There is no golden-file sweep. No generated-source snapshot asserts the descent's text, because
code-string assertions on generated bodies are banned at every test tier; the one test that
string-matches an `instanceof Map<?, ?> map1` chain (`ServiceMethodCallEmitterTest`) exercises
`ServiceMethodCallEmitter`'s own separate list-aware chain, not `WireMapChain`.

## Tests

The regression test is a fixture mutation whose input is a nested object carrying a `@nodeId` field,
asserted by **compiling** the generated source. A paren bug is invisible to a substring assertion
and unmissable to a compiler, and the compile tier is already the named enforcer for
"classifier guarantees shape emitter assumptions". That the defect shipped is itself the finding:
nothing in the fixture corpus reaches nested path x `@nodeId`-decoded leaf, so the whole family of
descent-splice faults is currently uncovered.

Cover both the `instanceof` operand and the `==` operand, since they are two operators and one
fixture need not exercise both: a `@nodeId` leaf under a nested input reaches the decode locals, and
the same leaf under an explicit-null rule that is not `CannotArrive` reaches the refused-clear guard.

No unit test in `graphitron/src/test` references `WireMapChain` or `nestedMapValueExpr` today. A
direct unit test on the producer is worth adding only if it asserts the contract rather than the
characters: that the returned block survives being spliced into an operand slot. Prefer the
compile-tier fixture as the load-bearing coverage and treat any producer-level test as secondary.

## Other solutions we've considered

**Parenthesise at each hazardous emit statement** (`(($L) instanceof $T _s$L)`). Two tokens per
site, touches nothing else. Rejected: it spells one fact at nine sites with no enforcer, which is
the "fact restated with no single enforcer" drift smell by name, and it leaves the tenth consumer
to rediscover the hazard. The report that filed this defect already notes it is the second time the
shape has cost something.

**Give `WireMapChain` two entry points**, one primary-expression and one raw. Rejected as strictly
worse than the status quo: it converts an invisible hazard into a caller obligation, and the caller
is by construction the party that cannot see the operator its result lands in.

**Lift the contract into the type**: have `of` return a `record PrimaryExpr(CodeBlock code)` so
nothing *can* splice a bare descent. This is the honest enforcer, and it is the arm to reach for if
the boundary wrap proves insufficient. Held back here because wrapping at the `of` boundary already
makes the invariant unbreakable from inside the producer, which is where the risk of a future edit
actually lies; the wrapper's remaining value is guarding against a second public entry point, which
the bullet above rules out on separate grounds.

**Route every hazardous site through `ArgPathHelperRegistry`**, the statement-form sibling
`WireMapChain`'s own javadoc names as "the form to grow towards". The call site collapses to
`argInTilbysIPeriodeFraTerminId(in)`, a method invocation, which is a primary expression by
construction; the descent becomes a readable statement sequence in a private static helper, which
also discharges the "statement form over expression tricks" and "no throwaway pattern-binding noise
(`_s`, `_r`)" readability rules that these same lines violate today. This is the right end state and
is not being argued against. It is not this item because the registry does not reach these sites:
`TypeFetcherGenerator` already holds one on its emission context and already drains it onto the
class, but the mutation DML walk is a chain of `private static` methods that would each grow a
parameter (`buildInsertDecodeLocals` alone has nine call sites), so the diff is a signature sweep
through the mutation half of a 6000-line file rather than a fix. Landing the correctness fix first
and the statement-form migration second is additive-then-cutover; doing it in one item is one
rewrite of those methods with a shipped consumer blocker riding on it. R334 already owns the
family-wide move: its 2026-07-24 scope names the deep-input-path `instanceof Map`/`List` chains and
the insert-value ternaries and states the fix shape as hoisting to named locals or routing through a
generated helper. This item should leave a note there rather than re-deriving it.

## Documentation

Two prose statements go stale under this fix and should be corrected in the same item rather than
found at the Done gate:

* `docs/architecture/principles/development-principles.adoc`, readability-rules smell paragraph,
  asserts "(The NodeId-decode instance is cleaned up; the `@condition` arg-extraction instance is
  not.)" The NodeId-decode ternary with `_s`-prefixed pattern variables is live at four sites in
  `TypeFetcherGenerator`. That parenthetical is the sentence a reader uses to decide whether this
  family is already handled, and it says yes for the half that is not.
* `docs/architecture/reference/argument-resolution.adoc`, section "Runtime: nested input-field arg
  extraction", narrates the ternary chain as the contract and should state the primary-expression
  guarantee.

## Provenance

Filed from a consumer migration to Graphitron 10, where these five sites were the last errors
standing between the consumer schema and a clean build. The fork between parenthesising at the emit
sites and fixing the producer was raised in the report as deliberately worth deciding rather than
patching, on the grounds that the bug class is any consumer splicing the descent into an operand
slot and not just the three sites then known. Investigating the fork found three more hazardous
sites than the report listed (`emitAgreementDecodeLocal` and the two `==` guards), which is the
argument for the producer-level fix stated as evidence rather than as principle.

## Reviewer findings
