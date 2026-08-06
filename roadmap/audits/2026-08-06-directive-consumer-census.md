# Directive consumer census: grounding for the semantic stratum

A working document, not a roadmap item; it lives in `audits/` so the roadmap-tool ignores it.
It records the per-directive consumer census commissioned for the semantic-stratum inventory
in the graphitron-model spec (the `intent_` relations): for each directive the rewrite
declares, which code reads its applications, which declared arguments are actually consumed,
what decoding the raw values get, and what happens to retired or deprecated applications.
Sibling records: `2026-08-05-fact-base-h2-spike.md`, `2026-08-06-graphql-java-diff-spike.md`.

## The consumption mechanism

There is no directive enum or registry object. Consumption is a three-layer convention:

- **Name constants**: `BuildContext`'s `DIR_*` / `ARG_*` blocks are the closest thing to a
  registry; several constants document which fact visitor "owns" the name.
- **Accessor helpers**: `BuildContext.argString` / `argStringList` / `argBoolean` / `asMap`,
  each tolerant of both AST-literal and already-coerced forms.
- **Fact visitors**: `no.sikt.graphitron.facts.*FactVisitor`, each the declared single home
  for one or two directive names (presence plus at most one payload argument), with the rest
  of the payload read in the `rewrite` classifiers and resolvers.

One shared string decoder covers every pair grammar: `ArgBindingMap.parseArgMapping`
delegating to `GraphQLSelectionParser.parseEntries` (comma-separated `key: dotted.path`
entries), used by `@service` and `@condition` argMappings, `@routine`'s argMapping and
columnMapping, and nominally by `@experimental_constructType(selection:)`. The emission-side
namespace predicate is `SchemaDirectiveRegistry.isSurvivor` over `DeclaredDirectives.names()`:
a directive survives to the emitted schema exactly when graphitron's `directives.graphqls`
does not declare it.

## Per-directive summary

| Directive | Main consumers | Arguments read | Notes |
|---|---|---|---|
| `@splitQuery` | `DeliveryFactVisitor`, `FieldBuilder` | (none) | warn + delete-fix on record-backed parents; conflicts with `@routine` |
| `@pivot` | `FieldBuilder.classifyPivot` region | `on`, `value`, `vocabulary` (all) | vocabulary resolves an enum whose values' `@field(name:)` map slots to tokens |
| `@notGenerated` | reject sites only | (none) | removed; hard reject on field and input-field sites |
| `@table` | `BuildContext`, `TypeBuilder`, resolvers | `name` (OBJECT/INTERFACE) | INPUT_OBJECT: ignored, `name` never read, per-usage warning |
| `@scalarType` | `TypeBuilder.classifyScalarType`, `ScalarTypeResolver` | `scalar` | presence read via an SDL pre-pass because assembly strips built-in redeclarations; registry capture removes that carve-out |
| `@field` | `FieldSourceSigil` sites plus ~15 plain `argString` sites | `name` | `$source` / `$errors` sigil forms; plain sites default to the SDL name |
| `@externalField` | `ExternalFieldDirectiveResolver` | `reference.className`, `.method` | `.argMapping` read only to reject (inert) |
| `@enum` | `TypeBuilder` | `.argMapping` only, to reject | `className` and `method` are declared but never read; binding is reflective |
| `@service` | `ServiceFactVisitor` (presence), `ServiceDirectiveResolver` | all | argMapping through the shared pair decoder |
| `@error` | `TypeBuilder.buildErrorType` | every `ErrorHandler` field | cross-field handler rules are rejection arms |
| `@reference` | `BuildContext.parsePath` | `path` (all elements) | repeatable; applications concatenate in written order; an empty path means FK auto-discovery; a multi-application chain rejects an element-less application; repetition rejected on input fields |
| `@referenceFor` | `FieldBuilder.resolveChildPolymorphicJoinPaths` | `type`, `path` | repeatable, independent per participant; duplicate participant rejects |
| `@multitableReference` | reject site only | (none; `routes` never read) | removed; hard reject |
| `@sourceRow` | `SourceRowDirectiveResolver` | `className`, `method` | flat arguments, not an ExternalCodeReference |
| `@condition` | `ConditionFactVisitor`, `BuildContext.readConditionDirective` | all | argMapping through the shared pair decoder; override gates mutation-input use |
| `@lookupKey` | `LookupFactVisitor`, `LookupKeyDirectiveResolver` | (none) | ARGUMENT_DEFINITION live; INPUT_FIELD_DEFINITION retired, hard reject at two sites |
| `@tenantFanOut` | `DeliveryFactVisitor`, `TenantBindingIndex` | (none) | large conflict-rejection surface |
| `@mutation` | `WriteFactVisitor` | `typeName`, `multiRow`, `table` (all) | per-argument coercion of AST and coerced forms |
| `@asConnection` | `PaginationFactVisitor`, `ConnectionPromoter` | `defaultFirstValue`, `connectionName` | connectionName is honoured, not ignored; deprecation surfaced by lint via the native `@deprecated` marker |
| `@asFacet` | `FacetFieldValidation`, `ConnectionPromoter` | (none) | column comes from `@field(name:)`; malformed applications skip synthesis and surface as diagnostics |
| `@orderBy` | `OrderByFactVisitor`, `OrderByResolver` | (none) | argument-positioned marker |
| `@index` | `OrderByResolver` | `name` | deprecated but honoured as an alias of `@order(index:)` when `@order` is absent; lint warns |
| `@order` | `OrderByResolver` | `index`, `fields`, `primaryKey` (all) | per-entry `FieldSort` fields read; no directive-level direction |
| `@defaultOrder` | `OrderByFactVisitor`, `OrderByResolver` | all four | directive-level `direction` is the per-entry fallback |
| `@record` | `TypeBuilder.emitDirectiveIgnoredWarning` | `record.className`, for warning text only | ignored; three warning arms compare declared vs reflected class; `method` / `argMapping` never read |
| `@discriminate` | `TypeBuilder` | `on` | catalog resolution with raw-value fallback; requires `@table` on the interface |
| `@discriminator` | `TypeBuilder` participant build | `value` | read from the participant during the parent's participant-list build |
| `@node` | `TypeBuilder`, `NodeDeclaration` | `typeId`, `keyColumns` (both) | keyColumns resolved per column with candidate hints; SDL wins over jOOQ metadata, different column sets reject |
| `@nodeId` | `NodeIdLeafResolver.inferTypeName` and ~10 sites | `typeName` | explicit value wins; inference by table mapping, ambiguity rejects |
| `@routine` | `RoutineDirectiveResolver`, `FieldBuilder.walkRoutineChain` | `name`, `argMapping`, `columnMapping` (all) | repeatable; composes one chain with `@reference` in written order; more than one routine node defers |
| `@experimental_constructType` | none | none (`selection` never read) | not a graphitron directive; its declaration in `directives.graphqls` is a bug, and applications are silently dropped at emit because of it; no intent relation |

Federation (from the sibling federation census): `@key` is the only federation directive with
real downstream semantics (`fields` and `resolvable` read; flat field-set parse, nested
selections rejected with a located message; every keyed type seeds reachability). `@link` is
read at load for `url` and `import` (including the object form). `@tag` and `@shareable` are
read only inside the connection-promotion machinery, which in the store design is the capture
walk itself reading the AST in hand; everything else federation-namespace is pass-through and
re-emitted via the survivor rule.

## Findings that shaped the DDL

- **Application-level rows for `@reference`.** An empty-path application (FK auto-discovery)
  and the element-less-application rejection are both facts about one application, invisible
  in a chain that concatenates steps flat. The family became a parent relation per
  application with steps inside it; the chain is an ORDER BY.
- **Positions on every application-level intent row.** A field's `@routine` and `@reference`
  applications compose one table chain in written order, so cross-directive document order
  must be recoverable; and the graphitron namespace has no `applied_` twin, so the intent row
  is the only record of where the author wrote the application. Detections mint located
  diagnostics from these columns.
- **No relation for `@experimental_constructType`, whose declaration is itself a bug.** The
  directive has no consumer at all and is not a graphitron directive; declaring it in
  `directives.graphqls` only makes emission strip applications graphitron does not own. It
  gets no store surface, and once the stray declaration is removed its applications are
  foreign and take the `applied_` fidelity path like any user-authored directive.
- **Pair children only where a consumer binds pairs.** The shared decoder's live sites get
  ordered pair relations; inert sites (`@externalField`, `@enum`) keep the raw column, since
  their only consumer is a presence-triggered rejection.
- **Retired directives capture existence, not payload** (`@notGenerated`,
  `@multitableReference`), except where a warning arm reads a payload value (`@record`'s
  `className`, compared against the reflected backing class).
- **No CHECK constraints on author-spelled enum literals** (`MutationType`,
  `ErrorHandlerType`, `SortDirection`): the consumers' own coercers tolerate malformed forms
  and reject in classification, confirming vocabulary membership as detection business.
