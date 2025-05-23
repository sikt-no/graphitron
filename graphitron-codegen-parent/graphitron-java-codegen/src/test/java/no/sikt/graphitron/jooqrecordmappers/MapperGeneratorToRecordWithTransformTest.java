package no.sikt.graphitron.jooqrecordmappers;

import no.sikt.graphitron.codereferences.transforms.SomeTransform;
import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.configuration.externalreferences.GlobalTransform;
import no.sikt.graphitron.configuration.externalreferences.TransformScope;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.mapping.RecordMapperClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

@DisplayName("JOOQ Mappers - Mapper content for mapping graph types to jOOQ records")
public class MapperGeneratorToRecordWithTransformTest extends GeneratorTest {
    @Override
    protected String getSubpath() {
        return "jooqmappers/torecord";
    }

    @Override
    protected Set<GlobalTransform> getGlobalTransforms() {
        return Set.of(new GlobalTransform(SomeTransform.class.getName(), "someTransform", TransformScope.ALL_MUTATIONS));
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new RecordMapperClassGenerator(schema, true));
    }

    @Test
    @DisplayName("Default case for global record transforms")
    void defaultCase() {
        assertGeneratedContentContains(
                "withTransforms",
                "customerRecordList = (ArrayList<CustomerRecord>) SomeTransform.someTransform(ctx, customerRecordList);" +
                        "return customerRecordList"
        );
    }
}
