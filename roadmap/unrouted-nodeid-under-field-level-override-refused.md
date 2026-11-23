---
id: R1205
title: "An unrouted @nodeId argument under a field-level @condition(override: true) is refused, though the override suppresses the predicate a route would serve"
status: Backlog
bucket: bug
priority: 3
theme: nodeid
depends-on: []
created: 2026-11-23
last-updated: 2026-11-23
---

# An unrouted @nodeId argument under a field-level @condition(override: true) is refused, though the override suppresses the predicate a route would serve

## Goal

A `@nodeId(typeName:)` argument that a field-level `@condition(override: true)` reads builds without a foreign-key route from the field's table to the node's table. The override suppresses the argument's implicit predicate, so nothing joins to the node's table and the route has nothing to serve; the id is decoded and handed to the condition method, which is the whole of what the author asked for. Today the build refuses it in every spelling, by parameter name and through `argMapping`, and the remedy it names, a `@reference` path, asks the author to describe a join the generated query never makes. A consumer whose predicate goes through a join table (the reporter of [GitHub issue 583](https://github.com/sikt-no/graphitron/issues/583): organisations that are members of one of several collections, through a membership table) therefore has no way to keep `@nodeId` on the argument without writing paths that mean nothing.

## What happens today

Probed through the build's validate pass on trunk at `032de1aa`, against the sakila catalog: a root field on `film` with a list argument naming a node on `category` and a single argument naming a node on `actor`. No single foreign key joins `film` to either, and neither argument carries `@reference`. One field-level condition reads both.

| Spelling | `override: true` | Outcome |
|---|---|---|
| Parameters named after the arguments | no | Refused by the store: `NODE_ID_LANDS_ON_TABLE`. |
| Parameters named after the arguments | yes | Refused by the store: `NODE_ID_LANDS_ON_TABLE`. |
| Any `argMapping` entry, stopping on the argument or naming a key column past it, single id or list | no | No stored defect. Refused by the walk: "no foreign key found between tables 'film' and 'actor'; auto-discovery is single-hop ...". |
| The same | yes | The same. |

The *walk* is the classifier that still emits condition glue; the *store* is the fact store replacing it. With a multi-hop `@reference` path written on each argument the by-name spelling builds, with and without the override (probed at `a66d14f`).

The reporter says the by-name spelling built and ran on 10.0.0-RC56 with no paths, decoding both arguments and raising the usual client error for a malformed or wrong-type id; their issue shows the condition without `override: true`.

## The two override rows are the defect

The rows without the override are the rule, not a defect. A field-level condition without override is ANDed with every argument's implicit predicate (`docs/manual/reference/directives/condition.adoc`), that predicate needs the route, and none or several foreign keys is `NODE_ID_LANDS_ON_TABLE` with `@reference` as the remedy (Alf, 2026-11-06 and 2026-11-07, recorded in R876's graph and in `docs/architecture/reference/code-generation-triggers.adoc`). RC56 building it was older behaviour of the walk. Nothing records that change as breaking, which is a release-notes gap to close when this lands.

Under a field-level `override: true` three statements already agree that no route is owed, and two pieces of code do not follow them:

* The manual: field-level `override: true` "suppresses the implicit predicates from the field's direct arguments and from any input-typed argument nested under them".
* The store's own rule, in the comment on the second `NODE_ID_LANDS_ON_TABLE` arm of `graphitron-model.sql`: "An id that binds no table predicate owes no route".
* The walk's author-owned arm, `NodeIdLeafResolver.Resolved.AuthorOwnedPredicate`, which exists for exactly this: the author has taken the predicate, so only the decode is needed.

The store's exemption tests only the id's *own* `@condition(override: true)` (the `NOT EXISTS` over `graphitron_ast_input_value_condition_entry` at the id's coordinate), not the enclosing field's. The walk's escape is taken on the same narrower condition. So an id whose predicate the *field's* override suppressed is still asked for a route by both.

A defect raised on a valid schema is a regression to fix ahead of planned work (`docs/architecture/explanation/defects-as-facts.adoc`), which is why this is filed above the items around it.

## Scope

In: the store's exemption and the walk's escape both recognise a field-level `override: true` on the field the argument belongs to, for a direct argument and for an input field nested under one, as the manual states the override's reach. A fact document under `facts/` pins the store side as a minimal pair (the overridden field draws nothing; the same field without the override draws the defect), and an execution case runs the reported shape.

Out, and stated so nobody settles it by accident: whether an id that an `argMapping` entry hands to a `@condition` binds a table predicate when `override` is *false*. The store says it does not (the same comment: "one an argMapping entry hands to a call" owes no route); the manual says a condition without override keeps every argument's implicit predicate; the walk follows the manual. That is unruled, and it is a question for Alf or Filip, not for an implementer: *does such an id bind a table predicate when `override` is false, and does the by-name spelling of the same binding agree?*

## How this sits against other items

* **R1202** makes an `argMapping` entry naming a key column past a list of node ids build at a `@condition`. Its probes all had a route. With both landed, the issue 583 schema builds with `override: true`, entries naming the key column, and no paths; with only R1202, it still needs a path on each argument.
* **R876**: the condition flip moves every condition caller to the store at once, so the store half of this is what survives it and the walk half is deleted by it. The store half should not wait for the flip: it is a stored defect on a valid schema today.
