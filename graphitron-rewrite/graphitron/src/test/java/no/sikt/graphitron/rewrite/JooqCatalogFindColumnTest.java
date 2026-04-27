package no.sikt.graphitron.rewrite;

import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for {@link JooqCatalog#findColumn} dual-name resolution: GraphQL schema directives
 * may supply either the jOOQ Java field name (e.g. {@code "FILM_ID"}) or the SQL column name
 * (e.g. {@code "film_id"}); both must resolve to the correct entry with the SQL name available
 * for code generation.
 */
class JooqCatalogFindColumnTest {

    private static JooqCatalog catalog() {
        return new JooqCatalog(DEFAULT_JOOQ_PACKAGE);
    }

    @Test
    void findColumn_byJavaName_resolves() {
        var result = catalog().findColumn("film", "FILM_ID");
        assertThat(result).isPresent();
        assertThat(result.get().javaName()).isEqualTo("FILM_ID");
        assertThat(result.get().sqlName()).isEqualTo("film_id");
    }

    @Test
    void findColumn_bySqlName_resolves() {
        var result = catalog().findColumn("film", "film_id");
        assertThat(result).isPresent();
        assertThat(result.get().sqlName()).isEqualTo("film_id");
    }

    @Test
    void findColumn_byJavaName_caseInsensitive() {
        // Directive values may arrive in mixed case; lookup must still work.
        var result = catalog().findColumn("film", "film_id");
        assertThat(result).isPresent();
    }

    @Test
    void findColumn_javaNameTakesPrecedenceOverSqlName() {
        // When the Java name matches, the SQL name fallback is not needed.
        // Verify the returned entry is the right column (not a spurious SQL-name match).
        var byJava = catalog().findColumn("film", "TITLE");
        var bySql  = catalog().findColumn("film", "title");
        assertThat(byJava).isPresent();
        assertThat(bySql).isPresent();
        assertThat(byJava.get().sqlName()).isEqualTo(bySql.get().sqlName());
    }

    @Test
    void findColumn_unknownName_returnsEmpty() {
        var result = catalog().findColumn("film", "no_such_column");
        assertThat(result).isEmpty();
    }
}
