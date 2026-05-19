---
id: R180
title: "Centralize ResultType column-read emission for @record parents"
status: Backlog
depends-on: []
created: 2026-05-19
last-updated: 2026-05-19
---

# Centralize ResultType column-read emission for @record parents

Every emitter that needs to "read a named column off the parent record"
reconstructs its own four-arm switch over `ResultType` and emits subtly
different code per arm: `JooqTableRecordType` → `.get(Tables.X.COL)`,
`JooqRecordType` → `.get(sqlName)`, `JavaRecordType` → `.camelCase()`,
`PojoResultType.Backed` → `.getCamelCase()`. The same predicate (which
kind of result parent? which Java syntax reads a column?) is evaluated
at every site, and each site picks its own subset of arms:
`propertyOrRecordValue` collapses two arms with `||`, `buildFkRowKey`
keeps all four, `backingClassOf` throws on two of them.

The duplication is a drift hazard: when a fifth `ResultType` variant is
added (or a current arm's emission shape changes), each site has to be
audited and patched independently, and the subset-selection asymmetry
makes it easy to miss one. A single dispatcher (likely a
sealed-`switch` utility, e.g. `RecordColumnReads.read(parent, name)`)
would centralize the predicate without coupling the model to a Java
emission concern. Sites that legitimately reject a subset of arms keep
their own explicit guards at the callsite.

## Question to answer

Is the per-site arm variation incidental drift (collapse it) or
meaningful policy (keep the guards explicit, only centralize the emit
shape)? A 30-minute audit of the four callsites above, plus a sweep
for any other `instanceof ResultType` ladders, would settle it.
