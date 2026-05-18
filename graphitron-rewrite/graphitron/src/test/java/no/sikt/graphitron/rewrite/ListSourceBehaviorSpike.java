package no.sikt.graphitron.rewrite;

import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static graphql.schema.idl.TypeRuntimeWiring.newTypeWiring;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike: pin down graphql-java's behavior when a parent DataFetcher returns a list-shaped
 * source and the SDL parent type is non-list. Informs R178's bulk-DML alignment section:
 * is "the films DataFetcher iterates the list internally" the only honest encoding, or
 * does graphql-java auto-traverse the parent list and invoke the films DataFetcher per
 * element?
 *
 * <p>The five variants below cover the encodings R178 might lean on:
 * <ol>
 *   <li><b>V1</b> baseline: single payload, single child. env.getSource() == payload.</li>
 *   <li><b>V2</b> single payload, list child, DataFetcher returns an explicit list.
 *       Today's emit shape.</li>
 *   <li><b>V3</b> single payload, list child, DataFetcher returns env.getSource() directly
 *       (the payload is itself the list-shaped producer return). Tests whether graphql-java
 *       auto-unwraps a list-shaped source when the SDL field is list-typed.</li>
 *   <li><b>V4</b> list-at-root: Mutation field returns [Film!]! directly, no payload wrapper.
 *       Tests whether the wrapper is doing any work that the bare list shape can't.</li>
 *   <li><b>V5</b> payload-shaped source is a list, payload SDL is non-list. The "bulk DML
 *       producer leaves a list as env.getSource()" shape — what does the payload-level
 *       films DataFetcher actually receive?</li>
 * </ol>
 */
@UnitTier
public class ListSourceBehaviorSpike {

    /** Captures what each DataFetcher saw in env.getSource(). Reset per test. */
    private final List<Capture> captures = new ArrayList<>();

    private record Capture(String label, Object source, Object returned) {}

    private void capture(String label, DataFetchingEnvironment env, Object returned) {
        captures.add(new Capture(label, env.getSource(), returned));
    }

    // ---------------------------------------------------------------------
    // V1: baseline — single payload, single child
    // ---------------------------------------------------------------------

    @Test
    void v1_singlePayload_singleChild_filmDataFetcherSeesPayloadAsSource() {
        String sdl = """
            type Query { createFilm: CreateFilmPayload }
            type CreateFilmPayload { film: Film }
            type Film { id: String, title: String }
            """;
        Map<String, Object> filmRow = Map.of("id", "F1", "title", "Alpha");
        Map<String, Object> payload = Map.of("film", filmRow);

        GraphQL g = buildGraphQL(sdl, RuntimeWiring.newRuntimeWiring()
            .type(newTypeWiring("Query")
                .dataFetcher("createFilm", env -> { capture("Query.createFilm", env, payload); return payload; }))
            .type(newTypeWiring("CreateFilmPayload")
                .dataFetcher("film", env -> { capture("CreateFilmPayload.film", env, filmRow); return env.<Map<String,Object>>getSource().get("film"); }))
            .type(newTypeWiring("Film")
                .dataFetcher("id", env -> { capture("Film.id", env, "F1"); return env.<Map<String,Object>>getSource().get("id"); })
                .dataFetcher("title", env -> { capture("Film.title", env, "Alpha"); return env.<Map<String,Object>>getSource().get("title"); })
            )
            .build());

        ExecutionResult r = g.execute("{ createFilm { film { id title } } }");
        assertThat(r.getErrors()).isEmpty();

        Capture filmFetcher = byLabel("CreateFilmPayload.film");
        assertThat(filmFetcher.source()).isSameAs(payload);

        Capture filmIdFetcher = byLabel("Film.id");
        assertThat(filmIdFetcher.source()).isSameAs(filmRow);
    }

    // ---------------------------------------------------------------------
    // V2: single payload, list child, DataFetcher returns explicit list
    // ---------------------------------------------------------------------

    @Test
    void v2_singlePayload_listChild_explicitListReturn_eachFilmSeesElementAsSource() {
        String sdl = """
            type Query { createFilms: CreateFilmsPayload }
            type CreateFilmsPayload { films: [Film!]! }
            type Film { id: String, title: String }
            """;
        Map<String, Object> f1 = Map.of("id", "F1", "title", "Alpha");
        Map<String, Object> f2 = Map.of("id", "F2", "title", "Beta");
        List<Map<String, Object>> filmList = List.of(f1, f2);
        Map<String, Object> payload = Map.of("films", filmList);

        GraphQL g = buildGraphQL(sdl, RuntimeWiring.newRuntimeWiring()
            .type(newTypeWiring("Query")
                .dataFetcher("createFilms", env -> { capture("Query.createFilms", env, payload); return payload; }))
            .type(newTypeWiring("CreateFilmsPayload")
                .dataFetcher("films", env -> { capture("CreateFilmsPayload.films", env, filmList); return env.<Map<String,Object>>getSource().get("films"); }))
            .type(newTypeWiring("Film")
                .dataFetcher("id", env -> { Map<String,Object> src = env.getSource(); capture("Film.id[" + src.get("id") + "]", env, src.get("id")); return src.get("id"); })
                .dataFetcher("title", env -> { Map<String,Object> src = env.getSource(); capture("Film.title[" + src.get("id") + "]", env, src.get("title")); return src.get("title"); })
            )
            .build());

        ExecutionResult r = g.execute("{ createFilms { films { id title } } }");
        assertThat(r.getErrors()).isEmpty();

        Capture filmsFetcher = byLabel("CreateFilmsPayload.films");
        assertThat(filmsFetcher.source()).isSameAs(payload);

        Capture id1 = byLabel("Film.id[F1]");
        Capture id2 = byLabel("Film.id[F2]");
        assertThat(id1.source()).isSameAs(f1);
        assertThat(id2.source()).isSameAs(f2);
    }

    // ---------------------------------------------------------------------
    // V3: single payload AS list, list child returns env.getSource() identity
    //
    // The payload-level DataFetcher returns a List<Map> instead of a Map-with-list.
    // The films DataFetcher then returns env.getSource() (the list) directly.
    // Does graphql-java accept this and walk each element?
    // ---------------------------------------------------------------------

    @Test
    void v3_payloadSourceIsList_filmsDataFetcherReturnsSourceIdentity() {
        String sdl = """
            type Query { createFilms: CreateFilmsPayload }
            type CreateFilmsPayload { films: [Film!]! }
            type Film { id: String, title: String }
            """;
        Map<String, Object> f1 = Map.of("id", "F1", "title", "Alpha");
        Map<String, Object> f2 = Map.of("id", "F2", "title", "Beta");
        List<Map<String, Object>> producerOutput = List.of(f1, f2);

        GraphQL g = buildGraphQL(sdl, RuntimeWiring.newRuntimeWiring()
            .type(newTypeWiring("Query")
                .dataFetcher("createFilms", env -> { capture("Query.createFilms", env, producerOutput); return producerOutput; }))
            .type(newTypeWiring("CreateFilmsPayload")
                .dataFetcher("films", env -> { capture("CreateFilmsPayload.films", env, env.getSource()); return env.getSource(); }))
            .type(newTypeWiring("Film")
                .dataFetcher("id", env -> { Map<String,Object> src = env.getSource(); capture("Film.id[" + src.get("id") + "]", env, src.get("id")); return src.get("id"); })
                .dataFetcher("title", env -> { Map<String,Object> src = env.getSource(); capture("Film.title[" + src.get("id") + "]", env, src.get("title")); return src.get("title"); })
            )
            .build());

        ExecutionResult r = g.execute("{ createFilms { films { id title } } }");
        assertThat(r.getErrors()).as("v3 errors").isEmpty();

        // Payload-level films DataFetcher sees the producer's list as source.
        Capture filmsFetcher = byLabel("CreateFilmsPayload.films");
        assertThat(filmsFetcher.source()).isSameAs(producerOutput);

        // Each Film element DataFetcher should see its element as source.
        Capture id1 = byLabel("Film.id[F1]");
        Capture id2 = byLabel("Film.id[F2]");
        assertThat(id1.source()).isSameAs(f1);
        assertThat(id2.source()).isSameAs(f2);
    }

    // ---------------------------------------------------------------------
    // V4: list-at-root, no payload wrapper
    // ---------------------------------------------------------------------

    @Test
    void v4_listAtRoot_noPayloadWrapper_filmFetchersSeeEachElement() {
        String sdl = """
            type Query { createFilms: [Film!]! }
            type Film { id: String, title: String }
            """;
        Map<String, Object> f1 = Map.of("id", "F1", "title", "Alpha");
        Map<String, Object> f2 = Map.of("id", "F2", "title", "Beta");
        List<Map<String, Object>> producerOutput = List.of(f1, f2);

        GraphQL g = buildGraphQL(sdl, RuntimeWiring.newRuntimeWiring()
            .type(newTypeWiring("Query")
                .dataFetcher("createFilms", env -> { capture("Query.createFilms", env, producerOutput); return producerOutput; }))
            .type(newTypeWiring("Film")
                .dataFetcher("id", env -> { Map<String,Object> src = env.getSource(); capture("Film.id[" + src.get("id") + "]", env, src.get("id")); return src.get("id"); })
                .dataFetcher("title", env -> { Map<String,Object> src = env.getSource(); capture("Film.title[" + src.get("id") + "]", env, src.get("title")); return src.get("title"); })
            )
            .build());

        ExecutionResult r = g.execute("{ createFilms { id title } }");
        assertThat(r.getErrors()).isEmpty();

        Capture id1 = byLabel("Film.id[F1]");
        Capture id2 = byLabel("Film.id[F2]");
        assertThat(id1.source()).isSameAs(f1);
        assertThat(id2.source()).isSameAs(f2);
    }

    // ---------------------------------------------------------------------
    // V5: payload-shaped SDL, producer returns a list, no payload-level
    // DataFetcher intercepts. Each Film element DataFetcher under the
    // films field — does graphql-java auto-walk the list for the non-list
    // payload type?
    //
    // This is the load-bearing question for bulk DML under R178: if
    // graphql-java rejects this (or coerces in an unexpected way), the
    // emit shape MUST include a DataFetcher that consciously handles the
    // list at the payload->films boundary.
    // ---------------------------------------------------------------------

    @Test
    void v5_payloadNonList_producerReturnsList_observeBehavior() {
        String sdl = """
            type Query { createFilms: CreateFilmsPayload }
            type CreateFilmsPayload { films: [Film!]! }
            type Film { id: String, title: String }
            """;
        Map<String, Object> f1 = Map.of("id", "F1", "title", "Alpha");
        Map<String, Object> f2 = Map.of("id", "F2", "title", "Beta");
        List<Map<String, Object>> producerOutput = List.of(f1, f2);

        // No DataFetcher on CreateFilmsPayload.films — we rely on graphql-java's
        // default PropertyDataFetcher, which on a Map looks up "films" by key.
        // Since the source IS the list (not a Map), the property lookup will fail
        // OR graphql-java will exhibit some other behavior we want to observe.
        GraphQL g = buildGraphQL(sdl, RuntimeWiring.newRuntimeWiring()
            .type(newTypeWiring("Query")
                .dataFetcher("createFilms", env -> { capture("Query.createFilms", env, producerOutput); return producerOutput; }))
            .type(newTypeWiring("Film")
                .dataFetcher("id", env -> { Map<String,Object> src = env.getSource(); capture("Film.id[" + src.get("id") + "]", env, src.get("id")); return src.get("id"); })
                .dataFetcher("title", env -> { Map<String,Object> src = env.getSource(); capture("Film.title[" + src.get("id") + "]", env, src.get("title")); return src.get("title"); })
            )
            .build());

        ExecutionResult r = g.execute("{ createFilms { films { id title } } }");
        // Report only — pin the observed shape. The assertion records the actual outcome
        // for the Spec rather than dictating an expectation.
        System.out.println("=== V5 result ===");
        System.out.println("data:   " + r.getData());
        System.out.println("errors: " + r.getErrors());
        System.out.println("captures: " + captures);
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private static GraphQL buildGraphQL(String sdl, RuntimeWiring wiring) {
        TypeDefinitionRegistry registry = new SchemaParser().parse(sdl);
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(registry, wiring);
        return GraphQL.newGraphQL(schema).build();
    }

    private Capture byLabel(String label) {
        return captures.stream()
            .filter(c -> c.label().equals(label))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no capture for label " + label
                + "; captures so far: " + captures.stream().map(Capture::label).toList()));
    }
}
