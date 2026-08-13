package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;

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
 * <h2>Single-connection safety</h2>
 * The provider instance is built once per pinned connection, inside the carrier entry that owns
 * the connection's one cached {@code DSLContext}, and holds that connection's only nesting depth
 * and savepoint stack. That is sound because SQL on one pinned connection runs on one thread at a
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
    private static final ClassName DEQUE = ClassName.get("java.util", "Deque");
    private static final ClassName ARRAY_DEQUE = ClassName.get("java.util", "ArrayDeque");
    private static final ClassName TRANSACTION_PROVIDER = ClassName.get("org.jooq", "TransactionProvider");
    private static final ClassName TRANSACTION_CONTEXT = ClassName.get("org.jooq", "TransactionContext");
    private static final ClassName DATA_ACCESS_EXCEPTION = ClassName.get("org.jooq.exception", "DataAccessException");

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
        var savepointDeque = ParameterizedTypeName.get(DEQUE, SAVEPOINT);

        var connectionField = FieldSpec.builder(CONNECTION, "connection", Modifier.PRIVATE, Modifier.FINAL).build();
        var policyField = FieldSpec.builder(commitPolicy, "commitPolicy", Modifier.PRIVATE, Modifier.FINAL).build();
        var savepointsField = FieldSpec.builder(savepointDeque, "savepoints", Modifier.PRIVATE, Modifier.FINAL)
            .initializer("new $T<>()", ARRAY_DEQUE)
            .build();
        var depthField = FieldSpec.builder(int.class, "depth", Modifier.PRIVATE).build();

        var canonicalConstructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(CONNECTION, "connection")
            .addParameter(commitPolicy, "commitPolicy")
            .addStatement("this.connection = connection")
            .addStatement("this.commitPolicy = commitPolicy")
            .addJavadoc("Builds a provider over the pinned {@code connection} applying {@code commitPolicy}\n"
                + "to every top-level transaction it demarcates. One instance per pinned connection,\n"
                + "constructed inside the carrier entry that owns the connection's one cached\n"
                + "{@code DSLContext}, so this instance's {@code depth} is the only nesting counter on\n"
                + "that connection.\n")
            .build();

        var begin = MethodSpec.methodBuilder("begin")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .addParameter(TRANSACTION_CONTEXT, "ctx")
            .beginControlFlow("try")
            .beginControlFlow("if (depth == 0 && commitPolicy == $T.ROLLBACK_ONLY)", commitPolicy)
            .addComment("Deferred-rollback dev mode: open the operation transaction once and keep")
            .addComment("it open across field settles, so post-settle read-backs observe the writes; each")
            .addComment("field boundary is a savepoint. PinnedConnection.release discards the whole")
            .addComment("transaction and restores autocommit at operation completion.")
            .beginControlFlow("if (connection.getAutoCommit())")
            .addStatement("connection.setAutoCommit(false)")
            .endControlFlow()
            .addStatement("savepoints.push(connection.setSavepoint())")
            .nextControlFlow("else if (depth == 0)")
            .addComment("Top-level: a mutation field opens a writable transaction by turning autocommit off.")
            .addComment("No mode is captured: autocommit-on is the asserted resting state on an owned")
            .addComment("connection, and settle re-asserts it unconditionally.")
            .addStatement("connection.setAutoCommit(false)")
            .nextControlFlow("else")
            .addStatement("savepoints.push(connection.setSavepoint())")
            .endControlFlow()
            .addStatement("depth++")
            .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
            .addStatement("throw new $T($S, e)", DATA_ACCESS_EXCEPTION, "Could not begin transaction")
            .endControlFlow()
            .build();

        var commit = MethodSpec.methodBuilder("commit")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .addParameter(TRANSACTION_CONTEXT, "ctx")
            .beginControlFlow("try")
            .addStatement("depth--")
            .beginControlFlow("if (depth == 0 && commitPolicy == $T.ROLLBACK_ONLY)", commitPolicy)
            .addComment("Deferred-rollback: the field settles by releasing its savepoint; the operation")
            .addComment("transaction stays open so later read-backs observe the writes, and nothing settles")
            .addComment("until release discards the whole transaction.")
            .addStatement("connection.releaseSavepoint(savepoints.pop())")
            .nextControlFlow("else if (depth == 0)")
            .addComment("Top-level: the commit policy decides persist-vs-discard.")
            .addStatement("settle(false)")
            .nextControlFlow("else")
            .addStatement("connection.releaseSavepoint(savepoints.pop())")
            .endControlFlow()
            .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
            .addStatement("throw new $T($S, e)", DATA_ACCESS_EXCEPTION, "Could not commit transaction")
            .endControlFlow()
            .build();

        var rollback = MethodSpec.methodBuilder("rollback")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .addParameter(TRANSACTION_CONTEXT, "ctx")
            .beginControlFlow("try")
            .addStatement("depth--")
            .beginControlFlow("if (depth == 0 && commitPolicy != $T.ROLLBACK_ONLY)", commitPolicy)
            .addComment("Top-level failure: roll the whole transaction back and restore autocommit.")
            .addComment("failed == true forces a rollback regardless of the commit policy.")
            .addStatement("settle(true)")
            .nextControlFlow("else")
            .addComment("Nested, or a deferred-rollback field: discard exactly this scope's writes and")
            .addComment("keep the enclosing (or operation) transaction open.")
            .addStatement("connection.rollback(savepoints.pop())")
            .endControlFlow()
            .nextControlFlow("catch ($T e)", SQL_EXCEPTION)
            .addStatement("throw new $T($S, e)", DATA_ACCESS_EXCEPTION, "Could not roll back transaction")
            .endControlFlow()
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

        return TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(TRANSACTION_PROVIDER)
            .addJavadoc("Custom jOOQ {@link $T} over one pinned connection: the one seam every\n"
                + "mutation transaction boundary routes through. Reimplemented from scratch (jOOQ's\n"
                + "{@code DefaultTransactionProvider} is {@code final} on commit) so\n"
                + "{@link CommitPolicy#ROLLBACK_ONLY} can suppress a commit. See\n"
                + "{@code GraphitronTransactionProviderGenerator} for the full contract.\n", TRANSACTION_PROVIDER)
            .addType(commitPolicyEnum)
            .addField(connectionField)
            .addField(policyField)
            .addField(savepointsField)
            .addField(depthField)
            .addMethod(canonicalConstructor)
            .addMethod(begin)
            .addMethod(commit)
            .addMethod(rollback)
            .addMethod(settle)
            .build();
    }
}
