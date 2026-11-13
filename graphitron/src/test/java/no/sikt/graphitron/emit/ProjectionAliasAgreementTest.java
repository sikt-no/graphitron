package no.sikt.graphitron.emit;

import no.sikt.graphitron.command.ReservedAliases;
import no.sikt.graphitron.rewrite.model.AliasOwner;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The names a store-fed projection unit writes are the names the field readers read: the emitter
 * spells its prefix and path delimiter itself, reading nothing of the walk's, so this holds the two
 * spellings equal until both take their names from one place.
 */
@UnitTier
class ProjectionAliasAgreementTest {

    @Test
    @DisplayName("a shared name's prefix is the readers'")
    void shared() {
        assertThat(ProjectionEmitter.aliasPrefix(null)).isEqualTo(ReservedAliases.resultKeyPrefix(AliasOwner.shared()));
    }

    @Test
    @DisplayName("an owned name's prefix is the readers'")
    void owned() {
        assertThat(ProjectionEmitter.aliasPrefix("Item"))
            .isEqualTo(ReservedAliases.resultKeyPrefix(AliasOwner.qualifiedBy("Item")));
    }

    @Test
    @DisplayName("a nesting level's delimiter is the readers'")
    void delimiter() {
        assertThat(ProjectionEmitter.PATH_DELIMITER).isEqualTo(ReservedAliases.RESULT_KEY_PATH_DELIMITER);
    }
}
