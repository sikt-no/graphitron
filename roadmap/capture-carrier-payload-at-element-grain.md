---
id: R1001
title: "Capture the argMapping carrier payload at the element grain"
status: Backlog
bucket: architecture
priority: 6
theme: tooling
depends-on: []
created: 2026-10-08
last-updated: 2026-10-08
---

# Capture the argMapping carrier payload at the element grain

## Goal

An `@argMapping` carrier, meaning the argument or input field whose value a mapping reads, is
answered by one keyed read of a stored relation, not reassembled from two subtype relations at
every read. `graphql_element` is already the supertype, and its `element_kind` already says which
coordinates are `FIELD_ARGUMENT` and `INPUT_FIELD`. What the store lacks is the value payload at
that grain: name, named type, list-ness and declaring type, over `graphql_argument` plus the fields
of input object types. So `ArgMappingCandidates` and anything else that asks "what value sits at
this coordinate" rebuild it from `graphql_argument` and `graphql_field` each time.
`fact-model.adoc` § "Derived reads are views, not stored facts" puts capturing a fact above every
other lever, so the carrier belongs at capture. R998 makes the read cheap without that, by driving
from `graphql_element` on its key. This item is the modeling fix, and it is not priced by a profile.

## Notes for the Spec

* Start from the readers: `ArgMappingCandidates` (`expand`, `markContestedCarrierName`, and the
  two seeds) after R998. Then look for other places that union arguments and input fields.
* Follow the "Adding a relation" discipline: say whether the payload is captured from the SDL corpus
  or derived in the anchoring phase, and give the relation an owner, a grain sentence, and a
  mark-and-sweep.
