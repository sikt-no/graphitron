package no.sikt.graphitron.model.test;

import graphql.language.ArrayValue;
import graphql.language.Directive;
import graphql.language.IntValue;
import graphql.language.NullValue;
import graphql.language.ObjectValue;
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
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaSource;
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
 * <p>A document's folder is a claim about its graph ({@link Claim}). A document is a
 * {@link Scenario}: it states its own example and pulls in what it builds on, the building blocks
 * under {@code layouts} and {@code shapes} ({@link #buildingBlocks()}), with {@code @withFacts}.
 */
public final class FactDocuments {

    private FactDocuments() {}

    /** The fact folder, which holds the engine's directive declarations and the document folders. */
    static final CorpusDocuments.Folder FACTS = new CorpusDocuments.Folder("facts", 0);

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

    /** One folder's documents. */
    public static List<CorpusDocuments.Document> documents(Claim claim) {
        return CorpusDocuments.documents(claim.folder());
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

    /** The document a path names, a scenario or a building block, if one does. */
    public static Optional<CorpusDocuments.Document> at(String path) {
        return claimed().entrySet().stream()
            .filter(entry -> path(entry.getValue(), entry.getKey()).equals(path))
            .map(Map.Entry::getKey)
            .findFirst()
            .or(() -> Optional.ofNullable(buildingBlocks().get(path)));
    }

    /** The folders of building blocks: documents other documents pull in, no folder claim on them. */
    static final List<String> BUILDING_BLOCKS = List.of("layouts", "shapes");

    /**
     * Every building block by path: the documents under {@code layouts} and {@code shapes}, which a
     * scenario pulls in with {@code @withFacts} and which no folder claim applies to. A folder that
     * does not exist yet holds none.
     */
    public static Map<String, CorpusDocuments.Document> buildingBlocks() {
        var blocks = new java.util.TreeMap<String, CorpusDocuments.Document>();
        for (var name : BUILDING_BLOCKS) {
            var folder = new CorpusDocuments.Folder(FACTS.name() + "/" + name, 0);
            if (folder.candidates().stream().noneMatch(Files::isDirectory)) {
                continue;
            }
            var root = CorpusDocuments.folder(folder);
            try (var files = Files.walk(root)) {
                for (var file : files.filter(f -> f.toString().endsWith(CorpusDocuments.SUFFIX)).toList()) {
                    var relative = root.relativize(file).toString().replace('\\', '/');
                    var id = relative.substring(0, relative.length() - CorpusDocuments.SUFFIX.length());
                    blocks.put(name + "/" + id, new CorpusDocuments.Document(id, Files.readString(file), null));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return blocks;
    }

    /**
     * The engine's own directives, declared for an editor and the documentation renderer to read a
     * document against; never rendered into a graph.
     */
    public static String prelude() {
        return CorpusDocuments.prelude(FACTS);
    }

    /** Whether the document states a configuration its graph is captured under, at any step. */
    public static boolean statesConfiguration(CorpusDocuments.Document document) {
        return StatedConfiguration.first(document.sdl()).isPresent();
    }

    /**
     * Whether the document states any configuration beside its file annotations, at any step: the
     * configuration a generating run over the scenario would have to be told, where file annotations
     * travel with the files themselves ({@link #annotated}).
     */
    public static boolean statesRunConfiguration(CorpusDocuments.Document document) {
        return StatedConfiguration.all(document.sdl()).stream()
            .anyMatch(directive -> directive.getArguments().stream()
                .anyMatch(argument -> !argument.getName().equals(StatedConfiguration.FILE_ANNOTATIONS)));
    }

    /**
     * One rendered file of a scenario's folder given a configured tag, a description note, or both,
     * as a build's schema input entry configures its file. {@code file} names the file as
     * {@code @withFacts} wrote it: its {@code as}, else the path it pulled in, or the scenario's own
     * path for the scenario's own file.
     */
    public record FileAnnotation(String file, Optional<String> tag, Optional<String> note) {
        public FileAnnotation {
            java.util.Objects.requireNonNull(file, "file");
            java.util.Objects.requireNonNull(tag, "tag");
            java.util.Objects.requireNonNull(note, "note");
        }
    }

    /**
     * The file annotations a configuration states, {@code configuration} being the
     * {@code extend schema @withConfiguration(...)} text a {@link Scenario} hands its capture; none
     * where it states none or is empty.
     */
    public static List<FileAnnotation> fileAnnotations(String configuration) {
        return StatedConfiguration.fileAnnotations(
            configuration.isEmpty() ? List.of() : List.of(configuration));
    }

    /**
     * {@code files}, a scenario's rendered folder as a build reads it, as the schema inputs a build
     * configured with {@code annotations} hands its run: each file with the tag and note its
     * annotation states, the rest unattributed. Annotations name files and never add one, so the
     * inputs are always exactly {@code files}; an annotation naming a file the folder does not hold
     * is refused.
     */
    public static List<SchemaInput> annotated(Path folder, List<Path> files,
                                              List<FileAnnotation> annotations) {
        var byName = new LinkedHashMap<String, Path>();
        for (var file : files) {
            var relative = folder.relativize(file).toString().replace('\\', '/');
            byName.put(relative.endsWith(CorpusDocuments.SUFFIX)
                ? relative.substring(0, relative.length() - CorpusDocuments.SUFFIX.length())
                : relative, file);
        }
        var byFile = new LinkedHashMap<Path, FileAnnotation>();
        for (var annotation : annotations) {
            var file = byName.get(annotation.file());
            if (file == null) {
                throw new IllegalArgumentException("@withConfiguration(fileAnnotations:) names "
                    + annotation.file() + ", a file the scenario's folder does not hold at this reading;"
                    + " it holds " + byName.keySet() + ". An annotation configures a rendered file and"
                    + " never adds one to what the run reads.");
            }
            byFile.put(file, annotation);
        }
        return files.stream()
            .map(file -> Optional.ofNullable(byFile.get(file))
                .map(annotation -> new SchemaInput(SchemaSource.file(file), annotation.tag(),
                    annotation.note()))
                .orElseGet(() -> SchemaInput.file(file)))
            .toList();
    }

    /**
     * The catalog a plain document's graph is captured against: the one its configuration names,
     * {@code fixture} where it names none, or none at all where it names {@code null}. Graphs share
     * a store only when they share this, so a relation keyed by no graph holds the same rows
     * wherever a document lands.
     */
    public static Optional<String> catalogPackage(CorpusDocuments.Document document,
                                                  CatalogInput fixture) {
        var first = StatedConfiguration.first(document.sdl())
            .map(directive -> graphql.language.AstPrinter.printAst(graphql.language.SchemaExtensionDefinition
                .newSchemaExtensionDefinition().directive(directive).build()))
            .map(List::of)
            .orElse(List.of());
        return Optional.ofNullable(StatedConfiguration.catalog(first, fixture))
            .map(CatalogInput::jooqPackage);
    }

    /**
     * The configuration a graph's documents state with {@code @withConfiguration}, as the run
     * configuration a build would have handed the reading. At most one of the texts may carry it; none
     * is a run that declared nothing. The recipe is the harness's own whatever this says: its file
     * annotations give a file the recipe already reads a tag or a note, and never name a file to read.
     */
    static final class StatedConfiguration {

        private static final String DIRECTIVE = "withConfiguration";

        static final String FILE_ANNOTATIONS = "fileAnnotations";

        /** Every configuration {@code text} states, at any step, in script order. */
        static List<Directive> all(String text) {
            return new Parser().parseDocument(text).getDefinitions().stream()
                .flatMap(definition -> definition instanceof SchemaDefinition schema
                    ? schema.getDirectives(DIRECTIVE).stream() : java.util.stream.Stream.empty())
                .toList();
        }

        /**
         * The files the configuration annotates, each {@code {file:, tag:, note:}} entry of
         * {@code fileAnnotations} in the order stated. A file named twice is refused, since which
         * of the two a build applied would be the order's accident.
         */
        static List<FileAnnotation> fileAnnotations(List<String> texts) {
            var argument = application(texts)
                .map(directive -> directive.getArgument(FILE_ANNOTATIONS))
                .orElse(null);
            if (argument == null) {
                return List.of();
            }
            if (!(argument.getValue() instanceof ArrayValue list)) {
                throw new AssertionError("@" + DIRECTIVE + "(" + FILE_ANNOTATIONS + ":) is a list of"
                    + " {file:, tag:, note:} entries");
            }
            var annotations = new java.util.ArrayList<FileAnnotation>();
            var files = new LinkedHashSet<String>();
            for (Value<?> value : list.getValues()) {
                if (!(value instanceof ObjectValue entry)) {
                    throw new AssertionError("@" + DIRECTIVE + "(" + FILE_ANNOTATIONS + ":) is a list of"
                        + " {file:, tag:, note:} entries");
                }
                var fields = new LinkedHashMap<String, String>();
                for (var field : entry.getObjectFields()) {
                    if (!List.of("file", "tag", "note").contains(field.getName())) {
                        throw new AssertionError("@" + DIRECTIVE + "(" + FILE_ANNOTATIONS + ":) entries"
                            + " take file, tag and note; found " + field.getName());
                    }
                    fields.put(field.getName(), text(field.getValue(), FILE_ANNOTATIONS));
                }
                var file = fields.get("file");
                if (file == null) {
                    throw new AssertionError("@" + DIRECTIVE + "(" + FILE_ANNOTATIONS + ":) entries name"
                        + " their file");
                }
                if (!files.add(file)) {
                    throw new AssertionError("@" + DIRECTIVE + "(" + FILE_ANNOTATIONS + ":) names "
                        + file + " twice");
                }
                annotations.add(new FileAnnotation(file, Optional.ofNullable(fields.get("tag")),
                    Optional.ofNullable(fields.get("note"))));
            }
            return List.copyOf(annotations);
        }

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

        /**
         * The first configuration a scenario's script states, which sets its catalog: a scenario may
         * change its configuration between steps, never its catalog.
         */
        static Optional<Directive> first(String text) {
            return new Parser().parseDocument(text).getDefinitions().stream()
                .flatMap(definition -> definition instanceof SchemaDefinition schema
                    ? schema.getDirectives(DIRECTIVE).stream() : java.util.stream.Stream.empty())
                .findFirst();
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
