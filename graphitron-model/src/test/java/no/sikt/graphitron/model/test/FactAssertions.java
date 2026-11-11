package no.sikt.graphitron.model.test;

import graphql.language.SchemaExtensionDefinition;
import graphql.language.StringValue;
import graphql.parser.Parser;
import no.sikt.graphitron.model.test.CorpusExpectations.Block;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/**
 * Expectations about a fact document stated beside it rather than in it: an assertion file names its
 * document with {@code @factDocument(path:)} and carries claims, so a test adds what it needs to
 * pin about an example without changing the example.
 *
 * <pre>{@code
 * extend schema @factDocument(path: "scenarios/field-key-row")
 *
 * extend schema @assertContains(relation: "graphitron_occurrence_producer_lift", rows: """
 *   coordinate,                       read
 *   Query.filmKeySummaries.keyCensus, ACCESSOR
 *   """)
 * }</pre>
 *
 * <p>A file is read as text and never captured into the graph it is about, so it adds no rows of its
 * own to the relations a document's claims are about.
 *
 * <p><b>Additive only.</b> A store claim here may say a row is there ({@code @assertContains}) or is
 * not ({@code @assertAbsent}). {@code @expectEquals} and {@code @assertEmpty} state what the
 * document's graph is, all of it, which is the document's own statement: beside it they would be a
 * second statement of the same population, union with the document's silently, and be missing from
 * the manual, which renders a document's own blocks.
 *
 * <p>A module keeps its files under its own {@code src/test/resources/fact-assertions}, the folder
 * {@link #in(Path)} reads; the claims a module above the store adds about its own answers are
 * declared by that module.
 */
public final class FactAssertions {

    /** Where a module keeps its assertion files, relative to the module. */
    public static final Path FOLDER = Path.of("src", "test", "resources", "fact-assertions");

    private static final String DOCUMENT_DIRECTIVE = "factDocument";

    private FactAssertions() {}

    /**
     * One assertion file: where it is, the document it names (or null where it names none) and the
     * claims it carries, each a block on the document's graph.
     */
    public record AssertionFile(Path file, String document, List<Block> blocks) {

        /** Why this file cannot be checked as it stands; empty where it can. */
        public List<String> refusals(Collection<String> documents) {
            var refusals = new ArrayList<String>();
            if (document == null) {
                refusals.add(file + " names no fact document; open it with exactly one "
                    + "extend schema @factDocument(path: \"<folder>/<name>\")");
                return refusals;
            }
            if (!documents.contains(document)) {
                refusals.add(file + " names the fact document '" + document + "', which does not "
                    + "exist; a path is a folder under facts and a name, as scenarios/field-key-row");
            }
            for (var block : blocks) {
                if (block.mode() == CorpusExpectations.Mode.EQUALS
                        || block.mode() == CorpusExpectations.Mode.EMPTY) {
                    refusals.add(file + " states " + block.relation() + "'s whole population with "
                        + (block.mode() == CorpusExpectations.Mode.EQUALS ? "@expectEquals" : "@assertEmpty")
                        + "; that is the document's own statement, so state it there, or use "
                        + "@assertContains or @assertAbsent here");
                }
            }
            return refusals;
        }
    }

    /** Every assertion file under {@code folder}, in path order; none where the folder is absent. */
    public static List<AssertionFile> in(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(file -> file.toString().endsWith(CorpusDocuments.SUFFIX))
                .sorted()
                .map(FactAssertions::read)
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One file, read as text: the document it names and its claims as blocks on that graph. */
    public static AssertionFile read(Path file) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var named = Parser.parse(text).getDefinitionsOfType(SchemaExtensionDefinition.class).stream()
            .flatMap(extension -> extension.getDirectives(DOCUMENT_DIRECTIVE).stream())
            .map(directive -> directive.getArgument("path"))
            .filter(argument -> argument != null && argument.getValue() instanceof StringValue)
            .map(argument -> ((StringValue) argument.getValue()).getValue())
            .distinct()
            .toList();
        var document = named.size() == 1 ? named.getFirst() : null;
        var blocks = document == null ? List.<Block>of()
            : CorpusExpectations.declaredBlocks(DSL.using(SQLDialect.H2), document, text);
        return new AssertionFile(file, document, blocks);
    }
}
