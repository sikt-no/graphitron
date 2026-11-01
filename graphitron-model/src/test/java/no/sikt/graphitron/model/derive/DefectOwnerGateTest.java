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
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * A defect is written by the gatherer whose facts it judges: each {@code graphitron_defect_type}
 * code names its owner, that owner writes the code's {@code graphitron_entry_defect} rows and sweeps
 * only its own codes, so a code written by another gatherer would be swept by nobody, or by the
 * wrong one.
 *
 * <p>Read off the store's own definitions rather than a roster kept here. A stored writer is a rule
 * view: the codes it emits are the code literals in the view bodies its read expands, and the
 * {@code CHECK} lists of the tables it reaches that carry a code column, which is how a defect
 * relation of its own feeds a rule. A Java writer states its codes as a constant beside the
 * statement that writes them, and is listed here by the gatherer that runs it.
 */
class DefectOwnerGateTest {

    /** The rule view {@link EntryDefects} writes from. */
    private static final String RULE = "graphitron_entry_defect_rule";

    private static final Pattern LITERAL = Pattern.compile("'([A-Z][A-Z0-9_]*)'");

    @Test
    @DisplayName("every defect code names a gatherer as its owner")
    void everyOwnerIsAGatherer() {
        ThreadConfinedStore.run(dsl -> assertThat(dsl
                .select(GRAPHITRON_DEFECT_TYPE.CODE).from(GRAPHITRON_DEFECT_TYPE)
                .whereNotExists(dsl.selectOne().from(META_GATHERER)
                    .where(META_GATHERER.GATHERER_NAME.eq(GRAPHITRON_DEFECT_TYPE.OWNER_GATHERER)))
                .fetch(GRAPHITRON_DEFECT_TYPE.CODE))
            .as("codes whose owner_gatherer is no meta_gatherer row")
            .isEmpty());
    }

    @Test
    @DisplayName("the rule view emits only codes its owner owns, and its writer is that owner")
    void theRuleEmitsOnlyItsOwnersCodes() {
        ThreadConfinedStore.run(dsl -> {
            String owner = ownerOf(dsl, RULE);
            assertThat(EntryDefects.GATHERER)
                .as("the gatherer running EntryDefects is the rule view's owner")
                .isEqualTo(owner);
            var emitted = codesEmittedBy(dsl, RULE);
            assertThat(emitted).as("the rule emits codes, or this gate checks nothing").isNotEmpty();
            assertThat(foreignCodes(dsl, emitted, owner))
                .as("codes " + RULE + " emits whose owner_gatherer is not " + owner
                    + ", which its sweep would leave or another owner's sweep would take")
                .isEmpty();
        });
    }

    @Test
    @DisplayName("each Java writer of graphitron_entry_defect writes only its gatherer's codes")
    void eachJavaWriterWritesOnlyItsGatherersCodes() {
        ThreadConfinedStore.run(dsl -> javaWriters().forEach((gatherer, codes) -> {
            assertThat(codes).as("a writer listed with no codes checks nothing").isNotEmpty();
            assertThat(foreignCodes(dsl, codes, gatherer))
                .as("codes written by a stage of " + gatherer + " and owned by another gatherer")
                .isEmpty();
        }));
    }

    @Test
    @DisplayName("the rule's owner may read every table the rule reaches")
    void theRulesOwnerCanReadItsInputs() {
        ThreadConfinedStore.run(dsl -> {
            String owner = ownerOf(dsl, RULE);
            var readable = readableBy(dsl, owner);
            var unreadable = new TreeSet<String>();
            for (String table : ViewReferences.tablesReachedBy(dsl, RULE)) {
                String tableOwner = dsl.select(META_RELATION.OWNER_NAME).from(META_RELATION)
                    .where(META_RELATION.RELATION_NAME.eq(table)).fetchOne(META_RELATION.OWNER_NAME);
                if (tableOwner != null && !readable.contains(tableOwner)) {
                    unreadable.add(table + " (" + tableOwner + ")");
                }
            }
            assertThat(unreadable)
                .as("tables " + RULE + " reaches whose owner " + owner
                    + " neither owns nor declares a dependency on in meta_gatherer_dependency")
                .isEmpty();
        });
    }

    /**
     * The Java writers of {@code graphitron_entry_defect}, keyed by the gatherer whose anchor phase
     * runs them, with the codes each states beside its statement.
     */
    private static Map<String, List<String>> javaWriters() {
        return Map.of("graphitron", FieldPaths.ENTRY_DEFECT_CODES);
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

    /** Of {@code codes}, the ones whose owner is not {@code gatherer}, each with its owner. */
    private static List<String> foreignCodes(DSLContext dsl, java.util.Collection<String> codes,
                                             String gatherer) {
        var t = GRAPHITRON_DEFECT_TYPE;
        return dsl.select(t.CODE, t.OWNER_GATHERER).from(t)
            .where(t.CODE.in(codes)).and(t.OWNER_GATHERER.ne(gatherer))
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
