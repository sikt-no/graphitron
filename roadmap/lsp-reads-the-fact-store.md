---
id: R638
title: "The LSP reads the fact store instead of the catalog projection seam"
status: Spec
bucket: architecture
priority: 2
theme: lsp
depends-on: []
created: 2026-08-12
last-updated: 2026-08-12
---

# The LSP reads the fact store instead of the catalog projection seam

The language server answers hover, completion, go-to-definition, inlay hints, diagnostics and code
actions out of `CompletionData`, a pre-baked in-memory projection of four fixed collections built
per pipeline round. That shape bounds what the LSP can offer: it can only ask what someone
projected in advance, its lookups are linear scans over lists (`getTable` streams and filters),
and the projection accretes a field whenever a consumer needs something new, which its own
backwards-compatible three-argument constructor records. The fact store holds the same knowledge
as queryable relations carrying source line and column, so hover and hints could ask questions
nobody pre-projected: which columns of a table sibling fields already claim, what the foreign-key
graph reaches from here, what the claim strata resolved and why. The store is also the one place
where a new fact family becomes available to every reader at once, and the language server is
currently the reader that cannot see it: it imports no store relation at all, while the generator
module reads sixteen and the MCP server two.

Scoping this turned up the sharper version of the problem. The jOOQ catalog is projected three
times, not twice. The `sql_` family holds it as relations. `CompletionData` holds it again,
Java-name-centric, for the LSP. `CatalogFacts` holds it a third time, SQL-name-centric, for the
MCP `catalog.tables` / `catalog.describe` tools, and its own javadoc names the situation exactly:
"a sibling projection to `CompletionData`, not a widening of it ... each carries exactly what its
consumer reads." Both Java projections are built in one pass by `CatalogBuilder` over a jOOQ
reflection scope that closes at the end of the pass, which is why each is frozen into resolved
immutable values. The store has no such boundary. So the eventual shape is not "the LSP gets a
second way to read the catalog", it is one relational catalog with two shells reading it and two
frozen projections deleted.

This item does the first slice of that and nothing more.

## Scope

The pilot is the catalog-backed arms of `Hovers`: `tableHover`, the `tableColumnHover` leaf,
`fkHover`, `classNameHover`, `methodHover`, `nodeTypeHover`, and the formatters and lookup helpers
they reach through (`findExternal`, `columnGraphqlType`, `formatTable`, `formatColumn`,
`formatClass`, `formatMethod`, `formatNodeType`). That is roughly 167 of the 513 lines in
`Hovers.java`.

Explicitly out of scope, each for its own reason:

* `DeclarationHovers` (459 lines) reads `LspSchemaSnapshot.Built`'s classification maps
  (`fieldClassificationsByCoord`, `typeClassificationsByName`), not the catalog. Classification is
  derived, and the derivation is only partly in the store today; porting it is a different item
  with a different risk profile.
* `Hovers`' snapshot-backed arms, `slotHover` over `TypeBackingShape` member slots and the
  `lspColumnDispatch` switch in `columnHover`, for the same reason. `columnHover` keeps its
  classification dispatch and changes only where its leaf resolves a table column.
* Tree-sitter parsing. The store reflects the last successful capture; the server must answer on
  an unparseable mid-edit buffer.
* The `SourceWalker` Javadoc overlay. This is not a concession, it is the store's stated design:
  the `jvm_class` family comment says Javadoc and source positions "deliberately stay out; those
  live on the LSP's `SourceWalker` cadence and are joined at request time, so a `.java` edit is
  seen without a generator rebuild." Hover already joins at request time through `Descriptions`.
  That stays exactly as it is.
* Protocol lifecycle and tracing.
* `CatalogFacts` and the MCP tools reading it. Collapsing that projection is the obvious sibling
  and is deliberately not bundled here; this item is sized to produce a measurement, not to
  finish the migration.

## Implementation

**A lookup seam hover depends on, with two implementations.** The structural-pivot technique
applies: introduce the replacement alongside the old surface, migrate behind the compiler, delete
the old surface last. Add an interface in `rewrite/catalog` naming exactly the six questions the
in-scope hover arms ask, and nothing else:

    Optional<TableInfo>      table(String name)
    Optional<ColumnInfo>     column(String tableName, String columnName)
    Optional<ForeignKeyInfo> foreignKey(String keyName)
    Optional<ClassInfo>      externalClass(String fqn)
    Optional<MethodInfo>     method(String fqn, String methodName)
    Optional<NodeInfo>       node(String typeName)

The value types are the smallest records that carry what the formatters render, not a re-export of
`CompletionData`'s nested records. Ship two implementations: one delegating to a `CompletionData`
instance, one issuing SQL. Hover takes the interface. This is what keeps the migration cheap in
the test tier, and it makes the parity test structural rather than hand-written: the same fixture
feeds both implementations and every question is asked of both.

Note what the interface shape itself buys. `getTable` is a linear stream scan; `fkHover` is a
nested scan over every table's references; `columnGraphqlType` is a nested scan over every table's
every column. Naming the questions is the step that lets either side answer them with an index.

**A read connection for the LSP.** `GraphitronModelStore` holds one `java.sql.Connection` and one
`DSLContext` over it. `DevMojo` hands that same `dsl()` to the MCP server, which is safe there
because MCP is turn-based; the LSP is not, and would put concurrent request threads on a
connection a build thread is writing through. Add a read-only accessor that opens an additional
connection on the same URL. Both URL shapes admit one: the in-memory store is a named database
held open by `DB_CLOSE_DELAY=-1`, and the file store is `AUTO_SERVER=TRUE`.

The second connection is not only a thread-safety fix. Capture runs as a single transaction
(`FactCapture`'s `dsl.transaction`), so a reader on its own connection sees the previous committed
state until that transaction commits and the new state afterwards, never a half-written round.
That is precisely the invariant `Workspace` hand-rolls today by swapping `catalog`, `catalogFacts`
and `snapshot` together so "a single set of volatile reads observes one" build. The store gives it
by construction.

**Freshness stays where it is.** `LspSchemaSnapshot`'s `Built.Current` / `Built.Previous`
distinction gets no store equivalent and should not get one. Rows cannot say whether the round
that wrote them is the round matching the buffer the user is editing; that is workspace state, and
`Workspace.demote` is where it is decided. The store answers *what*, the workspace keeps answering
*how fresh*. Do not let H2 isolation semantics quietly become the freshness contract.

**Wiring.** `DevMojo` already resolves `sessionStore` and hands a `StoreHandle` to the MCP server;
route a read handle into `Workspace` the same way, on the same line of the same method. The LSP
module already depends on `graphitron`, which depends on `graphitron-model` at compile scope, so
the generated `Tables` is already on its classpath. No new dependency.

**Parity gaps in the captured facts.** Four differences between what `CompletionData` renders and
what the store holds. Each is a decision, and the default is to widen capture rather than
re-derive a string in the LSP, because a derived string in a shell is how the third projection
happened in the first place.

* `CompletionData.Column.graphqlType` is misnamed: it is `col.getType().getName()`, the Java class
  name (`java.lang.Integer`), and hover renders it verbatim. `sql_column.sql_type` is the SQL type
  as jOOQ reports it. Different values. Capture the Java type as its own column; `sql_column`
  already carries `jooq_name` for exactly this reason, its comment saying the name rides along
  because "the LSP surface is Java-name-centric."
* `CompletionData.Reference.keyName` is the generated Java constant on the `Keys` class
  (`FILM__FILM_LANGUAGE_ID_FKEY`), with the SQL constraint name as fallback, and it is what an
  author types in `@reference(key:)`. `sql_constraint` carries only the SQL name. Add a
  `jooq_name`, symmetric with the one `sql_table` and `sql_column` each already have; the foreign
  key is the odd one out rather than a new precedent.
* `CompletionData.Table.classFqn` is the generated table class FQN and is load-bearing: it is the
  join key into `SourceWalker.Index` for the Javadoc overlay and for goto-definition.
  `sql_table.jooq_name` is the table *field* name. Recomposing the FQN from `source_name` plus a
  guessed `.tables.` segment is exactly the kind of derived-in-the-shell string this item exists
  to remove. Capture it.
* `jvm_class` is filtered to public, non-synthetic, top-level, outside the generated jOOQ package.
  Confirm `CompletionData.ExternalReference` agrees on all four before assuming the sets match;
  the shadow test is what settles it, and a disagreement is a finding either way.

One difference was checked and is immaterial: `NodeMetadata.keyColumns` distinguishes an absent
`@node(keyColumns:)` from an empty list, and `graphitron_node_key_column` cannot, but hover's
`formatNodeType` treats null and empty identically.

**Deletion.** On cutover, the `CompletionData`-backed implementation of the lookup interface
survives (the 87 test sites and the out-of-scope features still build the record); what deletes is
the hover code paths that reached through `CompletionData` directly. `CompletionData` itself does
not shrink in this item, and the plan should not pretend otherwise. Recording that honestly is
part of the point: see the measurement discussion below.

## Tests

**Shadow parity, in the LSP test tier.** The protocol `AuthoredClaimConflicts` used applies:
land the store-backed lookup shadowed, pin it equal to the seam-backed lookup under a parity test,
cut over only once it agrees, then delete the shadowed path.

Compare at the lookup interface, not at the rendered markdown. Hover's output is a formatted
string, and byte-equality on it would pin two things that are not worth pinning: the exact
markdown, which we may want to improve, and `methodHover`'s `findFirst` over same-named methods,
which picks an arbitrary overload and is arguably a defect. Pinning at the interface keeps the
formatters as the single renderer for both sides, so a markdown change cannot break parity, and it
leaves the overload question visible as its own decision. If the store-backed side resolves the
overload correctly by descriptor (`jvm_method` keys on it, `CompletionData.Method` carries it but
hover ignores it), that is a behaviour *change* and needs its own line in the plan, not a silent
improvement smuggled through a parity gate.

**Fixture.** `RejectionSeverityCoverageTest` already opens a `GraphitronModelStore` in this
module, so the tier can boot one. The capture-side helper `CapturedStore` is package-private in
`graphitron`'s test sources and is not reachable from here; either widen it deliberately or write
the small equivalent locally. Prefer whichever leaves one helper rather than two.

**Latency.** Measure per-request hover latency for the in-scope arms, store-backed against
seam-backed, on the Sakila fixture. State the number in the item before cutover. The expectation
that a map lookup beats SQL is not obviously true here, because the incumbent is a linear scan and
two of the arms are nested linear scans; that is a reason to measure, not a reason to assume the
result goes our way.

## Gate criteria

Cutover requires: the parity test green across the fixture corpus; the measured latency stated and
no worse than the seam at p99; and the four capture gaps closed or explicitly deferred with the
arm they affect left on the seam.

## What this measures

The relational core has exactly one shell besides the generator, so the claim that it lowers the
cost of an additional consumer has one data point to divide by (`docs/history/road-to-the-relational-core.adoc`
records the measurement). This item makes it two, under conditions that make the comparison
honest: the same feature, the same behaviour held fixed by a parity test, both sides measured.

The prediction under the hypothesis is that the ported arms shrink. The null is that they stay
flat, because the shared *Java* model already made new facts nearly free for existing shells a
month before the store existed. Record both the line and branch counts on each side, at the
cutover commit rather than off the working tree, and record them whichever way they come out.

## User documentation

Exempt. Hover output is unchanged by construction, and the item adds no goal, directive, or wire
format. If the overload-resolution change above is taken, it is a behaviour change and needs a
line wherever hover behaviour is described.

## Roadmap entries

* Collapsing `CatalogFacts` onto the `sql_` family, retiring the third projection.
* The remaining feature packages: completions, definition, inlay, diagnostics, code actions.
* `DeclarationHovers` and the classification-backed arms, once more of the classification
  derivation lives in the store.
</content>
