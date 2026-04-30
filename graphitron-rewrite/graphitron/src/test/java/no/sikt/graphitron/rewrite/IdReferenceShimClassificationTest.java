package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronType.TableInputType;
import no.sikt.graphitron.rewrite.model.InputField;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * Classification tests for the FK-qualifier synthesis shim. Post-R50 the shim emits
 * the column-shaped successor of the retired {@code IdReferenceField}: arity-1 PK targets
 * land on {@link InputField.ColumnReferenceField} carrying
 * {@link no.sikt.graphitron.rewrite.model.CallSiteExtraction.SkipMismatchedElement} plus the
 * resolved FK joinPath. Uses the {@code idreffixture} jOOQ catalog (studieprogram + studierett)
 * because the shim gate requires {@code nodeIdMetadata(targetTable)} to be present — the
 * standard Sakila catalog tables have no {@code __NODE_TYPE_ID} metadata.
 *
 * <p>The idreffixture schema provides:
 * <ul>
 *   <li>{@code studieprogram}: target table with {@code __NODE_TYPE_ID = "Studieprogram"}.
 *       No outgoing FKs.</li>
 *   <li>{@code studierett}: source table with two FKs to {@code studieprogram}:
 *     <ul>
 *       <li>FK1 {@code studierett_studieprogram_id_fkey}: HAR role (src = tgt = studieprogram_id)
 *           → qualifier {@code "StudieprogramId"}. Raw map key {@code "studieprogram_id"}
 *           coincides with the source column name, so the shim-before-column-lookup ordering
 *           is load-bearing for Case 4a.</li>
 *       <li>FK2 {@code studierett_registrar_studieprogram_fkey}: role-prefixed
 *           (registrar_studieprogram → studieprogram_id) → qualifier
 *           {@code "RegistrarStudieprogramStudieprogramId"}. Raw map key
 *           {@code "registrar_studieprogram_studieprogram_id"} does not match any column.</li>
 *     </ul>
 *   </li>
 * </ul>
 */
@PipelineTier
class IdReferenceShimClassificationTest {

    private static final RewriteContext IDREF_CTX = new RewriteContext(
        List.of(),
        Path.of(""),
        Path.of(""),
        "fake.code.generated",
        "no.sikt.graphitron.rewrite.idreffixture",
        Map.of()
    );

    private static final String SHARED_SDL_PREFIX = """
        type Studieprogram @table(name: "studieprogram") { studieprogramId: String }
        type Studierett @table(name: "studierett") { studierettId: ID }
        """;

    private GraphitronSchema build(String schemaText) {
        return TestSchemaHelper.buildSchema(schemaText, IDREF_CTX);
    }

    enum ShimCase {

        // Case 4a: @field(name:) value = "STUDIEPROGRAM_ID" → raw map key "studieprogram_id" →
        // matches FK1. Without pre-column placement the column lookup would find the
        // studieprogram_id column and classify as ColumnField; the shim wins because it runs
        // first for ID-typed fields.
        SHIM_EXPLICIT_FIELD(
            "[ID!] @field(name: \"STUDIEPROGRAM_ID\") → shim fires before column lookup → ColumnReferenceField with NodeIdDecodeKeys",
            SHARED_SDL_PREFIX + """
            input StudierettFilterInput @table(name: "studierett") {
              studieprogramIds: [ID!] @field(name: "STUDIEPROGRAM_ID")
            }
            type Query { studierett: Studierett }
            """,
            schema -> {
                var tit = (TableInputType) schema.type("StudierettFilterInput");
                var f = (InputField.ColumnReferenceField) tit.inputFields().stream()
                    .filter(InputField.ColumnReferenceField.class::isInstance).findFirst().orElseThrow();
                assertThat(f.list()).isTrue();
                assertThat(f.column().sqlName()).isEqualTo("studieprogram_id");
                assertThat(f.extraction())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.SkipMismatchedElement.class);
                assertThat(f.joinPath()).hasSize(1);
            }),

        // Case 4b: bare plural field name — default columnName = "studieprogramIds" →
        // lowercase "studieprogramids" hits the plural camel map key.
        SHIM_BARE_LIST(
            "[ID!] with bare plural field name studieprogramIds → plural map key hit → ColumnReferenceField with NodeIdDecodeKeys",
            SHARED_SDL_PREFIX + """
            input StudierettFilterInput @table(name: "studierett") {
              studieprogramIds: [ID!]
            }
            type Query { studierett: Studierett }
            """,
            schema -> {
                var tit = (TableInputType) schema.type("StudierettFilterInput");
                var f = (InputField.ColumnReferenceField) tit.inputFields().stream()
                    .filter(InputField.ColumnReferenceField.class::isInstance).findFirst().orElseThrow();
                assertThat(f.list()).isTrue();
                assertThat(f.extraction())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.SkipMismatchedElement.class);
            }),

        // Case 4c: bare scalar field name — default columnName = "studieprogramId" →
        // lowercase "studieprogramid" hits the camelCase map key.
        SHIM_BARE_SCALAR(
            "ID (scalar) bare field name studieprogramId → camelCase map key hit → ColumnReferenceField with NodeIdDecodeKeys",
            SHARED_SDL_PREFIX + """
            input StudierettFilterInput @table(name: "studierett") {
              studieprogramId: ID
            }
            type Query { studierett: Studierett }
            """,
            schema -> {
                var tit = (TableInputType) schema.type("StudierettFilterInput");
                var f = (InputField.ColumnReferenceField) tit.inputFields().stream()
                    .filter(InputField.ColumnReferenceField.class::isInstance).findFirst().orElseThrow();
                assertThat(f.list()).isFalse();
                assertThat(f.extraction())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.SkipMismatchedElement.class);
            }),

        // Case 4d: bare id: ID on a table that has nodeId metadata but no outgoing FKs.
        // studieprogram has __NODE_TYPE_ID but no outgoing FK → empty qualifier map →
        // "id" doesn't match → column lookup misses (no column named "id") →
        // falls to the synthesis shim. Post-R50, the shim routes onto ColumnField with
        // NodeIdDecodeKeys.SkipMismatchedElement (arity-1 single-PK NodeType) instead of
        // the retired wire-shape NodeIdField.
        DOES_NOT_SHIM_OWN_ID(
            "bare id: ID on node-typed @table with no outgoing FKs → ColumnField with NodeIdDecodeKeys (post-R50; retired wire-shape NodeIdField successor)",
            """
            type Studieprogram @table(name: "studieprogram") { studieprogramId: String }
            input StudieprogramFilterInput @table(name: "studieprogram") {
              id: ID
            }
            type Query { studieprogram: Studieprogram }
            """,
            schema -> {
                var tit = (TableInputType) schema.type("StudieprogramFilterInput");
                var f = tit.inputFields().stream()
                    .filter(g -> g.name().equals("id")).findFirst().orElseThrow();
                assertThat(f).isInstanceOf(InputField.ColumnField.class);
                var cf = (InputField.ColumnField) f;
                assertThat(cf.extraction())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.SkipMismatchedElement.class);
            }),

        // Case 4e: role-prefixed qualifier (FK2). The raw map key
        // "registrar_studieprogram_studieprogram_id" does NOT match any column on studierett
        // (columns: studierett_id, studieprogram_id, registrar_studieprogram). Without the
        // pre-column shim, this field would be Unresolved and propagate as UnclassifiedType.
        SHIM_ROLE_PREFIXED(
            "[ID!] @field where key ≠ any column (role-prefixed qualifier) → ColumnReferenceField with NodeIdDecodeKeys",
            SHARED_SDL_PREFIX + """
            input StudierettFilterInput @table(name: "studierett") {
              registrarStudieprogramIds: [ID!] @field(name: "REGISTRAR_STUDIEPROGRAM_STUDIEPROGRAM_ID")
            }
            type Query { studierett: Studierett }
            """,
            schema -> {
                var tit = (TableInputType) schema.type("StudierettFilterInput");
                var f = (InputField.ColumnReferenceField) tit.inputFields().stream()
                    .filter(InputField.ColumnReferenceField.class::isInstance).findFirst().orElseThrow();
                assertThat(f.list()).isTrue();
                assertThat(f.column().sqlName()).isEqualTo("studieprogram_id");
                assertThat(f.extraction())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.SkipMismatchedElement.class);
                assertThat(f.joinPath()).hasSize(1);
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;

        ShimCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }

        @Override
        public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ShimCase.class)
    void shimClassification(ShimCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }
}
