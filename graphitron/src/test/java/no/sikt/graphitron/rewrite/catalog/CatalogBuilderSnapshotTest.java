package no.sikt.graphitron.rewrite.catalog;

import graphql.language.SourceLocation;
import graphql.schema.FieldCoordinates;
import graphql.schema.idl.SchemaParser;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CatalogBuilder#buildSnapshot} unit tests against hand-crafted
 * {@link graphql.schema.idl.TypeDefinitionRegistry} fixtures. Parse failures
 * throw upstream of {@code buildSnapshot}, so the tests below assume a successful
 * parse and verify the directive shape round-trips faithfully.
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

    // ---- per-type backing projection ----

    @Test
    void tableTypeProjectsToTableBacking() {
        var registry = new SchemaParser().parse("type Query { x: Int }");
        var schema = schemaOf("Film", new GraphitronType.TableType("Film", SourceLocation.EMPTY,
            new TableRef("film", "FILM", null, null, null, List.of(), List.of())));

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
            new TableRef("film", "FILM", null, null, null, List.of(), List.of()),
            List.of()));

        var snapshot = CatalogBuilder.buildSnapshot(registry, schema, CompletionData.empty());

        assertThat(snapshot.typesByName().get("Movie"))
            .isInstanceOfSatisfying(TypeBackingShape.TableBacking.class,
                t -> assertThat(t.tableName()).isEqualTo("film"));
    }

    /**
     * The shape names the backing class and nothing else. What the class offers a member name is a
     * fact about the class, so the components no longer ride the projection and the rule that reads
     * them is pinned where it lives, over real classfiles, by
     * {@code no.sikt.graphitron.rewrite.derive.ClassMemberSlotTest}.
     */
    @Test
    void javaRecordTypeProjectsToRecordBackingNamingTheClass() {
        var registry = new SchemaParser().parse("type Query { x: Int }");
        var schema = schemaOf("FilmCard",
            new GraphitronType.JavaRecordType("FilmCard", SourceLocation.EMPTY, "com.example.FilmCard"));

        var snapshot = CatalogBuilder.buildSnapshot(registry, schema, CompletionData.empty());

        assertThat(snapshot.typesByName().get("FilmCard"))
            .isEqualTo(new TypeBackingShape.RecordBacking("com.example.FilmCard"));
    }

    /**
     * The same for a plain class, and the same reason: the bean rule that turns its public methods
     * into member names is the member-slot relation's, not this projection's, so no classpath census
     * is needed to project the shape at all.
     */
    @Test
    void pojoResultTypeBackedProjectsToPojoBackingNamingTheClass() {
        var registry = new SchemaParser().parse("type Query { x: Int }");
        var schema = schemaOf("FilmDto",
            new GraphitronType.PojoResultType.Backed("FilmDto", SourceLocation.EMPTY, "com.example.FilmDto"));

        var snapshot = CatalogBuilder.buildSnapshot(registry, schema, CompletionData.empty());

        assertThat(snapshot.typesByName().get("FilmDto"))
            .isEqualTo(new TypeBackingShape.PojoBacking("com.example.FilmDto"));
    }

    @Test
    void jooqTableRecordTypeProjectsToJooqRecordBackingCarryingTableName() {
        var registry = new SchemaParser().parse("type Query { x: Int }");
        var schema = schemaOf("FilmRecord",
            new GraphitronType.JooqTableRecordType("FilmRecord", SourceLocation.EMPTY,
                "no.sikt.example.tables.records.FilmRecord",
                new TableRef("film", "FILM", null, null, null, List.of(), List.of())));

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

    // ---- type-definition source locations ----

    @Test
    void typeDefinitionLocationsCarryUserTypesAndScalarsButNotBuiltinsOrBundled(@TempDir Path dir) throws Exception {
        // Drive the production parse path (MultiSourceReader + trackData) so SourceLocations
        // carry a real sourceName; SchemaParser.parse(String) would leave it null. The loader
        // also injects the bundled directives.graphqls, whose inputs/enums must be filtered.
        var schemaFile = dir.resolve("schema.graphqls");
        Files.writeString(schemaFile, """
            type Query {
              film: Film
            }

            type Film {
              title: String
            }

            scalar DateTime
            """);

        var snapshot = CatalogBuilder.buildSnapshot(RewriteSchemaLoader.load(List.of(SchemaSource.file(schemaFile))));
        var locations = snapshot.typeDefinitionLocations();

        // User-authored object type: keyed by name, pointing at its declaring file at the
        // declaration start, reduced to 0-based LSP coordinates.
        var film = snapshot.typeDefinitionLocation("Film").orElseThrow();
        assertThat(film.uri()).isEqualTo("file://" + schemaFile);
        assertThat(film.line()).isEqualTo(4);   // "type Film" is the 5th line (0-based 4)
        assertThat(film.column()).isZero();

        // User-declared scalar is carried too.
        assertThat(locations).containsKey("DateTime");

        // Built-in scalars (null sourceName) and bundled-directive inputs/enums are dropped
        // rather than emitted as dead file:// URIs.
        assertThat(locations).doesNotContainKeys("String", "Int", "ErrorHandler", "MutationType");
    }

    private static GraphitronSchema schemaOf(String name, GraphitronType type) {
        var types = new LinkedHashMap<String, GraphitronType>();
        types.put(name, type);
        return new GraphitronSchema(types, new LinkedHashMap<FieldCoordinates, no.sikt.graphitron.rewrite.model.GraphitronField>());
    }
}
