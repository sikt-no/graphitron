---
id: R536
title: "Retire the always-empty ErrorChannel.Mapped slots on the polymorphic and table-interface @service variants"
status: Backlog
bucket: cleanup
priority: 5
theme: error-channel
depends-on: []
created: 2026-07-25
last-updated: 2026-07-25
---

# Retire the always-empty ErrorChannel.Mapped slots on the polymorphic and table-interface @service variants

Four root `@service` field variants carry an `Optional<ErrorChannel.Mapped>` component that is structurally always empty, and the two sides disagree about whether that dead slot makes the field a channel carrier. `FieldBuilder.buildServiceField` resolves the channel through `resolveServiceOutcomeChannel`, which returns `NoChannel` for anything but a `ResultReturnType`; the polymorphic and table-interface arms are built from `ServiceDirectiveResolver.Resolved.Polymorphic` (a `PolymorphicReturnType`) and `.TableBound` (a `TableBoundReturnType`), so `MutationServicePolymorphicField`, `MutationServiceTableInterfaceField`, `QueryServicePolymorphicField`, and `QueryServiceTableInterfaceField` can never hold a present channel. The Query-side pair nonetheless implements `WithErrorChannel` while the Mutation-side pair implements only `ServiceField`, so a channel-agnostic reader consuming `instanceof WithErrorChannel` sees two of the four for no stated reason.

The dead slot has already been paid for twice. `MappingsConstantNameDedup.withResolvedChannel` claims to pattern-match every `WithErrorChannel` variant and omits both Query-side arms, landing them on its `default -> throw new IllegalStateException`; that throw is unreachable only because the slot is always empty, i.e. an invariant the type does not carry is what keeps the switch honest. `CatalogBuilder.errorChannelName` likewise folds over a slot that has one value.

Decide the shape once: either these returns genuinely cannot host an `@error` payload, in which case drop the component and the `WithErrorChannel` implementation from all four and let the dedup switch become exhaustive over what remains; or the omission is a gap, in which case `resolveServiceOutcomeChannel` should admit them and the dedup switch needs the two missing arms. The second reading overlaps R397, which wants more query return shapes to host `@error`; if that item lands first, this one is the audit of what its widening leaves behind. Either way the pipeline-tier fixtures that currently synthesise a channel on a root `@service` field need to state which variants are legitimate hosts.
