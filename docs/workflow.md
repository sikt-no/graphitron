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

- **Draft → Draft** (iterate): reviewer sees improvements; author revises.
- **Pending Review → Approved** (rework): reviewer finds further work is
  needed before calling the implementation complete. The plan is
  updated to capture the remaining work; the item goes back to Approved
  and a new implementation cycle starts on that leftover.

## Transitions

| From | To | Trigger | Required outputs |
|------|----|---------|------------------|
| Unplanned | Draft | Someone picks the item | New `docs/planning/plan-<slug>.md` with `> **Status:** Draft`; roadmap item gains `[Draft]` marker + link to plan; commit + push |
| Draft | Draft | Reviewer requests iteration | Plan updated in place (by whoever has context — reviewer or author); push; roadmap unchanged |
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

### Authorship during iteration

"Author" in the transitions table means the most recent committer of
the plan file in Draft state, not a fixed identity. During Draft →
Draft iteration either the original author or the reviewer may commit
the revision — whichever has the context. When a reviewer identifies
concrete changes and has the plan loaded, committing the revision
directly is the faster path; handing back a feedback list is also
valid but slower.

The approval-integrity rule then becomes: the Draft → Approved sign-off
must come from someone other than whoever wrote the most recent Draft
commit. Iteration rotates the "author" identity; the next approver
must be a third party to that revision.

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

Taking a feature from idea to Done, minimum three commits by at least
two parties:

1. **Author** picks an `[Unplanned]` roadmap item, drafts
   `docs/planning/plan-foo.md`, sets roadmap to `[Draft]`. One commit.
2. **Reviewer (not the author)** reads the plan, either suggests
   iterations (back to Draft) or approves (status → Approved in both
   plan and roadmap). One commit when approving.
3. **Implementer** writes code, updates the plan (remove shipped,
   keep pending), sets roadmap to `[Pending Review]`. One commit bundling
   code + plan update.
4. **Reviewer (not the implementer)** reads the diff and the plan's
   remaining content. Either requests more work (plan updated;
   roadmap back to `[Approved]`; new implementation cycle) or
   approves (plan deleted; roadmap item deleted or moved to a short
   "Done since" note). One commit.

Four commits, three states entered, two reviewers. Nothing shorter
preserves the checks that make the workflow worth following.
