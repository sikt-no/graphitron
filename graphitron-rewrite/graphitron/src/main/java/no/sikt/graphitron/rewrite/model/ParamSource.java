package no.sikt.graphitron.rewrite.model;

/**
 * Classifies the runtime source of a single parameter in a {@link MethodRef}.
 *
 * <p>The generator uses this to emit the correct expression for each parameter in the generated
 * service call, condition invocation, or table-method call:
 *
 * <ul>
 *   <li>{@link Arg} — the parameter is a GraphQL field argument; bound via
 *       {@code DataFetchingEnvironment.getArgument(name)}.</li>
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
 * For {@link Arg} the parameter name is the Java identifier; the GraphQL argument key (which
 * may diverge under {@code @field(name:)} on the argument site) lives on
 * {@link Arg#graphqlArgName}.
 */
public sealed interface ParamSource
    permits ParamSource.Arg, ParamSource.Context, ParamSource.Sources,
            ParamSource.DslContext, ParamSource.Table, ParamSource.SourceTable {

    /**
     * A GraphQL field argument bound via {@code DataFetchingEnvironment.getArgument(graphqlArgName)}.
     *
     * <p>{@code graphqlArgName} is the GraphQL argument key — equal to the enclosing
     * {@link MethodRef.Param#name()} (the Java identifier) when no override is in effect, or the
     * value supplied by {@code @field(name:)} on an {@code @service} / {@code @tableMethod}
     * argument when the schema author binds a Java parameter to a differently-named GraphQL arg.
     *
     * <p>{@code extraction} is the pre-resolved strategy for extracting this argument's value
     * from the GraphQL execution context. Set at classification time by
     * {@link no.sikt.graphitron.rewrite.ServiceCatalog} (jOOQ enum detection) and enriched by
     * {@link no.sikt.graphitron.rewrite.FieldBuilder} (text-map detection). Defaults to
     * {@link CallSiteExtraction.Direct} for plain scalar arguments.
     */
    record Arg(CallSiteExtraction extraction, String graphqlArgName) implements ParamSource {}

    /**
     * A context argument bound via {@code GraphitronContext.getContextArgument(dfe, name)}.
     * The context key equals the parameter name on the enclosing {@link MethodRef.Param}.
     */
    record Context() implements ParamSource {}

    /**
     * The DataLoader batch-key list ({@code List<KeyType>}).
     * The element type and key-construction strategy are determined by {@link BatchKey}.
     */
    record Sources(BatchKey batchKey) implements ParamSource {}

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
