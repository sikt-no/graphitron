---
id: R971
title: "The tenant fold resolves an unrecognised slot extraction as a raw tenant value"
status: Backlog
bucket: cleanup
priority: 6
theme: classification-model
depends-on: []
created: 2026-09-24
last-updated: 2026-09-24
---

# The tenant fold resolves an unrecognised slot extraction as a raw tenant value

## Goal

When a query field routes on a tenant argument, the tenant fold decides how the generated fetcher turns
the argument's wire value into the tenant key: use it as is, or decode it first (a node id carries the
tenant inside an encoded key). That decision is `TenantBindingIndex.Fold.accessOf`, whose second switch
ends in `default -> SlotProjection.Raw`, meaning "the wire value already is the tenant value". That is
right for every extraction that reaches it today (`Direct`, `JooqConvert`, `EnumValueOf`, `ContextArg`),
and silently wrong for any future `CallSiteExtraction` arm that encodes its value: a schema using it would
build and then route on the encoded text, failing at request time or reading a tenant map entry that does
not exist. Making the switch exhaustive turns a new arm into a compile error asking which projection it
is, and changes no verdict today.

Two things a Spec should settle. `ContextArg` reaches this switch as itself, because the first switch
passes it on as the leaf after setting the `SlotRead.ContextArg` location, so it needs an explicit `Raw`
arm rather than a throw; throwing would make that location and its renderings in `TenantDslEmitter` and
`RoutineWriteCommands.slotReadOf` a guaranteed crash. And the record-shaped arms (`NodeIdDecodeRecord`,
`NodeIdDecodePolymorphicRecord`, `InputBean`, `JooqRecord`), minted only by `InputBeanResolver`,
`ServiceCatalog` and the fetcher generator for arguments that bind no single column, should throw an
invariant naming the carrier, as should a doubly-wrapped `NestedInputField`, which nothing mints.

Split out of R965's plan, where it had no bearing on the bug being fixed: R965 hands `accessOf` exactly
the extractions it already sees.
