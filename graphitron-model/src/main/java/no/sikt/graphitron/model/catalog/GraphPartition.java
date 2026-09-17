package no.sikt.graphitron.model.catalog;

import org.jooq.DSLContext;

import java.util.List;
import java.util.Set;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * The store's partition dimension: the column a graph-keyed relation leads its key with, the census
 * of the relations carrying it, and the selectivity the store declares for it.
 *
 * <p>One home because the predicate "does this relation carry {@code graph_name}" has more than one
 * consumer and is a branch over a model field rather than over anything a caller knows: the
 * materializer picks a refresh shape by it, the bootstrap declares a selectivity on it, and the
 * statistics reader in the generator's tests has to exclude the column it declares. Two spellings of
 * one predicate is how a relation ends up graph-keyed for one consumer and not for another.
 *
 * <p>Asked of the database rather than read off the generated model, which cannot work here: the
 * classes reading this are compiled by the codegen driver, before jOOQ has generated anything to
 * read, and that only shows on a clean build. Asking the catalog also keeps the answer right for a
 * relation the DDL never declared, which a test registers after creating it at runtime. Registry
 * rows and DDL spell relation names in lower case where H2's catalog holds the folded upper-case
 * spelling, so every name here is the folded one and a caller comparing against a registry row
 * folds it first.
 *
 * <p>One query per caller per pass, not one per relation. The materializer's shape decision had been
 * per registration per refresh, which is the same answer fetched again for each of the register's
 * entries: over one run of {@code graphitron}'s suite that was 3543 executions and 76.2 s, more than
 * every other statement that module issued put together.
 */
public final class GraphPartition {

    /** The partition column, folded as H2's catalog holds it. */
    public static final String COLUMN = "GRAPH_NAME";

    /**
     * The selectivity the store declares for {@link #COLUMN} on every graph-keyed base table, as a
     * count of distinct values per hundred rows.
     *
     * <p><b>Declared, not measured.</b> It is a floor chosen so that a partition column can never
     * price as decisive, not an estimate of any population: a store holds one partition or a few, so
     * the column's distinctness is a fact about the shape of the model rather than about the rows in
     * it. On a small relation the value is wrong in the conservative direction on purpose, and that
     * is exactly what makes it durable, a measured value being right for one population where this
     * one is right for the shape. Correcting it to a measurement is how the cliff it exists to
     * remove comes back.
     *
     * <p>What it removes: H2 reports fifty for a column no {@code ANALYZE} has looked at, which on a
     * partition column reads as nearly unique, and prices the one-column foreign-key index on
     * {@code graph_name} below a multi-column index that answers the same predicate exactly. A seek
     * that returns the whole graph's partition then looks decisive, once per driving row. A pass
     * planning inside a transaction cannot reach any other statement of this fact: H2's
     * {@code ANALYZE} commits, so such a pass can never run one, and the declared value is all its
     * planner has.
     *
     * <p>An {@code ANALYZE} overwrites it with the measured value, which is correct and not a loss:
     * on a store holding one partition the measured value is this one. The declaration is the floor
     * that holds in the window before any analysis has run.
     */
    public static final int DECLARED_SELECTIVITY = 1;

    private GraphPartition() {}

    /**
     * Every relation in this store carrying the partition column, views included: the predicate as
     * the materializer asks it, which decides whether a target is emptied for one graph or whole.
     */
    public static Set<String> keyedRelations(DSLContext dsl) {
        return dsl.select(field(name("TABLE_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "COLUMNS")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("COLUMN_NAME"), String.class).eq(COLUMN))
            .fetchSet(0, String.class);
    }

    /**
     * The same predicate narrowed to base tables, in name order: the population the declaration
     * above is stated on, a view having no selectivity of its own to state.
     */
    public static List<String> keyedBaseTables(DSLContext dsl) {
        return dsl.select(field(name("C", "TABLE_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "COLUMNS")).as("C"))
            .join(table(name("INFORMATION_SCHEMA", "TABLES")).as("T"))
            .on(field(name("T", "TABLE_SCHEMA"), String.class)
                .eq(field(name("C", "TABLE_SCHEMA"), String.class)))
            .and(field(name("T", "TABLE_NAME"), String.class)
                .eq(field(name("C", "TABLE_NAME"), String.class)))
            .where(field(name("C", "TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("C", "COLUMN_NAME"), String.class).eq(COLUMN))
            .and(field(name("T", "TABLE_TYPE"), String.class).eq("BASE TABLE"))
            .orderBy(field(name("C", "TABLE_NAME")))
            .fetch(0, String.class);
    }
}
