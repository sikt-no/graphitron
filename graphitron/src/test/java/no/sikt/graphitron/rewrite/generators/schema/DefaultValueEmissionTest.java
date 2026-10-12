package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.rewrite.SchemaShapeRenderTestSupport;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
class DefaultValueEmissionTest {

    @Test
    void fieldArgument_defaultInt_emitsAuthoredLiteral() {
        var queryBody = findObjectBody("""
            type Query {
              films(first: Int = 100): [String]
            }
            """, "QueryType");
        assertThat(queryBody)
            .contains(".name(\"first\")")
            .contains(".defaultValueLiteral(graphql.parser.Parser.parseValue(\"100\"))");
    }

    @Test
    void fieldArgument_defaultString_emitsQuotedDefault() {
        var queryBody = findObjectBody("""
            type Query {
              search(term: String = "hello"): [String]
            }
            """, "QueryType");
        assertThat(queryBody).contains(".defaultValueLiteral(graphql.parser.Parser.parseValue(\"\\\"hello\\\"\"))");
    }

    @Test
    void fieldArgument_defaultList_emitsListLiteral() {
        var queryBody = findObjectBody("""
            type Query {
              tags(names: [String!] = ["a", "b"]): [String]
            }
            """, "QueryType");
        assertThat(queryBody).contains(".defaultValueLiteral(graphql.parser.Parser.parseValue(\"[\\\"a\\\", \\\"b\\\"]\"))");
    }

    @Test
    void fieldArgument_withoutDefault_omitsDefaultValueCall() {
        var queryBody = findObjectBody("""
            type Query {
              films(first: Int): [String]
            }
            """, "QueryType");
        assertThat(queryBody).doesNotContain(".defaultValueLiteral(").doesNotContain(".defaultValueProgrammatic(");
    }

    @Test
    void inputField_defaultBoolean_emitsAuthoredLiteral() {
        var body = findInputBody("""
            type Query { x(filter: FilterInput): String }
            input FilterInput { active: Boolean = true }
            """, "FilterInputType");
        assertThat(body).contains(".defaultValueLiteral(graphql.parser.Parser.parseValue(\"true\"))");
    }

    @Test
    void inputField_withoutDefault_omitsDefaultValueCall() {
        var body = findInputBody("""
            type Query { x(filter: FilterInput): String }
            input FilterInput { keyword: String }
            """, "FilterInputType");
        assertThat(body).doesNotContain(".defaultValueLiteral(").doesNotContain(".defaultValueProgrammatic(");
    }

    // Custom scalars are wired as stand-in coercers at build time, so coercing their default
    // yields the AST node back. The authored literal is emitted instead and the real scalar
    // coerces it at runtime; no AST toString ("IntValue{value=0}") may reach the emitted code.

    private static final String BIG_DECIMAL =
        "scalar BigDecimal @scalarType(scalar: \"graphql.scalars.ExtendedScalars.GraphQLBigDecimal\")\n";

    @Test
    void fieldArgument_customScalarDefault_emitsAuthoredLiteral() {
        var queryBody = findObjectBody(BIG_DECIMAL + """
            type Query { x(p: BigDecimal = 0): String }
            """, "QueryType");
        assertThat(queryBody)
            .contains(".defaultValueLiteral(graphql.parser.Parser.parseValue(\"0\"))")
            .doesNotContain("Value{value=");
    }

    @Test
    void inputField_customScalarDefault_emitsAuthoredLiteral() {
        var body = findInputBody(BIG_DECIMAL + """
            type Query { x(filter: Range): String }
            input Range { fra: BigDecimal = 0 }
            """, "RangeType");
        assertThat(body)
            .contains(".defaultValueLiteral(graphql.parser.Parser.parseValue(\"0\"))")
            .doesNotContain("Value{value=");
    }

    @Test
    void fieldArgument_customScalarInsideListDefault_emitsAuthoredLiteral() {
        var queryBody = findObjectBody(BIG_DECIMAL + """
            type Query { x(l: [BigDecimal] = [1, 2]): String }
            """, "QueryType");
        assertThat(queryBody)
            .contains(".defaultValueLiteral(graphql.parser.Parser.parseValue(\"[1, 2]\"))")
            .doesNotContain("Value{value=");
    }

    @Test
    void fieldArgument_customScalarInsideInputObjectDefault_emitsAuthoredLiteral() {
        var queryBody = findObjectBody(BIG_DECIMAL + """
            type Query { x(r: Range = {fra: 0, til: 1}): String }
            input Range { fra: BigDecimal  til: BigDecimal }
            """, "QueryType");
        assertThat(queryBody)
            .contains(".defaultValueLiteral(graphql.parser.Parser.parseValue(\"{fra : 0, til : 1}\"))")
            .doesNotContain("Value{value=");
    }

    @Test
    void synthesisedConnectionFirst_emitsDefaultPageSizeAsLiteral() {
        // The emitted schema is derived from the store, which carries the synthesised `first`
        // default as SDL text, so it reaches emission in the literal state like any authored one.
        var queryBody = findObjectBody("""
            type Film @table(name: "film") { id: ID }
            type Query { films: [Film!]! @asConnection @defaultOrder(primaryKey: true) }
            """, "QueryType");
        assertThat(queryBody)
            .contains(".name(\"first\")")
            .containsPattern("\\.defaultValueLiteral\\(graphql\\.parser\\.Parser\\.parseValue\\(\"\\d+\"\\)\\)");
    }

    private static String findObjectBody(String sdl, String typeName) {
        var bundle = TestSchemaHelper.buildBundle(sdl);
        return findBody(SchemaShapeRenderTestSupport.objectTypes(bundle.model(), bundle.assembled()), typeName);
    }

    private static String findInputBody(String sdl, String typeName) {
        var bundle = TestSchemaHelper.buildBundle(sdl);
        return findBody(SchemaShapeRenderTestSupport.inputTypes(bundle.model(), bundle.assembled()), typeName);
    }

    private static String findBody(List<TypeSpec> specs, String typeName) {
        return specs.stream()
            .filter(s -> s.name().equals(typeName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no " + typeName + " in " + specs))
            .toString();
    }
}
