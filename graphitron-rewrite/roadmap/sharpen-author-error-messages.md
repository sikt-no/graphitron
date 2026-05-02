---
id: R59
title: "Sharpen author-error messages with concrete remediations"
status: Backlog
bucket: validation
priority: 5
theme: docs
depends-on: []
---

# Sharpen author-error messages with concrete remediations

Several validator rejection messages name the offending construct accurately but stop short of telling the author how to fix it. Examples seen in the wild on a downstream subgraph:

- `service method could not be resolved — parameter 'inputs' in method '…' does not match any GraphQL argument or context key on this field — available GraphQL arguments: [input]; available context keys: (none)` (`ServiceCatalog.java:234-238`, prefixed at `ServiceDirectiveResolver.java:117/121/130/145`). Lists the available names but doesn't mention the two remediations: rename the Java parameter, or add `@argMapping(arg: "input", target: "inputs")`. Hits authors repeatedly when they refactor a parameter name.
- `listed @table input arguments on @mutation fields are not yet supported` (`MutationInputResolver.java:252`). Bare unsupported-marker; doesn't say what shape *is* supported (single `@table` input wrapper) or whether the limit is structural or a roadmap gap. Should either point to an item or suggest the supported shape.
- `payload class '…' has 2 declared constructors; the carrier requires a single canonical (all-fields) constructor` (`FieldBuilder.java:1474` and `:1585`, duplicated). Names the count but not the constructors found, so the author has to bisect by hand. Suggested fixes (use a Java record, remove the extra constructor) aren't stated.
- `no foreign key found between tables 'X' and 'Y'; add a @reference directive to specify the join path` (`BuildContext.java:529`). Already actionable; a one-line example of the directive's syntax would save a docs trip.

Separately, the `RecordTableField requires a FK join path…` rejection at `FieldBuilder.java:2515-2525` is classified `RejectionKind.DEFERRED`, but R1 already shipped the `@batchKeyLifter` directive specifically to close it on free-form DTO parents. The `[deferred]` label misleads authors into thinking they're blocked on a future release; it should be `AUTHOR_ERROR` (the message body is already correct: "for free-form DTO parents, supply `@batchKeyLifter` on the field").

Scope: tighten the message strings and re-classify the one rejection. No structural changes to the validator pipeline; no new tests beyond updating existing substring assertions. Single commit per message is fine. The DEFERRED → AUTHOR_ERROR flip is the only behavior-changing piece and should land in its own commit so it can be reverted independently if a real "deferred" case for those variants surfaces later.
