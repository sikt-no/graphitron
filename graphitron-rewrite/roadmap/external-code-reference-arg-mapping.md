---
id: R53
title: "ExternalCodeReference.argMapping for Java-param binding"
status: Ready
bucket: architecture
priority: 5
theme: service
depends-on: []
---

# `ExternalCodeReference.argMapping` for Java-param binding

## Problem

`@field(name:)` on a GraphQL argument or input field has two distinct semantic
roles depending on what the slot binds to:

- **Table binding** — the column on the underlying jOOQ table to bind for the
  auto-equality predicate (or the `@reference` path).
- **Method binding** — the Java parameter on a developer method (`@service`,
  `@tableMethod`, `@condition`) that the slot's value should be passed to.

R41 introduced the method-binding interpretation on `@service` / `@tableMethod`
arg sites. Single-bound sites (root `@service` / `@tableMethod` args, with no
table-binding context) work cleanly under R41: the directive site disambiguates.
But an arg or input field can be bound to *both* a table and a method
simultaneously: filter args carrying `@condition`, `@table` input fields
carrying `@condition`, or any future construct that combines the two. On these
dual-bound sites, a single `@field(name:)` directive cannot carry both
overrides. R41 defers the cross-axis case (the `@condition` arms route through
`identityFor*` factories that ignore overrides), so the method-side override is
unavailable at exactly the sites where the column-side meaning is locked.

The architectural mismatch is that the override is colocated with the *slot*
(the arg / input field) when it is fundamentally a property of the *method*
(which Java parameter receives this slot's value). The slot doesn't know about
the method's parameter names; the method does. Pulling the override onto the
method reference resolves the dual-axis problem structurally, not by overload.

## Relationship to R41

R41 is `In Review` but unshipped: no consumer has upgraded to a Graphitron
release carrying the per-arg `@field(name:)` Java-param semantic. The cleanest
sequencing is to roll back R41's user-facing surface and let R53 land the
single canonical channel (`argMapping`) directly, rather than ship R41 to
production for a release cycle and have R53 retire it.

Concretely: R41's branch / PR is reverted (or never merged) and R53 absorbs
the work R41 was about to do. The R41 design is already reviewed, so R53
inherits its design conclusions and does not re-litigate them; R53's
implementation re-does the code, since the rollback removes R41's commits
from the tree. The framing below names which R41 plan items R53 absorbs
(reuse the design) versus which R41 plan items R53 deliberately does not
do (the user-facing per-arg semantic).

**Inherit from R41's reviewed design (R53 implements these, design choices
are settled):**

- `MethodRef.Param.Typed` split into `javaName` (used by emitters; matches the
  reflected parameter name) and `graphqlArgName` on `ParamSource.Arg` (used by
  `enrichArgExtractions` and any GraphQL-name-keyed lookup). R41 plan step 1.
- `enrichArgExtractions` (`FieldBuilder.java:1374`) keys off `graphqlArgName`
  rather than `p.name()`. R41 plan step 4.
- The post-reflection typo guard inside `ServiceCatalog`: when an override
  references a Java parameter that doesn't exist on the resolved method,
  surface as a `ServiceReflectionResult` failure with directive site, target,
  and available parameter list. R41 plan step 5b. R53 wires the same guard
  to the new `argMapping` source.
- `@field(javaName:)` deletion: directive arg, `ARG_JAVA_NAME` constant,
  `assertDirective` call (`GraphitronSchemaBuilder.java:592`), the
  `javaNamePresent` boolean on `ColumnField` / `ColumnReferenceField`, the two
  validator branches at `GraphitronSchemaValidator.java:327-342`, and the
  placeholder comment at line 237. R41 Proposal §2; orthogonal cleanup that
  R53 lands in the same change.
- `@field` directive docstring rewrite at `directives.graphqls:15-22`,
  dropping the stale "jOOQ table record fields" / "Java records" framing.
  R53 lands the rewritten docstring with the Java-method-parameter line
  pointing at `argMapping` rather than at `@field(name:)` per-arg.

**Drop from R41's reviewed plan (R53 deliberately does not do these):**

- The per-arg `@field(name:)` reading in `FieldBuilder.resolveServiceField`
  and the `@tableMethod` resolution path. R41 plan step 2.
- `ArgBindingMap.forField`. R53 introduces `of(Set<String>, Map<String, String>)`
  in its place.
- The pre-reflection collision check via `forField` value-duplicates. R41
  plan step 5a; superseded by R53's parser-time duplicate-key check, which
  catches the same cases earlier.

**Status mechanics.** R41 was `Discarded` per the workflow's terminal-state
rule (`graphitron-rewrite/docs/workflow.adoc` *States and transitions*); the
R41 file is deleted. R53 inherits no Spec → Ready review credit from R41;
the Spec → Ready transition for R53 is its own review. Anyone tracing R41
finds it via git history (`git log -- graphitron-rewrite/roadmap/service-arg-java-name-override.md`)
and the *Inherit from R41's reviewed design* / *Drop from R41's reviewed
plan* lists above.

## Proposal

Extend `ExternalCodeReference` with a string-DSL field naming the GraphQL→Java
parameter binding, modelled after `@experimental_constructType.selection`:

```graphql
input ExternalCodeReference {
    name: String @deprecated(reason: "...")
    className: String
    method: String
    """
    Maps Java method parameters to GraphQL argument names. Each entry is
    `javaParam: graphqlArg`; multiple entries are comma-separated. Unmentioned
    parameters bind to a GraphQL arg of the same name (identity).

    Use this on `@service`, `@tableMethod`, and every `@condition` site
    (field-level, argument-level, input-field-level, path-step). On
    `@condition` filter sites the same arg's `@field(name:)` continues to
    drive column binding for auto-equality; the two axes coexist on the
    same slot.

    Whitespace around `:` and `,` is permitted; multi-line text-block
    input is accepted (as in `@experimental_constructType.selection`).
    Empty string is identity for every parameter.

    Examples:
    - `argMapping: "inputs: input"` — Java parameter `inputs` receives GraphQL arg `input`.
    - `argMapping: "city: cityNames, country: countryId"` — two overrides.
    """
    argMapping: String
}
```

Reading: Java target on the left, GraphQL source on the right; the shape
matches the existing internal `Map<javaTarget, graphqlSource>` and the
target-on-left convention shared with `@experimental_constructType.selection`,
though the two DSLs operate on different axis pairs (Java↔GraphQL vs.
GraphQL↔column).

### Semantic separation

| Slot bound to                  | `@field(name:)` axis | `argMapping` axis |
|--------------------------------|----------------------|-------------------|
| table only (filter arg, plain input field) | column           | n/a               |
| method only (root @service / @tableMethod arg) | n/a              | Java param        |
| both (filter arg + @condition; @table input field + @condition) | column   | Java param        |

`argMapping` is the single mechanism for Java-parameter override on every
method-backed call: `@service`, `@tableMethod`, every `@condition` site
(field-level, argument-level, input-field-level, path-step). `@field(name:)`
reverts to its pre-R41 meaning: column on table-backed sites, db-string
mapping on `ENUM_VALUE`. R41's per-arg `@field(name:)` override semantic is
retired in the same change.

### Validation

- **Parse-time on the mini-DSL** (`ArgBindingMap.parseArgMapping`).
  - Malformed entries (missing `:`, empty key/value after trimming whitespace):
    rejected with the entry text in the message.
  - Duplicate Java target: rejected with the target name. The parser is the
    sole detector; by the time `of(...)` runs, no two map keys can collide.
- **Pre-reflection on the field** (`ArgBindingMap.of`). The factory has
  exactly one failure shape:
  - Override value names a GraphQL arg that doesn't exist on the field:
    rejected with the arg name and the available list (see Plan step 4 for
    the message format). At single-slot `@condition` sites the GraphQL-slot
    set has one element, so any override whose value isn't that element
    fails through this rule. At path-step `@condition` sites the slot set
    is empty, so any non-empty `argMapping` fails through this rule
    (per-entry). The site-specific framings ("single-slot" / "path-step")
    are not separate rules; they are how the one rule reads at sites where
    the slot set is small. Two Java parameters that bind to the same
    GraphQL slot is *legal* (e.g. `argMapping: "a: x, b: x"` against slot
    set `{x}` produces `{a: x, b: x}`), since the override map is keyed on
    Java target and a single GraphQL value flowing to two Java params is a
    coherent shape; if a future rule wants to ban it, it lands as its own
    bullet with a load-bearing reason.
- **Post-reflection** (inherited from R41; see *Relationship to R41* for the
  salvage list).
  - Override key names a Java parameter that doesn't exist on the resolved
    method: rejected with the directive site, the Java-target, and the
    available parameter list.
  - On `@tableMethod`: override key targeting the `Table<?>` slot: rejected.
- **Directive-site limits** (parse-time, in `GraphitronSchemaValidator`).
  - `argMapping` on `@externalField`, `@enum`, or `@record` is rejected with a
    "directive does not consume GraphQL-argument-bound parameters" message.
    These directives reach methods whose Java parameter set is fixed (parent
    table for `@externalField`, no params for `@enum` and `@record`'s current
    surface), so `argMapping` is structurally inert there. Per *Validator
    mirrors classifier invariants*, silent acceptance is the wrong default.

## Plan sketch

1. **SDL: extend `ExternalCodeReference`.** Add the optional `argMapping:
   String` field with the docstring above. No directive-fanout change is
   required; the new field threads through every directive that already takes
   `ExternalCodeReference!`. Where `argMapping` is structurally inert
   (`@externalField`, `@enum`, `@record`), the validator rejects it explicitly
   in step 6 rather than silently ignoring (per *Validator mirrors classifier
   invariants*). Update the `@field` directive docstring at
   `directives.graphqls:15-22` to point its Java-method-parameter line at
   `argMapping` rather than `@field(name:)` itself; on `ARGUMENT_DEFINITION`
   of an `@service` / `@tableMethod` field, `@field(name:)` has no role
   post-R53 (the column-binding axis doesn't apply at those sites either —
   the args feed a Java method, not a jOOQ predicate).

2. **Parser.** Add `ArgBindingMap.parseArgMapping(String) -> ParsedArgMapping`
   (record carrying either a validated `Map<String, String>` or an error
   message). Format: comma-separated `javaParam: graphqlArg` entries; whitespace
   tolerated around `:` and `,` (and around newlines for text-block input);
   empty string returns an empty map (identity-for-everything). Reject
   malformed entries and duplicate Java targets at this layer; `of(...)` in
   step 4 trusts that its `overrides` argument has unique keys.

3. **Carry the parsed mapping on `ExternalRef` and `ConditionDirective`.**
   Today `FieldBuilder.ExternalRef` is `(className, methodName, lookupError)`
   (`FieldBuilder.java:2487`); the parallel `BuildContext.ConditionDirective`
   record at `BuildContext.java:642` is `(className, methodName, override,
   contextArguments)`. Retype both:
   - `ExternalRef(className, methodName, argMapping, lookupError, argMappingError)`
   - `ConditionDirective(className, methodName, override, contextArguments,
     argMapping, argMappingError)`

   Distinct slots for the two failure shapes (named-reference lookup miss
   vs. mini-DSL parse failure) so downstream error messages stay precise
   per *Sub-taxonomies for resolution outcomes*. `FieldBuilder.parseExternalRef`
   parses `argMapping` via `ArgBindingMap.parseArgMapping` and populates
   `argMapping` or `argMappingError` accordingly; `BuildContext.readConditionDirective`
   does the same on its side, so `buildInputFieldCondition`,
   `buildArgCondition`, `buildFieldCondition`, and `resolveConditionRef`
   all consume a `ConditionDirective` that already carries the parsed
   mapping. No call site re-parses the directive map.

   Failure precedence on `parseExternalRef` (and `readConditionDirective`):
   when both `lookupError` and `argMappingError` would surface
   simultaneously, `lookupError` wins — "I can't resolve the class" reads
   ahead of "and your argMapping has a typo." `argMappingError` surfaces
   only when `className` resolved cleanly.

4. **Refactor `ArgBindingMap`.** Replace `forField(GraphQLFieldDefinition)`,
   `identityForField(...)`, `identityForSingleArg(...)`, and
   `identityForSingleInputField(...)` with a single factory:

   ```java
   sealed interface Result {
       record Ok(ArgBindingMap map) implements Result {}
       record UnknownArgRef(String message) implements Result {}
   }

   static Result of(Set<String> graphqlArgNames, Map<String, String> overrides)
   ```

   It builds identity for every name in `graphqlArgNames`, applies overrides,
   and returns `Ok` or `UnknownArgRef`. The new factory is axis-agnostic —
   every call site funnels through it. There is no collision shape at this
   layer: the parser already enforces unique Java targets (step 2), and
   identity bindings cannot collide with each other (GraphQL arg names are
   unique on a field). The only failure `of(...)` detects is "an override's
   GraphQL-source name is not in `graphqlArgNames`" (the *Pre-reflection on
   the field* item under Validation).

   `UnknownArgRef.message` is formatted to mirror the post-reflection
   typo guard's wire shape: name the override's GraphQL-source value, name
   the available `graphqlArgNames` list. The site context (which directive
   the override sits on) is added by the caller when wrapping the result;
   `of(...)` doesn't know whether it ran for `@service`, `@condition`, or
   a path-step.

   `empty()` keeps its current shape (path-step `@condition` resolution
   where the method takes no GraphQL-bound parameters and the directive
   carries no `argMapping`); `of(Set.of(), Map.of())` produces the same
   value via the new factory. When a path-step *does* carry `argMapping`,
   the call funnels through `of(Set.of(), parsedArgMapping)` and the
   `UnknownArgRef` rule rejects every entry.

5. **Wire through every reflect call site.** `FieldBuilder.resolveServiceField`,
   the two `@tableMethod` arms (root + child), `buildArgCondition`,
   `buildFieldCondition`, `BuildContext.resolveConditionRef`, and
   `BuildContext.buildInputFieldCondition` each pull the `argMapping` from the
   `ExternalRef` (or condition-map equivalent) and pass it to
   `ArgBindingMap.of`. The previous `identityFor*` factories drop out; every
   call site funnels through the single `of(...)` factory. `ArgBindingMap`
   itself never reads `@field(name:)` — that directive's column-axis stays in
   the column-binding code path.

6. **Validator: structural-inertness rejections for `argMapping`.**
   `argMapping` on `@externalField`, `@enum`, or `@record` is rejected (per
   Validation §`Directive-site limits`). Place each rejection in
   `GraphitronSchemaValidator` next to the existing per-directive shape
   checks; the corresponding tests belong in `GraphitronSchemaBuilderTest`
   alongside the other invalid-schema cases. The validator does not need a
   site-conditional check on `@field(name:)` because R41's per-arg semantic
   never lands user-facing (see *Relationship to R41*); the column-binding
   axis on filter args remains the sole interpretation of `@field(name:)` on
   `ARGUMENT_DEFINITION`.

7. **Author override-using fixtures directly in `argMapping` form.** Because
   R41 is rolled back, no fixture migration is required — fixtures land in
   their final shape on first commit.
   - `graphitron-rewrite/graphitron-test/src/main/resources/graphql/schema.graphqls`:
     `filmsByServiceRenamed` is authored with
     `@service(service: {className: "...", method: "filmsByServiceRenamed", argMapping: "filmIds: ids"})`.
     `GraphQLQueryTest.queryServiceTable_filmsByServiceRenamed_*` covers it
     end-to-end through PostgreSQL.

8. **Author override-using unit tests directly in `argMapping` form.**
   - `ServiceCatalogTest`: override, post-reflection typo guard, and
     `Table<?>`-slot rejection cases pass the override map to
     `ArgBindingMap.of` directly. Identity-when-empty case covers the no-op
     path (no `argMapping` → `of(graphqlArgNames, Map.of())`).
   - `GraphitronSchemaBuilderTest`: SDL-driven happy path for `@service` +
     `argMapping`; SDL-driven failure cases for parser-rejected forms and
     `of(...)`-rejected unknown-arg-refs.

9. **Add cross-axis coverage.** New test cases that R41's design couldn't
   express:
   - `@condition` arg-level: `argMapping` overrides while the same arg's
     `@field(name:)` independently drives column binding for auto-equality.
     Both axes coexist on the same arg.
   - `@condition` field-level: `argMapping` on a field-level `@condition`
     binds one Java parameter to a differently-named GraphQL arg.
   - `@condition` input-field-level: `argMapping` on an input-field
     `@condition` binds the input-field's value to a differently-named Java
     parameter.
   - `@reference.path[].condition`: `argMapping` on a path-step `@condition`
     rejects with the `UnknownArgRef` message naming "no GraphQL arguments
     are in scope at a path-step `@condition`" (or whatever wording the
     site-context wrapper produces; assert the site-context fragment, not
     the message verbatim).
   - Pipeline: one schema fixture exercising the dual-bound case end-to-end
     (table input field + `@condition` with `argMapping`).
   - Parser-error cases: malformed entry, duplicate Java target, override
     value naming a non-existent GraphQL arg.

10. **R41 already discarded.** R41's file was deleted under the workflow's
    `Discarded` terminal-state rule; no further action here. The `@field`
    directive docstring update lands as part of step 1 above (no separate
    revert step needed because the per-arg-override line never reaches main).

## What we're NOT doing

- **`@service.contextArguments` overrides.** Context arguments key by name into
  the runtime context (`R31` `service-context-value-registry`'s territory). An
  override would have to propagate to the registry; not in scope here. File a
  follow-up if a real case appears.
- **The deprecated `ExternalCodeReference.name` field.** Stays as-is; the
  named-reference lookup path through `RewriteContext.namedReferences()` is
  orthogonal to `argMapping` and gets its own retirement when consumers
  migrate off `name:` to `className:`.
- **Reshaping `@record` to take method parameters.** Today `@record` wraps a
  type in a Java record and consumes no arg-bound parameters; `argMapping`
  rejects there per the validator-inertness rule (step 6). If a future change
  makes `@record` take method-style parameters, that change extends `argMapping`
  acceptance there as part of its own scope.
- **`@reference.condition` accepting `argMapping`.** Path-step `@condition`
  resolves with an empty GraphQL-arg set (`BuildContext.java:622`); any
  `argMapping` there has no slot to bind to and rejects per Validation §
  `Pre-reflection on the field`. If a future feature gives path-step
  conditions access to the surrounding arg set, that feature extends
  `argMapping` acceptance there.
- **A structured GraphQL list form** (`argMapping: [{javaParam: "x", graphqlArg: "y"}]`).
  R53 stays with the string mini-DSL for symmetry with
  `@experimental_constructType.selection`; revisiting the surface form is a
  separate item if and when both DSLs migrate.

## Tests

Coverage by tier:

- **Unit (`ArgBindingMap` / parser)**: malformed entries, duplicate Java
  target, override value not in argument set, identity-when-empty.
- **Unit (`ServiceCatalogTest`)**: override binds Java param to differently-named
  GraphQL arg (`@service` and `@tableMethod`); typo guard fires when override
  key targets a non-existent Java parameter; `@tableMethod` Table<?>-slot
  rejection.
- **`GraphitronSchemaBuilderTest`**: end-to-end SDL → classified field with
  `argMapping`; cross-axis case (`@field(name:)` column + `argMapping` Java
  param on the same dual-bound arg); validator rejects `argMapping` on
  `@externalField`, `@enum`, and `@record` (one case each).
- **Pipeline**: one fixture with a dual-bound input field + `@condition`
  using `argMapping`. Asserts the classified `MethodRef` carries
  `Param.Typed(javaName=X, source=Arg(graphqlArgName=Y, ...))` for the
  override (X != Y), and the column-binding axis on the same arg (the
  `ColumnRef` reached through `@field(name:)`) is unaffected by the
  presence of `argMapping`.
- **`graphitron-test` execute**: `filmsByServiceRenamed` round-trips a real
  query through PostgreSQL with the `argMapping`-form `@service` directive.

## Notes

- **Direction of the mini-DSL.** `javaParam: graphqlArg` matches the existing
  internal `Map<javaTarget, graphqlSource>` shape used throughout
  `ArgBindingMap` and `MethodRef.Param`. A schema author reading
  `argMapping: "inputs: input"` reads "the Java parameter `inputs` receives
  the value of the GraphQL argument `input`." The target-on-left convention
  is shared with `@experimental_constructType.selection`, though the two
  DSLs operate on different axis pairs (Java↔GraphQL here; GraphQL↔column
  there); the parallel is conventional, not structural.
- **R41 rollback rather than additive replacement.** Because R41 is unshipped
  to consumers (see *Relationship to R41*), R53 does not need to land
  additively or carry a deprecation cycle. The single canonical channel is
  `argMapping` from R53 onward; no validator backstop for the retired R41
  syntax is required because the R41 syntax never reaches main.
- **Co-considered with R1 (`BatchKey` lifter directive)**: orthogonal —
  R1 covers DTO-to-key conversion, R53 covers parameter naming. Both extend
  the binding language for service method parameters from different angles.
- **Parser style.** String mini-DSL chosen over a structured GraphQL list
  for symmetry with `@experimental_constructType`. The cost is slightly more
  parsing logic in graphitron; the benefit is a single cell on the directive
  rather than a list-of-records construction at every call site.
