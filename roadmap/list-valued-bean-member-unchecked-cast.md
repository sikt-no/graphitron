---
id: R703
title: "Generated list-valued bean members emit an unchecked cast that fails a consumer build under -Werror"
status: Backlog
bucket: bug
priority: 3
theme: service
depends-on: []
created: 2026-08-18
last-updated: 2026-08-18
---

# Generated list-valued bean members emit an unchecked cast that fails a consumer build under -Werror

A `@service` input bean whose member is a list of scalars or enums (`List<String>`,
`List<Integer>`, a list of enum constants) gets a helper body containing an unchecked cast:
`InputBeanInstantiationEmitter.directExpr`'s list arm emits
`raw.get("tags") == null ? null : (List<String>) raw.get("tags")`. Generated sources land in the
consumer's own build, so under `-Werror` (which `graphitron-sakila-example` itself uses) that
warning is a hard compile failure, and no `@SuppressWarnings` can be attached to a cast sitting
inside an expression.

Surfaced while implementing the grouping-input flatten on the member axis. The sibling case there,
a *singular* nested bean narrowed with `(Map<String, Object>) raw.get(k)`, was the same defect and
was fixed in that pass: the helper's parameter widened to `Map<?, ?>` and the call site became an
`instanceof` pattern, which is not an unchecked cast. The list arm was left alone because the fix
does not transfer mechanically. `List<?>` cannot be narrowed to `List<String>` by a pattern, so
producing a correctly-typed list means copying it element-wise
(`list.stream().map(o -> (String) o).toList()`), which changes what the generated code does (a new
list rather than the wire list, and per-element rather than deferred failure). That is a design
call, not a mechanical substitution, which is why it is its own item.

No consumer is known to be hitting this today, because no fixture in the reactor pairs a
list-of-scalars bean member with the `-Werror` compile gate; the singular-bean twin went unnoticed
for the same reason until a fixture happened to exercise it. Worth checking whether the enum list
arm (`enumExpr`) and any other emitter that casts a wire value to a parameterised type have the
same exposure before scoping the fix.
