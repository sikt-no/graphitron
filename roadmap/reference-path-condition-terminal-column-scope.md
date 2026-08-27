---
id: R847
title: "A reference path ending in a condition hop resolves no column scope"
status: Spec
bucket: bug
priority: 2
theme: classification-model
depends-on: []
created: 2026-08-26
last-updated: 2026-08-27
---

# A reference path ending in a condition hop resolves no column scope

An argument whose `@reference` path ends in a condition hop classifies and executes, but the fact
store cannot see where the path lands: the hop derivation has no arm for a condition element, so
the chain never reaches the terminal, the argument resolves no column scope, no column match and no
filter role, and the coordinate is missing from `intent_condition_membership`. The generator emits
a filter predicate at exactly these coordinates, so every store reader asking "does this argument
contribute a predicate" gets a false no. These are the last two misses in the fold-versus-producer
diff recorded in `roadmap/planners-read-facts-emitters-read-commands.md`, and the producer
conversion there is gated on the diff closing, so this silence is what stands between
`ConditionCommands.produce` and reading the fold.

An argument carrying `@reference(path: [...])` resolves its column against the path's terminal
table: that is `intent_argument_column_scope`'s `PATH_TERMINAL` basis, reading
`intent_argument_reference_step_target`. A path step can be a key hop (`{key: "..."}`) or a
condition hop (`{condition: {className, method}}`), and a condition hop names no foreign key, so the
step target relation has no row for it and the scope relation has no row for the whole path.

Two coordinates in the sakila example schema are in this population, and both are deliberate
fixtures for the shape:

- `Query.customersByConditionDistrict`, whose `district` argument has a single-step path that is a
  bare condition hop, and
- `Query.filmsByBridgedActorFirstName`, whose `firstName` argument has an FK hop to the
  `film_actor` junction and then a terminal condition hop to `actor`.

The classifier resolves both. `intent_argument_column_scope` and `intent_argument_column_match`
have no rows for either, so `intent_argument_filter_role` has no row either, absence in that
relation meaning "a rejection's population" where these two are nothing of the kind.

Found by diffing `intent_condition_membership` against what `ConditionCommands.produce` actually
yields for the example schema: the producer emits a condition at both coordinates and the fold
could not see either.

## The rule being transcribed, and where it lives on the Java side

`BuildContext.parsePathElement`'s bare-condition arm builds the hop from
`resolveConditionJoinTarget`, whose rule has two rungs. A chain-ending element on an *output field*
prefers a declared target, the carrier field's return-type `@table` binding; that rung exists so a
method typed `(Table<?>, Table<?>)` works at a projection site. A *filter* path never has a
declared target, so the target is reflected off the condition method's second parameter, which must
be a concrete generated jOOQ table class (a wildcard `Table<?>` target is a typed author error),
matched by exact class identity in `JooqCatalog.findTableByClass`. Parameter 0 denotes the
departing table and parameter 1 the arriving one (the emitter calls `method(sourceAlias,
targetAlias)` positionally); `validateConditionParamTables` checks each concrete parameter against
the hop's actual source and target, tolerating wildcards, and a mismatch is a build-failing
finding, not a resolution input.

The capture question the Backlog note raised is answered: nothing new needs capturing, and the
strata rules forbid capturing the resolved hop, since the answer is a function of rows the store
already holds. `graphitron_argument_reference_step` and `graphitron_field_reference_step` carry the
condition's `class_name` and `method` flattened in place. The jvm census carries the method and its
parameters, and `jvm_method_parameter_type_ref.referenced_class` is deliberately not a foreign key,
so it names generated jOOQ table classes even though `jvm_class` excludes the generated package.
`sql_table.class_fqn` is documented as "the join key that reaches generated sources at all" and has
zero join consumers today. The census already decomposed the declared parameter type into rows, so
the string surgery the Java resolver does on type names is a parse the views never perform, the
same boundary `intent_spelled_table` sits on. The fix is derivation alone.

## The change

**A shared rung: the route a condition method's signature declares.** One new derived relation
(working name `intent_condition_method_route`), keyed by `(graph_name, class_name, method)` plus
the resolved tables: the departing-table candidates parameter 0 declares and the target table
parameter 1 declares, each read from `jvm_method_parameter_type_ref` at `type_path = ''` joined to
`sql_table.class_fqn` through `store_graph_source`. A wildcard or absent parameter 0 declares no
departure constraint, so the from side is every table in the graph's sources on those rows, the
chain narrowing it exactly as it narrows `NAME_MATCH` departures; a wildcard parameter 1 yields no
row, which is the resolver's own refusal on a filter path. This is a rung both hop views join, the
shape `intent_spelled_table` and `intent_name_matched_key_pair` already have, and it is what keeps
the two sibling hop views textually parallel arm for arm instead of forking the rule. No
`returns_condition` guard: `pickMethod` rejects by name-ambiguity alone, so filtering overloads by
return type here would make the store *route* a chain the generator refuses as ambiguous; overload
multiplicity flows into the existing arity columns instead, with a comment noting the census is
public-only so the count approximates `pickMethod` from below.

**A fourth arm, `via = 'CONDITION'`, on both hop views.** Population: captured elements where
`class_name IS NOT NULL AND key_ref IS NULL AND table_ref IS NULL`. An element carrying a condition
beside its key or table stays the KEY or TABLE arm's row, the condition being that hop's WHERE-side
filter and not its route. The arm joins the route rung; `key_matched_by`, `constraint_name` and
`fk_on_from` are NULL as on NAME_MATCH. Parameter 0 stays out of the routing: the chain already
knows its departure (the scope table at position 0, the previous arrival after that), and the
agreement between parameter 0 and that departure is a detection, not a step. Composition with FK
hops falls out of the existing recursion in `intent_argument_reference_step_target` unchanged: the
bridged fixture's condition hop sits at position 1 and its candidate departures include
`film_actor`, so the chain extends. Downstream relations pick the rows up with no edit of their
own: `intent_argument_column_scope`'s `PATH_TERMINAL` arm, `intent_argument_column_match`,
`intent_argument_filter_role`'s NAME_MATCHED arm, and `intent_condition_membership`.

**A defect view with a closed vocabulary, so one absence does not carry seven meanings.**
`resolveConditionJoinTarget` has typed author errors (fewer than two parameters, wildcard target
parameter, a target class that resolves to no generated table) and the census adds silences of its
own (a condition class the scan drops, nested or non-public). Absent a verdict relation, all of
those and "chain not reached" would be one indistinguishable no-row. A small view on
`intent_argmapping_projection_defect`'s pattern, over the same joins as the rung, states the
verdicts; the hop arm's comment then owes exactly one silence, "not reached". If implementation
finds the defect view growing past a screenful, it splits out as its own item, recorded explicitly
at that point rather than absorbed.

**The field walk's declared-target preference is deliberately not here.** With the rung shared, the
field-site hop view gains the same CONDITION arm and `Customer.districtByCondition` (a scalar leaf
through a concrete-typed condition hop) resolves for free. What does not resolve is a terminal
condition hop whose method is wildcard-typed and whose carrier field's return type supplies the
target, `Customer.addressByCondition` being the exercised coordinate: that preference is a property
of the projection site, not of the hop, so it belongs at the field walk (in
`intent_field_column_scope`'s territory), and putting it in a hop arm would hand the input-field
walk, which reads the same hop relation as a filter site, a preference it must not have. That rung
is filed as `roadmap/field-walk-declared-target-condition-rung.md`, with `addressByCondition` as
its population.

## Corrections this item carries

- The claim that a condition element takes its target from the condition method's *return type* is
  wrong and appears in the DDL comment near `intent_field_reference_step_target`, in
  `docs/architecture/explanation/fact-model.adoc`, and in `ArgumentReferenceStepTargetTest`'s
  javadoc. The two actual sources are the carrier's return-type `@table` and the method's second
  parameter. Correct every site a grep finds.
- `anElementNamingNeitherKeyNorTableIsNotAHop` pins the very absence this item removes; it inverts
  into a fixture of the new arm rather than being re-scoped.
- The `via` column comments enumerate a closed vocabulary and gain CONDITION; the sibling views'
  "textually identical arm for arm" comments hold with one added sentence naming the shared rung,
  and the sibling anchor test keeps its full claim.
- `intent_condition_membership`'s missing-populations sentence drops the stale coordinate count and
  names the enforcer below instead.

## Anchors and the Done measurement

- graphitron-model unit tier, `ArgumentReferenceStepTargetTest` and `ArgumentColumnScopeTest`
  (fixture rows for the census, `class_fqn` and condition steps): a bare terminal condition hop
  resolves; FK-then-condition composes; an element with a condition beside its key stays KEY; a
  wildcard target parameter is a defect row and no hop; overload multiplicity lands in the arities.
  The route rung and the defect view get their own seeded tests on the same terms.
- A shadow test in `rewrite/derive` beside `ColumnMatchShadowTest`, asserting
  `intent_condition_membership` against the `(coordinate, table)` keys `ConditionCommands.produce`
  yields for the example schema, replaces the hand-run diff as the enforcer and retires with the
  walk when the producer converts. This item lands it; the producer conversion in
  `roadmap/planners-read-facts-emitters-read-commands.md` then consumes it, and if that item lands
  one first, this item adopts it instead.
- Read cost, measured and stated at Done: the argument hop view is unregistered and inlined into
  both terms of a recursion feeding a registered target, so the arm's census joins are priced on
  the read-cost fixture (before and after, `OPTIMIZE_REUSE_RESULTS` off), and the rung is the
  registerable unit if the measurement asks for one.
