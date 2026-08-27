---
id: R675
title: "@condition resolves its method by name alone, so per-participant overloads on a multitable filter are inexpressible"
status: Spec
bucket: architecture
priority: 3
theme: interface-union
depends-on: []
created: 2026-08-14
last-updated: 2026-08-19
---

# @condition resolves its method by name alone, so per-participant overloads on a multitable filter are inexpressible

An author writing a filter input for a query that returns a multitable interface wants one predicate method per participant, since each participant is a different table with differently-named columns. The natural Java expression of that is an overload set: three methods named `navn`, one per participant table type. The rewrite rejects it at build time:

> [author-error] input field 'navn' @condition: method 'navn' in class '...' is overloaded (3 declarations with parameter counts [2, 2, 2]) - graphitron cannot pick one; rename or remove overloads so exactly one method named 'navn' exists

Reported against 10.0.0-RC30 as one half of a filter-input report on multitable queries. The author's fallback is a single method taking `org.jooq.Table<?>` and resolving columns by name off it, which works and is what the multitable filter design intends, but gives up jOOQ's generated column typing on a surface where the whole point of jOOQ is that the columns are typed.

## Why it happens

`ServiceCatalog.pickMethod` is the single method-resolution point for every directive that names a Java method. It filters `cls.getDeclaredMethods()` by name and then judges: zero matches produce `Rejection.AuthorError.UnknownName`, more than one produce `ReflectionError.AmbiguousMethod` carrying each candidate's parameter arity. Nothing consults the *coordinate* the method is being resolved for, so a name shared by several declarations is ambiguous by construction, regardless of whether the surrounding context would pick one unambiguously.

The machinery for narrowing already exists and is one argument away. `pickMethod` has a second form taking a `SeamFilter`, which narrows same-named declarations before ambiguity is judged and produces its own two rejections (`SeamParameterMissing`, `SeamCandidateAmbiguous`). Only the session-hook path passes one; the three directive reflect helpers pass null and keep exact-name behaviour. So the question is not whether narrowing is possible but what the narrowing key should be for a per-participant `@condition`.

## What has to be decided

The multitable filter design deliberately chose the `Table<?>`-generic form. `FieldBuilder.lowerParticipantFilters` reflects the `@condition` method once per participant and calls it against each branch's stage-1 alias, and its own documentation states the contract: "a `Table<?>`-typed first parameter serves every branch, while a concrete participant-table parameter surfaces a mismatched branch at the consumer's javac". Per-participant overloads were never in scope; they are not an oversight so much as an unbuilt alternative.

So the item owns a decision, not a repair:

1. **Support per-participant dispatch.** `pickMethod` gains a participant-table-typed selector for the multitable coordinate, so the overload whose table parameter matches the branch's generated table type is chosen and the author keeps typed columns. Costs a resolution rule that varies by coordinate, and needs an answer for the partial case (overloads covering some participants but not all).
2. **Confirm `Table<?>` as the terminus.** Then the rejection is right and the gap is that it reads as a limitation rather than a signpost: the message says "rename or remove overloads" when what the author needs to hear is "on a multitable filter, one method takes `Table<?>` and serves every branch". The manual documents the `Table<?>` form generally, but not at this coordinate, and the reporter arrived here from the legacy README's per-type-overload pattern.

## Notes carried from Backlog

- The report claims per-type overloads work for `@condition` on a query field and fail only on an input field. That should not be true in the rewrite: all three directive reflect helpers route through the same name-keyed `pickMethod` with a null filter, so a query-field `@condition` with three same-named declarations should hit the same rejection. Most likely the claim is carried over from the legacy generator. The parity test below confirms it and stays as the regression pin.
- Whichever option ships, the multitable filter documentation is in scope: the reporter called the `Table<?>` form "undocumented". `add-custom-conditions.adoc` shows the form, and `global-id.adoc`'s `[#multitable-filter-inputs]` section even states it as load-bearing for the `@nodeId` `override: true` leaf, but no general multitable-filter documentation tells an author what to write at the reporter's coordinate.

Reported at https://github.com/sikt-no/graphitron/issues/525 (first half; the `@nodeId` half is R676, `nodeid-filter-per-participant-paths`).

---

## Decision: admit overload sets that agree on the binding shape; javac dispatches

Neither Backlog option ships as written. The resolution is a third shape, surfaced during the principles consultation and verified against the emitters: `pickMethod` stays name-keyed and coordinate-invariant, but the `@condition` reflect path (`reflectTableMethod`, the single entry for all four coordinates: argument-level, field-level, input-field, path-step) stops treating "more than one declaration" as ambiguous by itself. It judges the *binding shape* instead, because that is the only thing the model actually consumes from the reflection.

The load-bearing fact, verified in both `@condition` emission paths: the author-declared type of a `Table`-assignable parameter never appears in emitted code. `ConditionGlueRenderer.buildGlueMethod` types the glue's `table` parameter from the coordinate (`row.table().tableClass()`); `authoredExpr` passes that local straight through for a reach-free predicate, and for a reach-bearing one passes the terminal hop alias, itself declared from the hop target's generated table class. `PathFragments.emitTwoArgMethodCall` passes the two alias locals. (`ArgCallEmitter`'s `ParamSource.Table` arm is not a third path: its own javadoc states every `@service` caller passes a null `tableExpression`, and the arm exists so a leaked table slot fails loudly.) So the call site the generator emits is typed by the coordinate, identical for every member of an overload set that agrees on everything except its table slots, and the consumer's javac performs overload selection there, exactly as it already does for the single concrete-parameter form the fixtures use (`Condition c(Address address, ...)`).

One coordinate does consume the declared type, at classification time rather than in emission. On a path step, `BuildContext.resolveConditionJoinTarget` resolves the hop's target table from the method's second table slot whenever no declared target answers the question (a filter-path site never carries one), rejecting a `Table<?>` slot there as resolving nothing, and `validateConditionParamTables` checks concrete slot types against the hop's origin and target. Overload admission therefore has to make those two consumers set-aware; the admission deliverable below carries the rule.

The rule: same-named declarations are admitted as one `@condition` target when they agree position-by-position on the binding shape: each parameter position is either `Table`-assignable in every declaration, or identical in name and declared type in every declaration; all declarations are static and agree on the return type. Any declaration then serves as the reflected representative (its table-slot declared types are carried but emission-inert). Declarations that disagree on the binding shape reject as `ReflectionError.AmbiguousMethod`, which narrows to its true meaning: not "the name is shared" but "the shared name does not denote one call shape".

What this buys, with no new resolution machinery:

- **The reporter's overload set works, with typed columns.** `navn(Film, String)` / `navn(Forestilling, String)` / `navn(Arrangement, String)` agree on the binding shape; each branch's glue passes its concretely-typed stage-1 alias and javac picks the participant's declaration.
- **Partial coverage is consumer javac, which is already the documented contract.** Overloads covering two of three participants leave the third branch's call site with no applicable declaration; that is precisely the behaviour `FieldBuilder.lowerParticipantFilters`' javadoc states for a concrete parameter today ("a concrete participant-table parameter surfaces a mismatched branch at the consumer's javac").
- **Mixed sets and ties are javac's most-specific rule, not a graphitron rule.** A `Table<?>` declaration beside concrete ones acts as the fallback branch; nothing invisible-at-the-SDL is invented.
- **Resolution stays coordinate-invariant.** The shape judgement is table-blind, so input-field `@condition` (`BuildContext.buildInputFieldCondition`, which has no table in scope) resolves unchanged, and no participant table threads into input classification. The path-step coordinate's classification-time consumers of slot types become set-aware instead (the admission deliverable); reflection itself does not fork on coordinate.

### Why not the Backlog options

**Option 1 (participant-table-typed selector in `pickMethod`)** threads a coordinate into the one resolution point that is deliberately coordinate-blind, restructures input classification (input types are reusable across queries and resolve with no table in scope), and has to author semantics for partial coverage, mixed sets, and assignability ties that javac already owns. The seam-filter precedent does not transfer: `SeamFilter.SESSION_HOOK` exists because jOOQ's generated `Routines` classes force same-named overloads on the author; a `@condition` class is the author's own code.

**Option 2 (confirm `Table<?>` as the terminus)** erases type information at exactly the boundary the adapter/composer principle says not to: the glue parameter is already concretely typed, so the generated side of the pair carries the type and the terminus decision would discard it, with the documented recovery being runtime rediscovery (`instanceof` narrowing, or a `table.field(...)` probe that is null on the wrong branch and throws at request time on `.eq`). That is the DSL-runtime surprise the pair rule exists to prevent. The `Table<?>` single-method form stays fully supported and documented; it is just not the *only* expressible form.

## Deliverables

### Binding-shape admission in `reflectTableMethod`

`ServiceCatalog.reflectTableMethod` receives every same-named declaration (a new `pickMethod` outcome or a sibling entry that returns the candidate list; `pickMethod`'s zero/one/many-by-name contract for the `@service`, `@externalField`, and session-hook paths is untouched) and applies the shape rule above:

- Agreement admits: reflect the binding shape from any representative. `inferBindingsByType`, the `-parameters` warning, and `ParamSource` classification are representative-invariant by construction: the shape rule already forces name-and-type identity on every non-table position, and all declarations live in one class, so the `-parameters` state is uniform across the set.
- One existing check is *not* representative-invariant, and this item decides it. `checkConditionOverrideTargets` reads the *names* of the table-assignable parameters to reject an `argMapping` entry that targets the reserved slot, while the shape rule deliberately lets table slots differ in name as well as in type (`navn(Film film, ...)` beside `navn(Forestilling forestilling, ...)` is the reporter's own shape). Both branches reject today, but with different prose: a hit on the representative's table-slot name renders the "slot is reserved" message, a miss falls through to `checkOverrideTargets` and renders "references Java parameter 'film', but method ... has parameters [forestilling, navn]", naming a declaration the author did not write the mapping against, and which of the two fires depends on `getDeclaredMethods()` order, which the JVM does not specify. The rule: `checkConditionOverrideTargets` collects the table-slot names from *every* admitted declaration, so each table slot in the set is reserved and the reserved-slot message fires regardless of representative. (Requiring name identity on table positions would also close it, but it rejects the reporter's natural per-participant naming for no gain.)
- Disagreement rejects as `AmbiguousMethod`.
- The path-step coordinate reads table-slot declared types at classification time, so its two consumers become set-aware rather than representative-blind. The admitted set's table-slot types are carried, not only the representative's. `resolveConditionJoinTarget`: where a declared target answers the question the slot stays inert and overloads pass; where resolution falls to the method signature (every filter-path site), a set whose second slots all resolve the same table behaves as today, and a set that disagrees on the resolved target rejects through the existing unresolved-target path, prose naming the disagreeing declarations (the remedy is an agreeing set or, on sites that can carry one, a declared target). `validateConditionParamTables` becomes per-anchor applicability, the same statement handed to R647 below: at least one declaration whose slots accept the hop's origin and target, most-specific selection left to javac.
- Everywhere else the representative's table-slot declared type is carried on the `MethodRef.Param` as today and must stay emission-inert (it already is: `MethodRef`'s extraction accessors throw on `ParamSource.Table`, `ServiceMethodCallWalker` skips it, and the emitters substitute coordinate-typed expressions). If some future consumer starts reading it, the representative choice becomes visible; leave a pointer to this invariant at the admission site.

### `AmbiguousMethod` carries the candidates as data, not prose

Per the rejection contract (rejections are facts rendered into views, never prose composed at the detection site), the message improvement is structural:

- `AmbiguousMethod` gains the rendered candidate signatures (the `ServiceCatalog.renderSignature` form the seam arms already carry), replacing or augmenting `candidateArities`. Any consumer, including the LSP, can then see the overload set the author actually wrote without parsing prose.
- The `@condition`-path rejection (shape disagreement) renders its own guidance from that data: which positions disagree, and that overloads may differ only in their table slots (or collapse to a single `Table<?>` method). If path-specific wording is needed, the blessed shape is a typed discriminant threaded as an explicit input the way `SeamFilter` is, with `message()` switching on it (the `InvalidSchema.CaseFoldCollision.Origin` precedent); not a nullable pre-rendered hint slot.
- Arm identity and `lspCode()` (`graphitron.reflect.ambiguous-method`) stay stable. Drift-guards to touch: the `AmbiguousMethod` sentence in `typed-rejection.adoc`, `RejectionSeverityCoverageTest.sampleFor`, and the `RejectionResidueDrainageTest` roster if the component set changes.
- Reach of this deliverable: the improved rejection surfaces at the argument-, field-, and input-field coordinates. The path-step coordinate discards typed reflection rejections today (`BuildContext.resolveConditionRef` maps any failure to `ConditionResolution.Unresolved()` and the caller authors a generic message); restoring its rejection fidelity is a pre-existing gap, out of scope here.

### Documentation at the multitable coordinate

- `docs/manual/how-to/add-custom-conditions.adoc`: a new section on filtering multitable interfaces/unions, presenting both forms. The overload-set form: one declaration per participant, differing only in the table parameter; the branch emitter calls the shared name once per participant against that branch's concretely-typed alias, and the consumer's javac picks the declaration; a participant with no applicable declaration fails the consumer's compile, which is the intended guard for partial coverage. The single-method form: a `Table<?>` parameter serves every branch; the null-probe pattern `table.field(FILM.NAVN)` is typed and returns null on a branch whose table lacks the column, so the section must say in the same breath that an unguarded `.eq(...)` on that null throws at request time and that `DSL.noCondition()` is the escape for a non-matching branch; `instanceof` narrowing (Java 16+, safe on the consumer 17 floor) recovers the whole concrete table. The same file states the `Table<?>` first parameter as an invariant in three places that the new section contradicts, so they are reworded in the same commit: the "Every condition method has the same shape" lead under *Write the Java method*, the "*The first parameter is the surrounding `Table<?>`*" bullet below it, and "The first Java parameter is always the surrounding `Table<?>`" under *Constraints*. The file already contradicts itself: four prose signatures in its own walkthroughs type the first parameter concretely (`(City table, List<String> cityNames)`, `(City table, String countryId, List<String> cityNames)`, `(Film table, String filmId)`, `tenantScoped(City table, UUID tenantId)`), so the reword reconciles the whole file, presenting those as instances of the admitted concrete form rather than leaving them as exceptions to a stated invariant. The reference page also shows a concretely-typed slot in a canonical example (`iRegelverksamling(Regelverksamling rs, String regelverksamligId)`), so the how-to is the coordinate out of step, not the reference.
- `docs/manual/how-to/polymorphic-types.adoc`: the multitable section gains a short "Filtering" pointer to the new section (the reporter arrived at polymorphic types first and found nothing about filters there). The pointer names `@condition` explicitly: that page already uses the bare word "condition" throughout in the reference-path `{condition:}` join sense, and the pointer must not read as more of that.
- `docs/manual/reference/directives/condition.adoc`: state the admission rule (overloads are legal exactly when they agree on the binding shape; disagreement rejects) and cross-reference the how-to section. Its *Constraints* rung on multitable `@nodeId` `override: true` leaves ends "one method cannot mean both tables", which the admission rule falsifies as stated; reconcile the wording in the same commit. Whether the route-split rejection itself lifts under per-participant overloads is R676's territory, not this item's.
- `docs/manual/how-to/global-id.adoc`, `[#multitable-filter-inputs]`: this is the one existing multitable-coordinate statement of the shape, and it leans on it ("The condition method's table parameter is `Table<?>`-shaped, so a split would compile and show up only as a wrong `WHERE` at request time"). Under admission a concretely-typed per-participant set is legal at that leaf too, so both the shape claim and the would-compile reasoning are updated in the same commit.

### Tests

Pipeline tier (per `docs/architecture/how-to/testing.adoc`; `TestConditionStub` is the shared `@condition` fixture class), asserting typed arms, not message substrings (the same delta changes the message, so prose assertions would couple the pin to text under edit):

- **Admission**: an overload set agreeing modulo table slots classifies clean at the query-field and input-field coordinates, and the reflected `ConditionFilter` is the same either way. Home: `MultiTableFilterLoweringTest`, which already carries the `union Occupant = Customer | Staff` schema, the per-participant input-field lowering case, and the `assertLowersConditionFilterPerParticipant` helper the new case asserts through. Fixture: a new pair on `TestConditionStub` under its own name, one declaration per participant table (`Customer` and `Staff` share `first_name`, so the overload set is meaningful rather than synthetic). Do not overload the existing `occupantsFirstName`: its `Table<?>` declaration is the single-method pin the argument-bearing per-participant cases assert through (the no-argument field-level case pins `lifterFieldCondition`), and adding declarations to it would silently convert that pin into a mixed-set case.
- **Rejection parity**: a shape-disagreeing overload set produces `ReflectionError.AmbiguousMethod` at both coordinates (the typed-arm assertion mirrors `ServiceRootFetcherPipelineTest.serviceOnOverloadedMethod_surfacesAsTypedAmbiguousMethod`). This discharges the Backlog note's claim check: the report said the query-field coordinate accepts overloads while the input-field coordinate rejects; both route through the same resolution point, and this pair is the executable form of that reading. If writing it disproves the reading, stop and reopen to Spec.
- **Dispatch proof**: the reporter's scenario end-to-end: a multitable query with a filter input whose `@condition` names a per-participant overload set, proving each branch calls its own declaration. The execution tier is the natural home (the emitted dispatch is javac plus runtime behaviour), and the live coordinate to extend is the `AddressOccupant` union (`Customer | Staff`) developer-`@condition` family: `MultiTableConditionFixtures` (a main-source fixture class in `graphitron-sakila-service`) holds the `Table<?>` condition methods, the `@condition` declarations sit in the sakila-example schema, and `MultiTableFilterExecutionTest` already proves per-branch firing on `Query.occupantsByNamePrefix` by asserting a prefix match returns rows from both branches, which an implicit equality could not. A per-participant overload set on a sibling query field, asserted the same way, is the dispatch proof. The compilation tier (the same module) additionally proves a mixed set (`Table<?>` fallback beside a concrete declaration) compiles.
- **Path-step set-awareness**: pipeline pair for the set-aware target resolution above: an admitted set whose second table slots resolve the same table classifies on a filter-path hop as the single method does today; a set disagreeing on the resolved target rejects through the unresolved-target path. Home: the pipeline tests around reference-path `{condition:}` resolution, fixtures beside `ReferencePathConditionFixtures`.
- Honesty note: coordinate-invariance itself is enforced by `reflectTableMethod` being the sole `@condition` resolution entry over `pickMethod`'s single name-filter; the pipeline pair above is a regression sample over that invariant, not the invariant's enforcer. A structural check (no second `getDeclaredMethods()` name filter in main sources) is deliberately out of scope.

### Anchor definition handed to R647

R647 (`condition-table-parameter-anchor-assignability`) needs "the anchor table" defined before it can check anything. This item fixes that definition: the anchor is per emit-site arm and per slot (the coordinate's table for the single-table arms, each participant's table per branch for the multitable arm, source and target per slot for the path-step arm), and under overload admission R647's check statement is per-anchor applicability: at least one declaration of the set whose table slot accepts that anchor, with most-specific selection left to javac. R647's item body carries a pointer to this section (added with this spec).

## Out of scope

- Any resolution change for `@service`, `@externalField`, or the session-hook path: zero/one/many by name, seam filter where it applies, all unchanged.
- R647's actual assignability check (this item defines the anchor; that item builds the check).
- A structural enforcer for the single-resolution-point invariant (named in the Tests deliverable).
- Path-step rejection fidelity: `resolveConditionRef` discarding typed reflection rejections predates this item and stays; the set-aware target resolution renders through the existing unresolved-target message path.
- The `@nodeId` half of issue 525 (R676, its own item, further along in the pipeline).
- Relaying the outcome to the reporter on issue 525 happens when this ships, but the issue reply itself is not a gate for Done.

## Acceptance

- The reporter's per-participant overload set classifies, compiles, and dispatches per branch on a multitable interface or union filter; a shape-disagreeing set rejects with the typed `AmbiguousMethod` at the argument-, field-, and input-field coordinates, message rendered from candidate-signature data (the path-step coordinate keeps its caller-authored message; see out of scope). A set agreeing on shape but disagreeing on the path-step target slot rejects through the unresolved-target path.
- Non-`@condition` overload rejections keep their arm and code; their message may improve (signatures instead of arities) but their admission behaviour is unchanged.
- The four documentation coordinates are reconciled with the admission rule, and the how-to presents both forms, including the null-probe failure mode and the `DSL.noCondition()` escape.
- Full `mvn install -Plocal-db` green.

---

## Reviewer findings

### Round 2, Spec → Ready, revisions requested (session `aea644f4-5bb1-4c42-b837-90b42c4dcd6d`, 2026-08-27)

The decision holds. I re-verified the load-bearing claim independently and it stands in
emission: `ConditionGlueRenderer.buildGlueMethod` types the glue's `table` parameter from
`row.table().tableClass()`, `authoredExpr` passes the literal `table` local for a reach-free
predicate and the terminal hop alias for a reach-bearing one, `PathFragments.emitTwoArgMethodCall`
passes two bare aliases, and `ArgCallEmitter`'s `ParamSource.Table` arm throws on a null
`tableExpression` rather than emitting anything. `FieldBuilder.lowerParticipantFilters` reflects
per participant against `tb.table()` and rows group by `row.glue().owner()`, so each participant's
glue really is typed to that participant's generated table class and javac dispatches there. Every
symbol, test home, and documentation coordinate the last revision named checks out against the tree,
including the four concretely-typed prose signatures, the three `Table<?>`-invariant statements,
`condition.adoc`'s "one method cannot mean both tables" rung, `global-id.adoc`'s
`[#multitable-filter-inputs]` shape claim, and `polymorphic-types.adoc` having no filter material
at all. Three findings, all inside the admission deliverable.

**F1. `inferBindingsByType` is not representative-invariant either, and the set-wide fix as written
does not reach it.** The admission deliverable states that `inferBindingsByType`, the `-parameters`
warning, and `ParamSource` classification "are representative-invariant by construction", and
carves out `checkConditionOverrideTargets` as the one exception. That carve-out is too narrow.
`inferBindingsByType`'s reflective form builds `paramNames` from *every* named parameter, table
slots included, and the shared form uses it to decide which GraphQL slots count as claimed:

```
for (var entry : existing.entrySet()) {
    if (paramNames.contains(entry.getKey())) claimedSlots.add(entry.getValue().headName());
}
```

`ArgBindingMap.of` populates an identity entry for every unclaimed slot, and
`checkConditionOverrideTargets` explicitly skips identity entries, so an identity entry whose key
equals a table parameter's name reaches this loop unguarded. A slot is then claimed or unclaimed
depending on which declaration is the representative, and the shape rule deliberately lets table
slots differ in name.

Concretely: a field with arguments `film: FilmFilter, navn: String` and the overload set
`navn(Film film, FilmFilter kriterier)` / `navn(Forestilling forestilling, FilmFilter kriterier)`.
With the `Film` declaration as representative, slot `film` counts as claimed, inference finds no
unclaimed slot, and `kriterier` falls through to the structural rejection "parameter 'kriterier'
in method 'navn' is not a GraphQL argument and not a context key". With the `Forestilling`
declaration as representative, slot `film` is unclaimed, the arity-unique branch fires
(`FilmFilter` is a named input, the parameter is not a canonical scalar), `kriterier` binds, and
the build is green. Build passes or fails on `getDeclaredMethods()` order, which the JVM does not
specify. That is the same defect the last round found, one site over.

A third site reads table-slot names as prose: `checkOverrideTargets`' fall-through message renders
`formatNameSet(paramNames)`, which includes the representative's table-slot name. Once the reserved-
slot branch is set-wide, a genuine non-table typo still renders "has parameters [forestilling,
navn]" or "[film, navn]" nondeterministically. Lower severity than the above, same cause.

So table-slot *names* leak out of the table positions in three places, not one. The deliverable
should stop enumerating exceptions and state the invariant positively, because the current shape
does not scale: the set's table-slot names are collected once at admission, and every consumer that
reads a table-slot name reads that union rather than the representative's. Name the three current
readers under it. Alternatively, reconsider requiring name identity on table positions, which the
plan currently dismisses as costing the reporter's natural naming "for no gain": with three readers
found across two review rounds and no structural enforcer planned (the plan declines one, correctly),
the gain is now visible and worth re-weighing. Either resolution is fine; the claim as written is not.

**F2. The shape rule does not cover the whole carried signature.** `reflectTableMethod` builds
`MethodRef.StaticOnly(..., declaredExceptionFqns(javaMethod))`, so the model carries the
representative's `throws` clause. The shape rule requires agreement on static-ness and return type
but says nothing about declared exceptions, and the inert-carriage pointer at the end of the
deliverable names only the table-slot declared type. Two admitted declarations may differ in
`throws`, and which set the model carries is `getDeclaredMethods()` order again. It is inert today
(`checkDeclaredCheckedExceptions`' two callers are `buildWithChannel` and `buildServiceField`, both
on the `@service` path; the glue renderer emits no exception handling), so this is not a live
defect, which is exactly why it needs deciding now rather than being discovered by whoever wires
`@condition` into the `@error` channel. Cheapest resolution: add `throws`-clause agreement to the
shape rule, since return type is already there and `throws` is the other half of what the model
carries. Otherwise name `declaredExceptions` alongside the table-slot type in the inert-carriage
pointer. While there: the rule should say explicitly that declarations must agree on parameter
count. "Agree position-by-position" implies it, but `AmbiguousMethod` exists today precisely because
differing arity is the common overload case, so the implementer should not have to infer it.

**F3. Mixed sets disable the guard the plan advertises for partial coverage, and the documentation
deliverable does not say so.** The Decision section blesses a mixed set ("a `Table<?>` declaration
beside concrete ones acts as the fallback branch") and the compilation-tier test proves one
compiles. Separately, the plan rests partial coverage on the consumer's javac: "a participant with
no applicable declaration fails the consumer's compile, which is the intended guard". Those two
statements interact and the plan never puts them together. `Customer` is a subtype of
`Table<?>`, so javac's most-specific rule picks the concrete declaration where one applies and the
fallback everywhere else. A set covering two of three participants *plus* a `Table<?>` fallback
therefore compiles clean and silently serves the third branch from the fallback, which is precisely
the case where an author who mistyped one participant's table class most needs to hear about it.
R647's per-anchor applicability check will not catch it either: the fallback is applicable.

This is not a reason to refuse mixed sets, and the author opted into the fallback by writing it.
But the how-to deliverable currently presents two pure forms and asserts the javac guard
unconditionally, so it would ship prose the plan's own test contradicts. The new section needs the
third case in one sentence: a `Table<?>` declaration in an otherwise concrete set serves every
branch no concrete declaration covers, so it trades the compile-time partial-coverage guard for
runtime fallback behaviour. The acceptance criteria should carry the same distinction.

None of the three touches the decision or the test plan; F1 and F2 are inside the admission
deliverable and F3 is a sentence in two deliverables. Status stays Spec; the next pass may be this
session or another.

### Round 3, Spec → Ready, revisions requested (session `817ceb7b-5f71-46fc-b079-eba974782179`, 2026-08-27)

The spec body has not changed since round 2, so F1, F2 and F3 above are still open and this round
does not restate them. I did not take them on trust either. `inferBindingsByType`'s reflective form
builds `paramNames` from every named parameter with no `Table` filter (the `eligible` list drops
`Table` parameters one loop later, and only there), and `ArgBindingMap.of` populates an identity
entry for every unclaimed slot, so F1's worked example reaches the structural "not a GraphQL
argument and not a context key" rejection exactly as written. `reflectTableMethod` closes with
`new MethodRef.StaticOnly(..., declaredExceptionFqns(javaMethod))`, so F2's `throws` carriage is
the representative's. F3 needs no code reading: `Customer` is a `Table<?>` subtype, so javac's
most-specific rule does what F3 says it does. The decision and the emission claim under it hold;
`buildGlueMethod` types `table` from `row.table().tableClass()` and `authoredCall` emits the bare
local name, so no author-declared table type reaches generated source.

One finding is new, and it corrects round 2's own remedy at two of the three sites round 2 found.

**F4. Two of F1's three sites need table-slot names *excluded*, not unioned, and one of them is a
live single-method defect today.** Round 2 prescribed one rule for all three readers: collect the
table-slot names from every admitted declaration, and have each reader read that union instead of
the representative's. That is right for `checkConditionOverrideTargets`, where "reserved" genuinely
is a set-wide property: every table slot any admitted declaration declares must be unbindable. It
is wrong for the other two.

`inferBindingsByType` does not read table-slot names in order to reserve them. It reads them to
decide which GraphQL slots are already *claimed by a binding*, and a table parameter never claims a
slot: that is why the `eligible` filter drops `Table`-assignable parameters outright, and why the
loop's own comment says a slot counts as claimed only "when some Java parameter actually targets
it". Under the union rule, slot `film` counts as claimed whenever *any* admitted declaration
happens to name a table slot `film`, so inference is suppressed for a slot that no parameter binds.
Deterministic instead of `getDeclaredMethods()`-ordered, and still wrong. The rule this site needs
is that a `Table`-assignable parameter contributes no name to `paramNames` at all.

That rule also closes a defect that is live today with no overloads in sight, which is why it is
worth deciding here rather than leaving to the implementer's judgement. A single `@condition` method
`cond(Film film, FilmFilter kriterier)` on a field whose only argument is `film: FilmFilter` has
slot `film` marked claimed by its own *table* parameter's name, so `unclaimedSlotNames` comes out
empty, inference returns early, and `kriterier` falls through to the structural "parameter
'kriterier' in method 'cond' is not a GraphQL argument and not a context key". A table parameter
named after a field argument silently disables type-based inference. The item should say whether
that repair rides along (it is the same line at the same site) or is filed separately; either is
fine, but the admission deliverable is the only thing looking at this code.

The third site, `checkOverrideTargets`' fall-through message, wants exclusion for the same reason.
On the `@condition` path a `javaTarget` equal to any table-slot name has already been rejected by
`checkConditionOverrideTargets` before the fall-through runs, so table-slot names affect nothing
but the rendered `formatNameSet(paramNames)`. Under exclusion the message names the parameters an
`argMapping` entry may actually target, which is what the author needs to read; under the union it
names a parameter list that no single declaration has. The reflection-free form of
`checkOverrideTargets` is the `@service` path's and must not change.

So the positive invariant round 2 asked for has two halves, not one: a table slot's *name* is
reserved set-wide where admission checks bindability, and is invisible everywhere else a parameter
name is read as a binding target or printed as one. Both halves belong in the admission
deliverable, and the second half is what makes the invariant scale rather than accumulating a
fourth exception.

Gate: question 2. F1 through F4 are all claims the plan makes about code the implementer has to
touch, and the plan is wrong about it at three sites, so the implementer would be redesigning the
admission deliverable rather than executing it. The decision, the `AmbiguousMethod` deliverable,
the documentation deliverable and the test plan are unaffected and need no rework.

Non-blocking, no revision required:

- The admission deliverable says the admitted set's table-slot types are "carried" for the two
  path-step consumers, but `resolveConditionJoinTarget` and `validateConditionParamTables` read
  those types off the `MethodRef` in `BuildContext`, downstream of admission. So set-wide *types*
  need a carrier on the model while set-wide *names* stay local to `ServiceCatalog`. One sentence
  naming the carrier would help, because `MethodRef` is shared with the `@service` and
  `@externalField` paths.
- The shape-disagreement rejection is specified to render "which positions disagree", which cannot
  express a disagreement on static-ness, return type, or (per F2) parameter count. Same deliverable,
  one clause.
- "R647's item body carries a pointer to this section (added with this spec)" is already true in the
  tree: `roadmap/condition-table-parameter-anchor-assignability.md` carries it. Nothing to do.

Status stays Spec.
