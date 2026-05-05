package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural tests for {@link CompositeDecodeHelperRegistry}. The registry is the seam between
 * {@link ArgCallEmitter}'s composite-key NodeId path and {@link QueryConditionsGenerator}'s
 * per-class helper emission; these tests pin the dedup key shape and helper-naming matrix so a
 * future refactor cannot silently break either guarantee.
 */
@UnitTier
class CompositeDecodeHelperRegistryTest {

    private static final ClassName ENCODER = ClassName.get("no.sikt.example.util", "NodeIdEncoder");

    private static HelperRef.Decode decodeFilmActor() {
        return new HelperRef.Decode(
            ENCODER, "decodeFilmActor",
            List.of(
                new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer"),
                new ColumnRef("film_id", "FILM_ID", "java.lang.Integer")));
    }

    @Test
    void register_sameKey_returnsSameHelperName() {
        var registry = new CompositeDecodeHelperRegistry();
        var decode = decodeFilmActor();
        String first = registry.register(decode, CompositeDecodeHelperRegistry.Mode.SKIP, true);
        String second = registry.register(decode, CompositeDecodeHelperRegistry.Mode.SKIP, true);
        assertThat(first).isEqualTo(second);
        assertThat(registry.emit()).hasSize(1);
    }

    @Test
    void register_skipAndThrow_emitDistinctHelpers() {
        var registry = new CompositeDecodeHelperRegistry();
        var decode = decodeFilmActor();
        String skipName = registry.register(decode, CompositeDecodeHelperRegistry.Mode.SKIP, true);
        String throwName = registry.register(decode, CompositeDecodeHelperRegistry.Mode.THROW, true);
        assertThat(skipName).isEqualTo("decodeFilmActorRows");
        assertThat(throwName).isEqualTo("decodeFilmActorRowsOrThrow");
        assertThat(registry.emit()).hasSize(2);
    }

    @Test
    void register_scalarAndList_emitDistinctHelpers() {
        var registry = new CompositeDecodeHelperRegistry();
        var decode = decodeFilmActor();
        String scalar = registry.register(decode, CompositeDecodeHelperRegistry.Mode.SKIP, false);
        String list = registry.register(decode, CompositeDecodeHelperRegistry.Mode.SKIP, true);
        assertThat(scalar).isEqualTo("decodeFilmActorRow");
        assertThat(list).isEqualTo("decodeFilmActorRows");
        assertThat(registry.emit()).hasSize(2);
    }

    @Test
    void emit_listSkipHelper_hasTypedRowReturnAndFilterChain() {
        var registry = new CompositeDecodeHelperRegistry();
        registry.register(decodeFilmActor(), CompositeDecodeHelperRegistry.Mode.SKIP, true);
        MethodSpec helper = registry.emit().iterator().next();
        assertThat(helper.name()).isEqualTo("decodeFilmActorRows");
        assertThat(helper.returnType().toString())
            .isEqualTo("java.util.List<org.jooq.Row2<java.lang.Integer, java.lang.Integer>>");
        String body = helper.code().toString();
        assertThat(body)
            .contains("instanceof java.util.List<?>")
            .contains("NodeIdEncoder.decodeFilmActor")
            .contains("filter(java.util.Objects::nonNull)")
            .contains("Record2::valuesRow");
    }

    @Test
    void emit_listThrowHelper_swapsFilterForThrowingMap() {
        var registry = new CompositeDecodeHelperRegistry();
        registry.register(decodeFilmActor(), CompositeDecodeHelperRegistry.Mode.THROW, true);
        MethodSpec helper = registry.emit().iterator().next();
        String body = helper.code().toString();
        assertThat(body)
            .contains("graphql.GraphqlErrorException")
            .doesNotContain("Objects::nonNull");
    }

    @Test
    void emit_scalarSkipHelper_returnsTypedRowDirectly() {
        var registry = new CompositeDecodeHelperRegistry();
        registry.register(decodeFilmActor(), CompositeDecodeHelperRegistry.Mode.SKIP, false);
        MethodSpec helper = registry.emit().iterator().next();
        assertThat(helper.returnType().toString())
            .isEqualTo("org.jooq.Row2<java.lang.Integer, java.lang.Integer>");
        String body = helper.code().toString();
        assertThat(body)
            .contains("wire instanceof String s")
            .contains("r == null ? null : r.valuesRow()");
    }
}
