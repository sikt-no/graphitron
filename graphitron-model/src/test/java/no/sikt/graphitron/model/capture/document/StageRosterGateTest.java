package no.sikt.graphitron.model.capture.document;

import no.sikt.graphitron.model.capture.code.CodeCapture;
import no.sikt.graphitron.model.derive.StageAnswerAgreementTest;
import no.sikt.graphitron.model.derive.ViewReferences;
import no.sikt.graphitron.model.test.StageRuleFiles;
import no.sikt.graphitron.model.test.ThreadConfinedStore;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static no.sikt.graphitron.model.Tables.META_GATHERER_DEPENDENCY;
import static no.sikt.graphitron.model.Tables.META_RELATION;
import static no.sikt.graphitron.model.Tables.META_STAGE;
import static no.sikt.graphitron.model.Tables.META_STAGE_DEPENDENCY;
import static no.sikt.graphitron.model.Tables.META_STAGE_WRITE;
import static no.sikt.graphitron.model.Tables.META_STAGE_WRITE_KIND;
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
 * <p>The rule views checked are the owner stages' defect rules, {@code meta_stage_rule}; a stage
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
            var kinds = declaredKinds(dsl);
            var violations = new TreeSet<String>();
            var rules = new java.util.ArrayList<>(StageAnswerAgreementTest.defectRules(dsl));
            // A stage's defect rule kept as a file of its own, under the stage its folder names.
            var files = StageRuleFiles.all().stream()
                .filter(file -> file.rule().endsWith("_entry_defect_rule")).toList();
            files.forEach(file ->
                rules.add(new StageAnswerAgreementTest.DefectRule(file.stage(), file.rule())));
            StageRuleFiles.withViews(dsl, files, () -> {
                for (var rule : rules) {
                    var stage = dsl.selectFrom(META_STAGE)
                        .where(META_STAGE.STAGE_NAME.eq(rule.stage())).fetchSingle();
                    var later = dsl.select(META_STAGE.STAGE_NAME).from(META_STAGE)
                        .where(META_STAGE.GATHERER_NAME.eq(stage.getGathererName()))
                        .and(META_STAGE.POSITION.gt(stage.getPosition()))
                        .fetch(META_STAGE.STAGE_NAME);
                    var upTo = dsl.select(META_STAGE.STAGE_NAME).from(META_STAGE)
                        .where(META_STAGE.GATHERER_NAME.eq(stage.getGathererName()))
                        .and(META_STAGE.POSITION.le(stage.getPosition()))
                        .fetch(META_STAGE.STAGE_NAME);
                    String view = rule.view();
                    var reached = ViewReferences.tablesReachedBy(dsl, view);
                    for (String laterStage : later) {
                        for (String written : declared.getOrDefault(laterStage, Set.of())) {
                            // A relation split by a kind column is read in the kinds the reader or a stage
                            // before it writes, which the later stage never writes.
                            if (reached.contains(written) && !splitApart(kinds, upTo, laterStage, written)) {
                                violations.add(view + " (" + rule.stage() + ") reads " + written
                                    + ", which " + laterStage + " writes after it");
                            }
                        }
                    }
                }
            });
            assertThat(violations)
                .as("a stage's rule reading a later stage's relation reads the previous reading's"
                    + " rows: move the arm to the later stage, or the stage after it")
                .isEmpty();
        });
    }

    @Test
    @DisplayName("a stage declares only earlier stages of its own gatherer as the ones it reads from")
    void aStageDeclaresOnlyEarlierStagesOfItsGatherer() {
        ThreadConfinedStore.run(dsl -> {
            var stage = META_STAGE.as("s");
            var upstream = META_STAGE.as("u");
            var d = META_STAGE_DEPENDENCY;
            var wrong = dsl.select(d.STAGE_NAME, d.UPSTREAM_STAGE_NAME).from(d)
                .join(stage).on(stage.STAGE_NAME.eq(d.STAGE_NAME))
                .join(upstream).on(upstream.STAGE_NAME.eq(d.UPSTREAM_STAGE_NAME))
                .where(upstream.GATHERER_NAME.ne(stage.GATHERER_NAME)
                    .or(upstream.POSITION.ge(stage.POSITION)))
                .fetch(r -> r.value1() + " names " + r.value2());
            assertThat(wrong)
                .as("a stage reads another gatherer's relations through meta_gatherer_dependency, and only"
                    + " the stages before it in its own")
                .isEmpty();
        });
    }

    /**
     * A stage reads only from the stages before it that it declares (Alf, 2026-11-27): every relation
     * its rule reaches is written by the stage itself, by an earlier stage of its gatherer it names in
     * {@code meta_stage_dependency}, by its gatherer's capture, or by a gatherer its gatherer depends
     * on. A relation no gatherer owns, a stated vocabulary, is everyone's to read. Over the rules kept
     * as files of their stage's own; a rule still a view is held to the order by the case above.
     */
    @Test
    @DisplayName("every relation a stage's rule file reads comes from a stage or gatherer it may read from")
    void aStageReadsOnlyWhatItDeclares() {
        ThreadConfinedStore.run(dsl -> {
            var gathererOf = new TreeMap<String, String>();
            dsl.select(META_STAGE.STAGE_NAME, META_STAGE.GATHERER_NAME).from(META_STAGE)
                .forEach(r -> gathererOf.put(r.value1(), r.value2()));
            var writers = new TreeMap<String, Set<String>>();
            dsl.select(META_STAGE_WRITE.RELATION_NAME, META_STAGE_WRITE.STAGE_NAME).from(META_STAGE_WRITE)
                .forEach(r -> writers
                    .computeIfAbsent(r.value1().toLowerCase(Locale.ROOT), k -> new TreeSet<>())
                    .add(r.value2()));
            var owner = new TreeMap<String, String>();
            dsl.select(META_RELATION.RELATION_NAME, META_RELATION.OWNER_NAME).from(META_RELATION)
                .forEach(r -> owner.put(r.value1().toLowerCase(Locale.ROOT), r.value2()));
            var dependsOn = new TreeSet<String>();
            dsl.select(META_GATHERER_DEPENDENCY.GATHERER_NAME, META_GATHERER_DEPENDENCY.DEPENDS_ON)
                .from(META_GATHERER_DEPENDENCY)
                .forEach(r -> dependsOn.add(r.value1() + " " + r.value2()));
            var declared = new TreeSet<String>();
            dsl.selectFrom(META_STAGE_DEPENDENCY)
                .forEach(r -> declared.add(r.getStageName() + " " + r.getUpstreamStageName()));
            var undeclared = new TreeSet<String>();
            var files = StageRuleFiles.all();
            StageRuleFiles.withViews(dsl, files, () -> {
                for (var file : files) {
                    String gatherer = file.gatherer();
                    for (String table : ViewReferences.tablesReachedBy(dsl, file.rule())) {
                        var stagesWriting = writers.getOrDefault(table, Set.of());
                        var own = stagesWriting.stream()
                            .filter(w -> gatherer.equals(gathererOf.get(w))).toList();
                        boolean readable;
                        if (!own.isEmpty()) {
                            readable = own.stream().anyMatch(w -> w.equals(file.stage())
                                || declared.contains(file.stage() + " " + w));
                        } else {
                            var sources = new TreeSet<String>();
                            stagesWriting.forEach(w -> sources.add(gathererOf.get(w)));
                            if (owner.get(table) != null) {
                                sources.add(owner.get(table));
                            }
                            readable = sources.stream().allMatch(source -> source.equals(gatherer)
                                || dependsOn.contains(gatherer + " " + source));
                        }
                        if (!readable) {
                            undeclared.add(file.stage() + "'s " + file.rule() + " reads " + table
                                + ", written by "
                                + (stagesWriting.isEmpty() ? owner.get(table) : stagesWriting));
                        }
                    }
                }
            });
            assertThat(undeclared)
                .as("reads no declaration allows: name the earlier stage in meta_stage_dependency, or the"
                    + " gatherer in meta_gatherer_dependency")
                .isEmpty();
        });
    }

    /** Each stage's declared kinds of each relation it shares, keyed by stage and relation. */
    private static Map<String, Set<String>> declaredKinds(DSLContext dsl) {
        var out = new TreeMap<String, Set<String>>();
        dsl.selectFrom(META_STAGE_WRITE_KIND).fetch().forEach(r -> out
            .computeIfAbsent(r.getStageName() + " " + r.getRelationName().toLowerCase(Locale.ROOT),
                k -> new TreeSet<>())
            .add(r.getKind()));
        return out;
    }

    /**
     * Whether {@code later} writes {@code relation} split by a kind column from a stage in
     * {@code earlier}, the reader or a stage before it, each declaring kinds of it and none in
     * common: the split the fact model allows (Alf, 2026-11-12), read from the reader's own kinds
     * or from those an earlier stage wrote, a stage being allowed to read rows earlier stages wrote
     * (Alf, 2026-11-17).
     */
    private static boolean splitApart(Map<String, Set<String>> kinds, List<String> earlier, String later,
                                      String relation) {
        var theirs = kinds.get(later + " " + relation);
        return theirs != null && earlier.stream()
            .map(stage -> kinds.get(stage + " " + relation))
            .anyMatch(mine -> mine != null && mine.stream().noneMatch(theirs::contains));
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
