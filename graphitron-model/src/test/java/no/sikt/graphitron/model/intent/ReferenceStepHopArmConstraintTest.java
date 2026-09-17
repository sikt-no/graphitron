package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_FIELD_REFERENCE_STEP_HOP;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_REFERENCE_STEP_HOP_KEYED;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_REFERENCE_STEP_HOP_KEYLESS;
import static no.sikt.graphitron.model.test.SeededStore.derive;
import static no.sikt.graphitron.model.test.SeededStore.seedConditionMethod;
import static no.sikt.graphitron.model.test.SeededStore.seedConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedField;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReference;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceCall;
import static no.sikt.graphitron.model.test.SeededStore.seedFieldReferenceStep;
import static no.sikt.graphitron.model.test.SeededStore.seedGraphSource;
import static no.sikt.graphitron.model.test.SeededStore.seedReferentialConstraint;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedTableBinding;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the two reference-step hop arms refuse: the duplicate row their keys exclude, and the
 * {@code via}-to-{@code key_matched_by} disagreements their {@code CHECK}s exclude.
 *
 * <p>This pins the DDL and not the rule. {@link ReferenceStepTargetTest} states, and keeps, that
 * the hop's rule is pinned once, through the chain that reaches or refuses it; every case here
 * writes rows of its own instead, because what it is about is the constraint and a rule that is
 * correct produces no row for a constraint to refuse. Both tables carry rows on this fixture before
 * anything is inserted into them, which {@link #bothArmsHoldRowsOnThisFixture} states so the
 * duplicate cases cannot pass by inserting into an empty table.
 *
 * <p>Why the constraints exist at all is the split itself: the four arms have two key shapes, so
 * one relation over them could carry no primary key and nothing refused a duplicate row in it. The
 * keyed arm's identity takes in the constraint's name and its orientation, both orientations of one
 * foreign key being separate rows; the keyless arm has no constraint to be identified by, so the
 * element coordinate with the two table triples is already total. {@code key_matched_by} is the one
 * column that survives the split nullable, inapplicable on a {@code TABLE} hop rather than omitted,
 * and the biconditional {@code CHECK} is what states that rather than a column comment.
 */
class ReferenceStepHopArmConstraintTest {

    @Test
    @DisplayName("both arm tables hold rows on this fixture")
    void bothArmsHoldRowsOnThisFixture() {
        withArmedStore(dsl -> {
            assertThat(dsl.fetchCount(INTENT_FIELD_REFERENCE_STEP_HOP_KEYED))
                .as("the KEY arm, from the seeded key path")
                .isGreaterThan(0);
            assertThat(dsl.fetchCount(INTENT_FIELD_REFERENCE_STEP_HOP_KEYLESS))
                .as("the CONDITION arm, from the seeded condition path")
                .isGreaterThan(0);
            assertThat(dsl.fetchCount(INTENT_FIELD_REFERENCE_STEP_HOP))
                .as("the union view presents both arms under the name every reader spells")
                .isEqualTo(dsl.fetchCount(INTENT_FIELD_REFERENCE_STEP_HOP_KEYED)
                    + dsl.fetchCount(INTENT_FIELD_REFERENCE_STEP_HOP_KEYLESS));
        });
    }

    @Test
    @DisplayName("a keyed hop already present is refused by the key")
    void aDuplicateKeyedHopIsRefused() {
        withArmedStore(dsl -> {
            var existing = dsl.selectFrom(INTENT_FIELD_REFERENCE_STEP_HOP_KEYED).fetchAny();
            assertThat(existing).as("a keyed row to duplicate").isNotNull();
            assertThatThrownBy(() -> dsl.insertInto(INTENT_FIELD_REFERENCE_STEP_HOP_KEYED)
                .set(copyOf(dsl, existing)).execute())
                .as("the thirteen-column key names one candidate route in one orientation")
                .isInstanceOf(DataAccessException.class);
        });
    }

    @Test
    @DisplayName("a keyless hop already present is refused by the key")
    void aDuplicateKeylessHopIsRefused() {
        withArmedStore(dsl -> {
            var existing = dsl.selectFrom(INTENT_FIELD_REFERENCE_STEP_HOP_KEYLESS).fetchAny();
            assertThat(existing).as("a keyless row to duplicate").isNotNull();
            assertThatThrownBy(() -> dsl.insertInto(INTENT_FIELD_REFERENCE_STEP_HOP_KEYLESS)
                .set(copyOf(dsl, existing)).execute())
                .as("with no constraint to enumerate, the coordinate and the two triples are total")
                .isInstanceOf(DataAccessException.class);
        });
    }

    @Test
    @DisplayName("a TABLE hop carrying a namespace is refused, and a KEY hop without one too")
    void theKeyedArmHoldsViaToItsNamespace() {
        withArmedStore(dsl -> {
            assertThatThrownBy(() -> insertKeyed(dsl, "TABLE", "SQL_NAME", "a"))
                .as("a table element writes no name, so no namespace can have answered")
                .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> insertKeyed(dsl, "KEY", null, "b"))
                .as("a key element's namespace is computed from the constraint it matched")
                .isInstanceOf(DataAccessException.class);
            assertThatCode(() -> {
                insertKeyed(dsl, "TABLE", null, "c");
                insertKeyed(dsl, "KEY", "JOOQ_NAME", "d");
            }).as("the two shapes the biconditional admits").doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("a namespace outside the closed vocabulary is refused")
    void theKeyedArmHoldsItsNamespaceVocabulary() {
        withArmedStore(dsl -> assertThatThrownBy(() -> insertKeyed(dsl, "KEY", "GUESSED", "e"))
            .isInstanceOf(DataAccessException.class));
    }

    @Test
    @DisplayName("each arm refuses the other arm's verdicts")
    void neitherArmAdmitsTheOthersVerdicts() {
        withArmedStore(dsl -> {
            assertThatThrownBy(() -> insertKeyless(dsl, "KEY", "f"))
                .as("a KEY hop is identified by a constraint this relation does not carry")
                .isInstanceOf(DataAccessException.class);
            assertThatCode(() -> insertKeyless(dsl, "NAME_MATCH", "g"))
                .as("the admitted verdict, to show the case above refuses the verdict and not the row")
                .doesNotThrowAnyException();
            assertThatThrownBy(() -> insertKeyed(dsl, "CONDITION", null, "h"))
                .as("a CONDITION hop names no constraint, so it cannot be keyed by one")
                .isInstanceOf(DataAccessException.class);
        });
    }

    // ===== Helpers =====

    private static final String GRAPH = "g";
    private static final String PKG = "pkg";
    private static final String JAR = "conditions.jar";
    private static final String PUBLIC = "public";
    private static final String CONDITIONS = "com.example.Conditions";

    /** Every column of {@code row} as a jOOQ field map, which is what re-inserting it needs. */
    private static java.util.Map<org.jooq.Field<?>, Object> copyOf(DSLContext dsl, Record row) {
        var values = new java.util.LinkedHashMap<org.jooq.Field<?>, Object>();
        for (var field : row.fields()) {
            values.put(field, row.get(field));
        }
        return values;
    }

    /** One keyed row at a coordinate of the case's own, so only the constraint under test refuses. */
    private static void insertKeyed(DSLContext dsl, String via, String keyMatchedBy, String field) {
        var t = INTENT_FIELD_REFERENCE_STEP_HOP_KEYED;
        dsl.insertInto(t)
            .set(t.GRAPH_NAME, GRAPH).set(t.TYPE_NAME, "Film").set(t.FIELD_NAME, field)
            .set(t.ORDINAL, 0).set(t.POSITION, 0)
            .set(t.VIA, via).set(t.KEY_MATCHED_BY, keyMatchedBy)
            .set(t.FROM_SOURCE_NAME, PKG).set(t.FROM_SCHEMA, PUBLIC).set(t.FROM_TABLE, "film")
            .set(t.TO_SOURCE_NAME, PKG).set(t.TO_SCHEMA, PUBLIC).set(t.TO_TABLE, "actor")
            .set(t.CONSTRAINT_NAME, "film_actor_film_id_fkey").set(t.FK_ON_FROM, true)
            .execute();
    }

    /** The same for the keyless arm, whose row carries no constraint columns to state. */
    private static void insertKeyless(DSLContext dsl, String via, String field) {
        var t = INTENT_FIELD_REFERENCE_STEP_HOP_KEYLESS;
        dsl.insertInto(t)
            .set(t.GRAPH_NAME, GRAPH).set(t.TYPE_NAME, "Film").set(t.FIELD_NAME, field)
            .set(t.ORDINAL, 0).set(t.POSITION, 0).set(t.VIA, via)
            .set(t.FROM_SOURCE_NAME, PKG).set(t.FROM_SCHEMA, PUBLIC).set(t.FROM_TABLE, "film")
            .set(t.TO_SOURCE_NAME, PKG).set(t.TO_SCHEMA, PUBLIC).set(t.TO_TABLE, "actor")
            .execute();
    }

    /**
     * A catalog, a key path and a condition path, refreshed: the smallest fixture that puts rows in
     * both arm tables at once, which is what makes the duplicate cases mean anything.
     */
    private static void withArmedStore(Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            seedGraphSource(dsl, GRAPH, PKG);
            seedSource(dsl, JAR, "JAR");
            seedGraphSource(dsl, GRAPH, JAR);
            for (String table : List.of("film", "actor")) {
                seedTable(dsl, PKG, PUBLIC, table);
                seedConstraint(dsl, PKG, PUBLIC, table, table + "_pkey", "PRIMARY KEY", null);
            }
            seedConstraint(dsl, PKG, PUBLIC, "film", "film_actor_film_id_fkey", "FOREIGN KEY", null);
            seedReferentialConstraint(dsl, PKG, PUBLIC, "film", "film_actor_film_id_fkey",
                PKG, PUBLIC, "actor", "actor_pkey");

            seedTableBinding(dsl, GRAPH, "Film", "film");
            seedField(dsl, GRAPH, "Film", "actors");
            seedFieldReference(dsl, GRAPH, "Film", "actors", 0);
            seedFieldReferenceStep(dsl, GRAPH, "Film", "actors", 0, 0, null,
                "film_actor_film_id_fkey");

            seedConditionMethod(dsl, JAR, CONDITIONS, "filmToActor",
                PKG + ".tables.film", PKG + ".tables.actor");
            seedField(dsl, GRAPH, "Film", "actorsByCondition");
            seedFieldReference(dsl, GRAPH, "Film", "actorsByCondition", 0);
            seedFieldReferenceCall(dsl, GRAPH, "Film", "actorsByCondition", 0, 0,
                CONDITIONS, "filmToActor");

            derive(dsl);
            body.accept(dsl);
        });
    }
}
