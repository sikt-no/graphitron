---
id: R906
title: "Roadmap item bodies state a goal, then a plan"
status: Spec
bucket: process
priority: 3
theme: docs
depends-on: []
created: 2026-09-01
last-updated: 2026-09-01
---

# Roadmap item bodies state a goal, then a plan

## Goal

A roadmap item body reads in two parts: a clearly stated goal, then the current plan for reaching it. The goal says what changes for graphitron or its consumers when the item lands, in terms a reader can judge on its own: either the goal is good or it is not, and that decides whether the plan is worth investing in at all. Only then does the plan get evaluated and iterated on. Today item bodies tend to accrete as winding stories of research and argumentation, so a reader has to reconstruct both the goal and the current plan from the narrative.

When this item lands, the shape is defined in one place (`roadmap/workflow.adoc`), every other surface that talks about item bodies points there instead of paraphrasing, and the roadmap tool's `create` subcommand stamps new items with a `## Goal` skeleton, so the corpus converges by attrition: item files are deleted at Done, and every new file is born in shape.

The shape alone does not make a body readable, and the tree already measures how unreadable they are: the `explain` skill exists to translate item bodies into plain language after the fact, glossing every project term on first use and grounding the behaviour in real SDL. Those are authoring techniques, not translation techniques, so this convention lifts them to writing time. The test for a new item is that it needs no explain pass: the `## Goal` section is the explanation.

Two corollaries define what a revision looks like:

* A revision improves the plan in place. It does not argue against previous versions of the plan; git history keeps every prior version, so the body only ever carries the current best plan. Facts stay: a measurement, a `shipped at <sha>` note, a constraint discovered in flight are premises of the current plan and belong in it. The test is whether the sentence constrains what the implementer does next; a sentence about what an earlier draft got wrong does not, and git owns it.
* When the chosen plan is non-obvious, an optional `## Other solutions we've considered` section may follow the plan sections, naming the alternatives and why the current plan won. A one-sentence rejection that the plan's shape depends on stays inline beside the decision it motivates; the trailing section is for section-length explorations with no single decision to attach to.

The shape binds the plan body, that is, items at Spec and beyond. A Backlog stub stays a one-paragraph problem statement under the `## Goal` heading the tool stamps.

## Plan

`roadmap/workflow.adoc` owns the definition; everything else points at it.

* "Item file conventions": add a bullet codifying the body shape, with a short inline skeleton rather than a citation of a live item (item files are deleted at Done, so any exemplar citation rots). Content of the bullet:
  * Section order: `## Goal` first; plan sections follow (including `## Retired vocabulary` and a first-client `## User documentation` draft where those conventions apply); optional `## Other solutions we've considered` after the plan sections; optional `## Provenance` for investigation items; `## Reviewer findings` stays last.
  * The first paragraph of `## Goal` is rendered by the roadmap tool as the item's one-line description in the `README.md` roll-up and on the published status board, so it is written to stand alone.
  * For an item whose purpose is to settle a question rather than ship a change, the goal is the question and what a settled answer lets the next item do.
  * For an item with a user-visible surface, the first-client docs draft is the goal's strongest form; the `## Goal` section states the outcome and defers detail to that draft rather than competing with it.
  * The goal is stated in the reader's terms. For author-visible behaviour that means schema, query, and result, not classifier, variant, and emitter; the generator-internal mechanism belongs in the plan sections. Where the change is schema-visible, the goal carries an SDL example mined from real schemas (the corpus under `graphitron/src/test/resources/corpus/`, the sakila example schemas) rather than invented, as a minimal pair when a contrast makes the point; the example states the goal more precisely than prose can.
  * The first time a project term appears in the body it arrives with a one-sentence plain-language gloss, and is then used freely. A gloss that restates the name glosses nothing, and a goal that needs a chain of glosses started too deep. These two rules are lifted from the `explain` skill, which stays as the after-the-fact translator for pre-convention bodies; a body written to the convention needs no translation.
  * The revision discipline and its fact-versus-rebuttal discriminator, as stated under Goal above, including one clause separating it from the reviewer-findings response notes: the note beneath a finding is where the reviewer's delta lives and is untouched by this rule; the plan body above carries only the current plan.
* "Default plan shape is flat sections" bullet: name `## Goal` as the leading section of the canonical list.
* "What each gate decides": add one clause pointing the first `Spec → Ready` question ("is the goal well communicated") at the `## Goal` section as the place the reviewer looks first. That gate stays the enforcer of the goal's quality.
* `CLAUDE.md`, "Writing style": replace the two roadmap-item sentences ("top-down writing approach" and "Don't use complex jargon without defining it first") with a one-clause pointer: state the goal first, then the plan, glossing project terms on first use; `roadmap/workflow.adoc` § Item file conventions owns the shape. No second definition; the gloss rule moves to workflow.adoc with the rest.
* `.claude/skills/roadmap/SKILL.md`, the `add` subcommand section: update the "single-paragraph TODO body / problem statement" sentence to name the stamped `## Goal` skeleton, again deferring to workflow.adoc.
* `roadmap-tool` `Main.java`, the `create` body template: emit `# <title>` followed by a `## Goal` heading and a placeholder line ("One paragraph: what changes for graphitron or its consumers when this lands; it doubles as the item's one-line description in the roll-up."). Adjust whatever test pins the template text.

The `CLAUDE.md`, skill, and `Main.java` edits sit outside `roadmap/`, so the implementation commit owes the full verification build, not the roadmap-only scoped one.

## Non-goals

* No retroactive rewrite of existing item bodies. The corpus is self-draining (files are deleted at Done) and the `create` template puts every new item in shape, so attrition does the migration; an author revising an old body reshapes it in passing.
* No mechanical shape gate in this item. The quality half already has an enforcer (the `Spec → Ready` gate's first question); the shape half cannot be gated while several hundred pre-convention bodies are live. A `## Goal`-heading check becomes viable once the pre-convention items drain; filing that check is future Backlog material with its own clock, not this item's scope.

## Other solutions we've considered

* Gating the shape mechanically now, alongside the existing roadmap-tool prose checks. Rejected: the check would fail every pre-convention body on day one, and a grandfather list rots; the honest sequencing is template first, gate when the corpus has drained.
* Rewriting existing item bodies into the new shape. Rejected: most bodies die with their file at Done, so the cleanup cost outruns the value.
* Citing a live item as the convention's worked example. Rejected: every item file is deleted at Done, so the citation is guaranteed to rot; the inline skeleton in workflow.adoc and the `create` template carry the shape instead.
* Keeping readability a translation concern: leave authoring as it is and let the `explain` skill translate on demand. Rejected: it treats the symptom, pays the translation cost on every reader instead of once at writing time, and the translated prose lives in a chat rather than in the body the next reader opens.
