package no.sikt.graphitron.rewrite.generators.schema;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}
