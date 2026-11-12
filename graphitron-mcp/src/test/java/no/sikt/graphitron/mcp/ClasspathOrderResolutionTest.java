package no.sikt.graphitron.mcp;

import no.sikt.graphitron.model.boot.StoreAnswer;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.model.test.TestRunContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where two entries on a graph's classpath declare one class name, the schema tool reports the
 * members of the copy the generator resolves the name to, the first entry in classpath order, and
 * never the shadowed copy's or both. The graph reads the classpath corpus and then a library
 * declaring {@code FilmBlurbHolder} again: the corpus's holds a {@code FilmBlurb}, the library's a
 * {@code lib.Blurb}, so which copy answered is visible in the member's rendered type.
 */
class ClasspathOrderResolutionTest {

    private static final String DOCUMENT = "scenarios/type-backing-partition-corpus-and-library";

    private static final String HOLDER = "no.sikt.graphitron.rewrite.test.services.FilmBlurbHolder";

    @Test
    @DisplayName("a backing class two entries declare offers the first entry's members, once")
    void backingMembersAreTheFirstEntrysCopy(@TempDir Path tmp) {
        try (var captured = CapturedStore.ownStoreOfDocument(tmp, DOCUMENT, "both",
                CatalogInput.of(TestRunContext.of()), ClasspathCorpus.entries());
             var reader = captured.reader()) {
            var answer = SchemaQueries.read(reader, "both", Optional.of("Holder"), Optional.empty(), 10);

            assertThat(answer).isInstanceOfSatisfying(StoreAnswer.Answered.class, answered -> {
                var schema = (SchemaQueries.SchemaAnswer) answered.value();
                assertThat(schema.page().types()).singleElement()
                    .satisfies(holder -> assertThat(holder.backing()).singleElement()
                        .satisfies(backing -> {
                            assertThat(backing.className()).isEqualTo(HOLDER);
                            assertThat(backing.members()).containsExactly(new SchemaQueries.MemberSlot(
                                "blurb", "FilmBlurb", "RECORD_COMPONENT", "blurb"));
                        }));
            });
        }
    }
}
