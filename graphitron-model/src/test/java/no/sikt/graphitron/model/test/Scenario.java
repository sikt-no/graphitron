package no.sikt.graphitron.model.test;

import graphql.language.Definition;
import graphql.language.Directive;
import graphql.language.Document;
import graphql.language.IntValue;
import graphql.language.ArrayValue;
import graphql.language.Node;
import graphql.language.ObjectValue;
import graphql.language.SDLDefinition;
import graphql.language.SchemaExtensionDefinition;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.parser.MultiSourceReader;
import graphql.parser.Parser;
import graphql.parser.ParserEnvironment;
import graphql.parser.ParserOptions;
import graphql.schema.idl.TypeDefinitionRegistry;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A fact document run as a scenario: set up a schema directory, capture it as a build would, and
 * verify what the store holds, in the order the document states.
 *
 * <p>A scenario is one SDL file. Two directives set it up and the claim directives verify it:
 * <ul>
 *   <li>{@code @withFacts(docs: [{path:, as:}])} pulls fact documents in. Each is parsed, its own
 *       {@code @withFacts} expanded first, its claims and {@code @tick}s set aside, into one
 *       {@link TypeDefinitionRegistry}: the delta this step adds. The delta is rendered into the
 *       folder, each definition into the file its source names ({@code as}, the path where omitted)
 *       at the line and column it was written at, so a rendered file lines up with its document.
 *       A file rendered again replaces its earlier version, and a document pulled in contributes
 *       the last version of each file it writes, never moving the clock.</li>
 *   <li>{@code @tick(ms:)} moves the clock, which starts at {@link #T0}; every file a step renders
 *       is dated at the clock's time.</li>
 *   <li>A claim ({@code @expectEquals}, {@code @assertContains}, {@code @assertAbsent},
 *       {@code @assertEmpty}) is handed to the verifier as it arrives and once only, after the
 *       folder is captured if anything was rendered since the last capture.</li>
 * </ul>
 *
 * <p>Two states, then: the folder, which is the corpus over time and what a capture reads, and the
 * registry of the step at hand, which exists to be rendered. A delta must merge cleanly; a conflict
 * a scenario means the capture to see is set up across two {@code @withFacts}, so each delta is
 * sound on its own and the folder holds the corpus as a developer's directory would.
 *
 * <p>The scenario's own file is the first delta, beside the prelude, at {@link #T0}.
 */
public final class Scenario {

    /** Where every scenario's clock starts: the same instant for every run, so marks are stable. */
    public static final Instant T0 = Instant.ofEpochSecond(1_000_000_000L);

    /** The file the prelude is rendered as, which no document's path can take. */
    public static final String PRELUDE = FactDocuments.PRELUDE_FILE;

    private static final String WITH_FACTS = "withFacts";
    private static final String TICK = "tick";
    private static final Set<String> CLAIMS =
        Set.of("expectEquals", "assertContains", "assertAbsent", "assertEmpty");

    /** The prelude's own declarations of the setup directives, which no capture reads. */
    private static final Set<String> SETUP_DECLARATIONS = Set.of(WITH_FACTS, TICK, "FactDoc");

    /** Captures the folder as a build would read it, at the clock's time. */
    @FunctionalInterface
    public interface Execute {
        void capture(Path folder, Instant at);
    }

    /** Checks one claim against the store as the last capture left it. */
    @FunctionalInterface
    public interface Verify {
        void claim(Claim claim);
    }

    /**
     * One claim as it arrives: the scenario stating it, where, at what clock time, and the claim
     * itself as an {@code extend schema} application of one claim directive.
     */
    public record Claim(String scenario, int line, Instant at, String sdl, Directive directive) {}

    private final String path;
    private final Function<String, Optional<String>> documents;
    private final String prelude;

    private Scenario(String path, Function<String, Optional<String>> documents, String prelude) {
        this.path = path;
        this.documents = documents;
        this.prelude = prelude;
    }

    /** The fact document at {@code path}, run against the fact folder and its prelude. */
    public static Scenario load(String path) {
        return load(path, p -> FactDocuments.at(p).map(CorpusDocuments.Document::sdl),
            FactDocuments.prelude());
    }

    /** A scenario over documents {@code documents} resolves by path, for a test of the engine. */
    static Scenario load(String path, Function<String, Optional<String>> documents, String prelude) {
        documents.apply(path).orElseThrow(() -> missing(path, path));
        return new Scenario(path, documents, prelude);
    }

    /** This scenario's path, which names its graph. */
    public String path() {
        return path;
    }

    /**
     * Runs the scenario in {@code folder}, which it empties first: renders the prelude and the
     * scenario's own file at {@link #T0}, then walks its directives in document order.
     */
    public void run(Path folder, Execute execute, Verify verify) {
        clear(folder);
        var clock = new Clock();
        var own = parse(path, text(path, path));
        var first = new LinkedHashMap<String, Source>();
        first.put(PRELUDE, new Source(PRELUDE, parse(PRELUDE, prelude), prelude));
        first.put(path, new Source(path, own, text(path, path)));
        render(folder, delta(first), clock.now());
        boolean changed = true;
        for (Definition<?> definition : own.getDefinitions()) {
            if (!(definition instanceof SchemaExtensionDefinition extension)) {
                continue;
            }
            for (var directive : extension.getDirectives()) {
                switch (directive.getName()) {
                    case WITH_FACTS -> {
                        var pulled = new LinkedHashMap<String, Source>();
                        for (var doc : docs(directive)) {
                            expand(doc.path(), doc.as(), path, pulled, new ArrayDeque<>(List.of(path)));
                        }
                        render(folder, delta(pulled), clock.now());
                        changed = true;
                    }
                    case TICK -> clock.advance(milliseconds(directive));
                    default -> {
                        if (CLAIMS.contains(directive.getName())) {
                            if (changed) {
                                execute.capture(folder, clock.now());
                                changed = false;
                            }
                            verify.claim(new Claim(path, line(directive), clock.now(),
                                graphql.language.AstPrinter.printAst(SchemaExtensionDefinition
                                    .newSchemaExtensionDefinition().directive(directive).build()),
                                directive));
                        }
                    }
                }
            }
        }
    }

    /**
     * {@code path}'s document, and first what it pulls in, into {@code sources} under {@code as}:
     * its own {@code @withFacts} expanded in order, its {@code @tick}s not read, so the last version
     * of each file it writes is the one kept.
     */
    private void expand(String path, String as, String namedBy, Map<String, Source> sources,
                        Deque<String> visiting) {
        if (visiting.contains(path)) {
            throw new IllegalArgumentException("@withFacts pulls in a cycle: "
                + String.join(" -> ", visiting.reversed()) + " -> " + path);
        }
        visiting.push(path);
        var text = text(path, namedBy);
        var document = parse(as, text);
        for (var definition : document.getDefinitionsOfType(SchemaExtensionDefinition.class)) {
            for (var directive : definition.getDirectives(WITH_FACTS)) {
                for (var doc : docs(directive)) {
                    expand(doc.path(), doc.as(), path, sources, visiting);
                }
            }
        }
        sources.remove(as);
        sources.put(as, new Source(as, document, text));
        visiting.pop();
    }

    /**
     * The registry one step adds: every source's definitions but its setup and claims. A step must
     * merge cleanly; a conflict meant for the capture is set up across two steps.
     */
    private static Delta delta(Map<String, Source> sources) {
        var registry = new TypeDefinitionRegistry();
        for (var source : sources.values()) {
            for (var definition : kept(source)) {
                registry.add(definition).ifPresent(error -> {
                    throw new IllegalArgumentException("@withFacts cannot merge " + source.name()
                        + " into its step: " + error.getMessage() + ". A conflict the capture is "
                        + "meant to see is set up across two @withFacts");
                });
            }
        }
        return new Delta(registry, List.copyOf(sources.values()));
    }

    /** One step: the registry it merges into and the sources it renders, in the order pulled in. */
    private record Delta(TypeDefinitionRegistry registry, List<Source> sources) {}

    /** One document as a file of the folder: the name it is rendered as, parsed and as written. */
    private record Source(String name, Document document, String text) {}

    /** {@code source}'s definitions a capture reads: all but setup and claims. */
    private static List<SDLDefinition<?>> kept(Source source) {
        var kept = new ArrayList<SDLDefinition<?>>();
        for (Definition<?> definition : source.document().getDefinitions()) {
            if (!(definition instanceof SDLDefinition<?> sdl) || setOnly(definition)
                    || declaresSetup(definition)) {
                continue;
            }
            if (definition instanceof SchemaExtensionDefinition extension) {
                kept.add(extension.transform(builder -> builder.directives(extension.getDirectives()
                    .stream().filter(directive -> !isSetup(directive)).toList())));
            } else {
                kept.add(sdl);
            }
        }
        return kept;
    }

    /**
     * Renders each of {@code delta}'s sources into the folder as its file, dated {@code at}: the
     * source's own text with everything the registry does not hold blanked to spaces, so every
     * kept definition sits at the line and column it was written at.
     */
    private static void render(Path folder, Delta delta, Instant at) {
        for (var source : delta.sources()) {
            var text = blank(source);
            var file = folder.resolve(source.name() + CorpusDocuments.SUFFIX);
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, text);
                Files.setLastModifiedTime(file, FileTime.from(at));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * {@code source}'s text with setup and claims blanked: a definition that is nothing but those,
     * and such a directive inside a definition that is more, each up to whatever follows it.
     */
    private static String blank(Source source) {
        var text = source.text();
        var offsets = new Offsets(text);
        var definitions = source.document().getDefinitions();
        var blanked = new StringBuilder(text);
        for (int i = 0; i < definitions.size(); i++) {
            Definition<?> definition = definitions.get(i);
            int end = i + 1 < definitions.size() ? offsets.of(definitions.get(i + 1)) : text.length();
            if (setOnly(definition) || declaresSetup(definition)) {
                spaces(blanked, offsets.of(definition), end);
            } else if (definition instanceof SchemaExtensionDefinition extension) {
                var directives = extension.getDirectives();
                for (int j = 0; j < directives.size(); j++) {
                    if (isSetup(directives.get(j))) {
                        int to = j + 1 < directives.size() ? offsets.of(directives.get(j + 1)) : end;
                        spaces(blanked, offsets.of(directives.get(j)), to);
                    }
                }
            }
        }
        return blanked.toString();
    }

    /** Whether {@code definition} is an {@code extend schema} carrying setup and claims alone. */
    private static boolean setOnly(Definition<?> definition) {
        return definition instanceof SchemaExtensionDefinition extension
            && !extension.getDirectives().isEmpty()
            && extension.getDirectives().stream().allMatch(Scenario::isSetup)
            && extension.getOperationTypeDefinitions().isEmpty();
    }

    /** Whether {@code definition} is the prelude's declaration of a setup directive or its input. */
    private static boolean declaresSetup(Definition<?> definition) {
        return definition instanceof graphql.language.NamedNode<?> named
            && (definition instanceof graphql.language.DirectiveDefinition
                || definition instanceof graphql.language.InputObjectTypeDefinition)
            && SETUP_DECLARATIONS.contains(named.getName());
    }

    private static boolean isSetup(Directive directive) {
        return directive.getName().equals(WITH_FACTS) || directive.getName().equals(TICK)
            || CLAIMS.contains(directive.getName());
    }

    private static void spaces(StringBuilder text, int from, int to) {
        for (int i = from; i < to; i++) {
            if (text.charAt(i) != '\n' && text.charAt(i) != '\r') {
                text.setCharAt(i, ' ');
            }
        }
    }

    /** One entry of a {@code @withFacts}: the document and the file it is rendered as. */
    private record Doc(String path, String as) {}

    private static List<Doc> docs(Directive directive) {
        var argument = directive.getArgument("docs");
        if (argument == null || !(argument.getValue() instanceof ArrayValue list)) {
            throw new IllegalArgumentException("@withFacts at line " + line(directive)
                + " takes docs: [{path: \"<folder>/<name>\", as: \"<file>\"}]");
        }
        var docs = new ArrayList<Doc>();
        for (Value<?> value : list.getValues()) {
            if (!(value instanceof ObjectValue object)) {
                throw new IllegalArgumentException("@withFacts at line " + line(directive)
                    + " lists something other than {path:, as:}");
            }
            String path = null;
            String as = null;
            for (var field : object.getObjectFields()) {
                if (field.getValue() instanceof StringValue string) {
                    switch (field.getName()) {
                        case "path" -> path = string.getValue();
                        case "as" -> as = string.getValue();
                        default -> { }
                    }
                }
            }
            if (path == null) {
                throw new IllegalArgumentException("@withFacts at line " + line(directive)
                    + " lists a document with no path");
            }
            docs.add(new Doc(path, as == null ? path : as));
        }
        return docs;
    }

    private static long milliseconds(Directive directive) {
        var argument = directive.getArgument("ms");
        if (argument == null || !(argument.getValue() instanceof IntValue ms)
                || ms.getValue().signum() <= 0) {
            throw new IllegalArgumentException("@tick at line " + line(directive)
                + " takes ms: a positive number of milliseconds; the clock only moves forward");
        }
        return ms.getValue().longValueExact();
    }

    private static int line(Node<?> node) {
        return Optional.ofNullable(node.getSourceLocation()).map(SourceLocation::getLine).orElse(-1);
    }

    private String text(String path, String namedBy) {
        return documents.apply(path).orElseThrow(() -> missing(path, namedBy));
    }

    private static IllegalArgumentException missing(String path, String namedBy) {
        return new IllegalArgumentException(namedBy + " names the fact document '" + path
            + "', which does not exist; a path is a folder under facts and a name, as "
            + "scenarios/field-key-row");
    }

    private static Document parse(String sourceName, String text) {
        try (var reader = MultiSourceReader.newMultiSourceReader()
                .reader(new StringReader(text), sourceName)
                .trackData(true)
                .build()) {
            return new Parser().parseDocument(ParserEnvironment.newParserEnvironment()
                .parserOptions(ParserOptions.getDefaultSdlParserOptions())
                .document(reader)
                .build());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void clear(Path folder) {
        try {
            if (Files.exists(folder)) {
                try (Stream<Path> paths = Files.walk(folder)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        if (!path.equals(folder)) {
                            Files.delete(path);
                        }
                    }
                }
            }
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The scenario's clock, starting at {@link #T0} and moved only by {@code @tick}. */
    private static final class Clock {
        private Instant now = T0;

        Instant now() {
            return now;
        }

        void advance(long milliseconds) {
            now = now.plusMillis(milliseconds);
        }
    }

    /** Line and column to offset, for one text. */
    private static final class Offsets {
        private final List<Integer> lineStarts = new ArrayList<>();

        Offsets(String text) {
            lineStarts.add(0);
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    lineStarts.add(i + 1);
                }
            }
        }

        int of(Node<?> node) {
            var location = node.getSourceLocation();
            return lineStarts.get(location.getLine() - 1) + location.getColumn() - 1;
        }
    }
}
