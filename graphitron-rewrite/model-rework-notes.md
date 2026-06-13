# Model rework — working notes & lifeboat (scratch, wip, off trunk)

Single source of "everything we learned" during the 2026-06-13 field-classification model rework,
plus the cleanup todolist. Companion to `verdict-table-scratch.md` (leaf-level verdicts). NOT a spec.
If context compacts, resume from here + the verdict table.

## Cleanup todolist & status

- [x] Re-table all 49→47 leaves on `carrier × intent × mapping` (see `verdict-table-scratch.md`); totality holds, no `⚠`.
- [ ] **R222** (`dimensional-model-pivot.md`): replace the "producer pipeline over `{Query, Service, Dml}`" target with the `carrier × intent × mapping` + derived-layer model. Capture the decisions ledger below. Reference the verdict table.
- [ ] **R290** (`datafetcher-field-dimensional-slots.md`): reshape from "producer pipeline in DataFetcherBuilder" to materialising `carrier × intent × mapping` + derived slots. State that `ConstructorField` dissolves and `SingleRecordTableField` collapses as part of R290.
- [ ] **R299** (`intention-classification-dimension.md`): reframe as "move the R281 corpus forward into the new model" — assert `intent` (+ `carrier`) in the corpus, migrating off the producer/mapping `LeafTupleAdapter`. Drop the "intention on top of producer" premise.
- [ ] Make `ConstructorField` dissolution + `SingleRecordTableField` collapse explicit in the tables (verdict table done; ensure R290 says it).
- [ ] Regenerate roadmap README if any front-matter/title changed.
- [ ] Commit + publish R222/R290/R299 to trunk; keep scratch files wip/off-trunk.

Open (minor, not blocking): `Nesting`'s family placement (own "structural" family vs a catalog read intent); whether R299 narrows to corpus-assert or merges forward.

## The model (target)

Three asserted axes + a derived layer. **The producer dimension is gone** — dissolved into the carrier
plus derived slots.

### carrier (= field type, = GraphQL parent-type category)
- `Query` — root Query type → `QueryField`
- `Mutation` — root Mutation type → `MutationField`
- `Source` — every other (non-Subscription) type → `SourceField`
- Carrier is **position** *and* the **legality gate** (writes only on `Mutation`; `NodeResolve`/`EntityResolve` only on `Query`; `Nesting` only on `Source`).

### intent (asserted — the operation kind)
- read: `Fetch`, `Lookup`, `NodeResolve`, `EntityResolve`, `Count`, `Facet`, `Nesting`
- write: `Insert`, `Upsert`, `Update`, `UpdateMatching`, `Delete`, `DeleteMatching`
- service: `QueryService`, `MutationService`

### mapping
- `Table`, `Column` — catalog (graphitron **builds** the SQL)
- `Record`, `Field` — domain (graphitron **consumes** a value it did not build)
- Mapping **is** the build-vs-consume axis; `Table:Column :: Record:Field` (mirror : reflect). There is no separate provider.

### derived (never asserted; computed from the above + slots)
- `FetchRelated` ← non-empty **join-path** slot (a `Fetch` that reaches a related entity via FK/`@reference`)
- **re-fetch** ← `(Service | DML intent) × Table mapping` (a domain/write producer with a catalog-table shape ⟹ re-project the table from the produced keys)
- **new-query** ← a `SourceField` slot, forced by `@splitQuery` / polymorphic UNION / record-handoff
- **polarity** (mutating?) ← the intent family (`Write` ∪ `{MutationService}` = mutating)

## Decisions ledger (the principles, in order of derivation)

1. **Producer dissolves.** Its information redistributes: position→carrier, build-vs-consume→mapping, operation→intent, new-query→derived slot. No standalone producer dimension survives.
2. **Carrier is the GraphQL parent-type category**, grounded in the spec (root Query / root Mutation / everything-else Source). It doubles as the legality gate the producer used to imply.
3. **Mapping carries build-vs-consume.** Catalog (`Table`/`Column`) = graphitron builds SQL; domain (`Record`/`Field`) = graphitron consumes a wired value. This is the catalog/domain (mirror/reflect) line settled long ago.
4. **Provider/mapping classifies graphitron's epistemic role, NOT runtime SQL location.** `@externalField` emits a jOOQ `Field<X>` that runs in the query, but graphitron only *reflects* its result (the user wrote the expression), so it is **domain** (`Field`/`Record`), not catalog. "In the SQL ≠ catalog." *(This is the load-bearing stance; ratified by the user driving the ExternalField reclassification.)*
5. **assert vs derive:** derive what another axis/slot already forces; assert only what nothing else carries. So `FetchRelated` (join-path forces it), re-fetch (intent×mapping forces it), new-query (`@splitQuery`/limitations force it), polarity (family forces it) are all **derived**.
6. **Carrier gates intent (make-illegal-states-unrepresentable):** write intents only on `Mutation`, `NodeResolve`/`EntityResolve` only on `Query`, `Nesting` only on `Source`.
7. **Enumerate legal write intents** rather than verb + a "matching" slot: there is no `InsertMatching`/`UpsertMatching`, so `{Insert, Upsert, Update, UpdateMatching, Delete, DeleteMatching}` is the legal set. **Bulk is a slot** (cardinality), not an intent (no `BulkInsert`).
8. **`Nesting` is a distinct structural operation** — produces nothing, inherits the parent's scope, regroups children. Sits *outside* read/write/service. **Asserted, not derived** from "empty join-path", because deriving-from-absence reintroduces the `No<Family>`/absence-as-domain-state shape R222 rejects.
9. **Service observability ceiling:** the service intent is the coarse polarity `{QueryService, MutationService}` only — graphitron can't know more than mutate-or-not about opaque user code. `LookupService` dropped (would need method-signature inference).
10. **`QueryService`/`MutationService`** name the parent context (read vs mutation root) that fixes polarity; transaction handling + mutation-permission validation are *derivations* of the value, realised in a later slice (R299 records the value, enforces nothing).
11. **node/entity resolvers are protocol-specific intents** (`NodeResolve` for Relay `node`/`nodes` — cardinality is a slot, not a second intent; `EntityResolve` for Federation `_entities`). NOT generalised into `Lookup`. Framework-defined operations resist generalisation — same instinct as "ConnectionType is the special thing."
12. **Model is complete; classifier coverage is partial.** Modeled-but-unpopulated intents (declared gaps, no current leaf): `EntityResolve`, `Count`, `Facet`, `UpdateMatching`, `DeleteMatching`. Model leads classifier; gaps are declared (`NO_CASE_REQUIRED`-style), never silent.
13. **`ConstructorField` dissolves** — dead since the `@record`-on-types ban; only reachable via an edge case that's not in use. *Code follow-up: delete the leaf + adapter arm after verifying no live reference (part of R290 reshape).*
14. **`SingleRecordTableField` collapses** — it's only an optimisation to skip the DataLoader for a `Source` field guaranteed a single source object; that skip is a derived detail, its verdict is the `(Service`/`DML) × Table` re-fetch. No distinct leaf.
15. **Connection protocol roles** (`edges`/`totalCount`/`facets`/`nodes`/`pageInfo`) are not current classified leaves. Classifying them = cracking the ConnectionType quarantine via role-classification (a separate item); their intents (`Count`/`Facet`) are among the declared gaps. `totalCount` = `Count`/`Column` (correlated, emit currently standalone = filed divergence).

## Carrier × intent × mapping leaf verdicts

See `verdict-table-scratch.md` for all 47 rows with derived columns (FR/RF/NQ) and the four mechanical
tests (totality passes; distinguishing relies on orthogonal slots; 5 declared-gap intents; carrier
gates intent).
