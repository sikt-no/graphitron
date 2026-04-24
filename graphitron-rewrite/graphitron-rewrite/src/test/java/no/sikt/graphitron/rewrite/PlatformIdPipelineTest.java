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
 * SDL → classified-variant pipeline tests for the platform-id / node-id path. Exercises the
 * input-side ({@link InputField.NodeIdField}), output-side ({@link ChildField.NodeIdField}),
 * and type-level {@link GraphitronType.NodeType} synthesis for tables whose jOOQ class exposes
 * {@code __NODE_TYPE_ID} + {@code __NODE_KEY_COLUMNS} constants (synthesized route) as well as
 * the SDL-declared {@code @node} / {@code @nodeId} directive paths.
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
            "input `id: ID!` on a node-type table → NodeIdField(nodeTypeId=Bar, keyColumns=[id_1,id_2]) — synthesized route",
            """
            input Foo @table(name: "bar") { id: ID! }
            type Query { x: String }
            """,
            schema -> {
                var t = (GraphitronType.TableInputType) schema.type("Foo");
                var f = (InputField.NodeIdField) t.inputFields().get(0);
                assertThat(f.nodeTypeId()).isEqualTo("Bar");
                assertThat(f.nodeKeyColumns()).extracting(ColumnRef::sqlName)
                    .containsExactly("id_1", "id_2");
            }),

        EXPLICIT_PERSON_ID(
            "input `personId: ID! @field(name: \"PERSON_ID\")` on a node-type table → NodeIdField (PERSON_ID has no column, nodeId metadata wins)",
            """
            input Foo @table(name: "bar") { personId: ID! @field(name: "PERSON_ID") }
            type Query { x: String }
            """,
            schema -> {
                var t = (GraphitronType.TableInputType) schema.type("Foo");
                var f = (InputField.NodeIdField) t.inputFields().get(0);
                assertThat(f.nodeTypeId()).isEqualTo("Bar");
                assertThat(f.nodeKeyColumns()).extracting(ColumnRef::sqlName)
                    .containsExactly("id_1", "id_2");
            }),

        EXPLICIT_NODE_ID_DIRECTIVE(
            "input `id: ID! @nodeId` on a node-type table → NodeIdField via declared @nodeId (same classifier path as synthesized)",
            """
            input Foo @table(name: "bar") { id: ID! @nodeId }
            type Query { x: String }
            """,
            schema -> {
                var t = (GraphitronType.TableInputType) schema.type("Foo");
                var f = (InputField.NodeIdField) t.inputFields().get(0);
                assertThat(f.nodeTypeId()).isEqualTo("Bar");
                assertThat(f.nodeKeyColumns()).extracting(ColumnRef::sqlName)
                    .containsExactly("id_1", "id_2");
            }),

        ACCESSOR_MISSING(
            "plain ID field on a table without node-id metadata and no platform-id accessor → TableInputType fails, type becomes UnclassifiedType",
            """
            input Foo @table(name: "qux") { id: ID! }
            type Query { x: String }
            """,
            schema -> assertThat(schema.type("Foo")).isInstanceOf(GraphitronType.UnclassifiedType.class)),

        LIST_VARIANT(
            "list ID input skips both the node-id and platform-id checks (list gate) → UnclassifiedType",
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

    // ===== Input reference side =====

    enum InputReferenceCase {
        REFERENCE_TO_NODE_TYPE(
            "input `relatedId: ID! @nodeId(typeName: 'Baz')` on bar table → NodeIdReferenceField via auto-inferred bar→baz FK",
            """
            type Baz @table(name: "baz") { id: ID! }
            input Foo @table(name: "bar") { relatedId: ID! @nodeId(typeName: "Baz") }
            type Query { x: String }
            """,
            schema -> {
                var t = (GraphitronType.TableInputType) schema.type("Foo");
                var f = (InputField.NodeIdReferenceField) t.inputFields().get(0);
                assertThat(f.typeName()).isEqualTo("Baz");
                assertThat(f.nodeTypeId()).isEqualTo("Baz");
                assertThat(f.nodeKeyColumns()).extracting(ColumnRef::sqlName)
                    .containsExactly("id");
                assertThat(f.joinPath()).hasSize(1);
            }),

        TARGET_NOT_A_NODE_TYPE(
            "input `relatedId: ID! @nodeId(typeName: 'Qux')` where Qux is not a NodeType → UnclassifiedType",
            """
            type Qux @table(name: "qux") { name: String }
            input Foo @table(name: "bar") { relatedId: ID! @nodeId(typeName: "Qux") }
            type Query { x: String }
            """,
            schema -> assertThat(schema.type("Foo")).isInstanceOf(GraphitronType.UnclassifiedType.class)),

        LIST_VARIANT(
            "list `[ID!]!` with @nodeId(typeName:) → UnclassifiedType (list-gate applies before @nodeId check)",
            """
            type Baz @table(name: "baz") { id: ID! }
            input Foo @table(name: "bar") { relatedId: [ID!]! @nodeId(typeName: "Baz") }
            type Query { x: String }
            """,
            schema -> assertThat(schema.type("Foo")).isInstanceOf(GraphitronType.UnclassifiedType.class)),

        NO_FK_PATH(
            "input `relatedId: ID! @nodeId(typeName: 'Baz')` where no FK from qux to baz → UnclassifiedType (path resolver rejects)",
            """
            type Baz @table(name: "baz") { id: ID! }
            input Foo @table(name: "qux") { relatedId: ID! @nodeId(typeName: "Baz") }
            type Query { x: String }
            """,
            schema -> assertThat(schema.type("Foo")).isInstanceOf(GraphitronType.UnclassifiedType.class));

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        InputReferenceCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(InputReferenceCase.class)
    void inputNodeIdReferenceClassification(InputReferenceCase tc) {
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
