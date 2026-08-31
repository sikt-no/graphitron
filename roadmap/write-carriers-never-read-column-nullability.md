---
id: R881
title: "No write carrier reads column nullability, so a clear or a write into a NOT NULL column is only the database's to refuse"
status: Backlog
bucket: validation
priority: 4
theme: mutation-write
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# No write carrier reads column nullability, so a clear or a write into a NOT NULL column is only the database's to refuse

`JooqCatalog.ColumnEntry` carries `nullable` and the fact store captures `sql_column.nullable`, but no write-path walker reads either. A nullable scalar `@field` bound to a NOT NULL column is admitted on UPDATE, INSERT and UPSERT alike; an explicit null on it binds a typed NULL and the statement fails at the database with a constraint violation naming the SQL column, not the input field the author wrote. The same now holds for a cleared reference carrier once R880 lands, which is where this was split out: R880 declined to gate its own arm on column nullability, because a rejection keyed on the cross product of (cross-table FK) and (straddles the matched key) and (writes a NOT NULL column) would leave the identical hazard on a plain field unchecked and put the cross product under hand maintenance.

The question this item answers is whether column nullability should be an axis the write path reads at all, and if so at which grain. Three candidate answers, and the item exists to pick one rather than to assume the first. Leave it to the database, and improve nothing but the error text a consumer sees. Reject at build time, which is knowable from the schema and consistent with graphitron's other build-time gates, but which refuses a whole mutation because one caller *might* send a null the other callers never send. Or state it as a fact the emitters read and turn into a runtime error naming the input field before the statement runs, which is what the value-agreement check already does for a hazard that is only knowable from the wire. Note that the middle answer is not free of the same objection R880 raised: `ID!` and `String!` in an input type mean "mandatory on every call" as well as "never null", so a build-time gate spends a PATCH input's optionality to express a value constraint.

Whichever answer wins, it applies uniformly across write carriers rather than to one reference arm, and the honest scope is every SET and INSERT column carrier.
