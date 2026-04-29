---
name: reviewer-prompt
description: Produce a single copy-pasteable code block that prompts another agent to perform a focused, architecture-oriented code review of the current branch (or a user-specified target). Use when the user asks for a "reviewer prompt", "review prompt", a "prompt for another agent to review", or otherwise wants a hand-off prompt for a fresh reviewer. The emitted prompt instructs the reviewer to find improvement opportunities aligned with graphitron-rewrite's design principles, not to bug-hunt, rubber-stamp, or police style.
---

# Reviewer Prompt

Generates a self-contained prompt the user copies into another agent (fresh Claude Code session, API playground, second pane, etc.). The reviewing agent then performs an architecture-focused review against graphitron-rewrite's principles.

The skill's job is the *hand-off*. Do not perform the review yourself.

## When to use

The user has just finished work, or wants a second opinion on a slice of work, and asks for a prompt to give to a different reviewer. Hand them back exactly one fenced block they can paste verbatim — no preamble, no trailing notes, nothing that breaks one-click copy.

## Procedure

1. **Resolve the review target.** Default is the current branch's changes against trunk:
   - Branch: `git branch --show-current`
   - Range: `origin/claude/graphitron-rewrite...HEAD`
   - Files: `git diff --name-only origin/claude/graphitron-rewrite...HEAD`
   - Run `git fetch origin claude/graphitron-rewrite` first if the cached ref looks stale.

   If the user specified a different target (a SHA range, a PR number, a single file or directory), substitute it. For a PR number, resolve the head ref via the GitHub MCP and use `<base>...<head>`.

2. **Sanity-check.** If the diff is empty, stop and ask what target the user actually wants reviewed; do not emit a prompt that points at nothing.

3. **Emit the prompt.** Output exactly one fenced block, pre-filled with the resolved branch, range, and file list. Use the template below verbatim for the body.

## Prompt template

Emit this as a fenced ```text``` block, replacing the `{{...}}` tokens. Keep the body literal otherwise — the template is the contract; do not improvise variants per call.

````text
You are reviewing graphitron-rewrite changes. Goal: surface architectural improvement
opportunities — places the design could be stronger or more in line with the project's
stated principles. Not bug-hunting, not style policing, not approve/reject.

Repo root: {{repo-root}}
Branch:    {{branch}}
Range:     {{range}}
Files changed:
{{file-list}}

# Read first (these define what "good" looks like here)

- graphitron-rewrite/docs/rewrite-design-principles.md  (technical principles)
- docs/graphitron-principles.md                         (strategic principles)
- graphitron-rewrite/docs/README.md                     (architectural orientation)
- graphitron-rewrite/docs/rewrite-model.md              (sealed-type hierarchy map)

Skim the diff once, then read the principles, then revisit the diff with the
principles in mind. The order matters: the principles reframe what you're looking at.

# Then look at the diff

    git -C {{repo-root}} diff {{range}}
    git -C {{repo-root}} log --oneline {{range}}

# What to look for

Architectural improvement opportunities, especially:

- **Generation-thinking gaps.** Model carries raw strings the generator re-parses;
  a generator branches on a predicate over pre-resolved data instead of on a sealed
  sub-variant; the same multi-arm switch recurs across generators.
- **Enum where a sealed hierarchy belongs.** Variants that carry different data
  forced into one shared field set.
- **Classification leaks.** Reflection types, raw `Table<?>`, raw `ForeignKey<?,?>`,
  or raw graphql-java schema types reaching past the parse boundary
  (`ServiceCatalog`, `JooqCatalog`, `TypeBuilder`, `FieldBuilder` are the only
  classes permitted to hold them).
- **Capability vs. sealed-switch confusion.** `instanceof` chain where a capability
  interface would express "uniformly true across variants"; a capability used where
  the generator actually forks on identity.
- **Component types too broad.** A field component declared at the sealed root
  when the classifier guarantees a narrower variant — the type system isn't
  carrying the certainty it could.
- **Sub-taxonomy candidates.** Resolution outcomes stored as raw strings or
  loosely-typed pairs that deserve their own sealed type.
- **Validator gaps.** A new classifier branch or invariant without a matching
  validate-time rejection — every classifier decision that implies a generator
  branch must fail at validate time if unimplemented.
- **Load-bearing classifier checks.** A classifier guarantee an emitter relies on
  that lacks `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` annotations.
- **Test-tier mismatch.** Code-string assertions on generated method bodies
  (banned at every tier); per-variant unit tests for behaviour that pipeline /
  compile / execute tiers should cover; a new feature without a pipeline test.
- **Stale references.** Javadoc, plan prose, or README naming a test/method/class
  that doesn't exist after this change.
- **Generated-output Java version.** Generator code may use Java 25 freely;
  emitted source must be valid Java 17. Flag Java 18+ syntax in anything written
  via JavaPoet templates.
- **Helper-locality / aliasing.** Emitted helpers that bind to a `Table` should
  receive it as a parameter, never declare it locally.

# What NOT to flag

- Formatting, import order, naming preferences.
- Restating what the diff does.
- Speculative features ("you could also support X").
- Conformance with the literal text of a principle when the spirit is met.
- Anything the diff already addresses or that an existing principle accepts as a
  trade-off (e.g. type erasure at jOOQ helper boundaries; selection-driven SQL
  for wide tables).

# Output format

Prioritized list, highest-leverage first. For each opportunity:

- **Summary.** One line.
- **Pointer.** `path/to/file.java:LINE` or function name.
- **Principle.** Cite the heading from `rewrite-design-principles.md` or
  `graphitron-principles.md` (e.g. "Generation-thinking", "Sealed hierarchies
  over enums").
- **Why the current shape is weaker.** One or two sentences. Concrete, not
  abstract.
- **Sketch of stronger shape.** One or two sentences, optional if obvious.

Stop once you've covered the architecturally interesting issues. A short,
high-signal list beats a long checklist. If the diff is genuinely clean against
the principles, say so plainly — do not invent findings to fill space.
````

## Output rules

- Exactly one fenced block. No "Here's the prompt:" preamble, no trailing notes — those break one-click copy.
- Pre-fill `{{repo-root}}`, `{{branch}}`, `{{range}}`, and `{{file-list}}`. The user shouldn't have to edit the block.
- The file list is one path per line, indented two spaces under `Files changed:`. Truncate with `... (+N more)` past 30 entries.
- If the user passed a PR target, replace the Branch / Range lines with `PR: #<n> (<head> → <base>)` and use the resolved `<base>...<head>` as the range.

## Hard rules

- Do not perform the review yourself. The skill exists to hand off; doing the work in-session defeats the point of getting a second pair of eyes.
- Do not improvise the template per call. Adjust pre-filled values; leave the body literal. Drift means each reviewer gets a different rubric.
- The prompt references files in the repo; it does not paste their contents. The reviewing agent reads them in its own session.
- The reviewer is asked for *opportunities*, not *defects*. If the user wants a defect-finding pass instead, that's a different skill (`/security-review`, `/review`).
