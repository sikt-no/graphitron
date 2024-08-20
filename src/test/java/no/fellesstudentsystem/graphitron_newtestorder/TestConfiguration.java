package no.fellesstudentsystem.graphitron_newtestorder;

import no.fellesstudentsystem.graphitron.configuration.Extension;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.ExternalReference;
import no.fellesstudentsystem.graphitron.configuration.externalreferences.GlobalTransform;
import no.fellesstudentsystem.graphitron.mojo.GraphQLGenerator;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TestConfiguration {
    public static final String
            SCHEMA_EXTENSION = ".graphqls",
            COMMON_TEST_SCHEMA_NAME = "schema" + SCHEMA_EXTENSION,
            DEFAULT_OUTPUT_PACKAGE = "fake.code.generated",
            DEFAULT_JOOQ_PACKAGE = "no.sikt.graphitron.jooq.generated.testdata",
            SRC_ROOT = "src/test/resources/new",
            COMPONENT_PATH = SRC_ROOT + "/components",
            SRC_DIRECTIVES = "src/main/resources/schema/directives" + SCHEMA_EXTENSION,
            EXPECTED_OUTPUT_NAME = "expected";

    @NotNull
    public static ProcessedSchema getProcessedSchema(String schemaPath, Set<String> components, boolean checkTypes) {
        GeneratorConfig.setSchemaFiles(Stream.concat(Stream.of(SRC_DIRECTIVES, schemaPath + "/" + COMMON_TEST_SCHEMA_NAME), components.stream()).collect(Collectors.toSet()));

        var processedSchema = GraphQLGenerator.getProcessedSchema();
        processedSchema.validate(checkTypes);
        return processedSchema;
    }

    public static void setProperties(List<ExternalReference> references, List<GlobalTransform> globalTransforms, List<Extension> extendedClasses) {
        GeneratorConfig.setProperties(
                Set.of(),
                "",
                DEFAULT_OUTPUT_PACKAGE,
                DEFAULT_JOOQ_PACKAGE,
                references,
                Set.of(
                    "no.fellesstudentsystem.graphitron_newtestorder.codereferences.conditions",
                    "no.fellesstudentsystem.graphitron_newtestorder.codereferences.dummyreferences",
                    "no.fellesstudentsystem.graphitron_newtestorder.codereferences.records",
                    "no.fellesstudentsystem.graphitron_newtestorder.codereferences.services",
                    "no.fellesstudentsystem.graphitron_newtestorder.codereferences.transforms"
                ),
                globalTransforms,
                extendedClasses
        );
    }

    public static void setProperties(List<ExternalReference> references) {
        setProperties(references, List.of(), List.of());
    }

    public static void setProperties() {
        GeneratorConfig.setProperties(
                Set.of(),
                "",
                DEFAULT_OUTPUT_PACKAGE,
                DEFAULT_JOOQ_PACKAGE,
                List.of(),
                Set.of(),
                List.of(),
                List.of()
        );
    }
}
