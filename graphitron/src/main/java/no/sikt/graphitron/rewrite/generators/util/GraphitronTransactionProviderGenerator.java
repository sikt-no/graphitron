package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * R429 slice 2 — emits {@code GraphitronTransactionProvider}, the custom jOOQ
 * {@link org.jooq.TransactionProvider} wrapped around the single pinned connection, into the
 * consumer's {@code <outputPackage>.schema} package. This is the one seam every transaction boundary
 * routes through.
 *
 * <p>Emitted (not shipped as a graphitron artifact), so bodies depend only on the JDK
 * ({@code java.sql.*}, {@code java.util.ArrayDeque}) and jOOQ ({@code org.jooq.TransactionProvider},
 * {@code org.jooq.TransactionContext}, {@code org.jooq.exception.DataAccessException}); valid Java 17.
 *
 * <h2>Why custom rather than jOOQ's {@code DefaultTransactionProvider}</h2>
 * {@code DefaultTransactionProvider.begin/commit/rollback} are {@code public final}, so a subclass
 * cannot suppress a commit. R429's commit-policy axis needs exactly that: under
 * {@link CommitPolicy#ROLLBACK_ONLY} (R428's rollback-everything dev tool, consumed by name) the
 * top-level {@code commit()} must roll back instead. A {@code TransactionListener} cannot fill the
 * role either: listeners observe boundaries but cannot change the outcome. So the provider is
 * reimplemented from scratch over the pinned connection.
 *
 * <h2>Commit policy is the one axis</h2>
 * The provider governs only mutation transactions: each mutation field's shipped
 * {@code dsl.transactionResult(...)} opens a writable transaction through this provider. Query
 * operations run in autocommit and never reach it (R429 drops blanket read-only enforcement; the
 * targeted successor is R460). {@link CommitPolicy} is global provider configuration, never
 * site-declared: {@code COMMIT} persists a successful top-level transaction; {@code ROLLBACK_ONLY}
 * (R428's dev mode) defers one operation transaction across field settles, savepoint-scoping each
 * field, so the generated DML two-step's post-settle payload read-back observes the uncommitted
 * write, and the whole transaction is discarded by {@code PinnedConnection#release} at operation
 * completion. A site opens a transaction to write; it does not get to choose
 * commit-versus-rollback.
 *
 * <p>Session identity stays orthogonal: the provider carries an opaque settle-completion
 * {@link Runnable} it runs after each top-level settle (autocommit already restored), and knows
 * nothing about hooks, handles, or claims. The acquisition machinery wires
 * {@code PinnedConnection#afterSettle} through it, which re-fires unconfirmed session hooks so a
 * settle can never leave stale or reverted identity; savepoint settles never trigger it, and query
 * operations never construct a transaction, so the read path is untaxed.
 *
 * <h2>Single-connection safety</h2>
 * The provider instance is built per operation over the pinned connection and holds its own nesting
 * depth and savepoint stack. That is sound because SQL for one operation runs sequentially on the
 * dispatch thread ({@code RowsMethodCall} emits synchronous batch loaders); no two transactions on
 * the pinned connection are ever open concurrently. Under {@code COMMIT}, top-level begin sets
 * autocommit false, top-level commit/rollback settles and restores autocommit, and nested begins are
 * savepoint-scoped. Under {@code ROLLBACK_ONLY} (the dev observe-then-discard topology), the first
 * top-level begin opens the one operation transaction, every field boundary (and nested begin) is a
 * savepoint, no depth-0 settle ever closes the transaction, and {@code PinnedConnection#release}
 * discards the whole thing at operation completion.
 *
 * <h2>Stated fidelity limitation of the deferred topology</h2>
 * Holding the operation transaction open structurally conflicts with the per-settle session-identity
 * re-fire contract (hooks assume autocommit and no open transaction), so under {@code ROLLBACK_ONLY}
 * the {@code afterSettle} seam never fires mid-operation: dev execution does not exercise a
 * consumer's unconfirmed connect/disconnect re-fire pair between mutation fields. Mounted identity
 * itself is unaffected (session-scoped state established at acquire, which the release rollback
 * cannot revert). Pinned by the generator's unit test, named in the dev-tool user doc.
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
        var afterSettleField = FieldSpec.builder(Runnable.class, "afterSettle", Modifier.PRIVATE, Modifier.FINAL)
            .addJavadoc("Settle-completion callback, run after each top-level transaction settles and\n"
                + "autocommit is restored. Opaque to the provider (commit policy stays the one axis it\n"
                + "owns); the acquisition machinery wires the session-identity re-fire through it.\n")
            .build();
        var savepointsField = FieldSpec.builder(savepointDeque, "savepoints", Modifier.PRIVATE, Modifier.FINAL)
            .initializer("new $T<>()", ARRAY_DEQUE)
            .build();
        var depthField = FieldSpec.builder(int.class, "depth", Modifier.PRIVATE).build();
        var priorAutoCommitField = FieldSpec.builder(boolean.class, "priorAutoCommit", Modifier.PRIVATE).build();

        var constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(CONNECTION, "connection")
            .addParameter(commitPolicy, "commitPolicy")
            .addStatement("this(connection, commitPolicy, () -> { })")
            .addJavadoc("Builds a provider with no settle-completion callback; see the canonical constructor.\n")
            .build();

        var canonicalConstructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(CONNECTION, "connection")
            .addParameter(commitPolicy, "commitPolicy")
            .addParameter(Runnable.class, "afterSettle")
            .addStatement("this.connection = connection")
            .addStatement("this.commitPolicy = commitPolicy")
            .addStatement("this.afterSettle = afterSettle")
            .addJavadoc("Builds a provider over the pinned {@code connection} applying {@code commitPolicy}\n"
                + "to every top-level transaction it demarcates, running {@code afterSettle} after each\n"
                + "top-level settle (once autocommit is restored). One instance per operation. The callback\n"
                + "is opaque here; the acquisition machinery passes the pinned connection's settle hook\n"
                + "({@code PinnedConnection#afterSettle}), which re-fires unconfirmed session hooks.\n")
            .build();

        var begin = MethodSpec.methodBuilder("begin")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .addParameter(TRANSACTION_CONTEXT, "ctx")
            .beginControlFlow("try")
            .beginControlFlow("if (depth == 0 && commitPolicy == $T.ROLLBACK_ONLY)", commitPolicy)
            .addComment("Deferred-rollback dev mode (R428): open the operation transaction once and keep")
            .addComment("it open across field settles, so post-settle read-backs observe the writes; each")
            .addComment("field boundary is a savepoint. PinnedConnection.release discards the whole")
            .addComment("transaction and restores autocommit at operation completion.")
            .beginControlFlow("if (connection.getAutoCommit())")
            .addStatement("connection.setAutoCommit(false)")
            .endControlFlow()
            .addStatement("savepoints.push(connection.setSavepoint())")
            .nextControlFlow("else if (depth == 0)")
            .addComment("Top-level: a mutation field opens a writable transaction by turning autocommit off.")
            .addStatement("priorAutoCommit = connection.getAutoCommit()")
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
            .addComment("until release, so afterSettle (the session-identity re-fire seam) stays unfired.")
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
            .addStatement("connection.setAutoCommit(priorAutoCommit)")
            .addComment("Settle-completion callback, outside the transaction just closed (autocommit is")
            .addComment("restored): the seam the session-identity re-fire rides. Top-level only; savepoint")
            .addComment("settles never reach here.")
            .addStatement("afterSettle.run()")
            .addJavadoc("Closes the top-level transaction under the {@code COMMIT} policy: rolls back when\n"
                + "the transaction {@code failed}, otherwise commits, then restores the prior autocommit\n"
                + "and runs the settle-completion callback. Unreachable under\n"
                + "{@link CommitPolicy#ROLLBACK_ONLY}, whose field boundaries are savepoint-scoped and\n"
                + "whose one real transaction is discarded at release.\n")
            .build();

        var commitPolicyEnum = TypeSpec.enumBuilder(COMMIT_POLICY_ENUM_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addEnumConstant("COMMIT")
            .addEnumConstant("ROLLBACK_ONLY")
            .addJavadoc("Global commit policy for every top-level transaction. {@code COMMIT} persists a\n"
                + "successful transaction; {@code ROLLBACK_ONLY} is R428's rollback-everything dev mode\n"
                + "(execute a mutation, observe its result, persist nothing): the operation transaction is\n"
                + "opened once and deferred across field settles (each field boundary is a savepoint), so\n"
                + "post-settle payload read-backs observe the uncommitted writes, and the whole transaction\n"
                + "is discarded when the pinned connection is released. One stated fidelity limit: nothing\n"
                + "settles mid-operation, so the per-settle session-identity re-fire never fires under this\n"
                + "policy. Provider configuration, never chosen per site.\n")
            .build();

        return TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(TRANSACTION_PROVIDER)
            .addJavadoc("Custom jOOQ {@link $T} over the single pinned connection: the one seam every\n"
                + "mutation transaction boundary routes through. Reimplemented from scratch (jOOQ's\n"
                + "{@code DefaultTransactionProvider} is {@code final} on commit) so\n"
                + "{@link CommitPolicy#ROLLBACK_ONLY} can suppress a commit. See\n"
                + "{@code GraphitronTransactionProviderGenerator} for the full contract.\n", TRANSACTION_PROVIDER)
            .addType(commitPolicyEnum)
            .addField(connectionField)
            .addField(policyField)
            .addField(afterSettleField)
            .addField(savepointsField)
            .addField(depthField)
            .addField(priorAutoCommitField)
            .addMethod(constructor)
            .addMethod(canonicalConstructor)
            .addMethod(begin)
            .addMethod(commit)
            .addMethod(rollback)
            .addMethod(settle)
            .build();
    }
}
