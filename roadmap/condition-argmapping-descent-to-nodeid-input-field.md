---
id: R884
title: "A field-level @condition parameter whose argMapping descends to a @nodeId input field still receives the wire string"
status: Backlog
bucket: dx
priority: 3
theme: nodeid
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# A field-level @condition parameter whose argMapping descends to a @nodeId input field still receives the wire string

A `@nodeId` slot's value is decoded before it leaves the generated glue, so an authored `@condition` parameter bound to such a slot receives the typed key rather than the base64 wire string. That rule is installed on the *slot*, and it recognises a whole-slot binding: a parameter named after the slot, or an `argMapping:` whose right-hand side is the bare slot name. One shape falls between the two rails that exist. A field-level `@condition` writing `argMapping: "p: filter.languageId"` descends through an input object and stops on a `@nodeId` **input field**: the path is dotted, so the whole-slot install does not claim it, and its last segment names an input field rather than a key column, so the `argMapping` projection rail (which serves `"p: filter.languageId.language_id"`, the column grain) does not claim it either. Such a parameter still receives the encoded string, and there is nothing an author can decode it with, the generated `NodeIdEncoder` being emitted downstream of the class the `@condition` is compiled into.

The consequence is small but is the exact failure class the decoded handoff exists to retire: the author either hand-rolls the wire format again, or declares the decoded type and meets a `ClassCastException` at request time rather than a build error. `ConditionResolver.installNodeIdDecode`'s javadoc records the gap, and `docs/manual/reference/directives/condition.adoc` tells an author to move the `@condition` onto the input field itself, which is the remedy that exists today.

Closing it wants a descent-aware install: the whole-slot predicate would have to recognise a path whose *terminal* segment names a `@nodeId` input field reached by descent, and hand the decode to that parameter with the leaf extraction composed under the descent, the way `ConditionResolver.rewrapForNested` composes an input-field parameter's own extraction. Worth checking at the same time whether the sibling shape at an argument's own `@condition` can arise, and whether the classify-time declared-type refusal covers the new coordinate for free once the install does.
