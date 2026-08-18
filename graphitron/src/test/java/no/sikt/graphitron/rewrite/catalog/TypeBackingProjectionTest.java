package no.sikt.graphitron.rewrite.catalog;

import graphql.language.SourceLocation;
import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link CatalogBuilder#projectTypesByName} makes of each classified type, over hand-crafted
 * {@link GraphitronSchema} fixtures. The snapshot carried this map to the language server until
 * every surface reading it asked the store instead; what the walk bound each type to is still
 * transcribed into the store as the shadow the backing derivation differs against, so the
 * projection is the subject and the snapshot is no longer its channel.
 */
@UnitTier
class TypeBackingProjectionTest {

    // ---- per-type backing projection ----

    @Test
    void tableTypeProjectsToTableBacking() {
        var schema = schemaOf("Film", new GraphitronType.TableType("Film", SourceLocation.EMPTY,
            new TableRef("film", "FILM", null, null, null, List.of(), List.of())));

        var backing = CatalogBuilder.projectTypesByName(schema);

        assertThat(backing.get("Film"))
            .isInstanceOfSatisfying(TypeBackingShape.TableBacking.class,
                t -> assertThat(t.tableName()).isEqualTo("film"));
    }

    @Test
    void tableInterfaceTypeProjectsToTableBacking() {
        // The existing FieldCompletionsTest's interfaceTypeWithTableDirectiveAlsoResolvesColumns
        // case pins this: an @table-bearing interface continues to behave
        // exactly like a TableType under the @field(name:) arm.
        var schema = schemaOf("Movie", new GraphitronType.TableInterfaceType("Movie", SourceLocation.EMPTY,
            "kind",
            new TableRef("film", "FILM", null, null, null, List.of(), List.of()),
            List.of()));

        var backing = CatalogBuilder.projectTypesByName(schema);

        assertThat(backing.get("Movie"))
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
        var schema = schemaOf("FilmCard",
            new GraphitronType.JavaRecordType("FilmCard", SourceLocation.EMPTY, "com.example.FilmCard"));

        var backing = CatalogBuilder.projectTypesByName(schema);

        assertThat(backing.get("FilmCard"))
            .isEqualTo(new TypeBackingShape.RecordBacking("com.example.FilmCard"));
    }

    /**
     * The same for a plain class, and the same reason: the bean rule that turns its public methods
     * into member names is the member-slot relation's, not this projection's, so no classpath census
     * is needed to project the shape at all.
     */
    @Test
    void pojoResultTypeBackedProjectsToPojoBackingNamingTheClass() {
        var schema = schemaOf("FilmDto",
            new GraphitronType.PojoResultType.Backed("FilmDto", SourceLocation.EMPTY, "com.example.FilmDto"));

        var backing = CatalogBuilder.projectTypesByName(schema);

        assertThat(backing.get("FilmDto"))
            .isEqualTo(new TypeBackingShape.PojoBacking("com.example.FilmDto"));
    }

    @Test
    void jooqTableRecordTypeProjectsToJooqRecordBackingCarryingTableName() {
        var schema = schemaOf("FilmRecord",
            new GraphitronType.JooqTableRecordType("FilmRecord", SourceLocation.EMPTY,
                "no.sikt.example.tables.records.FilmRecord",
                new TableRef("film", "FILM", null, null, null, List.of(), List.of())));

        var backing = CatalogBuilder.projectTypesByName(schema);

        assertThat(backing.get("FilmRecord"))
            .isInstanceOfSatisfying(TypeBackingShape.JooqRecordBacking.WithTable.class, j -> {
                assertThat(j.fqClassName()).isEqualTo("no.sikt.example.tables.records.FilmRecord");
                assertThat(j.tableName()).isEqualTo("film");
            });
    }

    @Test
    void jooqRecordTypeWithoutTableProjectsToStandalone() {
        var schema = schemaOf("CustomRecord",
            new GraphitronType.JooqRecordType("CustomRecord", SourceLocation.EMPTY,
                "no.sikt.example.records.CustomRecord"));

        var backing = CatalogBuilder.projectTypesByName(schema);

        assertThat(backing.get("CustomRecord"))
            .isInstanceOfSatisfying(TypeBackingShape.JooqRecordBacking.Standalone.class,
                j -> assertThat(j.fqClassName()).isEqualTo("no.sikt.example.records.CustomRecord"));
    }

    @Test
    void rootTypeProjectsToNoBackingRootCategoryError() {
        var schema = schemaOf("Query", new GraphitronType.RootType("Query", SourceLocation.EMPTY));

        var backing = CatalogBuilder.projectTypesByName(schema);

        assertThat(backing.get("Query"))
            .isInstanceOf(TypeBackingShape.NoBacking.Root.class);
    }

    @Test
    void plainInterfaceProjectsToNoBackingUnclassifiedInterface() {
        var schema = schemaOf("Shape",
            new GraphitronType.InterfaceType("Shape", SourceLocation.EMPTY, List.of()));

        var backing = CatalogBuilder.projectTypesByName(schema);

        assertThat(backing.get("Shape"))
            .isInstanceOf(TypeBackingShape.NoBacking.UnclassifiedInterface.class);
    }

    private static GraphitronSchema schemaOf(String name, GraphitronType type) {
        var types = new LinkedHashMap<String, GraphitronType>();
        types.put(name, type);
        return new GraphitronSchema(types, new LinkedHashMap<FieldCoordinates, no.sikt.graphitron.rewrite.model.GraphitronField>());
    }
}
