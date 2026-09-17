---
id: R956
title: "A reference-step hop is four arms with two natural keys, so it is two keyed relations under a view rather than one relation padded with nulls"
status: Backlog
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-09-17
last-updated: 2026-09-17
---

# A reference-step hop is four arms with two natural keys, so it is two keyed relations under a view rather than one relation padded with nulls

## Goal

A `@reference` path element's local resolution, and the walk that chains those elements, each become
relations that refuse a duplicate row, because each is split into the arms it always was: one relation
per key shape, keyed, every column `NOT NULL`, with each arm's legality stated in a `CHECK` rather than
in prose, and a view unioning them under the name every reader already spells. Today both are one
relation carrying two key shapes at once, which is why neither has a primary key and why nothing in
the tree refuses a duplicate row in either. Nothing a reader spells changes. When this lands, the two
relations can be declared in `meta_relation` and satisfy
`MetaDeclarationGateTest.aDeclaredTableKeyMatchesItsGrain` without that gate being touched, which is
what unblocks R954's conversion of them into gatherer-written facts.

Three terms, glossed once. The *fact store* is the H2 database each generator pass captures the schema,
the jOOQ catalog and the classpath into, and answers its verdicts out of by SQL. A *registration* is a
row of `meta_materialize`, which keeps a rule in a view under a `_live` name and moves the canonical
name onto a table a refresh pass empties and refills. An *arm* here is one branch of a `UNION ALL`
whose rows are told apart by a literal the branch projects into a discriminator column.

## The diagnosis

`intent_field_reference_step_hop` declares fifteen columns and **every one of them is nullable**, with
no primary key. Its rule, `intent_field_reference_step_hop_live`, is a `UNION ALL` of four branches
projecting `'KEY'`, `'TABLE'`, `'NAME_MATCH'` and `'CONDITION'` as constants into `via`. On the
`NAME_MATCH` and `CONDITION` branches the constraint columns are projected as literals: `NULL` for
`key_matched_by` and `constraint_name`, `CAST(NULL AS BOOLEAN)` for `fk_on_from`. A projected literal
`NULL` is not a fact about a hop. It is padding that makes four row shapes share one column list.

The three column comments already state the rule that padding encodes, one by one:
`key_matched_by` is "NULL on a TABLE, NAME_MATCH or CONDITION hop, none of which names a constraint";
`constraint_name` is "NULL on a NAME_MATCH hop, which joins on no foreign key, and on a CONDITION hop,
which joins on an authored predicate instead"; `fk_on_from` is "NULL on a NAME_MATCH hop ... and on a
CONDITION hop". That is the shape `docs/architecture/principles/development-principles.adoc` names
under sealed hierarchies over enums: variants carrying different data forced into one field set, whose
tell is that one discriminator value implies which fields are non-null.

**The arms have two different natural keys, and that is why no key exists.** On the `KEY` and `TABLE`
arms, `constraint_name` and `fk_on_from` are identity: both orientations of every foreign key
connecting the two tables are separate rows, which is exactly the ambiguity
`ix_field_reference_step_hop_step`'s comment names when it says it is "Not UNIQUE and not the grain".
On the `NAME_MATCH` and `CONDITION` arms there is no foreign key to enumerate, so the element
coordinate with the departing and arriving table triples is already total. One relation cannot carry
two key shapes.

`intent_field_reference_step_target`, the recursive walk over the hop, inherits the same three columns
and the same problem, and its own body in R954 records the conclusion without the diagnosis:
"Indexed and not keyed: the grain includes `constraint_name` and `fk_on_from`, both meaningfully
nullable, and H2 refuses a primary key over a nullable column." That is a statement about an engine.
It cannot be the reason for a modelling decision, and the reason underneath it is this item's subject.

This is the fact-model page's own worked anti-example rather than a new observation. That page records
a `@reference` path element that "needed nine nullable columns and a legality rule no constraint could
state" and became three relations, one per assertion. `intent_condition_method_route` and its
`_defect` sibling are the shipped instance of the same move.

## The shape

Per relation, two keyed base tables and a view.

- `graphitron_field_reference_step_hop_key` holds the `KEY` and `TABLE` arms, keyed on the element
  coordinate, both table triples, `constraint_name` and `fk_on_from`.
- `graphitron_field_reference_step_hop_match` holds the `NAME_MATCH` and `CONDITION` arms, keyed on the
  element coordinate and both table triples, which is already total there.
- `graphitron_field_reference_step_hop` is a view unioning the two, under the name both remaining walks
  and `intent_field_chain_node` already join. Nothing a reader spells changes.

The walk splits the same way and for the same reason, into a keyed and a match relation with `targets`
and `candidates` as payload, under a union view carrying the existing name.

Every column of every arm table is `NOT NULL`. Each carries a `CHECK` naming the `via` values it
admits, and the keyed one carries `CHECK (key_matched_by IS NULL OR via = 'KEY')`. The shipped exemplar
is `graphitron_field_table_link` at `graphitron-model.sql:5556-5594`: a stage-written `graphitron_`
table over the same `via` vocabulary, carrying a primary key beside `CHECK ((constraint_name IS NULL) =
(fk_on_from IS NULL))`, `CHECK (constraint_name IS NOT NULL OR via <> 'KEY')` and `CHECK
(key_matched_by IS NULL OR via = 'KEY')`.

**Why that table is keyed where the hop is not, since it is the nearest thing to a counter-case.** Its
arm-conditional columns are payload, one link per position per target; the hop's are identity, many
candidate routes per position. That asymmetry is why the answer here is decomposition rather than
copying that table's constraint set onto one relation.

Whether the two relations stay registration targets or become gatherer-written stages is R954's
question, not this one. This item changes what the relations are; it does not change who writes them.
Taking it first is what lets R954 declare them without touching a gate.

## What this owes before it can be believed

**A re-measurement of the recursive step's seek across the union view**, which is this item's
acceptance evidence and the reason it is separate from R954 rather than folded into it. The walk's
recursive term joins the hop once per accumulated row, and `ix_field_reference_step_hop_step` is what
makes that a seek: its own comment prices reading the walk whole at 18308 scans without the index and
523 with it. Under a union view the planner has to push that eight-column seek into both arm tables,
each carrying its own copy of the index. H2 usually does. Usually is not good enough for a figure
another item's cost claim rests on, so it is measured rather than assumed, per the `store-performance`
skill's method and on the same instrument R954's own measurement used. No figure is written here
because none has been taken.

The exposure is bounded and worth stating so the risk is not read as larger than it is: what that seek
prices is the walk's own build, not the reads above it, and a stored walk is read as a table whichever
way this lands.

## Relation to other items

**R954** converts this subtree's relations into facts the graphitron gatherer writes, and its phases 1
and 2 depend on this item. The dependency is the declaration the conversion forces: a converted
relation leaves `MaterializeRegistryGateTest.nothingMaterializesOutsideTheMechanism`'s `intent_`-scoped
scan and lands on `MetaDeclarationGateTest.theUndeclaredRosterOnlyShrinks`, whose ratchet makes a
`meta_relation` row mandatory, which in turn brings `aDeclaredTableKeyMatchesItsGrain` to bear. That
gate's message is that "an unkeyed declared table owes a key before it owes anything else", and of the
shipped tree's 270 base tables the 17 carrying no primary key are all registration targets standing on
the frozen undeclared roster. So a conversion of an unkeyable relation would be the first declared
keyless base table. This item removes the unkeyability instead of asking the gate to tolerate it.

**R876** is the doctrine both items instantiate. Its burn-down records that nothing refuses a duplicate
row in these targets today; a key closes that here rather than deferring it to whatever reconciles the
rows.

**R955** converts the register's remaining fifteen registrations and twelve of them carry no primary
key. Its rule is a primary key where the grain admits one, and this item is the answer to the case
where the grain does not, at least where the reason is a discriminated union: ask whether the relation
is one relation before asking the gate to admit a keyless one. Whether each of its twelve is that shape
is that item's to determine.

**R900** is the naming sweep. The two arm-table spellings proposed here are the ones a reader of the
existing family would guess and nothing in this item pre-empts that sweep.

## Provenance

Filed 2026-09-17 out of R954's round-6 Spec review, which found that the rename R954's conversion
argument rests on walks the converted relations into a declaration obligation and thence into the key
gate, and that three of its four relations looked unkeyable. Following why produced this diagnosis: one
of the three keys cleanly after all and the other two are not one relation each. R954's body records
the four arms weighed there and why three were declined, including a widening of the key gate that was
drafted and withdrawn.
