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
 * site-declared: {@code COMMIT} persists a successful top-level transaction, {@code ROLLBACK_ONLY}
 * rolls it back regardless. A site opens a transaction to write; it does not get to choose
 * commit-versus-rollback.
 *
 * <h2>Single-connection safety</h2>
 * The provider instance is built per operation over the pinned connection and holds its own nesting
 * depth and savepoint stack. That is sound because SQL for one operation runs sequentially on the
 * dispatch thread ({@code RowsMethodCall} emits synchronous batch loaders); no two transactions on
 * the pinned connection are ever open concurrently. Top-level begin sets autocommit false; nested
 * begins push a savepoint. Top-level commit/rollback restores autocommit; nested ones release or roll
 * back to the savepoint.
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
        var priorAutoCommitField = FieldSpec.builder(boolean.class, "priorAutoCommit", Modifier.PRIVATE).build();

        var constructor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameter(CONNECTION, "connection")
            .addParameter(commitPolicy, "commitPolicy")
            .addStatement("this.connection = connection")
            .addStatement("this.commitPolicy = commitPolicy")
            .addJavadoc("Builds a provider over the pinned {@code connection} applying {@code commitPolicy}\n"
                + "to every top-level transaction it demarcates. One instance per operation.\n")
            .build();

        var begin = MethodSpec.methodBuilder("begin")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(void.class)
            .addParameter(TRANSACTION_CONTEXT, "ctx")
            .beginControlFlow("try")
            .beginControlFlow("if (depth == 0)")
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
            .beginControlFlow("if (depth == 0)")
            .addComment("Top-level: the commit policy decides persist-vs-discard (ROLLBACK_ONLY discards).")
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
            .beginControlFlow("if (depth == 0)")
            .addComment("Top-level failure: roll the whole transaction back and restore autocommit.")
            .addComment("failed == true forces a rollback regardless of the commit policy.")
            .addStatement("settle(true)")
            .nextControlFlow("else")
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
            .beginControlFlow("if (failed || commitPolicy == $T.ROLLBACK_ONLY)", commitPolicy)
            .addStatement("connection.rollback()")
            .nextControlFlow("else")
            .addStatement("connection.commit()")
            .endControlFlow()
            .addStatement("connection.setAutoCommit(priorAutoCommit)")
            .addJavadoc("Closes the top-level transaction: rolls back when the transaction {@code failed} or\n"
                + "the {@link CommitPolicy} is {@link CommitPolicy#ROLLBACK_ONLY}, otherwise commits, then\n"
                + "restores the prior autocommit. The one commit-policy seam.\n")
            .build();

        var commitPolicyEnum = TypeSpec.enumBuilder(COMMIT_POLICY_ENUM_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addEnumConstant("COMMIT")
            .addEnumConstant("ROLLBACK_ONLY")
            .addJavadoc("Global commit policy for every top-level transaction. {@code COMMIT} persists a\n"
                + "successful transaction; {@code ROLLBACK_ONLY} rolls it back regardless of success, which is\n"
                + "R428's rollback-everything dev mode (execute a mutation, observe its result, persist\n"
                + "nothing). Provider configuration, never chosen per site.\n")
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
            .addField(savepointsField)
            .addField(depthField)
            .addField(priorAutoCommitField)
            .addMethod(constructor)
            .addMethod(begin)
            .addMethod(commit)
            .addMethod(rollback)
            .addMethod(settle)
            .build();
    }
}
