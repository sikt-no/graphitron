---
description: Final preparations before creating a PR — research changes, then plan cleanup and testing
model: opus
---

# Prepare for PR

You are tasked with preparing the current branch for a pull request by researching all changes against `origin/main`, then creating an implementation plan for final cleanup and quality improvements.

## Step 1: Research the Changes

Invoke `/research_changes`. It returns `{ research_doc_path, ticket, branch, diff_stat }` — use those values below; do not re-detect the ticket.

## Step 2: Create a Plan

Invoke `/create_plan` with the research path passed **inline in the same message** (that command prompts for input otherwise). Ask it to build an implementation plan with the goals below.

Plan goals:

1. **Simplify and refactor** — Look for opportunities to simplify the changed code: reduce nesting, improve readability, improve variable and method names where unclear. This mirrors what the `simplify` skill does; the user can run `Skill: simplify` after reviewing the plan.

2. **Add Javadoc** — Add Javadoc to public methods where it aids understanding. Skip trivial getters/setters or methods where the name is fully self-explanatory.

3. **Check test coverage:**
   - **Unit tests** in `graphitron-codegen-parent/graphitron-java-codegen/` — verify that new or changed logic has adequate unit tests. Suggest specific tests if coverage is missing.
   - **Integration tests** in `graphitron-example/graphitron-example-server/src/test/resources/approval/queries/` — verify that end-to-end behavior is covered by approval tests. Suggest new `.graphql` test queries if applicable.
   - **Unused test resources** — Check for test resource files (e.g., `schema.graphqls`, `.json`, `.graphql` files under `src/test/resources/`) that were added or modified on this branch but are not referenced by any test class. Flag any orphaned resources that should either be wired up to a test or removed.

4. **Update documentation** — Check if the changes affect any README files and suggest updates. Focus on:
   - `README.md` (root)
   - Module-specific READMEs (e.g., `graphitron-codegen-parent/graphitron-java-codegen/README.md`)
   - Any other documentation referenced in the project
   - If any README with a doctoc-generated table of contents is modified, run `doctoc <file>` to regenerate the TOC.

Save the plan to `thoughts/shared/plans/YYYY-MM-DD-<ticket-or-branch>-pr-preparation.md` (use `ticket` from Step 1 if non-null, otherwise `branch`).

## Important Notes

- Research the actual changes, not just the file list — read the diffs
- The plan should contain concrete, actionable items with file paths and line numbers
- Do not make any code changes during this command — only research and plan