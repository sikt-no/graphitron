---
id: R348
title: "Regenerate and guard the generated supported-schema-shapes migration doc against drift"
status: Backlog
bucket: tech-debt
priority: 3
theme: docs
depends-on: []
created: 2026-06-19
last-updated: 2026-06-19
---

# Regenerate and guard the generated supported-schema-shapes migration doc against drift

`docs/manual/_generated/supported-schema-shapes.adoc` is a generated migration fragment (included by
`docs/manual/how-to/migrating-from-legacy.adoc`) produced by `graphitron-roadmap-tool leaf-coverage
--mode=migration` (`LeafCoverageReport.run`, migration branch). Like its sibling
`supported-directives.adoc` (R346), its header promises "Regenerate via the verify-mode CI guard,"
but no such guard exists: nothing in `.github/workflows`, the poms, or any script runs `leaf-coverage
--mode=migration`. The only `leaf-coverage` step in CI (`rewrite-build.yml` "Regenerate leaf-coverage
report") runs `leaf-coverage graphitron-rewrite` with no `--mode=migration`, regenerating the internal
`inference-axis-coverage.adoc` report, not this fragment. So the fragment silently drifts whenever the
classified leaf set changes, and a reader migrating off legacy is shown a stale schema-shape support
surface.

This is the identical gap R346 closes for `supported-directives.adoc`, split out because the guard has
a *different shape*: regenerating this fragment depends on build-time `leaf-coverage.jsonl` trace files
(populated by the leaf-coverage profile during `mvn verify`), so the verify diff cannot live in the
SDL-only `roadmap-tool` `verify` execution R346 adds; it must run after the test phase, closer to the
existing trace-consuming `leaf-coverage` step. Spec should reuse R346's `--verify` idiom
(`leaf-coverage` already has a `--verify` flag; wire it) and decide the post-test seam (a phase-bound
execution after tests, or a CI step that diffs rather than only uploading). Land alongside a
regenerate-once pass so the committed fragment matches current output.
