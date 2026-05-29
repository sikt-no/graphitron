package no.sikt.graphitron.lsp;

import graphql.language.SourceLocation;
import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.model.Rejection;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Meta-test asserting the validator-to-LSP severity switch in
 * {@link Diagnostics} covers every {@link Rejection} sealed permit reachable from a
 * {@link ValidationError}. The exhaustive {@code switch} in production code already makes
 * "missing permit" a compile error; this test pins the runtime invariant against a
 * {@code default} branch sneaking in via refactor, and surfaces unmapped permits as a
 * targeted failure rather than a generic NPE.
 */
class RejectionSeverityCoverageTest {

    @Test
    void everyRejectionPermitMapsToANonNullSeverity() {
        var permits = collectLeafPermits(Rejection.class);
        assertThat(permits)
            .as("Rejection sealed hierarchy must have at least one leaf")
            .isNotEmpty();

        var path = "/tmp/coverage.graphqls";
        var uri = ValidationReport.canonicalUri(path);
        var loc = new SourceLocation(1, 1, path);
        var snapshot = new LspSchemaSnapshot.Built.Current(List.of(), Map.of(), Map.of());
        var file = new WorkspaceFile(1, "type Foo { x: Int }\n");

        var unmapped = new ArrayList<String>();
        for (var permit : permits) {
            var sample = sampleFor(permit);
            if (sample == null) {
                unmapped.add(permit.getName() + " (no test sample)");
                continue;
            }
            var report = ValidationReport.from(
                List.of(new ValidationError("Coord", sample, loc)),
                List.of());
            List<Diagnostic> diags;
            try {
                diags = Diagnostics.compute(uri, file, CompletionData.empty(), snapshot, report);
            } catch (RuntimeException e) {
                unmapped.add(permit.getName() + " (compute threw: " + e + ")");
                continue;
            }
            if (diags.size() != 1 || diags.get(0).getSeverity() == null) {
                unmapped.add(permit.getName() + " (unmapped severity)");
            }
        }
        assertThat(unmapped)
            .as("every Rejection permit must map to a non-null DiagnosticSeverity")
            .isEmpty();
    }

    private static Set<Class<?>> collectLeafPermits(Class<?> root) {
        var leaves = new LinkedHashSet<Class<?>>();
        walk(root, leaves);
        return leaves;
    }

    private static void walk(Class<?> node, Set<Class<?>> out) {
        var permits = node.getPermittedSubclasses();
        if (permits == null || permits.length == 0) {
            if (Rejection.class.isAssignableFrom(node)) {
                out.add(node);
            }
            return;
        }
        for (var p : permits) {
            walk(p, out);
        }
    }

    /**
     * Returns a representative {@link Rejection} instance for each leaf permit class. Kept here
     * (rather than in {@link Rejection} itself) so adding a permit forces an obvious test failure
     * and a deliberate edit at this site: the test author has to look at the new permit and
     * decide what severity it should map to in {@link Diagnostics}.
     */
    private static Rejection sampleFor(Class<?> permit) {
        if (permit == Rejection.AuthorError.Structural.class) {
            return new Rejection.AuthorError.Structural("reason");
        }
        if (permit == Rejection.AuthorError.UnknownName.class) {
            return new Rejection.AuthorError.UnknownName(
                "summary", Rejection.AttemptKind.COLUMN, "attempt", List.of("candidate"));
        }
        if (permit == Rejection.AuthorError.AccessorMismatch.class) {
            return new Rejection.AuthorError.AccessorMismatch("reason");
        }
        if (permit == Rejection.AuthorError.RecordBindingMultiProducer.class) {
            return new Rejection.AuthorError.RecordBindingMultiProducer(
                "FilmDetails",
                List.of(new no.sikt.graphitron.rewrite.model.ProducerBinding.RootService(
                    String.class, "Query", "filmDetails",
                    "com.example.FilmService", "getFilm", new SourceLocation(1, 1))));
        }
        if (permit == Rejection.AuthorError.TypeConflict.class) {
            // R190: cross-site contextArgument type-agreement rejection. Build a minimal
            // ConflictSite list with two entries so message() renders the multi-site shape.
            return new Rejection.AuthorError.TypeConflict(
                "fnr",
                List.of(
                    new no.sikt.graphitron.rewrite.model.ConflictSite(
                        new no.sikt.graphitron.rewrite.model.MethodRef.StaticOnly(
                            "com.example.S", "m", no.sikt.graphitron.javapoet.ClassName.OBJECT,
                            List.of(), List.of()),
                        no.sikt.graphitron.javapoet.ClassName.get(String.class)),
                    new no.sikt.graphitron.rewrite.model.ConflictSite(
                        new no.sikt.graphitron.rewrite.model.MethodRef.StaticOnly(
                            "com.example.T", "m", no.sikt.graphitron.javapoet.ClassName.OBJECT,
                            List.of(), List.of()),
                        no.sikt.graphitron.javapoet.ClassName.get(Long.class))));
        }
        if (permit == Rejection.AuthorError.MultiProducerDomainTypeDisagreement.class) {
            // R204: cross-producer DomainReturnType disagreement. Two participants on the same
            // SDL payload type with disagreeing arms is the minimum shape that exercises the
            // multi-arm message rendering; both samples below construct the typed payload arms
            // the validator emits.
            var filmTable = new no.sikt.graphitron.rewrite.model.TableRef(
                "film", "FILM",
                no.sikt.graphitron.javapoet.ClassName.bestGuess("com.example.jooq.tables.Film"),
                no.sikt.graphitron.javapoet.ClassName.bestGuess("com.example.jooq.tables.records.FilmRecord"),
                no.sikt.graphitron.javapoet.ClassName.bestGuess("com.example.jooq.Tables"),
                List.of());
            return new Rejection.AuthorError.MultiProducerDomainTypeDisagreement(
                "FilmListPayload",
                List.of(
                    new Rejection.AuthorError.MultiProducerDomainTypeDisagreement.Participant(
                        "Mutation", "createFilms",
                        new no.sikt.graphitron.rewrite.model.DomainReturnType.Record(filmTable)),
                    new Rejection.AuthorError.MultiProducerDomainTypeDisagreement.Participant(
                        "Mutation", "runFilms",
                        new no.sikt.graphitron.rewrite.model.DomainReturnType.TableRecord(
                            no.sikt.graphitron.javapoet.ClassName.bestGuess(
                                "com.example.jooq.tables.records.FilmRecord")))));
        }
        if (permit == Rejection.InvalidSchema.Structural.class) {
            return new Rejection.InvalidSchema.Structural("reason");
        }
        if (permit == Rejection.InvalidSchema.DirectiveConflict.class) {
            return new Rejection.InvalidSchema.DirectiveConflict(List.of("a", "b"), "reason");
        }
        if (permit == Rejection.InvalidSchema.CaseFoldCollision.class) {
            return new Rejection.InvalidSchema.CaseFoldCollision(
                List.of("Foo", "foo"),
                Rejection.InvalidSchema.CaseFoldCollision.Origin.SDL,
                "");
        }
        if (permit == Rejection.Deferred.class) {
            return new Rejection.Deferred(
                "summary", "plan-slug",
                new Rejection.StubKey.VariantClass(null));
        }
        // R238: ServiceMethodCallError sub-seal of AuthorError. The seal carries only the two arms
        // the translator-walker actually produces; the minimal sample is sufficient for the
        // severity-coverage walk (Diagnostics.compute's switch on Rejection.AuthorError catches the
        // whole sub-family uniformly). Further arms re-land under R256 as their producer paths do.
        if (permit == no.sikt.graphitron.rewrite.model.ServiceMethodCallError.MultipleDslContextSlots.class) {
            return new no.sikt.graphitron.rewrite.model.ServiceMethodCallError.MultipleDslContextSlots(
                "com.example.Svc",
                no.sikt.graphitron.rewrite.model.ServiceMethodCallError.Round.METHOD);
        }
        if (permit == no.sikt.graphitron.rewrite.model.ServiceMethodCallError.ParameterUnbindable.class) {
            return new no.sikt.graphitron.rewrite.model.ServiceMethodCallError.ParameterUnbindable(
                "title", List.of("name", "year"), "name");
        }
        return null;
    }
}
