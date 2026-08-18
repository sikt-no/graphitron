---
id: R704
title: "@routine without @table on its return type implies the routine result record"
status: Backlog
bucket: feature
priority: 2
theme: routine
depends-on: []
created: 2026-08-18
last-updated: 2026-08-18
---

# @routine without @table on its return type implies the routine result record

A field carrying `@routine` today must return a type that carries `@table`, and that `@table` must
name the routine's own result table. When it does not, the build reports:

```
Field 'Query.mineTilganger': @routine requires a @table-annotated return type
```

Field-reported as wrong. The `@table`-annotated return should stay legal, but it should not be the
only spelling: a `@routine` return type with no `@table` has an implied binding already, namely the
jOOQ record of the routine's own result. Graphitron can map the return type's fields against that
record and launch further queries from it.

## Vocabulary

* **Table-valued function**: a database function declared `RETURNS TABLE(...)` (or `SETOF`), so
  calling it yields rows rather than one value. jOOQ models one as a first-class catalog
  `Table<R>` tagged `TableOptions.function()`, with a generated record class for its result row.
  `@routine` accepts only this kind today; procedures and scalar functions are deferred elsewhere.
* **Result table**: the catalog `Table<R>` jOOQ generates for such a function. It has columns and a
  record type, but no primary key and no foreign keys.
* **Result record**: the jOOQ record of that result table, that is, one row of the function's
  output.

## What the author has to write today

The routine's result table is a real catalog table as far as `@table` is concerned, so the shipped
workaround is to name the routine on the type as well as on the field:

```graphql
type Tilgang @table(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr") {
    organisasjonskode: Int
    rollekode: String
}

type Query {
    mineTilganger(env: String!, serviceId: String!, feideId: String!): [Tilgang!]!
        @routine(
            name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr"
            argMapping: "pEnv: env, pServiceId: serviceId, pFeideId: feideId"
        )
}
```

That is the shape the sakila example fixture uses (`Tilgang`, `ActorFilm`). Three problems with it:

1. The routine name is repeated, once on the field and once on the type, and the two must agree or
   the terminus rule rejects the field. The second statement carries no information the first does
   not.
2. `@table` on a function result reads as a claim the type is a stored table, which it is not. It
   also silently makes the type usable as a plain root table read (`Query.tilgang: Tilgang` with no
   `@routine`), which would emit a `SELECT` against a function that has required arguments.
3. The type is welded to one routine. Two routines that return the same row shape cannot share a
   GraphQL type.

The wanted spelling drops the type annotation and lets the field supply the binding:

```graphql
type Tilgang {
    organisasjonskode: Int
    rollekode: String
}
```

## Where the rule lives

`RoutineDirectiveResolver.resolve` resolves the field's return type and rejects anything that is not
a `ReturnTypeRef.TableBoundReturnType`. Every `@routine` seat except one funnels through it:
the root chain (`FieldBuilder.classifyRootRoutineChain`), the Mutation write chain, and the child
chain. The exception is the Mutation payload carrier, which resolves the routine node alone through
`resolveCarrierNode` and leaves the return-shape demand to its own classifier. The chain-level
terminus rule (`FieldBuilder.routineChainVerdict`) then requires the chain's last node to be that
same `@table`.

So the rejection is correct for one case and over-broad for the rest. When the field carries
`@reference` hops, the chain lands on a catalog table and the return genuinely must name it. When
the routine result *is* the terminus (the single-node chain, at root or as a correlated child), no
second statement of the table is needed and the demand is pure ceremony.

## Why the implied binding is cheap

The routine node already resolves its own result table:
`JooqCatalog.resolveTableValuedFunction` returns a `RoutineResolution.Resolved` carrying a
`TableRef` for it, and `RoutineDirectiveResolver` threads that through as `resultTable`. The fact
the return type needs is therefore in hand at the moment of the rejection.

The consumer side is in place too. A `GraphitronType.JooqTableRecordType` with a non-null `table`
and a null `fqClassName` already means exactly "the runtime source is a projected row of this
table, and no reflected backing class exists". Under such a parent:

* scalar fields resolve to typed `Tables.X.COL` reads (`FieldBuilder.resolveColumnOnJooqTableRecord`),
* object fields with a `@table`-bound return resolve to record-parent table reads, which is the
  "launch a new query" half: a DataLoader keyed off the parent record's columns.

There is also precedent for minting that type from the producing edge rather than from the type's
own directives: `TypeBuilder.carrierVerdict`'s `CarrierBinding.TableBacked` arm mints exactly this
stand-in for a DML payload carrier, and `GraphitronSchemaBuilder` registers it at the edge that
produces it.

## Design fork for Spec

Two ways to spell the implied binding, and they differ in more than plumbing.

**A. Mint the return type as table-backed.** Classify the un-annotated type as if it carried
`@table(name: <routine name>)`. Nothing downstream changes: the field stays a `QueryTableField` with
a `TableBoundReturnType`, the terminus rule passes trivially, no emitter moves. Cheapest by a wide
margin. The cost is that the binding is a property of the *type*, so it inherits problems 2 and 3
above: the type remains globally table-backed and reusable from a non-routine seat, and it still
cannot be shared between two routines.

**B. Mint the return type as a result record.** Register the type as
`JooqTableRecordType(name, location, null, resultTable)` at the producing edge, the way the carrier
verdict already does. The binding is then scoped to the edge that produces it, so a non-routine
field returning the same type does not silently become a table read. The cost is that the routine
field's return is no longer a `TableBoundReturnType`, so the routine leaves the `QueryTableField` /
`RoutineResolution.Chain` seat that carries it today and needs a landing of its own, plus the
emitter arm to match.

Option B is what the report describes and is the better end state. Whether it is the first slice, or
whether A ships first behind the same author-visible spelling, is the Spec's call. Note that both
options are single-binding per type as written, so the shared-type case (problem 3) needs an
explicit answer either way: under B the natural one is that the binding lives on the edge and two
producing edges are simply two bindings, which A cannot express at all.

## Scope boundary

Only the single-node chain is in scope, that is, `@routine` with no `@reference` hop, at root and at
a correlated child position. A chain that hops on from the routine result lands on a catalog table,
and the existing rule that the return must name that table is correct and stays. The rejection
message should survive for that case and say so, rather than stating the demand unconditionally.

## Open questions for Spec

* Does the implied binding apply when the type carries no directives at all, or does it need an
  opt-in marker to keep it distinguishable from an unclassified type reached by mistake? The
  directiveless-nesting look-ahead in `TypeBuilder` is the nearest precedent for deciding this
  without a marker.
* What happens when the same un-annotated type is returned by two `@routine` fields naming
  different routines? Reject on conflict, or bind per edge?
* Does the type stay legal as a plain `@table` return as well, so existing schemas keep compiling
  unchanged? Assume yes; confirm no validator starts flagging the now-redundant `@table`.
* Do the sakila example fixtures (`Tilgang`, `ActorFilm`) migrate to the implied spelling, or does
  one of each stay to cover both forms?

