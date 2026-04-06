package no.sikt.graphitron.rewrite.generators.lookup;

import no.sikt.graphitron.javapoet.JavaFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LookupCodeGeneratorTest {

    private static final LookupCodeGenerator GEN = new LookupCodeGenerator();

    private static String render(LookupSpec spec) {
        var typeSpec = GEN.generate(spec);
        return JavaFile.builder("test.pkg", typeSpec).indent("    ").build().toString();
    }

    @Test
    void generate_classNamedAfterTypeName() {
        var spec = new LookupSpec("Customer", "CUSTOMER",
            List.of(new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer")));
        var typeSpec = GEN.generate(spec);
        assertThat(typeSpec.name()).isEqualTo("CustomerLookup");
    }

    @Test
    void generate_containsToInputRowsMethod() {
        var spec = new LookupSpec("Customer", "CUSTOMER",
            List.of(new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer")));
        var output = render(spec);
        assertThat(output).contains("toInputRows");
    }

    @Test
    void generate_methodIsPublicStatic() {
        var spec = new LookupSpec("Customer", "CUSTOMER",
            List.of(new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer")));
        var output = render(spec);
        assertThat(output).contains("public static");
    }

    @Test
    void generate_returnTypeIncludesRecord2ForOneField() {
        var spec = new LookupSpec("Customer", "CUSTOMER",
            List.of(new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer")));
        var output = render(spec);
        assertThat(output).contains("Record2<Integer, Integer>");
    }

    @Test
    void generate_returnTypeIncludesRecord3ForTwoFields() {
        var spec = new LookupSpec("Customer", "CUSTOMER", List.of(
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer"),
            new LookupInputFieldSpec("email", "EMAIL", "java.lang.String")
        ));
        var output = render(spec);
        assertThat(output).contains("Record3<Integer, Integer, String>");
    }

    @Test
    void generate_bodyUsesIntStreamRange() {
        var spec = new LookupSpec("Customer", "CUSTOMER",
            List.of(new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer")));
        var output = render(spec);
        assertThat(output).contains("IntStream.range(0, inputs.size())");
    }

    @Test
    void generate_bodyUsesDslNewRecord() {
        var spec = new LookupSpec("Customer", "CUSTOMER",
            List.of(new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer")));
        var output = render(spec);
        assertThat(output).contains("DSL.newRecord(");
    }

    @Test
    void generate_bodyIncludesGraphitronInputIdx() {
        var spec = new LookupSpec("Customer", "CUSTOMER",
            List.of(new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer")));
        var output = render(spec);
        assertThat(output).contains("GRAPHITRON_INPUT_IDX");
    }

    @Test
    void generate_bodyIncludesTableColumnReference() {
        var spec = new LookupSpec("Customer", "CUSTOMER",
            List.of(new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer")));
        var output = render(spec);
        assertThat(output).contains("CUSTOMER.CUSTOMER_ID");
    }

    @Test
    void generate_bodyIncludesCastAndArgName() {
        var spec = new LookupSpec("Customer", "CUSTOMER",
            List.of(new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer")));
        var output = render(spec);
        assertThat(output)
            .contains("(Integer) m.get(\"customerId\")")
            .contains("i + 1");
    }

    @Test
    void generate_bodyIncludesAllFieldsInValues() {
        var spec = new LookupSpec("Customer", "CUSTOMER", List.of(
            new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer"),
            new LookupInputFieldSpec("email", "EMAIL", "java.lang.String")
        ));
        var output = render(spec);
        assertThat(output)
            .contains("(Integer) m.get(\"customerId\")")
            .contains("(String) m.get(\"email\")")
            .contains("CUSTOMER.CUSTOMER_ID")
            .contains("CUSTOMER.EMAIL");
    }

    @Test
    void generate_bodyEndsWithToList() {
        var spec = new LookupSpec("Customer", "CUSTOMER",
            List.of(new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer")));
        var output = render(spec);
        assertThat(output).contains(".toList()");
    }

    @Test
    void generate_parameterIsListOfMaps() {
        var spec = new LookupSpec("Customer", "CUSTOMER",
            List.of(new LookupInputFieldSpec("customerId", "CUSTOMER_ID", "java.lang.Integer")));
        var output = render(spec);
        assertThat(output).contains("List<Map<String, Object>> inputs");
    }
}
