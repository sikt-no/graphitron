package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE_COLUMN_MAPPING_PAIR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_CANDIDATE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_UNDECODED_ARGUMENT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code $session} sigil at the capture seam: a {@code @service} argMapping routes through
 * the shared {@code ArgMappingSigil} owner, so a recognized sigil entry lands in the sibling
 * sigil relation as a decoded fact, the residual keeps its full pair set, and nothing
 * quarantines. The complement pins the boundary: columnMapping admits no sigil, so a
 * {@code $}-prefixed value there keeps its ordinary parse quarantine
 * ({@code graphitron_undecoded_argument}) rather than being quietly lifted.
 */
@UnitTier
class SessionSigilCaptureTest {

    private static final String SERVICE_WITH_SIGIL = """
        type Query {
          sessionPrincipal: String @service(
            service: {className: "com.example.SessionService", method: "principal",
                      argMapping: "identity: $session, extra: someArg"})
        }
        """;

    private static final String ROUTINE_WITH_DOLLAR_COLUMN = """
        type Query { rental: Rental }
        type Rental @table(name: "rental") { rentalId: Int @field(name: "rental_id") }
        type Mutation {
          rentFilm(inventoryId: Int!): [Rental!]!
            @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId",
                     columnMapping: "rentalId: $session")
            @reference(path: [{table: "rental"}])
        }
        """;

    @Test
    @DisplayName("a $session entry is an entry like any other, at the position the author wrote it")
    void sessionEntry_isAnOrdinaryEntry_atItsAuthoredPosition(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, SERVICE_WITH_SIGIL)) {
            var entries = store.dsl().selectFrom(GRAPHITRON_ARGMAPPING_ENTRY)
                .where(GRAPHITRON_ARGMAPPING_ENTRY.SITE.eq("SERVICE"))
                .orderBy(GRAPHITRON_ARGMAPPING_ENTRY.POSITION).fetch();
            assertThat(entries)
                .as("both entries land in the one relation that holds an argMapping entry")
                .hasSize(2);

            // The author wrote the sigil first. The lift takes it out of the middle of the list,
            // so a position taken from the residual's own numbering would renumber what is left;
            // these two assertions are what says it does not.
            assertThat(entries.get(0).getParamName()).isEqualTo("identity");
            assertThat(entries.get(0).getWrittenPath()).isEqualTo("$session");
            assertThat(entries.get(0).getPosition()).isZero();

            assertThat(entries.get(1).getParamName()).isEqualTo("extra");
            assertThat(entries.get(1).getWrittenPath()).isEqualTo("someArg");
            assertThat(entries.get(1).getPosition()).isEqualTo(1);

            var candidates = store.dsl().selectFrom(GRAPHITRON_ARGMAPPING_CANDIDATE)
                .where(GRAPHITRON_ARGMAPPING_CANDIDATE.ELEMENT_KIND.eq("SIGIL")).fetch();
            assertThat(candidates)
                .as("a sigil is something a right-hand side may name, so it has a candidate")
                .hasSize(1);
            assertThat(candidates.get(0).getCoordinate())
                .isEqualTo(entries.get(0).getCoordinate());
            assertThat(candidates.get(0).getName()).isEqualTo("$session");
            assertThat(candidates.get(0).getNamedType())
                .as("a sigil names a runtime value and no GraphQL type")
                .isNull();
            assertThat(candidates.get(0).getDepth()).isZero();

            assertThat(store.dsl().selectFrom(GRAPHITRON_UNDECODED_ARGUMENT).fetch())
                .as("a recognized sigil is a decoded fact, never malformed overflow")
                .isEmpty();
        }
    }

    @Test
    @DisplayName("a $ in columnMapping keeps its parse quarantine; no sigil is admitted there")
    void dollarInColumnMapping_quarantinesAsUndecoded(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, ROUTINE_WITH_DOLLAR_COLUMN)) {
            assertThat(store.dsl().selectFrom(GRAPHITRON_ROUTINE_COLUMN_MAPPING_PAIR).fetch())
                .as("the malformed mapping contributes no pair rows")
                .isEmpty();
            var undecoded = store.dsl().selectFrom(GRAPHITRON_UNDECODED_ARGUMENT).fetch();
            assertThat(undecoded)
                .as("the raw value quarantines with its argument name")
                .anySatisfy(row -> {
                    assertThat(row.getDirectiveArgumentName()).isEqualTo("columnMapping");
                    assertThat(row.getValueSdl()).contains("$session");
                });
        }
    }
}
