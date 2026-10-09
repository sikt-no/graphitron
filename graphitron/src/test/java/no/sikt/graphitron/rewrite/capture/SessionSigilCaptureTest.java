package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE_COLUMN_MAPPING_PAIR_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_UNDECODED_ARGUMENT_ENTRY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code $session} sigil's boundary at the capture seam: columnMapping admits no sigil, so a
 * {@code $}-prefixed value there keeps its ordinary parse quarantine
 * ({@code graphitron_undecoded_argument_entry}) rather than being quietly lifted. That an
 * argMapping sigil is an entry like any other, with a candidate, is the fact document
 * {@code argmapping-pair.graphqls}.
 */
@UnitTier
class SessionSigilCaptureTest {

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
    @DisplayName("a $ in columnMapping keeps its parse quarantine; no sigil is admitted there")
    void dollarInColumnMapping_quarantinesAsUndecoded(@TempDir Path tmp) {
        try (var store = CapturedStore.of(tmp, ROUTINE_WITH_DOLLAR_COLUMN)) {
            assertThat(store.dsl().selectFrom(GRAPHITRON_ROUTINE_COLUMN_MAPPING_PAIR_ENTRY).fetch())
                .as("the malformed mapping contributes no pair rows")
                .isEmpty();
            var undecoded = store.dsl().selectFrom(GRAPHITRON_UNDECODED_ARGUMENT_ENTRY).fetch();
            assertThat(undecoded)
                .as("the raw value quarantines with its argument name")
                .anySatisfy(row -> {
                    assertThat(row.getDirectiveArgumentName()).isEqualTo("columnMapping");
                    assertThat(row.getValueSdl()).contains("$session");
                });
        }
    }
}
