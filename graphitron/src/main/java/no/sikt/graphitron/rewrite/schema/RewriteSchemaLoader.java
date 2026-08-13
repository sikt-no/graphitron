package no.sikt.graphitron.rewrite.schema;

import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.SourceLocation;
import graphql.parser.InvalidSyntaxException;
import graphql.parser.MultiSourceReader;
import graphql.parser.Parser;
import graphql.parser.ParserEnvironment;
import graphql.parser.ParserOptions;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.SchemaParseException;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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

    /**
     * The {@link MultiSourceReader} source-name under which the bundled
     * {@code directives.graphqls} is registered (see {@link #parseDirectives}).
     * graphql-java stamps this string onto the {@code SourceLocation} of every
     * definition the bundled source contributes (the directive definitions plus the
     * inputs/enums they reference). Consumers that walk the
     * {@link TypeDefinitionRegistry} for user-authored declarations (the LSP's
     * goto-definition type-location map) filter this source out: it is a classpath
     * resource name, not a file path a consumer can open.
     */
    public static final String DIRECTIVES_SOURCE_NAME = DIRECTIVES_RESOURCE;

    private RewriteSchemaLoader() {}

    /**
     * Returns the bundled {@code directives.graphqls} SDL text. Consolidates
     * what was previously two private constants ({@code DIRECTIVES_RESOURCE}
     * here and {@code DeprecationMarkers.DIRECTIVES_RESOURCE} in the LSP
     * module) into a single producer-side accessor. Callers that need to
     * parse the directive surface (the LSP's vocabulary, drift checks)
     * read through this method rather than reaching for the resource path.
     */
    public static String directivesSdl() {
        var stream = RewriteSchemaLoader.class.getResourceAsStream(DIRECTIVES_RESOURCE);
        if (stream == null) {
            throw new IllegalStateException(DIRECTIVES_RESOURCE + " not found on classpath");
        }
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var buf = new char[4096];
            var sb = new StringBuilder();
            int n;
            while ((n = reader.read(buf)) >= 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + DIRECTIVES_RESOURCE, e);
        }
    }

    /**
     * One source the parser rejected: which file, why, and where. Carries the offending exception
     * so a caller that still wants to fail keeps the original cause, and derives the file-attributed
     * message rather than storing a second copy of what {@link #location} and {@link #brief} already
     * say.
     *
     * @param sourceName the source the loader was parsing, always known even when the parser
     *                   reported no location
     * @param brief      the first-sentence reason, stripped of the redundant offending-token tail
     * @param location   the offending site, or {@code null} where the parser reported none
     */
    public record SyntaxFailure(String sourceName, String brief, SourceLocation location,
                                InvalidSyntaxException cause) {

        /** The file-attributed one-liner {@link SchemaParseException#getMessage()} carries. */
        public String attributedMessage() {
            if (location == null || location.getSourceName() == null) {
                return "Schema parse failed: " + brief;
            }
            return "Schema parse failed in " + location.getSourceName()
                + " at line " + location.getLine() + " column " + location.getColumn()
                + ": " + brief;
        }
    }

    /**
     * The outcome of parsing a source set file by file: the registry the sources that parsed
     * produced, and one {@link SyntaxFailure} per source that did not.
     *
     * <p>A failure never subtracts from what its siblings contribute. That is the whole point of
     * parsing per file: an author editing one schema file in a workspace of well-formed ones has a
     * single invalid buffer, and the facts the other files declare are exactly what a question
     * about that buffer needs answered.
     *
     * @param registry the definitions every source that parsed contributed, the bundled directives
     *                 included; never null and never partial with respect to those sources
     * @param failures the rejected sources, in the order they were parsed
     */
    public record PerSourceParse(TypeDefinitionRegistry registry, List<SyntaxFailure> failures) {}

    /**
     * Parses the bundled directives plus every source in {@code userSchemaSources}, one parse per
     * source, and reports the rejected sources instead of failing on them.
     *
     * <p>Each source is parsed alone and the surviving definitions are handed to a single
     * {@link SchemaParser#buildRegistry} call, so the registry is assembled from exactly the
     * definition set a whole-document parse would have assembled it from, less the sources that
     * did not parse. Registry-level problems (a type two sources both declare) therefore still
     * surface where they always did, from that one call: they are the combining stage's business,
     * not a property of any single file.
     *
     * <p>Splitting the parse widens one incidental limit. The parser's token budget applied to the
     * concatenation before and applies per source now, so a source set that tripped it as a
     * concatenation may parse.
     */
    // Raw Definition is graphql-java's own declaration on both ends of this accumulation
    // (Document.getDefinitions and Document.Builder.definitions); parameterising it here would
    // need a cast through a wildcard list, which buys no checking the API can honour.
    @SuppressWarnings("rawtypes")
    public static PerSourceParse parsePerSource(Collection<SchemaSource.File> userSchemaSources) {
        var definitions = new ArrayList<Definition>();
        var failures = new ArrayList<SyntaxFailure>();
        definitions.addAll(parseDirectives().getDefinitions());
        for (SchemaSource.File source : userSchemaSources) {
            Reader reader = openSource(source.path());
            try {
                definitions.addAll(parseSource(source.sourceName(), reader).getDefinitions());
            } catch (InvalidSyntaxException e) {
                failures.add(new SyntaxFailure(
                    source.sourceName(), firstSentence(e.getMessage()), e.getLocation(), e));
            }
        }
        var document = Document.newDocument().definitions(definitions).build();
        return new PerSourceParse(new SchemaParser().buildRegistry(document), failures);
    }

    /**
     * Parses the bundled directives plus every source in {@code userSchemaSources}, failing on the
     * first source the parser rejected. The parameter is the file arm rather than a string
     * collection because a label has nothing to open: a caller holding one has to decide what that
     * means at its own boundary instead of discovering it as a parse-time surprise here.
     *
     * <p>Callers that want the rejected sources as data rather than as a thrown exception, so the
     * sources that did parse are still available, read {@link #parsePerSource} directly.
     */
    public static TypeDefinitionRegistry load(Collection<SchemaSource.File> userSchemaSources) {
        var parse = parsePerSource(userSchemaSources);
        if (!parse.failures().isEmpty()) {
            SyntaxFailure first = parse.failures().getFirst();
            throw new SchemaParseException(
                first.attributedMessage(), first.brief(), first.location(), first.cause());
        }
        return parse.registry();
    }

    /**
     * Parses one source alone, through a single-reader {@link MultiSourceReader} so the parser
     * stamps {@code sourceName} and source-relative line/column onto every definition and onto a
     * syntax error's location. The reader is closed whether the parse succeeded or threw.
     *
     * <p>One reader per parse is what makes that attribution structural. The tag and
     * description-note appliers match {@code SourceLocation.getSourceName()} against the
     * {@code SchemaInput}'s key and capture derives each row's source from the same field, so a
     * definition attributed to the wrong file is a correctness failure, not a cosmetic one. With a
     * single reader there is no adjacent source to attribute a line to.
     */
    private static Document parseSource(String sourceName, Reader reader) {
        try (var multi = MultiSourceReader.newMultiSourceReader()
                .reader(reader, sourceName)
                .trackData(true)
                .build()) {
            return new Parser().parseDocument(
                ParserEnvironment.newParserEnvironment()
                    .parserOptions(ParserOptions.getDefaultSdlParserOptions())
                    .document(multi)
                    .build());
        } catch (IOException e) {
            throw new RuntimeException("Schema parse failed", e);
        }
    }

    /**
     * Parses the bundled {@code directives.graphqls}. Its failure is this module shipping a broken
     * resource, not an author's mistake, so it throws rather than joining the reported failures:
     * a run that recorded it as one would carry a whole directive vocabulary's absence as data.
     */
    private static Document parseDirectives() {
        var stream = RewriteSchemaLoader.class.getResourceAsStream(DIRECTIVES_RESOURCE);
        if (stream == null) {
            throw new IllegalStateException(DIRECTIVES_RESOURCE + " not found on classpath");
        }
        try {
            return parseSource(DIRECTIVES_SOURCE_NAME,
                new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (InvalidSyntaxException e) {
            throw new IllegalStateException("bundled " + DIRECTIVES_RESOURCE + " does not parse", e);
        }
    }

    /**
     * Takes the first sentence of a graphql-java parser message. The upstream format is
     * {@code "Invalid syntax encountered. <subclass-detail>. Offending token '<X>' at line N column M"}
     * (see graphql-java's {@code InvalidSyntaxException.toMessage}); the subclass-detail and the
     * trailing offending-token line/column are redundant once the file path and source-relative
     * coordinates are in the message prefix.
     */
    private static String firstSentence(String message) {
        if (message == null) return "";
        int cut = message.indexOf(". ");
        return cut > 0 ? message.substring(0, cut + 1) : message;
    }

    private static Reader openSource(Path filePath) {
        if (!Files.exists(filePath)) {
            throw new RuntimeException("Schema file not found: " + filePath);
        }
        try {
            return Files.newBufferedReader(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Schema file unreadable: " + filePath, e);
        }
    }
}
