---
id: R1099
title: "Inputs navigate through output fields, and argMapping names its source"
status: Spec
bucket: architecture
priority: 3
theme: classification-model
depends-on: [derived-read-cost-is-a-shape-problem]
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

- **Arguments are the default source.** A bare name is an argument of the field, or a field inside an argument's input type. With exactly one argument, naming it is optional: at `rentFilm(input:)` both `input.inventoryId` and `inventoryId` resolve. A head that names the argument is read as the argument; only a head naming no argument is read inside the single argument's type, so `input` at a field whose argument `input` has a field `input` is the argument, and its field is `input.input`.
- **`$source.<column>`** reads a column of the row the directive's step departs from: the parent's row for a `@routine` first in its path, the table the preceding `@reference` reached otherwise. A bare name never binds a column.
- **`$session`** stays as it is (the per-caller handle, `@service` only). `$context`, replacing `contextArguments`, is a later item.
- **A node id unpacks to its key columns.** A node is defined by its keyColumns, not by fields, and not every key column is a field, so `filter.actorRef.actor_id` names a key column of the node type the id decodes to. Unpacking needs exactly one node type; an id with several candidate types can be passed whole but not unpacked.
- Input-type cycles are not supported.

### Navigation in `@field` on inputs

`@field(name:)` on an input field or argument keeps its meaning for a bare name: a column of the target. A name starting `$target.` navigates the target type's output fields:

- each segment is an output field of the type the previous one landed on; a field with a path (written or implicit) opens into its type through the walk's landing, and a nested type with no table of its own stays on the same row;
- the last segment is a scalar output field and lands on the column it binds, through its own path where it has one (`$target.languageName` on a `Film` filter reads `Film.languageName`'s column);
- a list field along the way filters with the semantics an input `@reference` through the same route has today, which the implementer pins with a fact before changing anything;
- a nested input field that navigates to an object-typed output field sets `$target` for the input beneath it: `cast: ActorFilter @field(name: "$target.actors")` makes `ActorFilter` read `Actor`'s output fields, so one input type serves every target offering its names, at the top level or nested;
- where the target is an interface or a union, a navigation resolves per member type, each member's own fields and table, since each member is its own walk; a member lacking a name draws the defect, naming the member;
- `$source.` navigates the using field's own path the same way; sis has no use of it on inputs, so it is specified and left unbuilt until a schema needs it, and until then a schema writing it draws a defect at the writing site with an `UNSUPPORTED` basis, as `ROOT_REFERENCE_WITHOUT_ROUTINE` does.

Every navigation is resolved and checked per occurrence, one using field and the path of input fields beneath its argument, and a defect sits at the writing site naming the using field.

An input `@reference` path stays legal. The store resolves both spellings into the same walk, so the deprecated one owns no machinery of its own.

### Store

The model is R876's branch "Arguments and input fields are resolved as fields are, from a target
model": every path is a row of one supertype, an output field's path is its one named subtype, and an
input field's or argument's path is anonymous and walked from the target of each field that uses it.
This item builds on it rather than modelling inputs a second time.

- **A `$target` navigation is an anonymous path** whose elements name the output fields it steps
  through. The walk takes such an element by following that field's named path, one more element
  kind over the same relations, so the resolved navigation is stored walk rows at the occurrence
  grain, written by the walk's stage with its mark and sweep.
- **A resolved argMapping is stored.** `graphitron_argmapping_binding`, one row per written entry at
  the site it is written: the parameter, its source (argument path, `$source` column, `$session`),
  and what that resolves to, a field of the input type it lands in or a node type's key column.
  Owned by the anchoring stage after the walk; marked and swept by `touched_at`. It replaces
  `graphitron_argmapping_candidate`, whose 13,574 rows on sis enumerate every writable path under
  every coordinate to serve 54 written mappings.
- **Defects at the writing site:** a name resolving to nothing, a navigation through a field whose own
  path does not resolve, unpacking a node id with several candidates, `$source` where no row stands.
- **Ledger.** It adds `graphitron_argmapping_binding` and one walk element kind.

| Dissolves | Read by today |
| --- | --- |
| `graphitron_argmapping_candidate` and its stage `ArgMappingCandidates` (13,574 rows on sis for 54 mappings) | `graphitron_argmapping_match` |
| `graphitron_argument_reachable_input` | `InputRecordPlanner` |
| `intent_argmapping_key_column_candidate`, `intent_resolved_node_key_projection`, `intent_argmapping_projection_defect` | `ResolvedKeyProjections`, `ArgmappingProjectionDefects`, `NodeIdDecodeCoverageFacts` |
| `ROUTINE_PARAMETER_AMBIGUOUS` and the two-sided lookup in `graphitron_field_path_parameter_binding` | the entry defect rule, the parameter rule |
| `graphitron_input_field_reference_step_target` and `graphitron_argument_reference_step_target` with their keyed and keyless children, `intent_input_occurrence_path` and its steps: R876's branch, not this item | counted there, reader by reader |

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

1. **`$source` replaces bare column names in `argMapping`.** Shipped as "breaking: a bare argMapping name is an argument, $source.<column> binds a column", amending R876's changelog line for the `columnMapping` break.
2. **argMapping resolves by the keyed walk.** `graphitron_argmapping_match` and the generator's `ArgBindingMap.of` read the walk; the candidate table, its stage and the `intent_` node-key chain go.
3. **`$target` navigation in `@field` on inputs**, once R876's branch has input `@reference` in the walk as anonymous paths: the field element kind, the per-occurrence `$target`, and the defects above.
4. **Editor completion** offers the names valid at the cursor: arguments, then fields of the type stood in, then key columns past a node id, and the sources' members after a sigil.

Each step lands green on its own; 2 is independent of R876's branch, 3 depends on it.

## User documentation (first-client check)

For `routine.adoc`, "Where a parameter takes its value":

> Every IN parameter takes its value from the name its `argMapping` entry writes, or from its own name where no entry names it. A bare name is an argument of the field, or a field inside one. `$source.<column>` is a column of the row the routine is applied to: the parent's row, or the table the preceding `@reference` reached. When the field has a single argument, you may leave its name out of the path.

For `field.adoc`, "Filtering through the output types":

> On an input field, `@field(name: "$target.actors.firstName")` filters on what the target type's `actors` field reaches, then on `Actor.firstName`'s column. The route is the one `Film.actors` declares, so you write it once, on the output type. A bare name is still a column of the target.

## Tests

- Facts: argMapping resolution through arguments, single-argument shorthand, nested input fields, `$source`, a node id unpacked to key columns, and each defect; `$target` navigation through a list field, a nested type and a scalar with its own path.
- Sakila: `FilmCastNameInput` converted to `$target.actors.firstName`, with an execution test returning the same rows as the `@reference` spelling; the routine fixtures converted to `$source.actor_id`.
- sis: the measurement above rerun on the capture after step 3, with the converted spelling producing the same walk as the input `@reference` it replaces; a capture timing before and after steps 2 and 3, per gatherer, with rows equal where the spelling is unchanged.

## Retired vocabulary

`columnMapping` is already gone. This item retires `ROUTINE_PARAMETER_AMBIGUOUS`, `graphitron_argmapping_candidate`, `ArgMappingCandidates`, `graphitron_argument_reachable_input`, `intent_argmapping_key_column_candidate`, `intent_resolved_node_key_projection`, `intent_argmapping_projection_defect`. The `*_reference_step_target` relations for inputs and arguments retire with R876's branch.

## Other solutions we've considered

- **A stored input-type hierarchy** (input types and node types as subtypes, members with `opens_into`): the store already holds every type and field under one key, so it would add four relations to say what exists.
- **Navigating by table names:** a table can occur twice in a path; field names cannot.
- **A bare name that may be an argument or a column, with a defect when it is both:** the rule that landed with the `columnMapping` removal; a new column could make a working mapping ambiguous.
- **Named path steps as a vocabulary:** reaches tables no output field exposes, but sis needs it for 5 of 260 paths, which keep `@reference`.
- **Out of scope, found in the same survey:** filter operators beyond equality with a null policy, JSON members as catalog keys and columns, and write support (fixed and default values, UPSERT, node-id key unification). Each is its own item.

## Reviewer findings

### Round 1: Spec → Ready, findings (session_01GTcAKUooH5fFN1jLWYMGph, 2026-10-24)

The goal stands on its own and the outcome is reachable: a consumer writes an input's route once, on
the output type, and every `argMapping` name has one meaning. Every symbol the plan names exists
under that name. Findings 1 and 2 bear on the second gate question, whether the solution fits the
architecture we have, and hold the sign-off; 3 to 5 are smaller and can be answered in the same
revision.

1. **The resolutions are views, where the store anchors derived facts into tables.** The ledger says
   the item "adds the walk views and the defects, and no stored relation", with `argMapping` names
   resolved by a recursive CTE and `$target` navigation chained over `graphitron_field_path` at read
   time. The modelling discipline anchors a derived fact into a table in the gatherer's anchoring
   phase, with an owner, a grain and a mark and sweep, and R876 has measured why: the recursive
   `intent_field_path_node` view could not be pruned by the graph filter and grew with the graphs in
   a store, and on 2026-10-23 a view probed inside a rule's recursion took one rule from 46 ms to
   31.6 s on sis. Say which relations hold the resolved mapping and the resolved navigation (per
   using field, since an input type is checked per field), their grains, and the stage that writes
   them; and add a sis timing before and after to `## Tests`.

   *Response:* the store section now holds the resolutions in tables: `$target` navigation as walk rows at the occurrence grain, written by the walk's stage, and the resolved argMapping as `graphitron_argmapping_binding`, owned by the anchoring stage after the walk with its mark and sweep. Tests gain a per-gatherer sis capture timing before and after steps 2 and 3.
2. **The item overlaps R876's deferred branch on the same relations, and neither says which owns
   them.** R876's "Arguments and input fields are resolved as fields are, from a target model"
   dissolves the input and argument step targets, `graphitron_field_scope_table` and the
   `intent_argmapping_` relations, starting with "An argument's and an input field's target is
   modelled first, as tables"; R876's "Nothing resolves a path but the walk" waits on that branch.
   This item's step 3 dissolves the same step targets and its step 2 the same `intent_` chain, with
   the two anchors as an unstored target model. State the division: whether this item is that
   branch's realisation (the target model as tables being its first step), or depends on it, or
   takes only `argMapping` and `$target` navigation and leaves the rest to R876; `depends-on:` and
   R876's graph then follow.

   *Response:* stated in Store and front-matter: R876's branch is the target model, reshaped to every path being a row of one supertype with an output field's path its named subtype, and input and argument paths anonymous. This item depends on it (`depends-on` R876) and keeps only the author-facing navigation and the argMapping binding; the input-side step targets and `intent_input_occurrence_path` retire there.
3. **The single-argument shorthand can make one name mean two things.** At a field whose one
   argument `input` has an input type with a field also named `input`, or whose argument name equals
   a field of its own input type, a bare name resolves both ways. Say which wins, or that it is a
   defect, so "a name never means two things" holds.

   *Response:* a head naming the argument is the argument; only a head naming no argument is read inside the single argument's type, so the inner field is reached as `input.input`. Stated under argMapping.
4. **Step 1 replaces a rule R876 shipped a day earlier.** R876's changelog line for the
   `columnMapping` break says a bare `argMapping` name may be an argument or a column, with
   `ROUTINE_PARAMETER_AMBIGUOUS` where it is both. If step 1 lands before R876 is Done, its commit
   should amend that line rather than add a second break beside it, so a consumer reads one change.

   *Response:* done in step 1, which amends R876's changelog line for the `columnMapping` break to the final rule instead of adding a second entry.
5. **`$source.` on an input is specified but unbuilt, with no stated behaviour meanwhile.** Say what
   a schema writing it gets until then: a defect at the writing site (an `UNSUPPORTED` basis, as
   `ROOT_REFERENCE_WITHOUT_ROUTINE` uses) rather than a silent pass.

   *Response:* until built, `$source.` on an input draws a defect at the writing site with an `UNSUPPORTED` basis. Stated under navigation.
6. **Reused input types are where the design pays most, and the plan does not say so or build
   for it.** Added in the same pass, before the author's response. Today an input field's `@reference`
   path is written from one starting table, so an input type fits the targets that start there and
   is otherwise copied. On sis (capture of 2026-10-22), 19 of 595 input types are used by more than
   one field and 10 across different target types (`PersonrolleLegacyIdInput` across 8), while 236
   input types fall into 47 groups with identical field names and types (the largest 36, a group of
   29 filter inputs); the comparison leaves directives out, so some of that is naming, not forced
   copying. Navigation by output field names fits every target type offering those names. The plan
   says navigation is checked per using field; three consequences belong in it:
   * the grain of finding 1's stored relation is the input occurrence, one row per using field and
     path of input fields beneath its argument, which is the grain of `intent_input_occurrence_path`
     and `intent_input_occurrence_path_step` (the latter about 16 s of a sis capture); say whether
     this item replaces them;
   * a nested input field that navigates (`cast: ActorFilter @field(name: "$target.actors")`) could
     set `$target` for the input beneath it, so `ActorFilter` serves wherever the target is `Actor`,
     at the top level or nested; that per-occurrence target is the target model finding 2 asks
     about. Say whether navigation retargets the nested input, and on which field shapes;
   * a reused input can resolve at one use and not another, so its defect is per occurrence: at the
     writing site, naming the using field. Say so, and say whether a navigation on an interface or
     union target resolves on that type's own fields or per member.

   *Response:* the occurrence is the grain: one walk per using field and path of input fields beneath its argument, in R876's branch, which replaces `intent_input_occurrence_path` and its steps. A nested input field navigating to an object-typed output field sets `$target` for the input beneath it, so `ActorFilter` serves any `Actor` target. Defects are per occurrence at the writing site, naming the using field; an interface or union target resolves per member type, each member its own walk. All stated under navigation.
