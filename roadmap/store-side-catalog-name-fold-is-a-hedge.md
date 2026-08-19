---
id: R724
title: "The store folds a stated key-column name as a hedge; make the match state its arity"
status: Spec
bucket: cleanup
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# The store folds a stated key-column name as a hedge; make the match state its arity

One view in the fact schema still folds case per row. `intent_node_metadata_defect`, in its
`KEY_COLUMN_UNRESOLVED` arm, matches `sql_column.jooq_name` and `sql_column.column_name` against
`sql_node_key_column.column_name` under `UPPER` on both sides, four per-row calls. The match is
buried inside a `NOT EXISTS`, so it answers with a boolean and has nowhere to put an ambiguity: on a
table carrying two columns that differ only by case, the entry is reported resolved and which column
resolved it is decided by whichever the join reached. This item mints the folded column the ordinary
rule owes this crossing, lifts the match out into a relation that states its own arity the way every
other folded resolution in the schema does, and lets the arity decide, exact spelling winning where
more than one column answers and an irreducible ambiguity becoming a stated defect instead of a
silent pick.

## What the tree settles about the open question

The Backlog body left one question open: whether the key-columns constant's spelling is
catalog-canonical in the same sense the crawler's own column reading is. If it were, the fix would be
deleting four `UPPER` calls; if it were not, `sql_node_key_column.column_name` would earn a folded
companion under the ordinary rule. The tree answers it, and the answer is the second branch.

`JooqCatalog.reduceNodeMetadata` fills an entry name with `field.getName()` on the entries of the
`Field<?>[]` the constant holds, and does not resolve that field against the table it is standing on.
`JooqCatalog.columnFactsOf` fills `sql_column.column_name` with `col.getName()` off the table's own
generated field constants and `jooq_name` with the reflective Java field name. So the two strings are
byte-identical exactly when the constant references the table's own field constants, which is what
Sikt's `KjerneJooqGenerator` emits, and are unrelated otherwise: an entry holding a `DSL.field("id")`
or a field belonging to another table yields whatever spelling that field carries. The store records
the entry as stated precisely so it can hold a name belonging to no column, which is the whole reason
`sql_node_key_column` declines a foreign key to `sql_column`.

The argument form matters, because the common case points the other way. Almost always the constant
does reference the table's own field constants and the two strings are identical, and reasoning from
that would settle the question wrongly. The case where the derivation fails is what decides it, and one
example is enough. The schema reached the same answer before the question was asked: that foreign key is
declined because "the constant spells a column by name and may spell one the table does not have", which
puts the column on the spelled-reference side of the structural-versus-spelled split. A spelled
reference is not a catalog reading.

An entry name is therefore a stated name, `sql_node_key_column.column_name` earns a `column_name_upper`
companion under the ordinary rule, and no amendment to where folds are minted is needed.

Both halves of the Backlog title are wrong as a result, and it is corrected in this revision. Both
operands were not catalog-produced, and exactness is what the match falls back to rather than what it
becomes.

One doc hazard this creates has to be handled rather than discovered later. After the column lands,
both operands of the match live in the `sql_` family, and three shipped comments
(`sql_column.column_name_upper`, `sql_table.table_name_upper`, `sql_constraint.constraint_name_upper`)
instruct the reader that a within-family comparison mints nothing and reaches a fold by joining the
owning relation instead. A reader arriving at the new column with that rule in hand deletes it. The
discriminator is not the family prefix but which corpus authored the spelling, and this family already
holds facts about generated classes rather than SQL's own vocabulary, `sql_node_metadata` being under
`sql_` because it shares a refresh unit with `sql_table` and not because a database produced it.

Two further facts the design leans on. `FactSink.claim` keys its dedupe set on a plain `HashSet` of
the key values, so `"Id"` and `"ID"` are two distinct `sql_column` rows and the store can hold the
collision at all. And the reading side this view mirrors, `JooqCatalog.findColumn`, matches the Java
name case-insensitively and then the SQL name case-insensitively, each with `findFirst`, so on a
colliding table it silently picks by reflective field order. That is the behaviour the store is meant
to replace, not the behaviour it is meant to reproduce.

## Design

This is not a new axis for the fold rule, and framing it as one would be both wrong and a weaker
argument. The fold stays entirely unconditional: every folded match becomes a row, exactly as
`intent_bound_table` makes every candidate table a row. What is conditional is a verdict a consumer
computes from arity, which is the discipline the schema already applies wherever ambiguity has a
relation to sit on. Ambiguity is rows, never a decline, and the count says so;
`intent_bound_table.candidates` and `intent_name_matched_key_pair.unmatched_columns` are that rule
written down. This comparison never got the arity column because it was buried in a `NOT EXISTS`
where arity had nowhere to surface, and that is the whole defect. No general statement about
conditional folds is needed, and none should be added.

The schema has done this shape before at another site, which is the precedent to follow rather than
invent against: `intent_field_reference_step_hop` already encodes SQL-name-wins precedence over the
jOOQ name in SQL, through a `NOT EXISTS`, and carries `key_matched_by` as a closed-vocabulary column.

Three pieces:

*The pairing relation.* A new view `intent_stated_key_column_match` at grain one row per (key-column
entry, `sql_column` row it could be). The grain is per column and not per (column, spelling) pairing:
a column whose SQL name and Java name are both the sought name must be one row and not two. The join
condition being a boolean rather than a row source gives that for free, so no `DISTINCT` is involved.
Every exact match is also a folded match, so the folded predicate is the join and exactness is a
projection over it, never a second arm.

Arity is stated as columns, not left to the reader: `candidates` (how many columns answer at all) and
`exact_candidates` (how many answer on an exact spelling), both window counts over the entry's
partition, with the inherited-window caution the schema's other arity comments carry. A boolean would
not be enough, and the case that proves it is reachable: on a quoted-identifier catalog, column A can
be exactly `Id` on its `jooq_name` while column B is exactly `Id` on its `column_name`. A
some-row-matched-exactly test calls that resolved and hands the pick back to whoever reaches a column
first, which is precisely the failure this item exists to stop. Two counts express it; one flag
cannot.

The relation deliberately does not carry which spelling matched and deliberately does not reproduce
`findColumn`'s Java-name-first precedence. That precedence is a rule for picking one column, and the
only inputs on which it would change an answer are exactly the ones the arity columns declare
malformed, so omitting it is total rather than deferred. That sentence belongs in the view comment; it
is the load-bearing half of the relation's justification. It exposes no `_upper` column either, so the
rule that a derived view never forwards a fold holds.

What earns the relation is not this one consumer, which would be the sanctioned CTE case. It is that
the entry-to-column resolution is already spelled twice and forwarded a third time: the defect view's
`EXISTS`, `JooqCatalog.findColumn`, and `intent_resolved_node_key_column.column_name`, whose comment
explicitly declines to perform the match ("settled convention rather than this relation's rule") and
forwards the stated spelling onward to `intent_resolved_node_key_projection` and an emitter that
eventually needs a real column. Two spellings of one resolution agree exactly until one of them
changes.

*The gated arm.* `intent_node_metadata_defect`'s entry arm reads columns instead of counting: an entry
resolves when `exact_candidates = 1`, or when `exact_candidates = 0 AND candidates = 1`.
`KEY_COLUMN_UNRESOLVED` keeps its meaning exactly, no column answers at all.

*The new defect value.* `KEY_COLUMN_CASE_AMBIGUOUS`, the complement: more than one exact candidate, or
none exact and more than one folded. One arm covers both shapes because the arity columns make them
one condition. It is a distinct value rather than a reading of `KEY_COLUMN_UNRESOLVED` because that
value's meaning is already fixed by the message the live probe composes from it, `references column 'X'
which does not belong to this table`, and on a colliding table that sentence is false: case-variant
columns do exist and the entry names none of them exactly.

That value changes what well-formed metadata means, and that has to be stated rather than treated as a
message refinement, because well-formedness has consumers. `intent_resolved_node_key_column`'s
`JOOQ_METADATA` tier gates on a table having no defect row at all, so a case-ambiguous entry stops that
tier resolving and falls through rather than resolving against a column picked by field order. That is
the payoff, and it needs no edit to that view. R711 is Ready and builds `intent_inferred_node_type` on
the same conjunction, so once it lands this value also narrows which types are nodes. Both are the right
answers and both are semantic changes this item owns.

*The Java enforcer.* The store's verdict needs one or it is a fact with no teeth.
`JooqCatalog.validateLookup` resolves entries through `findColumn`, which folds and takes `findFirst`
over the reflective field order, so without a Java-side change the store would report a table
malformed while the shipped generator quietly picks a column and emits against it. A store verdict the
build does not enforce is the drift this project treats as a smell. The change is contained to the
node-metadata call site: a lookup that applies exact-first and reports an ambiguous fold as a
`Malformed` reason rather than picking, leaving `findColumn`'s other callers alone. Those other callers
are R702's census and stay R702's, so this item still depends on nothing.

## Implementation

* `graphitron-model/src/main/resources/no/sikt/graphitron/model/graphitron-model.sql`
** `sql_node_key_column` gains `column_name_upper VARCHAR GENERATED ALWAYS AS (UPPER(column_name))`,
   commented in the same terms as the eleven `_upper` columns already there, naming the crossing as
   stated-name-meets-catalog-name.
** `CREATE VIEW intent_stated_key_column_match` with the columns
   `(source_name, table_schema, table_name, position, column_name, case_exact, exact_candidates,
   candidates)`, joining `sql_node_key_column` to `sql_column` on the table key and
   `(c.column_name_upper = k.column_name_upper OR c.jooq_name_upper = k.column_name_upper)`, guarded
   by `k.column_name IS NOT NULL`. `case_exact` is a `CASE WHEN ... THEN TRUE ELSE FALSE END` over
   `c.column_name = k.column_name OR c.jooq_name = k.column_name`, the established idiom for a
   boolean view column; all three operands are non-null under that guard, so no arm evaluates to
   unknown. The two counts are windows over
   `PARTITION BY source_name, table_schema, table_name, position`, `exact_candidates` a
   `COUNT(CASE WHEN <exact> THEN 1 END)`. The view must be defined ahead of
   `intent_node_metadata_defect`, which reads it.
** The `KEY_COLUMN_UNRESOLVED` arm becomes a `NOT EXISTS` over the new relation, and a
   `KEY_COLUMN_CASE_AMBIGUOUS` arm is added reading the two counts. The two arms are mutually
   exclusive by construction, which keeps the no-short-circuit grain the view comment promises. The
   four `UPPER` calls go.
** Comments. The new relation and its columns get theirs, including the sentence about declining
   `findColumn`'s precedence on purpose. `sql_node_key_column.column_name`'s comment currently ends
   "it matches the reading side: case-insensitively, against the generated Java name or the SQL name",
   which stops being true, and so do two neighbours that assert the same mirroring:
   `intent_resolved_node_key_column.column_name`'s "settled convention" sentence, and the javadoc on
   `NodeMetadataDefectTest.anEntryResolvesCaseInsensitivelyThroughEitherName`. All three are rewritten
   in the same commit, and the honest replacement is that fidelity to the predecessor is evidence
   rather than a specification: the store diverges from `findColumn` on the collision case deliberately,
   and the Java change below is what stops that being a divergence at all.
** `intent_node_metadata_defect`'s `defect` comment enumerates "a closed vocabulary of ten" and its
   `position` comment says "the eight that are about a whole constant". Both counts go stale. Rewrite
   to enumerate without the number rather than bumping it: the schema already carries two arm counts
   that disagree with each other, and a third is worse than none. A number belongs here only if a named
   test pins it.
* `graphitron/src/main/java/no/sikt/graphitron/rewrite/capture/FactWrites.java`: a
  `sqlNodeKeyColumn` write function naming the five writable columns, registered in the writers map.
  A relation carrying a computed column cannot go through the generic every-column arm, which is why
  the column above forces this; `WrittenStatementCoverageTest` already gates it with no roster to
  update. The parents-first ordering in `FactSink.flush` already spans written and generic relations,
  so nothing there changes. Conflict behaviour is stated per relation rather than inferred from the
  name, so state it: `onDuplicateKeyIgnore`, matching `sqlColumn` beside it and the generic arm's
  behaviour for this relation today, `FactSink.claim` having already deduped the key.
* `graphitron/src/main/java/no/sikt/graphitron/rewrite/JooqCatalog.java`: the node-metadata lookup
  stops picking on an ambiguous fold. `validateLookup`'s `columnLookup` argument is where this lands, so
  the change is a lookup that tries exact spellings first and reports an ambiguous fold as a `Malformed`
  reason, wired in at `doLookup`'s call site only. `findColumn` itself and its eight other callers are
  R702's census and are not touched here. The new reason string names the ambiguity, matching the store's
  new defect value in meaning without either side citing the other.
* `docs/architecture/explanation/fact-model.adoc`, the fold-rule paragraph. Its last two sentences
  carve out this comparison as "the one comparison the rule declines to serve" and say "it goes away by
  becoming exact rather than by being stored". Both become false, and the edit is a deletion rather than
  a rewrite: the rule ends up with no exception at all, which is a strengthening of the page. The
  paragraph's crossing statement also has to widen, from an author typing "a GraphQL or SDL identifier"
  to any hand-authored spelling, since the new fold's authored side is a Java expression in a generated
  or hand-written table class. Separately, the paragraph calls this the single surviving per-row fold,
  which stops being true for a different reason: `intent_resolved_node_key_projection` landed after the
  paragraph was written and carries two more. Say so and attribute them rather than dropping the count.

Nothing generated needs regenerating by hand. The schema reference under
`docs/architecture/reference/schema/` is rendered from the DDL and its comments by the docs module's
`render-schema-reference` execution, so the new relation documents itself.

## Tests

`graphitron-model/src/test/java/no/sikt/graphitron/model/intent/NodeMetadataDefectTest.java` is the
home; it seeds rows directly, which is what lets a colliding catalog be stated in two lines rather
than shipped as a broken generated class.

* Every existing case in that class passes unchanged, and that is an assertion about the design
  rather than a hope: the file's `anEntryResolvesCaseInsensitivelyThroughEitherName` resolves
  `FILM_ID` exactly on the Java name, `release_year` exactly on the SQL name, and `ReLeAsEyEaR` on a
  fold that exactly one column answers. Confirm this by running the class before touching it and
  after, not by reading the diff.
* A colliding table where the entry matches one column exactly: two columns differing only by case,
  the entry spelled as one of them. No defect, `candidates` two and `exact_candidates` one. This is the
  case the item exists for, and the one the old arm got right by accident rather than by verdict.
* A colliding table where the entry matches neither exactly: `KEY_COLUMN_CASE_AMBIGUOUS` at that
  position, and no `KEY_COLUMN_UNRESOLVED` beside it, so the two arms are pinned as exclusive.
* Two exact candidates: column A exact on its `jooq_name`, column B exact on its `column_name`, the
  same sought name. `exact_candidates` two, ambiguous. This is the case a boolean could not express and
  the reason the relation carries counts.
* An entry naming no column at all still yields `KEY_COLUMN_UNRESOLVED`, which pins that the new arm
  did not absorb the old one.
* The collision across the two spellings rather than within one: column A's `jooq_name` folds onto the
  same value as column B's `column_name`, neither exactly. Two candidates, none exact, ambiguous.
* One case on the grain itself: a column whose SQL name and Java name are both the sought name is one
  row and `candidates` one, so a single column answering twice cannot trip the ambiguity arm.
* A case pinning that a case-ambiguous entry suppresses `intent_resolved_node_key_column`'s
  `JOOQ_METADATA` tier, which is where the defect value earns its keep. Belongs in
  `ResolvedNodeKeyColumnTest` beside the tier's other cases rather than in this class.
* The Java side, beside `JooqCatalog`'s other `validateNodeIdMetadata` cases: an ambiguous fold reports
  `Malformed` rather than resolving. That factoring already takes a synthetic column lookup, so the
  collision is two entries in a map and needs no fixture class.

All the store cases are seeded rows, not crawler fixtures. The class javadoc already argues why:
reaching these states through a crawler would mean shipping a broken generated class into the fixture
tree for each of them, where stating them takes a line. Said here so the implementer does not try to
manufacture a quoted-identifier collision inside the sakila catalog.

`WrittenStatementCoverageTest` covers the write function without a new case: it enumerates the
relations the catalog reports a computed column for and round-trips a distinct value per writable
column.

## Deferral

If the exact-first decision turns out hard, what defers is that predicate plus the new defect value plus
the Java enforcer, together, and what lands is the folded column, the pairing relation with both arity
columns, and the entry arm rewritten to read it with today's semantics: a defect exactly when
`candidates = 0`. That intermediate is coherent on its own terms. There is one spelling of the
resolution instead of two, no vocabulary change, no consumer impact on the metadata tier or on R711,
the arity is already stated so a collision is visible to anyone who looks, and the remaining work is a
one-line predicate change plus its tests.

What must not be the fallback is landing the relation while leaving the defect view's own `EXISTS` in
place. That would leave the fold spelled twice with nothing binding the two, which is the drift this
item exists to remove, arriving by the back door.

Deferring is a judgement about difficulty encountered, not a default. If it happens, the deferred half
is filed as its own Backlog item with the design above carried across intact, and this item's body
records what made it hard so the next session does not rediscover it.

## Relationship to R668, which lands first

R668 is In Progress and shares this exact predicate on purpose. Its body states the constraint: the
`JOOQ_METADATA` tier of `intent_resolved_node_key_column` has to resolve an entry through the same
predicate as the arm that decides the entry is well-formed, "or the two can disagree about which
entries resolved". Today that agreement is held by having one copy in the defect view and the tier
reading nothing but its verdict, and R668 is deciding how the tier resolves.

That makes the pairing relation the thing R668 wants rather than a complication for it. Instead of two
copies of a folded predicate that have to be kept identical by review,
`intent_stated_key_column_match` is one relation both read, and it carries the matched column's own
spelling, which is what a tier projecting a resolved column needs and what the stated entry name is
not. Whether R668's tier takes that spelling is R668's call; this item's job is to make it available
and to stop the predicate existing twice.

Ordering, and the obligation it puts on this item: R668 lands first and is partway there. Its
segment-grain DDL work is on trunk already and left this arm untouched, all four `UPPER` calls still in
place, so as of this revision nothing extra is owed. That can still change before R668 reaches Done. If
it ends up shipping a second copy of the folded predicate, this item converts both readers onto the
relation rather than leaving one behind; the implementer re-reads the arm as it then stands rather than
trusting this paragraph.

The `depends-on` field stays empty deliberately: this item is not blocked by R668, it just must not be
written against a stale copy of the arm.

## Follow-ups not in scope

* R711's parked extraction of the well-formed-stated-metadata conjunction into its own relation on
  `sql_table`'s key. A different relation at a different grain from the pairing relation here, but a
  reader will pair them, so this item's view comment points at it rather than leaving the adjacency to
  look like an oversight.
* A schema-wide census of case-colliding columns on `sql_column`, which would answer whether any real
  consumer catalog exhibits the pathology at all and would serve R702's per-site reachability
  question. Deliberately not added here: the pairing relation already carries the witnesses for this
  view's own question, and a census with no reader today belongs with the item that needs it.
* The two per-row `UPPER` calls in `intent_resolved_node_key_projection`, against
  `graphitron_argument_path_segment.segment_name`. Not a hedge, an authored `argMapping` segment
  meeting a catalog name, so they want folded columns under the ordinary rule. R668 owns them and its
  body already says so.

R702 is the Java-side sibling over the same hedge at nine call sites. Neither item depends on the
other and the two must not settle opposite conventions. This item's convention, stated so R702 can
agree or argue with it: exactness is what a fold falls back to, and an ambiguity a fold would have
spent silently is a stated row.
