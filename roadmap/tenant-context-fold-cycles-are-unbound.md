---
id: R986
title: "The tenant-context fold reads every cycle as unbound, so a type reachable both ways is rejected under a bound root"
status: Backlog
bucket: bug
theme: runtime-connection
depends-on: []
created: 2026-10-01
last-updated: 2026-10-01
---

# The tenant-context fold reads every cycle as unbound, so a type reachable both ways is rejected under a bound root

## Goal

A schema whose types navigate to each other in both directions validates when every way in from a
root carries the tenant. Tenancy here is database-per-tenant: a field reading a tenant-scoped table
must know which tenant's connection to run on, either because its own arguments name the tenant
column (it binds) or because every path from a root to its type passes a field that binds (it
inherits a tenant context). Today any type on a cycle of navigation is held to have no context, so
an author who binds the tenant at every root still gets a `noTenantBinding` rejection on each
tenant-scoped field inside the cycle. The outcome is a fold that judges a cycle by the edges
entering it from outside, so the every-path guarantee holds and bidirectional navigation stops
costing it.

## What is true today

`TenantBindingIndex.Fold.tenantContextOf` asks of a type whether every reaching edge establishes or
transmits a context, recursing into the edge's parent. A type met again while its own answer is
in progress answers `false`, and that answer is memoised for every type computed under it. Take
`A` reached by `B.a`, and `B` reached by `Query.b`, which binds, and by `A.b`. Computing `A` asks
`B`, computing `B` asks `A`, which is in progress and so `false`; `B` is cached as `false` and `A`
follows. That is the least fixed point, and the javadoc states it as intended ("roots, unreached
types, cycles ... fold to false"). The every-path property wants the greatest one: a cycle adds no
path from a root, so assuming a context for a type in progress and disproving it through any
unbound edge from outside its cycle is what "every path" means. Present since the per-field tenant
binding axis landed (`334c6d595`, 2026-07-20). No test in `TenantBindingClassificationTest` builds
a cycle.

Observed on sis (2026-10-01): 595 tenancy rejections, one of them a genuinely unbound root
(`Query.godkjenningssakstatuskoder`, held on purpose), every other one "no ancestor established a
tenant context". A strongly-connected-component pass over the SDL field-type graph, interface
implementors included, finds one component of 121 types (`Emne`, `PersonProfil`,
`StudentVedLarested`, `Studieprogram` among them). 514 rejections sit on types in a cycle and 43
only downstream of one. The other 36 sit on payload types under `@service` mutations whose root
field does not bind; those are a separate question about service roots and are not this item.

## Direction

Fold per strongly connected component of the reaching-edge graph: a component has a context when
every edge entering it from outside the component establishes or transmits one, and every member
inherits that answer. Two things the change has to keep straight:

* **An unreached type is not an entry.** `computeTenantContext` already answers `false` for a type
  no field reaches, and under today's fold such a type's edges poison every type they point at. A
  type no root reaches never executes, so its edges say nothing about the paths that do; the fold
  should either compute over root-reachable types only or treat an unreached parent as vacuous.
  Whether sis has such types is unchecked.
* **The dispatch surfaces stay where they are.** The node and entity checks at the top of
  `computeTenantContext` are entries from outside the field graph and keep vetoing a component the
  way they veto a type today.

A test builds the `A`/`B` cycle above with a binding root and asserts `Inherited` on both, beside
one where the cycle is also entered by an unbound root field and both reject.
