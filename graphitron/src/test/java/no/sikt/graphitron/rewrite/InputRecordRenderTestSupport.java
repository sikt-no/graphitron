package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.plan.InputRecordPlanner;
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

    /**
     * The input-record classes a run emits for {@code sdl}.
     *
     * <p>Takes the schema text as well as the model because membership is a relation the store
     * states: which input types an argument reaches is read from a store captured over this text,
     * not folded out of the classified schema. A fixture that passed the model alone would read
     * that relation from no store and render nothing, which is a pass for the wrong reason.
     */
    public static List<TypeSpec> renderInputRecords(String sdl, GraphitronSchema schema,
                                                    String outputPackage) {
        var rows = TestSchemaHelper.withStoreOver(sdl,
            store -> InputRecordPlanner.produce(store, schema, outputPackage));
        return rows.stream()
            .map(row -> InputRecordGenerator.generateFor(schema.type(row.typeName()), outputPackage))
            .toList();
    }
}
