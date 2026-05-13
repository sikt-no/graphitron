package no.sikt.graphitron.rewrite.catalog;

import graphql.language.SourceLocation;
import graphql.schema.FieldCoordinates;
import graphql.schema.idl.SchemaParser;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CatalogBuilder#buildSnapshot} unit tests against hand-crafted
 * {@link graphql.schema.idl.TypeDefinitionRegistry} fixtures. The
 * {@code snapshot-built-implies-clean-parse} invariant is upstream of
 * {@code buildSnapshot} (parse failures throw before we get here), so the
 * tests below assume a successful parse and verify the directive shape
 * round-trips faithfully.
 */
@UnitTier
class CatalogBuilderSnapshotTest {

    @Test
    void userDeclaredDirectiveLandsInTheSnapshot() {
        var registry = new SchemaParser().parse("""
            directive @auth(role: String!) on FIELD_DEFINITION
            type Query { x: Int }
            """);

        var snapshot = CatalogBuilder.buildSnapshot(registry);

        assertThat(snapshot.directives()).extracting(DirectiveShape::name).contains("auth");
        var auth = snapshot.directive("auth").orElseThrow();
        assertThat(auth.args()).hasSize(1);
        var roleArg = auth.args().get(0);
        assertThat(roleArg.name()).isEqualTo("role");
        assertThat(roleArg.type())
            .isInstanceOfSatisfying(TypeShape.Named.class, named -> {
                assertThat(named.typeName()).isEqualTo("String");
                assertThat(named.nonNull()).isTrue();
            });
    }

    @Test
    void listAndNonNullWrappingIsPreservedAsSealedShape() {
        var registry = new SchemaParser().parse("""
            directive @composite(ids: [ID!]!) on OBJECT
            type Query { x: Int }
            """);

        var snapshot = CatalogBuilder.buildSnapshot(registry);

        var composite = snapshot.directive("composite").orElseThrow();
        var idsArg = composite.args().get(0);
        // [ID!]! — outer non-null list of non-null ID.
        assertThat(idsArg.type())
            .isInstanceOfSatisfying(TypeShape.List.class, list -> {
                assertThat(list.nonNull()).isTrue();
                assertThat(list.inner())
                    .isInstanceOfSatisfying(TypeShape.Named.class, named -> {
                        assertThat(named.typeName()).isEqualTo("ID");
                        assertThat(named.nonNull()).isTrue();
                    });
            });
    }

    @Test
    void bundledDirectiveNamesPassThroughWithoutFilter() {
        // No producer-side filter: the resolver's bundled-shadows-snapshot
        // precedence handles collisions. The snapshot ships every directive
        // in the registry, including names that happen to coincide with
        // graphitron's bundled set.
        var registry = new SchemaParser().parse("""
            directive @table(name: String) on OBJECT
            type Query { x: Int }
            """);

        var snapshot = CatalogBuilder.buildSnapshot(registry);

        assertThat(snapshot.directive("table")).isPresent();
    }

    @Test
    void descriptionRoundTripsWhenPresent() {
        var registry = new SchemaParser().parse("""
            "Marker for federation entities"
            directive @key(fields: String!) on OBJECT
            type Query { x: Int }
            """);

        var snapshot = CatalogBuilder.buildSnapshot(registry);

        var key = snapshot.directive("key").orElseThrow();
        assertThat(key.description()).isPresent();
        assertThat(key.description().get()).contains("federation entities");
    }

    // ---- R157: per-type backing projection ----

    @Test
    void tableTypeProjectsToTableBacking() {
        var registry = new SchemaParser().parse("type Query { x: Int }");
        var schema = schemaOf("Film", new GraphitronType.TableType("Film", SourceLocation.EMPTY,
            new TableRef("film", "FILM", null, null, null, List.of())));

        var snapshot = CatalogBuilder.buildSnapshot(registry, schema, CompletionData.empty());

        assertThat(snapshot.typesByName().get("Film"))
            .isInstanceOfSatisfying(TypeBackingShape.TableBacking.class,
                t -> assertThat(t.tableName()).isEqualTo("film"));
    }

    @Test
    void tableInterfaceTypeProjectsToTableBacking() {
        // The existing FieldCompletionsTest's interfaceTypeWithTableDirectiveAlsoResolvesColumns
        // case pins this: an @table-bearing interface continues to behave
        // exactly like a TableType under the @field(name:) arm.
        var registry = new SchemaParser().parse("type Query { x: Int }");
        var schema = schemaOf("Movie", new GraphitronType.TableInterfaceType("Movie", SourceLocation.EMPTY,
            "kind",
            new TableRef("film", "FILM", null, null, null, List.of()),
            List.of()));

        var snapshot = CatalogBuilder.buildSnapshot(registry, schema, CompletionData.empty());

        assertThat(snapshot.typesByName().get("Movie"))
            .isInstanceOfSatisfying(TypeBackingShape.TableBacking.class,
                t -> assertThat(t.tableName()).isEqualTo("film"));
    }

    @Test
    void javaRecordTypeProjectsToRecordBackingFromExternalReferences() {
        var registry = new SchemaParser().parse("type Query { x: Int }");
        var schema = schemaOf("FilmCard",
            new GraphitronType.JavaRecordType("FilmCard", SourceLocation.EMPTY, "com.example.FilmCard"));
        var catalog = new CompletionData(
            List.of(), List.of(),
            List.of(new CompletionData.ExternalReference(
                "com.example.FilmCard", "com.example.FilmCard", "", List.of(),
                List.of(
                    new CompletionData.RecordComponent("filmId", "Integer"),
                    new CompletionData.RecordComponent("title", "String")
                )
            )),
            Map.of(), Map.of()
        );

        var snapshot = CatalogBuilder.buildSnapshot(registry, schema, catalog);

        assertThat(snapshot.typesByName().get("FilmCard"))
            .isInstanceOfSatisfying(TypeBackingShape.RecordBacking.class, r -> {
                assertThat(r.fqClassName()).isEqualTo("com.example.FilmCard");
                assertThat(r.components()).extracting(TypeBackingShape.MemberSlot::name)
                    .containsExactly("filmId", "title");
            });
    }

    @Test
    void pojoResultTypeBackedProjectsToPojoBackingWithBeanAccessors() {
        var registry = new SchemaParser().parse("type Query { x: Int }");
        var schema = schemaOf("FilmDto",
            new GraphitronType.PojoResultType.Backed("FilmDto", SourceLocation.EMPTY, "com.example.FilmDto"));
        var catalog = new CompletionData(
            List.of(), List.of(),
            List.of(new CompletionData.ExternalReference(
                "com.example.FilmDto", "com.example.FilmDto", "",
                List.of(
                    new CompletionData.Method("getFilmId", "Integer", "", List.of()),
                    new CompletionData.Method("getTitle", "String", "", List.of()),
                    new CompletionData.Method("isAvailable", "boolean", "", List.of()),
                    // Not a bean accessor — filtered out:
                    new CompletionData.Method("setFilmId",  "void", "",
                        List.of(new CompletionData.Parameter("id", "Integer", null, ""))),
                    new CompletionData.Method("toString", "String", "", List.of()),
                    // Bean-shape but argful — filtered out:
                    new CompletionData.Method("getOption", "String", "",
                        List.of(new CompletionData.Parameter("k", "String", null, "")))
                ),
                List.of()
            )),
            Map.of(), Map.of()
        );

        var snapshot = CatalogBuilder.buildSnapshot(registry, schema, catalog);

        assertThat(snapshot.typesByName().get("FilmDto"))
            .isInstanceOfSatisfying(TypeBackingShape.PojoBacking.class, p -> {
                assertThat(p.fqClassName()).isEqualTo("com.example.FilmDto");
                assertThat(p.accessors()).extracting(TypeBackingShape.MemberSlot::name)
                    .containsExactly("filmId", "title", "available");
            });
    }

    @Test
    void jooqTableRecordTypeProjectsToJooqRecordBackingCarryingTableName() {
        var registry = new SchemaParser().parse("type Query { x: Int }");
        var schema = schemaOf("FilmRecord",
            new GraphitronType.JooqTableRecordType("FilmRecord", SourceLocation.EMPTY,
                "no.sikt.example.tables.records.FilmRecord",
                new TableRef("film", "FILM", null, null, null, List.of())));

        var snapshot = CatalogBuilder.buildSnapshot(registry, schema, CompletionData.empty());

        assertThat(snapshot.typesByName().get("FilmRecord"))
            .isInstanceOfSatisfying(TypeBackingShape.JooqRecordBacking.WithTable.class, j -> {
                assertThat(j.fqClassName()).isEqualTo("no.sikt.example.tables.records.FilmRecord");
                assertThat(j.tableName()).isEqualTo("film");
            });
    }

    @Test
    void jooqRecordTypeWithoutTableProjectsToStandalone() {
        var registry = new SchemaParser().parse("type Query { x: Int }");
        var schema = schemaOf("CustomRecord",
            new GraphitronType.JooqRecordType("CustomRecord", SourceLocation.EMPTY,
                "no.sikt.example.records.CustomRecord"));

        var snapshot = CatalogBuilder.buildSnapshot(registry, schema, CompletionData.empty());

        assertThat(snapshot.typesByName().get("CustomRecord"))
            .isInstanceOfSatisfying(TypeBackingShape.JooqRecordBacking.Standalone.class,
                j -> assertThat(j.fqClassName()).isEqualTo("no.sikt.example.records.CustomRecord"));
    }

    @Test
    void rootTypeProjectsToNoBackingRootCategoryError() {
        var registry = new SchemaParser().parse("type Query { x: Int }");
        var schema = schemaOf("Query", new GraphitronType.RootType("Query", SourceLocation.EMPTY));

        var snapshot = CatalogBuilder.buildSnapshot(registry, schema, CompletionData.empty());

        assertThat(snapshot.typesByName().get("Query"))
            .isInstanceOf(TypeBackingShape.NoBacking.Root.class);
    }

    @Test
    void plainInterfaceProjectsToNoBackingUnclassifiedInterface() {
        var registry = new SchemaParser().parse("type Query { x: Int }");
        var schema = schemaOf("Shape",
            new GraphitronType.InterfaceType("Shape", SourceLocation.EMPTY, List.of()));

        var snapshot = CatalogBuilder.buildSnapshot(registry, schema, CompletionData.empty());

        assertThat(snapshot.typesByName().get("Shape"))
            .isInstanceOf(TypeBackingShape.NoBacking.UnclassifiedInterface.class);
    }

    @Test
    void pojoResultTypeNoBackingProjectsToUnbackedResult() {
        var registry = new SchemaParser().parse("type Query { x: Int }");
        var schema = schemaOf("Hint",
            new GraphitronType.PojoResultType.NoBacking("Hint", SourceLocation.EMPTY));

        var snapshot = CatalogBuilder.buildSnapshot(registry, schema, CompletionData.empty());

        assertThat(snapshot.typesByName().get("Hint"))
            .isInstanceOf(TypeBackingShape.NoBacking.UnbackedResult.class);
    }

    private static GraphitronSchema schemaOf(String name, GraphitronType type) {
        var types = new LinkedHashMap<String, GraphitronType>();
        types.put(name, type);
        return new GraphitronSchema(types, new LinkedHashMap<FieldCoordinates, no.sikt.graphitron.rewrite.model.GraphitronField>());
    }
}
