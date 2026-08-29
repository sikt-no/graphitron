---
id: R877
title: "The graphitron-model house cleaning party: relation descriptions are argument transcripts, so nobody reads them and the same fact gets a second relation"
status: Spec
bucket: cleanup
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-08-29
last-updated: 2026-08-29
---

# The graphitron-model house cleaning party: relation descriptions are argument transcripts, so nobody reads them and the same fact gets a second relation

A relation's comment is supposed to tell you what one of its rows is. Most of them argue a case
instead. The reader who wanted a definition gets an essay, stops reading, and adds a new relation for
a fact the store already holds.

## The measurements

Taken over the 2563 `COMMENT ON` statements in `graphitron-model.sql`.

**The first sentence is fine.** A relation comment's opening sentence is its grain statement, and
`GrainSentence` already extracts it as one. Those run a median of 9 words. One of 276 is longer than
40 words. Whatever is wrong here, it is not the opening line.

**The rest is not.** Relation comments run a median of 117 words, a 90th percentile of 630, and a
maximum of 1431. The eight longest are between 900 and 1431 words each.

**There are almost no examples.** Four comments out of 2563 contain the words "for example" or "e.g.".

**Length does not track difficulty.** Comparing each relation's comment length against the size of
the statement it expands into, the correlation is weak, and the exceptions break it open.
`intent_field_scope_table` has the longest comment in the store, 1431 words, over one of the smaller
structures. `intent_condition_membership` describes a structure thirty-seven times larger in two
thirds the words.

## What the long comments actually contain

Not descriptions. Transcripts of arguments. `intent_field_scope_table`'s comment carries three essays
on shapes that were tried and rejected, timing figures from a fixture, a defence against a projection
two earlier authors argued for, and a clause disambiguating it from a relation with a similar name.
Every one of those is useful to somebody. None of them answers "what is one row of this".

## Why that produces extra relations

The chain is short. A description nobody finishes reading is a description nobody consults. A store
whose descriptions nobody consults cannot tell an author that the fact they are about to capture is
already captured somewhere else. So they capture it again, under a slightly different name.

The store has worked examples of the result:

- Six relations spell a reference to a table as `table_ref`. A seventh, `graphitron_routine`, spells
  the identical fact `routine_ref`. The mechanical detector that hunts for this defect missed that
  seventh relation for that reason alone.
- Ten views reconstruct a fact by unioning an argument-site relation with its field-site twin, for
  example `graphitron_argument_condition` with `graphitron_field_condition`. Each pair is one fact
  written at two coordinates with no relation naming it once. `SupertypeSignatureGateTest` counts
  them.
- Names diverge inside a single set. The four reference-step relations are
  `graphitron_field_reference_step`, `graphitron_argument_reference_step`,
  `graphitron_reference_for_step` and `graphitron_argument_reference_for_step`. Two carry a site
  prefix and two do not, which hides the fact that they are four spellings of one thing.

## Why the transcripts ended up in the DDL

Almost every line of this file was written by a Claude model, and Claude models elaborate when
nothing stops them. That is the cause. It is worth stating plainly because it decides what the fix
is: not better intentions, but a constraint that is present while the writing happens.

**The grain sentence is the control case, and it settles this.** The same models, writing about the
same relations, in the same file, produce a 9-word median where a convention and an extractor
(`GrainSentence`) treat the first sentence as a thing with a job, and a 117-word median from the
second sentence onward where nothing does. Same author, same subject, an order of magnitude apart,
and the only difference is whether something was watching.

That also rules out the more flattering explanation, which an earlier draft of this item gave and
which is wrong. It said the comments absorb rationale because a roadmap item is deleted at Done and
an author has nowhere durable to put a week of reasoning. If that were the pressure it would bear on
the first sentence too, and it does not. It would also produce writing that is misplaced but
compact, where what is actually there is diffuse. Rationale does belong in `docs/architecture/`
rather than in a relation comment, and that is worth doing, but it is a separate correction and not
the reason this file is 119530 words of comment.

## The scale, since the fix has to be proportionate

The file carries 119530 words across 2563 comments. 66628 of those words are on the 276 relations;
the rest are on columns and indexes. 115 relation comments are over 200 words, 90 are over 300, 61
are over 400, and 30 are over 600.

## What finishing this would look like

Not decided here. A Backlog item states the problem. Sketched only so the next reader knows the
shape:

- **A constraint that is present while the writing happens**, which is where the leverage is. Two
  kinds. A prompt: a rule in `CLAUDE.md` about this file specifically, since the writing-style
  section there governs prose and says nothing about relation comments. And a gate, which is what
  makes the prompt stick, because a rule nothing checks is a rule that decays.
- **A ratchet rather than a rewrite**, on the pattern this repo already uses for read cost. Pin the
  total and the maximum, both of which can only fall: a relation comment may not exceed the longest
  one that exists today, and the file's total may not exceed today's. Neither forbids a necessary
  paragraph; both forbid a new essay. The starting values are in the section above.
- A relation comment defines the row and gives one example of one, before it argues anything.
  "Carries an example" is checkable and today's answer is 4 of 2563.
- Rationale, measurements and rejected alternatives move to `docs/architecture/`, which outlives the
  item that produced them. Worth doing on its own merits; not the cause of the sprawl.
- Names inside one subtype set agree with each other.

## The candidate direction: documentation as data, not as comments

Move relation documentation out of `COMMENT ON` and into a `meta_` relation with columns. The store
already has the shape for it and this would not be a new mechanism.

**What it buys that a word cap cannot.** A comment is one blob, so every constraint over it is a
constraint over the whole thing at once, and the only property a blob has is its length. A relation
has columns, and each column can be constrained separately. That is what makes "define the row and
give an example of one before you argue anything" expressible at all: `grain` and `example` become
NOT NULL columns with their own length checks, and rationale becomes a column that is allowed to be
long or is not there at all. No cap over a blob can say that, which is why the cap invites the same
sprawl written more tersely.

**A `CHECK` is a stronger instrument than a test here.** It fires when the schema is applied, which
is at every store boot in every test, and cannot be skipped, disabled or forgotten. That is the
property the section above says is missing: a constraint present while the writing happens rather
than a verdict afterwards.

**Three things it does not buy, because they already exist.** Coverage is already gated:
`FactSchemaGateTest.commentCoverageIsTotal` reads `INFORMATION_SCHEMA` and fails on any relation or
column with no comment, so a test comparing the catalog against a meta relation is the same check
relocated, not a new one. The documentation pages already read both sources:
`SchemaReferencePages` renders per-object prose from the `COMMENT ON` text beside the `meta_family`
rows and interpolates both verbatim. And `StoreProse` already treats comment bodies and `meta_`
character values as one corpus, deliberately total over character-typed values so that "a later prose
column joins the corpus by existing rather than by being remembered". The seam this proposal needs
was built.

**The split worth considering rather than a wholesale move.** Keep `COMMENT ON` carrying the grain
sentence and nothing else, and move everything from the second sentence onward into the meta
relation. Three reasons. The grain sentences are the part that already works, so moving them buys
nothing and risks something. A comment is what a SQL client shows inline, and a store whose relations
describe themselves to `\d+` is worth keeping. And the split is exactly where the measurements say
the problem starts, which makes the migration mechanical: truncate each comment at its first
sentence, and the remainder is the meta row's first draft.

**What it does not fix.** The `meta_` rows are inserted by the same DDL file, so the file does not
get smaller and the author is still writing in the same place. The gain is the shape and the
enforcement, not the location. Anyone selling this as "the DDL gets shorter" has misread it.

## The plan: grain and owner become declared data

Two new relations in the `meta_` family. `meta_grain` rosters the grains this store knows about.
`meta_relation` gives every relation in the schema a row saying which grain it is at, who owns it,
what one of its rows is, and one example of one.

### What a grain is

A grain is what one row of a relation is about. `graphql_field` is at the grain of one field of one
type in one graph; `sql_column` is at the grain of one column of one table. The store already has
this concept in two places without naming it as data: a relation's primary key is its grain expressed
as columns, and the first sentence of its comment is its grain expressed as prose, which
`GrainSentence` already extracts on that convention.

**The grain roster is not guesswork, because the keys already state most of it.** Across the 161
tables in the schema, 141 declare a primary key and those keys fall into a short head:

[cols="1,4,1"]
|===
| tables | key shape | what one row is

| 18 | `graph_name, type_name, field_name` | a field of a type
| 12 | `graph_name, type_name` | a type
| 9 | `graph_name, ordinal` | a positioned item of a graph
| 7 | `graph_name, type_name, field_name, argument_name` | an argument of a field
| 6 | `graph_name` | a graph
| 5 | `graph_name, type_name, value_name` | an enum value
| 4 | `graph_name, type_name, field_name, position` | a step within a field's path
| 3 | `source_name, table_schema, table_name` | a database table
|===

Twelve key shapes cover 57% of the tables. The tail is long, 76 distinct shapes in all, and part of
that tail is the problem rather than the domain: a shape used once may be a genuine grain or may be a
relation that never decided what it was about.

### The finding that decides how seriously to take this

**The twenty tables in this schema with no declared primary key are exactly the twenty registered
materialization targets. Not mostly. Exactly, both directions.**

That is not a coincidence and the register gate already half explains it: most of those grains include
a meaningfully nullable column, and H2 refuses a primary key over one, which is why each target has
to declare an index instead. Read the other way round it says something sharper. A relation that
cannot state its grain as a key is a relation whose grain is conditional, one row per this except
when that, in which case per something else. Conditional grain is what makes a relation impossible to
key, hard to index, and expensive to read, and every one of them ended up with a registration in
front of it.

So declaring grain as data is not only a documentation change. It is the check that would have made
those twenty visible as modelling defects before each became a materialization decision.

### `meta_owner`

One row per fact gatherer, keyed on its name, and each row names the Java class that is the gatherer.
Naming the class is what stops this from becoming a second vocabulary: the compiler and the Javadoc
reference gate already hold a `{@link}` to a class that exists, so an owner roster that points at
`SdlFactCapture` cannot quietly outlive it. Columns: the name, the class, the corpus it reads, and
the order it runs in.

**The last owner is a real gatherer and not a null.** The derivation gatherer runs after every corpus
gatherer has finished, which is the earliest point at which a rule crossing families has all its
inputs. It owns the `intent_` relations, and it owns materializing the grain tables the views and the
queries above it stand on. That is what `meta_materialize` becomes: not a mechanism of its own
standing outside the ownership rule, but one owner's refresh plan, the same kind of thing any other
gatherer would hold for its own family.

### `meta_grain`

One row per grain, keyed on its name. Columns: the name; a one-sentence statement of what one
instance is, length-checked; the canonical key shape as a column list; and the corpus the grain lives
in, constrained to the corpora the store actually reads.

### `meta_relation`

One row per relation, keyed on the relation name, covering views as well as tables.

- `grain_name`, a foreign key into `meta_grain`.
- `owner`, a foreign key into `meta_owner`. NOT NULL, because everything has an owner.
- `grain_text`, the one sentence saying what one row is. Length-checked.
- `example`, one example row stated concretely. NOT NULL and length-checked.
- `rationale`, everything the comment currently says from its second sentence onward. Not
  length-checked at first, for the migration reason below.

**The owner column is where this meets the ownership rule on the fact model page.** That page states
that a view reading one family belongs to that family and is owned by that family's gatherer, and
that a rule whose facts cross families is owned by the gatherer that runs last. It also states, in its
own enforcement line, that nothing checks this. The `owner` column is what makes it checkable: a
relation owned by the last gatherer whose captured facts all sit in one family is the misfiling the
rule names, and that is a query rather than a review.

**Owner and grain must agree about the corpus, which is a second free check.** A gatherer reads one
corpus and a grain lives in one corpus, so a relation whose grain is a catalog grain and whose owner
is the SDL gatherer is a capture-time cross-corpus read. `CaptureCorpusIsolationTest` already catches
that dynamically by capturing twice; this would state it declaratively, and the two disagreeing is
worth knowing about either way.

### What this is already partly doing, which is why it should land clean

- `meta_materialize` is already a per-relation registry in this family, with a reason column and rows
  supplied by an `INSERT` in the same file. `meta_relation` is that pattern with a different subject.
- `meta_relation_family` already gives every relation a family, derived from its prefix.
- `meta_prefixless_relation` already carries per-relation exceptions to a rule.
- `GrainSentence` already extracts a grain sentence, so the convention exists and only its storage is
  changing.
- `StoreProse` already reads `meta_` character values as part of the store's prose corpus, and does
  it total over character-typed columns rather than by an enumerated list, so the new prose columns
  join the checked corpus by existing rather than by being remembered. That was built deliberately.
- `SchemaReferencePages` already renders from the `meta_` rows beside the comments, so the
  documentation pages get simpler by selecting columns instead of parsing prose.

### What the `COMMENT ON` becomes, which is the part that fixes the sprawl

**The comment stops being the documentation and becomes a label.** One or two sentences: name the row,
give an example. Nothing else. That is `grain_text` and `example` and no third thing.

This matters more than it sounds, because it retires the fix this item started with. An earlier
section proposed a word cap on comments, ratcheted down from today's 1431-word maximum. That was
treating the symptom. If the comment's job is to name the row and show one, it is two sentences by
construction and no cap is needed to make it so; a cap would only be measuring how far the file still
is from a job nobody had stated. **Consider the word-cap sketch superseded by this section.** What
made comments sprawl is that they were the only home for everything, and the fix is to give the other
things homes rather than to squeeze the one home smaller.

It also settles the SQL-client question the migration section raises. A relation whose inline
description reads "one field of one type in one graph; for example `Film.title`" is more useful at a
`\d+` prompt than 1431 words was, so nothing is lost by shortening it and the discoverability
argument for keeping prose in the comment survives intact.

**One authored source, one echo, one gate.** The prose is authored in `meta_relation`, where a
`CHECK` can constrain it. The comment repeats those two columns, because the DDL is static text and
cannot interpolate them. A gate requires the relation's comment to equal its row's `grain_text` and
`example` joined, so the echo cannot drift from the source and neither can be edited alone. That is
a small duplication bought for an engine-enforced constraint, and it is the reason `grain_text` is a
column at all rather than being left in the comment.

### Migration, which has to be lossless before it is tidy

Split every relation comment at its first sentence. The first sentence stays as the `COMMENT ON`, so
a SQL client still describes the relation inline, and becomes `grain_text`. Everything after it moves
verbatim into `rationale`. That step is mechanical, reversible and makes no judgment about any
relation.

`example` cannot be migrated because there is almost nothing to migrate: four comments in the file
contain the words "for example". Filling it is the real work and it is per relation.

`rationale` starts uncapped and carries a ratchet on its total that can only fall, so material leaves
for `docs/architecture/` or is deleted, relation by relation, without a flag day. Capping it on day
one would either block the migration or force 66628 words of triage before anything lands.

### Gates

- Every relation in `INFORMATION_SCHEMA` has a `meta_relation` row and every row names a relation
  that exists. Equality both ways.
- `meta_relation.grain_text` equals the relation's comment's first sentence, so the two homes cannot
  drift and a SQL client is never told something the pages do not say.
- Every `meta_relation.grain_name` resolves, by the foreign key.
- Owner and grain agree about the corpus, which is a join once `meta_owner` carries it.
- Every `meta_owner` row names a Java class that exists, held by the Javadoc reference gate the
  build already runs.
- The lengths, by `CHECK` constraints in the DDL rather than by tests, which is the point: a `CHECK`
  fires when the schema is applied, at every store boot, and cannot be skipped or disabled.

### Everything has a grain, including the views

All 107 `intent_` views declare a grain and an owner, the same as any table. None of them has a key to
derive one from, so each is a modelling decision rather than a transcription, and that is the bulk of
the work in this item. It is also the point of it: a rule that cannot say what one of its rows is
about is the defect being looked for, and the only way to find out which ones cannot is to make all of
them try.

### What a reviewer should press on

**The twenty unkeyed targets.** This plan requires them to state a grain, not to become keyable. Under
the model above they are the last gatherer's materializations, and what that gatherer should be
building is grain tables, meaning tables keyed on the thing a row is about. Twenty keyless copies of
view bodies are not that. So a reviewer should decide whether a stated but unkeyable grain is an
acceptable answer here, or whether it is exactly the defect this item exists to surface and each of
those twenty owes a real grain before it owes a registration.

**The size of the view half.** 107 modelling decisions is not a slice. A reviewer should say whether
this lands in one pass or whether the tables go first and the views follow per family, and if the
latter, what stops the second half from never happening.

**Whether `rationale` should exist at all.** It is proposed as a migration device, uncapped and
ratcheted down. The alternative is that the 66628 words go straight to `docs/architecture/` or are
deleted, and the item takes the triage cost up front rather than carrying a column that might become
permanent.

## Two cautions for whoever picks this up

**Do not delete the arguments.** They are the record of decisions that cost real work to reach, and
several of them are the only place a rejected shape is written down. They move; they do not go.

**The grain sentences are already good.** This item is not a rewrite of every comment in the store.
The opening sentences work and the mechanism that extracts them works. What needs doing starts at the
second sentence.

