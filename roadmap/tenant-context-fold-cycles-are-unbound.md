---
id: R986
title: "The tenant-context fold reads every cycle as unbound, so a type reachable both ways is rejected under a bound root"
status: In Progress
bucket: bug
theme: runtime-connection
depends-on: []
created: 2026-10-01
last-updated: 2026-10-05
---

# The tenant-context fold reads every cycle as unbound, so a type reachable both ways is rejected under a bound root

## Goal

A schema whose types navigate to each other in both directions validates when every way in from a
root carries the tenant. Tenancy here is database-per-tenant: a field reading a tenant-scoped table
must know which tenant's connection to run on, either because its own arguments name the tenant
column (it binds) or because every path from a root to its type passes a field that binds (it
inherits a tenant context). Today any type on a cycle of navigation is held to have no context, so
an author who binds the tenant at every root still gets a `noTenantBinding` rejection on each
tenant-scoped field inside the cycle. After this item a cycle is judged by the edges entering it
from outside, so the every-path guarantee holds and bidirectional navigation stops costing it.

The minimal pair, over the fixture catalog with `film_id` as the tenant column (`film` and
`inventory` both carry it):

```graphql
type Film @table(name: "film") {
    title: String
    inventories: [Inventory!]!
}
type Inventory @table(name: "inventory") {
    inventoryId: Int
    film: Film
}
type Query {
    films(filmId: Int @field(name: "film_id")): [Film!]!
}
```

Every path into `Film` and `Inventory` starts at `Query.films`, which binds, so `Film.inventories`
and `Inventory.film` both run on the divined tenant's connection. Today both reject with "no
ancestor established a tenant context"; delete `Inventory.film` and `Film.inventories` is accepted.
Adding an unbound `Query.inventories: [Inventory!]!` opens a path that carries no tenant, and then
both rejections are correct, before and after.

## What is true today

`TenantBindingIndex.Fold.tenantContextOf` asks of a type whether every reaching edge (a field whose
type, or one of whose interface implementors or union members, is the type) establishes or
transmits a context, recursing into the edge's parent. An edge establishes when the field is
`@tenantFanOut`-marked, binds the tenant itself, or is routable node dispatch; it transmits when its
parent has a context. A type met again while its own answer is in progress answers `false`, and
that answer is memoised for every type computed under it. In the pair above, computing `Inventory`
asks `Film`, computing `Film` asks `Inventory`, which is in progress and so `false`; `Film` is
cached as `false` and `Inventory` follows. That is the least fixed point, and the javadoc states it
as intended ("roots, unreached types, cycles ... fold to false"). The every-path property wants the
greatest one: a cycle adds no path from a root, so a type has a context unless some path from a root
reaches it without passing an establishing edge. Present since the per-field tenant binding axis
landed (`334c6d595`, 2026-07-20). No test in `TenantBindingClassificationTest` builds a cycle.

A second, smaller defect sits in the same recursion. `buildEdges` records an edge from every SDL
object type, including types the classification walk never reaches (the walk's domain is
`SchemaReachability.reachableTypeNames`: the roots, every node type, every `@key` type and what they
reach; an unreached object's fields are never classified). Such a type has no reaching edges, so
`computeTenantContext`'s empty-edges branch answers `false` for it, and its outgoing edges then
count against every type they point at. A type outside the domain never executes, so its edges say
nothing about the paths that do: `type Language @table(name: "language") { films: [Film] }`, with
nothing reaching `Language`, makes `Film` lose its context under a binding `Query.films` today.

The two any-path folds beside it, `anyBoundAncestor` and `anyFannedAncestor`, share the
recursion-with-in-progress-`false` shape. They feed the `@tenantFanOut` ladder's rejections ("sits
under a tenant-bound ancestor", the nested-marker rejection), which are meant to over-report. On a
cycle, whichever member is asked first can memoise a cycle-mate `false` before reaching its own
`true` through another edge, so the answer depends on which marked field the `armOf` loop reaches
first and on `buildEdges` order, and a marker on a field leading out of a cycle under a bound root
can be accepted as `FanOut` where a rejection is owed. A marker on a field inside the cycle is not
exposed to this: the marked field lies on a path back to its own parent, so the nested-marker rung,
which the ladder runs ahead of the bound-ancestor rung, rejects it today and after the change.

Observed on sis (2026-10-01): 595 tenancy rejections, one of them a genuinely unbound root
(`Query.godkjenningssakstatuskoder`, held on purpose), every other one "no ancestor established a
tenant context". A strongly-connected-component pass over the SDL field-type graph, interface
implementors included, finds one component of 121 types (`Emne`, `PersonProfil`,
`StudentVedLarested`, `Studieprogram` among them). 514 rejections sit on types in a cycle and 43
only downstream of one. The other 36 sit on payload types under `@service` mutations whose root
field does not bind; those are a separate question about service roots and are not this item. Of
sis's 6 object types no root reaches, none has an edge into any of the 155 types carrying
rejections, so on sis the cycle fix alone clears the cascade; the unreached-type rule is owed for
correctness, not for this consumer.

## Implementation

All in `TenantBindingIndex.Fold`; no model, emitter or runtime change. `Inherited` already reads the
tenant from graphql-java `localContext`, which an ancestor sets once and every descendant sees at
any depth (`TenantDslEmitter`), so a cycle traversed at runtime needs nothing new.

* **Split the edge predicate.** `edgeEstablishesOrTransmitsContext` becomes a non-recursive
  `edgeEstablishesContext` (fan-marked, `directBinding(...).divines()`, or `NODE_RESOLVE` with
  `nodeDispatchRoutable`), and the transmit half moves into the propagation below.
* **The domain is the walk's.** The fold computes over the types the classification walk reached,
  `SchemaReachability.reachableTypeNames` under the builder's `NodeDeclaration`, threaded into
  `TenantBindingIndex.compute` rather than recomputed with seeds of the fold's own, so the two sets
  cannot drift. Edges whose parent lies outside the domain are dropped when the parent-to-edges map
  is built (the inverse of the `reachingEdges` `buildEdges` already records, interface and union
  closure included). No verdict is asked of a type outside the domain: its fields are not in
  `fields`, so `armOf` never runs for them.
* **Propagate "no context" to a fixed point** over the domain. Seeds:
  * the operation roots;
  * every domain type the two dispatch checks at the top of `computeTenantContext` veto (a
    tenant-scoped node type that is not routable, a tenant-scoped entity type with no
    `EntityRepBound`);
  * every domain type no in-domain edge reaches that is not a routable dispatch surface (a node type
    in `nodePositions` under `nodeDispatchRoutable`, or an entity type in `byEntityType`). This is
    today's empty-edges branch, kept: a node or `@key` type is in the domain whether or not anything
    hands it a tenant, and an untenanted node type reached only through dispatch has no tenant in
    `localContext` for a child to inherit.

  From each type without context, follow its outgoing edges that do not establish and mark every
  type they reach. Stop when nothing new is marked. `tenantContextOf(t)` is then "not marked". This
  is the greatest fixed point the every-path property wants: a cycle entered only through
  establishing edges is never marked, and a veto or an unbound entry reaching any member marks the
  whole component through its non-establishing internal edges, so a dispatch veto still vetoes the
  component the way it vetoed the type.
* **The any-path folds become forward closures** over the same in-domain parent-to-edges map:
  `anyBoundAncestor(t)` holds when `t` is reached, following every edge, from the target of an edge
  for which `edgeDivinesTenant` holds; `anyFannedAncestor(t)` likewise from a `fanMarked` edge. Any
  path wants the least fixed point, which these already intend; their defect is the
  order-dependent memo, and a forward closure has none. One helper, "close forward from these seeds
  along edges satisfying this predicate", serves all three, so the class carries one traversal over
  one graph rather than a recursion per question. A marked field on a cycle lies in its own fanned
  closure (its target reaches its parent), so it keeps rejecting as nested, and the ladder's rung
  order in `fanOutArmOf` is unchanged.
* **When it runs.** The propagation reads `nodePositions`, `nodeDispatchRoutable` and
  `byEntityType`, which `classifyNodeDispatch` and `classifyEntityDispatch` fill. It runs eagerly in
  `run()` after those two and before the `armOf` loop; today's lazy memo hid that ordering. The
  shape is the grow-until-stable fixpoint `LookupFactVisitor.closeOverEdges` already uses.
* **Retire the recursion state:** `ctxMemo`, `ctxInProgress`, `fannedAncestorMemo`,
  `fannedAncestorInProgress`, `boundAncestorMemo`, `boundAncestorInProgress` give way to the
  computed sets.
* **Prose that would state the opposite.** The `Fold` class javadoc ("A reachable cycle resolves
  conservatively to 'no context' ... a cycle can never mint a spurious Inherited"), the
  `tenantContextOf` javadoc ("roots, unreached types, cycles ... fold to `false`"), and both
  any-path folds' "Cycles fold to `false`" are rewritten to state the new rule: every path from a
  root, judged over the walk's domain, cycles adding no path. The comment on the fold's call site in
  `GraphitronSchemaBuilder` makes no cycle claim and stays.

## Tests

`@UnitTier`, in `TenantBindingClassificationTest`, each built on the
`childBelowBoundAncestorYieldsInherited` fixture:

* **Cycle under a binding root.** The Goal's pair: `Film.inventories` and `Inventory.film` are both
  `Inherited` (with `Film` and `Inventory` as parent type), and `rejections()` is empty. This is the
  test that fails today.
* **Cycle also entered unbound.** The same plus `Query.inventories: [Inventory!]!`: both cycle
  fields reject `noTenantBinding`. Guards against a fold that forgets the entering edges.
* **Unreached parent.** The non-cyclic fixture plus `type Language @table(name: "language") { films:
  [Film] }` with nothing reaching `Language`: `Film.inventories` is `Inherited` and `rejections()`
  is empty (`Language.films` is never classified, so it carries no verdict either way). Fails
  today.
* **Vetoed dispatch on a cycle member.** The cyclic fixture with `Inventory implements Node`
  keyed on `inventory_id` alone, as in `nodeIdKeyMissingTenantColumnOnTenantScopedTableRejects`,
  and no `Query.node`, so the dispatch veto is the only thing denying `Inventory` a context:
  `Film.inventories` rejects alongside the node-key rejection, the veto reaching `Film` through
  `Inventory.film`.
* **Fan-out marker on a cycle edge.** The cyclic fixture with `Film.inventories` marked
  `@tenantFanOut` under the binding `Query.films` (list-typed, since `fanOutArmOf` rejects a
  single-valued field as "not list-shaped" ahead of the ancestor rungs): it rejects with the
  nested-marker message ("double-fan an already fanned context"), since the marked field is its own
  fanned ancestor through `Inventory.film`, and `Inventory.film` is `Inherited`. This passes today;
  it pins that a marker on a cycle reads as nested, so the closure cannot drop the self-reaching
  path.
* **Fan-out marker leaving a cycle.** A four-type cycle under the binding `Query.films`, with two
  types over the `film` table so the cycle can close through foreign keys the catalog has:

  ```graphql
  type Film @table(name: "film") {
      title: String
      inventories: [Inventory!]!
      categories: [FilmCategory!]! @tenantFanOut
  }
  type Inventory @table(name: "inventory") { inventoryId: Int film: Sequel }
  type Sequel @table(name: "film") {
      title: String
      filmActors: [FilmActor!]!
      categories: [FilmCategory!]! @tenantFanOut
  }
  type FilmActor @table(name: "film_actor") { actorId: Int film: Film }
  type FilmCategory @table(name: "film_category") { categoryId: Int }
  type Query { films(filmId: Int @field(name: "film_id")): [Film!]! }
  ```

  `Film.categories` and `Sequel.categories` both reject as "sits under a tenant-bound ancestor",
  and the four cycle fields (`Film.inventories`, `Inventory.film`, `Sequel.filmActors`,
  `FilmActor.film`) are `Inherited`. This fails today: `Sequel.categories` classifies `FanOut`,
  because `anyBoundAncestor(Film)`, asked first for `Film.categories`, walks the cycle and memoises
  `Sequel` as `false` before `Query.films` answers `true`.

No execution-tier case is owed. Emission and runtime are untouched: the change only lets more
fields reach the `Inherited` verdict, whose hand-down through `localContext`, across a
`@splitQuery` batch included, `TenantDivinedRoutingExecutionTest.inheritedChild_routesItsBatchToTheDivinedTenant`
already exercises.

Corroboration outside the repo: rebuilding sis should report 37 tenancy rejections (the held root
plus the 36 service payloads), down from 595. That is a check for the implementer to run, not the
acceptance gate.

## Other solutions we've considered

* **Condense strongly connected components, then fold the DAG.** Tarjan over the reaching-edge
  graph, then a component has a context when every edge entering it from outside establishes or
  transmits one. It gives the same answer as the propagation, since both compute the greatest fixed
  point. Propagation won on moving parts: no component bookkeeping, no separate rule for a vetoed
  member, and a shape already in the tree.
* **A reachable set of the fold's own.** Seeding reachability inside the fold from the roots and
  the routable dispatch surfaces would also stop unreached types poisoning their children, but its
  seeds differ from `SchemaReachability`'s (every node and `@key` type), so two sets meant to agree
  would drift. The walk's set is the domain the fields were classified over; the fold reads it.
* **Move the fold into the fact store.** A recursive view over captured edges would be the same
  propagation. `TenantBindingIndex` is an existing post-walk derivation and this is a correctness
  fix to it, not a new fact; moving tenancy into the store is its own item.
* **Leave the any-path folds alone.** They are a separate fact from the title's, but they share the
  graph and the defect; fixing one fold and leaving two recursions with in-progress-`false` memos
  over the same edges keeps a known order-dependent answer and two mechanisms side by side.

## Reviewer findings

### Round 1: Spec → Ready, withheld (session_01VkHxXd4pAENrVWLh4cq3xE, 2026-10-05)

The goal reads cleanly from its first paragraph and the minimal pair, and the outcome is reachable.
The diagnosis checks out against the tree: the in-progress-`false` memo in `tenantContextOf`, the
empty-edges branch, `buildEdges` recording edges from every SDL object type, and the
order-dependent `anyBoundAncestor`/`anyFannedAncestor` recursions (a two-cycle is enough: asking
`Film` first memoises `Inventory` as `false` before `Query.films` answers `true`). Run against the
tree today, the Goal pair rejects both cycle fields and the unreached-`Language` fixture rejects
`Film.inventories`, as the item says. The plan fits the architecture: the domain is
`SchemaReachability.reachableTypeNames`, whose seeds and implementor edges match the walk that
fills `fields`, and the propagation is the grow-until-stable shape `LookupFactVisitor.closeOverEdges`
already uses. Splitting the edge predicate changes nothing for node dispatch: `NODE_RESOLVE` is
minted only on `QueryNodeField`/`QueryNodesField`, whose parent is a root and so already marked,
which means the not-routable case that today answers `false` without transmitting gives the same
result after the split. One finding, on a named test.

1. **The fan-out cycle test expects a rejection the ladder never reaches.** (Gate question 2: the
   Tests section names the evidence the implementer will build against.) With
   `Film.inventories: [Inventory!]! @tenantFanOut` on the Goal pair's cycle under the binding
   `Query.films`, the field is its own fanned ancestor: `Inventory` is the marked edge's target and
   `Inventory.film` leads back to `Film`. `fanOutArmOf` runs the `anyFannedAncestor` rung ahead of
   `anyBoundAncestor`, so the field rejects as "sits below another @tenantFanOut field ... double-fan
   an already fanned context", not "sits under a tenant-bound ancestor". Building that fixture on the
   current tree gives exactly that rejection (and `Inventory.film` classifies `Inherited`), and the
   planned forward closure gives the same answer, since `Film` is reached from the seed `Inventory`.
   The test as written fails both before and after the change. Whether a marker on a cycle should
   read as nested is the author's call. The nested-marker message is arguably right, because at
   runtime the fanned field does recur below itself. If the test is meant to pin the bound-ancestor
   rung's order dependence instead, it needs a marked list field on a cycle member whose target lies
   outside the cycle, on the member the recursion memoises wrongly (in the two-cycle, `Inventory`
   when `Film` is asked first).

   *Response (session_01VkHxXd4pAENrVWLh4cq3xE, 2026-10-05):* did both. The cycle-edge test now
   expects the nested-marker rejection, pinning that a marker on a cycle reads as nested; the
   Implementation bullet on the forward closures says the self-reaching path stays in the fanned
   closure and the rung order is unchanged. A new test, "Fan-out marker leaving a cycle", uses a
   four-type cycle (`Film → Inventory → Sequel → FilmActor → Film`, with `Sequel` a second type over
   `film`) with markers on `Film.categories` and `Sequel.categories`. Built on the current tree,
   `Sequel.categories` classifies `FanOut`, so this test fails today and passes once the closure
   lands. The "What is true today" paragraph now says the exposure is a marker leading out of a
   cycle, not one inside it.
