---
id: R934
title: "Roadmap bodies cite store relations that nothing checks"
status: Backlog
bucket: testing
priority: 5
theme: tooling
depends-on: []
created: 2026-09-08
last-updated: 2026-09-08
---

# Roadmap bodies cite store relations that nothing checks

## Goal

A relation renamed in the fact store leaves the roadmap citing the old name, and no build step
notices. The fact store is the embedded database capture writes every fact into, and its DDL is the
model of record; `SchemaIdentifierDriftCheck` already holds the architecture documentation to it,
resolving every relation and column an `.adoc` page names against a booted store and failing the
build on one the store does not declare. Roadmap bodies are Markdown, the check walks `.adoc` only,
and they cite the store constantly. When this lands a renamer has one command that tells them which
roadmap prose their rename just falsified, and an author picking up an item is told before they
start that its premises name relations the tree no longer has.

## What the measurement says

Measured 2026-09-08 against a store booted from the DDL, running the existing check's own `scan` and
universe over `roadmap/**/*.md` instead of the architecture tree: **216 citations across 31 files
name something the store does not declare**, 100 distinct identifiers over 358 pages.

Most of that is correct prose rather than drift, and it separates cleanly by where it sits:

| Region | Citations | Why a dead name is right there |
|---|---|---|
| `changelog.md` and `audits/` | 64 | A dated record says what it said. Renaming it falsifies it. |
| `## Retired vocabulary` sections | 31 | The section exists to name what the item retired, and the workflow mandates it for exactly that. |
| `## Reviewer findings` sections | 14 | Appended per round and never rewritten by convention, so it holds what the reviewer saw. |
| Plan bodies | 107 | Three different things, and this is the whole difficulty. |

The 107 in plan bodies are 48 distinct identifiers across 21 items, and a plan body names a dead
relation for three legitimate reasons that its prose does not distinguish. It records history the
item itself made, as R876's landed table naming `intent_expanded_type` and `intent_expanded_field`
among the relations it deleted. It proposes a relation that does not exist yet, as the same item's
`graphitron_field_chain_application_entry`. Or it is simply stale, its premise fixed or falsified by
work that landed since. Three sampled at random were all stale in the third sense, each a
present-tense claim about a retired relation: `intent_argmapping_binding_leaf` "carries that case",
`graphitron_field_synthesis` "carries the macro's replacement", and an item whose entire subject is
that `graphitron_arg_mapping_pair` spells `arg_mapping` where its siblings spell `argmapping`, a
complaint R876 has since settled. Three of 107 is a sample and not a rate; sizing the stale share is
the first thing the plan owes.

`roadmap/README.md` carries two of them at line 190, which is worth noting because that file is
generated: the roll-up renders each item's goal paragraph, so it inherits whatever its sources say
and cannot be fixed except at the source.

## What makes this hard, and why it is not just a wider walk

The three exempt regions above are cheap, being location and section, both already conventions in
`workflow.adoc`. They cover 109 of the 216 and none of them is a judgement call.

The 107 are the item. A gate over them needs to tell a stale citation from a recorded one and from a
proposed one, and no property of the text says which is which. Requiring every plan body to mark its
proposed and historical names would push the cost onto every author for the benefit of a rename that
happens rarely. Leaving the region unchecked is what we have now. Between those sits a family of
answers the Spec should choose among rather than inherit: an advisory report of the kind the roadmap
tool already publishes, a ratcheted count that cannot rise, a gate scoped to a narrower region, or a
marking convention paid for only by items that need it.

One thing the measurement settles either way: whatever the mechanism, the 107 need a triage pass, and
that pass is worth doing on its own. An item whose premise names a retired relation is an item whose
plan may already be wrong, which is a staleness signal the roadmap has no other way to produce.

## Provenance

R876 slice 1 renamed 53 relations, giving the as-written half of the `graphitron_` family the
`_entry` suffix. The reactor renamed atomically. The roadmap did not, and across the five rebases the
slice needed to land, trunk delivered ten fresh pre-rename spellings in roadmap prose written in good
faith against a tree that had already moved. Each was found by a hand-rolled word-boundary scan for
each of the 53 old names, and the last two were found after the fast-forward, so trunk briefly
carried them. `fact-model.adoc` came through the same rename clean, because the check covers it.
