package no.sikt.graphitron.jakarta.rest;

import graphql.schema.GraphQLSchema;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Boilerplate-removing base for a {@link GraphitronApplication} adapter. A concrete subgraph adapter
 * passes method references to the generated facade to the constructor and overrides only what is its
 * own: the engine's extra instrumentation and how a rejected identity is answered. The schema, the
 * SDL and the recognition of a rejected session mount ride the inherited implementations.
 *
 * <pre>{@code
 * @ApplicationScoped
 * public class MySubgraphApplication extends AbstractGraphitronApplication {
 *     private final GraphitronRuntime runtime;
 *     private final GraphQLTelemetry telemetry;
 *
 *     MySubgraphApplication(@Named("my-db") DataSource dataSource, GraphQLTelemetry telemetry) {
 *         // only facade references, as method references
 *         super(Graphitron::buildSchema, Graphitron::schemaSdl, Graphitron::sessionMountFailure);
 *         this.runtime = Graphitron.runtime(dataSource, SQLDialect.POSTGRES, new Settings());
 *         this.telemetry = telemetry;
 *     }
 *
 *     @Override
 *     public GraphQL.Builder engineBuilder() {
 *         return runtime.newGraphQL(schema(), telemetry.newInstrumentation());
 *     }
 *
 *     @Override
 *     public Optional<WebApplicationException> rejectedIdentity(Throwable mountCause) {
 *         return Optional.of(new NotAuthorizedException("identity rejected"));
 *     }
 * }
 * }</pre>
 *
 * <p>The schema supplier, SDL printer and mount-failure recogniser reach the static facade through
 * method references, so this base never names a per-subgraph type; the only generated-symbol
 * references live in the tiny subclass. Generating the adapter would couple every generated codebase
 * to a {@code graphitron-jakarta-rest} version, so it stays hand-written. The input of a request is
 * built by the resource that serves it and handed to {@link GraphqlEndpoint}, not here.
 */
public abstract class AbstractGraphitronApplication implements GraphitronApplication {

    private final Supplier<GraphQLSchema> schemaSupplier;
    private final Function<GraphQLSchema, String> sdlPrinter;
    private final Function<Throwable, Optional<Throwable>> mountCauseFinder;
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
        this.mountCauseFinder = null;
    }

    /**
     * As the two-argument form, and {@link #mountCause(Throwable)} answers through
     * {@code mountCauseFinder}, so {@link #rejectedIdentity(Throwable)} is called with the mount's
     * own exception when the database rejects the caller's identity.
     *
     * @param schemaSupplier   builds the executable schema, typically {@code Graphitron::buildSchema};
     *                         invoked at most once, the result cached
     * @param sdlPrinter       prints the built schema as the SDL {@code /schema} serves, typically
     *                         {@code Graphitron::schemaSdl}; invoked at most once, the result cached
     * @param mountCauseFinder returns the mount's own exception when its argument is, or is caused
     *                         by, a rejected session mount, typically
     *                         {@code Graphitron::sessionMountFailure}
     */
    protected AbstractGraphitronApplication(Supplier<GraphQLSchema> schemaSupplier,
                                            Function<GraphQLSchema, String> sdlPrinter,
                                            Function<Throwable, Optional<Throwable>> mountCauseFinder) {
        this.schemaSupplier = Objects.requireNonNull(schemaSupplier, "schemaSupplier");
        this.sdlPrinter = Objects.requireNonNull(sdlPrinter, "sdlPrinter");
        this.mountCauseFinder = Objects.requireNonNull(mountCauseFinder, "mountCauseFinder");
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
        this.mountCauseFinder = null;
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

    /**
     * The mount's own exception through the constructor's finder; empty when the adapter was built
     * without one.
     */
    @Override
    public Optional<Throwable> mountCause(Throwable thrown) {
        return mountCauseFinder == null ? Optional.empty() : mountCauseFinder.apply(thrown);
    }
}
