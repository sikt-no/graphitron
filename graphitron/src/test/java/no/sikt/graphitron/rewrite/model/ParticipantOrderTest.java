package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A polymorphic field's participant paths keep the order its participants are stated in, whatever map
 * they arrive in: the parent row's columns are unioned in that order, so a map that drops it, as
 * {@link Map#copyOf} does under a salt chosen per JVM, makes the generated projection differ from run
 * to run.
 */
@UnitTier
class ParticipantOrderTest {

    @Test
    void pathsFollowTheParticipantsOrder() {
        var participants = List.<ParticipantRef>of(new ParticipantRef.Unbound("CategoryNode"),
            new ParticipantRef.Unbound("CategoryLabel"), new ParticipantRef.Unbound("CategoryTag"));
        var paths = new HashMap<String, String>();
        paths.put("CategoryTag", "tag");
        paths.put("CategoryLabel", "label");
        paths.put("CategoryNode", "node");

        assertThat(ParentRowDemand.inParticipantOrder(participants, paths).keySet())
            .containsExactly("CategoryNode", "CategoryLabel", "CategoryTag");
    }

    @Test
    void aPathOfNoStatedParticipantIsKeptAfterThem() {
        var participants = List.<ParticipantRef>of(new ParticipantRef.Unbound("CategoryNode"));
        var paths = Map.of("CategoryNode", "node", "Other", "other");

        assertThat(ParentRowDemand.inParticipantOrder(participants, paths).keySet())
            .containsExactly("CategoryNode", "Other");
    }
}
