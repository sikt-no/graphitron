package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.StageRuleFiles;
import no.sikt.graphitron.model.test.ThreadConfinedStore;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEFECT_TYPE;
import static no.sikt.graphitron.model.Tables.META_GATHERER_DEPENDENCY;
import static no.sikt.graphitron.model.Tables.META_RELATION;
import static no.sikt.graphitron.model.Tables.META_STAGE;
import static no.sikt.graphitron.model.Tables.META_STAGE_WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * A defect is raised by the stage holding its facts and keyed by it: nobody owns a code, so two stages
 * may raise one rule, and each stage writes and sweeps the rows it raised under its own name. Each
 * stage that raises defects does so from a rule view of its own, whose rows name that stage, or from
 * a Java writer stating its stage beside its statement; every such stage declares the write; and every
 * code the vocabulary declares is raised by some stage.
 *
 * <p>Read off the store's own definitions rather than a roster kept here. A stored writer is a stage's
 * rule view in {@code meta_stage_rule}, usually one per stage; a stage may raise from a second where
 * its first is a long union, since H2 keeps a parsed copy of a union at every level of its nesting and
 * each further arm costs every session holding the rule more than a short rule of its own does. The
 * codes it emits are the code literals in
 * the view bodies its read expands, and the {@code CHECK} lists of the tables it reaches that carry a
 * code column, which is how a defect relation of its own feeds a rule. A Java writer states its stage
 * and codes as constants beside the statement that writes them, and is listed here.
 */
class DefectStageGateTest {

    /** The suffix a stage's rule view is named with, after its family's prefix. */
    private static final String RULE_SUFFIX = "_ENTRY_DEFECT_RULE";

    private static final Pattern CODE_LITERAL = Pattern.compile("'([A-Z][A-Z0-9_]*)'");

    private static final Pattern STAGE_LITERAL = Pattern.compile("'([a-z][a-z0-9-]*)'");

    @Test
    @DisplayName("every defect rule view is one meta_stage_rule states")
    void everyRuleViewIsWritten() {
        ThreadConfinedStore.run(dsl -> {
            var declared = new TreeSet<String>(dsl.select(field(name("TABLE_NAME"), String.class))
                .from(table(name("INFORMATION_SCHEMA", "VIEWS")))
                .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
                .and(field(name("TABLE_NAME"), String.class).endsWith(RULE_SUFFIX))
                .fetch(0, String.class));
            var written = new TreeSet<String>();
            for (var rule : StageAnswerAgreementTest.defectRules(dsl)) {
                written.add(rule.view().toUpperCase(java.util.Locale.ROOT));
            }
            assertThat(written).as("the defect rule views meta_stage_rule names, against the views"
                    + " named *" + RULE_SUFFIX.toLowerCase() + " in the store; a view no Rule names"
                    + " is a rule nobody writes")
                .isEqualTo(declared);
        });
    }

    @Test
    @DisplayName("each rule view names its own stage and no other, and emits codes")
    void eachRuleNamesItsOwnStage() {
        ThreadConfinedStore.run(dsl -> {
            var stages = new HashSet<>(dsl.select(META_STAGE.STAGE_NAME).from(META_STAGE)
                .fetch(META_STAGE.STAGE_NAME));
            for (var rule : StageAnswerAgreementTest.defectRules(dsl)) {
                String view = rule.view();
                assertThat(definitionColumns(dsl, view)).as(view + " has a stage column").contains("STAGE");
                var named = new TreeSet<String>();
                // A literal compared against, a directive name such as 'outbox' among them, names
                // what the rule reads, not the stage its rows name.
                String projected = definitionOf(dsl, view)
                    .replaceAll("(=|<>)\\s*'[^']*'", "")
                    .replaceAll("(?i)\\bIN\\s*\\([^)]*\\)", "");
                var m = STAGE_LITERAL.matcher(projected);
                while (m.find()) {
                    if (stages.contains(m.group(1))) {
                        named.add(m.group(1));
                    }
                }
                assertThat(named).as(view + "'s rows name the stage that raises them, "
                        + rule.stage() + ", and no other")
                    .containsExactly(rule.stage());
                assertThat(codesEmittedBy(dsl, view)).as(view + " emits codes, or this gate checks nothing")
                    .isNotEmpty();
            }
        });
    }

    @Test
    @DisplayName("each Java writer of graphitron_entry_defect states its stage and codes")
    void eachJavaWriterStatesItsStage() {
        ThreadConfinedStore.run(dsl -> javaWriters().forEach((stage, codes) -> {
            assertThat(codes).as("a writer listed with no codes checks nothing").isNotEmpty();
            assertThat(dsl.fetchExists(META_STAGE, META_STAGE.STAGE_NAME.eq(stage)))
                .as(stage + " is a meta_stage row").isTrue();
        }));
    }

    @Test
    @DisplayName("every stage raising defects declares that it writes graphitron_entry_defect")
    void everyRaisingStageDeclaresTheWrite() {
        ThreadConfinedStore.run(dsl -> {
            var raising = new TreeSet<String>(javaWriters().keySet());
            StageAnswerAgreementTest.defectRules(dsl).forEach(rule -> raising.add(rule.stage()));
            var declared = new TreeSet<String>(dsl.select(META_STAGE_WRITE.STAGE_NAME).from(META_STAGE_WRITE)
                .where(META_STAGE_WRITE.RELATION_NAME.eq("graphitron_entry_defect"))
                .fetch(META_STAGE_WRITE.STAGE_NAME));
            assertThat(declared).as("the stages meta_stage_write says write graphitron_entry_defect,"
                    + " against the stages raising defects")
                .containsAll(raising);
        });
    }

    @Test
    @DisplayName("every declared code is raised by some stage")
    void everyCodeIsRaised() {
        ThreadConfinedStore.run(dsl -> {
            var raised = new TreeSet<String>();
            for (var rule : StageAnswerAgreementTest.defectRules(dsl)) {
                raised.addAll(codesEmittedBy(dsl, rule.view()));
            }
            // A stage's rule kept as a file of its own raises codes the same way.
            var files = defectFiles();
            StageRuleFiles.withViews(dsl, files,
                () -> files.forEach(file -> raised.addAll(codesEmittedBy(dsl, file.rule()))));
            javaWriters().values().forEach(raised::addAll);
            var declared = new TreeSet<String>(dsl.select(GRAPHITRON_DEFECT_TYPE.CODE)
                .from(GRAPHITRON_DEFECT_TYPE).fetch(GRAPHITRON_DEFECT_TYPE.CODE));
            declared.removeAll(raised);
            assertThat(declared).as("codes the vocabulary declares and no stage raises").isEmpty();
        });
    }

    @Test
    @DisplayName("each rule's gatherer may read every table its rule reaches")
    void eachRulesGathererCanReadItsInputs() {
        ThreadConfinedStore.run(dsl -> {
            for (var rule : StageAnswerAgreementTest.defectRules(dsl)) {
                String view = rule.view();
                String gatherer = gathererOf(dsl, rule.stage());
                assertThat(gatherer).as("the gatherer running " + view + "'s stage is the view's owner")
                    .isEqualTo(ownerOf(dsl, view));
                assertThat(unreadable(dsl, gatherer, view))
                    .as("tables " + view + " reaches whose owner " + gatherer
                        + " neither owns nor declares a dependency on in meta_gatherer_dependency")
                    .isEmpty();
            }
        });
    }

    /** The tables {@code rule} reaches whose owner {@code gatherer} neither owns nor may read. */
    private static Set<String> unreadable(DSLContext dsl, String gatherer, String rule) {
        var readable = readableBy(dsl, gatherer);
        var unreadable = new TreeSet<String>();
        for (String table : ViewReferences.tablesReachedBy(dsl, rule)) {
            String tableOwner = dsl.select(META_RELATION.OWNER_NAME).from(META_RELATION)
                .where(META_RELATION.RELATION_NAME.eq(table))
                .fetchOne(META_RELATION.OWNER_NAME);
            if (tableOwner != null && !readable.contains(tableOwner)) {
                unreadable.add(table + " (" + tableOwner + ")");
            }
        }
        return unreadable;
    }

    /** The stages' defect rules kept as files of their own, which raise rows of the defect relation. */
    static java.util.List<StageRuleFiles.RuleFile> defectFiles() {
        return StageRuleFiles.all().stream()
            .filter(file -> file.rule().endsWith("_entry_defect_rule"))
            .toList();
    }

    /**
     * The Java writers of {@code graphitron_entry_defect}, keyed by the stage each states, with the
     * codes each states beside its statement: {@link Steps} writes the walk's defects inside
     * {@link OccurrenceDepths}.
     */
    private static Map<String, List<String>> javaWriters() {
        return Map.of(Steps.STAGE, Steps.ENTRY_DEFECT_CODES);
    }

    /** The gatherer whose anchor phase runs {@code stage}. */
    private static String gathererOf(DSLContext dsl, String stage) {
        return dsl.select(META_STAGE.GATHERER_NAME).from(META_STAGE)
            .where(META_STAGE.STAGE_NAME.eq(stage)).fetchSingle(META_STAGE.GATHERER_NAME);
    }

    private static String ownerOf(DSLContext dsl, String relation) {
        return dsl.select(META_RELATION.OWNER_NAME).from(META_RELATION)
            .where(META_RELATION.RELATION_NAME.eq(relation)).fetchSingle(META_RELATION.OWNER_NAME);
    }

    /** The gatherer itself and every gatherer it declares it may read. */
    private static Set<String> readableBy(DSLContext dsl, String gatherer) {
        var out = new HashSet<String>(dsl.select(META_GATHERER_DEPENDENCY.DEPENDS_ON)
            .from(META_GATHERER_DEPENDENCY)
            .where(META_GATHERER_DEPENDENCY.GATHERER_NAME.eq(gatherer))
            .fetch(META_GATHERER_DEPENDENCY.DEPENDS_ON));
        out.add(gatherer);
        return out;
    }

    /** The stored definition of {@code view}. */
    private static String definitionOf(DSLContext dsl, String view) {
        return dsl.select(field(name("VIEW_DEFINITION"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "VIEWS")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_NAME"), String.class).equalIgnoreCase(view))
            .fetchSingle(0, String.class);
    }

    /**
     * The defect codes a read of {@code rule} can emit: code literals in every view body the read
     * expands, and the {@code CHECK} lists of the tables it reaches, kept to the strings that are
     * {@code graphitron_defect_type} codes.
     */
    private static Set<String> codesEmittedBy(DSLContext dsl, String rule) {
        var known = new HashSet<>(dsl.select(GRAPHITRON_DEFECT_TYPE.CODE).from(GRAPHITRON_DEFECT_TYPE)
            .fetch(GRAPHITRON_DEFECT_TYPE.CODE));
        var texts = new java.util.ArrayList<String>();
        for (String view : ViewReferences.viewsEvaluatedBy(dsl, List.of(rule))) {
            texts.add(definitionOf(dsl, view));
        }
        for (String reached : ViewReferences.tablesReachedBy(dsl, rule)) {
            // A stated vocabulary whose rows name a code, as graphitron_claim_inclusion names the
            // defect a pair of claims is reported under, emits the codes its rows hold.
            if (!reached.equalsIgnoreCase("graphitron_defect_type")
                    && dsl.fetchExists(dsl.selectOne().from(table(name("META_STATED_RELATION")))
                        .where(field(name("RELATION_NAME"), String.class).equalIgnoreCase(reached)))
                    && dsl.fetchExists(dsl.selectOne().from(table(name("INFORMATION_SCHEMA", "COLUMNS")))
                        .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
                        .and(field(name("TABLE_NAME"), String.class).equalIgnoreCase(reached))
                        .and(field(name("COLUMN_NAME"), String.class).eq("CODE")))) {
                dsl.selectDistinct(field(name("CODE"), String.class))
                    .from(table(name(reached.toUpperCase(java.util.Locale.ROOT))))
                    .where(field(name("CODE")).isNotNull())
                    .fetch(0, String.class)
                    .forEach(code -> texts.add("'" + code + "'"));
            }
            texts.addAll(dsl.select(field(name("cc", "CHECK_CLAUSE"), String.class))
                .from(table(name("INFORMATION_SCHEMA", "TABLE_CONSTRAINTS")).as("tc"))
                .join(table(name("INFORMATION_SCHEMA", "CHECK_CONSTRAINTS")).as("cc"))
                .on(field(name("cc", "CONSTRAINT_NAME")).eq(field(name("tc", "CONSTRAINT_NAME"))))
                .where(field(name("tc", "TABLE_SCHEMA"), String.class).eq("PUBLIC"))
                .and(field(name("tc", "TABLE_NAME"), String.class).equalIgnoreCase(reached))
                .fetch(0, String.class));
        }
        var out = new LinkedHashSet<String>();
        for (String text : texts) {
            var m = CODE_LITERAL.matcher(text);
            while (m.find()) {
                if (known.contains(m.group(1))) {
                    out.add(m.group(1));
                }
            }
        }
        return out;
    }

    /** The view's columns, upper case, as the catalog lists them. */
    private static java.util.Set<String> definitionColumns(org.jooq.DSLContext dsl, String view) {
        return new java.util.TreeSet<>(dsl.select(field(name("COLUMN_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "COLUMNS")))
            .where(field(name("TABLE_NAME"), String.class).eq(view.toUpperCase(java.util.Locale.ROOT)))
            .fetch(0, String.class));
    }
}
