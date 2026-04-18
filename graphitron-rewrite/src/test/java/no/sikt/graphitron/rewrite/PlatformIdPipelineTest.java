package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.InputField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDL → classified-variant pipeline tests for the legacy platform-id path. Exercises both the
 * input-side ({@link InputField.PlatformIdField}) and output-side ({@link ChildField.PlatformIdField})
 * classifier fallbacks that fire when a column lookup misses and the jOOQ table/record classes
 * expose legacy {@code get*Id}/{@code set*Id} accessors.
 *
 * <p>Uses the synthetic catalog in {@code no.sikt.graphitron.rewrite.platformidfixture} instead of
 * the sakila-style test fixture — the standard jOOQ generator never emits platform-id shape, so
 * a hand-written catalog is the only way to exercise the positive branches end-to-end. The fixture
 * has two tables:
 * <ul>
 *   <li>{@code bar} — table class has {@code getId()}/{@code getPersonId()} returning
 *       {@code SelectField<String>}; record has {@code getId()}/{@code setId(String)} and
 *       {@code getPersonId()}/{@code setPersonId(String)}.</li>
 *   <li>{@code qux} — plain table; no platform-id accessors on either class. Negative-case fixture.</li>
 * </ul>
 */
class PlatformIdPipelineTest {

    private static final String FIXTURE_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.platformidfixture";

    @BeforeEach
    void setup() {
        RewriteConfig.setProperties(Set.of(), "", "fake.code.generated", FIXTURE_JOOQ_PACKAGE, Map.of());
    }

    @AfterEach
    void teardown() {
        RewriteConfig.clear();
    }

    // ===== Input side =====

    enum InputCase {
        IMPLICIT_ID(
            "input field `id: ID!` on a platform-id table → InputField.PlatformIdField(getId/setId)",
            """
            input Foo @table(name: "bar") { id: ID! }
            type Query { x: String }
            """,
            schema -> {
                var t = (GraphitronType.TableInputType) schema.type("Foo");
                var f = (InputField.PlatformIdField) t.inputFields().get(0);
                assertThat(f.getterName()).isEqualTo("getId");
                assertThat(f.setterName()).isEqualTo("setId");
            }),

        EXPLICIT_PERSON_ID(
            "input field `personId: ID! @field(name: \"PERSON_ID\")` → PlatformIdField(getPersonId/setPersonId)",
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
        tc.assertions.accept(TestSchemaHelper.buildSchema(tc.sdl));
    }

    // ===== Output side =====

    enum OutputCase {
        IMPLICIT_ID(
            "output field `id: ID!` on a platform-id table → ChildField.PlatformIdField(getId)",
            """
            type Foo @table(name: "bar") { id: ID! }
            type Query { foo: Foo }
            """,
            schema -> {
                var f = (ChildField.PlatformIdField) schema.field("Foo", "id");
                assertThat(f.getterName()).isEqualTo("getId");
            }),

        EXPLICIT_PERSON_ID(
            "output field `personId: ID! @field(name: \"PERSON_ID\")` → PlatformIdField(getPersonId)",
            """
            type Foo @table(name: "bar") { personId: ID! @field(name: "PERSON_ID") }
            type Query { foo: Foo }
            """,
            schema -> {
                var f = (ChildField.PlatformIdField) schema.field("Foo", "personId");
                assertThat(f.getterName()).isEqualTo("getPersonId");
            }),

        ACCESSOR_MISSING(
            "column absent AND no platform-id accessor on table class → UnclassifiedField",
            """
            type Foo @table(name: "qux") { id: ID! }
            type Query { foo: Foo }
            """,
            schema -> assertThat(schema.field("Foo", "id"))
                .isInstanceOf(GraphitronField.UnclassifiedField.class)),

        NODE_ID_DIRECTIVE_BYPASSES_FALLBACK(
            "`@nodeId` without `@node` → UnclassifiedField (platform-id fallback not taken)",
            """
            type Foo @table(name: "bar") { id: ID! @nodeId }
            type Query { foo: Foo }
            """,
            schema -> assertThat(schema.field("Foo", "id"))
                .isInstanceOf(GraphitronField.UnclassifiedField.class));

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
        tc.assertions.accept(TestSchemaHelper.buildSchema(tc.sdl));
    }
}
