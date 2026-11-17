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
 * The carrier's mutation field scope: the members {@code OwnedConnections} gains so that every
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
 *       joins it.</li>
 *   <li>{@code markFieldFailed(env, thrown)} sets the scope's failed flag and remembers what was
 *       handled, so settle can tell a nested failure the field's code caught from one that propagated. {@code ErrorRouter} calls it for
 *       every exception an emitted fetcher hands it, whether redacted, surfaced or claimed into a
 *       payload; it is lenient (no carrier, no active scope, a path outside the field: no-op), so
 *       the escape hatch and queries are untouched.</li>
 *   <li>{@code settleField(failed)} first scans every scope the field opened (a failed flag, a nested
 *       scope a consumer's code opened that failed, a key that timed out or was still opening, and
 *       on PostgreSQL a probe that catches a transaction the database already aborted, which pgjdbc
 *       commits silently), then ends them in the order opened: all rolled back on any finding,
 *       else committed. A commit that itself fails rolls back that entry and the ones after it. It
 *       returns the cause when settling itself is what failed the field, or a
 *       {@code NestedScopeFailedException} when a consumer's nested scope failed, for the
 *       instrumentation to report at the field's path.</li>
 * </ol>
 *
 * <h2>Threads</h2>
 * Only {@code enlist} runs off the dispatch thread, on scatter workers. A worker enlists on the
 * scope captured when its {@code scatter} started ({@code SCATTER_FIELD}), never on whatever
 * scope is active when it resumes, and enlisting synchronises with settling on the scope: settle
 * marks the scope settled first, and an enlist against a settled scope, or for a timed-out key,
 * is refused, so a straggler writes nothing. A timed-out key's scope is never ended: its worker may still be executing on the
 * connection, which {@code releaseAll} aborts, discarding the transaction with it.
 *
 * <h2>After a rollback</h2>
 * In a build with an {@code @outbox(on: ERROR)} message the scope also carries a field's failure
 * callback ({@code onFieldRollback}), which settle runs once the field's transaction has rolled back,
 * on every path that ends in one, with what the field's payload was claimed as
 * ({@code field.claimed}, the first claim): the link of the cause chain {@code ErrorRouter.claim}
 * matched, or the first error a {@code VALIDATION} refusal produced ({@code markFieldRefused}, which
 * marks the field failed too, so a refused field rolls back and writes its messages). It chooses the connection by what the rollback left of
 * the field's own, the first it opened: that connection when the rollback succeeded; a fresh one
 * ({@code writeOnFreshConnection}) when the tenant timed out, or when the rollback failed and an
 * exception settle holds names the transaction as why the session ended
 * ({@code endedOverTransaction}); nothing, logged, otherwise. A field without the callback settles
 * exactly as it would in a build without the message.
 */
final class MutationFieldScopeEmitter {

    static final String FIELD_SCOPE_CLASS_NAME = "FieldScope";

    static final String OPENING_CLASS_NAME = "Opening";
    static final String NESTED_SCOPE_FAILED_CLASS_NAME = "NestedScopeFailedException";
    static final String FIELD_ROLLBACK_CLASS_NAME = "FieldRollback";

    private static final ClassName COMPLETABLE_FUTURE = ClassName.get("java.util.concurrent", "CompletableFuture");
    private static final ClassName COMPLETION_EXCEPTION = ClassName.get("java.util.concurrent", "CompletionException");
    private static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");
    private static final ClassName ATOMIC_BOOLEAN = ClassName.get("java.util.concurrent.atomic", "AtomicBoolean");
    private static final ClassName ATOMIC_REFERENCE = ClassName.get("java.util.concurrent.atomic", "AtomicReference");
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
    static TypeSpec fieldScopeType(ClassName self, ClassName provider, TypeName keyType, boolean failureMessages) {
        var optionalKey = keyType;
        var scope = provider.nestedClass("Scope");
        var builder = TypeSpec.classBuilder(FIELD_SCOPE_CLASS_NAME)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("One top-level mutation field's transaction state: its result path, whether it\n"
                + "failed, and the provider scope it opened on each connection it touched, in the order\n"
                + "opened. {@code opened} and {@code settled} are guarded by this object's monitor, which\n"
                + "only ever covers bookkeeping: no JDBC and no consumer code runs under it.\n")
            .addField(FieldSpec.builder(OBJECT_LIST, "path", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(ATOMIC_BOOLEAN, "failed", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T()", ATOMIC_BOOLEAN)
                .build())
            .addField(FieldSpec.builder(ParameterizedTypeName.get(MAP, optionalKey, self.nestedClass(OPENING_CLASS_NAME)), "opened",
                    Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T<>()", LINKED_HASH_MAP)
                .build())
            .addField(FieldSpec.builder(boolean.class, "settled", Modifier.PRIVATE).build())
            .addField(FieldSpec.builder(ParameterizedTypeName.get(LIST, ClassName.get(Throwable.class)), "handled",
                    Modifier.PRIVATE, Modifier.FINAL)
                .initializer("$T.synchronizedList(new $T<>())", ClassName.get("java.util", "Collections"), ARRAY_LIST)
                .addJavadoc("The exceptions {@code ErrorRouter} handled for this field, so settle can tell a nested\n"
                    + "failure the field's code caught from one that propagated out of it.\n")
                .build())
            .addField(FieldSpec.builder(ParameterizedTypeName.get(ATOMIC_REFERENCE, ClassName.get(Object.class)),
                    "claimed", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T<>()", ATOMIC_REFERENCE)
                .addJavadoc("What an {@code @error} channel placed in this field's payload for its failure, the first\n"
                    + "claim: the link of the cause chain a mapping matched, or the first {@code GraphQLError} a\n"
                    + "{@code VALIDATION} refusal produced; {@code null} when nothing claimed it. A redacted or\n"
                    + "surfaced failure never writes it.\n")
                .build());
        if (failureMessages) {
            builder.addField(FieldSpec.builder(self.nestedClass(FIELD_ROLLBACK_CLASS_NAME), "onRollback", Modifier.PRIVATE)
                    .addJavadoc("What runs after this field's transaction rolled back, or {@code null}; guarded by this\n"
                        + "object's monitor.\n")
                    .build())
                .addField(FieldSpec.builder(ParameterizedTypeName.get(LIST, ClassName.get(String.class)),
                        "rollbackEvents", Modifier.PRIVATE)
                    .addJavadoc("The events {@link #onRollback} writes, named when they cannot be written.\n")
                    .build());
        }
        return builder
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(OBJECT_LIST, "path")
                .addStatement("this.path = $T.copyOf(path)", LIST)
                .build())
            .build();
    }

    /**
     * The carrier's {@code FieldRollback}: a field's failure messages, which settle runs on the
     * connection it chose after the field's transaction rolled back.
     */
    static TypeSpec fieldRollbackType() {
        return TypeSpec.interfaceBuilder(FIELD_ROLLBACK_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(FunctionalInterface.class)
            .addJavadoc("What a mutation field writes after its transaction rolled back: its\n"
                + "{@code @outbox(on: ERROR)} messages, registered by the field's fetcher through\n"
                + "{@link $L#onFieldRollback} and run by the carrier when the field settles.\n", "OwnedConnections")
            .addMethod(MethodSpec.methodBuilder("write")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter(DSL_CONTEXT, "dsl")
                .addParameter(Object.class, "claimed")
                .addJavadoc("Writes the field's failure messages.\n"
                    + "@param dsl the connection to write on, its session mounted, outside any transaction\n"
                    + "@param claimed the error an {@code @error} channel claimed the failure as, a {@code Throwable}\n"
                    + "or a validation refusal's {@code GraphQLError}, or {@code null}\n")
                .build())
            .build();
    }

    /** The carrier's {@code Opening} nested type: one connection's reserved, then recorded, scope. */
    static TypeSpec openingType(ClassName provider) {
        var scope = provider.nestedClass("Scope");
        return TypeSpec.classBuilder(OPENING_CLASS_NAME)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("One connection's scope within a field. The first acquirer reserves the key by creating\n"
                + "this, opens the scope outside the field's monitor, records it under the monitor, and\n"
                + "completes {@code done}; every other acquirer of the key waits on {@code done} before it\n"
                + "is handed the {@code DSLContext}, so no statement for the field runs before autocommit\n"
                + "is off or while the open's JDBC calls are still running.\n")
            .addField(FieldSpec.builder(scope, "scope", Modifier.PRIVATE).build())
            .addField(FieldSpec.builder(ParameterizedTypeName.get(COMPLETABLE_FUTURE, ClassName.get(Void.class)),
                    "done", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T<>()", COMPLETABLE_FUTURE)
                .build())
            .build();
    }

    /**
     * The carrier's {@code NestedScopeFailedException}: what {@code settleField} returns when a
     * transaction a consumer's code opened inside the field failed, so the field cannot commit
     * whether or not that code caught the failure.
     */
    static TypeSpec nestedScopeFailedType() {
        return TypeSpec.classBuilder(NESTED_SCOPE_FAILED_CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .superclass(RuntimeException.class)
            .addField(FieldSpec.builder(long.class, "serialVersionUID",
                    Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer("1L")
                .build())
            .addJavadoc("A transaction a consumer's code opened inside a mutation field failed, so the field's\n"
                + "transaction was rolled back. Its cause, when there is one, is that scope's failure.\n"
                + "{@code ErrorRouter.settleFailure} answers it with a fixed message.\n")
            .addField(FieldSpec.builder(boolean.class, "fieldReported", Modifier.PRIVATE, Modifier.FINAL)
                .addJavadoc("Whether the field had already been reported as failed (an error at its path, or a\n"
                    + "claimed payload) when it settled.\n")
                .build())
            .addMethod(MethodSpec.constructorBuilder()
                .addParameter(Throwable.class, "cause")
                .addParameter(boolean.class, "caught")
                .addParameter(boolean.class, "fieldReported")
                .addStatement("super(caught ? $S : $S, cause)",
                    "A nested transaction in this field failed; its exception was caught by the code that opened it",
                    "A nested transaction in this field failed; its exception propagated out of the field")
                .addStatement("this.fieldReported = fieldReported")
                .build())
            .addMethod(MethodSpec.methodBuilder("fieldReported")
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addStatement("return fieldReported")
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
    static FieldSpec brokenKeys(TypeName keyType) {
        var optionalKey = keyType;
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
    static MethodSpec enlist(ClassName self, ClassName provider, TypeName keyType, boolean multiTenant) {
        var optionalKey = keyType;
        var fieldScope = self.nestedClass(FIELD_SCOPE_CLASS_NAME);
        var opening = self.nestedClass(OPENING_CLASS_NAME);
        var scope = provider.nestedClass("Scope");
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
        builder.beginControlFlow("if (field == null)")
            .addStatement("return entry.dsl")
            .endControlFlow()
            .addStatement("$T opening", opening)
            .addStatement("boolean first = false")
            .beginControlFlow("synchronized (field)");
        builder.addComment("Bookkeeping only: the key is reserved here, and the open runs outside the monitor.");
        if (multiTenant) {
            builder.beginControlFlow("if (field.settled || (key.tenant().isPresent() && timedOutTenants.contains(key.tenant().get())))");
        } else {
            builder.beginControlFlow("if (field.settled)");
        }
        builder.addComment("A straggler, or a key that timed out: the connection is aborted at release, so a")
            .addComment("statement here would run outside any transaction the field settles.")
            .addStatement("throw new $T($S + field.path + $S)", IllegalStateException.class,
                "Mutation field ", " has settled; a late acquisition is refused so that it writes nothing")
            .endControlFlow()
            .addStatement("opening = field.opened.get(key)")
            .beginControlFlow("if (opening == null)")
            .addStatement("opening = new $T()", opening)
            .addStatement("field.opened.put(key, opening)")
            .addStatement("first = true")
            .endControlFlow()
            .endControlFlow()
            .beginControlFlow("if (first)")
            .beginControlFlow("try")
            .addStatement("$T opened = entry.provider.open()", scope)
            .beginControlFlow("synchronized (field)")
            .beginControlFlow("if (field.settled)")
            .addComment("The field settled while this open ran, so the key is timed out and the connection is")
            .addComment("aborted at release: the scope is not recorded.")
            .addStatement("throw new $T($S + field.path + $S)", IllegalStateException.class,
                "Mutation field ", " settled while its transaction was opening")
            .endControlFlow()
            .addStatement("opening.scope = opened")
            .endControlFlow()
            .addStatement("opening.done.complete(null)")
            .nextControlFlow("catch ($T | $T e)", SQL_EXCEPTION, RuntimeException.class)
            .addComment("A waiter fails the way the first acquirer did.")
            .addStatement("opening.done.completeExceptionally(e)")
            .addStatement("throw e")
            .endControlFlow()
            .nextControlFlow("else")
            .addStatement("await(opening)")
            .endControlFlow()
            .addStatement("return entry.dsl");
        return builder
            .addJavadoc("Hands out {@code entry}'s {@code DSLContext}, first opening the active mutation\n"
                + "field's transaction on its connection when this is the field's first use of it. The\n"
                + "key is reserved under the field's monitor and opened outside it, so a stalled open\n"
                + "never blocks another worker or the settle; any other acquirer of the key waits until\n"
                + "the open has finished. A failed open propagates to every acquirer, which fails the\n"
                + "field; an acquisition after the field settled is refused.\n")
            .build();
    }

    /** {@code private static void await(Opening opening)}: waits for the first acquirer's open. */
    static MethodSpec await(ClassName self) {
        return MethodSpec.methodBuilder("await")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(self.nestedClass(OPENING_CLASS_NAME), "opening")
            .addException(SQL_EXCEPTION)
            .beginControlFlow("try")
            .addStatement("opening.done.join()")
            .nextControlFlow("catch ($T e)", COMPLETION_EXCEPTION)
            .addStatement("$T cause = e.getCause()", Throwable.class)
            .beginControlFlow("if (cause instanceof $T sql)", SQL_EXCEPTION)
            .addStatement("throw sql")
            .endControlFlow()
            .beginControlFlow("if (cause instanceof $T unchecked)", RuntimeException.class)
            .addStatement("throw unchecked")
            .endControlFlow()
            .addStatement("throw e")
            .endControlFlow()
            .addJavadoc("Waits for the first acquirer of a key to finish opening its scope, and fails with the\n"
                + "failure it had, so no acquirer is handed a {@code DSLContext} ahead of the open.\n")
            .build();
    }

    /** {@code public Throwable settleField(boolean failed)}. */
    static MethodSpec settleField(ClassName self, ClassName provider, TypeName keyType, boolean multiTenant,
                                  boolean failureMessages) {
        var optionalKey = keyType;
        var fieldScope = self.nestedClass(FIELD_SCOPE_CLASS_NAME);
        var opening = self.nestedClass(OPENING_CLASS_NAME);
        var scope = provider.nestedClass("Scope");
        var topLevel = provider.nestedClass("TopLevel");
        var entryType = self.nestedClass("Entry");
        var recordedType = ParameterizedTypeName.get(MAP, optionalKey, scope);
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
            .addStatement("$T recorded = new $T<>()", recordedType, LINKED_HASH_MAP)
            .addStatement("$T<$T> inFlight = new $T<>()", LIST, optionalKey, ARRAY_LIST);
        if (failureMessages) {
            builder.addStatement("$T write", self.nestedClass(FIELD_ROLLBACK_CLASS_NAME))
                .addStatement("$T<$T> events", LIST, String.class)
                .addStatement("$T own", optionalKey);
        }
        builder.beginControlFlow("synchronized (field)")
            .addComment("No scope is recorded on this field from here on; every one it holds is now visible.")
            .addStatement("field.settled = true");
        if (failureMessages) {
            builder.addComment("The field's own connection is the first it opened: its fetcher acquires it first.")
                .addStatement("write = field.onRollback")
                .addStatement("events = field.rollbackEvents")
                .addStatement("own = field.opened.isEmpty() ? null : field.opened.keySet().iterator().next()");
        }
        builder
            .beginControlFlow("for ($T<$T, $T> e : field.opened.entrySet())", MAP.nestedClass("Entry"), optionalKey, opening)
            .beginControlFlow("if (e.getValue().scope != null)")
            .addStatement("recorded.put(e.getKey(), e.getValue().scope)")
            .nextControlFlow("else if (!e.getValue().done.isDone())")
            .addComment("Reserved but not recorded: a worker still opening, which the join only")
            .addComment("leaves behind once its deadline passed.")
            .addStatement("inFlight.add(e.getKey())")
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .addStatement("boolean reported = failed || field.failed.get()")
            .addStatement("boolean rollBack = reported")
            .addStatement("$T cause = null", Throwable.class)
            .addStatement("boolean nestedFailed = false")
            .addStatement("$T nested = null", Throwable.class);
        if (failureMessages) {
            builder.addComment("What ending the field's own connection raised, for the failure messages' choice.")
                .addStatement("$T<$T> ownLost = new $T<>()", LIST, Throwable.class, ARRAY_LIST)
                .addStatement("boolean ownRollbackFailed = false");
        }
        if (multiTenant) {
            builder.beginControlFlow("for ($T key : inFlight)", optionalKey)
                .addComment("Its worker may still be executing on the connection: never end the scope, leave")
                .addComment("it to releaseAll's abort. The field fails, so the client is never told a write")
                .addComment("committed that the abort will roll back.")
                .addStatement("rollBack = true")
                .beginControlFlow("if (cause == null)")
                .addStatement("cause = new $T($S + key + $S + field.path + $S)", IllegalStateException.class,
                    "Connection '", "' was still opening its transaction when mutation field ",
                    " settled; its transaction is discarded with its connection")
                .endControlFlow()
                .addCode(failureMessages ? abortTimedOutForFailureMessages(self) : com.palantir.javapoet.CodeBlock.of(""))
                .endControlFlow()
                .beginControlFlow("for ($T key : recorded.keySet())", optionalKey)
                .beginControlFlow("if (key.tenant().isPresent() && timedOutTenants.contains(key.tenant().get()))")
                .addComment("Its worker may still be executing on the connection: never end the scope, leave")
                .addComment("it to releaseAll's abort, which discards the transaction. The field fails, so")
                .addComment("the client is never told a write committed that the abort will roll back.")
                .addStatement("rollBack = true")
                .beginControlFlow("if (cause == null)")
                .addStatement("cause = new $T($S + key.tenant().get() + $S + field.path + $S)", IllegalStateException.class,
                    "Tenant '", "' timed out while mutation field ", " was open; its transaction is discarded"
                        + " with its connection")
                .endControlFlow()
                .addCode(failureMessages ? abortTimedOutForFailureMessages(self) : com.palantir.javapoet.CodeBlock.of(""))
                .endControlFlow()
                .endControlFlow();
        }
        builder.addComment("Scan every connection before any of them commits: a nested scope that failed, and on")
            .addComment("PostgreSQL a transaction the database already aborted (pgjdbc commits those without")
            .addComment("an error), fail the field on every connection, never on some.")
            .beginControlFlow("for ($T scanned : recorded.entrySet())", mapEntry)
            .addStatement("$T key = scanned.getKey()", optionalKey);
        if (multiTenant) {
            builder.beginControlFlow("if (key.tenant().isPresent() && timedOutTenants.contains(key.tenant().get()))")
                .addStatement("continue")
                .endControlFlow();
        }
        builder.addStatement("$T entry = entries.get(key)", entryType)
            .beginControlFlow("if (entry == null)")
            .addStatement("continue")
            .endControlFlow()
            .addStatement("$T scope = scanned.getValue()", scope)
            .beginControlFlow("if (scope.rollbackOnly)")
            .addStatement("rollBack = true")
            .beginControlFlow("if (scope.consumerFailure && !nestedFailed)")
            .addStatement("nestedFailed = true")
            .addStatement("nested = scope.cause")
            .endControlFlow()
            .nextControlFlow("else if (!rollBack && scope instanceof $T"
                + " && entry.pinned.dialect().family() == $T.POSTGRES)", topLevel, SQL_DIALECT)
            .beginControlFlow("try")
            .addStatement("probe(entry)")
            .nextControlFlow("catch ($T | $T e)", SQL_EXCEPTION, RuntimeException.class)
            .addComment("The probe throws (25P02) on an aborted transaction, so the field fails instead of")
            .addComment("reporting writes that are gone.")
            .addStatement("rollBack = true")
            .addStatement("cause = e")
            .addCode(failureMessages
                ? com.palantir.javapoet.CodeBlock.builder()
                    .add("// The first statement after the database ended the session is the one that says why.\n")
                    .beginControlFlow("if (key.equals(own))")
                    .addStatement("ownLost.add(e)")
                    .endControlFlow()
                    .build()
                : com.palantir.javapoet.CodeBlock.of(""))
            .endControlFlow()
            .endControlFlow()
            .endControlFlow();
        builder.addComment("End every scope in the order opened. Only a commit that itself fails (a deferred")
            .addComment("constraint, a serialization failure, a lost connection) can leave the field partly")
            .addComment("committed: that entry and every one after it roll back, and entries already committed")
            .addComment("stay committed, since there is no transaction across connections.")
            .beginControlFlow("for ($T ending : recorded.entrySet())", mapEntry)
            .addStatement("$T key = ending.getKey()", optionalKey);
        if (multiTenant) {
            builder.beginControlFlow("if (key.tenant().isPresent() && timedOutTenants.contains(key.tenant().get()))")
                .addStatement("continue")
                .endControlFlow();
        }
        builder
            .addStatement("$T entry = entries.get(key)", entryType)
            .beginControlFlow("if (entry == null)")
            .addStatement("continue")
            .endControlFlow()
            .addStatement("$T scope = ending.getValue()", scope)
            .beginControlFlow("try")
            .addStatement("entry.provider.end(scope, rollBack)")
            .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
            .beginControlFlow("if (!rollBack)")
            .addStatement("rollBack = true")
            .addStatement("cause = e")
            .addCode(failureMessages
                ? com.palantir.javapoet.CodeBlock.builder()
                    .addStatement("$T quiet = rollBackQuietly(entry, scope)", Throwable.class)
                    .beginControlFlow("if (key.equals(own))")
                    .add("// A COMMIT that failed, and the rollback after it: both name what happened.\n")
                    .addStatement("ownLost.add(e)")
                    .beginControlFlow("if (quiet != null)")
                    .addStatement("ownRollbackFailed = true")
                    .addStatement("ownLost.add(quiet)")
                    .endControlFlow()
                    .endControlFlow()
                    .build()
                : com.palantir.javapoet.CodeBlock.builder().addStatement("rollBackQuietly(entry, scope)").build());
        if (failureMessages) {
            builder.nextControlFlow("else if (key.equals(own))")
                .addComment("The rollback failed: the connection did not survive it.")
                .addStatement("ownRollbackFailed = true")
                .addStatement("ownLost.add(e)");
        }
        builder.endControlFlow()
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
            .nextControlFlow("catch ($T e)", RuntimeException.class)
            .addComment("An unchecked failure from a driver or pool wrapper fails this connection and the")
            .addComment("field, and the remaining connections still settle.")
            .addStatement("rollBack = true")
            .beginControlFlow("if (cause == null)")
            .addStatement("cause = e")
            .endControlFlow()
            .addCode(failureMessages
                ? com.palantir.javapoet.CodeBlock.builder()
                    .addStatement("$T quiet = rollBackQuietly(entry, scope)", Throwable.class)
                    .beginControlFlow("if (key.equals(own))")
                    .addStatement("ownLost.add(e)")
                    .beginControlFlow("if (quiet != null)")
                    .addStatement("ownRollbackFailed = true")
                    .addStatement("ownLost.add(quiet)")
                    .endControlFlow()
                    .endControlFlow()
                    .build()
                : com.palantir.javapoet.CodeBlock.builder().addStatement("rollBackQuietly(entry, scope)").build())
            .endControlFlow()
            .endControlFlow();
        if (failureMessages) {
            builder.beginControlFlow("if (rollBack && write != null)")
                .addComment("The field's transaction rolled back: its failure messages are written now, on the")
                .addComment("connection what happened to its own leaves, or not at all and logged.")
                .addStatement("afterRollback(field, write, events, own, ownRollbackFailed, ownLost)")
                .endControlFlow();
        }
        return builder
            .beginControlFlow("if (nestedFailed)")
            .addComment("A transaction a consumer's code opened inside the field failed, whether or not that")
            .addComment("code caught it: reported beside whatever else the field already reported.")
            .addStatement("boolean caught = true")
            .beginControlFlow("if (nested != null)")
            .addComment("The log under the report's reference says whether the consumer's code caught the")
            .addComment("exception: it did not when the router handled an exception whose cause chain holds it.")
            .beginControlFlow("synchronized (field.handled)")
            .beginControlFlow("for ($T handledThrowable : field.handled)", Throwable.class)
            .beginControlFlow("for ($T t = handledThrowable; t != null; t = t.getCause())", Throwable.class)
            .beginControlFlow("if (t == nested)")
            .addStatement("caught = false")
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .addStatement("$T report = new $T(nested, caught, reported)", self.nestedClass(NESTED_SCOPE_FAILED_CLASS_NAME),
                self.nestedClass(NESTED_SCOPE_FAILED_CLASS_NAME))
            .beginControlFlow("if (cause != null)")
            .addStatement("report.addSuppressed(cause)")
            .endControlFlow()
            .addStatement("return report")
            .endControlFlow()
            .addComment("A field already reported as failed (an error at its path, or a claimed payload) needs")
            .addComment("no second report; only a failure settling itself discovered does.")
            .addStatement("return reported ? null : cause")
            .addJavadoc("Settles the active top-level mutation field and clears it. Scans every connection the\n"
                + "field used before any commits: the field's failed flag, a nested scope a consumer's code\n"
                + "opened that failed, a key that timed out or was still opening, and on PostgreSQL a\n"
                + "transaction the database already aborted. Any finding rolls every connection back;\n"
                + "otherwise they commit in the order opened, and a commit that itself fails rolls back\n"
                + "that connection and the ones after it. Runs on the dispatch thread.\n"
                + "@param failed whether the field failed by the instrumentation's own reading (an error\n"
                + "under its path)\n"
                + "@return a {@code NestedScopeFailedException} when a consumer's nested scope failed, even if\n"
                + "the field had already failed some other way; otherwise the cause when settling is what\n"
                + "failed the field, for the instrumentation to report at the field's path; {@code null}\n"
                + "when the field committed or had already failed\n")
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

    /** {@code private static Throwable rollBackQuietly(Entry entry, Scope scope)}. */
    static MethodSpec rollBackQuietly(ClassName self, ClassName provider) {
        return MethodSpec.methodBuilder("rollBackQuietly")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(Throwable.class)
            .addParameter(self.nestedClass("Entry"), "entry")
            .addParameter(provider.nestedClass("Scope"), "scope")
            .beginControlFlow("try")
            .addStatement("entry.provider.end(scope, true)")
            .addStatement("return null")
            .nextControlFlow("catch ($T | $T failure)", SQL_EXCEPTION, IllegalStateException.class)
            .addComment("release rolls back an open transaction before returning the connection.")
            .addStatement("return failure")
            .endControlFlow()
            .addJavadoc("Rolls {@code scope} back after its commit failed; the provider leaves a scope whose\n"
                + "commit failed open for exactly this call. A failure here is left to release, and\n"
                + "returned, so settle can tell whether the connection survived; {@code null} when the\n"
                + "rollback succeeded.\n")
            .build();
    }

    /**
     * The statements that abort a timed-out key's connection at settle, for a field with failure
     * messages, and remove its entry: its worker may still be running on it, and the abort is what
     * keeps that straggler from committing behind the message, and gives the pool its slot back
     * before a fresh connection is asked for. Without the callback the connection is left to
     * {@code releaseAll}, as it always was.
     */
    private static com.palantir.javapoet.CodeBlock abortTimedOutForFailureMessages(ClassName self) {
        return com.palantir.javapoet.CodeBlock.builder()
            .beginControlFlow("if (write != null)")
            .addStatement("$T stale = entries.remove(key)", self.nestedClass("Entry"))
            .beginControlFlow("if (stale != null)")
            .addStatement("stale.pinned.abort()")
            .endControlFlow()
            .endControlFlow()
            .build();
    }

    /** {@code REPORTED_JOINS}: the calling methods already warned about, for the life of the process. */
    static FieldSpec reportedJoins() {
        return FieldSpec.builder(ParameterizedTypeName.get(SET, ClassName.get(String.class)), "REPORTED_JOINS",
                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("$T.newKeySet()", CONCURRENT_HASH_MAP)
            .addJavadoc("The calling methods already warned about, so a service that wraps every call in\n"
                + "{@code transactionResult} logs one line for the life of the deployment, not one per\n"
                + "request, and a sweep that nests per item logs one.\n")
            .build();
    }

    /** {@code private void warnConsumerJoin(String caller)}: the provider's report of a consumer's joined scope. */
    static MethodSpec warnConsumerJoin(ClassName self) {
        var fieldScope = self.nestedClass(FIELD_SCOPE_CLASS_NAME);
        return MethodSpec.methodBuilder("warnConsumerJoin")
            .addModifiers(Modifier.PRIVATE)
            .addParameter(String.class, "caller")
            .beginControlFlow("if (!REPORTED_JOINS.add(caller))")
            .addStatement("return")
            .endControlFlow()
            .addStatement("$T field = activeField", fieldScope)
            .addStatement("LOGGER.warn($S, caller, field == null ? $S : field.path)",
                "A transaction opened in {} joined the enclosing transaction (mutation field: {}) and took no"
                    + " savepoint: a failure inside it now fails the whole transaction, even if the code that"
                    + " opened it catches the exception. Work that must commit on its own belongs in its own"
                    + " top-level mutation field.",
                "none")
            .addJavadoc("Warns, once per calling method, that a scope a consumer's code opened joined the open\n"
                + "transaction instead of taking a savepoint, naming the running mutation field when there is\n"
                + "one. Graphitron's own fetchers never reach this: their scopes are marked.\n")
            .build();
    }

    /** {@code public static void markFieldFailed(DataFetchingEnvironment env, Throwable thrown)}. */
    static MethodSpec markFieldFailed(ClassName self) {
        var fieldScope = self.nestedClass(FIELD_SCOPE_CLASS_NAME);
        return MethodSpec.methodBuilder("markFieldFailed")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(Throwable.class, "thrown")
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
            .beginControlFlow("if (thrown != null)")
            .addStatement("field.handled.add(thrown)")
            .endControlFlow()
            .endControlFlow()
            .addJavadoc("Marks the running top-level mutation field failed, so its transaction rolls back\n"
                + "when it settles. {@code ErrorRouter} calls this for every exception an emitted fetcher\n"
                + "hands it, whether redacted, surfaced as a client error or claimed into an {@code @error}\n"
                + "payload. Lenient by design: a no-op without a carrier (the escape hatch), without an\n"
                + "active field (queries), or for a path outside the active field. Thread-safe.\n"
                + "@param env the failing field's environment, or {@code null}\n"
                + "@param thrown the exception the router handled, kept so settle can tell whether a nested\n"
                + "failure was caught by the consumer's code or propagated out of the field\n")
            .build();
    }

    /** {@code public static void markFieldClaimed(DataFetchingEnvironment env, Throwable claimed)}. */
    static MethodSpec markFieldClaimed(ClassName self) {
        var fieldScope = self.nestedClass(FIELD_SCOPE_CLASS_NAME);
        return MethodSpec.methodBuilder("markFieldClaimed")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(Throwable.class, "claimed")
            .addStatement("$T field = runningField(env)", fieldScope)
            .beginControlFlow("if (field != null && claimed != null)")
            .addStatement("field.claimed.compareAndSet(null, claimed)")
            .endControlFlow()
            .addJavadoc("Records the link of the cause chain an {@code @error} channel claimed the running\n"
                + "top-level mutation field's failure as; the first claim wins. {@code ErrorRouter.claim}\n"
                + "calls this beside {@link #markFieldFailed}, and nothing else does, so a redacted or\n"
                + "surfaced failure is never recorded as claimed. Lenient as {@code markFieldFailed} is.\n"
                + "@param env the failing field's environment, or {@code null}\n"
                + "@param claimed the matched link, which the payload's {@code @error} type reads\n")
            .build();
    }

    /** {@code public static void markFieldRefused(DataFetchingEnvironment env, List<?> errors)}. */
    static MethodSpec markFieldRefused(ClassName self) {
        var fieldScope = self.nestedClass(FIELD_SCOPE_CLASS_NAME);
        return MethodSpec.methodBuilder("markFieldRefused")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(ParameterizedTypeName.get(LIST, com.palantir.javapoet.WildcardTypeName.subtypeOf(Object.class)),
                "errors")
            .addStatement("$T field = runningField(env)", fieldScope)
            .beginControlFlow("if (field == null)")
            .addStatement("return")
            .endControlFlow()
            .addStatement("field.failed.set(true)")
            .beginControlFlow("if (errors != null && !errors.isEmpty() && errors.get(0) != null)")
            .addStatement("field.claimed.compareAndSet(null, errors.get(0))")
            .endControlFlow()
            .addJavadoc("Marks the running top-level mutation field failed because a {@code VALIDATION} pre-step\n"
                + "refused its input, and records the first error the refusal produced as what the failure\n"
                + "was claimed as, first claim winning as in {@link #markFieldClaimed}. The field's payload\n"
                + "still carries the errors; the refusal only rolls the field back and, where it has\n"
                + "{@code @outbox(on: ERROR)} messages, writes them. Lenient as {@link #markFieldFailed} is.\n"
                + "@param env the refused field's environment, or {@code null}\n"
                + "@param errors the errors the refusal answers with, in the order the payload lists them\n")
            .build();
    }

    /**
     * {@code private static FieldScope runningField(DataFetchingEnvironment env)}: the running
     * top-level mutation field {@code env} is at or under, or {@code null}.
     */
    static MethodSpec runningField(ClassName self) {
        var fieldScope = self.nestedClass(FIELD_SCOPE_CLASS_NAME);
        return MethodSpec.methodBuilder("runningField")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(fieldScope)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .beginControlFlow("if (env == null || env.getExecutionStepInfo() == null)")
            .addStatement("return null")
            .endControlFlow()
            .addStatement("$T context = env.getGraphQlContext()", GRAPHQL_CONTEXT)
            .addStatement("$T carrier = context == null ? null : context.get($T.class)", self, self)
            .beginControlFlow("if (carrier == null)")
            .addStatement("return null")
            .endControlFlow()
            .addStatement("$T field = carrier.activeField", fieldScope)
            .beginControlFlow("if (field == null)")
            .addStatement("return null")
            .endControlFlow()
            .addStatement("$T path = env.getExecutionStepInfo().getPath().toList()", OBJECT_LIST)
            .addStatement("return path.size() >= field.path.size() && path.subList(0, field.path.size())"
                + ".equals(field.path) ? field : null")
            .build();
    }

    /**
     * {@code public static void onFieldRollback(DataFetchingEnvironment env, List<String> events,
     * FieldRollback write)}: a fetcher's registration of its failure messages.
     */
    static MethodSpec onFieldRollback(ClassName self) {
        var fieldScope = self.nestedClass(FIELD_SCOPE_CLASS_NAME);
        var stringList = ParameterizedTypeName.get(LIST, ClassName.get(String.class));
        return MethodSpec.methodBuilder("onFieldRollback")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(DATA_FETCHING_ENVIRONMENT, "env")
            .addParameter(stringList, "events")
            .addParameter(self.nestedClass(FIELD_ROLLBACK_CLASS_NAME), "write")
            .addStatement("$T context = env.getGraphQlContext()", GRAPHQL_CONTEXT)
            .addStatement("$T carrier = context == null ? null : context.get($T.class)", self, self)
            .addStatement("$T path = env.getExecutionStepInfo() == null ? $T.of() : env.getExecutionStepInfo().getPath().toList()",
                OBJECT_LIST, LIST)
            .beginControlFlow("if (carrier == null)")
            .addComment("The escape hatch: the caller owns the connection and its transaction, so there is no")
            .addComment("point after the rollback where graphitron holds a connection to write on.")
            .addStatement("LOGGER.warn($S, path, events)",
                "Mutation field {} runs on a caller-supplied DSLContext, where graphitron holds no connection"
                    + " after its transaction rolls back: its @outbox(on: ERROR) messages {} are not written"
                    + " if it fails.")
            .addStatement("return")
            .endControlFlow()
            .addStatement("$T field = carrier.activeField", fieldScope)
            .beginControlFlow("if (field == null || !field.path.equals(path))")
            .addStatement("LOGGER.warn($S, path, events)",
                "Mutation field {} is not running as a top-level mutation field: its @outbox(on: ERROR)"
                    + " messages {} are not written if it fails.")
            .addStatement("return")
            .endControlFlow()
            .beginControlFlow("synchronized (field)")
            .beginControlFlow("if (field.onRollback == null)")
            .addStatement("field.onRollback = write")
            .addStatement("field.rollbackEvents = $T.copyOf(events)", LIST)
            .endControlFlow()
            .endControlFlow()
            .addJavadoc("Registers what the running top-level mutation field writes after its transaction rolled\n"
                + "back, its {@code @outbox(on: ERROR)} messages. The field's fetcher calls this once per call,\n"
                + "before it acquires a connection, so a failure to acquire or to mount is covered too; the\n"
                + "carrier runs {@code write} when the field settles, after a rollback and never after a\n"
                + "commit. Without a carrier (the escape hatch) a warning naming the field is logged and\n"
                + "nothing is registered.\n"
                + "@param env the field's environment\n"
                + "@param events the events {@code write} writes, named in the log when they cannot be\n"
                + "@param write the field's failure messages\n")
            .build();
    }

    /**
     * {@code private void afterRollback(...)}: runs a field's failure messages on the connection
     * what happened to its own leaves, or logs their loss.
     *
     * @param acquireExpression the acquisition of a fresh connection for key {@code k}, as
     *                          {@code entryFor} spells it
     */
    static MethodSpec afterRollback(ClassName self, TypeName keyType, boolean multiTenant, boolean mounts) {
        var fieldScope = self.nestedClass(FIELD_SCOPE_CLASS_NAME);
        var stringList = ParameterizedTypeName.get(LIST, ClassName.get(String.class));
        var throwableList = ParameterizedTypeName.get(LIST, ClassName.get(Throwable.class));
        var builder = MethodSpec.methodBuilder("afterRollback")
            .addModifiers(Modifier.PRIVATE)
            .addParameter(fieldScope, "field")
            .addParameter(self.nestedClass(FIELD_ROLLBACK_CLASS_NAME), "write")
            .addParameter(stringList, "events")
            .addParameter(keyType, "own")
            .addParameter(boolean.class, "rollbackFailed")
            .addParameter(throwableList, "lost")
            .addStatement("$T claimed = field.claimed.get()", Object.class)
            .beginControlFlow("try")
            .beginControlFlow("if (own == null)")
            .addStatement("$T mount = null", Throwable.class);
        if (mounts) {
            builder.addStatement("mount = firstAcquisitionFailure.get()");
        }
        builder.beginControlFlow("synchronized (field.handled)")
            .beginControlFlow("for ($T handledThrowable : field.handled)", Throwable.class)
            .beginControlFlow("if (mount == null && isAcquisitionFailure(handledThrowable))")
            .addStatement("mount = handledThrowable")
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .beginControlFlow("if (mount != null)")
            .addComment("Every owned statement runs on a mounted connection, and the mount may be what sets")
            .addComment("the role or the row-level security context: without one there is nothing they may be")
            .addComment("written on.")
            .addStatement("dropped(field, events, $S, mount)", "the connection source failed to lease a connection")
            .nextControlFlow("else")
            .addStatement("dropped(field, events, $S, null)", "the field acquired no connection")
            .endControlFlow()
            .addStatement("return")
            .endControlFlow();
        if (multiTenant) {
            builder.beginControlFlow("if (own.tenant().isPresent() && timedOutTenants.contains(own.tenant().get()))")
                .addComment("The transaction cost the connection: it was aborted at settle, so the messages go on")
                .addComment("a fresh one.")
                .addStatement("writeOnFreshConnection(field, write, events, own, claimed, null)")
                .addStatement("return")
                .endControlFlow();
        }
        builder.beginControlFlow("if (brokenKeys.contains(own))")
            .addStatement("dropped(field, events, $S, null)",
                "a transaction opened inside the field was left open on its connection")
            .addStatement("return")
            .endControlFlow()
            .addStatement("$T entry = entries.get(own)", self.nestedClass("Entry"))
            .beginControlFlow("if (rollbackFailed)")
            .addStatement("$T<$T> held = new $T<>(lost)", LIST, Throwable.class, ARRAY_LIST)
            .beginControlFlow("synchronized (field.handled)")
            .addStatement("held.addAll(field.handled)")
            .endControlFlow()
            .addStatement("$T dialect = entry != null ? entry.pinned.dialect() : runtime.dialect()", SQL_DIALECT)
            .beginControlFlow("for ($T heldThrowable : held)", Throwable.class)
            .beginControlFlow("if (endedOverTransaction(heldThrowable, dialect))")
            .addComment("The transaction cost the connection: the database ended the session over it. The old")
            .addComment("connection is dead and is evicted at release.")
            .addStatement("writeOnFreshConnection(field, write, events, own, claimed, heldThrowable)")
            .addStatement("return")
            .endControlFlow()
            .endControlFlow()
            .addComment("Nothing held names the transaction: the connection failed for a reason outside it, and a")
            .addComment("COMMIT whose answer was lost may have committed, so nothing is written.")
            .addStatement("dropped(field, events, $S, lost.isEmpty() ? null : lost.get(0))",
                "its connection failed for a reason outside the transaction")
            .addStatement("return")
            .endControlFlow()
            .beginControlFlow("if (entry == null)")
            .addStatement("dropped(field, events, $S, null)", "its connection was already released")
            .addStatement("return")
            .endControlFlow()
            .addComment("The connection survived the rollback, its session still mounted.")
            .addStatement("write.write(entry.dsl, claimed)")
            .nextControlFlow("catch ($T e)", RuntimeException.class)
            .addStatement("LOGGER.error($S, events, field.path, e)",
                "Writing the @outbox(on: ERROR) messages {} of mutation field {} failed after its transaction"
                    + " rolled back; the response is unchanged.")
            .endControlFlow()
            .addJavadoc("Runs the failure messages of {@code field}, whose transaction has rolled back. The\n"
                + "connection is chosen by what the rollback left of the field's own, the first it opened:\n"
                + "that connection when it survived; a fresh one when the transaction cost it (the tenant\n"
                + "timed out, or the rollback failed and an exception held names the transaction as why\n"
                + "the session ended); none otherwise, and the loss is logged. A failure writing them is\n"
                + "logged and changes nothing in the response, since the field has already failed.\n");
        return builder.build();
    }

    /** {@code private static void dropped(FieldScope field, List<String> events, String why, Throwable cause)}. */
    static MethodSpec dropped(ClassName self) {
        return MethodSpec.methodBuilder("dropped")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(self.nestedClass(FIELD_SCOPE_CLASS_NAME), "field")
            .addParameter(ParameterizedTypeName.get(LIST, ClassName.get(String.class)), "events")
            .addParameter(String.class, "why")
            .addParameter(Throwable.class, "cause")
            .addStatement("LOGGER.warn($S, events, field.path, why, cause)",
                "The @outbox(on: ERROR) messages {} of mutation field {} were not written: {}.")
            .addJavadoc("Logs that a field's failure messages were not written, naming the field's path, the\n"
                + "events and why, with the cause when there is one.\n")
            .build();
    }

    /**
     * {@code private void writeOnFreshConnection(...)}: acquires and mounts a connection for the
     * field's key outside {@code entryFor}, bounded by {@code outboxAcquisitionTimeout}, writes the
     * messages on it and releases it at once.
     */
    static MethodSpec writeOnFreshConnection(ClassName self, ClassName provider, ClassName pinnedConnection,
                                             TypeName keyType, String acquireExpression, boolean handled) {
        var fieldScope = self.nestedClass(FIELD_SCOPE_CLASS_NAME);
        var future = ParameterizedTypeName.get(COMPLETABLE_FUTURE, pinnedConnection);
        var timeUnit = ClassName.get("java.util.concurrent", "TimeUnit");
        var atomicLong = ClassName.get("java.util.concurrent.atomic", "AtomicLong");
        var builder = MethodSpec.methodBuilder("writeOnFreshConnection")
            .addModifiers(Modifier.PRIVATE)
            .addParameter(fieldScope, "field")
            .addParameter(self.nestedClass(FIELD_ROLLBACK_CLASS_NAME), "write")
            .addParameter(ParameterizedTypeName.get(LIST, ClassName.get(String.class)), "events")
            .addParameter(keyType, "k")
            .addParameter(Object.class, "claimed")
            .addParameter(Throwable.class, "ended")
            .addStatement("$T bound = runtime.outboxAcquisitionTimeout()", ClassName.get("java.time", "Duration"))
            .addStatement("$T pin = new $T<>()", future, COMPLETABLE_FUTURE)
            .addStatement("long queuedAt = System.nanoTime()")
            .addStatement("$T startedAt = new $T()", atomicLong, atomicLong)
            .beginControlFlow("try")
            .addCode("runtime.outboxExecutor().execute(() -> {\n$>")
            .addStatement("startedAt.set(System.nanoTime())")
            .beginControlFlow("try")
            .addStatement("$T pinned = $L", pinnedConnection, acquireExpression)
            .beginControlFlow("if (!pin.complete(pinned))")
            .addComment("The wait was abandoned: a connection that arrives late is evicted, never used.")
            .addStatement("pinned.abort()")
            .endControlFlow()
            .nextControlFlow("catch ($T t)", Throwable.class)
            .addStatement("pin.completeExceptionally(t)")
            .endControlFlow()
            .addCode("$<});\n")
            .nextControlFlow("catch ($T e)", RuntimeException.class)
            .addStatement("dropped(field, events, $S, e)", "the executor refused the acquisition of a fresh connection")
            .addStatement("return")
            .endControlFlow()
            .addStatement("$T pinned", pinnedConnection)
            .beginControlFlow("try")
            .addStatement("pinned = pin.get(bound.toNanos(), $T.NANOSECONDS)", timeUnit)
            .nextControlFlow("catch ($T e)", ClassName.get("java.util.concurrent", "TimeoutException"))
            .addStatement("long now = System.nanoTime()")
            .addStatement("long started = startedAt.get()")
            .addStatement("long queuedMillis = (started == 0 ? now - queuedAt : started - queuedAt) / 1_000_000")
            .addStatement("long acquiringMillis = started == 0 ? 0 : (now - started) / 1_000_000")
            .beginControlFlow("if (!pin.completeExceptionally(e))")
            .addComment("It arrived as the wait gave up: evict it all the same.")
            .addStatement("pin.join().abort()")
            .endControlFlow()
            .addStatement("dropped(field, events, $S + bound.toMillis() + $S + queuedMillis + $S + acquiringMillis"
                + " + $S, ended)",
                "no fresh connection within outboxAcquisitionTimeout (", " ms: ",
                " ms queued for the executor, ", " ms acquiring)")
            .addStatement("return")
            .nextControlFlow("catch ($T e)", ClassName.get("java.util.concurrent", "ExecutionException"))
            .addStatement("$T failure = e.getCause()", Throwable.class)
            .addStatement("dropped(field, events, isAcquisitionFailure(failure) ? $S : $S, failure)",
                "the connection source failed to lease a fresh connection", "no fresh connection could be acquired")
            .addStatement("return")
            .nextControlFlow("catch ($T e)", InterruptedException.class)
            .addStatement("Thread.currentThread().interrupt()")
            .beginControlFlow("if (!pin.completeExceptionally(e))")
            .addStatement("pin.join().abort()")
            .endControlFlow()
            .addStatement("dropped(field, events, $S, e)", "interrupted while acquiring a fresh connection")
            .addStatement("return")
            .endControlFlow()
            .beginControlFlow("try")
            .addStatement("$T dsl = $T.using(pinned.connection(), pinned.dialect(), pinned.settings())",
                DSL_CONTEXT, ClassName.get("org.jooq.impl", "DSL"))
            .addStatement("dsl.configuration().set(new $T(pinned.connection(), commitPolicy, this::warnConsumerJoin))",
                provider);
        if (handled) {
            builder.beginControlFlow("if (pinned.handle() != null)")
                .addStatement("dsl.configuration().data($S, pinned.handle())",
                    no.sikt.graphitron.emit.SessionIdentityRows.HANDLE_DATA_KEY)
                .endControlFlow();
        }
        return builder
            .addStatement("write.write(dsl, claimed)")
            .nextControlFlow("finally")
            .addStatement("pinned.release()")
            .endControlFlow()
            .addJavadoc("Writes a field's failure messages on a fresh connection for {@code k}, acquired and\n"
                + "mounted as {@code entryFor} mints one but outside it: no entry is registered, so the\n"
                + "refusal of a timed-out tenant there stays as it is, and the connection is released as\n"
                + "soon as the messages are written. One attempt, on the runtime's outbox executor, waited\n"
                + "on for at most {@code outboxAcquisitionTimeout}, so settle never stalls the next field\n"
                + "longer than that; past it the attempt is abandoned, a late connection evicted, and the\n"
                + "loss logged with the time spent queued and the time spent acquiring. A failed mount\n"
                + "writes nothing.\n"
                + "@param ended the exception that named the transaction as why the old session ended, or\n"
                + "{@code null} for a timed-out tenant\n")
            .build();
    }

    /** {@code public static boolean endedOverTransaction(Throwable thrown, SQLDialect dialect)}. */
    static MethodSpec endedOverTransaction() {
        var sqlException = SQL_EXCEPTION;
        return MethodSpec.methodBuilder("endedOverTransaction")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter(Throwable.class, "thrown")
            .addParameter(SQL_DIALECT, "dialect")
            .beginControlFlow("for ($T t = thrown; t != null; t = t.getCause())", Throwable.class)
            .beginControlFlow("if (!(t instanceof $T sql))", sqlException)
            .addStatement("continue")
            .endControlFlow()
            .beginControlFlow("for ($T next = sql; next != null; next = next.getNextException())", sqlException)
            .addStatement("$T state = next.getSQLState()", String.class)
            .beginControlFlow("if ($S.equals(state) || $S.equals(state))", "25P03", "25P04")
            .addComment("PostgreSQL ended the session over its transaction: idle_in_transaction_session_timeout")
            .addComment("(25P03) or transaction_timeout (25P04).")
            .addStatement("return true")
            .endControlFlow()
            .addComment("By name: the open-source jOOQ edition the generated code may compile against has no")
            .addComment("Oracle constant.")
            .beginControlFlow("if (dialect != null && $S.equals(dialect.family().name()))", "ORACLE")
            .addStatement("int code = next.getErrorCode()")
            .beginControlFlow("if (code == 28 || (code >= 2392 && code <= 2399 && code != 2398))")
            .addComment("Oracle killed the session (ORA-00028) or a resource limit ended it (ORA-02392 to")
            .addComment("ORA-02399, the CPU, I/O, idle, private SGA and connect time limits).")
            .addStatement("return true")
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .addStatement("return false")
            .addJavadoc("Whether {@code thrown}'s cause chain, or a {@code SQLException}'s chained exceptions, names\n"
                + "a transaction as why the database ended its session, which is what cost a mutation field its\n"
                + "connection when the rollback after it failed: {@code 25P03} and {@code 25P04} on\n"
                + "PostgreSQL, a killed session or a resource limit on Oracle. Anything else, a\n"
                + "connection-class error, an I/O failure without a state, a server shutdown, is outside the\n"
                + "transaction, and so is anything unrecognised, since writing nothing is the safer error.\n"
                + "Read beside {@link #isAcquisitionFailure} when a field with failure messages settles.\n"
                + "@param thrown an exception settle holds\n"
                + "@param dialect the dialect of the connection it came from\n")
            .build();
    }

    /** The {@code brokenKeys} refusal at the top of {@code entryFor}. */
    static List<String> brokenKeyRefusalMessage() {
        return List.of("Key '", "' was left with an open transaction by an earlier mutation field in this"
            + " operation; its connection is never reused.");
    }
}
