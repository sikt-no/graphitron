---
id: R188
title: "Simplify UPDATE mutations: drop @value, infer SET/WHERE from PK metadata"
status: Backlog
bucket: cleanup
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# Simplify UPDATE mutations: drop @value, infer SET/WHERE from PK metadata

UPDATE mutations currently partition `@table` input fields by an explicit `@value` directive: marked fields become the SET clause, unmarked fields become the WHERE clause (required to cover the PK unless `multiRow: true`). For the overwhelming common case, this partition is mechanical and the schema already implies it via PK metadata: PK columns identify the row (WHERE), non-PK columns carry the new values (SET). The `@value` directive, the paired structural diagnostics ("no @value fields", "every field is @value-marked"), and the load-bearing classifier check `mutation-input.update-set-fields-equal-value-marked` (see `MutationInputResolver.java:319-323`) all exist to police a partition the catalog already knows. Non-PK row lookup ("update where `email = X`") is the genuinely interesting case and is better expressed through `@condition`, which is already the established mechanism for non-PK filtering and which is what users reach for anyway when the row-identity story diverges from the PK. Dropping `@value` removes a directive, two diagnostics, a load-bearing classifier check, and a structural-symmetry mismatch with INSERT (which has no SET/WHERE partition at all).

Open questions to settle in Spec: (1) the fate of the unique-index-covering WHERE-coverage rule from R130 / `mutation-cardinality-safety-unique-index` — whether the relaxation moves to `@condition` entirely or whether unique-index covering still admits non-PK row lookup from input columns; (2) the revised meaning of `multiRow: true` — under the new shape WHERE comes from PK (always row-identity) or `@condition` (potentially multi-row), so `multiRow:` only makes sense paired with `@condition`; (3) the parallel rewrite of R145 (UPSERT), which currently leans on `@value` to partition the conflict-target from the SET values; (4) migration story for any existing UPDATE schemas in `graphitron-fixtures-codegen` and the Sakila example that use `@value`.
