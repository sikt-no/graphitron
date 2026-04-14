package no.sikt.graphitron.rewrite.model;

/**
 * A single WHERE-clause contribution for a SQL-generating field.
 *
 * <ul>
 *   <li>{@link ColumnFilter} — a scalar GraphQL argument bound to a database column.
 *       Generates {@code TABLE.COL.eq(DSL.val(arg, TABLE.COL))}.</li>
 *   <li>{@link EnumColumnFilter} — a GraphQL enum argument bound to a jOOQ enum column.
 *       Generates {@code TABLE.COL.eq(DSL.val(EnumClass.valueOf(arg), TABLE.COL))}.</li>
 *   <li>{@link InputFilter} — a table-bound input object argument ({@link GraphitronType.TableInputType});
 *       generates a composite {@code col1 = ? AND col2 = ?} filter.</li>
 *   <li>{@link ConditionFilter} — a developer-supplied {@code @condition} method.</li>
 * </ul>
 */
public sealed interface WhereFilter
        permits WhereFilter.ColumnFilter, WhereFilter.EnumColumnFilter,
                WhereFilter.InputFilter, ConditionFilter {

    /**
     * A scalar GraphQL argument resolved to a column on the field's return table.
     *
     * <p>Generated code: {@code TABLE.COL.eq(DSL.val(env.getArgument("name"), TABLE.COL))}.
     */
    record ColumnFilter(
        String name,
        String typeName,
        boolean nonNull,
        boolean list,
        ColumnRef column
    ) implements WhereFilter {}

    /**
     * A GraphQL enum argument resolved to a jOOQ enum column.
     *
     * <p>{@code enumClassName} is the fully qualified Java enum class name
     * (e.g. {@code "no.example.jooq.enums.MpaaRating"}). Validated at build time: every GraphQL
     * enum value has a matching Java enum constant (by name, or by {@code @field(name:)} mapping).
     *
     * <p>Generated code:
     * {@code TABLE.COL.eq(DSL.val(MpaaRating.valueOf(env.<String>getArgument("name")), TABLE.COL))}.
     */
    record EnumColumnFilter(
        String name,
        String typeName,
        boolean nonNull,
        boolean list,
        ColumnRef column,
        String enumClassName
    ) implements WhereFilter {}

    /**
     * A table-bound input object argument ({@link GraphitronType.TableInputType}).
     * Generates a composite WHERE filter from the resolved input fields.
     */
    record InputFilter(
        String name,
        String typeName,
        boolean nonNull,
        boolean list
    ) implements WhereFilter {}
}
