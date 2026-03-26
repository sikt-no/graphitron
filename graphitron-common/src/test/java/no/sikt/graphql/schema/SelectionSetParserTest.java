package no.sikt.graphql.schema;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SelectionSetParserTest {

    @Test
    void parseSingleField() {
        var fields = SelectionSetParser.parseFields("id");
        assertThat(fields).hasSize(1);
        assertThat(fields.get(0).getName()).isEqualTo("id");
        assertThat(fields.get(0).getAlias()).isNull();
    }

    @Test
    void parseMultipleFields() {
        var fields = SelectionSetParser.parseFields("id name email");
        assertThat(fields).hasSize(3);
        assertThat(fields).extracting("name").containsExactly("id", "name", "email");
    }

    @Test
    void parseCommaDelimitedFields() {
        var fields = SelectionSetParser.parseFields("firstName: FIRST_NAME, lastName: LAST_NAME");
        assertThat(fields).hasSize(2);
        assertThat(fields.get(0).getAlias()).isEqualTo("firstName");
        assertThat(fields.get(0).getName()).isEqualTo("FIRST_NAME");
        assertThat(fields.get(1).getAlias()).isEqualTo("lastName");
        assertThat(fields.get(1).getName()).isEqualTo("LAST_NAME");
    }

    @Test
    void parseNestedFields() {
        var fields = SelectionSetParser.parseFields("user { id name }");
        assertThat(fields).hasSize(1);
        assertThat(fields.get(0).getName()).isEqualTo("user");
        assertThat(fields.get(0).getSelectionSet()).isNotNull();

        var nested = fields.get(0).getSelectionSet().getSelections();
        assertThat(nested).hasSize(2);
    }

    @Test
    void parseMixedAliasedAndUnaliasedFields() {
        var fields = SelectionSetParser.parseFields("first_name, surname: LAST_NAME");
        assertThat(fields).hasSize(2);
        assertThat(fields.get(0).getName()).isEqualTo("first_name");
        assertThat(fields.get(0).getAlias()).isNull();
        assertThat(fields.get(1).getAlias()).isEqualTo("surname");
        assertThat(fields.get(1).getName()).isEqualTo("LAST_NAME");
    }

    @Test
    void parseInputAlreadyWrappedInBraces() {
        var fields = SelectionSetParser.parseFields("{ firstName: FIRST_NAME, lastName: LAST_NAME }");
        assertThat(fields).hasSize(2);
        assertThat(fields.get(0).getAlias()).isEqualTo("firstName");
        assertThat(fields.get(0).getName()).isEqualTo("FIRST_NAME");
        assertThat(fields.get(1).getAlias()).isEqualTo("lastName");
        assertThat(fields.get(1).getName()).isEqualTo("LAST_NAME");
    }

    @Test
    void parseInvalidSelectionThrows() {
        assertThatThrownBy(() -> SelectionSetParser.parseFields(":::"))
                .isInstanceOf(Exception.class);
    }
}
