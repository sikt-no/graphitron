package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.classpath.CompletionData;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_NODE_ID_DECODE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a decoded node id lands when the coordinate is captured for real rather than seeded row by
 * row: the fork between a table predicate and a Java slot, and which of the two slot destinations
 * answers.
 *
 * <p>The store tier already states this relation's algebra against seeded rows, in
 * {@code no.sikt.graphitron.model.intent.NodeIdDecodeDestinationTest}, and it states it far more
 * finely than this file does. What that tier cannot state is that a real capture writes the operands
 * the algebra reads. The slot fork spans three families that only a capture fills together, the
 * directive applications, the input-occurrence paths and the classpath census, and a fork that is
 * correct over seeded rows and finds nothing on a captured schema would be inert without a single
 * red assertion. So the cases here are few and coarse on purpose: one per destination, plus the
 * control that says the fork is a fork.
 *
 * <p>The SDL is captured and the census is hand-built, on {@code TypeBackingClassTest}'s terms: a
 * directive application is a fact capture produces, while a census row is a name, a descriptor and a
 * decomposed declared type, which is all these rules read. The record type named below is the test
 * catalog's own generated record for {@code film}, so the agreement the record destination turns on
 * is against the catalog rather than against a string this file chose.
 */
@PipelineTier
class NodeIdDecodeSlotCaptureTest {

    private static final String APP = "app-classes";
    private static final String SERVICE = "app.Films";
    private static final String FILM_RECORD =
        "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord";

    private static final String SDL = """
        type Film implements Node @table(name: "film") @node { id: ID! title: String }
        input ModifyFilmInput {
            filmId: ID! @nodeId(typeName: "Film")
            title: String @field(name: "title")
        }
        type Query {
            films(id: ID! @nodeId(typeName: "Film")): [Film!]!
            findFilm(id: ID! @nodeId(typeName: "Film")): String
                @service(service: {className: "app.Films", method: "find"})
            modifyFilm(in: ModifyFilmInput!): String
                @service(service: {className: "app.Films", method: "modify"})
        }
        """;

    @TempDir
    Path tmp;

    /**
     * The whole point of the fork, in one fixture. Three coordinates name the same node type and the
     * same key, and the destination differs because what receives the value differs: a table
     * predicate where nothing consumes the argument in Java, the film record where a service takes
     * one, and the key's single column where a service takes a value of that column's own type.
     *
     * <p>Asserted together rather than one case each because the claim is the difference. Any of the
     * three read alone would still pass with the fork asked at the wrong coordinate, or not asked.
     */
    @Test
    void eachCoordinateGetsTheDestinationItsOwnConsumerDecides() {
        withCapturedStore(dsl -> assertThat(destinations(dsl)).containsExactly(
            "Query.films(id) OWN_TABLE_COLUMNS 1",
            "Query.findFilm(id) SINGLE_KEY_COLUMN 1",
            "Query.modifyFilm(in)/filmId JOOQ_RECORD 1"));
    }

    private static List<String> destinations(DSLContext dsl) {
        var d = INTENT_NODE_ID_DECODE;
        return dsl.select(d.USE_SITE, d.DESTINATION, d.ARITY)
            .from(d)
            .orderBy(d.USE_SITE)
            .fetch()
            .map(row -> row.get(d.USE_SITE) + " " + row.get(d.DESTINATION) + " " + row.get(d.ARITY));
    }

    private void withCapturedStore(Consumer<DSLContext> body) {
        try (var store = CapturedStore.ofCatalog(tmp, "g", SDL, jooq(), census())) {
            body.accept(store.dsl());
        }
    }

    /**
     * One service class with two methods: one taking the generated film record, one taking the key
     * column's own Java type. The parameter names are the argument names the SDL spells, which is
     * the match the generator itself makes and the match the slot relation reads.
     */
    private static List<CompletionData.ExternalReference> census() {
        return List.of(new CompletionData.ExternalReference(SERVICE, SERVICE, "",
            List.of(
                method("modify", "(L" + FILM_RECORD.replace('.', '/') + ";)Ljava/lang/String;",
                    parameter("in", FILM_RECORD)),
                method("find", "(Ljava/lang/Integer;)Ljava/lang/String;",
                    parameter("id", "java.lang.Integer"))),
            List.of(), List.of(), "CLASS", APP, List.of()));
    }

    private static CompletionData.Method method(String name, String descriptor,
                                               CompletionData.Parameter parameter) {
        return new CompletionData.Method(name, "String", "", List.of(parameter), false, descriptor,
            "String", List.of(new CompletionData.TypeRef("", "java.lang.String", "NONE")));
    }

    private static CompletionData.Parameter parameter(String name, String declaredType) {
        return new CompletionData.Parameter(name, declaredType, "", "", declaredType,
            List.of(new CompletionData.TypeRef("", declaredType, "NONE")));
    }

    private static JooqCatalog jooq() {
        var ctx = testContext();
        return new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
    }
}
