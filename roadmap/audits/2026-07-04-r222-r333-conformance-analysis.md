# R222/R333 conformance analysis: 2026-07-04

A point-in-time comparison of the target internal architecture defined by **R222**
(`dimensional-model-pivot.md`, Spec) and **R333**
(`coordinate-lowers-to-datafetcher-queryparts.md`, Spec) against (a) the current
implementation on `claude/graphitron-rewrite` (HEAD `28a5b56`) and (b) the
architecture documentation under `docs/architecture/`. Companion to the
`2026-07-03-roadmap-staleness-audit.md` (which checks items against code; this
checks code and docs against the two target-architecture items). Like that file,
this is an analysis artifact, not a roadmap item: it lives in the subdirectory the
roadmap-tool ignores, and it is Markdown so `check-adoc-tables` leaves it alone.

## 1. Implementation vs R222, stage by stage

| Stage | Target | State on HEAD |
|---|---|---|
| 1 Foundation | `ServiceMethodCall` on `ServiceField` | **Done** (R238; `ServiceMethodCall.java:23`, `ServiceField.java:19`) |
| 2 Walker carriers | one slice per carrier | **Partial** (see below) |
| 3 Field dimensional axes | `(source, operation, target)` | **Landed inverted** (R316; see below) |
| 4 Failure at the wrapper | `Unclassified*` retire; unified `Diagnostic` stream | **Partial** (see below) |
| 5 Legacy permit deletion | input-side permits, cross-product leaves | Not started |
| 6 Namespace collapse | `GraphitronField permits OutputField` only, then rename | Not started |
| 7 Directive narrowing | `@table`/`@record`/`@value` off INPUT_OBJECT | Not started |

**Stage 2 detail.** Landed: `ServiceMethodCall` (R238), the error-channel carrier
work (R244), `UpdateRows` (sealed, single arm `Identified`), `DeleteRows` (sealed
`Identified`/`Broadcast`). `PaginationSpec` and `OrderBySpec` exist but predate the
carrier framing. Missing as named types anywhere in `src/main/java`:
`ConditionCall`, `TableMethodCall`, `ExternalFieldCall`, `ValidationShape`,
`PredicateCarrier`, `InsertRows` (INSERT/UPSERT still carry a `TableInputArg`
directly, `Operation.java:113,116`). `MethodBackedField` is still alive with six
implementers (`TableMethodField`, `RecordTableMethodField`, `ServiceTableField`,
`ServiceRecordField`, `ComputedField`, `QueryTableMethodTableField`); its own
Javadoc (`MethodBackedField.java:6-10`) still lists the four root `*Service*`
permits that R238 moved onto `ServiceField`, a code-level doc staleness.

**Stage 3 detail: the axes exist, but the dependency is inverted.** The R316
sealed families all ship (`Source.java:38`, `Operation.java:30`, `Target.java:22`,
`TargetShape.java:28`) and every `OutputField` exposes `source()` / `operation()` /
`target()`. But each accessor is an exhaustive switch **over leaf identity**
(`ChildField.java:38-174`, `QueryField.java:36-84`, `MutationField.java:28-76`):
the 50-odd leaf permits are the substrate and the axes are the derived view. Both
target specs want the opposite (leaves as denormalized views over the facts). The
only production consumer of the axes is `OutputField.requiresReFetch()`
(`OutputField.java:128-146`) plus its hand-kept validator mirror
`dispatchPerformsReFetch`, whose retirement is R314's stated goal. The emit is
untouched: `TypeFetcherGenerator` (6 588 lines) dispatches on leaf type with 45+
case arms, plus a second dispatch keyed on `SourceKey.wrap()`
(`TypeFetcherGenerator.java:792-802`). Leaf counts today: `ChildField` 26 concrete
leaves (19 direct permits, `TableTargetField` sealing 8 more), `QueryField` 13,
`MutationField` 14, `InputField` 6.

**Stage 4 detail.** `WalkerResult<C>` exists with the spec'd compact-constructor
invariants (`WalkerResult.java:14`) and is used by the service / update / delete /
error-channel walkers, the builder, and the validator. `Diagnostic` exists as an
LSP-aligned record (`Diagnostic.java:23-30`). But: `UnclassifiedType` /
`UnclassifiedField` are still permits; `validateUnclassifiedType` /
`validateUnclassifiedField` still run (`GraphitronSchemaValidator.java:1182,1186`);
`ValidationReport` carries only `errors` / `warnings` / `sourceUris`, with **no
`walkerDiagnostics` slot**, although R222's foundation-slice section states the
foundation slice adds one. `Diagnostic` also lacks the `tags` component
(`Unnecessary` / `Deprecated`) R222 specifies. Two small spec-vs-landed
divergences inside R222 itself.

**Stages 5-7 detail.** `GraphitronType.InputType` still permits the four
backing-class variants with `TableInputType` as a sibling root
(`GraphitronType.java:302,379`); `TypeBuilder.findReturnTablesForInput` back-scan
intact (`TypeBuilder.java:1764-1789`); `RootField` intermediate intact; the six
`InputField` leaves intact, plus two capability seals R222's text predates
(`LookupKeyField`, `SetField`, `InputField.java:41-51`). R335 (fold input
classification into the single walk, Spec) is the adjacent driver-side item.

## 2. Implementation vs R333

R333 scopes itself to the model ("no code in this item beyond what is needed to
make the model executable as tests"), so a near-zero code footprint is conformant,
not a gap. What the audit found:

- **No `SchemaCoordinate` type.** Coordinates are graphql-java `FieldCoordinates`
  plus `"ParentType.fieldName"` render strings (`CatalogBuilder.java:206`). The
  render-as-key discipline R333 describes is already practiced; the sealed
  five-kind key is unbuilt.
- **No fact relations.** `reference` / `referencedTable` / `resolvedTable` /
  `tableExpr` exist only as local variable names in `TypeBuilder`.
- **No seam registry, no referential-closure check.** "Seam" appears only as prose
  in `TypeFetcherGenerator` comments. Thread J's regime-2 worklist (the roughly
  eight independently retyped `$fields` call sites, `scatterByIdx`, the
  half-migrated `<field>Condition` name) is all still regime 2.
- **`SourceKey` undecomposed**: still `(target, columns, path, wrap, cardinality,
  reader)` with the compact constructor pinning `SourceRowsCall -> Wrap.Row`
  (`SourceKey.java:77-172`). The staleness audit already names this the next
  load-bearing structural pivot; live items R425/R426 sit directly on it.
- **The three-consumer layering R333 describes does ship**: the `catalog` package
  projects the classifier into `FieldClassification` / `TypeClassification` /
  `TypeBackingShape` / `CompletionData` / `CatalogFacts` via exhaustive switches
  over the leaf permits (`CatalogBuilder.projectFieldClassification`,
  `CatalogBuilder.java:227-527`), consumed by the LSP (`LspSchemaSnapshot` with the
  availability x freshness arms exactly as R333 states) and the MCP tools. This
  makes R333's "single load-bearing requirement" live: any leaf dissolution must
  re-source these projection switches onto the facts or the leaf zoo survives as an
  LSP shim.
- **Known corners confirmed**: `CompositeColumnReferenceField` is the single entry
  in `TypeFetcherGenerator.STUBBED_VARIANTS`; the `List(Column)` cell of the
  reference 2x2 is named-but-unmodeled (R333 requires settling it before
  implementation).

## 3. Docs vs implementation and vs the target architecture

No file under `docs/architecture/` mentions R333's model (coordinate facts, method
graph, seams, DataFetcher/QueryPart as node-kind views). Specific findings:

- **`explanation/dispatch-axes.adoc`**: the whole page presents
  `SourceKey`/`LoaderRegistration` as the finished, principled dispatch model and
  ties itself to the sealed-hierarchies principle as "the live worked example".
  Both target specs treat `SourceKey` as three conflated concerns to decompose.
  Strongest single divergence in the corpus.
- **`explanation/rewrite-design-principles.adoc`**: lines 23/25 use
  `SourceKey.Reader` and the DataLoader source side as the pattern exemplar;
  line 45 lists `MethodBackedField` / `BatchKeyField` / `SqlGeneratingField` as
  "established interfaces", where R222 names the same set the mixin-overlay symptom
  and plans `MethodBackedField`'s retirement. No forward notes.
- **`explanation/pipeline-overview.adoc:12`**: documents `Unclassified*` rejection
  as the validator's normal job; R222 Stage 4 retires exactly that.
- **`reference/code-generation-triggers.adoc`**: mixed vintage. The field
  classification section is at R316 vintage with an in-page "treat this as
  canonical" hedge (the one self-aware doc), but the type-classification section,
  the leaf reference tables, and the source map document `RootField`, the
  `InputType` four-arm split, and `UnclassifiedType`/`UnclassifiedField` with no
  forward notes. The doc's drift guards pin the corpus-backed worked examples, not
  the leaf tables.
- **`reference/argument-resolution.adoc`**: presents `ArgumentRef.TableInputArg` /
  `PlainInputArg` and the `InputField` sealed family (verbatim entries of R222's
  Stage 5 retirement list) as current design detail.
- **`index.adoc:51`**: routes readers to "the classification taxonomy" as the thing
  to read; there is no route from the docs site to the target architecture at all.
- **Code-level doc rot**: `MethodBackedField` Javadoc (see Stage 2 above).

No third vision was found: the docs either describe current code or trail R222's
in-flight stages under older vocabulary. The risk is canonization of the present
(new contributors and reviewer sessions reading `dispatch-axes.adoc` will defend
`SourceKey`'s current shape), not a competing direction. R207
(`design-doc-implementation-conformance-audit`, Backlog) is the natural home for
the remediation pass.

## 4. Divergence between the two target items themselves

- **R314 is written in a dead vocabulary.** Its design section still targets
  `Carrier.Source` / `Intent` / `Mapping` and
  `f(carrier.sourceContext, intent.operation, mapping.target)`; R316 deleted those
  types. The staleness audit flags this for re-spec; R333 goes further: "this spec
  likely reframes R314's plan or feeds it directly; sequence to be decided"
  (R333:1757). Until that sentence is resolved, the emit re-platforming has two
  half-owners and no actionable spec.
- **R222's Stage 3 sketch is partially superseded by R333.** R222's carrier table
  still plans `ValidationShape` / `Pagination` / `Ordering` / `PredicateCarrier` /
  `InsertRows` / `UpdateRows` slots; R333's fact catalog redistributes several of
  these (generated conditions become operation rows minted from input coordinates;
  ordering becomes `operation: orderBy` payload; the lookup partition becomes the
  `Lookup` operation). R222 self-describes as "direction, not contract", but a
  reader today cannot tell which Spec document governs the back half of Stage 3.
  R333 names its relationships to R316 and R314 but never states its relationship
  to the R222 umbrella it grew out of.
- **Two small R222 claims did not land as written**: the `walkerDiagnostics` slot
  on `ValidationReport` and the `tags` component on `Diagnostic` (section 1,
  Stage 4 above).

## 5. Where the leverage is

Ordered reading of the above, for effort planning; the decision itself is the
maintainer's.

1. **Settle the R333 Ready gate and re-spec R314 onto it.** The model spec is
   essentially complete (every directive has an owning fact; the remaining open
   questions are enumerated). The two blocking forks are the R314 relationship and
   the re-query unification question, which decides whether
   `SplitTableField`/`RecordTableField` collapse to one emit unit. This is prose
   work with outsized downstream effect: it converts two mid-pivot documents into
   one implementable item.
2. **Ship the beachhead: the `SplitTableField`/`RecordTableField` collapse.**
   R333:1740-1746 names it the cheapest honest demonstration: both already lower to
   the same `load<X>` rows-method, Split's only extra (the parent-key projection)
   already lands via `collectRequiredProjectionColumns`, and it retires one
   cross-product axis with no generator rewrite while exercising the
   address-as-name-resolution primitive exactly once. Thread I's bidirectional
   closure invariant is its natural acceptance test.
3. **The `SourceKey` decomposition is the enabling mechanical step.** It is
   precisely "move the source-read facts to the source endpoint" that the emit
   re-platforming needs, the staleness audit's named next pivot, and two live
   correctness items (R425, R426) sit on its surface, so it pays down bugs and
   architecture at once. R71, R234, R314, R425, R426 all re-anchor when it lands.
4. **Guard the projection seam in every dissolution slice.** When a leaf retires,
   `CatalogBuilder.projectFieldClassification` and siblings must re-source from the
   facts with their compile-checked coverage intact (R333's single load-bearing
   requirement). Cheap to enforce per slice; expensive to retrofit if missed.
5. **Docs: banner-first remediation, not a rewrite.** The architecture is
   mid-pivot, so rewriting docs to the target now would just create a second drift
   surface. Add forward notes at the four canonizing sites (`dispatch-axes.adoc`,
   the two `rewrite-design-principles.adoc` exemplars, `pipeline-overview.adoc`'s
   validator line, the leaf tables in `code-generation-triggers.adoc` /
   `argument-resolution.adoc`), fix the `MethodBackedField` Javadoc, and give
   `index.adoc` a pointer to R222/R333 as the stated direction. Rides R207.
6. **Defer the input side (Stages 5-7, R335) until the output-side dissolution
   proves the pattern**, unless a second parallel workstream is wanted; R335 is
   driver-side and independent of the emit re-platforming.

---

_Review date: 2026-07-04. Implementation facts verified against HEAD `28a5b56`;
doc findings against the same tree; R333 section/line references against the
2026-06-25 revision of the item file._
