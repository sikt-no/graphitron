---
id: R524
title: "Trim verbose javadoc and align comments with the terse-and-pinned conventions"
status: Backlog
bucket: cleanup
priority: 7
theme: docs
depends-on: []
created: 2026-07-24
last-updated: 2026-07-24
---

# Trim verbose javadoc and align comments with the terse-and-pinned conventions

Several main-source classes carry long narrative javadoc and comment blocks that were written while the design was still moving: they restate what the code already shows, walk through implementation history, or argue for decisions in paragraphs of unpinned prose. The R483 drift audit corrected claims that had become *wrong*, but its unit of work was accuracy, not length; a comment can be fully accurate today and still be the kind of prose that diverges as the code matures, because nothing mechanical breaks when it goes stale. The conventions have since firmed up (CLAUDE.md "Javadoc conventions": prefer terse over verbose, name live things via `{@link}` so the R492 reference gate checks them, no transient citations per the R482/R484 guards), and the existing comment stock predates them.

## Scope

A sweep over the in-scope modules' hand-authored sources (main and test), trimming toward the conventions rather than re-verifying facts:

- Delete prose that restates the code, narrates retired design history, or argues for a decision at essay length; keep only claims a reader cannot recover from the code itself (constraints, invariants, non-obvious rationale).
- For each kept claim, prefer a pinned form: an `{@link}` to the live symbol, a pointer to the published docs, or a test that fails when the claim breaks. Surviving free prose should be short enough that re-verifying it during later edits is cheap.
- Apply the R483 routing discipline: a load-bearing claim that cannot be cleanly trimmed or pinned gets a follow-on item, not a fresh confident rewrite.

Prioritize the largest offenders first (measure javadoc block sizes at Spec time rather than hard-coding a target list here). The sweep is re-runnable; if the methodology proves cheap, a recurring cadence can be considered as a follow-on.

## Out of scope

- Adding missing orientation javadoc and `package-info.java` files: [`source-orientation-javadocs.md`](source-orientation-javadocs.md). The two items are complementary and can share a Spec-time file census.
- Generated-output javadoc and hygiene: [`generated-output-hygiene-sweep.md`](generated-output-hygiene-sweep.md).
- Fixing individually tracked drifted claims (R494, R496, R498); this sweep must not rewrite the prose those items pin.
