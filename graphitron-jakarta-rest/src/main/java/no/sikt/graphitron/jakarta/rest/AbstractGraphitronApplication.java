package no.sikt.graphitron.jakarta.rest;

import graphql.schema.GraphQLSchema;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Boilerplate-removing base for a {@link GraphitronApplication} adapter. A concrete subgraph adapter
 * passes a schema supplier to the constructor and implements only the auth-bearing
 * {@link #newExecutionInput()}; the engine and SDL plumbing ride the inherited defaults.
 *
 * <pre>{@code
 * @ApplicationScoped
 * public class MySubgraphApplication extends AbstractGraphitronApplication {
 *     @Inject AuthenticatedContextProvider auth;   // @RequestScoped, per-subgraph
 *
 *     public MySubgraphApplication() {
 *         super(() -> Graphitron.buildSchema(b -> {}));   // only facade reference, via lambda
 *     }
 *
 *     @Override
 *     public ExecutionInput.Builder newExecutionInput() {
 *         return Graphitron.newExecutionInput(auth.getContext());   // + any context args
 *     }
 * }
 * }</pre>
 *
 * <p>The schema supplier is a lambda over the static facade, so this base never names a per-subgraph
 * type; the only generated-symbol references live in the tiny subclass. Generating the adapter would
 * couple every generated codebase to a {@code graphitron-jakarta-rest} version and break the
 * generator/runtime decoupling the generator deliberately keeps, so it stays hand-written.
 */
public abstract class AbstractGraphitronApplication implements GraphitronApplication {

    private final Supplier<GraphQLSchema> schemaSupplier;
    private volatile GraphQLSchema schema;

    /**
     * @param schemaSupplier builds the executable schema, typically {@code () ->
     *                       Graphitron.buildSchema(b -> {})}; invoked at most once, the result cached
     */
    protected AbstractGraphitronApplication(Supplier<GraphQLSchema> schemaSupplier) {
        this.schemaSupplier = Objects.requireNonNull(schemaSupplier, "schemaSupplier");
    }

    /**
     * Builds the schema on first call and caches it, so the engine and the {@code /schema} endpoint
     * share one built schema. Thread-safe via double-checked locking; the supplier runs at most once.
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

    // graphiqlEnabled() is inherited as a default method from GraphitronApplication (default true);
    // a concrete subgraph adapter overrides it to gate GraphiQL behind its own configuration.
}
