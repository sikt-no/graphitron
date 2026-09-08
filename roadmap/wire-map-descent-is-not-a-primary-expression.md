---
id: R932
title: "Nested wire-map descent is spliced into operand slots, emitting Java that cannot compile"
status: Ready
bucket: bug
priority: 1
theme: codegen-correctness
depends-on: []
created: 2026-09-07
last-updated: 2026-09-08
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
  rather than `instanceof`: the `if ($L == null)` null-agreement guard in `appendAgreementValue`
  and the `if ($L && $L == null)` refused-clear guard in `emitBulkSetDecodeLocals`'s plain-carrier
  arm. Both reparse with the comparison bound to the descent's own null arm,
  `... ? ... : (null == null)`, leaving an `Object` where the `if` (or the enclosing `&&`) needs a
  `boolean`.

Six emit statements in all, since the `appendDecodeLocal` overload's six callers share one format
string. An audit of all twenty-three `nestedMapValueExpr` call sites found no seventh: every other
site splices the descent into argument position (`DSL.val(<descent>, <col>.getDataType())`, the
`DSL.row(...)` lookup-key tuple, `requireColumnAgreement`'s value arguments, a `List.add`), where
any expression is legal. That split is the tell: whether a given splice compiles depends on an
operator the producer cannot see and the caller does not think about.

`ConditionGlueRenderer` already hand-wraps four of its own splices, three as `($L) instanceof ...`
and one as `($L) != null`, across two methods: `appendAuthoredAnd`'s `FieldPresent` guard (both
branches, and the one comment in the tree that names both operators), and `nestedExtraction`'s
`EnumValueOf` arm and `JooqConvert` list arm. One consumer discovered the hazard independently and
fixed it locally, in four places. `appendSetDecodeLocal` reaches the same end by a different
route: it first binds the descent to a named `Object` local and splices the local. Three
consumers, three answers, one of which is "nothing".

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
* One wrap suffices at any path depth, and the reason is worth stating because it is what makes the
  boundary the right place. `at`'s two non-leaf arms already parenthesise their recursive result
  (`... ? ($L) : null`), and the `currentExpr` they descend into is always `binding.get(key)`, a
  postfix expression. So however deep the path, exactly one bare conditional expression escapes, the
  outermost one, and it escapes through `of`. Depth is not a case to handle; it is already handled.
* `nestedContainsKeyExpr` gets the same wrap on its deeper-than-one-segment arm, for the same
  reason and before it costs anything. It is depth-independent for the analogous reason: the chain
  it builds is flat `&&` at every depth, so one wrap closes it whatever the segment count.
* Delete the now-redundant hand-wraps in `ConditionGlueRenderer`, all four: `appendAuthoredAnd`'s
  two branches together with the comment that justifies them (which after this fix would assert a
  caller obligation the producer discharges), the `EnumValueOf` arm and the `JooqConvert` list arm.
  That is what leaves the tree carrying one answer rather than three. Leave `appendSetDecodeLocal`'s
  named `Object` local alone: it exists to be read twice (the refusal guard and the mismatch guard
  both test it), not to dodge precedence.

The single-segment arms of both producers stay unparenthesised. `mapLocal.get(key)` and
`mapLocal.containsKey(key)` are already primary expressions, and wrapping them would change emitted
output at every existing call site for no gain.

The cost is a redundant paren pair at the argument-position consumers, `DSL.val((<descent>), ...)`.
Accepted deliberately: generated code is a consumer artifact and this is a real debit against that,
but a paren pair is not what a reader of that line notices, and the alternative is a fact restated
at six emit sites in `TypeFetcherGenerator` plus the four `ConditionGlueRenderer` already carries,
with nothing failing when one of them disagrees.

There is no golden-file sweep. No generated-source snapshot asserts the descent's text, because
code-string assertions on generated bodies are banned at every test tier; the one test that
string-matches an `instanceof Map<?, ?> map1` chain (`ServiceMethodCallEmitterTest`) exercises
`ServiceMethodCallEmitter`'s own separate list-aware chain, not `WireMapChain`.

## Tests

The regression test is a fixture mutation whose input is a nested object carrying a `@nodeId` field,
asserted by **compiling** the generated source. A paren bug is invisible to a substring assertion
and unmissable to a compiler, and the compile tier is already the named enforcer for
"classifier guarantees shape emitter assumptions".

The load-bearing property of that fixture is that the mutation must be **DML**, a
`@mutation(typeName: INSERT | UPDATE)` field whose write graphitron generates. Nesting plus
`@nodeId` alone does not reach the defect, and the corpus proves it: `customerUpsert` in
`graphitron-sakila-example`'s `schema.graphqls` already declares
`CustomerUpsertInput.identity -> CustomerIdentityGroup.customerId @nodeId(typeName: "Customer")`,
compiles clean, and has seven passing `GraphQLQueryTest` execution tests. It is a `@service`
mutation, so its descent goes through `ArgCallEmitter.buildNestedInputFieldExtraction` into a
service-call argument list, which is argument position and therefore one of the safe sites above.
An implementer who builds the near-miss gets a green compile and no regression test.

So the coverage gap is narrower than "nested `@nodeId` is untested" and worth naming exactly: no
fixture anywhere reaches a nested path with a `@nodeId`-decoded leaf *through the generated DML
decode-locals walks*, which is why the whole family of descent-splice faults ships uncovered.
`customerUpsert` is the fixture to sit the new one beside; the two together are the minimal pair
that says which half of the shape is load-bearing.

Cover both the `instanceof` operand and the `==` operand, since they are two operators and one
fixture need not exercise both: a `@nodeId` leaf under a nested input reaches the decode locals, and
the same leaf under an explicit-null rule that is not `CannotArrive` reaches the refused-clear guard.

No unit test in `graphitron/src/test` references `WireMapChain` or `nestedMapValueExpr` today. A
direct unit test on the producer is worth adding only if it asserts the contract rather than the
characters: that the returned block survives being spliced into an operand slot. Prefer the
compile-tier fixture as the load-bearing coverage and treat any producer-level test as secondary.

## Other solutions we've considered

**Parenthesise at each hazardous emit statement** (`(($L) instanceof $T _s$L)`). Two tokens per
site, touches nothing else. Rejected: it spells one fact at ten sites with no enforcer, which is
the "fact restated with no single enforcer" drift smell by name, and it leaves the eleventh consumer
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
parameter (`buildInsertDecodeLocals` alone has eight call sites), so the diff is a signature sweep
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
  not.)" The NodeId-decode ternary with `_s`-prefixed pattern variables is live at five sites in
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

### Round 1 (2026-09-07, Spec -> Ready, reviewer session 01DvSBrMskSSE6GFhvW1cDR4)

Verdict: withhold. Two findings, one on each gate question. The goal reads cleanly without
reconstruction from the plan: a consumer whose published schema nests an input object with a
`@nodeId` field inside another input object can generate and compile code today only by flattening
that schema, and after this lands they do not have to. The defect analysis is the strongest part of
the item and it checks out line by line: `WireMapChain.of` does return an unparenthesised
conditional expression, `ArgCallEmitter.nestedMapValueExpr` does short-circuit only the
single-segment case, and every hazardous splice site the spec names exists with the format string
it quotes, including the two `==` guards the original report missed. The chosen fix, wrapping at
the `of` boundary rather than inside `at`, is the right shape and the rejected alternatives are
argued honestly. Both findings below are factual rather than design: the plan is built on two
counts of the tree that are wrong, and each wrong count changes what the implementer builds.

**Finding 1 (question one: is the outcome reachable as planned). The corpus-coverage claim is
false, and the fixture recipe as written describes something that already exists and compiles.**

The Tests section states that "nothing in the fixture corpus reaches nested path x
`@nodeId`-decoded leaf, so the whole family of descent-splice faults is currently uncovered", and
prescribes "a fixture mutation whose input is a nested object carrying a `@nodeId` field".

`graphitron-sakila-example/src/main/resources/graphql/schema.graphqls` already carries exactly that
shape:

```graphql
input CustomerUpsertInput {
    identity: CustomerIdentityGroup
    details: CustomerDetailsGroup
}

input CustomerIdentityGroup {
    customerId: ID! @nodeId(typeName: "Customer")
}
```

reached by the `customerUpsert(in: CustomerUpsertInput!)` mutation, which compiles today and has
three passing execution tests in `GraphQLQueryTest`
(`customerUpsert_nestedLeafSet_landsOnColumn_omittedSiblingUntouched` and its two
explicit-null counterparts). It is the only nested-input-with-`@nodeId` mutation in either the
sakila schemas or `graphitron/src/test/resources/corpus/`.

The reason it does not fire the defect is the property the spec never states: `customerUpsert` is a
`@service` mutation, so its descent goes through `ArgCallEmitter.buildNestedInputFieldExtraction`
into a service-call argument list, which is argument position and therefore one of the safe sites
the spec's own defect section identifies. What is uncovered is nested path x `@nodeId` leaf
*reaching the generated DML decode-locals walks* in `TypeFetcherGenerator`. An implementer who
builds the fixture the spec describes gets a green compile and no regression test.

What would satisfy this: state the mutation kind the fixture needs (a generated insert/update, not
a `@service` one) as the load-bearing property of the fixture, and correct the coverage claim to
say what is actually absent. The follow-on sentence about covering both the `instanceof` operand
and the `==` operand already points at the decode locals and the refused-clear guard, so the
guidance is half there; it is the headline claim and the one-line recipe that mislead. Naming
`customerUpsert` as the near-miss is worth a sentence too, since the next reader will find it and
wonder whether the gap is real.

*Author response (2026-09-08).* Done. The Tests section now states the DML requirement as the
fixture's load-bearing property, names `customerUpsert` as the near-miss and says why it misses
(a `@service` mutation splices into argument position), and narrows the coverage claim to
"through the generated DML decode-locals walks". The two fixtures are called out as the minimal
pair, since which half of the shape is load-bearing is the thing a later reader will get wrong.

**Finding 2 (question two: architecture fit). `ConditionGlueRenderer` has three hand-wrap sites,
not two, and the one the Implementation section omits is the one carrying the comment that states
the caller obligation this fix retires.**

The defect section says `ConditionGlueRenderer` "already hand-wraps two of its own splices as
`($L) instanceof ...` with a comment naming both operators", and the Implementation section says to
"delete the now-redundant hand-wraps in `ConditionGlueRenderer`'s enum and `JooqConvert` arms, so
the tree carries one answer rather than three".

There are three `($L) instanceof` wraps, in three methods:

* `nestedArgExtraction`'s `EnumValueOf` arm (`ConditionGlueRenderer.java:628`), named.
* `nestedArgExtraction`'s `JooqConvert` list arm (`ConditionGlueRenderer.java:635`), named.
* `appendAuthoredAnd`'s `FieldPresent` guard (`ConditionGlueRenderer.java:408`), not named. Its
  sibling non-list branch wraps the same read as `(($L) != null)` at line 411, which is the `==`
  operand hazard in its second habitat.

The comment naming both operators sits at lines 402 to 404, on `appendAuthoredAnd`, not on either
arm the deletion bullet lists. So following the Implementation section as written deletes two of
three wraps and leaves the third, plus a comment that after the fix asserts a caller obligation the
producer now discharges. The tree would still carry two answers, and the surviving comment is
precisely the artefact that teaches the next author to hand-wrap.

What would satisfy this: fold `appendAuthoredAnd` into the deletion bullet (both branches, and the
comment), and correct the "two splices" count in the defect section. This is a scope addition to a
cleanup bullet rather than a design change, but it is the author's because it changes the site list
the implementer works from.

*Author response (2026-09-08).* Done, and it was four wraps rather than three: `appendAuthoredAnd`
guards both branches, the list one with `($L) instanceof` and the scalar one with `($L) != null`.
The defect section now inventories all four across their three methods, and the Implementation
bullet deletes all four plus the comment. Also corrected in passing: the `==` guard the bullet list
called "the SET-list append" is `appendAgreementValue`, and only the `emitBulkSetDecodeLocals` one
fails an enclosing `&&`; the other fails the `if` directly.

**Non-blocking corrections, for the same revision.** Two counts in the body are off by one against
the tree, neither load-bearing:

* The Documentation section says the NodeId-decode ternary is "live at four sites in
  `TypeFetcherGenerator`". It is five: lines 2608, 3164, 3410, 3704 and 4452 all emit
  `($L instanceof $T _s$L) ? ...`.
* The `ArgPathHelperRegistry` alternative says "`buildInsertDecodeLocals` alone has nine call
  sites". It has eight (lines 2333, 2347, 4175, 4192, 5471, 5485, 5516, 5814); the other four
  occurrences of the name are javadoc `{@link}`s.

*Author response (2026-09-08).* Both corrected. While recounting I audited all twenty-three
`nestedMapValueExpr` call sites rather than only the named ones, which settled the two remaining
loose numbers: the hazardous sites are six emit statements, not nine, and there is no seventh. The
two indirect consumers that looked like candidates (`SetKeyReader.valueAt` and the reference-side
`refValue`) both land in `requireColumnAgreement` arguments, so they are safe. The cost paragraph
now says six plus `ConditionGlueRenderer`'s four.

**Checked and clean, so the next pass need not redo it.** Every other symbol the spec names exists
as named: `WireMapChain.of`, `ArgCallEmitter.nestedMapValueExpr`, `nestedContainsKeyExpr`,
`buildInsertDecodeLocals`, `emitAgreementDecodeLocal`, `emitBulkSetDecodeLocals`, the
`appendDecodeLocal(..., CodeBlock wireValueExpr, ...)` overload, `appendSetDecodeLocal`,
`OnExplicitNull.CannotArrive`, `ArgPathHelperRegistry`, `ServiceMethodCallEmitter` and
`ServiceMethodCallEmitterTest`. The six call sites feeding a descent into the `appendDecodeLocal`
overload are exactly six. `appendSetDecodeLocal` does bind the descent to a named `Object` local
and does read it twice, so leaving it alone is right. `nestedContainsKeyExpr` is safe at all of its
current call sites for the precedence reason given. `ServiceMethodCallEmitterTest`'s
`instanceof Map<?, ?> map1` assertions exercise `ServiceMethodCallEmitter.walkSegments` and no test
anywhere references `WireMapChain` or `nestedMapValueExpr`, so the no-golden-file-sweep claim
holds. Both documentation statements are where the spec says they are and both say what it says
they say. `R334`'s 2026-07-24 expanded scope does name the deep-input-path chains and the
insert-value ternaries with the fix shape the spec attributes to it.

### Round 2 (2026-09-08, Spec -> Ready, reviewer session 01HkBTPpTDLngWmP9E1fi7P4)

Verdict: sign off. Both round-1 findings are discharged and the revision holds against the tree.

**Question one, restated without the plan.** A consumer publishing an input object nested inside
another input object, where the inner one carries a `@nodeId` field, and reached by a mutation whose
write graphitron generates, gets Java that javac rejects; the only workaround is flattening the
published schema. After this lands that schema compiles, and the Graphitron 10 upgrade stops
depending on a schema change the consumer did not want to make. Nothing in that had to be
reconstructed from the phases.

**Question one, reachability.** The load-bearing structural claim is the depth invariant, and it is
the one worth re-deriving rather than taking on the spec's word, so: `at`'s two non-leaf arms emit
`... ? ($L) : null` around their recursive result, and the `currentExpr` they recurse into is
`CodeBlock.of("$L.get($S)", binding, key)`, a postfix expression. Every leaf arm and both non-leaf
arms are conditional expressions, and every one of them except the outermost is already inside a
paren pair its parent owns. So exactly one bare conditional escapes, through `of`, at any path
length, and one wrap at the `of` boundary discharges the contract without a depth case. The same
reasoning holds for `nestedContainsKeyExpr`: the builder emits a flat `&&` chain whatever the
segment count, so one wrap closes it.

**Question two.** The fix converges three answers into one at the party that can see the shape,
which is the direction the tree already leans; the four `ConditionGlueRenderer` deletions are what
make it a convergence rather than a fourth answer, and leaving `appendSetDecodeLocal`'s named local
alone is right because it is read three times, not once. The accepted paren-pair debit at the
argument-position consumers is named as a debit rather than waved off. The handoff to the
statement-form migration is additive-then-cutover with the family-wide move left where it is owned,
which is the right split for a shipped consumer blocker. I would hand this to an implementer as
written.

**Verified against the tree, so a later pass need not redo it.** The six hazardous emit statements
are exactly the six named, in the methods named: `buildInsertDecodeLocals` (the descent in the
`instanceof` operand), `emitAgreementDecodeLocal` (`_sa` salt), `emitBulkSetDecodeLocals`'s
`CannotArrive` arm, the `appendDecodeLocal(..., CodeBlock, ...)` overload, `appendAgreementValue`'s
`if ($L == null)` and `emitBulkSetDecodeLocals`'s plain-carrier `if ($L && $L == null)`. There are
five `($L instanceof $T _s$L) ? ...` emits in `TypeFetcherGenerator`; the fifth is
`appendSetDecodeLocal`, which is the one the spec correctly excludes. `nestedMapValueExpr` has
twenty-three call sites and no seventh hazardous one: `KeyReader.valueAt` and `refValue` both land
in `requireColumnAgreement` arguments, and the `appendSetDecodeLocal` delegation into
`appendDecodeLocal` is only ever handed `innerMap.get(leafKey)`, so the overload's descent-fed
callers are exactly six. `nestedContainsKeyExpr` is safe at every current site: `if ($L)`, the left
operand of an `&&`, and the condition slot of a `$L ? DSL.val(...) : DSL.defaultValue(...)`.
`buildInsertDecodeLocals` has eight call sites in a 6348-line file, and
`TypeFetcherEmissionContext` does hold an `ArgPathHelperRegistry` that `TypeFetcherGenerator`
drains with `ctx.argPathHelpers().emit().forEach(builder::addMethod)`, so the reason the registry
route is deferred is the signature sweep and not a missing registry. `customerUpsert` is a
`@service` mutation on `CustomerRecordService.describeCustomerUpsert`, which is why the near-miss
misses. No test references `WireMapChain` or `nestedMapValueExpr`, the only
`instanceof Map<?, ?> map1` string assertions are `ServiceMethodCallEmitterTest`'s on
`ServiceMethodCallEmitter.walkSegments`, and the code-string ban is stated at
`development-principles.adoc` as claimed, so the no-golden-file-sweep claim holds. Both
documentation targets exist and say what the spec says they say, and `R334`'s 2026-07-24 scope
names the chains and ternaries with the fix shape attributed to it.

**Corrected in this commit, none of it load-bearing.** `ConditionGlueRenderer`'s four hand-wraps
sit in two methods, not three: `appendAuthoredAnd` (both branches) and `nestedExtraction` (the
`EnumValueOf` and `JooqConvert` arms), now named so the implementer can find them; the consumer
fixed it in four places, not three. The rejected per-site alternative spells the fact at ten sites
(six plus `ConditionGlueRenderer`'s four), not nine, so it is the eleventh consumer left to
rediscover. `customerUpsert` has seven `GraphQLQueryTest` execution tests, not three.

**Non-blocking, and scope rather than a finding.** Two more producers return non-primary descents
and do not get the invariant: `ArgCallEmitter.walkSegments` (behind
`buildListAwarePathExtraction`) and `ServiceMethodCallEmitter.walkSegments`, both bare conditional
expressions, both safe only because their current consumers splice into argument position. The
spec deliberately scopes to `WireMapChain` plus `nestedContainsKeyExpr` and names the
`ServiceMethodCallEmitter` chain as separate, which is a defensible line and not a reason to
withhold; the same one-line boundary wrap would close both, and an implementer who wants that
should file it rather than widen this item.
