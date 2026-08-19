---
id: R738
title: "Complete an argMapping path after a dot, per openable kind, off the captured facts"
status: Backlog
bucket: feature
priority: 3
theme: lsp
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# Complete an argMapping path after a dot, per openable kind, off the captured facts

`ArgMappingCompletions.rightCandidates` offers nothing the moment a `.` appears in the value, with the
comment "dot-path expansion into nested input fields is not modelled; offer nothing rather than a
misleading flat list once a '.' is present". So an author writing `pOrganisasjonskode: input.` gets no
help at exactly the position where the grammar has the most to say, and that has been true since
before the grammar had two openable kinds.

The grammar's own rule makes this one question rather than a list of cases: **a dot opens the thing at
that position, and what it opens into depends on what the thing is.** An input object opens into its
fields; an `ID` carrying `@nodeId(typeName:)` opens into the key columns of the node type it names;
nothing else opens. Completion after a dot is therefore "what does the thing at this position open
into", answered per kind, which is a uniform trigger rather than a special case per kind.

## What the store already holds, and what the answer joins

Both openable kinds are answerable from captured facts today, and the node-id arm needs no new capture
at all:

* **The input-object arm.** `graphql_field` under an `INPUT_OBJECT` parent is the field list at a
  depth; the descent itself is `intent_input_occurrence_path` and its step relation, which already
  expand every reachable input path from an argument.
* **The node-id arm.** The node type is already captured *at the coordinate*:
  `graphitron_argument_node_id (graph_name, type_name, field_name, argument_name, node_type_ref)` and
  `graphitron_field_node_id (graph_name, type_name, field_name, node_type_ref)`. From there the
  openable columns are one join away, to `intent_resolved_node_key_column`, which resolves the three
  key-column tiers in precedence and is the one relation any reader of a node type's key list joins.

That division is the decision this item is filed with: **capture the node type at the coordinate, and
let the consumer join for the keys.** Materialising the key columns per `argMapping` coordinate would
duplicate a three-tier resolution that already has one home, and the tier precedence has to stay in
that home or two readers will eventually disagree about which tier won.

`intent_argmapping_binding_leaf` already assembles exactly this shape for the compiler's use: it
carries `node_id_declared` and `node_type_ref` per bound segment, coalesced from those two relations.
Whether the completion reads that view or the base relations directly is an open question for the
Spec; the view is keyed by a *written* path, so a cursor sitting inside a half-typed path may have no
row, which argues for the base relations plus the occurrence expansion.

## What this is not

Not the walk's own openability rule. `ArgBindingMap.of` asks whether a slot declares a `@nodeId` by
reading the directive, and that cannot become a join: the schema is assembled and walked before
capture runs, so the store is empty while the walk executes. The two answers agree by construction
because both read the same declaration, and the walk's is the one that fails the build; this item is
about the editor's, which has no build to fail.

Not the nested object form (R249's syntax), and not the `@routine`-coordinate completion and
diagnostic surface generally. Both compose with this rather than containing it.

## Tests

* One completion case per openable kind, over a fixture carrying both: a dot on an input-object field
  offers that type's fields, a dot on a `@nodeId(typeName:)` `ID` offers the node type's key columns in
  key order.
* A dot on something that does not open offers nothing, which is the case that keeps the arm honest:
  today's blanket empty answer would pass a test that only asserted the negative.
* A key-column completion where the node type resolves on each of the three tiers, so a tier's
  spelling reaching the editor is pinned rather than assumed.
* A half-typed path (`input.inven`) filters rather than emptying, which is the behaviour the current
  code's own comment gives as its reason for offering nothing.
