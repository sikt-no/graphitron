---
id: R590
title: "Verify gate for the generated migration fragment"
status: Backlog
bucket: cleanup
theme: docs
depends-on: []
created: 2026-08-04
last-updated: 2026-08-04
---

# Verify gate for the generated migration fragment

`docs/manual/_generated/supported-schema-shapes.adoc` is a committed materialized view over the classifier traces (`LeafCoverageReport`, migration mode), but unlike its sibling `supported-directives.adoc` it has no `--verify` execution: the CI leaf-coverage step regenerates a report artifact without comparing the committed fragment, so a regeneration from a partial trace set commits silently. This has bitten twice in one programme, in both directions (a shipped shape advertised as `(not yet supported)` in the user manual's migration page, and the inverse), and each time the error was caught by hand at a review gate rather than by the build. Add a verify-mode execution for the migration fragment so a full-trace mismatch fails the build the same way the directive-support fragment already does, and decide where it runs (the trunk-gated CI step has full traces; a local `-pl` build does not, so the gate needs the same no-traces short-circuit the tool already carries).
