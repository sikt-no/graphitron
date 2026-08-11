package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.rewrite.PathExpr;

import java.util.List;
import java.util.Objects;

/**
 * Classifies the runtime source of a single parameter in a {@link MethodRef} or a
 * {@link RoutineRef}: one call-source taxonomy for service, condition, table-method, and
 * routine calls. The generator switches on it to emit the correct expression for each parameter
 * at the call site; each variant documents its own binding.
 *
 * <p>The parameter name and Java type are held on the enclosing {@link MethodRef.Param} record;
 * they are not repeated here. For {@link Context} the parameter name equals the context key.
 * For {@link Arg} the parameter name is the Java identifier; the GraphQL slot (and any tail
 * segments for path expressions) lives on {@link Arg#path}.
 */
public sealed interface ParamSource
    permits ParamSource.RoutineParamSource, ParamSource.Context, ParamSource.Sources,
            ParamSource.DslContext, ParamSource.Table, ParamSource.SourceTable {

    /**
     * The two arms a {@link RoutineRef.ArgBinding} may carry, named as a type so the routine
     * emitter's switch is exhaustive over exactly them. A routine IN parameter reads either a
     * GraphQL field argument ({@link Arg}) or a column of the chain's previous node
     * ({@link SourceColumn}); the remaining {@link ParamSource} arms are reflected-method
     * concepts a routine call has no seat for.
     *
     * <p>Switches over the whole {@link ParamSource} taxonomy keep enumerating the leaves and
     * stay exhaustive: covering every permitted subtype of a sealed member covers the member.
     */
    sealed interface RoutineParamSource extends ParamSource permits Arg, SourceColumn {}

    /**
     * A GraphQL field argument bound via the directive's argMapping rule.
     *
     * <p>{@code path} is the resolved {@link PathExpr} for this binding. The single-segment
     * {@link PathExpr.Head} case is the single-name baseline ({@code env.getArgument(path.headName())}).
     * The multi-segment {@link PathExpr.Step} chain case walks from the outer argument's map
     * through nested input-field keys to the leaf value, with intermediate-null short-circuit
     * (any null in the chain produces a null leaf without an NPE).
     *
     * <p>{@code extraction} is the pre-resolved strategy for transforming the leaf value once
     * extracted. Set at classification time by
     * {@link no.sikt.graphitron.rewrite.ServiceCatalog} (jOOQ enum detection) and enriched by
     * {@link no.sikt.graphitron.rewrite.FieldBuilder} (text-map detection). Defaults to
     * {@link CallSiteExtraction.Direct} for plain scalar arguments.
     */
    record Arg(CallSiteExtraction extraction, PathExpr path) implements RoutineParamSource {}

    /**
     * A context argument bound via {@code GraphitronContext.getContextArgument(dfe, name)}.
     * The context key equals the parameter name on the enclosing {@link MethodRef.Param}.
     */
    record Context() implements ParamSource {}

    /**
     * The DataLoader batch-key list ({@code List<KeyType>}) or set ({@code Set<KeyType>}).
     * Carries the {@code (wrap, columns, container)} triple that determines the parameter's
     * Java type and key-construction strategy: {@link SourceKey.Wrap} for the per-row shape
     * (Row / Record / typed TableRecord), {@code columns} for the parent-side PK/FK tuple,
     * and {@link LoaderRegistration.Container} for the mapped/positional axis.
     */
    record Sources(
            SourceKey.Wrap wrap,
            List<ColumnRef> columns,
            LoaderRegistration.Container container) implements ParamSource {
        public Sources {
            Objects.requireNonNull(wrap, "wrap");
            Objects.requireNonNull(container, "container");
            columns = List.copyOf(columns);
        }
    }

    /** The jOOQ {@code DSLContext}; injected by the framework. */
    record DslContext() implements ParamSource {}

    /**
     * The jOOQ {@code Table<?>} instance for the field's target table.
     * Used in condition and table-method calls to build SQL expressions.
     */
    record Table() implements ParamSource {}

    /**
     * The jOOQ {@code Table<?>} instance for the parent/source table.
     * Present only in join-condition methods where both ends of the join must be referenced.
     */
    record SourceTable() implements ParamSource {}

    /**
     * A single column of the previous table node in the field's chain: the column-granularity
     * sibling of {@link SourceTable}, authored via {@code @routine(columnMapping:)}. A
     * column-bound routine parameter makes the call correlated: the emitter renders the routine
     * as {@code CROSS JOIN LATERAL} with this column of the previous node as the argument
     * expression. {@code column} is the resolved column on the previous node's table.
     *
     * <p>Produced only for {@link RoutineRef.ArgBinding}; never a {@link MethodRef} param source.
     */
    record SourceColumn(ColumnRef column) implements RoutineParamSource {
        public SourceColumn {
            Objects.requireNonNull(column, "column");
        }
    }
}
