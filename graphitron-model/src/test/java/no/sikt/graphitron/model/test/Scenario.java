package no.sikt.graphitron.model.test;

import graphql.language.ArrayValue;
import graphql.language.AstPrinter;
import graphql.language.Definition;
import graphql.language.Directive;
import graphql.language.Document;
import graphql.language.EnumTypeDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.IntValue;
import graphql.language.NamedNode;
import graphql.language.Node;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectValue;
import graphql.language.SDLDefinition;
import graphql.language.SchemaExtensionDefinition;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.language.TypeDefinition;
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
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A fact document run as a scenario: set up a schema directory, capture it as a build would, and
 * verify what the store holds, in the order the document states. A scenario is one graph, named by
 * its path; every file it renders is a source of that graph.
 *
 * <p>A scenario is one SDL file, walked top to bottom:
 * <ul>
 *   <li>{@code @withFacts(docs: [{path:, as:}])} pulls fact documents in. Each is parsed, its own
 *       {@code @withFacts} expanded first, its engine directives set aside, into one
 *       {@link TypeDefinitionRegistry}: the delta this step adds, rendered into the folder, each
 *       definition into the file its source names ({@code as}, the path where omitted) at the line
 *       and column it was written at. A file rendered again replaces its earlier version, and a
 *       document pulled in contributes the last version of each file it writes and moves no
 *       clock.</li>
 *   <li>The scenario's own definitions are a step of their own, rendered where the first of them
 *       is written, so a {@code @withFacts} above them is in the folder before them.</li>
 *   <li>{@code @tick(ms:)} moves the clock, which starts at {@link #T0}; every file a step renders
 *       is dated at the clock's time, a microsecond after the file before it at the same time, so
 *       reading order follows writing order.</li>
 *   <li>A document with nothing to render, claims or configuration alone or an emptied version,
 *       writes no file and takes away the earlier version of its own; its step is still a reading,
 *       at that time, of the files that stand.</li>
 *   <li>Every render is followed by a capture at the clock's time. A claim ({@code @expectEquals},
 *       {@code @assertContains}, {@code @assertAbsent}, {@code @assertEmpty}) is handed to the
 *       verifier as it arrives and once only, to check against the store as the last capture left
 *       it.</li>
 *   <li>{@code @withConfiguration} is a step of the scenario's own script: the last one the walk has
 *       passed is the configuration every capture after it runs under, so a scenario can change its
 *       configuration between readings. In a document pulled in it is ignored.</li>
 * </ul>
 *
 * <p>The engine owns those directives. They are never rendered, since none of them is part of an
 * example, and a rendered file holds the example alone.
 *
 * <p><b>Duplicates compose.</b> A document is meant to read as a standalone example, so it states
 * its types whole: two documents may both write {@code type Film @table(name: "film")}. Where a
 * type the folder already defines is defined again compatibly, the later definition is rendered as
 * an extension: its keyword becomes {@code extend}, and the fields and directive applications it
 * repeats identically are blanked, so only what it adds is left. A definition that disagrees, a
 * field of another type, is rendered as written, for the capture to see: that is how a scenario
 * states a conflict on purpose, and an unintended one fails the scenario's own folder claim.
 */
public final class Scenario {

    /** Where every scenario's clock starts: the same instant for every run, so marks are stable. */
    public static final Instant T0 = Instant.ofEpochSecond(1_000_000_000L);

    private static final String WITH_FACTS = "withFacts";
    private static final String TICK = "tick";
    private static final String WITH_CONFIGURATION = "withConfiguration";
    private static final Set<String> CLAIMS =
        Set.of("expectEquals", "assertContains", "assertAbsent", "assertEmpty");

    /**
     * Captures the folder as a build would read it, at the clock's time, under the configuration in
     * effect ({@code extend schema @withConfiguration(...)}, or empty where the script has stated
     * none yet); later captures are warm.
     */
    @FunctionalInterface
    public interface Execute {
        void capture(Path folder, Instant at, boolean first, String configuration);
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
    private final String text;
    private final Function<String, Optional<String>> documents;

    private Scenario(String path, String text, Function<String, Optional<String>> documents) {
        this.path = path;
        this.text = text;
        this.documents = documents;
    }

    /** The fact document at {@code path}, pulling documents in from the fact folder. */
    public static Scenario load(String path) {
        return load(path, p -> FactDocuments.at(p).map(CorpusDocuments.Document::sdl));
    }

    /** A scenario over documents {@code documents} resolves by path, for a test of the engine. */
    static Scenario load(String path, Function<String, Optional<String>> documents) {
        var text = documents.apply(path).orElseThrow(() -> missing(path, path));
        return new Scenario(path, text, documents);
    }

    /** This scenario's path, which names its graph. */
    public String path() {
        return path;
    }

    /** This scenario's own text, where its {@code @withConfiguration} is read from. */
    public String text() {
        return text;
    }

    /**
     * The scenario's final schema as one text: its folder rendered with nothing captured, the files
     * joined in the order a capture reads them. For a reader that wants the example a document
     * composes, its building blocks pulled in and its duplicates composed, without a store: the
     * documentation renderer.
     */
    public String composed() {
        try {
            var folder = Files.createTempDirectory("graphitron-scenario");
            try {
                run(folder, (dir, at, first, configuration) -> { }, claim -> { });
                try (Stream<Path> files = Files.walk(folder)) {
                    var ordered = files.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing((Path file) -> {
                            try {
                                return Files.getLastModifiedTime(file);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        }).thenComparing(Path::toString))
                        .toList();
                    var joined = new StringBuilder();
                    for (var file : ordered) {
                        joined.append(Files.readString(file)).append('\n');
                    }
                    return joined.toString();
                }
            } finally {
                clear(folder);
                Files.delete(folder);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs the scenario in {@code folder}, which it empties first. */
    public void run(Path folder, Execute execute, Verify verify) {
        new Run(folder, execute, verify).walk();
    }

    /** One run: the folder's state, the clock, and the captures asked for so far. */
    private final class Run {
        private final Path folder;
        private final Execute execute;
        private final Verify verify;
        private final Map<String, List<TypeDefinition<?>>> rendered = new LinkedHashMap<>();
        private Instant clock = T0;
        private long written;
        private int captures;
        private String configuration = "";

        Run(Path folder, Execute execute, Verify verify) {
            this.folder = folder;
            this.execute = execute;
            this.verify = verify;
        }

        void walk() {
            clear(folder);
            var own = parse(path, text);
            boolean ownRendered = false;
            for (Definition<?> definition : own.getDefinitions()) {
                if (!engineOnly(definition)) {
                    if (!ownRendered) {
                        var sources = new LinkedHashMap<String, Source>();
                        sources.put(path, new Source(path, own, text));
                        step(sources);
                        ownRendered = true;
                    }
                    if (!(definition instanceof SchemaExtensionDefinition)) {
                        continue;
                    }
                }
                for (var directive : ((SchemaExtensionDefinition) definition).getDirectives()) {
                    switch (directive.getName()) {
                        case WITH_FACTS -> {
                            var pulled = new LinkedHashMap<String, Source>();
                            for (var doc : docs(directive)) {
                                expand(doc.path(), doc.as(), path, pulled, new ArrayDeque<>(List.of(path)));
                            }
                            step(pulled);
                        }
                        case TICK -> clock = clock.plusMillis(milliseconds(directive));
                        case WITH_CONFIGURATION -> configuration = AstPrinter.printAst(
                            SchemaExtensionDefinition.newSchemaExtensionDefinition().directive(directive).build());
                        default -> {
                            if (CLAIMS.contains(directive.getName())) {
                                if (captures == 0) {
                                    throw new IllegalArgumentException(path + " states a claim at line "
                                        + line(directive) + " before anything is rendered to claim about");
                                }
                                verify.claim(new Claim(path, line(directive), clock,
                                    AstPrinter.printAst(SchemaExtensionDefinition
                                        .newSchemaExtensionDefinition().directive(directive).build()),
                                    directive));
                            }
                        }
                    }
                }
            }
        }

        /** Renders one step's sources into the folder, then captures it. */
        private void step(Map<String, Source> sources) {
            var registry = new TypeDefinitionRegistry();
            for (var source : sources.values()) {
                var composed = compose(source);
                for (var definition : composed.kept()) {
                    // A conflict the capture is meant to see is rendered as written; the registry
                    // holds the step's sound part.
                    registry.add(definition);
                }
                if (!composed.renders()) {
                    // Nothing to render: a document of claims or configuration alone, or an emptied
                    // version. It writes no file and takes away the earlier version of its own; the
                    // step is still a reading, at this time, of the files that stand.
                    remove(source.name());
                    rendered.remove(source.name());
                } else {
                    write(source.name(), composed.text());
                    rendered.put(source.name(), composed.definitions());
                }
            }
            execute.capture(folder, clock, captures++ == 0, configuration);
        }

        /**
         * {@code source} as rendered against the folder: its engine directives blanked, and each
         * type the folder already defines turned into an extension where it agrees with it.
         */
        private Composed compose(Source source) {
            var text = source.text();
            var offsets = new Offsets(text);
            var blanks = new ArrayList<int[]>();
            var inserts = new TreeMap<Integer, String>();
            var definitions = source.document().getDefinitions();
            var kept = new ArrayList<SDLDefinition<?>>();
            var bases = new ArrayList<TypeDefinition<?>>();
            var defined = defined(source.name());
            boolean renders = false;
            for (int i = 0; i < definitions.size(); i++) {
                Definition<?> definition = definitions.get(i);
                int start = offsets.of(definition);
                int end = i + 1 < definitions.size() ? offsets.of(definitions.get(i + 1)) : text.length();
                if (engineOnly(definition)) {
                    blanks.add(new int[]{start, end});
                    continue;
                }
                if (definition instanceof SchemaExtensionDefinition extension) {
                    var directives = extension.getDirectives();
                    for (int j = 0; j < directives.size(); j++) {
                        if (isEngine(directives.get(j))) {
                            int to = j + 1 < directives.size() ? offsets.of(directives.get(j + 1)) : end;
                            blanks.add(new int[]{offsets.of(directives.get(j)), to});
                        }
                    }
                    kept.add(extension.transform(builder -> builder.directives(extension.getDirectives()
                        .stream().filter(directive -> !isEngine(directive)).toList())));
                    renders = true;
                    continue;
                }
                if (definition instanceof TypeDefinition<?> type && !isExtension(type)) {
                    var base = defined.get(type.getName());
                    if (base != null && hasMembers(type) && compatible(base, type)) {
                        extend(text, offsets, type, base, start, end, blanks, inserts);
                        renders = true;
                        continue;
                    }
                    if (base != null && printed(base).equals(printed(type))) {
                        blanks.add(new int[]{start, end});
                        continue;
                    }
                    // Only another source's definition composes: a type repeated within one document
                    // is that document's own statement, rendered as written.
                    bases.add(type);
                }
                if (definition instanceof SDLDefinition<?> sdl) {
                    kept.add(sdl);
                    renders = true;
                }
            }
            var out = new StringBuilder(text);
            for (var blank : blanks) {
                spaces(out, blank[0], blank[1]);
            }
            for (var insert : inserts.descendingMap().entrySet()) {
                out.insert(insert.getKey(), insert.getValue());
            }
            return new Composed(out.toString(), kept, bases, renders);
        }

        /** The base definitions the folder holds outside {@code source}, by type name. */
        private Map<String, TypeDefinition<?>> defined(String source) {
            var defined = new LinkedHashMap<String, TypeDefinition<?>>();
            rendered.forEach((name, types) -> {
                if (!name.equals(source)) {
                    types.forEach(type -> defined.putIfAbsent(type.getName(), type));
                }
            });
            return defined;
        }

        private void remove(String name) {
            try {
                Files.deleteIfExists(folder.resolve(name + CorpusDocuments.SUFFIX));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void write(String name, String text) {
            var file = folder.resolve(name + CorpusDocuments.SUFFIX);
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, text);
                Files.setLastModifiedTime(file, FileTime.from(clock.plusNanos(1_000 * written++)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * One source as rendered: its text, its definitions the step's registry merges, the types it
     * defines, and whether it renders anything at all, an extension counting.
     */
    private record Composed(String text, List<SDLDefinition<?>> kept, List<TypeDefinition<?>> definitions,
                            boolean renders) {}

    /** One document as a file of the folder: the name it is rendered as, parsed and as written. */
    private record Source(String name, Document document, String text) {}

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
        var pulledText = documents.apply(path).orElseThrow(() -> missing(path, namedBy));
        var document = parse(as, pulledText);
        for (var definition : document.getDefinitionsOfType(SchemaExtensionDefinition.class)) {
            for (var directive : definition.getDirectives(WITH_FACTS)) {
                for (var doc : docs(directive)) {
                    expand(doc.path(), doc.as(), path, sources, visiting);
                }
            }
        }
        sources.remove(as);
        sources.put(as, new Source(as, document, pulledText));
        visiting.pop();
    }

    /**
     * Whether {@code base}'s definition and {@code type}'s agree, so the later can extend the first:
     * the same kind, and every field, input field or value and every directive application they
     * both carry printed alike.
     */
    private static boolean compatible(TypeDefinition<?> base, TypeDefinition<?> type) {
        if (base.getClass() != type.getClass()) {
            return false;
        }
        var baseMembers = members(base);
        for (var member : members(type).entrySet()) {
            var other = baseMembers.get(member.getKey());
            if (other != null && !printed(other).equals(printed(member.getValue()))) {
                return false;
            }
        }
        var baseDirectives = new LinkedHashMap<String, List<String>>();
        base.getDirectives().forEach(d -> baseDirectives.computeIfAbsent(d.getName(), k -> new ArrayList<>())
            .add(AstPrinter.printAst(d)));
        for (var directive : type.getDirectives()) {
            var same = baseDirectives.get(directive.getName());
            if (same != null && !same.contains(AstPrinter.printAst(directive))) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code type} is a kind whose members an extension can add to. */
    private static boolean hasMembers(TypeDefinition<?> type) {
        return type instanceof ObjectTypeDefinition || type instanceof InterfaceTypeDefinition
            || type instanceof InputObjectTypeDefinition || type instanceof EnumTypeDefinition;
    }

    /**
     * Renders {@code type} as an extension of {@code base}: {@code extend} before its keyword, its
     * description and the members and directive applications it repeats blanked.
     */
    private static void extend(String text, Offsets offsets, TypeDefinition<?> type, TypeDefinition<?> base,
                               int start, int end, List<int[]> blanks, Map<Integer, String> inserts) {
        int keyword = start;
        if (type instanceof graphql.language.AbstractDescribedNode<?> described
                && described.getDescription() != null) {
            keyword = keywordAfterDescription(text, start,
                offsets.of(described.getDescription().getSourceLocation()));
            blanks.add(new int[]{start, keyword});
        }
        inserts.put(keyword, "extend ");
        var baseMembers = members(base);
        var ownMembers = new ArrayList<>(members(type).values());
        for (int i = 0; i < ownMembers.size(); i++) {
            Node<?> member = ownMembers.get(i);
            var same = baseMembers.get(((NamedNode<?>) member).getName());
            if (same != null) {
                int to = i + 1 < ownMembers.size() ? offsets.of(ownMembers.get(i + 1))
                    : text.lastIndexOf('}', end - 1);
                blanks.add(new int[]{offsets.of(member), to});
            }
        }
        var baseDirectives = base.getDirectives().stream().map(AstPrinter::printAst).toList();
        for (var directive : type.getDirectives()) {
            if (baseDirectives.contains(AstPrinter.printAst(directive))) {
                int from = offsets.of(directive);
                blanks.add(new int[]{from, endOfDirective(text, from)});
            }
        }
    }

    /** {@code type}'s fields, input fields or enum values, by name; none for any other kind. */
    private static Map<String, Node<?>> members(TypeDefinition<?> type) {
        var members = new LinkedHashMap<String, Node<?>>();
        switch (type) {
            case ObjectTypeDefinition object -> object.getFieldDefinitions().forEach(f -> members.put(f.getName(), f));
            case InterfaceTypeDefinition object -> object.getFieldDefinitions().forEach(f -> members.put(f.getName(), f));
            case InputObjectTypeDefinition input -> input.getInputValueDefinitions().forEach(f -> members.put(f.getName(), f));
            case EnumTypeDefinition enumeration -> enumeration.getEnumValueDefinitions().forEach(v -> members.put(v.getName(), v));
            default -> { }
        }
        return members;
    }

    private static String printed(Node<?> node) {
        return AstPrinter.printAstCompact(node);
    }

    private static boolean isExtension(TypeDefinition<?> type) {
        return type.getClass().getSimpleName().endsWith("Extension")
            || type.getClass().getSimpleName().endsWith("ExtensionDefinition");
    }

    /** Where the keyword after a description starts, the description spanning from {@code from}. */
    private static int keywordAfterDescription(String text, int start, int from) {
        int i = from;
        if (text.startsWith("\"\"\"", i)) {
            i = text.indexOf("\"\"\"", i + 3) + 3;
        } else {
            i = text.indexOf('"', i + 1) + 1;
        }
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Where a directive application starting at {@code from} ends: after its name, or its arguments. */
    private static int endOfDirective(String text, int from) {
        int i = from + 1;
        while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) {
            i++;
        }
        int j = i;
        while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
            j++;
        }
        if (j >= text.length() || text.charAt(j) != '(') {
            return i;
        }
        int depth = 0;
        for (int k = j; k < text.length(); k++) {
            char c = text.charAt(k);
            if (c == '"') {
                k = text.startsWith("\"\"\"", k) ? text.indexOf("\"\"\"", k + 3) + 2 : skipString(text, k);
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return k + 1;
            }
        }
        return text.length();
    }

    private static int skipString(String text, int quote) {
        for (int k = quote + 1; k < text.length(); k++) {
            if (text.charAt(k) == '\\') {
                k++;
            } else if (text.charAt(k) == '"') {
                return k;
            }
        }
        return text.length();
    }

    /**
     * Whether {@code text} states an example of its own: any definition beside the engine's
     * directives. A document that does not is all claims over what it pulls in.
     */
    static boolean statesExample(String text) {
        return parse("example", text).getDefinitions().stream().anyMatch(d -> !engineOnly(d));
    }

    /** Whether {@code definition} is an {@code extend schema} carrying engine directives alone. */
    private static boolean engineOnly(Definition<?> definition) {
        return definition instanceof SchemaExtensionDefinition extension
            && !extension.getDirectives().isEmpty()
            && extension.getDirectives().stream().allMatch(Scenario::isEngine)
            && extension.getOperationTypeDefinitions().isEmpty();
    }

    private static boolean isEngine(Directive directive) {
        var name = directive.getName();
        return name.equals(WITH_FACTS) || name.equals(TICK) || name.equals(WITH_CONFIGURATION)
            || CLAIMS.contains(name);
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
            String docPath = null;
            String as = null;
            for (var field : object.getObjectFields()) {
                if (field.getValue() instanceof StringValue string) {
                    switch (field.getName()) {
                        case "path" -> docPath = string.getValue();
                        case "as" -> as = string.getValue();
                        default -> { }
                    }
                }
            }
            if (docPath == null) {
                throw new IllegalArgumentException("@withFacts at line " + line(directive)
                    + " lists a document with no path");
            }
            docs.add(new Doc(docPath, as == null ? docPath : as));
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

    private static IllegalArgumentException missing(String path, String namedBy) {
        return new IllegalArgumentException(namedBy + " names the fact document '" + path
            + "', which does not exist; a path is a folder under facts and a name, as "
            + "scenarios/field-key-row");
    }

    private static Document parse(String sourceName, String text) {
        // A file holding nothing but comments is a version too, an emptied one; graphql-java refuses
        // to parse a document without a definition.
        if (text.lines().map(String::strip).allMatch(line -> line.isEmpty() || line.startsWith("#"))) {
            return Document.newDocument().build();
        }
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
                    for (var file : paths.sorted(Comparator.reverseOrder()).toList()) {
                        if (!file.equals(folder)) {
                            Files.delete(file);
                        }
                    }
                }
            }
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
            return of(node.getSourceLocation());
        }

        int of(SourceLocation location) {
            return lineStarts.get(location.getLine() - 1) + location.getColumn() - 1;
        }
    }
}
