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
