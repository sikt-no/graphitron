package no.sikt.graphitron.lsp;

import graphql.language.SourceLocation;
import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.model.read.SourceUri;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.model.Rejection;
import org.eclipse.lsp4j.Diagnostic;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.rewrite.FactWriters.rejectionFacts;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Meta-test asserting that every {@link Rejection} sealed permit reachable from a
 * {@link ValidationError} survives the round trip a finding now takes to the editor: written to the
 * store's rejection residue by the build's loader, read back through the diagnostics view, replayed
 * as one squiggle carrying a severity. The residue loader's exhaustive {@code switch} already makes
 * "missing permit" a compile error; this pins that no permit is lost or doubled on the wire, and
 * surfaces a permit that is as a targeted failure rather than a generic NPE.
 */
class RejectionSeverityCoverageTest {

    /** The graph every fixture here writes under, and the one the replay reads back through. */
    private static final String GRAPH = "coverage";

    @Test
    void everyRejectionPermitReplaysWithASeverity(@TempDir java.nio.file.Path tmp) {
        var permits = collectLeafPermits(Rejection.class);
        assertThat(permits)
            .as("Rejection sealed hierarchy must have at least one leaf")
            .isNotEmpty();

        var path = "/tmp/coverage.graphqls";
        var uri = SourceUri.of(path);
        var loc = new SourceLocation(1, 1, path);
        var file = WorkspaceFileTestSupport.snapshot("type Foo { x: Int }\n");

        var unmapped = new ArrayList<String>();
        try (var store = FactStores.inMemory()) {
            var facts = rejectionFacts(store.dsl(), GRAPH, tmp);
            for (var permit : permits) {
                var sample = sampleFor(permit);
                if (sample == null) {
                    unmapped.add(permit.getName() + " (no test sample)");
                    continue;
                }
                List<Diagnostic> diags;
                try {
                    facts.write(List.of(new ValidationError("Coord", sample, loc)));
                    diags = replay(store.dsl(), uri, file);
                } catch (RuntimeException e) {
                    unmapped.add(permit.getName() + " (replay threw: " + e + ")");
                    continue;
                }
                if (diags.size() != 1 || diags.get(0).getSeverity() == null) {
                    unmapped.add(permit.getName() + " (unmapped severity)");
                }
            }
        }
        assertThat(unmapped)
            .as("every Rejection permit must replay as one diagnostic carrying a severity")
            .isEmpty();
    }

    /** The editor's read of what the build recorded, over the store this test writes into. */
    private static List<Diagnostic> replay(
        DSLContext dsl, String uri, FileSnapshot file
    ) {
        return Diagnostics.compute(BundledVocabulary.get(), uri, file,
            Optional.of(new StoreHandle(dsl, GRAPH)));
    }

    /**
     * The membership binding for the {@code lspCode()} sub-seals: every leaf that declares a code
     * carries it to the wire, and every codeless leaf arrives codeless. Membership is read off the
     * leaf itself (does it expose a public no-arg {@code lspCode()}?), so a leaf <em>gaining</em> a
     * code the residue loader's match list does not know fails here rather than passing silently,
     * which its exhaustive switch alone cannot catch. One decode site now: the loader writes
     * {@code lsp_code} and the editor reads the column, where the code used to be matched a second
     * time on the way to the diagnostic.
     */
    @Test
    void everyDeclaredLspCodeReachesTheWire(@TempDir java.nio.file.Path tmp) {
        var permits = collectLeafPermits(Rejection.class);
        var path = "/tmp/coverage.graphqls";
        var uri = SourceUri.of(path);
        var loc = new SourceLocation(1, 1, path);
        var file = WorkspaceFileTestSupport.snapshot("type Foo { x: Int }\n");

        var samples = new ArrayList<Rejection>();
        for (var permit : permits) {
            var sample = sampleFor(permit);
            assertThat(sample).as("no test sample for %s", permit.getName()).isNotNull();
            samples.add(sample);
        }
        assertThat(samples.stream().anyMatch(s -> declaredLspCode(s) != null))
            .as("the hierarchy declares codes, so this pins something")
            .isTrue();

        try (var store = FactStores.inMemory()) {
            var facts = rejectionFacts(store.dsl(), GRAPH, tmp);
            for (var sample : samples) {
                String declared = declaredLspCode(sample);
                facts.write(List.of(new ValidationError("Coord", sample, loc)));

                var storeCode = store.dsl()
                    .selectFrom(no.sikt.graphitron.model.Tables.REJECTION_VALIDATION_ERROR)
                    .fetchOne(r -> Optional.ofNullable(r.getLspCode()));
                assertThat(storeCode).isNotNull();
                assertThat(storeCode.orElse(null))
                    .as("the residue lsp_code for %s", sample.getClass().getName())
                    .isEqualTo(declared);

                var diags = replay(store.dsl(), uri, file);
                String onTheWire = diags.size() == 1 && diags.get(0).getCode() != null
                    ? diags.get(0).getCode().getLeft()
                    : null;
                assertThat(onTheWire)
                    .as("the replayed code for %s", sample.getClass().getName())
                    .isEqualTo(declared);
            }
        }
    }

    /** The leaf's own declaration: its public no-arg {@code lspCode()}, or null where none. */
    private static String declaredLspCode(Rejection sample) {
        try {
            return (String) sample.getClass().getMethod("lspCode").invoke(sample);
        } catch (NoSuchMethodException e) {
            return null;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("lspCode() on " + sample.getClass().getName() + " failed", e);
        }
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
            // Cross-site contextArgument type-agreement rejection. Build a minimal
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
            // Cross-producer DomainReturnType disagreement. Two participants on the same
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
            // An @orderBy sort enum carrying values with no ordering directive. Two missing
            // values exercise the accumulate-all multi-line message shape; Diagnostics.compute's
            // switch on Rejection.AuthorError catches it uniformly (Error severity).
            return new Rejection.AuthorError.SortEnumMissingOrder(
                "ActorOrderField", List.of("LAST_NAME", "LAST_UPDATE"));
        }
        if (permit == Rejection.AuthorError.TenantColumnTypeDisagreement.class) {
            // Tenant-scope classification rejection: the configured tenant column resolves to
            // disagreeing Java types across catalog tables. Two sites exercise the multi-line
            // message shape; Diagnostics.compute's switch on Rejection.AuthorError catches it
            // uniformly (Error severity).
            return new Rejection.AuthorError.TenantColumnTypeDisagreement(
                "eier_organisasjon",
                List.of(
                    new Rejection.AuthorError.TenantColumnTypeDisagreement.TableSite(
                        "public.emne",
                        no.sikt.graphitron.javapoet.ClassName.get(Integer.class)),
                    new Rejection.AuthorError.TenantColumnTypeDisagreement.TableSite(
                        "public.person",
                        no.sikt.graphitron.javapoet.ClassName.get(String.class))));
        }
        if (permit == Rejection.AuthorError.NoTenantBinding.class) {
            // Tenant-binding fold rejection: a field reaches a tenant-scoped table with no
            // binding in scope. Diagnostics.compute's switch on Rejection.AuthorError catches
            // it uniformly (Error severity).
            return new Rejection.AuthorError.NoTenantBinding(
                "Query.emner", "emne",
                "no argument or input field maps to tenant column 'eier_organisasjon', and no"
                    + " ancestor established a tenant context.");
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
                "summary",
                new Rejection.StubKey.VariantClass(null));
        }
        // ServiceMethodCallError sub-seal of AuthorError. The seal carries only the two arms
        // the translator-walker actually produces; the minimal sample is sufficient for the
        // severity-coverage walk (Diagnostics.compute's switch on Rejection.AuthorError catches the
        // whole sub-family uniformly). Further arms re-land later as their producer paths do.
        if (permit == no.sikt.graphitron.rewrite.model.ServiceMethodCallError.MultipleDslContextSlots.class) {
            return new no.sikt.graphitron.rewrite.model.ServiceMethodCallError.MultipleDslContextSlots(
                "com.example.Svc",
                no.sikt.graphitron.rewrite.model.ServiceMethodCallError.Round.METHOD);
        }
        if (permit == no.sikt.graphitron.rewrite.model.ServiceMethodCallError.ParameterUnbindable.class) {
            return new no.sikt.graphitron.rewrite.model.ServiceMethodCallError.ParameterUnbindable(
                "title", List.of("name", "year"), "name");
        }
        // Re-added ServiceMethodCallError service-binding arms. One sample per arm;
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
        if (permit == no.sikt.graphitron.rewrite.model.ServiceMethodCallError.SourcesOnPkLessParent.class) {
            return new no.sikt.graphitron.rewrite.model.ServiceMethodCallError.SourcesOnPkLessParent(
                "keys", "getRank", "FilmList", "film_list");
        }
        // ReflectionError sub-seal of AuthorError (shared reflection-intrinsic arms). One
        // sample per arm; lspCodeOf forwards each arm's stable graphitron.reflect.* code.
        if (permit == no.sikt.graphitron.rewrite.model.ReflectionError.ClassNotLoaded.class) {
            return new no.sikt.graphitron.rewrite.model.ReflectionError.ClassNotLoaded("com.example.Missing");
        }
        if (permit == no.sikt.graphitron.rewrite.model.ReflectionError.ReturnTypeMismatch.class) {
            return new no.sikt.graphitron.rewrite.model.ReflectionError.ReturnTypeMismatch(
                "com.example.Svc", "getFilm", "FilmRecord", "String");
        }
        if (permit == no.sikt.graphitron.rewrite.model.ReflectionError.ParameterNamesMissing.class) {
            return new no.sikt.graphitron.rewrite.model.ReflectionError.ParameterNamesMissing(
                "com.example.Svc", "getFilm");
        }
        if (permit == no.sikt.graphitron.rewrite.model.ReflectionError.AmbiguousMethod.class) {
            return new no.sikt.graphitron.rewrite.model.ReflectionError.AmbiguousMethod(
                "com.example.Svc", "getFilm", List.of(0, 1));
        }
        if (permit == no.sikt.graphitron.rewrite.model.ReflectionError.SeamParameterMissing.class) {
            return new no.sikt.graphitron.rewrite.model.ReflectionError.SeamParameterMissing(
                "com.example.db.Routines", "connect", List.of("connect(org.jooq.Field)"));
        }
        if (permit == no.sikt.graphitron.rewrite.model.ReflectionError.SeamCandidateAmbiguous.class) {
            return new no.sikt.graphitron.rewrite.model.ReflectionError.SeamCandidateAmbiguous(
                "com.example.Hooks", "mount",
                List.of("mount(org.jooq.Configuration)", "mount(java.sql.Connection)"));
        }
        if (permit == no.sikt.graphitron.rewrite.model.ReflectionError.HookNotStatic.class) {
            return new no.sikt.graphitron.rewrite.model.ReflectionError.HookNotStatic(
                "com.example.Hooks", "mount");
        }
        if (permit == no.sikt.graphitron.rewrite.model.ReflectionError.HookThrowsChecked.class) {
            return new no.sikt.graphitron.rewrite.model.ReflectionError.HookThrowsChecked(
                "com.example.Hooks", "mount", List.of("java.sql.SQLException"));
        }
        if (permit == no.sikt.graphitron.rewrite.model.ReflectionError.HandleTypeMismatch.class) {
            return new no.sikt.graphitron.rewrite.model.ReflectionError.HandleTypeMismatch(
                "com.example.Hooks", "mount", "SessionHandleRecord",
                "com.example.Hooks", "unmount", "String");
        }
        // UpdateRowsError sub-seal of AuthorError. One sample per arm; Diagnostics.compute's
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
            // only carrier shape that still reaches this arm now that a self-FK reference routes
            // wholly to SET instead of straddling the matched key.
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
        // DeleteRowsError sub-seal of AuthorError. One sample per arm; Diagnostics.compute's
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
        // MutationTableArgError sub-seal of AuthorError. One sample per arm; Diagnostics.compute's
        // switch on Rejection.AuthorError catches the whole sub-family uniformly (Error severity), and
        // lspCodeOf forwards the stable graphitron.mutation-table-arg.* code.
        if (permit == no.sikt.graphitron.rewrite.model.MutationTableArgError.UnsupportedVerb.class) {
            return new no.sikt.graphitron.rewrite.model.MutationTableArgError.UnsupportedVerb(
                "INSERT", List.of("DELETE"));
        }
        // ErrorChannelWalkerError sub-seal of AuthorError. One sample per arm; Diagnostics.compute's
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
                "FilmPayload", "FilmError", "com.example.FilmErrorHandler", "code", "code", List.of("message", "path"));
        }
        // WireCoercionError sub-seal of AuthorError. One sample per arm; Diagnostics.compute's
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
        // ServiceCarrierShapeError sub-seal of AuthorError. One sample per arm;
        // Diagnostics.compute's switch on Rejection.AuthorError catches them uniformly (Error
        // severity), and lspCodeOf forwards each arm's stable graphitron.service-carrier-shape.* code.
        if (permit == no.sikt.graphitron.rewrite.model.ServiceCarrierShapeError.ProducerArrivalMismatch.class) {
            return new no.sikt.graphitron.rewrite.model.ServiceCarrierShapeError.ProducerArrivalMismatch(
                "FilmPayload", "Mutation", "runFilms",
                no.sikt.graphitron.rewrite.model.Arity.MANY,
                no.sikt.graphitron.rewrite.model.Arity.ONE,
                "com.example.FilmService", "runFilm");
        }
        if (permit == no.sikt.graphitron.rewrite.model.ServiceCarrierShapeError.DataFieldArrivalConflict.class) {
            return new no.sikt.graphitron.rewrite.model.ServiceCarrierShapeError.DataFieldArrivalConflict(
                "FilmListPayload", "Mutation", "runFilmsList", "films", "Film",
                no.sikt.graphitron.rewrite.model.Arity.MANY,
                no.sikt.graphitron.rewrite.model.Arity.MANY);
        }
        // PivotError sub-seal of AuthorError (@pivot classification). One sample per arm;
        // Diagnostics.compute's switch on Rejection.AuthorError catches them uniformly (Error
        // severity), and lspCodeOf forwards each arm's stable graphitron.pivot.* code.
        if (permit == no.sikt.graphitron.rewrite.model.PivotError.NonNullSlot.class) {
            return new no.sikt.graphitron.rewrite.model.PivotError.NonNullSlot("nn", "TranslatedTexts");
        }
        if (permit == no.sikt.graphitron.rewrite.model.PivotError.NonScalarSlot.class) {
            return new no.sikt.graphitron.rewrite.model.PivotError.NonScalarSlot("nn", "TranslatedTexts");
        }
        if (permit == no.sikt.graphitron.rewrite.model.PivotError.DivergentSlotType.class) {
            return new no.sikt.graphitron.rewrite.model.PivotError.DivergentSlotType(
                "nn", "TranslatedTexts", "String", "Int");
        }
        if (permit == no.sikt.graphitron.rewrite.model.PivotError.VocabularyNotTextEnum.class) {
            return new no.sikt.graphitron.rewrite.model.PivotError.VocabularyNotTextEnum("Sprak");
        }
        if (permit == no.sikt.graphitron.rewrite.model.PivotError.SlotMissingFromVocabulary.class) {
            return new no.sikt.graphitron.rewrite.model.PivotError.SlotMissingFromVocabulary(
                "se", "Sprak", List.of("nn", "nb"));
        }
        if (permit == no.sikt.graphitron.rewrite.model.PivotError.DuplicateSlotToken.class) {
            return new no.sikt.graphitron.rewrite.model.PivotError.DuplicateSlotToken(
                "nob", List.of("nb", "bokmaal"));
        }
        if (permit == no.sikt.graphitron.rewrite.model.PivotError.ColumnUnresolved.class) {
            return new no.sikt.graphitron.rewrite.model.PivotError.ColumnUnresolved(
                "on", "langcode", "film_translation", List.of("lang_code", "title_txt"));
        }
        if (permit == no.sikt.graphitron.rewrite.model.PivotError.ValueTypeMismatch.class) {
            return new no.sikt.graphitron.rewrite.model.PivotError.ValueTypeMismatch(
                "amount", "java.math.BigDecimal", "String");
        }
        if (permit == no.sikt.graphitron.rewrite.model.PivotError.ListReturn.class) {
            return new no.sikt.graphitron.rewrite.model.PivotError.ListReturn("Film.titleTranslations");
        }
        if (permit == no.sikt.graphitron.rewrite.model.PivotError.UnsupportedReferencePath.class) {
            return new no.sikt.graphitron.rewrite.model.PivotError.UnsupportedReferencePath(
                "Film.titleTranslations", "the path has 2 hops");
        }
        if (permit == no.sikt.graphitron.rewrite.model.PivotError.RecordBackedParent.class) {
            return new no.sikt.graphitron.rewrite.model.PivotError.RecordBackedParent(
                "Holder.texts", "Holder");
        }
        if (permit == no.sikt.graphitron.rewrite.model.PivotError.InvalidProjectionType.class) {
            return new no.sikt.graphitron.rewrite.model.PivotError.InvalidProjectionType(
                "Film.titleTranslations", "Language", "is not a plain output type");
        }
        // JooqRecordInputError sub-seal of AuthorError, minted by InputBeanResolver's per-column fold
        // over a @service jOOQ-record parameter. Two live writers is the rejecting shape; a group whose
        // superseded fields carry @deprecated is admitted and never reaches a rejection at all.
        if (permit == no.sikt.graphitron.rewrite.model.JooqRecordInputError.LiveColumnCollision.class) {
            return new no.sikt.graphitron.rewrite.model.JooqRecordInputError.LiveColumnCollision(
                "in", "modifyFilm", "com.example.FilmService", "in",
                List.of(
                    new no.sikt.graphitron.rewrite.model.JooqRecordInputError.CollidingField("title", false),
                    new no.sikt.graphitron.rewrite.model.JooqRecordInputError.CollidingField("details.aka", false)),
                "title", "film");
        }
        return null;
    }
}
