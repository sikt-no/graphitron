---
id: R168
title: "Sub-agent classifier for blast-radius effort (Low/Medium/High) at Spec stage"
status: Backlog
bucket: cleanup
priority: 50
theme: tooling
depends-on: []
created: 2026-05-16
last-updated: 2026-05-16
---

# Sub-agent classifier for blast-radius effort (Low/Medium/High) at Spec stage

The roadmap roll-up sorts by `priority:` but carries no signal about how big a
piece of work each Active item is. A reader scanning the table cannot tell
whether `Ready` means "one afternoon" or "a multi-phase lift across four
modules", and the author setting `priority:` is making that judgement implicitly
without surfacing it. Add an `effort:` front-matter field with values `Low |
Medium | High` defined as **blast radius** (files touched, design forks, test
tiers reached), populated by a sub-agent classifier at the **Spec stage**
(Backlog stubs are too thin to grade against and explicitly do not carry the
field). The classifier reads one plan file at a time and emits a single bucket,
so a batch reclassify across ~30 Active items costs roughly one normal turn's
worth of tokens. Render the field as a column on the Active table in
`roadmap/README.md` and as an attribute in the per-plan AsciiDoc page; gate the
validator so an `effort:` value on a `Backlog` item is a hard error.

## Question to answer

- **What counts as blast radius?** Working definition: Low = one module, no
  design fork, additive or single-file refactor; Medium = one or two modules,
  one design fork, touches one test tier; High = three or more modules, multi
  phase, or reaches the execution tier. Needs to be encoded in the classifier
  prompt and pinned in `docs/workflow.adoc` so manual overrides have a shared
  rubric.
- **Where does the classifier live?** Options: (a) a new `classify-effort`
  subcommand on `roadmap-tool` that shells out to `claude` per item, (b) a
  hand-rolled bash loop that dispatches sub-agents, (c) a one-shot manual
  pass with the existing `Agent` tool. Option (a) is the only one that
  composes with CI verify; the other two are starting points.
- **Re-run policy.** Effort estimates rot fast (plans get re-scoped, phases
  ship). Re-classify on demand, not on every transition. The subcommand
  should accept `--stale-only` (skip items where the field is set and
  `last-updated:` is newer than some watermark) and `--force` (reclassify
  everything).
