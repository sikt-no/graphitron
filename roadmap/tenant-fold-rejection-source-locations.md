---
id: R523
title: "Carry field SourceLocations through the tenant-binding fold rejections"
status: Backlog
bucket: tech-debt
priority: 2
theme: classification-model
depends-on: []
created: 2026-07-24
last-updated: 2026-08-06
---

# Carry field SourceLocations through the tenant-binding fold rejections

Every rejection the tenant-binding fold produces (`TenantBindingIndex`: the `noTenantBinding` family, the node/entity dispatch rejections, and the whole `@tenantFanOut` ladder) carries `SourceLocation.EMPTY`, although the SDL field definition's real location is available at each producing site. The validator therefore prints these author errors without file:line coordinates, unlike the classifier rejections produced during the walk, which makes multi-schema builds needlessly hard to debug. Thread the field definition's `SourceLocation` through the fold's rejection constructors; purely mechanical, no behavioural change.

## Fact-base note (2026-08-06)

Either do the trivial threading now with the understanding that it becomes moot, or park behind the tenant relations' migration: detections over located facts inherit their locations for free, so hand-threading constructors is work against the surface being strangled.
Context and the whole-board picture: `roadmap/audits/2026-08-06-fact-base-impact-sweep.md`.
