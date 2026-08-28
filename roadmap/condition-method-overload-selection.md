---
id: R675
title: "@condition resolves its method by name alone, so per-participant overloads on a multitable filter are inexpressible"
status: Ready
bucket: architecture
priority: 3
theme: interface-union
depends-on: []
created: 2026-08-14
last-updated: 2026-08-28
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

Reported at https://github.com/sikt-no/graphitron/issues/525 (first half; the `@nodeId` half was R676, `nodeid-filter-per-participant-paths`, since shipped and recorded in `roadmap/changelog.md`).

---

## Decision: admit overload sets that agree on the binding shape; javac dispatches

Neither Backlog option ships as written. The resolution is a third shape, surfaced during the principles consultation and verified against the emitters: `pickMethod` stays name-keyed and coordinate-invariant, but the `@condition` reflect path (`reflectTableMethod`, the single entry for all four coordinates: argument-level, field-level, input-field, path-step) stops treating "more than one declaration" as ambiguous by itself. It judges the *binding shape* instead, because that is the only thing the model actually consumes from the reflection.

The load-bearing fact, verified in both `@condition` emission paths: the author-declared type of a `Table`-assignable parameter never appears in emitted code. `ConditionGlueRenderer.buildGlueMethod` types the glue's `table` parameter from the coordinate (`row.table().tableClass()`); `authoredExpr` passes that local straight through for a reach-free predicate, and for a reach-bearing one passes the terminal hop alias, itself declared from the hop target's generated table class. `PathFragments.emitTwoArgMethodCall` passes the two alias locals. (`ArgCallEmitter`'s `ParamSource.Table` arm is not a third path: its own javadoc states every `@service` caller passes a null `tableExpression`, and the arm exists so a leaked table slot fails loudly.) So the call site the generator emits is typed by the coordinate, identical for every member of an overload set that agrees on everything except its table slots, and the consumer's javac performs overload selection there, exactly as it already does for the single concrete-parameter form the fixtures use (`Condition c(Address address, ...)`).

One coordinate does consume the declared type, at classification time rather than in emission. On a path step, `BuildContext.resolveConditionJoinTarget` resolves the hop's target table from the method's second table slot whenever no declared target answers the question (a filter-path site never carries one), rejecting a `Table<?>` slot there as resolving nothing, and `validateConditionParamTables` checks concrete slot types against the hop's origin and target. Overload admission therefore has to make those two consumers set-aware; the admission deliverable below carries the rule.

The rule: same-named declarations are admitted as one `@condition` target when they agree on the binding shape: the same parameter count; position by position, each parameter position is either `Table`-assignable in every declaration, or identical in name and declared type in every declaration; all declarations are static; and all agree on the return type and the declared `throws` clause. Parameter count is implied by positional agreement and `throws` by nothing, so both are stated rather than left to inference: differing arity is the common overload case `AmbiguousMethod` exists for, and `throws` is the other half of what the model carries beside the return type (`MethodRef.StaticOnly` holds `declaredExceptions`; inert today, but "inert today" is a review-only invariant with a named future violator, whoever wires `@condition` into the `@error` channel, so agreement is required now instead). Admission mints the agreed shape as a value and the model is built from that value, so no declaration survives as a privileged representative; the admission deliverable carries the components. Declarations that disagree on the binding shape reject as `ReflectionError.AmbiguousMethod`, which narrows to its true meaning: not "the name is shared" but "the shared name does not denote one call shape".

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

`ServiceCatalog.reflectTableMethod` receives every same-named declaration (a new `pickMethod` outcome or a sibling entry that returns the candidate list; `pickMethod`'s zero/one/many-by-name contract for the `@service`, `@externalField`, and session-hook paths is untouched) and applies the shape rule above. Earlier revisions stated the set's invariants as prose ("representative-invariant by construction") with a roster of exceptional readers, and two review rounds each found a reader the roster missed, so the invariants are now structural: admission computes the agreed shape as a value, and everything downstream is built from that value rather than from a chosen `java.lang.reflect.Method`.

- **The agreed-shape value.** Admission folds the declarations into a record carrying: the non-table parameter list (names, declared types, positions); the table-slot positions, each with its decided table facts (below); two disjoint name components, `bindableParamNames` (the non-table parameter names) and `reservedTableSlotNames` (the union of every admitted declaration's table-slot names); the agreed return type; and the agreed `throws` set. Its constructor computes agreement and fails on disagreement, so arity, static-ness, return-type, and `throws` agreement are checks the value cannot be built without, not rules a reader has to remember. `reflectTableMethod` builds the `MethodRef` from this value, and no representative `Method` survives past admission, which is what makes representative-invariance unconstructable rather than asserted. `inferBindingsByType`'s eligibility input and the `-parameters` warning read the same value; all declarations live in one class, so the `-parameters` state is uniform across the set.
- **Table-slot names, two halves.** A table slot's name is reserved set-wide where admission checks bindability, and invisible everywhere else a parameter name is read as a binding target or printed as one. The distinction is carried by which name component a consumer receives, not by a sentence to remember: `checkConditionOverrideTargets` takes both components, so an `argMapping` target hitting `reservedTableSlotNames` renders the reserved-slot message no matter which declaration named the slot; `inferBindingsByType`'s reflective form and `checkOverrideTargets`' reflective form take `bindableParamNames` and cannot be handed a table-slot name, so a table parameter never claims a GraphQL slot and the fall-through message renders only names an `argMapping` entry may actually target. The reflection-free forms of both are the `@service` path's and do not change. (Requiring name identity on table positions would also have closed the original one-site finding, but not the exclusion sites, and it rejects the reporter's natural per-participant naming.) This invariant's scope is build-side classification in `ServiceCatalog`: the LSP's two `argMapping` readers answer the same bindable-target question from the census corpus and are a second producer of the fact in another module, carried by R854 (see out of scope).
- **The live single-method repair rides along.** With a singleton set the same component split closes a defect that is live today with no overloads in sight: a table parameter named after a field argument currently lands in `inferBindingsByType`'s `paramNames`, marks the slot claimed, and silently disables type-based inference, so `cond(Film film, FilmFilter kriterier)` on a field whose only argument is `film: FilmFilter` rejects with the structural "not a GraphQL argument and not a context key" message. It rides along rather than filing separately: it is the same producer and the same line, and shipping the invariant while a known counterexample survives inside the very method the invariant is about would be the shape this item refuses elsewhere. Under the agreed-shape value the singleton set takes the same split, so the defect closes by construction; it is a behaviour change, so it keeps its own pin (Tests below).
- **Table-slot facts are decided at admission, not re-derived downstream.** Both path-step consumers today run the identical decode over `params.get(i).typeName()`: the wildcard string predicate, a substring strip of type arguments, then `Class.forName` plus `JooqCatalog.findTableByClass`, and the code cites itself for the duplication ("Same predicate as `resolveConditionJoinTarget`"). Pluralizing the type-name string would multiply that recomputation by the declaration count, so the carrier is the decided fact instead: `ParamSource.Table` (an empty record today) gains a sealed per-slot outcome, a wildcard arm and a bound arm holding the admitted declarations' resolved `TableRef`s for that position, minted at reflection time in `ServiceCatalog`, which already wraps the catalog and already performs exactly this decode on the `@externalField` path (`checkExternalFieldParentTable`'s `findTableByClass`). Per-position on the slot is the right grain: the fact depends on the slot, and a set-level list on `MethodRef.StaticOnly` would be a repeating group keyed by position index on a record shared with `@externalField`, where the component is meaningless. Both existing `ParamSource.Table` construction sites mint the outcome (`reflectExternalField`'s is a singleton). `MethodRef.Param.Typed`'s `typeName`/`javaType` on a table slot stay emission-inert exactly as today (`MethodRef`'s extraction accessors throw on `ParamSource.Table`, `ServiceMethodCallWalker` skips it, and the emitters substitute coordinate-typed expressions); under the agreed-shape value there is no representative choice left to leak through them.
- **Path-step consumers read the decided fact.** `resolveConditionJoinTarget`: where a declared target answers the question the slot stays inert and overloads pass; where resolution falls to the method signature (every filter-path site), a bound slot whose `TableRef`s agree resolves that table, the wildcard arm keeps the existing wildcard rejection, and a bound slot whose `TableRef`s disagree rejects through the existing unresolved-target path, prose naming the disagreeing declarations (the remedy is an agreeing set or, on sites that can carry one, a declared target). `validateConditionParamTables` becomes per-anchor applicability over the slot's `TableRef` list via `denotesSameTableAs`, the same statement handed to R647 below: at least one declaration whose slots accept the hop's anchors, most-specific selection left to javac. The slot-2 agreement demand is a graphitron-side reduction, so it needs saying why it is not the resolution machinery the Decision refuses: the hop target is a generator fact naming the joined table in emitted SQL, decided at classification time, so there is no consumer javac call site to defer to; agreement is demanded exactly where javac cannot dispatch. A slot mixing a wildcard declaration with concrete ones is the wildcard arm, not the bound arm: the emitted `EXISTS` names one joined table, so a set that leaves the generator a choice of target leaves it no target, which is the same reduction one sentence up rather than a new rule. Those are the build's two consumers, and there is a third outside the build, carried by the deliverable below.
- **The path-step coordinate is reachable per participant, and the rule survives it.** An argument-level `@reference(path:)` filter on a multitable field is classified once per participant (`FieldBuilder.classifyArgument` parses the path from the participant's table, under `lowerParticipantFilters`' loop), so a `{condition:}` hop's origin varies per branch. Slot-1 origins per branch are exactly the per-anchor applicability statement, run per branch as classification already runs per branch. Slot-2 target resolution reads the admitted set, which is branch-invariant, so the agreed target is the same on every branch by construction. A set that intends a different join target per branch is asking for per-branch join topology, a different feature than admitting overloads at an existing call shape; it rejects through the same disagreeing-target path (out of scope below).

### The census-side route follows the resolver

`intent_condition_method_route` in `graphitron-model` answers the path-step routing question a second time, over the fact store rather than over live reflection, and both hop views join it. Its population is the same one the deliverable above changes: a reference step naming a condition class and neither a key nor a table, which is exactly the filter-path site where no declared target answers the question. The view's own comment states the agreement as load-bearing ("the generator's resolver reads it exactly this way at a filter site"), and its stated reason for carrying no return-type guard is that "the generator picks the method by name alone and rejects by name ambiguity". Admission removes that premise, so the store follows the resolver here rather than being left to drift; the alternative, a pointer to a later item, would ship an invariant with a known counterexample in another module, which is the shape this item refuses elsewhere.

The change is a fold and it needs no new capture. The view joins `jvm_method` per descriptor, so an overload set is already several rows, and the outer `UNION` collapses declarations that arrive at the same table. What moves is which sets survive that collapse. Two readings to fix before the rule, both from round 6: the `arrival` CTE's key is the pair *plus* `descriptor` plus the three arrival-table columns, which is exactly why an overload set is several rows there and why a `NOT EXISTS` correlated on that CTE's own key would be a no-op; and "an agreeing set stays one route" is true of arrival agreement only. The item's own headline shape, a per-participant set differing on the *departure* slot, is several route rows, one per departing table, which is the candidacy `from_table`'s comment already documents and the chain already narrows. Nothing below touches it.

- **One rule over the declarations, not a test over the arrivals.** A route survives only where every position-1 parameter the pair declares resolves, through the same `class_fqn` join the view already performs, to that route's own arrival table. Anything else routes nothing. Round 6 showed why the narrower form (count the distinct `sql_table`s among the arrival rows) cannot work, and the reason generalises past the case that exposed it: `arrival` reaches a table only via `sql_table.class_fqn = tr.referenced_class`, so a declaration whose position-1 slot names anything no table is generated as contributes no arrival row at all and is invisible to any test phrased over arrival rows. A test over arrivals can only ever see the declarations that arrived. `cond(Customer, Table<?>)` beside `cond(Customer, Address)` therefore leaves one arrival row and one distinct table, routes `customer->address`, and never reaches the defect view, while the build rejects it as the wildcard arm; `cond(Customer, Widget)` beside `cond(Customer, Address)` is the same defect one class name over, which is why the fix is a rule about declarations rather than a second special case. Spelled as a `NOT EXISTS` over `jvm_method_parameter` at `position = 1` for the pair whose body negates the arrival join, correlated to the candidate row. `jvm_method_parameter` and not `jvm_method_parameter_type_ref`, because a primitive slot declares a parameter and names no class, so it has no type-ref row; the view already leans on that asymmetry (`ConditionMethodRouteTest.aParameterNamingNoClassReadsAsTheSameAbsenceAWildcardDoes`). It is also the relation the defect view already joins for `FEWER_THAN_TWO_PARAMETERS`, so the two views keep answering over the same joins their comments claim for each other.
- **Where the rule stops, and why that is the line and not an oversight.** A declaration carrying no position-1 parameter declares no arrival, so it makes no claim this relation answers, and the pair keeps routing off the declarations that do. Whether such a pair is nevertheless ill-formed because its declarations disagree on arity is admission's question rather than routing's, and leaving it alone is what keeps `FEWER_THAN_TWO_PARAMETERS`'s existing reading ("no overload of the name declares a parameter at position 1") true word for word and its shipped pin (`ConditionMethodRouteDefectTest.aNameCarryingTwoDefectiveOverloadsIsOneRowAtThePrecedingVerdict`) unmoved. The closing paragraph below says which part of that boundary is capture and which is choice.
- **A sixth verdict, and the vocabulary stays closed and total without touching precedence.** Three populations are newly suppressed and two of them already have a verdict whose prose describes them exactly, reached by the `CASE` as it stands. A mixed wildcard-and-concrete set hits `WILDCARD_TARGET_PARAMETER`, whose test is an `EXISTS` over any position-1 slot naming `org.jooq.Table` and so is already set-wide; that, and not verdict ordering, is what makes the mixed set read the same on both sides, since ordering decides nothing for a pair that never enters the population. A set whose slots include a class no table is generated as, a primitive included, hits the `TARGET_NOT_A_TABLE_CLASS` fall-through, whose prose already reads "a second parameter that names something else, or that names no class at all as a primitive one does". One honesty note on that arm: for a singleton it is the resolver's own routing refusal, while for a set it is the build's *admission* refusal instead, a slot that is `Table`-assignable in one declaration and not in another disagreeing on shape before routing is ever asked. Both are no route, which is the whole of what the rung claims, and the verdict names the fact the census can see rather than the arm the build took. Only disagreement among slots that all resolve has no verdict, and that is what `TARGET_DISAGREEMENT_ACROSS_OVERLOADS` names, inserted after the wildcard arm and before the fall-through so a set that is both wildcard-mixed and disagreeing reads as the wildcard case, which is the resolver's own arm order. The totality claim survives by construction: a pair the route view suppresses lands in the defect population the same query already drives.
- **Two shipped pins invert; this is not an additive change.** `ConditionMethodRouteTest.twoOverloadsLandingOnTwoTablesAreTwoRoutes` seeds precisely the disagreeing set and asserts `containsExactly("bridge customer->address", "bridge film_actor->actor")`, under the section heading "Overloads and the guard that is deliberately absent" and a javadoc stating the pre-admission rule; assertion, heading and javadoc all invert to no route plus the new defect row. `ArgumentReferenceStepTargetTest.twoOverloadsOfOneConditionAreTwoTargetsAtOnePosition` seeds the same set through the argument-site hop view and asserts `film->actor` and `film->language` at `targets = 2` and `candidates = 2` under a javadoc reading "Overload multiplicity lands in the arities and nowhere else"; suppression empties that chain, so the case asserts the empty chain and the javadoc's claim narrows to the departure slot, where overload multiplicity does still land. And there is no agreeing-set case anywhere in `ConditionMethodRouteTest`: two declarations arriving at one table are the case to *write*, and they are what pins the outer-`UNION` collapse this whole deliverable rests on.
- **The stale rationale goes with it, in the DDL and in the test prose that repeats it.** The route view's no-return-type-guard sentence and its `method` column comment ("two overloads of it are two routes here") both assert the pre-admission rule, as does the `WILDCARD_TARGET_PARAMETER` line in the defect view's verdict comment where it explains itself in terms of what the resolver refuses; all three are rewritten in the same commit. So is `ConditionMethodRouteTest.aMethodTheCensusSaysReturnsNoConditionRoutesAnyway`'s javadoc, which repeats the DDL's rationale in the same words ("a chain the generator refuses as ambiguous"): the case itself stands, since the absence of a return-type guard is unaffected, but its stated reason does not. The DDL is the model's source of truth and the schema reference renders from these comments, so a comment asserting a resolver behaviour that no longer holds is a defect in the model rather than documentation drift, and a test javadoc restating it is the same defect with a second copy.

What stays out is the rest of the admission rule, and the boundary is part capture and part choice. The round-5 revision attributed all of it to capture, which round 6 corrected. **Capture:** `jvm_method` carries `return_type`, `declared_return_type` and `returns_condition`, and no static flag and no declared-exception list, so static-ness and `throws` agreement are not expressible over the census at any price. **Choice:** `jvm_method_parameter` carries `position`, `parameter_name`, `parameter_type` and `declared_parameter_type`, and `jvm_method_parameter_type_ref` carries `referenced_class` per position, so arity agreement and per-position type and name identity are all expressible today, arity with a single `NOT EXISTS`, and arity is the very disagreement the admission deliverable calls the common overload case. They stay out because they are a different question at a different grain. Every guard above is a statement about a declared arrival, which is what this relation is keyed to answer; whether a name denotes one call shape at all is asked at all four `@condition` coordinates, of which this routing population is one, so answering half of it here would put an admission verdict into a vocabulary whose stated subject is why a condition method routes no hop. Whoever captures the two missing columns can state the whole rule once, in a relation keyed on the class and method; that is the better shape than half the rule keyed on the routing population. The gap that leaves is a shape-disagreeing set still routing in the store while the build rejects it with `AmbiguousMethod`. It is real, it is pre-existing, and admission narrows it rather than widening it: today *any* second declaration is an `AmbiguousMethod` while the store routes the pair (see out of scope).

### `AmbiguousMethod` carries the candidates as data, not prose

Per the rejection contract (rejections are facts rendered into views, never prose composed at the detection site), the message improvement is structural:

- `AmbiguousMethod` gains the rendered candidate signatures (the `ServiceCatalog.renderSignature` form the seam arms already carry), replacing or augmenting `candidateArities`. Any consumer, including the LSP, can then see the overload set the author actually wrote without parsing prose.
- The `@condition`-path rejection (shape disagreement) renders its own guidance from that data plus the axis of disagreement, carried as data the way the candidates are: a typed discriminant naming static-ness, return type, parameter count, the `throws` clause, or a numbered parameter position, which the agreed-shape constructor knows at the moment it fails (a positions-only rendering could not express the first four). The guidance states that overloads may differ only in their table slots (or collapse to a single `Table<?>` method). If path-specific wording is needed, the blessed shape is a typed discriminant threaded as an explicit input the way `SeamFilter` is, with `message()` switching on it (the `InvalidSchema.CaseFoldCollision.Origin` precedent); not a nullable pre-rendered hint slot.
- Arm identity and `lspCode()` (`graphitron.reflect.ambiguous-method`) stay stable. Drift-guards to touch: the `AmbiguousMethod` sentence in `typed-rejection.adoc`, `RejectionSeverityCoverageTest.sampleFor`, and the `RejectionResidueDrainageTest` roster if the component set changes.
- Reach of this deliverable: the improved rejection surfaces at the argument-, field-, and input-field coordinates. The path-step coordinate discards typed reflection rejections today (`BuildContext.resolveConditionRef` maps any failure to `ConditionResolution.Unresolved()` and the caller authors a generic message); restoring its rejection fidelity is a pre-existing gap, out of scope here.

### Documentation at the multitable coordinate

- `docs/manual/how-to/add-custom-conditions.adoc`: a new section on filtering multitable interfaces/unions, presenting both forms. The overload-set form: one declaration per participant, differing only in the table parameter; the branch emitter calls the shared name once per participant against that branch's concretely-typed alias, and the consumer's javac picks the declaration; a participant with no applicable declaration fails the consumer's compile, which is the intended guard for partial coverage. The mixed set is the third case and the section must state its trade-off in the same breath, because the two pure forms' guarantees do not compose: a `Table<?>` declaration in an otherwise concrete set serves every branch no concrete declaration covers (javac's most-specific rule), so it trades the compile-time partial-coverage guard for runtime fallback behaviour; the author opts in by writing the fallback declaration, and nothing at build time flags a partial concrete set with a fallback (see acceptance). The single-method form: a `Table<?>` parameter serves every branch; the null-probe pattern `table.field(FILM.NAVN)` is typed and returns null on a branch whose table lacks the column, so the section must say in the same breath that an unguarded `.eq(...)` on that null throws at request time and that `DSL.noCondition()` is the escape for a non-matching branch; `instanceof` narrowing (Java 16+, safe on the consumer 17 floor) recovers the whole concrete table. The same file states the `Table<?>` first parameter as an invariant in three places that the new section contradicts, so they are reworded in the same commit: the "Every condition method has the same shape" lead under *Write the Java method*, the "*The first parameter is the surrounding `Table<?>`*" bullet below it, and "The first Java parameter is always the surrounding `Table<?>`" under *Constraints*. The file already contradicts itself: four prose signatures in its own walkthroughs type the first parameter concretely (`(City table, List<String> cityNames)`, `(City table, String countryId, List<String> cityNames)`, `(Film table, String filmId)`, `tenantScoped(City table, UUID tenantId)`), so the reword reconciles the whole file, presenting those as instances of the admitted concrete form rather than leaving them as exceptions to a stated invariant. The reference page also shows a concretely-typed slot in a canonical example (`iRegelverksamling(Regelverksamling rs, String regelverksamligId)`), so the how-to is the coordinate out of step, not the reference.
- `docs/manual/how-to/polymorphic-types.adoc`: the multitable section gains a short "Filtering" pointer to the new section (the reporter arrived at polymorphic types first and found nothing about filters there). The pointer names `@condition` explicitly: that page already uses the bare word "condition" throughout in the reference-path `{condition:}` join sense, and the pointer must not read as more of that.
- `docs/manual/reference/directives/condition.adoc`: state the admission rule (overloads are legal exactly when they agree on the binding shape; disagreement rejects) and cross-reference the how-to section. Its *Constraints* rung on multitable `@nodeId` `override: true` leaves ends "one method cannot mean both tables", which the admission rule falsifies as stated; reconcile the wording in the same commit. Whether the route-split rejection itself lifts under per-participant overloads is R676's territory, not this item's.
- `docs/manual/how-to/global-id.adoc`, `[#multitable-filter-inputs]`: this is the one existing multitable-coordinate statement of the shape, and it leans on it ("The condition method's table parameter is `Table<?>`-shaped, so a split would compile and show up only as a wrong `WHERE` at request time"). Under admission a concretely-typed per-participant set is legal at that leaf too, so both the shape claim and the would-compile reasoning are updated in the same commit.

### Tests

Pipeline tier (per `docs/architecture/how-to/testing.adoc`; `TestConditionStub` is the shared `@condition` fixture class), asserting typed arms, not message substrings (the same delta changes the message, so prose assertions would couple the pin to text under edit):

- **Admission**: an overload set agreeing modulo table slots classifies clean at the query-field and input-field coordinates, and the reflected `ConditionFilter` is deterministic because it is built from the agreed-shape value, so the case may assert the reflected shape directly. (The existing helper's discipline of asserting `methodName` only was a guard against pinning `getDeclaredMethods()` order; under the agreed shape that discipline is a consequence of the construction, not a rule the test holds by luck.) Home: `MultiTableFilterLoweringTest`, which already carries the `union Occupant = Customer | Staff` schema, the per-participant input-field lowering case, and the `assertLowersConditionFilterPerParticipant` helper the new case asserts through. Fixture: a new pair on `TestConditionStub` under its own name, one declaration per participant table (`Customer` and `Staff` share `first_name`, so the overload set is meaningful rather than synthetic). Do not overload the existing `occupantsFirstName`: its `Table<?>` declaration is the single-method pin the argument-bearing per-participant cases assert through (the no-argument field-level case pins `lifterFieldCondition`), and adding declarations to it would silently convert that pin into a mixed-set case.
- **Rejection parity**: a shape-disagreeing overload set produces `ReflectionError.AmbiguousMethod` at both coordinates (the typed-arm assertion mirrors `ServiceRootFetcherPipelineTest.serviceOnOverloadedMethod_surfacesAsTypedAmbiguousMethod`). This discharges the Backlog note's claim check: the report said the query-field coordinate accepts overloads while the input-field coordinate rejects; both route through the same resolution point, and this pair is the executable form of that reading. If writing it disproves the reading, stop and reopen to Spec.
- **Dispatch proof**: the reporter's scenario end-to-end: a multitable query with a filter input whose `@condition` names a per-participant overload set, proving each branch calls its own declaration. The execution tier is the natural home (the emitted dispatch is javac plus runtime behaviour), and the live coordinate to extend is the `AddressOccupant` union (`Customer | Staff`) developer-`@condition` family: `MultiTableConditionFixtures` (a main-source fixture class in `graphitron-sakila-service`) holds the `Table<?>` condition methods, the `@condition` declarations sit in the sakila-example schema, and `MultiTableFilterExecutionTest` already proves per-branch firing on `Query.occupantsByNamePrefix` by asserting a prefix match returns rows from both branches, which an implicit equality could not. A per-participant overload set on a sibling query field, asserted the same way, is the dispatch proof. The compilation tier (the same module) additionally proves a mixed set (`Table<?>` fallback beside a concrete declaration) compiles.
- **Path-step set-awareness**: pipeline pair for the set-aware target resolution above: an admitted set whose second table slots resolve the same table classifies on a filter-path hop as the single method does today; a set disagreeing on the resolved target rejects through the unresolved-target path. Home: the pipeline tests around reference-path `{condition:}` resolution, fixtures beside `ReferencePathConditionFixtures`.
- **Census-side route agreement**: model tier, against a seeded store, which is where what a view returns given rows is pinned. Two of these are inversions of shipped assertions rather than additions, so the direction is stated: `ConditionMethodRouteTest.twoOverloadsLandingOnTwoTablesAreTwoRoutes` becomes the no-route case, section heading and javadoc following it; `ArgumentReferenceStepTargetTest.twoOverloadsOfOneConditionAreTwoTargetsAtOnePosition` becomes an empty chain, its javadoc's overload-multiplicity claim narrowed to the departure slot. Genuinely new, in `ConditionMethodRouteTest`: the agreeing set (two declarations arriving at one table, one route), which nothing pins today and which is the outer-`UNION` collapse the fold rests on; the mixed wildcard-and-concrete set, no route; and the concrete-beside-a-non-table set, no route, since that population is newly suppressed too and was the one the round-5 wording missed one class name over. In `ConditionMethodRouteDefectTest`: `TARGET_DISAGREEMENT_ACROSS_OVERLOADS` for the disagreeing set, `WILDCARD_TARGET_PARAMETER` for the mixed set and `TARGET_NOT_A_TABLE_CLASS` for the concrete-beside-a-non-table set, the last two being reached by the untouched precedence rather than by a new arm, which is what those two cases exist to pin; plus a re-assertion of the pairing case that makes the vocabulary total, so a suppressed route is named exactly once. The mixed set is the case where the two modules' rules could most easily be spelled differently, so it is asserted on both relations rather than one.
- **Inference repair pin**: the single-method defect the admission closes, as its own pipeline case: a method whose table parameter is named after the field's only argument slot (the `cond(Film film, FilmFilter kriterier)` shape against `film: FilmFilter`) classifies clean with the non-table parameter bound by type-based inference, where today it rejects structurally. Home: beside the existing `inferBindingsByType` pipeline cases.
- Honesty note: coordinate-invariance itself is enforced by `reflectTableMethod` being the sole `@condition` resolution entry over `pickMethod`'s single name-filter; the pipeline pair above is a regression sample over that invariant, not the invariant's enforcer. A structural check stays deliberately out of scope, and not merely as redundant: other directive paths run their own `getDeclaredMethods()` name filters by design (`RecordBindingResolver.findUniqueMethod` on the grounding pass and `LifterMethodResolver.resolve` on the `@sourceRow` preamble are the current illustrations), so a blanket no-second-name-filter check would be a false-positive machine. While in the area: `findUniqueMethod`'s comment justifies its first-match pick by citing `ServiceCatalog.pickMethod`'s `methods.get(0)`, which `pickMethod` stopped doing when it grew the `AmbiguousMethod` arm, and the admission change redefines that contract again; correct the stale citation in passing.

### Anchor definition handed to R647

R647 (`condition-table-parameter-anchor-assignability`) needs "the anchor table" defined before it can check anything. This item fixes that definition, stated by provenance per slot rather than as a per-coordinate list, because not every anchor comes from the method signature: an anchor is the table the emitter will pass in that slot, wherever that table was resolved. The provenances: the coordinate's table for the single-table arms; each participant's table per branch for the multitable arm; for a path-step `{condition:}` hop, the hop's origin and the declared or slot-2-resolved target; and for a condition on an FK-derived hop, reached through `validateWhereFilterParamTables` (the second caller of `validateConditionParamTables`), the `synthesizeFkJoin`-resolved `originTable` and `targetTable`, which the method signature never sees. Under overload admission R647's check statement is per-anchor applicability: at least one declaration of the set whose table slot accepts that anchor, with most-specific selection left to javac. Under the admission deliverable's decided per-slot facts the anchor and the slot's admitted `TableRef`s are the same kind of value, so the check is a `denotesSameTableAs` scan rather than a type-name decode. R647's item body already carries a pointer to this section.

## Implementation notes

Three details the implementation settled that the plan left to the implementer, recorded because a reviewer reads the plan against the tree.

- **The per-slot outcome has three arms, not two.** The path-step deliverable names "a wildcard arm and a bound arm". The two existing author-facing refusals the deliverable says to keep are distinct messages: a wildcard `Table<?>` gets the wildcard-specific sentence, while a concrete type resolving to no catalog table gets the fall-through that quotes the type name. `ParamSource.Table.TableSlot` therefore carries `Wildcard`, `Unresolved` and `Bound`, with the wildcard-then-unresolved-then-bound precedence the census-side verdict order already reads. The third arm is the fall-through the message already had, not a new rule.
- **The `Bound` arm carries a display name beside each `TableRef`.** `TableRef` has no schema component, and the concrete-parameter mismatch message renders the declared side schema-qualified so two tables sharing a bare name across schemas stay distinguishable (`MultiSchemaConditionParamTest` asserts exactly that). The arm holds a `BoundTable(TableRef, qualifiedName)` pair rather than a bare ref so the message keeps its text.
- **The rejection-parity pair sits at the field-level and input-field coordinates.** Both surface the typed `AmbiguousMethod`. The argument-level coordinate on a multitable field does not, and the reason is not admission: an argument-level `@condition` whose reference fails to resolve at all is dropped silently there, which reproduces with a method name that does not exist and is therefore pre-existing and independent of this item. Filed as its own Backlog item (`multitable-arg-condition-rejection-dropped`) rather than repaired here: it is a question about where an argument-coordinate rejection is read on the per-participant lowering path, not about which declarations one name may denote.

## Out of scope

- Any resolution change for `@service`, `@externalField`, or the session-hook path: zero/one/many by name, seam filter where it applies, all unchanged.
- The LSP's `argMapping` bindable-target divergence: `ArgMappingCompletions.leftCandidates` and `Diagnostics.parameterNames` answer the bindable-target question from the census with no per-directive reservation, so today they offer and accept a table-slot name the build rejects, and admission multiplies that by the participant count. That is a second producer of the fact, in another module, over a different corpus, on a coordinate shared with `@service` where a table slot is not reserved; it is R854 (`lsp-argmapping-bindable-target-projection`), which owns the modelling decision (a per-directive projection over a shared derivation, not a blanket type filter). The discriminator against the single-method inference repair, which does ride along here: that one is the same producer and the same line; this one is a different producer in a different module.
- Per-branch join topology on a path-step `{condition:}` hop: a set whose slot-2 declarations intend a different join target per participant branch rejects through the disagreeing-target path; making a hop's target vary per branch is a join-shape feature, not overload admission.
- Mirroring the *binding-shape* half of admission over the census, for two different reasons that the census deliverable's closing paragraph separates. Static-ness and `throws` agreement are not expressible at all: `jvm_method` carries neither column. Arity and per-position type and name identity *are* expressible today over `jvm_method_parameter` and `jvm_method_parameter_type_ref`, and are left out by choice, being admission's question rather than the routing question this relation is keyed to answer. Either way a set disagreeing on shape rather than on its declared arrivals keeps routing in the store while the build rejects it; pre-existing and narrowed rather than widened by admission, so it belongs to whoever next widens the census, with the shape rule as the statement to capture against and the class-and-method grain as the place to state it whole.
- R647's actual assignability check (this item defines the anchor; that item builds the check).
- A structural enforcer for the single-resolution-point invariant (named in the Tests deliverable).
- Path-step rejection fidelity: `resolveConditionRef` discarding typed reflection rejections predates this item and stays; the set-aware target resolution renders through the existing unresolved-target message path.
- The `@nodeId` half of issue 525 (R676, its own item, since shipped).
- Relaying the outcome to the reporter on issue 525 happens when this ships, but the issue reply itself is not a gate for Done.

## Acceptance

- The reporter's per-participant overload set classifies, compiles, and dispatches per branch on a multitable interface or union filter; a shape-disagreeing set rejects with the typed `AmbiguousMethod` at the argument-, field-, and input-field coordinates, message rendered from candidate-signature data and carrying the axis of disagreement (the path-step coordinate keeps its caller-authored message; see out of scope). A set agreeing on shape but disagreeing on the path-step target slot rejects through the unresolved-target path.
- Non-`@condition` overload rejections keep their arm and code; their message may improve (signatures instead of arities) but their admission behaviour is unchanged.
- The single-method inference defect is closed: a table parameter named after a field argument no longer suppresses type-based inference, pinned by its own pipeline case.
- The four documentation coordinates are reconciled with the admission rule, and the how-to presents both pure forms plus the mixed set's trade-off, including the null-probe failure mode and the `DSL.noCondition()` escape.
- The mixed-set gap is a documented opt-in, stated here as well as in the how-to: a `Table<?>` declaration in an otherwise concrete set serves every branch no concrete declaration covers, and no build-time enforcer flags a partial concrete set with a fallback. The classifier holds the participant list and the admitted set at `lowerParticipantFilters`, so the gap is a choice, not an impossibility; naming it keeps a detector available as later roadmap material without building it in this item.
- The census-side route agrees with the resolver on every set the store can judge: a pair all of whose declared position-1 slots resolve to one table routes as it does today, and every other pair routes nothing and is named exactly once, a mixed wildcard-and-concrete set as `WILDCARD_TARGET_PARAMETER`, a slot naming no generated table as `TARGET_NOT_A_TABLE_CLASS`, and disagreement among resolving slots as the new `TARGET_DISAGREEMENT_ACROSS_OVERLOADS`. The defect vocabulary stays closed and total over the unrouted population, and its precedence is unchanged. Neither view carries a sentence asserting the pre-admission rule, and neither does the test prose that repeats it.
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

### Round 4, Spec → Ready, revisions requested (session `019dE95KHUhTJFYPk8eyKmHs`, 2026-08-27)

The spec body is unchanged since round 3, so F1 through F4 are still open and this round does not
restate them. I re-derived them rather than taking them on trust. `inferBindingsByType`'s reflective
form builds `paramNames` from every named parameter with no `Table` predicate, and the `eligible`
list drops `Table`-assignable parameters one loop later, so F1's worked example and F4's live
single-method defect both reproduce exactly as written; `checkConditionOverrideTargets` skips
identity entries before its reserved-slot branch, which is what lets an identity entry keyed on a
table-slot name reach the claimed-slots loop unguarded. `reflectTableMethod` closes with
`declaredExceptionFqns(javaMethod)`, so F2's `throws` carriage is the representative's. The decision
holds and the rest of the plan checks out against the tree: `candidateArities` has no reader outside
`AmbiguousMethod.message()`, so that deliverable is as cheap as the plan implies; `reflectTableMethod`
has exactly the four callers the plan names (`ConditionResolver.resolveArg` / `resolveField`,
`BuildContext.resolveConditionRef` / `buildInputFieldCondition`); all four documentation coordinates
read as described, including `polymorphic-types.adoc` carrying no filter material at all; and every
test home and fixture exists, with `assertLowersConditionFilterPerParticipant` asserting `methodName`
only, so the chosen pipeline home is already representative-invariant.

One finding is new. It is round 3's own invariant, in a habitat the deliverable's scope does not
reach.

**F5. The LSP reads table-slot names as `argMapping` binding targets in two places, and admission
multiplies what they get wrong.** `@condition(condition: ExternalCodeReference)` shares its
`argMapping` coordinate with `@service` and `@externalField`, and the LSP keys
`Behavior.ArgMappingBinding` on `InputField("ExternalCodeReference", "argMapping")`, so both LSP
`argMapping` surfaces are live at every `@condition` site:

- `ArgMappingCompletions.leftCandidates` selects `JVM_METHOD_PARAMETER.PARAMETER_NAME` for the named
  class and method with no type predicate, deduplicated across descriptors. Its own javadoc states
  the union is deliberate ("the union ... offers every name that could be right").
- `Diagnostics.parameterNames` unions `parameterNames()` across `answers.overloads(...)` the same
  way, and `judgeArgMappingJavaParam` accepts any name in that union, flagging only names outside it.

Today, with a single `cond(Film film, FilmFilter kriterier)`, the LSP offers `film` as a left-hand
target and accepts `argMapping: "film: ..."` without a diagnostic, while
`checkConditionOverrideTargets` rejects it at build. That is a pre-existing divergence over one name.
Under admission the reporter's set makes it three: `film`, `forestilling` and `arrangement` are all
offered and all accepted, all three must be rejected by the build once F4's first half makes
reservation set-wide, and two of the three are not even parameters of the declaration reflection
picks. So the item widens an existing wrong-completion surface by a factor of the participant count,
at the same coordinate whose reserved-slot rule it is redefining.

The census carries `JVM_METHOD_PARAMETER.PARAMETER_TYPE` and `DECLARED_PARAMETER_TYPE`, so the
filter is expressible without new capture. But the coordinate is shared with `@service`, where a
`Table`-assignable parameter is not a reserved slot in the same sense (`ArgCallEmitter`'s
`ParamSource.Table` arm exists precisely to fail loudly on a leaked one), so a blanket type filter at
that coordinate is not obviously the right shape and this may deserve its own item.

What the spec has to do is decide which, in a clause. Round 3's positive invariant is worded to reach
every reader of a table-slot name as a binding target ("invisible everywhere else a parameter name is
read as a binding target or printed as one"), while the deliverable that carries it is scoped to
`ServiceCatalog`. As it stands the implementer either overreaches into `graphitron-lsp` unbriefed, or
lands an invariant with two counterexamples in another module and no record that they were seen.

Non-blocking, no revision required:

- The plan declines a structural enforcer for the single-resolution-point invariant, correctly, but
  for a weaker reason than the tree supports. `RecordBindingResolver.findUniqueMethod` and
  `LifterMethodResolver.resolve` are two further `getDeclaredMethods()` name filters in main sources,
  on the `@service` / `@externalField` grounding pass and the `@sourceRow` preamble respectively, so
  the check as described ("no second `getDeclaredMethods()` name filter in main sources") would be a
  false-positive machine rather than merely redundant. Worth a clause only if that sentence is
  touched anyway. While in the area: `findUniqueMethod`'s comment justifies its first-match pick as
  mirroring "`ServiceCatalog.pickMethod`'s `methods.get(0)`", which `pickMethod` has not done since
  it grew the `AmbiguousMethod` arm, and the admission deliverable redefines that contract again.
- `validateConditionParamTables` has two callers, not one: the condition-hop arm passes `cr.ref()`
  with the reflected target, and `validateWhereFilterParamTables` passes `hop.filter().method()`
  against an FK-join hop's `originTable` / `targetTable`, which `synthesizeFkJoin` resolved rather
  than the method signature. Naming the method covers both, but the anchor definition handed to R647
  reads as if the condition hop were the only path-step anchor source, and R647 inherits that
  sentence.
- The Tests deliverable says the admitted set's reflected `ConditionFilter` is "the same either way".
  Any assertion that reaches into the filter's params pins whichever declaration
  `getDeclaredMethods()` yielded first, which is stable within a JVM run and unspecified across runs.
  The existing helper asserts `methodName` only; the new admission case should hold that discipline
  explicitly rather than by luck.

Gate: question 2, unchanged from round 3. Status stays Spec.

### Round 5, Spec → Ready, revisions requested (session `01R3bwxzhVfG7DSZCQPLmtrr`, 2026-08-27)

Rounds 2 through 4 are all closed by the round-4 revision, and I checked each rather than
taking the revision's word for it. F1 and F4 are answered structurally by the disjoint
`bindableParamNames` / `reservedTableSlotNames` components: reservation set-wide where admission
checks bindability, exclusion everywhere a name is read or printed as a binding target, carried by
which component a consumer receives rather than by a roster. F2 is answered by the shape rule now
naming parameter count, static-ness, return type and the `throws` clause, with a reason for
demanding `throws` agreement while it is inert. F3 is answered by the mixed set's trade-off landing
in the how-to section and in acceptance. F5 is answered by the R854 out-of-scope bullet, and R854
exists in Backlog carrying the modelling decision. Round 3's and round 4's non-blocking notes are
all taken up: the carrier for set-wide slot types is now a sealed per-slot outcome on
`ParamSource.Table` with an argument for the grain, the shape-disagreement discriminant can express
static-ness and arity rather than positions only, the `findUniqueMethod` / `LifterMethodResolver`
false-positive argument and the stale `pickMethod` citation are in the Tests honesty note,
`validateWhereFilterParamTables` is named as the second anchor provenance, and the admission test's
determinism is stated as a consequence of the agreed-shape value.

The decision holds and I re-derived the load-bearing claim independently in both emission paths.
`ConditionGlueRenderer.buildGlueMethod` types the glue's `table` parameter from
`row.table().tableClass()`; `authoredCall` emits `$T.$L($L)` over the bare alias local and the
binding locals, with no reference to any declared parameter type; `PathFragments.emitTwoArgMethodCall`
passes two bare aliases; `ArgCallEmitter`'s `ParamSource.Table` arm throws on a null
`tableExpression` rather than emitting. I also checked the model side of the inertness claim, which
the deliverable states for `typeName`/`javaType` but not for `name`: every remaining reader of
`MethodRef#params()` discriminates on `ParamSource` before reading a component
(`GraphitronSchemaValidator` on `SessionHandle`, `ConditionResolver.rewrapForNested` passing
non-`Arg` params through untouched, `ServiceMethodCallWalker` skipping), so a table slot's `name`
is inert too and the claim is sound as written. Bullet 5's per-participant reachability checks out
precisely: `classifyArgument` resolves an argument `@reference(path:)` through
`ctx.parsePath(arg, name, rt.tableName(), null)` under `lowerParticipantFilters`' loop, with the
null declared target the plan predicts for a filter-path site.

One finding, and it is terrain no previous round could have seen: it landed on trunk eight minutes
after the round-4 revision was committed.

**F6. A third producer of the path-step routing fact is now on trunk, and its design rationale
rests on the behaviour this item removes.** R847 (shipped at `b6b0ca1`) added
`intent_condition_method_route` and `intent_condition_method_route_defect` to `graphitron-model`'s
store. They answer over the census the same question `BuildContext.resolveConditionJoinTarget`
answers over live reflection, at the same coordinate and over the same population: R847's arm is
scoped to captured elements where `class_name IS NOT NULL AND key_ref IS NULL AND table_ref IS NULL`,
which is exactly the filter-path site where no declared target answers the question and resolution
falls to the method signature. The route view's own comment states the agreement as load-bearing:
"the generator's resolver reads it exactly this way at a filter site: parameter 0 denotes the
departure and parameter 1 the arrival". Its wildcard refusal is described the same way, as "the
resolver's own refusal on a filter path".

The collision is specifically about overloads, which is this item's whole subject. R847's item
states why the relation carries no return-type guard: "`pickMethod` rejects by name-ambiguity alone,
so filtering overloads by return type here would make the store *route* a chain the generator
refuses as ambiguous; overload multiplicity surfaces where extra hop rows always surface, in the hop
and target views' `targets` and `candidates`". That justification is a statement about the
generator's behaviour at this coordinate, and this item removes it. After admission the generator
does not refuse by name ambiguity here; it admits the set and then, per the path-step deliverable,
either resolves the agreed target or rejects through the unresolved-target path when the admitted
slots disagree. An agreeing set is fine on both sides (R847 already handles it: "two overloads
landing on one table still resolve, the scope arm demanding `targets = 1`"). A disagreeing set is
where the two part company: the store reports two candidate routes and leaves the chain to narrow
them, while the generator now rejects the pair as an author error. Neither side is wrong for its own
purpose, but nothing records that they have diverged, and the defect view is documented as a closed
five-verdict vocabulary that is "total over the unrouted population", so whether a rejected-because-
disagreeing pair belongs in that population at all is a question this item creates and does not
answer.

This is not a build break. The route relations are pinned by seeded tests
(`ConditionMethodRouteTest`, `ConditionMethodRouteDefectTest`) that assert what the SQL returns
given rows, and `ConditionMembershipShadowTest` gates the condition *fold* against
`ConditionCommands`, not the route against the resolver. So nothing fails; the two modules simply
end up asserting different things about the same coordinate, with the store's prose claiming an
agreement it no longer has.

What would satisfy it is a clause, decided the way F5 was decided for the LSP: either the store
relations are in scope here and the path-step deliverable says which, or they belong to an item
that owns reconciling the census-side route with post-admission resolver semantics, named in Out of
scope with the same same-producer / different-producer discriminator the item already uses for R854.
The path-step deliverable currently enumerates its consumers as exactly two,
`resolveConditionJoinTarget` and `validateConditionParamTables`; whichever way the clause goes, that
enumeration wants the third one visible, because an incomplete consumer roster at this coordinate is
the failure mode rounds 2 through 4 spent three passes replacing with structure. R847 reached Done during this
session, so the two could not be settled together and the store relations this touches are shipped
code. Resolved by the round-5 revision taking the reconciliation into scope rather than deferring
it, per the item owner's direction; see the census-side deliverable.

Gate: question 2. Question 1 passes without qualification, and I could state the consumer-facing
outcome from the body alone. The decision, the admission deliverable, the `AmbiguousMethod`
deliverable, the documentation deliverable and the test plan are all unaffected and need no rework;
F6 is one clause in the path-step deliverable and its matching Out-of-scope line.

Non-blocking, no revision required:

- `ConditionFilter`'s javadoc states "The first parameter always has `ParamSource.Table` as its
  source", and `CallParam`'s and `WhereFilter`'s say the same in their own words, but
  `reflectTableMethod` accepts a `Table`-assignable parameter at any position (`foundTable` is set
  anywhere in the loop) while `authoredCall` unconditionally emits the table alias first and the
  bindings after. So `cond(String name, Table<?> table)` classifies and emits a transposed call
  today. Entirely pre-existing and independent of admission, but the agreed-shape value records
  table-slot positions and its constructor is the natural place such an invariant would become
  unconstructable, so it is worth knowing about while this code is open. Backlog material rather
  than scope for this item.
- Acceptance covers the rejecting half of the path-step set-awareness (a set disagreeing on the
  target rejects) but not the passing half (an agreeing set classifies as the single method does).
  The Tests deliverable carries both. Cosmetic asymmetry only.
- The `condition.adoc` deliverable defers "whether the route-split rejection itself lifts under
  per-participant overloads" to R676, which has since shipped, so that question now has no owner.
  The instruction to the implementer is unaffected: reconcile the "one method cannot mean both
  tables" wording, leave the rejection alone.

Two broken pointers corrected in this commit, no design prose touched: R676 has shipped and its
item file is gone from `roadmap/`, so both references to it now say so.

Status stays Spec.

### Round 6, Spec → Ready, revisions requested (session `01N57zenZX5hF4QAcGRk6pzx`, 2026-08-27)

Rounds 2 through 5 stay closed and I re-derived rather than trusting the previous rounds. The
load-bearing emission claim holds in both paths: `ConditionGlueRenderer.buildGlueMethod` types the
glue's `table` parameter from the coordinate (`row.table().tableClass()` reaching it as
`jooqTableClass`), `PathFragments.emitTwoArgMethodCall` passes bare aliases, `ParamSource.Table` is
an empty record, and `resolveConditionJoinTarget` performs exactly the `typeName()` decode the
admission deliverable proposes to replace, with `validateConditionParamTables` citing it
("Same predicate as `resolveConditionJoinTarget`"). `reflectTableMethod` has the four callers the
plan names. Every documentation coordinate reads as described: the three `Table<?>`-invariant
statements in `add-custom-conditions.adoc`, the four concretely-typed prose signatures that already
contradict them, `condition.adoc`'s "one method cannot mean both tables" rung and its
`iRegelverksamling(Regelverksamling rs, …)` example, `global-id.adoc`'s
`[#multitable-filter-inputs]` shape claim, and `polymorphic-types.adoc` carrying no filter material.
Every test home, fixture and census relation exists as named, and `jvm_method` carries exactly
`return_type`, `declared_return_type` and `returns_condition`, with no static flag and no exception
list, so the out-of-scope reason for the shape half is real (see F9 for its limit). Question 1
passes: I could state the consumer-facing outcome from the body alone.

Three findings, all inside the census-side deliverable the round-5 revision added. The decision, the
admission deliverable, the `AmbiguousMethod` deliverable, the documentation deliverable and the
pipeline, compilation and execution tests are unaffected and need no rework.

**F7. The mixed wildcard-and-concrete set does not read as the wildcard case on the census side, and
the arrival guard as written cannot make it.** The path-step deliverable rules that a slot mixing a
wildcard declaration with concrete ones is the wildcard arm, so the build rejects such a set at a
filter site. The census deliverable claims the store agrees, and the acceptance criterion and the
model-tier test bullet both pin it ("a mixed wildcard-and-concrete set reads as the wildcard case on
both sides"). It does not, and the mechanism the bullet cites (verdict ordering) is not the one that
decides the case.

`intent_condition_method_route`'s `arrival` CTE joins `sql_table t ON t.class_fqn =
tr.referenced_class` at `position = 1, type_path = ''`. A `Table<?>` slot's `referenced_class` is
`org.jooq.Table` (the same string the defect view's `WILDCARD_TARGET_PARAMETER` arm matches on, and
what `SeededStore.seedConditionMethod`'s javadoc records as the class the census keeps at the root of
it), which no generated table is, so a wildcard declaration contributes no arrival row at all. Take
`cond(Customer, Table<?>)` beside `cond(Customer, Address)`: one arrival row, one distinct
`sql_table`, so the new guard ("arrival rows naming more than one distinct `sql_table`") does not
fire, the pair routes `customer->address`, and it therefore never enters the defect view, whose
population is `named` minus the pairs a route row exists for. No verdict is reached for it, so no
ordering decides anything. The build rejects; the store routes. On the one case the test bullet
identifies as "the case where the two rules could most easily be spelled differently in the two
modules", they are.

The ordering argument does hold for a different population: a three-declaration set that both
carries a wildcard and disagrees on arrival is suppressed by the guard, does reach the defect view,
and the wildcard arm (an `EXISTS` over any position-1 naming `org.jooq.Table`) fires ahead of the new
verdict. So the bullet is right about precedence and wrong about which sets reach it.

What would satisfy: either widen the suppression so a pair any of whose position-1 slots names
`org.jooq.Table` routes nothing, which is one more `NOT EXISTS` on the same CTE and makes the
wildcard verdict genuinely what both sides say; or drop the both-sides claim and record the
divergence as the store's own reading (the census answers what a signature declares, so it routes
the concrete arm where the build refuses to choose). Either is a clause, and acceptance plus the test
bullet follow whichever.

Resolved by the round-6 revision taking the first resolution, generalised. The suppression is
widened, but phrased over the declarations rather than over the arrival rows, because the wildcard
case is not the only one the narrow test cannot see: a declaration whose position-1 slot names any
class no table is generated as contributes no arrival row either, so `cond(Customer, Widget)` beside
`cond(Customer, Address)` is the same divergence one class name over. The rule is now that every
position-1 parameter the pair declares must resolve to the route's own arrival table. Acceptance and
the test bullet follow it, and the mixed set reads as the wildcard case on both sides because the
existing `WILDCARD_TARGET_PARAMETER` arm is already set-wide, not because of verdict ordering; the
finding is right that ordering decides nothing for a pair that never enters the population.

**F8. Two shipped pins invert, and the deliverable's blast radius does not name them.** The
deliverable says "Only the disagreeing set moves" and "nothing else in the view moves", and the test
bullet reads as additive: `ConditionMethodRouteTest` "gains the disagreeing set … asserting no route,
beside its existing agreeing-set case". Both halves are the wrong way round against the tree.

- `ConditionMethodRouteTest.twoOverloadsLandingOnTwoTablesAreTwoRoutes` already seeds exactly the
  disagreeing set and asserts `containsExactly("bridge customer->address", "bridge
  film_actor->actor")`, under the section heading "Overloads and the guard that is deliberately
  absent" and a javadoc stating the pre-admission rule. So the disagreeing case is a shipped pin to
  invert, prose and heading included, not a case to add. And there is no existing agreeing-set case:
  no case in that class seeds two declarations landing on one table. The agreeing set is the one that
  needs writing, and it is what would pin the outer-`UNION` collapse the deliverable rests on.
- `ArgumentReferenceStepTargetTest.twoOverloadsOfOneConditionAreTwoTargetsAtOnePosition` seeds the
  same disagreeing set through the argument-site hop view and asserts `film->actor` and
  `film->language` with `targets = 2` and `candidates = 2`, under a javadoc reading "Overload
  multiplicity lands in the arities and nowhere else". Suppression empties that chain, so a second
  model-tier class and a downstream relation's stated rationale move too. The test bullet names only
  the two `ConditionMethodRoute*` classes.
- `ConditionMethodRouteTest.aMethodTheCensusSaysReturnsNoConditionRoutesAnyway`'s javadoc repeats the
  DDL's no-return-type-guard rationale in the same words the stale-rationale bullet retires ("a chain
  the generator refuses as ambiguous"). That bullet scopes the rewrite to the two views' comments.

What would satisfy: name the two inverting pins and the third test's prose, and state the direction,
so the implementer reads this as flipping a shipped assertion about a shipped relation plus adding
the agreeing case, rather than as three new cases beside cases that already say the opposite. This is
the roster failure mode round 5 flagged at this coordinate, one module over.

Resolved by the round-6 revision, and verified against the tree first rather than taken on trust:
`twoOverloadsLandingOnTwoTablesAreTwoRoutes` does seed the disagreeing set and assert both routes,
`ConditionMethodRouteTest` carries no case seeding two declarations that arrive at one table,
`twoOverloadsOfOneConditionAreTwoTargetsAtOnePosition` does assert `film->actor` / `film->language`
at `targets = 2` and `candidates = 2`, and `aMethodTheCensusSaysReturnsNoConditionRoutesAnyway`'s
javadoc does repeat the DDL rationale. The census deliverable gains a blast-radius bullet naming both
inverting pins with their direction, the stale-rationale bullet extends to that third javadoc, and
the test bullet is rewritten as inversions plus additions rather than as three new cases. The
agreeing set is now called out as the case to write, since it is what pins the collapse the fold
rests on.

**F9. Arity and positional type identity *are* expressible over the census; only static-ness and
`throws` are not.** The out-of-scope bullet and the deliverable's closing paragraph both attribute
the whole in-scope/out-of-scope split to capture. `jvm_method_parameter` carries `position`,
`parameter_name`, `parameter_type` and `declared_parameter_type`, and
`jvm_method_parameter_type_ref` carries `referenced_class` per position, so arity agreement and
per-position type and name identity are all expressible as the store stands. The conjunction is
indeed not, which is what the sentence literally claims, but the gap the bullet goes on to describe
("a set disagreeing on shape rather than on arrival still routing in the store while the build
rejects it") is in practice the arity case, expressible with one `NOT EXISTS` over
`jvm_method_parameter`, and arity is the disagreement the admission deliverable itself calls "the
common overload case `AmbiguousMethod` exists for". So the boundary is partly a judgement call. Not a
redesign, and the scope may stay exactly where it is; the reason should say which part is capture and
which part is choice, since the revision leans on capture to justify the line.

Resolved by the round-6 revision. The scope stays where it is and the reason is split: capture for
static-ness and `throws` (`jvm_method` carries neither column), choice for arity and per-position
type and name identity, which are expressible today. The reason for the choice is grain rather than
cost: every guard the deliverable adds is a statement about a declared arrival, which is the question
this relation is keyed to answer, while whether a name denotes one call shape is asked at all four
`@condition` coordinates and is better stated whole, once, at the class-and-method grain than half of
it here. Both the closing paragraph and the Out-of-scope bullet now say so.

Non-blocking, no revision required:

- "The guard sits on the existing `arrival` CTE, whose key is already the pair" understates the CTE:
  its key is the pair plus `descriptor` plus the three arrival-table columns, which is precisely why
  an overload set is several rows there. The guard is a grouping over `arrival` by the pair; an
  implementer correlating a `NOT EXISTS` on the CTE's own key would write a no-op.
- "an agreeing set needs nothing: it resolves to one route today and keeps doing so" is true of
  arrival agreement but not of the item's headline shape. The reporter's per-participant set differs
  on the *departure* slot, so it is several route rows, one per departing table, which is the
  `from_table` column's documented reading (a candidacy the chain narrows) and needs no change.
  Worth a word, because the sentence invites a reader to expect one row for the case the item is
  about.

Both taken up by the round-6 revision, in the census deliverable's opening paragraph: the CTE's full
key is stated with the no-op warning, and the guard is placed over the pair rather than over that
key; and the departure-slot multiplicity is named as the item's own headline shape, unaffected by
anything the deliverable adds.

Gate: question 2, and confined to one deliverable. Status stays Spec.

### Round 8, In Review → Done, rework requested (session `01GMrrdoiVpD3Gu5scCzJU2g`, 2026-08-28)

The mechanism is delivered and I could not fault it. `mvn install -Plocal-db` is green on the
rebased tree. Everything below is one finding against one acceptance bullet; nothing else in the
delivery needs to move.

What I verified rather than took on trust. Admission folds the declarations into
`AgreedConditionShape` whose constructor cannot be reached without arity, static-ness, return-type
and `throws` agreement, and no `java.lang.reflect.Method` survives past it, so representative
invariance is unconstructable as the deliverable promised. `pickMethod` keeps its zero/one/many
contract byte for byte for `@service`, `@externalField` and the session hook, with
`candidateMethods` extracted as the shared name filter so the zero-match rejection cannot drift.
The two name components are disjoint and reach exactly the consumers the deliverable named:
`checkConditionOverrideTargets` takes both, `inferBindingsByType`'s reflective form takes
`bindableParamNames` alone, and the single-method inference repair falls out of that split with its
own pipeline pin. `ParamSource.Table.TableSlot` carries the three arms the Implementation notes
disclose, both path-step consumers read the decided fact instead of re-decoding a type name, and
`resolveConditionJoinTarget`'s pre-existing behaviour is preserved arm for arm including the scalar
second parameter now routed through the shared fall-through. `AmbiguousMethod` carries rendered
signatures plus a typed `Ambiguity` discriminant that `message()` switches on, with `lspCode()`
unchanged.

Completeness evidence, checked against what the spec named for itself rather than against the
build. The census `NOT EXISTS` is a rule over declarations and not a test over arrivals: I traced
it and a wildcard or primitive slot is suppressed precisely because it contributes no arrival row,
which is the reason round 6 established. Both shipped pins invert as directed and the three
genuinely new route cases exist, the agreeing set among them. `TARGET_DISAGREEMENT_ACROSS_OVERLOADS`
sits after the wildcard arm, so the mixed set reads as `WILDCARD_TARGET_PARAMETER` on both
relations, asserted on both as the spec asked. The dispatch proof is real: two concrete declarations
on `MultiTableConditionFixtures` naming their slots `customer` and `ansatt`, so the case also
exercises the table-slot-name divergence the rule deliberately admits, and
`occupantsByTypedNamePrefix` returns Mary and Mike, which an implicit equality could not. The mixed
set compiles in `graphitron-sakila-example`. All four documentation coordinates are reconciled,
including the null-probe failure mode, the `DSL.noCondition()` escape and the mixed set's trade-off
stated in the same breath. No code-string assertions on generated method bodies anywhere in the
delivered tests; the one message-substring assertion is at the path-step coordinate, which has no
typed arm to assert by the spec's own out-of-scope note, and it follows three pre-existing
assertions in the same file. Item declares no retired vocabulary. No roadmap-internal markers in the
`docs/manual/` prose.

#### F1. Three sentences in the two census views still count the pre-admission vocabulary

The census deliverable said the stale rationale goes with the fold, "in the DDL and in the test
prose that repeats it", and the acceptance bullet says neither view carries a sentence asserting the
pre-admission rule. The three sentences the deliverable enumerated by name were rewritten. Three
arithmetic sentences that the sixth verdict falsifies were not:

- `intent_condition_method_route_defect.verdict` opens "which refusal, in a closed vocabulary of
  five" and then enumerates six. Its closing "over five tests" was correctly updated in the same
  comment, which is what makes the opening read as an oversight rather than a different reading.
- `intent_condition_method_route_defect`'s view comment says "any of seven things at once, four …
  two … and the sixth the walk's honest 'not reached'". Four plus two makes the walk's silence the
  seventh; the leading count was updated and the ordinal was not.
- `intent_condition_method_route`'s view comment ends "its vocabulary spans both kinds of reason:
  three of the resolver's own typed author errors and two silences the census contributes". Four
  now, as the defect view's own comment and `fact-model.adoc` both say.

This is not phrasing. Both comments render into the published schema reference
(`architecture/reference/schema/intent.adoc`), so the authoritative statement of the vocabulary's
size now contradicts its own enumeration, and a reader cannot tell whether the sixth verdict is real
or a leftover. It reads as drift rather than as a judgement because the same delta updated exactly
this arithmetic in `fact-model.adoc` (six to seven, three to four) while leaving the DDL copy the
architecture doc's prose is derived from. Under the item's own standard, a comment asserting a rule
that no longer holds is a defect in the model rather than documentation drift; a comment asserting a
vocabulary size that no longer holds is the same defect.

To satisfy: correct the three counts, and confirm no fourth copy survives (`fact-model.adoc` and the
`method` column comment are already right).

#### F2, non-blocking, but fix it in the same pass

Acceptance still reads "rejects with the typed `AmbiguousMethod` at the argument-, field-, and
input-field coordinates", carving out only the path step. The Implementation notes record that the
argument-level coordinate does not surface it, that this is pre-existing and reproduces with a
nonexistent method name, and that it was filed as `multitable-arg-condition-rejection-dropped`. That
disclosure is the right call and the Backlog item exists; the Acceptance bullet just was not brought
along, so the contract now contradicts its own implementation note. Narrow the bullet to the two
coordinates the pair actually pins.

Gate: question 1, on one acceptance bullet, and the fix is three counts plus one bullet. Question 2
passes as delivered. Status back to Ready.
