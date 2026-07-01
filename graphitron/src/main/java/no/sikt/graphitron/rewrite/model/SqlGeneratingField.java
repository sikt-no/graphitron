package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * A field that generates SQL directly — carrying a table-bound return type, filters,
 * ordering, and pagination.
 *
 * <p>Implemented by three root {@link QueryField} variants:
 * {@link QueryField.QueryTableField}, {@link QueryField.QueryLookupTableField},
 * {@link QueryField.QueryTableInterfaceField}; and by all {@link ChildField.TableTargetField}
 * variants.
 *
 * <p>Generators that process any SQL-generating field uniformly (e.g.
 * {@link no.sikt.graphitron.rewrite.generators.TypeConditionsGenerator}) use this interface
 * instead of switching on concrete types. Adding a new SQL-generating field variant to either
 * {@link QueryField} or {@link ChildField} only requires implementing this interface —
 * no generator switch updates needed.
 *
 * <p>This interface is intentionally standalone (does not extend {@link GraphitronField}) so that
 * it can be applied as an orthogonal capability without being restricted by the sealed hierarchy.
 * Generators receive {@link GraphitronField} and pattern-match with {@code instanceof SqlGeneratingField}.
 */
public interface SqlGeneratingField {
    ReturnTypeRef.TableBoundReturnType returnType();
    List<WhereFilter> filters();
    OrderBySpec orderBy();
    PaginationSpec pagination();
}
