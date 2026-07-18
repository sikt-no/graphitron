---
id: R495
title: "Reconcile InputRecordGenerator generated service-reference-audit javadoc with actual enforcement"
status: In Review
bucket: docs
priority: 6
theme: docs
depends-on: []
created: 2026-07-16
last-updated: 2026-07-18
---

# Reconcile InputRecordGenerator generated service-reference-audit javadoc with actual enforcement

`InputRecordGenerator` (`generators/schema/InputRecordGenerator.java`) holds the same "these carriers are graphitron-internal" fact in two places, and they contradict:

* Its own class javadoc (`:42-44`) says a build-time audit of service-side references "is deferred as a follow-on; in the meantime the 'graphitron-internal' intent rides on the package boundary and the per-class Javadoc this generator stamps".
* The per-class javadoc it stamps into generated output (`buildClassSpec`, `:150-151`, a string literal) tells consumers: "Service code consumes the consumer-bean path or the existing `Map.get` pattern; a build-time audit enforces this rule."

One says the audit is deferred; the other tells consumers it enforces the rule today.

## Ground truth

No build-time audit of service-side references runs. The audit is a separate, still-Backlog item, R172 (`inputs-package-internal-use-audit`, "Audit: forbid service-side references to `<outputPackage>.inputs.*`"). So the class javadoc ("deferred") is accurate and the emitted javadoc ("enforces this rule") is a false claim baked into consumer-facing generated output. Because it is a string literal in generated javadoc, neither `RoadmapReferenceGuardTest` nor the javadoc reference gate polices it, and no test pins the emitted string, so the only safety net is manual golden-output review.

This is the drift smell: a fact restated with no single enforcer binding the two copies, and the copies diverged.

## Direction

Correct the emitted per-class javadoc to state only the present guarantee. The "graphitron-internal" intent is a naming and packaging convention riding on the `<outputPackage>.inputs` boundary and the per-class javadoc, not a mechanically-enforced rule. Drop the "a build-time audit enforces this rule" clause. Keep the "Do not reference from service code" instruction, but let it rest on the stated convention rather than on a promised-but-absent mechanism, so the emitted text matches the honest framing the class javadoc already carries at `:43-44`.

Do not add a forward-looking "will be enforced by a future audit" note: that is a transient forward-reference into a consumer artifact with no roadmap directory, exactly the shape the string-literal sweep removes, and one that carries no id so nothing mechanical would catch it. The emitted text carries no `R<n>` id today, so the defect is the enforcement claim itself, not a citation to drop; the stub's "dropping the transient id" framing does not apply to this site.

## Non-goals

* No equality assertion pinning the exact emitted javadoc string. A code-string assertion on generated output is out of bounds under the "behaviour is pinned at the pipeline tier and above" principle; javadoc *content* stays review-only (golden-output), and the fix is to make the text honest, not to freeze its wording.
* No single-sourcing of the internal-and-emitted statement. Binding the two copies so they cannot diverge is a mechanism change disproportionate to a one-line convention note; the real enforcement lands with R172. Out of scope here.
* The class-level javadoc at `:42-44` is already accurate and stays.

Surfaced by the R483 javadoc drift audit.
