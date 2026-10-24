---
id: R1099
title: "Inputs navigate through output fields, and argMapping names its source"
status: Spec
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-10-24
last-updated: 2026-10-24
---

# Inputs navigate through output fields, and argMapping names its source

## Goal

A filter input that reads a related table names the output fields that already reach it, instead of restating the route with an `@reference` path of its own, and every `argMapping` right-hand side reads from a named source. The output types are where a schema maps GraphQL onto the database; inputs and mappings reuse that mapping rather than spelling SQL routes a second time. On FS-plattform's sis subgraph, 233 of the 260 input `@reference` paths that resolve (90%) restate a route its output fields already walk, the most repeated one 39 times.

Today, from the sakila example schema, `Query.filmsByCastNames` returns `[Film!]!` and its input repeats the route `Film.actors` already declares:

```graphql
type Film @table(name: "film") {
    actors(actor_id: [Int!] @lookupKey): [Actor!]! @reference(path: [
        {key: "film_actor_film_id_fkey"},
        {key: "film_actor_actor_id_fkey"}
    ])
}
input FilmCastNameInput {
    firstName: String! @field(name: "first_name") @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
}
```

With this item, the input names the output fields, and the route is the one `Film.actors` declares:

```graphql
input FilmCastNameInput {
    firstName: String! @field(name: "$target.actors.firstName")
}
```

And a child `@routine` reading a column of the row it is applied to says so, where today a bare name may be either an argument or a column:

```graphql
type Actor @table(name: "actor") {
    films(minLength: Int!): [ActorFilm!]
        @routine(name: "films_for_actor", argMapping: "pActorId: $source.actor_id, pMinLength: minLength")
}
```

## Design

### Two anchors

An input hooks into two anchors, both inherited from the field it is used on.

| Anchor | What it is | Navigates | Cannot |
| --- | --- | --- | --- |
| `$target` | the rows the field returns | forward through the target type's output fields | go back to the source |
| `$source` | the row the field hangs off, or the row a path step departs from | forward along the field's own path, junction steps included | reach tables the path does not pass |

Navigation names schema things, never tables: a field name is unique within its type, where a table can occur twice in one path. The one SQL name inside a navigation is a node id's key column, below.

An input type is reused across fields, so its navigation is checked per field that uses it, not once per input type.

### argMapping

A right-hand side is a source followed by names inside it. A name never means two things.

- **Arguments are the default source.** A bare name is an argument of the field, or a field inside an argument's input type. With exactly one argument, naming it is optional: at `rentFilm(input:)` both `input.inventoryId` and `inventoryId` resolve.
- **`$source.<column>`** reads a column of the row the directive's step departs from: the parent's row for a `@routine` first in its path, the table the preceding `@reference` reached otherwise. A bare name never binds a column.
- **`$session`** stays as it is (the per-caller handle, `@service` only). `$context`, replacing `contextArguments`, is a later item.
- **A node id unpacks to its key columns.** A node is defined by its keyColumns, not by fields, and not every key column is a field, so `filter.actorRef.actor_id` names a key column of the node type the id decodes to. Unpacking needs exactly one node type; an id with several candidate types can be passed whole but not unpacked.
- Input-type cycles are not supported.

### Navigation in `@field` on inputs

`@field(name:)` on an input field or argument keeps its meaning for a bare name: a column of the target. A name starting `$target.` navigates the target type's output fields:

- each segment is an output field of the type the previous one landed on; a field with a path (written or implicit) opens into its type through the walk's landing, and a nested type with no table of its own stays on the same row;
- the last segment is a scalar output field and lands on the column it binds, through its own path where it has one (`$target.languageName` on a `Film` filter reads `Film.languageName`'s column);
- a list field along the way filters with the semantics an input `@reference` through the same route has today, which the implementer pins with a fact before changing anything;
- `$source.` navigates the using field's own path the same way; sis has no use of it on inputs, so it is specified and left unbuilt until a schema needs it.

An input `@reference` path stays legal. The store resolves both spellings into the same walk, so the deprecated one owns no machinery of its own.

### Store

- **Name resolution is a keyed walk** over relations that exist: `graphitron_argument`, `graphitron_type`, `graphitron_field`, and `graphitron_node_keycolumn` past a node id. A recursive CTE takes a written mapping one name at a time, each step a probe on `(type, name)`; the string handling touches only the written mappings (54 on sis).
- **`$target` navigation joins the output fields' walk** (`graphitron_field_path`), chained field by field. The filter's join is the steps those fields already have.
- **Defects at the writing site:** a name resolving to nothing; a navigation through a field whose own path does not resolve; unpacking a node id with several candidates; `$source` where no row stands (a root field).
- **Ledger.** It adds the walk views and the defects, and no stored relation.

| Dissolves | Read by today |
| --- | --- |
| `graphitron_argmapping_candidate` and its stage `ArgMappingCandidates` (13,574 rows on sis for 54 mappings) | `graphitron_argmapping_match` |
| `graphitron_argument_reachable_input` | `InputRecordPlanner` |
| `intent_argmapping_key_column_candidate`, `intent_resolved_node_key_projection`, `intent_argmapping_projection_defect` | `ResolvedKeyProjections`, `ArgmappingProjectionDefects`, `NodeIdDecodeCoverageFacts` |
| `ROUTINE_PARAMETER_AMBIGUOUS` and the two-sided lookup in `graphitron_field_path_parameter_binding` | the entry defect rule, the parameter rule |
| `graphitron_input_field_reference_step_target` and `graphitron_argument_reference_step_target` with their keyed and keyless children, once input `@reference` resolves into the walk | counted reader by reader at pickup |

### Evidence

sis (FS-plattform `origin/main` c162d48c1c) writes 282 `@reference` paths on input fields and arguments; in a Graphitron 10 capture, 260 resolve, using 128 distinct routes. Compared by table sequence with what the target type's output fields walk (written paths, implicit foreign-key paths and nested types, chained up to three fields):

| How the input route relates to the target's output fields | Uses |
| --- | --- |
| Output fields walk the same tables | 231 |
| A prefix of an output-field walk | 2 |
| Output fields reach the table another way, mostly role tables carrying a direct key the outputs reach one hop further | 22 |
| Not reached (fødselsnummer lookups, the "meld foretatt" term) | 5 |

The comparison is by tables, not keys: where two keys reach the same table (a start term and an end term), the converted filter must name the output field for the right one, which a key-level check confirms per route. The two prefix cases filter on UNDERVISNINGSENHET from `EmneSoknad`, which has no field returning it; adding that field makes them ordinary navigation.

## Implementation

1. **`$source` replaces bare column names in `argMapping`.** Breaking for child `@routine` mappings, used only in sakila and Graphitron's tests; the commit carries its changelog line. `ROUTINE_PARAMETER_AMBIGUOUS` goes; a bare name that is no argument is `ROUTINE_PARAMETER_UNBOUND` as today. `RoutineDirectiveResolver` and the parameter binding read the source from the sigil.
2. **argMapping resolves by the keyed walk.** `graphitron_argmapping_match` and the generator's `ArgBindingMap.of` read the walk; the candidate table, its stage and the `intent_` node-key chain go.
3. **`$target` navigation in `@field` on inputs, and input `@reference` resolved into the same walk.** The input-side step-target families go as their readers move.
4. **Editor completion** offers the names valid at the cursor: arguments, then fields of the type stood in, then key columns past a node id, and the sources' members after a sigil.

Each step lands green on its own; 1 and 2 are independent of 3.

## User documentation (first-client check)

For `routine.adoc`, "Where a parameter takes its value":

> Every IN parameter takes its value from the name its `argMapping` entry writes, or from its own name where no entry names it. A bare name is an argument of the field, or a field inside one. `$source.<column>` is a column of the row the routine is applied to: the parent's row, or the table the preceding `@reference` reached. When the field has a single argument, you may leave its name out of the path.

For `field.adoc`, "Filtering through the output types":

> On an input field, `@field(name: "$target.actors.firstName")` filters on what the target type's `actors` field reaches, then on `Actor.firstName`'s column. The route is the one `Film.actors` declares, so you write it once, on the output type. A bare name is still a column of the target.

## Tests

- Facts: argMapping resolution through arguments, single-argument shorthand, nested input fields, `$source`, a node id unpacked to key columns, and each defect; `$target` navigation through a list field, a nested type and a scalar with its own path.
- Sakila: `FilmCastNameInput` converted to `$target.actors.firstName`, with an execution test returning the same rows as the `@reference` spelling; the routine fixtures converted to `$source.actor_id`.
- sis: the measurement above rerun on the capture after step 3, with the converted spelling producing the same walk as the input `@reference` it replaces.

## Retired vocabulary

`columnMapping` is already gone. This item retires `ROUTINE_PARAMETER_AMBIGUOUS`, `graphitron_argmapping_candidate`, `ArgMappingCandidates`, `graphitron_argument_reachable_input`, `intent_argmapping_key_column_candidate`, `intent_resolved_node_key_projection`, `intent_argmapping_projection_defect`, and the `*_reference_step_target` relations for inputs and arguments.

## Other solutions we've considered

- **A stored input-type hierarchy** (input types and node types as subtypes, members with `opens_into`): the store already holds every type and field under one key, so it would add four relations to say what exists.
- **Navigating by table names:** a table can occur twice in a path; field names cannot.
- **A bare name that may be an argument or a column, with a defect when it is both:** the rule that landed with the `columnMapping` removal; a new column could make a working mapping ambiguous.
- **Named path steps as a vocabulary:** reaches tables no output field exposes, but sis needs it for 5 of 260 paths, which keep `@reference`.
- **Out of scope, found in the same survey:** filter operators beyond equality with a null policy, JSON members as catalog keys and columns, and write support (fixed and default values, UPSERT, node-id key unification). Each is its own item.
