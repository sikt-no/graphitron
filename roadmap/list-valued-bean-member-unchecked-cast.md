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
`instanceof` pattern, which is not an unchecked cast. The list arm was left out of that pass because
`List<?>` cannot be pattern-narrowed to `List<String>`, so the pattern fix does not transfer.

**The shape of the fix is already in the same file, which makes this smaller than it first looks.**
`enumExpr`'s list arm casts the wire value to `List<?>` and then maps element-wise
(`((List<?>) root.get(k)).stream().map(...).toList()`). A cast to `List<?>` has no type arguments to
check, so it does not warn, and the per-element `(String) o` inside the map is an ordinary checked
narrowing cast. Applying that same shape to `directExpr`'s list arm removes the warning with a
precedent one method away, rather than inventing an idiom.

What still needs deciding, and why this is an item rather than a one-line patch: the mapped form
changes what the generated code *does*. It hands the bean a fresh list instead of the wire list, and
it converts every element eagerly, so a wrong element type surfaces at bean construction rather than
as a deferred `ClassCastException` at first access. Both readings are arguably better than today's,
but they are a behaviour change to already-shipped generated code, so they want a stated decision.
Null elements survive either way (casting null succeeds and `toList()` admits nulls).

No consumer is known to be hitting this today. That is not reassurance: it is the same blind spot
that hid the singular-bean twin, namely that no fixture in the reactor pairs a list-of-scalars bean
member with the `-Werror` compile gate. Note also that the grouping flatten adds a second route to
this arm (a list-of-scalars member hoisted out of a group), on top of the top-level route that
already reaches it, so a consumer adopting grouping inputs on a bean with such a member meets this
immediately. Confirmed *not* affected: `enumExpr` (both arms), and the singular arms of `directExpr`
and `nestedBeanExpr`. Worth sweeping other emitters that cast a wire value to a parameterised type
before settling the scope.
