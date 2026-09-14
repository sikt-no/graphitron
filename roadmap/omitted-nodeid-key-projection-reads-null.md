---
id: R948
title: "An omitted nullable @nodeId in a key projection reads as null, not as an NPE"
status: Spec
bucket: bug
priority: 2
theme: nodeid
depends-on: []
created: 2026-09-14
last-updated: 2026-09-14
---

# An omitted nullable @nodeId in a key projection reads as null, not as an NPE

## Goal

A nullable `@nodeId` input field that an `argMapping` opens (a *key projection*: the generated code
decodes the opaque node id the client sent into a jOOQ record and reads one key column off it) no
longer crashes the request when the client omits the field. Today the emitted SDL advertises the
field as optional, but the generated fetcher reads the key column off a record the decode helper
returned as `null`, so omitting the field surfaces as a redacted internal error, on both consumers
of the projection: a `@routine` argument and a `@condition` method parameter. When this lands, an
omitted or explicitly-null `@nodeId` in a key projection reaches the routine or condition as a plain
`null`, letting the database function or the condition author define what an absent filter means,
and no shape of nullability along the path ships a runtime NPE. Only absence is affected: a
malformed node id, or one encoded for another node type, still fails the request as a client error
before the routine or condition runs. Reported as
[issue 547](https://github.com/sikt-no/graphitron/issues/547); the WHERE-clause path for the same
field shape already carries the guard, so this closes the gap between the two rails.

```graphql
input MineTilgangerFilterInput {
  miljo: ID! @nodeId(typeName: "Miljo")
  navnerom: ID @nodeId(typeName: "Tilgangsnavnerom")   # nullable: omitting it NPEs today
}

type Query {
  mineTilganger(filter: MineTilgangerFilterInput!): [Brukertilgang]
    @routine(name: "mine_tilganger",
             argMapping: "pMiljokode: filter.miljo.MILJOKODE, pNavneromskode: filter.navnerom.NAVNEROMSKODE")
}
```

The same read crashes when the nullability sits above the node id rather than on it. The generated
descent through a nested argument yields `null` at any absent level, so a nullable input object
holding a non-null `@nodeId` decodes to the same null record when the object is omitted:

```graphql
input FilmsFilter {
  actorId: ID! @nodeId(typeName: "Actor")       # non-null leaf ...
}

type Query {
  films(filter: FilmsFilter): [Film]              # ... under a nullable parent: same NPE today
    @routine(name: "films_for_actor_or_all", argMapping: "pActorId: filter.actorId.actor_id, ...")
}
```

Both shapes are one defect at one emit site, and one change closes both.

## Decisions

Settled at filing, so the plan below does not reopen them:

* **Project `null`, do not refuse.** A build-time rejection would leave no declarative way to pass an
  optional node-id filter to a routine; the two workarounds the issue lists are both worse than the
  bug. A routine whose parameter is not NULL-tolerant now fails inside the database rather than in
  Java, which is that routine's declared contract and not ours.
* **Emit the guard unconditionally.** Every key-projection read becomes null-safe regardless of the
  declared nullability of the leaf or its ancestors. The justification is the decode helper's own
  contract, stated in `RecordDecodeFragments`: the helper returns `null` for exactly one case, a wire
  value that is not a `String` (absent or wrong-shaped), throws on a well-formed id of a foreign type,
  and leaves it to the call site to decide what absence means, because only the call site knows what
  the value was for. The guard is this call site answering that one question; it cannot swallow a
  foreign-type id. Deriving "can this path be absent" over every segment would spare an `ID!` leaf
  under an all-non-null path a check graphql-java already makes dead, at the cost of one more place
  to get the segment arithmetic wrong.
* **Hoist the guarded read into the prelude.** The emitter already declares the decoded record as a
  statement so a developer can breakpoint the decode. The column read joins it as a second declared
  local, and the consumer splices only the local's name. The development principles' "statement form
  over expression tricks" rule is why: the argument list stays free of conditionals, the guard is one
  breakpointable line beside the decode it guards, and the local is effectively final so it survives
  inside a lambda when a list-shaped segment lifts the read into a stream.
* **A primitive-typed consuming parameter is a build error, whatever the path's nullability.** The
  type check the projection already performs stands aside where the consuming parameter is a
  primitive `int` (the `intent_resolved_node_key_projection` view's comment says so), and the
  compiler backstop it leans on does not catch `int p = <boxed null>`, which compiles and NPEs at the
  unboxing. Today no null reaches that unboxing; after this change one can. The refusal does not ask
  whether the path is nullable, and that is the decision rather than an omission. By the decision
  above the guard is emitted unconditionally, so the projected read is a boxed local at every path
  shape; what a primitive parameter cannot take is that local, which is a fact about the parameter's
  declared type and not about the path. Asking the narrower question would mean building the
  per-segment nullability derivation the last bullet of `## Other solutions we've considered`
  declines to pay for, to buy back a shape that is safe rather than useful: an author whose path
  cannot be absent loses nothing by declaring `Integer`. The cost is the secondary reason, though.
  The first is that a nullability-conditioned refusal would split one decision across two passes: the
  emitter would guard unconditionally and the validator would refuse conditionally, and the two would
  have to agree about "can this path be absent" with nothing binding them to. While the guard is
  unconditional, a validator that mirrors it unconditionally is the correct mirror rather than a
  coarse one. So the rule is flat, and it is the projection's existing type question finally being
  asked where it used to stand aside, not a second question about nullability standing beside it.
* **R948 stays narrow.** The follow-up comment's two adjacent observations, `@field` silently ignored
  on an input field of a spent routine argument and the hand-decode workaround's degraded error
  contract, are out of scope. The first has its own Backlog item; the second retires on its own once
  the declarative route works.

## Implementation

`ProjectedKeyReads` in `graphitron/src/main/java/no/sikt/graphitron/render/` is the single site that
turns a resolved key projection (a *key projection* is one `argMapping` binding that decodes a node id
and hands its consumer one column of the decoded key) into generated code. Its `read` method formats
`<local>.get(Tables.<T>.<COL>)` unconditionally and returns it as the expression the consumer splices.
The change:

1. `read` registers a second declared local, one per projected column of a decoded record, typed by
   the column's Java type and initialised by the null check. The type is `ColumnRef.columnClass` on
   the `KeyProjection` command's `column`, lifted through `CatalogRefs.columnType` and boxed. The
   boxing is not incidental: `CatalogRefs.decodeBindingType` maps the primitive spellings
   `Class.getName()` produces, and its `PRIMITIVE_NAMES` javadoc says it must, so the lift can yield
   a primitive `TypeName` and `int keyXCol = keyX == null ? null : ...` would not compile at a
   consumer. Boxing it is one call, and it is what turns the fourth decision's premise, that the
   projected read is a boxed local at every path shape, from an assumption into a property of the
   emitted code.

   That lift does return `null` for a ref carrying no real class name, and an earlier draft of this
   item planned a build error for one. It is not needed, which is worth stating rather than silently
   dropping. A resolved projection's `column` is assembled by `StoreNodeTables.columnOf` off
   `sql_column.binding_type`, which is `NOT NULL` and is `Field.getType()`'s fully qualified name, so
   it is always present and always a reference type; `CatalogRefs.columnType`'s own javadoc says its
   null arm exists for fixture placeholders that are never emitted; and the two populations
   `intent_resolved_node_key_projection`'s comment calls untypeable, a node type with no unambiguous
   table binding and a pinned key column the bound table does not have, are already invariant throws
   in `ResolvedKeyProjections.projectionOf`. So the hoist adds no refusal here and rewrites none of
   that comment's argument. What it does add is one law, in the generator-bug register those two
   throws already use rather than as an author-facing defect: `ProjectedKeyReads.declare` throws when
   the lift yields no type, alongside the two "Graphitron generator bug (key projection)" throws
   `leafOf` already makes. Not `KeyProjection`'s compact constructor, and the reason is worth stating
   because the earlier draft put it there. That constructor holds cross-axis laws about the row as a
   whole (`column` is one of `keyColumns`, none of the three is optional), while this is a law about
   one component's string at `ColumnRef`'s own grain, and `ColumnRef` carries placeholders across the
   walk-side tree by design. More to the point, the property that matters is "the lift yields a type",
   which needs the emit library to state, and `command` may not see it; a blankness test is the
   approximation reachable from there, and it is strictly weaker, since `decodeBindingType` also
   returns null for a non-blank name `ClassName.bestGuess` rejects. The tier that reads the type is
   the tier that can state the law.

   ```java
   CustomerRecord keyInputCustomerId = decodeCustomerRecord(argInputCustomerId(env.getArgument("input")));
   Integer keyInputCustomerIdCustomerId = keyInputCustomerId == null ? null : keyInputCustomerId.get(Tables.CUSTOMER.CUSTOMER_ID);
   ```

   `read` then returns the bare local name. `declarations()` keeps emitting in first-use order, so
   the column local always follows the record local it reads.
2. One ordered declaration sequence, not two maps. The existing `declared` map is keyed by leaf path
   as a `String`; the column local is a second population on a second axis, and fusing the two into
   one string key (or interleaving two maps by hand) would make "the column local follows the record
   local it reads" hold by the emission code's arithmetic. Key the sequence by a small two-arm sealed
   key instead, `RecordDecode(leafPath)` and `ColumnRead(leafPath, columnJavaName)`, so insertion
   order is dependency order by construction and `declarations()` keeps its one-line body. Local
   naming: the existing `key<Path>` record local plus the column's Java field name in camel case, so
   the two read as a pair; two projections of the same record column at one coordinate share the
   local, as two reads of one record share the record local today.
3. No main-source consumer changes. Every drain of `declarations()` (`RootLauncherRenderer`,
   `RoutineWriteFetcherRenderer`, `ConditionGlueRenderer`) emits into the same method body its splice
   sites (`RoutineCallEmitter`, `ConditionGlueRenderer`) read from, and each renderer takes a fresh
   sink per method, so a bare name is a valid expression at every splice site a `get(...)` was. The
   nullability of the leaf and of its ancestors is not consulted at emit time, by the second decision
   above. On the condition path the projected read lands in a binding local whose presence guard is
   `PresenceGuard.Always` (a field-level method is bound to the whole field), which is the producer
   decision behind the manual sentence below that the method is called with `null`; the pipeline test
   asserts that arm. The hoist leaves that path with an alias-only statement (`Integer keyXCol = …;
   Integer pActorId = keyXCol;`); the binding local may take the guarded initialiser directly at that
   site if the implementer prefers one decision per line, and either spelling satisfies the tests.
4. The parameter type check the projection already performs (an `Integer` column into a `String`
   parameter is a build error) keeps its predicate; the local's type is the column's, which is the
   type that check already agreed with the parameter. What changes is one of the absences it used to
   stand aside on, which is step 5.
5. The primitive-parameter refusal, which is this item's one validate-time change. It is a fact in
   the store and a verdict in the consumer, and both halves are placements this item argues rather
   than picks.

   **The fact.** `intent_argmapping_bound_parameter_type` resolves nothing for a primitive, and its
   comment is explicit that the resulting absence is four facts it distinguishes none of: the
   reference resolved no method, the method declares no parameter of that name, names were not
   compiled in, or the parameter's type names no class. The fix is not to re-derive the split at
   each reader. The view's classpath arm ends on a join to `jvm_declared_type_ref` at `type_path = ''`
   and `owner_kind = 'METHOD_PARAMETER'`; that join becomes a `LEFT JOIN`, so the name-matched
   `jvm_method_parameter` row is the membership condition and the type is a payload, and a parameter
   whose type names no class becomes a row with `java_type` NULL instead of no row. The arm's
   `SELECT DISTINCT` gains `mp.parameter_type` beside `tr.referenced_class`, and the view's column
   list gains `parameter_type` at the end; the routine arm supplies `CAST(NULL AS VARCHAR)` there.
   That second column is what the predicate below reads, and putting it here rather than reaching for
   it downstream is the whole of why the widening is worth its cost: without it the relation would
   carry the membership of a primitive parameter and not the one fact that distinguishes a primitive
   from the array and the type variable beside it, and the consumer would have to re-resolve the
   chain to find it. Two precedents, both load-bearing. The other operand of the very same equality already made this choice:
   `intent_argmapping_key_column_candidate.column_java_type` is "NULL where the catalog cannot
   answer", and its comment argues that absence "is a payload absence rather than a missing row", so
   the two sides of one comparison use opposite membership rules today. And
   `intent_node_id_decode_slot` has already reached this same `jvm_method_parameter` chain for this
   same distinction, its comment stating that "both arms therefore reach the type by outer join and
   an untypeable parameter is a row with a null type, which is what lets a consumer carry the decode
   out on arity alone and ask for a type only when it is about to refuse". Re-spelling the chain
   here would make it the third spelling of one resolution, in a family whose own comment records
   collapsing seven spellings of it into one.

   Blast radius, checked rather than assumed, and each of the three readers gates on two things
   rather than one. `intent_resolved_node_key_projection` reaches the parameter type by `LEFT JOIN`
   and keeps `p.java_type IS NULL`, so a pair that now draws a null-typed row still resolves exactly
   as it did when it drew none. `KEY_COLUMN_TYPE_MISMATCH` tests
   `pt.java_type <> ca.column_java_type`, which is NULL-false on the new rows, so no new row is
   itself a rejection. The third reader, `intent_node_id_decode_slot`, already outer-joins this
   relation and names `bp.java_type` alone, so an ordinary primitive parameter arrives there as the
   same one row carrying the same NULL it was null-extended into before. All three name their
   columns, none selects `*`, so the new column reaches no reader that did not ask for it.

   The second gate is the one the row count moves. Both of the first two readers carry
   `candidates = 1` inside the join itself, so admitting a row at a grain that already has one takes
   the count to two and stands both of them aside: the projection keeps the pair on its
   `p.java_type IS NULL` arm with the type agreement unasked, and the mismatch arm drops it. What
   could reach that grain is an overload set on the referenced method name with one untypeable arm
   beside a typed one, and the reason this widening does not open a hole there is that the set is
   already refused one tier up. `ServiceCatalog.admitConditionShape` judges every declaration of a
   `@condition` method name before a slot is minted and admits the set only where arity, staticness,
   return type, throws clause and, at every non-table position, the parameter's name and declared
   type all agree; a set disagreeing in declared type at a bound position is
   `Ambiguity.ParameterPosition`, a set disagreeing in arity is `Ambiguity.ParameterCount`, and
   `reflectTableMethod` turns either into `ReflectionError.AmbiguousMethod`. `pickMethod` refuses a
   second same-named declaration outright on the `@service` rail. So the author at such a grain
   already has a build error naming both signatures, and an admitted set agrees on the bound
   parameter's declared type across its declarations, which is one distinct row and `candidates = 1`
   whether or not the type resolves. This is the family's own move rather than a new one:
   `intent_node_id_decode_defect`'s comment states the same silence and locates the same refusal, an
   overloaded producer being "a reference the schema walk rejects by name before any of this is
   read, so declining to pick here leaves no silence".

   One residue remains and it is narrower than the overload it is mistaken for. The view's comment
   names two causes of `candidates` above one, and the walk's refusal covers only the first. The
   second is a class declared by two classpath entries whose declarations disagree: the walk reflects
   through the codegen loader and sees whichever class it resolved, while the census sees both source
   entries, so nothing upstream refuses it. Where one of those declarations types the bound parameter
   and the other does not, the count goes to two and both gates stand aside, leaving the projection
   emitting with its type unchecked and the compiler as the backstop. That is the reading the
   projection's comment already argues for an unresolved parameter type, reached by a second route,
   and it is stated here rather than left to be discovered.

   The `candidates` reading the widening owes settles itself once the column is projected, which is
   why the two are one decision rather than two. `candidates` counts rows in the grain's partition
   and the arm is a `SELECT DISTINCT`, so a column that is selected is inside that DISTINCT by
   construction: two untypeable overloads at one position (`int` and `long`) stay two rows and report
   `candidates = 2`, where a widening that projected only the NULL type would have collapsed them
   into one row claiming an unambiguous answer. Every reader requiring `candidates = 1` therefore
   stands aside on such a pair exactly as it does on an overload of two reference types, which is the
   discipline the view's comment already states and the one place a naive widening would have
   silently broken it.

   Two precisions on that count, because the population the `LEFT JOIN` admits is larger than the
   population the refusal fires on and the paragraph reads as if they were the same. The join admits
   a row for every name-matched parameter whose declared type names no class, which is primitives and
   arrays and type variables alike, so an `int[]` or a `T` inflates the count on the same terms an
   `int` does; only the predicate below narrows to the eight. And the count grows in that population
   alone, which is the bound worth stating rather than leaving the effect open-ended:
   `parameter_type` is the erased source spelling of the same declared type `java_type` names the
   root of, so wherever the type resolves the spelling is determined by it and the projected column
   splits no row that the `DISTINCT` collapses today.

   `intent_node_id_decode_slot` is the one reader that deliberately does not require one candidate,
   counting its own rows instead so that an ambiguity refuses rather than resolves; an untypeable
   parameter becomes one row there where it was one null-extended row, and an overload set of two
   becomes two where it was one, which is the direction that relation's own comment asks for and is
   stated here rather than discovered.

   Two comment edits, and no more. "Absence is therefore four facts" becomes three absences plus a
   payload NULL, which is the relation getting more precise rather than less. And `candidates`'s own
   column comment, "how many distinct types resolved for this pair", is rewritten rather than
   amended, because the widening changes both halves of what it says. What it counts is now the
   distinct type-and-spelling answers, so two untypeable overloads stay two rows; and `candidates = 1`
   stops implying that a type resolved, which is the load-bearing half. Every existing reader was
   written when those two were the same fact, and `java_type` is what now says which of them a row
   carries. Nothing about that is inferable from the one clause an amendment would have added, so the
   comment states it.

   What the routine arm must not do is put `sql_routine_parameter.binding_type` in the new column.
   That type is fully qualified where `jvm_method_parameter.parameter_type` drops the package, and
   one column spelled two ways across two arms is the exact confusion the view's comment says the
   classpath arm reaches one relation further to avoid. NULL there is the honest answer: the erased
   source spelling is a classpath fact, a routine parameter has no `jvm_method_parameter` row to read
   it from, and the refusal this column exists for cannot fire at a routine anyway.

   **The predicate.** The NULL payload is not by itself the primitive test, and this is the place the
   refusal would most easily over-fire. `jvm_declared_type_ref` has no root row for an array or a
   type variable either, which the tree states in as many words at
   `intent_condition_param_extraction.java_type`: NULL there is "the type names no class, a
   primitive, an array, or a type variable". An `int[]` or a generic `T` parameter would have drawn a
   refusal telling its author to declare `Integer`, which names the wrong fact and offers a remedy
   that does not apply. So the refusal tests the erased source form against the eight primitive
   spellings: a closed vocabulary, and the same spelling the message needs to quote. It reads it off
   the widened relation's new `parameter_type` column, which is the whole reason that column is
   projected: the consumer already joins this relation on this grain, so the operand rides the join
   it already makes and no second resolution is spelled anywhere. Reaching `jvm_method_parameter`
   from the consumer instead would mean re-spelling the classpath chain
   (`graphitron_method_reference_entry` to `store_graph_source` to `jvm_method` to the parameter row,
   matched on the parameter name), which is the third spelling `## Other solutions we've considered`
   rejects one section down, and it is rejected here for the same reason.

   Carrying the column at all is safe on the view's own terms, which is worth stating because its
   comment spends a paragraph on why the classpath arm does *not* read that column for the type.
   The objection there is to comparing it: it drops the package, so an equality against the catalog
   arm's qualified type would never match and the mismatch would read as a genuine disagreement. This
   column is compared across no census and never against `java_type`. It is tested against a closed
   vocabulary of eight literals and quoted in a message, and its own column comment says exactly that,
   so the arm's documented reason for reaching `jvm_declared_type_ref` for the *type* stands
   untouched. The precedent is one relation over: `intent_node_id_decode_slot`'s `NAMED_PARAMETER`
   arm already holds `jvm_method_parameter` and outer-joins `jvm_declared_type_ref` off it at the
   same root type path, so a parameter row in hand beside a nullable decomposition is a shape this
   family already has. Arrays and type variables keep today's stand-aside, and the item states that
   as a silence it owns rather than leaving it as a gap.

   **The verdict.** Minted in `ArgmappingProjectionDefects`, not as a sixth arm of
   `intent_argmapping_projection_defect`. The view's comment states the rule: an arm whose
   justification is a fact about the generator's own code, rather than about the schema, lives "with
   the consumer that knows the wired set rather than being asserted by a view that cannot see it".
   This refusal exists because the emitter now produces a boxed local; reverse the guard decision
   above and it evaporates, which is exactly not true of `KEY_COLUMN_TYPE_MISMATCH`, whose two
   operands are captured facts and whose verdict is Java assignability. So the verdict vocabulary
   stays closed at five and that view's comment is not rewritten. The consumer joins the widened
   relation on the grain it already holds, reads both of its type columns off that one join, and
   mints an ordinary non-deferred defect beside the ones it already mints: a row whose `java_type` is
   NULL, whose `parameter_type` is one of the eight, and whose `candidates` is 1. The message quotes
   the spelling the author wrote and names the boxed type they want instead, since the remedy is one
   word at the parameter.

   That third conjunct is the one worth arguing, because it is a qualified refusal standing beside an
   unconditional guard and so looks like the mirror the fourth decision refuses. It is not, and the
   reason is that the two conditions sit on different axes. The fourth decision is about whether the
   projected path can be absent, and the refusal asks nothing about that: it fires at every path
   shape, which is what makes it the flat mirror of a guard emitted at every path shape.
   `candidates` is about whether this pair resolved one answer or several, which is a question about
   the resolution and not about the schema, and every gating reader of this relation already requires
   one. Refusing on an ambiguous grain would mean quoting one spelling out of several and offering a
   remedy against a declaration the author may not have meant, which is the same over-firing the
   predicate above rejects for `int[]` and `T`. Standing aside there is what the two readers beside
   it do, and the silence is the one the blast-radius paragraph above names and locates: the overload
   is refused at `ServiceCatalog`, and the doubly-declared class is the residue this item owns the
   boundary of.

   Scope, cost and residue. The routine arm reads `sql_routine_parameter.binding_type`, the generated
   method's own boxed type, so a `@routine` parameter cannot present a primitive and this refusal is
   the `@condition` half's alone. No new relation stands up, so there is no owner to compute, and
   the whole store-side cost is one `JOIN` to `LEFT JOIN`, one projected column added to the arm's
   `SELECT DISTINCT`, to the `resolved` CTE's own column list and to the view's, with its comment,
   two rewritten comments elsewhere on the same view, and nothing outside it. Nothing beyond the view
   pays: `FactCaptureAgreementTest` and `DerivedReadCostTest` register this relation by name rather
   than by column list, and `SupertypeSiteReferenceTest` narrates only its join key.
   `ArgmappingProjectionDefects.READS` gains `INTENT_ARGMAPPING_BOUND_PARAMETER_TYPE` as its only new
   named root, which is what projecting the column buys: the consumer names one relation it did not
   name before rather than the four a re-resolution would have taken.
   `DetectionReadReachGateTest`'s pin does not move, that relation already sitting in this
   component's reach and the widening adding no view to it, every other relation in the chain being a
   base table the walk stops at. Four absences remain as the residue this refusal owns the boundary
   of and does not close. Three are the ones the relation folded together before: a reference
   resolving no method, a method declaring no parameter of that name, and a consumer compiled without
   `-parameters` still project a boxed null into a parameter nothing typed, so such a consumer
   declaring `int` keeps today's NPE with the compiler as the backstop it already was. The fourth is
   the doubly-declared class of the blast-radius paragraph, where the count reaches two and the
   refusal stands aside with the two gates beside it. All four leave the pair exactly where it is
   today, which is why the refusal strictly adds rejections at the grains it does reach.

## Tests

* **Emission** (`ArgmappingKeyProjectionEmissionPipelineTest`, `graphitron` pipeline tier): the
  existing routine and `@condition` cases assert the hoisted column local in the prelude and a bare
  name at the splice, and that no column read remains inside a `Routines.<m>(...)` argument list or
  a condition binding. `TypeSpecAssertions` declares itself the single home of the rendered spelling
  of this emitter's output, so the new question lives there as a named helper ("is the column read
  hoisted out of the call?") rather than as a string match in the test; `invocationTakesProjectedRead`
  asserts exactly the shape this item removes and retires with it (see Retired vocabulary). The
  primitive-parameter refusal gets its rejection case one class over, in
  `ArgmappingProjectionRejectionPipelineTest`: that is where this family's refusals already live,
  covering the unknown-key-column verdict at each of the five sites, while this class holds emission
  cases and no rejection. There is no untyped-column case, step 1 having dropped that refusal. Nor is
  there a case for the refusal's stand-aside on an ambiguous grain, and that is a deliberate absence
  rather than a gap: reaching it through this tier would mean an SDL fixture whose `@condition`
  method is an overload set, which `ServiceCatalog.admitConditionShape` refuses before the store is
  read, so the case that arrived would pin that refusal and not this one. The residue that is
  genuinely reachable, a class declared by two classpath entries, needs a second source entry on the
  test classpath and is out of proportion to what it pins.
* **Execution** (`graphitron-sakila-example`, beside `RoutineFieldExecutionTest`): a new
  NULL-tolerant table-valued function in `graphitron-sakila-db/src/main/resources/init.sql`,
  `films_for_actor_or_all(p_actor_id INTEGER, p_min_length INTEGER)`, whose body reads
  `(p_actor_id IS NULL OR fa.actor_id = p_actor_id)`. It is a near-twin of `films_for_actor`, and
  the reason not to widen the incumbent instead is where it is bound: `Actor.films` calls it at the
  correlated child position with `p_actor_id` fed from the parent row's `actor_id` column, and a
  fixture that returns every film for a null parent column would give that test a meaning it never
  asked for. The new function's name states the NULL contract the tests here depend on. Three example-schema fields over it, each with its own case:
  - nullable leaf: `filter: FilmsFilter!` holding `actorId: ID @nodeId(typeName: "Actor")`; omitting
    `actorId` returns every film at or above `minLength`, and supplying it narrows to the actor;
  - nullable ancestor: `filter: FilmsFilter` nullable holding `actorId: ID!`; omitting `filter`
    returns rows rather than an error;
  - `@condition` consumer: a field-level `@condition` whose `argMapping` projects a nullable node id
    on a table-backed field; the fixture method receives `null` and returns no condition, and the
    query returns the unfiltered rows. It goes in the conditions fixture package beside
    `InputFieldConditionFixtures` and `MultiTableConditionFixtures`, on the field-level rail rather
    than the input-field one the first of those names.
  Each case also asserts the negative that the issue observed: no error in the response, so the
  redacted internal error the `catch (Exception e)` produced is gone rather than merely reworded.

## User-facing docs

The rule lands once, in the routine page's "Binding a parameter to a node id's key column" section
(`docs/manual/reference/directives/routine.adoc`, anchor `node-id-key-projection`, which is the
spelling every xref into it uses), beside the three build errors it already lists: an
omitted or null `@nodeId` anywhere on the projected path projects `null`, and the routine parameter
receives it, so a NULL-tolerant function is the way to express an optional filter. The new build
error does not go on this page, and that is the correction worth stating: a `@routine` parameter's
type comes from the generated method and is boxed, so the refusal can never fire at a routine. What
the page does owe is one clause off its third bullet, which today lists two ways the type check
stands aside, "a routine whose call surface was not captured or a parameter declared `int` rather
than `Integer`". The first stays true and is the routine rail's own. The second never described this
rail and stops being true anywhere once step 5 lands, so it leaves the sentence and the bullet keeps
its remaining stand-aside.

The condition page's projection bullet (`condition.adoc`) gets one clause: a field-level `@condition`
bound to such a projection is called with `null` for that parameter, and the author decides what
absence means, since a field-level method is bound to the whole field. The contrast worth drawing
there is with the FK-target `@nodeId` whole-slot rail, where an absent value skips the call; that is
the rail `PresenceGuard.FieldPresent` is minted for, so the sentence should name the rail rather than
the site. That bullet already ends on "its declared Java type must be that column's", which is the
rule step 5 starts enforcing rather than a new one being introduced, so it gains the refusal as a
consequence and not as a second rule: a primitive-typed parameter cannot take a projected key read,
with no nullability qualifier, because the read is boxed at every path shape. The same bullet states
the silence beside it, that an array or type-variable parameter and a consumer compiled without
`-parameters` keep the compiler as their backstop. The nodeId page keeps pointing at the routine
section.

## Retired vocabulary

* `invocationTakesProjectedRead` in `TypeSpecAssertions`, and the spelling it pinned: a named-column
  read inside the invocation's argument list. Replaced by the hoisted-read helper above.
  `materialisationPrecedesFirstRead` and `projectedColumnReads` change meaning with it, the "first
  read" moving into the prelude, and are re-read rather than retired.
* The narration of the old spelling in two prose sites: the `KeyProjection` javadoc and the `read`
  javadoc in `ProjectedKeyReads`, each of which describes the read as `<local>.get(Tables.<T>.<COL>)`
  at the call. `ArgmappingProjectionDefects`'s `EMITTING_SITES` javadoc is deliberately not on this
  list: it says the two sites read "their column off a decoded record through `ProjectedKeyReads`",
  which survives the hoist unchanged and would give the sweep nothing to find.
* "Absence is therefore four facts and this relation distinguishes none of them" in
  `intent_argmapping_bound_parameter_type`'s view comment, and the enumeration of four that follows
  it. Three absences and a payload NULL after step 5. Beside it on the same view, "how many distinct
  types resolved for this pair" in `candidates`'s own column comment: it counts distinct declared
  spellings once step 5 projects one, which is what keeps two primitive overloads two rows.
  `intent_argmapping_projection_defect`'s "closed verdict vocabulary of five" is deliberately not on
  this list: the verdict lands in the consumer, so that vocabulary stays closed at five.

## Other solutions we've considered

* **Refuse the combination at build time.** Loud and simple, but it forecloses the reporter's use case
  entirely and the emitted SDL would still be the honest one; see Decisions.
* **Inline ternary at the splice site.** The smallest diff, but it puts a conditional inside an
  argument list and repeats the local name per read; the principle the prelude already follows says
  otherwise.
* **A per-class generic helper** `keyOrNull(record, field)` through a registry like the decode-helper
  registries. Follows the "lift into a helper" clause literally and needs no column type, but adds a
  registry and a trivially generic helper to every class that projects a key, for a one-line check
  the prelude can hold.
* **Emit the guard only where the path is nullable.** Keeps `ID!` projections byte-identical, at the
  cost of a store derivation over every segment's nullability; rejected in Decisions. An earlier
  draft of the fourth decision reintroduced that same cost on the validate-time side, which made this
  rejection argue against the item's own plan. As revised it does not: no derivation over segment
  nullability is built anywhere here.
* **A build error for an untypeable projected column.** Planned in an earlier draft, on the premise
  that the hoist turns the column's Java type from an optional fact into a required one. The premise
  does not hold on the emit path, for the reasons step 1 gives, and the law that remains is an
  invariant rather than a refusal. Taking it would have reversed an argued rule in
  `intent_resolved_node_key_projection`'s comment to buy nothing.
* **Re-spell the parameter chain as a second arm instead of widening the relation.** An earlier draft
  of step 5 repeated `intent_argmapping_bound_parameter_type`'s classpath join and turned its last
  leg into a `NOT EXISTS`, which needs no change to any existing relation. Rejected: it would be the
  third spelling of one resolution, in a family whose own comment records collapsing seven spellings
  of that join into one, and the two spellings would agree exactly until one of them changed. The
  widening costs one `JOIN` to `LEFT JOIN`, one projected column, and two clauses of a comment. The
  same rejection covers the variant that reaches `jvm_method_parameter` from the consumer rather than
  from a second view arm: it is the same re-resolution wearing Java instead of SQL, it would name
  four relations where the projected column names one, and it would have to match on the parameter
  name a second time to get there.
* **Keep the widening's new rows out of `candidates`.** Counting over typed rows only would leave
  every `candidates = 1` reader seeing exactly what it sees today, which is the whole appeal.
  Rejected, and it is the arm a later reader is most likely to re-propose. It relocates the defect
  step 5 already argues against rather than removing it: two untypeable overloads at one position
  (`int` and `long`) would report `candidates = 1` and every gating reader would act on an
  unambiguous answer that does not exist, and that grain is precisely where the new refusal fires. It
  also promotes one reader's predicate into the column, where `candidates` states rows at a grain and
  each reader decides what to require of it.
* **A second count column beside `candidates`.** Separating "typed answers" from "declared spellings"
  resembles `intent_field_reference_step_target`, which carries `targets` beside `candidates`. It is
  not that shape, and that relation's own column comment is why: the two are separate there "because
  the two arities answer different questions and genuinely differ", a step reaching one table by
  three routes. Here both counts answer one question under two definitions of an answer, and the
  second definition exists only so one reader may ignore rows another reader must see.
* **A sixth verdict in `intent_argmapping_projection_defect`.** Keeps the predicate in SQL beside
  `KEY_COLUMN_TYPE_MISMATCH`, which it resembles. Rejected on that view's own placement rule: this
  refusal exists because of how the generator emits, not because of what the schema says, and the
  view states that such arms belong with the consumer. The tell was that taking it required rewriting
  a comment that declares the vocabulary closed.

## Reviewer findings

### Round 1 (2026-09-14, Spec -> Ready, reviewer session 0179PrtvxY9DeKDUik4bbmM2)

Verdict: withhold. One blocking finding on question two. Question one passes.

The goal reads without reconstruction, and it is worth having. Today, if you declare an optional
`@nodeId` filter (nullable on the leaf, or non-null under a nullable input object) and bind it to a
routine parameter or a `@condition` method parameter with `argMapping`, omitting it costs the client
a redacted internal error even though the emitted SDL says the field is optional. After this lands
the routine or the condition method receives `null` and decides for itself what absence means, and a
malformed or foreign node id still fails as a client error. The narrow scope is right and the
"project null, do not refuse" decision is the right one: the alternative forecloses the reporter's
whole use case.

The claims about the tree check out, with one exception noted below. Verified: `ProjectedKeyReads`
is the single emit site and its `read` does format `<local>.get($T.<T>.<COL>)` unconditionally off a
`LinkedHashMap` keyed by leaf path; the three drains of `declarations()` are exactly
`RootLauncherRenderer`, `RoutineWriteFetcherRenderer` and `ConditionGlueRenderer`, and the condition
path does emit `declarations()` ahead of an alias-only binding statement, so the predicted
`Integer pActorId = keyXCol;` shape is right; `RecordDecodeFragments.decodeHelper` returns `null` on
exactly the non-`String` wire arm and routes every other failure through the mismatch throw, so the
unconditional-guard justification holds and cannot swallow a foreign-type id; `CatalogRefs.columnType`
does return `null` for a `ColumnRef` carrying no real class name, and `KeyProjection`'s compact
constructor is where the completeness law lives; `PresenceGuard.always()` is what a same-table
`ConditionFilter` gets, so the field-level `@condition` case really is called with `null`;
`films_for_actor` really is bound at the correlated child position with `pActorId` fed by
`columnMapping` from the parent row (`Actor.films`, `Actor.castFilms`, `Actor.castRecentFilms`), so
declining to widen it is correct; `TypeSpecAssertions` holds all three named helpers, and
`declarationOf` will not false-match the proposed `key<Path><Column>` local against the record local
it extends; the routine page's "Projecting a key column out of a node id" section does list three
build errors, its third bullet ending on the very sentence this item invalidates ("a parameter
declared `int` rather than `Integer`, the build lets it through and your compiler is the backstop");
and the WHERE-clause rail does already guard, in `ConditionGlueRenderer.appendGuardedAnd`, so "closes
the gap between the two rails" is accurate.

**Finding 1 (question two: architecture fit). The item introduces two new build errors and plans
neither, and the operand the primitive-parameter one needs is not on the relation the spec cites.**

The `## Implementation` section is four steps, all inside `ProjectedKeyReads`. Nothing in it covers
the validate-time half, which is two new refusals:

* the primitive-typed consuming parameter under a nullable path (fourth bullet of `## Decisions`), and
* the untyped column, refused by "the derivation that mints the row" (step 1).

Each needs work the plan does not allocate, and this repo's Ready specs do allocate it. The sibling
`stated-key-column-match-states-its-arity.md` spells its store half down to the view name, its column
list, its join predicate and which comments have to be rewritten; that is the granularity this item
gives its emitter half and withholds from its store half.

*a. The primitive fact is not where the spec looks for it.* The decision cites
`intent_resolved_node_key_projection`'s comment as saying the type check stands aside for a primitive
`int`, and it does. But the relation that holds the operand,
`intent_argmapping_bound_parameter_type`, says in its own comment why: its classpath arm reaches the
parameter's type through `jvm_declared_type_ref` at the root type path, and "that relation has no row
where the position names no class, so a primitive parameter resolves nothing here rather than
resolving `int`", with the further statement that the resulting absence "is four facts and this
relation distinguishes none of them: the reference resolved no method, the method declares no
parameter of that name, names were not compiled in, or the parameter's type names no class". So
"reject where the parameter is primitive" cannot be a join over that operand. The fact does exist, on
`jvm_method_parameter.parameter_type` (erased source-form, so `int` appears there), which is the
column that arm deliberately declined to read because it drops the package. Opening that reach, and
saying how the new refusal avoids firing on the other three absences, is a store-shape decision with
an argued precedent against it. It is the author's to make, not something an implementer should
settle mid-change.

*b. No relation states "this projected path has a nullable segment".* The facts are there:
`graphql_field.non_null` covers input fields (`graphql_field` carries `INPUT_OBJECT` parents),
`graphql_argument.non_null` covers arguments, and `graphitron_argmapping_candidate` carries
`parent_path` and `depth` for the ancestry. What does not exist is the derivation over them, and the
plan names neither where it lives, nor its shape, nor its comment. This also turns the last bullet of
`## Other solutions we've considered` on itself: "emit the guard only where the path is nullable" is
rejected there "at the cost of a store derivation over every segment's nullability", and the
primitive arm pays that cost anyway. The unconditional guard may well still be the right call, for
the emit-time reasons the second decision gives, but the stated reason for rejecting the alternative
does not survive the fourth decision and the item should say which reason it is standing on.

*c. Refusing the untyped column contradicts an argued rule, and the plan does not say where the
rewrite lands.* `intent_resolved_node_key_projection`'s comment argues at length that standing aside
on a column the catalog cannot type is deliberate, that it "strictly adds rejections and removes no
emission", and specifically that requiring the type "would additionally have contradicted the
key-column relation's own rule that a pinned name resolves without a table". Making that pair a build
error is a defensible trade for the hoisted local's type, and the plan states the trade. What it does
not state is where the verdict is minted, what the message says, or that the SQL comment arguing the
opposite has to be rewritten; `## Retired vocabulary` names three javadoc sites and no SQL comment.

*What would satisfy this finding.* An implementation entry for the validate-time half at the
granularity the emitter half already has: which relation answers "is this parameter primitive" and
how the refusal is kept off the other absences that relation folds together, what shape the
nullable-segment derivation takes and where it sits, where each new verdict enters
`intent_argmapping_projection_defect` and `ArgmappingProjectionDefects`, and which existing SQL
comments change. One narrowing that may shrink the work: the routine arm reads
`sql_routine_parameter.binding_type`, which is the generated method's boxed type, so the primitive
case can only arise at the authored-method sites, which is the `@condition` half alone.

*Non-blocking, no reply needed.*

* `## Retired vocabulary` lists the `ArgmappingProjectionDefects` javadoc among three prose sites that
  "describe the read as `<local>.get(Tables.<T>.<COL>)` at the call". It does not; its `EMITTING_SITES`
  javadoc says "both reading their column off a decoded record through `ProjectedKeyReads`", which
  stays true after the hoist. The other two sites are as described. The retirement sweep will just
  find nothing there.
* `## User-facing docs` contrasts the new condition behaviour with "the input-field `@condition` path
  where an absent value skips the call". The skip is the FK-target `@nodeId` + `@condition` whole-slot
  rail specifically (`PresenceGuard.FieldPresent` is minted only for `FkTargetConditionFilter`), and
  `INPUT_FIELD_CONDITION` is not even in `ArgmappingProjectionDefects.EMITTING_SITES`. Worth naming
  the rail rather than the site when that sentence gets written.

#### Addendum to round 1 (2026-09-14, same reviewer session)

R876's capture commits reached trunk while this review was open, so the round above was written
against the tree one step behind. Re-checked on the new head: all ten relations the round names still
exist, and the three comments it quotes survive verbatim through that item's 485-line change to the
model DDL. The finding stands unchanged.

What R876 adds is the rule that decides where the missing half goes, which is why this is an addendum
rather than a question back. Five consequences, all of them constraints on the revision rather than
new findings:

* **Placement is computed, not chosen.** R876's target architecture states it as a computation: a
  view's owner is the latest, in gatherer dependency order, of the owners of what it reads, and
  `meta_relation.owner_name` must equal the computed one. A nullable-segment derivation reading only
  `graphitron_argmapping_candidate`, `graphql_field` and `graphql_argument` computes to `sdl`. Written
  as another `intent_` view in the graphitron family it would be a tenth instance of the misplacement
  R876 enumerates nine of and is in progress to remove. The revision should compute the owner and say
  what it got, the same way that item does.
* **No `_live` pairing.** That convention is retired: "a rule its owner decides to store is stored
  under its own name."
* **The ancestry walk is a recursive walk over `parent_path`.** R876 deleted
  `graphitron_argument_path_segment` and the per-segment resolution with it, on the finding that the
  candidate tree states once, with a parent link, what that relation denormalized. A plan written
  against a flat segment relation would not compile on this head.
* **Nullability is an entry-side fact, and the candidate relation already carries its sibling.**
  `graphitron_argmapping_candidate.is_list` is there for a stated reason that applies word for word to
  `non_null`: "a consumer binding a parameter to this candidate needs the arity as much as the type,
  and reading it here is what keeps such a consumer from joining back to the SDL relation the writer
  already read." Whether the column joins it or the fact stays a join is the author's call. The point
  is that R876's rule is what decides it, and the item should decide it rather than leave it.
* **There is a build gate on the answer.** `ArgmappingProjectionDefects.READS` and
  `DetectionReadReachGateTest` pin the detection pass's read-cadence reach by equality, and that gate
  caught its own subject the first time it ran. Any relation the two new refusals read enters that
  reach and fails the build until the pin is edited in the commit that prices it. Naming the relation
  and its cost is part of what this half of the plan owes.

One note on the cost side, since it moved. R876's rule leaves the shape of the refusal itself
untouched: a defect is the anti-join between what the author wrote and what resolved, which is what
`intent_argmapping_projection_defect` already is, so both new refusals belong there rather than as a
column inside the resolution. What did move is the price of the nullability half, upward: it is a new
relation, placed by a rule this item has to run, inside the gate above. A refusal that needs no new
relation is now cheaper than it looked when the fourth decision was written. Which arm to take is
still the author's.

### Round 2 (2026-09-14, Spec -> Ready, reviewer session 01VDtRrZdq4JSLrTDbYED7Hk)

Verdict: withhold. Round 1's finding stands, re-verified independently on this head. Question one
passes, for the reasons round 1 gives; I reached the same reading of the goal without working back
from the phase list, so nothing is owed there.

The plan body is byte-identical to the revision round 1 reviewed: `git diff dac8e60 HEAD` on this
file adds the `## Reviewer findings` section and nothing else, and trunk head is the round 1
addendum itself, so no revision has been attempted yet. This round therefore re-states no findings
the author has already answered; it records that I checked round 1's claims rather than inheriting
them, and adds the one pointer it did not give.

**Finding 1 (question two) is unaddressed and correct.** Re-checked, each against the tree as it
stands:

* `ProjectedKeyReads.read` formats `$L.get($T.$L.$L)` unconditionally off a `LinkedHashMap` keyed by
  leaf path, and `declare` emits the record local. The emitter half of the plan describes the site
  accurately.
* `intent_argmapping_bound_parameter_type`'s view comment says verbatim that its
  `jvm_declared_type_ref` arm "has no row where the position names no class, so a primitive parameter
  resolves nothing here", and that the resulting "absence is therefore four facts and this relation
  distinguishes none of them". The operand the primitive refusal needs is not on the relation the
  fourth decision cites. `jvm_method_parameter.parameter_type` exists and is `NOT NULL`, so the fact
  is reachable, but only by opening the reach that arm argued against.
* `graphitron_argmapping_candidate` carries `parent_path`, `depth` and `is_list`, and carries **no**
  `non_null`. `graphitron_argument_path_segment` is absent from the DDL, so the ancestry answer is a
  recursive walk over `parent_path` or a new column, and either is a store-shape decision. No
  relation today states "this projected path has a nullable segment".
* `intent_resolved_node_key_projection`'s comment does argue that standing aside on "a column the
  catalog cannot type" is deliberate, that requiring it "would additionally have contradicted the
  key-column relation's own rule that a pinned name resolves without a table", and that the gate as
  written "strictly adds rejections and removes no emission". Step 1 reverses that trade in a
  subordinate clause and `## Retired vocabulary` names no SQL comment.
* `ArgmappingProjectionDefects.READS` pins exactly `INTENT_ARGMAPPING_PROJECTION_DEFECT`,
  `INTENT_RESOLVED_NODE_KEY_PROJECTION` and `GRAPHITRON_ARGMAPPING_ENTRY` through
  `NodeIdMessages.readsWith`, and `DetectionReadReachGateTest` exists to hold it by equality. Either
  new refusal grows that set and fails the build until the pin is edited, which is a cost the plan
  does not price.
* Round 1's offered narrowing holds: `sql_routine_parameter.binding_type` is documented as the type
  "as the generated method takes it", the example being "a java.lang.Integer", so the routine arm
  cannot present a primitive and the primitive refusal is the `@condition` half's alone.

What would satisfy the finding is unchanged from round 1, so I do not restate it.

**One pointer round 1 did not give, on the Tests section's half of the same gap.** `## Tests` places
the two new rejection cases "at the same tier" as the emission cases. That tier is right, but the
class is not: `ArgmappingKeyProjectionEmissionPipelineTest` holds twelve emission cases and no
rejection case at all, while `ArgmappingProjectionRejectionPipelineTest` beside it is the existing
home for exactly this family, already covering the unknown-key-column refusal at each of the five
sites. Naming that class when the validate-time half gets written costs nothing and keeps the two
kinds of case where the tier already sorts them.

*Non-blocking, no reply needed.*

* Round 1's first non-blocking note is confirmed: `ArgmappingProjectionDefects`'s `EMITTING_SITES`
  javadoc reads "both reading their column off a decoded record through `ProjectedKeyReads`", which
  survives the hoist unchanged, so listing it under `## Retired vocabulary` gives the sweep nothing
  to find. Its second is confirmed too: `INPUT_FIELD_CONDITION` is a `Site` enum constant but is not
  in `EMITTING_SITES`, which is `ROUTINE`, `FIELD_CONDITION` and `ARGUMENT_CONDITION`.
* All three `TypeSpecAssertions` helpers `## Retired vocabulary` names resolve, as does the private
  `declarationOf` the retirement reasoning leans on.

### Round 3 (2026-09-14, Spec -> Ready, reviewer session 01Xi4kaHzdoQh5HpYfNFTzKu)

Verdict: withhold. Round 1's blocking finding on question two stands, re-verified independently on
this head. Question one passes.

The plan body is still unrevised. `git diff dac8e60 HEAD` on this file adds rounds 1 and 2 and
nothing else, and trunk head is the round 2 review commit itself, so three rounds have now reviewed
one text. I restate no finding the author has answered, because none has been answered; what follows
is what I checked for myself plus two things the earlier rounds did not give.

On question one I read the goal without working back from the phase list and landed where both
earlier rounds did, so nothing is owed there. A `@nodeId` the emitted SDL advertises as optional,
either nullable on the leaf or non-null under a nullable input object, bound through `argMapping` to
a `@routine` parameter or a `@condition` method parameter, today costs the client a redacted internal
error when they omit it. After this lands the routine or the method receives `null` and decides what
absence means, and a malformed or foreign-type id still fails as a client error before either runs.

Re-verified with FQN-aware grep rather than inherited: `ProjectedKeyReads.read` formats
`$L.get($T.$L.$L)` unconditionally off a `LinkedHashMap<String, Declared>` keyed by leaf path, and
`declare` emits the record local, so the emitter half describes the site accurately;
`intent_argmapping_bound_parameter_type`'s comment still says verbatim that "a primitive parameter
resolves nothing here rather than resolving int" and that the resulting "absence is therefore four
facts and this relation distinguishes none of them"; `graphitron_argmapping_candidate` carries
`parent_path`, `depth` and `is_list` and no `non_null`, and `graphitron_argument_path_segment` is
absent from the whole tree, so no relation states "this projected path has a nullable segment";
`intent_resolved_node_key_projection`'s comment does name "a column the catalog cannot type" among
the pairs it deliberately lets project, and does claim the gate "strictly adds rejections and removes
no emission"; `ArgmappingProjectionDefects.READS` pins exactly three relations and
`DetectionReadReachGateTest` holds it by equality; `ArgmappingProjectionRejectionPipelineTest` holds
thirteen cases including the unknown-key-column refusal at five sites, while
`ArgmappingKeyProjectionEmissionPipelineTest` holds twelve cases and no rejection, confirming round
2's pointer. The finding is unchanged and so is what would satisfy it, so I do not restate it.

**One thing that shrinks the cost side, since the fourth decision's arm is still open.** The new
primitive refusal would break nothing in the tree. The `@condition` fixtures are three classes,
`InputFieldConditionFixtures`, `MultiTableConditionFixtures` and `ReferencePathConditionFixtures`,
and none of them declares a primitive-typed parameter at all, so no existing case migrates and no
existing SDL fixture has to be rewritten. Combined with round 1's narrowing (the routine arm reads
`sql_routine_parameter.binding_type`, which is boxed, so the primitive case is the `@condition`
half's alone) the whole price of that refusal is the store-shape decision and the `READS` pin, with
no regression surface behind it. That is worth knowing before choosing between the nullable-segment
arm and the cheaper blanket one the round 1 addendum raised; it does not decide which, which stays
the author's.

*Non-blocking, no reply needed.*

* Step 1 puts the new completeness law on `KeyProjection`'s compact constructor, which today
  validates `column` for null and then `keyColumns.contains(column)` by record equality. Because
  `column` is required to be one of `keyColumns`, "refuses a blank `columnClass`" is ambiguous
  between binding `column` alone and binding every entry of the list. Only `column`'s type is needed
  at emit, so either is defensible; the revision should just say which, since the constructor is
  where the spec chose to put the law.
* The `@condition` execution case places its fixture method "beside `InputFieldConditionFixtures`"
  while describing a field-level `@condition`. Same-package placement is fine, but that class is the
  home of the rail round 1 and 2 both flagged as the contrasting one, so naming it as the neighbour
  reads as naming it as the site.

### Round 4 (2026-09-14, Spec -> Ready, reviewer session 01N7auVMScKDoe3YEhRFDQZy)

Verdict: withhold. One blocking finding on question two, and it is a narrower one than the three
rounds before it: the revision answers rounds 1b and 1c outright and leaves 1a one step short of
landing. Question one passes.

This is the first round with a revised plan body in front of it, so what follows is new work rather
than a re-check. Rounds 1c and 1b are closed and closed well. The untyped-column refusal is gone,
and step 1 does not merely drop it: it argues from `sql_column.binding_type` being `NOT NULL` and
`Field.getType()`'s own spelling, which I confirmed at the DDL and the column comment, so the
premise that the hoist makes the column type a required fact genuinely does not hold on the emit
path, no SQL comment needs rewriting, and what replaces the refusal is an invariant throw in the
tier that reads the type. The nullable-segment derivation is gone with the blanket refusal, which
also retires the round 1 addendum's whole constraint set: R876's owner computation, the `_live`
pairing, the recursive `parent_path` walk and the `non_null` column question all only bit on a
relation this revision no longer stands up. Round 2's test-class pointer and both of round 3's
non-blocking notes are taken.

Question one passes and I read it without working back from the phase list. Declare a `@nodeId`
filter your clients may leave out, either nullable on the leaf or non-null under a nullable input
object, bind it through `argMapping` to a `@routine` parameter or a `@condition` method parameter,
and today omitting it costs the client a redacted internal error while the emitted SDL says the
field was optional all along. After this lands the routine or the method is handed a plain `null`
and decides what an absent filter means, and a malformed or foreign-type id still fails as a client
error before either runs.

Verified on this head, by FQN-aware grep and by reading the sites: `ProjectedKeyReads.read` formats
`$L.get($T.$L.$L)` unconditionally off a `LinkedHashMap<String, Declared>` keyed by leaf path, and
`declare` emits the record local, so the hoist lands where the item says; `leafOf` already makes
exactly two "Graphitron generator bug (key projection)" throws, so the new law joins a register that
exists; `CatalogRefs.columnType(ColumnRef)` is the lift, its null arm is javadoc'd as the fixture
placeholder case, `decodeBindingType` does return a primitive `TypeName` out of `PRIMITIVE_NAMES`
whose own javadoc says it must, and does return null for a non-blank name `ClassName.bestGuess`
rejects, so the blankness test really is strictly weaker and `TypeName.box()` really is the one call
the hoist needs; `KeyProjection`'s compact constructor holds cross-axis laws about the row, as the
item's reason for not putting the new one there describes; `ResolvedKeyProjections.projectionOf`
does carry the two untypeable populations as invariant throws; `PresenceGuard.Always` is what a
field-level `@condition` gets and `FieldPresent` is the FK-target rail the docs section is told to
name; `films_for_actor(p_actor_id INTEGER, p_min_length INTEGER)` is bound at the correlated child
position with `p_actor_id` fed by `columnMapping` from the parent row, so the proposed twin is a
twin and declining to widen the incumbent is right; `ArgmappingProjectionRejectionPipelineTest`
holds 13 cases and `ArgmappingKeyProjectionEmissionPipelineTest` 12 with no rejection among them;
and `routine.adoc`'s third projection bullet ends verbatim on "a routine whose call surface was not
captured or a parameter declared `int` rather than `Integer`, the build lets it through and your
compiler is the backstop", while `condition.adoc`'s projection bullet ends verbatim on "its declared
Java type must be that column's". The docs plan is accurate down to the clause.

Step 5's fact half checks out too, including the part that is easiest to get wrong. The classpath
arm does end on `JOIN jvm_declared_type_ref tr ... AND tr.type_path = '' AND tr.owner_kind =
'METHOD_PARAMETER'`, so the `LEFT JOIN` is the one-word change described. The blast radius is as
stated: `intent_resolved_node_key_projection` keeps `p.java_type IS NULL` as the first arm of its
`WHERE`, so a pair that now draws a NULL-typed row resolves on the same arm it used to reach by
drawing none, and `KEY_COLUMN_TYPE_MISMATCH`'s `pt.java_type <> ca.column_java_type` is NULL-false
on the new rows and neither gains nor loses a rejection. The predicate half's reasoning is right as
well, and it is the part rounds 1 through 3 could not have written: `jvm_declared_type_ref` has no
root row for an array or a type variable either, exactly as `intent_condition_param_extraction`'s
`java_type` comment says in as many words, so the NULL payload alone would have fired on `int[]` and
on `T` with a message naming the wrong fact. Testing the erased source spelling is the correct
answer to that.

**Finding 1 (question two: architecture fit). The widened relation carries the membership but not
the operand the predicate names, and the item states no route from one to the other.**

`intent_argmapping_bound_parameter_type`'s column list is `(graph_name, site, use_site, position,
param_name, java_type, candidates)`. It does not project `jvm_method_parameter.parameter_type`, and
the widening as step 5 states it does not add it: the sentence enumerating what the widening owes
names a stated reading of `candidates` for a NULL-typed row and one sentence of the view's comment,
and nothing else. So "the consumer joins the widened relation on the grain it already holds" leaves
the consumer holding membership plus a NULL, with the column its own predicate tests one relation
away and no stated way to reach it. Both available routes carry a consequence this item argues
elsewhere and does not price here:

* *Project the column onto the view.* One column, and not a free one. Three relations read this view
  (`intent_resolved_node_key_projection`, `intent_argmapping_projection_defect` and
  `intent_node_id_decode_slot`), the routine arm of the `UNION ALL` has to put something in that
  position for a parameter that has no `jvm_method_parameter` row at all, and whether the column
  joins that arm's `SELECT DISTINCT` decides what `candidates` counts. That last one is not a
  separate question from the one the item already says the widening owes, which is why leaving it
  open is what makes this blocking rather than editorial. Outside the `DISTINCT`, two primitive
  overloads at one position (`int` and `long`) both project NULL, collapse to one row and report
  `candidates = 1`, which is an ambiguity the view's own comment says it must not resolve and its
  readers rely on `candidates` to see. Inside it, they stay two rows and the NULL-typed population
  the widening adds is counted on a different rule than the paragraph implies. One decision, two
  readings, and the item makes neither.
* *Reach `jvm_method_parameter` from the consumer.* That means re-spelling the classpath resolution
  the view already spells, `graphitron_method_reference_entry` to `store_graph_source` to
  `jvm_method` to `jvm_method_parameter` matched on `parameter_name`, which is precisely the third
  spelling `## Other solutions we've considered` rejects in its own words one section earlier. It
  also adds roots to `ArgmappingProjectionDefects.READS` that step 5 does not name.

The pin claim survives either route, so that part of the accounting is sound and I checked it rather
than assuming: `DetectionReadReachGateTest`'s `REACH` already lists
`intent_argmapping_bound_parameter_type` under `ArgmappingProjectionDefects`, and its walk stops at
every table, so neither naming the view as a root nor reading base tables beneath it moves the
number.

*What would satisfy this finding.* One stated answer to where the consumer's predicate gets its
operand. If it is a column on the view, say so where the widening's cost is stated, say what the
routine arm puts in that position, and fold the `candidates` reading the item already owes into that
same decision rather than leaving the two to be discovered as one. If it is the consumer's own
reach, name the relations it joins and reconcile that with the third-spelling rejection, since a
reader who meets the two paragraphs in order will read them as contradicting.

*Non-blocking, no reply needed.*

* `## User-facing docs` calls the routine page's section "Projecting a key column out of a node id".
  That is the xref link text `condition.adoc`, `service.adoc` and `nodeId.adoc` all use, but the
  heading itself (`routine.adoc:146`, anchor `node-id-key-projection`) reads "Binding a parameter to
  a node id's key column". The anchor is unambiguous so nothing is owed; worth knowing when the edit
  is written.
* `routine.adoc:300`'s summary bullet carries the same stand-aside a second time, as "resolvable
  only where the routine's call surface was captured and the parameter is a reference type". On the
  routine page that clause stays true after step 5, a routine parameter always being boxed, so
  naming only the projection section's third bullet is probably right; the second site just exists.

### Round 5 (2026-09-14, Spec -> Ready, reviewer session 01139f1ob6dmqW6erUcXHm79)

Verdict: withhold. One blocking finding on question two, narrower again than round 4's. Revision 3
answers round 4 outright: the operand is on the relation now, and the route from membership to
predicate is stated. What is left is one population the `candidates` argument does not reach, and it
is the population where an existing reader changes behaviour. Question one passes.

Read without working back from the phase list: declare a `@nodeId` filter your clients may leave
out, either nullable on the leaf or non-null under a nullable input object, bind it through
`argMapping` to a `@routine` parameter or a `@condition` method parameter, and today omitting it
costs the client a redacted internal error while the emitted SDL advertised the field as optional.
After this lands the routine or the method is handed a plain `null` and decides what an absent
filter means, and a malformed or foreign-type id still fails as a client error before either runs.

Revision 3's new material checks out, at the DDL and at the code. The classpath arm does end on
`JOIN jvm_declared_type_ref tr ... AND tr.type_path = '' AND tr.owner_kind = 'METHOD_PARAMETER'`, so
the `LEFT JOIN` is the one-word change. `jvm_method_parameter.parameter_type` is `NOT NULL` and is
documented as the erased source form with the package dropped, so `int` appears there literally and
the eight-spelling test reads a closed vocabulary; `jvm_declared_type_ref` equally has no root row
for an array or a type variable, which `intent_condition_param_extraction.java_type`'s comment states
in as many words, and an erased `int[]` or `Object` is not one of the eight, so the predicate stands
aside on both exactly as the item claims. Both precedents are real and say what the item quotes:
`intent_argmapping_key_column_candidate.column_java_type` argues its NULL is "a payload absence
rather than a missing row", and `intent_node_id_decode_slot`'s comment carries the outer-join
sentence verbatim. That relation's MAPPED_PARAMETER arm does outer-join this view and name
`bp.java_type` alone, and its own `candidates` counts rows over `(graph_name, use_site)`, so an
ordinary primitive parameter arrives as the same one row carrying the same NULL it was null-extended
into, which is the item's claim exactly. The three readers all name their columns and none selects
`*`. The pin claim holds: `DetectionReadReachGateTest`'s `REACH` already lists
`intent_argmapping_bound_parameter_type` under `ArgmappingProjectionDefects`. Step 1 holds too:
`sql_column.binding_type` is `NOT NULL` and is `Field.getType()`'s spelling, `CatalogRefs.columnType`
does return a primitive `TypeName` out of `PRIMITIVE_NAMES` and also returns null for a non-blank
name `ClassName.bestGuess` rejects (so the blankness test really is strictly weaker),
`ResolvedKeyProjections.projectionOf` carries the two untypeable populations as invariant throws, and
`ProjectedKeyReads.leafOf` already makes the two "Graphitron generator bug (key projection)" throws
the new law would join. `declarationOf` still will not false-match the proposed
`key<Path><Column>` local. `films_for_actor(p_actor_id INTEGER, p_min_length INTEGER)` is bound at
the correlated child position with `p_actor_id` fed by `columnMapping`, and both doc clauses the item
quotes are verbatim.

**Finding 1 (question two: architecture fit). The widening admits a new row into a partition three
readers count, and the item reasons about that count only where the new rows are alone in it.**

Both gating readers carry `candidates = 1` inside the join itself, not only the `java_type` test the
blast-radius paragraph examines: `intent_resolved_node_key_projection` joins
`... AND p.position = n.position AND p.candidates = 1`, and the `KEY_COLUMN_TYPE_MISMATCH` arm joins
`... AND pt.position = ca.position AND pt.candidates = 1`. Take a grain where the classpath resolves
an overload set with one primitive arm and one reference arm sharing the parameter name, which the
arm's join admits because it matches `jvm_method` on name alone and `jvm_method_parameter` on
`parameter_name`. Today the primitive draws no row, `candidates = 1`, and both readers act. After the
widening it draws its own row, `candidates = 2`, and both stand aside. Two consequences, neither
stated:

* *The mismatch arm loses a rejection it makes today.* The blast-radius sentence checks that
  `pt.java_type <> ca.column_java_type` is NULL-false on the new rows, which is right and is not what
  drops the row; `pt.candidates = 1` is. A projection whose types genuinely disagree at such a grain
  goes from a build error naming both types to a compile error in code the author did not write,
  which is the direction `intent_resolved_node_key_projection`'s comment says the gate does not move
  in ("strictly adds rejections and removes no emission").
* *The new refusal fires there, with the remedy that does not apply.* The predicate as step 5 states
  it is a row whose `java_type` is NULL and whose `parameter_type` is one of the eight, with no
  `candidates` qualifier, and such a row exists at that grain. The projection also survives, the
  `p.java_type IS NULL` arm keeping it, so there is a projection to refuse. The author is told to
  declare `Integer` when they have already declared an `Integer` overload. That is the same mistake
  the predicate section is built to avoid, one population over: the item is careful that `int[]` and
  `T` must not draw a refusal "telling its author to declare `Integer`, which names the wrong fact and
  offers a remedy that does not apply", and an overload set with one primitive arm draws exactly that.

The `candidates` paragraph reasons about the new population counting *itself*, and on that it is
correct: two primitive overloads at one position do stay two rows under the `DISTINCT` once the
column is projected, and every reader requiring one candidate stands aside on them exactly as it does
on two reference types, where today it stands aside on their absence instead. What it does not reach
is the new population being counted *beside a row that resolves today*, which is the only shape in
which behaviour changes for a pair the tree already serves.

*What would satisfy this finding.* One stated decision about the mixed grain, in the paragraph where
the widening's cost is stated. Whether the new refusal requires `candidates = 1`, and what an
ambiguous grain gets instead if anything; and whether the mismatch arm standing aside there is
accepted as the same ambiguity discipline the item already invokes, or avoided. Either answer is
defensible and either is a clause of SQL rather than a redesign. What is not tenable is the
blast-radius paragraph as written, which asserts that neither reader changes.

> **Author response (revision 4).** Taken, and the finding turned out to point one tier further than
> it reached. The refusal now requires `candidates = 1`, and step 5 argues why that qualifier is not
> the conditional mirror the fourth decision refuses: it sits on the resolution-ambiguity axis, not
> on the path-nullability axis the decision is about. The blast-radius paragraph is rewritten to name
> both gates rather than one, and to state what the count does. The consequence the finding draws
> from it does not survive contact with `ServiceCatalog.admitConditionShape`, which admits a
> `@condition` overload set only where every declaration agrees on arity, staticness, return type,
> throws clause and, at each non-table position, the parameter's name and declared type; the finding's
> worked shape is `Ambiguity.ParameterPosition` or `Ambiguity.ParameterCount` before the store is
> read, and `pickMethod` refuses a second same-named declaration outright on the `@service` rail. So
> the mismatch rejection is not exchanged for a compile error, and the misleading "declare `Integer`"
> message cannot be the only error at that grain. What the paragraph now owes instead is the narrower
> residue the walk cannot see: a class declared by two classpath entries whose declarations disagree,
> which is stated and located rather than absorbed. The silence is spelled in the family's own words,
> `intent_node_id_decode_defect`'s comment already making the same move for an overloaded producer.
> Two further precisions came out of the same pass: the `LEFT JOIN` admits arrays and type variables
> beside primitives, so the count paragraph under-described what inflates it; and a typed row's
> spelling is determined by its type, which bounds the growth to the untypeable population. The
> `candidates` column comment is rewritten rather than amended, because `candidates = 1` stops
> implying a resolved type and no one clause carries that. Both non-blocking notes are taken, the CTE
> column list into the cost sentence and the three unaffected tests into it as well. Arm B of the
> finding and the two-count variant are written into `## Other solutions we've considered`.

*Non-blocking, no reply needed.*

* The `resolved` CTE carries its own explicit column list
  `(graph_name, site, use_site, position, param_name, java_type)`. Step 5 names the view's column
  list and the arm's `SELECT DISTINCT` and not that one; an implementer meets it on the first
  compile, so nothing is owed, but the sentence enumerating the whole store-side cost is one item
  short.
* No cost outside the view, confirmed rather than assumed: `FactCaptureAgreementTest` and
  `DerivedReadCostTest` register this relation by name and not by column list, and
  `SupertypeSiteReferenceTest` only narrates its join key, so the widened column list reaches none of
  the three.
