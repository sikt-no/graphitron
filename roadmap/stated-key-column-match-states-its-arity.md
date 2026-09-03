---
id: R724
title: "The stated key-column match spends its ambiguity silently; make it state its arity"
status: Ready
bucket: cleanup
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-08-19
last-updated: 2026-09-03
---

# The stated key-column match spends its ambiguity silently; make it state its arity

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

**Dated 2026-09-03: one relation this item's prose names three times has been retired.** The
argMapping coordinate remodelling deleted `graphitron_argument_path_segment`, and with it the
`segment_name_upper` column that three passages below cite as the properly-folded authored side of
`intent_resolved_node_key_projection`'s crossing. The narrative about what R668 decided and why is
accurate as history and is left standing. What has changed for anyone acting on it is only where the
fold lives: the authored name is `graphitron_argmapping_entry.tail_name` and its fold is
`tail_name_upper` on that same relation, so the crossing still compares one stored operand against
one computed one exactly as R668 left it. Neither the count of unfolded operands nor the sibling
question changed, and this item's own subject, `intent_node_metadata_defect`, is untouched.

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

The Backlog title, "the store folds two catalog-produced names as a hedge; make the comparison exact",
is wrong in both halves as a result: the operands are not both catalog-produced, and exactness is what
the match falls back to rather than what it becomes. A third word went with them in this revision. The
fold is not a hedge at all. A hedge is a fold with no crossing under it, which is R702's subject; this
one bridges a spelled reference and a catalog reading and is the semantic the rule exists to serve. It
stays, unconditionally. What is wrong is that the match spends an ambiguity without saying so, and the
title and slug now name that instead.

One doc hazard this creates is handled in the Implementation below rather than discovered later.
After the column lands, both operands of the match live in the `sql_` family, and seven shipped
`_upper` comments carry the same sentence: "Two values of one family are compared exactly, and a
comparison that does want a fold on both sides reaches this column by joining ... on its key rather
than by having it forwarded through a derived view." They sit on `sql_table` (two), `sql_column`
(two) and `sql_constraint` (three), which is every `_upper` column the `sql_` family has. A reader
arriving at the new column with that rule in hand deletes it.

The rule is wrong as stated rather than merely incomplete, and the word doing the damage is *family*.
The discriminator was never the relation prefix: it is whether a value is a reading of the thing it
names or a name spelled at it. Both operands here are `sql_` and one of them is a spelled reference,
which is the split the missing foreign key already stands on. The family holds facts about generated
Java classes beside facts a database produced, `sql_node_metadata` sitting under `sql_` because it
shares a refresh unit with `sql_table` rather than because a database produced it. So the fix is to say
*catalog readings* where those comments say *values of one family*: one phrase in seven places, after
which the new column is obviously not an instance of the rule rather than an exception to be argued.

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

The relation deliberately does not carry which spelling matched, and deliberately replaces
`findColumn`'s Java-name-first precedence with exactness rather than reproducing it. What that costs
has to be stated exactly, because the tempting claim is the wrong one. The two rules agree on every
table whose column spellings, Java and SQL names taken together, are case-insensitively distinct:
exactly one column can answer at all there, so both rules return it. They diverge only where two
columns collide under the fold, which is the population this item exists for. They do *not* diverge
only on the inputs the arity columns call malformed: a table where one column answers exactly and a
second answers only folded has two candidates and one exact, resolves with no defect, and resolves to
the exactly-spelled column where `findColumn` would have taken the Java-name match. That is a
deliberate behaviour change on a colliding catalog rather than a divergence to apologise for, and the
statement of it belongs in the view comment; it is the load-bearing half of the relation's
justification. It exposes no `_upper` column either, so the rule that a derived view never forwards a
fold holds.

What earns the relation, given that it will have one view reading it. An earlier revision argued two
readers, R668's tier being the second; R668 has since settled and does not read it, so that argument
is dead and is replaced here rather than quietly left standing. One reader is the shape this schema
otherwise answers with a CTE, so the question is real.

The load-bearing answer is that a CTE would state the arity where nobody can read it. This schema's
rule for an ambiguity is not that a count exists but that the witnesses are rows;
`intent_name_matched_key_pair.unmatched_columns` says so outright, a consumer explaining a refusal
reading the rows behind a number above it, and `intent_bound_table` offering every candidate to an
editor is the same rule. A `KEY_COLUMN_CASE_AMBIGUOUS` row says a table is malformed and says nothing
about which columns collided, which is the question anyone who hits it asks first. Shipping a verdict
whose witnesses are sealed inside the view that reached it would reproduce, one level up, exactly the
defect this item exists to remove: an answer with nowhere to put what it knows.

The resolution being spelled more than once is a second argument and no longer the main one. It stays
spelled in Java by `JooqCatalog.findColumn`, which is R729's, and
`intent_resolved_node_key_column.column_name` forwards the stated spelling onward to
`intent_resolved_node_key_projection`, which R668 has landed, and to an emitter that eventually needs
a real column; that column's comment explicitly declines to perform the match, "settled convention
rather than this relation's rule". Lifting the defect view's own copy out takes the SQL count from two
to one rather than to zero, which is worth stating plainly rather than claiming a sweep.

*The gated arm.* `intent_node_metadata_defect`'s entry arm stops reading columns and reads the counts:
an entry resolves when `exact_candidates = 1`, or when `exact_candidates = 0 AND candidates = 1`.
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
the payoff, and it needs no edit to that view. `intent_inferred_node_type` shipped while this item was
in Spec and stands on the same conjunction, so a case-ambiguous key column also stops the type being
an inferred node. Both are the right answers and both are semantic changes this item owns.

Neither consumer needs editing, and the reason is worth stating so nobody goes looking. Both gate on a
table having no defect row at all, with no filter on which value, so a new value reaches both by
construction. The schema also deliberately accepts two spellings of that conjunction rather than
extracting it, each view's comment naming the other as its sibling spelling, so the count of
value-agnostic gates is already two and would keep growing. That is what makes the
propagation worth a pinned case rather than an observation: a gate that inherits a new value silently
is exactly the kind that nobody notices has changed.

*The Java enforcer.* The store's verdict needs one or it is a fact with no teeth.
`JooqCatalog.validateLookup` resolves entries through `findColumn`, which folds and takes `findFirst`
over the reflective field order, so without a Java-side change the store would report a table
malformed while the shipped generator quietly picks a column and emits against it. A store verdict the
build does not enforce is the drift this project treats as a smell.

The enforcer mirrors the relation rather than inventing a second rule, which is what stops the two
sides drifting apart the way the folded predicate already did. `validateLookup`'s `columnLookup`
argument stops returning one column and starts returning every column that answers the name, and
`validateLookup` computes the verdict from that list exactly as the defect arm computes it from the
counts: one exact candidate wins, a single folded candidate wins where none is exact, anything else is
`Malformed`. The list has the relation's grain, one entry per column and not per spelling, which falls
out of walking the table's fields once. An `Optional` cannot carry this, and that is the real reason
the signature moves rather than a convenience: the existing empty case already means no column answers
and composes the sentence that says so, and telling an ambiguity apart from it is what stops that
sentence being printed about a table whose columns do exist.

`findColumn` is not touched and neither are its other callers. They carry the same silent pick, and
that is R729, filed off this item's analysis. This item leaves one worked example of the rule rather
than a sweep, and depends on nothing.

## Implementation

* `graphitron-model/src/main/resources/no/sikt/graphitron/model/graphitron-model.sql`
** `sql_node_key_column` gains `column_name_upper VARCHAR GENERATED ALWAYS AS (UPPER(column_name))`,
   null exactly where `column_name` is. Its comment takes the shape the twenty-nine `_upper` columns
   across twelve relations already share (what the fold is for, that nothing writes it and nothing
   can) and names this crossing as a spelled reference meeting a catalog reading, which is the
   sentence that stops a reader deleting it as a within-family fold.
** `CREATE VIEW intent_stated_key_column_match` with the columns
   `(source_name, table_schema, table_name, position, column_name, case_exact, exact_candidates,
   candidates)`, the first four keying the entry and `column_name` being the *matched* column's SQL
   name rather than the entry's stated spelling, which the column comment has to say outright since
   the parent relation spells the other thing under that name. Joining `sql_node_key_column` to
   `sql_column` on the table key and
   `(c.column_name_upper = k.column_name_upper OR c.jooq_name_upper = k.column_name_upper)`, guarded
   by `k.column_name IS NOT NULL`. `case_exact` is a `CASE WHEN ... THEN TRUE ELSE FALSE END` over
   `c.column_name = k.column_name OR c.jooq_name = k.column_name`, the established idiom for a
   boolean view column; all three operands are non-null under that guard, so no arm evaluates to
   unknown. The two counts are windows over
   `PARTITION BY source_name, table_schema, table_name, position`, `exact_candidates` a
   `COUNT(CASE WHEN <exact> THEN 1 END)`. The view must be defined ahead of
   `intent_node_metadata_defect`, which reads it.
** The `KEY_COLUMN_UNRESOLVED` arm becomes a `NOT EXISTS` over the new relation, and a
   `KEY_COLUMN_CASE_AMBIGUOUS` arm is added reading the two counts. Both arms keep the
   `k.column_name IS NOT NULL` guard the entry arm carries today. The relation guards itself, so the
   guard looks redundant and is not: an arm that dropped it would report a null entry unresolved
   beside `KEY_COLUMN_ENTRY_NULL`, and `aTableExhibitingSeveralDefectsGetsARowForEachOfThem` is what
   catches it. The two arms are mutually exclusive by construction, which keeps the no-short-circuit
   grain the view comment promises. The four `UPPER` calls go.
** Comments. The new relation and its columns get theirs, including the sentence about declining
   `findColumn`'s precedence on purpose. `sql_node_key_column.column_name`'s comment currently ends
   "it matches the reading side: case-insensitively, against the generated Java name or the SQL name",
   which stops being true, and so do two neighbours that assert the same mirroring:
   `intent_resolved_node_key_column.column_name`'s "settled convention" sentence, and the javadoc on
   `NodeMetadataDefectTest.anEntryResolvesCaseInsensitivelyThroughEitherName`. All three are rewritten
   in the same commit, and the honest replacement is that fidelity to the predecessor is evidence
   rather than a specification: the store diverges from `findColumn` on the collision case deliberately,
   and the Java change below is what stops that being a divergence at all. On
   `intent_resolved_node_key_column.column_name` the edit is that one sentence only. R668 appended a
   paragraph to that comment explaining why the column exposes no fold, and is rewriting its reason
   under R731; leave the paragraph alone either way, whichever wording is standing when this lands.
** The seven `sql_`-family `_upper` comments (`sql_table` two, `sql_column` two, `sql_constraint`
   three) say "Two values of one family are compared exactly". Replace *values of one family* with
   *catalog readings* in each. It is the same claim said accurately: those columns' comparisons are
   between two readings, the new column's is not, and a reader who reaches the new column with the
   corrected rule in hand keeps it instead of deleting it. The sentence's second half, that a
   comparison wanting a fold on both sides joins the owning relation rather than having one forwarded,
   is untouched and still true. The page these seven got the word from is edited below, so the schema
   and the docs land the correction together.
** `intent_node_metadata_defect`'s `defect` comment enumerates "a closed vocabulary of ten" and its
   `position` comment says "the eight that are about a whole constant". One added arm stales both, off
   one edit, which is the argument for dropping the numbers rather than bumping them: two
   hand-maintained counts over one vocabulary, neither pinned by a test. Rewrite to enumerate without
   the number. Where the schema does carry a count it either pins it or restates it from one place,
   `intent_argmapping_pair`'s vocabulary of eight being cited by name at three other columns rather
   than recounted; this view does neither, so the number is inventory.
* `graphitron/src/main/java/no/sikt/graphitron/rewrite/capture/FactWrites.java`: a
  `sqlNodeKeyColumn` write function naming the five writable columns, registered in the writers map.
  A relation carrying a computed column cannot go through the generic every-column arm, which is why
  the column above forces this; `WrittenStatementCoverageTest` already gates it with no roster to
  update. The parents-first ordering in `FactSink.flush` already spans written and generic relations,
  so nothing there changes. Conflict behaviour is stated per relation rather than inferred from the
  name, so state it: `onDuplicateKeyIgnore`, matching `sqlColumn` beside it and the generic arm's
  behaviour for this relation today, `FactSink.claim` having already deduped the key.
* `graphitron/src/main/java/no/sikt/graphitron/rewrite/JooqCatalog.java`: the node-metadata lookup
  stops picking on an ambiguous fold. `columnLookup` changes from
  `Function<String, Optional<ColumnEntry>>` to a function returning every column that answers the
  name; `validateLookup` derives the verdict from that list the way the defect arm derives it from the
  counts; `doLookup` passes a private matcher over the standing table's fields, and is still the only
  production call site touched. The ambiguity gets a new `Malformed` reason naming it, distinct from
  the existing "does not belong to this table", which is the distinction the `Optional` could not
  carry. It matches the store's new defect value in meaning without either side citing the other.
  `findColumn` is untouched and so are its other callers; that residue is R729.
  `validateNodeIdMetadata`'s signature moves with `columnLookup`'s, which reaches
  `JooqCatalogNodeIdMetadataTest`'s nine call sites through the one `RESOLVE_ID_COLUMNS` constant they
  share, so the test churn is that constant plus the new cases.
* `docs/architecture/explanation/fact-model.adoc`, the fold-rule paragraph. Its last two sentences
  carve out this comparison as "the one comparison the rule declines to serve" and say "it goes away by
  becoming exact rather than by being stored". Both become false and both are deleted: the reason the
  page gives for declining this crossing is the reasoning the first section of this item refutes, so
  the carve-out goes rather than being reworded. Two further corrections to the same paragraph. Its
  crossing statement widens, from an author typing "a GraphQL or SDL identifier" to any hand-authored
  spelling, since the new fold's authored side is a Java expression in a generated or hand-written
  table class. And its second consequence, "a comparison between two values of one family mints
  nothing", is where the seven column comments got the word: it becomes a statement about two catalog
  readings, so the page and the comments carry the corrected rule in the same terms.
* The same paragraph, and the reason the deletion above is not the end of it. The page promises "views
  that hold no per-row case fold on any comparison the rule reaches", and treats the defect view as the
  sole survivor. That stopped being true before this item is implemented: R668 landed
  `intent_resolved_node_key_projection`, which folds `intent_resolved_node_key_column.column_name` per
  row against `graphitron_argument_path_segment.segment_name_upper`, and did not touch this page. So
  removing this item's survivor leaves the page asserting a clean sweep that is false, which is worse
  than the carve-out it replaces. State the surviving site, and state it as an open question rather
  than as a settled exception: the rule's second consequence forbids a derived view from exposing a
  fold, and `intent_resolved_node_key_column` hands out a spelling rather than a resolved column, so a
  consumer matching against it has nowhere to reach and folds at the crossing. Whether that relation
  should hand out a spelling at all is R731's, and the page should say so. Do not write the reason as
  a three-tier pick having no base relation to reach a fold through: `intent_spelled_table` reads arm
  folds across as many arms without trouble, and R668 has withdrawn that wording from its own body.
  The wording of the site is R668's; not leaving the paragraph false is this item's, being the item
  that edits it.

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
  the entry spelled as one of them. No defect, `candidates` two and `exact_candidates` one, and the
  exactly-spelled column is the one row carrying `case_exact`, which is the column a tier reading this
  relation takes. That last assertion is where the deliberate override of `findColumn`'s Java-name-first
  precedence is pinned rather than only described. This is the case the item exists for, and the one the
  old arm got right by accident rather than by verdict.
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
* The same for nodehood: a case-ambiguous key column takes the type out of `intent_inferred_node_type`,
  beside that relation's own cases. That relation is on trunk and the item that built it is Done, so
  the case is written unconditionally.
* The Java side, beside `JooqCatalogNodeIdMetadataTest`'s other `validateNodeIdMetadata` cases. Three,
  matching the store's: an ambiguous fold reports `Malformed` with the new reason rather than resolving;
  an exact candidate beside a folded one resolves to the exact column, which is where the Java and the
  store agree on overriding `findColumn`'s precedence; and a name no column answers still reports the
  existing "does not belong to this table" reason, pinning that the two messages stayed apart. The
  helper already takes a synthetic column lookup, so a collision is two entries the stub hands back
  together and needs no fixture class.

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
resolution instead of two, no vocabulary change, no consumer impact on the metadata tier or on
`intent_inferred_node_type`, the arity is already stated so a collision is visible to anyone who
looks, and the remaining work is a one-line predicate change plus its tests.

What must not be the fallback is landing the relation while leaving the defect view's own `NOT EXISTS`
in place. That would leave the fold spelled twice with nothing binding the two, which is the drift this
item exists to remove, arriving by the back door.

Deferring is a judgement about difficulty encountered, not a default. If it happens, the deferred half
is filed as its own Backlog item with the design above carried across intact, and this item's body
records what made it hard so the next session does not rediscover it.

## Relationship to R668, which has settled

R668 has answered the question this section used to hold open, and the answer removes an obligation
rather than adding one. Its earlier body stated the constraint that the `JOOQ_METADATA` tier of
`intent_resolved_node_key_column` must resolve an entry through the same predicate as the arm deciding
the entry is well-formed, "or the two can disagree about which entries resolved". Its landed stages
hold that agreement the way it was always held: the tier reads nothing but the defect view's verdict
and never resolves an entry itself.

What R668 needed was a different match, a trailing `argMapping` segment against a resolved key column,
and it closed that one without spelling a predicate twice either: the authored side is folded on its
own base relation as `graphitron_argument_path_segment.segment_name_upper`, the key-column side is
folded at the crossing in `intent_resolved_node_key_projection`, and the unknown-column defect is
stated as the absence of a projection row rather than as a repeated predicate. R668's body records
that it shipped a `column_name_upper` on `intent_resolved_node_key_column` and then reverted it, on
the rule that a derived view never forwards a fold, and `intent_resolved_node_key_column.column_name`'s
comment now carries the reason so nobody mints one again.

Two consequences for this item, one of them uncomfortable and stated rather than buried. R668 will not
read `intent_stated_key_column_match`, so the two-readers argument this item first made for the
relation is gone; what earns it now is argued above, on witnesses. And R668's landed DDL left this
item's arm alone exactly as its body promises: the four `UPPER` calls are in place and
`sql_node_key_column.column_name_upper` is unminted, both deliberately deferred here. The implementer
still re-reads the arm as it then stands rather than trusting this paragraph, R668 not yet being Done.

The `depends-on` field stays empty deliberately: this item is not blocked by R668, it just must not be
written against a stale copy of the arm.

## Follow-ups not in scope

* The parked extraction of the well-formed-stated-metadata conjunction into its own relation on
  `sql_table`'s key. Not filed as an item; it is recorded where it cannot rot, in the comments of the
  two views that carry the conjunction twice, each calling it the follow-on neither performs
  unilaterally. A different relation at a different grain from the pairing relation here, but a reader
  will pair them, so this item's view comment points at it rather than leaving the adjacency to look
  like an oversight.
* A schema-wide census of case-colliding columns on `sql_column`, which would answer whether any real
  consumer catalog exhibits the pathology at all and would serve R729's per-site reachability
  question. Deliberately not added here: the pairing relation already carries the witnesses for this
  view's own question, and a census with no reader today belongs with the item that needs it.
* The per-row fold in `intent_resolved_node_key_projection`, which is R731's and not this item's.
  R668 landed the authored side as `graphitron_argument_path_segment.segment_name_upper` and folded
  the key-column side at the crossing, having shipped an `_upper` on the reduction and reverted it;
  no view in this schema exposes one. R731 asks the layer below that, whether
  `intent_resolved_node_key_column` should hand out a spelling rather than a resolved column at all.
  This item's new column does not serve that site and must not be pointed at it, the value there being
  whichever tier won rather than the stated entry name; it is a precondition for one tier of R731 and
  nothing more. What this item owes the site is the page edit above.
* R729, `findColumn`'s silent pick at its other seventeen call sites, filed off this item's analysis.
  This item installs the rule at one of those sites because a store verdict about malformed metadata
  needs an enforcer or it has no teeth; generalising it needs a per-site reachability call this item
  has no reason to make.

Two neighbouring items must not settle a convention opposite to this one, and they are not the same
item as each other. R729 is the Java-side sibling over *this* crossing, a spelled name meeting a
catalog reading, where the fold stays and only the silent pick goes. R702 is over the other one, two
catalog readings compared under a fold that bridges nothing, where the fold itself goes. Neither is a
dependency. This item's convention, stated so both can agree or argue with it: a fold is minted where
a spelled reference meets a reading and nowhere else, exactness is what a fold falls back to when more
than one thing answers, and an ambiguity a fold would have spent silently is a stated row.
