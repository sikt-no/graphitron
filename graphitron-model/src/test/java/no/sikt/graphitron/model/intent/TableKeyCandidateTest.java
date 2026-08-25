package no.sikt.graphitron.model.intent;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.INTENT_TABLE_KEY_CANDIDATE;
import static no.sikt.graphitron.model.test.SeededStore.seedColumn;
import static no.sikt.graphitron.model.test.SeededStore.seedPrimaryKey;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.seedUniqueKey;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code intent_table_key_candidate} states: the keys a consumer may identify a row by, in the
 * order the generator considers them.
 *
 * <p>The order is what the cases are about rather than the membership. A relation listing the same
 * keys unordered would pass every assertion that only asked which keys came out, and would leave
 * the choice between them to whoever read it; the write surface takes the first candidate its
 * columns cover, so two readers disagreeing about the order is two readers writing different
 * WHERE clauses. So each case names the rank.
 *
 * <p>Two projections carry over from the walk the relation transcribes and both get a case, because
 * each is a row a naive listing would emit and this one must not: a unique key whose column set the
 * primary key already carries is dropped rather than ranked second, and a table's keys are ranked
 * primary-first regardless of the order the catalog enumerated them in.
 */
class TableKeyCandidateTest {

    private static final String PKG = "no.sikt.jooq";
    private static final String PUBLIC = "public";

    /** A catalog with one source, and whatever tables the case seeds under it. */
    private static void withCatalog(Consumer<DSLContext> body) {
        withSeededStore(dsl -> {
            seedSource(dsl, PKG, "JOOQ_SCHEMA");
            body.accept(dsl);
        });
    }

    private static void table(DSLContext dsl, String name, String... columns) {
        seedTable(dsl, PKG, PUBLIC, name);
        for (int ordinal = 0; ordinal < columns.length; ordinal++) {
            seedColumn(dsl, PKG, PUBLIC, name, columns[ordinal], ordinal,
                columns[ordinal].toUpperCase(java.util.Locale.ROOT));
        }
    }

    private static List<String> ranked(DSLContext dsl, String tableName) {
        return dsl.select(INTENT_TABLE_KEY_CANDIDATE.CONSTRAINT_NAME)
            .from(INTENT_TABLE_KEY_CANDIDATE)
            .where(INTENT_TABLE_KEY_CANDIDATE.TABLE_NAME.eq(tableName))
            .orderBy(INTENT_TABLE_KEY_CANDIDATE.CANDIDATE_RANK.asc())
            .fetch(INTENT_TABLE_KEY_CANDIDATE.CONSTRAINT_NAME);
    }

    /** The ordinary table: one primary key, one candidate, and it is rank zero. */
    @Test
    void aPrimaryKeyAloneIsTheFirstCandidate() {
        withCatalog(dsl -> {
            table(dsl, "film", "film_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film", "film_pkey", "film_id");

            assertThat(ranked(dsl, "film")).containsExactly("film_pkey");
            assertThat(dsl.select(INTENT_TABLE_KEY_CANDIDATE.PRIMARY_KEY)
                .from(INTENT_TABLE_KEY_CANDIDATE)
                .where(INTENT_TABLE_KEY_CANDIDATE.TABLE_NAME.eq("film"))
                .fetchOne(INTENT_TABLE_KEY_CANDIDATE.PRIMARY_KEY)).isTrue();
        });
    }

    /**
     * A unique key over other columns is a second candidate and ranks after the primary key. The
     * case a consumer meets when its input covers the alternate key and not the primary one.
     */
    @Test
    void aUniqueKeyRanksAfterThePrimaryKey() {
        withCatalog(dsl -> {
            table(dsl, "hub", "hub_id", "hub_code");
            seedPrimaryKey(dsl, PKG, PUBLIC, "hub", "hub_pkey", "hub_id");
            seedUniqueKey(dsl, PKG, PUBLIC, "hub", "hub_code_uk", "hub_code");

            assertThat(ranked(dsl, "hub")).containsExactly("hub_pkey", "hub_code_uk");
        });
    }

    /**
     * Seeding the unique key first does not make it the first candidate. The enumeration position
     * orders the unique keys among themselves and the primary key precedes all of them, so this is
     * the case that fails if the rank is read off the enumeration alone.
     */
    @Test
    void thePrimaryKeyLeadsWhateverOrderTheCatalogEnumeratedIn() {
        withCatalog(dsl -> {
            table(dsl, "hub", "hub_id", "hub_code");
            seedUniqueKey(dsl, PKG, PUBLIC, "hub", "hub_code_uk", "hub_code");
            seedPrimaryKey(dsl, PKG, PUBLIC, "hub", "hub_pkey", "hub_id");

            assertThat(ranked(dsl, "hub")).containsExactly("hub_pkey", "hub_code_uk");
        });
    }

    /**
     * Two unique keys beyond the primary one keep the order they were enumerated in. Without the
     * captured position this is the pair a relation would have had to invent a tiebreaker for.
     */
    @Test
    void uniqueKeysKeepTheirEnumerationOrder() {
        withCatalog(dsl -> {
            table(dsl, "hub", "hub_id", "hub_code", "hub_slug");
            seedPrimaryKey(dsl, PKG, PUBLIC, "hub", "hub_pkey", "hub_id");
            seedUniqueKey(dsl, PKG, PUBLIC, "hub", "hub_code_uk", "hub_code");
            seedUniqueKey(dsl, PKG, PUBLIC, "hub", "hub_slug_uk", "hub_slug");

            assertThat(ranked(dsl, "hub")).containsExactly("hub_pkey", "hub_code_uk", "hub_slug_uk");
        });
    }

    /**
     * A unique constraint declared over the primary key's own columns is not a second candidate.
     * Covering it and covering the primary key are the same act, so ranking it would hand a
     * consumer a choice that does not exist, and the survivor is the earlier of the two.
     */
    @Test
    void aUniqueKeyOverThePrimaryKeysColumnsIsNotASecondCandidate() {
        withCatalog(dsl -> {
            table(dsl, "film", "film_id");
            seedPrimaryKey(dsl, PKG, PUBLIC, "film", "film_pkey", "film_id");
            seedUniqueKey(dsl, PKG, PUBLIC, "film", "film_id_uk", "film_id");

            assertThat(ranked(dsl, "film")).containsExactly("film_pkey");
        });
    }

    /**
     * The dedup is by column set and not by column order, a two-column key spelled in either order
     * identifying the same rows. Stated because a signature built by concatenation would pass the
     * case above and fail this one.
     */
    @Test
    void theDedupIgnoresColumnOrder() {
        withCatalog(dsl -> {
            table(dsl, "pair", "a", "b");
            seedPrimaryKey(dsl, PKG, PUBLIC, "pair", "pair_pkey", "a", "b");
            seedUniqueKey(dsl, PKG, PUBLIC, "pair", "pair_ba_uk", "b", "a");

            assertThat(ranked(dsl, "pair")).containsExactly("pair_pkey");
        });
    }

    /** A table declaring no uniqueness constraint offers no candidate, which is an absence and not a row. */
    @Test
    void aTableWithNoKeyOffersNoCandidate() {
        withCatalog(dsl -> {
            table(dsl, "log_line", "message");

            assertThat(ranked(dsl, "log_line")).isEmpty();
        });
    }
}
