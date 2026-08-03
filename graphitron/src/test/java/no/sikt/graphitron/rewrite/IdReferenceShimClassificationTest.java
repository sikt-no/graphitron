package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.QueryField;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * Classification tests for the FK-qualifier synthesis shim. The shim routes arity-1 PK targets
 * to {@link InputField.ColumnBackedReferenceField} carrying
 * {@link no.sikt.graphitron.rewrite.model.CallSiteExtraction.SkipMismatchedElement} plus the
 * resolved FK joinPath. Uses the {@code idreffixture} jOOQ catalog (studieprogram + studierett)
 * because the shim gate requires {@code nodeIdMetadata(targetTable)} to be present; the
 * standard Sakila catalog tables have no {@code __NODE_TYPE_ID} metadata.
 *
 * <p>The inputs are plain (directiveless) input types resolved against each consuming field's
 * return-type table, so the classified leaves surface as the consuming {@code Query} field's
 * {@link GeneratedConditionFilter} body params: the reference carrier's lifted FK source column
 * and its {@code NodeIdDecodeKeys} extraction ride through
 * {@link CallSiteExtraction.NestedInputField} on the projected {@link BodyParam}.
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
        "no.sikt.graphitron.rewrite.idreffixture"
    );

    private static final String SHARED_SDL_PREFIX = """
        type Studieprogram @table(name: "studieprogram") { studieprogramId: String }
        type Studierett @table(name: "studierett") { studierettId: ID }
        """;

    private GraphitronSchema build(String schemaText) {
        return TestSchemaHelper.buildSchema(schemaText, IDREF_CTX);
    }

    /** The consuming field's single implicit predicate of the expected operator shape. */
    private static <T extends BodyParam> T bodyParam(GraphitronSchema schema, String queryFieldName, Class<T> shape) {
        var f = (QueryField.QueryTableField) schema.field("Query", queryFieldName);
        var gcf = (GeneratedConditionFilter) f.filters().stream()
            .filter(GeneratedConditionFilter.class::isInstance)
            .findFirst().orElseThrow();
        return gcf.bodyParams().stream()
            .filter(shape::isInstance).map(shape::cast).findFirst().orElseThrow();
    }

    /** Unwraps the leaf extraction the input-field carrier contributed to the projected param. */
    private static CallSiteExtraction leafExtraction(BodyParam bp) {
        return ((CallSiteExtraction.NestedInputField) bp.extraction()).leaf();
    }

    enum ShimCase {

        // Case 4a: @field(name:) value = "STUDIEPROGRAM_ID" → raw map key "studieprogram_id" →
        // matches FK1. Without pre-column placement the column lookup would find the
        // studieprogram_id column and classify as ColumnBackedField with a plain (non-decode)
        // leaf; the shim wins because it runs first for ID-typed fields, so the projected
        // predicate decodes node ids.
        SHIM_EXPLICIT_FIELD(
            "[ID!] @field(name: \"STUDIEPROGRAM_ID\") → shim fires before column lookup → In-predicate with NodeIdDecodeKeys leaf",
            SHARED_SDL_PREFIX + """
            input StudierettFilterInput {
              studieprogramIds: [ID!] @field(name: "STUDIEPROGRAM_ID")
            }
            type Query { studierett(filter: StudierettFilterInput): Studierett }
            """,
            schema -> {
                var bp = bodyParam(schema, "studierett", BodyParam.In.class);
                assertThat(bp.column().sqlName()).isEqualTo("studieprogram_id");
                assertThat(leafExtraction(bp))
                    .isInstanceOf(CallSiteExtraction.SkipMismatchedElement.class);
            }),

        // Case 4b: bare plural field name; default columnName = "studieprogramIds" →
        // lowercase "studieprogramids" hits the plural camel map key.
        SHIM_BARE_LIST(
            "[ID!] with bare plural field name studieprogramIds → plural map key hit → In-predicate with NodeIdDecodeKeys leaf",
            SHARED_SDL_PREFIX + """
            input StudierettFilterInput {
              studieprogramIds: [ID!]
            }
            type Query { studierett(filter: StudierettFilterInput): Studierett }
            """,
            schema -> {
                var bp = bodyParam(schema, "studierett", BodyParam.In.class);
                assertThat(leafExtraction(bp))
                    .isInstanceOf(CallSiteExtraction.SkipMismatchedElement.class);
            }),

        // Case 4c: bare scalar field name; default columnName = "studieprogramId" →
        // lowercase "studieprogramid" hits the camelCase map key.
        SHIM_BARE_SCALAR(
            "ID (scalar) bare field name studieprogramId → camelCase map key hit → Eq-predicate with NodeIdDecodeKeys leaf",
            SHARED_SDL_PREFIX + """
            input StudierettFilterInput {
              studieprogramId: ID
            }
            type Query { studierett(filter: StudierettFilterInput): Studierett }
            """,
            schema -> {
                var bp = bodyParam(schema, "studierett", BodyParam.Eq.class);
                assertThat(leafExtraction(bp))
                    .isInstanceOf(CallSiteExtraction.SkipMismatchedElement.class);
            }),

        // Case 4d: bare id: ID on a table that has nodeId metadata but no outgoing FKs.
        // studieprogram has __NODE_TYPE_ID but no outgoing FK → empty qualifier map →
        // "id" doesn't match → column lookup misses (no column named "id") →
        // falls to the synthesis shim, which routes onto ColumnBackedField with
        // NodeIdDecodeKeys.SkipMismatchedElement (arity-1 single-PK NodeType), projected as an
        // Eq-predicate against the table's own key column.
        DOES_NOT_SHIM_OWN_ID(
            "bare id: ID on a node-typed table with no outgoing FKs → own-key Eq-predicate with NodeIdDecodeKeys leaf (post-R50; retired wire-shape NodeIdField successor)",
            """
            type Studieprogram @table(name: "studieprogram") { studieprogramId: String }
            input StudieprogramFilterInput {
              id: ID
            }
            type Query { studieprogram(filter: StudieprogramFilterInput): Studieprogram }
            """,
            schema -> {
                var bp = bodyParam(schema, "studieprogram", BodyParam.Eq.class);
                assertThat(bp.name()).isEqualTo("id");
                assertThat(bp.column().sqlName()).isEqualTo("studieprogram_id");
                assertThat(leafExtraction(bp))
                    .isInstanceOf(CallSiteExtraction.SkipMismatchedElement.class);
            }),

        // Case 4e: role-prefixed qualifier (FK2). The raw map key
        // "registrar_studieprogram_studieprogram_id" does NOT match any column on studierett
        // (columns: studierett_id, studieprogram_id, registrar_studieprogram). Without the
        // pre-column shim, this field would be Unresolved and reject the consuming field.
        // The predicate binds the lifted FK source column on the consumer's own table
        // (registrar_studieprogram), not the target's key column.
        SHIM_ROLE_PREFIXED(
            "[ID!] @field where key ≠ any column (role-prefixed qualifier) → In-predicate on the FK source column with NodeIdDecodeKeys leaf",
            SHARED_SDL_PREFIX + """
            input StudierettFilterInput {
              registrarStudieprogramIds: [ID!] @field(name: "REGISTRAR_STUDIEPROGRAM_STUDIEPROGRAM_ID")
            }
            type Query { studierett(filter: StudierettFilterInput): Studierett }
            """,
            schema -> {
                var bp = bodyParam(schema, "studierett", BodyParam.In.class);
                assertThat(bp.column().sqlName()).isEqualTo("registrar_studieprogram");
                assertThat(leafExtraction(bp))
                    .isInstanceOf(CallSiteExtraction.SkipMismatchedElement.class);
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
