package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;

/**
 * The one renderer of a transaction graphitron's own fetcher opens. Every generated
 * {@code transactionResult} goes through {@link #transactionResult}, so the emitted
 * {@code GraphitronTransactionProvider} can tell a scope graphitron opened from one a consumer's
 * code opened, and a write emitter added later gets the mark by calling this rather than by
 * remembering it. {@code OwnedScopeSeamGuardTest} fails the build when an emitter renders a
 * transaction any other way.
 */
public final class OwnedScope {

    private static final String PROVIDER_CLASS_NAME = "GraphitronTransactionProvider";

    private OwnedScope() {}

    /** The emitted provider, which lives beside the runtime in {@code <outputPackage>.schema}. */
    public static ClassName provider(String outputPackage) {
        return ClassName.get(outputPackage + ".schema", PROVIDER_CLASS_NAME);
    }

    /**
     * The head of an owned transaction: {@code <Provider>.owned(dsl).transactionResult(tx -> }.
     * The caller appends the lambda body and the closing parenthesis.
     *
     * @param provider the emitted provider, from {@link #provider}
     * @param dsl      the name of the {@code DSLContext} local the transaction is opened on
     */
    public static CodeBlock transactionResult(ClassName provider, String dsl) {
        return CodeBlock.of("$T.owned($L).transactionResult(tx -> ", provider, dsl);
    }
}
