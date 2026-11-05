---
id: R1151
title: "Require an unmount on SessionState so identity never rests on a pooled connection"
status: Backlog
bucket: bug
priority: 1
theme: runtime-connection
depends-on: []
created: 2026-11-05
last-updated: 2026-11-05
---

# Require an unmount on SessionState so identity never rests on a pooled connection

## Goal

A request's identity never stays on a pooled connection after the request that mounted it has released it. Today the generated `SessionState` offers `of(Mount)`, a mount with no unmount, whose javadoc says the next request's mount "overwrites this one's identity wholesale on a pooled connection". That holds only when every borrower of the DataSource mounts every variable on every request. It fails when an anonymous request's mount skips `set_config` for missing claims, when a variable is only set on some requests, and when anything else borrows from the same pool: escape-hatch operations, `@service` code using an injected DataSource, background jobs, health checks. In each case the next borrower runs as the previous user. When this lands the generated `SessionState` cannot be built without an unmount (or, if R1152 lands first and the reset backstop is unconditional, the mount-only form is documented as safe because of that backstop rather than because of an overwrite).

## Context

Found in an adversarial review of the owned-connection session lifecycle, prompted by a consumer asking whether `app.claims` can leak between requests. The emitted shape is in `SessionState` (schema package) and its generator in `ConnectionRuntimeClassGenerator`. The existing execution test `nextMountOverwritesWholesale_theMountOnlyContractsGround` in `SessionHookExecutionTest` pins the overwrite on one raw connection, which is the very assumption this item questions; it would be retired or rewritten.

## Open questions

- Remove `of(Mount)` outright, or keep it behind the R1152 backstop? Removing it is a breaking change to generated API; consumers on the mount-only form get a compile error pointing at the fix, which is the desired failure mode for a security defect.

