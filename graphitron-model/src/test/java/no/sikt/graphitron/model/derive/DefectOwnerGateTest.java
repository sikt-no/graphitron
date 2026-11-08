package no.sikt.graphitron.model.derive;

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
import static no.sikt.graphitron.model.Tables.META_GATHERER;
import static no.sikt.graphitron.model.Tables.META_GATHERER_DEPENDENCY;
import static no.sikt.graphitron.model.Tables.META_RELATION;
import static no.sikt.graphitron.model.Tables.META_STAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * A defect is written by the stage whose facts it judges: each {@code graphitron_defect_type} code
 * names its owner stage, a {@code meta_stage} row, that stage writes the code's
 * {@code graphitron_entry_defect} rows and no other stage writes them, and the stage's gatherer
 * sweeps only its stages' codes, so a code written by another gatherer would be swept by nobody, or
 * by the wrong one.
 *
 * <p>Read off the store's own definitions rather than a roster kept here. A stored writer is an
 * owner's rule view, one per owner stage in {@link EntryDefects#RULES}: the codes it emits are the
 * code literals in the view bodies its read expands, and the {@code CHECK} lists of the tables it
 * reaches that carry a code column, which is how a defect relation of its own feeds a rule. A Java
 * writer states its codes as a constant beside the statement that writes them, and is listed here by
 * its class, which names its stage through {@code meta_stage.stage_class}.
 */
class DefectOwnerGateTest {

    /** The suffix an owner's rule view is named with, after its family's prefix. */
    private static final String RULE_SUFFIX = "_ENTRY_DEFECT_RULE";

    private static final Pattern LITERAL = Pattern.compile("'([A-Z][A-Z0-9_]*)'");

    /**
     * Codes a stage writes beside the stage that owns them, each with why. Not a ratchet to grow: a
     * code is one rule, and two writers of it are two statements of that rule with nothing comparing
     * them. An entry comes off when the arm moves to the owner.
     */
    private static final Map<String, String> SHARED = Map.of(
        "ROUTE_AMBIGUOUS", "graphitron_entry_defect_rule's inferred-route arm, read off the depth"
            + " loop's needs and claims, beside the walk's written-route defect; it moves to the walk",
        "NO_ROUTE_TO_TARGET", "the same inferred-route arm as ROUTE_AMBIGUOUS");

    @Test
    @DisplayName("every defect code names a stage of a gatherer as its owner")
    void everyOwnerIsAStage() {
        ThreadConfinedStore.run(dsl -> assertThat(dsl
                .select(GRAPHITRON_DEFECT_TYPE.CODE).from(GRAPHITRON_DEFECT_TYPE)
                .whereNotExists(dsl.selectOne().from(META_STAGE).join(META_GATHERER)
                    .on(META_GATHERER.GATHERER_NAME.eq(META_STAGE.GATHERER_NAME))
                    .where(META_STAGE.STAGE_NAME.eq(GRAPHITRON_DEFECT_TYPE.OWNER_STAGE)))
                .fetch(GRAPHITRON_DEFECT_TYPE.CODE))
            .as("codes whose owner_stage is no meta_stage row of a gatherer")
            .isEmpty());
    }

    @Test
    @DisplayName("a stage owns exactly the codes it writes")
    void aStageOwnsExactlyTheCodesItWrites() {
        ThreadConfinedStore.run(dsl -> {
            var written = new java.util.HashMap<String, Set<String>>();
            for (var rule : EntryDefects.RULES) {
                written.computeIfAbsent(rule.stage(), k -> new TreeSet<>())
                    .addAll(codesEmittedBy(dsl, rule.view().getName().toLowerCase(java.util.Locale.ROOT)));
            }
            javaWriters().forEach((writer, codes) ->
                written.computeIfAbsent(stageOf(dsl, writer), k -> new TreeSet<>()).addAll(codes));
            var t = GRAPHITRON_DEFECT_TYPE;
            var owned = new java.util.HashMap<String, Set<String>>();
            dsl.select(t.OWNER_STAGE, t.CODE).from(t).fetch().forEach(r ->
                owned.computeIfAbsent(r.value1(), k -> new TreeSet<>()).add(r.value2()));
            var unwritten = new TreeSet<String>();
            owned.forEach((stage, codes) -> codes.stream()
                .filter(code -> !written.getOrDefault(stage, Set.of()).contains(code))
                .forEach(code -> unwritten.add(code + " (" + stage + ")")));
            assertThat(unwritten)
                .as("codes whose owner stage writes none of their rows: a stage owning a code it"
                    + " does not write leaves the code to a writer the sweep does not hold to it")
                .isEmpty();
            var foreign = new TreeSet<String>();
            written.forEach((stage, codes) -> codes.stream()
                .filter(code -> !owned.getOrDefault(stage, Set.of()).contains(code))
                .filter(code -> !SHARED.containsKey(code))
                .forEach(code -> foreign.add(code + " (written by " + stage + ")")));
            assertThat(foreign)
                .as("codes a stage writes that another stage owns")
                .isEmpty();
        });
    }

    @Test
    @DisplayName("every owner's rule view is a rule EntryDefects writes, one per owner")
    void everyRuleViewIsWritten() {
        ThreadConfinedStore.run(dsl -> {
            var declared = new TreeSet<String>(dsl.select(field(name("TABLE_NAME"), String.class))
                .from(table(name("INFORMATION_SCHEMA", "VIEWS")))
                .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
                .and(field(name("TABLE_NAME"), String.class).endsWith(RULE_SUFFIX))
                .fetch(0, String.class));
            var written = new TreeSet<String>();
            for (var rule : EntryDefects.RULES) {
                written.add(rule.view().getName().toUpperCase(java.util.Locale.ROOT));
            }
            assertThat(written).as("the rule views EntryDefects.RULES names, against the views"
                    + " named *" + RULE_SUFFIX.toLowerCase() + " in the store; a view no Rule names"
                    + " is a rule nobody writes")
                .isEqualTo(declared);
            assertThat(EntryDefects.RULES.stream().map(EntryDefects.Rule::stage).distinct().count())
                .as("one rule per owner stage")
                .isEqualTo(EntryDefects.RULES.size());
        });
    }

    @Test
    @DisplayName("each rule view emits only codes its stage owns, and the view's owner runs that stage")
    void eachRuleEmitsOnlyItsOwnersCodes() {
        ThreadConfinedStore.run(dsl -> {
            for (var rule : EntryDefects.RULES) {
                String view = rule.view().getName().toLowerCase(java.util.Locale.ROOT);
                assertThat(gathererOf(dsl, rule.stage()))
                    .as("the gatherer running " + view + "'s stage is the view's owner")
                    .isEqualTo(ownerOf(dsl, view));
                var emitted = codesEmittedBy(dsl, view);
                assertThat(emitted).as(view + " emits codes, or this gate checks nothing")
                    .isNotEmpty();
                emitted.removeAll(sharedWrittenBy(dsl, emitted));
                assertThat(foreignCodes(dsl, emitted, rule.stage()))
                    .as("codes " + view + " emits whose owner_stage is not " + rule.stage()
                        + ", which another stage writes or its sweep would take")
                    .isEmpty();
            }
        });
    }

    @Test
    @DisplayName("each Java writer of graphitron_entry_defect is a stage and writes only its codes")
    void eachJavaWriterWritesOnlyItsStagesCodes() {
        ThreadConfinedStore.run(dsl -> javaWriters().forEach((writer, codes) -> {
            assertThat(codes).as("a writer listed with no codes checks nothing").isNotEmpty();
            String stage = stageOf(dsl, writer);
            assertThat(foreignCodes(dsl, codes, stage))
                .as("codes written by " + writer.getSimpleName() + ", stage " + stage
                    + ", and owned by another stage")
                .isEmpty();
        }));
    }

    @Test
    @DisplayName("each rule's owner may read every table its rule reaches")
    void eachRulesOwnerCanReadItsInputs() {
        ThreadConfinedStore.run(dsl -> {
            for (var rule : EntryDefects.RULES) {
                String view = rule.view().getName().toLowerCase(java.util.Locale.ROOT);
                String owner = gathererOf(dsl, rule.stage());
                var readable = readableBy(dsl, owner);
                var unreadable = new TreeSet<String>();
                for (String table : ViewReferences.tablesReachedBy(dsl, view)) {
                    String tableOwner = dsl.select(META_RELATION.OWNER_NAME).from(META_RELATION)
                        .where(META_RELATION.RELATION_NAME.eq(table))
                        .fetchOne(META_RELATION.OWNER_NAME);
                    if (tableOwner != null && !readable.contains(tableOwner)) {
                        unreadable.add(table + " (" + tableOwner + ")");
                    }
                }
                assertThat(unreadable)
                    .as("tables " + view + " reaches whose owner " + owner
                        + " neither owns nor declares a dependency on in meta_gatherer_dependency")
                    .isEmpty();
            }
        });
    }

    /**
     * The Java writers of {@code graphitron_entry_defect}, keyed by their class, which
     * {@code meta_stage.stage_class} names, with the codes each states beside its statement.
     */
    private static Map<Class<?>, List<String>> javaWriters() {
        return Map.of(Steps.class, Steps.ENTRY_DEFECT_CODES);
    }

    /**
     * Of {@code codes}, the {@link #SHARED} ones, which a stage writes beside their owner. The owner
     * still writes them: a shared code its owner stopped writing is no longer shared.
     */
    private static Set<String> sharedWrittenBy(DSLContext dsl, Set<String> codes) {
        var out = new TreeSet<String>();
        for (String code : codes) {
            if (SHARED.containsKey(code)) {
                out.add(code);
            }
        }
        return out;
    }

    @Test
    @DisplayName("a shared code is still written by the stage that owns it")
    void aSharedCodeIsWrittenByItsOwner() {
        ThreadConfinedStore.run(dsl -> {
            var t = GRAPHITRON_DEFECT_TYPE;
            for (String code : SHARED.keySet()) {
                String owner = dsl.select(t.OWNER_STAGE).from(t).where(t.CODE.eq(code))
                    .fetchSingle(t.OWNER_STAGE);
                var ownWrites = new TreeSet<String>();
                javaWriters().forEach((writer, codes) -> {
                    if (stageOf(dsl, writer).equals(owner)) {
                        ownWrites.addAll(codes);
                    }
                });
                for (var rule : EntryDefects.RULES) {
                    if (rule.stage().equals(owner)) {
                        ownWrites.addAll(codesEmittedBy(dsl,
                            rule.view().getName().toLowerCase(java.util.Locale.ROOT)));
                    }
                }
                assertThat(ownWrites).as(code + " is listed as shared, so its owner " + owner
                    + " writes it too; take it off SHARED once one writer is left").contains(code);
            }
        });
    }

    /** The {@code meta_stage} row whose class is {@code writer}. */
    private static String stageOf(DSLContext dsl, Class<?> writer) {
        return dsl.select(META_STAGE.STAGE_NAME).from(META_STAGE)
            .where(META_STAGE.STAGE_CLASS.eq(writer.getName()))
            .fetchOptional(META_STAGE.STAGE_NAME)
            .orElseThrow(() -> new AssertionError(writer.getName()
                + " writes graphitron_entry_defect and is no meta_stage row's class"));
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

    /** Of {@code codes}, the ones whose owner is not {@code stage}, each with its owner. */
    private static List<String> foreignCodes(DSLContext dsl, java.util.Collection<String> codes,
                                             String stage) {
        var t = GRAPHITRON_DEFECT_TYPE;
        return dsl.select(t.CODE, t.OWNER_STAGE).from(t)
            .where(t.CODE.in(codes)).and(t.OWNER_STAGE.ne(stage))
            .orderBy(t.CODE)
            .fetch(r -> r.value1() + " (" + r.value2() + ")");
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
            texts.add(dsl.select(field(name("VIEW_DEFINITION"), String.class))
                .from(table(name("INFORMATION_SCHEMA", "VIEWS")))
                .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
                .and(field(name("TABLE_NAME"), String.class).equalIgnoreCase(view))
                .fetchSingle(0, String.class));
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
            var m = LITERAL.matcher(text);
            while (m.find()) {
                if (known.contains(m.group(1))) {
                    out.add(m.group(1));
                }
            }
        }
        return out;
    }
}
