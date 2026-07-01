---
id: R107
title: "Classify leaf mentions in inference-axis-coverage report"
status: Backlog
bucket: validation
theme: structural-refactor
depends-on: []
---

# Classify leaf mentions in inference-axis-coverage report

`LeafCoverageReport.parseMentions` (R104) joins each sealed leaf simple-name against every roadmap `*.md` body via a `\b<simpleName>\b` regex. The match is undifferentiated: backticked code spans, code-fenced blocks, and bare prose mentions all collapse into the same `Roadmap` cell. Two consequences. First, every roadmap edit that names a leaf in any form drifts `inference-axis-coverage.adoc` and trips the `verify-leaf-coverage-report` CI gate, which is the regen-friction tax R104 deferred. Second, a reviewer reading the column has no way to sanity-check a match — `Field` against `FieldType` is excluded by `\b`, but a phrase like "the field type" cannot be told apart from a deliberate `` `Field` `` symbol reference without re-reading the source spec body.

The post-processor already has the full sealed-leaf inventory at parse time, so this is a typing problem, not a data-availability problem. Classify each mention into a sealed `Mention.{Backticked, CodeFenced, Prose}` (or equivalent) at parse time and surface the kind in the report — either as a separate column or by collapsing prose-only matches into a paler "weak mention" rendering. That changes the column's contract from "any token-level overlap" to "this spec references this leaf as a code symbol", which is the intent the column was added for.

Out of scope for this item: changing the regen-friction itself (a workflow change to auto-regen on roadmap touches is a separate question), and any change to the sealed-leaf-inventory parser. This item is purely about the mention join.
