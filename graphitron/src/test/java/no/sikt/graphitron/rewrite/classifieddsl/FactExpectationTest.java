package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.rewrite.classifieddsl.CorpusExpectations.Block;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fact examples: what each document in {@code src/test/resources/facts} says the store holds
 * for it.
 *
 * <p>A neighbour of {@link CorpusExpectationTest} sharing its whole mechanism, and separate from it
 * for what the two folders are about rather than for anything mechanical. A corpus document carries
 * a classification verdict and renders into an author-facing page; a document here is about the
 * shape of the store, which is a contributor's concern. Folding them together would put store
 * plumbing on that page and make a fact example read as a claim about generated code.
 *
 * <p>What this class does not carry is the corpus's other obligations, and the absence is the point:
 * no projection operation, no documentation fragment, no verdict, no launcher-command apparatus. A
 * fact document is an SDL example and the rows it says the store holds for it.
 */
@PipelineTier
class FactExpectationTest {

    /** The fact folder, with the floor that stops a folder which has stopped resolving. */
    private static final CorpusDocuments.Folder FACTS = new CorpusDocuments.Folder("facts", 1);

    @TempDir
    static Path tmp;

    private static CapturedStore captured;
    private static List<Block> blocks;

    @BeforeAll
    static void captureEveryDocument() {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        var documents = CorpusDocuments.documents(FACTS);
        captured = CapturedStore.ofCatalog(tmp, documents.getFirst().id(), full(documents.getFirst()), jooq);
        for (var document : documents.subList(1, documents.size())) {
            captured.andCatalogGraph(document.id(), full(document), jooq);
        }
        blocks = CorpusExpectations.blocks(captured.dsl());
    }

    @AfterAll
    static void closeTheStore() {
        if (captured != null) {
            captured.close();
        }
    }

    /** The prelude and the document, which is what the loader captures as one graph. */
    private static String full(CorpusDocuments.Document document) {
        return CorpusDocuments.prelude(FACTS) + "\n" + document.sdl();
    }

    @Test
    @DisplayName("every declared row is a row the store holds")
    void everyDeclaredRowIsProduced() {
        assertThat(CorpusExpectations.divergences(captured.dsl(), blocks))
            .as("a row a document declares and the relation does not hold, or the reverse where the "
                + "document claimed the whole population")
            .isEmpty();
    }

    /**
     * The floor that makes an empty read loud. Every other assertion here passes over a folder that
     * has stopped resolving, a glob that has stopped matching, or a document whose blocks were all
     * deleted, so the one that cannot is worth stating separately.
     */
    @Test
    @DisplayName("the documents declare blocks at all")
    void theDocumentsDeclareBlocks() {
        assertThat(blocks)
            .as("a fact folder that declares nothing passes every sweep over it")
            .isNotEmpty();
        assertThat(blocks).allSatisfy(block -> assertThat(block.rows())
            .as("a block declaring no rows asserts nothing, in either mode")
            .isNotEmpty());
    }

    /**
     * Ragged lines are a cell count that disagrees with the header, which the decoder keeps rather
     * than guessing at. A document that lines its columns up by eye is easy to get wrong by one
     * comma, and the failure is otherwise a missing row rather than a malformed one.
     */
    @Test
    @DisplayName("no block has a line whose cells disagree with its header")
    void everyBlockIsWellFormed() {
        assertThat(blocks.stream().flatMap(block -> block.raggedLines().stream()).toList())
            .as("lines whose cell count differs from the header's")
            .isEmpty();
    }
}
