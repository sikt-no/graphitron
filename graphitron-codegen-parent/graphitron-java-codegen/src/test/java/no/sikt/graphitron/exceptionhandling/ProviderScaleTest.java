package no.sikt.graphitron.exceptionhandling;

import no.sikt.graphitron.common.GeneratorTest;
import no.sikt.graphitron.common.configuration.SchemaComponent;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.generate.GraphQLGenerator;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphitron.generators.exception.ExceptionToErrorMappingProviderGenerator;
import no.sikt.graphql.exception.ExceptionToErrorMappingProvider;
import no.sikt.graphql.schema.ProcessedSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.common.configuration.ReferencedEntry.DUMMY_SERVICE;
import static no.sikt.graphitron.common.configuration.SchemaComponent.ERROR;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Exception handling - Provider generation for a schema too large for a single method")
public class ProviderScaleTest extends GeneratorTest {
    private static final int OPERATION_COUNT = 500;
    private static final String PROVIDER_CLASS_NAME = "GeneratedExceptionToErrorMappingProvider";

    @Override
    protected String getSubpath() {
        return "exceptions/provider";
    }

    @Override
    protected Set<ExternalReference> getExternalReferences() {
        return makeReferences(DUMMY_SERVICE);
    }

    @Override
    protected Set<SchemaComponent> getComponents() {
        return makeComponents(ERROR);
    }

    @Override
    protected List<ClassGenerator> makeGenerators(ProcessedSchema schema) {
        return List.of(new ExceptionToErrorMappingProviderGenerator(schema));
    }

    @BeforeEach
    public void setup() {
        super.setup();
        GeneratorConfig.setRecordValidation(new RecordValidation(true, null));
    }

    /**
     * The generated provider constructor previously exceeded the JVM's limit of 65535 bytes of bytecode
     * per method for large schemas. This test generates from a schema large enough that the default size
     * budget requires splitting into multiple methods, and then verifies the result with the real
     * compiler, which enforces the JVM limit and fails with "code too large" if any method exceeds it.
     */
    @Test
    @DisplayName("A large schema is split into multiple methods and the result compiles")
    void largeSchema(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve(TestConfiguration.COMMON_TEST_SCHEMA_NAME), buildLargeSchema());
        var processedSchema = TestConfiguration.getProcessedSchema(
                tempDir.toString(), mergeComponentsAndSetConfig(Set.of()), validateSchema(), checkProcessedSchemaDefault);
        var generated = GraphQLGenerator.generateAsStrings(makeGenerators(processedSchema));
        var providerSource = String.join("\n", generated.get(PROVIDER_CLASS_NAME));

        assertThat(providerSource)
                .withFailMessage("Expected the default method size budget to split generation into multiple methods.")
                .contains("initMappings2()");

        assertCompiles(tempDir, providerSource);
    }

    private static String buildLargeSchema() {
        var schema = new StringBuilder("type Mutation {\n");
        for (int i = 0; i < OPERATION_COUNT; i++) {
            schema.append("  mutation").append(i).append(": Response").append(i).append(" @mutation(typeName: UPDATE)\n");
        }
        schema.append("}\n\n");
        for (int i = 0; i < OPERATION_COUNT; i++) {
            schema
                    .append("type Response").append(i).append(" {\n")
                    .append("  errors: [SomeError").append(i).append("]\n")
                    .append("}\n\n")
                    .append("type SomeError").append(i)
                    .append(" implements Error @error(handlers: [{handler: GENERIC, className: \"java.lang.IllegalArgumentException\"}]) {\n")
                    .append("  path: [String!]!\n")
                    .append("  message: String!\n")
                    .append("}\n\n");
        }
        return schema.toString();
    }

    private static void assertCompiles(Path tempDir, String providerSource) throws Exception {
        var sources = new ArrayList<JavaFileObject>();
        sources.add(new StringJavaSource(PROVIDER_CLASS_NAME, providerSource));
        for (int i = 0; i < OPERATION_COUNT; i++) {
            sources.add(new StringJavaSource("SomeError" + i,
                    "package fake.graphql.example.model;\n\n" +
                            "public class SomeError" + i + " {\n" +
                            "    public SomeError" + i + "(java.util.List<String> path, String msg) {\n" +
                            "    }\n" +
                            "}\n"));
        }

        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var classpath = Path.of(ExceptionToErrorMappingProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var classesDirectory = Files.createDirectory(tempDir.resolve("classes"));
        try (var fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var options = List.of("-classpath", classpath, "-d", classesDirectory.toString());
            var success = compiler.getTask(null, fileManager, diagnostics, options, null, sources).call();
            assertThat(success)
                    .withFailMessage(() -> "Generated code failed to compile:\n" + diagnostics.getDiagnostics().stream()
                            .map(Object::toString)
                            .collect(Collectors.joining("\n")))
                    .isTrue();
        }
    }

    private static class StringJavaSource extends SimpleJavaFileObject {
        private final String source;

        StringJavaSource(String className, String source) {
            super(URI.create("string:///" + className + ".java"), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
