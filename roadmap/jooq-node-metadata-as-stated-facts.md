---
id: R710
title: "The jOOQ crawler records node metadata as stated, not as validated"
status: Spec
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-18
last-updated: 2026-08-18
---

# The jOOQ crawler records node metadata as stated, not as validated

The store holds no record of the `__NODE_TYPE_ID` / `__NODE_KEY_COLUMNS` constants a jOOQ-generated
table class publishes. `CatalogFactCapture` does not mention them; the only reader is
`JooqCatalog.nodeIdMetadata`, which reflects on the class at pipeline time and answers a validated
question (`Optional.empty()` for malformed, with the reason available separately through
`nodeIdMetadataDiagnostic`). So the metadata exists only as a live reflection result, never as a
fact, and the one consumer that needs it during capture reaches across corpora to get it.

This item records it as a fact, and records it *as stated*.

## Why as-stated matters here

Capture transcribes what a corpus says; it does not decide whether what the corpus says is
admissible. The two are separable for this metadata, and keeping them separate is what lets the
jOOQ crawler own its corpus without knowing anything about the SDL that will eventually claim
against it. A malformed constant is a fact about the consumer's generated code, and a fact worth
having: it is exactly the state an author needs a diagnostic about, and today it is indistinguishable
in the store from a table that publishes nothing at all.

So the rows go in whether or not the metadata is well-formed, and well-formedness becomes a
question asked of the rows afterwards: the type id non-empty, the key-column list non-empty, and
every entry resolving to a `sql_column` of that `sql_table`. All three are joins inside the jOOQ
corpus, so the check is a legal derivation over one corpus rather than a validation smuggled into a
crawler.

## Relations

Two base relations in the `sql_` family, in `graphitron-model.sql` beside the family they join:
a parent per table and a child per `__NODE_KEY_COLUMNS` entry, mirroring how
`graphitron_node` / `graphitron_node_key_column` split the same metadata's SDL spelling.

Family placement is `sql_` even though the constants are declared by generated Java code rather
than by the database. The precedent is `sql_table.class_fqn`, whose comment already commits the
family to owning facts about generated jOOQ classes ("jvm_class cannot supply it because that
family deliberately excludes the generated jOOQ package"), and the cadence argument seals it: the
corpus is the same generated package `sql_table` partitions on, one `source_name`, one
`clearSchemaSources` round, one crawler. A new family for two relations would put a prefix
boundary through the middle of one refresh unit. Because the family charter renders as the schema
reference's page preamble and currently reads "What the consumer's database declares", the
`meta_family` definition row for `sql_` is extended in the same change to own the
generated-model facts the family already carries; a table comment cannot amend the charter above
it.

`sql_node_metadata`:

- Key `(source_name, table_schema, table_name)`, FK to `sql_table`. The metadata is a property of
  the table, not of the class, which is why the key is `sql_table`'s and not a class name.
- A row exists exactly when the table class declares **either** constant. A class declaring only
  half the pair is exactly the state this item's rationale condemns leaving invisible (today
  `JooqCatalog.doLookup` folds it into `Absent` silently, since `getField` throws on either
  constant missing), so it gets a row with the undeclared constant's form arm below, and
  `CHECK (NOT (type_id_form = 'ABSENT' AND key_columns_form = 'ABSENT'))` keeps "no row"
  unambiguous: a table with no row publishes nothing at all.
- `type_id_form` VARCHAR NOT NULL, CHECK `('STRING', 'NULL', 'OTHER', 'ABSENT')`: what the
  constant stated. Closed taxonomy per the schema's convention: the probe's own discrimination
  (`validateLookup`'s `instanceof String` test plus the null case) plus the undeclared arm.
- `type_id` VARCHAR: the value exactly when `type_id_form` is `STRING`, including the empty
  string, which is a stated value the derivation judges rather than a capture-time rejection.
  NULL otherwise.
- `type_id_class` VARCHAR: the value's runtime class FQN exactly when `type_id_form` is `OTHER`;
  the deterministic witness behind the diagnostic's "got: ..." clause. NULL otherwise.
  Deliberately the class name and not a `toString` rendering: an arbitrary object's rendering can
  carry an identity hash, and a nondeterministic column fails the warm/cold agreement gate.
- `key_columns_form` VARCHAR NOT NULL, CHECK `('FIELD_ARRAY', 'NULL', 'OTHER', 'ABSENT')`, and
  `key_columns_class` VARCHAR non-NULL exactly on `OTHER`: same pattern for the array constant.
  Child rows exist exactly when the form is `FIELD_ARRAY`; zero child rows under that form is an
  empty array, stated structurally rather than through a flag.
- The form-to-nullability correspondences are stated as `CHECK` constraints, not only in comments
  (`type_id IS NOT NULL` iff the form is `STRING`, each `_class` non-NULL iff its form is
  `OTHER`): every invariant has an enforcer, and the engine can hold these.

`sql_node_key_column`:

- Key `(source_name, table_schema, table_name, position)`, FK to `sql_node_metadata`. `position`
  is the array index and is load-bearing, recorded rather than reconstructed: `JooqCatalog`
  documents that the encoded identity depends on the declared order, so a reader that recovered
  the order from anywhere else would encode different IDs.
- `column_name` VARCHAR: `Field.getName()` as found (the SQL column name the entry states). NULL
  exactly when the array entry itself is null, which is a stated fact about the entry.
- Deliberately **no** FK to `sql_column`: the constant spells a column by name and may spell one
  the table does not have, which is exactly the malformed state worth recording. The schema's own
  rule ("a FOREIGN KEY only where the walk writes the child while standing on the parent, never on
  a reference the author spells by name") already covers this; the crawler stands on the table,
  not on the column.

Two invariants above range over rows rather than sitting inside one, so no `CHECK` can hold them
and they would otherwise be stated with nothing failing when they break. Both get a case in
`FactSchemaGateTest`, where the schema's engine-unenforceable invariants already live: no
`sql_node_key_column` row may hang off a parent whose `key_columns_form` is anything but
`FIELD_ARRAY`, and `position` must be dense from zero within each parent. The second is the shape `mergeOrdinalsAreDense` and
`applicationOrdinalsAreDense` already pin for other relations, so it is a third case in an
established pattern rather than a new kind of gate.

## The well-formedness derivation

A view in the `intent_` family, `intent_node_metadata_defect`: one row per defect the stated
metadata exhibits. The rows transcribed above are `sql_` because a walk read them; the verdict is
not, because no walk read "this metadata is malformed": graphitron's rule produces it, which
places it in stratum two. It is keyed by the catalog's own key, `(source_name, table_schema,
table_name, defect, position)`, with no `graph_name`, on `intent_class_member_slot`'s stated
terms: the question is about a table, and a graph reaches it the way it reaches any source-keyed
fact. `intent_name_matched_key_pair` is the closer precedent, being a derivation over the jOOQ
corpus alone keyed on that corpus's own key rather than on the classpath census's; a graph-free
`intent_` resident is settled shape and not a novelty, so the view states its key's terms and
claims nothing about being unusual in having them.

- `defect` is a closed vocabulary, one arm per state the probe distinguishes today plus the two
  the `ABSENT` forms add: `TYPE_ID_NOT_DECLARED`, `TYPE_ID_NULL`, `TYPE_ID_WRONG_TYPE`,
  `TYPE_ID_EMPTY`, `KEY_COLUMNS_NOT_DECLARED`, `KEY_COLUMNS_NULL`, `KEY_COLUMNS_WRONG_TYPE`,
  `KEY_COLUMNS_EMPTY`, `KEY_COLUMN_ENTRY_NULL`, `KEY_COLUMN_UNRESOLVED` (spellings adjustable at
  review). `position` is the offending entry's array index on the two per-entry arms, NULL on the
  whole-constant arms.
- Every defect observed gets a row; there is deliberately no first-failing short-circuit, which
  would make `validateLookup`'s evaluation order normative ("name the row, not the question"). A
  reader wanting one message reduces by an ordering it owns.
- **No reason text column.** The closed vocabulary plus the witness columns already stored
  (`type_id_class`, `key_columns_class`, `position`, `column_name`) are the fact base the item
  promises; message prose lands with its eventual consumer, decoded in Java the way
  `AuthoredClaimConflicts` decodes a closed verdict vocabulary. A composed-text column with no
  consumer and no agreement anchor would be nobody's spelling.
- `KEY_COLUMN_UNRESOLVED` resolves the spelled name the way `JooqCatalog.findColumn` does:
  case-insensitively against both the Java field name and the SQL name, i.e. an
  `UPPER()`-normalised join against `sql_column.jooq_name` OR `sql_column.column_name`, the same
  two-tier spelling `intent_column_match_claim` already uses. Mirroring only the SQL-name tier
  would diverge from the probe exactly where the tier order exists to arbitrate.
- Every join is inside the jOOQ corpus, which is what keeps this a legal derivation rather than a
  validation smuggled into a crawler.

Well-formed metadata is a `sql_node_metadata` row with zero defect rows. The view's comment states
the owned silences explicitly: absence of defect rows alone also covers a table that publishes
nothing, so "well-formed" is the conjunction, never the anti-join alone. The positive resolved
view (the validated type id plus the ordered, resolved `sql_column` coordinates) is deliberately
left to the sibling nodehood item as its first reader, per the fact model's "a derivation gets a
relation as soon as a second reader asks it"; this item ships the facts and the defect rows. The
defect view does not wait on that same rule because it already has its reader here: the agreement
assertion below reads it as the store's half of the verdict, which is what makes these rows
checkable against the live probe on the day they land rather than on the day a diagnostic arrives
to consume them.

## Capture

- `JooqCatalog` gains a `NodeMetadataFacts` value record and a `nodeMetadataFactsOf(Table<?>)`
  accessor beside its four existing capture-facing siblings (`ColumnFacts`, `ForeignKeyFacts`,
  `IndexFacts`, `RoutineCallFacts`): the two form arms, the type-id value, the class FQNs, and the
  ordered entry names as a list with nulls preserved. Reflection and raw-`Object` interpretation
  stay at their one containment site; capture receives already-reduced values, per
  `CatalogFactCapture`'s own "both inputs arrive already reduced to values". A raw pair crossing
  the boundary would put a second reflection-interpretation site outside `JooqCatalog` (and
  package visibility cannot reach `...rewrite.capture` anyway). `doLookup`'s read half and the new
  reduction share the `getField` probe so "declares the constant" has one definition; validation
  stays where it is.
- `CatalogFactCapture` gains a `captureNodeMetadata` step in the table walk beside
  `captureColumns`, transcribing the reduced facts into the two relations and claiming each row's
  key through `sink.claim` exactly as `captureColumns` does, so a second walk over the same
  coordinate skips rather than collides. It reads the live `Table` the walk already holds; no new
  source, no new coupling, and no cross-corpus read.
- `clearSchemaSources` deletes the two new relations in round two, child before parent, before
  `sql_column` / `sql_table`.
- `FactCaptureAgreementTest` registers both base relations in the EQUALITY arm and
  `intent_node_metadata_defect` as DERIVED; the registration is what puts them under the
  warm/cold agreement sweep.

No behaviour changes: `JooqCatalog.nodeIdMetadata` and `nodeIdMetadataDiagnostic` keep their live
reflection path and their exact current answers, nothing reads the new rows, and generator output
is byte-identical. The reader arrives with the sibling item that makes nodehood a derivation.

## Tests

- Reduction unit seam: the raw-values-to-`NodeMetadataFacts` reduction is exercised with
  synthetic raw values, the same technique `JooqCatalogNodeIdMetadataTest` uses against
  `validateNodeIdMetadata` and sitting beside it for the same reason, covering each form
  (`STRING`/`NULL`/`OTHER`/`ABSENT` on both constants, empty string reduced as a value, null
  entry preserved, unresolvable name reduced as spelled).
- Pipeline capture coverage: the fixture tables `NodeIdFixtureGenerator` decorates (`bar` with
  `id_1`, `id_2` in constant order, the composite and custom-typeId cases) produce the expected
  rows; a stock fixture table produces none.
- Derivation coverage by stating rows directly through `SeededStore` in `graphitron-model`'s test
  tree, the module that declares the view (the `FieldColumnTableTest` convention): one case per
  defect arm, the multiple-defects-per-table grain, and the case-insensitive two-tier resolution.
  No reflection and no fixture classes needed, which is the point of having the rows.
- An agreement assertion over the fixture corpus: for every `sql_table` row of a source the live
  catalog covers, the store's verdict matches the reflection probe's, the probe called with the
  qualified `schema.table` spelling rather than the bare table name. `nodeIdMetadata` takes a SQL
  name, and its unqualified path answers `Absent` for a name two schemas share exactly as it does
  for one no catalog holds, so a bare `table_name` would let the gate agree by luck; `findTable`'s
  qualified path "never surface[s] `Ambiguous`" by construction. That the fixture corpus has no
  such collision today is hand-kept rather than structural (`init.sql` warns that
  `NodeIdFixtureGenerator.METADATA` "is keyed on the bare table name across every codegen
  execution"), which is exactly the sort of property a gate should not be resting on. Probe
  `Present` iff a metadata row exists with zero defect rows,
  and the stored typeId and entry names match the probe's resolution; probe `Malformed` iff a
  metadata row has a defect row and both forms are declared; probe `Absent` iff there is no row or
  a form is `ABSENT` (the store legitimately says more than the probe there, recording the
  half-declared pair the probe folds into silence). This is the check that keeps the derivation
  honest against the path it will eventually replace, and it is cheap because both sides are
  already in memory in a pipeline test.

## Roadmap entries

The sibling item `nodehood-derives-from-two-corpora` depends on this one and is already filed;
no new entries.

## Out of scope

- Nodehood itself, and the capture-time cross-corpus read that decides it today.
- Retiring `JooqCatalog.nodeIdMetadata`'s reflection path in favour of the rows.
- Reason text of any kind. This item stores no message prose; the defect vocabulary and witness
  columns are the fact base, and the eventual consumer composes its own text. In particular the
  live path's `toString`-based "got: ..." clause is not reproducible from deterministic columns
  and does not need to be, since `nodeIdMetadataDiagnostic` keeps composing its own text until it
  retires.
- Any other jOOQ metadata convention the generator publishes.
- The `intent_` family charter. Its opening ("The SDL strata stack's third layer") describes a
  jOOQ-only derivation no better than it already describes the graph-free residents sitting there
  now, so the mismatch predates this item and is not made worse by it. The `sql_` charter is
  amended here on a narrower ground: this item is what first puts a *relation* of generated-model
  facts under a family whose charter says database, where `sql_table.class_fqn` put only a column.
  Rewording `intent_`'s charter is a separate change with its own reviewer.
