# Development Workflow

Every code change in this repo moves through a fixed pipeline. This doc
is the source of truth for state transitions, reviewer independence,
and what "publish" means.

## States

Every unit of work sits in exactly one state at a time. The roadmap
(`planning/rewrite-roadmap.md`) is the single ledger that tracks state per item.

```
Unplanned → Draft → Approved → In Progress → Pending Review → Done
                                                                │
                             ┌──────────────────────────────────┘
                             ▼
                        (plan deleted, removed from roadmap)
```

Two reverse transitions are legal:

- **Draft → Draft** (iterate): reviewer finds weaknesses or missing
  pieces; plan is revised. The default expectation is the reviewer
  commits the revision directly (see "What review is for" below).
- **Pending Review → Approved** (rework): reviewer finds further work is
  needed before calling the implementation complete. The plan is
  updated to capture the remaining work; the item goes back to Approved
  and a new implementation cycle starts on that leftover.

## Transitions

| From | To | Trigger | Required outputs |
|------|----|---------|------------------|
| Unplanned | Draft | Someone picks the item | New `docs/planning/plan-<slug>.md` with `> **Status:** Draft`; roadmap item gains `[Draft]` marker + link to plan; commit + push |
| Draft | Draft | Review identifies weaknesses or improvements | Reviewer commits revisions directly in place (default) or hands back a feedback list for the author to apply (fallback); push; roadmap unchanged |
| Draft | Approved | Reviewer (≠ most recent Draft committer) signs off | Plan front-matter changes to `> **Status:** Approved`; roadmap marker changes to `[Approved]`; commit + push |
| Approved | In Progress | Implementer starts work | Roadmap marker changes to `[In Progress]` (plan status unchanged); push |
| In Progress | Pending Review | Implementation commits landed | Plan updated: remove what shipped, keep what remains or was discovered; plan status becomes `> **Status:** Pending Review`; roadmap marker becomes `[Pending Review]`; implementation commits + plan update in the same branch; push |
| Pending Review | Approved | Reviewer (≠ implementer) requests more work | Plan updated to capture the remaining work; status back to `Approved`; roadmap back to `[Approved]`; push |
| Pending Review | Done | Reviewer (≠ implementer) approves | Plan file deleted; roadmap item deleted (or moved to a short "Done since…" line if it documents a milestone); push |

## Reviewer independence

- **Draft → Approved** must be signed off by someone other than the
  most recent Draft committer.
- **Pending Review → Done** must be signed off by someone other than
  the implementer. (The implementer may also have been the plan's
  author; that's fine. What matters is that the reviewer is a third
  party to the implementation.)

In Claude Code sessions, the human user is the usual reviewer. An
independent Claude session (a fresh agent with no prior context on the
work) can also review — it has no shared context and must evaluate on
the artifact alone.

### What review is for

Review is an active editing role, not a yes/no vote. A reviewer's job
is to:

1. **Find weaknesses** — factual errors, missing integration points,
   unstated assumptions, invariants that don't hold, stale references
   to code that no longer exists, hand-wavy decisions that will trip up
   the implementer, missing test coverage, scope creep.
2. **Find opportunities** — simpler alternatives, cheaper commit
   structures, places where an existing pattern already solves the
   problem, decisions worth pinning explicitly rather than leaving open.
3. **Land the improvements directly.** A reviewer who has the plan
   loaded and has identified a concrete fix is already the cheapest
   committer of that fix. Write the edit; commit it; push. Handing back
   a feedback list for someone else to apply is a valid but slower
   fallback — use it when the change needs the original author's
   judgment (schema design calls, genuine ambiguity) or when the
   reviewer lacks confidence in the surrounding context.

A "LGTM" review that adds no commit is suspect. If the plan was
already perfect, the reviewer should be able to articulate *why* it's
complete — what they looked for and didn't find. A review that ends in
sign-off without any prior iteration commit should include explicit
reasoning about the cases that *could* have been weaknesses.

Pending Review → Done follows the same rule: if the reviewer sees
corrections to the implementation, land them (or a plan update
capturing the remaining work) rather than just commenting.

### Authorship during iteration

"Author" in the transitions table means the most recent committer of
the plan file in Draft state, not a fixed identity. The Draft →
Approved sign-off must come from someone other than whoever wrote the
most recent Draft commit. Iteration rotates the "author" identity;
each round's reviewer must be a third party to the revision they're
approving.

This means a plan can be revised by the original author, then by
reviewer A (who commits improvements), then approved by reviewer B —
three parties, two reviews, one approval. It also means a reviewer
who lands substantive edits disqualifies themselves from approving
that revision; another party must sign off.

## Plan file conventions

- Location: `docs/planning/plan-<slug>.md`. Slug describes the work, not the
  phase (`plan-variant-coverage-meta-test.md`, not `plan-phase-2.md`).
- First non-heading line is the status front-matter, verbatim:
  `> **Status:** Draft | Approved | Pending Review`
- Plans may be multi-phase. When a phase ships, the implementation
  commit updates the plan to mark that phase done (typically by
  collapsing its section into a one-line "shipped at <sha>" note and
  capturing any learnings). The overall plan's status tracks what's
  next — if more phases remain, status stays `Approved`; if only the
  just-shipped phase is pending review, status is `Pending Review`.
- Plans describe *what* to do, not *how many commits* to land it in.
  Implementation commit structure is the implementer's judgment —
  split when the seams add review value, keep unified when they
  don't. A plan that pre-enumerates "C1 / C2 / C3" with commit-bounded
  scope is prescribing past its usefulness: the natural seams are only
  visible once the code exists, and locking them in up front forces
  premature predictions (e.g. writing C1-scope tests against C2-scope
  expectations). Plans *may* discuss logical units of work where that
  aids review, but without fixing them to commits.
- A plan deleted on Done has its file removed outright. Git history
  preserves it; leaving a tombstone file encourages staleness.

## Roadmap conventions

Each roadmap item gets a status suffix and, if a plan exists, a link:

- `1. **Title.** Description. [Approved] ([plan-slug.md](plan-slug.md))`
- `7. **Title.** Description. [Unplanned]`

Use `[Done]` only for milestones worth keeping as history (e.g.,
"Sealed-switch dispatch landed at <sha>"); routine completions
disappear from the roadmap entirely.

The roadmap is the source of truth for state. A plan file's status
front-matter mirrors the roadmap; drift is caught because both move
together in the same commit.

## Publishing

"Publish" = commit + push. A change that lives only in your working
copy doesn't exist for the rest of the workflow. The trunk-push rule
from `CLAUDE.md` applies: any push to your branch must be followed by
a fast-forward to `claude/graphitron-rewrite`.

## Adding to the roadmap

Any session can add items to the roadmap at any time. Opportunities
spotted during review, implementation, or unrelated work all land
here as `[Unplanned]` items. The expectation is that they're
substantive enough to justify eventual planning — not every passing
thought.

## Canonical path (example)

Taking a feature from idea to Done. Minimum of four commits by at
least two parties; typical paths are longer because review involves
iteration:

1. **Author** picks an `[Unplanned]` roadmap item, drafts
   `docs/planning/plan-foo.md`, sets roadmap to `[Draft]`. One commit.
2. **Reviewer (not the author)** reads the plan, finds weaknesses
   and opportunities, and either commits improvements directly (plan
   stays Draft; another reviewer then approves) or — if the plan is
   already in good shape — signs off (status → Approved). In practice
   most reviews involve at least one revision commit before sign-off.
3. **Implementer** writes code, updates the plan (remove shipped,
   keep pending), sets roadmap to `[Pending Review]`. One or more
   commits at the implementer's judgment; the last one carries the
   plan-status + roadmap update.
4. **Reviewer (not the implementer)** reads the diff and the plan's
   remaining content. If improvements are needed, either commits them
   directly or updates the plan to capture remaining work (roadmap back
   to `[Approved]`; new implementation cycle). If the work is complete,
   approves (plan deleted; roadmap item deleted or moved to a short
   "Done since" note). One commit.

The typical path is five-to-six commits when reviews involve
iteration — author draft, reviewer iteration(s), approval,
implementation, reviewer iteration(s) on the implementation,
done-approval. Nothing shorter preserves the checks; nothing longer
means something is actually being improved each round.
