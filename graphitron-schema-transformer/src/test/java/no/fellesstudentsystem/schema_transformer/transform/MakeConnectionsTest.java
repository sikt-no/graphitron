package no.fellesstudentsystem.schema_transformer.transform;

import graphql.schema.GraphqlTypeComparatorRegistry;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.SchemaPrinter;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import org.approvaltests.Approvals;
import org.approvaltests.core.Options;
import org.approvaltests.namer.NamerWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class MakeConnectionsTest {
    private static final Path SCHEMA_PATH = Paths.get("src/test/resources/asConnectionRewriterTest/");

    @ParameterizedTest
    @MethodSource(value="schemaFiles")
    public void testTransform(String filename) throws IOException {
        var registry = new SchemaParser().parse(Files.newBufferedReader(SCHEMA_PATH.resolve(filename), StandardCharsets.UTF_8));
        MakeConnections.transform(registry);

        var schemaPrintingOptions = SchemaPrinter.Options.defaultOptions()
                .includeDirectiveDefinitions(false)
                .setComparators(GraphqlTypeComparatorRegistry.AS_IS_REGISTRY);
        var schema = new SchemaPrinter(schemaPrintingOptions)
                .print(UnExecutableSchemaGenerator.makeUnExecutableSchema(registry));

        Approvals.verify(schema, new Options().forFile().withNamer(new NamerWrapper(
                () -> filename.replace(".graphqls",".result"),
                SCHEMA_PATH.resolve("approved")::toString)));
    }

    @Test
    public void testInvalidUnnamed() throws IOException{
        var registry = new SchemaParser().parse(Files.newBufferedReader(SCHEMA_PATH.resolve("invalidUnnamed.graphqls.txt"), StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> MakeConnections.transform(registry));
    }

    @Test
    public void testInvalidNonList() throws IOException{
        var registry = new SchemaParser().parse(Files.newBufferedReader(SCHEMA_PATH.resolve("invalidNonList.graphqls.txt"), StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> MakeConnections.transform(registry));
    }

    @Test
    public void testInvalidDuplicateDirective() throws IOException{
        var registry = new SchemaParser().parse(Files.newBufferedReader(SCHEMA_PATH.resolve("invalidDuplicateDirective.graphqls.txt"), StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> MakeConnections.transform(registry));
    }

    private static Stream<Arguments> schemaFiles() throws IOException {
        return Files.list(SCHEMA_PATH)
                .map(Path::getFileName)
                .map(Path::toString)
                .filter(it -> it.endsWith(".graphqls"))
                .map(Arguments::of);
    }
}
