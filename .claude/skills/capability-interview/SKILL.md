---
name: capability-interview
description: Investigative-journalist-style discovery skill that walks graphitron's model (sealed variants, records, directive resolvers, SDL types), asks Socratic questions about what each thing exists to deliver, and turns answers into capability catalog stubs and candidate `@capability(name:)` SDL tags. Use when the user asks to "interview the model", "discover capabilities", "walk the codebase for capabilities", "what capability does X serve", or otherwise wants conversational capability discovery rather than the heuristic walk `capability-catalog suggest` does. Sibling to capability-catalog: this skill discovers; that skill records.
---

# Capability interview

A conversational, journalist-style discovery skill. The user supplies the framing; the skill asks the questions and converts answers into actionable artefacts — new capability stubs (via the `capability-catalog` skill) and candidate `@capability(name:)` tags on SDL coordinates (surfaced for the user or another agent to apply).

The skill is designed for the seeding pass R115's plan body calls for, and for periodic gap-finding once the catalog exists. It complements `capability-catalog suggest` (heuristic walk: grep + cross-check, fast and shallow) with Socratic investigation: pick one model surface at a time, gather its context, ask what it's for, capture the answer.

## Output discipline

The skill produces two things only:

1. **Capability stubs.** When the interview surfaces a capability not yet in the catalog, hand off to the `capability-catalog add` subcommand to create the stub. The journalist proposes the slug and one-sentence definition; `capability-catalog` writes the file so the integrity rules apply.
2. **SDL tag candidates.** When the interview reveals a coordinate that should carry `@capability(name: <slug>)`, surface the coordinate + slug pair. The skill does *not* edit SDL files — that's an explicit user step (or a follow-up agent task) so SDL changes go through normal review.

The skill never writes Java. Capabilities live on SDL coordinates, not on Java types; the model→capability connection is derived at build time via the KB join (`coordinate → classifier_call → variant_family`), not authored on the Java side.

## Procedure

The user typically opens the skill with one of:

- `/capability-interview` — no target; the skill picks an un-tagged surface to investigate.
- `/capability-interview <hint>` — hint can be a Java FQN, an SDL type name, a directive name, or a file path. The skill anchors on what's named.

Once a target is chosen, follow this loop:

1. **Gather context.** Read the target and adjacent material: sibling sealed-variant subtypes, call sites, the coordinate it produces (if any), the directive that triggers it (if any), test classes that exercise it. Surface a short brief — three to five sentences max — naming what's there.

2. **Pose the journalist question.** Ask one open question at a time: "what does this exist to deliver to a developer?", "how would a developer recognise this in a generated API?", "what would break for them if it disappeared?". Probe for the user-facing promise, not the implementation mechanism.

3. **Listen and converge.** The user's answer falls into one of three buckets:
   - **Existing capability.** The slug already exists in `graphitron-rewrite/capabilities/`. Propose attaching `@capability(name: "<slug>")` to the coordinates the target backs; surface the list as SDL tag candidates.
   - **New capability.** The slug doesn't exist yet. Propose a slug + one-sentence definition; hand off to `capability-catalog add` to write the stub. Then surface SDL tag candidates as above.
   - **Plumbing, no capability.** The target is internal machinery; no slug applies. Acknowledge and move on.

4. **Move on.** Pick the next target (user-driven, or skill-suggested from un-investigated material) and loop. End the session when the user says so.

## Discovery sources for picking targets

When the user doesn't supply a hint, pick from:

- Classifier sealed-variant families that produce non-trivial fetchers (`graphitron-rewrite/graphitron/src/main/java`, grep `sealed `). Each family is a candidate capability boundary.
- Directive resolvers (`*DirectiveResolver.java` in the same tree). Each is the implementation side of a directive whose user-facing capability needs naming.
- SDL types in the sakila example with directive applications that don't yet carry `@capability` (`graphitron-rewrite/graphitron-sakila-example/src/main/resources/graphql/`).
- The "missing surfaces" finding from `capability-catalog audit` — sealed-variant families with no covering capability slug, the highest-priority targets.

Avoid re-interviewing already-tagged surfaces; if a coordinate already carries `@capability(name:)`, the journalist's work is done for that coordinate.

## Hard rules

- The journalist asks; the user decides. Don't propose a slug or a tag candidate as a fait accompli; propose, listen for redirect, converge.
- One target at a time. Trying to interview a whole sealed-variant family in one pass produces shallow output; pick the most representative subtype and let the framing generalise.
- The journalist never writes the catalog or the SDL directly. Catalog writes go through `capability-catalog add` so integrity rules apply; SDL writes are an explicit user/agent step so they go through normal review.
- Capabilities live on SDL coordinates, not on Java types. If the interview surfaces "this Java class is the heart of capability X", that's a finding, not an annotation — the durable tag goes on the coordinates the class backs.
- "Plumbing, no capability" is a valid outcome and should be honoured. Not every type maps to a capability; some types serve capabilities transitively. Don't force a slug onto plumbing.
