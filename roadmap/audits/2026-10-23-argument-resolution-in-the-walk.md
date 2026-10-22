# How the walk resolves arguments and input fields

A behavioural specification of what the classifier does with arguments and input fields today, written
before designing their model in the store from first principles. The agreed starting point: an
argument or input field means what its field's fetcher type makes of it, and the directives on it are
claims. This records today's behaviour so it can be reproduced where it is right and fixed where it is
not, and so the claims can be read off it.

Paths are relative to `graphitron/src/main/java/no/sikt/graphitron/rewrite/` unless noted. FB is
`FieldBuilder.java`, BC `BuildContext.java`, TB `TypeBuilder.java`, IFR `InputFieldResolver.java`, MIR
`MutationInputResolver.java`, SC `ServiceCatalog.java`, RDR `RoutineDirectiveResolver.java`, TBI
`TenantBindingIndex.java`, NLR `NodeIdLeafResolver.java`, CR `ConditionResolver.java`, URW and DRW
`walker/UpdateRowsWalker.java` and `walker/DeleteRowsWalker.java`, SDL
`graphitron-model/src/main/resources/no/sikt/graphitron/model/schema/directives.graphqls`. Line
numbers are as of trunk `fb2bc8354`; a `~` marks a line cited from a range. Claims marked inferred were
not traced end to end. Read from source, with the architecture page
`docs/architecture/reference/argument-resolution.adoc` and the directive manual pages beside it.

## 1. Pipeline

Arguments are classified inside field classification (`FB.classifyField` FB:4071, `classifyFieldInner`
FB:4143), before any generation, and nothing re-reads the SDL afterwards: generators read the model
records it produces (`WhereFilter`s, `OrderBySpec`, `PaginationSpec`, `LookupResolution`, `MethodRef`
parameters, the DML `Write` arms, `RoutineRef`). The exceptions are post-walk folds that read raw SDL
markers: the tenant index, facet synthesis and the validator. The facts engine
(`graphitron/.../facts/`) runs before classification; of its relations only pagination roles, delivery
markers, the service and write membership triggers and lookup's field gate are read, and condition and
order-by facts have no reader.

* **Table reads** enter through `resolveTableFieldComponents(parent, fieldDef, table, returnTypeName,
  plan[, emitAdvisory, spentInputs])` (FB:562-603): the `@asConnection` same-table advisory (FB:594),
  `classifyArguments` (FB:598) and `projectForFilter` (FB:2747), giving `TableFieldComponents.Ok(filters,
  orderBy, pagination, lookup)` or `Rejected`. The table passed: a root `@table` field's return table
  (FB:6037), a single-table interface's (FB:6049), a root `@lookupKey`'s (FB:6000), each participant's
  for a multi-table interface or union (`lowerParticipantFilters`, FB:1010-1058), a child table field's
  return table (FB:1528), a child single-table interface's (FB:1622), a child under a record or result
  parent (FB:7580, 7647), and a `@routine` chain's terminus with its spent inputs (FB:4306-4327).
* **`NodeIdArgPlan`** (FB:396-447), built per field and per participant by `buildNodeIdArgPlan`
  (FB:448), resolves every top-level `@nodeId` argument through NLR once, records the first required
  same-table hit for the advisory, and under polymorphism the arguments whose node type differs across
  participants.
* **`classifyArguments`** (FB:2108-2126) reads the field-level `@condition` override flag, skips an
  argument a routine spent, classifies the rest, and records an `UnclassifiedArg` or `UnboundArg` as a
  node-id decode refusal.
* **`classifyArgument`** dispatches first match (FB:2179-2591):
  1. `@orderBy` (FB:2193).
  2. A pagination role from the gathered fact, by name (FB:2198-2207).
  3. The argument's own `@condition`; a rejection makes it an `UnclassifiedArg` (FB:2213-2222).
  4. `@referenceFor` on anything but an `ID @nodeId` argument is rejected (FB:2231-2239).
  5. Input-like (an input-object type): `@notGenerated` or `@lookupKey` on any input field rejected,
     argument `@lookupKey` to `classifyPlainLookupKeyArg`, otherwise `IFR.resolve` to a `PlainInputArg`
     (FB:2249-2306).
  6. `ID @nodeId` through NLR (FB:2312-2423).
  7. Implicit `id: ID` on a node-returning field (FB:2443-2497).
  8. `@reference` scalar (FB:2509-2561).
  9. Plain scalar column (FB:2564-2590).
* **Other fetchers have their own paths**: `@service` binds parameters (`ServiceDirectiveResolver`, SC;
  `classifyArguments` never runs), `@mutation` classifies its one input argument against the write table
  (`resolveDmlWalkerInputArg` and `BC.classifyInputField`), an accessor maps arguments to method
  parameters (`mapArgsToParamShape`), and `@externalField`, column and nesting children read no
  arguments.
* **Root precedence** (`classifyQueryField`, FB:5919-6084): `@service`, the `Node` fetcher,
  `@lookupKey`, `@routine`, then table, table interface, interface and union. `QueryNodeField` and
  `QueryNodesField` classify no arguments; the generator reads `id`/`ids` by name.
* **Input fields** enter through `BC.classifyInputField(field, parentTypeName, resolvedTable,
  ClassifyContext, conditionFailures)` (BC:2847), from `IFR.resolve`, from `TB.resolveInputFields`
  (TB:1965; argument `@lookupKey` and DML) and recursively for nesting (BC:3027). One shared tree serves
  filters, lookup keys and DML; each consumer then admits or rejects what it built.

## 2. Each path

### 2.1 Plain scalar argument to a column filter (FB:2564-2590)

* Reads the argument name, `@field(name:)`, the type, nullability and list-ness, against the consuming
  field's table.
* The column is `@field(name:)`, else the argument name, by `catalog.findColumn`. Enums are validated
  (`validateEnumFilter`, FB:2574-2585); the extraction is `Direct`, `EnumValueOf` or `JooqConvert`
  (`deriveExtraction`); `@lookupKey` sets `isLookupKey`; a list gives `In` rather than `Eq` (FB:3237).
* Produces `ColumnBackedArg(columns, extraction, argCondition, suppressedByFieldOverride, isLookupKey,
  joinPath=[], Local)`.
* Rejects a column miss as `UnboundArg` with a candidate hint, fatal at projection (FB:3209), and an
  enum mismatch.
* Read by `projectFilters` into one `GeneratedConditionFilter` (FB:3213-3302), by
  `LookupMappingResolver` when it is a key, and by the column-binding ledger.

### 2.2 Argument `@reference` (FB:2509-2561)

* A repeated `@reference` is rejected (FB:2513-2519). The path is parsed from the consuming table with no
  target table, so an empty path cannot infer a key (FB:2520). The column is `@field(name:)` or the
  argument name on the path's terminal table (FB:2525).
* The binding is Local when the path is empty, otherwise Remote, a correlated `EXISTS`
  (`wrapIfRemote`, FB:4042).
* Rejects a path error, an unreachable column, an enum mismatch.

### 2.3 `@condition`, and the override cascade

* **Parsing** (`BC.readConditionDirective`, BC:2692-2710): a missing `className` or `method` makes the
  condition silently absent; `override` defaults to false.
* **On an argument** (`CR.resolveArg`, CR:67-92): scoped to that argument, reflected by
  `SC.reflectTableMethod`; any failure refuses the argument.
* **On a field** (`CR.resolveField`, CR:99-120): scoped to every argument, applied after the arguments in
  `projectForFilter` (FB:2756-2802); a `@nodeId` argument it binds whole gets its decode installed; a
  dispatched multi-table `@nodeId` argument bound whole is rejected (FB:2771-2784).
* **On an input field** (`BC.buildInputFieldCondition`, BC:2720-2755): scoped to that field; a failure
  leaves the field classified, and IFR then rejects the argument (IFR:107-117).
* **Reflection** (SC:1083-1185): static methods, consistent overloads, at least one `Table` parameter;
  other parameters bind by name, by `argMapping` or by inference; an input-object leaf binds as a bean,
  a jOOQ record carrier or a `Map`; a scalar leaf gets `EnumValueOf` or `Direct` with no wire-type check
  (SC:1408-1416); a parameter bound to a whole `@nodeId` slot receives the decoded key and its declared
  type must match exactly (CR:227-256).
* **The cascade** (FB:3125-3305, 3358-3507):
  * A field's `override:true` suppresses every scalar's implicit predicate and is carried into every
    input walk.
  * An argument's `override:true` suppresses its implicit predicate, and for an input argument is carried
    down.
  * A nesting field's `override:true` is carried down.
  * **On an input field, any `@condition` suppresses the field's own implicit predicate, `override:false`
    included** (FB:3382-3387): the implicit predicate is emitted only when the field has no condition.
    On an argument `override:false` keeps both.
  * Explicit methods always fire. A suppressed slot with no condition of its own must be read by some
    condition parameter or by tenant routing, else `OverrideSlotUnread` (FB:3030-3070). A condition
    parameter reading below a list of input objects is rejected (FB:2891-2910).

### 2.4 `@nodeId` leaves (NLR.resolve, NLR:307-450)

* Called from the argument arm (FB:2325), the input-field arm (BC:2926-2943) and the plan builder.
  Reads `@nodeId(typeName:)`, `@reference`, `@referenceFor`, the containing table, the participant, and
  whether the leaf's own `@condition(override:true)` owns the predicate (FB:501-504).
* Rules in order:
  1. The type is `typeName:`, else the one node type over the containing table; none or several is
     rejected (NLR:462-472).
  2. The type must exist and be a `@table` object; a multi-table interface or union is refused towards
     `@service` (NLR:316-335).
  3. Target keys; more than 22 columns is rejected (NLR:348-356).
  4. The decode helper is the node's own, or one minted for a `@table` type with catalog node metadata,
     so `@node` is not strictly required (NLR:369-386).
  5. The route (`selectRoute`, NLR:528-566): `@reference` beside `@referenceFor` conflicts;
     `@referenceFor` naming another participant falls back to discovery.
  6. Same table and discovery gives `SameTable(keyColumns)`.
  7. A stated path must be foreign-key hops only; discovery must find one unique foreign key, one hop
     (NLR:630-683).
  8. A failed discovery under an author-owned predicate is `AuthorOwnedPredicate`; a stated route that
     fails stays a rejection.
  9. Every key position landing on a local column is `DirectFk`; otherwise `TranslatedFk`.
* On an argument: `@field` is rejected (FB:2319-2324); `AuthorOwnedPredicate` is a `ConditionOwnedArg`;
  `SameTable` a `ColumnBackedArg` filtering the key, a lookup key under `@lookupKey`; `DirectFk` a Local
  `ColumnBackedReferenceArg`; `TranslatedFk` a Remote one; `@lookupKey` on either foreign-key form is
  rejected. A dispatched argument prunes on a type mismatch, any other throws.
* On an input field the same arms give `ColumnBackedField`, `ColumnBackedReferenceField` or
  `ConditionOwnedField` (BC:3223-3303); `@field(name:)` there is ignored (inferred).

### 2.5 Implicit `id`

* On an argument (FB:2443-2497): `id: ID` with no `@field`, its node taken from the return type. A real
  `id` column shadows it and is rejected; a composite key without `@lookupKey` is rejected; a list of one
  key without `@lookupKey` falls through to a column lookup of `id`.
* On an input field (BC:3057-3093): `id: ID` with no `@field`, `@nodeId` or `@reference`, its node found
  by the resolving table; several nodes are rejected, none falls through to a column.

### 2.6 `@lookupKey`

* Gated by the gathered lookup trigger (FB:7252); a root needs a table-bound return, a child may not be
  a connection or single.
* Forms: a plain or `@reference` scalar, a same-table `@nodeId`, implicit `id`, or an input-typed
  argument (`classifyPlainLookupKeyArg`, FB:2609-2657), whose fields must all be column or reference
  fields, a Remote one deferred, lists refused.
* On an input field it is retired and rejected (BC:2904-2911, FB:2269-2281).
* Projected by `LookupMappingResolver` (:49-91) into a VALUES join (`render/LookupRows`); non-key
  arguments stay filters. Validator: a lookup may not return a connection, list keys need a list return,
  list elements must be nullable, and `@orderBy` on a lookup is rejected (GraphitronSchemaValidator:905-945).

### 2.7 `@orderBy`

* The argument's input type needs exactly one sort field (an enum whose values carry `@order` or
  `@index`) and one direction field (FB:2659-2696).
* On a field that is neither a list nor a connection it is silently ignored (OrderByResolver:72-85), and
  only the first `@orderBy` argument is read. Each enum value must carry `@order` or `@index`; the base
  ordering is `@defaultOrder`, else the primary key.
* Without `@orderBy`: `@defaultOrder`, else the primary key, else none. The validator requires an
  ordering for a paginated field and for a list field.

### 2.8 Pagination

* Roles are assigned by the reserved names `first`, `last`, `after` and `before` with no type check
  (inferred; `facts/PaginationFactVisitor.java:38-66`), ahead of every arm but `@orderBy`.
* `@asConnection` with no authored role synthesises `first: Int` and `after: String`.

### 2.9 Plain input types (IFR, `BC.classifyInputField`)

* Always resolved against the consumer's table, once per use site; `@table` on an input is ignored
  (TB:1906-1922).
* Dispatch (BC:2896-3155): `@notGenerated` rejected; `@lookupKey` rejected; `ID @nodeId` through NLR;
  `@referenceFor` rejected; `@reference` (repeated rejected, else a reference field, Local or Remote);
  an input-object type nests **against the same table**, with a cycle guard and the override carried
  down; implicit `id`; a column hit is a `ColumnBackedField`, or a `ConditionOwnedField` under
  `override:true`; a miss is `ConditionOwnedField` under `override:true`, an immediate unknown column
  under `override:false`, and otherwise an `UnboundField` whose legality its consumer decides.
* Input fields get no enum validation (inferred).
* At projection an `UnboundField` with no override is rejected; under an override it is a suppressed
  slot. The output is a `PlainInputArg`, or `UnclassifiedArg` "plain input type 'T' against table 'X': N
  input field(s) could not be resolved".

### 2.10 Lists of input objects (FB:3540-3711)

* A list `PlainInputArg` or list nesting projects to `In` or `RowIn` over `ElementRows`, Remote-wrapped
  when the cells share a reach.
* Refused per element field: a `@condition` inside an element, a `@nodeId` or composite cell, a list
  inside a list, a nullable nested input, a list or nullable cell, a leaf other than `Direct`,
  `JooqConvert` or `EnumValueOf`, an unbound cell, cells disagreeing on reach, more than 22 cells, and a
  list-level condition reading below the list.

### 2.11 DML inputs (FB:6086-7189, MIR, URW, DRW)

* Verb from `@mutation(typeName:)` (MIR:77-92); UPSERT is deferred at dispatch (FB:6282-6286);
  `table:` only on DELETE, INSERT and UPDATE; `missingAsDeleted` only on DELETE; `multiRow` deferred on
  UPDATE, rejected on INSERT, a broadcast on DELETE.
* Exactly one argument, an input type, with no `@condition`; an outer list is bulk (FB:6418-6458).
* The write table: INSERT and UPDATE take the return's `@table` or a carrier payload's data field,
  else `@mutation(table:)`, the two agreeing; DELETE takes `@mutation(table:)` only (MIR:209-228).
* Fields classify against the write table through the shared tree.
* INSERT (MIR:476-609): `@condition` without override, condition-owned and unbound fields, list or
  condition nesting and plain column collisions rejected; composite `@nodeId` and Remote references
  deferred.
* UPDATE (URW:145-408): nesting flattened; Remote, condition-owned, unbound, list and condition nesting
  rejected; row identity is the first covered key (primary key, then unique keys); fields wholly in the
  key go to WHERE, the rest to SET, a straddler rejected; cross-table foreign keys become identity claims
  and agreement obligations; an empty SET rejected.
* DELETE (DRW:56-148): as UPDATE, every column a WHERE; a covered key identifies, else a broadcast under
  `multiRow`, else rejected; `missingAsDeleted` needs exactly the primary key.
* A length check applies to `Direct` single-column non-enum string columns with a declared length
  (BC:3168-3184, FB:611-645).

### 2.12 `@service` arguments (ServiceDirectiveResolver:208-286, SC)

* Arguments are parameters. `argMapping` entries are `javaParam: seg(.seg)*`, the head a GraphQL
  argument name, `@field` playing no part, the tail walking input fields; unmapped parameters get
  identity entries; `$session` is accepted (ArgBindingMap:153-312).
* Per parameter: `DSLContext`, `$session`, a bound argument (explicit or inferred), a context key, the
  sources shape, else unclaimed and rejected (SC:346-370).
* A `@nodeId` leaf decodes to a key, a record or a polymorphic record (SC:1633-1685); other leaves are
  enum-checked or matched by exact type (SC:1570-1604). An input-object parameter is a `JooqRecord`
  (columns by `@field`) or an `InputBean` (members by `@field`, else the GraphQL name); a `Map`
  parameter is rejected.
* Unmapped GraphQL arguments and unused context arguments are silently ignored.

### 2.13 `@routine` arguments (RDR:143-578, FB:4269-4768)

* Each IN parameter, in catalog order, binds by `columnMapping` (illegal at a root), by `argMapping`, or
  by an argument of the same name (never walking into inputs); otherwise rejected.
* A leaf must be a scalar or enum; a path through a list is deferred; a `@nodeId` leaf bypasses the
  gate; non-`Direct` extraction is deferred; `@field` or `@condition` on a spent element conflicts.
* A bound path is spent. On a read the rest filter the terminus; on a write any unspent argument or leaf
  is rejected, and `@orderBy` or `@condition` deferred.

### 2.14 `@externalField`, accessors, column and nesting children

* `@externalField`: `argMapping` is rejected; the method takes exactly the parent's table; the field's
  SDL arguments are never read and never rejected (inferred).
* An accessor maps arguments to method parameters by arity and assignability, or a single
  `DataFetchingEnvironment`; a public field qualifies only with none. The batch-key path is zero-argument
  only.
* Column and nesting children read no arguments and reject none (inferred).

### 2.15 `@tenant` and `@asFacet`

* `@tenant` is not read by the classifier; the tenant index binds it only through a root `@service`'s
  plain scalar leaf of the tenant key type, and rejects it elsewhere (TBI:237-264, 677-745, 1944-2031).
  The implicit tenant binding needs no directive and reads column-binding slots, lookup keys, DML WHERE
  keys and INSERT inputs.
* `@asFacet` is not read by `classifyArgument`; facet fields classify as plain filters, with
  definition-keyed refusals (FacetFieldValidation:33-60) and use-keyed ones (GraphitronSchemaBuilder:
  1229-1417), built into a `FacetSpec` on a directive-driven connection (ConnectionPromoter:551-589).

## 3. Fetcher type against argument claim

Today's field variants by fetcher type:

* SELECT: `QueryTableField`, `QueryTableInterfaceField`, `QueryInterfaceField` and `QueryUnionField`
  (per participant).
* LOOKUP: a table field whose lookup resolution is keyed.
* COUNT and FACETS: no variant; `totalCount` is synthesised over the page predicate and a facet is a
  `FacetSpec` on the connection; their operation members are defined and never populated.
* INSERT, UPDATE, DELETE: `DmlTableField`, `MutationDmlRecordField`, `MutationBulkDmlRecordField`.
* ROUTINE: a routine chain on a table field; `MutationRoutineWrite[Record]Field`.
* SERVICE: the service field variants.
* TABLE (an inline table child): `TableField`, `TableInterfaceField`, `InterfaceField`, `UnionField`,
  `SameRowTableField`; `BatchedTableField` classifies the same and fetches on its own.
* NESTED: `NestingField`. COLUMN: `ColumnBackedField`, `ColumnBackedReferenceField`. EXPRESSION:
  `ComputedField`. ACCESSOR: `RecordReadField`.

Legend: F filter on the target, F-x filter through `EXISTS`, C condition method, K lookup key, P
parameter, R written value, W WHERE identity, O ordering, Pg page, D rejected, I silently ignored.

| Fetcher | plain scalar | `@reference` | `@condition` | `@nodeId` | `@lookupKey` | plain input | list of inputs | `@orderBy` | pagination |
|---|---|---|---|---|---|---|---|---|---|
| SELECT | F | F-x | C | F or F-x | becomes LOOKUP | F per leaf | F | O | Pg |
| LOOKUP | F, K under `@lookupKey` | F-x or K | C | K same table, D foreign key | K | F or K | F | D | D (connection) |
| INSERT | D (not an input) | R, Remote deferred | D | R, composite deferred | D | R | D | D | D |
| UPDATE | D | R or W, Remote D | D | W or R | D | W or R | D | D | D |
| DELETE | D | W, Remote D | D | W | D | W | D | D | D |
| ROUTINE read | P if spent, else F | F-x | C, D on a spent leaf | P or F | D (deferred) | P or F | deferred through a list | O | Pg |
| ROUTINE write | P, D unspent | D | D (deferred) | P | D | P, D unspent | deferred | D | D |
| SERVICE | P | I | not read (inferred) | P decoded | not read | P (bean or record) | P | I | I |
| TABLE | F | F-x | C | F | K | F | F | O | Pg with `@splitQuery` |
| NESTED, COLUMN, EXPRESSION | I | I | I | I | I | I | I | I | I |
| ACCESSOR | P | I | I | I | I | P | P | I | I |

`@field` names the column wherever a column is bound (reads, DML, a jOOQ record input) and the member
for a service bean, plays no part in service parameter binding, and is rejected beside `@nodeId` on an
argument. `@tenant` binds only at a root service; `@asFacet` only on a directive-driven connection's
root table read. An inline `TableField` with `@asConnection` but no `@splitQuery` is rejected
(FB:1572-1578), and a child `TableInterfaceField` with any filter is deferred
(GraphitronSchemaValidator:105-118). A multi-table SELECT rejects a filter whose extraction is a bean, a
jOOQ record or a record decode (FB:1453-1480).

## 4. What each directive claims

* **`@field(name:)`**: "my underlying name is X". A column for reads, a jOOQ record input and DML; a
  member for a service bean; ignored in service parameter binding; rejected beside `@nodeId` on an
  argument, ignored on an input field; rejected on a spent routine leaf; required on a facet.
* **`@reference(path:)`**: "the value's column is at the end of this path from the consumer's table".
  A correlated `EXISTS` on reads, Local when the path is empty; the stated route for `@nodeId`; Remote
  deferred on INSERT and rejected on UPDATE and DELETE; never repeated on an argument or input field.
* **`@referenceFor(type:, path:)`**: a per-participant route, legal only with `@nodeId`, inert for a
  participant it does not name.
* **`@condition(condition:, override:, contextArguments:)`**: "an authored predicate over this slot, or
  the field's arguments; `override` means I own the implicit predicate". ANDed on reads, override
  cascading down; rejected on DML; rejected on a spent routine leaf and deferred on a routine write; not
  read for a service. On a `@nodeId` slot its parameter receives the decoded key.
* **`@nodeId(typeName:)`**: "this ID encodes node T's key". The consumer derives the binding: a same-table
  filter or key, a foreign-key filter (Local or Remote), a DML SET or WHERE cell, or a service decode. A
  polymorphic type is legal only at a service. Without `typeName:` it is inferred over the consumer's
  table.
* **Implicit `id`**: a `@nodeId` claim naming the target node, keyed by return type on an argument and by
  table on an input field; a real `id` column shadows it and is rejected.
* **`@lookupKey`** (arguments only): "one row per key value", which makes the fetcher a LOOKUP; rejected
  on a foreign-key `@nodeId`, a routine read and a lookup connection; retired on input fields.
* **`@orderBy`**: "this input chooses the ordering", meaningful on list and connection table reads,
  ignored on a single field, rejected on lookups and DML.
* **Pagination names**: page-window roles by name; a connection synthesises the defaults.
* **`@tenant`**: "this value is the tenant key", honoured only at a root service's plain scalar.
* **`@asFacet`**: "count this filter column's distinct values on the connection".
* **`@notGenerated`**: rejected everywhere on inputs.
* **`argMapping`** (on `@service`, `@condition`, `@routine`): "parameter p reads the GraphQL path a.b.c";
  rejected on `@externalField`; on `@routine` it spends the path.
* **`columnMapping`** (on `@routine`): "parameter p reads the previous node's column"; rejected at a root.

## 5. Gaps and inconsistencies

1. **An input field's `@condition(override:false)` drops the implicit predicate** (FB:3382-3387). On an
   argument `override:false` keeps both, and the architecture page and `condition.adoc` say both sides
   keep it.
2. **The architecture page has drifted**: it names `projectOrderBySpec`, `projectPaginationSpec`,
   `projectForLookup` and `buildArgCondition`, none of which exist; it shows a two-component
   `NestedInputField`; it calls `@nodeId` input fields out of scope for `@condition`.
3. **`lookupKey.adoc`** shows `@lookupKey` on input fields and on `@mutation`, both retired; the SDL
   description reads as `@nodeId`-only while plain scalar keys work.
4. **UPSERT** is documented (`mutation.adoc`) and deferred at dispatch (FB:6282); its emitter arm
   (TypeFetcherGenerator:4249) is unreachable.
5. **`@reference` with `@lookupKey` on a scalar argument** is not gated, where the input-field twin is
   deferred (FB:2641-2652).
6. **Silently ignored**: arguments on column, nesting and expression children; `@orderBy` on a single
   field and a second `@orderBy` argument; unmapped service arguments and unused context arguments; a
   `@condition` missing its method; `@field` on a `@nodeId` input field.
7. **Implicit `id`** is keyed by return type on arguments and by table on input fields; a list of one key
   without `@lookupKey` falls through to a column lookup.
8. **Duplicated rules**: the `@notGenerated` and `@lookupKey` refusals at the argument top level and per
   field; an unreachable `@lookupKey` refusal in MIR:486; near-identical projection branches for table and
   plain inputs (FB:3144-3199); `@nodeId` resolution both in the plan and in place.
9. **Stale comments and docs**: MIR:565 says `ConditionOwnedField` admits on UPDATE and DELETE (it is
   rejected); MethodRef.java:92-94 says a slot key can diverge under `@field` (it cannot); the SDL says
   `@nodeId` needs `@node` (a catalog-backed `@table` type works).
10. **Unchecked binding types**: `@condition` scalar parameters get no wire or enum check (SC:1408-1416);
    `checkScalar` lets list-ness mismatches through; a dotted service path ending on an input object
    skips bean enrichment (inferred).
11. **`service.adoc`**: says `method` defaults to the field name (it is required, SC:276), describes
    `Map` parameters (rejected), and says context-argument parameters are checked (they are not).
12. **INSERT admits a list-typed scalar carrier** that UPDATE and DELETE reject (inferred, URW:509).
13. **Tenant**: routine-spent arguments never route the tenant, unreported (inferred); the `@tenant`
    SDL description is garbled.
14. **A path-step `@condition` reflection failure** loses its typed reason (BC:2654).
15. **Pagination roles** are assigned by name with no type check (inferred).
16. **COUNT and FACETS** have no argument model; their operation members are never populated.
