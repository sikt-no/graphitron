---
id: R1043
title: "A @splitQuery to the same table row with no @reference is classified or rejected, never reaching the emitter with a null correlation"
status: Backlog
bucket: bug
priority: 3
theme: classification-model
depends-on: []
created: 2026-10-15
last-updated: 2026-10-15
---

# A @splitQuery to the same table row with no @reference is classified or rejected, never reaching the emitter with a null correlation

## Goal

A `@splitQuery` field whose target is the same table as its parent, with no `@reference`, either generates a batched child fetch or fails classification with an author error naming the field and both types. It never passes validation and then crashes the generator. Today `graphitron:generate` reports no author errors and then fails with `NullPointerException: correlation` thrown from `LaunchSource.CorrelatedChain`'s compact constructor, reached through `LauncherCommands.batchedChildRow` and `EmitPlan.produce`; the message names neither the field nor the type, so the reporter had to bisect the schema to find it. The broader invariant this item should leave behind: no `@splitQuery` shape reaches the emitter without a parent correlation.

## Evidence

Reported from the sis port on 2026-10-15 against trunk `e4cfef7c1`:

```graphql
type FeideBruker @table(name: "PERSON") @key(fields: "brukernavn") @defaultTenant {
  brukernavn: String! @field(name: "FEIDE_BRUKER") @shareable
}
extend type FeideBruker {
  personProfil: PersonProfil @splitQuery   # PersonProfil is @table(name: "PERSON"), no @reference
}
```

```
Caused by: java.lang.NullPointerException: correlation
    at java.util.Objects.requireNonNull
    at no.sikt.graphitron.command.LaunchSource$CorrelatedChain.<init> (LaunchSource.java:104)
    at no.sikt.graphitron.plan.LauncherCommands.batchedRow (LauncherCommands.java:854)
    at no.sikt.graphitron.plan.LauncherCommands.batchedRow (LauncherCommands.java:842)
    at no.sikt.graphitron.plan.LauncherCommands.batchedChildRow (LauncherCommands.java:273)
    at no.sikt.graphitron.plan.LauncherCommands.produce (LauncherCommands.java:213)
    at no.sikt.graphitron.plan.EmitPlan.produce (EmitPlan.java:167)
```

Isolated by the reporter: dropping `@splitQuery` from this one field, or removing the field, makes the build succeed. sis no longer depends on it (the `@splitQuery` was a 9.3.2 workaround, removed in sis `58e862f94f`), so priority is driven by other schemas hitting the same crash.

The reporter's reading, inferred from the code and not yet traced: PERSON to PERSON with no `@reference` yields an empty `joinPath`, the classifier lands no `ParentCorrelation` arm (neither `OnLiftedSlots` nor a rejection), `parentCorrelation()` is null, and the carrier-invariant check does not test for null, so the null reaches the emitter. The parent is reached as a `@defaultTenant` entity, which may route classification differently from a plain object parent; the Spec should check both.

## Questions for the Spec

- **Support or reject.** Graphitron 9 generated a batched re-select keyed on the parent's primary key (here `(INSTITUSJONSNR_EIER, PLNR)`), which is a self-correlation on the PK. Supporting it is the leaning if it falls out of an existing correlation arm without new emitter shape; otherwise reject with an author error such as "`@splitQuery` from `FeideBruker` to the same `PERSON` row has nothing to correlate on; remove `@splitQuery` or add a `@reference`". A table without a primary key needs the rejection either way.
- **Where the null escapes.** Locate the classifier branch that returns a batched child with no correlation, and decide whether the guard belongs in classification (the author error) or also as a non-null invariant on the model record, so a future shape fails at classification with a field name rather than at launch.
