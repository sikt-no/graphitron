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
and no shape of nullability along the path ships a runtime NPE. Reported as
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
  declared nullability of the leaf or its ancestors. Uniform output at a shared emit site is worth
  more than sparing an `ID!` leaf under an all-non-null path a check graphql-java already makes dead;
  the alternative needs a "path can be absent" derivation over every segment and is one more place to
  get the arithmetic wrong.
* **Hoist the guarded read into the prelude.** The emitter already declares the decoded record as a
  statement so a developer can breakpoint the decode. The column read joins it as a second declared
  local, and the consumer splices only the local's name. The development principles' "statement form
  over expression tricks" rule is why: the argument list stays free of conditionals, the guard is one
  breakpointable line beside the decode it guards, and the local is effectively final so it survives
  inside a lambda when a list-shaped segment lifts the read into a stream.
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

1. `read` registers a second declared local, keyed by leaf path plus column so two columns projected
   off one record get two locals, typed by the column's `CatalogColumn.javaTypeName` (already carried
   on the `KeyProjection` command's `column`), and initialised by the null check:

   ```java
   CustomerRecord keyInputCustomerId = decodeCustomerRecord(argInputCustomerId(env.getArgument("input")));
   Integer keyInputCustomerIdCustomerId = keyInputCustomerId == null ? null : keyInputCustomerId.get(Tables.CUSTOMER.CUSTOMER_ID);
   ```

   `read` then returns the bare local name. `declarations()` keeps emitting in first-use order, so
   the column local always follows the record local it reads.
2. Local naming: the existing `key<Path>` record local plus the column's Java field name in camel
   case, so the two locals read as a pair. Two projections of the same record column at one
   coordinate share the local, exactly as two reads of one record share the record local today.
3. No consumer changes. `RoutineCallEmitter` (routine reads and the Mutation write path through
   `RoutineWriteFetcherRenderer`) and `ConditionGlueRenderer` already splice whatever `read` returns;
   a bare name is a valid expression at every splice site a `get(...)` was. The nullability of the
   leaf and of its ancestors is not consulted anywhere, by the second decision above.
4. The routine parameter type check the projection already performs (an `Integer` column into a
   `String` parameter is a build error) is unchanged; the local's type is the column's, which is the
   type that check already agreed with the parameter.

## Tests

* **Emission** (`ArgmappingKeyProjectionEmissionPipelineTest`, `graphitron` pipeline tier): the
  existing routine and `@condition` cases assert the hoisted column local in the prelude and a bare
  name at the splice, and assert that no `.get(Tables.` read remains inside a `Routines.<m>(...)`
  argument list or a condition binding. This pins the shape so a later refactor cannot reintroduce
  the inline read unnoticed.
* **Execution** (`graphitron-sakila-example`, beside `RoutineFieldExecutionTest`): a new
  NULL-tolerant table-valued function in `graphitron-sakila-db/src/main/resources/init.sql`,
  `films_for_actor_or_all(p_actor_id INTEGER, p_min_length INTEGER)`, whose body reads
  `(p_actor_id IS NULL OR fa.actor_id = p_actor_id)`; the name states the NULL contract so no
  existing fixture changes meaning. Three example-schema fields over it, each with its own case:
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
decides what absence means. The nodeId page keeps pointing at the routine section.

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
