package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.configuration.externalreferences.GlobalTransform;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.TransformScope;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.db.update.UpdateDBClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.RecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.TransformerClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.update.UpdateResolverClassGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.fellesstudentsystem.graphitron.TestReferenceSet.TRANSFORM_0;

public class GraphQLGeneratorTransformTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "transform";

    public GraphQLGeneratorTransformTest() {
        super(
                SRC_TEST_RESOURCES_PATH,
                List.of(TRANSFORM_0.get()),
                List.of(new GlobalTransform("TEST_TRANSFORM", "someTransform", TransformScope.ALL_MUTATIONS)),
                List.of()
        );
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(
                new UpdateResolverClassGenerator(schema),
                new UpdateDBClassGenerator(schema),
                new TransformerClassGenerator(schema),
                new RecordMapperClassGenerator(schema, true)
        );
    }

    @Test
    void generate_mutation_shouldGenerateResolversWithTransform() {
        assertGeneratedContentMatches("resolverWithTransforms");
    }
}
