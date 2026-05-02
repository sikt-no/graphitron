package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * One parameter of a generated condition method, as seen from the method body generator.
 *
 * <p>Carries everything {@link no.sikt.graphitron.rewrite.generators.TypeConditionsGenerator}
 * needs to emit both the method signature and the method body for one filter argument:
 *
 * <ul>
 *   <li>{@link #name()} — the parameter name (matches the GraphQL argument name).</li>
 *   <li>{@link #javaType()} — the fully qualified Java type for the method parameter
 *       (e.g. {@code "java.lang.String"} for scalars, the enum class name for
 *       {@link CallSiteExtraction.EnumValueOf}).</li>
 *   <li>{@link #nonNull()} — when {@code true}, the null guard is omitted and the condition
 *       is always applied; when {@code false}, the condition is wrapped in a null check.</li>
 *   <li>{@link #list()} — whether the parameter is a list. Derived from the predicate-arm
 *       identity for {@link ColumnPredicate}: {@code Eq} / {@code RowEq} are scalar,
 *       {@code In} / {@code RowIn} are list. The slot does not exist as a record component on
 *       the predicate arms — the operator picks the value-arity, not a redundant flag.</li>
 *   <li>{@link #extraction()} — how to extract the value at the fetcher call site; also
 *       carried here so that
 *       {@link no.sikt.graphitron.rewrite.generators.TypeConditionsGenerator} can generate the
 *       static enum-map field for {@link CallSiteExtraction.TextMapLookup} params. Use
 *       {@link CallSiteExtraction.JooqConvert} for {@code ID} arguments that require jOOQ's
 *       {@code DataType.convert()} coercion at the call site.</li>
 * </ul>
 *
 * <p>The parallel call-site view of this parameter is {@link CallParam}. A {@link BodyParam}
 * and its corresponding {@link CallParam} share the same {@code name} and {@code extraction}.
 */
public sealed interface BodyParam permits BodyParam.ColumnPredicate {

    /** Parameter name (matches the GraphQL input field name). */
    String name();

    /** Whether the parameter is a list (drives the call-site extraction shape too). */
    boolean list();

    /** Whether a runtime null guard is needed. */
    boolean nonNull();

    /** Java type of the method parameter (e.g. {@code "java.lang.String"}). */
    String javaType();

    /** How to extract the value at the fetcher call site (NestedInputField for input-type fields). */
    CallSiteExtraction extraction();

    /**
     * A column-shaped predicate body. Sealed sub-taxonomy — the operator and value-arity are
     * captured by the variant identity rather than a {@code boolean list} flag plus a uniform
     * record. Each arm carries exactly the columns it needs:
     *
     * <ul>
     *   <li>{@link Eq} — single-column scalar equality. Body emits {@code table.col.eq(arg)}.</li>
     *   <li>{@link In} — single-column IN. Body emits {@code table.col.in(arg)}.</li>
     *   <li>{@link RowEq} — composite-key single-tuple comparison. Body emits
     *       {@code DSL.row(c1, ..., cN).eq(DSL.row(v1, ..., vN))}. Used for composite-PK NodeId
     *       equality.</li>
     *   <li>{@link RowIn} — composite-key row-IN. Body emits
     *       {@code DSL.row(c1, ..., cN).in(rows)}. Used by composite-PK row-IN filters where
     *       per-Node {@code decode<TypeName>} helpers feed typed key tuples.</li>
     * </ul>
     *
     * <p>The body emitter switches exhaustively on these four arms: no
     * {@code columns.size() == 1 ? ... : ...} ladder, no {@code list ? ... : ...} ladder.
     * Adding a future operator (e.g. {@code RowGreaterThan} for keyset pagination tuples) is a
     * new sealed arm plus an emitter switch arm; the same shape <em>Generation-thinking</em>
     * expects.
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
     * arity-1 case routes to {@link Eq}.
     */
    record RowEq(
        String name,
        List<ColumnRef> columns,
        String javaType,
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
     * {@code columns.size() >= 2}; the arity-1 case routes to {@link In}.
     */
    record RowIn(
        String name,
        List<ColumnRef> columns,
        String javaType,
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

}
