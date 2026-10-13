---
id: R1027
title: "A rejected Declined or Unbound tenant root does not void its subtree tenant context"
status: Backlog
bucket: bug
priority: 3
theme: diagnostics
depends-on: [rejected-optional-only-root-keeps-subtree-context]
created: 2026-10-13
last-updated: 2026-10-13
---

# A rejected Declined or Unbound tenant root does not void its subtree tenant context

## Goal

A field that needs a tenant and rejects because its arguments decline to route (`Reading.Declined`) or name no tenant at all (`Reading.Unbound`) is the only error it causes: the fields below it are judged as they will be once the author applies the routing fix the rejection offers, which hands the subtree a tenant. Today such a root hands its subtree nothing, so every tenant-scoped child below it rejects too with "no ancestor established a tenant context". This applies to Declined and Unbound sites the forward-looking posture R1023 adopts for optional-only (`Conditional`) sites, so that "a site that needs a tenant hands one down in every build that passes" holds for every reading. Adopting it reverses the 14 "no ancestor established" assertions pinned below unbound roots in `TenantBindingClassificationTest`, which is why R1023 left it out; the Spec settles whether each decline detail's offered fix really hands a tenant down, and widens R1023's post-classification check to match.
