---
id: R868
title: "A checkout that is never built again keeps its whole store home"
status: Backlog
bucket: cleanup
priority: 4
theme: tooling
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# A checkout that is never built again keeps its whole store home

The fact store now releases the stamped directories under the *home* it opened in, keeping the three
most recently used. That bounds one home. It does nothing about a home nobody opens any more.

A store home is keyed on the checkout path, so every checkout a contributor has ever built in has its
own home under the per-user cache root, and a checkout that is deleted or abandoned leaves its home
behind whole. Nothing opens a store there, so nothing sweeps it. On the contributor machine that
prompted the store sweep this was most of the reported 49 GB: 87 stamped directories spread over many
workspace segments, only a handful of which belonged to a checkout still in use.

## Why this is a separate mechanism rather than a wider sweep

Reaping across homes needs two facts the store does not have and must not guess: that the cache root
was resolved by graphitron's own default convention rather than pinned by a consumer to a directory
that may hold other things, and the checkout path each home segment was derived from, so its continued
existence can be tested. Both live in `AbstractRewriteMojo.resolveStoreDirectory`, which is the only
home resolver and the only place that knows whether it pinned or defaulted.

So the shape is a plugin-side caller handing the existing `StoreReaper` a list of homes, not a change
to the store's own sweep. The store keeps its rule (it reads and removes nothing outside a directory it
has positively recognised as a store's own, and never a directory another process holds), and the
plugin supplies the population.

## Open questions for the Spec

* What proves a workspace dead. A missing checkout directory is the obvious test and is not
  sufficient on its own: a home segment is a digest of the path, so the mapping back to a path has to
  be recorded somewhere rather than inverted.
* Whether the population is bounded by a count, as the per-home sweep is, or by the existence test
  alone. A count over homes is a different policy from a count over stamps and the argument for it is
  not the same one.
* Where it runs. Once per build is the obvious place and may be too often for a scan over the whole
  cache root.
