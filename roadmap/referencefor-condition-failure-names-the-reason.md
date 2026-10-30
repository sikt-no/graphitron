---
id: R1136
title: "A @referenceFor condition that cannot be resolved does not say why"
status: Backlog
bucket: bug
priority: 5
theme: diagnostics
depends-on: []
created: 2026-10-30
last-updated: 2026-10-30
---

# A @referenceFor condition that cannot be resolved does not say why

## Goal

When a `@referenceFor` route's condition method cannot be resolved, the rejection says why, as
the argument `@condition` path already does. Today it says only that the method could not be
resolved.

## The defect

The cause in kreg was a private helper `barnCondition(Field<Long>, Field<Long>)` next to nine public
`barnCondition(VKoX parent, VKoY child)` overloads. The rejection reads:

```
Field 'KregFag.barn': Field 'KregFag.barn': @referenceFor route for participant 'KregFag' could not
be resolved: condition method 'method 'barnCondition' in class
'no.sikt.nvb.graphql.conditions.KompetanseoppnaelseConditions'' could not be resolved.
```

The same mistake on an argument `@condition` (a helper `nasjonalIdCondition(Field<UUID>, String)`)
gets a message that explains it: "has 4 declarations (...): parameter 0 is neither a jOOQ Table
parameter in every declaration nor identical in name and declared type across them ... collapse
the set to a single method taking org.jooq.Table<?>". The `@referenceFor` message also doubles the
`Field 'KregFag.barn':` prefix and nests its quotes (`'method 'barnCondition' in class '...''`).

## Direction

Route the `@referenceFor` resolution failure through the same overload explanation the argument
path uses, and fix the doubled prefix and the nested quoting. A pipeline-tier case asserting the
rejection names the conflicting declarations.

Provenance: reported by the kreg Graphitron 10 port session (fs-plattform's kreg module on 10.0.0-RC46, branch `worktree-kreg-g10` at `7858137af4`, not pushed), 2026-10-30; the reporter found it present on graphitron `origin/main` the same day.
