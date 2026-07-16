package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.schema.FetcherRegistrationsEmitter;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronSchemaClassGenerator;
import no.sikt.graphitron.rewrite.generators.schema.ObjectTypeGenerator;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end regression: keeps the keyset/method-emission contract between
 * {@link FetcherRegistrationsEmitter}, {@link ObjectTypeGenerator}, and
 * {@link GraphitronSchemaClassGenerator} aligned, so the emitted
 * {@code GraphitronSchema.java} never calls a {@code <Name>Type.registerFetchers(...)}
 * method that {@code <Name>Type} doesn't declare (the original field report's javac
 * failure), and conversely never emits an orphan {@code registerFetchers} method that
 * no call site invokes (the inverse drift the symptom direction missed).
 *
 * <p>Pinning both directions catches the bug class regardless of which side of the
 * contract drifts: a consumer adding a new iteration site or the emitter relaxing its
 * construction-site gate both fail this test directly, not via a downstream compile
 * error.
 */
@PipelineTier
class FetcherRegistrationsPipelineTest {

    /**
     * Pattern matching the {@code <Name>Type.registerFetchers(codeRegistry);} call shape
     * that {@link GraphitronSchemaClassGenerator} emits for each entry in the bodies map.
     */
    private static final Pattern CALL_SITE = Pattern.compile(
        "\\b([A-Z][A-Za-z0-9_]*)Type\\.registerFetchers\\(codeRegistry\\)");

    @Test
    void bidirectionalInvariant_betweenCallSitesAndMethodEmission_fieldReportFixture() {
        // The field report: an unreferenced payload-shaped type whose only field is
        // @nodeId. Before this fix this produced an empty-body entry in the bodies map,
        // surfacing as an orphan registerFetchers call in GraphitronSchema.build().
        assertBidirectionalInvariant("""
            type Query { x: String }
            type SlettRegelverksamlingPayload { regelverksamlingId: [ID!] @nodeId }
            """);
    }

    @Test
    void bidirectionalInvariant_betweenCallSitesAndMethodEmission_mixedFixture() {
        // A realistic mix: real fetched types plus the orphan-payload regression shape.
        assertBidirectionalInvariant("""
            type Film @table(name: "film") { id: ID }
            type Query { film: Film films: [Film!]! @asConnection @defaultOrder(primaryKey: true) }
            type SlettRegelverksamlingPayload { regelverksamlingId: [ID!] @nodeId }
            """);
    }

    private static void assertBidirectionalInvariant(String sdl) {
        var bundle = TestSchemaHelper.buildBundle(sdl);
        Map<String, CodeBlock> bodies = FetcherRegistrationsEmitter.emit(
            bundle.model(), DEFAULT_OUTPUT_PACKAGE);

        var schemaClass = GraphitronSchemaClassGenerator.generate(
            bundle.model(), bundle.assembled(), bodies.keySet(),
            DEFAULT_OUTPUT_PACKAGE, false);
        Set<String> callSiteTypes = collectCallSiteTypeNames(schemaClass);

        var objectTypes = ObjectTypeGenerator.generate(
            bundle.model(), bundle.assembled(), bodies);
        Set<String> methodEmittingTypes = collectMethodEmittingTypeNames(objectTypes);

        assertThat(callSiteTypes)
            .as("no orphan call sites in GraphitronSchema.build(): every "
                + "<Name>Type.registerFetchers(codeRegistry) call must point at an "
                + "emitted registerFetchers method on <Name>Type")
            .isEqualTo(methodEmittingTypes);
    }

    private static Set<String> collectCallSiteTypeNames(java.util.List<TypeSpec> generatedClasses) {
        var names = new TreeSet<String>();
        for (var spec : generatedClasses) {
            for (var method : spec.methodSpecs()) {
                Matcher m = CALL_SITE.matcher(method.code().toString());
                while (m.find()) {
                    names.add(m.group(1));
                }
            }
        }
        return names;
    }

    private static Set<String> collectMethodEmittingTypeNames(java.util.List<TypeSpec> objectTypes) {
        var names = new TreeSet<String>();
        for (var spec : objectTypes) {
            String name = spec.name();
            if (!name.endsWith("Type")) continue;
            boolean hasRegister = spec.methodSpecs().stream()
                .map(MethodSpec::name)
                .anyMatch("registerFetchers"::equals);
            if (hasRegister) {
                names.add(name.substring(0, name.length() - "Type".length()));
            }
        }
        return names;
    }
}
