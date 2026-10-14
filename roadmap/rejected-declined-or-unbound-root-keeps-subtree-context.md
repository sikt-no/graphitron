---
id: R1027
title: "A rejected Declined or Unbound tenant root does not void its subtree tenant context"
status: Backlog
bucket: bug
priority: 3
theme: diagnostics
depends-on: []
created: 2026-10-13
last-updated: 2026-10-13
---

# A rejected Declined or Unbound tenant root does not void its subtree tenant context

## Goal

A field that needs a tenant and rejects because its arguments decline to route (`Reading.Declined`) or name no tenant at all (`Reading.Unbound`) is the only error it causes: the fields below it are judged as they will be once the author applies the routing fix the rejection offers, which hands the subtree a tenant. Today such a root hands its subtree nothing, so every tenant-scoped child below it rejects too with "no ancestor established a tenant context". This applies to Declined and Unbound sites the forward-looking posture R1023 adopts for optional-only (`Conditional`) sites, so that "a site that needs a tenant hands one down in every build that passes" holds for every reading. Adopting it reverses the 14 "no ancestor established" assertions pinned below unbound roots in `TenantBindingClassificationTest`, which is why R1023 left it out; the Spec settles whether each decline detail's offered fix really hands a tenant down, and widens R1023's post-classification check to match.

## Evidence: a federation entity key as the rejected root

Reported from sis on 2026-10-14. A new stable entity `type FeideBruker @key(fields: "brukernavn")`, with `@table` on `PERSON` and a sis `@splitQuery` contribution `personProfil`, rejects correctly: "'FeideBruker' reaches tenant-scoped table 'PERSON' with no tenant binding in scope: its federation key alternative #0 does not carry tenant column 'INSTITUSJONSNR_EIER'". This is a root kind the Goal does not name: an `_entities` key alternative, not a field's arguments. The Spec should say whether the posture covers it. Unlike a Declined or Unbound argument site, there may be no routing fix inside the key to offer, so "judge the subtree as it will be once fixed" first needs to know which fix the author will take.

The same build reports about 692 "no ancestor established a tenant context" errors below `PersonProfil` (into `StudentVedLarested`, `Studieprogram`, `Emne`, `FagpersonVedLarested` and others). It also reports about 20 nested fields judged on their own optional slots, for example `Emne.undervisningsenheter` "takes its tenant only from optional values (terminer)". None of these appeared before the rebase. They have not been bisected: some may come from new optional-only roots on main rather than from the entity, and the optional-slot ones look like `Conditional` territory rather than this item's. Do not size this item from the 692 until a stub-the-entity bisect separates the two.

Context, not a requirement: v9 routed this entity to the logged-in user's home institution from the token (`UserInfo.getHjemmeinstitusjonsnr`), with an ownership check. That rule is not R1005's request default tenant. sis has not decided which rule it wants.
