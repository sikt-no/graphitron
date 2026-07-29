package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;

/**
 * Per-class collector for the {@code graphitronContext(env)} helper: a context-reading emission
 * obtains the call expression through {@link #call()}, which records that the host class needs
 * the helper, and {@link #collectInto} drains the recorded need as the
 * {@code private static GraphitronContext graphitronContext(DataFetchingEnvironment env)} method.
 *
 * <p>The construct-thread-drain bracket exists for the same reason
 * {@link CompositeDecodeHelperRegistry}'s does: the emitted call and the helper it names must be
 * decided at one seam. The bug class this prevents is real and was shipped twice, by the retired
 * root conditions shim (a {@code graphitronContext(env)} call on a class that never carried the
 * helper) and by the {@code $project}-hosted inline sites (a throwaway emission context whose
 * recorded need nothing drained), both surfacing as the consumer's javac failure.
 */
public final class RequestContextHelper {

    private final String outputPackage;
    private boolean required;

    private RequestContextHelper(String outputPackage) {
        this.outputPackage = outputPackage;
    }

    /**
     * Brackets construct-thread-drain: hands a fresh collector to {@code body}, then, if any
     * emission recorded a context read, adds the helper method onto {@code classBuilder}.
     */
    public static void collectInto(TypeSpec.Builder classBuilder, String outputPackage,
            java.util.function.Consumer<RequestContextHelper> body) {
        var helper = new RequestContextHelper(outputPackage);
        body.accept(helper);
        if (helper.required) {
            classBuilder.addMethod(helper.buildHelper());
        }
    }

    /** The literal {@code graphitronContext(env)} call expression; recording the helper need. */
    public CodeBlock call() {
        required = true;
        return CodeBlock.of("graphitronContext(env)");
    }

    private MethodSpec buildHelper() {
        var ctxType = ClassName.get(outputPackage + ".schema", "GraphitronContext");
        return MethodSpec.methodBuilder("graphitronContext")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(ctxType)
            .addParameter(ClassName.get("graphql.schema", "DataFetchingEnvironment"), "env")
            .addStatement("return env.getGraphQlContext().get($T.class)", ctxType)
            .build();
    }
}
