package no.sikt.graphitron.rewrite.classifieddsl;

import graphql.language.Definition;
import graphql.language.FragmentDefinition;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The spec-by-example corpus, loaded from the folder of GraphQL documents at
 * {@code graphitron/src/test/resources/corpus}. One example is one document: the annotated fixture
 * schema, the prose that teaches it as SDL descriptions, and optionally the projection operation the
 * documentation renders it through. The filename is the example's id, so adding an example is adding
 * a file.
 *
 * <p><b>Read from the source tree, not the classpath.</b> The folder resolves as a path, the way
 * {@link CorpusFragmentRenderer} resolves the documentation page, so what the corpus asserts is exactly
 * what an author edits and a stale copy under {@code target/test-classes} cannot answer for a
 * document that is no longer there.
 *
 * <p><b>The prelude is a document too.</b> {@code _prelude.graphqls} holds the test-only directive
 * definitions, their SDL enums, {@code interface Node}, the {@code Query} root and the
 * {@code CorpusAnchor} type; the leading underscore keeps it out of the document glob. Every reader
 * prepends {@link #prelude()} to a document's {@link Document#sdl()} before classifying it, which is
 * what {@link ClassifiedHarness#classify(String)} does.
 *
 * <p><b>A folder is the container that can pass while empty</b>, so the documents are executed and
 * never surveyed. {@link #MIN_DOCUMENTS} is the non-vacuity ratchet, and {@code CorpusDocumentsTest}
 * carries the rest of the floors: the loader's admissions agree with an independent listing of the
 * folder, every loaded document is claimed by the parameterized corpus tests, and every document
 * annotates at least one coordinate.
 */
public final class CorpusDocuments {

    private CorpusDocuments() {}

    /**
     * The floor on the corpus's size, pinned at the count the folder holds and raised when it grows
     * (the ratchet {@code CommandRelationFragment.MIN_RELATIONS} applies to its own scan). A folder
     * that stops resolving, or a glob that stops matching, reads as an empty corpus and passes every
     * sweep over it; this is what makes that failure loud instead.
     */
    static final int MIN_DOCUMENTS = 57;

    /** The prelude document's filename, excluded from the document glob by its leading underscore. */
    static final String PRELUDE_DOCUMENT = "_prelude.graphqls";

    /** The document suffix. */
    static final String SUFFIX = ".graphqls";

    /**
     * One folder of documents in this format, and the reason the loader takes a parameter at all.
     * The classification corpus renders into an author-facing page and its documents carry a
     * verdict, which is a semantics a document about the store's own shape has no business
     * inheriting; a second folder gets the format and the assertion directives without it. What is
     * shared is exactly the mechanism, so an addition to it reaches both.
     *
     * @param name the folder under {@code src/test/resources}
     * @param minDocuments the floor on its size, since a folder that stops resolving reads as empty
     *     and passes every sweep over it
     */
    public record Folder(String name, int minDocuments) {

        List<Path> candidates() {
            return List.of(Path.of("graphitron", "src", "test", "resources", name),
                Path.of("src", "test", "resources", name));
        }
    }

    /** The spec-by-example corpus: documents carrying a classification verdict. */
    public static final Folder CORPUS = new Folder("corpus", MIN_DOCUMENTS);

    /**
     * One corpus document: its id (the filename without the suffix), the type-system half every
     * reader classifies, and the projection operation the documentation renders it through, or
     * {@code null} where the document has none. A document with no projection is corpus-only: it
     * pins a verdict and renders nowhere.
     */
    public record Document(String id, String sdl, String projection) {

        @Override
        public String toString() {
            return id;
        }
    }

    private static final Map<String, List<Document>> documentsByFolder = new HashMap<>();
    private static final Map<String, String> preludeByFolder = new HashMap<>();

    /** The corpus documents, ordered by id. */
    public static List<Document> documents() {
        return documents(CORPUS);
    }

    /** One folder's documents, ordered by id. */
    public static synchronized List<Document> documents(Folder folder) {
        return documentsByFolder.computeIfAbsent(folder.name(), ignored -> {
            var loaded = new ArrayList<Document>();
            Path path = folder(folder);
            for (Path file : documentFiles(folder)) {
                String name = file.getFileName().toString();
                loaded.add(split(name.substring(0, name.length() - SUFFIX.length()), read(file)));
            }
            if (loaded.size() < folder.minDocuments()) {
                throw new AssertionError("the folder " + path.toAbsolutePath() + " holds "
                    + loaded.size() + " documents, below the floor of " + folder.minDocuments()
                    + "; a folder that reads as empty passes every sweep over it");
            }
            return List.copyOf(loaded);
        });
    }

    /** The documents carrying a projection operation, ordered by id. */
    public static List<Document> withProjection() {
        return documents().stream().filter(d -> d.projection() != null).toList();
    }

    /** The prelude document's text, prepended to every document's SDL before classification. */
    public static String prelude() {
        return prelude(CORPUS);
    }

    /** One folder's prelude text. */
    public static synchronized String prelude(Folder folder) {
        return preludeByFolder.computeIfAbsent(folder.name(),
            ignored -> read(folder(folder).resolve(PRELUDE_DOCUMENT)));
    }

    /** The corpus folder, resolved against the working directory the test run happens to use. */
    public static Path folder() {
        return folder(CORPUS);
    }

    /** One folder, resolved against the working directory the test run happens to use. */
    public static Path folder(Folder folder) {
        return folder.candidates().stream()
            .filter(Files::isDirectory)
            .findFirst()
            .orElseThrow(() -> new AssertionError("could not locate the " + folder.name()
                + " folder from working dir " + Path.of("").toAbsolutePath()));
    }

    /** The document files the loader admits: every {@code *.graphqls} but the prelude, sorted by name. */
    static List<Path> documentFiles() {
        return documentFiles(CORPUS);
    }

    /** One folder's document files. */
    static List<Path> documentFiles(Folder folder) {
        try (Stream<Path> files = Files.list(folder(folder))) {
            return files
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                .filter(p -> !p.getFileName().toString().startsWith("_"))
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Splits a document into the type-system half and the projection operation.
     *
     * <p>graphql-java's parser accepts a document mixing SDL with an operation, but
     * {@link no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader} refuses a non-SDL definition on
     * purpose, so nothing downstream may see the operation. The split is therefore a truncation at
     * the operation's own source line, which keeps every remaining line at the line number an author
     * reads (re-printing the type-system half would move them all). The convention it rests on is
     * that the operation is the document's last definition, asserted here.
     */
    private static Document split(String id, String text) {
        int operationLine = -1;
        for (Definition<?> definition : new Parser().parseDocument(text).getDefinitions()) {
            boolean executable = definition instanceof OperationDefinition
                || definition instanceof FragmentDefinition;
            if (executable && operationLine == -1) {
                operationLine = definition.getSourceLocation().getLine();
            } else if (!executable && operationLine != -1) {
                throw new AssertionError("document " + id + " declares SDL after its projection "
                    + "operation (line " + definition.getSourceLocation().getLine() + "); the "
                    + "projection is the document's last definition, because the loader splits the "
                    + "document by truncating at its line");
            }
        }
        if (operationLine == -1) {
            return new Document(id, text, null);
        }
        List<String> lines = text.lines().toList();
        return new Document(id,
            String.join("\n", lines.subList(0, operationLine - 1)),
            String.join("\n", lines.subList(operationLine - 1, lines.size())));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The set of sealed {@code GraphitronField} / {@code GraphitronType} leaves the corpus
     * demonstrates classification for, by classifying every document and collecting the leaf each
     * {@code @classified} / {@code @classifiedType} coordinate landed on, descending the ridden lists
     * a classified leaf carries ({@code NestingField.nestedFields()}, {@code PivotSpec.slots()}); a
     * pivot slot or a nesting child has no top-level coordinate of its own, so the descent is what
     * lets the corpus walk observe it. This set alone carries the output-field and type side of the
     * variant-coverage obligation ({@code ExemptionRegistry}): a leaf absent here fails coverage even
     * when an enum case still asserts it.
     *
     * <p>Synthesised type leaves join through {@code @synthesises} on a carrier coordinate: an arm
     * counts only when a declared mint agrees with the connection-synthesis relation's produced row
     * (same name, same arm, registry entry matching), never from the producer's output alone, so the
     * coverage stays author-checkable.
     */
    public static Set<Class<?>> coveredLeaves() {
        var leaves = new HashSet<Class<?>>();
        var mintedArmsBySimpleName = new java.util.HashMap<String, Class<?>>();
        for (var arm : no.sikt.graphitron.rewrite.model.ConnectionSynthesis.MINTED_ARM_VOCABULARY) {
            mintedArmsBySimpleName.put(arm.getSimpleName(), arm);
        }
        for (Document document : documents()) {
            var result = ClassifiedHarness.classify(document.sdl());
            for (var fc : result.fields()) {
                var field = result.schema().field(fc.parentType(), fc.fieldName());
                ClassifiedHarness.forEachWithRiddenFields(field, f -> leaves.add(f.getClass()));
            }
            for (var tc : result.types()) {
                if (tc.leaf() != null) {
                    leaves.add(tc.leaf());
                }
            }
            for (var sc : result.synthesises()) {
                for (var declared : sc.declared()) {
                    if (sc.produced().contains(declared)) {
                        leaves.add(mintedArmsBySimpleName.get(declared.arm()));
                    }
                }
            }
        }
        return leaves;
    }

    /** The loaded document ids, for the floors that compare the loader against the folder. */
    static Set<String> ids() {
        return documents().stream().map(Document::id)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
