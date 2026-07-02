---
id: R426
title: "TableRecord-sourced @service keys are partial records; service bodies reading non-key columns get silent nulls"
status: Backlog
bucket: bug
priority: 6
theme: service
depends-on: []
created: 2026-07-02
last-updated: 2026-07-02
---

# TableRecord-sourced @service keys are partial records; service bodies reading non-key columns get silent nulls

A `@service` child whose `Sources` parameter is typed-`TableRecord` (e.g. `Set<FilmRecord>`,
`SourceKey.Wrap.TableRecord`) receives keys built via
`((Record) env.getSource()).into(Tables.X)` (`GeneratorUtils.buildKeyExtraction`). That record
is *partial*: it carries only the columns the parent `$fields` SELECT projected (the GraphQL
selection plus the force-included key columns; after R425 the key columns are guaranteed).
Nothing stops a developer's service body from reading a non-key column off that record
(`key.getTitle()` when only `film_id` + selected columns are populated); the read returns
`null` with no error, the same silent-null failure family as R425 but on the developer side of
the service contract rather than the generated side. The guaranteed-projected set is
statically known (key columns); which columns a service body reads is not. Candidate
directions, to be weighed at Spec: document the contract ("a TableRecord source is a key,
only key columns are guaranteed populated") in the user manual's service chapter; reject or
warn at validation time where detectable; or have the extraction populate only the key
columns explicitly (making the partiality uniform and obvious) rather than `.into(Tables.X)`
passing through whatever the selection happened to project. Surfaced by the R425
principles-architect consult; see that item's spec for the projection-side analysis.
