package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.plan.TypeUnitCommands;
import no.sikt.graphitron.rewrite.generators.schema.InputRecordGenerator;

import java.util.List;

/**
 * Test-side twin of the pipeline's input-record fold ({@code GraphQLRewriteGenerator}): the
 * type-unit relation decides membership, one render call per row. Mirrors
 * {@link ProjectionRenderTestSupport}'s role for tests that previously called the retired
 * whole-population {@code InputRecordGenerator.generate}.
 */
public final class InputRecordRenderTestSupport {

    private InputRecordRenderTestSupport() {}

    public static List<TypeSpec> renderInputRecords(GraphitronSchema schema, String outputPackage) {
        return TypeUnitCommands.produce(schema, outputPackage).inputRecords().stream()
            .map(row -> InputRecordGenerator.generateFor(schema.type(row.typeName()), outputPackage))
            .toList();
    }
}
