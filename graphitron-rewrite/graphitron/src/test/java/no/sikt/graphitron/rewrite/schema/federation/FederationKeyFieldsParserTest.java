package no.sikt.graphitron.rewrite.schema.federation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
class FederationKeyFieldsParserTest {

    @Test
    void singleFieldName_naked() {
        assertThat(FederationKeyFieldsParser.parse("id")).containsExactly("id");
    }

    @Test
    void singleFieldName_braced() {
        assertThat(FederationKeyFieldsParser.parse("{ id }")).containsExactly("id");
    }

    @Test
    void multipleFieldNames_naked() {
        assertThat(FederationKeyFieldsParser.parse("id sku tenantId"))
            .containsExactly("id", "sku", "tenantId");
    }

    @Test
    void multipleFieldNames_braced() {
        assertThat(FederationKeyFieldsParser.parse("{ id sku }"))
            .containsExactly("id", "sku");
    }

    @Test
    void mixedWhitespace_isIgnored() {
        assertThat(FederationKeyFieldsParser.parse("id\tsku\n  tenantId"))
            .containsExactly("id", "sku", "tenantId");
    }

    @Test
    void leadingAndTrailingWhitespace_naked() {
        assertThat(FederationKeyFieldsParser.parse("   id  "))
            .containsExactly("id");
    }

    @Test
    void leadingAndTrailingWhitespace_braced() {
        assertThat(FederationKeyFieldsParser.parse("  {  id  sku  }  "))
            .containsExactly("id", "sku");
    }

    @Test
    void underscoreIdentifiers_areAccepted() {
        assertThat(FederationKeyFieldsParser.parse("_id _sku my_field"))
            .containsExactly("_id", "_sku", "my_field");
    }

    @Test
    void digitsInNameContinuation_areAccepted() {
        assertThat(FederationKeyFieldsParser.parse("field1 field2 a3"))
            .containsExactly("field1", "field2", "a3");
    }

    @Test
    void emptyInput_throws() {
        assertThatThrownBy(() -> FederationKeyFieldsParser.parse(""))
            .isInstanceOf(FederationKeyFieldsParser.ParseException.class)
            .hasMessageContaining("empty field list");
    }

    @Test
    void whitespaceOnlyInput_throws() {
        assertThatThrownBy(() -> FederationKeyFieldsParser.parse("   \t\n  "))
            .isInstanceOf(FederationKeyFieldsParser.ParseException.class)
            .hasMessageContaining("empty field list");
    }

    @Test
    void emptyBraces_throws() {
        assertThatThrownBy(() -> FederationKeyFieldsParser.parse("{}"))
            .isInstanceOf(FederationKeyFieldsParser.ParseException.class)
            .hasMessageContaining("empty field list");
    }

    @Test
    void nullInput_throws() {
        assertThatThrownBy(() -> FederationKeyFieldsParser.parse(null))
            .isInstanceOf(FederationKeyFieldsParser.ParseException.class)
            .hasMessageContaining("missing");
    }

    @Test
    void nestedSelection_throws() {
        assertThatThrownBy(() -> FederationKeyFieldsParser.parse("owner { id }"))
            .isInstanceOf(FederationKeyFieldsParser.ParseException.class)
            .hasMessageContaining("nested selections")
            .hasMessageContaining("owner");
    }

    @Test
    void nestedSelectionInsideBraces_throws() {
        assertThatThrownBy(() -> FederationKeyFieldsParser.parse("{ id owner { x } }"))
            .isInstanceOf(FederationKeyFieldsParser.ParseException.class)
            .hasMessageContaining("nested selections")
            .hasMessageContaining("owner");
    }

    @Test
    void unbalancedOpeningBrace_throws() {
        assertThatThrownBy(() -> FederationKeyFieldsParser.parse("{ id"))
            .isInstanceOf(FederationKeyFieldsParser.ParseException.class)
            .hasMessageContaining("unbalanced");
    }

    @Test
    void strayClosingBrace_throws() {
        assertThatThrownBy(() -> FederationKeyFieldsParser.parse("id }"))
            .isInstanceOf(FederationKeyFieldsParser.ParseException.class)
            .hasMessageContaining("trailing input");
    }

    @Test
    void dottedFieldName_throws() {
        // Federation grammar disallows dotted names.
        assertThatThrownBy(() -> FederationKeyFieldsParser.parse("owner.id"))
            .isInstanceOf(FederationKeyFieldsParser.ParseException.class)
            .hasMessageContaining("unexpected character")
            .hasMessageContaining("'.'");
    }

    @Test
    void aliasedFieldName_throws() {
        assertThatThrownBy(() -> FederationKeyFieldsParser.parse("foo: id"))
            .isInstanceOf(FederationKeyFieldsParser.ParseException.class)
            .hasMessageContaining("unexpected character")
            .hasMessageContaining("':'");
    }

    @Test
    void fieldWithArguments_throws() {
        assertThatThrownBy(() -> FederationKeyFieldsParser.parse("id(x: 1)"))
            .isInstanceOf(FederationKeyFieldsParser.ParseException.class)
            .hasMessageContaining("unexpected")
            .hasMessageContaining("'('");
    }

    @Test
    void hashComment_throws() {
        // Federation grammar does not allow hash-comments; treat as an illegal character.
        assertThatThrownBy(() -> FederationKeyFieldsParser.parse("# nope"))
            .isInstanceOf(FederationKeyFieldsParser.ParseException.class)
            .hasMessageContaining("unexpected character");
    }

    @Test
    void variableSyntax_throws() {
        assertThatThrownBy(() -> FederationKeyFieldsParser.parse("$id"))
            .isInstanceOf(FederationKeyFieldsParser.ParseException.class)
            .hasMessageContaining("unexpected character")
            .hasMessageContaining("'$'");
    }

    @Test
    void numericValue_throws() {
        assertThatThrownBy(() -> FederationKeyFieldsParser.parse("123"))
            .isInstanceOf(FederationKeyFieldsParser.ParseException.class)
            .hasMessageContaining("unexpected character")
            .hasMessageContaining("'1'");
    }

    @Test
    void compoundKey_isReturnedInOrder() {
        assertThat(FederationKeyFieldsParser.parse("tenantId sku"))
            .isEqualTo(List.of("tenantId", "sku"));
    }

    @Test
    void commasBetweenNames_areIgnored() {
        // Federation examples sometimes show commas; treat them as ignored token separators
        // (graphql-java does the same in its selection grammar).
        assertThat(FederationKeyFieldsParser.parse("id, sku, tenantId"))
            .containsExactly("id", "sku", "tenantId");
    }
}
