---
id: R696
title: "The authored-claim conflict view carries semantics, not a rendered message"
status: Backlog
bucket: architecture
theme: diagnostics
depends-on: []
created: 2026-08-17
last-updated: 2026-08-17
---

# The authored-claim conflict view carries semantics, not a rendered message

## Problem

`intent_authored_claim_conflict` computes a human-readable `message` in SQL, and it is the only
relation in the schema that computes prose rather than transcribing prose something else authored.
Its own column comment already concedes the character of the thing: "display only, never a
dimension". The row's job is the semantics of the violation, which it already carries as `verdict`,
`directives` and the coordinate; the sentence is a projection, and it depends on a context the store
does not have.

## The column is already redundant on the production path

`AuthoredClaimConflicts.rejectionOf(String verdict, String directives)` builds the `Rejection` from
the two semantic columns and never reads `message`. Its javadoc says why: the message's fixed naming
order is re-derived in Java where the order lives, and the deferral's message is composed from the
carve-out's own enum constants. So the consumer that has to be byte-identical to the legacy text
already treats the row as semantics and the sentence as its own.

That leaves `message` with one reader, the `diagnostic` view. And the `declared` LISTAGG, whose CASE
restates `AuthoredClaim`'s Java declaration order, exists only to feed that column: nothing else
reads it. Both go together, and the view comment's admission that "two pieces of Java logic live in
this SQL" drops to one, the routine-plus-lookup carve-out, which is genuinely semantics and stays.

`directives` stays too. Its comment marks it as the canonical grouping spelling every diagnostics
dimension shares, so it is a dimension; the two columns' own comments already draw the line this
item acts on.

## Three surfaces want three different sentences

* **The build report.** Coordinate prefix and file position, because nothing else locates the
  violation. The only surface with a byte-identical text contract, pinned by the registered
  agreement anchor, and it already renders in Java.
* **The language server.** The predicate alone. Today `Diagnostics` hands `error.message()` to the
  editor verbatim at a range, so the author sees `Field 'Film.title': ...` squiggled on the line that
  is `Film.title`. The editor is paying for the console's constraint. Its own composition can also be
  richer where the console cannot: related-information at the other claiming directive's position.
* **The MCP tool.** An agent reading rows wants the dimensions, which the view already carries as
  columns.

## Shape

Drop `message` and the `declared` LISTAGG from the view. `diagnostic`'s conflict arm projects NULL
for `message`, which leaves that column nullable by arm; acceptable here and only here, because that
view already nulls `variant`, `lsp_code`, `attempt_kind`, `stub_key` and `lint_rule` per arm as a
union across four vocabularies. Each consumer composes its own text from `verdict`, `directives` and
the coordinate.

The language server's own composition is not this item's work: it belongs with the diagnostics
surface in `lsp-reads-the-fact-store.md`, and the two are independent because the LSP reads through
`Diagnostics` rather than off this column. This item is the model change plus the report's text
staying where it already is.

The doctrine this instantiates is `views-carry-keys-not-payloads.md` (R698); if that item lands
first, this one is its worked example rather than its argument.

## Care

The agreement anchor's per-fixture expectations are hand-written messages the view does not produce,
so the text contract is unaffected by construction. Confirm that before editing rather than after:
the whole safety of the change rests on the report's render already being Java's.
