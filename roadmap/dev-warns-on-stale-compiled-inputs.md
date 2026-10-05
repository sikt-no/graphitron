---
id: R987
title: "graphitron:dev warns when the compiled classes or jOOQ catalog it reads are older than their sources"
status: Backlog
bucket: dx
priority: 5
theme: dev-loop
depends-on: []
created: 2026-10-05
last-updated: 2026-10-05
---

# graphitron:dev warns when the compiled classes or jOOQ catalog it reads are older than their sources

## Goal

When the compiled inputs `graphitron:dev` reads are older than their sources, the developer sees one plain warning before any of the "could not be resolved" errors this causes. The warning names the stale module or catalog artifact and gives the command that fixes it. Today `graphitron:dev` uses sibling modules' `target/classes` and the jOOQ catalog as it finds them and never builds them (the documented caveat under "Multi-module projects" in `docs/architecture/how-to/dev-loop-internals.adoc`). After a `git pull` that brings in new migrations or service methods, the schema is checked against old classes, every new name shows up as an author error, and the developer ends up debugging a schema that is correct.

## Motivating case

On opptak, `graphitron:dev` reported 98 author errors against a correct schema. Every one was a lookup that came back empty: a service method, a record or exception class, a table, a foreign key or a column. Every missing name existed in source. Every module's `target/` dated from 23 July, and the installed `opptak-jooq-1.0-SNAPSHOT.jar` from 21 September. The sources were current to 5 October: migration `V398__tidlig_opptak_melding.sql` landed on 1 October and `ManglerDatoForAldersberegningException.java` on 28 September. Before it showed those 98 errors, dev should have shown one line, roughly:

> `opptak-service` was last compiled 2026-07-23, but its sources changed 2026-10-05. Graphitron is checking your schema against the old classes, so anything added since then shows up as "not found". Run `mvn install -DskipTests` in `fs-plattform/opptak`, then restart `graphitron:dev`.

## What "intuitive" asks of the warning

- It names what is stale (a module or the catalog artifact), both dates, and the fix as a command the developer can copy. It does not name an internal mechanism.
- It appears once per stale input, not once per affected coordinate. It shows in the console at dev startup, in the MCP `status` and `diagnostics` answers, and as an LSP diagnostic, so a developer who only looks at the editor still sees it.
- The "could not be resolved" diagnostics it explains point back to it, or the triage view groups them under it, so 98 entries read as one cause with 98 consequences.
- It is a warning, not an error, and it never blocks generation. A false positive (sources touched without a semantic change, or a checkout that resets mtimes) costs one line of noise, not a stopped loop.

## Open questions for Spec

- **What "older than its sources" compares.** For a sibling module, the cheap answer is the newest mtime under `src/main` against the newest under `target/classes`. For the jOOQ catalog, the inputs are migrations or DDL in another module, so the check has to know where the catalog came from (a reactor `target/classes` or a jar in the local repository) and what generated it. A sound approximation is enough.
- **Whether the dev loop already holds the stamps.** The source census already records `java_file` stamps, so the staleness check may be a query against facts the store already captures rather than a new filesystem walk.
- **When to re-check.** At startup at minimum. Open: whether rebuilding the sibling while dev runs should clear the warning live.
