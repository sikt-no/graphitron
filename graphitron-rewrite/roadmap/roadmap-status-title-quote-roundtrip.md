---
id: R306
title: "status subcommand strips quotes from colon-bearing titles"
status: Backlog
bucket: cleanup
depends-on: []
created: 2026-06-14
last-updated: 2026-06-14
---

# status subcommand strips quotes from colon-bearing titles

The roadmap-tool `status` subcommand corrupts any front-matter scalar that
requires YAML quoting, most visibly a `title:` containing a colon (e.g. R290's
`"R290: ..."`). `parseFrontMatter` loads the front-matter through snakeyaml,
which strips the quote characters during parsing, so `title: "R290: foo"`
becomes the Java string `R290: foo`. `applyStatusTransition` then re-serializes
every field by hand (`out.append(key).append(": ").append(value)`) with no
re-quoting, writing the bare string back as `title: R290: foo`. The quotes are
lost on *every* transition, and the result is ambiguous YAML (an unquoted scalar
containing `: `). By contrast the `create` subcommand quotes the title
correctly, so the damage only appears once an item starts moving through the
state machine. The quotes have to be restored by hand after each transition,
which is silent data corruption in a tool whose whole job is safe front-matter
round-tripping.

The fix should re-quote scalar values that need YAML quoting when re-emitting
front-matter in `applyStatusTransition` (and audit any other hand-rolled
front-matter writers for the same gap). Options range from a minimal "quote when
the value contains a YAML indicator" helper to dumping the map through snakeyaml
instead of the hand-rolled serializer; the Spec should weigh round-trip fidelity
against keeping the deterministic field ordering and formatting the current
writer guarantees. A regression test should cover a colon-bearing title
surviving a full transition round-trip.
