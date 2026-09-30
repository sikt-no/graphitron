# What converting the node-id instruction found

Evidence behind the graph branch "A node id's candidates are modelled from what was written".
Filed as an audit for the reason the earlier ones state: the item's file dies at Done and the
evidence has to survive it.

## Three documents, and the third one crashed capture

`NodeIdInstructionTest` was converted into three fact documents, split where one graph could not hold
two populations: a second node type over `film` makes every table-mediated reading of film ambiguous
for the whole graph. Two of them matched real capture on the first run, every row as the seeded cases
predicted: thirteen rows across the five rules, and two rows over the shared table.

The third, the multitable coordinate, never reached its assertions. Capture failed inside the
derivation pass:

```
Unique index or primary key violation: GRAPHITRON_INPUT_FIELD_CARRIER_ROLE
  (graph, type, field, source, schema, table) =
  ('node-id-instruction-multitable', 'MediaFilter', 'someId', ..., 'public', 'actor')
```

Reduced to one trigger, a bare `@nodeId` input field whose only consumer returns an interface over
two node types:

```graphql
interface Media { lastUpdate: String }
type Film  implements Media @node @table(name: "film")  { lastUpdate: String }
type Actor implements Media @node @table(name: "actor") { lastUpdate: String }
input MediaFilter { someId: ID @nodeId }
extend type Query { media(where: MediaFilter): [Media] }
```

A consumer writing that schema gets a failed capture. The seeded case of the same shape passed,
because its hand-written catalog carried no columns, so the stage that crashes never met a row.

## The grain drops the branch

`graphitron_node_id_instruction` is keyed `(graph_name, use_site, resolved_type_name)`. At a
coordinate whose type is an interface or a union, it holds one row per member, and its own rule
computes the branch in a `slot_table` CTE and does not project it. So no row says which branch it
belongs to.

`intent_node_id_decode_endpoint` rebuilds the pairing by crossing. It joins the coordinate's scope
table on the coordinate alone and the node type's table on the node type alone:

```sql
JOIN graphitron_argument_scope_table sc
  ON sc.graph_name = i.graph_name
 AND sc.type_name = COALESCE(p.root_type_name, i.type_name) ...
JOIN graphitron_tabletype bt
  ON bt.graph_name = i.graph_name AND bt.type_name = i.resolved_type_name
```

For the reproduction that is four readings where two are real: Film from film and Actor from actor,
and the invented Film from actor and Actor from film.

## What the cross breaks

Stored tables keyed per branch without the node type assume the type follows from the branch, and
their comments say so. The invented readings break the assumption:

* `graphitron_input_field_carrier_role` crashes. At `actor` the rule's `decoded` CTE keeps
  `identity`, which depends on the node type, while dropping the node type, so the key gets
  `OWN_COLUMNS` and `REMOTE`. Its `landing` aggregate also counts positions across both node types,
  so even the real reading computes `local` wrongly.
* `graphitron_node_id_decode_hop` and `_hop_column` would crash wherever two branches reach each
  other by a foreign key, at the argument site too. Inferred from the SQL, not reproduced.
* `graphitron_mutation_payload_column` is keyed the same way.

Views answer wrongly without failing:

* `intent_node_id_decode` counts positions per origin with no node type, so Actor from actor is
  `TARGET_TABLE_COLUMNS` where it should be `OWN_TABLE_COLUMNS`.
* `intent_node_id_decode_slot` counts `candidates` per use site, inflated by the fan-out, so a
  `candidates = 1` gate drops the slot.
* `graphitron_input_field_filter_role` and `intent_argument_filter_role` collapse the node types with
  `MIN` and `ROW_NUMBER` and lose which one applies.

The argument site fans out the same way and did not crash only because it has no stored twin of the
carrier role. Output fields do not fan out today. Outside `graphitron-model`, nothing reads these
relations directly; the decode coverage census reads the instruction and would report a fanned-out
coordinate twice.

## What the design discussion settled

The first reading was that the instruction's key lacks the branch. It does not: for a type inferred
per member the node type is the member, so `(use_site, node_type)` already tells the inferred
candidates apart. What is missing is what the author wrote. A written `typeName` names its type for
the whole coordinate, whatever the coordinate's type, and a type inferred from a member applies to
that member alone. The instruction holds both without saying which, and the endpoint crosses both
with every table.

Keying the branch by table was what made two cases look special, and neither is. A coordinate whose
type is a scalar, `greet(userId: ID! @nodeId(typeName: "Film")): String`, is a written candidate and
its type is irrelevant. A single-table interface is an interface like any other, because a node is a
type. The one rule a table decides is a bare `@nodeId` with `@reference`, a path landing on a table.

Two consequences recorded for the corpus. The bare argument over a table two node types share is
the type's candidate, where today it declines; the shared-table document asserted the old rule. And
`@node` on an interface is not supported, so an interface's bare `@nodeId` has one candidate per
member; allowing it would give one, the interface's, and is a possible future addition.
