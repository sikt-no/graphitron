---
id: R41
title: "@field(name:) on @service method args"
status: Backlog
bucket: architecture
priority: 5
theme: service
depends-on: [external-code-reference-arg-mapping]
---

# `@field(name:)` on `@service` method args

Tombstone. Superseded by R53 ([`external-code-reference-arg-mapping.md`](external-code-reference-arg-mapping.md)) before R41's user-facing surface ever shipped. The R41 file deletes when R53 reaches Done; until then it serves as a redirect.

## Why R53 supersedes R41

R41 proposed a per-arg `@field(name:)` interpretation as a Java-parameter override on `@service` / `@tableMethod` argument sites. The dual-axis case (filter args carrying `@condition`, `@table` input fields carrying `@condition`) cannot be expressed in that shape because the same `@field(name:)` slot is already locked at the column-binding axis. R53 resolves the dual-axis problem structurally by lifting the override onto the method reference (`ExternalCodeReference.argMapping`) instead of colocating it with the slot.

Because no consumer upgraded to a Graphitron release carrying R41's per-arg semantic, R53 rolls back R41's user-facing surface rather than shipping it for one release cycle and then deprecating. R53's *Relationship to R41* section enumerates which R41 plan items R53 inherits (the `MethodRef.Param.Typed` split, `enrichArgExtractions` keying off `graphqlArgName`, the post-reflection typo guard, the `@field(javaName:)` deletion, the `@field` docstring rewrite) and which R41 plan items R53 deliberately drops (the per-arg `@field(name:)` reading, `ArgBindingMap.forField`, the pre-reflection collision check via `forField` value-duplicates).

R41 only re-opens if a concrete forcing function emerges that R53's `argMapping` channel structurally cannot serve; pin that shape here before re-spec'ing.
