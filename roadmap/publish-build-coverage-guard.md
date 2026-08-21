---
id: R788
title: "Publish stops when the verification build did not cover HEAD"
status: Backlog
bucket: workflow
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# Publish stops when the verification build did not cover HEAD

R787 states a rule with no enforcer: never push a tree the verification build did not cover. Today the only thing that fails when the rule breaks is trunk CI, after the unverified tree is already on trunk, which is exactly the shape the "every invariant has an enforcer" axiom disallows. The publish skill cannot catch it either: its step 2 checks trunk divergence, and "did the build cover `HEAD`" is a different predicate, one that stays uncovered in the cases the R787 ordering makes more likely (a review fix or plan edit committed after the build ran).

Sketch of the cheap enforcer: on success, the verification build records `git rev-parse HEAD` in a gitignored marker file, and publish compares `HEAD` against the marker before pushing, turning "your build did not cover this tree" into a stop instead of a prose obligation. Candidate enforcer family is the session tooling (`.claude/settings.json` hooks or a publish-skill step), not the roadmap-tool `check-*` steps, which check document text rather than session behaviour. Open questions for Spec: how the marker gets written (Maven execution vs wrapper vs hook), what invalidates it besides a new commit (a rebase rewrites `HEAD` even with an identical tree; comparing tree hashes instead of commit hashes would let a pure history rewrite keep its verification), and whether docs-only commits are exempt.
