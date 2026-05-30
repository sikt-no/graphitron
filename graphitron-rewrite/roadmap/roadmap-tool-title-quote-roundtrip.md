---
id: R264
title: "roadmap-tool status round-trip strips quotes from front-matter titles"
status: Backlog
bucket: cleanup
priority: 3
depends-on: []
created: 2026-05-30
last-updated: 2026-05-30
---

# roadmap-tool status round-trip strips quotes from front-matter titles

The `status` subcommand rewrites an item's front-matter through a lossy
load-then-hand-serialize round-trip. `applyStatusTransition` (`Main.java:344`)
parses the block with snakeyaml (`parseFrontMatter` at `Main.java:1027`, which
calls `new Yaml().load(yaml)`); the load returns the `title` value as a *bare*
Java string with its surrounding double-quotes already gone. It then
re-serializes the map by hand (`Main.java:365-372`):
`out.append(e.getKey()).append(": ").append(v)`, with no value-quoting logic at
all. So any `title:` whose unquoted form is not valid YAML is written back
broken. The trigger is a colon-space sequence (`": "`, e.g. `R256`'s
`"Absorb the service walker substrate: typed per-arm errors + multi-arg ctors"`):
the value is read as `Absorb the service walker substrate: typed ...` and
re-emitted as `title: Absorb the service walker substrate: typed ...`, which is
invalid YAML because the inner `: ` reads as a second mapping-value indicator.

This is silent and self-corrupting. The transition that triggers it appears to
succeed (the `status:` line is written correctly), but the very next parse of
that file, including the README regeneration the same subcommand runs
immediately afterward, throws
`org.yaml.snakeyaml.scanner.ScannerException: mapping values are not allowed
here` and aborts. Observed live during the `R256` Spec -> Ready flip on
2026-05-30: status was written, title was de-quoted, regeneration crashed, and
the file was left in a state that fails every subsequent tool run until the
quotes are restored by hand. A title with a bare colon-without-space
(`graphitron:dev` in `R212`) is unaffected, since that is a valid plain scalar;
only `": "` triggers it, which is exactly the common "subtitle: detail" title
shape.

The fix lives in the hand-serialization at `Main.java:365-372`: quote any value
whose round-trip is not safe unquoted (minimally, wrap in double-quotes when the
value contains `": "`; ideally apply the general YAML plain-scalar rule). The
cheaper, less error-prone alternative is to stop round-tripping through a
`load` at all on the write path: preserve the original front-matter lines and
patch only the `status:` and `last-updated:` lines in place, leaving every
other line (including the already-correctly-quoted `title:`) byte-for-byte
untouched. That also removes the risk of the load/serialize pair silently
reformatting other values (lists, dates) on every transition. A regression test
should add an item whose title contains `": "`, run a `status` transition, and
assert the file re-parses and the title round-trips byte-for-byte. Audit
existing titles for the same shape while here. Low blast radius (one module,
no generator/runtime surface), but a real foot-gun: it corrupts an item file on
every affected transition and the failure mode points at the wrong file.
