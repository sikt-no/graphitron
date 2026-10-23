package no.sikt.graphitron.rewrite.walker;

import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.model.ColumnOverlap.SlotColumn;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit coverage for {@link KeyMembership#of}: one case per arm, the slots each arm carries, and the
 * rule that only an own-columns carrier can cover the key plus more.
 */
@UnitTier
class KeyMembershipTest {

    private static final ColumnRef ACTOR_ID = new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer");
    private static final ColumnRef FILM_ID = new ColumnRef("film_id", "FILM_ID", "java.lang.Integer");
    private static final ColumnRef LAST_UPDATE = new ColumnRef("last_update", "LAST_UPDATE", "java.time.LocalDateTime");
    private static final Set<String> KEY = Set.of("actor_id", "film_id");

    @Test
    void everyColumnInTheKey_isAllInKey() {
        var m = KeyMembership.of(new CarrierRole.OwnColumns(), List.of(FILM_ID, ACTOR_ID), KEY);
        assertThat(m).isEqualTo(new KeyMembership.AllInKey(List.of(new SlotColumn(0, FILM_ID), new SlotColumn(1, ACTOR_ID))));
    }

    @Test
    void noColumnInTheKey_isNoneInKey() {
        var m = KeyMembership.of(new CarrierRole.CrossTableFk(), List.of(LAST_UPDATE), KEY);
        assertThat(m).isEqualTo(new KeyMembership.NoneInKey(List.of(new SlotColumn(0, LAST_UPDATE))));
    }

    @Test
    void ownColumnsCoveringTheWholeKeyPlusMore_isCoversKeyPlus() {
        var m = KeyMembership.of(new CarrierRole.OwnColumns(), List.of(ACTOR_ID, LAST_UPDATE, FILM_ID), KEY);
        assertThat(m).isEqualTo(new KeyMembership.CoversKeyPlus(
            List.of(new SlotColumn(0, ACTOR_ID), new SlotColumn(2, FILM_ID)),
            List.of(new SlotColumn(1, LAST_UPDATE))));
    }

    @Test
    void ownColumnsCoveringPartOfTheKeyPlusMore_straddles() {
        var m = KeyMembership.of(new CarrierRole.OwnColumns(), List.of(ACTOR_ID, LAST_UPDATE), KEY);
        assertThat(m).isEqualTo(new KeyMembership.Straddles(
            List.of(new SlotColumn(0, ACTOR_ID)), List.of(new SlotColumn(1, LAST_UPDATE))));
    }

    @Test
    void referenceCoveringTheWholeKeyPlusMore_straddles() {
        var m = KeyMembership.of(new CarrierRole.CrossTableFk(), List.of(ACTOR_ID, FILM_ID, LAST_UPDATE), KEY);
        assertThat(m).isEqualTo(new KeyMembership.Straddles(
            List.of(new SlotColumn(0, ACTOR_ID), new SlotColumn(1, FILM_ID)),
            List.of(new SlotColumn(2, LAST_UPDATE))));
    }

    @Test
    void selfFk_isNeverMeasured() {
        assertThatThrownBy(() -> KeyMembership.of(new CarrierRole.SelfFk(), List.of(ACTOR_ID), KEY))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
