package no.sikt.graphitron.model.test;

import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static no.sikt.graphitron.model.Tables.META_STAGE_WRITE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_WRITE_STAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * Every relation a stage writes is one {@code meta_stage_write} declares for it, read off what every
 * fact document's captures recorded in {@code store_graph_write_stage}, and every declared relation
 * is a base table.
 *
 * <p>The declarations are what the stage order gate orders by, so a write missing from them is a
 * later stage's relation that gate cannot see. A write no fact document exercises is not caught
 * here; a stage gaining one declares it by hand. A sweep's delete counts as a write here as any
 * delete does; it removes what this reading did not find, and a stage that sweeps a relation
 * declares it.
 */
class StageWriteGateTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    @Test
    @DisplayName("every relation a stage wrote in a fact document's capture is declared for it")
    void everyWriteIsDeclared() {
        FACTS.warm(FACTS.paths());
        var undeclared = new TreeSet<String>();
        var observed = new TreeSet<String>();
        FACTS.eachStore((dsl, graphs) -> {
            var s = STORE_GRAPH_WRITE_STAGE;
            var d = META_STAGE_WRITE;
            dsl.selectDistinct(s.STAGE_NAME, lower(s.TABLE_NAME)).from(s)
                .whereNotExists(dsl.selectOne().from(d)
                    .where(d.STAGE_NAME.eq(s.STAGE_NAME), d.RELATION_NAME.eq(lower(s.TABLE_NAME))))
                .fetch(r -> "('" + r.value1() + "', '" + r.value2() + "')")
                .forEach(undeclared::add);
            dsl.selectDistinct(s.STAGE_NAME).from(s).fetch(s.STAGE_NAME).forEach(observed::add);
            return null;
        });
        assertThat(observed).as("the fact documents' captures recorded the stages' writes")
            .isNotEmpty();
        assertThat(undeclared)
            .as("writes a stage made that meta_stage_write does not declare; add the rows")
            .isEmpty();
    }

    @Test
    @DisplayName("every declared write names a base table")
    void everyDeclaredWriteIsATable() {
        ThreadConfinedStore.run(dsl -> assertThat(missing(dsl))
            .as("meta_stage_write rows naming no base table")
            .isEmpty());
    }

    private static Set<String> missing(DSLContext dsl) {
        var tables = new TreeSet<String>(dsl.select(field(name("TABLE_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "TABLES")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_TYPE"), String.class).eq("BASE TABLE"))
            .fetch(r -> r.value1().toLowerCase(Locale.ROOT)));
        var out = new TreeSet<String>();
        dsl.selectFrom(META_STAGE_WRITE).fetch().forEach(r -> {
            if (!tables.contains(r.getRelationName())) {
                out.add(r.getStageName() + " writes " + r.getRelationName());
            }
        });
        return out;
    }
}
