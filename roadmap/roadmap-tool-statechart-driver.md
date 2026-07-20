---
id: R506
title: "Roadmap tool as statechart driver, items as machine instances"
status: Backlog
bucket: architecture
priority: 6
theme: tooling
depends-on: []
created: 2026-07-20
last-updated: 2026-07-20
---

# Roadmap tool as statechart driver, items as machine instances

The development workflow is a state machine interpreted by prose: `roadmap/workflow.adoc` describes states, guarded transitions, entry/exit rituals (delete on Done, changelog entry, README regen), and cross-item conventions (Backlog tombstones, reopen-on-divergence), and every session re-interprets that prose by hand. The costs are structural, and they match the drift smells named in `docs/architecture/explanation/development-principles.adoc`. The flat `status:` value splices orthogonal axes into one identifier (lifecycle position, build/evidence state, sign-off freshness; the bolted-on `deferred:` boolean is the leak that proves it). Sign-offs are derived facts maintained apart from their source: nothing binds a Spec -> Ready or In Review -> Done approval to the content that was reviewed, so a post-approval edit silently stales the approval, mitigated only by convention. The reviewer-rule guard, the tombstone reactions, and the trunk-sync bracketing are all enforced by discipline, not by an enforcer, in a repo whose central axiom is that an invariant exists only while something fails when it breaks.

Reframe the workflow as a statechart and make `roadmap-tool` its driver, with each roadmap item file a machine instance:

- **Machine definition as data.** The statechart (states, orthogonal regions for evidence axes, guards, entry/exit actions, event subscriptions) becomes a checked-in artifact; `workflow.adoc`'s state table and diagram become rendered views of it, as `README.md` already is of front-matter. Quality gates get a single registration point: adding one is an edit to the definition, not a new scattered ritual.
- **Git history as event log, driver as deterministic fold.** Sessions assert events (sign off, approve, start, abandon), already attributed via the session trailer; the driver folds the log into each instance's configuration. Front-matter `status:` becomes a verified snapshot of the fold, kept for rendering and grep. Illegal transitions become typed rejections with stable codes; historical conformance becomes replayable; the fast-forward-before-and-after ritual becomes compare-and-swap semantics on an append-only log. No server or daemon: the driver is a library every session and CI run executes, and determinism guarantees they agree.
- **Event delivery.** A trunk push touching content under a sign-off fingerprint transitions that item's freshness region to Stale without a human noticing it; tombstones declare their subscription (`on: R<n> Done -> discard`) instead of relying on memory; eventless transitions flag or reopen items whose evidence regions go red.
- **Entry actions up to session spawning.** Entering In Review generates the evidence bundle and reviewer prompt (subsuming the manual `srp` handoff); "next actionable item" becomes a query over instance configurations rather than a README read.

The recursion is the design argument: this is the same architecture as graphitron itself (declarative source parsed once at a boundary into a typed model; downstream consumers are derived views and validations), so the project's own principles and test tiers apply verbatim. Staged deliverables, each independently valuable: (1) definition as data with rendered views, (2) fold/replay in `verify` including mechanical reviewer-rule evaluation, (3) sign-off content fingerprints and freshness regions, (4) driver-committed reactions and subscriptions, (5) evidence-bundle entry actions. Scope guard: the definition stays minimal (states, regions, guards, actions, subscriptions); rationale and technique remain prose in `workflow.adoc`, mirroring the product's model/documentation split. Consult `principles-architect` before Spec, particularly on the event-sourcing claim and the definition/prose boundary. Related: the doc-drift checker discussion (LLM-judged staleness gates) supplies the first non-trivial evidence region; the sign-off fingerprint deliverable stands alone even if no LLM gate ever ships.
