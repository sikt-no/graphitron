---
id: R927
title: "Generated filters over a converter-backed column are proven in all four predicate shapes"
status: In Review
bucket: testing
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-09-07
last-updated: 2026-09-08
---

# Generated filters over a converter-backed column are proven in all four predicate shapes

## Goal

A filter value compared against a converter-backed column produces a query the database accepts,
whichever of the four predicate shapes the coordinate lowers to, and the tree shows this against
PostgreSQL rather than arguing it from a reading of the renderer. A *converter-backed column* is one
whose jOOQ codegen attached a `Converter`, so the Java type generated code handles differs from the
column's SQL type: `converter_campus.org_code` is a `bigint` domain that generated code sees as
`String`. A *generated filter* is a predicate graphitron mints from a schema argument bound to a
column, with no developer method involved; the four shapes are single-column equality, single-column
membership, and the row-value forms of both, which a `@nodeId` over a composite key produces.

```graphql
type Query {
    # single-column equality: { converterCampusesByOrgCode(orgCode: "1120") { campusName } }
    # answers Trondheim and Gjøvik
    converterCampusesByOrgCode(orgCode: String @field(name: "ORG_CODE")): [ConverterCampus!]!
        @defaultOrder(primaryKey: true)
    # single-column membership
    converterCampusesByOrgCodes(orgCodes: [String!] @field(name: "ORG_CODE")): [ConverterCampus!]!
        @defaultOrder(primaryKey: true)
}
```

Both queries bind the value as the column's SQL type, so PostgreSQL compares `org_code_domain` against
`bigint` and answers rows. Nothing in the tree executes a generated filter over a converter-backed
column today: the two live converter pins in `GraphQLQueryTest` cover split-query `VALUES` cells and
service rows, and the claim that the filter rail is sound rests on a reading of jOOQ's behaviour. When
the item lands, an execution-tier test executes each of the four shapes over a converter-backed
column, the bind rule that makes them sound is stated once at the surface that owns it with the
producer guarantee it rests on linked from the consumer, and the one spelling asymmetry in
`ConditionGlueRenderer.columnCompare` that invites a misreading is gone.

What a settled answer lets the next item do: the composite-key converter fixture this item adds is the
database evidence a relaxation of the `LANDING_TYPE_DISAGREEMENT` refusal (the
`intent_node_id_decode_landing_defect` verdict R926 shipped) into a `ColumnComparison` coercion would
need, since the row-value bind side is exactly what such a coercion has to be proven against.

## What the tree does today

All four spellings `columnCompare` emits bind their value at the receiving column's `DataType`, so a
registered `Converter` runs in every branch. Probed against the pinned jOOQ 3.20.11 with a `BIGINT`
`DataType` carrying a `Long`-to-`String` converter, `col.eq(DSL.val(v, col))`, `col.in(List<String>)`,
`DSL.row(c1, c2).eq(record.valuesRow())` and `DSL.row(c1, c2).in(List<Row2>)` all render the value
inline as `186`, a `bigint` literal, never as `'186'`; so do the plain `col.eq("186")` and an explicitly
inferred `DSL.val("186")` once it meets the column inside a comparison. The mechanism is jOOQ's:
`Field.eq(T)` and `Field.in(Collection)` wrap each value through `DSL.val(value, field)`, and
`Row.eq(Row)` compares field to field, where the value row's cells come from `Record.valuesRow()` typed
at the record's own fields. For a decoded node key those fields are the node table's catalog columns,
because `RecordDecodeFragments` loads the key with `decoded.fromArray(values, Tables.T.C1, Tables.T.C2)`.

The equality branch's explicit `DSL.val(local, alias.COL)` is therefore redundant rather than
load-bearing. It is not a compile-time necessity either: `ConditionGlueRenderer.localType` declares the
binding local at the column's own Java type, because `FieldBuilder.javaTypeFor` answers
`column.columnClass()` for every column-bound extraction, so `alias.COL.eq(local)` type-checks in every
arm. The spelling is residue from before `MatchKind` collapsed the four arms into shape times arity;
after this item the four branches differ only in receiver expression and operator over identical data,
which is the shape that enum's javadoc already claims.

The untyped bind that broke the split-query rail lived in a `VALUES` derived table, where a cell has no
receiving column to be typed against; that is why `LookupRows` spells `DSL.val(v, table.COL.getDataType())`
itself. A generated filter's value always meets a catalog column.

The probe is a statement about jOOQ's rendering; PostgreSQL accepting the statement is what the
execution pins add, and they are the only enforcer of the bind's type (see Tests).

## Implementation

`ConditionGlueRenderer.columnCompare`: emit the equality branch as `alias.COL.eq(local)`, so all four
branches read `receiver.op(local)` and none carries an explicit bind. The readability rule for generated
code favours it independently of symmetry: `campus.ORG_CODE.eq(orgCode)` is what a consumer stepping
through the glue method expects to read. Give the method a short javadoc stating that the value binds at
the receiving column's `DataType` because jOOQ types every comparison operand that way, with two links:
`{@link ColumnComparison}` for the rule, and `{@link BodyParam.ColumnPredicate}` for the producer
guarantee below, so the javadoc reference gate holds the consumer-to-producer linkage.

`BodyParam.ColumnPredicate` javadoc: one sentence recording the guarantee the renderer relies on, that a
column predicate's binding local is declared at the predicate column's own Java type (`javaTypeFor`
answers `column.columnClass()` for every column-bound extraction, and the row forms carry their typed
`Row<N>` from the columns), which is what makes `receiver.eq(local)` bind at the receiver without a
spelling. If an arm ever answers a wire type instead, `receiver.eq(local)` still compiles and binds at
the wrong type, and the drop above removes the last `DSL.val` that would have masked it, so the
linkage is the enforcer's address, not decoration.

`ColumnComparison`, the "companion bind rule" section: sharpen the rule it already opens with rather than
adding a scope section. The rule is *a value binds at the `DataType` of the column it was read from*.
For a generated filter that column is the receiver, so jOOQ's own receiver typing satisfies the rule and
no spelling is needed; an explicit `DSL.val(value, col.getDataType())` appears exactly where the receiver
is not the column the value came from, with one exemplar for each of the two ways that happens: no
receiver at all (a `VALUES` cell, `LookupRows`), and a diverged sibling column (the coerced arm of
`equalityAgainstValue`, whose existing paragraph already argues the direction). No list of call sites:
an inventory in prose is what rots.

Fixture for the row shapes: no table in `init.sql` has a composite key over a converter-backed column
(`diverged_child_label` is keyed on `label_code` alone), so add one beside the other converter fixtures
rather than re-pointing the forcedType, on the reasoning that file already records for
`diverged_ref_child`:

```sql
CREATE TABLE converter_campus_term (
    org_code   org_code_domain NOT NULL REFERENCES converter_org(org_code),
    term_no    integer         NOT NULL,
    term_name  varchar(50)     NOT NULL,
    PRIMARY KEY (org_code, term_no)
);
INSERT INTO converter_campus_term (org_code, term_no, term_name) VALUES
    (186, 1, 'Autumn UiT'), (1120, 1, 'Autumn NTNU'), (1120, 2, 'Spring NTNU');
```

Its header comment states what it pins: a composite primary key whose first column carries the
`OrgCodeStringConverter`, exercised by the row-value equality and membership shapes of the generated
filter rail. Comments name live things, so it cites the converter class and the fixture types, not a
roadmap item. The type-selected forcedType (`includeTypes: org_code_domain`) picks up `org_code` with no
codegen change.

Schema additions in the example schema, all beside the existing converter fixture types:

* `type ConverterCampusTerm implements Node @table(name: "converter_campus_term") @node` with
  `id: ID! @nodeId` and the three columns as fields;
* a listing root `converterCampusTerms: [ConverterCampusTerm!]! @defaultOrder(primaryKey: true)`, which
  the tests read ids off;
* the two row-shape roots and their filter input types, spelled the way
  `filmActorBySingularCompositeNodeId(filter: FilmActorSingularNodeIdFilter)` (`BodyParam.RowEq`) and
  `filmActorsByCompositeNodeIds(filter: FilmActorCompositeNodeIdFilter)` (`BodyParam.RowIn`) are spelled
  today, over the new type;
* the two single-column roots from the Goal, beside `converterCampuses`.

In the web sandbox the `init.sql` edit re-triggers the jOOQ-catalog cascade; `.claude/web-environment.md`
has the recovery.

## Tests

Execution tier, since the contract is "PostgreSQL accepts the statement and answers the right rows"; a
compilation-tier case adds nothing because all four branches compile today. One new `@ExecutionTier`
class in `graphitron-sakila-example`, `ConverterFilterExecutionTest`, on the
`NodeIdValueAgreementExecutionTest` harness shape (container or `test.db.url`, one `GraphQL` instance).
A companion class rather than cases in `GraphQLQueryTest` because the four cases share a fixture family
and a single claim, which is the grain the other execution companions in that module are cut at. One
case per shape:

* single-column equality: `converterCampusesByOrgCode(orgCode: "1120")` answers `campusName`
  Trondheim, Gjøvik in primary-key order;
* single-column membership: `converterCampusesByOrgCodes(orgCodes: ["186", "1120"])` answers all three
  campuses, and `["186"]` answers Tromsø alone;
* row equality: the singular composite `@nodeId` root over `ConverterCampusTerm`, fed the `id` of Spring
  NTNU read off `converterCampusTerms` in the same test, answers that one term;
* row membership: the list root, fed the ids of Autumn UiT and Autumn NTNU read the same way, answers
  exactly those two.

Ids are read off the listing rather than minted with the generated `NodeIdEncoder`, because minting one
in the test would re-encode the very converted value the case is verifying.

The assertion in each is row content; a bind that reached PostgreSQL as `character varying` fails the
request outright, so returned rows are the proof. Two enforcers, kept distinct: `ConditionSqlBaselineTest`
logs statement text with every bind rendered as `?`, so its pins staying green shows the `columnCompare`
edit moved no SQL text and can show nothing about a bind's type; the new class is the only enforcer of
the type, and only over converter-backed columns, which is the only place the two can differ. The
renderer edit therefore lands with or after the new class, never ahead of it.

## Other solutions we've considered

**Discard the item, since the fault it names does not exist.** Declined. `Discarded` is for a plan
superseded, infeasible or abandoned, and what remains here is a live coverage gap with a named enforcer
missing: a third-party library's typing behaviour is exactly the kind of claim that should not rest on a
reading.

**Discard, and refile the coverage gap as a fresh Backlog item.** Declined. It would carry the same
Goal, Tests and fixture as this body with the `columnCompare` cleanup and the `ColumnComparison`
sharpening dropped, and those two are what stop the misreading from being made again; the item's body is
also already the spec the refiled item would need.

**Route all four branches through a typed bind in `ColumnComparison`, the original plan.** Declined.
The emitted SQL would not move and generated code would grow, and the change would enshrine a rule, "a
filter value needs an explicit typed bind", that is false for comparisons and true only where no
receiving column exists; the next reader would then infer the reverse fault at every comparison site
that omits it. `ColumnComparison` also mints column-against-column equality and coerces on Java-type
divergence; a filter compares a column against a request value declared at that column's own type, so
no divergence can arise, and the `in` and `row(...).in` operators are not that class's to mint.

**Pin the row shapes without a new table**, with a jOOQ-level execution test running
`DSL.row(CONVERTER_CAMPUS.ORG_CODE, CONVERTER_CAMPUS.CAMPUS_ID).eq(record.valuesRow())` against the
database. Declined. It proves the mechanism and not the generator's emission, which is the split
inference the original filing rested on, and a converter-backed composite key is the fixture the
forward dependency in the Goal needs anyway.

## Provenance

Found while specifying R926, the `@nodeId` landing checks (shipped; its entry in `roadmap/changelog.md`
carries the design), which named the four-branch asymmetry as adjacent and out of its own scope, on the
reading that three of the four branches bind untyped. Specifying this item falsified that reading (see
"What the tree does today"). R926's entry leans on it once: coercing rather than refusing a landing
divergence was deferred because "`ColumnComparison`'s `coerce` is sound only with its companion bind
rule, and three of the four branches of `ConditionGlueRenderer.columnCompare` bind the bare local
today". That reason falls with the reading; the entry's other reason, that a divergence is accidental as
often as intended and the author is the only party who can say which, stands on its own and is why the
Goal above frames the fixture as evidence for a future relaxation rather than as a defect. The changelog
is history and stays as written.
