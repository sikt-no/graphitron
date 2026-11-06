package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BuildQuery}'s answers over a captured schema: an input object made into the record class a
 * service takes, one member per field; a list of input objects into a list of that record; a nested
 * input object built into its member's class; a grouping whose fields bind against the enclosing
 * class; and a jOOQ record, which binds on columns, refused here.
 */
@PipelineTier
class BuildQueryTest {

    private static final String SERVICES = "no.sikt.graphitron.rewrite.test.services.";

    private static final String SDL = """
        input FilmDraftInput { title: String! }
        input FilmBundleLimitsInput { capacity: Int }
        input FilmBundleDraftInput {
          name: String
          limits: FilmBundleLimitsInput
          films: [FilmDraftInput!]
        }
        input FilmRecordDraftInput { title: String @field(name: "title") }
        type FilmDraftsCountedPayload { count: Int }
        type Query {
          countFilmDrafts(in: [FilmDraftInput!]!): FilmDraftsCountedPayload
            @service(service: {className: "%1$sFilmDraftService", method: "count", argMapping: "drafts: in"})
          countFilmBundleDraft(in: FilmBundleDraftInput!): FilmDraftsCountedPayload
            @service(service: {className: "%1$sFilmBundleDraftService", method: "count", argMapping: "bundle: in"})
          countFilmRecordDrafts(in: [FilmRecordDraftInput!]!): FilmDraftsCountedPayload
            @service(service: {className: "%1$sFilmRecordDraftService", method: "count", argMapping: "drafts: in"})
        }
        """.formatted(SERVICES);

    @TempDir
    static Path tmp;

    private static Map<String, BuildQuery.Result> results;

    @BeforeAll
    static void read() {
        try (var store = CapturedStore.ofCatalogWith(tmp, CapturedStore.GRAPH, SDL,
                CatalogInput.of(testContext()), ClasspathCorpus.entries())) {
            results = BuildQuery.read(new StoreHandle(store.dsl(), CapturedStore.GRAPH)).entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().callCoordinate(), Map.Entry::getValue));
        }
    }

    private static BuildQuery.Build built(String call) {
        assertThat(results.get(call)).isInstanceOf(BuildQuery.Result.Built.class);
        return ((BuildQuery.Result.Built) results.get(call)).build();
    }

    @Test
    void aListOfInputObjectsIsMadeIntoTheRecordTheListHolds() {
        var build = built("Query.countFilmDrafts");
        assertThat(build.className()).isEqualTo(SERVICES + "FilmDraft");
        assertThat(build.shape()).isEqualTo("POSITIONAL");
        assertThat(build.members()).extracting(m -> m.slot().slotName(), BuildQuery.Member::path,
                BuildQuery.Member::typeKind)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("title", List.of("title"), "SCALAR"));
    }

    @Test
    void aGroupingsFieldsBindAgainstTheEnclosingClassAndANestedInputBuildsItsMember() {
        var build = built("Query.countFilmBundleDraft");
        assertThat(build.className()).isEqualTo(SERVICES + "FilmBundleDraft");
        var byMember = build.members().stream()
            .collect(Collectors.toMap(m -> m.slot().slotName(), m -> m));
        assertThat(byMember.keySet()).containsExactlyInAnyOrder("name", "capacity", "films");
        assertThat(byMember.get("capacity").path()).containsExactly("limits", "capacity");
        var films = byMember.get("films");
        assertThat(films.list()).isTrue();
        assertThat(films.nested()).hasValueSatisfying(nested ->
            assertThat(nested.className()).isEqualTo(SERVICES + "FilmDraft"));
    }

    @Test
    void aJooqRecordIsRefusedForTheColumnAxis() {
        assertThat(results.get("Query.countFilmRecordDrafts"))
            .isInstanceOfSatisfying(BuildQuery.Result.Refused.class, refused ->
                assertThat(refused.reason()).contains("jOOQ record"));
    }
}
