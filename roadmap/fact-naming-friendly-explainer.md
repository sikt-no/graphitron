---
id: R791
title: "A friendly architecture article on naming relations for their grain and fact"
status: Spec
bucket: docs
priority: 3
theme: docs
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# A friendly architecture article on naming relations for their grain and fact

## Problem

The naming discipline behind the fact store (every relation named for its grain and the fact one
row asserts, never for a consumer's question) is stated precisely in
`docs/architecture/explanation/fact-model.adoc`, but that page is written for contributors who
already live in the model: it is dense, rule-per-enforcer prose. An interested party meeting the
store for the first time (a new contributor, a consumer-side developer, a curious colleague) has
no gentle way in. They either bounce off the fact-model page or absorb the discipline piecemeal
from table comments. We owe them one article that teaches the idea in simple language, with
examples and a metaphor or two, that explains what the habit buys without being zealous or
preachy about it.

## Shape of the deliverable

One new explanation article, `docs/architecture/explanation/naming-the-row.adoc`, plus one bullet
in `docs/architecture/explanation/index.adoc`, plus one pointer sentence near the top of
`fact-model.adoc` so a reader who lands on the strict page first can find the gentle one.

Constraints the article must hold:

- Friendly register: simple sentences, every project term glossed on first use, no rule stated
  as law. The law stays on the fact-model page; this article links to it rather than restating
  enforcement detail.
- Grounded in the real schema: every relation it names must exist in `graphitron-model.sql`
  (the examples below were verified against the booted store's `INFORMATION_SCHEMA`).
- Honest about costs and known deviations, so it does not read as advocacy. The fact-model page
  already discloses the inversions; this article may acknowledge they exist and point there.
- No em dashes; AsciiDoc inline subset consistent with sibling explanation pages; no tables
  unless in `|===` blocks (none planned).
- No transient citations (no `R<n>`, no `roadmap/<slug>` paths) per the citation rule.

## Draft article (proposed content, to be landed at implementation)

```asciidoc
= Naming the row
:description: A friendly introduction to the fact store's naming habit: say what one row means, name the table after that sentence, and let questions be views.
:!toc:

The generator keeps everything it learns in a small relational database called the fact store.
This page explains one habit that shapes every table in it: a table is named for what a single
row of it *says*, never for the question somebody wanted answered. The precise rules, and the
tests that enforce the enforceable parts, live in xref:fact-model.adoc[The fact model]. This page
is the guided tour: what the habit looks like, why it feels roomy to work in, and where it
pinches.

== A database of sentences

When the generator runs, it reads three things: your GraphQL schema files, your database catalog
(through the jOOQ classes generated from it), and the Java classes on your classpath. Everything
it learns goes into tables in an embedded H2 database.

Each table holds exactly one kind of statement. `graphql_field` holds "this type declares this
field"; one row per field, keyed by the field's own coordinates. `sql_table` holds "the consumer's
database declares this table". `jvm_class` holds "this class exists on the declared classpath".
A table is a stack of filled-in copies of its one sentence, and the sentence itself is written on
the table as a SQL comment, so the store describes itself: connect to it, read
`INFORMATION_SCHEMA`, and every table and column tells you what it means. The generated
xref:../reference/schema/index.adoc[schema reference] is rendered from those same comments.

Two words from data modeling are worth glossing, because the whole habit hangs on them. The
*grain* of a table is what one row is about: one row per field, one row per foreign-key hop, one
row per lint finding. It is the difference between a spreadsheet of sales with one row per
receipt and one with one row per day; same subject, different grain, different table. The *fact*
is the statement the row makes about that grain. Name the grain, state the fact, and the table is
designed; the name and the comment both fall out of that sentence.

== Label the jar by what is in it

Think of the tables as jars in a pantry. The habit says: label each jar with its contents,
"raspberry jam, August 2026", never with your plans for it, "for the Sunday scones".

A contents label keeps working when plans change. Anyone can walk into the pantry and cook
something you never thought of, because the labels say what is actually there. A plans label goes
stale the moment Sunday passes, and there is a quieter problem: you cannot check it. Whether the
jar really contains raspberry jam is a fact you can verify by opening it; whether it is truly
"for the scones" is knowable only by asking whoever wrote the label.

Relations work the same way. `graphitron_table` says "the author put `@table` on this type,
binding it to this database table name". That is a contents label: you can hold it up against the
schema file and check it. A table named for its consumer, say `generator_backing_class`, would be
a plans label: its meaning depends on what the generator happens to do this month.

== The check that costs one sentence

Before a new table exists, its author finishes this sentence out loud: "one row of this table
says that ...". Two things disqualify an answer. Naming a consumer ("the class the resolver
would bind to this type") fails, because the row's meaning would then move whenever the resolver
does. Naming nothing checkable fails too: a fact needs a source you can hold it against, the
schema file, the catalog, the classfile.

"This classfile declares this supertype through this clause" passes. "This directive application
spelled this argument" passes. The failing sentences usually split, under mild pressure, into two
or three passing ones, and that split is the habit doing its work: the question you started from
still gets answered, but by a *view* that joins the facts, not by a table that froze the
question.

== A worked example: which table backs a type?

The generator needs to know which database table a GraphQL type is bound to. The tempting design
is one table holding the answer: type in, table out, done.

The store instead holds the ingredients and derives the answer:

* `graphitron_table` holds what the author wrote: `@table` on a type, with the name they spelled.
* `sql_table` holds what the catalog actually declares.
* `intent_spelled_table` is a view that answers a small, general question: how does a written
  table name meet the catalog? The same rule whether the name was written in `@table`, in a
  `@reference` path, or as a mutation's target.
* `intent_bound_table` is a view one layer up: which catalog table does *this type's* binding
  resolve to, including how many candidates matched.

That last point is where the design pays for itself. The code generator wants exactly one
candidate and refuses an ambiguous binding. The language server, asked to complete a table name
in an editor, wants *every* candidate. Both read the same view, because the candidate count is a
column rather than a rule buried inside whichever consumer asked first. A single type-to-table
answer table could not have served them both without growing a flag, then a mode, then a second
copy.

The history is telling and ordinary: `intent_bound_table` began as a subquery inside one
consumer's query, and became a named view the day a second consumer asked the same question.
Facts stayed facts; the question got promoted to a view when it earned it.

== What the habit buys

Nothing here is magic; each benefit is a direct consequence of rows meaning something on their
own.

*You can read the store like prose.* Every table and column carries its sentence as a comment.
Debugging a wrong answer starts with `SELECT`, not with a debugger: query the view, then query
the facts under it, and at each step there is an independent source to compare against.

*New capabilities are additions.* A new fact lands as a new relation beside the old ones, and a
new question lands as a new view over them. Existing tables do not change shape to accommodate
it, so existing readers do not notice.

*Consumers agree by construction.* The generator, the language server, and the documentation
tools read the same base tables through their own views. There is no moment where two private
copies of the model drift apart, because there are no private copies.

*Tests get real oracles.* A fact table can be checked against the file, catalog, or classfile it
transcribes. A question-shaped table has no independent source; the only available oracle is the
old code being replaced, and comparing against that quietly turns yesterday's bugs into pinned
expectations.

== Where it pinches

It would be dishonest to sell this as free.

There are more tables than a first sketch would draw. The store holds over two hundred
relations, and a question that felt like "one table" routinely becomes three facts and a view.
Each piece is small and self-describing, but the census is real, and so is the naming effort:
the one-sentence check takes actual thought, and it is a habit rather than a gate. The build
enforces the surroundings (every relation commented, every relation inside a chartered family
with its own prefix), but no test can check that a name was honest. That part is review and
culture.

The rule also has sanctioned exceptions and known deviations, written down rather than hidden.
The `diagnostic` view is deliberately question-shaped: it is a read surface unioning several
families' verdicts for whoever asks "what is wrong", and the roster records it as the exemption.
And the fact-model page names, in the open, the places where today's store stores a derivation
it should derive. The discipline is a compass, not a purity test; the pages that state the rules
also state where the tree currently falls short of them.

== Where to go next

The strict statement of all of this, each rule with its enforcing test named, is
xref:fact-model.adoc[The fact model]. The rendered
xref:../reference/schema/index.adoc[schema reference] shows every relation with its sentence. And
the store is a database you can simply open: a running session can serve it over the PostgreSQL
wire protocol (see {@code StoreConsole} in `graphitron-model`) and `psql` plus
`INFORMATION_SCHEMA` make a fine reading room.

xref:index.adoc[← Explanation index]
```

## Implementation plan

1. Land the article at `docs/architecture/explanation/naming-the-row.adoc` (content above,
   final polish allowed; fix the one `{@code}` slip, which is Javadoc syntax, to AsciiDoc
   backticks before landing).
2. Add the index bullet in `docs/architecture/explanation/index.adoc` after the fact-model
   entry: "Naming the row: a gentle introduction to the store's naming habit, with examples and
   the honest costs; start here before the fact model page if you are new."
3. Add one sentence to `fact-model.adoc`'s preamble pointing readers who want the gentle
   version at the new page.
4. Verify: full verification build (docs render is part of it; the AsciiDoctor render fails on
   broken xrefs), plus a manual read for em dashes and register.

## Acceptance

- Article renders in the docs site build with working xrefs, listed on the explanation index.
- Every relation name the article cites exists in `graphitron-model.sql`.
- No em dashes, no transient citations, no markdown tables in adoc.
- Register check: a reader with no graphitron context can follow it; no rule stated here that
  contradicts or duplicates fact-model.adoc's enforcement detail.
