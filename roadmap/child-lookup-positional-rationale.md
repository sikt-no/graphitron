---
id: R679
title: "A child lookup is rejected for a positional contract it does not have"
status: Backlog
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-08-14
last-updated: 2026-08-14
---

# A child lookup is rejected for a positional contract it does not have

`LookupKeyDirectiveResolver.resolveAtChild` rejects `@asConnection` beside `@lookupKey` with
"@asConnection on @lookupKey fields is invalid: @lookupKey establishes a positional correspondence
between the input key list and the output list (one entry per key), which pagination would break."
The rejection is right; the rationale is not. That positional correspondence is the *root* lookup's
contract. A child lookup coordinate narrows each parent's list rather than filling positions, as the
user manual's `@splitQuery` section and the pinned behaviour of
`GraphQLQueryTest.splitLookupTableField_filterExcludesActorsNotInFilm` both say, and `resolveAtChild`
is the only site that emits this message. So the one author who ever reads it is told their field has
a property it does not have.

The same root-contract language sits on two test-side descriptions of the same cell:
`GraphitronSchemaBuilderTest`'s `AS_CONNECTION_LOOKUP_REJECTED` prose (which also asserts
`.contains("positional correspondence")`, so the message and the assertion move together) and
the `split-lookup` corpus document's prose, which opens "a list child whose `@lookupKey` argument
establishes a positional input-list <-> output-list correspondence".

Predates the positional-contract work and was left alone by it deliberately: that item's blocking
findings were about the user manual, and the message is a separate consumer surface with its own
history. Small and self-contained. A plausible replacement rationale is that a caller-narrowed keyset
and a cursor window are two different ways to choose which children come back, and the generator
does not compose them; whoever specs this should confirm that against the classifier rather than
inheriting it from here.
