package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.RowsMethodBody;

import javax.lang.model.element.Modifier;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.ENV;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.LIST;

/**
 * Single entry point for emitting a DataLoader rows-method's {@link MethodSpec}: the
 * declaration scaffolding around a {@link RowsMethodBody} permit. Each construction site
 * ({@link SplitRowsMethodEmitter} and {@code TypeFetcherGenerator}'s service rows method)
 * projects from the field's variant and {@code LoaderRegistration.container()} to the
 * matching permit and hands it here.
 *
 * <p>Body content is opaque to the skeleton: each permit carries its own
 * {@link RowsMethodBody#content()} {@code CodeBlock}, pasted unchanged after the framing.
 */
public final class RowsMethodSkeleton {

    private RowsMethodSkeleton() {}

    /**
     * Builds a rows-method {@link MethodSpec} for the given body permit.
     *
     * @param methodName        the rows-method name, per
     *                          {@link no.sikt.graphitron.rewrite.model.BatchKeyField#rowsMethodName()}
     *                          (service-backed leaves override to {@code load<X>}).
     * @param outerReturnType   the rows-method's outer return type, produced via
     *                          {@link no.sikt.graphitron.rewrite.model.RowsMethodShape#outerRowsReturnType}.
     * @param keysContainerType {@code List<K>} for positional-list registrations,
     *                          {@code Set<K>} for mapped-set registrations.
     * @param dslDeclaration    the full {@code DSLContext dsl = ...;} declaration statement(s),
     *                          resolved per the field's tenant binding by
     *                          {@link TenantDslEmitter}.
     * @param body              the per-shape body permit; carries the SELECT / scatter /
     *                          service-call content the skeleton pastes after the framing.
     */
    public static MethodSpec build(
            String methodName,
            TypeName outerReturnType,
            TypeName keysContainerType,
            CodeBlock dslDeclaration,
            RowsMethodBody body) {

        var b = MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(outerReturnType)
            .addParameter(keysContainerType, "keys")
            .addParameter(ENV, "env");

        switch (body) {
            case RowsMethodBody.SqlBatchedTable s      -> emitSqlBody(b, s.content(), dslDeclaration);
            case RowsMethodBody.SqlBatchedLookupTable s -> emitSqlBody(b, s.content(), dslDeclaration);
            case RowsMethodBody.SqlBatchedPivot s      -> emitSqlBody(b, s.content(), dslDeclaration);
            case RowsMethodBody.Service s              -> emitServiceBody(b, s, dslDeclaration);
        }
        return b.build();
    }

    /**
     * SQL framing. The permit's body content references both {@code keys} and the {@code dsl}
     * local declared here.
     */
    private static void emitSqlBody(MethodSpec.Builder b, CodeBlock content, CodeBlock dslDeclaration) {
        b.beginControlFlow("if (keys.isEmpty())")
         .addStatement("return $T.of()", LIST)
         .endControlFlow();
        b.addCode(dslDeclaration);
        b.addCode(content);
    }

    /**
     * Service framing. {@code needsDsl} mirrors the developer's {@code @service} method
     * {@link no.sikt.graphitron.rewrite.model.MethodRef.CallShape}. The empty-input gate is
     * deliberately absent: adding it to service rows methods would change behaviour.
     */
    private static void emitServiceBody(MethodSpec.Builder b, RowsMethodBody.Service service, CodeBlock dslDeclaration) {
        if (service.needsDsl()) {
            b.addCode(dslDeclaration);
        }
        b.addCode(service.content());
    }
}
