package no.sikt.graphitron.model.test;

import graphql.language.ArrayValue;
import graphql.language.Directive;
import graphql.language.IntValue;
import graphql.language.NullValue;
import graphql.language.SchemaDefinition;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.parser.Parser;
import no.sikt.graphitron.model.config.OutboxConfig;
import no.sikt.graphitron.model.config.SessionStateConfig;
import no.sikt.graphitron.model.lint.LintConfig;
import no.sikt.graphitron.model.run.CapturedSchema;
import no.sikt.graphitron.model.run.OutputCoordinates;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.config.ClasspathEntry;

import org.jooq.DSLContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * The fact documents under {@code src/test/resources/facts}, and how a graph of one is captured:
 * the one reading of them, for {@code FactExpectationTest}, which checks what each says the store
 * holds, and for {@code graphitron}'s {@code GenerateTestSchemas}, which runs each scenario as a
 * generating run over the same text and classpath, so the code under test is generated from the
 * facts the documents check.
 *
 * <p>A document's folder is a claim about its graph ({@link Claim}). The prelude sits above all
 * four folders and is a file of every graph: a plain document's graph is two files, the prelude and
 * the document, so a claim's source lines are the document's own.
 */
public final class FactDocuments {

    private FactDocuments() {}

    /**
     * The name the prelude's file takes within a graph, after the graph's own name: a file of the
     * graph like any other, which no document name can take since none starts with an underscore.
     */
    public static final String PRELUDE_FILE = "_prelude";

    /** The fact folder, which holds the prelude and the four folders of documents. */
    static final CorpusDocuments.Folder FACTS = new CorpusDocuments.Folder("facts", 0);

    /**
     * A stepped document's file name, whole: the graph, the file within it, and the instant this
     * version of the file is read at as ten-digit epoch seconds. Anchored on the extension and on
     * a graph with no dot of its own, so a name either is a step or is not one; a plain document's
     * name carries no dot, and a name with one that does not match is refused rather than read as
     * whichever kind it happens to resemble.
     */
    static final Pattern STEP =
        Pattern.compile("([^.]+)\\.([a-z]+)\\.(\\d{10})" + Pattern.quote(CorpusDocuments.SUFFIX));

    /**
     * What a folder of fact documents claims about each graph in it, each folder with the floor
     * that stops one which has stopped resolving.
     */
    public enum Claim {
        /**
         * The graph generates: the store records no defect and no schema problem for it, and
         * {@code GenerateTestSchemas} generates it for the tests of generated code.
         */
        SCENARIO("facts/scenarios", 15),
        /** The graph does not generate, and the store says why: at least one defect or problem. */
        DEFECT("facts/defects", 24),
        /** A graph read in steps, which no single schema stands for. */
        INCREMENTAL_CAPTURE("facts/incremental-captures", 18),
        /**
         * No claim yet: a document the generator refuses with nothing stored, or one tripping over
         * a defect it does not state. It only shrinks, each document leaving for a folder whose
         * claim it meets.
         */
        UNDECIDED("facts/undecided", 0);

        private final CorpusDocuments.Folder folder;

        Claim(String name, int minDocuments) {
            this.folder = new CorpusDocuments.Folder(name, minDocuments);
        }

        public CorpusDocuments.Folder folder() {
            return folder;
        }
    }

    /**
     * Every fact document with its folder's claim, in folder then name order. Refuses a name in two
     * folders, so a document's name alone still tells it apart wherever a path is too long to spell,
     * a generated package among them.
     */
    public static Map<CorpusDocuments.Document, Claim> claimed() {
        var claimed = new LinkedHashMap<CorpusDocuments.Document, Claim>();
        var ids = new LinkedHashSet<String>();
        for (var claim : Claim.values()) {
            for (var document : documents(claim)) {
                if (!ids.add(document.id())) {
                    throw new AssertionError(document.id() + " is in two fact folders");
                }
                claimed.put(document, claim);
            }
        }
        return claimed;
    }

    /**
     * One folder's documents. Refuses a stepped document outside
     * {@link Claim#INCREMENTAL_CAPTURE} and a plain one inside it.
     */
    public static List<CorpusDocuments.Document> documents(Claim claim) {
        var documents = CorpusDocuments.documents(claim.folder());
        for (var document : documents) {
            var stepped = STEP.matcher(document.id() + CorpusDocuments.SUFFIX).matches();
            if (stepped != (claim == Claim.INCREMENTAL_CAPTURE)) {
                throw new AssertionError(document.id() + CorpusDocuments.SUFFIX + " is in "
                    + claim.folder().name() + ", which holds " + (stepped ? "no" : "only")
                    + " graphs read in steps");
            }
        }
        return documents;
    }

    /**
     * A document's path, which is its identity: its folder under {@code facts} and its name, as
     * {@code scenarios/field-key-row}. A plain document's graph is named by it, and
     * {@code @factDocument(path:)}, {@code -Dfacts} and every other reference to a document spell
     * it.
     */
    public static String path(Claim claim, CorpusDocuments.Document document) {
        return claim.folder().name().substring(FACTS.name().length() + 1) + "/" + document.id();
    }

    /** The document a path names, if one does. */
    public static Optional<CorpusDocuments.Document> at(String path) {
        return claimed().entrySet().stream()
            .filter(entry -> path(entry.getValue(), entry.getKey()).equals(path))
            .map(Map.Entry::getKey)
            .findFirst();
    }

    /** The prelude's text. */
    public static String prelude() {
        return CorpusDocuments.prelude(FACTS);
    }

    /**
     * A plain document's graph written into {@code directory} as the files it is read over, in the
     * order they are read: the prelude, as {@code <path>._prelude.graphqls}, then the document, as
     * {@code <path>.graphqls}, {@code path} being the document's {@link #path}. Every reading of a plain document reads these files, so what
     * {@code FactExpectationTest} checks is what {@code GenerateTestSchemas} generates from.
     *
     * <p>The prelude is dated a second before the document. Reading order is modification time,
     * name breaking a tie, and two files written in one breath may land either side of a clock tick;
     * stating the order keeps the prelude the incumbent of every coordinate it declares, which is
     * what a document extending its {@code Query} expects.
     */
    public static List<Path> write(Path directory, String path, CorpusDocuments.Document document) {
        var prelude = directory.resolve(path + "." + PRELUDE_FILE + ".graphqls");
        var file = directory.resolve(path + ".graphqls");
        var now = Instant.now();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(prelude, prelude());
            Files.writeString(file, document.sdl());
            Files.setLastModifiedTime(prelude, FileTime.from(now.minusSeconds(1)));
            Files.setLastModifiedTime(file, FileTime.from(now));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.of(prelude, file);
    }

    /** Whether the document states the configuration its graph is captured under. */
    public static boolean statesConfiguration(CorpusDocuments.Document document) {
        return StatedConfiguration.application(List.of(document.sdl())).isPresent();
    }

    /**
     * A plain document's graph, captured under its {@link #path} into the store {@code dsl} writes:
     * against the catalog its configuration names, else {@code fixture}, over {@code classpath},
     * under the configuration it states, from the files {@link #write} lays down in
     * {@code directory}.
     *
     * @return the two schemas the capture built
     */
    public static CapturedSchema capture(DSLContext dsl, Path directory, String path,
                                         CorpusDocuments.Document document, CatalogInput fixture,
                                         List<ClasspathEntry> classpath) {
        var texts = List.of(document.sdl());
        return CapturedStore.captureFilesInto(dsl, directory, path, write(directory, path, document),
            StatedConfiguration.catalog(texts, fixture), classpath, StatedConfiguration.of(texts));
    }

    /**
     * The catalog a plain document's graph is captured against: the one its configuration names,
     * {@code fixture} where it names none, or none at all where it names {@code null}. Graphs share
     * a store only when they share this, so a relation keyed by no graph holds the same rows
     * wherever a document lands.
     */
    public static Optional<String> catalogPackage(CorpusDocuments.Document document,
                                                  CatalogInput fixture) {
        return Optional.ofNullable(StatedConfiguration.catalog(List.of(document.sdl()), fixture))
            .map(CatalogInput::jooqPackage);
    }

    /**
     * The configuration a graph's documents state with {@code @withConfiguration}, as the run
     * configuration a build would have handed the reading. At most one of the texts may carry it; none
     * is a run that declared nothing. The recipe is the harness's own whatever this says.
     */
    static final class StatedConfiguration {

        private static final String DIRECTIVE = "withConfiguration";

        static SubjectConfig of(List<String> texts) {
            return application(texts).map(StatedConfiguration::decode).orElse(SubjectConfig.none());
        }

        /**
         * The catalog the graph is captured against. The harness's own where the configuration
         * leaves {@code jooqPackage} out, which every document stating nothing about a catalog
         * relies on; the package it names; or none where it writes {@code jooqPackage: null}, a run
         * that declared no catalog. All documents share one store, so a graph with no catalog of
         * its own still sits beside the rows the others' catalog wrote.
         */
        static CatalogInput catalog(List<String> texts, CatalogInput fixture) {
            var argument = application(texts)
                .map(directive -> directive.getArgument("jooqPackage"))
                .orElse(null);
            if (argument == null) {
                return fixture;
            }
            if (argument.getValue() instanceof NullValue) {
                return null;
            }
            return new CatalogInput(text(argument.getValue(), "jooqPackage"), fixture.loader());
        }

        static Optional<Directive> application(List<String> texts) {
            var applications = texts.stream()
                .flatMap(text -> new Parser().parseDocument(text).getDefinitions().stream())
                .flatMap(definition -> definition instanceof SchemaDefinition schema
                    ? schema.getDirectives(DIRECTIVE).stream() : java.util.stream.Stream.empty())
                .toList();
            if (applications.size() > 1) {
                throw new AssertionError("a graph states its configuration once; found "
                    + applications.size() + " @" + DIRECTIVE + " applications");
            }
            return applications.stream().findFirst();
        }

        private static SubjectConfig decode(Directive directive) {
            var outputPackage = string(directive, "outputPackage");
            var outputJooqPackage = string(directive, "outputJooqPackage");
            var outputDirectory = string(directive, "outputDirectory");
            Optional<OutputCoordinates> output = Optional.empty();
            if (outputPackage.isPresent() || outputJooqPackage.isPresent() || outputDirectory.isPresent()) {
                if (outputPackage.isEmpty() || outputJooqPackage.isEmpty() || outputDirectory.isEmpty()) {
                    throw new AssertionError("@" + DIRECTIVE + " states the output coordinates "
                        + "together, outputPackage, outputJooqPackage and outputDirectory, or none");
                }
                output = Optional.of(new OutputCoordinates(outputPackage.get(),
                    outputJooqPackage.get(), Path.of(outputDirectory.get())));
            }
            var disabled = strings(directive, "lintDisabledRules");
            var excluded = strings(directive, "lintExcludedTypes");
            var lint = disabled.isEmpty() && excluded.isEmpty()
                ? LintConfig.empty()
                : LintConfig.validated(new LinkedHashSet<>(disabled), excluded);
            var handle = string(directive, "sessionHandle");
            var session = !bool(directive, "sessionState") && handle.isEmpty()
                ? SessionStateConfig.none()
                : SessionStateConfig.from(handle.orElse(null), null, null);
            var outbox = OutboxConfig.from(string(directive, "outboxTable").orElse(null),
                string(directive, "outboxFunction").orElse(null),
                roles(strings(directive, "outboxColumns")),
                roles(strings(directive, "outboxParameters")),
                string(directive, "outboxPayload").orElse(null));
            return new SubjectConfig(Optional.empty(), Optional.empty(),
                string(directive, "supergraph"), output, string(directive, "tenantColumn"), lint,
                session, integer(directive, "maxAllowedPageSize"), outbox);
        }

        /** {@code role=name} strings as the ordered role bindings they state. */
        private static java.util.Map<String, String> roles(List<String> bindings) {
            var out = new java.util.LinkedHashMap<String, String>();
            for (var binding : bindings) {
                int equals = binding.indexOf('=');
                if (equals <= 0) {
                    throw new AssertionError("an outbox role is written role=name; found '" + binding + "'");
                }
                out.put(binding.substring(0, equals).strip(), binding.substring(equals + 1).strip());
            }
            return out;
        }

        private static boolean bool(Directive directive, String name) {
            var argument = directive.getArgument(name);
            if (argument == null) {
                return false;
            }
            if (!(argument.getValue() instanceof graphql.language.BooleanValue value)) {
                throw new AssertionError("@" + DIRECTIVE + "(" + name + ":) takes a boolean");
            }
            return value.isValue();
        }

        private static OptionalInt integer(Directive directive, String name) {
            var argument = directive.getArgument(name);
            if (argument == null) {
                return OptionalInt.empty();
            }
            if (!(argument.getValue() instanceof IntValue value)) {
                throw new AssertionError("@" + DIRECTIVE + "(" + name + ":) takes an integer");
            }
            return OptionalInt.of(value.getValue().intValueExact());
        }

        private static Optional<String> string(Directive directive, String name) {
            var argument = directive.getArgument(name);
            if (argument == null) {
                return Optional.empty();
            }
            return Optional.of(text(argument.getValue(), name));
        }

        private static List<String> strings(Directive directive, String name) {
            var argument = directive.getArgument(name);
            if (argument == null) {
                return List.of();
            }
            if (!(argument.getValue() instanceof ArrayValue list)) {
                throw new AssertionError("@" + DIRECTIVE + "(" + name + ":) is a list of strings");
            }
            return list.getValues().stream().map(value -> text(value, name)).toList();
        }

        private static String text(Value<?> value, String name) {
            if (!(value instanceof StringValue string)) {
                throw new AssertionError("@" + DIRECTIVE + "(" + name + ":) takes strings");
            }
            return string.getValue();
        }
    }
}
