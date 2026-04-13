package no.sikt.graphitron.rewrite.model;

/**
 * A single WHERE-clause contribution for a SQL-generating field.
 *
 * <p>The three permitted variants reflect the three sources from which WHERE predicates arise:
 *
 * <ul>
 *   <li>{@link ColumnFilter} — a scalar GraphQL argument bound to a database column; generates
 *       {@code col = ?}.</li>
 *   <li>{@link InputFilter} — a table-bound input object argument ({@link GraphitronType.TableInputType});
 *       generates a composite {@code col1 = ? AND col2 = ?} filter derived from the input type's
 *       fields.</li>
 *   <li>{@link ConditionFilter} — a developer-supplied {@code @condition} method on
 *       {@code FIELD_DEFINITION}; calls {@code method(targetTable, arg1, arg2, ...)} and ANDs the
 *       result into the WHERE clause.</li>
 * </ul>
 *
 * <p>For {@link ColumnFilter} and {@link InputFilter}, the {@code name} and {@code typeName}
 * fields describe the GraphQL argument. The {@code nonNull} and {@code list} flags reflect the
 * argument's GraphQL type wrapper.
 *
 * <p><b>Model gap — {@code @condition} on argument definitions:</b>
 * {@code @condition} can also appear on {@code ARGUMENT_DEFINITION} and
 * {@code INPUT_FIELD_DEFINITION}. Those cases are not yet modelled. When implemented,
 * {@link ColumnFilter} and {@link InputFilter} would gain a nullable
 * {@code ConditionFilter condition} component representing argument-level conditions.
 * See the javadoc on those records for details.
 */
public sealed interface WhereFilter
        permits WhereFilter.ColumnFilter, WhereFilter.InputFilter, ConditionFilter {

    /**
     * A scalar GraphQL argument resolved to a column on the field's return table.
     * Generates a {@code col = ?} WHERE predicate.
     *
     * <p>{@code column} is the resolved jOOQ {@link ColumnRef} for the target column.
     *
     * <p><b>Model gap:</b> {@code @condition} on {@code ARGUMENT_DEFINITION} for a scalar argument
     * should be represented as a nullable {@code ConditionFilter condition} component here. When
     * present, the condition method is called as {@code method(targetTable, argValue)}.
     * Without {@code override: true} (which the builder handles by omitting this {@link ColumnFilter}),
     * both the {@code col = ?} predicate and the condition call are generated. With
     * {@code override: true} the {@code col = ?} predicate is suppressed (this filter is omitted)
     * and only the condition is emitted.
     */
    record ColumnFilter(
        String name,
        String typeName,
        boolean nonNull,
        boolean list,
        ColumnRef column
    ) implements WhereFilter {}

    /**
     * A table-bound input object argument ({@link GraphitronType.TableInputType}).
     * Graphitron generates a composite WHERE filter from the resolved input fields.
     *
     * <p>The actual {@link GraphitronType.TableInputType} instance is available via
     * {@link no.sikt.graphitron.rewrite.GraphitronSchema#types()}.
     *
     * <p><b>Model gap:</b> {@code @condition} on {@code ARGUMENT_DEFINITION} for an input-type
     * argument should be a nullable {@code ConditionFilter condition} component here. When present,
     * the condition method is called with {@code (targetTable, leaf1, leaf2, ...)} — the target
     * table alias followed by the flattened leaf scalar values of the input type. Without
     * {@code override: true}, the normal column-equality predicates for the input fields are
     * generated alongside the condition call. With {@code override: true}, those predicates are
     * suppressed for this input type's fields; predicates from other filters are unaffected.
     */
    record InputFilter(
        String name,
        String typeName,
        boolean nonNull,
        boolean list
    ) implements WhereFilter {}
}
