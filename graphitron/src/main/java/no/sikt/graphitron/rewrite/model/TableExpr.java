package no.sikt.graphitron.rewrite.model;

/**
 * The table node a {@link JoinStep.Hop} joins to — the <em>target</em> axis of the two-axis
 * step model: a step is a target node materialized by a table expression, plus an
 * {@link On} describing how the step joins to it. The two facts are orthogonal; this seal
 * carries only the node.
 *
 * <ul>
 *   <li>{@link Catalog} — the static generated table reference from the jOOQ catalog, aliased
 *       per hop by the emitters ({@code Tables.<T>.as(alias)}).</li>
 * <li>{@link RoutineCall} — a table-valued function call: the node's rows are produced
 *       by invoking the schema's generated {@code Routines} convenience method with the bound
 *       IN parameters, then aliased like any table.</li>
 *   <li>{@link MethodCall} — the developer's static {@code @tableMethod} call: the node's rows
 *       come from invoking a consumer-authored method returning a generated jOOQ table, then
 *       aliased like any table (the {@code @tableMethod} rewire, landed when
 *       {@code RecordTableMethodField} dissolved onto the merged batched leaf).</li>
 * </ul>
 */
public sealed interface TableExpr permits TableExpr.Catalog, TableExpr.RoutineCall, TableExpr.MethodCall {

    /**
     * A statically generated jOOQ catalog table. {@code table} carries the resolved
     * {@link TableRef} (table class, constants class, SQL name) the emitters alias and join.
     */
    record Catalog(TableRef table) implements TableExpr {
        public Catalog {
            if (table == null) {
                throw new NullPointerException("TableExpr.Catalog.table must not be null");
            }
        }
    }

    /**
 * A jOOQ table-valued function call: the routine node of an order-significant
     * {@code @routine} / {@code @reference} chain. jOOQ generates the function as a first-class
     * catalog {@code Table<R>}, so the node has a real {@link TableRef} identity
     * ({@code resultTable}) that answers {@link JoinStep.Hop#targetTable()} — alias generation,
     * terminus checks, and {@code $fields} projection all read the result table uniformly with
     * {@link Catalog} nodes.
     *
     * <p>{@code routine} carries the call surface ({@code Routines} class, table-form
     * convenience method, IN-parameter bindings). A {@link ParamSource.SourceColumn} binding
     * makes the call correlated: the emitters render every argument as a jOOQ {@code Field}
     * ({@code DSL.val(...)} for value-sourced bindings, the previous node's aliased column for
     * column-sourced ones), which selects the generated method's {@code Field}-overload at
     * javac overload resolution — no separate catalog resolution of that overload is needed.
     */
    record RoutineCall(RoutineRef routine, TableRef resultTable) implements TableExpr {
        public RoutineCall {
            if (routine == null) {
                throw new NullPointerException("TableExpr.RoutineCall.routine must not be null");
            }
            if (resultTable == null) {
                throw new NullPointerException("TableExpr.RoutineCall.resultTable must not be null");
            }
        }
    }

    /**
     * A consumer-authored static {@code @tableMethod} call returning a generated jOOQ table.
     * Like {@link RoutineCall}, the node has a real {@link TableRef} identity
     * ({@code resultTable}, the method's declared return-table binding) that answers
     * {@link JoinStep.Hop#targetTable()}, so alias generation, terminus checks, and
     * {@code $fields} projection read it uniformly with {@link Catalog} nodes; only the
     * materialization differs ({@code MethodClass.method(<args>)} instead of
     * {@code Tables.<T>}). {@code method} carries the call surface; argument assembly walks
     * {@link MethodRef#params()} at the emit site.
     */
    record MethodCall(MethodRef method, TableRef resultTable) implements TableExpr {
        public MethodCall {
            if (method == null) {
                throw new NullPointerException("TableExpr.MethodCall.method must not be null");
            }
            if (resultTable == null) {
                throw new NullPointerException("TableExpr.MethodCall.resultTable must not be null");
            }
        }
    }
}
