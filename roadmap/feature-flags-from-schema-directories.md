---
id: R1077
title: "Apply @feature(flags:) from the features/<flag>/ directory a schema file sits in, as Graphitron 9 did"
status: Backlog
bucket: feature
priority: 3
theme: tooling
depends-on: []
created: 2026-10-20
last-updated: 2026-10-20
---

# Apply @feature(flags:) from the features/<flag>/ directory a schema file sits in, as Graphitron 9 did

## Goal

Schema files placed under `features/<flag>/` get `@feature(flags: "<flag>")` applied to the
elements they declare, as Graphitron 9's `<transform><addFeatureFlags>` did, so the federated
`_service { sdl }` carries the same feature-flag applications a consumer's tooling filters on.

## Evidence and decision

Surfaced by the sis v9 against v10 differential test (2026-10-19): `@feature(flags:)` applications in
`_service { sdl }` fell from 8,870 on Graphitron 9 to 94 on v10, the 94 being the ones written by
hand in the SDL. v10 has no counterpart of the transform; nothing in the generator, the Maven plugin
or the manual mentions `@feature`. The user decided (relayed through the sis session, 2026-10-20) to
port an equivalent.

Graphitron 9's documented behaviour (`graphitron-schema-transform/README.md`, "Automatic Feature
Directive Application", deleted from this tree with the legacy reactor):

- Files in a direct subdirectory of `features/` get the directory name as their flag; files outside
  `features/` get none. Only the top level is recognised, not nested directories.
- The directive goes on fields, arguments and enum values those files declare.
- An element that already carries explicit flags keeps them; the automatic flag is not added.

sis lays its schema out this way (`schema/features/stable/`, `beta/`, `experimental/`).

## For the plan

Whether this is a generator option (mirroring `addFeatureFlags`) or always on when a `features/`
directory is present; where the application lives so that both the runtime schema and
`_service { sdl }` carry it; how it composes with `extend type` in one directory over a type
declared in another (which file's flag an extension's fields get); and whether the `@feature`
directive definition is supplied by Graphitron or expected in the consumer's SDL. Graphitron 9's
companion features (filtering a schema by active flags, splitting into per-flag output schemas) are
out of scope unless the plan finds a consumer depending on them.
