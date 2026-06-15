---
id: R200
title: Honor @field(name:) in InputBeanResolver for SDL input bean/record member binding
status: In Review
bucket: bug
priority: 5
theme: service
depends-on: []
created: 2026-05-20
last-updated: 2026-06-15
---

# Honor @field(name:) in InputBeanResolver for SDL input bean/record member binding

`InputBeanResolver` populates a `@service` Java parameter from an SDL `INPUT_OBJECT`
by matching SDL field names against JavaBean setter-derived property names
(`indexJavaBeanSetters`, `InputBeanResolver.java:461-475`) or canonical record
component names (`indexRecordComponents`, `:446-453`). It reads **zero directives**:
`buildInputBeanBody` iterates `sdlOrder(iot)` (JavaBean) or `recordOrder(beanClass)`
(record) and looks the name up in `javaMembersBySdlName` (`:255-271`). When a Java
member name diverges from the SDL field name, the binding is lost:

- **JavaBean:** the SDL field finds no member, is skipped (`:270`), and if *no* field
  matches by name the bean produces zero bindings → the rejection
  `bean class '<X>' has no fields matching the SDL input type '<Y>'` (`:322-327`).
  This is the surfaced symptom (`UtdanningsspesifikasjonsstatusService` /
  `EndreUtdanningsspesifikasjonsstatusInput` in `utdanningsregisteret`).
- **Record:** the record arm drives `recordOrder` (component names) and looks members
  up in `indexRecordComponents`, which is keyed by the **same** component names, so the
  `member == null` arm at `:259-260` is unreachable for records and its
  `record '<X>' has no component named '<f>'` rejection (`:264-268`) is **dead code**.
  The live failure is the silent drop at `:272-277`: `iot.getField(componentName)`
  returns `null` whenever a component has no same-named SDL field, hits `continue`, and
  yields an **under-arity** argument list the emitter feeds to the canonical
  constructor. This is reachable **today, without `@field`** (a plain component/SDL name
  mismatch), so the `:274-275` comment ("For records this can't happen") is already
  false; honoring `@field(name:)` only widens the set of mismatches that reach it. A
  record's correspondence to its SDL input type is **bidirectional and total**: every
  component must bind (else under-arity) and every SDL field must be consumed (else
  silent data drop, the unreachable `:264-268`'s intent). This item closes both
  directions (see Implementation).

`@field(name: "<javaMemberName>")` on the SDL input field is the author escape hatch
and is the input-side mirror of R191 (shipped), which made `@field(name:)` name the
**Java accessor** on free-form `@record`-shaped *output* parents
(`FieldBuilder.collectAccessorMatches`). The directive's `INPUT_FIELD_DEFINITION` scope
(`directives.graphqls:39`) already admits it here; only the resolver ignores it. The
fix reads `DIR_FIELD` per SDL field and uses the directive value as the Java-member
binding key, restoring `@field` symmetry across input bean/record binding (this item)
and output record construction (R201).

## The binding axis this item owns

`@field(name:)` is **site-determined**. On a free-form `@record`/POJO/Java-record
parent it names a **Java member** (the axis R191 added on output, this item adds on
input). On a `@table`-bound input it names a **jOOQ column** (R97's axis). The seam
between this item and R97 is the *parent backing class*, and the code already forks on
it: `looksLikeBeanCandidate` (`:506-515`) rejects `org.jooq.*` classes, so a top-level
`@service` parameter whose own type is a jOOQ `TableRecord` never enters
`buildInputBean` at all. This item owns the consumer-authored POJO/Java-record param —
**including a jOOQ-record-typed *member* nested inside it** (R195's `NodeIdDecodeRecord`
arm at `:303-318`, which reads `@nodeId`, not `@field`, and is untouched here). It does
**not** own the case where the param *is* a jOOQ `TableRecord` (`@table`-on-input
column-axis `@field` + `@nodeId` → scalar-key decode); that is R97 plus the deferred
"top-level `@service` param is a jOOQ record" follow-on noted in R195's changelog entry.

> Consumer note: the motivating `utdanningsregisteret` error
> (`EndreUtdanningsspesifikasjonsstatusInput @table` bound to
> `UtdanningsspesifikasjonsstatusRecord`) is **not** closed by R200 — its `@field`
> values name columns (`DATO_FRA`), its `@nodeId` IDs decode into scalar key columns,
> and the param is a jOOQ `TableRecord`. That schema's path forward is R97, or
> switching to a consumer-authored input record (the `inputs.*Record` pattern used by
> `opprettUtdanningsmulighet`). R200 fixes the consumer-authored-bean shape only.

## Implementation

All changes are in `InputBeanResolver.buildInputBeanBody` and its helpers; no model or
emitter change. `CallSiteExtraction.FieldBinding` already carries `javaFieldName`
separately from `sdlFieldName` (`CallSiteExtraction.java:295-303`; populated at
`InputBeanResolver:319-320` from `member.javaName()`), so the runtime call path is
agnostic to *how* the Java member was chosen, the same property R191 relied on. The item
adds **no new `CallSiteExtraction` leaf variant** (it changes *which* member a binding
selects and rejects binding ambiguity before a binding is built), so
`InputBeanInstantiationEmitter.perFieldValueExpr`'s exhaustive leaf `switch` with no
default (`InputBeanInstantiationEmitter.java:129-146`) is intentionally unaffected.

- **Read the directive once per SDL field.** Add a helper computing the Java-member
  binding key for an SDL field using the house `@field` read idiom
  (`f.hasAppliedDirective(DIR_FIELD) ? argString(f, DIR_FIELD, ARG_NAME).orElse(f.getName()) : f.getName()`).
  That idiom is the column-axis read at `BuildContext.java:1769-1772`; R191's
  Java-member-axis read lives in `FieldBuilder.collectAccessorMatches`
  (`FieldBuilder.java:4749+`). Import `DIR_FIELD` / `ARG_NAME` from `BuildContext` (the
  file already imports `ARG_TYPE_NAME` / `DIR_NODE_ID` / `argString` from there, `:28-30`).

- **Compute the target once, dispatch into two named arms.** The record-vs-JavaBean
  fork is computed once at `:237-251` (`Target.RECORD` / `Target.JAVA_BEAN`) but is then
  re-derived by re-testing `beanClass.isRecord()` through the loop body (`:255`, `:264`).
  Lift the per-target correspondence walk into two private helpers dispatched from a
  single `switch (target)`, `bindRecord(...)` and `bindJavaBean(...)`, each returning a
  `List<FieldBinding>` or a `Built.Fail`. The two arms encode a real invariant
  difference (records are positional and total; JavaBean setters are independent and
  partial), so the two iteration spines stay; only the scattered `isRecord()` re-tests
  collapse. The shared per-field leaf classification (`:278-321`) and the binding-key
  helper factor out as common steps both arms call. This localizes the record arity
  guarantee to one method instead of spreading it across mid-body branches.

- **JavaBean arm (`bindJavaBean`).** Iterate `sdlOrder(iot)`; for each SDL field look up
  the member by `bindingKey(sdlField)` rather than `sdlField.getName()`. A field whose
  key matches no setter still skips (`:270`, unchanged JavaBean tolerance: setters are
  applied independently, so partial population is legal). The empty-bindings rejection
  (`:322-327`) then fires only when *no* field, by name or by `@field`, matches: the
  genuine "this bean does not mirror this input" case.

- **Record arm (`bindRecord`): one bidirectional bijection check.** Build a
  `Map<String /*bindingKey*/, GraphQLInputObjectField>` from the SDL fields keyed by
  `bindingKey(f)` (collision-checked, see the ambiguity guard), then reconcile it
  against `recordOrder` in a single reduction, the input twin of R191's zero/one/many
  accessor reduction (`FieldBuilder.java:4658-4687`):
  - **Every component must bind.** A component with no entry in the map (no field named
    after it, none carrying `@field(name: "<component>")`) is a hard `Built.Fail`: the
    canonical constructor needs every component, so a missing one must fail at classify
    time rather than emit a malformed N-1-arg call. *(direction A, the under-arity drop
    that is live today)*
  - **Every SDL field must be consumed.** An SDL field whose binding key names no
    component is a hard `Built.Fail` naming the field and its key. The justification is
    the **total-mirror-by-contract** invariant, not "silent data loss": the JavaBean arm
    is lossy in exactly the same way (an unconsumed SDL field is skipped at `:270`) yet
    deliberately tolerated because beans are partial by design, so a data-loss framing
    would wrongly indict it too. *(direction B, the intent of the dead `:264-268`; it is
    also a behavioural flip with a weaker safety net than direction A, see Tests)*
  **Delete the dead `:264-268` branch.** Its "has no component named" message is
  unreachable on the record arm (the loop drives `recordOrder` against a
  component-keyed index, so `member == null` never fires for a record), and direction B
  subsumes its intent. Shipping a live direction-A Fail beside a dead direction-B Fail
  is the shape to avoid: one reduction owns both directions and no arm is left dead.

- **Reject member-binding ambiguity.** Two SDL fields resolving to the same binding key
  (two `@field` values colliding, or a `@field` value colliding with another field's
  plain name) is a structural `Built.Fail`. The symptom differs by arm: on the record
  arm the key map is built from SDL fields, so a collision is silent last-write-wins
  (order-dependent binding); on the JavaBean arm both fields look up the same setter, so
  it is invoked twice. Detect the collision when building the key map (record) or with a
  seen-key set across the `sdlOrder` walk (JavaBean); the message names both colliding
  SDL fields and the shared key. The closest precedents are the many-to-one ambiguity
  reduction R191 already ships on the output side (`FieldBuilder.java:4658-4687`) and the
  `@nodeId`+`@field` axis-collision guard (`FieldBuilder.java:1028-1032`). The Fail
  surfaces through the standard sealed-result path: `InputBeanResolver.Result.Failed`
  becomes `ServiceDirectiveResolver`'s `Resolved.Rejected`
  (`ServiceDirectiveResolver.java:155-157`), then an `UnclassifiedField` rejected by
  `GraphitronSchemaValidator.validateUnclassifiedField` (`:981`), exactly as the existing
  recursive / non-public / Map rejections do. The classifier is the sole gate; there is
  **no** separate `@service`-input-bean validator to mirror these invariants into (the
  validator's own comments at `GraphitronSchemaValidator.java:511`, `:520` confirm the
  unresolved service method is caught by the builder).

Member resolution happens **before** leaf classification: the directive selects *which*
Java member binds; the member's Java type then drives the leaf branch
(Direct / `EnumValueOf` / R195's `NodeIdDecodeRecord`), unchanged from today's
`:282-318` and now reached through the shared per-field helper both arms call. The two
concerns stay orthogonal.

## Directive documentation

`@field`'s docstring (`directives.graphqls:30-39`) enumerates the column / argument /
enum-value axes but omits the **Java-member axis** that R191 (output) and R200 (input)
add on free-form `@record`/bean parents. As it stands (absent this update) the docstring
is false by omission: an author reading it concludes `@field` on a bean-backed
`@service` input field is inert. Close the whole omission in this change, covering both
sites the axis spans rather than only R200's:
- `FIELD_DEFINITION` of a free-form `@record`/bean **output** parent (R191, which
  shipped without documenting its axis): the value names the Java accessor to read.
- `INPUT_FIELD_DEFINITION` of a free-form `@record`/POJO/Java-record-backed `@service`
  **input** parameter (R200): the value names the Java member (record component /
  JavaBean setter base) to bind.

R200 lands first among the symmetry items, so it pays off R191's pre-existing
output-axis doc debt here rather than leaving it ownerless (R201/R202 cover other output
sub-axes, not the base accessor axis). Keep the prose free of roadmap markers (the
`Spec → Done` user-facing-doc check applies).

## Tests

Behavioural change (a divergent member name now binds where it silently dropped, and a
record mismatch that silently under-arited now Fails), so the primary tier is pipeline,
not per-variant binding-list unit assertions. No generated-body string matches (banned
at every tier).

- **Pipeline-tier** (`GraphitronSchemaBuilderTest`, alongside the R150
  `SERVICE_MUTATION_FIELD_INPUT_BEAN_*` cases at `:6962+`): a `@service` whose
  consumer bean/record uses member names diverging from the SDL field names, bridged by
  `@field(name: "<javaName>")`, classified `InputBean` with the binding resolved. Assert
  `FieldBinding.javaFieldName()` is the directive value while `sdlFieldName()` stays the
  SDL name. Cover both `Target.RECORD` and `Target.JAVA_BEAN`.
- **Rejection cases** (same enum, alongside `SERVICE_RECURSIVE_BEAN_REJECTED` /
  `SERVICE_NON_PUBLIC_BEAN_REJECTED` at `:9006`): (a) record component with no binding
  SDL field, the direction-A under-arity Fail; (b) SDL field whose binding key names no
  component, the direction-B consume Fail; (c) two SDL fields resolving to one binding
  key, the ambiguity Fail. Assert on `Rejection.message()`.
- **Regression floor (two behavioural flips, asymmetric risk):**
  - A divergent-name JavaBean with **no** `@field` still rejects with
    `has no fields matching the SDL input type`; the directive is the only bridge, and
    its absence must not start matching by coincidence.
  - **Direction A flip** (record component with no SDL field): now a classify-time Fail
    where it previously emitted a silent under-arity call. Lower risk, because the old
    behaviour already broke downstream (the under-arity canonical-ctor call is a sakila
    javac error), so the flip only moves the failure earlier and clearer. Guard it so a
    future reader does not misread the earlier rejection as new breakage.
  - **Direction B flip** (record SDL field consumed by no component, rejection case (b)
    above): the **riskier** flip and the one needing the stronger guard. A subset record
    (`R(a, b)` against input `{a, b, c}`) constructs fine **today** and silently drops
    `c` with no error at any tier, so there is no javac backstop, today or against a
    future change that re-loosens direction B. The rejection-case fixture is therefore a
    forward-looking guard, not a fix for a broken test; no existing fixture exercises a
    subset record (the `TestInputBean` cases are all exact mirrors).
- **Compile-tier backstop** (`graphitron-sakila-example`): add an input type whose
  consumer-authored record uses a `@field`-renamed component and round-trip it through a
  mutation. The cross-module `-Plocal-db` compile verifies the happy path emits a
  well-formed canonical-constructor call; it is also a belt-and-suspenders net for the
  direction-A arity invariant, since an under-arity call (were the classify-time Fail
  ever to regress) surfaces here as a javac error.
- **Fixtures:** a `TestInputBean`-style record and a JavaBean with member names
  diverging from their SDL field names (sibling to `TestInputBean` /
  `TestInputJavaBeanWithBoolean`), plus a **subset record** (fewer components than its
  SDL input has fields) for the direction-B flip; the existing `TestInputBean` cases are
  all exact mirrors, so this shape is new.

## Interaction with other items

- **R191 (shipped)** — output-side counterpart; same `argString(…).orElse(name)` read,
  same "directive names the Java member" semantics, same "reflected/declared name
  carried on the binding so emit is selection-agnostic" property.
- **R201 / R202 (Backlog)** — the other two `@field`-symmetry items (output payload
  construction; `@error` extra-field accessors). The directive docstring update is
  shared across the three; R200 lands first and documents the full Java-member axis
  (both the input site and R191's output `FIELD_DEFINITION` site, see Directive
  documentation), so R201/R202 reference it rather than re-adding.
- **R97 (Backlog)** — owns the `@table`-on-input column axis and the jOOQ-`TableRecord`-
  as-param case carved out above. R200 does not touch `looksLikeBeanCandidate`'s
  `org.jooq.*` rejection, which is the seam between the two.
- **R195 (shipped)** — its jOOQ-record-*member* `NodeIdDecodeRecord` arm reads `@nodeId`,
  not `@field`, and is untouched. Member resolution (R200) runs before leaf
  classification (R195), so the two compose without interference.

## Out of scope

- Top-level `@service` parameter that **is** a jOOQ `TableRecord` (column-axis `@field`,
  `@nodeId` → scalar-key decode). R97 + deferred-R195.
- Restructuring `@field(name:)` syntax or admitting dotted paths.
- The FK-derivation and `@nodeId` leaf paths (catalog-metadata-driven, indifferent to
  `@field`).
