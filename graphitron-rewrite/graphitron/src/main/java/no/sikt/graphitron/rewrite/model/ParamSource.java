package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.rewrite.PathExpr;

/**
 * Classifies the runtime source of a single parameter in a {@link MethodRef}.
 *
 * <p>The generator uses this to emit the correct expression for each parameter in the generated
 * service call, condition invocation, or table-method call:
 *
 * <ul>
 *   <li>{@link Arg} — the parameter is a GraphQL field argument; bound via
 *       {@code DataFetchingEnvironment.getArgument(path.headName())} for head-only paths, or
 *       Map traversal from the outer argument for multi-segment paths (R84 path expressions).</li>
 *   <li>{@link Context} — the parameter is a context argument; bound via
 *       {@code GraphitronContext.getContextArgument(dfe, name)}.</li>
 *   <li>{@link Sources} — the DataLoader batch-key list; element type and construction strategy
 *       are classified by the contained {@link BatchKey}.</li>
 *   <li>{@link DslContext} — the jOOQ {@code DSLContext}; injected by the framework.</li>
 *   <li>{@link Table} — the jOOQ {@code Table<?>} instance for the field's target table.</li>
 *   <li>{@link SourceTable} — the jOOQ {@code Table<?>} instance for the parent/source table;
 *       used in join-condition methods where both ends of the join are needed.</li>
 * </ul>
 *
 * <p>The parameter name and Java type are held on the enclosing {@link MethodRef.Param} record;
 * they are not repeated here. For {@link Context} the parameter name equals the context key.
 * For {@link Arg} the parameter name is the Java identifier; the GraphQL slot (and any tail
 * segments for path expressions) lives on {@link Arg#path}.
 */
public sealed interface ParamSource
    permits ParamSource.Arg, ParamSource.Context, ParamSource.Sources,
            ParamSource.DslContext, ParamSource.Table, ParamSource.SourceTable {

    /**
     * A GraphQL field argument bound via the directive's argMapping rule.
     *
     * <p>{@code path} is the resolved {@link PathExpr} for this binding. The single-segment
     * {@link PathExpr.Head} case is the R53 baseline ({@code env.getArgument(path.headName())}).
     * The multi-segment {@link PathExpr.Step} chain case (R84) walks from the outer argument's
     * map through nested input-field keys to the leaf value, with intermediate-null
     * short-circuit (any null in the chain produces a null leaf without an NPE).
     *
     * <p>{@code extraction} is the pre-resolved strategy for transforming the leaf value once
     * extracted. Set at classification time by
     * {@link no.sikt.graphitron.rewrite.ServiceCatalog} (jOOQ enum detection) and enriched by
     * {@link no.sikt.graphitron.rewrite.FieldBuilder} (text-map detection). Defaults to
     * {@link CallSiteExtraction.Direct} for plain scalar arguments.
     */
    record Arg(CallSiteExtraction extraction, PathExpr path) implements ParamSource {

        /** Convenience: the GraphQL slot name (head segment of the path). */
        public String graphqlArgName() {
            return path.headName();
        }
    }

    /**
     * A context argument bound via {@code GraphitronContext.getContextArgument(dfe, name)}.
     * The context key equals the parameter name on the enclosing {@link MethodRef.Param}.
     */
    record Context() implements ParamSource {}

    /**
     * The DataLoader batch-key list ({@code List<KeyType>}).
     * The element type and key-construction strategy are determined by {@link BatchKey}.
     */
    record Sources(BatchKey.ParentKeyed batchKey) implements ParamSource {}

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
}
