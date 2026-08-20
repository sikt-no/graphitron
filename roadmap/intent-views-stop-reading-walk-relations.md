---
id: R743
title: "Reachability facts from the staged SDL gatherer replace the walk_ gate in the intent_ stratum"
status: Backlog
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# Reachability facts from the staged SDL gatherer replace the walk_ gate in the intent_ stratum

The `walk_` family transcribes the legacy classification walk's reach, and the walk is going away
as it currently stands (`roadmap/planners-read-facts-emitters-read-commands.md`, R682, carries the
deletion as its terminal deliverable). One view in the `intent_` stratum still reads that family as
a live gate: `intent_authored_claim_conflict` joins `walk_claim_domain_type` and
`walk_claim_domain_field` so the conflict detection mints only where the walk visited. When the
walk retires, those tables lose their writer and the view's population silently empties, so the
gate has to move onto a store-native population first. R740
(`roadmap/retire-oracle-diff-shadow-tests.md`) explicitly declined to schedule this flip, saying it
moves "when a diagnostics bug report or feature request gives a reason"; the walk's retirement is
now that reason. (`walk_type_backing_class`, the family's third relation, has no view reader and is
R740's own scope.)

## The architecture that replaces the gate

The SDL fact gatherer becomes a staged pipeline (owner's direction, 2026-08-20):

1. Each GraphQL file is parsed with its own `TypeDefinitionRegistry` and ingested: the per-file
   parse census, source membership and syntax errors.
2. All files are loaded into a single `TypeDefinitionRegistry` and ingested; a file that fails to
   load emits validation-error facts.
3. The single registry is assembled into a `GraphQLSchema`; failure to assemble emits
   validation-error facts.
4. A depth-first traversal over the full assembled schema populates the fact tables whose primary
   key is the graph name plus the coordinate.
5. A depth-first traversal rooted at the operation roots plus the node and entity types (the
   traversal `SchemaReachability` drives for the classifier today) populates the reachability
   facts.

Stages 1 through 3 are the three judging censuses `docs/architecture/explanation/fact-model.adoc`
already documents ("Reading the schema is itself a pipeline of judging stages") and whose payload
R714 (`roadmap/assembled-schema-owns-the-sdl-census.md`) assigns to assembly. Stages 4 and 5 are
not yet documented anywhere; writing them into the architecture docs is part of this item, and
R714's deliverable is stage 4's transcription source, so the two items must align rather than
restate each other.

## The fix

Reachability facts written by stage 5, at both grains the conflict view gates on (type and field
coordinate), replace `walk_claim_domain_type` / `walk_claim_domain_field` in
`intent_authored_claim_conflict`. Stage 5 is the same node-and-entity-rooted traversal the
classification walk runs today, so the gate's population reproduces the legacy accept line by
construction rather than by transcribing the walk's registries; the two membership grains then
drain, ahead of R682's terminal deletion instead of blocking it. The `walk_` family header proposed
a different destination for this flip (the resolved demand relation); the Spec decides which gate
the detection ends on, but the reachability facts exist under the new architecture either way.

One collision to settle in the Spec: `intent_type_domain` is already a store-native reachability
answer at type grain, materialized by `ReachabilityRows` as a semi-naive closure over captured SDL
edges, transcribing `SchemaReachability`'s seeds. Stage 5 populates reachability by traversing the
assembled schema object instead. Two derivations of one answer must not both survive; decide
whether the stage-5 facts subsume `intent_type_domain` (whose field grain the closure never had) or
land as its writer.
