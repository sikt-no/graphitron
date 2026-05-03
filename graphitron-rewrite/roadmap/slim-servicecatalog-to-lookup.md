---
id: R72
title: "Slim ServiceCatalog down to a lookup primitive"
status: Backlog
bucket: architecture
priority: 5
theme: structural-refactor
depends-on: []
---

# Slim ServiceCatalog down to a lookup primitive

`ServiceCatalog.java` (~700 lines) is named like a lookup helper but carries three resolver-sized routines on top of the lookup: `reflectServiceMethod` (~170 lines), `reflectTableMethod` (~90 lines), and `reflectExternalField` (~50 lines). Each owns its own `Class.forName` + `getDeclaredMethods` scaffolding, its own argument-binding policy (override targets, typo guards, SOURCES batching hints), its own expected-return-type rule, and its own rejection wording. The caller surface (`ServiceDirectiveResolver`, `TableMethodDirectiveResolver`, `ExternalFieldDirectiveResolver`, `ConditionResolver`, `BuildContext`) is the natural home for those policy decisions; the catalog should expose lookup-shaped operations only.

The visible cost today: the same `"method 'X' in class 'Y' must return 'EXP' to match the field's declared return type — got 'ACT'"` message text is duplicated character-for-character across `ServiceCatalog.java:194-196` and `ServiceDirectiveResolver.java:290-292` — same conceptual `TypeName.equals` check, two copies. Inside `ServiceCatalog.java` itself the "method not found in class" rejection appears three times (lines 186, 375, 472), and the "parameter names not available" rejection twice (lines 227, 408). The lookup-side scaffolding (load class, find method by name, emit not-found with available-name hints) is triplicated. `BatchKeyLifterDirectiveResolver.java:211-233` runs a fourth structurally similar reflection block for its own purpose.

Surfaced while reviewing FQN bleed-through in validator error messages (the trigger for that work was the `must return …` rendering using `TypeName.toString()` on both sides). Slimming the catalog is the right home for the message-text and check-shape consolidation; the in-place FQN fix is a tactical band-aid until then.

Not blocking anything; pick up when someone is in `ServiceCatalog` for an unrelated reason or when the per-resolver wording diverges enough that the duplication starts hurting.
