package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Condition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.SQL_TABLE;

/**
 * The tables of the catalog census, by the name an author wrote or by a key a resolution produced:
 * one read of {@code sql_table}, carrying the generated table class's FQN as well as the key.
 *
 * <p>Shared because goto-definition and the unknown-table diagnostic ask one question of one
 * relation from opposite ends. Definition needs the generated class the name lands in; the
 * diagnostic needs only whether the name lands anywhere. Both need the same three-valued answer
 * about the census itself, for the reason {@link ClasspathClasses} states: a catalog nobody has
 * generated yet must not turn every {@code @table} in a schema red.
 *
 * <p>{@link CatalogColumns} carries the same pair of reads for the same reason, and the pair means
 * the same thing here. A spelling is matched case-insensitively and answers with every table that
 * spells it, ambiguity across schemas included, because which one an author meant is a resolution
 * question {@code sql_table}'s own charter leaves open. A key answers about the one table a
 * resolution already picked, so an ambiguous name cannot widen the result behind the caller.
 */
public final class CatalogTables {

    private CatalogTables() {}

    /** What the census says about one table name. */
    public sealed interface Match permits Match.Tables, Match.Unknown, Match.NoCensus {

        /** Every table that spells the name, in schema then table order, never empty. */
        record Tables(List<Table> tables) implements Match {}

        /** The census holds tables, and none of them spells this name. */
        record Unknown() implements Match {}

        /** The census holds no table at all: this graph's generated model is not there yet. */
        record NoCensus() implements Match {}
    }

    /**
     * The census's answer for {@code spelling}, matched case-insensitively: the name comes from a
     * directive an author typed rather than from the database, and the database's own casing is not
     * necessarily what they typed.
     */
    public static Match named(StoreHandle store, String spelling) {
        var tables = read(store, spelledBy(spelling));
        if (!tables.isEmpty()) {
            return new Match.Tables(tables);
        }
        return store.dsl().fetchExists(SQL_TABLE, store.reads(SQL_TABLE.SOURCE_NAME))
            ? new Match.Unknown()
            : new Match.NoCensus();
    }

    /**
     * The match rule {@link #named} applies, as a condition over {@code sql_table}, for a caller
     * composing a statement of its own. Public because a projection asking about many spellings at
     * once must ask the question this class defines rather than a second spelling of it; the rule is
     * one line today and being one line is not a reason to have two copies of it.
     */
    public static Condition spelledBy(String spelling) {
        return SQL_TABLE.TABLE_NAME.equalIgnoreCase(spelling);
    }

    /**
     * The one table a key names. Empty only where the key came from somewhere other than this
     * graph's census, which is a caller mixing two graphs' rows rather than an author's mistake.
     */
    public static Optional<Table> of(StoreHandle store, CatalogTable key) {
        return read(store, SQL_TABLE.SOURCE_NAME.eq(key.sourceName())
            .and(SQL_TABLE.TABLE_SCHEMA.eq(key.schema()))
            .and(SQL_TABLE.TABLE_NAME.eq(key.tableName()))).stream().findFirst();
    }

    /**
     * The tables whose rows jOOQ binds to {@code recordClassFqn}: the reverse of the record class
     * {@code sql_table} carries, and the only route a type known by its backing class has to a
     * table. Normally one table, and more only where two catalog sources generated the same record
     * class name, which is a resolution question this read leaves open on {@link #named}'s terms.
     *
     * <p>Empty for the census's own no-record-class sentinel, which every table jOOQ generated no
     * record for reports. Reading it as a class name would match all of them at once, and it names
     * no class a producer could deliver.
     */
    public static List<Table> ofRecordClass(StoreHandle store, String recordClassFqn) {
        if (NO_RECORD_CLASS.equals(recordClassFqn)) return List.of();
        return read(store, SQL_TABLE.RECORD_CLASS_FQN.eq(recordClassFqn));
    }

    private static final String NO_RECORD_CLASS = "org.jooq.Record";

    private static List<Table> read(StoreHandle store, Condition match) {
        var rows = store.dsl()
            .select(SQL_TABLE.SOURCE_NAME, SQL_TABLE.TABLE_SCHEMA, SQL_TABLE.TABLE_NAME,
                SQL_TABLE.CLASS_FQN)
            .from(SQL_TABLE)
            .where(store.reads(SQL_TABLE.SOURCE_NAME))
            .and(match)
            .orderBy(SQL_TABLE.TABLE_SCHEMA, SQL_TABLE.TABLE_NAME)
            .fetch();
        var tables = new ArrayList<Table>(rows.size());
        for (var row : rows) {
            tables.add(new Table(new CatalogTable(row.value1(), row.value2(), row.value3()),
                row.value4()));
        }
        return tables;
    }

    /**
     * One table: the census key, and the generated class a jump lands in.
     *
     * @param classFqn the generated jOOQ table class. Always present, the catalog walk reading it off
     *                 the live table, and it is the only route the store has to a generated source:
     *                 the class census excludes the generated package by design, so nothing else
     *                 names this class.
     */
    public record Table(CatalogTable key, String classFqn) {

        /** The table's SQL name, which is what a message about the table calls it. */
        public String tableName() {
            return key.tableName();
        }
    }
}
