package no.sikt.graphitron.rewrite.capture;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.ScalarInfo;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_POLY_MEMBER;
import static no.sikt.graphitron.model.test.CapturedStore.withCapturedStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The coordinate census against graphql-java's own composition, which is what makes capture's
 * merge trustworthy rather than merely present.
 *
 * <p>Capture merges a type's declaration sites itself: the base definition, then each extension in
 * document order, first-wins on a coordinate two sites declare. graphql-java does the same merge
 * during assembly, from the same registry, and the two are independent implementations of one
 * specification rule. The obvious way to remove the risk of them disagreeing is to delete ours and
 * transcribe the assembled schema instead, and that is the wrong trade: the assembled schema does
 * not exist on the schemas an author is halfway through writing, and a census that vanishes with it
 * takes the editor's answers along. So both stand, and this is the anchor that holds them together:
 * wherever a document assembles, the coordinates capture claimed are exactly the coordinates
 * graphql-java composed.
 *
 * <p>Equality, not containment, and in both directions: a coordinate capture invents is a phantom
 * every relation hanging off it inherits, and a coordinate capture drops is a fact the store cannot
 * be asked about at all. Two populations are carved out of the comparison and each is carved out in
 * the open. Introspection is filtered on the assembled side, the {@code __} prefix being
 * graphql-java's own marker: those types are the engine's contribution to an executable schema, no
 * source declares them, and the census is of what sources declare. The specified scalars are
 * compared separately, by the case that states why the two censuses genuinely differ there.
 *
 * <p>The merge's own output, the ordinals, is pinned here too, by value rather than by density:
 * where the equality arms ask whether the merge reaches the same set, the ordinal arm asks whether
 * it reached it in the document's order.
 */
@PipelineTier
class SdlCoordinateCensusTest {

    private static final String INTROSPECTION_PREFIX = "__";

    @TempDir
    Path tmp;

    @Test
    @DisplayName("the type coordinates are the assembled schema's declared types")
    void typeCoordinatesMatchTheAssembledSchema() {
        withCapturedStore(tmp, MERGED, dsl ->
            assertThat(declared(typeCoordinates(dsl)))
                .as("capture's merge against graphql-java's, at the type grain")
                .isEqualTo(declared(assembledTypes(assemble()))));
    }

    /**
     * The one place the two censuses differ, stated rather than carved out silently. The registry
     * hands over all five specified scalars whether or not the document mentions them, and capture
     * transcribes what the registry holds; assembly keeps only the ones something references. The
     * store's reading is the useful one for a reader asking what a field may be typed as, and
     * nothing is merged into a specified scalar, so it is not a merge disagreement and the anchor
     * above compares the declared types.
     */
    @Test
    @DisplayName("every specified scalar is a coordinate, referenced or not")
    void specifiedScalarsAreCoordinatesWhetherReferencedOrNot() {
        withCapturedStore(tmp, MERGED, dsl -> {
            assertThat(typeCoordinates(dsl))
                .as("all five, though this document names three")
                .contains("String", "Boolean", "Int", "Float", "ID");
            assertThat(assembledTypes(assemble()))
                .as("assembly keeps the referenced ones only, which is the difference")
                .doesNotContain("Float", "ID");
        });
    }

    @Test
    @DisplayName("the field coordinates are the assembled schema's effective fields")
    void fieldCoordinatesMatchTheAssembledSchema() {
        withCapturedStore(tmp, MERGED, dsl ->
            assertThat(fieldCoordinates(dsl))
                .as("capture's merge against graphql-java's, at the field grain: a base body and "
                    + "three extensions of one type contribute one field set")
                .isEqualTo(assembledFields(assemble())));
    }

    /**
     * The argument grain, which is a genuine arm rather than a corollary of the field one: capture
     * numbers a type's arguments with one type-wide counter that runs across the declaration sites,
     * so a base field's arguments and an extension field's arguments land in the same sequence and
     * the grain has its own way to disagree with the composed set.
     *
     * <p>Output-field arguments only, on both sides. Input-object fields take none, and a directive
     * definition's arguments are a different relation with a different key.
     */
    @Test
    @DisplayName("the argument coordinates are the assembled schema's effective field arguments")
    void argumentCoordinatesMatchTheAssembledSchema() {
        withCapturedStore(tmp, MERGED, dsl ->
            assertThat(argumentCoordinates(dsl))
                .as("capture's merge against graphql-java's, at the argument grain: a base field's "
                    + "arguments and an extension field's belong to one type")
                .isEqualTo(assembledArguments(assemble())));
    }

    @Test
    @DisplayName("the enum value coordinates are the assembled schema's effective values")
    void enumValueCoordinatesMatchTheAssembledSchema() {
        withCapturedStore(tmp, MERGED, dsl ->
            assertThat(enumValueCoordinates(dsl))
                .as("capture's merge against graphql-java's, at the enum value grain")
                .isEqualTo(assembledEnumValues(assemble())));
    }

    // ===== The merge order the ordinals record =====

    /**
     * The ordinals the merge assigns, on the same fixture, stated as values rather than as a
     * density gate. Density says the sequence has no holes; only the values say the sequence is the
     * document's own order, and the fixture is built so the two answers differ: an extension of
     * {@code Film} is written above the base definition, so a walk that numbered sites in the order
     * it met them would put the base at 1 and pass every density check on the way.
     *
     * <p>Written out per type because the property is per type: the base definition is merge ordinal
     * 0 wherever it sits in the file, extensions follow in document order, and each element family's
     * ordinal continues across the site boundary rather than restarting at each site.
     *
     * <p>Every merge-ordered family is here, because every one of them is numbered by a counter
     * {@code SdlFactCapture.ElementOrdinals} holds per type and carries across the sites: the field,
     * argument, enum-value and union-member ordinals, and the per-name type-directive ordinals whose
     * counter is type-wide for exactly this reason. A family pinned only by density would pass on all
     * five with the merge order inverted.
     */
    @Test
    @DisplayName("the base definition is merge ordinal zero, and extensions follow in document order")
    void theMergeOrderIsTheDocumentsOrder() {
        withCapturedStore(tmp, MERGED, dsl -> {
            assertThat(dsl.select(GRAPHQL_TYPE_DECLARATION.MERGE_ORDINAL,
                    GRAPHQL_TYPE_DECLARATION.IS_EXTENSION)
                .from(GRAPHQL_TYPE_DECLARATION)
                .where(GRAPHQL_TYPE_DECLARATION.GRAPH_NAME.eq(CapturedStore.GRAPH))
                .and(GRAPHQL_TYPE_DECLARATION.TYPE_NAME.eq("Film"))
                .orderBy(GRAPHQL_TYPE_DECLARATION.MERGE_ORDINAL)
                .fetch(r -> r.value1() + (r.value2() ? ":extension" : ":base")))
                .as("the base is 0 though an extension of Film is written above it")
                .containsExactly("0:base", "1:extension", "2:extension", "3:extension");

            assertThat(fieldOrdinals(dsl, "Film"))
                .as("field ordinals run across the site boundary in merge order")
                .containsExactly("title=0", "rating=1", "released=2", "language=3");
            assertThat(fieldOrdinals(dsl, "Query"))
                .containsExactly("film=0", "films=1", "screen=2");
            assertThat(fieldOrdinals(dsl, "FilmFilter"))
                .as("an extended input object numbers the same way")
                .containsExactly("title=0", "released=1");

            assertThat(dsl.select(GRAPHQL_ENUM_VALUE.VALUE_NAME, GRAPHQL_ENUM_VALUE.ORDINAL)
                .from(GRAPHQL_ENUM_VALUE)
                .where(GRAPHQL_ENUM_VALUE.GRAPH_NAME.eq(CapturedStore.GRAPH))
                .and(GRAPHQL_ENUM_VALUE.TYPE_NAME.eq("Rating"))
                .orderBy(GRAPHQL_ENUM_VALUE.ORDINAL)
                .fetch(r -> r.value1() + "=" + r.value2()))
                .as("an extended enum numbers the same way")
                .containsExactly("G=0", "PG=1", "R=2");

            assertThat(dsl.select(GRAPHQL_ARGUMENT.FIELD_NAME, GRAPHQL_ARGUMENT.ARGUMENT_NAME,
                    GRAPHQL_ARGUMENT.ORDINAL)
                .from(GRAPHQL_ARGUMENT)
                .where(GRAPHQL_ARGUMENT.GRAPH_NAME.eq(CapturedStore.GRAPH))
                .and(GRAPHQL_ARGUMENT.TYPE_NAME.eq("Query"))
                .orderBy(GRAPHQL_ARGUMENT.ORDINAL)
                .fetch(r -> r.value1() + "." + r.value2() + "=" + r.value3()))
                .as("the argument counter is the type's, not the field's: the extension's argument "
                    + "continues the base field's sequence rather than restarting at 0")
                .containsExactly("film.title=0", "film.limit=1", "films.match=2");

            assertThat(dsl.select(GRAPHQL_POLY_MEMBER.MEMBER_TYPE_NAME, GRAPHQL_POLY_MEMBER.POSITION)
                .from(GRAPHQL_POLY_MEMBER)
                .where(GRAPHQL_POLY_MEMBER.GRAPH_NAME.eq(CapturedStore.GRAPH))
                .and(GRAPHQL_POLY_MEMBER.CONTAINER_NAME.eq("Screen"))
                .and(GRAPHQL_POLY_MEMBER.CONTAINER_KIND.eq("UNION"))
                .orderBy(GRAPHQL_POLY_MEMBER.POSITION)
                .fetch(r -> r.value1() + "=" + r.value2()))
                .as("the base union's members come first though its extension is written above it")
                .containsExactly("Film=0", "Poster=1", "Trailer=2");

            // MacroCaptureTest.repeatedApplicationsNumberAcrossSites already pins this family by
            // value; what it cannot pin is the out-of-order case, its own fixture writing the base
            // above the extension. This arm is that case and nothing more.
            assertThat(dsl.select(GRAPHQL_TYPE_DIRECTIVE_ARG.ORDINAL, GRAPHQL_TYPE_DIRECTIVE_ARG.VALUE_SDL)
                .from(GRAPHQL_TYPE_DIRECTIVE_ARG)
                .where(GRAPHQL_TYPE_DIRECTIVE_ARG.GRAPH_NAME.eq(CapturedStore.GRAPH))
                .and(GRAPHQL_TYPE_DIRECTIVE_ARG.TYPE_NAME.eq("Film"))
                .and(GRAPHQL_TYPE_DIRECTIVE_ARG.DIRECTIVE_NAME.eq("tag"))
                .orderBy(GRAPHQL_TYPE_DIRECTIVE_ARG.ORDINAL)
                .fetch(r -> r.value1() + "=" + r.value2()))
                .as("a repeatable type directive numbers across the sites, the base's application "
                    + "first though an extension carrying one is written above it")
                .containsExactly("0=\"base\"", "1=\"early\"", "2=\"late\"");
        });
    }

    private static List<String> fieldOrdinals(DSLContext dsl, String typeName) {
        return dsl.select(GRAPHQL_FIELD.FIELD_NAME, GRAPHQL_FIELD.ORDINAL)
            .from(GRAPHQL_FIELD)
            .where(GRAPHQL_FIELD.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .and(GRAPHQL_FIELD.TYPE_NAME.eq(typeName))
            .orderBy(GRAPHQL_FIELD.ORDINAL)
            .fetch(r -> r.value1() + "=" + r.value2());
    }

    /**
     * A document exercising every merge shape the rule has, and exercising each one out of document
     * order where the shape allows it: a type extended more than once with an extension arriving
     * before the base definition it extends, an input object and an enum extended too, an interface
     * whose implementor picks up a field from an extension, a union extended from above its own base,
     * arguments contributed by both a base field and an extension's field, and a repeatable type
     * directive applied on the base and on two extensions, one of them written above the base.
     *
     * <p>Every added type is reachable from a root, because the assembled side of the comparison
     * reads {@code getAllTypesAsList()} and an unreferenced declaration would make the two censuses
     * differ for a reason that has nothing to do with the merge. Only {@code String} and {@code Int}
     * are referenced among the specified scalars, which is what
     * {@link #specifiedScalarsAreCoordinatesWhetherReferencedOrNot} stands on.
     */
    private static final String MERGED = """
        directive @tag(name: String!) repeatable on OBJECT

        extend union Screen = Trailer

        extend type Film @tag(name: "early") { rating: Rating }

        interface Titled { title: String }

        type Query {
            film(title: String, limit: Int): Film
        }

        type Film implements Titled @tag(name: "base") {
            title: String
        }

        extend type Film @tag(name: "late") { released: Int }
        extend type Film { language: String }

        extend type Query { films(match: FilmFilter): [Film!], screen: Screen }

        union Screen = Film | Poster

        type Poster { caption: String }
        type Trailer { url: String }

        input FilmFilter { title: String }
        extend input FilmFilter { released: Int }

        enum Rating { G, PG }
        extend enum Rating { R }
        """;

    // ===== The store side =====

    private static Set<String> typeCoordinates(DSLContext dsl) {
        return new LinkedHashSet<>(dsl.select(GRAPHQL_TYPE_ELEMENT.TYPE_NAME)
            .from(GRAPHQL_TYPE_ELEMENT)
            .where(GRAPHQL_TYPE_ELEMENT.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .orderBy(GRAPHQL_TYPE_ELEMENT.TYPE_NAME)
            .fetch(0, String.class));
    }

    private static Set<String> fieldCoordinates(DSLContext dsl) {
        return new LinkedHashSet<>(dsl.select(GRAPHQL_FIELD_ELEMENT.TYPE_NAME,
                GRAPHQL_FIELD_ELEMENT.FIELD_NAME)
            .from(GRAPHQL_FIELD_ELEMENT)
            .where(GRAPHQL_FIELD_ELEMENT.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .orderBy(GRAPHQL_FIELD_ELEMENT.TYPE_NAME, GRAPHQL_FIELD_ELEMENT.FIELD_NAME)
            .fetch(r -> r.value1() + "." + r.value2()));
    }

    private static Set<String> argumentCoordinates(DSLContext dsl) {
        return new LinkedHashSet<>(dsl.select(GRAPHQL_ARGUMENT_ELEMENT.TYPE_NAME,
                GRAPHQL_ARGUMENT_ELEMENT.FIELD_NAME, GRAPHQL_ARGUMENT_ELEMENT.ARGUMENT_NAME)
            .from(GRAPHQL_ARGUMENT_ELEMENT)
            .where(GRAPHQL_ARGUMENT_ELEMENT.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .orderBy(GRAPHQL_ARGUMENT_ELEMENT.TYPE_NAME, GRAPHQL_ARGUMENT_ELEMENT.FIELD_NAME,
                GRAPHQL_ARGUMENT_ELEMENT.ARGUMENT_NAME)
            .fetch(r -> r.value1() + "." + r.value2() + "(" + r.value3() + ":)"));
    }

    private static Set<String> enumValueCoordinates(DSLContext dsl) {
        return new LinkedHashSet<>(dsl.select(GRAPHQL_ENUM_VALUE_ELEMENT.TYPE_NAME,
                GRAPHQL_ENUM_VALUE_ELEMENT.VALUE_NAME)
            .from(GRAPHQL_ENUM_VALUE_ELEMENT)
            .where(GRAPHQL_ENUM_VALUE_ELEMENT.GRAPH_NAME.eq(CapturedStore.GRAPH))
            .orderBy(GRAPHQL_ENUM_VALUE_ELEMENT.TYPE_NAME, GRAPHQL_ENUM_VALUE_ELEMENT.VALUE_NAME)
            .fetch(r -> r.value1() + "." + r.value2()));
    }

    /** The census with the specified scalars removed, which is the population the merge shapes. */
    private static Set<String> declared(Set<String> names) {
        return names.stream().filter(name -> !ScalarInfo.isGraphqlSpecifiedScalar(name))
            .collect(Collectors.toCollection(TreeSet::new));
    }

    // ===== The assembled side =====

    private GraphQLSchema assemble() {
        var assembly = SchemaAssembly.of(CapturedStore.registryOf(tmp, MERGED));
        assertThat(assembly)
            .as("the fixture has to assemble for this anchor to say anything")
            .isInstanceOf(SchemaAssembly.Assembled.class);
        return ((SchemaAssembly.Assembled) assembly).schema();
    }

    private static Set<String> assembledTypes(GraphQLSchema schema) {
        return authored(schema).map(GraphQLNamedType::getName)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> assembledFields(GraphQLSchema schema) {
        var names = new TreeSet<String>();
        authored(schema).forEach(type -> {
            switch (type) {
                case GraphQLFieldsContainer container -> container.getFieldDefinitions()
                    .forEach(field -> names.add(type.getName() + "." + field.getName()));
                case GraphQLInputObjectType input -> input.getFieldDefinitions()
                    .forEach(field -> names.add(type.getName() + "." + field.getName()));
                default -> { }
            }
        });
        return names;
    }

    /** Output-field arguments only, the population {@code graphql_argument_element} holds. */
    private static Set<String> assembledArguments(GraphQLSchema schema) {
        var names = new TreeSet<String>();
        authored(schema).forEach(type -> {
            if (type instanceof GraphQLFieldsContainer container) {
                container.getFieldDefinitions().forEach(field -> field.getArguments()
                    .forEach(argument -> names.add(
                        type.getName() + "." + field.getName() + "(" + argument.getName() + ":)")));
            }
        });
        return names;
    }

    private static Set<String> assembledEnumValues(GraphQLSchema schema) {
        var names = new TreeSet<String>();
        authored(schema).forEach(type -> {
            if (type instanceof GraphQLEnumType enumType) {
                enumType.getValues().forEach(value -> names.add(type.getName() + "." + value.getName()));
            }
        });
        return names;
    }

    private static Stream<GraphQLNamedType> authored(GraphQLSchema schema) {
        return schema.getAllTypesAsList().stream()
            .filter(type -> !type.getName().startsWith(INTROSPECTION_PREFIX));
    }
}
