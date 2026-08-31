---
id: R883
title: "Five prose sites name the parent-source derivation by its retired boolean"
status: Backlog
bucket: cleanup
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# Five prose sites name the parent-source derivation by its retired boolean

When a generated data fetcher reads its parent object, it has to know whether that object
arrived bare or wrapped in the error-channel `Outcome` type. The generator answers that
question once per GraphQL type. That answer used to be a plain boolean local named
`sourceIsOutcome`, threaded as a parameter into each fetcher builder; it is now a sealed value,
`ParentSourceBinding`, that pairs the narrowing statement with the expression the parent is read
from, so the two halves cannot be minted apart.

The boolean local survives at the one site that feeds the producer
(`TypeFetcherGenerator.generateTypeSpec`), so nothing is stale in the compiler's eyes. But five
prose sites still describe the whole derivation by that local's name, which now points at an
input to the real mechanism rather than at the mechanism:

- `ChildField.java:193` ("the same axis the table-field sibling's emitter derives as
  `sourceIsOutcome`")
- `KeyLift.java:79` ("handled by the generator at the type level (`sourceIsOutcome`)")
- `FieldBuilder.java:6800`, `:6880`, `:6925` (three variants of "derived at the type level by the
  generator (`sourceIsOutcome`)")

A reader following any of those lands on a boolean instead of on `ParentSourceBinding`, which is
where the prelude/source pairing and the three-arm fork actually live. Repoint them at the
binding with `{@link}`, which is javadoc-checked and so cannot rot the same way. Two sibling
prose surfaces are already correct and are the model to match: `SourceEnvelope`'s javadoc and the
matching paragraph in `docs/architecture/explanation/dispatch-axes.adoc`.

Cheap and self-contained: five comment edits, no behaviour change, no new test. Filed out of
R873's Done gate, where it was recorded as non-blocking twice rather than fixed inside an item
whose scope was the defect itself.

