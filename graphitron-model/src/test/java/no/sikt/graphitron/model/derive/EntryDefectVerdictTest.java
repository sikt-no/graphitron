package no.sikt.graphitron.model.derive;

import graphql.language.SourceLocation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntryDefectVerdictTest {

    private static final SourceLocation AT = new SourceLocation(5, 13, "schema.graphqls");

    @Test
    void aFieldDefectNamesTheFieldTheStatementTheDetailAndTheCode() {
        var error = EntryDefectVerdict.error(new EntryDefectVerdict.Row("COLUMN_UNRESOLVED",
            "A field names a column its table does not have.", "title_txt", "Film.title", "Film",
            "title", AT));
        assertThat(error.coordinate()).isEqualTo("Film.title");
        assertThat(error.message()).isEqualTo("Field 'Film.title': A field names a column its table"
            + " does not have. (title_txt) [COLUMN_UNRESOLVED]");
        assertThat(error.location()).isEqualTo(AT);
    }

    @Test
    void aTypeDefectWithNoDetailNamesTheType() {
        var error = EntryDefectVerdict.error(new EntryDefectVerdict.Row("TABLE_UNRESOLVED",
            "A @table names a table the catalog does not hold.", null, "Ghost", "Ghost", null, AT));
        assertThat(error.coordinate()).isEqualTo("Ghost");
        assertThat(error.message())
            .isEqualTo("Type 'Ghost': A @table names a table the catalog does not hold. [TABLE_UNRESOLVED]");
    }

    @Test
    void anExplanationTheCallerFoundFollowsTheDetail() {
        var error = EntryDefectVerdict.error(new EntryDefectVerdict.Row("CODE_REFERENCE_CLASS_NOT_READ",
            "A code reference names a class the classpath reading did not read.", "org.jooq.impl.DSL",
            "Query.now", "Query", "now", AT),
            "class 'org.jooq.impl.DSL' is in org.jooq:jooq, which this module does not declare.");
        assertThat(error.message()).isEqualTo("Field 'Query.now': A code reference names a class the"
            + " classpath reading did not read. (org.jooq.impl.DSL) class 'org.jooq.impl.DSL' is in"
            + " org.jooq:jooq, which this module does not declare. [CODE_REFERENCE_CLASS_NOT_READ]");
    }

    @Test
    void aDefectAboutNeitherIsTheSchemasAndMayHaveNoPosition() {
        var error = EntryDefectVerdict.error(new EntryDefectVerdict.Row("NODE_WITHOUT_KEY",
            "A node pins no key.", null, null, null, null, null));
        assertThat(error.coordinate()).isEqualTo("<schema>");
        assertThat(error.message()).isEqualTo("A node pins no key. [NODE_WITHOUT_KEY]");
        assertThat(error.location()).isNull();
    }
}
