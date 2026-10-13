---
id: R1023
title: "A rejected optional-only tenant root does not void its subtree tenant context"
status: Backlog
bucket: bug
priority: 1
theme: diagnostics
depends-on: []
created: 2026-10-13
last-updated: 2026-10-13
---

# A rejected optional-only tenant root does not void its subtree tenant context

## Goal

A root field whose tenant arguments are all optional (a "Conditional" reading: a request may omit every slot that names the tenant column) rejects with "takes its tenant only from optional values", and that one rejection is the only error it causes. Today it also voids the tenant context of every type it reaches, on every route, so each tenant-scoped child of those types rejects as well, either with "no ancestor established a tenant context" or, when the child carries an optional slot of its own, with a second "optional values" rejection. On sis the 22 intended root rejections arrive buried under roughly 700 consequential ones (719 author errors in all, against 1 at the commit before the regression), and the nested ones read like a routing regression in children that routed fine before.

## Diagnosis

`TenantBindingIndex.Fold.siteEstablishesContext` used to ask `DirectBinding.divines()`, which held for optional slots. The optional-only rejection rework (`20e11d5af`) narrowed it to `Reading.Guaranteed`. `tenantContextOf` is an every-path fold (`edgeEstablishesContext` is an `allMatch`, and `withoutContext` propagates transitively), so one Conditional root reaching a type takes context away from that type for every other root that reaches it too. Children of a tenant-scoped `@table` parent route only through the Inherited rung; `parentRowBinding` serves a *global* parent row that carries the tenant column, so it never rescues them. With context gone they fall to the reading switch: no slot means "no ancestor established a tenant context", an optional slot means a nested "optional values" rejection. This is not an interaction with the parent-row rung (`7731985d7`).

The cascade was pinned on purpose: `TenantFanOutClassificationTest.anUnmarkedOptionalOnlyParentRejectsAndHandsItsChildrenNoTenant` asserts the child's "no ancestor" rejection. So this item reverses a decision rather than repairing an oversight, and the reversal is what the Spec review should judge.

## Proposed change

A Conditional site that needs a tenant (it reaches a tenant-scoped table, or is a connection-bound root service) counts as establishing context in the fold. Both fixes its rejection offers, a non-null slot (Guaranteed) and `@tenantFanOut` (a fanned edge), make that edge establish context, so the subtree is judged as it will be once the author applies either fix, and the field rejects alone. Where such a site does not reject, it inherits, and its subtree has the inherited tenant anyway. A Conditional site that needs no tenant keeps falling through to the default source and keeps establishing nothing, so a tenant-scoped child below it still rejects on its own; that is armOf's documented fall-through and stays.

`needsTenant` and `connectionService` move out of `armOf` into helpers over the site alone (`reachedTables` is static and pure), so the fold and the arm share one predicate.

A prototype exists: the new `TenantBindingClassificationTest.aRejectedOptionalOnlyRootLeavesItsSubtreeInheritingFromTheOtherRoots` fails on trunk (`Film.inventories` classifies `null`, expected `Inherited`) and passes with the change, and the pinned fan-out test flips to expect one rejection. The generator unit tier (`-DexcludedGroups=execution`) is green with it. Still owed: the execution tier, and a sis rerun (`mvn -o clean install -P quick -pl :sis-graphql-spec,:fsapi-rest` on the sis upgrade branch), which should drop from 719 errors to the root ones only.
