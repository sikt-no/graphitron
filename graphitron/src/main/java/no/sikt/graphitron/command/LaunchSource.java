package no.sikt.graphitron.command;

import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.TableExpr;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.List;
import java.util.Objects;

/**
 * How a launcher's rows are sourced and projected: for the SQL-composing arms, the FROM
 * clause's origin and the select list's derivation, one axis because the two co-vary (a routine
 * chain's projection targets the terminus alias, never a declared table local; the discriminated
 * arm has no single projection unit at all); for the {@code @service} arms, the developer
 * method whose invocation IS the sourcing ({@link ServiceCall} delegates outright,
 * {@link ServiceTableLift} re-projects the returned records). Absorbing both facts into the arm
 * keeps the illegal cells unrepresentable and spares the command a table slot whose meaning
 * would change per arm. The {@link Reentry} arms source rows from the mutation's captured
 * {@code RETURNING} keys: the write already happened, and the launcher re-selects the payload
 * by key.
 */
public sealed interface LaunchSource {

    /**
     * The plain origin: one {@code @table}-bound anchor, declared as the body's table local,
     * projected through the one {@link #projection} unit's {@code $project}.
     */
    record AnchorTable(TableRef table, UnitRef projection) implements LaunchSource {
        public AnchorTable {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(projection, "projection");
        }
    }

    /**
     * A {@code @routine} chain: the FROM source is the bound table-valued function (the start
     * expression, IN parameters bound from field arguments), hops join forward out of the
     * routine result, and {@link #projection} targets the <em>terminus</em> (the last hop's
     * alias, or the start itself when hop-less; the classifier's terminus invariant pins the
     * projected type to the chain's last node). The hop list is the narrowed
     * {@link JoinStep.Hop} form the model's chain constructor guarantees, so the renderer never
     * casts.
     */
    record RoutineChain(TableExpr.RoutineCall start, List<JoinStep.Hop> hops, UnitRef projection)
            implements LaunchSource {
        public RoutineChain {
            Objects.requireNonNull(start, "start");
            hops = List.copyOf(hops);
            Objects.requireNonNull(projection, "projection");
        }
    }

    /**
     * The correlated-chain capability shared by the batched child arms: the terminal
     * {@link #table}, the hop chain and the step-0 {@link #correlation}. Exactly the topology
     * the batched renderer's prelude, parent-input attach and WHERE fold read; the arms fork
     * only where the SQL genuinely differs (the lookup sibling's second VALUES join, the
     * discriminated arm's participant-driven select list).
     *
     * <p>The select list is deliberately <em>not</em> on this capability. Two arms project one
     * unit's {@code $project} and declare it as a component; the discriminated arm has no
     * single projection unit at all (the same fact {@link DiscriminatedTable} states), so a
     * {@code projection()} member here would have no answer on a third of the population.
     */
    sealed interface Correlated extends LaunchSource
            permits Correlated.Projected, DiscriminatedCorrelatedChain {
        TableRef table();

        List<JoinStep> joinPath();

        no.sikt.graphitron.rewrite.model.ParentCorrelation correlation();

        /**
         * The two correlated arms whose select list is one unit's {@code $project} over the
         * terminal alias. The intermediate seal is what keeps {@link #projection()} total where
         * it is declared instead of unanswerable on the discriminated sibling.
         */
        sealed interface Projected extends Correlated
                permits CorrelatedChain, CorrelatedLookupChain {
            UnitRef projection();
        }
    }

    /**
     * A batched child's sourcing: the composition anchors on the parent-input VALUES derived
     * table (the batch keys, built by the delivery arm's key facts), attaches the chain's first
     * hop per {@link #correlation}'s arm, joins the remaining {@link #joinPath} hops forward,
     * and projects the <em>terminal</em> table through {@link #projection}'s {@code $project}.
     * The correlation and the hop list are borrowed whole (the correlation's four arms decide
     * the step-0 attach and the parent-side columns; each hop's {@code On} decides its own
     * join form), so the renderer reads the same facts the inline child's projection wrap
     * reads, one derivation per fact.
     */
    record CorrelatedChain(TableRef table, UnitRef projection, List<JoinStep> joinPath,
            no.sikt.graphitron.rewrite.model.ParentCorrelation correlation)
            implements Correlated.Projected {
        public CorrelatedChain {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(projection, "projection");
            joinPath = List.copyOf(joinPath);
            Objects.requireNonNull(correlation, "correlation");
        }
    }

    /**
     * A batched {@code @lookupKey} child: {@link CorrelatedChain}'s topology narrowed further by
     * the {@code @lookupKey} VALUES derived table joined against the terminal on
     * {@link #mapping}'s key columns. Carries what neither sibling alone can:
     * {@link KeyedLookup} holds the mapping and {@link #inputRows} ref but no hop chain;
     * {@link CorrelatedChain} holds the chain but no key mapping. The division mirrors the
     * root's: key arguments ride the mapping's VALUES rows (built by the emitted
     * {@link #inputRows} helper, whose ref is minted beside the row's own), never the WHERE
     * slot. An empty lookup input short-circuits to per-key empty results before any SQL,
     * arm-entailed like the {@code idx} scatter column.
     */
    record CorrelatedLookupChain(TableRef table, UnitRef projection, List<JoinStep> joinPath,
            no.sikt.graphitron.rewrite.model.ParentCorrelation correlation,
            LookupMapping.ColumnMapping mapping, UnitMethodRef inputRows)
            implements Correlated.Projected {
        public CorrelatedLookupChain {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(projection, "projection");
            joinPath = List.copyOf(joinPath);
            Objects.requireNonNull(correlation, "correlation");
            Objects.requireNonNull(mapping, "mapping");
            Objects.requireNonNull(inputRows, "inputRows");
        }
    }

    /**
     * A batched child returning a single-table discriminated interface: {@link CorrelatedChain}'s
     * topology (the parent-input VALUES anchor, the step-0 attach, the forward hops) with the
     * {@link #discriminated} arm's participant-driven select list in place of one unit's
     * {@code $project}, borrowed whole the way {@link DiscriminatedReentry} borrows it. The two
     * halves are orthogonal, which is why the arm composes rather than duplicates: the batch
     * grain never reaches the select list (every participant scalar rides it as a capped
     * correlated subselect against the base row, every join it emits is the joined-detail
     * 1:0..1 hop), and the discriminator restriction never reaches the topology.
     *
     * <p>{@link #table()} answers the discriminated base table, the terminal the chain's single
     * FK hop lands on and the local the assembly addresses.
     */
    record DiscriminatedCorrelatedChain(DiscriminatedTable discriminated, List<JoinStep> joinPath,
            no.sikt.graphitron.rewrite.model.ParentCorrelation correlation) implements Correlated {
        public DiscriminatedCorrelatedChain {
            Objects.requireNonNull(discriminated, "discriminated");
            joinPath = List.copyOf(joinPath);
            Objects.requireNonNull(correlation, "correlation");
        }

        @Override
        public TableRef table() {
            return discriminated().table();
        }
    }

    /**
     * A {@code @service} record child: the rows method delegates outright to the developer's
     * method, whose declared return type IS the rows method's return type (the classifier
     * acceptance enforces strict equality with the loader-container wrap, so the borrowed
     * {@link #method} carries the shape and no derivation runs here). Whether a {@code dsl}
     * local exists at all derives from the borrowed
     * {@code method().callShape()} (the one arm where the rendered method may bind no
     * {@code DSLContext}); the call expression itself is shell-composed argument assembly
     * handed to the renderer as a fragment, the dsl declaration's carve-out. The rendered body
     * deliberately has no empty-keys gate: adding one to a service rows method would change
     * behaviour (the developer method owns the empty batch).
     */
    record ServiceCall(no.sikt.graphitron.rewrite.model.MethodRef.Service method)
            implements LaunchSource {
        public ServiceCall {
            Objects.requireNonNull(method, "method");
        }
    }

    /**
     * A {@code @service} table child: the developer's method produces real table records; the
     * rows method lifts them back by re-projecting each returned record's primary key by
     * identity through {@link #projection}'s {@code $project} over an {@code (idx, seq, pk...)}
     * VALUES join against {@link #table}, so multiset sub-fields resolve off the projected row
     * where the verbatim service return carried only stored columns. The {@code seq} ordering
     * (each parent bucket keeps the service's flatten order) and the {@code dsl} local are
     * arm-entailed: the lift always SELECTs. Like {@link ServiceCall}, the call expression is
     * the shell's fragment and the body has no empty-keys gate.
     */
    record ServiceTableLift(no.sikt.graphitron.rewrite.model.MethodRef.Service method,
            TableRef table, UnitRef projection) implements LaunchSource {
        public ServiceTableLift {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(projection, "projection");
        }
    }

    /**
     * A batched {@code @pivot} child: the parent-input VALUES table LEFT JOINs the attribute
     * {@link #pivotTable} over {@link #correlation}'s FK slots, and the batch collapses to one
     * aggregate row per key under {@code GROUP BY} on the idx column. Both are source-entailed,
     * the discriminated arm's IN-restriction reasoning: the key-preserving left join and the
     * per-key group are what make the arm's one-record-per-parent invariant hold (a row-less
     * parent keeps its group and scatters to one record of null slots), so neither rides the
     * WHERE slot or the result shape. The select list is {@link #projection}'s {@code $project},
     * the coordinate-grain pivot unit the inline delivery's multiset arm shares. The correlation
     * is the narrowed {@link no.sikt.graphitron.rewrite.model.ParentCorrelation.OnFkSlots}: a
     * {@code @pivot} path is a single unfiltered FK hop by
     * {@link no.sikt.graphitron.rewrite.model.PivotSpec}'s own pin, so the parent-anchor
     * topology is unrepresentable here, and the hop is not carried beside the correlation (it
     * is the correlation's own {@code firstHop()}; a second slot would denormalise the same
     * column pairs unbound).
     */
    record PivotAggregate(TableRef pivotTable, UnitRef projection,
            no.sikt.graphitron.rewrite.model.ParentCorrelation.OnFkSlots correlation)
            implements LaunchSource {
        public PivotAggregate {
            Objects.requireNonNull(pivotTable, "pivotTable");
            Objects.requireNonNull(projection, "projection");
            Objects.requireNonNull(correlation, "correlation");
        }
    }

    /**
     * A {@code @lookupKey} root: the anchor {@link #table} joined to the input VALUES derived
     * table over the mapping's key columns, projected through the one {@link #projection} unit's
     * {@code $project}. The key arguments ride {@link #mapping}'s VALUES rows (built by the
     * emitted {@link #inputRows} helper, whose ref is minted beside the launcher's own), never
     * the WHERE slot, which stays purely condition glue for the coordinate's non-key filters.
     * The composition is input-ordered by the derived table's {@code idx} column, a
     * source-entailed ordering (the lookup's one-row-per-input-key contract), so the result
     * shape's ordering slot stays absent, the same division that keeps the discriminated arm's
     * {@code IN} restriction off the WHERE slot.
     */
    record KeyedLookup(TableRef table, UnitRef projection, LookupMapping.ColumnMapping mapping,
            UnitMethodRef inputRows) implements LaunchSource {
        public KeyedLookup {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(projection, "projection");
            Objects.requireNonNull(mapping, "mapping");
            Objects.requireNonNull(inputRows, "inputRows");
        }
    }

    /**
     * The reentry capability shared by the keyed companions: the launcher's FROM anchors on the
     * {@link #table} the caller captured keys for, restricted to exactly those keys by
     * {@link #correlation}'s lifted slots (single: key equality; list: an {@code (idx, keys...)}
     * VALUES join, input-ordered by {@code idx}). Both callers derive their key list from the
     * same correlation the companion's parameter type reads (the write emitter its
     * {@code RETURNING} columns, the root {@code @service} fetcher the columns it lifts off the
     * returned records), so the keys handed across the generated call boundary are
     * assignment-compatible by construction, not by javadoc agreement.
     */
    sealed interface Reentry extends LaunchSource permits ProjectedReentry, DiscriminatedReentry {
        TableRef table();

        no.sikt.graphitron.rewrite.model.ParentCorrelation.OnLiftedSlots correlation();
    }

    /**
     * A projected companion: the returned type is one table-backed object, re-selected through
     * the {@link #projection} unit's {@code $project} over the key restriction. The table is the
     * correlation's own target (the keys are the payload table's own key for this arm, whether
     * the write returned them or the fetcher lifted them off a service's records), so no second
     * slot restates it. Deliberately no empty-input gate: the caller owns it (the write emitter's
     * no-match guard; the root {@code @service} fetcher's null-or-empty gate), and the companion
     * is only called with captured keys.
     */
    record ProjectedReentry(UnitRef projection,
            no.sikt.graphitron.rewrite.model.ParentCorrelation.OnLiftedSlots correlation)
            implements Reentry {
        public ProjectedReentry {
            Objects.requireNonNull(projection, "projection");
            Objects.requireNonNull(correlation, "correlation");
        }

        @Override
        public TableRef table() {
            return correlation().targetTable();
        }
    }

    /**
     * A discriminated mutation companion: the returned type is a single-table interface, so the
     * payload re-select is the {@link #discriminated} arm's participant-driven composition (the
     * payload borrowed whole, the {@code FacetPlan.Entry} precedent) with the key restriction
     * seeded into the condition ahead of the discriminator {@code IN}.
     */
    record DiscriminatedReentry(DiscriminatedTable discriminated,
            no.sikt.graphitron.rewrite.model.ParentCorrelation.OnLiftedSlots correlation)
            implements Reentry {
        public DiscriminatedReentry {
            Objects.requireNonNull(discriminated, "discriminated");
            Objects.requireNonNull(correlation, "correlation");
        }

        @Override
        public TableRef table() {
            return discriminated().table();
        }
    }

    /**
     * A single-table discriminated interface: the FROM source is the shared base {@link #table},
     * the select list is participant-driven (no single projection unit), and the arm carries the
     * source-entailed discriminator restriction ({@link #discriminatorColumn} {@code IN}
     * {@link #knownValues}, ANDed into the condition by the renderer) so the command's WHERE slot
     * stays purely condition glue.
     *
     * <p>{@link #baseSlice} is the joined-table participants' base-resident SELECT terms as one
     * whole-query fact: the dedup (first participant wins, one namespace across both term kinds)
     * and the ordering (schema field order across participants) are properties of the query's
     * SELECT list, not of any one participant, so the slot lives at the query's grain and the
     * renderer folds it without re-deriving either. Derived once post-walk
     * (the schema's joined-table reprojection index); the producer copies it here.
     *
     * <p>{@link #branches} carries the per-participant facts, one branch per table-backed
     * participant in declaration order (non-table members of a discriminated interface are
     * rejected at the parse boundary, so the two arms are total over the population).
     */
    record DiscriminatedTable(TableRef table, ColumnRef discriminatorColumn, List<String> knownValues,
            List<BaseSliceTerm> baseSlice, List<Branch> branches,
            SelectionRestriction selectionRestriction) implements LaunchSource {
        public DiscriminatedTable {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(discriminatorColumn, "discriminatorColumn");
            Objects.requireNonNull(selectionRestriction, "selectionRestriction");
            knownValues = List.copyOf(knownValues);
            baseSlice = List.copyOf(baseSlice);
            branches = List.copyOf(branches);
        }

        /**
         * How the fold scopes each participant's {@code $project} input, as one whole-query fact.
         *
         * <p>The fold hands every branch the <em>same</em> grouped selection, so a participant's
         * arm fires for a result key another participant's fragment selected. That is harmless
         * while every arm mints the same alias over the same occurrence set, and wrong data the
         * moment either half diverges: an aliased jOOQ field compares equal on its alias alone, so
         * two arms rendering different SQL under one alias collapse to whichever landed first.
         *
         * <p>Hence the invariant this slot exists to make structural: <em>a shared alias requires
         * a shared occurrence set.</em> {@link #perTypeFieldNames} is exactly the field names whose
         * alias the participant type qualifies (their stamped
         * {@link no.sikt.graphitron.rewrite.model.AliasOwner} is the participant's own type name),
         * so those and only those get their occurrences filtered to the declaring participant. A
         * name every arm aliases identically, whether an interface-declared key qualified by the
         * interface or a bare key a spliced nesting unit contributes, keeps every occurrence in
         * every arm, which is what it has always done. An empty set means the fold restricts
         * nothing, the pre-existing behaviour, and every schema with no participant-local
         * result-key-aliased field lands there.
         *
         * <p>{@link #helper} is the generated {@code PolymorphicSelectionSet} view the restriction
         * runs through; a bare filtered map would not survive the {@code $project} contract, which
         * recurses through {@code SelectedField.getSelectionSet()}. It is the same view, and the
         * same call, the multi-table stage-2 per-typename SELECT already feeds that contract.
         */
        public record SelectionRestriction(UnitRef helper, List<String> perTypeFieldNames) {
            public SelectionRestriction {
                Objects.requireNonNull(helper, "helper");
                perTypeFieldNames = List.copyOf(perTypeFieldNames);
            }

            /** {@code true} when the fold hands every branch the unrestricted selection. */
            public boolean isEmpty() {
                return perTypeFieldNames.isEmpty();
            }
        }

        /**
         * One table-backed participant's contribution to the discriminated query, embedding the
         * borrowed {@link ParticipantRef} variant (type name, discriminator value, cross-table
         * fields, child-to-parent hop and alias formulas ride the ref) plus the facts the ref
         * cannot carry.
         *
         * <p>A participant without a {@code @discriminator} value classifies and reaches these
         * arms today (nothing rejects the omission upstream); its projection contribution
         * renders but its gated JOIN arms are skipped, mirroring the legacy assembly. The
         * renderer holds that one gate; see the launcher item's hand-off for the parse-time
         * rejection this deserves.
         */
        public sealed interface Branch {

            /**
             * A single-table participant: its data lives on the shared base, projected through
             * the one {@link #projection} unit's {@code $project}, plus its
             * {@link #crossTableTerms} for the fields whose value lives one {@code @reference}
             * hop off the base.
             */
            record SingleTable(ParticipantRef.TableBound participant, UnitRef projection,
                    List<CrossTableTerm> crossTableTerms) implements Branch {
                public SingleTable {
                    Objects.requireNonNull(participant, "participant");
                    Objects.requireNonNull(projection, "projection");
                    crossTableTerms = List.copyOf(crossTableTerms);
                }

                /**
                 * One cross-table participant field, lowered to the SELECT-list term that
                 * resolves it. {@link #fieldName} is what the runtime selection gate tests
                 * ({@code <Type>.<fieldName>}); the term carries the hop, the fixed projected
                 * alias the participant's per-field fetcher reads back, and the branch's
                 * discriminator gate, which makes a non-matching row project NULL.
                 *
                 * <p>The term is a capped correlated subselect, the generator's standing shape
                 * for a scalar one hop away, rather than a join into the row-producing
                 * statement: a subselect cannot multiply the enclosing statement's rows whatever
                 * the hop's cardinality, which is what lets the assembly's statement be
                 * paginated and keeps the participant's entity grain intact on every other
                 * shape.
                 */
                public record CrossTableTerm(String fieldName, SelectTerm.ScalarSubselect term) {
                    public CrossTableTerm {
                        Objects.requireNonNull(fieldName, "fieldName");
                        Objects.requireNonNull(term, "term");
                        if (term.asName() == null) {
                            throw new IllegalArgumentException(
                                "CrossTableTerm.term must carry a fixed projected name: the "
                                + "interface fetcher reads the value back by that alias, and the "
                                + "assembly has no result-key loop to alias against");
                        }
                    }
                }
            }

            /**
             * A joined-table participant: its base-resident terms sit in the arm's
             * {@link DiscriminatedTable#baseSlice}; {@link #detailFields} are its
             * detail-exclusive columns, projected against the participant's detail alias behind
             * the discriminator-gated LEFT JOIN. Per-participant (never deduped across
             * participants), in schema field order.
             */
            record JoinedDetail(ParticipantRef.JoinedTableBound participant,
                    List<DetailField> detailFields) implements Branch {
                public JoinedDetail {
                    Objects.requireNonNull(participant, "participant");
                    detailFields = List.copyOf(detailFields);
                }
            }
        }

        /**
         * One base-resident SELECT term of a joined-table participant, forked on how the
         * generated reader addresses it (which entails the emission shape: a runtime
         * result-key loop versus one unconditional aliased projection).
         */
        public sealed interface BaseSliceTerm {

            /**
             * An inherited base-resident {@code @reference}: the base column projected once per
             * selected result-key bucket under {@link ReservedAliases#RESULT_KEY_PREFIX} plus
             * the key, matching the standalone correlated-subquery projection so the one
             * registered fetcher reads both queries' rows the same way.
             */
            record InheritedRef(String fieldName, ColumnRef baseColumn) implements BaseSliceTerm {
                public InheritedRef {
                    Objects.requireNonNull(fieldName, "fieldName");
                    Objects.requireNonNull(baseColumn, "baseColumn");
                }
            }

            /**
             * A shared-key column (a child-to-parent hop column, present on both base and
             * detail): the paired base-side column projected under the detail column's SQL name
             * (a no-op alias when the two share a name) so NULL-through rows still resolve it
             * and the participant's plain column fetcher reads it back unchanged.
             */
            record SharedKey(ColumnRef baseColumn, String alias) implements BaseSliceTerm {
                public SharedKey {
                    Objects.requireNonNull(baseColumn, "baseColumn");
                    Objects.requireNonNull(alias, "alias");
                }
            }
        }

        /**
         * A joined-table participant's detail-exclusive column: projected under its natural name
         * against the detail alias, gated at runtime on the type-conditioned selection
         * ({@code <Type>.<fieldName>}).
         */
        public record DetailField(String fieldName, ColumnRef column) {
            public DetailField {
                Objects.requireNonNull(fieldName, "fieldName");
                Objects.requireNonNull(column, "column");
            }
        }
    }
}
