---
id: R200
title: Honor @field(name:) in InputBeanResolver for SDL input bean/record member binding
status: Spec
bucket: bug
priority: 5
theme: service
depends-on: []
created: 2026-05-20
last-updated: 2026-06-09
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
- **Record:** today the lookup axis and the iteration axis are the same (both
  component names), so divergence cannot yet arise. The moment `@field(name:)`
  re-points a field to a component, the index key (directive value) and `recordOrder`
  (component name) split: a component with no binding SDL field silently falls out of
  `bindings` (`:272-277`, `sdlField == null` → `continue`), yielding an **under-arity**
  argument list the emitter feeds to the canonical constructor.

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
emitter change — `CallSiteExtraction.FieldBinding` already carries the resolved
`javaName` separately from `sdlFieldName` (`:319-320`), so the runtime call path is
agnostic to *how* the Java member was chosen (same property R191 relied on:
`AccessorRef.methodName()` is the reflected name regardless of selection).

- **Read the directive once per SDL field.** Add a helper computing the Java-member
  binding key for an SDL field using the established pattern (BuildContext R191
  precedent at `:1769-1772`):
  `f.hasAppliedDirective(DIR_FIELD) ? argString(f, DIR_FIELD, ARG_NAME).orElse(f.getName()) : f.getName()`.
  Import `DIR_FIELD` / `ARG_NAME` from `BuildContext` (the file already imports
  `ARG_TYPE_NAME` / `DIR_NODE_ID` / `argString` from there, `:28-30`).

- **JavaBean arm.** When iterating `sdlOrder(iot)`, look up
  `javaMembersBySdlName.get(bindingKey(sdlField))` instead of
  `javaMembersBySdlName.get(sdlField.getName())`. A field whose key matches no member
  still skips (`:270`, unchanged JavaBean tolerance). The empty-bindings rejection
  (`:322-327`) now fires only when *no* field — by name or by `@field` — matches, which
  is the genuine "this bean does not mirror this input" case.

- **Record arm.** Build `Map<String /*javaName*/, GraphQLInputObjectField>` from the
  SDL fields keyed by `bindingKey(f)`, then drive the existing `recordOrder` iteration:
  for each component name, the binding SDL field is `byJavaName.get(componentName)`.
  **Tighten the silent under-arity drop to a hard Fail**: a canonical component with no
  binding SDL field (none named after it, none carrying `@field(name: "<component>")`)
  is a structural rejection in the same family as the existing
  `record '<X>' has no component named '<f>'` message (`:264-268`) — the canonical
  constructor needs every component, so a missing one must fail at classify time, not
  emit a malformed N-1-arg call. This closes the record half of the bug.

- **Reject member-binding ambiguity.** Two SDL fields resolving to the same Java member
  (`@field` values colliding, or a `@field` value colliding with another field's plain
  name) is a structural Fail. Without it, `javaMembersBySdlName`/`byJavaName` is a `Map`
  and the collision is silent last-write-wins (JavaBean: one setter invoked twice;
  record: order-dependent binding). This mirrors the existing `@nodeId`+`@field`
  axis-collision guard at `FieldBuilder.java:1028-1032` and the "validator mirrors
  classifier invariants" discipline. Message names both colliding SDL fields and the
  shared Java member.

Member resolution happens **before** leaf classification: the directive selects *which*
Java member binds; the member's Java type then drives the leaf branch
(Direct / `EnumValueOf` / R195's `NodeIdDecodeRecord`) exactly as today (`:282-318`).
The two concerns stay orthogonal.

## Directive documentation

`@field`'s docstring (`directives.graphqls:30-39`) enumerates the column / argument /
enum-value axes but omits the Java-member axis that R191 (output) and R200 (input) add
on free-form `@record`/bean parents. After this item the docstring is false by
omission — an author reading it concludes `@field` on a bean-backed `@service` input
field is inert. Add the axis in the same change: on `INPUT_FIELD_DEFINITION` of a
free-form `@record`/POJO/Java-record-backed `@service` parameter, the directive value
names the Java member (record component / JavaBean setter base) to bind. Keep the prose
free of roadmap markers (the `Spec → Done` user-facing-doc check applies).

## Tests

Behavioural change (a divergent member name now binds where it silently dropped), so
the primary tier is pipeline, not per-variant binding-list unit assertions. No
generated-body string matches (banned at every tier).

- **Pipeline-tier** (`GraphitronSchemaBuilderTest`, alongside the R150
  `SERVICE_MUTATION_FIELD_INPUT_BEAN_*` cases at `:6962+`): a `@service` whose
  consumer bean/record uses member names diverging from the SDL field names, bridged by
  `@field(name: "<javaName>")` → classified `InputBean` with the binding resolved;
  assert `FieldBinding.javaName()` is the directive value while `sdlFieldName()` stays
  the SDL name. Cover both `Target.RECORD` and `Target.JAVA_BEAN`.
- **Rejection cases** (same enum, alongside `SERVICE_RECURSIVE_BEAN_REJECTED` /
  `SERVICE_NON_PUBLIC_BEAN_REJECTED` at `:8786+`): (a) record component with no binding
  SDL field → the new under-arity Fail; (b) two SDL fields resolving to one Java member
  → the ambiguity Fail. Assert on `Rejection.message()`.
- **Regression floor:** a divergent-name bean with **no** `@field` still rejects with
  `has no fields matching the SDL input type` (the directive is the only bridge; absence
  of it must not start matching by coincidence).
- **Compile-tier backstop** (`graphitron-sakila-example`): add an input type whose
  consumer-authored record uses a `@field`-renamed component and round-trip it through a
  mutation. The cross-module `-Plocal-db` compile is the real guard against the record
  under-arity case — an under-arity canonical-constructor call surfaces there as a javac
  error.
- **Fixtures:** a `TestInputBean`-style record and a JavaBean with member names
  diverging from their SDL field names (sibling to `TestInputBean` /
  `TestInputJavaBeanWithBoolean`).

## Interaction with other items

- **R191 (shipped)** — output-side counterpart; same `argString(…).orElse(name)` read,
  same "directive names the Java member" semantics, same "reflected/declared name
  carried on the binding so emit is selection-agnostic" property.
- **R201 / R202 (Backlog)** — the other two `@field`-symmetry items (output payload
  construction; `@error` extra-field accessors). The directive docstring update is
  shared across the three; whichever lands first adds the Java-member axis and the
  others reference it.
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
