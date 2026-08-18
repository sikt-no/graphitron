---
id: R693
title: "Flatten a nested grouping input onto a consumer bean at @service, the member-axis sibling of R336"
status: Spec
bucket: feature
priority: 3
theme: service
depends-on: []
created: 2026-08-17
last-updated: 2026-08-18
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

## Where the sockets already are

Re-measured at the Spec pass. Three of the four notes in the Backlog draft hold; the fourth
was wrong and the correction is the reason D1 is two carriers wide rather than one.

* `CallSiteExtraction.FieldBinding` carries a single `sdlFieldName`. R336's D1 solved the
  same problem on its own carriers by widening to an ordered access path whose last element
  is the `Map` key and whose earlier elements are the enclosing nested-input field names,
  "adopting the `NestedInputField` representation".
* `InputBeanInstantiationEmitter.perFieldValueExpr` reads `raw.get(fb.sdlFieldName())` in
  every arm. Its `case CallSiteExtraction.NestedInputField ignored -> throw notALeaf(fb)`
  arm is *not* the socket: that arm rejects a `NestedInputField` sitting in the `leaf`
  slot, and a flattened leaf's transform stays `Direct` / `EnumValueOf` /
  `NodeIdDecodeRecord` exactly as an unflattened one does. The path belongs on the binding,
  not on the leaf, so the arm stays a throw and the emit half is the `raw.get(...)` root
  instead. Recorded here because the Backlog draft named the wrong site.
* `ServiceMethodCallWalker.fieldBindingShape` appends one path segment per level when it
  lowers to `ValueShape`; a flattened leaf needs it to append the group's segments too.
* R336's D4 null-safe parent-`Map` descent, including skip-not-throw for an absent nullable
  group, is the emit idiom to mirror rather than reinvent.

The correction: the draft's claim that "nothing below the walker changes shape" is false.
`TypeFetcherGenerator.registerBeanHelper` and `convertNestedFieldBindings` rebuild a
`CallSiteExtraction.FieldBinding` *out of* a `ValueShape.FieldBinding`, reading
`vfb.sdlFieldName()` as the `Map` key. A path carried only on the classifier's carrier is
dropped on the way to the emitter for every root `@service` coordinate, which is the
coordinate the motivating schema uses. `ValueShape.FieldBinding` therefore widens too. This
is the round-trip R402 exists to delete; see Coordination below.

## Binding rule

Implicit, no new directive, and the flatten is *directive-gated* so no authored claim is
ever silently reinterpreted. For a nested input-object field `g` on a bean whose member set
is known before any field binds:

1. **A member matches `g`'s binding key.** Bind as today: `g` recurses into a nested
   `InputBean` leaf. This is what `periode` in the motivating schema depends on, and it wins
   unconditionally, including when the matched member's type is not a viable bean class (that
   stays today's rejection rather than becoming a flatten).
2. **No member matches, and `g` carries neither `@field(name:)` nor `@nodeId`.** Descend:
   `g`'s own fields are hoisted into the enclosing bean's binding-key index under the access
   path `["g", <leaf>]`, and each hoisted leaf binds against the enclosing bean's members by
   the normal rules. Recursive: a hoisted group descends again.
3. **No member matches, and `g` carries `@field(name:)` or `@nodeId`.** Reject, naming the
   directive and the member it failed to find. Both directives are authored claims that the
   field binds to a named Java member; flattening past a claim that does not resolve would
   turn a typo into silently-different behaviour, which is the failure mode this item exists
   to remove rather than relocate.

`@table` on a grouping input is not a gate. It is deprecated and inert, so the type is an
ordinary grouping input and flattens exactly as its directiveless twin does, mirroring
R336's `nestedTableInput_flattensLikeItsDirectivelessTwin` decision on the column axis; the
existing deprecation advisory keeps firing on the nested type.

Paths are **bean-local**, not argument-absolute: a nested bean's helper receives its own
`Map`, so a path hoisted inside a nested bean is relative to that bean's `raw`. Same scoping
as `ColumnBinding.path`, which is relative to the record's own input `Map`.

The draft flagged one cost to weigh: that the SDL's meaning becomes a function of the Java
side. It does, but only for a directiveless nested field that binds to nothing today, so no
currently-working schema changes meaning. See Behaviour changes for the one exception, which
is on the JavaBean arm and moves a silent outcome to a loud one.

## D1: an access path on both `FieldBinding` encodings

Replace `sdlFieldName` with an ordered, non-empty `List<String> accessPath` plus an accessor
returning the last element, on both:

* `CallSiteExtraction.FieldBinding` (the classifier's carrier), and
* `ValueShape.FieldBinding` (the `ServiceMethodCall` carrier's composite).

**The accessor cannot be called `leaf()`.** `CallSiteExtraction.FieldBinding` already declares a
`CallSiteExtraction leaf` component, whose `leaf()` accessor is read by
`ServiceMethodCallWalker.fieldBindingShape` and `InputBeanInstantiationEmitter.notALeaf` to get
the *extraction*, not a path element. Borrowing `ColumnBinding`'s name here is a compile error on
one carrier and a different meaning on the other. Call it `mapKey()` on both `FieldBinding`
encodings: it names what the value is used for (the wire `Map` key), stays distinct from the
existing `leaf` component, and reads correctly on `ValueShape.FieldBinding` too. `ColumnBinding`
keeps `leaf()` unchanged; the two carriers are not required to agree on a name they cannot share.

Replace rather than add a second component: keeping both invites the emitter reading one and
the walker the other, and the compile error at every renamed call site is the forcing function
that makes each one consider the path. A top-level binding is a one-element path and every
emitted body is byte-identical to today. Constructor validation mirrors `ColumnBinding`:
non-empty path, no empty elements.

Consumers to update, all of them small: `InputBeanInstantiationEmitter.perFieldValueExpr` /
`notALeaf`, `ServiceMethodCallWalker.inputBeanToValueShape` /
`fieldBindingShape`, and `TypeFetcherGenerator.registerBeanHelper` /
`convertNestedFieldBindings` (six sites across three main-source files). The walker folds the
whole path onto the `ArgPath`
(`for (String s : fb.accessPath()) path = path.append(s)`); group segments are never
list-lifting, because a list-shaped group is rejected in D3, and the leaf's own list-ness
keeps riding `fb.list()` into the `ValueShape.ListOf` wrap exactly as today.

## D2: build the binding-key index by descent

`InputBeanResolver.buildInputBeanBody` today indexes SDL fields by binding key into
`Map<String, GraphQLInputObjectField> sdlByBindingKey` before dispatching to `bindRecord` /
`bindJavaBean`. Widen the index value to carry the access path alongside the field, and fill
it by a recursive walk applying the three-way rule above. The member set
(`javaMembersByName`) is already computed before the index is built, so the gate has what it
needs with no reordering.

Both arms then read the path off the index entry and pass it to the `FieldBinding`
constructor. Neither arm's invariant changes shape:

* **Record arm.** Direction A (every component binds) now finds hoisted components, which is
  the whole point: `varighetTall` and `varighettypeId` resolve. Direction B (every SDL field
  is consumed) is checked against index entries, so a group field never appears as an
  unconsumed key in its own right; a *hoisted leaf* that names no component still fails
  direction B, which is correct, because its value would otherwise be dropped on the way to
  the canonical constructor.
* **JavaBean arm.** Unchanged and still partial by design: a hoisted leaf whose key names no
  setter is skipped exactly as a top-level one is.

`bindField` itself does not change. The leaf transform for a hoisted leaf is computed from
the SDL field and the matched member exactly as before, including the `@nodeId` jOOQ-record
decode arm and the enum-parity check.

## D3: rejections

Parity with `collectJooqBindings`, adapted where the axis differs. Each is a
`Rejection.structural` surfacing at validate time, and each names the dotted access path so
the message points at the SDL the author wrote.

* **Cyclic grouping.** Load-bearing, not cosmetic: `buildInputBean`'s existing guard is a
  `Set<Class<?>> visited` on the Java axis, and a flattened group has no Java class to add,
  so an SDL type that reaches itself through directiveless nesting would recurse until the
  stack dies. Thread the `ClassifyContext` SDL-type-name `expanding` set through the descent,
  seeded with the bean's own input type, the same second axis `buildJooqRecord` threads.
* **List-shaped grouping input.** A group that is a list of groups has no flat member to land
  on. Reject, mirroring the column axis; the message should say to make the field singular
  rather than suggesting a `List<X>` member, since there is no member at all.
* **Collision.** The existing duplicate-binding-key rejection in `buildInputBeanBody` is
  already the right home and already fires before either arm builds. It now also catches a
  hoisted leaf colliding with a top-level field, and two groups hoisting the same key.
  Extend its message to dotted paths so `varighet.antall` versus `antall` reads clearly.
* **Depth.** No limit, matching `collectJooqBindings`. The cycle guard bounds the descent:
  each level adds a distinct SDL type name to a finite set.

## D4: emit one descent local per group prefix

`InputBeanInstantiationEmitter.buildSingularHelper` declares one typed local per field from
an expression rooted at `raw`. Keep that shape and change the root. Before the field locals,
declare one `Map<?, ?>` local per distinct group prefix in first-encounter order:

```java
Map<?, ?> varighetMap = raw.get("varighet") instanceof Map<?, ?> m ? m : null;
BigDecimal varighetTall = varighetMap == null ? null : (BigDecimal) varighetMap.get("antall");
String varighettypeId = varighetMap == null ? null : (String) varighetMap.get("varighettypeId");
```

`perFieldValueExpr` takes the root local name instead of hardcoding `raw`, so every arm's
body is unchanged and a depth-1 binding emits byte-identical output.

Note what the example above carries that the sentence does not: the `varighetMap == null ? null :`
guard is *not* part of any `perFieldValueExpr` arm, and no arm is null-safe against its own root
(`recordDecodeExpr` in particular passes `root.get(...)` straight into a decode helper). Swapping
the root without adding a guard NPEs on an absent group, contradicting the "absent group yields
null for every member" contract two paragraphs below. Either `buildSingularHelper` wraps every
hoisted field's expression in that guard, or, simpler, the descent local binds an empty map rather
than null:

```java
Map<?, ?> varighetMap = raw.get("varighet") instanceof Map<?, ?> m ? m : Map.of();
BigDecimal varighetTall = (BigDecimal) varighetMap.get("antall");
```

Then no guard is needed anywhere, every arm's body really is unchanged, and an absent group yields
`null` per member by ordinary `Map.get` semantics. Prefer this form; confirm at implementation that
the `NodeIdDecodeRecord` decode helper tolerates a null argument (it must already, since a top-level
omitted `@nodeId` field reaches it the same way), and fall back to the guard if it does not.

Statement form with
explicit types and named locals, per the generated-code-is-read-and-debugged principle that
`buildRecordDecodeHelper` already follows, and one descent per group rather than one per
sibling leaf. Local names come from the same `camelJoin(prefix) + "Map"` scheme the jOOQ
emitter uses; confirm at implementation that the collision exposure against a
same-named bean member is identical to the jOOQ emitter's (a collision is a javac error in
the consumer's generated sources, so it fails loud, but do not make it worse than the
precedent).

**The member axis has no omitted-versus-null tri-state.** On the column axis an absent group
leaves its columns `changed=false`; a bean member has only a value, so an absent, null, or
non-`Map` group yields `null` for every member hoisted out of it. That is exactly what
omitting those leaves at the top level of a flat input already does, so no new semantics
appear, and the graphql-java nested-present-`null` narrowing R336 hit in the execution tier
is invisible here (both readings produce `null`). Record targets pass those nulls positionally
to the canonical constructor; a hoisted component declared non-null in SDL is still
graphql-java's business at the boundary.

## D5: user documentation (first-client check)

New schema shape at `@service`, so the plan carries its own docs draft. Home:
`docs/manual/how-to/handle-services.adoc`, in the argument-flow section immediately after
the paragraph on `argMapping` reaching inside an input. Draft, to be transposed to AsciiDoc
when it moves:

> ### Grouping input fields under a nested input type
>
> An input type's fields can be clustered under nested input objects for the client's
> benefit while the backing Java class stays flat. A nested input field with no matching
> member on the backing class is treated as a *grouping*: its own fields bind against the
> backing class as if they had been declared at the top level of the enclosing input.
>
> ```graphql
> input FilmCreateInput {
>     title:    String!
>     duration: FilmDurationInput
> }
>
> input FilmDurationInput {
>     length:     Int
>     rentalDays: Int
> }
> ```
>
> ```java
> public record FilmCreateInput(String title, Integer length, Integer rentalDays) {}
> ```
>
> The client sends `duration: { length: 120 }`; the method receives `length = 120`. The
> grouping is wire-format ergonomics only and has no effect on the Java side.
>
> A nested input field whose name *does* match a member of the backing class keeps binding
> to that member as a nested object, so adding a matching member is how you opt a group back
> out of flattening. A group that is absent or null leaves every field under it null, the
> same as omitting those fields would.
>
> Rejected shapes, each named at build time: a grouping input that reaches itself; a
> list-shaped grouping input (there is nothing for a list of groups to flatten onto); two
> fields that bind to the same member, whether or not a group is involved; and a nested
> input field carrying `@field(name:)` or `@nodeId` whose named member does not exist, which
> is reported as the missing member rather than silently flattened.

If that reads badly at implementation time, the design is wrong and changes before the code
does.

## Tests

Follow R336's tier split. No generated-body string assertions.

* **Pipeline.** The existing `@service` bean coverage lives in
  `GraphitronSchemaBuilderTest`'s `RootFieldCase` rows
  (`SERVICE_MUTATION_FIELD_INPUT_BEAN_*`) and asserts on `ValueShape.FieldBinding`. Five assertion
  sites across four of those rows read `ValueShape.FieldBinding::sdlFieldName` (the `_SINGULAR`,
  `_PRIMITIVE_RECORD`, and both `*_FIELD_RENAMED_*` rows) and switch to `mapKey()` under D1; it is
  not only the two renamed rows. Add rows for:
  flatten onto a record with two-element paths; mixed top-level and hoisted leaves keeping
  their one- and two-element paths; a matching member still winning over the flatten
  (`periode`); depth 2; the JavaBean arm hoisting; a hoisted `@nodeId` record member. New
  record and JavaBean fixtures alongside `TestInputBeanRenamed`.
* **Pipeline, rejections.** One case per D3 arm plus the directive-gate reject, asserted by
  message substring, mirroring the reject cases in `JooqRecordServiceParamPipelineTest`
  (`cyclicNestedInput_rejects`, `listValuedNestedGrouping_rejects`,
  `plainColumnCollisionAcrossNesting_rejects`; R336's fourth became the accept case
  `nestedTableInput_flattensLikeItsDirectivelessTwin` when `@table` went inert). Each of these is
  an accept-to-reject on the JavaBean arm, so pin the *current* build-succeeds behaviour of each
  shape before changing it, and assert the new rejection against a JavaBean fixture as well as a
  record one.
* **Compilation.** Extend the `graphitron-sakila-example` schema and the
  `graphitron-sakila-service` bean behind `submitFilmReviewWithDetails` (or a sibling
  mutation, if reshaping that one loses the existing nested-bean pin) so a real flattened
  group type-checks against a real consumer record at `<release>17</release>`.
* **Execution.** Round-trip the flattened group through a real mutation: a present group
  binding its leaves, an absent group leaving them null, and a matching-member group still
  arriving as a nested object.

## Behaviour changes and accepted consequences

* **Record arm: reject to accept only.** Every schema this item newly admits fails the build
  today, on direction A or direction B. On this arm the new D3 rejections can only fire on shapes
  that already fail. There is no record-arm schema that builds today and stops building.
* **JavaBean arm: five new loud failures, and they are the second half of the fix.** Not one case,
  and not a cost. Today
  `bindJavaBean` skips an SDL field whose binding key names no setter with a bare `continue`,
  *before* `bindField` runs, so an unmatched nested input field is never descended into and never
  inspected at all. Every shape this item newly examines on that arm therefore builds today. After
  this change, all of these fail the build:
  * a group whose hoisted leaf key collides with a top-level field's key (the group is silently
    dropped today, the top-level field wins);
  * a directiveless group that reaches itself (D3 cycle);
  * a list-shaped group (D3 list);
  * two groups hoisting the same key (D3 collision);
  * a group carrying `@field(name:)` or `@nodeId` whose named member does not exist (binding rule 3).

  None of these can fire on a schema whose data currently arrives intact. In every one, the data
  under the group is *already* being discarded; the only thing that changes is that someone finds
  out. "It builds today" is not a property worth preserving here, it is the precise mechanism that
  produced the report this item comes from: the build stayed green, the client received nulls on
  fields it expected populated, and the defect surfaced only when a human noticed missing data.

  So this item delivers two things on the JavaBean arm, not one, and the second is not a side
  effect. A grouping shape that *can* work now works, by flattening. A grouping shape that *cannot*
  work now fails at build time with a message naming the SDL that caused it. Either outcome is
  acceptable; the third outcome, binding nothing and saying nothing, is the bug. Enumerate the full
  set in the changelog entry so a consumer hitting one of the five can see which shape they are in,
  and expect that a consumer whose schema is already dropping data will get a build break out of
  this item. That is the item working.

  **What this does narrow, deliberately:** the JavaBean arm's partial-by-design contract, which
  today tolerates *any* SDL field that binds to nothing. After this item that tolerance no longer
  extends to nested input-object fields, which either flatten or reject. An unmatched *scalar* field
  is still skipped in silence, so the arm is left inconsistent on purpose: this item narrows the
  contract exactly as far as it can see. It has to look inside a nested field to flatten it, so it
  can no longer be blind there, whereas a scalar drop needs the separate diagnostic machinery R695
  carries. Worth saying out loud in the changelog, because the asymmetry is otherwise a surprise.

  The record arm is unaffected by this widening, for the reason the bullet above gives: direction B
  already rejects every unmatched field, so nothing on that arm reaches the new checks without
  failing first.
* **Bean-helper dedup is keyed by bean class, and flattening widens what that hides.** Two
  `@service` fields binding one bean class through two SDL input types collapse to one
  `create<Bean>` helper (`collectTransitively` and `registerBeanHelper` both
  `putIfAbsent(beanClass, ...)`), so divergent `@field(name:)` mappings already route
  through the first-seen helper. This is the member-axis twin of the R437 correctness bug,
  it exists today, and this item neither fixes nor worsens its failure class; it only adds
  access paths to the set of things that can diverge. Filed as R694.

## Out of scope

* The record arm's totality requirement. A component that no SDL field is meant to populate
  (a service-filled value) still fails direction A of the bijection; that is a separate gap
  and deserves its own item if it turns out to matter.
* `@nodeId` on a bean member typed as a plain `String`, which binds the raw wire id rather
  than decoding. Orthogonal to nesting, and unchanged by this item.
* Any change to the jOOQ-record axis, the `@table`-input path, or the graphitron-emitted
  input record class, which mirrors the SDL and stays SDL-shaped.
* Re-keying the bean-helper dedup on binding shape (R694).
* A lint rule for an SDL input field that binds to no member on a JavaBean-target bean
  (R695). The Backlog draft raised this as an open question; it is answered by deferral,
  because the specific silent drop this item names is *fixed* here rather than warned about,
  and what remains is the JavaBean arm's pre-existing partial-by-design contract, whose
  diagnostic needs its own `LintRule` arm, source locations, and a call on noise against
  deliberately-partial beans. Note the relationship this item creates: R693 takes the first bite out
  of that contract by refusing to skip an unmatched nested input-object field, so R695 is no longer
  an independent lint idea but the remainder of the same job on the scalar axis. Whether that
  remainder should also be a hard reject rather than a lint is R695's call to make, informed by
  whatever noise this item's rejections turn out to produce against real consumer beans.
* Reclassifying the grouping input type itself. A flattened group's leaves are bean members
  and hover should eventually say so; that surface is R337's, and this item consults the
  enclosing bean's member set only, never the group type's own verdict.

## Coordination with adjacent items

* **R402** deletes the `ValueShape` to synthetic `CallSiteExtraction.InputBean` round-trip
  that forces D1 to widen two encodings instead of one. Not a dependency in either
  direction: whichever lands first, the other's diff shrinks. If R402 lands first, D1 is
  one carrier wide.
* **R518**'s `argMapping` grouping form is the explicit escape valve for shapes the implicit
  rule here cannot express (one input's fields scattering across several service params).
  Complementary, not competing: this item is the convention, that one is the override.
* **The shipped `argMapping` dot-path** (`argMapping: "title: in.title"`, documented at
  `docs/manual/how-to/handle-services.adoc` under "Nested input types") already reaches inside an
  input, so it is the nearest existing mechanism and worth distinguishing explicitly: it rebinds
  *method parameters*, scattering an input's fields across several of them. It cannot express the
  motivating case, where there is one bean parameter and the binding to fix is on the bean's
  *members*. Different axis, no overlap.
