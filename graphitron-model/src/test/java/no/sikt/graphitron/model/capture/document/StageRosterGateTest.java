package no.sikt.graphitron.model.capture.document;

import no.sikt.graphitron.model.capture.code.CodeCapture;
import no.sikt.graphitron.model.derive.EntryDefects;
import no.sikt.graphitron.model.derive.ViewReferences;
import no.sikt.graphitron.model.test.ThreadConfinedStore;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static no.sikt.graphitron.model.Tables.META_STAGE;
import static no.sikt.graphitron.model.Tables.META_STAGE_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A gatherer's anchor phase runs its stages in the order {@code meta_stage} states, each stage
 * writes only what {@code meta_stage_write} declares for it, and no stage's rule view reads a
 * relation a later stage writes, which would read the previous reading's rows.
 *
 * <p>The order is data: {@link GraphitronAstCapture#STAGES} is the list the gatherer runs, held equal
 * to the roster here. The declared writes are held to what the stages write by
 * {@code StageWriteGateTest}, over every fact document's captures.
 *
 * <p>The rule views checked are the owner stages' defect rules, {@link EntryDefects#RULES}; a stage
 * whose rule is Java code states no read set this gate can see, as {@code StageOrderGateTest} says of
 * the derivation gatherer.
 */
class StageRosterGateTest {

    private static final String GATHERER = GraphitronAstCapture.GATHERER;

    @Test
    @DisplayName("the graphitron gatherer runs the stages its roster states, in the roster's order")
    void theRosterIsWhatTheGathererRuns() {
        ThreadConfinedStore.run(dsl -> {
            var roster = dsl.select(META_STAGE.STAGE_NAME, META_STAGE.STAGE_CLASS)
                .from(META_STAGE)
                .where(META_STAGE.GATHERER_NAME.eq(GATHERER))
                .orderBy(META_STAGE.POSITION)
                .fetch(r -> r.value1() + " " + r.value2());
            var runs = GraphitronAstCapture.STAGES.stream()
                .map(stage -> stage.name() + " " + stage.owner().getName())
                .toList();
            assertThat(runs).as("GraphitronAstCapture.STAGES against meta_stage's rows for "
                    + GATHERER + " by position; a stage added, moved or renamed in one and not the"
                    + " other runs in an order nothing checks")
                .containsExactlyElementsOf(roster);
        });
    }

    @Test
    @DisplayName("the code gatherer runs the stages its roster states, in the roster's order")
    void theCodeRosterIsWhatTheCodeGathererRuns() {
        ThreadConfinedStore.run(dsl -> {
            var roster = dsl.select(META_STAGE.STAGE_NAME, META_STAGE.STAGE_CLASS)
                .from(META_STAGE)
                .where(META_STAGE.GATHERER_NAME.eq(CodeCapture.GATHERER))
                .orderBy(META_STAGE.POSITION)
                .fetch(r -> r.value1() + " " + r.value2());
            var runs = CodeCapture.STAGES.stream()
                .map(stage -> stage.name() + " " + stage.owner().getName())
                .toList();
            assertThat(runs).as("CodeCapture.STAGES against meta_stage's rows for "
                    + CodeCapture.GATHERER + " by position")
                .containsExactlyElementsOf(roster);
        });
    }

    @Test
    @DisplayName("no stage's rule view reads a relation a later stage of its gatherer writes")
    void aStagesRuleReadsNothingALaterStageWrites() {
        ThreadConfinedStore.run(dsl -> {
            var declared = declaredWrites(dsl);
            var violations = new TreeSet<String>();
            for (var rule : EntryDefects.RULES) {
                var stage = dsl.selectFrom(META_STAGE)
                    .where(META_STAGE.STAGE_NAME.eq(rule.stage())).fetchSingle();
                var later = dsl.select(META_STAGE.STAGE_NAME).from(META_STAGE)
                    .where(META_STAGE.GATHERER_NAME.eq(stage.getGathererName()))
                    .and(META_STAGE.POSITION.gt(stage.getPosition()))
                    .fetch(META_STAGE.STAGE_NAME);
                String view = rule.view().getName().toLowerCase(Locale.ROOT);
                var reached = ViewReferences.tablesReachedBy(dsl, view);
                for (String laterStage : later) {
                    for (String written : declared.getOrDefault(laterStage, Set.of())) {
                        if (reached.contains(written)) {
                            violations.add(view + " (" + rule.stage() + ") reads " + written
                                + ", which " + laterStage + " writes after it");
                        }
                    }
                }
            }
            assertThat(violations)
                .as("a stage's rule reading a later stage's relation reads the previous reading's"
                    + " rows: move the arm to the later stage, or the stage after it")
                .isEmpty();
        });
    }

    /** Each stage's declared writes, lower case. */
    private static Map<String, Set<String>> declaredWrites(DSLContext dsl) {
        var out = new TreeMap<String, Set<String>>();
        dsl.selectFrom(META_STAGE_WRITE).fetch().forEach(r -> out
            .computeIfAbsent(r.getStageName(), k -> new TreeSet<>())
            .add(r.getRelationName().toLowerCase(Locale.ROOT)));
        return out;
    }
}
