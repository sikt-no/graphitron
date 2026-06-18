---
id: R331
title: "LSP @field(name:) validation on @table-interface participant cross-table reference fields scopes to the participant @table instead of the @reference terminal table"
status: In Review
bucket: bug
theme: lsp
depends-on: []
created: 2026-06-18
last-updated: 2026-06-18
---

# LSP @field(name:) validation on @table-interface participant cross-table reference fields scopes to the participant @table instead of the @reference terminal table

## Symptom

On a single-table-interface participant whose scalar field reaches a column on a *different* table via a single-hop `@reference`, the LSP flags the `@field(name:)` value as an unknown column on the participant's own `@table`:

```graphql
type DokumentMelding implements Melding @table(name: "INNBOKS_MELDING") @discriminator(value: "DOKUMENT") {
    soknadId: ID @reference(path: [{key: "DOKUMENT_MELDING__DOKUMENT_MELDING_BASE_FK"}]) @field(name: "SOKNAD_ID")
}
```

The diagnostic reads `Unknown column 'SOKNAD_ID' on table 'INNBOKS_MELDING'`. That is true of `INNBOKS_MELDING` but irrelevant: the `@reference` path tells the reader to resolve `SOKNAD_ID` against the FK's *terminal* table, not the participant's own table. The codegen pipeline classifies and emits this field correctly; only the LSP arm is wrong, so the author sees a red squiggle on a schema that builds clean.

## Root cause

The field classifies as `FieldClassification.ParticipantCrossTable` (the projection of `ChildField.ParticipantColumnReferenceField`, introduced by R36's single-table-interface work). That record already carries the right table in its `targetTableName` component (the `@reference` terminal table) alongside `columnName`, `fkName`, and `alias`.

The three column-name LSP consumers (validation, hover, completion) share one dispatch, `FieldClassification.lspColumnDispatch()` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/catalog/FieldClassification.java:123-153`). R224/R233 routed the four `@table`-backed column-bearing permits (`Column`, `ColumnReference`, `CompositeColumn`, `CompositeColumnReference`) to `Resolve(tableName)` so the column-name surfaces validate/hover/complete against the projected terminal table. `ParticipantCrossTable` was left in the `FallThrough` arm (`FieldClassification.java:131`).

`FallThrough` sends each consumer back to its backing-driven default, which uses the *enclosing type's* `@table`:

- `Diagnostics.validateFieldMember` (`graphitron-rewrite/graphitron-lsp/.../diagnostics/Diagnostics.java:556-582`) falls through to `validateColumnOnTable(catalog, <enclosing @table>, ...)`, producing the wrong-table error above.
- `Hovers` (`.../hover/Hovers.java:284-286`) and `FieldCompletions` (`.../completions/FieldCompletions.java:93-95`) consume the same dispatch, so for this field shape hover renders the wrong table's column metadata and completion offers the participant table's columns. Same root cause, two more wrong surfaces.

(`DeclarationHovers.java:120`, `InlayHints`, and `LspClassificationLabels` pattern-match `ParticipantCrossTable` directly and are *not* affected; they render the FK-constant / alias payload that is the reason the record stays distinct from `ColumnReference`.)

## Fix

**Shipped.** Move `ParticipantCrossTable` out of the `FallThrough` arm and into `Resolve`, keyed on its terminal table:

```java
case ParticipantCrossTable c -> new LspColumnDispatch.Resolve(c.targetTableName());
```

All three consumers route through the one dispatch, so validation, hover, and completion correct together. The `@field(name:)` value (`memberName` at the diagnostics call site) equals `c.columnName()` by construction, so validating that value against `targetTableName` is the same column-on-a-table question the four already-routed permits answer; routing here does not disturb the record staying distinct for the FK-constant/alias-rendering surfaces, which is an orthogonal axis.

The dispatch switch is exhaustive with no `default`, so this is a single-arm relocation, not a new branch.

## Test

**Shipped.** Mirrored the R233 trio with the interface-participant dimension (`type DokumentMelding implements Melding @table(name:"film") @discriminator(value:"DOKUMENT") { ... @field @reference }`), built from inline schema source the way the R233 tests do (a `ParticipantCrossTable` classification injected into the snapshot, backing pinned to the participant's own `@table`, terminal table reached only via `targetTableName`):

- `DiagnosticsTest` (`participantCrossTableReferenceValidatesAgainstTerminalTable`, `participantCrossTableReferenceBogusColumnCitesTerminalTable`): a real terminal-table column emits **no** diagnostic; a bogus column emits `Unknown column 'NOPE' on table 'language'` and **no** diagnostic citing the participant table `'film'`.
- `HoversTest` (`participantCrossTableReferenceHoversOnTerminalTableColumn`) and `FieldCompletionsTest` (`participantCrossTableReferenceCompletesTerminalTableColumns`): hover renders the terminal-table column, completion offers the terminal table's columns and not the participant table's. No assertions on generated method bodies.

## Out of scope

- The runtime validator's candidate-hint terminal-table bug (R236) is the sibling on the build-time-validator surface; it is filed separately and is not touched here.
- Multi-hop participant references, and participant fields that are not cross-table (a participant column on the shared discriminator table classifies elsewhere and already validates correctly).
- The `ParticipantCrossTable` record shape itself: it stays distinct from `ColumnReference` for the FK-constant/alias hover surfaces; this item only changes which `lspColumnDispatch()` arm it lands in.
