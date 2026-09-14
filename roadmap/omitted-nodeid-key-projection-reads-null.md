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
* **A primitive-typed consuming parameter under a nullable path is a build error.** The type check
  the projection already performs stands aside where the consuming parameter is a primitive `int`
  (the `intent_resolved_node_key_projection` view's comment says so), and the compiler backstop it
  leans on does not catch `int p = <boxed null>`, which compiles and NPEs at the unboxing. Today no
  null reaches that unboxing; after this change one can. So the projection's defect derivation grows
  one arm beside `KEY_COLUMN_TYPE_MISMATCH`: a primitive-typed consuming parameter whose projected
  path has a nullable segment (the leaf or any input object above it) is rejected, naming the
  coordinate, the parameter and the boxed type that fixes it. A primitive under an all-non-null path
  stays legal, since graphql-java guarantees the value is present there. This is a validate-time join
  over facts the SDL strata already hold, not emit-time arithmetic, so it does not reopen the decision
  above.
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
   the `KeyProjection` command's `column`, lifted through `CatalogRefs.columnType`, and that lift
   returns `null` for a column the catalog cannot type: the store deliberately lets such a pair
   project unchecked, which today costs nothing because `record.get(field)` needs no type name. The
   hoisted local turns that optional fact into a required one, so `KeyProjection`'s compact
   constructor, where the row's completeness law already lives, refuses a blank `columnClass`, and
   the derivation that mints the row refuses the untyped column with a defect naming it rather than
   leaving a `$T` to be fed `null` at emit time.

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
4. The routine parameter type check the projection already performs (an `Integer` column into a
   `String` parameter is a build error) is unchanged; the local's type is the column's, which is the
   type that check already agreed with the parameter.

## Tests

* **Emission** (`ArgmappingKeyProjectionEmissionPipelineTest`, `graphitron` pipeline tier): the
  existing routine and `@condition` cases assert the hoisted column local in the prelude and a bare
  name at the splice, and that no column read remains inside a `Routines.<m>(...)` argument list or
  a condition binding. `TypeSpecAssertions` declares itself the single home of the rendered spelling
  of this emitter's output, so the new question lives there as a named helper ("is the column read
  hoisted out of the call?") rather than as a string match in the test; `invocationTakesProjectedRead`
  asserts exactly the shape this item removes and retires with it (see Retired vocabulary). The
  primitive-parameter defect and the untyped-column defect each get a rejection case at the same
  tier.
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
    on a table-backed field; the fixture method (beside `InputFieldConditionFixtures`) receives `null`
    and returns no condition, and the query returns the unfiltered rows.
  Each case also asserts the negative that the issue observed: no error in the response, so the
  redacted internal error the `catch (Exception e)` produced is gone rather than merely reworded.

## User-facing docs

The rule lands once, in the routine page's "Projecting a key column out of a node id" section
(`docs/manual/reference/directives/routine.adoc`), beside the three build errors it already lists: an
omitted or null `@nodeId` anywhere on the projected path projects `null`, and the routine parameter
receives it, so a NULL-tolerant function is the way to express an optional filter. The condition page's
projection bullet (`condition.adoc`) gets one clause: a field-level `@condition` bound to such a
projection is called with `null` for that parameter, unlike the input-field `@condition` path where an
absent value skips the call, since a field-level method is bound to the whole field and the author
decides what absence means. The same section states the new build error: a primitive-typed parameter
cannot take a projection whose path can be absent. The nodeId page keeps pointing at the routine
section.

## Retired vocabulary

* `invocationTakesProjectedRead` in `TypeSpecAssertions`, and the spelling it pinned: a named-column
  read inside the invocation's argument list. Replaced by the hoisted-read helper above.
  `materialisationPrecedesFirstRead` and `projectedColumnReads` change meaning with it, the "first
  read" moving into the prelude, and are re-read rather than retired.
* The narration of the old spelling in three prose sites: the `KeyProjection` javadoc, the
  `ArgmappingProjectionDefects` javadoc, and the `read` javadoc in `ProjectedKeyReads`, each of which
  describes the read as `<local>.get(Tables.<T>.<COL>)` at the call.

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
  cost of a store derivation over every segment's nullability; rejected in Decisions.

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
