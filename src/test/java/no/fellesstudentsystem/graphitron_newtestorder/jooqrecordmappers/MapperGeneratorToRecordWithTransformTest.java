package no.fellesstudentsystem.graphitron_newtestorder.jooqrecordmappers;

import no.fellesstudentsystem.graphitron.configuration.externalreferences.GlobalTransform;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.TransformScope;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.abstractions.ClassGenerator;
import no.fellesstudentsystem.graphitron.generators.resolvers.mapping.RecordMapperClassGenerator;
import no.fellesstudentsystem.graphitron_newtestorder.GeneratorTest;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.transforms.SomeTransform;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

@DisplayName("JOOQ Mappers - Mapper content for mapping graph types to jOOQ records")
public class MapperGeneratorToRecordWithTransformTest extends GeneratorTest {
    public static final String SRC_TEST_RESOURCES_PATH = "jooqmappers/torecord";

    public MapperGeneratorToRecordWithTransformTest() {
        super(
                SRC_TEST_RESOURCES_PATH,
                Set.of(),
                Set.of(new GlobalTransform(SomeTransform.class.getName(), "someTransform", TransformScope.ALL_MUTATIONS)),
                List.of()
        );
    }

    @Override
    protected List<ClassGenerator<? extends GenerationTarget>> makeGenerators(ProcessedSchema schema) {
        return List.of(new RecordMapperClassGenerator(schema, true));
    }

    @Test
    @DisplayName("Default case for global record transforms")
    void defaultCase() {
        assertGeneratedContentMatches("withTransforms");
    }
}
