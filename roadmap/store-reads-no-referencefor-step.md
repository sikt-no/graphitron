---
id: R929
title: "A @referenceFor route is a discovered key in the store"
status: Backlog
bucket: bug
priority: 3
theme: nodeid
depends-on: []
created: 2026-09-07
last-updated: 2026-09-07
---

# A @referenceFor route is a discovered key in the store

## Goal

A `@nodeId` filter whose route is a per-participant `@referenceFor` path is stated in the fact store as
the path the author wrote, so the store's answer for that slot and the generator's agree. Today they
disagree with nothing failing, and every store consumer that asks how such a slot navigates gets the
wrong answer.

`@referenceFor` steps are captured: `graphitron_reference_for_step` holds them at a field coordinate and
`graphitron_argument_reference_for_step` at an argument coordinate, both read as the same element
grammar as `@reference`. No view in
`graphitron-model/src/main/resources/no/sikt/graphitron/model/graphitron-model.sql` reads either table.
`intent_node_id_instruction_live` derives `carries_reference_path` from `graphitron_field_reference_step`
and `graphitron_argument_reference_step` alone, and `intent_node_id_decode_hop` resolves an
`AUTHORED_PATH`'s hops through `intent_argument_reference_step_target` and
`intent_input_field_reference_step_target`, both built over those same two `@reference` step tables.
So `intent_node_id_decode_endpoint.navigation` reads `SAME_TABLE` or `DISCOVERED_KEY` for such a slot,
and the hop it carries where it carries one is the single foreign key auto-discovery would find, not
the chain the author wrote. `NodeIdLeafResolver.selectRoute` takes its `Route.ParticipantRoute` arm on
exactly those branches and walks the authored chain, so the walk and the store state different
navigations, different hops and different landings for one slot.

Nothing fails today. `FactCaptureAgreementTest`, whose job is to hold the walk and the store to one
answer, pins no `@referenceFor` `@nodeId` case at all, so the disagreement is not a regression that
slipped a gate; it is a shape the gate never covered.

## What makes this bigger than a fourth `navigation` value

The endpoint relation's key is the coordinate plus the *departing table*, not the participant.
`intent_node_id_decode_endpoint` takes its departure from `intent_argument_scope_table`, which joins
`intent_field_scope_table`, whose participant arm is `SELECT DISTINCT ... 'PARTICIPANT_TABLE'` over
`intent_field_participant_scope_table`: the projection drops the participant name deliberately, and
`intent_field_scope_table`'s own comment says a consumer needing the participant reads the
participant relation directly. Two participants of one consuming field may bind one table, and the
walk decides per participant (`selectRoute` dispatches on `ParticipantRef.TableBound`), so it can take
`Route.ParticipantRoute` for one of them and `Route.AutoDiscover` for the other while the store holds
one endpoint row for both. A per-participant route therefore needs the participant in the endpoint
family's key, which is a re-keying of that family onto an axis the scope projection drops, not a
fourth value in a closed vocabulary. Whoever picks this up should decide that before writing any
step-target view, because it decides the grain of everything under it.

## Why it is filed on its own

The disagreement exists whether or not any consumer of the decode family lands.
`roadmap/nodeid-key-landing-is-never-verified.md` verifies a `@nodeId` key landing over the store and
excludes a participant-route branch from both of its verdicts for exactly this reason, stating the
exclusion as a fact that stops being true when this item lands rather than as a scope note. That
exclusion is the pin: when the endpoint family models a per-participant route, the predicate matches
nothing by construction and that item's boundary test inverts. Neither item blocks the other, and the
two can land in either order.
