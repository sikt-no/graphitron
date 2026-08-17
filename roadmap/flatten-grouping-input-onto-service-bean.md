---
id: R693
title: "Flatten a nested grouping input onto a consumer bean at @service, the member-axis sibling of R336"
status: Backlog
bucket: feature
priority: 3
theme: service
depends-on: []
created: 2026-08-17
last-updated: 2026-08-17
---

# Flatten a nested grouping input onto a consumer bean at @service, the member-axis sibling of R336

A `@service` method whose parameter is a consumer-authored bean cannot group SDL input
fields under a nested input object the way a jOOQ-record parameter can. R336 shipped that
grouping on the column axis: a param typed as a generated `TableRecord` may nest its
columns under directiveless input objects that flatten onto the one backing table. Its D2
note states the choice explicitly, `collectJooqBindings` recurses "parallel to the
member-axis `buildInputBean` walk", so the member axis was left where it was rather than
considered and declined. The result is an axis-dependent schema vocabulary: the same
nesting an author is invited to write against a jOOQ record either hard-fails or silently
drops data against a bean.

## The shape

An author groups two related leaves under a wrapper input, while the Java bean carries
them flat:

```graphql
input OpprettUtdanningsinstansInput {
    kode:     String! @field(name: "utdanningsinstanskode")
    varighet: OpprettUtdanningsvarighetInput
    periode:  OpprettUtdanningPeriodeInput
}

input OpprettUtdanningsvarighetInput {
    antall:         BigDecimal @field(name: "varighetTall")
    varighettypeId: ID
}
```

```java
public record OpprettUtdanningsinstansInputRecord(
    String utdanningsinstanskode,
    BigDecimal varighetTall,          // flattened out of `varighet`
    String varighettypeId,            // flattened out of `varighet`
    OpprettUtdanningPeriodeInputRecord periode) {}
```

`periode` binds today (a nested SDL input whose Java member is a matching bean recurses to
a nested `InputBean` leaf). `varighet` does not, and the outcome splits by bean shape:

* **Record arm.** `InputBeanResolver.bindRecord` enforces a bidirectional bijection, so the
  build fails with "record ... component 'varighetTall' has no SDL input field bound to it".
* **JavaBean arm.** `bindJavaBean` is partial by design and skips an SDL field whose binding
  key names no setter, so `varighet` is dropped, `varighetTall` stays null, and nothing in
  the build says so. The silent half is the worse one.

## Why the sockets are already cut

* `CallSiteExtraction.FieldBinding` carries a single `sdlFieldName`. R336's D1 solved the
  same problem on its own carriers by widening to an ordered access path whose last element
  is the `Map` key and whose earlier elements are the enclosing nested-input field names,
  "adopting the `NestedInputField` representation".
* `InputBeanInstantiationEmitter` already reserves the arm and refuses it:
  `case CallSiteExtraction.NestedInputField ignored -> throw notALeaf(fb)`. Implementing
  that arm is the emit half.
* `ServiceMethodCallWalker.fieldBindingShape` appends one path segment per level when it
  lowers to `ValueShape`; a flattened leaf needs it to append the group's segments too.
  `ValueShape.Scalar` already carries a full `ArgPath`, so nothing below the walker changes
  shape.
* R336's D4 null-safe parent-`Map` descent, including skip-not-throw for an absent nullable
  group, is the emit idiom to mirror rather than reinvent.

## Binding rule

Implicit, no new directive. A nested input-object field binds to a matching Java member
when one exists (today's behaviour, which `periode` depends on); only when no member
matches its binding key does the walk descend and hoist the group's leaves into the
enclosing bean's binding-key index. That keeps every currently-classifying schema binding
exactly as it does now. The cost to weigh at Spec is that the SDL's meaning becomes a
function of the Java side, which the column axis never had to face because it has no
competing interpretation.

## Open questions for the spec pass

* Whether the JavaBean arm's silent skip should become a diagnostic once flattening exists.
  A field that matches no member and cannot be flattened is now a strictly better warning
  candidate than it was, and the arm's partial-by-design contract is what currently makes
  the failure invisible.
* Collision handling when a hoisted leaf's binding key already names a bound member, or two
  groups hoist the same key. The record arm's existing duplicate-key rejection is the
  natural home; confirm it fires before either arm builds.
* Rejection parity with `collectJooqBindings`: cyclic nesting (reuse the `ClassifyContext`
  `expanding` set), list-shaped grouping inputs, and depth limits.

## Out of scope

* The record arm's totality requirement. A component that no SDL field is meant to populate
  (a service-filled value) still fails direction A of the bijection; that is a separate gap
  and deserves its own item if it turns out to matter.
* `@nodeId` on a bean member typed as a plain `String`, which binds the raw wire id rather
  than decoding. Orthogonal to nesting, and unchanged by this item.
* Any change to the jOOQ-record axis, the `@table`-input path, or the graphitron-emitted
  input record class, which mirrors the SDL and stays SDL-shaped.
