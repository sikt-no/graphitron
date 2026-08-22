---
id: R669
title: "RootLauncherRendererTest asserts on generated body strings"
status: Backlog
bucket: tech-debt
priority: 3
theme: testing
depends-on: []
created: 2026-08-14
last-updated: 2026-08-14
---

# RootLauncherRendererTest asserts on generated body strings

`RootLauncherRendererTest` asserts on the rendered text of generated method bodies. It calls
`render(row).code().toString()` through a `body(...)` helper and then matches literal Java and jOOQ
source fragments against it, roughly 33 `contains` / `doesNotContain` calls across its arms
(`.contains("rows = filmByIdInputRows(env, filmTable)")`,
`.contains(".values(rows).as(\"filmByIdInput\", \"idx\", \"film_id\")")`, and so on).
`docs/architecture/principles/development-principles.adoc` bans exactly this: "Code-string
assertions on generated method bodies are banned at every tier: they test implementation, not
behaviour, and break on every refactor", with the compile and execution tiers named as the
replacement. `docs/architecture/how-to/testing.adoc` repeats the ban for the neighbouring
sub-families. The ban is review-enforced rather than build-enforced, and this file is where it has
drifted furthest.

The reason to file it rather than fix it opportunistically is that the file is not wrong about
*what* it wants to pin, only about how. A renderer is a total function over a command's sealed arms
and per-arm coverage of the emit is genuinely valuable; `testing.adoc`'s renderer-arm-test paragraph
says so. The question the item has to answer is what the structural form of each assertion should be
once the body string is off the table: `MethodSpec` shape (name, return type, parameters) is already
asserted separately in the same file, the SQL that actually reaches PostgreSQL is pinned by
`RootLauncherSqlBaselineTest`, and emitted runtime helpers can be invoked reflectively the way
`ScatterLookupByIdxTest` does. A plausible outcome is that most of the 33 assertions are already
covered at a sanctioned tier and simply delete, with a residue that needs a baseline or a
reflective-invocation test built for it. The audit is the work; the deletions are cheap.

Provenance: found at the third In Review gate on the lookup positional-contract item, which added
three such assertions for its scatter arm. That item removes its own three; this one covers the
pre-existing remainder, which is out of its scope.

