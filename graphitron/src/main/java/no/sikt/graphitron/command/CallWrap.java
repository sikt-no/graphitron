package no.sikt.graphitron.command;

import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ParentCorrelation;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.List;
import java.util.Objects;

/**
 * How a {@link Contribution.Call}'s callee fields arrive in this unit's projection. The axis is
 * row identity, not provenance: {@link Splice} when the callee projects the <em>same row</em>
 * (its terms merge into this list), a multiset arm when the callee projects <em>other rows</em>
 * (its terms sit inside one correlated subselect field, aliased by result key).
 *
 * <p>The three multiset arms differ by SQL shape, per the term-arm rule: {@link Multiset} is the
 * {@code @reference}-path correlated subquery, {@link LookupMultiset} additionally narrows by a
 * {@code @lookupKey} VALUES + JOIN keyset (with a generated per-field rows helper and an
 * empty-input short circuit no plain multiset has), and {@link PivotMultiset} correlates the
 * attribute table by the pivot's single FK hop with no join chain at all.
 *
 * <p>The multiset arms carry the model's own join vocabulary verbatim ({@link JoinStep},
 * {@link ParentCorrelation}, {@link OrderBySpec} ride the borrow dial) rather than a narrowed
 * copy: what shape a launcher-of-a-correlated-subquery vocabulary should have is the launcher
 * family's decision, and pre-narrowing here would convert accepted, emitting shapes (condition
 * joins, lateral routine hops) into producer throws.
 */
public sealed interface CallWrap {

    /**
     * Same row: the callee is a nesting unit sharing this unit's table instance, so its returned
     * fields are added as they are ({@code fields.addAll(<Callee>.$project(merged, table, env))}).
     */
    record Splice() implements CallWrap {}

    /**
     * Other rows over a {@code @reference} path (or standalone against the callee's own table
     * when {@code path} is empty): exactly one projected field, a
     * {@code DSL.multiset(<inner select>).as("__rk_" + key)} whose inner select list is the
     * callee's projection.
     *
     * @param path            the resolved join path; empty for the standalone shape (start table
     *                        equals target table)
     * @param correlation     the step-0 parent correlation; {@code null} exactly when
     *                        {@code path} is empty (the standalone shape has no parent
     *                        correlation), mirroring the model's carrier invariant
     * @param terminalTable   the callee's table (the subselect's FROM when the path is empty,
     *                        and the alias-naming read for the terminal hop otherwise)
     * @param arity           result cardinality; {@link Arity#SINGLE} caps the inner select at
     *                        {@code .limit(1)}
     * @param orderBy         the field's authoritative ordering; only the
     *                        {@link OrderBySpec.Fixed} arm renders inline, matching the emit
     *                        this command replaced
     * @param limitByFirstArgument whether the inner select honours a runtime {@code first}
     *                        argument read off the canonical occurrence (list cardinality only)
     * @param filter          the coordinate's condition glue call, or {@code null} when the
     *                        field carries no filters
     * @param guardArguments  whether the arm must assert occurrence-argument consistency before
     *                        serving the canonical occurrence's arguments (true exactly when the
     *                        emitted arm reads runtime state off the canonical
     *                        {@code SelectedField})
     */
    record Multiset(
        List<JoinStep> path,
        ParentCorrelation correlation,
        TableRef terminalTable,
        Arity arity,
        OrderBySpec orderBy,
        boolean limitByFirstArgument,
        GlueCall filter,
        boolean guardArguments
    ) implements CallWrap {
        public Multiset {
            path = List.copyOf(path);
            Objects.requireNonNull(terminalTable, "terminalTable");
            Objects.requireNonNull(orderBy, "orderBy");
            Objects.requireNonNull(arity, "arity");
            if (path.isEmpty() && correlation != null) {
                throw new IllegalArgumentException(
                    "Multiset with an empty path is the standalone shape and carries no parent "
                    + "correlation; got " + correlation.getClass().getSimpleName());
            }
            if (!path.isEmpty() && correlation == null) {
                throw new NullPointerException(
                    "Multiset with a non-empty path requires a parent correlation");
            }
            if (arity == Arity.SINGLE && limitByFirstArgument) {
                throw new IllegalArgumentException(
                    "Multiset cannot both cap at limit(1) and honour a runtime first argument");
            }
        }
    }

    /**
     * Other rows narrowed by a {@code @lookupKey} VALUES + JOIN keyset. The generated
     * {@code inputRowsHelper} on this unit's class builds the typed {@code Row<N+1>[]} off the
     * canonical occurrence's arguments; an empty input short-circuits in Java to a
     * {@code falseCondition()} multiset (jOOQ rejects {@code DSL.values([])}), keeping the
     * aliased slot on the parent record. Ordering is structurally {@code input.idx} (input-row
     * order); the occurrence-argument guard is structurally unconditional because the
     * {@code @lookupKey} read is what the arm is.
     *
     * @param path           the resolved join path; may be empty (standalone lookup)
     * @param correlation    the step-0 parent correlation; {@code null} exactly when
     *                       {@code path} is empty
     * @param terminalTable  the callee's table (the lookup keyset's target)
     * @param mapping        the {@code @lookupKey} column mapping the VALUES keyset and the
     *                       rows helper are built from
     * @param inputRowsHelper the per-field rows-helper method generated onto this unit's class
     * @param filter         the coordinate's non-key condition glue call, or {@code null}
     */
    record LookupMultiset(
        List<JoinStep> path,
        ParentCorrelation correlation,
        TableRef terminalTable,
        LookupMapping.ColumnMapping mapping,
        UnitMethodRef inputRowsHelper,
        GlueCall filter
    ) implements CallWrap {
        public LookupMultiset {
            path = List.copyOf(path);
            Objects.requireNonNull(terminalTable, "terminalTable");
            Objects.requireNonNull(mapping, "mapping");
            Objects.requireNonNull(inputRowsHelper, "inputRowsHelper");
            if (path.isEmpty() && correlation != null) {
                throw new IllegalArgumentException(
                    "LookupMultiset with an empty path is the standalone shape and carries no "
                    + "parent correlation; got " + correlation.getClass().getSimpleName());
            }
            if (!path.isEmpty() && correlation == null) {
                throw new NullPointerException(
                    "LookupMultiset with a non-empty path requires a parent correlation");
            }
        }
    }

    /**
     * The {@code @pivot} envelope: one multiset over the attribute table correlated by the
     * pivot's single FK hop, whose inner select list is the pivot unit's projection (the
     * selected slots' filtered aggregates). The aggregate over the correlated set collapses to
     * one row on its own, so no arity slot: single by construction.
     *
     * @param attributeTable the attribute table the callee projects against
     * @param correlation    the parent-to-attribute-table FK column pairs
     */
    record PivotMultiset(
        TableRef attributeTable,
        On.ColumnPairs correlation
    ) implements CallWrap {
        public PivotMultiset {
            Objects.requireNonNull(attributeTable, "attributeTable");
            Objects.requireNonNull(correlation, "correlation");
        }
    }
}
