package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Emits {@code GraphitronTransactionProvider}, the custom jOOQ
 * {@link org.jooq.TransactionProvider} wrapped around one pinned connection, into the
 * consumer's {@code <outputPackage>.schema} package. This is the one seam every transaction boundary
 * routes through.
 *
 * <p>Emitted (not shipped as a graphitron artifact), so bodies depend only on the JDK and jOOQ;
 * valid Java 17.
 *
 * <h2>Why custom rather than jOOQ's {@code DefaultTransactionProvider}</h2>
 * {@code DefaultTransactionProvider.begin/commit/rollback} are {@code public final}, so a subclass
 * cannot suppress a commit. The commit-policy axis needs exactly that: under
 * {@code CommitPolicy.ROLLBACK_ONLY} (the rollback-everything dev mode) the top-level
 * {@code commit()} must roll back instead. A {@code TransactionListener} cannot fill the role
 * either: listeners observe boundaries but cannot change the outcome. So the provider is
 * reimplemented from scratch over the pinned connection.
 *
 * <h2>Commit policy is the one axis</h2>
 * The provider governs only mutation transactions: each mutation field's shipped
 * {@code dsl.transactionResult(...)} opens a writable transaction through this provider. Query
 * operations run in autocommit and never reach it. {@code CommitPolicy} is global provider
 * configuration, never site-declared: {@code COMMIT} persists a successful top-level transaction; {@code ROLLBACK_ONLY}
 * (the dev-execution mode) defers one operation transaction across field settles, savepoint-scoping each
 * field, so the generated DML two-step's post-settle payload read-back observes the uncommitted
 * write, and the whole transaction is discarded by {@code PinnedConnection#release} at operation
 * completion. A site opens a transaction to write; it does not get to choose
 * commit-versus-rollback. Session identity stays orthogonal: the provider knows nothing about
 * hooks, handles, or payloads, and identity survives settles because the mount ran in autocommit
 * as its own committed transaction, not because anything here defends it.
 *
 * <h2>Graphitron asserts the transaction mode it settles into</h2>
 * The provider runs over a connection graphitron owns for the operation, so autocommit-on is the
 * resting state it asserts after closing a top-level transaction, whatever preceded it; there is
 * no prior mode to capture and restore, because the pool's configuration is not an input to a
 * connection graphitron holds. The two surviving {@code getAutoCommit()} reads interrogate state
 * graphitron itself asserted ({@code begin}'s deferred-rollback reopen guard, and
 * {@code PinnedConnection.release}'s open-transaction detection), never a mode to preserve.
 *
 * <h2>Every close names the scope its open recorded</h2>
 * The provider keeps a stack of open scopes, each a {@code TopLevel} transaction or a
 * {@code Savepointed} one, and ties every close to its open through jOOQ's per-call
 * {@code TransactionContext}: {@code transactionResult} builds a fresh context for each call and
 * passes that same object to {@code begin}, {@code commit} and {@code rollback}. The contract:
 * <ul>
 *   <li>An open records its scope (pushes it and puts it on the context) only once every JDBC call
 *       succeeded. jOOQ follows a {@code begin} that throws with a {@code rollback} on the same
 *       context, and a rollback for a context with no scope recorded changes nothing, so a failed
 *       open (a savepoint refused inside an aborted Postgres transaction, say) never closes the
 *       scope around it.</li>
 *   <li>A close dispatches on the variant of the scope it names, not on how deeply it is nested, and refuses one
 *       that is not innermost.</li>
 *   <li>A failed commit leaves its scope open, so jOOQ's follow-up rollback closes that same scope
 *       exactly once.</li>
 * </ul>
 * A mutation field's own scope has no {@code TransactionContext}: the connection carrier opens it
 * through the package-private {@code open()} on the field's first acquisition of the connection,
 * holds the returned {@code Scope}, and ends it through {@code end(scope, failed)} when the field
 * settles. Every jOOQ transaction inside the field then nests on top as a savepoint.
 * Savepoint release is skipped where the driver does not implement it (Oracle), since an
 * unreleased marker dies with the enclosing transaction; no other release failure is hidden,
 * because a swallowed release over an aborted transaction turns into a silent rollback at the
 * enclosing commit.
 *
 * <h2>Single-connection safety</h2>
 * The provider instance is built once per pinned connection, inside the carrier entry that owns
 * the connection's one cached {@code DSLContext}, and holds that connection's only scope stack.
 * That is sound because SQL on one pinned connection runs on one thread at a
 * time ({@code RowsMethodCall} emits synchronous batch loaders; a scatter worker owns its key's
 * connection exclusively); no two transactions on the pinned connection are ever open
 * concurrently.
 */
public final class GraphitronTransactionProviderGenerator {

    public static final String CLASS_NAME = "GraphitronTransactionProvider";
    public static final String COMMIT_POLICY_ENUM_NAME = "CommitPolicy";

    private static final ClassName CONNECTION = ClassName.get("java.sql", "Connection");
    private static final ClassName SQL_EXCEPTION = ClassName.get("java.sql", "SQLException");
    private static final ClassName SAVEPOINT = ClassName.get("java.sql", "Savepoint");
    private static final ClassName SQL_FEATURE_NOT_SUPPORTED_EXCEPTION =
        ClassName.get("java.sql", "SQLFeatureNotSupportedException");
    private static final ClassName DEQUE = ClassName.get("java.util", "Deque");
    private static final ClassName ARRAY_DEQUE = ClassName.get("java.util", "ArrayDeque");
    private static final ClassName TRANSACTION_PROVIDER = ClassName.get("org.jooq", "TransactionProvider");
    private static final ClassName TRANSACTION_CONTEXT = ClassName.get("org.jooq", "TransactionContext");
    private static final ClassName DATA_ACCESS_EXCEPTION = ClassName.get("org.jooq.exception", "DataAccessException");
    private static final String SCOPE_KEY = "SCOPE_KEY";
    private static final String OWN_SCOPE_KEY = "OWN_SCOPE_KEY";
    private static final ClassName CONFIGURATION = ClassName.get("org.jooq", "Configuration");
    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");

    private GraphitronTransactionProviderGenerator() {}

    /**
     * @param outputPackage the consumer's root output package; the provider is emitted into
     *                      {@code outputPackage + ".schema"} (beside {@code GraphitronRuntime})
     */
    public static List<TypeSpec> generate(String outputPackage) {
        String schemaPackage = outputPackage + ".schema";
        var self = ClassName.get(schemaPackage, CLASS_NAME);
        var commitPolicy = self.nestedClass(COMMIT_POLICY_ENUM_NAME);
        return List.of(provider(self, commitPolicy));
    }

    private static TypeSpec provider(ClassName self, ClassName commitPolicy) {
        var scope = self.nestedClass("Scope");
        var topLevel = self.nestedClass("TopLevel");
        var savepointed = self.nestedClass("Savepointed");
        var scopeDeque = ParameterizedTypeName.get(DEQUE, scope);

        var connectionField = FieldSpec.builder(CONNECTION, "connection", Modifier.PRIVATE, Modifier.FINAL).build();
        var policyField = FieldSpec.builder(commitPolicy, "commitPolicy", Modifier.PRIVATE, Modifier.FINAL).build();
        var scopesField = FieldSpec.builder(scopeDeque, "scopes", Modifier.PRIVATE, Modifier.FINAL)
            .initializer("new $T<>()", ARRAY_DEQUE)
            .addJavadoc("The open scopes, innermost first. A scope is pushed only once its open fully\n"
                + "succeeded and removed when it closes.\n")
            .build();
        var scopeKeyField = FieldSpec.builder(Object.class, SCOPE_KEY, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("new $T()", Object.class)
            .addJavadoc("The {@code TransactionContext} data key a successful {@code begin} records its scope\n"
                + "under. jOOQ builds a fresh context, with its own data map, for every\n"
                + "{@code transactionResult} call, so the entry names exactly the scope that call opened.\n")
            .build();

        var ownScopeKeyField = FieldSpec.builder(Object.class, OWN_SCOPE_KEY, Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("new $T()", Object.class)
            .addJavadoc("The {@code Configuration} data key {@link #owned} marks a scope graphitron's own\n"
                + "fetcher opens with. jOOQ derives the configuration a {@code TransactionContext} carries\n"
                + "from the one the call was made on, so {@code begin} reads the mark off the context.\n")
            .build();

        var owned = MethodSpec.methodBuilder("owned")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(DSL_CONTEXT)
            .addParameter(DSL_CONTEXT, "dsl")
            .addStatement("$T configuration = dsl.configuration().derive()", CONFIGURATION)
            .addStatement("configuration.data($L, $T.TRUE)", OWN_SCOPE_KEY, Boolean.class)
            .addStatement("return $T.using(configuration)", DSL)
            .addJavadoc("The seam every generated fetcher opens its own transaction through: a {@code DSLContext}\n"
                + "on a copy of {@code dsl}'s configuration that marks the scopes opened on it as\n"
                + "graphitron's own. A generated fetcher's failure already reaches the router as the\n"
                + "fetcher's exception, so the provider tells it from a scope a consumer's code opened\n"
                + "by this mark. On a connection graphitron does not own the mark is ignored.\n"
                + "@param dsl the context the fetcher would otherwise open the transaction on\n"
                + "@return a context whose transactions are marked as graphitron's own\n")
            .build();

        var isOwn = MethodSpec.methodBuilder("isOwn")
            .addModifiers(Modifier.STATIC)
            .returns(boolean.class)
            .addParameter(TRANSACTION_CONTEXT, "ctx")
            .addStatement("return ctx.configuration().data($L) != null", OWN_SCOPE_KEY)
            .addJavadoc("Whether the scope {@code ctx} opens was marked by {@link #owned}.\n")
            .build();

        var canonicalConstructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(CONNECTION, "connection")
            .addParameter(commitPolicy, "commitPolicy")
            .addStatement("this.connection = connection")
            .addStatement("this.commitPolicy = commitPolicy")
            .addJavadoc("Builds a provider over the pinned {@code connection} applying {@code commitPolicy}\n"
                + "to every top-level transaction it demarcates. One instance per pinned connection,\n"
                + "constructed inside the carrier entry that owns the connection's one cached\n"
                + "{@code DSLContext}, so this instance's scope stack is the only record of open\n"
                + "transactions on that connection.\n")
            .build();

        var open = MethodSpec.methodBuilder("open")
            .returns(scope)
            .addException(SQL_EXCEPTION)
            .addStatement("$T opened", scope)
            .beginControlFlow("if (scopes.isEmpty() && commitPolicy == $T.ROLLBACK_ONLY)", commitPolicy)
            .addComment("Deferred-rollback dev mode: open the operation transaction once and keep")
            .addComment("it open across field settles, so post-settle read-backs observe the writes; each")
            .addComment("field boundary is a savepoint. PinnedConnection.release discards the whole")
            .addComment("transaction and restores autocommit at operation completion. When the savepoint")
            .addComment("fails after autocommit went off, no scope is recorded and the next open reuses")
            .addComment("the transaction, as a second field does.")
            .beginControlFlow("if (connection.getAutoCommit())")
            .addStatement("connection.setAutoCommit(false)")
            .endControlFlow()
            .addStatement("opened = new $T(connection.setSavepoint())", savepointed)
            .nextControlFlow("else if (scopes.isEmpty())")
            .addComment("Top-level: a mutation field opens a writable transaction by turning autocommit off.")
            .addComment("No mode is captured: autocommit-on is the asserted resting state on an owned")
            .addComment("connection, and settle re-asserts it unconditionally.")
            .addStatement("connection.setAutoCommit(false)")
            .addStatement("opened = new $T()", topLevel)
            .nextControlFlow("else")
            .addStatement("opened = new $T(connection.setSavepoint())", savepointed)
            .endControlFlow()
            .addComment("Every JDBC call succeeded: only now does the provider's state change.")
            .addStatement("scopes.push(opened)")
            .addStatement("return opened")
            .addJavadoc("Opens one scope, all or nothing: the JDBC calls run first and the scope is pushed\n"
                + "only once they all succeeded, so a failed open leaves the stack as it was.\n"
                + "Package-private: {@code begin} records the scope on jOOQ's context, and the connection\n"
                + "carrier holds a mutation field's scope itself and hands it back to {@link #end}.\n")
            .build();

        var begin = MethodSpec.methodBuilder("begin")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .addParameter(TRANSACTION_CONTEXT, "ctx")
            .beginControlFlow("try")
            .addStatement("ctx.data($L, open())", SCOPE_KEY)
            .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
            .addStatement("throw new $T($S, e)", DATA_ACCESS_EXCEPTION, "Could not begin transaction")
            .endControlFlow()
            .build();

        var close = MethodSpec.methodBuilder("close")
            .addModifiers(Modifier.PRIVATE)
            .returns(void.class)
            .addParameter(scope, "closing")
            .addParameter(boolean.class, "failed")
            .addException(SQL_EXCEPTION)
            .beginControlFlow("if (closing instanceof $T sp && failed)", savepointed)
            .addComment("Nested, or a deferred-rollback field: discard exactly this scope's writes and")
            .addComment("keep the enclosing (or operation) transaction open.")
            .addStatement("connection.rollback(sp.savepoint())")
            .nextControlFlow("else if (closing instanceof $T sp)", savepointed)
            .addComment("Nested, or a deferred-rollback field settling: release the marker; the enclosing")
            .addComment("(or operation) transaction stays open.")
            .addStatement("releaseSavepoint(sp.savepoint())")
            .nextControlFlow("else")
            .addComment("Top-level: the commit policy decides persist-vs-discard; failed forces a rollback.")
            .addStatement("settle(failed)")
            .endControlFlow()
            .addJavadoc("Closes {@code closing}, dispatching on its variant: a savepointed scope rolls back\n"
                + "to its savepoint when {@code failed} and releases it otherwise; the top-level scope\n"
                + "settles. Leaves the stack to the caller.\n")
            .build();

        var innermost = MethodSpec.methodBuilder("requireInnermost")
            .addModifiers(Modifier.PRIVATE)
            .returns(void.class)
            .addParameter(scope, "closing")
            .addParameter(String.class, "operation")
            .beginControlFlow("if (scopes.peek() != closing)")
            .addStatement("throw new $T($S + operation + $S + scopes.size() + $S)", IllegalStateException.class,
                "Cannot ", " a transaction scope that is not the innermost open one (",
                " open); scopes close in the reverse order they opened")
            .endControlFlow()
            .addJavadoc("Refuses to close a scope that is not the innermost open one, leaving the stack as\n"
                + "it is: jOOQ's {@code transactionResult} cannot produce that order, so it is an\n"
                + "ordering bug to stop at its site rather than a scope closed wrongly fields later.\n")
            .build();

        var commit = MethodSpec.methodBuilder("commit")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .addParameter(TRANSACTION_CONTEXT, "ctx")
            .addStatement("$T closing = ($T) ctx.data($L)", scope, scope, SCOPE_KEY)
            .beginControlFlow("if (closing == null)")
            .addStatement("throw new $T($S)", IllegalStateException.class,
                "Cannot commit a transaction scope that never opened")
            .endControlFlow()
            .addStatement("requireInnermost(closing, $S)", "commit")
            .addComment("The scope closes (pop, and the context entry cleared) only once its commit succeeded:")
            .addComment("when anything here throws, the scope stays open, so the rollback jOOQ issues next")
            .addComment("for the same context closes it exactly once.")
            .beginControlFlow("try")
            .addStatement("close(closing, false)")
            .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
            .addStatement("throw new $T($S, e)", DATA_ACCESS_EXCEPTION, "Could not commit transaction")
            .endControlFlow()
            .addStatement("scopes.pop()")
            .addStatement("ctx.data().remove($L)", SCOPE_KEY)
            .build();

        var releaseSavepoint = MethodSpec.methodBuilder("releaseSavepoint")
            .addModifiers(Modifier.PRIVATE)
            .returns(void.class)
            .addParameter(SAVEPOINT, "savepoint")
            .addException(SQL_EXCEPTION)
            .beginControlFlow("try")
            .addStatement("connection.releaseSavepoint(savepoint)")
            .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
            .beginControlFlow("if (e instanceof $T || (e.getSQLState() != null && e.getSQLState().startsWith($S)))",
                SQL_FEATURE_NOT_SUPPORTED_EXCEPTION, "0A")
            .addComment("The driver does not implement release (Oracle: ORA-17023). Releasing only frees the")
            .addComment("marker; an unreleased savepoint dies with the enclosing transaction anyway.")
            .addStatement("return")
            .endControlFlow()
            .addStatement("throw e")
            .endControlFlow()
            .addJavadoc("Releases {@code savepoint}, skipping the release where the driver does not implement\n"
                + "it ({@link $T}, or SQLState class {@code 0A}). Every other failure is rethrown: over\n"
                + "an aborted transaction a swallowed release would let the enclosing commit report\n"
                + "success while the database silently rolls back.\n", SQL_FEATURE_NOT_SUPPORTED_EXCEPTION)
            .build();

        var rollback = MethodSpec.methodBuilder("rollback")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .addParameter(TRANSACTION_CONTEXT, "ctx")
            .addStatement("$T closing = ($T) ctx.data($L)", scope, scope, SCOPE_KEY)
            .beginControlFlow("if (closing == null)")
            .addComment("The open failed and recorded nothing: jOOQ's follow-up rollback for it has no")
            .addComment("scope to close, so the enclosing scope and the connection stay untouched.")
            .addStatement("return")
            .endControlFlow()
            .addStatement("requireInnermost(closing, $S)", "roll back")
            .addComment("Removed whether or not the JDBC call succeeds: nothing calls rollback for this scope")
            .addComment("again, and a failed top-level rollback is left to PinnedConnection.release, whose")
            .addComment("open-transaction detection covers it.")
            .addStatement("scopes.pop()")
            .addStatement("ctx.data().remove($L)", SCOPE_KEY)
            .beginControlFlow("try")
            .addStatement("close(closing, true)")
            .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
            .addStatement("throw new $T($S, e)", DATA_ACCESS_EXCEPTION, "Could not roll back transaction")
            .endControlFlow()
            .build();

        var end = MethodSpec.methodBuilder("end")
            .returns(void.class)
            .addParameter(scope, "closing")
            .addParameter(boolean.class, "failed")
            .addException(SQL_EXCEPTION)
            .addStatement("requireInnermost(closing, failed ? $S : $S)", "roll back", "commit")
            .beginControlFlow("if (failed)")
            .addComment("As rollback: removed first, so a failed JDBC call leaves nothing to close twice;")
            .addComment("a failed top-level rollback is left to PinnedConnection.release.")
            .addStatement("scopes.pop()")
            .addStatement("close(closing, true)")
            .nextControlFlow("else")
            .addComment("As commit: removed only once the close succeeded, so a failed commit leaves the")
            .addComment("scope open for the caller's end(closing, true).")
            .addStatement("close(closing, false)")
            .addStatement("scopes.pop()")
            .endControlFlow()
            .addJavadoc("Ends {@code closing} without a {@code TransactionContext}: the connection carrier's\n"
                + "close for a mutation field's scope, which it opened through {@link #open} and holds\n"
                + "itself. Same ordering as the jOOQ pair: a successful end pops only once its commit or\n"
                + "release succeeded, so a throw leaves the scope open for {@code end(closing, true)}; a\n"
                + "failed end pops first. Refuses a scope that is not innermost with\n"
                + "{@link IllegalStateException}, leaving the stack as it is.\n"
                + "@throws SQLException the commit, release or rollback failure, unwrapped\n")
            .build();

        var settle = MethodSpec.methodBuilder("settle")
            .addModifiers(Modifier.PRIVATE)
            .returns(void.class)
            .addParameter(boolean.class, "failed")
            .addException(SQL_EXCEPTION)
            .beginControlFlow("if (failed)")
            .addStatement("connection.rollback()")
            .nextControlFlow("else")
            .addStatement("connection.commit()")
            .endControlFlow()
            .addComment("Graphitron asserts autocommit on a connection it owns: after a top-level")
            .addComment("transaction closes, the resting state is re-asserted unconditionally, whatever")
            .addComment("the pool lent. The mode is never captured or restored.")
            .addStatement("connection.setAutoCommit(true)")
            .addJavadoc("Closes the top-level transaction under the {@code COMMIT} policy: rolls back when\n"
                + "the transaction {@code failed}, otherwise commits, then asserts autocommit (the\n"
                + "resting state graphitron holds an owned connection in). Unreachable under\n"
                + "{@link CommitPolicy#ROLLBACK_ONLY}, whose field boundaries are savepoint-scoped and\n"
                + "whose one real transaction is discarded at release.\n")
            .build();

        var commitPolicyEnum = TypeSpec.enumBuilder(COMMIT_POLICY_ENUM_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addEnumConstant("COMMIT")
            .addEnumConstant("ROLLBACK_ONLY")
            .addJavadoc("Global commit policy for every top-level transaction. {@code COMMIT} persists a\n"
                + "successful transaction; {@code ROLLBACK_ONLY} is the rollback-everything dev mode\n"
                + "(execute a mutation, observe its result, persist nothing): the operation transaction is\n"
                + "opened once and deferred across field settles (each field boundary is a savepoint), so\n"
                + "post-settle payload read-backs observe the uncommitted writes, and the whole transaction\n"
                + "is discarded when the pinned connection is released. Provider configuration, never\n"
                + "chosen per site.\n")
            .build();

        var scopeType = TypeSpec.interfaceBuilder("Scope")
            .addModifiers(Modifier.STATIC, Modifier.SEALED)
            .addJavadoc("One transaction this provider demarcates: the top-level transaction or a savepoint\n"
                + "nested inside it. Compared by identity; both arms live in this compilation unit, so the\n"
                + "sealed interface needs no {@code permits} clause.\n")
            .build();
        var topLevelType = TypeSpec.classBuilder("TopLevel")
            .addModifiers(Modifier.STATIC, Modifier.FINAL)
            .addSuperinterface(scope)
            .addJavadoc("A top-level transaction under {@link CommitPolicy#COMMIT}: opened by turning\n"
                + "autocommit off, closed by {@code settle}.\n")
            .build();
        var savepointedType = TypeSpec.classBuilder("Savepointed")
            .addModifiers(Modifier.STATIC, Modifier.FINAL)
            .addSuperinterface(scope)
            .addJavadoc("Every other scope (a nested transaction, or a field under\n"
                + "{@link CommitPolicy#ROLLBACK_ONLY}): closed by releasing or rolling back to its savepoint.\n")
            .addField(FieldSpec.builder(SAVEPOINT, "savepoint", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addParameter(SAVEPOINT, "savepoint")
                .addStatement("this.savepoint = savepoint")
                .build())
            .addMethod(MethodSpec.methodBuilder("savepoint")
                .returns(SAVEPOINT)
                .addStatement("return savepoint")
                .build())
            .build();

        return TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(TRANSACTION_PROVIDER)
            .addJavadoc("Custom jOOQ {@link $T} over one pinned connection: the one seam every\n"
                + "mutation transaction boundary routes through. Reimplemented from scratch (jOOQ's\n"
                + "{@code DefaultTransactionProvider} is {@code final} on commit) so\n"
                + "{@link CommitPolicy#ROLLBACK_ONLY} can suppress a commit. See\n"
                + "{@code GraphitronTransactionProviderGenerator} for the full contract.\n", TRANSACTION_PROVIDER)
            .addType(commitPolicyEnum)
            .addType(scopeType)
            .addType(topLevelType)
            .addType(savepointedType)
            .addField(scopeKeyField)
            .addField(ownScopeKeyField)
            .addField(connectionField)
            .addField(policyField)
            .addField(scopesField)
            .addMethod(canonicalConstructor)
            .addMethod(owned)
            .addMethod(isOwn)
            .addMethod(begin)
            .addMethod(commit)
            .addMethod(rollback)
            .addMethod(open)
            .addMethod(end)
            .addMethod(close)
            .addMethod(innermost)
            .addMethod(releaseSavepoint)
            .addMethod(settle)
            .build();
    }
}
