package no.sikt.graphitron.rewrite.generators.schema;

import graphql.language.ArrayValue;
import graphql.language.IntValue;
import graphql.schema.InputValueWithState;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
class GraphQLValueEmitterTest {

    @Test
    void emit_null() {
        assertThat(GraphQLValueEmitter.emit(null).toString()).isEqualTo("null");
    }

    @Test
    void emit_string_quotes() {
        assertThat(GraphQLValueEmitter.emit("hello").toString()).isEqualTo("\"hello\"");
    }

    @Test
    void emit_booleanIntegerDouble_asLiterals() {
        assertThat(GraphQLValueEmitter.emit(true).toString()).isEqualTo("true");
        assertThat(GraphQLValueEmitter.emit(42).toString()).isEqualTo("42");
        assertThat(GraphQLValueEmitter.emit(3.5).toString()).isEqualTo("3.5D");
    }

    @Test
    void emit_list_rendersAsListOf() {
        var out = GraphQLValueEmitter.emit(List.of("a", "b")).toString();
        assertThat(out).isEqualTo("java.util.List.of(\"a\", \"b\")");
    }

    @Test
    void emit_emptyList_rendersAsListOfWithNoArgs() {
        assertThat(GraphQLValueEmitter.emit(List.of()).toString()).isEqualTo("java.util.List.of()");
    }

    @Test
    void emit_map_rendersAsMapOf() {
        var out = GraphQLValueEmitter.emit(Map.of("k", "v")).toString();
        assertThat(out).isEqualTo("java.util.Map.of(\"k\", \"v\")");
    }

    @Test
    void emit_emptyMap_rendersAsMapOfWithNoArgs() {
        assertThat(GraphQLValueEmitter.emit(Map.of()).toString()).isEqualTo("java.util.Map.of()");
    }

    @Test
    void emit_nestedList_rendersRecursively() {
        var out = GraphQLValueEmitter.emit(List.of(List.of(1, 2), List.of(3))).toString();
        assertThat(out).isEqualTo("java.util.List.of(java.util.List.of(1, 2), java.util.List.of(3))");
    }

    @Test
    void emit_unsupportedValue_throwsNamingTheClass() {
        assertThatThrownBy(() -> GraphQLValueEmitter.emit(new IntValue(BigInteger.ZERO)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("graphql.language.IntValue");
    }

    @Test
    void emitDefault_literalState_emitsParsedAuthoredLiteral() {
        var state = InputValueWithState.newLiteralValue(ArrayValue.newArrayValue()
            .value(new IntValue(BigInteger.ONE)).value(new IntValue(BigInteger.TWO)).build());
        assertThat(GraphQLValueEmitter.emitDefault(state, "Query.x(l:)").toString())
            .isEqualTo("defaultValueLiteral(graphql.parser.Parser.parseValue(\"[1, 2]\"))");
    }

    @Test
    void emitDefault_internalState_emitsProgrammaticValue() {
        var state = InputValueWithState.newInternalValue(100);
        assertThat(GraphQLValueEmitter.emitDefault(state, "Query.films(first:)").toString())
            .isEqualTo("defaultValueProgrammatic(100)");
    }

    @Test
    void emitDefault_externalState_throwsNamingTheCoordinate() {
        var state = InputValueWithState.newExternalValue(100);
        assertThatThrownBy(() -> GraphQLValueEmitter.emitDefault(state, "Query.films(first:)"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Query.films(first:)");
    }

    @Test
    void emitDefault_notSet_throwsNamingTheCoordinate() {
        assertThatThrownBy(() -> GraphQLValueEmitter.emitDefault(InputValueWithState.NOT_SET, "Input.f"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Input.f");
    }
}
