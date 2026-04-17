package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * Generation-ready mapping for a lookup field: the set of key columns and the target table they
 * bind to. Carried on every {@link LookupField} variant.
 *
 * <p>Represents the N × M positional contract described in
 * {@code docs/code-generation-triggers.md}: given M input rows (each row being a tuple of the
 * declared lookup keys), the field returns N results per input row — preserving input order.
 *
 * <p>The generator materialises this as a {@code VALUES(idx, col1, col2, …)} derived table
 * joined against the target table by equality on each key column, ordered by {@code input.idx}
 * to preserve input ordering. See {@code docs/argument-resolution.md#lookupmapping} and the
 * VALUES + JOIN builder in {@code GeneratorUtils} (argres step 7).
 *
 * <p>{@code columns} carries the scalar keys declared via {@code @lookupKey} on arguments, in
 * declaration order. Composite-key input types (argres step 9) contribute additional columns
 * derived from {@code InputColumnBinding}s — those are surfaced here alongside scalar columns.
 */
public record LookupMapping(
    List<LookupColumn> columns,
    TableRef targetTable
) {

    /**
     * One column in the lookup key tuple.
     *
     * <p>{@code argName} is the GraphQL argument name (or, for composite-key inputs, the
     * argument name paired with the input field — the VALUES column still needs a unique label).
     * {@code targetColumn} is the column on {@link LookupMapping#targetTable} that the JOIN
     * equates against.
     * {@code extraction} tells the generator how to read the value from
     * {@code DataFetchingEnvironment} at the call site.
     * {@code list} is {@code true} when the argument (or its list cardinality) contributes
     * multiple VALUES rows; {@code false} when the value is broadcast as a scalar across all rows.
     */
    public record LookupColumn(
        String argName,
        ColumnRef targetColumn,
        CallSiteExtraction extraction,
        boolean list
    ) {}
}
