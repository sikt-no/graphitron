---
id: R207
title: "Audit design-doc claims for implementation conformance"
status: Backlog
bucket: architecture
depends-on: []
created: 2026-05-21
last-updated: 2026-05-21
---

# Audit design-doc claims for implementation conformance

R205 surfaced a five-layer survival pattern where a documented design claim (`docs/argument-resolution.adoc`'s truth table at `:262-275` saying plain inputs and `@table` inputs share the same implicit-predicate behaviour) diverged from the implementation (`FieldBuilder.java:1349` passing `null` for `implicitBodyParams` on plain inputs) and survived because no enforcing test asserted the symmetry. The same shape — design doc says X, code does Y, no test pinning X — plausibly exists elsewhere in the rewrite-internal docs (`argument-resolution.adoc`, `typed-rejection.adoc`, `rewrite-design-principles.adoc`, per-resolver javadocs).

This Backlog stub is the Spec-stage prompt to scope the audit. None of the below have an obvious answer; the Spec phase picks one and defends the choice:

- **Audit method.** Mechanical (extract every truth table / numbered claim / "every X does Y" sentence from the docs, grep for the test that enforces it), manual (read each doc paragraph-by-paragraph against the code it describes), or audit-tool-assisted (something that diffs documented invariants against `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` coverage)?
- **Sweep depth.** All rewrite-internal docs, or only design docs (excluding principles)? Per-resolver javadocs in scope, or only top-level `.adoc` files?
- **Output shape.** A single audit report committed under `graphitron-rewrite/docs/`, or one Backlog item per drift site found?
- **Forcing function for future drift.** Add a `@LoadBearingClassifierCheck`-style anchor to every truth-table row that points at a test that pins it? Or a doc-side convention (`xref:test-file.java:TestName[]`) plus a CI lint that fails on dangling references?

R205 closes the specific instance at the plain-input-filter surface but does not generalise; that's this item's job.

The five layers R205 named, for the audit to look for analogues of:

1. Design doc says symmetric, code is asymmetric.
2. Classifier-tier tests assert the type, not the projection.
3. Projection meta-tests explicitly allowlist the divergent variant.
4. Execution-tier negative tests encode the bug as expected behaviour by name and assertion.
5. Handoff at a phase boundary scopes the follow-up narrowly enough that the divergent half falls between the gap.

If the audit finds the pattern is rare (one or two sites), point fixes per site are probably right. If common (a dozen+), a forcing function (lint, meta-test, anchor convention) is probably right. The audit's job is to decide which world we live in.

Likely starting points based on the R205 investigation:

- `docs/argument-resolution.adoc` § "Truth table" — the pattern that bit us; the rest of this doc may have analogues.
- `docs/typed-rejection.adoc` § "Sealed `Resolved` across the resolver siblings" — the thirteen sibling resolvers; spot-check each producer/consumer pair for "the rejection arm is read by every consumer."
- `docs/rewrite-design-principles.adoc` § "Builder-step results are sealed, not strings or out-params" — the meta-principle; audit for `List<String> errors` out-params co-existing with sealed result types (the violation R205 closed at `InputFieldResolver.resolve`).
- Per-resolver javadocs on `*DirectiveResolver.Resolved` declarations.
