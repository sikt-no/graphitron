package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.RowsMethodBody;

import javax.lang.model.element.Modifier;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.ENV;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.LIST;

/**
 * Single entry point for emitting a DataLoader rows-method's {@link MethodSpec}. Consumed by
 * the five rows-method emitter sites in R38 Phase 2 (today: {@code SplitRowsMethodEmitter}'s
 * four entry points + {@code TypeFetcherGenerator.buildServiceRowsMethod}); each construction
 * site projects from the field's {@code (SourceKey.reader(), LoaderRegistration.container())}
 * pair to the matching {@link RowsMethodBody} permit and hands it here.
 *
 * <p>Owns the per-method declaration scaffolding:
 * <ul>
 *   <li>{@code public static} modifiers</li>
 *   <li>parameters {@code (keys, env)} — keys-container type ({@code List<K>} for positional,
 *       {@code Set<K>} for mapped) supplied by the caller from the field's
 *       {@code LoaderRegistration.container()}</li>
 *   <li>declared return type — the rows method's outer wrapper, built upstream via
 *       {@link no.sikt.graphitron.rewrite.model.RowsMethodShape#outerRowsReturnType}</li>
 *   <li>empty-input short-circuit (SQL permits only — service-path gate is out of scope per
 *       R38's spec)</li>
 *   <li>{@code DSLContext dsl = <graphitronContextCall>.getDslContext(env)} line — always for
 *       SQL permits; conditional on {@link RowsMethodBody.Service#needsDsl()} for the service
 *       permit</li>
 * </ul>
 *
 * <p>Body content is opaque to the skeleton: each permit carries its own
 * {@link RowsMethodBody#content()} {@code CodeBlock}, pasted unchanged after the framing.
 * Phase 2 wires the existing body builders into the construction sites that produce these
 * permits; Phase 1 ships only this seam.
 */
public final class RowsMethodSkeleton {

    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");

    private RowsMethodSkeleton() {}

    /**
     * Builds a rows-method {@link MethodSpec} for the given body permit.
     *
     * @param methodName            the rows-method name (today produced by
     *                              {@link no.sikt.graphitron.rewrite.model.BatchKeyField#rowsMethodName()};
     *                              service-backed leaves override to {@code load<X>}).
     * @param outerReturnType       the rows-method's outer return type (e.g.
     *                              {@code List<List<Record>>}, {@code Map<K, V>}); produced via
     *                              {@link no.sikt.graphitron.rewrite.model.RowsMethodShape#outerRowsReturnType}.
     * @param keysContainerType     {@code List<K>} for positional-list registrations,
     *                              {@code Set<K>} for mapped-set registrations.
     * @param graphitronContextCall the call expression for the per-class
     *                              {@code graphitronContext(env)} helper (e.g.
     *                              {@code CodeBlock.of("graphitronContext(env)")}); used to
     *                              emit the {@code DSLContext dsl = ...} line. Callers in
     *                              {@code TypeFetcherGenerator} pass
     *                              {@code ctx.graphitronContextCall()} so the helper-emission
     *                              dependency is recorded.
     * @param body                  the per-shape body permit; carries the SELECT / scatter /
     *                              service-call content the skeleton pastes after the framing.
     */
    public static MethodSpec build(
            String methodName,
            TypeName outerReturnType,
            TypeName keysContainerType,
            CodeBlock graphitronContextCall,
            RowsMethodBody body) {

        var b = MethodSpec.methodBuilder(methodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(outerReturnType)
            .addParameter(keysContainerType, "keys")
            .addParameter(ENV, "env");

        switch (body) {
            case RowsMethodBody.SqlSplitTable s        -> emitSqlBody(b, s.content(), graphitronContextCall);
            case RowsMethodBody.SqlSplitLookupTable s  -> emitSqlBody(b, s.content(), graphitronContextCall);
            case RowsMethodBody.SqlRecordTable s       -> emitSqlBody(b, s.content(), graphitronContextCall);
            case RowsMethodBody.SqlRecordLookupTable s -> emitSqlBody(b, s.content(), graphitronContextCall);
            case RowsMethodBody.Service s              -> emitServiceBody(b, s, graphitronContextCall);
        }
        return b.build();
    }

    /**
     * SQL framing: empty-input short-circuit (returning {@code List.of()}) followed by the
     * DSL local resolution, then the permit's body content. The body content references both
     * {@code keys} and {@code dsl}.
     */
    private static void emitSqlBody(MethodSpec.Builder b, CodeBlock content, CodeBlock graphitronContextCall) {
        b.beginControlFlow("if (keys.isEmpty())")
         .addStatement("return $T.of()", LIST)
         .endControlFlow();
        b.addStatement("$T dsl = $L.getDslContext(env)", DSL_CONTEXT, graphitronContextCall);
        b.addCode(content);
    }

    /**
     * Service framing: optional DSL local resolution (driven by {@code needsDsl}, which
     * mirrors the developer's {@code @service} method {@link no.sikt.graphitron.rewrite.model.MethodRef.CallShape}),
     * then the permit's body content. The empty-input gate is intentionally omitted; per
     * R38's "Out of scope" carve-out, adding the gate to service rows methods is a behaviour
     * change tracked as a separate Backlog item.
     */
    private static void emitServiceBody(MethodSpec.Builder b, RowsMethodBody.Service service, CodeBlock graphitronContextCall) {
        if (service.needsDsl()) {
            b.addStatement("$T dsl = $L.getDslContext(env)", DSL_CONTEXT, graphitronContextCall);
        }
        b.addCode(service.content());
    }
}
