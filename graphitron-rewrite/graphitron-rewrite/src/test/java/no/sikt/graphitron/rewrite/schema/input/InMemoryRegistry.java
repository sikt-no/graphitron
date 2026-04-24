package no.sikt.graphitron.rewrite.schema.input;

import graphql.parser.MultiSourceReader;
import graphql.parser.Parser;
import graphql.parser.ParserEnvironment;
import graphql.parser.ParserOptions;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;

/**
 * Test-only helper: builds a {@link TypeDefinitionRegistry} from an in-memory map
 * of (source-name, SDL) pairs. Mirrors {@link no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader}
 * without the filesystem or directive-resource plumbing, so applier tests can control
 * source names directly.
 */
final class InMemoryRegistry {

    private InMemoryRegistry() {}

    static TypeDefinitionRegistry of(Map<String, String> sourceToSdl) {
        var builder = MultiSourceReader.newMultiSourceReader();
        sourceToSdl.forEach((source, sdl) -> builder.reader(new StringReader(sdl), source));
        try (var multi = builder.trackData(true).build()) {
            var document = new Parser().parseDocument(
                ParserEnvironment.newParserEnvironment()
                    .parserOptions(ParserOptions.getDefaultSdlParserOptions())
                    .document(multi)
                    .build());
            return new SchemaParser().buildRegistry(document);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
