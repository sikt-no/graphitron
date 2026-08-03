package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * A field that generates SQL directly — carrying a table-bound return type, filters,
 * ordering, and pagination.
 *
 * <p>Implemented by three root {@link QueryField} variants:
 * {@link QueryField.QueryTableField},
 * {@link QueryField.QueryTableInterfaceField}; and by all {@link ChildField.TableTargetField}
 * variants.
 *
 * <p>Generators and producers that process any SQL-generating field uniformly (e.g. the condition
 * command producer, {@code no.sikt.graphitron.plan.ConditionCommands}) use this interface
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

    /**
     * True when any of this coordinate's condition bindings reads the request context
     * ({@link CallParam#readsRequestContext()}): the fold every consumer of the env-appending
     * glue signature asks. The producer decides the glue signature from it, each converged call
     * site appends the {@code env} argument by it, and keeping the fold here (one home) is what
     * stops the two ends drifting on which coordinates take the environment.
     */
    default boolean conditionsReadRequestContext() {
        return WhereFilter.anyReadRequestContext(filters());
    }
}
