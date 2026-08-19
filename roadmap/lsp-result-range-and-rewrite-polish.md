---
id: R739
title: "Consolidate LSP byte-range result construction and rewrite/dedup nits"
status: Backlog
bucket: architecture
priority: 7
theme: lsp
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# Consolidate LSP byte-range result construction and rewrite/dedup nits

Residue of a discarded consolidation umbrella whose shipped slices left `graphitron-lsp` with
one navigation home (`Nodes` / `GraphqlNodeKind`), one directive-policy table (`DirectivePolicy`),
and one completion dispatcher (`Completions`), and whose remaining premises the fact-store
migration made moot. Three small facts survived a 2026-08-19 audit of the module and are worth
one polish pass:

- **Outbound byte-range → lsp4j `Range` construction has no single home.** The inbound direction
  is consolidated (`Positions` owns LSP position → byte offset), but the outbound build is
  re-implemented in `Hovers`, `DeclarationHovers`, and a few sites in `Diagnostics`. A small
  shared helper (the `Positions` sibling direction) retires the copies.
- **`CodeActions` runs each rewrite three times per match.** The partition into apply / preview /
  edit maps the same `action.rewrite().rewrite(file, m)` over the match list three times
  (`CodeActions.java:178/186/193`); materialize the result once per match, then partition.
- **The recalc queue dedups by linear scan.** `Workspace.enqueueTouched` guards its add with
  `List.contains` (`Workspace.java:346`); a `LinkedHashSet` keeps order and drops the O(n) probe.

All three are behaviour-preserving; the existing `graphitron-lsp` test suite is the oracle.
Verify each claim against the tree before starting, since the module moves quickly.
