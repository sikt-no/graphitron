# The graphitron_ and intent_ families against their grains: inventory, findings, and a naming rule

An analysis artifact, not a roadmap item: it lives in the subdirectory the roadmap-tool ignores, and
it is Markdown so `check-adoc-tables` leaves it alone. Companion to
`2026-09-05-coordinate-facts-as-relations.md`, which named the grains; this one places every
relation the two families already have against them and asks what stops the placement drifting again.

## Method

A base table's primary key *is* its grain, which is `meta_grain`'s own claim ("a relation's primary
key is its grain as columns and its comment's first sentence is its grain as prose"). So the
inventory is mechanical: every `CREATE TABLE graphitron_*` block, its declared primary key, bucketed
by key shape. 70 tables.

## The inventory

| Grain | Key shape | Count | Relations |
|---|---|---|---|
| Type | `graph, type` | 11 | `type`, `tabletype`, `table`, `node`, `node_entry`, `enum`, `record`, `error`, `scalar_type`, `discriminate`, `discriminator` |
| Field | `graph, type, field` | 16 | `field`, `field_binding`, `field_condition`, `field_lookup_key`, `field_navigation`, `field_node_id`, `connection`, `default_order`, `external_field`, `facet`, `multitable_reference`, `mutation`, `pivot`, `service`, `split_query`, `tenant_fan_out` |
| Argument | `graph, type, field, argument` | 6 | `argument`, `argument_binding`, `argument_condition`, `argument_lookup_key`, `argument_node_id`, `order_by` |
| Element | `graph, coordinate` | 2 | `element`, `minted_conflict` |
| Input path | `graph, coordinate, path` | 1 | `argmapping_candidate` |
| Mint | `graph, source_coordinate, …` | 3 | `minted_type`, `minted_field`, `minted_argument` |
| Per-directive application | `graph, type, field, ordinal` | 3 | `field_reference`, `reference_for`, `routine` |
| Position under a field | `graph, type, field, position` | 3 | `default_order_field`, `field_condition_context_arg`, `service_context_arg` |
| Position under an application | `graph, type, field, ordinal, position` | 3 | `field_reference_step`, `reference_for_step`, `routine_column_mapping_pair` |
| The argument-side mirrors | `…, argument, ordinal[, position]` | 5 | `argument_reference`, `argument_reference_for`, `argument_reference_step`, `argument_reference_for_step`, `argument_condition_context_arg` |
| Route | `graph, type, field, to_source, to_schema, to_table` | 1 | `field_table` |
| One-offs | various | 16 | federation key trio, link pair, `method_reference`, `argmapping_entry`, `spelled_reference`, `undecoded_argument`, node keycolumn pair, order/index trio, `error_handler` |

## Four findings

**1. The name does not predict the grain, and that is the root cause.** At Field grain, 6 of 16
relations carry a `field_` prefix and 10 do not: `connection`, `default_order`, `external_field`,
`facet`, `multitable_reference`, `mutation`, `pivot`, `service`, `split_query`, `tenant_fan_out` are
all keyed exactly `(graph, type, field)` and none says so. At Type grain, 11 relations sit at
`(graph, type)` under seven different naming shapes. A reader cannot tell a relation's grain from its
name, which is precisely why `graphitron_field_table` and the proposed `graphitron_tablefield` could
be argued about for several rounds without either side being obviously wrong.

**2. One grain has two spellings.** `graphitron_element` and `graphitron_minted_conflict` key
`(graph, coordinate)`; everything else at type, field and argument grain keys by decomposed parts.
Both spellings are legitimate, the coordinate being the only key that spans the three, but they are
not interchangeable and nothing records which a new relation should use. The rule that falls out of
the inventory: **decompose when the relation is about one kind of element, use the coordinate when it
spans kinds.** `graphitron_element` spans; `graphitron_minted_conflict` spans; nothing else does.

**3. The three per-directive relations are not at the Application grain, and that is the chain
defect.** `graphitron_field_reference`, `graphitron_reference_for` and `graphitron_routine` each key
`(graph, type, field, ordinal)`, where the ordinal counts applications *of that one directive*.
`graphql_field_directive` keys `(graph, type, field, directive_name, ordinal)`, the real application
grain. So the three numberings are independent and no relation orders them against each other, which
is exactly why `intent_field_chain_node` recovers order by comparing source line and column and
reports two nodes where the manual's sandwich has four. The grain gap and the defect are the same
fact.

**4. `graphitron_field_table` has a key shape shared with nothing else in the store.** One relation,
one shape, and the shape is a coordinate plus an arriving table, which the previous audit identified
as the Route grain. That is the signature of an experiment rather than a resident, and it matches its
own history: it was written recently, it has no reader outside its writer and its tests, and it holds
facts from three grains at once.

## What actually stops this recurring

Naming is the visible half; the load-bearing half is that **57 of the 70 tables declare no grain at
all**. Only 13 carry a `meta_relation` row, and the rest sit on the frozen undeclared roster. Where a
grain is declared, `MetaDeclarationGateTest` already checks that the primary key equals the grain's
key shape, so the placement cannot drift. Where it is not, nothing checks anything and the name is
the only signal, which finding 1 shows is no signal.

So the cleanup is **declaring the grains**, and the naming rule rides along on the declaration rather
than standing on its own.

## The rule to land

`graphitron_<grain>[_<fact>]`, where `<grain>` is the singular subject the primary key identifies and
the bare form is that grain's anchor. `type`, `field`, `argument`, `element`, `route`, `step`,
`application`, `operation`. The store's existing `_entry` convention stays and means the authored
decode beside a resolved anchor, as in `graphitron_node_entry` beside `graphitron_node`.

It is mechanically checkable without a new column, because the grain's `key_shape` already implies
the prefix: `graph_name, type_name` implies `graphitron_type`, `graph_name, type_name, field_name`
implies `graphitron_field`, and so on. Adding that check to `MetaDeclarationGateTest` binds it to the
declaration, so it applies to a relation the day someone declares its grain and never before. The
undeclared roster is then the backlog, and it only shrinks, which is the ratchet the store already
runs.

## Conformance of the 13 declared relations

| Relation | Declared grain | Conforms |
|---|---|---|
| `graphitron_type` | expanded-type | yes |
| `graphitron_field` | expanded-field | yes |
| `graphitron_argument` | expanded-argument | yes |
| `graphitron_element` | expanded-element | yes |
| `graphitron_minted_type` / `_field` / `_argument` | minted-* | yes, mint being its own grain |
| `graphitron_minted_conflict` | minted-conflict | no: keyed at element grain, named for the mint |
| `graphitron_node` | node-type | no: type grain, should lead with `type` |
| `graphitron_tabletype` | bound-table-type | no: type grain, same |
| `graphitron_node_keycolumn` | node-key-position | no: a position under a type |
| `graphitron_argmapping_candidate` | argmapping-candidate | no: input-path grain |
| `graphitron_field_table` | field-target-table | no: route grain, and dissolving anyway |

Six of thirteen. The rule bites on relations that already exist, which is the point of writing it
down rather than applying it only to new ones.

## Sizing the rename

Ten at Field grain and nine at Type grain do not lead with their grain, plus the seven above. That is
a large mechanical diff and it should not be one commit, nor should it be done ahead of the
declarations: renaming an undeclared relation moves a name nobody has checked the grain of, which is
how the last round of this started. The order is declare, then check, then rename what the check
names.

The one exception worth taking early is `graphitron_field_table`, because it is dissolving into the
Route and Field grains regardless and a rename would be churn on a corpse.

## The intent_ family: the same disease, one stage further on

132 relations, 25 tables and 107 views, of which **3 declare a grain**. The graphitron_ family is 13
of 70; this one is 3 of 132.

A view has no primary key, so its grain cannot be read off its definition the way a table's can. The
projection's leading columns name the entity it is about but not how many rows it has per entity, so
the buckets below are an upper bound on coarseness rather than a measurement. A probe that would have
measured it, by comparing each view's row count against its distinct key prefix on a captured store,
is written but could not run; see the note at the end.

| Bucket | Prefix | Views | Tables |
|---|---|---|---|
| Field | `graph, type, field` | 47 | 1 |
| Type | `graph, type` | 21 | 1 |
| Graph or finer | `graph` then something unrecognised | 19 | 1 |
| Argument | `graph, type, field, argument` | 6 | 2 |
| Condition site | `graph, site, use_site` | 6 | 0 |
| Table | `source, schema, table` | 3 | 1 |
| No known prefix | | 5 | 0 |
| Keyed on a coordinate plus a table | | 0 | 3 |
| **No primary key at all** | | | **15** |

**Finding 5: fifteen of the twenty-five intent_ tables have no primary key**, and they are exactly the
materialization targets. Every one is the base of a `_live` view pair. A materialized table without a
key cannot state its grain, nothing refuses a duplicate row in it, and
`MetaDeclarationGateTest`'s check that a declared base table's primary key equals its grain's key
shape has nothing to check. This is a stronger version of the graphitron_ finding: there the name did
not carry the grain, here the relation does not carry it either.

**Finding 6: the one convention that holds perfectly is the one that is gated.** All 20 `_live` views
pair with a table of the base name, 20 out of 20, with no exceptions and no drift. That convention is
enforced by the materialization register and its gate. Every ungated convention in both families has
drifted. The two families together are therefore a controlled experiment in the claim this report
rests on, and it came out the way the claim predicts.

**Finding 7: the Field bucket dominates both families.** 47 intent_ views and 16 graphitron_ tables
sit at or below `(graph, type, field)`, which is 63 relations about one grain. That is not itself a
fault, the field being the busiest entity in the domain, but it is the reason a field-grain anchor
that is complete pays for itself more than any other single relation, and the reason a name that
fails to say "field" costs more here than elsewhere.

## What the intent_ half adds to the rule

The naming rule carries across unchanged, `intent_<grain>[_<fact>]`, and the `_live` suffix stays as
the materialization pair marker it already is. Two additions the graphitron_ half did not need:

**A materialized target declares a primary key.** It is a table; a table without a key cannot state a
grain, and the existing gate is silently vacuous on fifteen of them. This is the one item in this
report that is a correctness fix rather than a tidying: it is what stops a refresh writing a
duplicate.

**A view's grain is declared or it is not checkable.** There is no key to read it off, so for views
the `meta_relation` row is the only possible statement of grain, and the roster of undeclared views
is 104 long.

## A note on the measurement that did not run

The probe that would have measured each view's true grain, rather than inferring it from the
projection, could not execute: the `graphitron-model` snapshot installed in the shared local
repository is another worktree's, and this tree's `graphitron` test sources do not compile against
it. The buckets above are therefore parse-derived and honest about being an upper bound. Rerunning
the probe after the repository holds this tree's model would turn findings about the Field and Graph
buckets from inference into measurement, and is worth doing before any rename touches a view.
