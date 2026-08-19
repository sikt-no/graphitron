# GitHub issue to roadmap linkage audit: 2026-08-19

Every **open** issue on [`sikt-no/graphitron`](https://github.com/sikt-no/graphitron/issues)
checked against the roadmap under [`roadmap/`](../), on `claude/graphitron-rewrite`
(HEAD `3fd8048`, audited 2026-08-19). The question this answers: does each open
issue have a roadmap item that owns the work, and can a reader travel between the
two in both directions?

This file is an analysis artifact, not a roadmap item: it lives in a subdirectory
so the roadmap-tool (which scans `roadmap/*.md` non-recursively and requires `id:`
front-matter on each) ignores it, and it is Markdown so the `check-adoc-tables`
build step (which scans `.adoc` only) leaves it alone.

## Method

Eight open issues (nothing beyond #530; the numbering gaps below #512 are closed
issues). For each one:

1. Searched `roadmap/*.md` for a citation of the issue URL or number.
2. Where no citation existed, searched by symptom vocabulary (`@reference`,
   fan-out, cardinality, multiset, `DISTINCT`, transaction, `transactionResult`)
   across item titles and bodies to see whether an uncited item nonetheless owns
   the work.
3. Read the issue's comments to check for a reverse link (the established
   convention on this repo is a comment carrying the rendered plan URL,
   `https://graphitron.sikt.no/roadmap/plans/<slug>.html`).

Linkage is therefore scored on two axes: **is there an owning item**, and **is the
link visible from the issue**.

## Summary

| Issue | Subject | Owning item(s) | Status | Link on issue |
|---|---|---|---|---|
| #530 | `@mutation` batches are transactional, `@service` mutations are not | **none** | | no |
| #529 | `@reference` list fields over 1:N paths return duplicate rows | **none** | | no |
| #527 | `type Int not found in schema` when `@asConnection` is the only `Int` use | R672 `register-referenced-builtin-scalars` | Backlog | yes |
| #526 | `@nodeId` lookup arg binds one implementation instead of dispatching on typeId | R673 `nodeid-arg-dispatches-on-typeid` | Backlog | yes |
| #525 | `@condition` per-type overloads, and `@nodeId` on multitable filter inputs | R675 `condition-method-overload-selection`, R676 `nodeid-filter-per-participant-paths` | Backlog, Backlog | yes (both) |
| #524 | Documented auto-fetch from `@service`-returned records does not happen | R674 `service-record-return-pk-autofetch` | Backlog | yes |
| #523 | `@orderBy`/`@defaultOrder` silently ignored on multitable interface queries | R382 `multitable-interface-query-orderby-lowering` (root field), R663 `split-query-child-list-drops-default-order` (nested child), R677 `list-ordering-invariant-enforcement` (the enforcer) | Backlog, Spec, Backlog | no |
| #512 | Annotate several fields to auto-generate a search index | R427 `relevance-ranked-search` | Ready | yes |

Six of eight issues have an owning item. Two have none. One of the six covered
issues, the largest one, has no link visible from the issue and its headline
symptom sits on an item that does not cite the report.

## Gap 1: #530 has no owning item

[#530](https://github.com/sikt-no/graphitron/issues/530), filed 2026-08-18
against 10.0.0-RC31, reports that a generated `@mutation` batch runs inside
`dsl.transactionResult(...)` while a `@service` mutation is called raw, outside
any transaction, and that nothing in the schema or the manual signals the
difference. The consequence they hit is real: a `@service` batch mutation whose
contract is all-or-nothing partially committed, element 1 landing while the error
payload told the client nothing had been created.

The issue is explicit that the docs half is the actual request and that
auto-wrapping is **not** wanted. Their reasoning is worth preserving in whatever
item we file, because it is the design argument, not a preference: a service may
have effects a rollback cannot reach (a second datasource), a service that
already manages its own `ctx.transaction()` would have it demoted to a savepoint
under an outer wrap, and `@service` is the documented escape hatch, so owning
transaction boundaries belongs with owning the implementation. Their optional
suggestion is an opt-in `@service(transactional: true)`.

What the manual says today confirms the gap. `docs/manual/reference/directives/mutation.adoc`
states the write "runs inside a transaction"; `docs/manual/reference/directives/service.adoc`
mentions transactions only as a *reason to reach for* `@service`
("multi-statement transactions") and never says the invocation itself is
untransacted. The one place the asymmetry is stated at all is
`docs/manual/how-to/mcp-agent-context.adoc`, which notes in passing that a
query-path `@service` or `@routine` that writes "runs outside graphitron's
transaction demarcation in production". That is the right fact in the wrong
place, and it covers the query path rather than the mutation path the issue is
about.

Two candidate shapes, and they are separable:

* **A docs item** stating the demarcation contract on both the `@service` page
  and the error-channel page: what graphitron wraps, what it does not, and that
  a `@service` implementation owns its own boundaries. Small, unblocked, and it
  closes the footgun the issue actually reports.
* **A directive item** for opt-in `@service(transactional: true)`. Larger, needs
  a decision on whether we want the surface at all, and interacts with
  `roadmap/query-read-only-enforcement.md`, which already reasons about the
  read-only enforcement graphitron cannot apply to paths it does not control
  (`@routine`, `@service`). That item is the natural neighbour for the framing
  question of what graphitron may assume about a hand-written service.

Recommendation: file the docs half as a Backlog item now, and file the directive
half as a separate Backlog item cross-referenced to it, so the docs fix is not
held hostage to the surface decision.

## Gap 2: #529 has no owning item

[#529](https://github.com/sikt-no/graphitron/issues/529), filed 2026-08-15, began
as a duplicate-rows bug report and was reformulated by its author on 2026-08-15
into a feature request. The reformulation matters more than the original title,
which still reads as a bug:

Their own conclusion is that graphitron is doing what the config asks.
`@reference(path: [...])` is mechanical FK-path traversal, SQL joins produce bags
rather than sets, and a default `SELECT DISTINCT` would be wrong in general
(it changes semantics for legitimate multiset uses and interacts badly with
pagination and ordering). Their `SELECT DISTINCT` view is, on reflection, the
idiomatically correct relational answer.

What they argue is actionable is a **static** verdict at codegen time, from
metadata we already hold. Every hop in a reference path has a known cardinality
from the FK direction the joins are generated from: child to parent is N:1 and
safe, parent to child is 1:N and introduces fan-out. If a path contains a 1:N hop
and does not terminate in that child table, the result is a multiset whenever the
intermediate has multiple matching rows. That is a graph property of the
configuration, decidable without runtime insight. Their concrete asks:

1. A codegen or validator warning when a `@reference` path traverses a 1:N hop
   into a further projection, in the same class of feedback as the existing
   `@defaultOrder` requirement, which shows the validator already takes positions
   on list-field semantics.
2. Either an opt-in `distinct` flag on `@reference`, or documentation blessing the
   DISTINCT-view plus synthetic-FK pattern as the official answer for
   set-semantics fields.

Nothing in the roadmap owns either half. Filed as R723
`reference-path-fanout-verdict` (Backlog) after a second pass established how much
of the verdict the fact base already computes: `intent_field_reference_step_target`
carries `fk_on_from` per path element ("TRUE when the departing table declares the
foreign key; the element's direction"), so the rule is a predicate over rows that
exist rather than a new catalog traversal, and `sql_constraint.constraint_type`
plus `sql_constraint_column` sharpen it to exclude 1:1 reverse hops the reporter's
own heuristic would have flagged. The nearest neighbours are all adjacent rather
than overlapping, and are named in the item so the next reader does not re-derive
the search:

* `roadmap/path-element-surface-cleanup.md`, which separates join-shape from
  WHERE-filter on the `@reference` path element surface. A `distinct` flag would
  land on that surface, so the shape of this item depends on whether that
  cleanup lands first.
* `roadmap/lsp-reference-path-authoring.md`, which is the authoring-time
  counterpart. If path-hop cardinality becomes a derived fact for a validator
  warning, it is the same fact an LSP hint would show while the path is typed.
* `roadmap/list-ordering-invariant-enforcement.md`, which is the closest
  *precedent* rather than a neighbour: an invariant about list results, enforced
  off a single relation slot where every leak site is visible. A fan-out warning
  is the same problem shape (a property of the configuration that no check
  currently compares against the emitted SQL), and probably wants the same
  treatment, sourced from FK-direction facts rather than from the emitter.

Recommendation: one Backlog item for the static fan-out verdict, carrying both
asks and the author's argument against default DISTINCT, and explicitly noting
that the issue title still says "bug" while its author has retitled the substance
to an enhancement. Ask the reporter whether they want the issue relabelled; they
offered.

## Gap 3: #523 is covered three ways and links to none of them

[#523](https://github.com/sikt-no/graphitron/issues/523) is the best-covered issue
on the board and the worst-linked. Its two halves are owned separately, and the
split is deliberate:

* The **root query field** half, `@orderBy`/`@defaultOrder` accepted and discarded
  on a multitable interface query, is R382 `multitable-interface-query-orderby-lowering`
  (Backlog, created 2026-06-25, so it predates the report).
* The **nested child list** half from the follow-up comment, `@splitQuery` plus
  `@defaultOrder` dropped at emit, is R663 `split-query-child-list-drops-default-order`
  (Spec). That item already carries the field report in full, including the
  view-backed target detail, and does the useful work of contradicting the
  reporter's attribution: the axis is `@splitQuery` versus inline, not the
  parent's polymorphism, because two plain-object-type fixtures lose ordering the
  same way.
* The **class** of failure behind both, an ordering the model resolved that never
  reaches the emitted SQL with no check comparing the two ends, is R677
  `list-ordering-invariant-enforcement`, which lists the `@splitQuery` child and
  the multitable root among its five known leak sites.

Two things are missing. R382, which owns the issue's *headline* symptom, does not
cite the issue at all, so the field report and the "validator compels the
directive that emit discards" sharpening live only on R663. And the issue carries
no plan-link comment, unlike #524 through #527, so a reader arriving from GitHub
sees an unanswered bug report.

Recommendation: add the field-report citation to R382, then post one comment on
#523 linking all three plan pages with a sentence on which half each owns.

## Reverse-linkage state

The plan-link-comment convention is followed on #524, #525, #526 and #527 (all
posted 2026-08-14 by `folkef6`) and on #512 (the item carries an `Origin:` line
and the discussion is on the issue). It is not followed on #523, #529 or #530.

For #529 and #530 that is a consequence of Gap 1 and Gap 2: there is no plan page
to link yet. Posting the links is the closing step of filing the items, not a
separate task.

## Working list

Ordered so that the cheap, unblocked closures come first.

- [x] File an item: document the `@mutation` versus `@service` transaction
      demarcation on the `@service` and error-channel pages (#530, docs half).
      Filed as R721 `service-transaction-demarcation-undocumented`, taken
      straight to Spec, and linked from the issue.
- [x] File an item: opt-in `@service(transactional:)`, cross-referenced to
      the docs item and to `roadmap/query-read-only-enforcement.md` (#530,
      surface half). Filed as R722 `service-opt-in-transaction-wrap`, taken
      straight to Spec, and linked from the issue. Both await a Spec → Ready
      sign-off from a different party.
- [x] File a Backlog item: static fan-out verdict for `@reference` paths
      traversing a 1:N hop (#529). Filed as R723 `reference-path-fanout-verdict`,
      with the neighbours named and the `distinct` flag left to R235, and linked
      from the issue.
- [ ] Add the #523 field-report citation to R382, whose half of the issue is the
      one the reporter led with.
- [ ] Comment on #523 with the three plan links and which half each owns.
- [x] Comment on #529 and #530 with their plan links once filed, and on #529 ask
      whether the reporter wants it relabelled as an enhancement, which they
      offered. Both posted; the relabel is put to the reporter and not yet done.
- [ ] Decide whether R663's Spec-stage plan should absorb R382, or whether the
      two halves stay separate through implementation. R663 argues the ordering
      loss is not multitable-specific, which weakens the case for treating the
      two as one fix, so the default is to keep them separate.

Nothing here needs a decision from the reporter before we can start, apart from
the #529 relabel, which is cosmetic.
