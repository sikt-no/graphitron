---
id: R1031
title: "A field rejected at classification voids its subtree tenant context"
status: Spec
bucket: bug
priority: 4
theme: diagnostics
depends-on: [rejected-declined-or-unbound-root-keeps-subtree-context]
created: 2026-10-14
last-updated: 2026-10-14
---

# A field rejected at classification voids its subtree tenant context

## Goal

In a database-per-tenant build, a field that classification rejects outright reports its own error, and the tenant-scoped fields below it are not also reported as having "no ancestor established a tenant context" when the field's return type leads to tenant-scoped tables. Today such a field hands its subtree nothing, so one rejected root can bury its own error under hundreds of consequential ones: sis hit this on 2026-10-14 with one rejected root returning `Node`, which voided the context of every node type and produced about 680 follow-on errors. Removing the directive cleared all of them. Where the field's return type leads to no tenant-scoped table, its subtree keeps being judged as having no tenant, because that is what any fix will leave it. A build that passes is unchanged, since it contains no classification-rejected field.

A minimal pair over the sakila fixture with `film_id` as the tenant column, where `@notGenerated` stands in for any classification rejection (it is retired, so classification rejects every use):

```graphql
type Film @table(name: "film") {
    title: String
    inventories: [Inventory!]! @splitQuery
}
type Inventory @table(name: "inventory") { inventoryId: Int }
type Query {
    films: [Film!]! @notGenerated        # leads to tenant-scoped 'film'
}
```

Today: the `@notGenerated` rejection on `Query.films`, plus `Film.inventories` ("no ancestor established a tenant context"), plus one per tenant-scoped field below `Inventory`. After: the `@notGenerated` rejection alone. Swap the root for `languages: [Language!]! @notGenerated` over the global `language` table, with `Language.films: [Film!]!` below it, and `Language.films` keeps its "no ancestor established" error, since fixing the root leaves a global read that hands down no tenant.

Terms used below. The *tenant fold* (`TenantBindingIndex.Fold`) decides, before any field gets its verdict, which types have a tenant on every path from a root (*context*): a tenant-scoped field on such a type inherits that tenant. It walks *edges*, one per SDL field returning an object, interface or union type, and asks of each edge whether it establishes a context (hands its subtree a tenant) or only passes on its parent's. A *site* is one classified instance of a field, the per-field input the fold reads. A *classification-rejected field* is one whose coordinate holds a `GraphitronField.UnclassifiedField`: classification could not make anything of it, and the validator reports it.

## Why it happens

`Fold.sitesOf` records sites for `OutputField`s only, and `UnclassifiedField` is not one, so a classification-rejected field has an edge (`buildEdges` reads the SDL) and no site. `edgeEstablishesContext` answers false for an edge with no sites, so the edge passes on its parent's context. At a root, or below a global parent, the parent has none, and the closure from the unbound seeds (`foldAncestorContexts`) carries "no context" through the edge into everything it reaches.

Two facts bound the fix:

- **Site-less edges are not all rejections.** An SDL-declared connection type's fields (`FilmConnection.edges`, `FilmConnection.nodes`, `FilmEdge.node`, `pageInfo`) are never classified (`GraphitronSchemaBuilder.classifyFieldsOfObject` returns early for `ConnectionType` and `EdgeType`), so they are site-less edges in passing builds, correctly passing on the context the connection-returning field established. The rule below therefore keys on the coordinate holding an `UnclassifiedField`, never on an edge having no sites.
- **No passing build has an `UnclassifiedField`.** `GraphitronSchemaValidator.validateUnclassifiedField` reports every one as a `ValidationError`, which has no severity and stops emission; only fields of reached types are classified, so none sits outside the walk's domain. A rejection nested inside a nesting type lands at the embedding field's coordinate (`FieldBuilder` turns the whole `NestingField` into an `UnclassifiedField` there), so it is covered by the same rule. Whatever the fold does with these edges changes only the error list of a failing build.

## The rule

R1027 judges a rejected site's subtree as every fix its rejection offers leaves it. A classification-rejected field has no site, so no reach, reading or offered fix to argue from; its rejection may be any of dozens of classification errors, and its fix may produce any field shape. The SDL still says what the field returns, and that is enough to answer the one question the fold needs: once fixed, will this field's own statement need a tenant?

A classification-rejected edge is *counted* when:

1. The field does not carry `@globalData`. A marked field takes its verdict from the marker's ladder once it classifies, and a field that runs on the default source hands nothing down; its subtree's errors are then real. (`@tenantFanOut` needs no condition: `edgeEstablishesContext` already answers true for a fan-marked edge ahead of reading sites, and `belowFannedEdge` already seeds from it, so a fan-marked rejected field is already judged as fanning.)
2. Its *SDL reach* holds a tenant-scoped table. The SDL reach is the set of tables the field's own statement would select from, read off the SDL: starting at the edge's targets (`edgeTargets`, which already expand interfaces and unions to their members), a type whose `types` entry is a `GraphitronType.TableBackedType` contributes its `table()`; a `GraphitronType.ConnectionType` or `GraphitronType.EdgeType` is walked through along its own outgoing edges; every other type contributes nothing and stops the walk. So `Query.films: FilmConnection` reaches `film` exactly as `Query.films: [Film!]!` does.

The pass-through set is exactly the connection and edge wrappers because those are the only types whose fields are part of the returning field's own statement: `GraphitronSchemaBuilder.classifyFieldsOfObject` never classifies their fields, so their edges are the returning field's statement seen through the wrapper. Every other non-table type's fields are sites of their own once the build passes, and decide their own subtrees. A nesting type is the clearest case: a classified `NestingField` hands down nothing (`siteEstablishesContext` is false for it, the edge passes on its parent's context), so a fixed nesting field under a context-less parent leaves its nesting type context-less too, and the nesting type's own split-query fields then rule on their own. A service or record result type is the other: a rejected root `@service` returning `Report { films: [Film!]! }` is most naturally fixed into a plain service whose own statement touches no table and runs `Untenanted`, so a tenant-scoped child below it has a real error to report. Mutation payloads stop the walk for the same reason; a rejected DML mutation returning a payload keeps today's behaviour, which is the right answer when the fix is a payload-returning service and over-reports when it is a DML write.

A counted edge establishes a context (`edgeEstablishesContext`) and divines a tenant (`edgeDivinesTenant`). Both folds read one set of counted edges, computed once in `run` beside R1027's `countedSites`, before `foldAncestorContexts`.

Why "divines" too: R1027 keeps the invariant that every type with a context sits below a divining edge, a fanned edge, or a routed dispatch entry, and its argument that `siteDivinesTenant` needs no restriction rests on it. A counted edge that established without divining would break that invariant in failing builds. Divining also gives the right answer for a `@tenantFanOut` field below the rejected field: once the field is fixed it binds a tenant, so the marker below it will reject "sits under a tenant-bound ancestor"; saying so now is the same error one build earlier.

Why SDL reach is the right proxy. A fixed field hands its subtree nothing only when its own statement needs no tenant, which is when it reaches no tenant-scoped table (a global read, a plain value), or when it carries `@globalData`, which condition 1 excludes and whose ladder refuses a tenant-scoped table return anyway. A field that does reach a tenant-scoped table, once it classifies, either hands a tenant down (a binding, the parent row, node dispatch, a fan-out) or is itself a tenant rejection, which R1027 judges as fixed. So in both branches the subtree's "no ancestor established" errors are consequential until the field resolves, which is what the rule reports.

What it can get wrong, and what that costs, in both directions. *Over-counting*: a counted field whose fix classifies it as needing no tenant even though its SDL return type leads straight (or through a connection) to a tenant-scoped table. Then a tenant-scoped child's real "no ancestor established" error is hidden, and, because a counted edge also divines, a `@tenantFanOut` child below it is rejected "sits under a tenant-bound ancestor" where the fixed build would accept it. Both surface correctly in the build after the field classifies, against a field that then has a site and the ordinary rules; the build is already failing on the field's own error, which is always reported. R1027 refused that trade for `@globalData` because its rejection text *offers* `@globalData`, which makes the masking fix a likely path; with the walk confined to table-backed types and connection wrappers, the untenanted fix of a field returning tenant-scoped rows is neither offered nor natural. *Under-counting*: a field the walk stops on (a payload, a result type, a nesting type) whose fix does hand a tenant down. Its subtree's consequential errors stay reported, which is today's behaviour, unchanged.

The cross-scope condition of R1027 has no counterpart. It excludes a site whose one statement touches tenant-scoped and global tables, which no binding can route. An SDL return type whose closure holds both, the motivating `Node` among them, says nothing about one statement: node dispatch routes each type on its own, and `siteEstablishesContext` already counts routable node dispatch as establishing for every type in `Node`'s closure, global ones included. Counting the rejected `Node` root is the same answer.

## Implementation

All in `graphitron/src/main/java/no/sikt/graphitron/rewrite/TenantBindingIndex.java`.

1. `Fold` keeps the classification-rejected coordinates as a `Set<FieldCoordinates>`, collected in the same pass over `fields` that `sitesOf` makes, so "rejected" and "has a site" come from one read (flat only; a nested rejection already sits at the embedding coordinate).
2. A private `sdlReachesTenantTable(FieldCoordinates edge)` implementing condition 2 as `closeForward(edgeTargets.get(edge), e -> passesThrough(e.getTypeName()))`, then testing the closed set for a `TableBackedType` whose `table()` is `tenantScoped`. `passesThrough` is a sealed switch on the parent's `GraphitronType`: true for `ConnectionType` and `EdgeType`, false otherwise. Reusing `closeForward` keeps one traversal (and its domain filtering) in the fold rather than a second hand-written loop. It reads only the SDL edges and the classified types, never ancestor context, so it can run before the folds.
3. The counted set: every classification-rejected coordinate that is an in-domain edge, not `globalMarked`, and `sdlReachesTenantTable`. Computed once in `run`, in the loop that fills `countedSites`, so both are ready before `foldAncestorContexts`.
4. `edgeEstablishesContext`: after the `fanMarked` rung, a counted edge answers true. `edgeDivinesTenant`: a counted edge answers true. The site-based answers are untouched, so every edge that has sites, and every site-less connection-wrapper edge, behaves exactly as today.
5. Javadoc: on the counted set (what is counted, the SDL-reach proxy and its pass-through set, why only failing builds see it, and the bounded cost both ways). Say there too that the arms children get below a counted edge are counterfactual and reach no consumer: the only main-source readers of `TenantBindingIndex` are the validator, which reads `rejections()`, and `EntityFetcherDispatchClassGenerator`, which reads `byEntityType`, and neither runs on these arms in a failing build. A future diagnostics consumer that shows arms must revisit this. Add a sentence each on `edgeEstablishesContext`, `edgeDivinesTenant` and `foldAncestorContexts` naming the classification-rejected case. No change to `armOf`, `checkedArmOf`, the sites, any `TenantBinding` arm, the emitters, `graphitron-model` or the validator.

This builds on R1027 (In Review on trunk), which reshaped the same predicates: `countedSites`, the `DispatchFact` seeds, and the routed-entry seed of `belowBoundEdge`. The edge-level answer is a sibling of R1027's site-level one, not a change to it.

## Tests

All in `TenantBindingClassificationTest` (unit tier, classification only). Each fixture uses `@notGenerated` as the classification rejection and asserts the field is an `UnclassifiedField`. "Alone" means `tenantBindings().rejections()` is empty, the field's own rejection being the validator's.

- **The sis shape:** a tenant-scoped node type `Film` (its key, `film_id`, carries the tenant) with `Film.inventories: [Inventory!]! @splitQuery`, reached through an ordinary `Query.node` and through `Query.legacyNode(id: ID!): Node @notGenerated`; `Film.inventories` inherits, alone. Today the rejected root's edge voids `Film`'s context despite `Query.node`, and `Film.inventories` rejects "no ancestor established".
- **A list root:** the Goal's `Query.films: [Film!]! @notGenerated`; `Film.inventories` inherits, alone.
- **Through a declared connection:** `Query.films: FilmConnection @notGenerated` over an SDL-declared `FilmConnection`/`FilmEdge`; `Film.inventories` inherits, alone. Witnesses that the walk passes through both wrapper kinds, and (beside the passing connection tests that already exist) that site-less wrapper edges keep passing context on.
- **A result type stops the walk:** a rejected root `@service` (a service class that does not resolve, or `@notGenerated`) returning a non-table `Report { films: [Film!]! }`; the tenant-scoped field below `Film` keeps its "no ancestor established" error.
- **Global reach stays void:** the Goal's `Query.languages: [Language!]! @notGenerated` with `Language.films: [Film!]! @reference(path: [{key: "film_language_id_fkey"}])`; `Language.films` still rejects "no ancestor established a tenant context".
- **`@globalData` excluded:** `Query.films: [Film!]! @notGenerated @globalData`; `Film.inventories` still rejects "no ancestor established", beside the marker sweep's rejection of the unreached marker.
- **Fan-out below:** `Query.films: [Film!]! @notGenerated` with `Film.inventories @tenantFanOut`; the marker rejects "sits under a tenant-bound ancestor".
- **A nesting type stops the walk:** a nesting type under a context-less global `@table` parent with one child that fails classification, so the embedding field is the `UnclassifiedField`, and a tenant-scoped field below the nesting type; it keeps its "no ancestor established" error, as it would once the nesting field classifies and passes on its parent's (absent) context.

No pipeline, compilation or execution test changes: they run passing builds, which have no classification-rejected field. A change in one contradicts the "no passing build has an `UnclassifiedField`" premise and is a finding, not an edit to make. Other unit tests whose failing fixtures carry a classification-rejected field and assert tenant rejections may drop consequential errors; each such edit is that drop and nothing else.

## Other solutions we've considered

- **Count every classification-rejected edge.** Simpler, and a single predicate. Rejected because a rejected field over a global table is fixed into a global read that hands nothing down, so its subtree's "no ancestor established" errors are real and would be hidden for a round with nothing gained.
- **Count every site-less edge.** Would read the SDL-declared connection wrapper edges, which are site-less in passing builds, as establishing, and so change passing builds' classification (a wrapper edge below a context-less parent would hand down a tenant no one supplied). Keying on `UnclassifiedField` makes "failing builds only" structural.
- **Use the return type's structural closure alone, without walking through connections.** Misses the commonest root shape, a connection, whose own type is not a `@table` type.
- **Walk through every non-table type.** Catches payloads too, but walks past nesting and result types whose fields are sites of their own once the build passes; the nesting case then pins a wrong answer outright (a fixed nesting field hands nothing down), and the result-type case masks the real error of the most natural fix. Payloads would need an argument of their own; none is offered here, and they keep today's behaviour.
- **Suppress consequential errors after the fact** (drop every "no ancestor established" error traced to a classification-rejected edge). Needs a provenance trail the fold does not have, and leaves the `@tenantFanOut` ladder judged against a context that will not exist; R1027 rejected the same alternative for the same reason.
- **Leave it.** sis cleared its instance by removing the directive. But any classification error on a root returning `Node` or a widely reached type reproduces the cascade, and the field's own error is the one buried.
