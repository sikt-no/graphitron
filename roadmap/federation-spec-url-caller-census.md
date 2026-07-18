---
id: R497
title: "Pin or drop the FederationSpec.URL caller census in its class javadoc"
status: Ready
bucket: docs
priority: 7
theme: docs
depends-on: []
created: 2026-07-16
last-updated: 2026-07-18
---

# Pin or drop the FederationSpec.URL caller census in its class javadoc

`FederationSpec` (`schema/federation/FederationSpec.java`) is a one-constant leaf: a `public static final String URL` holding the Apollo Federation 2 spec URL, deliberately kept free of pipeline-timing and runtime coupling so callers at any stage can reference it. Its class javadoc carries a hand-maintained enumerated caller census ("Three callers reach for it: `TagLinkSynthesiser`; `FederationLinkApplier`; and `GraphitronSchemaBuilder`") that has already drifted from the code.

## Ground truth (verified against `graphitron/src/main/java`)

Real value-consumers of the constant (code that reads `FederationSpec.URL`):

* `TagLinkSynthesiser` (`schema/input/TagLinkSynthesiser.java:128`): reads it into a `StringValue` when synthesising a `@link`.
* `GraphitronSchemaBuilder` (`GraphitronSchemaBuilder.java:94`): passes it to the federation directive-set load.

Not value-consumers, though the census implies otherwise:

* `FederationLinkApplier` (`schema/input/FederationLinkApplier.java:36`) names `FederationSpec#URL` only in a javadoc `{@link}`; its own javadoc states it consumes the value indirectly via the registry contents. It is wrongly listed as a direct caller.
* `ScalarTypeResolver` (`ScalarTypeResolver.java:102`) references `FederationSpec.URL` in a javadoc `{@code}` but is omitted from the census entirely.

So the census carries one false entry and one omission: the drift the R483 audit flagged.

## Why it fooled the audit

The two `{@link}` targets in the census are build-checked live symbols (the javadoc reference gate resolves them), so the census reads as enforced. But the gate pins that the *symbol exists*, not that it *reads the value of `URL`*. That guarantee is orthogonal to the census's actual claim, which is precisely why a link-only referrer sits inside the list and a real consumer sits outside it.

## Direction

Drop the enumerated census. An unguarded inventory on a leaf constant reads as authoritative and rots on every caller add or remove while earning nothing derivation cannot recover: find-usages is the single source of the caller set, and the correctly-directed consumer-to-producer `{@link}`s already live at the call sites. Replacing the census with a mechanically-checked form is the wrong altitude here; there is no compiler-checked "who reads this constant" mechanism for a `public static final String`, so that path would mean inventing bespoke enforcement for a one-constant class.

Keep, do not touch:

* The neutrality rationale ("kept neutral (no pipeline timing or federation-jvm runtime call) so callers at different stages can reference it"). That is a design fact explaining the class shape, not a census, and does not rot.
* The `URL` field javadoc and its "bump alongside the library, verify `loadFederationSpecDefinitions` accepts it" guidance.

The class javadoc should state what the constant is and why the holder is neutral, and stop there.

## Non-goals

* No bespoke caller-set enforcement for the constant.
* No change to any consumer; this is a single-file javadoc edit.

Surfaced by the R483 javadoc drift audit.
