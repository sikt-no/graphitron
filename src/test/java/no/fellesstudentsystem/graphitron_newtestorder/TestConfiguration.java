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

public class TestConfiguration {
    public static final String
            COMMON_SCHEMA_NAME = "default.graphqls",
            DEFAULT_OUTPUT_PACKAGE = "fake.code.generated",
            DEFAULT_JOOQ_PACKAGE = "no.sikt.graphitron.jooq.generated.testdata",
            SRC_ROOT = "src/test/resources/new",
            SRC_COMMON_SCHEMA = SRC_ROOT + "/" + COMMON_SCHEMA_NAME,
            SRC_DIRECTIVES = "src/main/resources/schema/directives.graphqls",
            EXPECTED_OUTPUT_NAME = "expected";

    @NotNull
    public static ProcessedSchema getProcessedSchema(String schemaPath, String subpathSchema, boolean checkTypes) {
        GeneratorConfig.setSchemaFiles(SRC_COMMON_SCHEMA, SRC_DIRECTIVES, subpathSchema, schemaPath + "/schema.graphqls");

        var processedSchema = GraphQLGenerator.getProcessedSchema();
        processedSchema.validate(checkTypes);
        return processedSchema;
    }

    public static void setProperties(List<ExternalReference> references, List<GlobalTransform> globalTransforms, List<Extension> extendedClasses, String outputDirectory) {
        GeneratorConfig.setProperties(
                Set.of(),
                outputDirectory,
                DEFAULT_OUTPUT_PACKAGE,
                DEFAULT_JOOQ_PACKAGE,
                references,
                globalTransforms,
                extendedClasses
        );
    }

    public static void setProperties(List<ExternalReference> references, String outputDirectory) {
        setProperties(references, List.of(), List.of(), outputDirectory);
    }

    public static void setProperties(List<ExternalReference> references) {
        setProperties(references, "");
    }

    public static void setProperties() {
        GeneratorConfig.setProperties(
                Set.of(),
                "",
                DEFAULT_OUTPUT_PACKAGE,
                DEFAULT_JOOQ_PACKAGE,
                List.of(),
                List.of(),
                List.of()
        );
    }
}
