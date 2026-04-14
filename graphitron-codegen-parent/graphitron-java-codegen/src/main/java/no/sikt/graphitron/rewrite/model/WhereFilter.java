package no.sikt.graphitron.rewrite.model;

import java.util.Map;

/**
 * A single WHERE-clause contribution for a SQL-generating field.
 *
 * <ul>
 *   <li>{@link ColumnFilter} — a scalar GraphQL argument bound to a database column.
 *       Generates {@code TABLE.COL.eq(DSL.val(arg, TABLE.COL))}.</li>
 *   <li>{@link EnumColumnFilter} — a GraphQL enum argument bound to a jOOQ enum column.
 *       Generates {@code TABLE.COL.eq(DSL.val(EnumClass.valueOf(arg), TABLE.COL))}.</li>
 *   <li>{@link TextEnumColumnFilter} — a GraphQL enum argument bound to a text/varchar column.
 *       Generates {@code TABLE.COL.eq(DSL.val(MAPPING.get(arg), TABLE.COL))} where MAPPING
 *       is a generated static map from GraphQL enum value names to database string values.</li>
 *   <li>{@link InputFilter} — a table-bound input object argument ({@link GraphitronType.TableInputType});
 *       generates a composite {@code col1 = ? AND col2 = ?} filter.</li>
 *   <li>{@link ConditionFilter} — a developer-supplied {@code @condition} method.</li>
 * </ul>
 */
public sealed interface WhereFilter
        permits WhereFilter.ColumnFilter, WhereFilter.EnumColumnFilter,
                WhereFilter.TextEnumColumnFilter,
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
     * <p>{@code enumClassName} is the fully qualified Java enum class name. Validated at build
     * time: every GraphQL enum value has a matching Java enum constant.
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
     * A GraphQL enum argument resolved to a text/varchar database column.
     *
     * <p>{@code valueMapping} maps each GraphQL enum value name to its database string value.
     * The mapping is built at build time from the GraphQL enum definition: each value's database
     * string comes from {@code @field(name:)} if present, otherwise the enum value name itself.
     *
     * <p>{@code mapFieldName} is the generated static field name for the lookup map
     * (e.g. {@code "FILMS_TEXTRATING_MAP"}), computed at build time from the owning field name
     * and argument name to avoid collisions.
     *
     * <p>Generated code: a static {@code Map<String, String>} field plus
     * {@code TABLE.COL.eq(DSL.val(FILMS_TEXTRATING_MAP.get(env.<String>getArgument("name")), TABLE.COL))}.
     */
    record TextEnumColumnFilter(
        String name,
        String typeName,
        boolean nonNull,
        boolean list,
        ColumnRef column,
        Map<String, String> valueMapping,
        String mapFieldName
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
