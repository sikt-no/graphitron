package no.sikt.graphitron.lsp;

import graphql.language.SourceLocation;
import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
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
        var file = WorkspaceFileTestSupport.snapshot("type Foo { x: Int }\n");

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
                    no.sikt.graphitron.rewrite.model.ConflictSite.of(
                        new no.sikt.graphitron.rewrite.model.MethodRef.StaticOnly(
                            "com.example.S", "m", no.sikt.graphitron.javapoet.ClassName.OBJECT,
                            List.of(), List.of()),
                        no.sikt.graphitron.javapoet.ClassName.get(String.class)),
                    no.sikt.graphitron.rewrite.model.ConflictSite.of(
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
                List.of(),
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
        if (permit == Rejection.AuthorError.SortEnumMissingOrder.class) {
            // R453: an @orderBy sort enum carrying values with no ordering directive. Two missing
            // values exercise the accumulate-all multi-line message shape; Diagnostics.compute's
            // switch on Rejection.AuthorError catches it uniformly (Error severity).
            return new Rejection.AuthorError.SortEnumMissingOrder(
                "ActorOrderField", List.of("LAST_NAME", "LAST_UPDATE"));
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
        // R256: re-added ServiceMethodCallError service-binding arms. One sample per arm;
        // Diagnostics.compute's switch on Rejection.AuthorError catches them uniformly (Error),
        // and lspCodeOf forwards each arm's stable graphitron.service-method-call.* code.
        if (permit == no.sikt.graphitron.rewrite.model.ServiceMethodCallError.InstanceHolderUnconstructible.class) {
            return new no.sikt.graphitron.rewrite.model.ServiceMethodCallError.InstanceHolderUnconstructible(
                "com.example.Svc", "getFilm", "Svc",
                no.sikt.graphitron.rewrite.model.ServiceMethodCallError.HolderProblem.NO_BINDABLE_CTOR);
        }
        if (permit == no.sikt.graphitron.rewrite.model.ServiceMethodCallError.ArgumentParameterMismatch.class) {
            return new no.sikt.graphitron.rewrite.model.ServiceMethodCallError.ArgumentParameterMismatch(
                "title", "getFilm", List.of("name", "year"), List.of("tenantId"), " — rename or argMapping");
        }
        if (permit == no.sikt.graphitron.rewrite.model.ServiceMethodCallError.DtoSourcesUnsupported.class) {
            return new no.sikt.graphitron.rewrite.model.ServiceMethodCallError.DtoSourcesUnsupported(
                "keys", "getFilms", "sources type 'com.example.Dto' is not backed by a jOOQ TableRecord");
        }
        if (permit == no.sikt.graphitron.rewrite.model.ServiceMethodCallError.UnrecognizedSourcesType.class) {
            return new no.sikt.graphitron.rewrite.model.ServiceMethodCallError.UnrecognizedSourcesType(
                "input", "getFilms", "java.util.List<com.example.Weird>");
        }
        // R256: ReflectionError sub-seal of AuthorError (shared reflection-intrinsic arms). One
        // sample per arm; lspCodeOf forwards each arm's stable graphitron.reflect.* code.
        if (permit == no.sikt.graphitron.rewrite.model.ReflectionError.ClassNotLoaded.class) {
            return new no.sikt.graphitron.rewrite.model.ReflectionError.ClassNotLoaded("com.example.Missing");
        }
        if (permit == no.sikt.graphitron.rewrite.model.ReflectionError.ReturnTypeMismatch.class) {
            return new no.sikt.graphitron.rewrite.model.ReflectionError.ReturnTypeMismatch(
                "com.example.Svc", "getFilm", "FilmRecord", "String",
                no.sikt.graphitron.rewrite.model.ReflectionError.ReturnContext.SERVICE);
        }
        if (permit == no.sikt.graphitron.rewrite.model.ReflectionError.ParameterNamesMissing.class) {
            return new no.sikt.graphitron.rewrite.model.ReflectionError.ParameterNamesMissing(
                "com.example.Svc", "getFilm");
        }
        if (permit == no.sikt.graphitron.rewrite.model.ReflectionError.AmbiguousMethod.class) {
            return new no.sikt.graphitron.rewrite.model.ReflectionError.AmbiguousMethod(
                "com.example.Svc", "getFilm", List.of(0, 1));
        }
        // R246: UpdateRowsError sub-seal of AuthorError. One sample per arm; Diagnostics.compute's
        // switch on Rejection.AuthorError catches the whole sub-family uniformly (Error severity).
        if (permit == no.sikt.graphitron.rewrite.model.UpdateRowsError.NoUniqueKeyCoverage.class) {
            return new no.sikt.graphitron.rewrite.model.UpdateRowsError.NoUniqueKeyCoverage(
                "film",
                List.of(new no.sikt.graphitron.rewrite.model.ColumnRef("title", "TITLE", "java.lang.String")),
                List.of(new no.sikt.graphitron.rewrite.model.MatchedKey.PrimaryKey(
                    List.of(new no.sikt.graphitron.rewrite.model.ColumnRef("film_id", "FILM_ID", "java.lang.Integer")),
                    "film_pkey")));
        }
        if (permit == no.sikt.graphitron.rewrite.model.UpdateRowsError.NoSetFields.class) {
            return new no.sikt.graphitron.rewrite.model.UpdateRowsError.NoSetFields(
                "film",
                new no.sikt.graphitron.rewrite.model.MatchedKey.PrimaryKey(
                    List.of(new no.sikt.graphitron.rewrite.model.ColumnRef("film_id", "FILM_ID", "java.lang.Integer")),
                    "film_pkey"));
        }
        if (permit == no.sikt.graphitron.rewrite.model.UpdateRowsError.MixedCarrierKeyMembership.class) {
            // Models a cross-table FK reference whose lifted columns straddle the matched key — the
            // only carrier shape that still reaches this arm after R354 (a self-FK reference routes
            // wholly to SET instead of straddling).
            return new no.sikt.graphitron.rewrite.model.UpdateRowsError.MixedCarrierKeyMembership(
                "ref",
                List.of(new no.sikt.graphitron.rewrite.model.ColumnRef("actor_id", "ACTOR_ID", "java.lang.Integer")),
                List.of(new no.sikt.graphitron.rewrite.model.ColumnRef("last_update", "LAST_UPDATE", "java.time.LocalDateTime")));
        }
        if (permit == no.sikt.graphitron.rewrite.model.UpdateRowsError.UnsupportedInputFieldShape.class) {
            return new no.sikt.graphitron.rewrite.model.UpdateRowsError.UnsupportedInputFieldShape(
                "nested", "NestingField", "nested input types in @mutation(typeName: UPDATE) fields are not yet supported");
        }
        if (permit == no.sikt.graphitron.rewrite.model.UpdateRowsError.OverrideConditionNotSupported.class) {
            return new no.sikt.graphitron.rewrite.model.UpdateRowsError.OverrideConditionNotSupported(
                "syntheticName", new SourceLocation(1, 1));
        }
        if (permit == no.sikt.graphitron.rewrite.model.UpdateRowsError.PlainColumnCollision.class) {
            return new no.sikt.graphitron.rewrite.model.UpdateRowsError.PlainColumnCollision(
                "name", "alias", "name");
        }
        // R266: DeleteRowsError sub-seal of AuthorError. One sample per arm; Diagnostics.compute's
        // switch on Rejection.AuthorError catches the whole sub-family uniformly (Error severity),
        // and lspCodeOf forwards each arm's stable graphitron.delete-rows.* code.
        if (permit == no.sikt.graphitron.rewrite.model.DeleteRowsError.NoUniqueKeyCoverage.class) {
            return new no.sikt.graphitron.rewrite.model.DeleteRowsError.NoUniqueKeyCoverage(
                "film",
                List.of(new no.sikt.graphitron.rewrite.model.ColumnRef("title", "TITLE", "java.lang.String")),
                List.of(new no.sikt.graphitron.rewrite.model.MatchedKey.PrimaryKey(
                    List.of(new no.sikt.graphitron.rewrite.model.ColumnRef("film_id", "FILM_ID", "java.lang.Integer")),
                    "film_pkey")));
        }
        if (permit == no.sikt.graphitron.rewrite.model.DeleteRowsError.UnsupportedInputFieldShape.class) {
            return new no.sikt.graphitron.rewrite.model.DeleteRowsError.UnsupportedInputFieldShape(
                "nested", "NestingField", "nested input types in @mutation(typeName: DELETE) fields are not yet supported");
        }
        if (permit == no.sikt.graphitron.rewrite.model.DeleteRowsError.OverrideConditionNotSupported.class) {
            return new no.sikt.graphitron.rewrite.model.DeleteRowsError.OverrideConditionNotSupported(
                "syntheticName", new SourceLocation(1, 1));
        }
        // R457: MutationTableArgError sub-seal of AuthorError. One sample per arm; Diagnostics.compute's
        // switch on Rejection.AuthorError catches the whole sub-family uniformly (Error severity), and
        // lspCodeOf forwards the stable graphitron.mutation-table-arg.* code.
        if (permit == no.sikt.graphitron.rewrite.model.MutationTableArgError.UnsupportedVerb.class) {
            return new no.sikt.graphitron.rewrite.model.MutationTableArgError.UnsupportedVerb(
                "INSERT", List.of("DELETE"));
        }
        // R244: ErrorChannelWalkerError sub-seal of AuthorError. One sample per arm; Diagnostics.compute's
        // switch on Rejection.AuthorError catches the whole sub-family uniformly (Error severity), and
        // lspCodeOf forwards each arm's stable graphitron.error-channel.* code.
        if (permit == no.sikt.graphitron.rewrite.model.ErrorChannelWalkerError.MultipleErrorsFields.class) {
            return new no.sikt.graphitron.rewrite.model.ErrorChannelWalkerError.MultipleErrorsFields(
                "FilmPayload", List.of("errors", "problems"));
        }
        if (permit == no.sikt.graphitron.rewrite.model.ErrorChannelWalkerError.NonNullableSuccessProjectionField.class) {
            return new no.sikt.graphitron.rewrite.model.ErrorChannelWalkerError.NonNullableSuccessProjectionField(
                "FilmPayload", "film");
        }
        if (permit == no.sikt.graphitron.rewrite.model.ErrorChannelWalkerError.NonNullableErrorsField.class) {
            return new no.sikt.graphitron.rewrite.model.ErrorChannelWalkerError.NonNullableErrorsField(
                "FilmPayload", "errors");
        }
        if (permit == no.sikt.graphitron.rewrite.model.ErrorChannelWalkerError.ChannelRuleViolation.class) {
            return new no.sikt.graphitron.rewrite.model.ErrorChannelWalkerError.ChannelRuleViolation(
                "FilmPayload", "errors", 7, "two VALIDATION handlers in one channel");
        }
        if (permit == no.sikt.graphitron.rewrite.model.ErrorChannelWalkerError.HandlerSourceAccessorMissing.class) {
            return new no.sikt.graphitron.rewrite.model.ErrorChannelWalkerError.HandlerSourceAccessorMissing(
                "FilmPayload", "FilmError", "com.example.FilmErrorHandler", "code", List.of("message", "path"));
        }
        // R261: WireCoercionError sub-seal of AuthorError. One sample per arm; Diagnostics.compute's
        // switch on Rejection.AuthorError catches them uniformly (Error severity), and lspCodeOf
        // forwards each arm's stable graphitron.wire-coercion.* code.
        if (permit == no.sikt.graphitron.rewrite.model.WireCoercionError.Assignability.class) {
            return new no.sikt.graphitron.rewrite.model.WireCoercionError.Assignability(
                "ID", "java.lang.String", "java.lang.Long", "@service argument 'id' of method 'getFilm'");
        }
        if (permit == no.sikt.graphitron.rewrite.model.WireCoercionError.EnumConstantDivergence.class) {
            return new no.sikt.graphitron.rewrite.model.WireCoercionError.EnumConstantDivergence(
                "com.example.jooq.enums.MpaaRating", List.of("PG_13"), List.of("G", "PG", "R"),
                "input-bean field 'rating' of method 'createFilm'");
        }
        return null;
    }
}
