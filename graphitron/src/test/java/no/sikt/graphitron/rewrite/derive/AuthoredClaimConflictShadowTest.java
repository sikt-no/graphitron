package no.sikt.graphitron.rewrite.derive;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.capture.FactCapture;
import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedCorpus;
import no.sikt.graphitron.rewrite.classifieddsl.ClassifiedDsl;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_CLAIM_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shadow agreement for {@code intent_authored_claim_conflict}: the view's rows, derived the
 * way the cutover will derive {@link ValidationError} values from them, against the surviving
 * Java reduction ({@link AuthoredClaimConflicts}), byte-identical in coordinate, message and
 * location. The corpus sweep runs every classified example as its own graph in one store; the
 * targeted fixtures then pin each verdict population non-empty (a plain conflict at both
 * grains, the deferred routine-plus-lookup pair, the three-claim fixed naming order, an
 * undecoded presence-arm claim), so the sweep cannot go vacuous on a conflict-free corpus. The
 * domain-gate fixture pins the join side of the gate: an empty reach relation mutes the view
 * exactly as an empty {@link ClaimDomain} mutes the reduction.
 *
 * <p>This anchor re-aims at the cutover: once the report projects the view for this family,
 * agreement against the Java reduction would be the view compared against a projection of
 * itself, so the expectation moves to one the view does not produce (the corpus-level
 * expectations {@code AuthoredClaimConflictsTest} states per fixture are the nearest shape).
 */
@PipelineTier
class AuthoredClaimConflictShadowTest {

    private static final String GRAPH = "AuthoredClaimConflictShadowTest";
    private static final String SERVICE_STUB = "no.sikt.graphitron.rewrite.TestServiceStub";
    private static final String DEFERRED_MESSAGE =
        "@routine with @lookupKey on a root field classifies but does not emit yet";

    @TempDir
    Path tmp;

    // ===== The corpus sweep =====

    @Test
    @DisplayName("the view agrees with the Java reduction over the corpus, byte-identical")
    void viewAgreesWithTheJavaReductionOverTheCorpus() throws IOException {
        try (var store = GraphitronModelStore.open()) {
            for (ClassifiedCorpus.Example example : ClassifiedCorpus.examples()) {
                String full = ClassifiedDsl.PRELUDE + "\n" + example.sdl();
                if (!full.contains("interface Node")) {
                    full += "\ninterface Node { id: ID! }\n";
                }
                Path dir = Files.createDirectories(tmp.resolve(example.id()));
                var registry = RewriteSchemaLoader.load(List.of(write(dir, full).toString()));
                FactCapture.capture(store.dsl(), new FactCapture.GraphIdentity(example.id(), dir), registry);

                var domain = ClaimDomain.of(TestSchemaHelper.buildSchema(full));
                ClaimDomainRows.write(store.dsl(), example.id(), domain);

                assertThat(render(viewErrors(store.dsl(), example.id())))
                    .as("intent_authored_claim_conflict vs AuthoredClaimConflicts (%s)", example.id())
                    .isEqualTo(render(AuthoredClaimConflicts.detect(store.dsl(), example.id(), domain)
                        .violations()));
            }
        }
    }

    // ===== The targeted verdict populations =====

    @Test
    @DisplayName("both grains, the deferral, and the three-claim order agree, non-vacuously")
    void verdictPopulationsAgreeAndAreNonEmpty() {
        var sdl = """
            type Film @table(name: "film") @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) {
                id: String @service(service: {className: "%s", method: "get"}) @nodeId
            }
            type Query {
                film(id: ID @lookupKey): Film @routine(name: "film_fn")
                films(id: ID @lookupKey): [Film]
                    @service(service: {className: "%s", method: "get"})
                    @routine(name: "films_fn")
            }
            """.formatted(SERVICE_STUB, SERVICE_STUB);
        withDetectedStore(sdl, (dsl, domain) -> {
            var expected = AuthoredClaimConflicts.detect(dsl, GRAPH, domain).violations();
            assertThat(expected)
                .extracting(ValidationError::message)
                .containsExactly(
                    "Type 'Film': @table, @error are mutually exclusive",
                    "Field 'Film.id': @service, @nodeId are mutually exclusive",
                    "Field 'Query.film': " + DEFERRED_MESSAGE,
                    "Field 'Query.films': @service, @lookupKey, @routine are mutually exclusive");
            assertThat(render(viewErrors(dsl, GRAPH))).isEqualTo(render(expected));
        });
    }

    @Test
    @DisplayName("an undecoded presence-arm claim still conflicts through the view")
    void presenceArmClaimsAgree() {
        // @mutation without its required typeName never assembles; the presence arm keeps the
        // coordinate claiming on both sides. The domain is hand-built because a schema this
        // broken has no walked model to project one from.
        var sdl = """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation {
                createFilm: Film @service(service: {className: "%s", method: "run"}) @mutation
            }
            """.formatted(SERVICE_STUB);
        try (var store = GraphitronModelStore.open()) {
            capture(store.dsl(), sdl);
            var domain = new ClaimDomain(Set.of(), Set.of(
                graphql.schema.FieldCoordinates.coordinates("Mutation", "createFilm")));
            ClaimDomainRows.write(store.dsl(), GRAPH, domain);
            var expected = AuthoredClaimConflicts.detect(store.dsl(), GRAPH, domain).violations();
            assertThat(expected).hasSize(1);
            assertThat(render(viewErrors(store.dsl(), GRAPH))).isEqualTo(render(expected));
        }
    }

    @Test
    @DisplayName("an empty reach relation mutes the view exactly as the empty domain mutes the reduction")
    void theDomainGateIsAJoin() {
        var sdl = """
            type Film @table(name: "film") {
                id: String @service(service: {className: "%s", method: "get"}) @nodeId
            }
            type Query { film: Film }
            """.formatted(SERVICE_STUB);
        try (var store = GraphitronModelStore.open()) {
            capture(store.dsl(), sdl);
            var empty = new ClaimDomain(Set.of(), Set.of());
            ClaimDomainRows.write(store.dsl(), GRAPH, empty);
            assertThat(AuthoredClaimConflicts.detect(store.dsl(), GRAPH, empty).violations()).isEmpty();
            assertThat(viewErrors(store.dsl(), GRAPH))
                .as("a coordinate outside the reach rows must not surface, however conflicted its claims")
                .isEmpty();
        }
    }

    // ===== Helpers =====

    /**
     * The view's rows derived into {@link ValidationError} values the way the cutover derives
     * them: the verdict picks the rejection arm, the canonical directives render splits back
     * into the conflict's naming list, and the coordinate prefix comes from the grain.
     */
    private static List<ValidationError> viewErrors(DSLContext dsl, String graphName) {
        return dsl.selectFrom(INTENT_AUTHORED_CLAIM_CONFLICT)
            .where(INTENT_AUTHORED_CLAIM_CONFLICT.GRAPH_NAME.eq(graphName))
            .fetch(row -> {
                Rejection rejection;
                if ("DEFERRED".equals(row.getVerdict())) {
                    rejection = Rejection.deferred(DEFERRED_MESSAGE);
                } else {
                    List<String> names = List.of(row.getDirectives().split(","));
                    rejection = Rejection.directiveConflict(names, names.stream()
                        .map(name -> "@" + name)
                        .collect(Collectors.joining(", ")) + " are mutually exclusive");
                }
                SourceLocation location = row.getSourceLine() == null || row.getSourceColumn() == null
                    ? null
                    : new SourceLocation(row.getSourceLine(), row.getSourceColumn(), row.getSourceName());
                return row.getFieldName() == null
                    ? ValidationError.forType(row.getTypeName(), rejection, location)
                    : ValidationError.forField(row.getTypeName() + "." + row.getFieldName(),
                        rejection, location);
            });
    }

    /**
     * A stable, order-free rendering carrying everything the report projects: coordinate, the
     * rejection's typed arm, the exact message, and the full location, so a drifted position
     * fails as loudly as a drifted message. Sorted, because a view has no row order to pin.
     */
    private static List<String> render(List<ValidationError> errors) {
        return errors.stream()
            .map(error -> {
                var location = error.location();
                String rendered = location == null ? "unlocated"
                    : location.getSourceName() + ":" + location.getLine() + ":" + location.getColumn();
                String arm = error.rejection() instanceof Rejection.Deferred ? "DEFERRED" : "CONFLICT";
                return String.join("|", error.coordinate(), arm, error.message(), rendered);
            })
            .sorted(Comparator.naturalOrder())
            .collect(Collectors.toCollection(ArrayList::new));
    }

    private void withDetectedStore(String sdl, java.util.function.BiConsumer<DSLContext, ClaimDomain> body) {
        try (var store = GraphitronModelStore.open()) {
            capture(store.dsl(), sdl);
            var domain = ClaimDomain.of(TestSchemaHelper.buildSchema(sdl));
            ClaimDomainRows.write(store.dsl(), GRAPH, domain);
            body.accept(store.dsl(), domain);
        }
    }

    private void capture(DSLContext dsl, String sdl) {
        var registry = RewriteSchemaLoader.load(List.of(write(tmp, sdl).toString()));
        FactCapture.capture(dsl, new FactCapture.GraphIdentity(GRAPH, tmp), registry);
    }

    private static Path write(Path directory, String sdl) {
        Path file = directory.resolve("fixture.graphqls");
        try {
            Files.createDirectories(directory);
            Files.writeString(file, sdl);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }
}
