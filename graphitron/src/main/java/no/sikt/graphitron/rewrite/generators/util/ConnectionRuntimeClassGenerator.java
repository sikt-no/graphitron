package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import no.sikt.graphitron.model.grammar.ArgMappingSigil;
import no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator;
import no.sikt.graphitron.emit.ConnectionKeyShape;
import no.sikt.graphitron.emit.SessionIdentityRows;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Emits the connection-lifecycle runtime substrate into the consumer's
 * {@code <outputPackage>.schema} package: the connection contract ({@code ConnectionSource},
 * {@code Lease}, {@code KeyNotPermitted}, the ready-made {@code ConnectionSources}), the
 * application-scoped {@code GraphitronRuntime} holding the dialect and jOOQ {@code Settings}, the
 * acquisition-scoped {@code PinnedConnection} that wraps one lease, and the per-operation
 * {@code OwnedConnections} carrier.
 *
 * <p>Emitted (not shipped as a graphitron artifact), following the {@code GraphitronContext}
 * precedent: the bodies depend only on the JDK, jOOQ, graphql-java and the consumer's own named
 * classes. The bodies must be valid Java 17 (verified by the {@code graphitron-sakila-example}
 * {@code <release>17</release>} compile).
 *
 * <h2>The contract</h2>
 * The consumer hands each request a {@code ConnectionSource} through
 * {@code Graphitron.newOwnedExecutionInput(source, ...)}; Graphitron owns the lifecycle. The carrier
 * calls {@code acquire(key)} the first time a field needs a {@link ConnectionKeyShape connection key}'s
 * connection, never twice for one key in one request, and releases every lease it got exactly once
 * on every completion path (success, error, cancellation). {@code KeyNotPermitted} refuses a key:
 * the field that asked answers with an error, and an entity or node lookup with {@code null}. Any
 * other throw becomes an {@code AcquisitionFailedException}, remembered by the carrier and thrown out
 * of {@code GraphQL.execute} in place of the result. What the lease's connection carries (identity,
 * role, row-level security settings) is the source's business; the ready-made
 * {@code ConnectionSources.mounting} keeps the mount, unmount and PostgreSQL reset Graphitron used to
 * own.
 *
 * <h3>Graphitron asserts the connection's transaction mode</h3>
 * A leased connection must be in autocommit: each mutation field's transaction opens from it, and a
 * source that set its state inside an open transaction set it where the first rollback would revert
 * it. {@code PinnedConnection.acquire} refuses such a lease rather than roll it back. At release any
 * transaction the operation left open is rolled back before the lease's release, since JDBC commits
 * an open transaction when autocommit is turned on. A connection Graphitron must stop (a fan-out
 * worker past its deadline, a pin that completed after the operation released, a connection that
 * cannot be settled) is aborted through {@link java.sql.Connection#abort(java.util.concurrent.Executor)}
 * before its lease is released, so a release finding it closed has nothing to clean up.
 *
 * <h2>Load-bearing invariant: one connection per key, one thread per connection</h2>
 * Pinning exactly one connection per distinct key within an operation is safe because generated
 * batch loaders execute SQL synchronously on the dispatch thread
 * ({@code RowsMethodCall.batchLoaderLambda} emits
 * {@code CompletableFuture.completedFuture(rows(keys, dfe))}). That invariant is pinned at its
 * emission site by {@code RowsMethodCallTest}'s synchronous-body assertion; a future "make loaders
 * async" change fails there rather than as a distant execution-tier flake. See that test's javadoc,
 * which links back here.
 *
 * <p>In multi-tenant builds the carrier's {@code scatter} helper is the one deliberate exception,
 * and it preserves both halves of the invariant for a revised reason: concurrency is confined to
 * scatter's bounded workers, each owning exactly one keyed connection single-threaded through
 * {@code dslFor(key)}, while the dispatch thread is blocked inside the join for the scatter's whole
 * duration. Generated fetchers stay synchronous; the fanned fetcher calls {@code scatter} and
 * blocks, and concurrency never leaks into fetcher bodies.
 *
 * <h2>Session handles</h2>
 * Where {@code <sessionState>} names a handle class, {@code Lease} carries a typed {@code handle()},
 * which the carrier writes onto each key's {@code Configuration} for {@code $session}-bound
 * parameters to read.
 */
public final class ConnectionRuntimeClassGenerator {

    public static final String RUNTIME_CLASS_NAME = "GraphitronRuntime";
    public static final String PINNED_CONNECTION_CLASS_NAME = "PinnedConnection";
    /** The consumer-implemented source of a request's connections, one lease per connection key. */
    public static final String CONNECTION_SOURCE_CLASS_NAME = "ConnectionSource";
    /** What a {@code ConnectionSource} hands back: a connection, its session handle, and its release. */
    public static final String LEASE_CLASS_NAME = "Lease";
    /** The one refusal a {@code ConnectionSource} may throw: this request may not have that key's connection. */
    public static final String KEY_NOT_PERMITTED_CLASS_NAME = "KeyNotPermitted";
    /** The ready-made connection sources: over a pool per key, with a mount and an unmount. */
    public static final String CONNECTION_SOURCES_CLASS_NAME = "ConnectionSources";
    /**
     * The request-scoped rejection any other throwing {@code acquire} becomes: the carrier remembers
     * the first, and the instrumentation throws it out of {@code GraphQL.execute}.
     */
    public static final String ACQUISITION_FAILED_CLASS_NAME = "AcquisitionFailedException";

    /** The name the request's {@code ConnectionSource} travels under as a parameter and a field. */
    static final String SOURCE_SLOT = "source";
    /**
     * What a field running on the key its parent hands down is refused with where none was handed
     * down: a request-level error before any SQL.
     */
    static final String NO_HANDED_DOWN_KEY =
        "No connection key was handed down to this field; cannot choose the connection it runs on.";
    /** The per-operation carrier of the owned connections, one per connection key. */
    public static final String OWNED_CONNECTIONS_CLASS_NAME = "OwnedConnections";
    /**
     * The carrier's per-element tenant stamp, {@code <T> DataFetcherResult<T> handDown(T row, K
     * tenantKey)}, shared by the fan-out union and node and entity dispatch.
     */
    public static final String HAND_DOWN_METHOD = "handDown";
    /**
     * The request-tenancy value the factory takes in {@code <tenantColumn>} builds: the request
     * tenant set and an optional default tenant that is a member of it.
     */
    public static final String REQUEST_TENANTS_CLASS_NAME = "RequestTenants";
    /**
     * The consumer-implemented callback that decides how a fetcher exception is logged, both one no
     * {@code @error} mapping claims and one a handler caught; held by the runtime and read by
     * {@code ErrorRouter.toConfiguredLog}.
     */
    public static final String FETCHER_ERROR_LOG_CLASS_NAME = "FetcherErrorLog";
    /**
     * The consumer-implemented callback that decides what the client reads for a fetcher
     * exception: the reference, a caught error's {@code message:} and the redacted top-level text;
     * held by the runtime beside {@link #FETCHER_ERROR_LOG_CLASS_NAME} and read by {@code ErrorRouter}.
     */
    public static final String FETCHER_ERROR_RESPONSE_CLASS_NAME = "FetcherErrorResponse";
    /**
     * The consumer-implemented callback that bounds how many keys one batched query carries; held
     * by the runtime beside {@link #FETCHER_ERROR_LOG_CLASS_NAME} and read by
     * {@code ErrorRouter.loaderOptions}.
     */
    public static final String BATCH_SIZE_LIMIT_CLASS_NAME = "BatchSizeLimit";

    private static final ClassName CONNECTION = ClassName.get("java.sql", "Connection");
    private static final ClassName SQL_EXCEPTION = ClassName.get("java.sql", "SQLException");
    private static final ClassName STATEMENT = ClassName.get("java.sql", "Statement");
    private static final ClassName PREPARED_STATEMENT = ClassName.get("java.sql", "PreparedStatement");
    private static final ClassName RESULT_SET = ClassName.get("java.sql", "ResultSet");
    private static final TypeName STRING_TABLE = ArrayTypeName.of(ArrayTypeName.of(String.class));
    private static final ClassName DATA_SOURCE = ClassName.get("javax.sql", "DataSource");
    private static final ClassName SETTINGS = ClassName.get("org.jooq.conf", "Settings");
    private static final ClassName EXECUTOR = ClassName.get("java.util.concurrent", "Executor");
    private static final ClassName EXECUTORS = ClassName.get("java.util.concurrent", "Executors");
    private static final ClassName SQL_DIALECT = ClassName.get("org.jooq", "SQLDialect");
    private static final ClassName OBJECTS = ClassName.get("java.util", "Objects");
    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final ClassName HASH_MAP = ClassName.get("java.util", "HashMap");
    private static final ClassName LINKED_HASH_MAP = ClassName.get("java.util", "LinkedHashMap");
    private static final ClassName LINKED_HASH_SET = ClassName.get("java.util", "LinkedHashSet");
    private static final ClassName NO_SUCH_ELEMENT = ClassName.get("java.util", "NoSuchElementException");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");
    private static final ClassName SET = ClassName.get("java.util", "Set");
    private static final ClassName COLLECTION = ClassName.get("java.util", "Collection");
    private static final ClassName DURATION = ClassName.get("java.time", "Duration");
    private static final ClassName FUNCTION = ClassName.get("java.util.function", "Function");
    private static final ClassName CONCURRENT_HASH_MAP = ClassName.get("java.util.concurrent", "ConcurrentHashMap");
    private static final ClassName COMPLETABLE_FUTURE = ClassName.get("java.util.concurrent", "CompletableFuture");
    private static final ClassName COMPLETION_EXCEPTION = ClassName.get("java.util.concurrent", "CompletionException");
    private static final ClassName EXECUTION_EXCEPTION = ClassName.get("java.util.concurrent", "ExecutionException");
    private static final ClassName TIMEOUT_EXCEPTION = ClassName.get("java.util.concurrent", "TimeoutException");
    private static final ClassName TIME_UNIT = ClassName.get("java.util.concurrent", "TimeUnit");
    private static final ClassName ATOMIC_INTEGER = ClassName.get("java.util.concurrent.atomic", "AtomicInteger");
    private static final ClassName ATOMIC_REFERENCE = ClassName.get("java.util.concurrent.atomic", "AtomicReference");
    private static final ClassName ATOMIC_BOOLEAN = ClassName.get("java.util.concurrent.atomic", "AtomicBoolean");
    private static final ClassName THREAD_LOCAL = ClassName.get("java.lang", "ThreadLocal");
    private static final TypeName OBJECT_KEY = ClassName.get(Object.class);
    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName CONFIGURATION = ClassName.get("org.jooq", "Configuration");
    private static final ClassName DATA_TYPE = ClassName.get("org.jooq", "DataType");
    private static final ClassName DATA_FETCHING_ENVIRONMENT = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName DATA_ACCESS_EXCEPTION = ClassName.get("org.jooq.exception", "DataAccessException");
    private static final ClassName DATA_FETCHER_RESULT = ClassName.get("graphql.execution", "DataFetcherResult");
    private static final ClassName GRAPHQL_ERROR = ClassName.get("graphql", "GraphQLError");
    private static final ClassName GRAPHQL_ERROR_BUILDER = ClassName.get("graphql", "GraphqlErrorBuilder");
    private static final ClassName SLF4J_LOGGER = ClassName.get("org.slf4j", "Logger");
    /** How long {@code PinnedConnection.release} waits on {@code Connection.isValid} after a failed settle. */
    private static final int RELEASE_VALIDITY_SECONDS = 1;
    private static final ClassName SLF4J_LOGGER_FACTORY = ClassName.get("org.slf4j", "LoggerFactory");
    private static final ClassName UUID_CLASS = ClassName.get("java.util", "UUID");
    private static final ClassName OPTIONAL = ClassName.get("java.util", "Optional");
    /** The graphQLContext key the request tenant set is published under. */
    public static final String TENANTS_KEY_FIELD = "TENANTS_KEY";
    /** The literal value of the emitted {@code TENANTS_KEY} constant (also written by the factory). */
    public static final String TENANTS_KEY_VALUE = "no.sikt.graphitron.request.tenants";
    /**
     * The client-facing {@code extensions.classification} vocabulary for per-tenant fan-out
     * failures: one value per non-success {@code Outcome} arm, emitted as named constants on the
     * carrier's {@code FanOutFailure} so the wire contract is single-sourced and greppable.
     */
    public static final String FAN_OUT_FAILED_CLASSIFICATION = "TenantFanOutFailed";
    /** {@link #FAN_OUT_FAILED_CLASSIFICATION}'s deadline sibling. */
    public static final String FAN_OUT_TIMED_OUT_CLASSIFICATION = "TenantFanOutTimedOut";

    private ConnectionRuntimeClassGenerator() {}

    /**
     * @param outputPackage the consumer's root output package; the classes are emitted into
     *                      {@code outputPackage + ".schema"} (beside {@code GraphitronContext})
     * @param identity  the graph's session identity as the store states it: a handle class types
     *                      the {@code Lease}'s {@code handle()} and the carrier's guarded reads
     */
    public static List<TypeSpec> generate(String outputPackage, SessionIdentityRows identity) {
        return generate(outputPackage, identity, ConnectionKeyShape.none(), null);
    }

    /**
     * Canonical form carrying the divined tenant key type and the representative tenant column.
     * {@code tenantKeyType} is the tenant Java type read off the jOOQ catalog's tenant column when
     * {@code <tenantColumn>} is configured, or {@code null} for single-tenant builds. A configured
     * type replaces the erased {@code Object} on every tenant-keyed surface (the constructor map,
     * the keyed acquisition, the per-operation carrier), so a consumer wiring a map keyed with
     * the wrong type is a compile error rather than a first-request lookup miss.
     *
     * <p>{@code tenantColumn} is the representative column rendered as
     * {@code <Tables>.<TABLE>.<COLUMN>}, whose {@code DataType} and converter decide tenant key
     * equality: every key graphitron receives is put through the converter and back before it is
     * compared, so two spellings the column stores as one database value name one tenant. Both
     * arguments are {@code null} for a single-tenant build or both present.
     *
     * @throws IllegalArgumentException when exactly one of the two is {@code null}
     */
    public static List<TypeSpec> generate(String outputPackage, SessionIdentityRows identity,
                                          TypeName tenantKeyType, CodeBlock tenantColumn) {
        return generate(outputPackage, identity, ConnectionKeyShape.ofTenantKey(tenantKeyType), tenantColumn);
    }

    /**
     * The canonical form: the build's {@link ConnectionKeyShape}, computed once by the caller, decides
     * whether a {@code ConnectionKey} is emitted and the carrier keyed by it, and carries the tenant key
     * type; {@code tenantColumn} is the representative tenant column, present exactly when the shape has
     * a tenant part.
     */
    public static List<TypeSpec> generate(String outputPackage, SessionIdentityRows identity,
                                          ConnectionKeyShape shape, CodeBlock tenantColumn) {
        return generate(outputPackage, identity, shape, tenantColumn, false);
    }

    /**
     * As {@link #generate(String, SessionIdentityRows, ConnectionKeyShape, CodeBlock)}, for a build
     * that may vouch: where {@code vouches}, every public runtime constructor takes the
     * {@code VoucherKeys} last.
     */
    public static List<TypeSpec> generate(String outputPackage, SessionIdentityRows identity,
                                          ConnectionKeyShape shape, CodeBlock tenantColumn, boolean vouches) {
        return generate(outputPackage, identity, shape, tenantColumn, vouches, false);
    }

    /**
     * As {@link #generate(String, SessionIdentityRows, ConnectionKeyShape, CodeBlock, boolean)}, for a
     * graph whose schema may carry an {@code @outbox(on: ERROR)} message: where {@code failureMessages},
     * the carrier runs a field's registered failure messages after its transaction rolled back
     * ({@code onFieldRollback}), and the runtime gains the {@code outboxAcquisitionTimeout} that bounds
     * the fresh connection they may be written on.
     */
    public static List<TypeSpec> generate(String outputPackage, SessionIdentityRows identity,
                                          ConnectionKeyShape shape, CodeBlock tenantColumn, boolean vouches,
                                          boolean failureMessages) {
        TypeName tenantKeyType = shape.tenantKey().orElse(null);
        if ((tenantKeyType == null) != (tenantColumn == null)) {
            throw new IllegalArgumentException("A multi-tenant runtime needs both the tenant key type and"
                + " the representative tenant column; a single-tenant one neither (key type: "
                + tenantKeyType + ", column: " + tenantColumn + ")");
        }
        TypeName tenantKey = tenantKeyType == null
            ? OBJECT_KEY
            : (tenantKeyType.isPrimitive() ? tenantKeyType.box() : tenantKeyType);
        String schemaPackage = outputPackage + ".schema";
        var connectionSource = ClassName.get(schemaPackage, CONNECTION_SOURCE_CLASS_NAME);
        var lease = ClassName.get(schemaPackage, LEASE_CLASS_NAME);
        var keyNotPermitted = ClassName.get(schemaPackage, KEY_NOT_PERMITTED_CLASS_NAME);
        var acquisitionFailed = ClassName.get(schemaPackage, ACQUISITION_FAILED_CLASS_NAME);
        var connectionSources = ClassName.get(schemaPackage, CONNECTION_SOURCES_CLASS_NAME);
        TypeName handleType = identity.handleType().orElse(null);
        var pinnedConnection = ClassName.get(schemaPackage, PINNED_CONNECTION_CLASS_NAME);
        var runtime = ClassName.get(schemaPackage, RUNTIME_CLASS_NAME);
        var ownedConnections = ClassName.get(schemaPackage, OWNED_CONNECTIONS_CLASS_NAME);
        var requestTenants = ClassName.get(schemaPackage, REQUEST_TENANTS_CLASS_NAME);
        var fetcherErrorLog = ErrorRouterClassGenerator.fetcherErrorLogClass(outputPackage);
        var fetcherErrorResponse = ErrorRouterClassGenerator.fetcherErrorResponseClass(outputPackage);
        var batchSizeLimit = ErrorRouterClassGenerator.batchSizeLimitClass(outputPackage);
        var clientExceptionUnit = new no.sikt.graphitron.plan.GeneratedUnits(outputPackage).clientException();
        var clientException = ClassName.get(clientExceptionUnit.packageName(), clientExceptionUnit.simpleName());
        var errorRouter = ClassName.get(schemaPackage, ErrorRouterClassGenerator.CLASS_NAME);
        var instrumentation = ClassName.get(schemaPackage, GraphitronConnectionInstrumentationGenerator.CLASS_NAME);
        var provider = ClassName.get(schemaPackage, GraphitronTransactionProviderGenerator.CLASS_NAME);
        var commitPolicy = provider.nestedClass(GraphitronTransactionProviderGenerator.COMMIT_POLICY_ENUM_NAME);
        var columnValues = ClassName.get(outputPackage + ".util", ColumnValuesClassGenerator.CLASS_NAME);

        boolean multiTenant = shape.writesSlot();
        var connectionKey = ConnectionKeyShape.className(outputPackage);
        // Every build keys its connections by the ConnectionKey; an untenanted build's tenant part is never
        // present from a routed site, but the key is the same value either way.
        TypeName keyType = connectionKey;

        var units = new ArrayList<TypeSpec>();
        units.add(connectionKey(connectionKey, tenantKey));
        units.add(connectionSource(connectionSource, lease, connectionKey, keyNotPermitted));
        units.add(lease(lease, handleType));
        units.add(keyNotPermitted(keyNotPermitted));
        units.add(acquisitionFailed(acquisitionFailed));
        units.add(connectionSources(connectionSources, connectionSource, lease, connectionKey, handleType));
        units.add(pinnedConnection(pinnedConnection, connectionSource, lease, connectionKey, keyNotPermitted,
            acquisitionFailed, handleType));
        units.add(runtime(runtime, pinnedConnection, instrumentation, fetcherErrorLog, fetcherErrorResponse,
            batchSizeLimit, multiTenant,
            vouches ? ClassName.get(schemaPackage, no.sikt.graphitron.emit.VoucherRuntime.KEYS) : null,
            failureMessages, connectionKey, connectionSource));
        units.add(ownedConnections(ownedConnections, runtime, pinnedConnection, provider, commitPolicy, tenantKey,
            multiTenant, identity, requestTenants, tenantColumn, errorRouter, columnValues, acquisitionFailed,
            keyNotPermitted, connectionSource, keyType, connectionKey, failureMessages));
        if (multiTenant) {
            units.add(requestTenants(requestTenants, ownedConnections, tenantKey));
        }
        units.add(fetcherErrorLog(fetcherErrorLog, multiTenant ? tenantKey : null));
        units.add(fetcherErrorResponse(fetcherErrorResponse, clientException));
        units.add(batchSizeLimit(batchSizeLimit));
        return List.copyOf(units);
    }

    /** Back-compatible overload for callers that mount no identity (unit-tier drivers, no {@code <sessionState>}). */
    public static List<TypeSpec> generate(String outputPackage) {
        return generate(outputPackage, SessionIdentityRows.empty());
    }

    /**
     * The acquisition-scoped pinned connection: Graphitron's wrapper of one {@code Lease}. Acquire
     * calls the request's source and asserts autocommit on what it leased; release settles any
     * transaction the operation left open and then releases the lease; {@code abort()} aborts the
     * connection through JDBC, for a connection another thread may still be executing on, and then
     * releases the lease. {@code release()} and {@code abort()} share one compare-and-set flag, so
     * whichever runs first is the only one that acts and the lease is released exactly once.
     */
    private static TypeSpec pinnedConnection(ClassName pinnedConnection, ClassName source, ClassName lease,
            ClassName connectionKey, ClassName keyNotPermitted, ClassName acquisitionFailed, TypeName handleType) {
        var constructorBuilder = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .addParameter(lease, "lease")
            .addParameter(SQL_DIALECT, "dialect")
            .addParameter(SETTINGS, "settings")
            .addParameter(EXECUTOR, "abortExecutor")
            .addStatement("this.lease = lease")
            .addStatement("this.connection = lease.connection()")
            .addStatement("this.dialect = dialect")
            .addStatement("this.settings = settings")
            .addStatement("this.abortExecutor = abortExecutor");

        var acquire = MethodSpec.methodBuilder("acquire")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(pinnedConnection)
            .addParameter(source, "source")
            .addParameter(connectionKey, "key")
            .addParameter(SQL_DIALECT, "dialect")
            .addParameter(SETTINGS, "settings")
            .addParameter(EXECUTOR, "abortExecutor")
            .addStatement("$T lease", lease)
            .beginControlFlow("try")
            .addStatement("lease = source.acquire(key)")
            .nextControlFlow("catch ($T refused)", keyNotPermitted)
            .addStatement("throw refused")
            .nextControlFlow("catch ($T failed)", Exception.class)
            .addStatement("throw new $T(failed)", acquisitionFailed)
            .endControlFlow()
            .beginControlFlow("if (lease == null)")
            .addStatement("throw new $T(new $T($S))", acquisitionFailed, NullPointerException.class,
                "the connection source returned no lease")
            .endControlFlow()
            .beginControlFlow("try")
            .addComment("Graphitron asserts the connection's transaction mode rather than forcing it: autocommit")
            .addComment("is the resting state each mutation field's transaction opens from, and a source that")
            .addComment("leased a connection inside an open transaction set its state where a rollback would")
            .addComment("revert it. Refuse the lease rather than roll its state back.")
            .beginControlFlow("if (!lease.connection().getAutoCommit())")
            .addStatement("throw new $T($S)", IllegalStateException.class,
                "the connection source leased a connection with autocommit off")
            .endControlFlow()
            .nextControlFlow("catch ($T unusable)", Exception.class)
            .addStatement("$T failure = new $T(unusable)", acquisitionFailed, acquisitionFailed)
            .beginControlFlow("try")
            .addStatement("lease.release()")
            .nextControlFlow("catch ($T releaseFailure)", Exception.class)
            .addStatement("failure.addSuppressed(releaseFailure)")
            .endControlFlow()
            .addStatement("throw failure")
            .endControlFlow()
            .beginControlFlow("if (lease.dialect() == null)")
            .addStatement("return new $T(lease, dialect, settings, abortExecutor)", pinnedConnection)
            .endControlFlow()
            .addStatement("return new $T(lease, lease.dialect(),\n"
                + "$>lease.settings() != null ? lease.settings() : new $T(), abortExecutor)$<", pinnedConnection, SETTINGS)
            .addJavadoc("Leases the connection for {@code key} from {@code source} and asserts autocommit on it.\n"
                + "A {@code KeyNotPermitted} passes through unchanged; any other throw, a missing lease, or a\n"
                + "lease with autocommit off becomes an {@code AcquisitionFailedException}, the request-level\n"
                + "rejection, and a lease refused that way is released before it is thrown. The lease's own\n"
                + "dialect and settings win over the runtime's {@code dialect} and {@code settings}.\n")
            .build();

        var release = MethodSpec.methodBuilder("release")
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .beginControlFlow("if (!released.compareAndSet(false, true))")
            .addStatement("return")
            .endControlFlow()
            .addStatement("$T failure = null", RuntimeException.class)
            .beginControlFlow("try")
            .addComment("If the operation left a transaction open (it died mid-mutation before the provider")
            .addComment("settled), roll it back before the release: JDBC commits an open transaction when")
            .addComment("autocommit is turned on, and the source's release runs in autocommit.")
            .beginControlFlow("if (!connection.getAutoCommit())")
            .addStatement("connection.rollback()")
            .addStatement("connection.setAutoCommit(true)")
            .endControlFlow()
            .nextControlFlow("catch ($T settleFailure)", SQL_EXCEPTION)
            .beginControlFlow("if (isValid(connection))")
            .addComment("A live connection that cannot be settled: what it holds cannot be proven cleared.")
            .addStatement("failure = rethrow(settleFailure)")
            .nextControlFlow("else")
            .addComment("The connection is gone (the server ended its session, or the network dropped it), and")
            .addComment("with it every transaction it held; the operation's response stands.")
            .addStatement("LOGGER.warn($S, settleFailure)",
                "A pinned connection was no longer valid when released; it was aborted before its release.")
            .endControlFlow()
            .addStatement("evict(connection, abortExecutor)")
            .endControlFlow()
            .beginControlFlow("try")
            .addStatement("lease.release()")
            .nextControlFlow("catch ($T releaseFailure)", Exception.class)
            .beginControlFlow("if (failure == null)")
            .addStatement("failure = rethrow(releaseFailure)")
            .nextControlFlow("else")
            .addStatement("failure.addSuppressed(releaseFailure)")
            .endControlFlow()
            .endControlFlow()
            .beginControlFlow("if (failure != null)")
            .addStatement("throw failure")
            .endControlFlow()
            .addJavadoc("Settles any transaction the operation left open, then releases the lease. Idempotent,\n"
                + "and a no-op after {@link #abort()}. A failed settle aborts the connection through JDBC\n"
                + "before the lease is released, and is rethrown unless the connection is no longer valid\n"
                + "({@code isValid}), which is logged as a warning only; a throwing release is rethrown, after\n"
                + "the settle's failure where both fail.\n")
            .build();

        var abort = MethodSpec.methodBuilder("abort")
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .beginControlFlow("if (!released.compareAndSet(false, true))")
            .addStatement("return")
            .endControlFlow()
            .addStatement("evict(connection, abortExecutor)")
            .beginControlFlow("try")
            .addStatement("lease.release()")
            .nextControlFlow("catch ($T releaseFailure)", Exception.class)
            .addStatement("LOGGER.warn($S, releaseFailure)", "Releasing an aborted lease failed.")
            .endControlFlow()
            .addJavadoc("Aborts the connection through JDBC and then releases the lease, for a connection another\n"
                + "thread may still be executing on: a fan-out worker past the join deadline, or a pin that\n"
                + "completed after the operation released its connections. The connection cannot be proven\n"
                + "idle, so it is aborted rather than settled; the other thread's eventual completion lands\n"
                + "harmlessly on the dead connection, and the lease's release finds it closed. A failing\n"
                + "release is logged, not thrown. Idempotent, and a no-op after {@link #release()}, as\n"
                + "{@code release()} is after it: the two share one flag.\n")
            .build();

        var isValid = MethodSpec.methodBuilder("isValid")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter(CONNECTION, "connection")
            .beginControlFlow("try")
            .addStatement("return connection.isValid($L)", RELEASE_VALIDITY_SECONDS)
            .nextControlFlow("catch ($T validityFailure)", Throwable.class)
            .addComment("A connection that cannot say whether it is valid is not.")
            .addStatement("return false")
            .endControlFlow()
            .addJavadoc("Whether {@code connection} is still valid after settling it at release failed, asked of\n"
                + "the driver ({@code Connection.isValid}), bounded to $L second(s): the driver's own answer,\n"
                + "because what a dead session surfaces differs by driver and by timing.\n",
                RELEASE_VALIDITY_SECONDS)
            .build();

        var evict = MethodSpec.methodBuilder("evict")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(void.class)
            .addParameter(CONNECTION, "connection")
            .addParameter(EXECUTOR, "abortExecutor")
            .beginControlFlow("try")
            .addComment("abort() is the JDBC evict primitive: pool wrappers discard the physical connection")
            .addComment("rather than reclaiming it, and it also covers a connection too dead to close cleanly.")
            .addStatement("connection.abort(abortExecutor)")
            .nextControlFlow("catch ($T abortFailure)", Throwable.class)
            .addComment("abort unsupported or itself failing: fall back to close so the connection never leaks.")
            .beginControlFlow("try")
            .addStatement("connection.close()")
            .nextControlFlow("catch ($T ignored)", Throwable.class)
            .addComment("nothing left to do; the connection is already unusable.")
            .endControlFlow()
            .endControlFlow()
            .build();

        var rethrow = MethodSpec.methodBuilder("rethrow")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(RuntimeException.class)
            .addParameter(Throwable.class, "cause")
            .beginControlFlow("if (cause instanceof $T e)", Error.class)
            .addStatement("throw e")
            .endControlFlow()
            .beginControlFlow("if (cause instanceof $T e)", RuntimeException.class)
            .addStatement("return e")
            .endControlFlow()
            .addStatement("return new $T(cause)", RuntimeException.class)
            .addJavadoc("Turns a caught {@code cause} into an unchecked throwable to rethrow: {@link Error}\n"
                + "is rethrown as-is, a {@link RuntimeException} is returned unchanged, and the checked\n"
                + "residue is wrapped.\n")
            .build();

        var builder = TypeSpec.classBuilder(PINNED_CONNECTION_CLASS_NAME)
            .addModifiers(Modifier.FINAL)
            .addSuperinterface(AutoCloseable.class)
            .addJavadoc("Graphitron's hold on one leased connection for its acquisition-scoped lifetime: the lease,\n"
                + "the dialect and settings its contexts render with, and the flag that releases it exactly\n"
                + "once. Carries no transaction machinery (the {@code TransactionProvider} and execution\n"
                + "instrumentation compose over this seam). See {@code ConnectionRuntimeClassGenerator} for\n"
                + "the full lifecycle contract.\n")
            .addField(FieldSpec.builder(SLF4J_LOGGER, "LOGGER", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.getLogger($T.class)", SLF4J_LOGGER_FACTORY, pinnedConnection)
                .build())
            .addField(FieldSpec.builder(lease, "lease", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(CONNECTION, "connection", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(SQL_DIALECT, "dialect", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(SETTINGS, "settings", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(EXECUTOR, "abortExecutor", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(ATOMIC_BOOLEAN, "released", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T()", ATOMIC_BOOLEAN)
                .addJavadoc("Set by whichever of {@code release()} and {@code abort()} runs first; the other, and any\n"
                    + "repeat of either, then does nothing.\n")
                .build())
            .addMethod(constructorBuilder.build())
            .addMethod(acquire)
            .addMethod(MethodSpec.methodBuilder("connection")
                .addModifiers(Modifier.PUBLIC)
                .returns(CONNECTION)
                .addStatement("return connection")
                .addJavadoc("The leased connection every fetch of this key runs on.\n")
                .build())
            .addMethod(MethodSpec.methodBuilder("dialect")
                .addModifiers(Modifier.PUBLIC)
                .returns(SQL_DIALECT)
                .addStatement("return dialect")
                .addJavadoc("The dialect this connection's contexts render with.\n")
                .build())
            .addMethod(MethodSpec.methodBuilder("settings")
                .addModifiers(Modifier.PUBLIC)
                .returns(SETTINGS)
                .addStatement("return settings")
                .addJavadoc("The jOOQ {@code Settings} this connection's contexts render with.\n")
                .build());
        if (handleType != null) {
            builder.addMethod(MethodSpec.methodBuilder("handle")
                .addModifiers(Modifier.PUBLIC)
                .returns(handleType)
                .addStatement("return lease.handle()")
                .addJavadoc("The lease's session handle.\n")
                .build());
        }
        return builder.addMethod(release)
            .addMethod(abort)
            .addMethod(MethodSpec.methodBuilder("close")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class)
                .addStatement("release()")
                .addJavadoc("{@link AutoCloseable} alias for {@link #release()}.\n")
                .build())
            .addMethod(isValid)
            .addMethod(evict)
            .addMethod(rethrow)
            .build();
    }

    /**
     * {@code private static String[][] captureSessionSettings(Connection)}: reads, on PostgreSQL, the
     * built-in settings a connection carries with {@code source = 'session'} and
     * {@code context = 'user'}, the pool's post-connect configuration, as {@code {names, values}}.
     * Custom variables never appear in {@code pg_settings}, so they are not captured and the reset
     * clears them; settings only a superuser may change are left out, since restoring one under a
     * non-superuser role would fail every release.
     */
    private static MethodSpec captureSessionSettings() {
        var listOfString = ParameterizedTypeName.get(LIST, ClassName.get(String.class));
        return MethodSpec.methodBuilder("captureSessionSettings")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(STRING_TABLE)
            .addParameter(CONNECTION, "connection")
            .addException(SQL_EXCEPTION)
            .addStatement("$T names = new $T<>()", listOfString, ARRAY_LIST)
            .addStatement("$T values = new $T<>()", listOfString, ARRAY_LIST)
            .beginControlFlow("try ($T capture = connection.createStatement();\n$>$>$T settings = capture.executeQuery($S\n$>$>+ $S$<$<))$<$<",
                STATEMENT, RESULT_SET, "select name, current_setting(name) from pg_settings",
                " where source = 'session' and context = 'user'")
            .beginControlFlow("while (settings.next())")
            .addStatement("names.add(settings.getString(1))")
            .addStatement("values.add(settings.getString(2))")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return new $T[][] {names.toArray(new $T[0]), values.toArray(new $T[0])}",
                String.class, String.class, String.class)
            .addJavadoc("The built-in settings this connection carries session-set, as {@code {names, values}}:\n"
                + "what the pool configured after connecting, read before the mount so release can restore\n"
                + "it after {@code RESET ALL}. Custom variables never appear in {@code pg_settings}, so the\n"
                + "reset clears them; superuser-only settings are left out, since restoring one under a\n"
                + "non-superuser role would fail.\n")
            .build();
    }

    /**
     * The application-scoped runtime: the jOOQ dialect and {@code Settings} every context Graphitron
     * mints over a leased connection renders with (unless the lease names its own), the same-thread
     * executor {@code Connection.abort} runs on, and the consumer's application-scoped callbacks. It
     * holds no connection and no pool: a request's connections come from the {@code ConnectionSource}
     * given with it. Multi-tenant builds additionally carry the fan-out execution configuration (the
     * bounded scatter executor and the scatter deadline). The held callbacks are the consumer's
     * optional {@code FetcherErrorLog}, {@code FetcherErrorResponse} and {@code BatchSizeLimit}, each
     * set through a wither rather than a constructor parameter so they compose with every constructor
     * form instead of doubling them.
     */
    private static TypeSpec runtime(ClassName runtime, ClassName pinnedConnection, ClassName instrumentation,
                                    ClassName fetcherErrorLog, ClassName fetcherErrorResponse,
                                    ClassName batchSizeLimit, boolean multiTenant, ClassName voucherKeys,
                                    boolean failureMessages, ClassName connectionKey, ClassName connectionSource) {
        // In a graph with failure messages the copy constructor carries the outbox acquisition bound
        // after the batch size limit, so every wither hands it on.
        String outboxArg = failureMessages ? ", outboxAcquisitionTimeout" : "";
        // In a build that vouches every public constructor takes the voucher keys last, so a runtime
        // without them is a compile error; keysArg appends them to a delegation.
        String keysArg = voucherKeys == null ? "" : ", voucherKeys";
        java.util.function.UnaryOperator<String> delegating = call -> call.substring(0, call.length() - 1) + keysArg + ")";
        java.util.function.UnaryOperator<MethodSpec.Builder> takesKeys = b -> voucherKeys == null ? b
            : b.addParameter(voucherKeys, "voucherKeys");
        String head = "dialect, settings" + (multiTenant ? ", fanOutExecutor, fanOutTimeout" : "");

        var dialectField = FieldSpec.builder(SQL_DIALECT, "dialect", Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("The dialect every context over a leased connection renders with, unless the lease\n"
                + "names its own.\n")
            .build();
        var settingsField = FieldSpec.builder(SETTINGS, "settings", Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("The jOOQ {@code Settings} beside {@link #dialect}; never {@code null}.\n")
            .build();
        // Same-thread executor for Connection.abort(); the abort work is trivial and must complete before
        // the borrow returns, so there is no reason to hand it to another thread.
        var abortExecutorField = FieldSpec.builder(EXECUTOR, "abortExecutor", Modifier.PRIVATE, Modifier.FINAL)
            .initializer("$T::run", Runnable.class)
            .build();
        // Fan-out execution configuration (multi-tenant builds only): the bounded scatter executor
        // and the scatter deadline.
        var defaultFanOutConcurrencyField = FieldSpec.builder(int.class, "DEFAULT_FAN_OUT_CONCURRENCY",
                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer("8")
            .addJavadoc("Default scatter concurrency cap: workers in flight on the runtime's one bounded\n"
                + "pool, shared by all requests.\n")
            .build();
        var defaultFanOutTimeoutField = FieldSpec.builder(DURATION, "DEFAULT_FAN_OUT_TIMEOUT",
                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer("$T.ofSeconds(10)", DURATION)
            .addJavadoc("Default scatter deadline per fanned field.\n")
            .build();
        var fanOutExecutorField = FieldSpec.builder(EXECUTOR, "fanOutExecutor", Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("The bounded executor scatter workers run on. Independent of {@link #abortExecutor}\n"
                + "(same-thread, for {@code Connection.abort} only); the two are never conflated.\n")
            .build();
        var fanOutTimeoutField = FieldSpec.builder(DURATION, "fanOutTimeout", Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("The per-scatter deadline, enforced by the scatter join whichever executor runs the\n"
                + "workers.\n")
            .build();
        var fetcherErrorLogField = FieldSpec.builder(fetcherErrorLog, "fetcherErrorLog", Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("The consumer's log for fetcher exceptions, or {@code null} for Graphitron's default\n"
                + "line. Set by {@link #withFetcherErrorLog}.\n")
            .build();
        var fetcherErrorResponseField = FieldSpec.builder(fetcherErrorResponse, "fetcherErrorResponse",
                Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("The consumer's choice of what the client reads for a fetcher exception, or\n"
                + "{@code null} for Graphitron's default text. Set by {@link #withFetcherErrorResponse}.\n")
            .build();
        var batchSizeLimitField = FieldSpec.builder(batchSizeLimit, "batchSizeLimit", Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("The consumer's bound on how many keys one batched query carries, or {@code null} for\n"
                + "no bound. Set by {@link #withBatchSizeLimit}.\n")
            .build();

        // Canonical constructor; every other public form delegates here.
        var canonicalBuilder = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(SQL_DIALECT, "dialect")
            .addParameter(SETTINGS, "settings");
        if (multiTenant) {
            canonicalBuilder
                .addParameter(EXECUTOR, "fanOutExecutor")
                .addParameter(DURATION, "fanOutTimeout");
        }
        canonicalBuilder
            .addStatement("this.dialect = $T.requireNonNull(dialect, $S)", OBJECTS, "dialect")
            .addStatement("this.settings = settings == null ? new $T() : settings", SETTINGS);
        if (multiTenant) {
            canonicalBuilder
                .addStatement("this.fanOutExecutor = $T.requireNonNull(fanOutExecutor, $S)", OBJECTS, "fanOutExecutor")
                .addStatement("this.fanOutTimeout = $T.requireNonNull(fanOutTimeout, $S)", OBJECTS, "fanOutTimeout")
                .beginControlFlow("if (fanOutTimeout.isZero() || fanOutTimeout.isNegative())")
                .addStatement("throw new $T($S + fanOutTimeout)", IllegalArgumentException.class,
                    "fanOutTimeout must be positive, got: ")
                .endControlFlow();
        }
        takesKeys.apply(canonicalBuilder);
        if (voucherKeys != null) {
            canonicalBuilder.addStatement("this.voucherKeys = $T.requireNonNull(voucherKeys, $S)", OBJECTS, "voucherKeys");
        }
        canonicalBuilder.addStatement("this.fetcherErrorLog = null")
            .addStatement("this.fetcherErrorResponse = null")
            .addStatement("this.batchSizeLimit = null");
        if (failureMessages) {
            canonicalBuilder.addStatement("this.outboxAcquisitionTimeout = DEFAULT_OUTBOX_ACQUISITION_TIMEOUT");
        }
        var canonicalConstructor = canonicalBuilder
            .addJavadoc("Builds the runtime: the dialect and jOOQ {@code Settings} every context over a leased\n"
                + "connection renders with, unless the lease names its own, so a consumer's schema or render\n"
                + "mapping reaches every context graphitron mints.\n"
                + "@param dialect the jOOQ {@code SQLDialect}; must not be {@code null}\n"
                + "@param settings the jOOQ {@code Settings}; {@code null} means defaults\n"
                + (multiTenant
                    ? "@param fanOutExecutor the executor scatter workers run on; the supplier owns its\n"
                        + "concurrency bound (e.g. virtual threads); must not be {@code null}\n"
                        + "@param fanOutTimeout the per-scatter deadline, enforced by the join whichever\n"
                        + "executor runs the workers; must be positive\n"
                    : ""))
            .build();

        // The wither's copy constructor: every field as-is, so a multi-tenant runtime's fan-out
        // executor is shared rather than a second pool created.
        var copyBuilder = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .addParameter(SQL_DIALECT, "dialect")
            .addParameter(SETTINGS, "settings");
        if (multiTenant) {
            copyBuilder
                .addParameter(EXECUTOR, "fanOutExecutor")
                .addParameter(DURATION, "fanOutTimeout");
        }
        copyBuilder.addParameter(fetcherErrorLog, "fetcherErrorLog")
            .addParameter(fetcherErrorResponse, "fetcherErrorResponse")
            .addParameter(batchSizeLimit, "batchSizeLimit");
        if (failureMessages) {
            copyBuilder.addParameter(DURATION, "outboxAcquisitionTimeout");
        }
        takesKeys.apply(copyBuilder)
            .addStatement("this.dialect = dialect")
            .addStatement("this.settings = settings");
        if (multiTenant) {
            copyBuilder
                .addStatement("this.fanOutExecutor = fanOutExecutor")
                .addStatement("this.fanOutTimeout = fanOutTimeout");
        }
        var copyConstructor = copyBuilder
            .addStatement("this.fetcherErrorLog = fetcherErrorLog")
            .addStatement("this.fetcherErrorResponse = fetcherErrorResponse")
            .addStatement("this.batchSizeLimit = batchSizeLimit")
            .addCode(failureMessages ? CodeBlock.of("this.outboxAcquisitionTimeout = outboxAcquisitionTimeout;\n")
                : CodeBlock.of(""))
            .addCode(voucherKeys == null ? CodeBlock.of("") : CodeBlock.of("this.voucherKeys = voucherKeys;\n"))
            .build();

        var withFetcherErrorLog = MethodSpec.methodBuilder("withFetcherErrorLog")
            .addModifiers(Modifier.PUBLIC)
            .returns(runtime)
            .addParameter(fetcherErrorLog, "log")
            .addStatement(delegating.apply("return new $T(" + head + ", $T.requireNonNull(log, $S),"
                    + " fetcherErrorResponse, batchSizeLimit" + outboxArg + ")"),
                runtime, OBJECTS, "log")
            .addJavadoc("A copy of this runtime that hands every fetcher exception no {@code @error} mapping\n"
                + "claims to {@code log} instead of Graphitron's own ERROR line, and every exception a\n"
                + "handler caught to its {@code matched}. The client's response does not change. The copy\n"
                + "shares everything else with this runtime; this runtime is unchanged.\n"
                + "@param log the consumer's log; must not be {@code null}\n"
                + "@return a runtime carrying {@code log}\n")
            .build();
        var withFetcherErrorResponse = MethodSpec.methodBuilder("withFetcherErrorResponse")
            .addModifiers(Modifier.PUBLIC)
            .returns(runtime)
            .addParameter(fetcherErrorResponse, "response")
            .addStatement(delegating.apply("return new $T(" + head + ", fetcherErrorLog,"
                    + " $T.requireNonNull(response, $S), batchSizeLimit" + outboxArg + ")"),
                runtime, OBJECTS, "response")
            .addJavadoc("A copy of this runtime that asks {@code response} for the reference of every fetcher\n"
                + "exception, the {@code message:} of every error a handler caught and the top-level message\n"
                + "of every error none caught. The copy shares everything else with this runtime; this\n"
                + "runtime is unchanged.\n"
                + "@param response the consumer's response; must not be {@code null}\n"
                + "@return a runtime carrying {@code response}\n")
            .build();
        var withBatchSizeLimit = MethodSpec.methodBuilder("withBatchSizeLimit")
            .addModifiers(Modifier.PUBLIC)
            .returns(runtime)
            .addParameter(batchSizeLimit, "limit")
            .addStatement(delegating.apply("return new $T(" + head + ", fetcherErrorLog, fetcherErrorResponse,"
                    + " $T.requireNonNull(limit, $S)" + outboxArg + ")"),
                runtime, OBJECTS, "limit")
            .addJavadoc("A copy of this runtime that asks {@code limit} how many keys one batched query may\n"
                + "carry: a batch over the answer runs as several queries of at most that many keys. Without\n"
                + "one a batch is as large as the request makes it. The copy shares everything else with\n"
                + "this runtime; this runtime is unchanged.\n"
                + "@param limit the consumer's limit; must not be {@code null}\n"
                + "@return a runtime carrying {@code limit}\n")
            .build();
        var batchSizeLimitAccessor = MethodSpec.methodBuilder("batchSizeLimit")
            .addModifiers(Modifier.PUBLIC)
            .returns(ParameterizedTypeName.get(OPTIONAL, batchSizeLimit))
            .addStatement("return $T.ofNullable(batchSizeLimit)", OPTIONAL)
            .addJavadoc("The limit set by {@link #withBatchSizeLimit}, or empty for unbounded batches.\n")
            .build();
        var fetcherErrorLogAccessor = MethodSpec.methodBuilder("fetcherErrorLog")
            .addModifiers(Modifier.PUBLIC)
            .returns(ParameterizedTypeName.get(OPTIONAL, fetcherErrorLog))
            .addStatement("return $T.ofNullable(fetcherErrorLog)", OPTIONAL)
            .addJavadoc("The log set by {@link #withFetcherErrorLog}, or empty for Graphitron's default line.\n")
            .build();
        var fetcherErrorResponseAccessor = MethodSpec.methodBuilder("fetcherErrorResponse")
            .addModifiers(Modifier.PUBLIC)
            .returns(ParameterizedTypeName.get(OPTIONAL, fetcherErrorResponse))
            .addStatement("return $T.ofNullable(fetcherErrorResponse)", OPTIONAL)
            .addJavadoc("The response set by {@link #withFetcherErrorResponse}, or empty for Graphitron's\n"
                + "default text.\n")
            .build();

        var defaultFanOutConstructor = multiTenant
            ? takesKeys.apply(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(SQL_DIALECT, "dialect")
                .addParameter(SETTINGS, "settings"))
                .addStatement(delegating.apply("this(dialect, settings,"
                    + " boundedFanOutPool(DEFAULT_FAN_OUT_CONCURRENCY), DEFAULT_FAN_OUT_TIMEOUT)"))
                .addJavadoc("Builds the runtime with the default fan-out configuration.\n"
                    + "@param dialect the jOOQ {@code SQLDialect}; must not be {@code null}\n"
                    + "@param settings the jOOQ {@code Settings}; {@code null} means defaults\n")
                .build()
            : null;

        var cappedConstructor = multiTenant
            ? takesKeys.apply(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(SQL_DIALECT, "dialect")
                .addParameter(SETTINGS, "settings")
                .addParameter(int.class, "fanOutConcurrency")
                .addParameter(DURATION, "fanOutTimeout"))
                .addStatement(delegating.apply("this(dialect, settings, boundedFanOutPool(fanOutConcurrency),"
                    + " fanOutTimeout)"))
                .addJavadoc("Builds the runtime with an explicit fan-out cap and deadline; the runtime owns a\n"
                    + "bounded pool of platform threads sized by the cap. To own threading yourself (e.g.\n"
                    + "virtual threads), use the {@code Executor}-form constructor instead.\n"
                    + "@param dialect the jOOQ {@code SQLDialect}; must not be {@code null}\n"
                    + "@param settings the jOOQ {@code Settings}; {@code null} means defaults\n"
                    + "@param fanOutConcurrency the maximum scatter workers in flight; at least 1\n"
                    + "@param fanOutTimeout the per-scatter deadline; must be positive\n")
                .build()
            : null;

        var constructor = takesKeys.apply(MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(SQL_DIALECT, "dialect"))
            .addStatement(delegating.apply(multiTenant
                ? "this(dialect, null, boundedFanOutPool(DEFAULT_FAN_OUT_CONCURRENCY), DEFAULT_FAN_OUT_TIMEOUT)"
                : "this(dialect, null)"))
            .addJavadoc("Builds the runtime over {@code dialect} with default jOOQ {@code Settings}.\n"
                + "@param dialect the jOOQ {@code SQLDialect}; must not be {@code null}\n")
            .build();

        var boundedFanOutPool = MethodSpec.methodBuilder("boundedFanOutPool")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(EXECUTOR)
            .addParameter(int.class, "fanOutConcurrency")
            .beginControlFlow("if (fanOutConcurrency < 1)")
            .addStatement("throw new $T($S + fanOutConcurrency)", IllegalArgumentException.class,
                "fanOutConcurrency must be at least 1, got: ")
            .endControlFlow()
            .addStatement("$T names = new $T()", ATOMIC_INTEGER, ATOMIC_INTEGER)
            .addCode("return $T.newFixedThreadPool(fanOutConcurrency, task -> {\n", EXECUTORS)
            .addCode("    $T thread = new $T(task, $S + names.incrementAndGet());\n",
                Thread.class, Thread.class, "graphitron-fanout-")
            .addCode("    thread.setDaemon(true);\n")
            .addCode("    return thread;\n")
            .addCode("});\n")
            .addJavadoc("The default scatter executor: a fixed pool of daemon platform threads sized by the\n"
                + "cap (generated output targets Java 17, so no virtual threads here; a consumer on a newer\n"
                + "JVM supplies its own {@code Executor} instead). Daemon threads, so an application\n"
                + "shutdown is never held open by idle fan-out workers.\n")
            .build();
        var fanOutExecutorAccessor = MethodSpec.methodBuilder("fanOutExecutor")
            .addModifiers(Modifier.PUBLIC)
            .returns(EXECUTOR)
            .addStatement("return fanOutExecutor")
            .addJavadoc("The executor scatter workers run on.\n")
            .build();
        var fanOutTimeoutAccessor = MethodSpec.methodBuilder("fanOutTimeout")
            .addModifiers(Modifier.PUBLIC)
            .returns(DURATION)
            .addStatement("return fanOutTimeout")
            .addJavadoc("The per-scatter deadline, enforced by the scatter join.\n")
            .build();

        var dialectAccessor = MethodSpec.methodBuilder("dialect")
            .addModifiers(Modifier.PUBLIC)
            .returns(SQL_DIALECT)
            .addStatement("return dialect")
            .addJavadoc("The jOOQ {@code SQLDialect} contexts over a leased connection render with, unless the\n"
                + "lease names its own.\n")
            .build();
        var settingsAccessor = MethodSpec.methodBuilder("settings")
            .addModifiers(Modifier.PUBLIC)
            .returns(SETTINGS)
            .addStatement("return settings")
            .addJavadoc("The jOOQ {@code Settings} beside {@link #dialect()}; never {@code null}.\n")
            .build();

        var acquire = MethodSpec.methodBuilder("acquire")
            .returns(pinnedConnection)
            .addParameter(connectionKey, "key")
            .addParameter(connectionSource, "source")
            .addStatement("return $T.acquire(source, key, dialect, settings, abortExecutor)", pinnedConnection)
            .addJavadoc("Leases the connection for {@code key} from {@code source} and pins it under this\n"
                + "runtime's dialect and settings. The caller releases the returned {@code PinnedConnection}\n"
                + "exactly once; the per-operation carrier wired by the execution instrumentation does this,\n"
                + "so consumers register nothing.\n")
            .build();

        var graphQL = ClassName.get("graphql", "GraphQL");
        var graphQLBuilder = ClassName.get("graphql", "GraphQL", "Builder");
        var graphQLSchema = ClassName.get("graphql.schema", "GraphQLSchema");
        var newGraphQL = MethodSpec.methodBuilder("newGraphQL")
            .addModifiers(Modifier.PUBLIC)
            .returns(graphQLBuilder)
            .addParameter(graphQLSchema, "schema")
            .addStatement("return $T.newGraphQL(schema).instrumentation(new $T(this))", graphQL, instrumentation)
            .addJavadoc("Builds a {@link graphql.GraphQL.Builder} over {@code schema} with the connection-lifecycle\n"
                + "instrumentation already attached: every operation leases connections from the request's\n"
                + "connection source on first demand, runs each top-level mutation field in one transaction (queries in autocommit),\n"
                + "and releases at completion, with no registration by the consumer. This is the owned-connection engine assembly; pair it with\n"
                + "{@code Graphitron.buildSchema(...)}: {@code var engine = runtime.newGraphQL(Graphitron.buildSchema(b -> {})).build();}.\n"
                + "\n"
                + "<p>A later {@code .instrumentation(other)} on the returned builder replaces the attached\n"
                + "instrumentation, after which nothing is pinned or mounted; to add your own (for example\n"
                + "OpenTelemetry) use {@link #newGraphQL(graphql.schema.GraphQLSchema, graphql.execution.instrumentation.Instrumentation...)},\n"
                + "which keeps graphitron's first in the chain. Keep graphql-java's\n"
                + "serial mutation strategy: a {@code .mutationExecutionStrategy(...)} that is not an\n"
                + "{@code AsyncSerialExecutionStrategy} is refused when a mutation starts, since each\n"
                + "top-level mutation field settles its transaction when the next one starts.\n"
                + "\n<p>When the request's connection source fails to lease a connection, {@code execute}\n"
                + "throws {@code AcquisitionFailedException} in place of a result, with the source's exception\n"
                + "as its cause.\n"
                + "\n"
                + "<p>The escape-hatch engine ({@code Graphitron.newGraphQL()}) attaches no instrumentation;\n"
                + "there the caller owns the {@code DSLContext}, transactions, and identity.\n"
                + "@param schema the {@link graphql.schema.GraphQLSchema} from {@code Graphitron.buildSchema(...)}\n"
                + "@return a builder with the owned-connection instrumentation attached, ready for {@code .build()}\n")
            .build();

        var instrumentationType = ClassName.get("graphql.execution.instrumentation", "Instrumentation");
        var chainedInstrumentation = ClassName.get("graphql.execution.instrumentation", "ChainedInstrumentation");
        var newGraphQLWithInstrumentations = MethodSpec.methodBuilder("newGraphQL")
            .addModifiers(Modifier.PUBLIC)
            .varargs()
            .returns(graphQLBuilder)
            .addParameter(graphQLSchema, "schema")
            .addParameter(ArrayTypeName.of(instrumentationType), "instrumentations")
            .addStatement("$T.requireNonNull(instrumentations, $S)", OBJECTS, "instrumentations")
            .addStatement("$T<$T> chain = new $T<>()", ClassName.get("java.util", "List"), instrumentationType,
                ClassName.get("java.util", "ArrayList"))
            .addStatement("chain.add(new $T(this))", instrumentation)
            .beginControlFlow("for ($T instrumentation : instrumentations)", instrumentationType)
            .addStatement("chain.add($T.requireNonNull(instrumentation, $S))", OBJECTS, "instrumentation")
            .endControlFlow()
            .addStatement("return $T.newGraphQL(schema).instrumentation(new $T(chain))", graphQL, chainedInstrumentation)
            .addJavadoc("Like {@link #newGraphQL(graphql.schema.GraphQLSchema)}, with {@code instrumentations}\n"
                + "(for example OpenTelemetry) chained after the connection-lifecycle instrumentation, in the\n"
                + "order given, so adding one never removes connection handling and the consumer names\n"
                + "no chained instrumentation of their own. With none it is the one-argument form.\n"
                + "\n"
                + "<p>A later {@code .instrumentation(other)} on the returned builder still replaces the whole\n"
                + "chain, graphitron's included, after which nothing is pinned or mounted; pass instrumentations\n"
                + "here rather than setting them on the builder.\n"
                + "@param schema the {@link graphql.schema.GraphQLSchema} from {@code Graphitron.buildSchema(...)}\n"
                + "@param instrumentations further instrumentations, run after graphitron's; none may be {@code null}\n"
                + "@return a builder with the owned-connection instrumentation attached first, ready for {@code .build()}\n")
            .build();

        var builder = TypeSpec.classBuilder(RUNTIME_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Application-scoped runtime that owns the connection lifecycle: built once at wiring\n"
                + "time via {@code Graphitron.runtime(dialect)}, it leases each request's connections from the\n"
                + "request's {@code ConnectionSource} on first demand, demarcates one transaction per\n"
                + "top-level mutation field (via the instrumentation {@link #newGraphQL} attaches), and\n"
                + "releases every lease exactly once. Holds no per-request state and no connection. The\n"
                + "callbacks it holds are the consumer's application-scoped {@code FetcherErrorLog}\n"
                + "({@link #withFetcherErrorLog}), {@code FetcherErrorResponse}\n"
                + "({@link #withFetcherErrorResponse}) and {@code BatchSizeLimit}\n"
                + "({@link #withBatchSizeLimit}).\n");
        if (multiTenant) {
            builder.addField(defaultFanOutConcurrencyField)
                .addField(defaultFanOutTimeoutField);
        }
        builder.addField(dialectField)
            .addField(settingsField)
            .addField(abortExecutorField);
        if (multiTenant) {
            builder.addField(fanOutExecutorField)
                .addField(fanOutTimeoutField);
        }
        builder.addField(fetcherErrorLogField)
            .addField(fetcherErrorResponseField)
            .addField(batchSizeLimitField);
        if (failureMessages) {
            addOutboxAcquisition(builder, runtime, multiTenant, delegating);
        }
        if (voucherKeys != null) {
            builder.addField(FieldSpec.builder(voucherKeys, "voucherKeys", Modifier.PRIVATE, Modifier.FINAL)
                    .addJavadoc("The keys this subgraph signs and verifies vouchers with; every constructor takes\n"
                        + "them, so a build that vouches has no runtime without them.\n")
                    .build())
                .addMethod(MethodSpec.methodBuilder("voucherKeys")
                    .addModifiers(Modifier.PUBLIC)
                    .returns(voucherKeys)
                    .addStatement("return voucherKeys")
                    .addJavadoc("The keys vouchers are signed and verified with.\n")
                    .build());
        }
        builder.addMethod(canonicalConstructor)
            .addMethod(copyConstructor);
        if (multiTenant) {
            builder.addMethod(defaultFanOutConstructor)
                .addMethod(cappedConstructor);
        }
        builder.addMethod(constructor)
            .addMethod(dialectAccessor)
            .addMethod(settingsAccessor)
            .addMethod(acquire)
            .addMethod(newGraphQL)
            .addMethod(newGraphQLWithInstrumentations)
            .addMethod(withFetcherErrorLog)
            .addMethod(fetcherErrorLogAccessor)
            .addMethod(withFetcherErrorResponse)
            .addMethod(fetcherErrorResponseAccessor)
            .addMethod(withBatchSizeLimit)
            .addMethod(batchSizeLimitAccessor);
        if (multiTenant) {
            builder.addMethod(fanOutExecutorAccessor)
                .addMethod(fanOutTimeoutAccessor)
                .addMethod(boundedFanOutPool);
        }
        return builder.build();
    }

    /**
     * The runtime's members for a graph with {@code @outbox(on: ERROR)} messages: the bound on the
     * fresh connection such a message may be written on ({@code outboxAcquisitionTimeout}, with its
     * default, wither and accessor), and the executor that acquisition runs on: the scatter executor
     * in a multi-tenant build, which already exists there, and otherwise a small fixed pool of daemon
     * threads created on first use, as the default scatter pool is, and never shut down, since the
     * runtime has no lifecycle and an idle daemon thread holds no application open.
     */
    private static void addOutboxAcquisition(TypeSpec.Builder builder, ClassName runtime, boolean multiTenant,
                                             java.util.function.UnaryOperator<String> delegating) {
        builder.addField(FieldSpec.builder(DURATION, "DEFAULT_OUTBOX_ACQUISITION_TIMEOUT",
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.ofSeconds(5)", DURATION)
                .addJavadoc("Default bound on acquiring a fresh connection for a mutation field's\n"
                    + "{@code @outbox(on: ERROR)} messages.\n")
                .build())
            .addField(FieldSpec.builder(DURATION, "outboxAcquisitionTimeout", Modifier.PRIVATE, Modifier.FINAL)
                .addJavadoc("How long settle waits for a fresh connection to write a field's failure messages on,\n"
                    + "when the field's transaction cost it its own; set by {@link #withOutboxAcquisitionTimeout}.\n")
                .build());
        String fanOut = multiTenant ? ", fanOutExecutor, fanOutTimeout" : "";
        builder.addMethod(MethodSpec.methodBuilder("withOutboxAcquisitionTimeout")
                .addModifiers(Modifier.PUBLIC)
                .returns(runtime)
                .addParameter(DURATION, "timeout")
                .addStatement("$T.requireNonNull(timeout, $S)", OBJECTS, "timeout")
                .beginControlFlow("if (timeout.isZero() || timeout.isNegative())")
                .addStatement("throw new $T($S + timeout)", IllegalArgumentException.class,
                    "outboxAcquisitionTimeout must be positive, got: ")
                .endControlFlow()
                .addStatement(delegating.apply("return new $T(dialect, settings" + fanOut
                    + ", fetcherErrorLog, fetcherErrorResponse, batchSizeLimit, timeout)"), runtime)
                .addJavadoc("A copy of this runtime that waits at most {@code timeout} for a fresh connection to\n"
                    + "write a mutation field's {@code @outbox(on: ERROR)} messages on, when the field's\n"
                    + "transaction cost it its own (a tenant that timed out, or a session the database ended\n"
                    + "over the transaction). Past it the messages are not written and the loss is logged; the\n"
                    + "next field is never held longer. Five seconds by default. The copy shares everything\n"
                    + "else with this runtime; this runtime is unchanged.\n"
                    + "@param timeout the bound; must be positive\n"
                    + "@return a runtime carrying {@code timeout}\n")
                .build())
            .addMethod(MethodSpec.methodBuilder("outboxAcquisitionTimeout")
                .addModifiers(Modifier.PUBLIC)
                .returns(DURATION)
                .addStatement("return outboxAcquisitionTimeout")
                .addJavadoc("The bound set by {@link #withOutboxAcquisitionTimeout}, five seconds by default.\n")
                .build());
        var outboxExecutor = MethodSpec.methodBuilder("outboxExecutor")
            .addModifiers(Modifier.PUBLIC)
            .returns(EXECUTOR)
            .addJavadoc("The executor a fresh connection for a mutation field's {@code @outbox(on: ERROR)}\n"
                + "messages is acquired on"
                + (multiTenant ? ": the scatter executor.\n"
                    : ": a small pool of daemon threads, created on first use and\nshared by every runtime.\n"));
        if (multiTenant) {
            builder.addMethod(outboxExecutor.addStatement("return fanOutExecutor").build());
            return;
        }
        var pool = ClassName.get(runtime.packageName(), runtime.simpleName(), "OutboxAcquisitionPool");
        builder.addMethod(outboxExecutor.addStatement("return $T.EXECUTOR", pool).build());
        builder.addType(TypeSpec.classBuilder("OutboxAcquisitionPool")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("Holds the outbox acquisition pool, created when the class is first used: a fixed pool of\n"
                + "daemon platform threads, never shut down.\n")
            .addField(FieldSpec.builder(EXECUTOR, "EXECUTOR", Modifier.STATIC, Modifier.FINAL)
                .initializer(CodeBlock.builder()
                    .add("$T.newFixedThreadPool(4, task -> {\n", EXECUTORS).indent()
                    .addStatement("$T thread = new $T(task, $S)", Thread.class, Thread.class, "graphitron-outbox-acquisition")
                    .addStatement("thread.setDaemon(true)")
                    .addStatement("return thread")
                    .unindent().add("})")
                    .build())
                .build())
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .build());
    }

    /**
     * The message {@code entryFor} refuses with once the carrier is closed: before pinning
     * ({@code pinned} false) or for a pin that completed after release ({@code pinned} true). It
     * names a routed key's tenant and says nothing of the default source's empty key. The
     * multi-tenant refusal after a pin keeps naming the scatter deadline, which shares its check.
     */
    private static CodeBlock closedRefusal(boolean multiTenant, boolean pinned) {
        // The carrier is keyed by the ConnectionKey; its message names the tenant part as the Optional key
        // it replaced did.
        String tenant = "key.tenant()";
        if (multiTenant && pinned) {
            return CodeBlock.of("$S + $L + $S", "Key '", tenant,
                "' was pinned after its scatter deadline or after the operation completed.");
        }
        return CodeBlock.of("$S + $L.map(k -> $S + k + $S).orElse($S) + $S", "A connection", tenant,
            " for tenant '", "'", "", " was requested after the operation had released its connections.");
    }

    /**
     * The per-operation connection carrier, unified across both topologies with single-tenant as
     * the one-key case: one entry per <em>distinct</em> key within an operation, keyed by the
     * generated {@code ConnectionKey} in a build that writes the {@code localContext} slot, its empty
     * tenant the default source, and by {@code Optional<tenantKey>} with {@code Optional.empty()} as
     * the default source in one that does not (either is a non-null value, which is what sidesteps
     * {@code ConcurrentHashMap}'s no-null-keys rule).
     * An entry holds the key's pinned connection and its provider-bound {@code DSLContext}
     * together, minted inside the one {@code computeIfAbsent} and discarded together at release:
     * one cached {@code DSLContext} per key is one transaction provider per pinned connection,
     * so the provider's scope stack is the only record of open transactions on that connection, and the
     * mount's handle (written once to the entry's {@code Configuration}) lives and dies with the
     * connection it describes.
     *
     * <p>Acquisition is lazy on every path: {@code dslFor(key)} / {@code dslDefault()} pin and
     * mount on first demand and reuse thereafter, so an operation that touches no database never
     * pins and never mounts. {@code releaseAll()} releases every entry on every completion path,
     * per-connection eviction on unmount failure, idempotent. Release closes the carrier: any
     * acquisition after that point is refused, in both topologies, and a pin that was in flight
     * when the carrier closed is aborted rather than handed out or leaked.
     *
     * <p>The {@code DSL.using(...) + TransactionProvider} binding lives here, single-sourced, so
     * the many per-field routing sites consume {@code dslFor(key)} / {@code getDslContext(env)}
     * and never re-emit the binding (only <em>which key</em> and <em>where it routes</em> are
     * schema-shaped). The payload is retained once, by this carrier: lazy acquisition means any
     * fetcher may trigger the mount for a key, so the carrier holds the payload contextArguments
     * for the life of the request and hands them to {@code acquire} on each mint.
     */
    private static TypeSpec ownedConnections(ClassName self, ClassName runtime, ClassName pinnedConnection,
                                              ClassName provider, ClassName commitPolicy, TypeName tenantKey,
                                              boolean multiTenant, SessionIdentityRows identity,
                                              ClassName requestTenants, CodeBlock tenantColumn,
                                              ClassName errorRouter, ClassName columnValues,
                                              ClassName acquisitionFailed, ClassName keyNotPermitted,
                                              ClassName connectionSource, TypeName keyType,
                                              ClassName connectionKey, boolean failureMessages) {
        TypeName handleType = identity.handleType().orElse(null);
        boolean handled = handleType != null;
        var clientException = ClassName.get(self.packageName(),
            no.sikt.graphitron.rewrite.generators.schema.GraphitronClientExceptionClassGenerator.CLASS_NAME);
        var optionalKey = ParameterizedTypeName.get(ClassName.get("java.util", "Optional"), tenantKey);
        var entryType = self.nestedClass("Entry");
        var entryMapType = ParameterizedTypeName.get(MAP, keyType, entryType);

        var runtimeField = FieldSpec.builder(runtime, "runtime", Modifier.PRIVATE, Modifier.FINAL).build();
        var policyField = FieldSpec.builder(commitPolicy, "commitPolicy", Modifier.PRIVATE, Modifier.FINAL).build();
        var entriesField = FieldSpec.builder(entryMapType, "entries", Modifier.PRIVATE, Modifier.FINAL)
            .initializer("new $T<>()", CONCURRENT_HASH_MAP)
            .addJavadoc("Concurrent in both topologies, with per-key single acquisition\n"
                + "({@code computeIfAbsent}, the only minting mechanism in the carrier): "
                + (multiTenant
                    ? "under fan-out,\nscatter partitions distinct keys one worker each but nothing"
                        + " structural prevents a\nworker and the dispatch thread racing the same key,"
                        + " so one-pin-per-key is this map's\ncontract, not an accident of the callers"
                    : "under lazy\nacquisition, serial dispatch is an execution-strategy assumption"
                        + " rather than a\nstructural guarantee, and the concurrent map is cheaper than"
                        + " defending it")
                + ".\n")
            .build();
        var timedOutField = FieldSpec.builder(ParameterizedTypeName.get(SET, tenantKey), "timedOutTenants",
                Modifier.PRIVATE, Modifier.FINAL)
            .initializer("$T.newKeySet()", CONCURRENT_HASH_MAP)
            .addJavadoc("Tenant keys whose scatter worker missed the join deadline. A {@code TimedOut} outcome\n"
                + "means the join stopped waiting, not that the worker stopped working, so a timed-out\n"
                + "key's pinned connection may still be executing: {@link #dslFor} never hands it out\n"
                + "again, and {@link #releaseAll} routes it through {@code PinnedConnection.abort()}\n"
                + "instead of a close that would return a possibly-live connection to the pool.\n")
            .build();
        var acquisitionFailureField = FieldSpec.builder(ParameterizedTypeName.get(ATOMIC_REFERENCE, acquisitionFailed),
                "firstAcquisitionFailure", Modifier.PRIVATE, Modifier.FINAL)
            .initializer("new $T<>()", ATOMIC_REFERENCE)
            .addJavadoc("The operation's first failed acquisition, set once ({@code compareAndSet}, so concurrent\n"
                + "acquisitions resolve to one winner). Every later acquisition on any key rethrows it\n"
                + "rather than asking the source again: the request is rejected as a whole.\n")
            .build();
        var sourceField = FieldSpec.builder(connectionSource, SOURCE_SLOT, Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("The request's connection source, held for the life of the request: any fetcher may\n"
                + "trigger the first acquisition for a key.\n")
            .build();
        var closedField = FieldSpec.builder(boolean.class, "closed", Modifier.PRIVATE, Modifier.VOLATILE)
            .addJavadoc("Set by {@link #releaseAll} before draining. An acquisition that starts after it is set\n"
                + "is refused before taking a connection; one whose pin completes after it is set aborts\n"
                + "its own connection instead of leaking it into an operation that has already released.\n")
            .build();
        var scatterWorkerMarkerField = FieldSpec.builder(
                ParameterizedTypeName.get(THREAD_LOCAL, ClassName.get(Boolean.class)), "SCATTER_WORKER",
                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("new $T<>()", THREAD_LOCAL)
            .addJavadoc("Marks scatter worker threads for the re-entrancy guard: a {@code perTenant} body\n"
                + "calling {@link #scatter} would make a bounded pool wait on itself and deadlock, so the\n"
                + "violation throws immediately instead.\n")
            .build();

        var tenantSetType = ParameterizedTypeName.get(SET, tenantKey);
        var tenantsField = FieldSpec.builder(tenantSetType, "tenants", Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("The request tenant set: the tenants this request may touch in this deployment,\n"
                + "decoded once from the factory's parameter by the instrumentation. Every keyed\n"
                + "acquisition is checked against it in {@link #entryFor} before a connection is taken,\n"
                + "and it bounds {@link #fanOutDomain}, so routing and fan-out read one fact.\n")
            .build();
        var defaultTenantField = FieldSpec.builder(optionalKey, "defaultTenant", Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("The request's default tenant, the member of {@link #tenants} that serves global reads\n"
                + "({@link #dslGlobal()}), or empty when the request names none and global reads take the\n"
                + "default source.\n")
            .build();
        var entityDefaultTenantField = FieldSpec.builder(optionalKey, "entityDefaultTenant",
                Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("The request's entity default tenant, the member of {@link #tenants} on which the\n"
                + "entity dispatcher looks up a {@code @defaultTenant} type's representations, or empty when\n"
                + "the request names none and every such representation answers {@code null}.\n")
            .build();
        var constructorBuilder = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(runtime, "runtime")
            .addParameter(commitPolicy, "commitPolicy");
        if (multiTenant) {
            constructorBuilder.addParameter(requestTenants, "requestTenants");
        }
        constructorBuilder.addParameter(connectionSource, SOURCE_SLOT);
        constructorBuilder
            .addStatement("this.runtime = runtime")
            .addStatement("this.commitPolicy = commitPolicy");
        if (multiTenant) {
            constructorBuilder
                .addStatement("$T.requireNonNull(requestTenants, $S)", OBJECTS, "requestTenants")
                .addStatement("this.tenants = requestTenants.tenants()")
                .addStatement("this.defaultTenant = requestTenants.defaultTenant()")
                .addStatement("this.entityDefaultTenant = requestTenants.entityDefaultTenant()");
        }
        constructorBuilder.addStatement("this.$L = $T.requireNonNull($L, $S)", SOURCE_SLOT, OBJECTS, SOURCE_SLOT,
            SOURCE_SLOT);
        var constructor = constructorBuilder
            .addJavadoc((multiTenant
                ? "Builds a per-operation carrier over {@code runtime} for one request. One instance per\n"
                    + "operation. Concurrency is confined to {@link #scatter}'s bounded workers, each owning\n"
                    + "one keyed connection single-threaded through {@link #dslFor}, with the dispatch thread\n"
                    + "blocked on the join for the scatter's whole duration; every other access runs serially\n"
                    + "on the dispatch thread. {@code requestTenants} carries the request tenant set, the\n"
                    + "only tenants this carrier will acquire a connection for, the default tenant that\n"
                    + "serves global reads when the request names one, and the entity default tenant the\n"
                    + "entity dispatcher resolves {@code @defaultTenant} types on.\n"
                : "Builds a per-operation carrier over {@code runtime} for one request. One instance per\n"
                    + "operation; entries are pinned on first demand.\n")
                + "{@code source} is the request's connection source, held for the life of the request:\n"
                + "any fetcher may trigger the first acquisition for a key.\n")
            .build();

        String acquisition = "runtime.acquire(k, " + SOURCE_SLOT + ")";

        var entryForBuilder = MethodSpec.methodBuilder("entryFor")
            .addModifiers(Modifier.PRIVATE)
            .returns(entryType)
            .addParameter(keyType, "key")
            .addException(SQL_EXCEPTION);
        var brokenMessage = MutationFieldScopeEmitter.brokenKeyRefusalMessage();
        entryForBuilder
            .beginControlFlow("if (brokenKeys.contains(key))")
            .addStatement("throw new $T($S + key + $S)", IllegalStateException.class,
                brokenMessage.get(0), brokenMessage.get(1))
            .endControlFlow();
        if (multiTenant) {
            entryForBuilder
                .addComment("Authorization before anything else: only a key in the request tenant set ever")
                .addComment("reaches the entry map, and the refusal runs ahead of the runtime's hosting check,")
                .addComment("so an unauthorized caller cannot probe which tenants this deployment hosts. Every")
                .addComment("keyed acquisition funnels through here (the static and instance dslFor, and the")
                .addComment("scatter worker); nothing calls runtime.acquire directly.")
                .beginControlFlow("if (key.tenant().isPresent() && !tenants.contains(key.tenant().get()))")
                .addStatement("throw new $T($S + key.tenant().get() + $S)", clientException,
                    "Tenant '", "' is not permitted for this request.")
                .endControlFlow()
                .beginControlFlow("if (key.tenant().isPresent() && timedOutTenants.contains(key.tenant().get()))")
                .addComment("The key's scatter worker missed the join deadline; its connection may still be")
                .addComment("executing, so it is never reused within the operation.")
                .addStatement("throw new $T($S + key.tenant().get() + $S)", IllegalStateException.class,
                    "Tenant '", "' timed out earlier in this operation; its connection is never reused.")
                .endControlFlow();
        }
        entryForBuilder
            .addComment("A failed acquisition is remembered for the operation: answer every later one with it,")
            .addComment("after the tenant refusals above so their order is unchanged.")
            .addStatement("$T rejected = firstAcquisitionFailure.get()", acquisitionFailed)
            .beginControlFlow("if (rejected != null)")
            .addStatement("throw rejected")
            .endControlFlow();
        entryForBuilder
            .beginControlFlow("if (closed)")
            .addComment("The operation already released its connections. Refuse before borrowing: nothing")
            .addComment("would ever release a connection pinned now. This saves the borrow; the check after")
            .addComment("the pin below is the guard, since it also sees a pin that started before release.")
            .addStatement("throw new $T($L)", IllegalStateException.class, closedRefusal(multiTenant, false))
            .endControlFlow()
            .addStatement("$T entry", entryType)
            .beginControlFlow("try")
            .addComment("Per-key single acquisition: exactly one pin per key, the map's one minting")
            .addComment("mechanism. The whole entry is minted here: lease the key's connection from the")
            .addComment("request's source, bind a DSLContext to it through the lease's dialect and settings, swap in one")
            .addComment("transaction provider (one per pinned connection, so its scope stack is the only")
            .addComment("record of open transactions on that connection). jOOQ's single-connection provider treats release as a")
            .addComment("no-op, so the runtime keeps sole ownership of close/evict.")
            .addCode("entry = entries.computeIfAbsent(key, k -> {\n")
            .addCode("    $T pinned = $L;\n", pinnedConnection, acquisition)
            .addCode("    $T dsl = $T.using(pinned.connection(), pinned.dialect(), pinned.settings());\n",
                DSL_CONTEXT, DSL)
            .addCode("    $T provider = new $T(pinned.connection(), commitPolicy, this::warnConsumerJoin);\n", provider, provider)
            .addCode("    dsl.configuration().set(provider);\n");
        if (handled) {
            entryForBuilder.addCode("    if (pinned.handle() != null) {\n")
                .addCode("        dsl.configuration().data($S, pinned.handle());\n", SessionIdentityRows.HANDLE_DATA_KEY)
                .addCode("    }\n");
        }
        entryForBuilder
            .addCode("    return new Entry(pinned, dsl, provider);\n")
            .addCode("});\n")
            .nextControlFlow("catch ($T refused)", keyNotPermitted)
            .addComment("The source refused the key: the field that asked answers with an error, as a refused")
            .addComment("tenant does, and the rest of the request runs.")
            .addStatement("throw new $T(refused.getMessage())", clientException)
            .nextControlFlow("catch ($T e)", acquisitionFailed)
            .addStatement("firstAcquisitionFailure.compareAndSet(null, e)")
            .addStatement("throw firstAcquisitionFailure.get()")
            .endControlFlow();
        entryForBuilder
            .beginControlFlow(multiTenant
                ? "if (closed || (key.tenant().isPresent() && timedOutTenants.contains(key.tenant().get())))"
                : "if (closed)")
            .addComment(multiTenant
                ? "The operation moved on (released, or this key's join deadline passed) while the pin"
                : "The operation released while the pin was in flight; never hand out a connection the")
            .addComment(multiTenant
                ? "was in flight; never hand out a connection the release path can no longer own."
                : "release path can no longer own.")
            .addComment("The entry was published before closed was read, and releaseAll sets closed before it")
            .addComment("drains, so either the drain removed and released this entry or the remove below wins")
            .addComment("it. Abort rather than release: another caller may hold the same entry and be running")
            .addComment("a statement on it.")
            .addStatement("$T stale = entries.remove(key)", entryType)
            .beginControlFlow("if (stale != null)")
            .addStatement("stale.pinned.abort()")
            .endControlFlow()
            .addStatement("throw new $T($L)", IllegalStateException.class, closedRefusal(multiTenant, true))
            .endControlFlow();
        var entryFor = entryForBuilder
            .addStatement("return entry")
            .addJavadoc("Resolves (minting on first demand) the carrier entry for {@code key}, leasing its\n"
                + "connection from the request's source. A key the source refuses answers with a client error;\n"
                + "any other failure to lease is remembered and rejects the request.\n"
                + (multiTenant
                    ? "A present key outside the request tenant set is refused with a client error before\n"
                        + "any connection is taken. This is the one enforcer: every keyed acquisition in the\n"
                        + "carrier resolves through this method.\n"
                    : ""))
            .build();

        var dslFor = MethodSpec.methodBuilder("dslFor")
            .addModifiers(Modifier.PUBLIC)
            .returns(DSL_CONTEXT)
            .addParameter(tenantKey, "tenantKey")
            .addException(SQL_EXCEPTION)
            .addCode(CodeBlock.of("$T key = $T.forTenant(tenantKey);\n", connectionKey, connectionKey))
            .addStatement("return enlist(key, entryFor(key))")
            .addJavadoc("Returns the provider-bound {@code DSLContext} for {@code tenantKey}, pinning and\n"
                + "mounting one connection for the key on first use and reusing it thereafter. A drop-in for\n"
                + "{@code getDslContext(env)} at a routed fetcher site.\n"
                + "@param tenantKey the divined tenant value; an unknown key raises before any SQL\n")
            .build();

        var dslDefault = MethodSpec.methodBuilder("dslDefault")
            .addModifiers(Modifier.PUBLIC)
            .returns(DSL_CONTEXT)
            .addException(SQL_EXCEPTION)
            .addCode(CodeBlock.of("$T key = $T.defaultSource();\n", connectionKey, connectionKey))
            .addStatement("return enlist(key, entryFor(key))")
            .addJavadoc("Returns the provider-bound {@code DSLContext} for the default source, pinning and\n"
                + "mounting one connection on first use and reusing it thereafter. The untenanted\n"
                + "sibling of {@link #dslFor}, and always the fixed default source, whatever default\n"
                + "tenant the request names; the single-tenant path resolves every context through\n"
                + "this, as the one-key case.\n")
            .build();

        var dslGlobal = MethodSpec.methodBuilder("dslGlobal")
            .addModifiers(Modifier.PUBLIC)
            .returns(DSL_CONTEXT)
            .addException(SQL_EXCEPTION)
            .addStatement("$T key = new $T(defaultTenant)", connectionKey, connectionKey)
            .addStatement("return enlist(key, entryFor(key))")
            .addJavadoc("Returns the provider-bound {@code DSLContext} for graphitron's own reads of global\n"
                + "tables: the request's default tenant when it names one, else the default source. A\n"
                + "present default tenant resolves through {@link #entryFor} like any routed key, so it\n"
                + "shares the entry {@link #dslFor} uses for that tenant: one connection and one mount.\n"
                + "Writes and service calls keep {@link #dslDefault()}.\n")
            .build();

        var releaseAllBuilder = MethodSpec.methodBuilder("releaseAll")
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class);
        releaseAllBuilder
            .addComment("Close before draining, so an acquisition that finishes pinning after this point")
            .addComment("aborts its own connection instead of leaking a fresh pin into a completed operation.")
            .addStatement("closed = true")
            .addStatement("$T failure = null", RuntimeException.class)
            .beginControlFlow("for ($T key : entries.keySet())", keyType)
            .addComment("remove() hands each entry to exactly one processor, against a late acquisition's")
            .addComment("concurrent self-abort of the same entry.")
            .addStatement("$T entry = entries.remove(key)", entryType)
            .beginControlFlow("if (entry == null)")
            .addStatement("continue")
            .endControlFlow();
        if (multiTenant) {
            releaseAllBuilder
                .beginControlFlow("if (key.tenant().isPresent() && timedOutTenants.contains(key.tenant().get()))")
                .addComment("A TimedOut outcome means the join stopped waiting, not that the worker stopped")
                .addComment("working: the connection may still be mid-statement. A JDBC call cannot be safely")
                .addComment("killed, so route the straggler through the abort seam; never close or return a")
                .addComment("connection whose worker may still be executing on it.")
                .addStatement("entry.pinned.abort()")
                .addStatement("continue")
                .endControlFlow();
        }
        releaseAllBuilder
            .beginControlFlow("try")
            .addStatement("entry.pinned.release()")
            .nextControlFlow("catch ($T e)", RuntimeException.class)
            .addComment("release() already evicted this connection on unmount failure; keep releasing the")
            .addComment("rest so one entry's failed unmount never orphans another's connection.")
            .beginControlFlow("if (failure == null)")
            .addStatement("failure = e")
            .endControlFlow()
            .endControlFlow()
            .endControlFlow();
        var releaseAll = releaseAllBuilder
            .beginControlFlow("if (failure != null)")
            .addStatement("throw failure")
            .endControlFlow()
            .addJavadoc(multiTenant
                ? "Releases every pinned connection on every completion path (success, error,\n"
                    + "cancellation): each {@code release()} unmounts identity and returns or evicts its own\n"
                    + "connection, and one entry's unmount failure does not orphan the others. Idempotent:\n"
                    + "the drain removes each entry exactly once, so a redundant call is a no-op. Rethrows the\n"
                    + "first release failure after attempting them all. Closes the carrier first: any later\n"
                    + "acquisition is refused. A tenant whose scatter worker missed the join deadline is\n"
                    + "routed through {@code PinnedConnection.abort()}: its worker may still be executing, so\n"
                    + "the connection is evicted, never closed under a live statement nor returned to the pool.\n"
                : "Releases every pinned connection on every completion path (success, error,\n"
                    + "cancellation): each {@code release()} unmounts identity and returns or evicts its own\n"
                    + "connection, and one entry's unmount failure does not orphan the others. Idempotent:\n"
                    + "the drain removes each entry exactly once, so a redundant call is a no-op. Rethrows the\n"
                    + "first release failure after attempting them all. Closes the carrier first: any later\n"
                    + "acquisition is refused.\n")
            .build();

        var entryClass = TypeSpec.classBuilder("Entry")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("One key's carrier entry: the pinned connection and its provider-bound\n"
                + "{@code DSLContext}, minted together inside the one {@code computeIfAbsent} and\n"
                + "discarded together at release. The mount's handle rides the {@code DSLContext}'s own\n"
                + "{@code Configuration.data()} slot, so it is created and destroyed with the connection\n"
                + "it describes and a {@code $$session} read is per-key by construction.\n")
            .addField(FieldSpec.builder(pinnedConnection, "pinned", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(DSL_CONTEXT, "dsl", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(provider, "provider", Modifier.PRIVATE, Modifier.FINAL)
                .addJavadoc("The connection's one transaction provider, also bound into {@code dsl}: the carrier\n"
                    + "opens and ends a mutation field's scope on it directly.\n")
                .build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(pinnedConnection, "pinned")
                .addParameter(DSL_CONTEXT, "dsl")
                .addParameter(provider, "provider")
                .addStatement("this.pinned = pinned")
                .addStatement("this.dsl = dsl")
                .addStatement("this.provider = provider")
                .build())
            .build();

        var carrier = TypeSpec.classBuilder(OWNED_CONNECTIONS_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc(multiTenant
                ? "Per-operation carrier of the keyed pinned connections for one request. See\n"
                    + "{@code ConnectionRuntimeClassGenerator} for the full contract. Concurrency is confined\n"
                    + "to {@link #scatter}'s bounded workers; everything else runs serially on the dispatch\n"
                    + "thread.\n"
                : "Per-operation carrier of the pinned connections for one request, with single-tenant as\n"
                    + "the one-key case of the unified per-key design. See\n"
                    + "{@code ConnectionRuntimeClassGenerator} for the full contract. {@link #releaseAll}\n"
                    + "closes the carrier: any acquisition after that point is refused.\n");
        if (multiTenant) {
            carrier.addField(FieldSpec.builder(ParameterizedTypeName.get(DATA_TYPE, tenantKey), "TENANT_COLUMN_TYPE",
                        Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$L.getDataType()", tenantColumn)
                    .addJavadoc("The representative tenant column's data type, its converter included: the coercion\n"
                        + "a client value takes on its way to the column, the same one the generated SQL binds it\n"
                        + "with.\n")
                    .build())
                .addField(scatterWorkerMarkerField);
        }
        carrier.addField(runtimeField)
            .addField(policyField);
        if (multiTenant) {
            carrier.addField(tenantsField)
                .addField(defaultTenantField)
                .addField(entityDefaultTenantField);
        }
        carrier.addField(sourceField);
        carrier.addField(entriesField)
            .addField(MutationFieldScopeEmitter.activeField(self))
            .addField(MutationFieldScopeEmitter.brokenKeys(keyType));
        if (multiTenant) {
            carrier.addField(MutationFieldScopeEmitter.scatterField(self));
        }
        carrier.addField(acquisitionFailureField);
        if (multiTenant) {
            carrier.addField(timedOutField);
        }
        carrier.addField(closedField);
        carrier.addMethod(constructor)
            .addMethod(entryFor)
            .addMethod(dslFor)
            .addMethod(dslDefault)
            .addMethod(releaseAll)
            .addMethod(ofEnvironment(self))
            .addMethod(staticDslDefault(self))
            .addMethod(acquisitionCause(acquisitionFailed))
            .addMethod(isAcquisitionFailure())
            .addMethod(MutationFieldScopeEmitter.beginField(self))
            .addMethod(MutationFieldScopeEmitter.enlist(self, provider, keyType, multiTenant))
            .addMethod(MutationFieldScopeEmitter.await(self))
            .addMethod(MutationFieldScopeEmitter.settleField(self, provider, keyType, multiTenant, failureMessages))
            .addMethod(MutationFieldScopeEmitter.probe(self))
            .addMethod(MutationFieldScopeEmitter.rollBackQuietly(self, provider))
            .addMethod(MutationFieldScopeEmitter.markFieldFailed(self))
            .addMethod(MutationFieldScopeEmitter.markFieldClaimed(self))
            .addMethod(MutationFieldScopeEmitter.markFieldRefused(self))
            .addMethod(MutationFieldScopeEmitter.runningField(self));
        if (failureMessages) {
            String freshAcquisition = acquisition;
            carrier.addType(MutationFieldScopeEmitter.fieldRollbackType())
                .addMethod(MutationFieldScopeEmitter.onFieldRollback(self))
                .addMethod(MutationFieldScopeEmitter.afterRollback(self, keyType, multiTenant, true))
                .addMethod(MutationFieldScopeEmitter.dropped(self))
                .addMethod(MutationFieldScopeEmitter.writeOnFreshConnection(self, provider, pinnedConnection, keyType,
                    freshAcquisition, handled))
                .addMethod(MutationFieldScopeEmitter.endedOverTransaction());
        }
        carrier.addMethod(MethodSpec.methodBuilder("acquisitionFailure")
            .addModifiers(Modifier.PUBLIC)
            .returns(ParameterizedTypeName.get(ClassName.get("java.util", "Optional"), acquisitionFailed))
            .addStatement("return $T.ofNullable(firstAcquisitionFailure.get())", ClassName.get("java.util", "Optional"))
            .addJavadoc("The operation's first failed acquisition, if any. Read by the instrumentation when the\n"
                + "operation completes, to throw it in place of the result.\n")
            .build());
        if (handled) {
            carrier.addMethod(sessionHandleAccessor(handleType));
            for (var member : identity.handle().orElseThrow().members()) {
                carrier.addMethod(sessionMemberAccessor(member));
            }
        }
        carrier.addField(FieldSpec.builder(SLF4J_LOGGER, "LOGGER", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("$T.getLogger($T.class)", SLF4J_LOGGER_FACTORY, self)
                .build())
            .addField(MutationFieldScopeEmitter.reportedJoins())
            .addMethod(MutationFieldScopeEmitter.warnConsumerJoin(self));
        carrier.addType(entryClass)
            .addType(MutationFieldScopeEmitter.fieldScopeType(self, provider, keyType, failureMessages))
            .addType(MutationFieldScopeEmitter.openingType(provider))
            .addType(MutationFieldScopeEmitter.nestedScopeFailedType());
        if (multiTenant) {
            carrier.addField(FieldSpec.builder(String.class, TENANTS_KEY_FIELD,
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$S", TENANTS_KEY_VALUE)
                .addJavadoc("The {@code graphQLContext} key the request tenant set is published under.\n"
                    + "Written by the generated factory's dedicated tenant-set parameter; read once by the\n"
                    + "execution instrumentation, which hands the decoded set to this carrier. One constant so\n"
                    + "the two sites cannot drift, and a graphitron-owned name no contextArgument can collide\n"
                    + "with.\n")
                .build());
            carrier.addMethod(scatterMethod(self, tenantKey))
                .addMethod(scatterWorkerMethod(self, tenantKey))
                .addMethod(fanOutDomain(self, tenantKey))
                .addMethod(narrowedFanOutDomain(tenantKey))
                .addMethod(namedTenants())
                .addMethod(handDown(tenantKey, connectionKey))
                .addMethod(fanOutRows(self, tenantKey))
                .addMethod(fanOutBatchRows(self, tenantKey))
                .addMethod(collapseFanOut(self))
                .addMethod(logFanOutFailure(self, errorRouter, true))
                .addMethod(staticDslFor(self, tenantKey))
                .addMethod(keyedDslFor(connectionKey))
                .addMethod(staticKeyedDslFor(self, connectionKey))
                .addMethod(staticHandedDownDsl(self, connectionKey))
                .addMethod(dslGlobal)
                .addMethod(staticDslGlobal(self))
                .addMethod(namedTenant(tenantKey))
                .addMethod(divinedTenant(tenantKey))
                .addMethod(tenantKeyOrNull(tenantKey, columnValues))
                .addMethod(canonicalTenantKey(tenantKey))
                .addMethod(tenantKeyOf(tenantKey, clientException))
                .addMethod(divinedTenantAgree(tenantKey, clientException))
                .addMethod(permits(self, tenantKey))
                .addMethod(staticEntityDefaultTenant(tenantKey))
                .addMethod(tenantSlot())
                .addMethod(mapTenantLeaves())
                .addMethod(loaderName())
                .addMethod(tenantLoaderName(connectionKey))
                .addMethod(handedDownLoaderName(connectionKey))
                .addMethod(tenantLoaderNameFor())
                .addType(outcomeType(self, tenantKey))
                .addType(fanOutFailureType());
        }
        return carrier.build();
    }

    /**
     * {@code static List<K> fanOutDomain(DataFetchingEnvironment env)}: the request's fan-out domain,
     * the request tenant set. Whether a tenant in it is served is the connection source's to say.
     */
    private static MethodSpec fanOutDomain(ClassName self, TypeName tenantKey) {
        return MethodSpec.methodBuilder("fanOutDomain")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(LIST, tenantKey))
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addStatement("$T carrier = of(env)", self)
            .addStatement("return new $T<>(carrier.tenants)", ARRAY_LIST)
            .addJavadoc("The request's fan-out domain: the request tenant set, in its order. Each tenant in it\n"
                + "is asked of the request's connection source, which may refuse it.\n"
                + "@param env the fanned field's {@code DataFetchingEnvironment}\n")
            .build();
    }

    /**
     * {@code static List<K> fanOutDomain(DataFetchingEnvironment env, Object[] narrowing)}: the
     * fan-out domain narrowed by a fanned field's optional tenant slots, one runtime value per
     * slot. A slot contributes the set of tenants its value names only when every value its read
     * yields names one: a null anywhere (an absent argument, a null element, a filter element
     * leaving the slot out) or an empty read contributes nothing, since an element that leaves the
     * slot out may match rows in any tenant. The domain is the request's ({@link #fanOutDomain})
     * intersected with every contributed set; a named tenant outside the request set is silently
     * not queried, exactly as a hosted tenant outside it is not. Narrowing never changes which
     * rows answer, only how many tenants are asked, because a tenant's database holds only rows
     * naming that tenant.
     */
    private static MethodSpec narrowedFanOutDomain(TypeName tenantKey) {
        var hashSet = ClassName.get("java.util", "HashSet");
        return MethodSpec.methodBuilder("fanOutDomain")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(LIST, tenantKey))
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(Object[].class, "narrowing")
            .addStatement("$T<$T> domain = fanOutDomain(env)", LIST, tenantKey)
            .beginControlFlow("for (Object slot : narrowing)")
            .addStatement("$T<Object> named = new $T<>()", LIST, ARRAY_LIST)
            .beginControlFlow("if (!namedTenants(slot, named) || named.isEmpty())")
            .addComment("Left out, or holding an element that leaves it out: this slot constrains nothing.")
            .addStatement("continue")
            .endControlFlow()
            .addStatement("$T<$T> keys = new $T<>()", SET, tenantKey, hashSet)
            .beginControlFlow("for (Object value : named)")
            .addStatement("keys.add(tenantKeyOf(value))")
            .endControlFlow()
            .addStatement("domain.retainAll(keys)")
            .endControlFlow()
            .addStatement("return domain")
            .addJavadoc("The request's fan-out domain narrowed to the tenants a fanned field's optional\n"
                + "tenant slots name. A slot whose value is absent, holds a null anywhere, or is empty\n"
                + "narrows nothing; the rest intersect, so only tenants every given slot names are\n"
                + "queried. A named tenant the request may not see is never queried.\n"
                + "@param env the fanned field's {@code DataFetchingEnvironment}\n"
                + "@param narrowing each optional slot's runtime value (a value, a collection, or {@code null})\n")
            .build();
    }

    /**
     * The flatten behind the narrowed domain: collects every leaf of {@code candidate} into
     * {@code out}, flattening collections as {@code agreeOnTenant} does, and answers false as soon
     * as a leaf is null.
     */
    private static MethodSpec namedTenants() {
        var collection = ClassName.get("java.util", "Collection");
        return MethodSpec.methodBuilder("namedTenants")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter(Object.class, "candidate")
            .addParameter(ParameterizedTypeName.get(LIST, ClassName.get(Object.class)), "out")
            .beginControlFlow("if (candidate == null)")
            .addStatement("return false")
            .endControlFlow()
            .beginControlFlow("if (candidate instanceof $T<?> values)", collection)
            .beginControlFlow("for (Object value : values)")
            .beginControlFlow("if (!namedTenants(value, out))")
            .addStatement("return false")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return true")
            .endControlFlow()
            .addStatement("out.add(candidate)")
            .addStatement("return true")
            .build();
    }

    /**
     * {@code static boolean permits(DataFetchingEnvironment env, K tenantKey)}: whether the
     * request tenant set admits {@code tenantKey}. The read side of the rule {@code entryFor}
     * enforces, for surfaces that answer a refused tenant as presentation rather than an error
     * (the per-row lookups skip a refused group so its positions stay {@code null}); the rule
     * itself stays single-sourced on the carrier.
     */
    private static MethodSpec permits(ClassName self, TypeName tenantKey) {
        return MethodSpec.methodBuilder("permits")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(tenantKey, "tenantKey")
            .addStatement("return of(env).tenants.contains(tenantKey)")
            .addJavadoc("Whether the request tenant set admits {@code tenantKey}. {@link #entryFor} stays the\n"
                + "enforcer; this lets a per-row lookup skip a refused tenant's group, answering its ids\n"
                + "with {@code null} exactly as it answers an id that does not exist.\n"
                + "@param env the field's {@code DataFetchingEnvironment}\n"
                + "@param tenantKey the divined tenant value\n")
            .build();
    }

    /**
     * {@code static Optional<K> entityDefaultTenant(DataFetchingEnvironment env)}: the request's
     * entity default tenant, as the carrier holds it. Read by the entity dispatcher for a
     * {@code @defaultTenant} type, whose representations group on it; empty when the request names
     * none, and then every such representation answers {@code null}.
     */
    private static MethodSpec staticEntityDefaultTenant(TypeName tenantKey) {
        return MethodSpec.methodBuilder("entityDefaultTenant")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(OPTIONAL, tenantKey))
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addStatement("return of(env).entityDefaultTenant")
            .addJavadoc("The request's entity default tenant, or empty when the request names none. The\n"
                + "entity dispatcher resolves a {@code @defaultTenant} type's representations on it.\n"
                + "@param env the field's {@code DataFetchingEnvironment}\n")
            .build();
    }

    /**
     * {@code record ConnectionKey(Optional<K> tenant)}: the value the carrier keys its connections by
     * and the one value graphql-java's per-subtree {@code localContext} slot carries in generated code,
     * so an inherited acquisition below a routed row takes the connection the row was read on. Its
     * parts are the build's {@link ConnectionKeyShape}: the tenant, empty for the default source. Record
     * equality is the map key's, and the named factories say which connection a site means; the one
     * reader of the slot is {@code tenantIn}, so no site casts the slot's value itself.
     */
    private static TypeSpec connectionKey(ClassName self, TypeName tenantKey) {
        var optionalTenant = ParameterizedTypeName.get(OPTIONAL, tenantKey);
        return TypeSpec.recordBuilder(self)
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("The key a connection is acquired and mounted under, and the value handed down a subtree in\n"
                + "graphql-java's {@code localContext}: the tenant the connection serves, empty for the default\n"
                + "source.\n"
                + "@param tenant the tenant, or empty for the default source\n")
            .recordConstructor(MethodSpec.constructorBuilder()
                .addParameter(optionalTenant, "tenant")
                .build())
            .addMethod(MethodSpec.methodBuilder("defaultSource")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(self)
                .addStatement("return new $T($T.empty())", self, OPTIONAL)
                .addJavadoc("The key of the default source, which serves global tables.\n")
                .build())
            .addMethod(MethodSpec.methodBuilder("forTenant")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(self)
                .addParameter(tenantKey, "tenant")
                .addStatement("return new $T($T.of(tenant))", self, OPTIONAL)
                .addJavadoc("The key of {@code tenant}'s connection.\n"
                    + "@param tenant the tenant, never null\n")
                .build())
            .addMethod(MethodSpec.methodBuilder("tenantIn")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(tenantKey)
                .addParameter(Object.class, "localContext")
                .addStatement("return localContext instanceof $T key ? key.tenant().orElse(null) : null", self)
                .addJavadoc("The tenant a subtree inherited, read off the {@code localContext} value graphql-java\n"
                    + "handed the field, or {@code null} when it carries no key or a key of the default source.\n"
                    + "@param localContext the field's {@code localContext}\n")
                .build())
            .build();
    }

    /**
     * {@code static <T> DataFetcherResult<T> handDown(T row, K tenantKey)}: the per-element tenant
     * stamp. Wraps one row as the field value graphql-java completes for it, carrying the row's
     * tenant as {@code localContext} so the subtree below that one element reads it back. Both
     * per-row surfaces call it: the fan-out union ({@link #fanOutRows}, {@link #fanOutBatchRows})
     * and the node and entity dispatcher's per-tenant groups. Typed on the build's tenant key so
     * it is no general-purpose way into the slot.
     */
    private static MethodSpec handDown(TypeName tenantKey, ClassName connectionKey) {
        var t = com.palantir.javapoet.TypeVariableName.get("T");
        return MethodSpec.methodBuilder(HAND_DOWN_METHOD)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(t)
            .returns(ParameterizedTypeName.get(DATA_FETCHER_RESULT, t))
            .addParameter(t, "row")
            .addParameter(tenantKey, "tenantKey")
            .addStatement("return $T.<$T>newResult().data(row).localContext($T.forTenant(tenantKey)).build()",
                DATA_FETCHER_RESULT, t, connectionKey)
            .addJavadoc("Wraps one row as its own field value, handing the connection key for\n"
                + "{@code tenantKey} down to the\n"
                + "row's subtree: the per-element tenant stamp of the fan-out union and of node and\n"
                + "entity dispatch. graphql-java unwraps a list's elements one by one, so each element\n"
                + "keeps its own tenant.\n"
                + "@param row the resolved row\n"
                + "@param tenantKey the tenant the row was read from\n")
            .build();
    }

    /**
     * {@code static <R> List<Object> fanOutRows(env, perTenant)}: the root-field fan-out union.
     * Scatters the per-tenant statement over the domain, flattens each {@code Success} outcome's
     * rows into per-element {@code DataFetcherResult}s carrying that row's tenant as
     * {@code localContext} (graphql-java unwraps list elements individually, so children below the
     * fanned field read the right tenant with no further machinery), and appends one
     * {@code FanOutFailure} marker per failed or timed-out tenant after the successful rows.
     * {@code collapseFanOut} turns the markers into null elements plus path-bearing errors.
     */
    private static MethodSpec fanOutRows(ClassName self, TypeName tenantKey) {
        var r = com.palantir.javapoet.TypeVariableName.get("R");
        var outcome = self.nestedClass("Outcome");
        var listOfR = ParameterizedTypeName.get(LIST, r);
        return MethodSpec.methodBuilder("fanOutRows")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(r)
            .returns(ParameterizedTypeName.get(LIST, ClassName.get(Object.class)))
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(ParameterizedTypeName.get(FUNCTION, DSL_CONTEXT, listOfR), "perTenant")
            .addParameter(Object[].class, "narrowing")
            .varargs()
            .addStatement("$T<$T<$T>> outcomes = of(env).scatter(fanOutDomain(env, narrowing), perTenant)",
                LIST, outcome, listOfR)
            .addStatement("$T<Object> elements = new $T<>()", LIST, ARRAY_LIST)
            .beginControlFlow("for ($T<$T> outcome : outcomes)", outcome, listOfR)
            .beginControlFlow("if (outcome instanceof Outcome.Success<$T> success)", listOfR)
            .beginControlFlow("for ($T row : success.value())", r)
            .addStatement("elements.add($L(row, success.key()))", HAND_DOWN_METHOD)
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .addComment("Failed and timed-out tenants append after the successful rows (we cannot know how")
            .addComment("many rows they would have returned), one marker each.")
            .beginControlFlow("for ($T<$T> outcome : outcomes)", outcome, listOfR)
            .beginControlFlow("if (!(outcome instanceof Outcome.Success))")
            .addStatement("elements.add(logFanOutFailure(outcome, env))")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return elements")
            .addJavadoc("Runs the fanned field's statement once per domain tenant and unions the outcomes\n"
                + "in domain order: each row wrapped as a per-element {@code DataFetcherResult} whose\n"
                + "{@code localContext} carries the row's tenant, each failed or timed-out tenant as one\n"
                + "{@code FanOutFailure} marker appended after the successful rows. Feed the result to\n"
                + "{@link #collapseFanOut}. {@code narrowing} restricts the domain to the tenants the\n"
                + "field's optional tenant slots name; none given is the request's whole domain.\n")
            .build();
    }

    /**
     * {@code static <R> List<List<Object>> fanOutBatchRows(env, keyCount, perTenant)}: the batched
     * sibling of {@link #fanOutRows} for a fanned field under an untenanted parent. One scatter
     * per parent batch; {@code perTenant} runs the batch statement (per-key grouped) once per
     * tenant, and the per-key groups merge across tenants in domain order, each row stamped with
     * its tenant. A failed or timed-out tenant contributes one shared marker to <em>every</em>
     * parent's list (the failure hides that tenant's rows for every parent in the batch); each
     * parent's fetcher collapses its own list against its own path.
     */
    private static MethodSpec fanOutBatchRows(ClassName self, TypeName tenantKey) {
        var r = com.palantir.javapoet.TypeVariableName.get("R");
        var outcome = self.nestedClass("Outcome");
        var listOfListOfR = ParameterizedTypeName.get(LIST, ParameterizedTypeName.get(LIST, r));
        var listOfObject = ParameterizedTypeName.get(LIST, ClassName.get(Object.class));
        return MethodSpec.methodBuilder("fanOutBatchRows")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(r)
            .returns(ParameterizedTypeName.get(LIST, listOfObject))
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(int.class, "keyCount")
            .addParameter(ParameterizedTypeName.get(FUNCTION, DSL_CONTEXT, listOfListOfR), "perTenant")
            .addParameter(Object[].class, "narrowing")
            .varargs()
            .addStatement("$T<$T<$T>> outcomes = of(env).scatter(fanOutDomain(env, narrowing), perTenant)",
                LIST, outcome, listOfListOfR)
            .addStatement("$T<$T> merged = new $T<>(keyCount)", LIST, listOfObject, ARRAY_LIST)
            .beginControlFlow("for (int i = 0; i < keyCount; i++)")
            .addStatement("merged.add(new $T<>())", ARRAY_LIST)
            .endControlFlow()
            .beginControlFlow("for ($T<$T> outcome : outcomes)", outcome, listOfListOfR)
            .beginControlFlow("if (outcome instanceof Outcome.Success<$T> success)", listOfListOfR)
            .beginControlFlow("for (int i = 0; i < keyCount; i++)")
            .beginControlFlow("for ($T row : success.value().get(i))", r)
            .addStatement("merged.get(i).add($L(row, success.key()))", HAND_DOWN_METHOD)
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .beginControlFlow("for ($T<$T> outcome : outcomes)", outcome, listOfListOfR)
            .beginControlFlow("if (!(outcome instanceof Outcome.Success))")
            .addStatement("$T failure = logFanOutFailure(outcome, env)", self.nestedClass("FanOutFailure"))
            .beginControlFlow("for (int i = 0; i < keyCount; i++)")
            .addStatement("merged.get(i).add(failure)")
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .addStatement("return merged")
            .addJavadoc("The batched form of {@link #fanOutRows}: one scatter per parent batch, one\n"
                + "statement per tenant per batch, per-key groups merged across tenants in domain order\n"
                + "with per-element tenant stamping. A failed tenant contributes one shared marker to\n"
                + "every parent's list; each parent's fetcher collapses against its own path.\n"
                + "@param keyCount the parent batch size; {@code perTenant} must return one group per key\n"
                + "@param narrowing the field's optional tenant slot values, read off the batch's environment\n")
            .build();
    }

    /**
     * {@code static DataFetcherResult<List<Object>> collapseFanOut(env, items)}: turns a
     * marker-bearing element list into the fanned field's result. Every {@code FanOutFailure}
     * marker becomes one {@code null} element plus one error whose {@code path} points at that
     * element's index; SDL element nullability then composes the author's strictness for free
     * ({@code [Thing]} keeps partial data, {@code [Thing!]} lets graphql-java's null-bubbling
     * turn any tenant failure into a null field). The message carries only a correlation-id
     * reference (details are in the server log); a machine-readable classification rides in
     * {@code extensions}.
     */
    private static MethodSpec collapseFanOut(ClassName self) {
        var listOfObject = ParameterizedTypeName.get(LIST, ClassName.get(Object.class));
        return MethodSpec.methodBuilder("collapseFanOut")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(DATA_FETCHER_RESULT, listOfObject))
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(listOfObject, "items")
            .addStatement("$T<Object> elements = new $T<>(items.size())", LIST, ARRAY_LIST)
            .addStatement("$T<$T> errors = new $T<>()", LIST, GRAPHQL_ERROR, ARRAY_LIST)
            .beginControlFlow("for (Object item : items)")
            .beginControlFlow("if (item instanceof FanOutFailure failure)")
            .addCode("errors.add($T.newError(env)\n", GRAPHQL_ERROR_BUILDER)
            .addCode("    .message($S + failure.correlationId() + $S)\n",
                "A tenant's data did not arrive. Reference: ", ".")
            .addCode("    .path(env.getExecutionStepInfo().getPath().segment(elements.size()))\n")
            .addCode("    .extensions($T.<String, Object>of($S, failure.classification()))\n", MAP, "classification")
            .addCode("    .build());\n")
            .addStatement("elements.add(null)")
            .nextControlFlow("else")
            .addStatement("elements.add(item)")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return $T.<$T>newResult().data(elements).errors(errors).build()",
                DATA_FETCHER_RESULT, listOfObject)
            .addJavadoc("Collapses a {@link #fanOutRows} / {@link #fanOutBatchRows} element list into the\n"
                + "fanned field's {@code DataFetcherResult}: markers become null elements plus\n"
                + "path-bearing redacted errors (correlation-id reference in the message, classification\n"
                + "in {@code extensions}); everything else passes through as the per-element\n"
                + "tenant-stamped results.\n")
            .build();
    }

    /**
     * Logs one failure under a reference from {@code ErrorRouter.newReference} and returns its
     * in-band marker. A configured {@code FetcherErrorLog} takes the failure through its
     * {@code tenantFailed} (a timeout as a synthesized {@code TimeoutException}, so the consumer's
     * damping sees outages too); without one, the default line for the arm is written. A failed
     * tenant's default line is {@code ErrorRouter.describe}'s summary at ERROR with the cause at
     * DEBUG when the cause is a database failure, the cause at ERROR otherwise, as the router's
     * own default line is. Each tenant's failure is its own exception, so each keeps its own
     * reference and its own record.
     */
    private static MethodSpec logFanOutFailure(ClassName self, ClassName errorRouter, boolean mounts) {
        var outcome = self.nestedClass("Outcome");
        var failureType = self.nestedClass("FanOutFailure");
        var builder = MethodSpec.methodBuilder("logFanOutFailure")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(failureType)
            .addParameter(ParameterizedTypeName.get(outcome, WildcardTypeName.subtypeOf(Object.class)), "outcome")
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .beginControlFlow("if (outcome instanceof Outcome.Failed<?> failed)");
        if (mounts) {
            builder
                .beginControlFlow("if (isAcquisitionFailure(failed.cause()))")
                .addComment("A rejected mount fails the whole request when the operation completes; this marker")
                .addComment("never reaches a client, so nothing is logged and no reference is minted.")
                .addStatement("return new FanOutFailure($S, FanOutFailure.FAILED)", "")
                .endControlFlow();
        }
        return builder
            .addStatement("String reference = $T.newReference(failed.cause(), env)", errorRouter)
            .beginControlFlow("if (!$T.toConfiguredLog(env, reference,"
                + " log -> log.tenantFailed(failed.key(), failed.cause(), reference, env)))", errorRouter)
            .addStatement("$T summary = $T.$L(failed.cause(), env)",
                ParameterizedTypeName.get(OPTIONAL, ClassName.get(String.class)), errorRouter,
                ErrorRouterClassGenerator.DESCRIBE)
            .beginControlFlow("if (summary.isPresent())")
            .addStatement("LOGGER.error($S, failed.key(), reference, summary.get())",
                "Tenant fan-out failed for tenant {}; correlation id = {}; {}")
            .addStatement("LOGGER.debug($S, failed.key(), reference, failed.cause())",
                "Tenant fan-out failed for tenant {}; correlation id = {}")
            .nextControlFlow("else")
            .addStatement("LOGGER.error($S, failed.key(), reference, failed.cause())",
                "Tenant fan-out failed for tenant {}; correlation id = {}")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return new FanOutFailure(reference, FanOutFailure.FAILED)")
            .endControlFlow()
            .beginControlFlow("if (outcome instanceof Outcome.TimedOut)")
            .addStatement("$T timeout = new $T($S)", TIMEOUT_EXCEPTION, TIMEOUT_EXCEPTION,
                "tenant fan-out passed its deadline")
            .addStatement("String reference = $T.newReference(timeout, env)", errorRouter)
            .beginControlFlow("if (!$T.toConfiguredLog(env, reference,"
                + " log -> log.tenantFailed(outcome.key(), timeout, reference, env)))", errorRouter)
            .addStatement("LOGGER.error($S, outcome.key(), reference)",
                "Tenant fan-out timed out for tenant {}; correlation id = {}")
            .endControlFlow()
            .addStatement("return new FanOutFailure(reference, FanOutFailure.TIMED_OUT)")
            .endControlFlow()
            .addComment("Callers guard out Success, and Java 17 offers no sealed-switch exhaustiveness here:")
            .addComment("fail loud so a future Outcome arm cannot silently misclassify as a timeout.")
            .addStatement("throw new $T($S + outcome)", IllegalStateException.class,
                "Unclassifiable fan-out outcome: ")
            .addJavadoc("Redaction seam for per-tenant failures: the cause and tenant go to the server log\n"
                + "under a fresh correlation id; only the id and a machine-readable classification\n"
                + "travel to the client, on the marker.\n")
            .build();
    }

    /**
     * {@code static Optional<Throwable> acquisitionCause(Throwable thrown)}: what the connection source
     * threw, when a failed acquisition is anywhere on {@code thrown}'s cause chain. The facade's
     * {@code acquisitionFailure} and the fixed {@code ErrorRouter} ask through it.
     */
    private static MethodSpec acquisitionCause(ClassName acquisitionFailed) {
        var optionalThrowable = ParameterizedTypeName.get(OPTIONAL, ClassName.get(Throwable.class));
        return MethodSpec.methodBuilder("acquisitionCause")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(optionalThrowable)
            .addParameter(Throwable.class, "thrown")
            .beginControlFlow("for ($T t = thrown; t != null; t = t.getCause())", Throwable.class)
            .beginControlFlow("if (t instanceof $T)", acquisitionFailed)
            .addStatement("return $T.ofNullable(t.getCause())", OPTIONAL)
            .endControlFlow()
            .endControlFlow()
            .addStatement("return $T.empty()", OPTIONAL)
            .addJavadoc("What the request's connection source threw, when a failed acquisition is on\n"
                + "{@code thrown}'s cause chain; empty otherwise. The facade's {@code acquisitionFailure}\n"
                + "returns this, so an application can answer a rejected request from the source's own\n"
                + "exception.\n"
                + "@param thrown what was caught\n"
                + "@return the source's own exception, if a failed acquisition is on the chain\n")
            .build();
    }

    /**
     * {@code static boolean isAcquisitionFailure(Throwable thrown)}: whether a failed acquisition is
     * anywhere on {@code thrown}'s cause chain, which is whether {@link #acquisitionCause} is present.
     */
    private static MethodSpec isAcquisitionFailure() {
        return MethodSpec.methodBuilder("isAcquisitionFailure")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter(Throwable.class, "thrown")
            .addStatement("return acquisitionCause(thrown).isPresent()")
            .addJavadoc("Whether {@code thrown}'s cause chain carries a failed acquisition. The error router\n"
                + "declines such a throw: the operation fails as a whole when it completes, so no field logs\n"
                + "it, mints a reference for it, or places it in a payload.\n"
                + "@param thrown what a fetcher caught\n")
            .build();
    }

    /**
     * The consumer-implemented {@code FetcherErrorLog}: one abstract method, so it stays a
     * functional interface and every later hook point ({@code matched}, {@code tenantFailed}) is a
     * default method that breaks no implementor. {@code tenantFailed} exists only when
     * {@code tenantKey} is non-null (a {@code <tenantColumn>} build), typed with that key, so a
     * single-tenant build exposes no method that can never fire.
     */
    private static TypeSpec fetcherErrorLog(ClassName self, TypeName tenantKey) {
        var unmatched = MethodSpec.methodBuilder("unmatched")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addParameter(Throwable.class, "thrown")
            .addParameter(String.class, "reference")
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addJavadoc("Called once for each failure no {@code @error} mapping claims in a request, however\n"
                + "many fields it failed, in place of Graphitron's own log line: a batched query that\n"
                + "fails under 400 parents is 400 field errors quoting one reference, and one call here.\n"
                + "Graphitron logs nothing else about the exception, so the reference is the only thing\n"
                + "that joins a client's report to this log line."
                + (tenantKey == null ? "\n" : " The one exception is {@link #tenantFailed},\n"
                    + "whose default calls this once per failed tenant.\n")
                + "@param thrown the exception as the first field it failed caught it, message and cause\n"
                + "chain intact; a batch's failure arrives inside that field's own\n"
                + "{@link java.util.concurrent.CompletionException}\n"
                + "@param reference the text the client received after {@code \"Reference:\"}, in every\n"
                + "field error the failure produced\n"
                + "@param env the environment of the first field that failed with it (its path, field\n"
                + "definition and arguments)\n")
            .build();
        var matched = MethodSpec.methodBuilder("matched")
            .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
            .addParameter(Throwable.class, "thrown")
            .addParameter(Throwable.class, "claimed")
            .addParameter(String.class, "reference")
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addJavadoc("Called once for each exception an {@code @error} handler catches in a request, however\n"
                + "many fields it failed. Graphitron logs nothing about a caught exception, which is right\n"
                + "for a business error the client is meant to handle; override this to log what a broad\n"
                + "handler last in a union (a catch-all) catches, so an outage does not reach only the\n"
                + "client. Does nothing by default. {@code VALIDATION} errors and the column-length check\n"
                + "are not exceptions and never reach it.\n"
                + "@param thrown the exception the fetcher threw, message and cause chain intact\n"
                + "@param claimed the exception the handler caught: {@code thrown} or a link of its cause\n"
                + "chain, and the source of the payload's error entry\n"
                + "@param reference the reference the client can be given for this error, the one\n"
                + "{@code FetcherErrorResponse.matchedMessage} receives\n"
                + "@param env the environment of the first field that failed with it\n")
            .build();
        var builder = TypeSpec.interfaceBuilder(self)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(FunctionalInterface.class)
            .addJavadoc("Decides how a fetcher exception is logged. By default Graphitron logs an exception no\n"
                + "{@code @error} mapping claims once per request at ERROR through the {@code ErrorRouter}\n"
                + "logger, and logs nothing about one a handler caught. A database failure's default line\n"
                + "is a one-line summary ({@code ErrorRouter.describe}: the field's path, the exception and\n"
                + "driver classes, the SQL state, the vendor code and the first line of the driver's\n"
                + "message), with the full exception, SQL statement included, at DEBUG under the same\n"
                + "reference; any other exception's default line carries its message and cause chain. An\n"
                + "exception message can carry row data, the first line of a driver's message included.\n"
                + "Give the runtime one of these\n"
                + "({@code GraphitronRuntime.withFetcherErrorLog}) to decide yourself what reaches your logs:\n"
                + "levels, sanitising and rate limiting are yours to build here. {@link #unmatched} is the\n"
                + "one abstract method, so a lambda is a complete log; it and {@link #matched} are each\n"
                + "called once per exception per request, however many fields the exception failed. The\n"
                + "client's response does not change either way.\n"
                + "\n"
                + "<p>The reference each method receives is minted by\n"
                + "{@code FetcherErrorResponse.reference}, so a runtime with a log and no response gets the\n"
                + "default, a random UUID.\n"
                + "\n"
                + "<p>On the escape-hatch engine, which has no runtime, put the log into each request's\n"
                + "{@code GraphQLContext} under this class. A request without that entry gets the default\n"
                + "line. If the log throws, the client still gets its response and Graphitron logs one\n"
                + "ERROR line naming the log's class, the class of what it threw and the reference; only\n"
                + "JVM-fatal errors propagate.\n")
            .addMethod(unmatched)
            .addMethod(matched);
        if (tenantKey != null) {
            builder.addMethod(MethodSpec.methodBuilder("tenantFailed")
                .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
                .addParameter(tenantKey, "tenantKey")
                .addParameter(Throwable.class, "cause")
                .addParameter(String.class, "reference")
                .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
                .addStatement("unmatched(cause, reference, env)")
                .addJavadoc("Called once per tenant that failed or timed out in a field fanned out across\n"
                    + "tenants, in place of Graphitron's own log line. Defaults to {@link #unmatched};\n"
                    + "override it to log the tenant too.\n"
                    + "@param tenantKey the tenant whose part of the field did not arrive\n"
                    + "@param cause what the tenant's worker threw, or a\n"
                    + "{@link java.util.concurrent.TimeoutException} when it passed the fan-out deadline\n"
                    + "@param reference the text the client received after {@code \"Reference:\"}\n"
                    + "@param env the fanned field's environment\n")
                .build());
        }
        return builder.build();
    }

    /**
     * The consumer-implemented {@code FetcherErrorResponse}: default methods only, no abstract
     * one, so it is deliberately not a functional interface and a consumer overrides only what it
     * needs. The defaults are the only spelling of Graphitron's client-facing text for a fetcher
     * exception: {@code ErrorRouter} calls a default instance when none is configured rather than
     * restating them.
     */
    private static TypeSpec fetcherErrorResponse(ClassName self, ClassName clientException) {
        var reference = MethodSpec.methodBuilder("reference")
            .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
            .returns(String.class)
            .addParameter(Throwable.class, "thrown")
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addStatement("return $T.randomUUID().toString()", UUID_CLASS)
            .addJavadoc("Mints the text that joins a client's report to your log line, once per exception and\n"
                + "request: an exception that fails many fields, as a failed batch does, is asked for once\n"
                + "and every one of its field errors quotes the answer. (Two fields that fail with it at\n"
                + "the same moment can each ask; one answer stands and the other is discarded.)\n"
                + "The same reference reaches the {@code FetcherErrorLog}, {@code matchedMessage}\n"
                + "and {@code unmatchedMessage}. The default is a random UUID; a trace id lets an operator\n"
                + "go straight from a support ticket to the trace. A fetcher may run on a thread that does not carry the\n"
                + "request's tracing context, so read the trace id from the request's {@code GraphQLContext}.\n"
                + "@param thrown the exception the reference is for: a caught error's source, or the\n"
                + "exception no handler caught, as the first field it failed caught it\n"
                + "@param env the environment of the first field that failed with it\n"
                + "@return the reference; {@code null} falls back to the default\n")
            .build();
        var matchedMessage = MethodSpec.methodBuilder("matchedMessage")
            .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
            .returns(String.class)
            .addParameter(Throwable.class, "source")
            .addParameter(String.class, "defaultMessage")
            .addParameter(String.class, "reference")
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addStatement("return defaultMessage")
            .addJavadoc("The {@code message:} of an error a handler caught, called when the field is read. Return\n"
                + "{@code defaultMessage} for the errors you leave alone.\n"
                + "@param source the exception the handler caught, the error entry's source\n"
                + "@param defaultMessage what Graphitron returns without this method: the handler's\n"
                + "{@code description:}, else the exception's own message\n"
                + "@param reference the reference minted for {@code source}, the one the log received\n"
                + "@param env the {@code message} field's environment; {@code env.getParentType()} is the\n"
                + "{@code @error} type\n"
                + "@return the client's message; {@code null} falls back to {@code defaultMessage}\n")
            .build();
        var clientErrorMessage = MethodSpec.methodBuilder("clientErrorMessage")
            .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
            .returns(String.class)
            .addParameter(clientException, "error")
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addStatement("return error.getMessage()")
            .addJavadoc("The top-level error's message for a client error no handler caught, for example a\n"
                + "tenant outside the request's tenant set. Called once per such error, with the client\n"
                + "exception found on the cause chain. All of Graphitron's client errors are one class,\n"
                + "so tell them apart by message; that text is Graphitron's and may be reworded in a\n"
                + "release, so match it loosely and return\n"
                + "{@code FetcherErrorResponse.super.clientErrorMessage(error, env)} when nothing matches.\n"
                + "There is no reference: Graphitron neither logs these errors nor gives them one.\n"
                + "@param error the client exception no handler caught\n"
                + "@param env the failing field's environment\n"
                + "@return the client's message; {@code null} falls back to the exception's own message\n")
            .build();
        var unmatchedMessage = MethodSpec.methodBuilder("unmatchedMessage")
            .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
            .returns(String.class)
            .addParameter(Throwable.class, "thrown")
            .addParameter(String.class, "reference")
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addStatement("return $S + reference + $S", "An error occurred. Reference: ", ".")
            .addJavadoc("The top-level error's message when no handler caught the exception. It is the\n"
                + "client-facing text of a fault nobody planned for, so do not put the exception's message\n"
                + "in it unless you know what it contains.\n"
                + "@param thrown the exception the fetcher threw\n"
                + "@param reference the reference minted for {@code thrown}, the one the log received\n"
                + "@param env the failing field's environment\n"
                + "@return the client's message; {@code null} falls back to the default\n")
            .build();
        return TypeSpec.interfaceBuilder(self)
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("Decides what the client reads for a fetcher exception: its reference, the\n"
                + "{@code message:} of an error a handler caught, the top-level message of a client error\n"
                + "none caught, and of anything else none caught. Every method has a default reproducing\n"
                + "what Graphitron does without one, so override only what you need. Give the runtime one\n"
                + "of these\n"
                + "({@code GraphitronRuntime.withFetcherErrorResponse}); on the escape-hatch engine, which\n"
                + "has no runtime, put it into each request's {@code GraphQLContext} under this class.\n"
                + "\n"
                + "<p>If a method throws or returns {@code null}, the client gets the default text and\n"
                + "Graphitron logs one ERROR line naming the response's class, the class of what it threw\n"
                + "and the reference (the field's path, for {@code clientErrorMessage}); only JVM-fatal errors\n"
                + "propagate.\n")
            .addMethod(reference)
            .addMethod(matchedMessage)
            .addMethod(clientErrorMessage)
            .addMethod(unmatchedMessage)
            .build();
    }

    /**
     * The consumer-implemented {@code BatchSizeLimit}: one abstract method and nothing else, so a
     * lambda is a complete limit. It carries only the consumer's contract; graphitron's call into
     * it, with its containment, is {@code ErrorRouter.loaderOptions}.
     */
    private static TypeSpec batchSizeLimit(ClassName self) {
        var maxBatchSize = MethodSpec.methodBuilder("maxBatchSize")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .returns(int.class)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addJavadoc("The most keys one batched query for this field may carry. Asked once per batched\n"
                + "field per request, when the first field that needs the batch is fetched, so the answer\n"
                + "can depend on the field ({@code env.getFieldDefinition()},\n"
                + "{@code env.getExecutionStepInfo()}) and on the request.\n"
                + "@param env the environment of the first field that needed the batch\n"
                + "@return the limit; a value below 1 means no limit\n")
            .build();
        return TypeSpec.interfaceBuilder(self)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(FunctionalInterface.class)
            .addJavadoc("Bounds how many keys one batched query carries. A {@code @splitQuery} or batched\n"
                + "{@code @service} child runs once for every parent in the request, so by default one\n"
                + "query carries every parent's key: 2,000 parents are one statement with 2,000 keys. Give\n"
                + "the runtime one of these ({@code GraphitronRuntime.withBatchSizeLimit}) and a batch over\n"
                + "the limit runs as several queries of at most that many keys, one after another; each\n"
                + "parent still gets its own rows. For a {@code @service} child it is the service method\n"
                + "that is called once per chunk of keys. A query that fails takes down only the fields\n"
                + "whose keys were in it, and the chunks after it still run.\n"
                + "\n"
                + "<p>On the escape-hatch engine, which has no runtime, put the limit into each request's\n"
                + "{@code GraphQLContext} under this class. If the limit throws, the batch is unbounded and\n"
                + "Graphitron logs one ERROR line naming the limit's class, the class of what it threw and\n"
                + "the field's path; only JVM-fatal errors propagate.\n")
            .addMethod(maxBatchSize)
            .build();
    }

    /** The in-band per-tenant failure marker {@code collapseFanOut} turns into a null element + error. */
    private static TypeSpec fanOutFailureType() {
        return TypeSpec.classBuilder("FanOutFailure")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("One failed or timed-out tenant in a fan-out union, travelling in-band through the\n"
                + "element lists until {@link #collapseFanOut} turns it into a null element plus a\n"
                + "path-bearing error. Carries only the redacted facts (correlation id, classification);\n"
                + "the cause and the tenant key stay in the server log.\n")
            .addField(FieldSpec.builder(String.class, "FAILED",
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$S", FAN_OUT_FAILED_CLASSIFICATION)
                .addJavadoc("The {@code extensions.classification} value for a tenant whose worker threw.\n")
                .build())
            .addField(FieldSpec.builder(String.class, "TIMED_OUT",
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer("$S", FAN_OUT_TIMED_OUT_CLASSIFICATION)
                .addJavadoc("The {@code extensions.classification} value for a tenant past the scatter deadline.\n")
                .build())
            .addField(FieldSpec.builder(String.class, "correlationId", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(String.class, "classification", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(String.class, "correlationId")
                .addParameter(String.class, "classification")
                .addStatement("this.correlationId = correlationId")
                .addStatement("this.classification = classification")
                .build())
            .addMethod(MethodSpec.methodBuilder("correlationId")
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return correlationId")
                .addJavadoc("The reference logged with the failure's cause in the server log.\n")
                .build())
            .addMethod(MethodSpec.methodBuilder("classification")
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return classification")
                .addJavadoc("Machine-readable failure kind for the error's {@code extensions}.\n")
                .build())
            .build();
    }

    /**
     * {@code <R> List<Outcome<R>> scatter(Collection<K> keys, Function<DSLContext, R> perTenant)}:
     * the one place concurrency lives in the runtime. Every distinct key gets one worker on the
     * runtime's bounded fan-out executor; each worker resolves its {@code DSLContext} through
     * {@code dslFor(key)} so the pin-and-mount recipe stays single-sourced and per-tenant RLS
     * composes unchanged. The calling dispatch thread blocks in the join until every worker
     * completes or the deadline passes, then returns outcomes in key iteration order. Policy-neutral
     * about partial failure: every tenant ends as exactly one {@code Outcome}, nothing is dropped or
     * cancelled at this layer, and the caller decides what a {@code Failed} or {@code TimedOut}
     * tenant means.
     */
    private static MethodSpec scatterMethod(ClassName self, TypeName tenantKey) {
        var r = com.palantir.javapoet.TypeVariableName.get("R");
        var outcome = self.nestedClass("Outcome");
        var outcomeOfR = ParameterizedTypeName.get(outcome, r);
        return MethodSpec.methodBuilder("scatter")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(r)
            .returns(ParameterizedTypeName.get(LIST, outcomeOfR))
            .addParameter(ParameterizedTypeName.get(COLLECTION, tenantKey), "keys")
            .addParameter(ParameterizedTypeName.get(FUNCTION, DSL_CONTEXT, r), "perTenant")
            .beginControlFlow("if ($T.TRUE.equals(SCATTER_WORKER.get()))", Boolean.class)
            .addStatement("throw new $T($S)", IllegalStateException.class,
                "scatter is not re-entrant: a perTenant body must never call scatter (a bounded pool"
                    + " waiting on itself deadlocks).")
            .endControlFlow()
            .addComment("Distinct keys, one worker each, in the caller's iteration order: a duplicate key")
            .addComment("would put a second concurrent worker on one pinned connection.")
            .addStatement("$T<$T> order = new $T<>(new $T<>(keys))", LIST, tenantKey, ARRAY_LIST, LINKED_HASH_SET)
            .addStatement("$T<$T<$T>> futures = new $T<>(order.size())",
                LIST, COMPLETABLE_FUTURE, outcomeOfR, ARRAY_LIST)
            .addComment("Workers enlist on the mutation field that dispatched them, captured here on the")
            .addComment("dispatch thread, never on whatever field is active when a straggler resumes.")
            .addStatement("$T dispatching = activeField", self.nestedClass(MutationFieldScopeEmitter.FIELD_SCOPE_CLASS_NAME))
            .addStatement("int submitted = 0")
            .beginControlFlow("try")
            .beginControlFlow("while (submitted < order.size())")
            .addStatement("$T key = order.get(submitted)", tenantKey)
            .addStatement("futures.add($T.supplyAsync(() -> scatterWorker(key, perTenant, dispatching), runtime.fanOutExecutor()))",
                COMPLETABLE_FUTURE)
            .addStatement("submitted++")
            .endControlFlow()
            .nextControlFlow("catch ($T e)", ClassName.get("java.util.concurrent", "RejectedExecutionException"))
            .addComment("A consumer-supplied executor rejected mid-submit (the runtime's own bounded pool")
            .addComment("never rejects); already-submitted workers may be running. Quarantine their keys so")
            .addComment("dslFor never reuses them and releaseAll aborts rather than closes, then propagate:")
            .addComment("the fanned field fails as one unit.")
            .beginControlFlow("for (int i = 0; i < submitted; i++)")
            .addStatement("timedOutTenants.add(order.get(i))")
            .endControlFlow()
            .addStatement("throw e")
            .endControlFlow()
            .addStatement("long deadline = $T.nanoTime() + runtime.fanOutTimeout().toNanos()", System.class)
            .addStatement("$T<$T> outcomes = new $T<>(order.size())", LIST, outcomeOfR, ARRAY_LIST)
            .beginControlFlow("for (int i = 0; i < order.size(); i++)")
            .addStatement("$T key = order.get(i)", tenantKey)
            .beginControlFlow("try")
            .addStatement("outcomes.add(futures.get(i).get($T.max(0L, deadline - $T.nanoTime()), $T.NANOSECONDS))",
                Math.class, System.class, TIME_UNIT)
            .nextControlFlow("catch ($T e)", TIMEOUT_EXCEPTION)
            .addComment("The join stops waiting; the worker is not interrupted (a JDBC call cannot be safely")
            .addComment("killed). The key is quarantined: dslFor never reuses it and releaseAll aborts it.")
            .addStatement("timedOutTenants.add(key)")
            .addStatement("outcomes.add(new Outcome.TimedOut<>(key))")
            .nextControlFlow("catch ($T e)", EXECUTION_EXCEPTION)
            .addComment("Defensive: the worker catches Throwable itself; only an executor-level failure lands here.")
            .addStatement("outcomes.add(new Outcome.Failed<>(key, e.getCause()))")
            .nextControlFlow("catch ($T e)", InterruptedException.class)
            .addComment("The join stopped waiting (and the re-set interrupt makes every following get throw")
            .addComment("immediately), but no worker is stopped: quarantine the key exactly like a timeout, so")
            .addComment("its possibly-still-executing connection is never reused and releaseAll aborts rather")
            .addComment("than closes. Failed-vs-TimedOut on the outcome is caller policy; the quarantine is")
            .addComment("the load-bearing part.")
            .addStatement("$T.currentThread().interrupt()", Thread.class)
            .addStatement("timedOutTenants.add(key)")
            .addStatement("outcomes.add(new Outcome.Failed<>(key, e))")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return outcomes")
            .addJavadoc("Runs {@code perTenant} once per distinct key on the runtime's bounded fan-out\n"
                + "executor, each worker on its own tenant's pinned connection (resolved through\n"
                + "{@link #dslFor}, so per-tenant session identity and RLS compose unchanged), and blocks\n"
                + "until every worker completes or the deadline passes. Outcomes come back in the iteration\n"
                + "order of {@code keys} (duplicates collapse to the first occurrence), one per key:\n"
                + "{@code Success} (an empty result is a Success carrying an empty value, distinct from\n"
                + "failure), {@code Failed} (the worker threw; the cause is carried, never swallowed), or\n"
                + "{@code TimedOut} (the deadline passed first; the worker is not interrupted, its\n"
                + "connection is quarantined and aborted at release). Policy-neutral about partial failure:\n"
                + "nothing is dropped or cancelled here, and the caller decides what non-success means.\n"
                + "Workers never touch the default connection, and the dispatch thread is blocked inside\n"
                + "the join for the scatter's whole duration. Not re-entrant: a {@code perTenant} body\n"
                + "calling scatter throws immediately rather than deadlocking the bounded pool.\n"
                + "@param keys the fan-out domain, iterated in order; duplicates collapse\n"
                + "@param perTenant the per-tenant unit of work, handed only the keyed {@code DSLContext}\n")
            .build();
    }

    /** The scatter worker body: marker for the re-entrancy guard, Success/Failed fold, never throws. */
    private static MethodSpec scatterWorkerMethod(ClassName self, TypeName tenantKey) {
        var r = com.palantir.javapoet.TypeVariableName.get("R");
        var outcome = self.nestedClass("Outcome");
        return MethodSpec.methodBuilder("scatterWorker")
            .addModifiers(Modifier.PRIVATE)
            .addTypeVariable(r)
            .returns(ParameterizedTypeName.get(outcome, r))
            .addParameter(tenantKey, "key")
            .addParameter(ParameterizedTypeName.get(FUNCTION, DSL_CONTEXT, r), "perTenant")
            .addParameter(self.nestedClass(MutationFieldScopeEmitter.FIELD_SCOPE_CLASS_NAME), "dispatching")
            .addStatement("SCATTER_WORKER.set($T.TRUE)", Boolean.class)
            .addStatement("SCATTER_FIELD.set(dispatching)")
            .beginControlFlow("try")
            .addStatement("return new Outcome.Success<>(key, perTenant.apply(dslFor(key)))")
            .nextControlFlow("catch ($T cause)", Throwable.class)
            .addStatement("return new Outcome.Failed<>(key, cause)")
            .nextControlFlow("finally")
            .addStatement("SCATTER_WORKER.remove()")
            .addStatement("SCATTER_FIELD.remove()")
            .endControlFlow()
            .addJavadoc("One tenant's unit of work on a fan-out executor thread: pin-or-reuse the key's\n"
                + "connection through {@link #dslFor}, apply {@code perTenant}, fold the result or any\n"
                + "throwable into exactly one {@code Outcome}. Never throws; the cause travels on the\n"
                + "{@code Failed} arm.\n")
            .build();
    }

    /**
     * The per-tenant scatter outcome taxonomy: a sealed interface with one arm per way a tenant's
     * unit of work can end. Emitted as final classes with record-style accessors (the generated
     * output targets Java 17 and the emitter writes explicit classes); all three arms live in this
     * compilation unit, so the sealed interface needs no {@code permits} clause.
     */
    private static TypeSpec outcomeType(ClassName self, TypeName tenantKey) {
        var r = com.palantir.javapoet.TypeVariableName.get("R");
        var outcome = self.nestedClass("Outcome");
        var outcomeOfR = ParameterizedTypeName.get(outcome, r);

        var keyAccessor = MethodSpec.methodBuilder("key")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .returns(tenantKey)
            .addJavadoc("The tenant key this outcome belongs to.\n")
            .build();

        var success = TypeSpec.classBuilder("Success")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addTypeVariable(r)
            .addSuperinterface(outcomeOfR)
            .addJavadoc("The worker completed; {@code value} is {@code perTenant}'s result. An empty result\n"
                + "is a Success carrying an empty value, distinct from {@code Failed}: conflating the two\n"
                + "is exactly the incomplete-presented-as-complete confusion the error posture prevents.\n")
            .addField(FieldSpec.builder(tenantKey, "key", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(r, "value", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(tenantKey, "key")
                .addParameter(r, "value")
                .addStatement("this.key = key")
                .addStatement("this.value = value")
                .build())
            .addMethod(MethodSpec.methodBuilder("key")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(tenantKey)
                .addStatement("return key")
                .build())
            .addMethod(MethodSpec.methodBuilder("value")
                .addModifiers(Modifier.PUBLIC)
                .returns(r)
                .addStatement("return value")
                .addJavadoc("The worker's result; may be an empty collection, never a signal of failure.\n")
                .build())
            .build();

        var failed = TypeSpec.classBuilder("Failed")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addTypeVariable(r)
            .addSuperinterface(outcomeOfR)
            .addJavadoc("The worker threw; {@code cause} carries the throwable, never swallowed.\n")
            .addField(FieldSpec.builder(tenantKey, "key", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(Throwable.class, "cause", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(tenantKey, "key")
                .addParameter(Throwable.class, "cause")
                .addStatement("this.key = key")
                .addStatement("this.cause = cause")
                .build())
            .addMethod(MethodSpec.methodBuilder("key")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(tenantKey)
                .addStatement("return key")
                .build())
            .addMethod(MethodSpec.methodBuilder("cause")
                .addModifiers(Modifier.PUBLIC)
                .returns(Throwable.class)
                .addStatement("return cause")
                .addJavadoc("What the worker threw.\n")
                .build())
            .build();

        var timedOut = TypeSpec.classBuilder("TimedOut")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addTypeVariable(r)
            .addSuperinterface(outcomeOfR)
            .addJavadoc("The scatter deadline passed before the worker completed. The join stopped waiting;\n"
                + "the worker was not stopped, and its connection is quarantined for the rest of the\n"
                + "operation and aborted at release.\n")
            .addField(FieldSpec.builder(tenantKey, "key", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(tenantKey, "key")
                .addStatement("this.key = key")
                .build())
            .addMethod(MethodSpec.methodBuilder("key")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(tenantKey)
                .addStatement("return key")
                .build())
            .build();

        return TypeSpec.interfaceBuilder("Outcome")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.SEALED)
            .addTypeVariable(r)
            .addJavadoc("One tenant's {@link #scatter} outcome: exactly one arm per key. The substrate\n"
                + "reports; the caller decides what non-success means (partial data, a request error,\n"
                + "anything between); no outcome is ever dropped silently at this layer.\n")
            .addMethod(keyAccessor)
            .addType(success)
            .addType(failed)
            .addType(timedOut)
            .build();
    }

    /**
     * {@code static DSLContext dslFor(DataFetchingEnvironment env, T tenantKey)}: the one-call
     * routed acquisition emitted fetcher sites use. Resolves the carrier off the GraphQL
     * context and pins the key's connection, wrapping the checked acquisition failure in jOOQ's
     * {@code DataAccessException} so batch rows methods and dispatch surfaces (which declare no
     * checked exceptions) route it through the same redaction contract as any data-access fault.
     */
    private static MethodSpec staticDslFor(ClassName self, TypeName tenantKey) {
        return MethodSpec.methodBuilder("dslFor")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(DSL_CONTEXT)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(tenantKey, "tenantKey")
            .beginControlFlow("try")
            .addStatement("return of(env).dslFor(tenantKey)")
            .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
            .addStatement("throw new $T($S + tenantKey, e)", DATA_ACCESS_EXCEPTION,
                "Acquiring the routed connection failed for tenant key: ")
            .endControlFlow()
            .addJavadoc("Routed acquisition for emitted fetcher sites: {@link #of} + {@link #dslFor(Object)},\n"
                + "with the checked acquisition failure wrapped unchecked.\n"
                + "@param env the field's {@code DataFetchingEnvironment}\n"
                + "@param tenantKey the divined tenant value\n")
            .build();
    }

    /**
     * {@code DSLContext dslFor(ConnectionKey key)}: the carrier's keyed acquisition taking the key a
     * fetcher built, every part of it, rather than one part to build the key from; a drop-in beside
     * {@code dslFor(tenantKey)}, which goes when its last caller does.
     */
    private static MethodSpec keyedDslFor(ClassName connectionKey) {
        return MethodSpec.methodBuilder("dslFor")
            .addModifiers(Modifier.PUBLIC)
            .returns(DSL_CONTEXT)
            .addParameter(connectionKey, "key")
            .addException(SQL_EXCEPTION)
            .addStatement("return enlist(key, entryFor(key))")
            .addJavadoc("Returns the provider-bound {@code DSLContext} for {@code key}, pinning and mounting one\n"
                + "connection for it on first use and reusing it thereafter.\n"
                + "@param key the connection key the fetcher built; a tenant outside the request's set raises\n"
                + "before any SQL\n")
            .build();
    }

    /**
     * {@code static DSLContext dslFor(DataFetchingEnvironment env, ConnectionKey key)}: the routed
     * acquisition an emitted fetcher site makes with the key it built, the checked acquisition failure
     * wrapped as the tenant-keyed sibling wraps it.
     */
    private static MethodSpec staticKeyedDslFor(ClassName self, ClassName connectionKey) {
        return MethodSpec.methodBuilder("dslFor")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(DSL_CONTEXT)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(connectionKey, "key")
            .beginControlFlow("try")
            .addStatement("return of(env).dslFor(key)")
            .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
            .addStatement("throw new $T($S + key, e)", DATA_ACCESS_EXCEPTION,
                "Acquiring the routed connection failed for connection key: ")
            .endControlFlow()
            .addJavadoc("Routed acquisition for emitted fetcher sites, by the key the site built: {@link #of} and\n"
                + "the carrier's keyed {@code dslFor}, with the checked acquisition failure wrapped unchecked.\n"
                + "@param env the field's {@code DataFetchingEnvironment}\n"
                + "@param key the connection key\n")
            .build();
    }

    /**
     * {@code static DSLContext dslHandedDown(DataFetchingEnvironment env)}: the acquisition a child
     * call makes on the whole key its parent handed down in {@code localContext}, a request-level
     * error before any SQL where none was handed down.
     */
    private static MethodSpec staticHandedDownDsl(ClassName self, ClassName connectionKey) {
        return MethodSpec.methodBuilder("dslHandedDown")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(DSL_CONTEXT)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .beginControlFlow("if (env.getLocalContext() instanceof $T key)", connectionKey)
            .addStatement("return dslFor(env, key)")
            .endControlFlow()
            .addStatement("throw new $T($S)", NO_SUCH_ELEMENT, NO_HANDED_DOWN_KEY)
            .addJavadoc("Acquisition on the key the parent handed down, every part of it.\n"
                + "@param env the field's {@code DataFetchingEnvironment}\n")
            .build();
    }

    /** The global-read sibling of {@link #staticDslDefault}, emitted in multi-tenant builds only. */
    private static MethodSpec staticDslGlobal(ClassName self) {
        return MethodSpec.methodBuilder("dslGlobal")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(DSL_CONTEXT)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .beginControlFlow("try")
            .addStatement("return of(env).dslGlobal()")
            .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
            .addStatement("throw new $T($S, e)", DATA_ACCESS_EXCEPTION,
                "Acquiring the connection for a global read failed")
            .endControlFlow()
            .addJavadoc("Global-read acquisition for emitted fetcher sites: {@link #of} +\n"
                + "{@link #dslGlobal()}, with the checked acquisition failure wrapped unchecked.\n"
                + "@param env the field's {@code DataFetchingEnvironment}\n")
            .build();
    }

    /**
     * {@code RequestTenants}: the request tenant set, an optional default tenant and an optional
     * entity default tenant, one value so the membership invariant lives in its constructor rather
     * than spread over the factory, the instrumentation and the carrier. A final value class with
     * record-style accessors rather than a Java record, because the project's JavaPoet fork does not
     * expose a record builder; the consumer-facing surface (canonical constructor, {@code tenants()},
     * {@code defaultTenant()}, {@code entityDefaultTenant()}, value equality) is the same. The entity
     * default tenant is held resolved: the factories and withers decide what naming nothing
     * separately means, so the stored value needs no "inherit" marker. {@code tenantKey} is the boxed
     * tenant key type, so the class is not generic. Valid Java 17.
     */
    private static TypeSpec requestTenants(ClassName self, ClassName ownedConnections, TypeName tenantKey) {
        var setType = ParameterizedTypeName.get(SET, tenantKey);
        var optionalKey = ParameterizedTypeName.get(OPTIONAL, tenantKey);
        var collectionType = ParameterizedTypeName.get(COLLECTION, tenantKey);
        var constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(setType, "tenants")
            .addParameter(optionalKey, "defaultTenant")
            .addParameter(optionalKey, "entityDefaultTenant")
            .addStatement("$T<$T> canonical = new $T<>()", LIST, tenantKey, ARRAY_LIST)
            .beginControlFlow("for ($T tenant : $T.requireNonNull(tenants, $S))", tenantKey, OBJECTS, "tenants")
            .addStatement("canonical.add($T.canonicalTenantKey($T.requireNonNull(tenant, $S)))",
                ownedConnections, OBJECTS, "tenant")
            .endControlFlow()
            .addComment("In the order given: the fan-out domain asks the source for each tenant in this order.")
            .addStatement("$T<$T> copy = $T.unmodifiableSet(new $T<>(canonical))", SET, tenantKey,
                ClassName.get("java.util", "Collections"), LINKED_HASH_SET)
            .addStatement("$T<$T> canonicalDefault = $T.requireNonNull(defaultTenant, $S)"
                + ".map($T::canonicalTenantKey)", OPTIONAL, tenantKey, OBJECTS, "defaultTenant", ownedConnections)
            .beginControlFlow("if (canonicalDefault.isPresent() && !copy.contains(canonicalDefault.get()))")
            .addStatement("throw new $T($S + defaultTenant.get() + $S)", IllegalArgumentException.class,
                "Default tenant '", "' is not in the request tenant set.")
            .endControlFlow()
            .addStatement("$T<$T> canonicalEntityDefault = $T.requireNonNull(entityDefaultTenant, $S)"
                + ".map($T::canonicalTenantKey)", OPTIONAL, tenantKey, OBJECTS, "entityDefaultTenant",
                ownedConnections)
            .beginControlFlow("if (canonicalEntityDefault.isPresent() && !copy.contains(canonicalEntityDefault.get()))")
            .addStatement("throw new $T($S + entityDefaultTenant.get() + $S)", IllegalArgumentException.class,
                "Entity default tenant '", "' is not in the request tenant set.")
            .endControlFlow()
            .addStatement("this.tenants = copy")
            .addStatement("this.defaultTenant = canonicalDefault")
            .addStatement("this.entityDefaultTenant = canonicalEntityDefault")
            .addJavadoc("Every tenant is reduced to the tenant column's form ({@link "
                + ownedConnections.simpleName() + "#canonicalTenantKey})\n"
                + "before the membership check, so two spellings the column stores as one value are one\n"
                + "tenant, and {@link #tenants()}, {@link #defaultTenant()} and\n"
                + "{@link #entityDefaultTenant()} report that form.\n"
                + "@param tenants the tenants this request may touch in this deployment; copied\n"
                + "@param defaultTenant the member of {@code tenants} that serves global reads, or empty\n"
                + "@param entityDefaultTenant the member of {@code tenants} on which {@code _entities}\n"
                + "looks up a {@code @defaultTenant} type's representations, or empty, in which case\n"
                + "every such representation answers {@code null}\n"
                + "@throws IllegalArgumentException when {@code defaultTenant} or\n"
                + "{@code entityDefaultTenant} is present and not in {@code tenants}, or a tenant is not\n"
                + "a value of the tenant column\n")
            .build();
        var twoRoleConstructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(setType, "tenants")
            .addParameter(optionalKey, "defaultTenant")
            .addStatement("this(tenants, defaultTenant, defaultTenant)")
            .addJavadoc("A request whose entity default tenant is its default tenant.\n"
                + "@param tenants the tenants this request may touch in this deployment; copied\n"
                + "@param defaultTenant the member of {@code tenants} that serves global reads and\n"
                + "{@code @defaultTenant} entities, or empty\n"
                + "@throws IllegalArgumentException when {@code defaultTenant} is present and not in\n"
                + "{@code tenants}, or a tenant is not a value of the tenant column\n")
            .build();
        var of = MethodSpec.methodBuilder("of")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(self)
            .addParameter(collectionType, "tenants")
            .addStatement("return new $T(new $T<>($T.requireNonNull(tenants, $S)), $T.empty())",
                self, LINKED_HASH_SET, OBJECTS, "tenants", OPTIONAL)
            .addJavadoc("A request tenant set with no default tenant: global tables read the default\n"
                + "source, and {@code @defaultTenant} entities answer {@code null} unless\n"
                + "{@link #withEntityDefault} names a tenant for them.\n"
                + "@param tenants the tenants this request may touch in this deployment\n")
            .build();
        var withDefault = MethodSpec.methodBuilder("withDefault")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(self)
            .addParameter(collectionType, "tenants")
            .addParameter(tenantKey, "defaultTenant")
            .addStatement("return new $T(new $T<>($T.requireNonNull(tenants, $S)), $T.of(defaultTenant))",
                self, LINKED_HASH_SET, OBJECTS, "tenants", OPTIONAL)
            .addJavadoc("A request tenant set whose member {@code defaultTenant} serves graphitron's reads\n"
                + "of global tables, on that tenant's connection and under its session mount, and is also\n"
                + "the entity default tenant unless {@link #withEntityDefault} or\n"
                + "{@link #withoutEntityDefault} says otherwise.\n"
                + "@param tenants the tenants this request may touch in this deployment\n"
                + "@param defaultTenant the tenant whose database answers global reads; must be in\n"
                + "{@code tenants}\n"
                + "@throws IllegalArgumentException when {@code defaultTenant} is not in {@code tenants}\n")
            .build();
        var withEntityDefault = MethodSpec.methodBuilder("withEntityDefault")
            .addModifiers(Modifier.PUBLIC)
            .returns(self)
            .addParameter(tenantKey, "tenant")
            .addStatement("return new $T(tenants, defaultTenant, $T.of(tenant))", self, OPTIONAL)
            .addJavadoc("This request with {@code tenant} as its entity default tenant: {@code _entities}\n"
                + "looks up a {@code @defaultTenant} type's representations on it. The tenant set and the\n"
                + "default tenant are unchanged.\n"
                + "@param tenant the entity default tenant; must be in {@link #tenants()}\n"
                + "@throws IllegalArgumentException when {@code tenant} is not in the tenant set\n")
            .build();
        var withoutEntityDefault = MethodSpec.methodBuilder("withoutEntityDefault")
            .addModifiers(Modifier.PUBLIC)
            .returns(self)
            .addStatement("return new $T(tenants, defaultTenant, $T.empty())", self, OPTIONAL)
            .addJavadoc("This request with no entity default tenant: every representation of a\n"
                + "{@code @defaultTenant} type answers {@code null}, without an error and without opening a\n"
                + "connection for it. The tenant set and the default tenant are unchanged, so global reads\n"
                + "still use the default tenant.\n")
            .build();
        var tenantsAccessor = MethodSpec.methodBuilder("tenants")
            .addModifiers(Modifier.PUBLIC)
            .returns(setType)
            .addStatement("return tenants")
            .addJavadoc("The request tenant set, immutable.\n")
            .build();
        var defaultTenantAccessor = MethodSpec.methodBuilder("defaultTenant")
            .addModifiers(Modifier.PUBLIC)
            .returns(optionalKey)
            .addStatement("return defaultTenant")
            .addJavadoc("The default tenant, or empty when the request names none.\n")
            .build();
        var entityDefaultTenantAccessor = MethodSpec.methodBuilder("entityDefaultTenant")
            .addModifiers(Modifier.PUBLIC)
            .returns(optionalKey)
            .addStatement("return entityDefaultTenant")
            .addJavadoc("The entity default tenant, on which {@code _entities} looks up a\n"
                + "{@code @defaultTenant} type's representations, or empty when the request names none.\n")
            .build();
        var equals = MethodSpec.methodBuilder("equals")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(boolean.class)
            .addParameter(Object.class, "other")
            .addStatement("return other instanceof $T that && tenants.equals(that.tenants)"
                + " && defaultTenant.equals(that.defaultTenant)"
                + " && entityDefaultTenant.equals(that.entityDefaultTenant)", self)
            .build();
        var hashCode = MethodSpec.methodBuilder("hashCode")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(int.class)
            .addStatement("return $T.hash(tenants, defaultTenant, entityDefaultTenant)", OBJECTS)
            .build();
        var toString = MethodSpec.methodBuilder("toString")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(String.class)
            .addStatement("return $S + tenants + $S + defaultTenant + $S + entityDefaultTenant + $S",
                REQUEST_TENANTS_CLASS_NAME + "[tenants=", ", defaultTenant=", ", entityDefaultTenant=", "]")
            .build();
        return TypeSpec.classBuilder(self)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("The request's tenancy, passed to {@code Graphitron.newOwnedExecutionInput}: the request\n"
                + "tenant set, the tenants the request may touch in this deployment, and optionally a\n"
                + "<em>default tenant</em>, one member of the set whose database answers graphitron's reads\n"
                + "of global tables (tables without the tenant column) on that tenant's connection and\n"
                + "under its session mount. Writes to global tables and {@code @service} calls keep the\n"
                + "runtime's default source either way. A second role, the <em>entity default tenant</em>,\n"
                + "is the optional member on which {@code _entities} looks up the representations of a\n"
                + "{@code @defaultTenant} type; it is the default tenant unless {@link #withEntityDefault}\n"
                + "or {@link #withoutEntityDefault} names another or none, and with none every such\n"
                + "representation answers {@code null}. Build one with {@link #of} or\n"
                + "{@link #withDefault}; a default or entity default tenant outside the set is refused\n"
                + "here.\n")
            .addField(FieldSpec.builder(setType, "tenants", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(optionalKey, "defaultTenant", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(optionalKey, "entityDefaultTenant", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(constructor)
            .addMethod(twoRoleConstructor)
            .addMethod(of)
            .addMethod(withDefault)
            .addMethod(withEntityDefault)
            .addMethod(withoutEntityDefault)
            .addMethod(tenantsAccessor)
            .addMethod(defaultTenantAccessor)
            .addMethod(entityDefaultTenantAccessor)
            .addMethod(equals)
            .addMethod(hashCode)
            .addMethod(toString)
            .build();
    }

    /** The default-source sibling of {@link #staticDslFor}. */
    private static MethodSpec staticDslDefault(ClassName self) {
        return MethodSpec.methodBuilder("dslDefault")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(DSL_CONTEXT)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .beginControlFlow("try")
            .addStatement("return of(env).dslDefault()")
            .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
            .addStatement("throw new $T($S, e)", DATA_ACCESS_EXCEPTION,
                "Acquiring the default-source connection failed")
            .endControlFlow()
            .addJavadoc("Default-source acquisition for emitted fetcher sites: {@link #of} +\n"
                + "{@link #dslDefault()}, with the checked acquisition failure wrapped unchecked.\n"
                + "@param env the field's {@code DataFetchingEnvironment}\n")
            .build();
    }

    /**
     * {@code static String loaderName(DataFetchingEnvironment env, String parentType)}: the
     * DataLoader name every multi-tenant registration site reads, so the naming recipe cannot
     * drift between sites. Path keys only (list indices stripped), joined by {@code "/"}, then
     * {@code "@"} and the declaring parent type, exactly the single-tenant inline form. The
     * parent type keeps same-named fields on different participants of one polymorphic field in
     * separate loaders; {@code "@"} cannot occur in a GraphQL name or alias.
     */
    private static MethodSpec loaderName() {
        return MethodSpec.methodBuilder("loaderName")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(String.class, "parentType")
            .addStatement("return String.join($S, env.getExecutionStepInfo().getPath().getKeysOnly()) + $S + parentType",
                "/", "@")
            .addJavadoc("The DataLoader name: named path segments joined by {@code \"/\"}, then\n"
                + "{@code \"@\"} and the declaring parent type. The single naming seam for every\n"
                + "multi-tenant loader registration site.\n")
            .build();
    }

    /**
     * {@code static String tenantLoaderName(DataFetchingEnvironment env, String parentType)}: the
     * tenant-partitioned loader name for fields inheriting a divined tenant. Load-bearing, not
     * cosmetic: a batch loader resolves one {@code DSLContext} from the environment captured at
     * loader creation, so a tenant-mixed loader would execute every key against the first key's
     * tenant. The tenant segment is an opaque partition key, never parsed back (the captured
     * environment carries the typed tenant).
     */
    private static MethodSpec tenantLoaderName(ClassName connectionKey) {
        return MethodSpec.methodBuilder("tenantLoaderName")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(String.class, "parentType")
            .addStatement("return tenantLoaderName(env, parentType, $T.tenantIn(env.getLocalContext()))",
                connectionKey)
            .addJavadoc("The tenant-partitioned DataLoader name for a field inheriting a divined tenant:\n"
                + "{@link #tenantLoaderName(DataFetchingEnvironment, String, Object)} over the\n"
                + "handed-down tenant.\n")
            .build();
    }

    /**
     * {@code static String handedDownLoaderName(DataFetchingEnvironment env, String parentType)}: the
     * loader name for a child running on the key its parent handed down, partitioned by the whole
     * key rather than one part of it, so each batch acquires on the key every one of its rows was
     * handed. The key is an opaque segment, never parsed back.
     */
    private static MethodSpec handedDownLoaderName(ClassName connectionKey) {
        return MethodSpec.methodBuilder("handedDownLoaderName")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(String.class, "parentType")
            .beginControlFlow("if (env.getLocalContext() instanceof $T key)", connectionKey)
            .addStatement("return loaderName(env, parentType) + $S + key", " key:")
            .endControlFlow()
            .addStatement("throw new $T($S)", NO_SUCH_ELEMENT, NO_HANDED_DOWN_KEY)
            .addJavadoc("The DataLoader name for a child on the handed-down key: {@link #loaderName} plus\n"
                + "the whole key as an opaque segment, so each batch runs on one key.\n"
                + "@param env the field's {@code DataFetchingEnvironment}\n"
                + "@param parentType the declaring parent type\n")
            .build();
    }

    /**
     * {@code static String tenantLoaderName(DataFetchingEnvironment env, String parentType, Object tenant)}:
     * the tenant-partitioned naming recipe's one home. The two-argument form passes the
     * handed-down tenant; a field that reads its tenant off the parent row passes that value, so
     * its loaders partition per parent tenant the same way.
     */
    private static MethodSpec tenantLoaderNameFor() {
        return MethodSpec.methodBuilder("tenantLoaderName")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(String.class, "parentType")
            .addParameter(Object.class, "tenant")
            .addStatement("return loaderName(env, parentType) + $S + tenant", " tenant:")
            .addJavadoc("The tenant-partitioned DataLoader name: {@link #loaderName} plus an opaque\n"
                + "tenant segment (never parsed back), so each loader batch stays tenant-homogeneous\n"
                + "and its captured environment routes the right source. The separator contains\n"
                + "characters no GraphQL path segment can, so the segment cannot collide with a path\n"
                + "suffix.\n"
                + "@param env the field's {@code DataFetchingEnvironment}\n"
                + "@param parentType the declaring object type of the field\n"
                + "@param tenant the tenant the loader's batch runs against\n")
            .build();
    }

    /**
     * {@code static OwnedConnections of(DataFetchingEnvironment env)}: resolves the per-operation
     * carrier the execution instrumentation stashed in the GraphQL context, on both topologies.
     * Emitted fetchers route every owned acquisition through this, so an operation that did not
     * run through graphitron-owned acquisition fails loudly before any SQL instead of silently
     * targeting the wrong database (or binding a null).
     */
    private static MethodSpec ofEnvironment(ClassName self) {
        return MethodSpec.methodBuilder("of")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(self)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addStatement("$T carrier = env.getGraphQlContext().get($T.class)", self, self)
            .beginControlFlow("if (carrier == null)")
            .addStatement("throw new $T($S)", IllegalStateException.class,
                "No " + OWNED_CONNECTIONS_CLASS_NAME + " in the GraphQL context: the owned-connection path"
                    + " resolves every connection through the per-operation carrier, so the operation must be"
                    + " built with Graphitron.newOwnedExecutionInput(...) and executed through"
                    + " GraphitronRuntime.newGraphQL(schema).")
            .endControlFlow()
            .addStatement("return carrier")
            .addJavadoc("The per-operation carrier the execution instrumentation stashed in the GraphQL\n"
                + "context. Fails loudly when the operation did not run through graphitron-owned\n"
                + "acquisition; owned fetch paths never fall back to an unrouted connection.\n"
                + "@param env the field's {@code DataFetchingEnvironment}\n")
            .build();
    }

    /**
     * {@code static <handleType> sessionHandle(DSLContext dsl, String fieldCoordinate)}: the
     * guarded handle read every {@code $session}-bound call site routes through. The handle is
     * written into the pinned connection's per-{@code Configuration} data map at mint, so a
     * missing entry means graphitron never mounted this connection: the operation ran on a
     * caller-supplied {@code DSLContext} (the escape-hatch factory), whose pooled provider makes
     * identity not merely unmounted but unmountable. The build cannot see which factory the
     * caller used, so this is the one {@code $session} failure that is runtime rather than
     * build-time, and it fails loudly in {@link #ofEnvironment of(env)}'s idiom instead of
     * binding a null into a service parameter meant to carry mounted identity.
     */
    private static MethodSpec sessionHandleAccessor(TypeName handleType) {
        return MethodSpec.methodBuilder("sessionHandle")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(handleType)
            .addParameter(DSL_CONTEXT, "dsl")
            .addParameter(String.class, "fieldCoordinate")
            .addStatement("Object handle = dsl.configuration().data($S)", SessionIdentityRows.HANDLE_DATA_KEY)
            .beginControlFlow("if (handle == null)")
            .addStatement("throw new $T($S + fieldCoordinate + $S)", IllegalStateException.class,
                "Field '",
                "' binds a parameter to " + ArgMappingSigil.SESSION_LITERAL + ", but this DSLContext"
                    + " carries no session handle: the connection source's lease gave none, or the operation"
                    + " ran on a caller-supplied DSLContext (Graphitron.newExecutionInput(dsl, ...)), which"
                    + " cannot carry one. Build it with Graphitron.newOwnedExecutionInput(source, ...),"
                    + " execute it through GraphitronRuntime.newGraphQL(schema), and lease each connection"
                    + " with its handle.")
            .endControlFlow()
            .addStatement("return ($T) handle", handleType)
            .addJavadoc("The mounted session handle riding {@code dsl}'s per-{@code Configuration} data map,\n"
                + "written at mint by the owned acquisition path. Fails loudly when absent: only owned\n"
                + "acquisition mounts, so a missing handle means the operation ran on a caller-supplied\n"
                + "DSLContext (the escape hatch), which cannot carry mounted identity.\n"
                + "@param dsl the call site's resolved per-connection context\n"
                + "@param fieldCoordinate the {@code $$session}-binding field, for the failure message\n")
            .build();
    }

    /** The generated carrier's {@code ClassName}: {@code <outputPackage>.schema.OwnedConnections}. */
    public static ClassName ownedConnectionsClass(String outputPackage) {
        return ClassName.get(outputPackage + ".schema", OWNED_CONNECTIONS_CLASS_NAME);
    }

    /** The method name of the guarded read of one handle member, the one place it is spelled. */
    public static String sessionMemberMethod(String member) {
        return no.sikt.graphitron.command.SessionAccessors.memberMethod(member);
    }

    /**
     * {@code static <memberType> sessionMember_<name>(DSLContext dsl, String fieldCoordinate)}: the
     * guarded read of one handle member that a {@code $session.<member>} binding routes through. It
     * is {@link #sessionHandleAccessor}'s guarded handle plus the member's own accessor, so the
     * join of the authored member name and the decoded member lives here and nowhere in the
     * call-site emitters, which name only the member.
     */
    private static MethodSpec sessionMemberAccessor(SessionIdentityRows.Member member) {
        var read = CodeBlock.of("sessionHandle(dsl, fieldCoordinate).$L()", member.accessor());
        return MethodSpec.methodBuilder(sessionMemberMethod(member.name()))
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(member.type())
            .addParameter(DSL_CONTEXT, "dsl")
            .addParameter(String.class, "fieldCoordinate")
            .addStatement("return $L", read)
            .addJavadoc("The session handle's {@code $L} member, read through the guarded handle.\n"
                + "@param dsl the call site's resolved per-connection context\n"
                + "@param fieldCoordinate the binding field, for the guard's failure message\n", member.name())
            .build();
    }

    /**
     * {@code static T namedTenant(Object... candidates)}: folds every runtime value of a field's
     * tenant bindings into the one tenant they name, or {@code null} when they name none (every
     * slot absent, or every list it reads through empty). Collections flatten; all non-null values
     * must agree once reduced to the column's form; a value the column cannot store is a
     * request-level error. The absent-tolerant
     * fold behind {@code divinedTenant}, called directly where an empty request has its own
     * answer.
     */
    private static MethodSpec namedTenant(TypeName tenantKey) {
        return MethodSpec.methodBuilder("namedTenant")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(tenantKey)
            .addParameter(Object[].class, "candidates")
            .varargs()
            .addStatement("$T key = null", tenantKey)
            .beginControlFlow("for (Object candidate : candidates)")
            .addStatement("key = agreeOnTenant(key, candidate)")
            .endControlFlow()
            .addStatement("return key")
            .addJavadoc("Folds the runtime values of a field's tenant bindings into the one tenant they\n"
                + "name, or {@code null} when they name none: collections flatten, every non-null value is\n"
                + "reduced to the tenant column's form ({@link #canonicalTenantKey}) and all must agree,\n"
                + "and a value the column cannot store is a request-level error before any SQL.\n"
                + "@param candidates each bound slot's runtime value (a value, a collection of values, or {@code null})\n")
            .build();
    }

    /**
     * {@code static T divinedTenant(Object... candidates)}: {@code namedTenant}, where a fold
     * naming no tenant is a request-level error before any SQL.
     */
    private static MethodSpec divinedTenant(TypeName tenantKey) {
        return MethodSpec.methodBuilder("divinedTenant")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(tenantKey)
            .addParameter(Object[].class, "candidates")
            .varargs()
            .addStatement("$T key = namedTenant(candidates)", tenantKey)
            .beginControlFlow("if (key == null)")
            .addComment("Absent binding value: a request-level error before any SQL, same family as the")
            .addComment("unknown-tenant acquisition failure.")
            .addStatement("throw new $T($S)", NO_SUCH_ELEMENT,
                "The tenant binding value is absent; cannot route the operation to a tenant database.")
            .endControlFlow()
            .addStatement("return key")
            .addJavadoc("Folds the runtime values of a field's tenant bindings into the one divined key:\n"
                + "collections flatten, all non-null values must agree once reduced to the tenant\n"
                + "column's form, and an absent value or one the column cannot store is a request-level\n"
                + "error before any SQL. A wire-form candidate (a decoded node-id segment, an ID\n"
                + "argument) coerces through the column's data type, as the generated SQL binds it.\n"
                + "@param candidates each bound slot's runtime value (a value, a collection of values, or {@code null})\n")
            .build();
    }

    /**
     * {@code public static K tenantKeyOrNull(Object value)}: the tenant key a value names, or
     * {@code null} when it names none. The value takes the column trip of the generated
     * {@code ColumnValues.coerce} over the representative tenant column's {@code DataType}: coerced
     * to the column's Java form, put through its converter to the database value and back, so
     * every spelling the column stores as one database value comes out as one key, and a number
     * that would wrap or exceed the column's precision names no tenant. The non-throwing half the
     * lookups use, where an id whose tenant does not decode answers {@code null} like any
     * malformed id.
     */
    private static MethodSpec tenantKeyOrNull(TypeName tenantKey, ClassName columnValues) {
        return MethodSpec.methodBuilder("tenantKeyOrNull")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(tenantKey)
            .addParameter(Object.class, "value")
            .addStatement("return $T.coerce(TENANT_COLUMN_TYPE, value)", columnValues)
            .addJavadoc("The tenant key a value names, or {@code null} when it names none: {@link #canonicalTenantKey}\n"
                + "without the throw.\n"
                + "@param value a tenant value in any form the column's data type coerces\n"
                + "@return the key in the column's canonical Java form, or {@code null}\n")
            .build();
    }

    /**
     * {@code public static K canonicalTenantKey(Object value)}: the one tenant key equality, the
     * throwing layer over {@code tenantKeyOrNull}. Public so a consumer can key its own tenant
     * state by the same equality.
     *
     * <p>A {@code null} input, and a value the column cannot store (the coercion or the converter
     * gives {@code null} or throws {@code IllegalArgumentException} or {@code DataTypeException},
     * or a number would wrap or exceed the column's precision), throw
     * {@code IllegalArgumentException} naming it. Any other converter exception propagates: it is
     * a fault in the consumer's converter, not a fact about the value. A value the column binds
     * by coercion ({@code "1.5"} on an integer column) names the tenant it coerces to, because
     * the key follows the bind. Configured keys pass through here too, so a configured key that
     * would wrap is refused when the configuration is built: it is a misconfiguration.
     */
    private static MethodSpec canonicalTenantKey(TypeName tenantKey) {
        return MethodSpec.methodBuilder("canonicalTenantKey")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(tenantKey)
            .addParameter(Object.class, "value")
            .addStatement("$T canonical = tenantKeyOrNull(value)", tenantKey)
            .beginControlFlow("if (canonical == null)")
            .addStatement("throw new $T($S + value + $S)", IllegalArgumentException.class,
                "Tenant value '", "' is not a value of the tenant column.")
            .endControlFlow()
            .addStatement("return canonical")
            .addJavadoc("The tenant key a value names: the value coerced through the tenant column's data\n"
                + "type, then put through the column's converter to its database value and back. Two values\n"
                + "the column stores as one database value give one key, so use this to key your own tenant\n"
                + "state by the equality graphitron routes with. A number the column would store as a\n"
                + "different number (one that overflows the column's type or precision) names no tenant.\n"
                + "@param value a tenant value in any form the column's data type coerces\n"
                + "@return the key in the column's canonical Java form\n"
                + "@throws IllegalArgumentException when the value is {@code null} or the column cannot store it\n")
            .build();
    }

    /**
     * {@code private static K tenantKeyOf(Object value)}: one client-sourced tenant value reduced
     * to its canonical key, shared by the routing fold and the fan-out narrowing so a value names
     * the same tenant whichever reads it. A value the tenant column cannot store is the client's
     * mistake, raised as the generated client-error type before the request tenant set is
     * consulted, so it reveals nothing about which tenants exist, and before a narrowed domain is
     * intersected, so it cannot empty one.
     */
    private static MethodSpec tenantKeyOf(TypeName tenantKey, ClassName clientException) {
        return MethodSpec.methodBuilder("tenantKeyOf")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(tenantKey)
            .addParameter(Object.class, "value")
            .beginControlFlow("try")
            .addStatement("return canonicalTenantKey(value)")
            .nextControlFlow("catch ($T e)", IllegalArgumentException.class)
            .addStatement("throw new $T($S + value + $S)", clientException,
                "Tenant value '", "' is not a value of the tenant column.")
            .endControlFlow()
            .build();
    }

    /**
     * The recursive agree-fold behind {@code divinedTenant}: flattens collections, reduces each
     * leaf to its canonical key, rejects disagreement. Disagreement is the caller's mistake (a
     * batch whose ids decode to two tenants, or two bound arguments naming different ones), and
     * one statement on one connection has no correct execution for it, so it raises the generated
     * client-error type and the client reads which tenants disagreed rather than a redacted
     * reference.
     */
    private static MethodSpec divinedTenantAgree(TypeName tenantKey, ClassName clientException) {
        var collection = ClassName.get("java.util", "Collection");
        return MethodSpec.methodBuilder("agreeOnTenant")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(tenantKey)
            .addParameter(tenantKey, "current")
            .addParameter(Object.class, "candidate")
            .beginControlFlow("if (candidate == null)")
            .addStatement("return current")
            .endControlFlow()
            .beginControlFlow("if (candidate instanceof $T<?> values)", collection)
            .beginControlFlow("for (Object value : values)")
            .addStatement("current = agreeOnTenant(current, value)")
            .endControlFlow()
            .addStatement("return current")
            .endControlFlow()
            .addStatement("$T key = tenantKeyOf(candidate)", tenantKey)
            .beginControlFlow("if (current != null && !current.equals(key))")
            .addStatement("throw new $T($S + current + $S + key + $S)", clientException,
                "Tenant bindings disagree within one operation: '", "' vs '",
                "'. One call writes or reads one tenant database, so every tenant value it names"
                    + " must be the same.")
            .endControlFlow()
            .addStatement("return key")
            .build();
    }

    /**
     * {@code static Object mapTenantLeaves(Object value, Function<Object, Object> leaf)}: applies
     * {@code leaf} to every value a slot read handed back, through any list it was read over, so a
     * tenant inside a node id is decoded out of each id before the divined-tenant fold flattens them.
     */
    private static MethodSpec mapTenantLeaves() {
        var list = ClassName.get("java.util", "List");
        var arrayList = ClassName.get("java.util", "ArrayList");
        var collection = ClassName.get("java.util", "Collection");
        var function = ParameterizedTypeName.get(ClassName.get("java.util.function", "Function"),
            ClassName.get(Object.class), ClassName.get(Object.class));
        return MethodSpec.methodBuilder("mapTenantLeaves")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(Object.class, "value")
            .addParameter(function, "leaf")
            .beginControlFlow("if (value == null)")
            .addStatement("return null")
            .endControlFlow()
            .beginControlFlow("if (value instanceof $T<?> values)", collection)
            .addStatement("$T<Object> out = new $T<>()", list, arrayList)
            .beginControlFlow("for (Object each : values)")
            .addStatement("out.add(mapTenantLeaves(each, leaf))")
            .endControlFlow()
            .addStatement("return out")
            .endControlFlow()
            .addStatement("return leaf.apply(value)")
            .addJavadoc("Applies {@code leaf} to every value a slot read handed back, through any list it was\n"
                + "read over; null stays null.\n"
                + "@param value what a slot read handed back\n"
                + "@param leaf what each value is turned into\n")
            .build();
    }

    /**
     * {@code static Object tenantSlot(Object container, String... path)}: reads a nested
     * input-object slot by the exact key path computed at build time from the slot's column
     * mapping (never a name search). Null-safe at every step; a list-shaped level maps the
     * remaining path over its elements (the divined-tenant fold flattens the result).
     */
    private static MethodSpec tenantSlot() {
        var list = ClassName.get("java.util", "List");
        var arrayList = ClassName.get("java.util", "ArrayList");
        var arrays = ClassName.get("java.util", "Arrays");
        var collection = ClassName.get("java.util", "Collection");
        return MethodSpec.methodBuilder("tenantSlot")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(Object.class)
            .addParameter(Object.class, "container")
            .addParameter(String[].class, "path")
            .varargs()
            .addStatement("Object current = container")
            .beginControlFlow("for (int i = 0; i < path.length; i++)")
            .beginControlFlow("if (current instanceof $T<?> values)", collection)
            .addComment("List-shaped level (e.g. a batch input): read the remaining path off every element;")
            .addComment("the divined-tenant fold flattens and equality-guards the results.")
            .addStatement("$T<Object> out = new $T<>()", list, arrayList)
            .addStatement("String[] rest = $T.copyOfRange(path, i, path.length)", arrays)
            .beginControlFlow("for (Object value : values)")
            .addStatement("out.add(tenantSlot(value, rest))")
            .endControlFlow()
            .addStatement("return out")
            .endControlFlow()
            .beginControlFlow("if (!(current instanceof $T<?, ?> map))", MAP)
            .addStatement("return null")
            .endControlFlow()
            .addStatement("current = map.get(path[i])")
            .endControlFlow()
            .addStatement("return current")
            .addJavadoc("Reads a nested input-object slot by the exact key path the build computed from the\n"
                + "slot's column mapping. Null-safe at every step; a list-shaped level maps the remaining\n"
                + "path over its elements.\n"
                + "@param container the outer argument value (a {@code Map}, a {@code List} of maps, or {@code null})\n"
                + "@param path the build-time key path from the container down to the bound slot\n")
            .build();
    }

    /**
     * {@code ConnectionSource}: the consumer's side of the connection contract, given with each request
     * to {@code Graphitron.newOwnedExecutionInput}. Graphitron calls {@code acquire} the first time a
     * field needs the connection for a key, never twice for one key in one request, and releases the
     * lease it returns exactly once. A source is built when the request is, so it captures what the
     * consumer needs of the request, and acquires nothing until called.
     */
    private static TypeSpec connectionSource(ClassName self, ClassName lease, ClassName connectionKey,
                                             ClassName keyNotPermitted) {
        return TypeSpec.interfaceBuilder(self)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(FunctionalInterface.class)
            .addJavadoc("Where a request's connections come from: one {@link $T} per distinct {@link $T}.\n"
                + "Given with each request to {@code Graphitron.newOwnedExecutionInput(source, ...)}, so it\n"
                + "captures what it needs of the request (a token, a principal) when it is built and acquires\n"
                + "nothing until Graphitron calls it. Graphitron calls {@link #acquire} the first time a field\n"
                + "needs a key's connection, never twice for one key in one request, and releases every lease\n"
                + "exactly once on every path. It may call it for distinct keys from several threads at once,\n"
                + "so a source must be safe to call concurrently. {@code $T.of} and\n"
                + "{@code $T.mounting} build one over a pool.\n",
                lease, connectionKey, self.peerClass(CONNECTION_SOURCES_CLASS_NAME),
                self.peerClass(CONNECTION_SOURCES_CLASS_NAME))
            .addMethod(MethodSpec.methodBuilder("acquire")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(lease)
                .addParameter(connectionKey, "key")
                .addException(Exception.class)
                .addJavadoc("Leases the connection {@code key} runs on, with any session state the request needs\n"
                    + "already set on it and autocommit on. Throw {@link $T} to refuse the key: the field\n"
                    + "that asked answers with an error, and an entity or node lookup with {@code null}. Any\n"
                    + "other throw rejects the request as a whole, before or instead of its result.\n"
                    + "@param key what the connection is for\n"
                    + "@return the lease, released by Graphitron exactly once\n", keyNotPermitted)
                .build())
            .build();
    }

    /**
     * {@code Lease}: what a {@code ConnectionSource} hands back. A final class built by its static
     * factories, so a consumer writes no class: the connection, the session handle where the build
     * configures one, the release action, and optionally a dialect and jOOQ {@code Settings} of the
     * lease's own, which otherwise are the runtime's.
     */
    private static TypeSpec lease(ClassName self, TypeName handleType) {
        var release = self.nestedClass("Release");
        var builder = TypeSpec.classBuilder(self)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("One leased connection: what a {@code ConnectionSource} returns for a key. Graphitron runs\n"
                + "the key's fields on {@link #connection()}, demarcates each mutation field's transaction on it,\n"
                + "and calls {@link #release()} exactly once when the request is done with it, after rolling\n"
                + "back anything left open. A connection Graphitron had to stop (a fan-out worker past its\n"
                + "deadline, a connection it could not settle) is aborted through JDBC before the release, so\n"
                + "a release finding it closed has nothing to clean up.\n")
            .addField(FieldSpec.builder(CONNECTION, "connection", Modifier.PRIVATE, Modifier.FINAL).build());
        if (handleType != null) {
            builder.addField(FieldSpec.builder(handleType, "handle", Modifier.PRIVATE, Modifier.FINAL).build());
        }
        builder.addField(FieldSpec.builder(SQL_DIALECT, "dialect", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(SETTINGS, "settings", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(release, "release", Modifier.PRIVATE, Modifier.FINAL).build());

        var constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .addParameter(CONNECTION, "connection");
        if (handleType != null) {
            constructor.addParameter(handleType, "handle");
        }
        constructor.addParameter(SQL_DIALECT, "dialect")
            .addParameter(SETTINGS, "settings")
            .addParameter(release, "release")
            .addStatement("this.connection = $T.requireNonNull(connection, $S)", OBJECTS, "connection");
        if (handleType != null) {
            constructor.addStatement("this.handle = handle");
        }
        constructor.addStatement("this.dialect = dialect")
            .addStatement("this.settings = settings")
            .addStatement("this.release = $T.requireNonNull(release, $S)", OBJECTS, "release");
        builder.addMethod(constructor.build());

        String handleArg = handleType == null ? "" : "handle, ";
        var of = MethodSpec.methodBuilder("of")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(self)
            .addParameter(CONNECTION, "connection");
        var ofOwn = MethodSpec.methodBuilder("of")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(self)
            .addParameter(CONNECTION, "connection");
        if (handleType != null) {
            of.addParameter(handleType, "handle");
            ofOwn.addParameter(handleType, "handle");
        }
        builder.addMethod(of.addParameter(release, "release")
                .addStatement("return new $T(connection, $Lnull, null, release)", self, handleArg)
                .addJavadoc("A lease over {@code connection}"
                    + (handleType == null ? "" : " carrying {@code handle}, what {@code $$session} bindings read")
                    + ", rendered\nwith the runtime's dialect and settings, released by {@code release}.\n")
                .build())
            .addMethod(ofOwn.addParameter(SQL_DIALECT, "dialect")
                .addParameter(SETTINGS, "settings")
                .addParameter(release, "release")
                .addStatement("return new $T(connection, $L$T.requireNonNull(dialect, $S), settings, release)",
                    self, handleArg, OBJECTS, "dialect")
                .addJavadoc("A lease whose connection is rendered with a dialect and settings of its own, for a\n"
                    + "pool of another database or another configuration than the runtime's.\n"
                    + "{@code settings} {@code null} means jOOQ's defaults.\n")
                .build())
            .addMethod(MethodSpec.methodBuilder("connection")
                .addModifiers(Modifier.PUBLIC)
                .returns(CONNECTION)
                .addStatement("return connection")
                .addJavadoc("The leased connection.\n")
                .build());
        if (handleType != null) {
            builder.addMethod(MethodSpec.methodBuilder("handle")
                .addModifiers(Modifier.PUBLIC)
                .returns(handleType)
                .addStatement("return handle")
                .addJavadoc("The session handle {@code $$session} bindings read on this connection, or {@code null}.\n")
                .build());
        }
        return builder
            .addMethod(MethodSpec.methodBuilder("dialect")
                .addModifiers(Modifier.PUBLIC)
                .returns(SQL_DIALECT)
                .addStatement("return dialect")
                .addJavadoc("The lease's own dialect, or {@code null} for the runtime's.\n")
                .build())
            .addMethod(MethodSpec.methodBuilder("settings")
                .addModifiers(Modifier.PUBLIC)
                .returns(SETTINGS)
                .addStatement("return settings")
                .addJavadoc("The lease's own jOOQ {@code Settings}, or {@code null}: the runtime's where the lease\n"
                    + "has no dialect of its own, jOOQ's defaults where it has.\n")
                .build())
            .addMethod(MethodSpec.methodBuilder("release")
                .addModifiers(Modifier.PUBLIC)
                .addException(Exception.class)
                .addStatement("release.release()")
                .addJavadoc("Runs the release action. Graphitron calls it exactly once.\n")
                .build())
            .addType(TypeSpec.interfaceBuilder("Release")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(FunctionalInterface.class)
                .addJavadoc("Gives a leased connection back: clears what the source set on it and returns it to its\n"
                    + "pool.\n")
                .addMethod(MethodSpec.methodBuilder("release")
                    .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                    .addException(Exception.class)
                    .build())
                .build())
            .build();
    }

    /**
     * {@code KeyNotPermitted}: the one refusal a {@code ConnectionSource} may throw. The carrier turns
     * it into the field error a refused tenant answered with before, so an entity or node lookup
     * answers {@code null}; its message is what the client reads.
     */
    private static TypeSpec keyNotPermitted(ClassName self) {
        return TypeSpec.classBuilder(self)
            .addModifiers(Modifier.PUBLIC)
            .superclass(RuntimeException.class)
            .addJavadoc("Thrown by a {@code ConnectionSource} that refuses a key: this request may not have that\n"
                + "connection. The field that asked answers with an error carrying this exception's message,\n"
                + "which the client reads, and an entity or node lookup answers {@code null}. The rest of the\n"
                + "request runs.\n")
            .addField(FieldSpec.builder(long.class, "serialVersionUID",
                    Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("1L")
                .build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addStatement("this($S)", "This request may not use that connection.")
                .build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(String.class, "message")
                .addStatement("super(message)")
                .addJavadoc("@param message what the client reads\n")
                .build())
            .build();
    }

    /**
     * {@code AcquisitionFailedException}: what {@code PinnedConnection.acquire} turns any throw of the
     * source other than {@code KeyNotPermitted} into. A request-scoped fault, unlike the field-scoped
     * {@code GraphitronClientException}: the carrier remembers the first one per operation, and the
     * instrumentation throws it out of {@code GraphQL.execute} so the consumer can answer the request
     * as a whole.
     */
    private static TypeSpec acquisitionFailed(ClassName self) {
        return TypeSpec.classBuilder(self)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .superclass(RuntimeException.class)
            .addJavadoc("The request's connection source failed to lease a connection: its {@code acquire} threw\n"
                + "something other than {@code KeyNotPermitted}, or leased a connection Graphitron could not\n"
                + "use. Thrown out of {@code GraphQL.execute} on the owned-connection path in place of a\n"
                + "result. What the source threw is the {@linkplain #getCause() cause}, so its SQLState stays\n"
                + "inspectable; the message carries none of it.\n")
            .addField(FieldSpec.builder(long.class, "serialVersionUID",
                    Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("1L")
                .build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(Throwable.class, "cause")
                .addStatement("super($S, cause)", "The connection source failed to lease a connection")
                .addJavadoc("@param cause what the source threw\n")
                .build())
            .build();
    }

    /**
     * {@code ConnectionSources}: the ready-made sources, shipped with the contract so a consumer keeps
     * the behaviour graphitron used to own without writing a lease: {@code of} over a pool per key, and
     * {@code mounting} over a pool per key with a mount and an unmount. {@code mounting} carries what
     * {@code PinnedConnection} used to: autocommit forced before the mount, on PostgreSQL a capture of
     * the session-set built-in settings and a {@code RESET ALL} and restore after the unmount, and a
     * connection evicted rather than pooled when either callback or the reset fails.
     */
    private static TypeSpec connectionSources(ClassName self, ClassName source, ClassName lease,
                                              ClassName connectionKey, TypeName handleType) {
        var pool = ParameterizedTypeName.get(FUNCTION, connectionKey, DATA_SOURCE);
        var mount = self.nestedClass("Mount");
        var unmount = self.nestedClass("Unmount");
        String handleArg = handleType == null ? "" : "handle, ";
        String nullHandle = handleType == null ? "" : "null, ";

        var of = MethodSpec.methodBuilder("of")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(source)
            .addParameter(pool, "pool")
            .addStatement("$T.requireNonNull(pool, $S)", OBJECTS, "pool")
            .addCode("return key -> {\n$>")
            .addStatement("$T connection = pool.apply(key).getConnection()", CONNECTION)
            .addStatement("return $T.of(connection, $Lconnection::close)", lease, nullHandle)
            .addCode("$<};\n")
            .addJavadoc("A source leasing each key's connection from the pool {@code pool} names for it, with no\n"
                + "session state set and none to clear: the release returns it to the pool.\n"
                + "@param pool the pool for a key; called once per distinct key per request\n")
            .build();

        var mounting = MethodSpec.methodBuilder("mounting")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(source)
            .addParameter(pool, "pool")
            .addParameter(mount, "mount")
            .addParameter(unmount, "unmount")
            .addStatement("$T.requireNonNull(pool, $S)", OBJECTS, "pool")
            .addStatement("$T.requireNonNull(mount, $S)", OBJECTS, "mount")
            .addStatement("$T.requireNonNull(unmount, $S)", OBJECTS, "unmount")
            .addStatement("return key -> acquireMounted(pool.apply(key).getConnection(), key, mount, unmount)")
            .addJavadoc("A source leasing each key's connection from the pool {@code pool} names for it, with the\n"
                + "request's session state mounted by {@code mount} and cleared at release by {@code unmount}.\n"
                + "Autocommit is forced on before the mount (rolling back what the pool left open), so the\n"
                + "mount is its own committed statement and must set session-scoped state. On PostgreSQL the\n"
                + "built-in settings the connection carries session-set are captured before the mount, and\n"
                + "the unmount is followed by {@code RESET ALL} and their restore, so a defective unmount\n"
                + "leaves the next borrower with no identity rather than this one's; the role is left alone,\n"
                + "so a role switch is the unmount's to undo. A throwing mount, unmount or reset evicts the\n"
                + "physical connection rather than returning it to the pool.\n"
                + "@param pool the pool for a key; called once per distinct key per request\n"
                + "@param mount sets the request's session state on a freshly taken connection\n"
                + "@param unmount clears what the mount set, at release\n")
            .build();

        var acquireMounted = MethodSpec.methodBuilder("acquireMounted")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(lease)
            .addParameter(CONNECTION, "connection")
            .addParameter(connectionKey, "key")
            .addParameter(mount, "mount")
            .addParameter(unmount, "unmount")
            .addException(Exception.class)
            .addStatement("final $T restore", STRING_TABLE);
        if (handleType != null) {
            acquireMounted.addStatement("final $T handle", handleType);
        }
        acquireMounted
            .beginControlFlow("try")
            .addComment("Autocommit is the resting state: the mount must be its own committed transaction, or")
            .addComment("the first failing mutation field's rollback would revert it. Roll back what the pool")
            .addComment("left open first, since JDBC commits an open transaction when autocommit is turned on.")
            .beginControlFlow("if (!connection.getAutoCommit())")
            .addStatement("connection.rollback()")
            .endControlFlow()
            .addStatement("connection.setAutoCommit(true)")
            .addStatement("restore = isPostgres(connection) ? captureSessionSettings(connection) : null")
            .addStatement(handleType == null ? "mount.mount(connection, key)" : "handle = mount.mount(connection, key)")
            .nextControlFlow("catch ($T failure)", Throwable.class)
            .addComment("Fail closed: the mount may have set part of its state before throwing.")
            .addStatement("evict(connection)")
            .addStatement("throw failure")
            .endControlFlow()
            .addStatement("return $T.of(connection, $L() -> unmountAndReturn(connection, key, $Lrestore, unmount))",
                lease, handleArg, handleArg);

        var unmountAndReturn = MethodSpec.methodBuilder("unmountAndReturn")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(CONNECTION, "connection")
            .addParameter(connectionKey, "key");
        if (handleType != null) {
            unmountAndReturn.addParameter(handleType, "handle");
        }
        unmountAndReturn.addParameter(STRING_TABLE, "restore")
            .addParameter(unmount, "unmount")
            .addException(Exception.class)
            .beginControlFlow("if (connection.isClosed())")
            .addComment("Graphitron aborted it: the session that held the state is gone and nothing returns.")
            .addStatement("return")
            .endControlFlow()
            .beginControlFlow("try")
            .addStatement(handleType == null ? "unmount.unmount(connection, key)" : "unmount.unmount(connection, key, handle)")
            .beginControlFlow("if (restore != null)")
            .addComment("RESET ALL is transactional: an unmount that switched autocommit off would leave it in a")
            .addComment("transaction the pool's return-rollback reverts. Evict instead of settling.")
            .beginControlFlow("if (!connection.getAutoCommit())")
            .addStatement("throw new $T($S)", IllegalStateException.class,
                "the unmount left autocommit off; identity cannot be proven reset")
            .endControlFlow()
            .beginControlFlow("try ($T reset = connection.prepareStatement($S\n$>$>+ $S$<$<))", PREPARED_STATEMENT,
                "RESET ALL;", " select set_config(n, v, false) from unnest(?::text[], ?::text[]) as t(n, v)")
            .addStatement("reset.setArray(1, connection.createArrayOf($S, restore[0]))", "text")
            .addStatement("reset.setArray(2, connection.createArrayOf($S, restore[1]))", "text")
            .addStatement("reset.execute()")
            .endControlFlow()
            .endControlFlow()
            .nextControlFlow("catch ($T failure)", Throwable.class)
            .addComment("Identity cannot be proven cleared: evict the physical connection, never return it.")
            .addStatement("evict(connection)")
            .addStatement("throw failure")
            .endControlFlow()
            .addStatement("connection.close()");

        var isPostgres = MethodSpec.methodBuilder("isPostgres")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter(CONNECTION, "connection")
            .addException(SQL_EXCEPTION)
            .addStatement("return $S.equals(connection.getMetaData().getDatabaseProductName())", "PostgreSQL")
            .build();

        var evict = MethodSpec.methodBuilder("evict")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(CONNECTION, "connection")
            .beginControlFlow("try")
            .addComment("abort() is the JDBC evict primitive: pool wrappers discard the physical connection.")
            .addStatement("connection.abort(Runnable::run)")
            .nextControlFlow("catch ($T abortFailure)", Throwable.class)
            .beginControlFlow("try")
            .addStatement("connection.close()")
            .nextControlFlow("catch ($T ignored)", Throwable.class)
            .addComment("nothing left to do; the connection is already unusable.")
            .endControlFlow()
            .endControlFlow()
            .build();

        var mountMethod = MethodSpec.methodBuilder("mount")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .returns(handleType == null ? TypeName.VOID : handleType)
            .addParameter(CONNECTION, "connection")
            .addParameter(connectionKey, "key")
            .addException(Exception.class)
            .build();
        var unmountMethod = MethodSpec.methodBuilder("unmount")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addParameter(CONNECTION, "connection")
            .addParameter(connectionKey, "key");
        if (handleType != null) {
            unmountMethod.addParameter(handleType, "handle");
        }
        return TypeSpec.classBuilder(self)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addJavadoc("Ready-made {@code ConnectionSource}s over a pool per key.\n")
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(of)
            .addMethod(mounting)
            .addMethod(acquireMounted.build())
            .addMethod(unmountAndReturn.build())
            .addMethod(isPostgres)
            .addMethod(captureSessionSettings())
            .addMethod(evict)
            .addType(TypeSpec.interfaceBuilder("Mount")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(FunctionalInterface.class)
                .addJavadoc("Sets the request's session state on a freshly taken connection, in autocommit"
                    + (handleType == null ? "" : ", and returns\nthe handle {@code $$session} bindings read") + ".\n")
                .addMethod(mountMethod)
                .build())
            .addType(TypeSpec.interfaceBuilder("Unmount")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(FunctionalInterface.class)
                .addJavadoc("Clears what the mount set, at release, outside any transaction.\n")
                .addMethod(unmountMethod.addException(Exception.class).build())
                .build())
            .build();
    }
}
