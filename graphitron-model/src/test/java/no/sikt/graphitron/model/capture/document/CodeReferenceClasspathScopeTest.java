package no.sikt.graphitron.model.capture.document;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.model.test.TestRunContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_CODE_REFERENCE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two graphs whose classpaths differ, in one store, and a code reference that resolves only through
 * its own.
 *
 * <p>The resolution joins the method census through {@code store_graph_source}. Without that hop a
 * reference would meet a method row under any entry the store holds, including one only a sibling
 * graph claims, and find two methods where its own graph has one, so it would resolve to neither. The
 * discriminating shape is one class declared by two entries, which is what the {@code service-copy}
 * classpath fixture is: the same compiled class, copied into a root of its own, read again.
 *
 * <p>Both schemas are documents: {@code shapes/code-reference-sibling} read over the corpus, and
 * {@code scenarios/code-reference-copied-entry}, which reads it over the fixture and claims its own
 * resolution. A store of the test's own because two graphs of different classpaths in one store is
 * the subject, and the population never makes one: it keeps one classpath per store. The rest of
 * what this relation answers is stated in {@code facts/undecided/code-reference-resolution.graphqls}.
 */
class CodeReferenceClasspathScopeTest {

    @TempDir
    Path tmp;

    private static final String OWN = "own";
    private static final String SIBLING = "sibling";

    @Test
    @DisplayName("a reference resolves through its own graph's entry, not a sibling's")
    void siblingGraphsResolveThroughTheirOwnMembership() {
        var jooq = CatalogInput.of(TestRunContext.of());

        try (var captured = CapturedStore.ownStoreOfDocument(tmp, "shapes/code-reference-sibling", OWN,
                jooq, ClasspathCorpus.entries())) {
            captured.andDocument("scenarios/code-reference-copied-entry", SIBLING, jooq,
                ClasspathCorpus.entries());

            var r = GRAPHITRON_CODE_REFERENCE;
            var rows = captured.dsl()
                .select(r.GRAPH_NAME, r.METHOD_SOURCE_NAME)
                .from(r)
                .where(r.COORDINATE.eq("Film.titleUppercase"))
                .fetch();

            assertThat(rows).extracting(row -> row.value1())
                .as("one resolution per graph: a sibling's entry joining in would have made two methods"
                    + " answer and the reference resolve to neither")
                .containsExactlyInAnyOrder(OWN, SIBLING);
            assertThat(rows).extracting(row -> row.value2())
                .as("and the two entries differ, so the partition is what kept them apart")
                .doesNotHaveDuplicates();
        }
    }
}
