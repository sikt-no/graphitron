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
 * How a launcher's rows are sourced and projected: the FROM clause's origin and the select
 * list's derivation, one axis because the two co-vary (a routine chain's projection targets the
 * terminus alias, never a declared table local; the discriminated arm has no single projection
 * unit at all). Absorbing both facts into the arm keeps the illegal cells unrepresentable and
 * spares the command a table slot whose meaning would change per arm.
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
     * {@link #table}, its {@link #projection} unit, the hop chain and the step-0
     * {@link #correlation}. The batched renderer's prelude, topology and WHERE fold read this
     * capability; the arms fork only where the SQL genuinely differs (the lookup sibling's
     * second VALUES join).
     */
    sealed interface Correlated extends LaunchSource permits CorrelatedChain, CorrelatedLookupChain {
        TableRef table();

        UnitRef projection();

        List<JoinStep> joinPath();

        no.sikt.graphitron.rewrite.model.ParentCorrelation correlation();
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
            no.sikt.graphitron.rewrite.model.ParentCorrelation correlation) implements Correlated {
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
            LookupMapping.ColumnMapping mapping, UnitMethodRef inputRows) implements Correlated {
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
    record DiscriminatedTable(TableRef table, String discriminatorColumn, List<String> knownValues,
            List<BaseSliceTerm> baseSlice, List<Branch> branches) implements LaunchSource {
        public DiscriminatedTable {
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(discriminatorColumn, "discriminatorColumn");
            knownValues = List.copyOf(knownValues);
            baseSlice = List.copyOf(baseSlice);
            branches = List.copyOf(branches);
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
             * the one {@link #projection} unit's {@code $project}; cross-table fields (on the
             * borrowed ref) render as discriminator-gated LEFT JOIN arms.
             */
            record SingleTable(ParticipantRef.TableBound participant, UnitRef projection)
                    implements Branch {
                public SingleTable {
                    Objects.requireNonNull(participant, "participant");
                    Objects.requireNonNull(projection, "projection");
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
