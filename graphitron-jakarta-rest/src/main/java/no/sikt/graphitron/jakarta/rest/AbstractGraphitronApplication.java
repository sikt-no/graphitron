package no.sikt.graphitron.jakarta.rest;

import graphql.schema.GraphQLSchema;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Boilerplate-removing base for a {@link GraphitronApplication} adapter. A concrete subgraph adapter
 * passes a schema supplier and an SDL printer to the constructor and implements only the
 * auth-bearing {@link #newExecutionInput()}; the engine and SDL plumbing ride the inherited
 * defaults.
 *
 * <pre>{@code
 * @ApplicationScoped
 * public class MySubgraphApplication extends AbstractGraphitronApplication {
 *     @Inject AuthenticatedContextProvider auth;   // @RequestScoped, per-subgraph
 *
 *     public MySubgraphApplication() {
 *         // only facade references, via lambda and method reference
 *         super(() -> Graphitron.buildSchema(b -> {}), Graphitron::schemaSdl);
 *     }
 *
 *     @Override
 *     public ExecutionInput.Builder newExecutionInput() {
 *         return Graphitron.newExecutionInput(auth.getContext());   // + any context args
 *     }
 * }
 * }</pre>
 *
 * <p>The schema supplier and SDL printer reach the static facade through a lambda and a method
 * reference, so this base never names a per-subgraph type; the only generated-symbol references
 * live in the tiny subclass. Generating the adapter would couple every generated codebase to a
 * {@code graphitron-jakarta-rest} version, so it stays hand-written.
 */
public abstract class AbstractGraphitronApplication implements GraphitronApplication {

    private final Supplier<GraphQLSchema> schemaSupplier;
    private final Function<GraphQLSchema, String> sdlPrinter;
    private volatile GraphQLSchema schema;
    private volatile String schemaSdl;

    /**
     * @param schemaSupplier builds the executable schema, typically {@code () ->
     *                       Graphitron.buildSchema(b -> {})}; invoked at most once, the result cached
     * @param sdlPrinter     prints the built schema as the SDL {@code /schema} serves, typically
     *                       {@code Graphitron::schemaSdl}; invoked at most once, the result cached
     */
    protected AbstractGraphitronApplication(Supplier<GraphQLSchema> schemaSupplier,
                                            Function<GraphQLSchema, String> sdlPrinter) {
        this.schemaSupplier = Objects.requireNonNull(schemaSupplier, "schemaSupplier");
        this.sdlPrinter = Objects.requireNonNull(sdlPrinter, "sdlPrinter");
    }

    /**
     * Without an SDL printer, {@link #schemaSdl()} falls back to the
     * {@link GraphitronApplication#schemaSdl()} default, which a federation composer rejects.
     *
     * @param schemaSupplier builds the executable schema; invoked at most once, the result cached
     * @deprecated pass the generated facade's printer as well:
     *             {@code super(() -> Graphitron.buildSchema(b -> {}), Graphitron::schemaSdl)}
     */
    @Deprecated
    protected AbstractGraphitronApplication(Supplier<GraphQLSchema> schemaSupplier) {
        this.schemaSupplier = Objects.requireNonNull(schemaSupplier, "schemaSupplier");
        this.sdlPrinter = null;
    }

    /**
     * Builds the schema on first call and caches it, so the engine and the {@code /schema} endpoint
     * share one built schema. Thread-safe; the supplier runs at most once.
     */
    @Override
    public final GraphQLSchema schema() {
        GraphQLSchema local = schema;
        if (local == null) {
            synchronized (this) {
                local = schema;
                if (local == null) {
                    local = Objects.requireNonNull(schemaSupplier.get(), "schemaSupplier returned null");
                    schema = local;
                }
            }
        }
        return local;
    }

    /**
     * Prints {@link #schema()} with the constructor's SDL printer on first call and caches it.
     * Thread-safe; the printer runs at most once. An adapter built with the deprecated
     * one-argument constructor gets the {@link GraphitronApplication#schemaSdl()} default instead.
     */
    @Override
    public final String schemaSdl() {
        String local = schemaSdl;
        if (local == null) {
            synchronized (this) {
                local = schemaSdl;
                if (local == null) {
                    local = sdlPrinter == null
                        ? GraphitronApplication.super.schemaSdl()
                        : Objects.requireNonNull(sdlPrinter.apply(schema()), "sdlPrinter returned null");
                    schemaSdl = local;
                }
            }
        }
        return local;
    }
}
