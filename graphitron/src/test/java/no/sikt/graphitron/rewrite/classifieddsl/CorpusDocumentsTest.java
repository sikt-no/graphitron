package no.sikt.graphitron.rewrite.classifieddsl;

import graphql.language.Definition;
import graphql.language.FragmentDefinition;
import graphql.language.OperationDefinition;
import graphql.language.SDLDefinition;
import graphql.parser.Parser;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The floors under the corpus folder. A folder is exactly the container that can pass while empty: a
 * glob that stops matching, a loader that silently skips a file, or a document nothing runs all read
 * as "no findings" rather than as a failure. These are what make the corpus <em>executed</em> rather
 * than surveyed.
 *
 * <ul>
 *   <li><b>Non-vacuity with a ratchet</b>: the folder resolves and holds at least
 *       {@link CorpusDocuments#MIN_DOCUMENTS} documents.</li>
 *   <li><b>Listing agreement</b>: the set of documents the loader admitted equals an independent
 *       listing of the folder, so a file neither the glob nor the loader picked up fails here rather
 *       than becoming a document nothing reads.</li>
 *   <li><b>Every document is asserted on</b>: the parameterized corpus test's parameter list is the
 *       loader's, and its count matches, so a document cannot be loaded, counted, and never run.</li>
 *   <li><b>The split holds</b>: each document's type-system half carries no executable definition and
 *       each projection carries nothing but, which is the truncation's own invariant.</li>
 * </ul>
 *
 * <p>The fourth floor the corpus rests on lives with the test it belongs to: every document annotates
 * at least one coordinate, asserted per document by {@code ClassifiedDslTest}.
 */
@UnitTier
class CorpusDocumentsTest {

    @Test
    void theFolderHoldsAtLeastTheRatchetedNumberOfDocuments() {
        assertThat(CorpusDocuments.documents())
            .as("the corpus folder %s must hold at least %d documents; a corpus that reads as empty "
                + "passes every sweep over it, so this floor is raised as the corpus grows",
                CorpusDocuments.folder(), CorpusDocuments.MIN_DOCUMENTS)
            .hasSizeGreaterThanOrEqualTo(CorpusDocuments.MIN_DOCUMENTS);
    }

    @Test
    void everyFileInTheFolderIsEitherThePreludeOrALoadedDocument() throws IOException {
        List<String> names;
        try (Stream<Path> files = Files.list(CorpusDocuments.folder())) {
            names = files.filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .sorted()
                .toList();
        }
        var expected = Stream.concat(
                Stream.of(CorpusDocuments.PRELUDE_DOCUMENT),
                CorpusDocuments.ids().stream().map(id -> id + CorpusDocuments.SUFFIX))
            .sorted()
            .toList();
        assertThat(names)
            .as("every file in the corpus folder is either the prelude or a document the loader "
                + "admitted; a file the glob or the loader skipped is a fixture nothing reads")
            .isEqualTo(expected);
    }

    @Test
    void theCorpusTestClaimsEveryLoadedDocument() {
        assertThat(ClassifiedDslTest.corpus().toList())
            .as("the parameterized corpus test must run over every loaded document; a document that "
                + "loads and is never asserted on is a fixture the build believes it checked")
            .containsExactlyElementsOf(CorpusDocuments.documents());
    }

    @Test
    void everyDocumentSplitsIntoSdlAndAnOptionalProjection() {
        for (var document : CorpusDocuments.documents()) {
            for (Definition<?> definition : new Parser().parseDocument(document.sdl()).getDefinitions()) {
                assertThat(definition)
                    .as("document %s: the type-system half must carry no executable definition, "
                        + "because the schema loader refuses one", document.id())
                    .isInstanceOf(SDLDefinition.class);
            }
            if (document.projection() == null) {
                continue;
            }
            for (Definition<?> definition : new Parser().parseDocument(document.projection()).getDefinitions()) {
                assertThat(definition instanceof OperationDefinition
                    || definition instanceof FragmentDefinition)
                    .as("document %s: the projection must carry only an operation or a fragment, "
                        + "and it is the document's last definition", document.id())
                    .isTrue();
            }
        }
    }
}
