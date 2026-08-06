# Structural classifier census: grounding for inferred-claim provenance

A working document, not a roadmap item; it lives in `audits/` so the roadmap-tool ignores it.
It records the census of structural classification arms commissioned for the inferred-claim
design in `roadmap/validation-adds-facts.md`: every place the pipeline classifies a coordinate
without a graphitron directive triggering it, what fires each arm as data, and what each arm
resolves along the way. Sibling records: `2026-08-06-demand-exemption-census.md`,
`2026-08-06-directive-consumer-census.md`.

## Arm inventory

Field-side arms in `FieldBuilder`:

| Arm | Verdict | Structural trigger (as data) |
|---|---|---|
| scalar/enum on `@table` parent | `ColumnBackedField` | field name (or `@field(name:)`) matches a column of the parent's table; match rule is jOOQ javaName case-insensitive, then sqlName (`JooqCatalog.findColumn`) |
| `Node.id` precedence | `ColumnBackedField` + encode keys | parent verdict is `NodeType`, field named `id`, scalar `ID`, no `@field`; key columns and encode helper come from the type verdict |
| bare `ID` with no column (shim) | same | column lookup missed, parent is `NodeType`, type is scalar `ID`; deprecation warn |
| object return, target table-backed | `TableField` / `BatchedTableField` | target type's own `@table` binding via the `TableIndex` fixed point; join path possibly FK-auto-discovered |
| object return, discriminated interface | `TableInterfaceField` | target carries `@table` + `@discriminate`; single FK hop required |
| object return, plain interface / union | `InterfaceField` / `UnionField` (+ batched) | target's `lookAheadVerdict`; parent table must have a primary key; batched fork on list-ness plus a table-bound participant |
| per-participant FK discovery | `ParticipantCorrelation.*` | exactly one catalog FK between parent table and each participant table lacking `@referenceFor` |
| nesting target | `NestingField` | target is a `GraphQLObjectType` with no binding rejection, no type verdict, and no carrier binding (`isNestingEdgeTarget`); nested fields classify against the parent's table |
| root `Query` node fetcher | `QueryNodeField` / `QueryNodesField` | return-type signature is the `Node` interface (explicitly not name-based) |
| record-backed parent read | `RecordReadField` + `ValueLocator` ladder | typed column on the backing table, else reflective accessor match, else by-name, else default read |
| class-backed parent, table-bound child | `BatchedTableField` via accessor arm | exactly one public zero-arg accessor whose reflected return maps to the child's table with aligned cardinality |
| payload carrier data field | `BatchedTableField` / `SingleRecordIdField` / `RecordCompositeField` | parent has a producer binding whose table equals the child's return table |
| errors-shaped lift | `ErrorsField` | every polymorphic member resolves in the `ErrorIndex` and the wrapper is a nullable list; explicitly not keyed on the name `errors` |
| argument arms | `PaginationArgRef` / `PlainInputArg` / `ColumnBackedArg` / node-id decode | reserved pagination names; input-object type shape; name-vs-column match on the field's target table; `ID` plus jOOQ node metadata |

Context and type-side arms: empty-path FK auto-discovery and `{table:}` element discovery
(`BuildContext.parsePath`, exactly one FK between endpoints; self-referential orientation
decided solely by the field's list-ness), routine-hop name-matched keying (table-valued
function source, target PK columns present by name), condition-join target inference from a
Java method's second parameter type, input-field classification (nesting descent on input
object shape, FK-qualifier reverse-map shim, name-vs-column, node-metadata shim, uniform
`UnboundField` fallthrough), payload-carrier structural scan (exactly one non-errors data
channel), structural connection recognition (`edges.node` walk), node inference from jOOQ
`__NODE_TYPE_ID` metadata plus an `implements Node` clause, participant lists and joined-table
inheritance (detail-table FK columns must equal the detail PK), scalar and enum mapping,
support-type retention. Post-walk folds: connection promotion (structural arm reads the edge
name from the actual element type), UPDATE SET/WHERE partition by first covered candidate key,
DML write-target precedence, tenant scoping by configured column presence, context-argument
type agreement, producer-binding grounding with the parent-accessor cascade fixed point,
`@nodeId` leaf shape discrimination, default ordering falling back to the primary key.

## The trigger vocabulary

The distinct trigger shapes collapse into six families:

1. **Name resolution against the catalog**: SDL name (or `@field(name:)`) vs a table's columns,
   at the parent table or at a path terminal; reserved names (`first`/`last`/`after`/`before`,
   `id`, `edges`/`node`); name defaults minted from type or field names.
2. **Type-shape recognition**: nesting targets (absence of competing classification), the
   Relay connection walk, the payload-carrier scan, polymorphic error membership,
   wrapper/cardinality rules.
3. **Catalog key facts**: unique-FK discovery between table pairs, FK orientation by class
   identity with cardinality tie-break, FK/key column-tuple correspondence (positional or as a
   permutable set), candidate-key coverage, PK presence and fallback.
4. **Catalog metadata sentinels**: `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS`, table-valued
   function nodehood, the FK qualifier reverse map.
5. **Reflection facts**: accessor match by name, return type, and parameter shape; non-accessor
   signature inspection (condition method parameters, coercing type parameters, service return
   elements).
6. **Cross-site agreement**: context-argument types, producer-binding folds, minted connection
   shapes, node `typeId` uniqueness.

## Two findings that shape the design

**Every structural arm has an explicit directive mask upstream.** `@field(name:)` renames the
lookup; `@service`, `@externalField`, `@pivot`, `@sourceRow`, `@reference`, `@nodeId`,
`@referenceFor`, `@lookupKey`, `@condition`, `@orderBy` each intercept before the structural
reading fires, per arm. The masking structure the item asserts ("masking is the join's job")
matches the guard structure that actually exists; the guards move from procedural interception
to the authored-coverage anti-join without inventing new semantics.

**Cross-file triggers are the norm, not the exception.** Most arms read facts about another
type (the target's `@table`, its whole look-ahead verdict, participants' directives, node
metadata on a foreign table) or a developer Java class (reflection). This confirms the
capture-side rule already recorded in the model spec: no verdict is computable during a file's
parse, and derivations run over the whole store.

## Provenance today

`ClassificationTrace` is the only provenance-adjacent record in the tree: it carries the
verdict's leaf class name, an op kind, and a message, gated behind a system property, with no
trigger data. Nothing existing records which column matched, which FK was unique, or which
membership set proved a shape; the witness evaporates at each arm's return.
