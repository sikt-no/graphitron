package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE_COLUMN_MAPPING_PAIR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SERVICE_ARG_MAPPING_PAIR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SERVICE_ARG_MAPPING_SIGIL;
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
    @DisplayName("a $session entry lifts into the sigil relation; the residual keeps its pairs; nothing quarantines")
    void sessionEntry_writesTheSigilRow_keepsThePairSet_andNoUndecodedRow(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, SERVICE_WITH_SIGIL)) {
            var sigils = store.dsl().selectFrom(GRAPHITRON_SERVICE_ARG_MAPPING_SIGIL).fetch();
            assertThat(sigils).hasSize(1);
            assertThat(sigils.get(0).getParamName()).isEqualTo("identity");
            assertThat(sigils.get(0).getSigil()).isEqualTo("$session");
            assertThat(sigils.get(0).getPosition()).isZero();

            var pairs = store.dsl().selectFrom(GRAPHITRON_SERVICE_ARG_MAPPING_PAIR).fetch();
            assertThat(pairs).as("the residual entry keeps its ordinary pair row").hasSize(1);
            assertThat(pairs.get(0).getParamName()).isEqualTo("extra");
            assertThat(pairs.get(0).getArgumentPath()).isEqualTo("someArg");

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
