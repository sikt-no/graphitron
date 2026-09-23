package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.render.CompositeDecodeHelperRegistry;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural tests for {@link CompositeDecodeHelperRegistry}. The registry is the seam between
 * {@link ArgCallEmitter}'s NodeId-decode path (all arities) and the per-class helper emission on
 * the hosting classes ({@link no.sikt.graphitron.render.ConditionGlueRenderer}'s glue classes and
 * the fetcher-side hosts); these tests pin the dedup key
 * shape and helper-naming matrix so a future refactor cannot silently break either guarantee.
 */
@UnitTier
class CompositeDecodeHelperRegistryTest {

    private static final String OUTPUT_PACKAGE = "no.sikt.example";
    private static final ClassName ENCODER = ClassName.get(OUTPUT_PACKAGE + ".util", "NodeIdEncoder");

    private static HelperRef.Decode decodeFilmActor() {
        return new HelperRef.Decode(
            ENCODER, "decodeFilmActor",
            List.of(
                new ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer"),
                new ColumnRef("film_id", "FILM_ID", "java.lang.Integer")),
            "FilmActor");
    }

    @Test
    void register_sameKey_returnsSameHelperName() {
        var registry = new CompositeDecodeHelperRegistry(OUTPUT_PACKAGE);
        var decode = decodeFilmActor();
        String first = registry.register(decode, CompositeDecodeHelperRegistry.Mode.SKIP, true);
        String second = registry.register(decode, CompositeDecodeHelperRegistry.Mode.SKIP, true);
        assertThat(first).isEqualTo(second);
        assertThat(registry.emit()).hasSize(1);
    }

    @Test
    void register_skipAndThrow_emitDistinctHelpers() {
        var registry = new CompositeDecodeHelperRegistry(OUTPUT_PACKAGE);
        var decode = decodeFilmActor();
        String skipName = registry.register(decode, CompositeDecodeHelperRegistry.Mode.SKIP, true);
        String throwName = registry.register(decode, CompositeDecodeHelperRegistry.Mode.THROW, true);
        assertThat(skipName).isEqualTo("decodeFilmActorRows");
        assertThat(throwName).isEqualTo("decodeFilmActorRowsOrThrow");
        assertThat(registry.emit()).hasSize(2);
    }

    @Test
    void register_scalarAndList_emitDistinctHelpers() {
        var registry = new CompositeDecodeHelperRegistry(OUTPUT_PACKAGE);
        var decode = decodeFilmActor();
        String scalar = registry.register(decode, CompositeDecodeHelperRegistry.Mode.SKIP, false);
        String list = registry.register(decode, CompositeDecodeHelperRegistry.Mode.SKIP, true);
        assertThat(scalar).isEqualTo("decodeFilmActorRow");
        assertThat(list).isEqualTo("decodeFilmActorRows");
        assertThat(registry.emit()).hasSize(2);
    }

    @Test
    void emit_listSkipHelper_hasTypedRowReturnAndFilterChain() {
        var registry = new CompositeDecodeHelperRegistry(OUTPUT_PACKAGE);
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
    void emit_listThrowHelper_throwsClientExceptionWithTwoBranchMessage() {
        var registry = new CompositeDecodeHelperRegistry(OUTPUT_PACKAGE);
        registry.register(decodeFilmActor(), CompositeDecodeHelperRegistry.Mode.THROW, true);
        MethodSpec helper = registry.emit().iterator().next();
        String body = helper.code().toString();
        assertThat(body)
            // Throws the generated client-error type, not a bare GraphqlErrorException, and
            // peeks the wire prefix to distinguish malformed input from a wrong-type id.
            .contains("no.sikt.example.schema.GraphitronClientException")
            .contains("NodeIdEncoder.peekTypeId")
            .contains("not a valid FilmActor id")
            .contains("decodes to type")
            .contains("Record2::valuesRow")
            .doesNotContain("Objects::nonNull")
            .doesNotContain("GraphqlErrorException");
    }

    @Test
    void emit_scalarSkipHelper_returnsTypedRowDirectly() {
        var registry = new CompositeDecodeHelperRegistry(OUTPUT_PACKAGE);
        registry.register(decodeFilmActor(), CompositeDecodeHelperRegistry.Mode.SKIP, false);
        MethodSpec helper = registry.emit().iterator().next();
        assertThat(helper.returnType().toString())
            .isEqualTo("org.jooq.Row2<java.lang.Integer, java.lang.Integer>");
        String body = helper.code().toString();
        assertThat(body)
            .as("R260: readable statement-form body with meaningful locals, no underscore-prefixed names")
            .contains("wire instanceof String nodeId")
            .contains("key == null ? null : key.valuesRow()");
    }

    private static HelperRef.Decode decodeFilm() {
        return new HelperRef.Decode(
            ENCODER, "decodeFilm",
            List.of(new ColumnRef("film_id", "FILM_ID", "java.lang.Integer")),
            "Film");
    }

    @Test
    void register_arity1_namesKeyHelpersAndProjectsSingleColumn() {
        // The registry was extended from composite-only to all arities: an arity-1 decode lifts to
        // a `decode<Type>Key`/`Keys` helper that returns the bare key column type and projects via
        // Record1.value1(), rather than the former inline ternary at the call site.
        var registry = new CompositeDecodeHelperRegistry(OUTPUT_PACKAGE);
        var decode = decodeFilm();
        String scalar = registry.register(decode, CompositeDecodeHelperRegistry.Mode.SKIP, false);
        String list = registry.register(decode, CompositeDecodeHelperRegistry.Mode.SKIP, true);
        String listThrow = registry.register(decode, CompositeDecodeHelperRegistry.Mode.THROW, true);
        assertThat(scalar).isEqualTo("decodeFilmKey");
        assertThat(list).isEqualTo("decodeFilmKeys");
        assertThat(listThrow).isEqualTo("decodeFilmKeysOrThrow");
        assertThat(registry.emit()).hasSize(3);
    }

    @Test
    void emit_arity1ScalarSkipHelper_returnsKeyTypeAndProjectsValue1() {
        var registry = new CompositeDecodeHelperRegistry(OUTPUT_PACKAGE);
        registry.register(decodeFilm(), CompositeDecodeHelperRegistry.Mode.SKIP, false);
        MethodSpec helper = registry.emit().iterator().next();
        assertThat(helper.name()).isEqualTo("decodeFilmKey");
        assertThat(helper.returnType().toString()).isEqualTo("java.lang.Integer");
        String body = helper.code().toString();
        assertThat(body)
            .contains("wire instanceof String nodeId")
            .contains("NodeIdEncoder.decodeFilm(nodeId)")
            .contains("key == null ? null : key.value1()")
            .doesNotContain("valuesRow");
    }

    @Test
    void emit_arity1ListThrowHelper_returnsListOfKeyTypeAndThrows() {
        var registry = new CompositeDecodeHelperRegistry(OUTPUT_PACKAGE);
        registry.register(decodeFilm(), CompositeDecodeHelperRegistry.Mode.THROW, true);
        MethodSpec helper = registry.emit().iterator().next();
        assertThat(helper.name()).isEqualTo("decodeFilmKeysOrThrow");
        assertThat(helper.returnType().toString()).isEqualTo("java.util.List<java.lang.Integer>");
        String body = helper.code().toString();
        assertThat(body)
            .contains("instanceof java.util.List<?>")
            .contains("no.sikt.example.schema.GraphitronClientException")
            .contains("NodeIdEncoder.peekTypeId")
            .contains("not a valid Film id")
            .contains("Record1::value1")
            .doesNotContain("Objects::nonNull")
            .doesNotContain("GraphqlErrorException");
    }

    @Test
    void registerTenantSlot_staysDistinctFromThePredicateHelperForTheSameType() {
        // Same decode, different projection: the predicate helper hands back the whole key, the
        // tenant helper one slot of it. Two bodies, so two helpers; the dedup key says so.
        var registry = new CompositeDecodeHelperRegistry(OUTPUT_PACKAGE);
        var decode = decodeFilmActor();
        String predicate = registry.register(decode, CompositeDecodeHelperRegistry.Mode.THROW, true);
        String tenant = registry.registerTenantSlot(decode, 1);
        assertThat(predicate).isEqualTo("decodeFilmActorRowsOrThrow");
        assertThat(tenant).isEqualTo("decodeFilmActorTenantSlot1OrThrow");
        assertThat(registry.emit()).hasSize(2);
    }

    @Test
    void registerTenantSlot_sameSlotTwice_sharesOneHelper_differentSlotsDoNot() {
        var registry = new CompositeDecodeHelperRegistry(OUTPUT_PACKAGE);
        var decode = decodeFilmActor();
        assertThat(registry.registerTenantSlot(decode, 1))
            .isEqualTo(registry.registerTenantSlot(decode, 1));
        registry.registerTenantSlot(decode, 0);
        assertThat(registry.emit()).hasSize(2);
    }

    @Test
    void emit_tenantSlotHelper_takesAndReturnsObjectWhateverTheKeyArity() {
        // The divining fold hands the helper whatever the slot walk read, a scalar id or a
        // batch's list of them, and folds whatever comes back; so one Object-in, Object-out
        // helper serves both wire shapes and every key arity. The body's decode, flattening and
        // failure message are the compilation and execution tiers' to show.
        var registry = new CompositeDecodeHelperRegistry(OUTPUT_PACKAGE);
        registry.registerTenantSlot(decodeFilmActor(), 1);
        registry.registerTenantSlot(decodeFilm(), 0);
        assertThat(registry.emit())
            .extracting(MethodSpec::name)
            .containsExactly("decodeFilmActorTenantSlot1OrThrow", "decodeFilmTenantSlot0OrThrow");
        for (MethodSpec helper : registry.emit()) {
            assertThat(helper.returnType()).isEqualTo(TypeName.OBJECT);
            assertThat(helper.parameters())
                .singleElement()
                .satisfies(p -> assertThat(p.type()).isEqualTo(TypeName.OBJECT));
        }
    }

    @Test
    void emit_arity1ScalarThrowHelper_throwsClientExceptionWithTwoBranchMessage() {
        var registry = new CompositeDecodeHelperRegistry(OUTPUT_PACKAGE);
        registry.register(decodeFilm(), CompositeDecodeHelperRegistry.Mode.THROW, false);
        MethodSpec helper = registry.emit().iterator().next();
        assertThat(helper.name()).isEqualTo("decodeFilmKeyOrThrow");
        assertThat(helper.returnType().toString()).isEqualTo("java.lang.Integer");
        String body = helper.code().toString();
        assertThat(body)
            // The scalar arm has `nodeId` in scope directly, so the throw names the wire value too.
            .contains("wire instanceof String nodeId")
            .contains("no.sikt.example.schema.GraphitronClientException")
            .contains("NodeIdEncoder.peekTypeId(nodeId)")
            .contains("not a valid Film id")
            .contains("decodes to type")
            .contains("return key.value1()")
            .doesNotContain("GraphqlErrorException");
    }
}
