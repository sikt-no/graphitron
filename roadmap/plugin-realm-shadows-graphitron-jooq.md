---
id: R951
title: "A consumer pom that adds a dependency to the plugin realm can shadow the jOOQ graphitron parses with, and the store will not open"
status: Backlog
bucket: dx
priority: 1
theme: dev-loop
depends-on: []
created: 2026-09-15
last-updated: 2026-09-15
---

# A consumer pom that adds a dependency to the plugin realm can shadow the jOOQ graphitron parses with, and the store will not open

## Goal

A consumer whose build adds a dependency to the graphitron plugin's own `<dependencies>` gets a
working `graphitron:dev` and `graphitron:generate`. Today it can get neither, with a failure that
names a view body and points at nothing the author wrote or can change.

## What happens

Found while trying to price the refresh pass on the `sis` consumer. The module's pom adds
`sis-service` to the plugin block's `<dependencies>`, which is the ordinary way a consumer puts its
own service classes where the classpath scan can read them. That dependency brings
`org.jooq.pro:jooq:3.19.18` into the plugin realm, where graphitron's own `org.jooq:jooq:3.20.11`
already is. The two artifacts carry the same package and class names under different group ids, so
Maven's version mediation never compares them, both land in the realm, and which one answers a given
class load is an ordering accident.

When the older one answers, the failure is this:

```
graphitron: could not open the fact store at <cache>/...-dev:
the stored definition of view intent_field_scope_table_live did not parse,
and a definition walk reads it: Token ')' expected: [69:9]
```

`ViewReferences.parse` reads each registered rule's stored definition back with
`dsl.parser().parseQuery` so `MaterializeDependencies.populate` can derive the refresh order, and
H2 renders that view's derived table with its `UNION ALL` arms parenthesised. jOOQ 3.20.11 parses
that; 3.19.18 does not. So the store cannot be created at all, on every goal, and no schema the
author writes makes any difference.

Three things make this worth an item rather than a consumer-side note. The failure is total, not
degraded: `openAt` cannot fall back to warmth because the schema never gets created. It is invisible
in this repository, every reactor test booting a store with one jOOQ on the classpath. And it is
silent about its cause: nothing in the message mentions jOOQ, a realm, or the consumer's own pom, so
the reading an author will reach for first is that their schema broke the generator.

## Plan

Not settled; this is a Backlog stub with the diagnosis in it. Two directions worth pricing against
each other, and they are not exclusive.

Stop depending on the ordering. The parse exists to answer which relations a rule reads, and the
relations a rule reads are a function of the DDL, which is a resource this project ships and hashes
already. Deriving the read sets at build time from the authored DDL, rather than at boot from the
engine's rendering of it, removes the parser from the boot path entirely and would also remove the
per-boot cost of the walk. That is the larger change and probably the right one.

Or make the realm's jOOQ unambiguous, by whatever the plugin can do about a dependency it does not
control: an exclusion the plugin declares, a check at startup that names the clash in the author's
terms, or both. A check alone does not fix anything, but a failure that says which two artifacts are
present and which pom line added one of them is worth a great deal more than the current message,
and it is cheap.

Whatever lands, the acceptance is the same: a module whose plugin block adds a dependency carrying a
second jOOQ opens its store and completes a round.
