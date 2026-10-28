package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * The carrier's mutation field scope: the members {@code TenantConnections} gains so that every
 * top-level mutation field on the owned-connection path runs as one transaction per connection it
 * touches. Emitted into the carrier by {@link ConnectionRuntimeClassGenerator}; driven by
 * {@code GraphitronConnectionInstrumentation}, which begins a scope when a top-level mutation
 * field starts and settles it when the next one starts or the operation completes.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@code beginField(path)} makes a new scope active. It opens nothing: the first time the
 *       field acquires a connection, {@code enlist} opens a scope on that connection's provider
 *       ({@code GraphitronTransactionProvider.open()}) and records it, so a field that never
 *       touches the database opens no transaction. Every jOOQ transaction inside the field then
 *       nests on top as a savepoint.</li>
 *   <li>{@code markFieldFailed(env)} sets the scope's failed flag. {@code ErrorRouter} calls it for
 *       every exception an emitted fetcher hands it, whether redacted, surfaced or claimed into a
 *       payload; it is lenient (no carrier, no active scope, a path outside the field: no-op), so
 *       the escape hatch and queries are untouched.</li>
 *   <li>{@code settleField(failed)} ends every scope the field opened, in the order opened: rolled
 *       back when the field failed, else committed after a PostgreSQL probe that catches a
 *       transaction the database already aborted (pgjdbc commits those silently). Once one entry
 *       fails, the remaining entries roll back. It returns the cause when settling itself is what
 *       failed the field, for the instrumentation to report at the field's path.</li>
 * </ol>
 *
 * <h2>Threads</h2>
 * Only {@code enlist} runs off the dispatch thread, on scatter workers. A worker enlists on the
 * scope captured when its {@code scatter} started ({@code SCATTER_FIELD}), never on whatever
 * scope is active when it resumes, and enlisting synchronises with settling on the scope: settle
 * marks the scope settled first, and an enlist against a settled scope, or for a timed-out key,
 * opens nothing. A timed-out key's scope is never ended: its worker may still be executing on the
 * connection, which {@code releaseAll} aborts, discarding the transaction with it.
 */
final class MutationFieldScopeEmitter {

    static final String FIELD_SCOPE_CLASS_NAME = "FieldScope";

    private static final ClassName ATOMIC_BOOLEAN = ClassName.get("java.util.concurrent.atomic", "AtomicBoolean");
    private static final ClassName CONCURRENT_HASH_MAP = ClassName.get("java.util.concurrent", "ConcurrentHashMap");
    private static final ClassName LINKED_HASH_MAP = ClassName.get("java.util", "LinkedHashMap");
    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final ClassName SET = ClassName.get("java.util", "Set");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName OPTIONAL = ClassName.get("java.util", "Optional");
    private static final ClassName THREAD_LOCAL = ClassName.get("java.lang", "ThreadLocal");
    private static final ClassName SQL_EXCEPTION = ClassName.get("java.sql", "SQLException");
    private static final ClassName STATEMENT = ClassName.get("java.sql", "Statement");
    private static final ClassName SQL_DIALECT = ClassName.get("org.jooq", "SQLDialect");
    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName DATA_FETCHING_ENVIRONMENT = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName GRAPHQL_CONTEXT = ClassName.get("graphql", "GraphQLContext");
    private static final TypeName OBJECT_LIST = ParameterizedTypeName.get(LIST, ClassName.get(Object.class));

    private MutationFieldScopeEmitter() {}

    /** The carrier's {@code FieldScope} nested type: one top-level mutation field's transaction state. */
    static TypeSpec fieldScopeType(ClassName provider, TypeName tenantKey) {
        var optionalKey = ParameterizedTypeName.get(OPTIONAL, tenantKey);
        var scope = provider.nestedClass("Scope");
        return TypeSpec.classBuilder(FIELD_SCOPE_CLASS_NAME)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("One top-level mutation field's transaction state: its result path, whether it\n"
                + "failed, and the provider scope it opened on each connection it touched, in the order\n"
                + "opened. {@code opened} and {@code settled} are guarded by this object's monitor.\n")
            .addField(FieldSpec.builder(OBJECT_LIST, "path", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(ATOMIC_BOOLEAN, "failed", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T()", ATOMIC_BOOLEAN)
                .build())
            .addField(FieldSpec.builder(ParameterizedTypeName.get(MAP, optionalKey, scope), "opened",
                    Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T<>()", LINKED_HASH_MAP)
                .build())
            .addField(FieldSpec.builder(boolean.class, "settled", Modifier.PRIVATE).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(OBJECT_LIST, "path")
                .addStatement("this.path = $T.copyOf(path)", LIST)
                .build())
            .build();
    }

    /** {@code private volatile FieldScope activeField}: the field scope fetchers enlist on. */
    static FieldSpec activeField(ClassName self) {
        return FieldSpec.builder(self.nestedClass(FIELD_SCOPE_CLASS_NAME), "activeField",
                Modifier.PRIVATE, Modifier.VOLATILE)
            .addJavadoc("The top-level mutation field currently running, or {@code null} outside one (every\n"
                + "query operation, and between fields). Written on the dispatch thread only.\n")
            .build();
    }

    /** {@code brokenKeys}: keys whose scope stack a field could not end, refused for the operation. */
    static FieldSpec brokenKeys(TypeName tenantKey) {
        var optionalKey = ParameterizedTypeName.get(OPTIONAL, tenantKey);
        return FieldSpec.builder(ParameterizedTypeName.get(SET, optionalKey), "brokenKeys",
                Modifier.PRIVATE, Modifier.FINAL)
            .initializer("$T.newKeySet()", CONCURRENT_HASH_MAP)
            .addJavadoc("Keys whose connection a mutation field could not settle because a transaction opened\n"
                + "inside it was left open, which jOOQ's {@code transactionResult} cannot produce. The\n"
                + "connection was rolled back and is never handed out again in the operation; release\n"
                + "returns it in autocommit as usual.\n")
            .build();
    }

    /** {@code SCATTER_FIELD}: the field scope a scatter worker enlists on, captured at dispatch. */
    static FieldSpec scatterField(ClassName self) {
        return FieldSpec.builder(ParameterizedTypeName.get(THREAD_LOCAL, self.nestedClass(FIELD_SCOPE_CLASS_NAME)),
                "SCATTER_FIELD", Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("new $T<>()", THREAD_LOCAL)
            .addJavadoc("The field scope that was active when this worker's {@code scatter} started, set for\n"
                + "the worker's run: a straggler that resumes after its field settled must never enlist on\n"
                + "the next field.\n")
            .build();
    }

    /** {@code public void beginField(List<Object> path)}. */
    static MethodSpec beginField(ClassName self) {
        var fieldScope = self.nestedClass(FIELD_SCOPE_CLASS_NAME);
        return MethodSpec.methodBuilder("beginField")
            .addModifiers(Modifier.PUBLIC)
            .addParameter(OBJECT_LIST, "path")
            .addStatement("activeField = new $T(path)", fieldScope)
            .addJavadoc("Makes a new top-level mutation field active, its scope opened lazily per connection\n"
                + "on first acquisition. The instrumentation settles the previous field first.\n"
                + "@param path the field's result path ({@code ResultPath.toList()})\n")
            .build();
    }

    /**
     * {@code private DSLContext enlist(Optional<K> key, Entry entry)}: opens the active field's scope
     * on {@code entry}'s connection on first use, then hands out the entry's {@code DSLContext}.
     */
    static MethodSpec enlist(ClassName self, TypeName tenantKey, boolean multiTenant) {
        var optionalKey = ParameterizedTypeName.get(OPTIONAL, tenantKey);
        var fieldScope = self.nestedClass(FIELD_SCOPE_CLASS_NAME);
        var builder = MethodSpec.methodBuilder("enlist")
            .addModifiers(Modifier.PRIVATE)
            .returns(DSL_CONTEXT)
            .addParameter(optionalKey, "key")
            .addParameter(self.nestedClass("Entry"), "entry")
            .addException(SQL_EXCEPTION);
        if (multiTenant) {
            builder.addComment("A scatter worker enlists on the field that dispatched it, never on a later one.")
                .addStatement("$T field = $T.TRUE.equals(SCATTER_WORKER.get()) ? SCATTER_FIELD.get() : activeField",
                    fieldScope, Boolean.class);
        } else {
            builder.addStatement("$T field = activeField", fieldScope);
        }
        builder.beginControlFlow("if (field != null)")
            .beginControlFlow("synchronized (field)");
        if (multiTenant) {
            builder.addComment("Settled, or the key timed out: the connection is aborted at release, so opening")
                .addComment("a scope settle would never see would only leave it to the abort anyway.")
                .beginControlFlow("if (!field.settled && !field.opened.containsKey(key)"
                    + " && !(key.isPresent() && timedOutTenants.contains(key.get())))");
        } else {
            builder.beginControlFlow("if (!field.settled && !field.opened.containsKey(key))");
        }
        return builder
            .addStatement("field.opened.put(key, entry.provider.open())")
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .addStatement("return entry.dsl")
            .addJavadoc("Hands out {@code entry}'s {@code DSLContext}, first opening the active mutation\n"
                + "field's transaction on its connection when this is the field's first use of it. A\n"
                + "failed open propagates to the acquiring fetcher, which fails the field.\n")
            .build();
    }

    /** {@code public Throwable settleField(boolean failed)}. */
    static MethodSpec settleField(ClassName self, ClassName provider, TypeName tenantKey, boolean multiTenant) {
        var optionalKey = ParameterizedTypeName.get(OPTIONAL, tenantKey);
        var fieldScope = self.nestedClass(FIELD_SCOPE_CLASS_NAME);
        var scope = provider.nestedClass("Scope");
        var topLevel = provider.nestedClass("TopLevel");
        var entryType = self.nestedClass("Entry");
        var mapEntry = ParameterizedTypeName.get(MAP.nestedClass("Entry"), optionalKey, scope);
        var builder = MethodSpec.methodBuilder("settleField")
            .addModifiers(Modifier.PUBLIC)
            .returns(Throwable.class)
            .addParameter(boolean.class, "failed")
            .addStatement("$T field = activeField", fieldScope)
            .addStatement("activeField = null")
            .beginControlFlow("if (field == null)")
            .addStatement("return null")
            .endControlFlow()
            .beginControlFlow("synchronized (field)")
            .addComment("No scope is opened on this field from here on; every one it holds is now visible.")
            .addStatement("field.settled = true")
            .endControlFlow()
            .addStatement("boolean reported = failed || field.failed.get()")
            .addStatement("boolean rollBack = reported")
            .addStatement("$T cause = null", Throwable.class);
        if (multiTenant) {
            builder.beginControlFlow("for ($T key : field.opened.keySet())", optionalKey)
                .beginControlFlow("if (key.isPresent() && timedOutTenants.contains(key.get()))")
                .addComment("Its worker may still be executing on the connection: never end the scope, leave")
                .addComment("it to releaseAll's abort, which discards the transaction. The field fails, so")
                .addComment("the client is never told a write committed that the abort will roll back.")
                .addStatement("rollBack = true")
                .beginControlFlow("if (cause == null)")
                .addStatement("cause = new $T($S + key.get() + $S + field.path + $S)", IllegalStateException.class,
                    "Tenant '", "' timed out while mutation field ", " was open; its transaction is discarded"
                        + " with its connection")
                .endControlFlow()
                .endControlFlow()
                .endControlFlow();
        }
        builder.beginControlFlow("for ($T opened : field.opened.entrySet())", mapEntry)
            .addStatement("$T key = opened.getKey()", optionalKey);
        if (multiTenant) {
            builder.beginControlFlow("if (key.isPresent() && timedOutTenants.contains(key.get()))")
                .addStatement("continue")
                .endControlFlow();
        }
        return builder
            .addStatement("$T entry = entries.get(key)", entryType)
            .beginControlFlow("if (entry == null)")
            .addStatement("continue")
            .endControlFlow()
            .addStatement("$T scope = opened.getValue()", scope)
            .beginControlFlow("try")
            .beginControlFlow("if (!rollBack && scope instanceof $T"
                + " && entry.pinned.dialect().family() == $T.POSTGRES)", topLevel, SQL_DIALECT)
            .addComment("PostgreSQL aborts a transaction on any failed statement, even one the field's code")
            .addComment("caught, and pgjdbc's commit then rolls back without an error. The probe throws")
            .addComment("(25P02) on an aborted transaction, so the field fails instead of reporting writes")
            .addComment("that are gone.")
            .addStatement("probe(entry)")
            .endControlFlow()
            .addStatement("entry.provider.end(scope, rollBack)")
            .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
            .beginControlFlow("if (!rollBack)")
            .addComment("The probe or the commit failed: this entry rolls back, and so does every entry")
            .addComment("after it. Entries already committed stay committed: there is no transaction across")
            .addComment("connections.")
            .addStatement("rollBack = true")
            .addStatement("cause = e")
            .addStatement("rollBackQuietly(entry, scope)")
            .endControlFlow()
            .addComment("A failed rollback leaves the connection to release, which rolls an open")
            .addComment("transaction back before returning it.")
            .nextControlFlow("catch ($T e)", IllegalStateException.class)
            .addComment("end refused the field's scope: a transaction opened inside the field was left open.")
            .addComment("Roll the connection back and never hand it out again in this operation.")
            .addStatement("rollBack = true")
            .beginControlFlow("if (cause == null)")
            .addStatement("cause = new $T($S + field.path + $S, e)", IllegalStateException.class,
                "A transaction opened inside mutation field ", " was left open; the field is rolled back")
            .endControlFlow()
            .addStatement("brokenKeys.add(key)")
            .beginControlFlow("try")
            .addStatement("entry.pinned.connection().rollback()")
            .nextControlFlow("catch ($T ignored)", SQL_EXCEPTION)
            .addComment("release rolls back an open transaction before returning the connection.")
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .addComment("A field already reported as failed (an error at its path, or a claimed payload) needs")
            .addComment("no second report; only a failure settling itself discovered does.")
            .addStatement("return reported ? null : cause")
            .addJavadoc("Settles the active top-level mutation field and clears it: ends every scope the field\n"
                + "opened, in the order opened, rolled back when {@code failed} or when the field was\n"
                + "marked failed, else committed. A committing PostgreSQL top-level scope is probed first.\n"
                + "Once one entry fails to commit, the rest roll back"
                + (multiTenant ? "; a timed-out tenant's scope is never ended,\nand fails the field" : "")
                + ". Runs on the dispatch thread.\n"
                + "@param failed whether the field failed by the instrumentation's own reading (an error\n"
                + "under its path)\n"
                + "@return the cause when settling is what failed the field, for the instrumentation to\n"
                + "report at the field's path; {@code null} when the field committed or had already failed\n")
            .build();
    }

    /** {@code private static void probe(Entry entry)}: one round trip that throws on an aborted transaction. */
    static MethodSpec probe(ClassName self) {
        return MethodSpec.methodBuilder("probe")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(self.nestedClass("Entry"), "entry")
            .addException(SQL_EXCEPTION)
            .beginControlFlow("try ($T statement = entry.pinned.connection().createStatement())", STATEMENT)
            .addStatement("statement.execute($S)", "select 1")
            .endControlFlow()
            .addJavadoc("Runs one statement on {@code entry}'s connection; on PostgreSQL it throws (SQLState\n"
                + "{@code 25P02}) when the transaction is already aborted. Dialect-gated by the caller,\n"
                + "so the runtime never names a driver class.\n")
            .build();
    }

    /** {@code private static void rollBackQuietly(Entry entry, Scope scope)}. */
    static MethodSpec rollBackQuietly(ClassName self, ClassName provider) {
        return MethodSpec.methodBuilder("rollBackQuietly")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(self.nestedClass("Entry"), "entry")
            .addParameter(provider.nestedClass("Scope"), "scope")
            .beginControlFlow("try")
            .addStatement("entry.provider.end(scope, true)")
            .nextControlFlow("catch ($T | $T ignored)", SQL_EXCEPTION, IllegalStateException.class)
            .addComment("release rolls back an open transaction before returning the connection.")
            .endControlFlow()
            .addJavadoc("Rolls {@code scope} back after its commit failed; the provider leaves a scope whose\n"
                + "commit failed open for exactly this call. A failure here is left to release.\n")
            .build();
    }

    /** {@code public static void markFieldFailed(DataFetchingEnvironment env)}. */
    static MethodSpec markFieldFailed(ClassName self) {
        var fieldScope = self.nestedClass(FIELD_SCOPE_CLASS_NAME);
        return MethodSpec.methodBuilder("markFieldFailed")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .beginControlFlow("if (env == null || env.getExecutionStepInfo() == null)")
            .addStatement("return")
            .endControlFlow()
            .addStatement("$T context = env.getGraphQlContext()", GRAPHQL_CONTEXT)
            .addStatement("$T carrier = context == null ? null : context.get($T.class)", self, self)
            .beginControlFlow("if (carrier == null)")
            .addStatement("return")
            .endControlFlow()
            .addStatement("$T field = carrier.activeField", fieldScope)
            .beginControlFlow("if (field == null)")
            .addStatement("return")
            .endControlFlow()
            .addStatement("$T path = env.getExecutionStepInfo().getPath().toList()", OBJECT_LIST)
            .beginControlFlow("if (path.size() >= field.path.size() && path.subList(0, field.path.size()).equals(field.path))")
            .addStatement("field.failed.set(true)")
            .endControlFlow()
            .addJavadoc("Marks the running top-level mutation field failed, so its transaction rolls back\n"
                + "when it settles. {@code ErrorRouter} calls this for every exception an emitted fetcher\n"
                + "hands it, whether redacted, surfaced as a client error or claimed into an {@code @error}\n"
                + "payload. Lenient by design: a no-op without a carrier (the escape hatch), without an\n"
                + "active field (queries), or for a path outside the active field. Thread-safe.\n"
                + "@param env the failing field's environment, or {@code null}\n")
            .build();
    }

    /** The {@code brokenKeys} refusal at the top of {@code entryFor}. */
    static List<String> brokenKeyRefusalMessage() {
        return List.of("Key '", "' was left with an open transaction by an earlier mutation field in this"
            + " operation; its connection is never reused.");
    }
}
