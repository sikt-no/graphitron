package no.sikt.graphitron.rewrite.compile;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage of {@link CompileDependencyGraphBuilder} over a hand-built classified
 * model, exercising the node switch (per-type file contributions + singletons), the structural edge
 * projection (a fetcher references its target type's projection class and its parent conditions), the
 * blanket frozen-scaffolding edges, and the wiring-hub edges (schema class references every fetcher;
 * facade references the schema). The exhaustiveness of both switches is a compile-time guarantee; this
 * test pins the per-leaf edge/node payloads.
 */
@UnitTier
class CompileDependencyGraphBuilderTest {

    private static final String PKG = "com.example.gen";

    private static TableRef filmTable() {
        return new TableRef(
            "film", "FILM",
            ClassName.get("jooq.tables", "Film"),
            ClassName.get("jooq.tables.records", "FilmRecord"),
            ClassName.get("jooq", "Tables"),
            List.of(),
            List.of());
    }

    private static TableRef languageTable() {
        return new TableRef(
            "language", "LANGUAGE",
            ClassName.get("jooq.tables", "Language"),
            ClassName.get("jooq.tables.records", "LanguageRecord"),
            ClassName.get("jooq", "Tables"),
            List.of(),
            List.of());
    }

    private static ReturnTypeRef.TableBoundReturnType languageReturn() {
        return new ReturnTypeRef.TableBoundReturnType("Language", languageTable(), new FieldWrapper.Single(true));
    }

    /** A standalone-lookup-shape inline table field on {@code Film} projecting {@code Language}. */
    private static ChildField.TableField filmLanguageField(List<WhereFilter> filters) {
        return new ChildField.TableField(
            "Film", "language", null,
            languageReturn(),
            List.of(),          // empty joinPath -> standalone shape, parentCorrelation must be null
            filters,
            new OrderBySpec.None(), null, null);
    }

    /** A {@code @nodeId}-decoding filter, the shape CompositeDecodeHelperRegistry lifts a helper on. */
    private static WhereFilter nodeIdDecodingFilter() {
        var decode = new HelperRef.Decode(
            ClassName.get("com.example.gen.util", "NodeIdEncoder"),
            "decodeLanguage",
            List.of(new ColumnRef("ID", "java.lang.Integer", "java.lang.Integer")),
            "Language");
        var param = new CallParam("id",
            new CallSiteExtraction.SkipMismatchedElement(decode), false, "java.lang.Integer");
        return new GeneratedConditionFilter(
            "com.example.gen.conditions.LanguageConditions", "byId", languageTable(),
            List.of(param), List.of());
    }

    /** A minimal two-type schema: {@code type Film @table} + {@code Query { films: [Film] }}. */
    private static GraphitronSchema filmQuerySchema() {
        var types = new LinkedHashMap<String, GraphitronType>();
        types.put("Film", new GraphitronType.TableType("Film", null, filmTable()));
        types.put("Query", new GraphitronType.RootType("Query", null));

        var fields = new LinkedHashMap<FieldCoordinates, GraphitronField>();
        fields.put(FieldCoordinates.coordinates("Query", "films"),
            new QueryField.QueryTableField(
                "Query", "films", null,
                new ReturnTypeRef.TableBoundReturnType("Film", filmTable(),
                    new FieldWrapper.List(false, true)),
                List.of(), new OrderBySpec.None(), null));

        return new GraphitronSchema(types, fields);
    }

    @Test
    void perTypeFilesAndSingletonsAreNodes() {
        var g = CompileDependencyGraphBuilder.fromModel(filmQuerySchema(), PKG);

        assertThat(g.nodes()).contains(
            PKG + ".fetchers.FilmFetchers",
            PKG + ".types.Film",
            PKG + ".schema.FilmType",
            PKG + ".fetchers.QueryFetchers",
            // singletons
            PKG + ".util.NodeIdEncoder",
            PKG + ".util.LightFetcher",
            PKG + ".schema.GraphitronSchema",
            PKG + ".Graphitron");
    }

    @Test
    void queryFetcherReferencesTargetProjectionAndConditions() {
        var g = CompileDependencyGraphBuilder.fromModel(filmQuerySchema(), PKG);

        // The spec's slice-2 acceptance example: "a fetcher unit references its type unit and its
        // conditions unit." Query.films is a QueryTableField (SQL-generating), so QueryFetchers
        // references the Film projection class and the QueryConditions class.
        assertThat(g.directReferences(PKG + ".fetchers.QueryFetchers")).contains(
            PKG + ".types.Film",
            PKG + ".conditions.QueryConditions");
    }

    @Test
    void everyFetcherBlanketsTheFrozenScaffolding() {
        var g = CompileDependencyGraphBuilder.fromModel(filmQuerySchema(), PKG);

        assertThat(g.directReferences(PKG + ".fetchers.QueryFetchers")).contains(
            PKG + ".util.LightFetcher",
            PKG + ".util.ConnectionResult",
            PKG + ".schema.Outcome");
        // NodeIdEncoder is per-type-growing: it is NOT blanketed (no encode used in this schema).
        assertThat(g.directReferences(PKG + ".fetchers.QueryFetchers"))
            .doesNotContain(PKG + ".util.NodeIdEncoder");
    }

    @Test
    void schemaClassWiresFetchersAndFacadeWiresSchema() {
        var g = CompileDependencyGraphBuilder.fromModel(filmQuerySchema(), PKG);

        assertThat(g.directReferences(PKG + ".schema.GraphitronSchema")).contains(
            PKG + ".fetchers.QueryFetchers",
            PKG + ".fetchers.FilmFetchers",
            PKG + ".schema.FilmType");
        assertThat(g.directReferences(PKG + ".Graphitron")).contains(
            PKG + ".schema.GraphitronSchema",
            PKG + ".schema.GraphitronContext");
    }

    /**
     * A two-type schema where {@code Film} has an inline {@code @reference} table field projecting
     * {@code Language}. The {@code filters} let a case add a {@code @nodeId}-decoding filter.
     */
    private static GraphitronSchema filmProjectsLanguageSchema(List<WhereFilter> filters) {
        var types = new LinkedHashMap<String, GraphitronType>();
        types.put("Film", new GraphitronType.TableType("Film", null, filmTable()));
        types.put("Language", new GraphitronType.TableType("Language", null, languageTable()));

        var fields = new LinkedHashMap<FieldCoordinates, GraphitronField>();
        fields.put(FieldCoordinates.coordinates("Film", "language"), filmLanguageField(filters));

        return new GraphitronSchema(types, fields);
    }

    @Test
    void typeClassReferencesInlineProjectionTargetProjection() {
        var g = CompileDependencyGraphBuilder.fromModel(filmProjectsLanguageSchema(List.of()), PKG);

        // The Film type class's $fields composes Language.$fields(...) inline for the @reference
        // field, so types.Film references types.Language (a same-package, nested-$L reference the
        // completeness oracle now sees).
        assertThat(g.directReferences(PKG + ".types.Film")).contains(PKG + ".types.Language");
    }

    @Test
    void typeClassBlanketsGraphitronClientException() {
        var g = CompileDependencyGraphBuilder.fromModel(filmProjectsLanguageSchema(List.of()), PKG);

        // Every emitted type class carries the blanket frozen-scaffold edge (a THROW-mode decode
        // helper on the class references it); the target is frozen, so the over-approximation is safe.
        assertThat(g.directReferences(PKG + ".types.Film")).contains(PKG + ".schema.GraphitronClientException");
        assertThat(g.directReferences(PKG + ".types.Language")).contains(PKG + ".schema.GraphitronClientException");
    }

    @Test
    void typeClassReachesNodeIdEncoderOnlyWhenAnInlineFilterDecodesANodeId() {
        var without = CompileDependencyGraphBuilder.fromModel(filmProjectsLanguageSchema(List.of()), PKG);
        assertThat(without.directReferences(PKG + ".types.Film"))
            .doesNotContain(PKG + ".util.NodeIdEncoder");

        var with = CompileDependencyGraphBuilder.fromModel(
            filmProjectsLanguageSchema(List.of(nodeIdDecodingFilter())), PKG);
        // A @nodeId-decoding filter lifts a decode helper onto types.Film, reaching NodeIdEncoder
        // precisely (the one per-type-growing singleton).
        assertThat(with.directReferences(PKG + ".types.Film")).contains(PKG + ".util.NodeIdEncoder");
    }

    @Test
    void typeClassReferencesGeneratedConditionClassOfInlineFilter() {
        // The inline filter emits a generated FilmConditions/LanguageConditions method call into the
        // $fields switch arm, so the host type class references that generated conditions unit.
        var g = CompileDependencyGraphBuilder.fromModel(
            filmProjectsLanguageSchema(List.of(nodeIdDecodingFilter())), PKG);
        assertThat(g.directReferences(PKG + ".types.Film"))
            .contains(PKG + ".conditions.LanguageConditions");
    }

    @Test
    void nestingHostedInlineFieldAttributesEdgeToOuterTypeClass() {
        // Film { details: FilmDetails { language: Language @reference } } — the nested plain object
        // shares Film's table context and emits into Film's $fields, so the Language projection edge
        // must attach to types.Film, not types.FilmDetails (which has no type class at all).
        var types = new LinkedHashMap<String, GraphitronType>();
        types.put("Film", new GraphitronType.TableType("Film", null, filmTable()));
        types.put("Language", new GraphitronType.TableType("Language", null, languageTable()));

        // The nested field's own parentTypeName is FilmDetails (its immediate SDL parent); the walk
        // must still attribute its edge to the outer Film type class, not re-derive from parentTypeName.
        var nestedLanguage = new ChildField.TableField(
            "FilmDetails", "language", null,
            languageReturn(), List.of(), List.of(), new OrderBySpec.None(), null, null);
        var nesting = new ChildField.NestingField(
            "Film", "details", null,
            new ReturnTypeRef.TableBoundReturnType("FilmDetails", filmTable(), new FieldWrapper.Single(true)),
            List.of(nestedLanguage));

        var fields = new LinkedHashMap<FieldCoordinates, GraphitronField>();
        fields.put(FieldCoordinates.coordinates("Film", "details"), nesting);

        var g = CompileDependencyGraphBuilder.fromModel(new GraphitronSchema(types, fields), PKG);

        // The projection edge attaches to the outer host (types.Film), NOT the nested field's own
        // parentTypeName (types.FilmDetails, which never hosts a $fields projection).
        assertThat(g.directReferences(PKG + ".types.Film")).contains(PKG + ".types.Language");
        assertThat(g.directReferences(PKG + ".types.FilmDetails")).doesNotContain(PKG + ".types.Language");
    }

    @Test
    void fetcherOwningNestingTypeRegistersFetcherNodeAndWiringEdges() {
        // Film { meta: FilmMeta { language: Language @reference } } — FilmMeta is a plain-object
        // nesting type owning a classified field, so it emits a FilmMetaFetchers class its FilmMetaType
        // schema-shape wires (FilmMetaType -> FilmMetaFetchers). The nested type is absent from
        // schema.types() and its fields from schema.fieldsOf(...), so the node must be registered by the
        // dedicated NestingField walk, not addTypeNodes/addFieldEdges.
        var types = new LinkedHashMap<String, GraphitronType>();
        types.put("Film", new GraphitronType.TableType("Film", null, filmTable()));
        types.put("Language", new GraphitronType.TableType("Language", null, languageTable()));
        // The NestingType entry gives the schema-shape node (FilmMetaType); schemaType is unread here.
        types.put("FilmMeta", new GraphitronType.NestingType("FilmMeta", null, null));

        var nestedLanguage = new ChildField.TableField(
            "FilmMeta", "language", null,
            languageReturn(), List.of(), List.of(), new OrderBySpec.None(), null, null);
        var nesting = new ChildField.NestingField(
            "Film", "meta", null,
            new ReturnTypeRef.TableBoundReturnType("FilmMeta", filmTable(), new FieldWrapper.Single(true)),
            List.of(nestedLanguage));

        var fields = new LinkedHashMap<FieldCoordinates, GraphitronField>();
        fields.put(FieldCoordinates.coordinates("Film", "meta"), nesting);

        var g = CompileDependencyGraphBuilder.fromModel(new GraphitronSchema(types, fields), PKG);

        // The nested fetcher node exists (registered by the NestingField walk).
        assertThat(g.nodes()).contains(PKG + ".fetchers.FilmMetaFetchers");
        // Its schema-shape wiring class references it (schemaShape -> ownFetcher), added by the
        // wiring loop once the node is present.
        assertThat(g.directReferences(PKG + ".schema.FilmMetaType"))
            .contains(PKG + ".fetchers.FilmMetaFetchers");
        // The schema class wires the nested fetcher (schemaClass -> fetcher).
        assertThat(g.directReferences(PKG + ".schema.GraphitronSchema"))
            .contains(PKG + ".fetchers.FilmMetaFetchers");
        // The blanket GraphitronContext edge covers the nested fetcher too (spot-checked).
        assertThat(g.directReferences(PKG + ".fetchers.FilmMetaFetchers"))
            .contains(PKG + ".schema.GraphitronContext");
        // No negative empty-nestedFields case: vacuous, since the classifier cannot produce one.
    }

    @Test
    void reverseEdgesMirrorForwardEdges() {
        var g = CompileDependencyGraphBuilder.fromModel(filmQuerySchema(), PKG);

        // FilmType is referenced by the schema class, so the schema class is among its dependents.
        assertThat(g.directDependents(PKG + ".types.Film"))
            .contains(PKG + ".fetchers.QueryFetchers");
        assertThat(g.directDependents(PKG + ".fetchers.FilmFetchers"))
            .contains(PKG + ".schema.GraphitronSchema");
    }
}
