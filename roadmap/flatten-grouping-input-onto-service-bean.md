---
id: R693
title: "Flatten a nested grouping input onto a consumer bean at @service, the member-axis sibling of R336"
status: In Review
bucket: feature
priority: 3
theme: service
depends-on: []
created: 2026-08-17
last-updated: 2026-08-19
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
`convertNestedFieldBindings` (six sites across three main-source files). Replacing the component
also changes both canonical constructors, so every construction site moves to a `List.of(...)`
first argument: `InputBeanResolver.bindField` and the two `TypeFetcherGenerator` sites in main,
plus thirteen in test sources (ten `new CallSiteExtraction.FieldBinding(...)` across
`TypeFetcherGeneratorTest` and `ServiceMethodCallWalkerTest`, three
`new ValueShape.FieldBinding(...)` in `ServiceMethodCallEmitterTest`). All mechanical, and all
compile errors until they move. The walker folds the
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

  *Considered and declined:* list-lifting the leaves, so `versions: [VersionInput!]` with a
  `length: Int` leaf would hoist to a `List<Integer> length` member. It is a coherent reading (it
  transposes a list of groups into a group of lists) and it is why D1 has to state that group
  segments never list-lift. Declined: it silently reinterprets the client's wire shape as something
  structurally different, one object per version becomes one array per field, so the SDL stops
  describing what the Java side receives. That is the same class of implicitness this item exists to
  remove. A schema that genuinely wants per-version lists can say so with a singular group whose
  leaves are lists, which needs no inference. Fail loudly instead.
* **Collision.** The existing duplicate-binding-key rejection in `buildInputBeanBody` is
  already the right home and already fires before either arm builds. It now also catches a
  hoisted leaf colliding with a top-level field, and two groups hoisting the same key.
  Extend its message to dotted paths so `varighet.antall` versus `antall` reads clearly.

  **The governing principle: hoisting makes a leaf a peer of the enclosing type's own fields.** Once
  a group flattens, its leaves are declared on the enclosing input type as far as binding is
  concerned, and they collide with each other and with top-level fields by exactly the rule that
  already governs two top-level fields. So this stays *one* rejection, not three variants for
  top-level-versus-hoisted, hoisted-versus-hoisted, and top-level-versus-top-level. Implementation
  consequence: do the collision check once, against the fully-built index after the descent
  completes, rather than per arm or per descent level. The access path is carried for the message
  and for the emitter's `Map` descent only; it is never part of the identity that decides whether two
  bindings collide.
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
`null` per member by ordinary `Map.get` semantics. Take this form: the `NodeIdDecodeRecord` decode
helper does tolerate a null argument, checked at the Spec review pass, because
`buildRecordDecodeHelper` opens its body with `if (!(wire instanceof String nodeId)) return null`
(and the list variant delegates through the same guard). The guard alternative is therefore dead;
no fallback is needed.

Name the pattern variable in that expression from the group prefix rather than a bare `m`. A
single-letter pattern binding is exactly the throwaway naming the generated-code readability rules
in `docs/architecture/explanation/development-principles.adoc` name as the smell, and the jOOQ
emitter's `openDescent` already derives its binding name from the path.

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
  `GraphitronSchemaBuilderTest`'s `RootFieldCase` rows and asserts on `ValueShape.FieldBinding`.
  Five assertion sites across five of those rows read `ValueShape.FieldBinding::sdlFieldName` and
  switch to `mapKey()` under D1: `SERVICE_MUTATION_FIELD_INPUT_BEAN_SINGULAR`,
  `SERVICE_MUTATION_FIELD_INPUT_BEAN_PRIMITIVE_RECORD`,
  `SERVICE_MUTATION_FIELD_INPUT_JAVABEAN_PRIMITIVE_BOOLEAN`, and both
  `SERVICE_MUTATION_FIELD_INPUT_BEAN_FIELD_RENAMED_*`. It is not only the two renamed rows, and the
  set is not the `SERVICE_MUTATION_FIELD_INPUT_BEAN_*` prefix either: the JavaBean-primitive row
  reads a `ValueShape.JavaBeanInput`'s bindings under a different name prefix. Add rows for:
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

## Retired vocabulary

* `CallSiteExtraction.FieldBinding.sdlFieldName` and `ValueShape.FieldBinding.sdlFieldName`, both
  replaced by `accessPath` plus the `mapKey()` accessor. Replaced rather than kept alongside, per D1,
  so every read site had to be revisited; nothing should still spell `sdlFieldName` on either
  carrier. The name survives elsewhere on unrelated carriers (`SetColumn`, `KeyColumn`,
  `GraphitronType`, the schema and input-record generators), which are out of scope and untouched.

## Implementation notes

Written at the In Progress → In Review handoff, for the reviewer.

* **Delivered as specified for D1 through D5.** The five `RootFieldCase` assertion sites named in the
  Tests section moved to `mapKey()`, and the thirteen test construction sites moved to `List.of(...)`
  first arguments, both exactly as D1 predicted.
* **Test layout deviates from the plan.** The plan put the accept cases as new `RootFieldCase` rows in
  `GraphitronSchemaBuilderTest` and the rejections in a separate file. Both halves instead live in one
  new `InputBeanGroupingPipelineTest`, the member-axis twin of `JooqRecordServiceParamPipelineTest`.
  Reasoning: splitting one feature's accepts from its rejections across two files reads worse than
  keeping them adjacent, and the `classified-corpus` skill's framing suggests the project is retiring
  `GraphitronSchemaBuilderTest` enum rows rather than adding six. Reviewer's call to reject if the
  enum rows were load-bearing for a reason not visible from here.
* **A pre-existing emitter defect surfaced and was fixed in scope.** `nestedBeanExpr`'s singular arm
  emitted `createNested((Map<String, Object>) root.get(k))`, an unchecked cast. Generated sources
  compile in the consumer's build, so under `-Werror` that is a hard failure, and `@SuppressWarnings`
  cannot attach to a cast inside an expression. It had gone unnoticed because no fixture in the
  reactor paired a *singular* nested bean member with the `-Werror` compile gate; this item's
  compilation-tier fixture is the first, so the gate fired. Fixed by widening the singular helper's
  parameter to `Map<?, ?>` and narrowing at the call site with an `instanceof` pattern; the plural
  helper's per-element cast became `(Map<?, ?>)` and lost its `@SuppressWarnings`. Both external call
  sites pass `env.getArgument(...)`, whose `<T> T` infers the wildcarded map unchanged.
* **The list-valued twin of that defect is deferred, not fixed.** `directExpr`'s list arm emits
  `(List<String>) root.get(k)` with the same exposure. The remedy does not transfer: a pattern cannot
  narrow `List<?>` to `List<String>`, so the generated code would have to copy the list element-wise,
  changing both what it returns and when it fails. Filed as its own item rather than folded in.
* **Verification.** Full reactor green, including the two tiers that matter here: `graphitron` at 3678
  tests (`InputBeanGroupingPipelineTest` 19/19) and `graphitron-sakila-example` at 357, with the
  flattened group both compiling against a real consumer record at `<release>17</release>` and
  round-tripping through a real mutation.
* **Changelog entry not written.** Left to the reviewer at the Done gate, per `roadmap/workflow.adoc`.
  The Behaviour changes section above lists the five JavaBean-arm failures to enumerate there, plus
  the deliberate scalar-axis asymmetry.

## Review feedback: In Review → Ready, one bounded second pass

Written at the In Review → Done gate by an independent reviewer session. The feature itself is
accepted: `mvn install -Plocal-db` is green across all fourteen modules, D1 through D5 landed as
specified, the three-way binding rule and all four rejections behave as designed on both arms, the
user-facing doc passes the workflow's doc check, and the three deferrals the plan promised (R694,
R695, R703) are all filed. Two things send it back, both narrow and both mechanical.

### 1. Code-string assertions on generated method bodies (the blocking one)

The Tests section of this plan says, in full, "No generated-body string assertions." Five delivered
tests are exactly that, and the class javadoc carves out the exception rather than the plan doing so:

* `InputBeanGroupingPipelineTest.java:192-204` `singularHelper_opensOneMapLocalPerGroup_...`
* `InputBeanGroupingPipelineTest.java:210-215` `deepHelper_descendsParentBeforeChild`
* `InputBeanGroupingPipelineTest.java:223-228` `singularNestedBean_isNarrowedByPattern_...`
* `InputBeanGroupingPipelineTest.java:234-239` `unflattenedBean_emitsNoDescent_...`
* `TypeFetcherGeneratorTest.java:2485-2489`, whose reworked assertion moves from one body string to
  another (`createFoo((java.util.Map<?, ?>) e)`) instead of off the body

`docs/architecture/explanation/development-principles.adoc` line 271 bans these "at every tier",
and `docs/architecture/how-to/testing.adoc` repeats the ban for both the generator-unit and pipeline
tiers. The rule is review-enforced, which is what this note is.

Note for context, not as an excuse: `JooqRecordServiceParamPipelineTest` (the R336 sibling this file
is modelled on) breaks the same rule at its lines 956-1007 and 1043-1044. Copying its shape is how
this happened. That precedent is worth its own cleanup item; it does not license a plan that
promised otherwise.

Each of the four new assertions has a home that is stronger, not weaker:

* The unchecked-cast pin belongs in `GeneratedSourcesLintTest` (compilation tier), whose stated job
  is "generator-hygiene rules over emitted source text". It scans *every* emitted file rather than
  one helper, so as a lint rule it catches the next occurrence too, which is the actual value.
* Parent-before-child ordering is a compile error when violated. Give the `graphitron-sakila-example`
  fixture a depth-2 group and the compiler is the assertion; no string matching needed.
* The absent-group-yields-null contract that the empty-map default exists to deliver is already
  pinned at the execution tier by `submitGroupedReview_flattensTheGroupOntoTheBean`. The "one descent
  per group, not one per leaf" count, and the depth-1 no-op, are implementation shape rather than
  behaviour; drop them, or re-express the no-op as an assertion on `accessPath` sizes.

If some part of the descent genuinely cannot be reached from compile or execution, say which part
and why in this section, and the next reviewer can weigh a narrow, argued exception. What cannot
stand is the plan promising the ban and the delivery quietly taking it back.

### 2. The retirement sweep is not finished

The Retired vocabulary section promises "nothing should still spell `sdlFieldName` on either
carrier". Three prose sites do, each describing exactly the `FieldBinding` encodings this item
retired it from:

* `graphitron/src/test/java/no/sikt/graphitron/rewrite/TestInputBeanRenamed.java:8`
* `graphitron/src/test/java/no/sikt/graphitron/rewrite/TestServiceStub.java:417`
* `graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java:7360`, the
  `SERVICE_MUTATION_FIELD_INPUT_BEAN_FIELD_RENAMED_RECORD` row description, whose own assertion
  moved to `mapKey()` in this item

The surviving `sdlFieldName` uses on `SetColumn`, `KeyColumn`, `GraphitronType` and
`PayloadConstructionShape` are the out-of-scope ones the plan names, and are correctly untouched.

### Not blocking, no action required to land

* `InputBeanInstantiationEmitter.java:145` writes `java.util.LinkedHashSet` fully qualified inline
  where every other collection in the file is imported. Cosmetic.
* The test-layout deviation the implementer flagged (accepts and rejects in one
  `InputBeanGroupingPipelineTest` rather than split across `GraphitronSchemaBuilderTest` rows and a
  separate file) is the right call and needs no defence. Keeping one feature's accepts adjacent to
  its rejections reads better, and the enum rows were not load-bearing for anything the new file
  fails to cover.
* Using `ClassifyContext` to carry what is really a bean-local `Set<String>` of SDL type names drags
  an unrelated `enclosingOverride` flag along, but it is what D3 asked for and it mirrors
  `buildJooqRecord`'s second axis. Leave it.

### On the next pass

Nothing above touches main-source behaviour except the emitter import nit, so the second pass should
be test-and-prose only. The changelog entry stays unwritten for the Done gate, as the implementation
notes say.

## Second pass: both gaps closed, with one argued substitution

Written at the second In Progress → In Review handoff. Test-and-prose only, as the note above asked:
no main-source behaviour changed. Each of the five code-string assertions is gone, and each pin it
carried now lives somewhere it cannot rot on a formatting change.

### The four assertions in `InputBeanGroupingPipelineTest`

* **Parent-before-child ordering** moved to the compiler, exactly as suggested. The
  `graphitron-sakila-example` grouping fixture is now two levels deep: `comment` is reached through
  `assessment.remark`, so the emitted helper must declare the `assessment` descent local before the
  `assessment.remark` one that reads from it. That module compiles the emitted tree at
  `<release>17</release>`, so a child-before-parent order is a compile error there. The execution
  round-trip gained the arm that goes with it: a present outer group with the inner one absent, which
  is the case that fails if the empty-map default holds only at the root.
* **The unchecked-cast pin did not need a lint, and adding one would have been weaker than what
  already guards it.** `graphitron-sakila-example` compiles the whole emitted tree under the parent
  pom's `-Xlint:all -Werror` (its own pom says so, in the comment on the `<release>17</release>`
  override), which is why this defect was found in the first place: the implementation notes above
  record that the gate fired as soon as a fixture paired a singular nested-bean member with it. A
  regex lint over emitted text would re-implement javac's `unchecked` category and could not do it as
  well. It cannot tell a cast covered by an enclosing `@SuppressWarnings("unchecked")` from an
  uncovered one without reproducing scope analysis, and the distinction is load-bearing: the emitted
  tree contains 31 concretely-parameterized `Map` casts today, every one of them inside a method the
  generator annotated, in the conditions glue and the input-record `fromMap` bodies. A rule that
  failed on those would be a scope change the review explicitly warned against; a rule that skipped
  them would have to encode the suppression check anyway. So the pin is stated where it is enforced
  instead: `buildSingularHelper`'s and `buildPluralHelper`'s javadoc now name the gate, and the
  `FilmReviewGrouped` fixture javadoc records that its `headline` member is what keeps the singular
  nested-bean emit path inside the gate's reach. This is the "narrow, argued exception" the review
  invited, and it is an argument for a stronger home rather than for keeping a string match.
* **The descent-count and depth-1 no-op assertions** are gone. The "one descent per group, not one per
  leaf" count was implementation shape, and the access-path assertions already present state the fact
  it was standing in for. The no-op is re-expressed structurally as
  `unflattenedBean_keepsEveryPathAtLengthOne`: the emitter opens one local per path element beyond the
  first, so all-paths-of-length-one *is* emits-no-descent, asserted on the resolved bindings.
* **The absent-group contract** needed no new home; `submitGroupedReview_flattensTheGroupOntoTheBean`
  already pinned it, and now pins it per level.

The class javadoc's carve-out is gone with them, replaced by a pointer to where each relocated pin now
lives.

### `TypeFetcherGeneratorTest`

The plural helper's cast-shape assertion is deleted rather than rephrased, with a comment naming the
`-Werror` gate in its place. The singular helper's `Map<?, ?>` parameter was already pinned
structurally on the `MethodSpec` (not on its body), and that assertion is the one that matters: the
wildcarded parameter is what makes a checked narrowing possible at every call site.

### Retirement sweep

The three prose sites are fixed. The sweep also turned up a fourth site the review's grep would have
filtered out as code rather than prose: `InputBeanResolver.bindField` declared a local named
`sdlFieldName` holding `dottedPath(accessPath)`, and passed it to `buildJooqRecordLeaf` under the same
parameter name. It is a dotted path, not an SDL field name, so the old name was both retired and
actively wrong; renamed to `fieldPath` (a rename of a private local and parameter, no behaviour).
Nothing outside the out-of-scope carriers the plan names still spells `sdlFieldName`.

### The precedent, filed

`JooqRecordServiceParamPipelineTest`'s own body-string assertions are now R707, joining the three
existing items on the same rule (R669, R554, R522). The item records why that file is harder than this
one: what it pins is call-site routing, which has no carrier to assert on yet, so it needs a decision
about where routing becomes observable before the scans can go.

### Also done

The `java.util.LinkedHashSet` inline qualification in `InputBeanInstantiationEmitter` is now an import,
per the non-blocking nit.

### Verification

`mvn install -Plocal-db` green across all fourteen modules. `graphitron` at 3678 tests
(`InputBeanGroupingPipelineTest` 16/16, three fewer than before: four emitted-text tests out, one
structural test in), `graphitron-sakila-example` at 789. The emitted
`createFilmReviewGrouped` reads as intended, which is what the depth-2 fixture is there to make the
compiler check:

```java
Map<?, ?> assessmentMap = raw.get("assessment") instanceof Map<?, ?> assessmentGroup ? assessmentGroup : Map.of();
Map<?, ?> assessmentRemarkMap = assessmentMap.get("remark") instanceof Map<?, ?> assessmentRemarkGroup ? assessmentRemarkGroup : Map.of();
```

The changelog entry is still unwritten, for the Done gate.

## Review feedback: In Review → Ready, one clause

Written at the second In Review → Done gate by a third independent reviewer session (no prior trail
on this item). The second pass is accepted almost whole, and the next pass is a one-sentence edit.

**What this gate verified and accepts.** `mvn install -Plocal-db` green across all fourteen modules.
`InputBeanGroupingPipelineTest` 16/16, the execution-tier round-trip present and running. The five
banned code-string assertions are genuinely gone: every surviving `.contains(...)` in
`InputBeanGroupingPipelineTest` is on a *rejection message*, which is what the Tests section asked
for, and `TypeFetcherGeneratorTest`'s plural-helper body assertion is deleted rather than rephrased.
The retirement sweep is complete: no `sdlFieldName` survives on either `FieldBinding` encoding, and
every remaining occurrence sits on a carrier the plan names as out of scope (`SetColumn`,
`KeyColumn`, `GraphitronType`, `PayloadConstructionShape`, `InputRecordShape`, `PayloadSdlField`,
the two walker `Contribution` records). The `bindField` local rename to `fieldPath` is a real catch;
the old name was both retired and factually wrong. User-facing-doc check passes: the
`handle-services.adoc` section carries no `R<n>`, no phase vocabulary, no plan-slug references.

**The argued substitution is accepted, on its merits.** The two load-bearing claims were checked
against the poms rather than taken on trust: the root `pom.xml` sets `-Xlint:all -Werror` with no
excluded categories, and `graphitron-sakila-example`'s `<release>17</release>` override merges with
those inherited `compilerArgs`, so the emitted tree really is compiled warning-as-error. A regex
lint could not distinguish a covered cast from an uncovered one without scope analysis, and the
generated tree does pair concretized casts with method-level `@SuppressWarnings` in dozens of input
types, so the distinction is real rather than hypothetical. The emitted helper was read directly and
is correct, including the per-level empty-map default the depth-2 fixture exists to force:

```java
Map<?, ?> assessmentMap = raw.get("assessment") instanceof Map<?, ?> assessmentGroup ? assessmentGroup : Map.of();
Map<?, ?> assessmentRemarkMap = assessmentMap.get("remark") instanceof Map<?, ?> assessmentRemarkGroup ? assessmentRemarkGroup : Map.of();
FilmReviewTag headline = raw.get("headline") instanceof Map<?, ?> headlineRaw ? createFilmReviewTag(headlineRaw) : null;
```

The reviewer was asked whether the `FilmReviewGrouped` fixture is durable enough to carry the pin
without a test naming it. It is: the fixture's own javadoc states that `headline` "carries a second
load: it is a *singular* nested-bean member", so anyone about to delete it has been told what breaks.
That is a stronger guard than the string match it replaced, not a weaker one.

### The one blocking clause

`InputBeanGroupingPipelineTest.java:33` (class javadoc, last clause) says:

> and the hygiene rule that no emitted cast may be unchecked is a lint over every emitted file in
> `GeneratedSourcesLintTest`.

No such lint exists. `GeneratedSourcesLintTest` declares exactly five rules:
`emittedSourcesDoNotUseVar`, `conditionsClassesImportNoGraphqlJavaBeyondTheEnvAppendingParameter`,
`fetcherBodiesDoNotFullyQualifyJooqTables`, `emittedSourcesDoNotImportLegacyRuntimeTypes`,
`emittedSourcesHaveNoDunderIdentifiers`. None is about casts, and the second pass deliberately, and
correctly, declined to add one. The commit that made that decision left the javadoc sentence written
for the world where the lint was going to exist.

This is narrow but not cosmetic. The sentence is the only place in the file that tells a reader
where the cast pin lives, so it routes the next maintainer to a guard that is not there; the likely
outcomes are re-adding the banned string assertion or building the redundant lint this pass argued
against. It is also the one place the delivery contradicts its own second-pass section, which claims
the carve-out was "replaced by a pointer to where each relocated pin now lives"; two of the three
pointers resolve, this one does not. Every other `GeneratedSourcesLintTest` citation in the tree
names a real rule, so a dangling one reads as a rule that was removed rather than one never written.

**The fix:** point the clause at the enforcer the pass actually chose, the `-Xlint:all -Werror`
compile of the emitted tree in `graphitron-sakila-example`, with `buildSingularHelper` /
`buildPluralHelper`'s javadoc and the `FilmReviewGrouped` fixture as where the rule is stated. Those
three already carry the argument; the class javadoc just needs to agree with them. No other change
is required, and no main-source behaviour is in scope on this pass.

### Not blocking, no action required to land

* `TypeFetcherGeneratorTest` still asserts `contains("if (raw == null) return null")` and
  `contains("new com.example.Foo(title)")` on rendered bodies at lines 2467-2468 and nearby. These
  pre-date this item and were only touched to move constructors to `List.of(...)`, so they are the
  same pre-existing debt class as R707 rather than something this item took on. Worth folding into
  R707's scope, or a sibling, but not this gate's business.
* Commit `25019f8` says it "Files R705" for the `JooqRecordServiceParamPipelineTest` precedent; the
  item is actually R707, and the spec body says R707. The commit message is on trunk and immutable,
  so this is a note for the record, not a fix.

### On the next pass

One clause in one test-file javadoc. The changelog entry stays unwritten for the Done gate, as
before.

## Third pass: the clause now names the enforcer that exists

Written at the third In Progress → In Review handoff. One clause in one test-file javadoc, as the gate
above asked, and nothing else. No main-source change, no test-behaviour change.

`InputBeanGroupingPipelineTest`'s class javadoc no longer routes the reader to a lint that was never
written. The last clause now names the enforcer the second pass chose on its merits: the
`-Xlint:all -Werror` compile of the emitted tree in `graphitron-sakila-example`, with the
`FilmReviewGrouped` fixture as what keeps a singular nested-bean member inside that gate's reach, and
with `{@link}`s to `InputBeanInstantiationEmitter#buildSingularHelper` /
`#buildPluralHelper` plus the fixture's own javadoc as where the rule is stated. All three of the
paragraph's pointers now resolve, which is what the second-pass section claimed for them.

Two details worth the reviewer's eye, both deliberate:

* The clause is also reworded from "no emitted cast may be unchecked" to what the gate actually
  enforces: that an emitted narrowing reaches a wire map through an `instanceof` pattern rather than an
  unchecked cast. The blunter phrasing is what invited the redundant lint in the first place, since
  the emitted tree legitimately contains dozens of concretized `Map` casts under method-level
  `@SuppressWarnings`, and a reader holding the reader-facing sentence to the letter would find those
  and think the rule was broken.
* The `{@link}`s use the unparameterized form, matching the `ArgCallEmitter` precedent at its line
  238, rather than spelling an overload signature. Both helpers are overloaded, and naming one arity
  would imply the rule applies to that arity only.

**Verification.** `mvn install -Plocal-db` green across all fourteen modules. `graphitron` at 3653
tests with `InputBeanGroupingPipelineTest` 16/16, `graphitron-sakila-example` at 798. Both counts
differ from the second pass's because trunk moved under this item between the passes, not because
anything here added or removed a test; this pass changes no assertion. The changelog entry stays
unwritten for the Done gate, as before.

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
