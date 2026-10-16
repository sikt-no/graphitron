package no.sikt.graphitron.rewrite;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.schema.FieldCoordinates;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A filter field typed as a list of input objects classifies as one row-{@code IN} over its
 * element fields: {@link BodyParam.RowIn} at two or more cells and {@link BodyParam.In} at one,
 * reading the wire list through {@link CallSiteExtraction.ElementRows}. Before this, each element
 * field classified as an independent scalar {@code .eq} whose read treated the list as one
 * {@code Map}, so the filter was silently dropped.
 *
 * <p>An element the row cannot express is a located diagnostic at the offending element field's
 * own coordinate, carrying the occurrence path, and the consuming field keeps one consequence
 * rejection. Assertions are on the classified body params and the located diagnostics, never on
 * emitted code.
 *
 * <p>Every schema a test builds is also checked against the invariant the dropped list check
 * broke: no {@link CallSiteExtraction.NestedInputField} path descends through a list level,
 * except as the descent to an {@link CallSiteExtraction.ElementRows} leaf.
 */
@PipelineTier
class ListOfInputFilterPipelineTest {

    private static final String STUB = "no.sikt.graphitron.rewrite.TestConditionStub";
    private static final String CARRIER_STUB = "no.sikt.graphitron.rewrite.TestConditionCarrierStub";

    private static final String FILM_ACTOR = """
        type FilmActor @table(name: "film_actor") {
            actorId: Int! @field(name: "actor_id")
            filmId: Int! @field(name: "film_id")
        }
        """;

    /** Every schema built here, with its SDL, for the invariant check after all tests. */
    private static final List<Built> BUILT = new ArrayList<>();

    private record Built(String sdl, GraphitronSchema schema) {}

    private static GraphitronSchema build(String sdl) {
        var schema = TestSchemaHelper.buildSchema(sdl);
        synchronized (BUILT) {
            BUILT.add(new Built(sdl, schema));
        }
        return schema;
    }

    // ===== Positive classification =====

    @Test
    void twoCellElement_classifiesAsOneRowIn() {
        var schema = build("""
            input PairInput {
                actorId: Int! @field(name: "actor_id")
                filmId: Int! @field(name: "film_id")
            }
            input PairFilter { pairs: [PairInput] }
            """ + FILM_ACTOR + """
            type Query { filmActors(filter: PairFilter): [FilmActor!]! }
            """);

        var params = bodyParams(schema, "filmActors");
        assertThat(params).hasSize(1);
        var rowIn = (BodyParam.RowIn) params.get(0);
        assertThat(rowIn.name()).isEqualTo("pairs");
        assertThat(rowIn.columns()).extracting(c -> c.sqlName()).containsExactly("actor_id", "film_id");
        assertThat(rowIn.nonNull()).isFalse();
        var nif = (CallSiteExtraction.NestedInputField) rowIn.extraction();
        assertThat(nif.outerArgName()).isEqualTo("filter");
        assertThat(nif.path()).containsExactly("pairs");
        var rows = (CallSiteExtraction.ElementRows) nif.leaf();
        assertThat(rows.cells()).extracting(CallSiteExtraction.Cell::path)
            .containsExactly(List.of("actorId"), List.of("filmId"));
        assertThat(rows.cells()).extracting(CallSiteExtraction.Cell::leaf)
            .allMatch(CallSiteExtraction.Direct.class::isInstance);
    }

    @Test
    void oneCellElement_classifiesAsIn() {
        var schema = build("""
            input ActorInput { actorId: Int! @field(name: "actor_id") }
            input ActorFilter { actors: [ActorInput!]! }
            """ + FILM_ACTOR + """
            type Query { filmActors(filter: ActorFilter!): [FilmActor!]! }
            """);

        var in = (BodyParam.In) single(bodyParams(schema, "filmActors"));
        assertThat(in.column().sqlName()).isEqualTo("actor_id");
        assertThat(in.nonNull()).as("non-null argument and non-null list").isTrue();
        assertThat(CallSiteExtraction.ElementRows.of(in.extraction()).cells()).hasSize(1);
    }

    @Test
    void cellInsideNonListNestedInput_carriesTwoSegmentCellPath() {
        var schema = build("""
            input FilmPart { filmId: Int! @field(name: "film_id") }
            input PairInput {
                actorId: Int! @field(name: "actor_id")
                film: FilmPart!
            }
            input PairFilter { pairs: [PairInput!] }
            """ + FILM_ACTOR + """
            type Query { filmActors(filter: PairFilter): [FilmActor!]! }
            """);

        var rowIn = (BodyParam.RowIn) single(bodyParams(schema, "filmActors"));
        assertThat(CallSiteExtraction.ElementRows.of(rowIn.extraction()).cells())
            .extracting(CallSiteExtraction.Cell::path)
            .containsExactly(List.of("actorId"), List.of("film", "filmId"));
    }

    @Test
    void listTypedArgument_classifiesWithBareElementRows() {
        var schema = build("""
            input PairInput {
                actorId: Int! @field(name: "actor_id")
                filmId: Int! @field(name: "film_id")
            }
            """ + FILM_ACTOR + """
            type Query { filmActors(pairs: [PairInput!]): [FilmActor!]! }
            """);

        var rowIn = (BodyParam.RowIn) single(bodyParams(schema, "filmActors"));
        assertThat(rowIn.name()).isEqualTo("pairs");
        assertThat(rowIn.extraction()).isInstanceOf(CallSiteExtraction.ElementRows.class);
    }

    @Test
    void idTypedCell_readsThroughJooqConvert() {
        var schema = build("""
            input PairInput {
                actorId: ID! @field(name: "actor_id")
                filmId: Int! @field(name: "film_id")
            }
            input PairFilter { pairs: [PairInput!] }
            """ + FILM_ACTOR + """
            type Query { filmActors(filter: PairFilter): [FilmActor!]! }
            """);

        var rowIn = (BodyParam.RowIn) single(bodyParams(schema, "filmActors"));
        var cells = CallSiteExtraction.ElementRows.of(rowIn.extraction()).cells();
        assertThat(cells.get(0).leaf()).isInstanceOf(CallSiteExtraction.JooqConvert.class);
        assertThat(cells.get(1).leaf()).isInstanceOf(CallSiteExtraction.Direct.class);
    }

    @Test
    void elementFieldSlots_keepTheirShapeInTheColumnBindingLedger() {
        String sdl = """
            input PairInput {
                actorId: Int! @field(name: "actor_id")
                filmId: Int! @field(name: "film_id")
            }
            input PairFilter { pairs: [PairInput] }
            """ + FILM_ACTOR + """
            type Query { filmActors(filter: PairFilter): [FilmActor!]! }
            """;
        build(sdl);
        var bundle = TestSchemaHelper.buildBundle(sdl);
        var table = ((TableType) bundle.model().type("FilmActor")).table();
        var slots = bundle.columnBindings().slotsAt(FieldCoordinates.coordinates("Query", "filmActors"), table);
        assertThat(slots).extracting(ColumnBindingLedger.ColumnBoundSlot::slotName)
            .containsExactly("actorId", "filmId");
        assertThat(slots).extracting(ColumnBindingLedger.ColumnBoundSlot::extraction)
            .containsExactly(
                new CallSiteExtraction.NestedInputField("filter", List.of("pairs", "actorId")),
                new CallSiteExtraction.NestedInputField("filter", List.of("pairs", "filmId")));
    }

    // ===== Suppression =====

    @Test
    void enclosingOverride_suppressesTheListLevelWithoutRejection() {
        var schema = build("""
            input PairInput {
                actorId: Int @field(name: "actor_id")
                filmIds: [Int!] @field(name: "film_id")
            }
            input PairFilter { pairs: [PairInput] }
            """ + FILM_ACTOR + """
            type Query {
                filmActors(filter: PairFilter @condition(condition: {className: "%s", method: "lifterFieldCondition"}, override: true)): [FilmActor!]!
            }
            """.formatted(STUB));

        assertThat(schema.field("Query", "filmActors")).isInstanceOf(QueryField.QueryTableField.class);
        assertThat(bodyParams(schema, "filmActors")).isEmpty();
        assertThat(authored(schema, "filmActors")).hasSize(1);
    }

    @Test
    void overrideOnTheListField_receivesTheListAndSuppressesTheRowIn() {
        var schema = build("""
            input PairInput {
                actorId: Int @field(name: "actor_id")
                filmId: Int! @field(name: "film_id")
            }
            input PairFilter {
                pairs: [PairInput] @condition(condition: {className: "%s", method: "carrierMapList", argMapping: "input: pairs"}, override: true)
            }
            """.formatted(CARRIER_STUB) + FILM_ACTOR + """
            type Query { filmActors(filter: PairFilter): [FilmActor!]! }
            """);

        assertThat(schema.field("Query", "filmActors")).isInstanceOf(QueryField.QueryTableField.class);
        assertThat(bodyParams(schema, "filmActors")).isEmpty();
        var condition = single(authored(schema, "filmActors"));
        var arg = (ParamSource.Arg) condition.params().stream()
            .filter(p -> p.source() instanceof ParamSource.Arg).findFirst().orElseThrow().source();
        assertThat(arg.callSiteExtraction())
            .as("the method receives the whole list, read at the list field")
            .isEqualTo(new CallSiteExtraction.NestedInputField("filter", List.of("pairs")));
    }

    @Test
    void nonOverridingConditionOnTheListField_runsBesideTheRowIn() {
        var schema = build("""
            input PairInput {
                actorId: Int! @field(name: "actor_id")
                filmId: Int! @field(name: "film_id")
            }
            input PairFilter {
                pairs: [PairInput] @condition(condition: {className: "%s", method: "lifterFieldCondition"})
            }
            """.formatted(STUB) + FILM_ACTOR + """
            type Query { filmActors(filter: PairFilter): [FilmActor!]! }
            """);

        assertThat(single(bodyParams(schema, "filmActors"))).isInstanceOf(BodyParam.RowIn.class);
        assertThat(authored(schema, "filmActors")).hasSize(1);
    }

    @Test
    void nonOverridingConditionOnAListArgument_runsBesideTheRowIn() {
        var schema = build("""
            input PairInput {
                actorId: Int! @field(name: "actor_id")
                filmId: Int! @field(name: "film_id")
            }
            """ + FILM_ACTOR + """
            type Query {
                filmActors(pairs: [PairInput!] @condition(condition: {className: "%s", method: "lifterFieldCondition"})): [FilmActor!]!
            }
            """.formatted(STUB));

        assertThat(single(bodyParams(schema, "filmActors"))).isInstanceOf(BodyParam.RowIn.class);
        assertThat(authored(schema, "filmActors")).hasSize(1);
    }

    // ===== Rejections, one per case =====

    @Test
    void nullableElementField_isRejectedAtItsOwnCoordinate() {
        assertRejected("actorId: Int @field(name: \"actor_id\")", "PairInput.actorId", "pairs/actorId");
    }

    @Test
    void listTypedElementField_isRejected() {
        assertRejected("actorId: [Int!]! @field(name: \"actor_id\")", "PairInput.actorId", "pairs/actorId");
    }

    @Test
    void listOfInputsInsideAnElement_isRejected() {
        var schema = build("""
            input FilmPart { filmId: Int! @field(name: "film_id") }
            input PairInput {
                actorId: Int! @field(name: "actor_id")
                films: [FilmPart!]!
            }
            input PairFilter { pairs: [PairInput] }
            """ + FILM_ACTOR + """
            type Query { filmActors(filter: PairFilter): [FilmActor!]! }
            """);
        assertLocated(schema, "PairInput.films", "Query.filmActors(filter)/pairs/films");
    }

    @Test
    void nodeIdElementField_isRejected() {
        var schema = build("""
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId }
            input PairInput {
                actorId: Int! @field(name: "actor_id")
                film: ID! @nodeId(typeName: "Film")
            }
            input PairFilter { pairs: [PairInput] }
            """ + FILM_ACTOR + """
            type Query { filmActors(filter: PairFilter): [FilmActor!]! }
            """);
        assertLocated(schema, "PairInput.film", "Query.filmActors(filter)/pairs/film");
    }

    @Test
    void referenceElementField_isRejected() {
        assertRejected(
            "actorId: String! @field(name: \"first_name\") @reference(path: [{key: \"film_actor_actor_id_fkey\"}])",
            "PairInput.actorId", "pairs/actorId");
    }

    @Test
    void conditionOnAnElementField_isRejected() {
        assertRejected(
            "actorId: Int! @field(name: \"actor_id\") @condition(condition: {className: \"" + STUB
                + "\", method: \"lifterFieldCondition\"})",
            "PairInput.actorId", "pairs/actorId");
    }

    @Test
    void conditionOwnedElementField_isRejected() {
        assertRejected(
            "actorId: Int! @field(name: \"actor_id\") @condition(condition: {className: \"" + STUB
                + "\", method: \"lifterFieldCondition\"}, override: true)",
            "PairInput.actorId", "pairs/actorId");
    }

    @Test
    void unboundElementField_mintsTheCascadeVerdictOnce() {
        var schema = assertRejected("actorId: Int! @field(name: \"no_such_column\")",
            "PairInput.actorId", "pairs/actorId");
        assertThat(diagnosticsAt(schema, "PairInput.actorId")).hasSize(1);
    }

    @Test
    void conditionParameterDescendingPastTheList_isRejectedAtTheListField() {
        var schema = build("""
            input PairInput {
                actorId: Int! @field(name: "actor_id")
                filmId: Int! @field(name: "film_id")
            }
            input PairFilter {
                pairs: [PairInput] @condition(condition: {className: "%s", method: "filmIdArgCondition", argMapping: "filmId: pairs.actorId"})
            }
            """.formatted(STUB) + FILM_ACTOR + """
            type Query { filmActors(filter: PairFilter): [FilmActor!]! }
            """);
        assertLocated(schema, "PairFilter.pairs", "Query.filmActors(filter)/pairs");
    }

    @Test
    void fieldLevelConditionPathThroughTheList_doesNotClassify() {
        var schema = build("""
            input PairInput {
                actorId: Int! @field(name: "actor_id")
                filmId: Int! @field(name: "film_id")
            }
            input PairFilter { pairs: [PairInput] }
            """ + FILM_ACTOR + """
            type Query {
                filmActors(filter: PairFilter): [FilmActor!]!
                    @condition(condition: {className: "%s", method: "filmIdArgCondition", argMapping: "filmId: filter.pairs.actorId"})
            }
            """.formatted(STUB));
        assertThat(schema.field("Query", "filmActors")).isInstanceOf(UnclassifiedField.class);
    }

    // ===== The invariant, over every schema built above =====

    @AfterAll
    static void noNestedPathDescendsThroughAListLevel() {
        assertThat(BUILT).isNotEmpty();
        for (var built : BUILT) {
            var registry = new SchemaParser().parse(built.sdl());
            built.schema().fields().forEach((coordinate, field) -> {
                if (!(field instanceof SqlGeneratingField sql)) return;
                for (var extraction : extractionsOf(sql)) {
                    if (!(extraction instanceof CallSiteExtraction.NestedInputField nif)) continue;
                    assertThat(descendsThroughList(registry, coordinate, nif))
                        .as("%s: %s", coordinate, nif)
                        .isFalse();
                }
            });
        }
    }

    // ===== Helpers =====

    private static GraphitronSchema assertRejected(String elementField, String coordinate, String path) {
        var schema = build("""
            input PairInput {
                %s
                filmId: Int! @field(name: "film_id")
            }
            input PairFilter { pairs: [PairInput] }
            """.formatted(elementField) + FILM_ACTOR + """
            type Query { filmActors(filter: PairFilter): [FilmActor!]! }
            """);
        assertLocated(schema, coordinate, "Query.filmActors(filter)/" + path);
        return schema;
    }

    private static void assertLocated(GraphitronSchema schema, String coordinate, String occurrence) {
        var located = diagnosticsAt(schema, coordinate);
        assertThat(located).as("a located diagnostic at %s", coordinate).isNotEmpty();
        assertThat(located).anySatisfy(d -> {
            assertThat(d.location()).isNotNull();
            assertThat(d.message()).contains(occurrence);
        });
        assertThat(schema.field("Query", "filmActors")).isInstanceOf(UnclassifiedField.class);
    }

    private static List<ValidationError> diagnosticsAt(GraphitronSchema schema, String coordinate) {
        return schema.diagnostics().stream().filter(d -> coordinate.equals(d.coordinate())).toList();
    }

    private static List<BodyParam> bodyParams(GraphitronSchema schema, String fieldName) {
        var field = (QueryField.QueryTableField) schema.field("Query", fieldName);
        return field.filters().stream()
            .filter(GeneratedConditionFilter.class::isInstance)
            .map(GeneratedConditionFilter.class::cast)
            .flatMap(gcf -> gcf.bodyParams().stream())
            .toList();
    }

    private static List<ConditionFilter> authored(GraphitronSchema schema, String fieldName) {
        var field = (QueryField.QueryTableField) schema.field("Query", fieldName);
        return field.filters().stream()
            .filter(ConditionFilter.class::isInstance)
            .map(ConditionFilter.class::cast)
            .toList();
    }

    private static <T> T single(List<T> items) {
        assertThat(items).hasSize(1);
        return items.get(0);
    }

    private static List<CallSiteExtraction> extractionsOf(SqlGeneratingField field) {
        var out = new ArrayList<CallSiteExtraction>();
        for (var filter : field.filters()) {
            switch (filter) {
                case GeneratedConditionFilter gcf -> gcf.bodyParams().forEach(bp -> out.add(bp.extraction()));
                case ConditionFilter cf -> cf.params().forEach(p -> {
                    if (p.source() instanceof ParamSource.Arg arg) out.add(arg.callSiteExtraction());
                });
                default -> { }
            }
        }
        return out;
    }

    /**
     * Whether the descent from the outer argument through {@code nif.path()} steps into a list:
     * every level the path continues below must be a single input object. The last segment may
     * itself be a list, which is what an {@link CallSiteExtraction.ElementRows} leaf reads.
     */
    private static boolean descendsThroughList(TypeDefinitionRegistry registry, FieldCoordinates coordinate,
            CallSiteExtraction.NestedInputField nif) {
        var parent = registry.getTypeOrNull(coordinate.getTypeName(), ObjectTypeDefinition.class);
        if (parent == null) return false;
        var fieldDef = parent.getFieldDefinitions().stream()
            .filter(f -> f.getName().equals(coordinate.getFieldName())).findFirst().orElse(null);
        if (fieldDef == null) return false;
        var arg = fieldDef.getInputValueDefinitions().stream()
            .filter(a -> a.getName().equals(nif.outerArgName())).findFirst().orElse(null);
        if (arg == null) return false;
        Type<?> current = arg.getType();
        for (var segment : nif.path()) {
            if (isList(current)) return true;
            var input = registry.getTypeOrNull(namedType(current), InputObjectTypeDefinition.class);
            if (input == null) return false;
            var next = input.getInputValueDefinitions().stream()
                .filter(f -> f.getName().equals(segment)).findFirst().orElse(null);
            if (next == null) return false;
            current = next.getType();
        }
        return false;
    }

    private static boolean isList(Type<?> type) {
        return (type instanceof NonNullType nn ? nn.getType() : type) instanceof ListType;
    }

    private static String namedType(Type<?> type) {
        return switch (type) {
            case NonNullType nn -> namedType(nn.getType());
            case ListType list -> namedType(list.getType());
            case TypeName name -> name.getName();
            default -> throw new IllegalStateException("unexpected type " + type);
        };
    }
}
