package no.sikt.graphitron.rewrite.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MethodRef#callParams()} — specifically that
 * {@code ParamSource.Arg} parameters are mapped to the correct
 * {@link CallSiteExtraction} variant based on the Java parameter type.
 */
class MethodRefCallParamsTest {

    private static MethodRef method(MethodRef.Param... params) {
        return new MethodRef.Basic("com.example.Service", "method", "void", List.of(params));
    }

    private static MethodRef.Param.Typed arg(String name, String typeName) {
        return new MethodRef.Param.Typed(name, typeName, new ParamSource.Arg());
    }

    private static MethodRef.Param.Typed ctx(String name) {
        return new MethodRef.Param.Typed(name, "java.lang.String", new ParamSource.Context());
    }

    @Test
    void stringArg_mapsToDirect() {
        var params = method(arg("title", "java.lang.String")).callParams();
        assertThat(params).hasSize(1);
        assertThat(params.get(0).extraction()).isInstanceOf(CallSiteExtraction.Direct.class);
    }

    @Test
    void enumArg_mapsToEnumValueOf() {
        // java.time.DayOfWeek is a reliable built-in enum always on the classpath.
        var params = method(arg("day", "java.time.DayOfWeek")).callParams();
        assertThat(params).hasSize(1);
        assertThat(params.get(0).extraction())
            .isInstanceOf(CallSiteExtraction.EnumValueOf.class)
            .extracting(e -> ((CallSiteExtraction.EnumValueOf) e).enumClassName())
            .isEqualTo("java.time.DayOfWeek");
    }

    @Test
    void contextArg_mapsToContextArg() {
        var params = method(ctx("tenantId")).callParams();
        assertThat(params).hasSize(1);
        assertThat(params.get(0).extraction()).isInstanceOf(CallSiteExtraction.ContextArg.class);
    }

    @Test
    void implicitParams_areFiltered() {
        // DslContext, Sources, Table should not appear in callParams()
        var dsl = new MethodRef.Param.Typed("dsl", "org.jooq.DSLContext", new ParamSource.DslContext());
        var sources = new MethodRef.Param.Sourced("keys", new BatchKey.RowKeyed(List.of()));
        var params = method(dsl, sources, arg("title", "java.lang.String")).callParams();
        assertThat(params).hasSize(1);
        assertThat(params.get(0).name()).isEqualTo("title");
    }

    @Test
    void unknownClassArg_mapsToDirect() {
        // A type that can't be loaded falls back to Direct.
        var params = method(arg("x", "com.example.DoesNotExist")).callParams();
        assertThat(params).hasSize(1);
        assertThat(params.get(0).extraction()).isInstanceOf(CallSiteExtraction.Direct.class);
    }
}
