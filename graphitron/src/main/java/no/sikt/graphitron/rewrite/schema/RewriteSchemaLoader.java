package no.sikt.graphitron.rewrite.schema;

import graphql.GraphQLError;
import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.SDLDefinition;
import graphql.language.SourceLocation;
import graphql.parser.InvalidSyntaxException;
import graphql.parser.MultiSourceReader;
import graphql.parser.Parser;
import graphql.parser.ParserEnvironment;
import graphql.parser.ParserOptions;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.errors.NonSDLDefinitionError;
import graphql.schema.idl.errors.SchemaProblem;
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
import java.util.Optional;

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
 *
 * <p>Two of the three stages that judge a schema live here, and both keep what survived them
 * rather than refusing wholesale: sources are parsed one at a time, and the surviving definitions
 * are admitted to the registry one at a time. A source that will not parse costs its own
 * declarations and no others; a declaration the registry will not admit costs itself. The refusals
 * come back as data from {@link #parsePerSource}, which is what lets a caller record them and then
 * decide, and {@link #load} is the arm for a caller whose only decision is to fail. The third
 * stage, assembly, is {@link SchemaAssembly}, and it reads what this class produced.
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

        /**
         * The parser's message as it wrote it, which is what the store transcribes. {@link #brief}
         * is a rendering built for the exception's one-liner and drops the explanatory clause on
         * the parser's commonest message shape, so it is the wrong thing to persist: a row is a
         * transcription, and a reader wanting less can render less.
         */
        public String verbatimMessage() {
            return cause.getMessage();
        }

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
     * The outcome of reading a source set: the registry that came of it, one {@link SyntaxFailure}
     * per source the parser rejected, and one {@link SchemaError} per declaration the registry
     * refused to admit.
     *
     * <p>Neither kind of failure subtracts from what its siblings contribute, which is the whole
     * point of reading this way. An author editing one file in a workspace of well-formed ones has
     * a single invalid buffer, and the facts the other files declare are exactly what a question
     * about that buffer needs answered; the same holds one level down, where a declaration that
     * loses a name collision leaves every other declaration in its file standing.
     *
     * @param registry       every definition that parsed and was admitted, the bundled directives
     *                       included; never null, and complete with respect to exactly those
     * @param failures       the rejected sources, in the order they were parsed
     * @param registryErrors the refused declarations, in the order they were offered
     */
    public record PerSourceParse(TypeDefinitionRegistry registry, List<SyntaxFailure> failures,
                                 List<SchemaError> registryErrors) {

        /** Whether either stage refused anything, so a caller that must fail knows to. */
        public boolean rejectedAnything() {
            return !failures.isEmpty() || !registryErrors.isEmpty();
        }
    }

    /**
     * Parses the bundled directives plus every source in {@code userSchemaSources}, one parse per
     * source, and reports the rejected sources instead of failing on them.
     *
     * <p>Each source is parsed alone, and the surviving definitions are then admitted to one
     * registry one at a time, so a refusal at either stage costs exactly what refused it. The
     * definition set is the one a whole-document parse would have produced, less the sources that
     * did not parse; the registry over it is the one {@link SchemaParser#buildRegistry} would have
     * produced, less the declarations it refused. Cross-source problems are still the combining
     * stage's business rather than any single file's: a type two sources both declare is refused
     * here, not there.
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
        var registry = new TypeDefinitionRegistry();
        var registryErrors = new ArrayList<SchemaError>();
        for (Definition definition : definitions) {
            admit(registry, definition)
                .ifPresent(e -> registryErrors.add(SchemaError.of(SchemaError.Stage.REGISTRY, e)));
        }
        return new PerSourceParse(registry, failures, List.copyOf(registryErrors));
    }

    /**
     * Offers one definition to the registry, reporting a refusal instead of throwing.
     *
     * <p>This is {@link SchemaParser#buildRegistry}'s own loop with its terminal throw left out:
     * {@link TypeDefinitionRegistry#add} already reports a refusal as a returned error rather than
     * an exception, and {@code buildRegistry} collects those and throws if any landed. Admitting
     * one at a time keeps the definitions that were admitted, which is the whole difference. The
     * non-SDL guard mirrors the same method's, since a {@code Document} can in principle carry an
     * executable definition (a query operation in a file pointed at the schema loader) that the
     * registry has no slot for.
     */
    @SuppressWarnings("rawtypes")
    private static Optional<GraphQLError> admit(TypeDefinitionRegistry registry, Definition definition) {
        if (definition instanceof SDLDefinition<?> sdl) {
            return registry.add(sdl);
        }
        return Optional.of(new NonSDLDefinitionError(definition));
    }

    /**
     * Parses the bundled directives plus every source in {@code userSchemaSources}, failing on the
     * first source the parser rejected. The parameter is the file arm rather than a string
     * collection because a label has nothing to open: a caller holding one has to decide what that
     * means at its own boundary instead of discovering it as a parse-time surprise here.
     *
     * <p>Callers that want the refusals as data rather than as a thrown exception, so what was
     * admitted is still available, read {@link #parsePerSource} directly.
     */
    public static TypeDefinitionRegistry load(Collection<SchemaSource.File> userSchemaSources) {
        var parse = parsePerSource(userSchemaSources);
        throwIfRejected(parse);
        return parse.registry();
    }

    /**
     * Throws whichever exception the stages' refusals earn, in stage order, or returns having
     * thrown nothing. Each stage keeps the exception it always threw, so a caller that reads the
     * failure surface (the mojo's two catch arms, the dev loop's one-line parse report) is
     * unaffected by refusals having become data on the way here: a parse refusal is the first
     * source's {@link SchemaParseException}, and a registry refusal is the {@code SchemaProblem}
     * graphql-java's own {@code buildRegistry} would have thrown over the same error list.
     *
     * <p>Parse before registry because that is the order they happened in, and because a registry
     * refusal downstream of a file that never parsed is a consequence rather than a cause: naming
     * the syntax error first points at the edit that will fix both.
     */
    public static void throwIfRejected(PerSourceParse parse) {
        if (!parse.failures().isEmpty()) {
            SyntaxFailure first = parse.failures().getFirst();
            throw new SchemaParseException(
                first.attributedMessage(), first.brief(), first.location(), first.cause());
        }
        if (!parse.registryErrors().isEmpty()) {
            throw new SchemaProblem(parse.registryErrors().stream().map(SchemaError::cause).toList());
        }
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
