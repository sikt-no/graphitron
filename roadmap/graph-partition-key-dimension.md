---
id: R610
title: "SDL fact keys carry a graph partition dimension"
status: Spec
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-08
last-updated: 2026-08-08
---

# SDL fact keys carry a graph partition dimension

The fact store's SDL families key every row on the type name alone (`graphql_type` is
`PRIMARY KEY (type_name)` and everything downstream inherits that coordinate), which encodes an
assumption the store should not make: that it will only ever hold one GraphQL document universe.
A plausible future direction has one long-lived store serving several Apollo federation
subgraphs at once (shared fact gathering, cross-subgraph composition detections in the LSP, one
`graphitron:dev` process per workspace instead of per subgraph), and under federation that
assumption is false by design: entities are deliberately declared in multiple subgraphs, so
`User` in one subgraph and `User` in another are distinct facts with different fields, keys, and
ownership. Under today's keys, first-wins merge would silently fuse them, turning valid input
into either a fictional merged type or a primary-key violation, and the constraint split
(violations are capture bugs, never author errors) breaks. Directive definitions partition the
same way, since each subgraph carries its own `@link` with possibly aliased imports.

The justification is stated at its honest width: the dimension buys **no present-tense
mechanism**. Nothing today deletes per graph, queries per graph, or collides across graphs;
source partitionability earned its key parts because a live mechanism (per-source refresh)
needs them, and this dimension has no such mechanism yet. What it buys is a key shape whose
cost of acquisition scales with consumer count, bought while the consumer count is zero:
R589 (`validation-adds-facts`) is in Spec and about to migrate consumers onto these relations,
the store has no persisted state of record (the warm cache is stamp-discarded, never
migrated), and changing the model is editing the DDL and following the compiler. The same
change after R589's migration touches every consumer's queries; today it touches capture and
the gates.

One fork is dismissed on the record: **one store per graph** costs nothing now and needs no
rekey, but it cannot serve the target case. Shared fact gathering and cross-graph composition
detections need the facts side by side in one queryable store; a per-graph store makes every
cross-graph question an application-level merge, which is the thing the store exists to avoid.

## The DDL change

`graph_name VARCHAR NOT NULL` joins every base relation of the `graphql_` and `graphitron_`
families (83 relations today) as the **leading** primary-key column. Leading follows the
`sql_table` precedent, where the namespace dimension (`table_schema`) leads the natural key,
and makes every widened composite FK a prefix-consistent extension rather than a permutation.
The cascade through all 83 relations is forced, not chosen: the store admits no surrogate
keys, so a child key without `graph_name` would not be unique across graphs. One decision,
one blast radius as its consequence.

- Foreign keys between the two families widen automatically, since they reference the
  widened keys. The `graphql_directive_site` union view gains the column in every arm.
- A new `store_graph (graph_name)` relation anchors the partition. The family relations with
  no in-family parent (`graphql_type`, `graphql_directive_definition`,
  `graphql_schema_directive`, `graphql_duplicate_declaration`, `graphitron_link`) get a
  direct FK to it; every other relation reaches it through its existing parent chain.
- The `store_graph` comment owes two discriminators, so the DDL's conventions stay readable
  as consistent. First, why this FK exists while the SDL-to-`store_source` FK was declined:
  the graph is ambient before the walk begins and `NOT NULL` everywhere, while the source
  rows are a summary collected last and nullable at schema-level sites, so the FK doctrine
  admits one and not the other. Second, a recorded exemption: any derivation joining an SDL
  fact to a catalog or classpath fact (`graphitron_service`'s class name against `jvm_class`,
  `graphitron_table`'s table reference against `sql_table`) is graph-blind and correct only
  while exactly one graph exists; the multi-graph orchestration item closes it. Stating it
  makes the next such join read the rule instead of inheriting the hazard silently.
- `jvm_`, `sql_`, `store_source`, and `store_stamp` keep their keys untouched. Per-graph
  classpath scope is a membership and derivation question, not capture, and version skew
  between two graphs' classpaths is that future item's business.

Exactly one graph exists, and the store says so structurally rather than by convention. The
graph name is a single declared constant owned by capture (the store's only writer), spelled
so that no author-facing naming surface would accept it as a graph name (a bracketed sentinel;
exact spelling fixed at implementation), and a gate asserts `store_graph` holds exactly one
row. No configuration surface ships: a knob nothing reads is speculative, and the
exactly-one-row gate makes the no-knob decision self-enforcing. The day a second graph
appears, retiring that gate is the deliberate act that admits it.

## Capture

`FactSink` becomes **graph-scoped** rather than every call site threading a new leading
argument. The sink is constructed with the graph name, stamps the `graph_name` column on
every record it buffers, and namespaces its `claim` keys by its own graph. This is the load-
bearing half of the item: `claim` is a hand-maintained mirror of every natural key, and
widening the DDL keys without widening the claim keys would relocate the fusion this item
exists to prevent one layer up, where a two-graph load would first-wins-drop the second
graph's types before the widened primary keys could ever see them, misfiling them as author
duplicates. Scoping the sink leaves every existing `claim` call site untouched and correct by
construction; a future multi-graph load is a second sink, not two hundred call sites that
each remember a new argument.

`StoreRefresh`'s wholesale clear stays **unqualified** by `graph_name`. A graph-scoped delete
is a retention policy, and retention in this store requires a freshness proof
(`store_source.stamp`); graphs have none. Qualifying the clear would permanently retain rows
written under any other graph name with no mechanism ever deleting them, a live hazard the
moment the sentinel is ever respelled within a release, since `store_stamp` invalidates only
on DDL hash and generator version.

## Gates

Two new gate queries join `FactSchemaGateTest`, and the first is written in exemption
polarity, the same polarity `StoreRefresh.wholesale()` already chose:

- **Every base relation leads its primary key with `graph_name` unless its family is
  deliberately graph-blind**, with the exempt prefixes (`store_`, `sql_`, `jvm_`) enumerated
  in the gate and justified in a line each. An allow-list over today's two prefixes would
  silently not cover the reserved `intent_` stratum and R589's claim relations, which is
  exactly where the dimension matters most; under exemption polarity a new family is covered
  by default and its exemption has to be argued in.
- **`store_graph` holds exactly one row** while no configuration surface exists.

Comment coverage extends to the new columns automatically through the existing gate.

## The coordinate vocabulary widens with the keys

Store-wide, "coordinate" now means `(graph_name, ...)`. R589's claim relations, the demand
relation, and the occurrence-path key inherit the dimension by definition rather than by
later amendment; without this sentence the rekey this item buys against resurfaces one
stratum up, at exactly the moment it predicted the change would be expensive. R589's Spec
should say so when it next revises, and its reviewer can hold it to this item.

## What stays put

The agreement anchors compare one pipeline run against a single-graph store; they gain the
constant in their join keys and nothing else changes in what they assert. Warm-start refresh
semantics are unchanged in behavior. No consumer queries exist yet to update, which is the
point of doing this now.

## Deliberately out of scope

- **`store_graph_source` membership.** The Backlog stub proposed it; the Spec drops it. In a
  single-graph store the membership set is exactly `SELECT DISTINCT graph_name, source_name`
  over the captured rows plus the constant: a derived fact stored as a copy maintained apart
  from its source, with nothing enforcing agreement. It lands with multi-graph capture
  orchestration, when membership stops being derivable and becomes an input.
- Configuration surface for graph naming; multi-graph capture orchestration and per-graph
  classloader scopes; any composition-detection stratum; graph-aware resolution across the
  `jvm_`/`sql_` boundary. Each can land later without rekeying anything, which is the test
  this item's scope was cut by.

## Verification

Full `mvn install -Plocal-db` green. The DDL edit follows the compiler through capture and
the tests; the widened gate family and the agreement suite are the honesty check. No
behavioral change anywhere: the store's contents differ only by one constant-valued column.
