---
id: R1203
title: "Regenerating after a schema edit stops on a duplicate row the participant MERGE meets, where a fresh store builds"
status: Backlog
bucket: bug
priority: 3
theme: diagnostics
depends-on: []
created: 2026-11-20
last-updated: 2026-11-20
---

# Regenerating after a schema edit stops on a duplicate row the participant MERGE meets, where a fresh store builds

## Goal

A consumer who edits its schema and runs `generate` again, without `clean`, gets the same build a fresh store gives it. Today the re-run can stop inside the store, on a primary-key collision in the participant anchoring, where the same schema builds from an empty store.

## What was seen

Found on 2026-11-20 while building sis against trunk `4df4e926e` for the consumer run R534 records. `generate` keeps its store under the module's `target/graphitron-model` (the goal takes no `graphitron.store.directory`), so each run after the first re-captured the edited documents into the store the previous run left. Two of five such re-runs stopped with:

```
merge into "PUBLIC"."GRAPHITRON_OCCURRENCE_PARTICIPANT" using (select ... from "PUBLIC"."GRAPHITRON_OCCURRENCE_PARTICIPANT_RULE" where ... "GRAPH_NAME" = ?) t
  on (... "GRAPH_NAME" = t."GRAPH_NAME" and ... "COORDINATE" = t."COORDINATE" and ... "PARTICIPANT_TYPE" = t."PARTICIPANT_TYPE") ...
Unique index or primary key violation: "Merge using ON column expression, duplicate _ROWID_ target record already processed"
```

The statement reads `graphitron_occurrence_participant_rule` for the whole graph and matches on the table's primary key, so at that point the view yielded two rows for one `(graph_name, coordinate, participant_type)`. The same schema, from an empty store, generated and compiled each time, and after the failed run the stored state no longer yields a duplicate, so it takes a replay of the edit sequence to see the rows.

The two failing edits, each on a store the previous run had built:

* After a first run that refused 28 fields: eleven types declared `@node(resolvable: false)`, two `@reference` paths lengthened, two input fields removed, and a second `@key` removed from `PersonProfil` (a type that implements `Node`).
* After a run that built: an `extend type FeideBruker { personProfil: PersonProfil ... }` block removed, leaving `FeideBruker` a stub entity with a non-resolvable key.

Both edits take away a way of reaching `PersonProfil`; the three re-runs that passed touched no field or key of it. That is a lead, not a cause.
