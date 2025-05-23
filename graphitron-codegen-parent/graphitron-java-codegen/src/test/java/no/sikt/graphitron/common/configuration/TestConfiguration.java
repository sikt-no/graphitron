package no.sikt.graphitron.common.configuration;

import no.sikt.graphitron.configuration.Extension;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.configuration.externalreferences.GlobalTransform;
import no.sikt.graphitron.generate.GraphQLGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
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
            SRC_ROOT = "src/test/resources",
            COMPONENT_PATH = SRC_ROOT + "/components",
            SRC_DIRECTIVES = "../../graphitron-common/src/main/resources/directives" + SCHEMA_EXTENSION,
            EXPECTED_OUTPUT_NAME = "expected";

    @NotNull
    public static ProcessedSchema getProcessedSchema(String schemaPath, Set<String> components, boolean validate, boolean checkTypes) {
        var files = Stream.concat(Stream.of(SRC_DIRECTIVES, schemaPath + "/" + COMMON_TEST_SCHEMA_NAME), components.stream()).collect(Collectors.toSet());
        GeneratorConfig.setGeneratorSchemaFiles(files);
        GeneratorConfig.setUserSchemaFiles(files);

        var processedSchema = GraphQLGenerator.getProcessedSchema();
        if (validate) {
            processedSchema.validate(checkTypes);
        }
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
                    "no.sikt.graphitron.codereferences.conditions",
                    "no.sikt.graphitron.codereferences.dummyreferences",
                    "no.sikt.graphitron.codereferences.records",
                    "no.sikt.graphitron.codereferences.services",
                    "no.sikt.graphitron.codereferences.transforms",
                    "no.sikt.graphitron.codereferences.extensionmethods.ClassWithExtensionMethod",
                    "no.sikt.graphitron.codereferences.extensionmethods.ClassWithDuplicatedExtensionMethod"
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
