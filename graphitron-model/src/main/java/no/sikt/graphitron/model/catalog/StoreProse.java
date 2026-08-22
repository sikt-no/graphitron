package no.sikt.graphitron.model.catalog;

import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * The one answer to "what prose does the store carry": every {@code COMMENT ON} body and every
 * character-typed value of every {@code meta_} relation, each named by where it lives.
 *
 * <p>Store prose is checked from more than one side. It has to render as the inline AsciiDoc
 * subset the generated reference accepts, and the relation names it cites in running text have to
 * be relations the schema still declares. Both checks need the same corpus, and a corpus assembled
 * twice is one that can disagree with itself: the sweep that forgot a column would pass vacuously
 * over exactly the prose nobody was reading. So the corpus is read once, here, and the checks
 * differ only in what they do with it.
 *
 * <p>The {@code meta_} half is total over character-typed values rather than an enumerated column
 * list, so a later prose column joins the corpus by existing rather than by being remembered.
 * Reading whole {@code meta_} relations is safe at any time because their rows are a function of
 * the DDL: they hold what the file states, before any capture and after every one.
 */
public final class StoreProse {

    /** Where a prose value lives, so a finding can name it and a sweep can floor itself. */
    public enum Kind { RELATION_COMMENT, COLUMN_COMMENT, META_VALUE }

    /**
     * One prose value: its context ({@code relation} or {@code relation.column}, lowercased) and
     * the text verbatim.
     */
    public record Entry(Kind kind, String context, String text) {}

    private StoreProse() {}

    /** Reads the whole prose corpus from a booted store, comments first, then the meta rows. */
    public static List<Entry> read(DSLContext dsl) {
        var entries = new ArrayList<Entry>();
        dsl.select(field(name("TABLE_NAME"), String.class), field(name("REMARKS"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "TABLES")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .orderBy(field(name("TABLE_NAME")))
            .forEach(row -> entries.add(
                new Entry(Kind.RELATION_COMMENT, lower(row.value1()), row.value2())));
        dsl.select(field(name("TABLE_NAME"), String.class),
                field(name("COLUMN_NAME"), String.class),
                field(name("REMARKS"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "COLUMNS")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .orderBy(field(name("TABLE_NAME")), field(name("ORDINAL_POSITION")))
            .forEach(row -> entries.add(new Entry(Kind.COLUMN_COMMENT,
                lower(row.value1()) + "." + lower(row.value2()), row.value3())));

        for (String relation : metaRelations(dsl)) {
            for (var row : dsl.fetch(table(name(relation.toUpperCase(Locale.ROOT))))) {
                for (var value : row.fields()) {
                    if (row.get(value) instanceof String text) {
                        entries.add(new Entry(Kind.META_VALUE,
                            relation + "." + lower(value.getName()), text));
                    }
                }
            }
        }
        return entries;
    }

    private static List<String> metaRelations(DSLContext dsl) {
        return dsl.select(field(name("RELATION_NAME"), String.class))
            .from(table(name("META_RELATION_FAMILY")))
            .where(field(name("PREFIX"), String.class).eq("meta_"))
            .orderBy(field(name("RELATION_NAME")))
            .fetch(0, String.class);
    }

    private static String lower(String identifier) {
        return identifier.toLowerCase(Locale.ROOT);
    }
}
