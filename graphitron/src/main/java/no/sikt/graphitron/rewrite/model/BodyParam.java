package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * One parameter of a generated condition predicate, as seen from the body side. Carries what
 * the condition producer ({@link no.sikt.graphitron.plan.ConditionCommands}) needs to turn one
 * filter argument into a column term the glue renderer renders directly.
 *
 * <p>{@link #nonNull()} is the effective runtime nullability at the call site: the AND of
 * the binding source's own declared nullability and every enclosing link's nullability
 * (top-level argument plus each intermediate {@link InputField.NestingField}). The producer
 * ({@code FieldBuilder.projectFilters} for top-level scalar args,
 * {@code FieldBuilder.walkInputFieldConditions} for nested input fields) computes the
 * conjunction; the emitter assumes non-null when the flag is {@code true} and emits an
 * unguarded {@code condition.and(...)}, and wraps the condition in a null check when
 * {@code false}. The flag is NOT the binding's own SDL-declared nullability.
 *
 * <p>The parallel call-site view of this parameter is {@link CallParam}. A {@link BodyParam}
 * and its corresponding {@link CallParam} share the same {@code name} and {@code extraction}.
 */
public sealed interface BodyParam permits BodyParam.ColumnPredicate, BodyParam.RemoteColumnPredicate {

    /** Parameter name (matches the GraphQL input field name). */
    String name();

    /** Whether the parameter is a list (drives the call-site extraction shape too). */
    boolean list();

    /** See {@link BodyParam} for the producer / emitter contract. */
    boolean nonNull();

    /** How to extract the value at the fetcher call site (NestedInputField for input-type fields). */
    CallSiteExtraction extraction();

    /**
     * A column-shaped predicate body. The operator and value-arity are captured by the variant
     * identity rather than a {@code boolean list} flag plus a uniform record, and each arm
     * carries exactly the columns it needs, so the body emitter switches exhaustively over the
     * four arms with no arity or list ladders; a new operator is a new sealed arm plus an
     * emitter switch arm.
     */
    sealed interface ColumnPredicate extends BodyParam permits Eq, In, RowEq, RowIn {}

    /** Single-column scalar equality. Emits {@code table.col.eq(arg)}. */
    record Eq(
        String name,
        ColumnRef column,
        String javaType,
        boolean nonNull,
        CallSiteExtraction extraction
    ) implements ColumnPredicate {
        @Override public boolean list() { return false; }
    }

    /** Single-column IN. Emits {@code table.col.in(arg)}. */
    record In(
        String name,
        ColumnRef column,
        String javaType,
        boolean nonNull,
        CallSiteExtraction extraction
    ) implements ColumnPredicate {
        @Override public boolean list() { return true; }
    }

    /**
     * Composite-key single-tuple equality. Emits
     * {@code DSL.row(c1, ..., cN).eq(DSL.row(v1, ..., vN))}. {@code columns.size() >= 2}; the
     * arity-1 case routes to {@link Eq}. The method parameter type is the typed
     * {@code Row<N><T1, ..., TN>} computed from {@code columns} at emit time.
     */
    record RowEq(
        String name,
        List<ColumnRef> columns,
        boolean nonNull,
        CallSiteExtraction extraction
    ) implements ColumnPredicate {

        public RowEq {
            columns = List.copyOf(columns);
            if (columns.size() < 2) {
                throw new IllegalArgumentException(
                    "BodyParam.RowEq requires arity >= 2 (got " + columns.size() + "); arity-1 routes to Eq");
            }
        }

        @Override public boolean list() { return false; }
    }

    /**
     * Composite-key row-IN. Emits {@code DSL.row(c1, ..., cN).in(rows)}.
     * {@code columns.size() >= 2}; the arity-1 case routes to {@link In}. The method parameter
     * type is {@code List<Row<N><T1, ..., TN>>} computed from {@code columns} at emit time.
     */
    record RowIn(
        String name,
        List<ColumnRef> columns,
        boolean nonNull,
        CallSiteExtraction extraction
    ) implements ColumnPredicate {

        public RowIn {
            columns = List.copyOf(columns);
            if (columns.size() < 2) {
                throw new IllegalArgumentException(
                    "BodyParam.RowIn requires arity >= 2 (got " + columns.size() + "); arity-1 routes to In");
            }
        }

        @Override public boolean list() { return true; }
    }

    /**
     * A column predicate whose target column lives on a joined table, reached from the field's
     * own table through a {@code @reference(path:)} join path. The wrapped {@link #inner}
     * predicate is an ordinary {@link ColumnPredicate} whose {@link ColumnRef}s are bound to
     * the terminal table; {@link #joinPath} carries how to reach that table.
     *
     * <p>{@link no.sikt.graphitron.render.ConditionGlueRenderer} emits this as a correlated
     * {@code DSL.exists(...)} ANDed into the glue method's condition. The call-site extraction
     * and null / empty-list guards are identical to the {@link #inner} local predicate; only
     * the SQL shape differs, so every accessor delegates to {@code inner}.
     *
     * <p>The wrapping keeps the local-vs-remote axis off the operator/value-arity
     * {@link ColumnPredicate} taxonomy, mirroring how {@link FkTargetConditionFilter} wraps a
     * {@link ConditionFilter} rather than bolting a {@code joinPath} field onto it.
     */
    record RemoteColumnPredicate(
        List<JoinStep> joinPath,
        ColumnPredicate inner
    ) implements BodyParam {

        public RemoteColumnPredicate {
            joinPath = List.copyOf(joinPath);
            if (joinPath.isEmpty()) {
                throw new IllegalArgumentException(
                    "BodyParam.RemoteColumnPredicate requires a non-empty joinPath; an empty path "
                    + "means the column is local and should be the bare inner ColumnPredicate");
            }
        }

        @Override public String name() { return inner.name(); }
        @Override public boolean list() { return inner.list(); }
        @Override public boolean nonNull() { return inner.nonNull(); }
        @Override public CallSiteExtraction extraction() { return inner.extraction(); }
    }

}
