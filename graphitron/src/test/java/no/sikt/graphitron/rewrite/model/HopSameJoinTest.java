package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.rewrite.TestFixtures;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JoinStep.Hop#sameJoinAs} compares every record component but the alias. The component
 * set is read reflectively, so a component added to {@code Hop} fails here until the comparison
 * (and the variant map below) says whether it is part of the join.
 */
@UnitTier
class HopSameJoinTest {

    private static final ColumnRef ACTOR_ID = TestFixtures.col("actor_id", "ACTOR_ID", "java.lang.Integer");
    private static final ColumnRef FILM_ID = TestFixtures.col("film_id", "FILM_ID", "java.lang.Integer");
    private static final TableRef FILM_ACTOR = TestFixtures.tableRef(
        "film_actor", "FILM_ACTOR", "FilmActor", List.of(ACTOR_ID, FILM_ID));
    private static final TableRef ACTOR = TestFixtures.tableRef("actor", "ACTOR", "Actor", List.of(ACTOR_ID));
    private static final TableRef FILM = TestFixtures.tableRef("film", "FILM", "Film", List.of(FILM_ID));

    private static final JoinStep.Hop BASE = TestFixtures.fkJoin(
        TestFixtures.foreignKeyRef("film_actor_actor_id_fkey"),
        FILM_ACTOR, List.of(ACTOR_ID), ACTOR, List.of(ACTOR_ID), null, "names_0");

    private static final JoinStep.Hop OTHER = TestFixtures.fkJoin(
        TestFixtures.foreignKeyRef("film_actor_film_id_fkey"),
        ACTOR, List.of(FILM_ID), FILM, List.of(FILM_ID),
        new JoinConditionRef(TestFixtures.staticOnlyMethodRef(
            "no.sikt.graphitron.rewrite.TestConditionStub", "intermediate",
            ClassName.get("org.jooq", "Condition"))),
        "other_0");

    /** One hop per compared component, equal to {@link #BASE} in every other component. */
    private static final Map<String, JoinStep.Hop> DIFFERING_ONLY_IN = Map.of(
        "target", new JoinStep.Hop(OTHER.target(), BASE.on(), BASE.originTable(), BASE.filter(), BASE.alias()),
        "on", new JoinStep.Hop(BASE.target(), OTHER.on(), BASE.originTable(), BASE.filter(), BASE.alias()),
        "originTable", new JoinStep.Hop(BASE.target(), BASE.on(), OTHER.originTable(), BASE.filter(), BASE.alias()),
        "filter", new JoinStep.Hop(BASE.target(), BASE.on(), BASE.originTable(), OTHER.filter(), BASE.alias()));

    @Test
    void everyComponentButTheAliasIsCompared() {
        var compared = Stream.of(JoinStep.Hop.class.getRecordComponents())
            .map(RecordComponent::getName)
            .filter(name -> !name.equals("alias"))
            .toList();
        assertThat(DIFFERING_ONLY_IN.keySet())
            .as("a Hop component that sameJoinAs neither compares nor names as emit-side")
            .containsExactlyInAnyOrderElementsOf(compared);
        DIFFERING_ONLY_IN.forEach((component, hop) ->
            assertThat(BASE.sameJoinAs(hop)).as("hops differing in %s", component).isFalse());
    }

    @Test
    void theAliasIsNotCompared() {
        var renamed = new JoinStep.Hop(BASE.target(), BASE.on(), BASE.originTable(), BASE.filter(), "lastName_0");
        assertThat(BASE.sameJoinAs(renamed)).isTrue();
        assertThat(BASE).isNotEqualTo(renamed);
    }
}
