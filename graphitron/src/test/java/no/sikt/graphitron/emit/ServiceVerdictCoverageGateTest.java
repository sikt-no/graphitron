package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * On every fact document a schema problem does not stop, every {@code @service} the store does not
 * emit ({@link ServiceEmission#verdict}) carries an error defect: one whose site is the field, one of
 * its arguments, an input field under them, or the named type of any of these. A service the store
 * accepts and does not emit, with nothing stated against it, is a generator that fails where the
 * author was told nothing. A service on a field nothing fetches, under a type no root
 * reaches, never runs and is not judged; the failure message lists them.
 *
 * <p>The services that do not hold yet are listed in {@code uncovered-services.txt}, each with the
 * node whose landing deletes its line, and the list is asserted exactly: a new one fails here, and so
 * does a line whose service now holds. The list only shrinks, and the walk's service path is deleted
 * only once it is empty; the test and its list go with it.
 *
 * <p>It captures every fact document, so it runs on demand rather than in every build: by a change to
 * service emission or to the store's service refusals, and by the commit deleting the walk's service
 * path, which runs it green with an empty list:
 * {@code mvn test -pl graphitron -Dtest=ServiceVerdictCoverageGateTest -Dgraphitron.flipGate=true}.
 */
@PipelineTier
@Tag("flip-gate")
@EnabledIfSystemProperty(named = "graphitron.flipGate", matches = "true",
    disabledReason = "captures every fact document; run with -Dgraphitron.flipGate=true")
class ServiceVerdictCoverageGateTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String ROSTER = "uncovered-services.txt";

    /**
     * An error defect covering the service: at the field, its arguments, the input fields under them or
     * the named type of any of these; or on a participant type of the service's occurrence, or a node
     * type a {@code @nodeId} under the field names, a union's members included, by the site's type or
     * coordinate. Any error stops the build; this says the author is pointed at the service's surface.
     */
    private static final String UNCOVERED = """
        SELECT 1 FROM graphitron_entry_defect_site d
          JOIN graphitron_defect_type t ON t.code = d.code AND t.severity = 'error'
         WHERE d.graph_name = ?
           AND d.coordinate IN (
               SELECT o.element_coordinate FROM graphitron_occurrence o
                WHERE o.graph_name = ? AND o.field_coordinate = ?
               UNION
               SELECT f.named_type FROM graphitron_occurrence o
                 JOIN graphitron_field f ON f.graph_name = o.graph_name AND f.coordinate = o.element_coordinate
                WHERE o.graph_name = ? AND o.field_coordinate = ?
               UNION
               SELECT a.named_type FROM graphitron_occurrence o
                 JOIN graphitron_argument a ON a.graph_name = o.graph_name AND a.coordinate = o.element_coordinate
                WHERE o.graph_name = ? AND o.field_coordinate = ?)
        UNION ALL
        SELECT 1 FROM graphitron_entry_defect_site d
          JOIN graphitron_defect_type t ON t.code = d.code AND t.severity = 'error'
          JOIN (SELECT p.participant_type AS type_name FROM graphitron_occurrence_participant p
                 WHERE p.graph_name = ? AND p.coordinate = ?
                UNION
                SELECT ne.node_type_ref FROM graphitron_occurrence o
                  JOIN graphql_directive_application na
                    ON na.graph_name = o.graph_name AND na.coordinate = o.element_coordinate
                   AND na.directive_name = 'nodeId'
                  JOIN graphitron_ast_node_id_entry ne
                    ON ne.graph_name = na.graph_name AND ne.source_name = na.source_name
                   AND ne.source_line = na.source_line AND ne.source_column = na.source_column
                 WHERE o.graph_name = ? AND o.field_coordinate = ?
                UNION
                SELECT um.member_type_name FROM graphitron_occurrence o
                  JOIN graphql_directive_application na
                    ON na.graph_name = o.graph_name AND na.coordinate = o.element_coordinate
                   AND na.directive_name = 'nodeId'
                  JOIN graphitron_ast_node_id_entry ne
                    ON ne.graph_name = na.graph_name AND ne.source_name = na.source_name
                   AND ne.source_line = na.source_line AND ne.source_column = na.source_column
                  JOIN graphql_union_member um ON um.graph_name = ne.graph_name AND um.union_name = ne.node_type_ref
                 WHERE o.graph_name = ? AND o.field_coordinate = ?) x
            ON x.type_name = d.type_name OR x.type_name = d.coordinate
         WHERE d.graph_name = ?
        """;

    /** Whether anything fetches the service's field: one no root reaches has no fetcher and never runs. */
    private static final String FETCHED = """
        SELECT 1 FROM graphitron_occurrence o
          JOIN graphitron_field f ON f.graph_name = o.graph_name AND f.coordinate = o.element_coordinate
          JOIN graphitron_fetcher fe
            ON fe.graph_name = f.graph_name AND fe.type_name = f.type_name AND fe.field_name = f.field_name
         WHERE o.graph_name = ? AND o.coordinate = ?
        """;

    @Test
    void everyServiceTheStoreDoesNotEmitCarriesAnErrorDefect() {
        var paths = FACTS.paths();
        FACTS.warm(paths);
        var found = new TreeSet<String>();
        var skipped = new ArrayList<String>();
        var unfetched = new TreeSet<String>();
        var reasons = new TreeSet<String>();
        for (var path : paths) {
            FACTS.read(path, graph -> {
                var dsl = graph.handle().dsl();
                var name = graph.handle().graphName();
                if (dsl.fetchExists(dsl.selectOne().from("graphql_schema_problem").where("graph_name = ?", name))) {
                    skipped.add(path);
                    return null;
                }
                // Each service's verdict on its own: one that throws is a generator that throws on it,
                // judged like any other verdict rather than hiding the graph's other services.
                // Reading the parts can itself throw where the store refused what a part asserts it
                // never meets; then every service on the graph has that throw for its verdict.
                ServiceEmission emission = null;
                String readThrew = null;
                Map<BuildQuery.Root, BuildQuery.Result> built = Map.of();
                try {
                    emission = ServiceEmission.read(graph.handle());
                    built = BuildQuery.read(graph.handle());
                } catch (RuntimeException e) {
                    readThrew = "THROWS " + e;
                }
                var refusals = built;
                var census = new TreeMap<String, String>();
                for (var coordinate : dsl.resultQuery("SELECT coordinate FROM graphitron_occurrence_producer"
                        + " WHERE graph_name = ? AND kind = 'SERVICE'", name).fetch(0, String.class)) {
                    String verdict = readThrew;
                    if (verdict == null) {
                        try {
                            verdict = emission.verdict(coordinate);
                        } catch (RuntimeException e) {
                            verdict = "THROWS " + e;
                        }
                    }
                    census.put(coordinate, verdict);
                }
                census.forEach((coordinate, verdict) -> {
                    if (ServiceEmission.EMITTED.equals(verdict)) {
                        return;
                    }
                    if (dsl.resultQuery(FETCHED, name, coordinate).fetchAny() == null) {
                        unfetched.add(path + " | " + coordinate);
                        return;
                    }
                    if (dsl.resultQuery(UNCOVERED, name, name, coordinate, name, coordinate, name, coordinate,
                            name, coordinate, name, coordinate, name, coordinate, name).fetchAny() == null) {
                        found.add(line(path, coordinate, verdict));
                        var side = dsl.resultQuery(FETCHED.replace("SELECT 1", "SELECT fe.side"), name, coordinate)
                            .fetchOne(0, String.class);
                        reasons.add(line(path, coordinate, verdict) + " | graphitron_fetcher side " + side);
                        refusals.forEach((root, result) -> {
                            if (root.callCoordinate().equals(coordinate)
                                    && result instanceof BuildQuery.Result.Refused refused) {
                                reasons.add(line(path, coordinate, verdict) + " | " + refused.reason());
                            }
                        });
                    }
                });
                return null;
            });
        }
        var roster = roster();
        assertThat(roster.values())
            .as("every listed service names the node whose landing deletes its line")
            .allSatisfy(node -> assertThat(node).isNotBlank());
        assertThat(found)
            .as("services the store does not emit with no error defect at the field, its arguments, their"
                + " input fields, their types, its participants or its node types (path | coordinate | verdict),"
                + " against " + ROSTER
                + "; a missing line is a new uncovered service, an extra line one that now holds: remove it."
                + " Skipped for schema problems: " + skipped.size() + "; not emitted and fetched by nothing,"
                + " so never run: " + unfetched + "; their fetcher rows and refused builds, with why: " + reasons)
            .containsExactlyElementsOf(roster.keySet());
    }

    private static String line(String path, String coordinate, String verdict) {
        return path + " | " + coordinate + " | " + verdict;
    }

    /** The roster's lines, {@code path | coordinate | verdict | node}, keyed by their first three parts. */
    private static Map<String, String> roster() {
        var out = new TreeMap<String, String>();
        try (InputStream in = ServiceVerdictCoverageGateTest.class.getResourceAsStream(ROSTER)) {
            var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (var raw : text.split("\n")) {
                var lineText = raw.strip();
                if (lineText.isEmpty() || lineText.startsWith("#")) {
                    continue;
                }
                var parts = lineText.split("\\s*\\|\\s*", 4);
                out.put(line(parts[0], parts[1], parts[2]), parts.length > 3 ? parts[3] : "");
            }
        } catch (IOException e) {
            throw new IllegalStateException(ROSTER, e);
        }
        return out;
    }
}
