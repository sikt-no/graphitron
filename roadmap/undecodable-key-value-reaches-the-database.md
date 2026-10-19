---
id: R1067
title: "A node id or tenant value that does not fit its column is not caught at decode"
status: Backlog
bucket: bug
priority: 3
theme: nodeid
depends-on: []
created: 2026-10-19
last-updated: 2026-10-19
---

# A node id or tenant value that does not fit its column is not caught at decode

## Goal

A node id, entity representation or tenant argument whose key value cannot be a value of its
column (non-numeric text for a numeric column, a number that overflows the column's precision)
is caught when it is decoded, and gets the documented outcome, instead of reaching the database or
surfacing as a top-level error that nulls the whole response.

## Evidence

Surfaced by the sis v9 against v10 differential test (2026-10-19):

- In a mutation, the `Land` node id `62:abc` decoded to `LANDNR = -32768` and reached the database
  (ORA-01438). A conversion failure became a sentinel value that was written. `62:99999` returned the
  raw Java text "Value out of range..." instead of "Invalid node id...".
- In `node(id:)` and `_entities`, a key value that overflows its column gives a redacted error, and
  on `_entities` the whole `data` becomes null.
- A non-numeric tenant value (`eierOrganisasjonskode: "abc"`, `""`, `" 1234"`, `"99999999999"`, or a
  node id `20:abc,...` whose tenant slot does not parse) gives the top-level error
  "Tenant value 'abc' is not a value of the tenant column."; on `_entities` the whole `data` becomes
  null.

The manual sets a different expectation. `how-to/global-id.adoc` says `node(id:)` answers null ids,
base64 errors and unknown type ids with `null`, and `how-to/tenant-scoping.adoc` section 2 says the
lookups (`node`, `nodes`, `_entities`) answer an id whose tenant is outside the request's set with
`null`, "exactly as they answer an id that does not exist", resolving the rest of the batch. An id
whose key or tenant slot does not decode fits neither promise today: it errors, and on `_entities`
it takes the whole batch down.

## Scope for the plan

The node-id decode does not validate a key value against the column type before converting it; the
sentinel suggests a lossy `Convert` path. The plan has to decide, per surface (read lookup, `node`,
`_entities`, mutation argument, tenant argument), whether an undecodable value is `null` with no
error, `null` with a field error, or a client error, and make the manual and the generated code
agree. The `-32768` case is the urgent one: whatever the policy, a value that does not decode must
never be written.
