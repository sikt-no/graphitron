package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.InputField;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDL → classified-variant pipeline tests for the platform-id path. Exercises both the
 * input-side ({@link InputField.PlatformIdField}) and output-side ({@link ChildField.PlatformIdField})
 * classifier fallbacks that fire when a column lookup misses, plus the type-level {@link
 * GraphitronType.NodeType} synthesis landed in Step 2 of the platform-id plan (tables whose jOOQ
 * class exposes {@code __NODE_TYPE_ID} + {@code __NODE_KEY_COLUMNS} constants classify as {@code
 * NodeType} regardless of whether the SDL declares {@code @node}).
 *
 * <p>Uses the synthetic catalog in {@code no.sikt.graphitron.rewrite.platformidfixture} instead of
 * the sakila-style test fixture — the standard jOOQ generator never emits platform-id shape, so
 * a hand-written catalog is the only way to exercise the positive branches end-to-end. The fixture
 * has two tables:
 * <ul>
 *   <li>{@code bar} — table class carries {@code __NODE_TYPE_ID = "Bar"} and {@code __NODE_KEY_COLUMNS
 *       = { BAR.ID_1, BAR.ID_2 }}; also retains the legacy {@code getId()}/{@code getPersonId()}
 *       instance methods and the record-level accessors until Step 5 deletes the fallback.</li>
 *   <li>{@code qux} — plain table; no metadata, no platform-id accessors. Negative-case fixture.</li>
 * </ul>
 */
class PlatformIdPipelineTest {

    private static final String FIXTURE_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.platformidfixture";
    private static final RewriteContext FIXTURE_CTX = new RewriteContext(
        List.of(), Path.of(""), Path.of(""),
        DEFAULT_OUTPUT_PACKAGE, FIXTURE_JOOQ_PACKAGE,
        Map.of()
    );

    // ===== Type-level NodeType synthesis =====

    enum TypeCase {
        METADATA_ONLY(
            "`@table` on a metadata-carrying table synthesizes NodeType with the metadata values",
            """
            type Foo @table(name: "bar") { name: String }
            type Query { foo: Foo }
            """,
            schema -> {
                var t = (GraphitronType.NodeType) schema.type("Foo");
                assertThat(t.typeId()).isEqualTo("Bar");
                assertThat(t.nodeKeyColumns()).extracting(ColumnRef::sqlName)
                    .containsExactly("id_1", "id_2");
            }),

        NODE_AND_METADATA_AGREE(
            "`@node(typeId:, keyColumns:)` matching metadata exactly → NodeType (accepted)",
            """
            type Foo @table(name: "bar") @node(typeId: "Bar", keyColumns: ["id_1", "id_2"]) { name: String }
            type Query { foo: Foo }
            """,
            schema -> {
                var t = (GraphitronType.NodeType) schema.type("Foo");
                assertThat(t.typeId()).isEqualTo("Bar");
                assertThat(t.nodeKeyColumns()).extracting(ColumnRef::sqlName)
                    .containsExactly("id_1", "id_2");
            }),

        NODE_WITHOUT_ARGS_DELEGATES_TO_METADATA(
            "`@node` with neither `typeId` nor `keyColumns` delegates to metadata on both axes",
            """
            type Foo @table(name: "bar") @node { name: String }
            type Query { foo: Foo }
            """,
            schema -> {
                var t = (GraphitronType.NodeType) schema.type("Foo");
                assertThat(t.typeId()).isEqualTo("Bar");
                assertThat(t.nodeKeyColumns()).extracting(ColumnRef::sqlName)
                    .containsExactly("id_1", "id_2");
            }),

        TYPE_ID_DISAGREES(
            "`@node(typeId: \"Foo\")` disagrees with metadata (\"Bar\") → UnclassifiedType with both sides",
            """
            type Foo @table(name: "bar") @node(typeId: "Foo", keyColumns: ["id_1", "id_2"]) { name: String }
            type Query { foo: Foo }
            """,
            schema -> {
                var t = (GraphitronType.UnclassifiedType) schema.type("Foo");
                assertThat(t.reason())
                    .contains("@node(typeId: \"Foo\")")
                    .contains("typeId: \"Bar\"");
            }),

        KEY_COLUMNS_DISAGREE(
            "`@node(keyColumns: [...])` disagrees with metadata → UnclassifiedType with both sides (typeId match is not a waiver)",
            """
            type Foo @table(name: "bar") @node(typeId: "Bar", keyColumns: ["id_2", "id_1"]) { name: String }
            type Query { foo: Foo }
            """,
            schema -> {
                var t = (GraphitronType.UnclassifiedType) schema.type("Foo");
                assertThat(t.reason())
                    .contains("@node(keyColumns:")
                    .contains("[\"id_1\", \"id_2\"]");
            }),

        TYPE_ID_ONLY_DISAGREES_KEY_COLUMNS_OMITTED(
            "`@node(typeId: \"Foo\")` disagrees on typeId; keyColumns omitted → UnclassifiedType (partial disagreement still errors)",
            """
            type Foo @table(name: "bar") @node(typeId: "Foo") { name: String }
            type Query { foo: Foo }
            """,
            schema -> {
                var t = (GraphitronType.UnclassifiedType) schema.type("Foo");
                assertThat(t.reason()).contains("typeId");
            }),

        NO_METADATA_NO_NODE(
            "`@table` on a table without metadata → TableType (no synthesis)",
            """
            type Foo @table(name: "qux") { name: String }
            type Query { foo: Foo }
            """,
            schema -> assertThat(schema.type("Foo")).isInstanceOf(GraphitronType.TableType.class)),

        NODE_ONLY_NO_METADATA(
            "`@node` without metadata → NodeType with SDL-declared values (pre-pivot path preserved)",
            """
            type Foo @table(name: "qux") @node(typeId: "Foo") { name: String }
            type Query { foo: Foo }
            """,
            schema -> {
                var t = (GraphitronType.NodeType) schema.type("Foo");
                assertThat(t.typeId()).isEqualTo("Foo");
                assertThat(t.nodeKeyColumns()).isEmpty();
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        TypeCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TypeCase.class)
    void typeLevelNodeSynthesis(TypeCase tc) {
        tc.assertions.accept(TestSchemaHelper.buildSchema(tc.sdl, FIXTURE_CTX));
    }

    // ===== Input side =====

    enum InputCase {
        IMPLICIT_ID(
            "input `id: ID!` on a platform-id table → NodeType at type level, PlatformIdField at field level (until Step 3 flips)",
            """
            input Foo @table(name: "bar") { id: ID! }
            type Query { x: String }
            """,
            schema -> {
                // TableInputType still emits; the inner InputField still classifies as
                // PlatformIdField in Step 2 (Step 3 flips bare id: ID! to InputField.NodeIdField).
                var t = (GraphitronType.TableInputType) schema.type("Foo");
                var f = (InputField.PlatformIdField) t.inputFields().get(0);
                assertThat(f.getterName()).isEqualTo("getId");
                assertThat(f.setterName()).isEqualTo("setId");
            }),

        EXPLICIT_PERSON_ID(
            "input `personId: ID! @field(name: \"PERSON_ID\")` → PlatformIdField(getPersonId/setPersonId) at field level",
            """
            input Foo @table(name: "bar") { personId: ID! @field(name: "PERSON_ID") }
            type Query { x: String }
            """,
            schema -> {
                var t = (GraphitronType.TableInputType) schema.type("Foo");
                var f = (InputField.PlatformIdField) t.inputFields().get(0);
                assertThat(f.getterName()).isEqualTo("getPersonId");
                assertThat(f.setterName()).isEqualTo("setPersonId");
            }),

        ACCESSOR_MISSING(
            "platform-id fallback runs but record has no getId/setId → TableInputType fails, type becomes UnclassifiedType",
            """
            input Foo @table(name: "qux") { id: ID! }
            type Query { x: String }
            """,
            schema -> assertThat(schema.type("Foo")).isInstanceOf(GraphitronType.UnclassifiedType.class)),

        LIST_VARIANT(
            "list ID input skips the platform-id fallback (list gate) → UnclassifiedType",
            """
            input Foo @table(name: "bar") { id: [ID!]! }
            type Query { x: String }
            """,
            schema -> assertThat(schema.type("Foo")).isInstanceOf(GraphitronType.UnclassifiedType.class));

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        InputCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(InputCase.class)
    void inputPlatformIdClassification(InputCase tc) {
        tc.assertions.accept(TestSchemaHelper.buildSchema(tc.sdl, FIXTURE_CTX));
    }

    // ===== Output side =====

    enum OutputCase {
        IMPLICIT_ID(
            "output `id: ID!` on a platform-id table → NodeType parent, synthesized NodeIdField (Step 3: Path-2 short-circuits the platform-id fallback)",
            """
            type Foo @table(name: "bar") { id: ID! }
            type Query { foo: Foo }
            """,
            schema -> {
                assertThat(schema.type("Foo")).isInstanceOf(GraphitronType.NodeType.class);
                var f = (ChildField.NodeIdField) schema.field("Foo", "id");
                assertThat(f.nodeTypeId()).isEqualTo("Bar");
                assertThat(f.nodeKeyColumns()).extracting(ColumnRef::sqlName)
                    .containsExactly("id_1", "id_2");
            }),

        EXPLICIT_PERSON_ID(
            "output `personId: ID! @field(name: \"PERSON_ID\")` → NodeType parent, PlatformIdField(getPersonId) field (the !@field clause keeps this on the platform-id fallback until Step 5)",
            """
            type Foo @table(name: "bar") { personId: ID! @field(name: "PERSON_ID") }
            type Query { foo: Foo }
            """,
            schema -> {
                assertThat(schema.type("Foo")).isInstanceOf(GraphitronType.NodeType.class);
                var f = (ChildField.PlatformIdField) schema.field("Foo", "personId");
                assertThat(f.getterName()).isEqualTo("getPersonId");
            }),

        ACCESSOR_MISSING(
            "column absent AND no metadata / platform-id accessor → UnclassifiedField, parent stays TableType",
            """
            type Foo @table(name: "qux") { id: ID! }
            type Query { foo: Foo }
            """,
            schema -> {
                assertThat(schema.type("Foo")).isInstanceOf(GraphitronType.TableType.class);
                assertThat(schema.field("Foo", "id"))
                    .isInstanceOf(GraphitronField.UnclassifiedField.class);
            }),

        NODE_ID_DIRECTIVE_ON_SYNTHESIZED_NODE(
            "`id: ID! @nodeId` on a metadata-carrying table → NodeIdField (synthesized NodeType satisfies the @nodeId guard)",
            """
            type Foo @table(name: "bar") { id: ID! @nodeId }
            type Query { foo: Foo }
            """,
            schema -> {
                assertThat(schema.type("Foo")).isInstanceOf(GraphitronType.NodeType.class);
                var f = (ChildField.NodeIdField) schema.field("Foo", "id");
                assertThat(f.nodeTypeId()).isEqualTo("Bar");
                assertThat(f.nodeKeyColumns()).extracting(ColumnRef::sqlName)
                    .containsExactly("id_1", "id_2");
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        OutputCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(OutputCase.class)
    void outputPlatformIdClassification(OutputCase tc) {
        tc.assertions.accept(TestSchemaHelper.buildSchema(tc.sdl, FIXTURE_CTX));
    }
}
