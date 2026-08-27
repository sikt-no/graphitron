---
name: explain
description: Explain one graphitron roadmap item or one graphitron concept in plain language, grounded in SDL examples, glossing every project term before using it. Use when the user asks "explain R337", "what is a consumer-derived input table", "explain @nodeId", "help me understand the classifier", "what does this item actually do", "ELI5 this", or otherwise wants to understand something rather than change it. Produces conversational prose, never a file.
---

# explain

Explains one roadmap item or one graphitron concept, in conversation, in plain language, grounded in
real SDL.

Output is prose in the chat. Nothing is written to disk: no page, no roadmap edit, no docs commit. If
an explanation turns out to be worth keeping, where it should live is a separate decision the user
makes (see *Not this skill's job*).

## What it takes

**A roadmap item.** `explain R337`. The item's own file, plus whatever it links.

**A concept.** A directive (`@nodeId`), a project term ("typed rejection", "consumer-derived input
tables"), a sealed variant, a mechanism, a piece of vocabulary the user hit and did not recognise.

## Resolve the subject before explaining it

An explanation assembled from the name alone lands wrong. Read first.

For an item id:

```bash
grep -l "^id: R337$" roadmap/*.md
```

No match means the item shipped and its file is gone. Search `roadmap/changelog.md` for the id: the
changelog entry is the as-built record, and for a shipped item it is the better source anyway. Follow
the item's outbound links too (sibling items, architecture pages, manual pages); a roadmap item is
usually an argument against a named alternative, and the alternative is where the point is.

For a concept, go to the tree that owns it:

| Kind of concept | Where it is documented |
|---|---|
| A directive | `docs/manual/reference/directives/<name>.adoc` |
| Author-facing model or behaviour | `docs/manual/explanation/`, `docs/manual/tutorial/` |
| Contributor-facing mechanism | `docs/architecture/explanation/`, `docs/architecture/reference/` |
| What a schema shape classifies to | `docs/architecture/reference/code-generation-triggers.adoc` |
| A live symbol | grep the module; `docs/architecture/reference/modules.adoc` says which module owns what |

Then grep `roadmap/*.md` for items that argue about the concept. The roadmap carries the reasoning
that the as-built docs state as settled fact, which is often exactly the "why" the user is asking for.

## The shape of the explanation

Top-down. Each section earns the next, and a reader who stops after the first one still got a true
answer.

1. **The one-sentence version.** Lead with it. No preamble, no "this is a subtle topic".
2. **What breaks without it.** Concretely: the schema an author wants to write, the query they want
   to run, the thing that is currently rejected or silently wrong. State it in the author's terms
   (schema, query, result), not the generator's (classifier, variant, emitter).
3. **The SDL walkthrough.** The load-bearing section. See below.
4. **How graphitron sees it.** Only now the internal vocabulary, each term glossed on arrival. Keep
   to the mechanism that actually explains the behaviour; a tour of the pipeline is not an
   explanation of one concept.
5. **Where it stands.** Roadmap items only. Status from the front-matter, plus what has landed
   against what the item still only proposes. Say plainly when an item is Backlog or Spec: it
   describes a problem and a plan, not shipped behaviour, and readers conflate the two constantly.
6. **Read next.** Two or three pointers, each with one line on what that page gives you.

## SDL carries the explanation

Every concept gets SDL. An explanation with no schema in it is a definition, not an explanation.

- **Show, then say.** The SDL block comes first, the prose reading of it second.
- **Minimal pairs beat single examples.** Vary one thing and hold the rest still: the same field with
  and without `@splitQuery`, the same return type under a `@table` parent versus a `@record` parent.
  The contrast is the thing that teaches.
- **Mine real SDL, do not invent it.** In order of preference:
  - `docs/architecture/reference/code-generation-triggers.adoc`, SDL paired with the verdict it
    produces;
  - `graphitron/src/test/resources/corpus/*.graphqls`, the corpus those blocks render from, where
    each document carries its declared dimensions;
  - `graphitron-sakila-example/src/main/resources/graphql/*.graphqls`, schemas that actually build;
  - the directive's own reference page.
- **Sakila tables, real columns.** `film`, `actor`, `customer`, `city`, `country`, `rental`, not
  `Foo` and `Bar`. Strip the internal `@classified` / `@classifiedType` annotations when lifting from the
  corpus; they are test vocabulary and will confuse an author.
- **Avoid `film -> language`.** Two foreign keys, so the reference is ambiguous and the example is
  subtly wrong. `city -> country` is the clean single-FK case.
- **Trim to the point.** Cut every field that is not doing work in the contrast you are drawing.
- When a `mvn graphitron:dev` session is live, the graphitron MCP tools answer off the real build:
  `docs.search` for directive semantics, `schema` for what the classifier actually made of a
  coordinate, `catalog.describe` for a table's real columns. Prefer them over guessing at catalog
  shape.

## No jargon before its gloss

The rule: the first time a project term appears, it arrives with a one-sentence plain-language gloss.
After that, use it freely. Gloss it once, never twice.

The shape of a gloss:

> A *coordinate*, meaning one field on one type, which is the unit graphitron classifies, ...

Terms that essentially always need one: coordinate, classification and verdict, sealed variant, leaf,
emitter, join path, projection, lowering, fact model, typed rejection, dispatch axis, batching and
split queries, and any of the `@classified` dimension names.

Two failure modes:

- **Gloss chains.** If a gloss needs its own gloss to make sense, the explanation started too deep.
  Back up to something the schema author can see, and come down from there.
- **Restating the name.** "Typed rejection is rejection that is typed" glosses nothing. Say what it
  does: the generator refuses a schema it cannot support and hands back a specific reason pinned to
  the exact field that caused it, instead of failing later with something unrelated.

House prose rules from `CLAUDE.md` apply, including no em dashes.

## Depth

Default to the shortest explanation that is still true, roughly a screen including the SDL. Go longer
only when the user asks, or when the concept has two halves that genuinely mislead in isolation.

When the subject is too big for one explanation, say so, name the two or three pieces it decomposes
into, and explain the one the user most likely wanted. Do not silently explain a fragment as though
it were the whole.

## Not this skill's job

- **Changing anything.** Editing a roadmap item, transitioning its status, or regenerating the
  rolled-up README belongs to the `roadmap` skill.
- **Persisting the explanation.** If the user wants it durable, the destination is
  `docs/manual/explanation/` for author-facing material or `docs/architecture/explanation/` for
  contributor-facing material. Both are inside the reactor, so both need a roadmap item first per
  `CLAUDE.md`.
- **Reviewing.** If the reading turns up something that looks wrong rather than merely dense, say so
  plainly and stop there; the gate for acting on it is a roadmap item, not this skill.
