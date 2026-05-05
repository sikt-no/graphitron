package no.sikt.graphitron.rewrite.schema;

import graphql.parser.InvalidSyntaxException;
import graphql.parser.MultiSourceReader;
import graphql.parser.Parser;
import graphql.parser.ParserEnvironment;
import graphql.parser.ParserOptions;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;

/**
 * Builds a {@link TypeDefinitionRegistry} from a set of user-supplied schema file paths,
 * auto-injecting Graphitron's canonical {@code directives.graphqls} from this module's
 * own classpath. Rewrite's build-time entry point for schema parsing; replaces the
 * legacy {@code SchemaReadingHelper} for in-rewrite callers.
 *
 * <p>User schema inputs are read from the filesystem as streaming {@link Reader}s.
 * The directives source is a same-package classpath resource and therefore does not
 * require a consumer pom to list it. Callers must not include a {@code directives.graphqls}
 * entry in their user-schema list; doing so would re-declare every directive and fail
 * schema parse.
 */
public final class RewriteSchemaLoader {

    private static final String DIRECTIVES_RESOURCE = "directives.graphqls";

    private RewriteSchemaLoader() {}

    public static TypeDefinitionRegistry load(Collection<String> userSchemaPaths) {
        var builder = MultiSourceReader.newMultiSourceReader();
        addDirectivesSource(builder);
        userSchemaPaths.forEach(path -> builder.reader(terminated(openSource(path)), path));
        try (var multi = builder.trackData(true).build()) {
            var document = new Parser().parseDocument(
                ParserEnvironment.newParserEnvironment()
                    .parserOptions(ParserOptions.getDefaultSdlParserOptions())
                    .document(multi)
                    .build());
            return new SchemaParser().buildRegistry(document);
        } catch (InvalidSyntaxException e) {
            throw new RuntimeException(attributedMessage(e), e);
        } catch (IOException e) {
            throw new RuntimeException("Schema parse failed", e);
        }
    }

    /**
     * Rewrites a graphql-java parser exception's message so it names the offending
     * schema file. The upstream message reports only line/column; with
     * {@link MultiSourceReader#trackData(boolean) trackData(true)} the parser
     * populates {@link graphql.language.SourceLocation#getSourceName() sourceName}
     * and source-relative line/column on the exception's location, but does not
     * include them in {@code getMessage()}. Surfacing the source path turns
     * "extra tokens ... at line 15 column 5" into a message that points at the
     * actual file.
     */
    private static String attributedMessage(InvalidSyntaxException e) {
        var location = e.getLocation();
        if (location == null || location.getSourceName() == null) {
            return "Schema parse failed: " + e.getMessage();
        }
        return "Schema parse failed in " + location.getSourceName()
            + " at line " + location.getLine() + " column " + location.getColumn()
            + ": " + e.getMessage();
    }

    private static void addDirectivesSource(MultiSourceReader.Builder builder) {
        var stream = RewriteSchemaLoader.class.getResourceAsStream(DIRECTIVES_RESOURCE);
        if (stream == null) {
            throw new IllegalStateException(DIRECTIVES_RESOURCE + " not found on classpath");
        }
        builder.reader(terminated(new InputStreamReader(stream, StandardCharsets.UTF_8)), DIRECTIVES_RESOURCE);
    }

    /**
     * Wraps {@code inner} so that after it reports EOF the stream emits one final
     * newline character, unless the inner stream already ended with one. Without this,
     * {@link MultiSourceReader} would attribute the first line of each subsequent
     * source to the previous source's name when the previous source does not end with
     * a newline: source-name tracking is line-terminator-based, and an unterminated
     * last line bleeds into the next reader. Rewrite's tag / description-note
     * appliers rely on {@code SourceLocation.getSourceName()} matching the
     * {@code SchemaInput}'s key, so accurate attribution is a correctness
     * requirement, not a cosmetic concern.
     *
     * <p>The last-char check avoids doubling-up the newline for sources that are
     * already terminated (the common case: editors default to a trailing newline).
     * Without it, {@code SourceLocation.line} numbers in parse-error diagnostics
     * would shift by one on the synthetic trailing blank for every non-terminal
     * source in the pipeline.
     */
    private static Reader terminated(Reader inner) {
        return new Reader() {
            private boolean upstreamExhausted = false;
            private boolean done = false;
            private char lastChar;
            private boolean hasLastChar = false;

            @Override
            public int read(char[] buf, int off, int len) throws IOException {
                if (!upstreamExhausted) {
                    int n = inner.read(buf, off, len);
                    if (n > 0) {
                        lastChar = buf[off + n - 1];
                        hasLastChar = true;
                        return n;
                    }
                    if (n == 0) return 0;  // caller passed len==0; pass through
                    upstreamExhausted = true;
                }
                if (done || len <= 0) return -1;
                done = true;
                if (hasLastChar && lastChar == '\n') return -1;  // already terminated
                buf[off] = '\n';
                return 1;
            }

            @Override
            public void close() throws IOException {
                inner.close();
            }
        };
    }

    private static Reader openSource(String path) {
        var filePath = Paths.get(path);
        if (!Files.exists(filePath)) {
            throw new RuntimeException("Schema file not found: " + path);
        }
        try {
            return Files.newBufferedReader(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Schema file unreadable: " + path, e);
        }
    }
}
