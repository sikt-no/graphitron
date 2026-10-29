package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.config.SessionStateConfig;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.diagnostics.ValidationFailedException;
import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a {@code $session} binding the session configuration cannot answer fails the build, and one
 * it can answer does not: the store's verdicts reaching {@link GraphQLRewriteGenerator#validate()}.
 * The handle class is read from the test classes' own root by the classpath census like any consumer
 * class, so what {@code $session.<member>} may name is the store's member relation, not a
 * reflection result. The negatives sit beside the positives, so the gate cannot pass because
 * everything fails.
 */
@PipelineTier
class SessionIdentityRejectionPipelineTest {

    private static final String STUB = "no.sikt.graphitron.rewrite.SessionHandleStub";
    private static final String RECORD_HANDLE = STUB + "$RecordHandle";
    private static final String CONDITIONS = "no.sikt.graphitron.rewrite.TestConditionStub";
    private static final String SERVICES = "no.sikt.graphitron.rewrite.TestServiceStub";

    private static String serviceSdl(String method, String binding) {
        return """
            type Query {
                bruker: Int @service(service: {className: "%s", method: "%s", argMapping: "%s"})
            }
            """.formatted(SERVICES, method, binding);
    }

    private static String conditionSdl(String method, String binding) {
        return """
            type Film @table(name: "film") { title: String }
            type Query {
                films: [Film!]! @condition(condition: {className: "%s", method: "%s", argMapping: "%s"})
            }
            """.formatted(CONDITIONS, method, binding);
    }

    private static SessionStateConfig handle(String handleClass) {
        return SessionStateConfig.from(handleClass, null, null);
    }

    // ===== accepted =====

    @Test
    void aBoxedParameterOfAPrimitiveMember_isAccepted(@TempDir Path tmp) throws IOException {
        assertThat(errors(tmp, serviceSdl("brukerOf", "brukerId: $session.brukerId"), handle(RECORD_HANDLE)))
            .isEmpty();
    }

    @Test
    void aPrimitiveParameterOfAPrimitiveMember_isAccepted(@TempDir Path tmp) throws IOException {
        assertThat(errors(tmp, serviceSdl("brukerOfPrimitive", "brukerId: $session.brukerId"),
            handle(RECORD_HANDLE))).isEmpty();
    }

    @Test
    void theWholeHandleIntoAParameterOfTheHandleType_isAccepted(@TempDir Path tmp) throws IOException {
        assertThat(errors(tmp, conditionSdl("sessionWhole", "handle: $session"), handle(RECORD_HANDLE)))
            .isEmpty();
    }

    // ===== rejected =====

    @Test
    void aBoxedMemberIntoAPrimitiveParameter_rejectsNamingBothDeclarations(@TempDir Path tmp) throws IOException {
        assertThat(errors(tmp, serviceSdl("sokerOfPrimitive", "sokerId: $session.sokerId"), handle(RECORD_HANDLE)))
            .singleElement().asString()
            .contains("Query.bruker").contains("$session.sokerId").contains("long").contains("java.lang.Long");
    }

    @Test
    void aParameterOfAnotherType_rejects(@TempDir Path tmp) throws IOException {
        assertThat(errors(tmp, conditionSdl("sessionWrongType", "brukerId: $session.brukerId"),
            handle(RECORD_HANDLE)))
            .singleElement().asString()
            .contains("Query.films").contains("java.lang.String").contains("long");
    }

    @Test
    void anUnknownMember_rejectsListingTheMembersTheHandleOffers(@TempDir Path tmp) throws IOException {
        assertThat(errors(tmp, serviceSdl("brukerOf", "brukerId: $session.nope"), handle(RECORD_HANDLE)))
            .singleElement().asString()
            .contains("nope").contains("brukerId, sokerId");
    }

    @Test
    void aBindingWithNoSessionStateConfigured_rejectsNamingTheConfiguration(@TempDir Path tmp) throws IOException {
        assertThat(errors(tmp, conditionSdl("sessionBoxed", "brukerId: $session.brukerId"),
            SessionStateConfig.none()))
            .singleElement().asString()
            .contains("$session.brukerId").contains("no <sessionState> is configured");
    }

    @Test
    void aBindingWithNoHandleNamed_rejectsNamingTheHandleElement(@TempDir Path tmp) throws IOException {
        assertThat(errors(tmp, conditionSdl("sessionBoxed", "brukerId: $session.brukerId"),
            SessionStateConfig.from(null, null, null)))
            .singleElement().asString()
            .contains("$session.brukerId").contains("<handle>");
    }

    @Test
    void aHandleNoClasspathEntryHolds_rejectsOnceAsAConfigurationError(@TempDir Path tmp) throws IOException {
        assertThat(errors(tmp, serviceSdl("brukerOf", "brukerId: $session.brukerId"),
            handle("com.example.NoSuchIdentity")))
            .singleElement().asString()
            .contains("<sessionState><handle>").contains("com.example.NoSuchIdentity")
            .contains("holds as a public class");
    }

    // ===== Helpers =====

    private static List<String> errors(Path tmp, String sdl, SessionStateConfig config) throws IOException {
        Path schema = tmp.resolve("schema.graphqls");
        Files.writeString(schema, sdl);
        var ctx = new RunContext(
            List.of(new SchemaInput(SchemaSource.file(schema), Optional.empty(), Optional.empty())),
            tmp, "SessionIdentityRejectionPipelineTest", tmp,
            DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE, testClassesRoot()
        ).withSessionStateConfig(config);
        try (var store = GraphitronStore.captured(ctx)) {
            new GraphQLRewriteGenerator(ctx, store).validate();
            return List.of();
        } catch (ValidationFailedException e) {
            return e.errors().stream().map(ValidationError::message).toList();
        }
    }

    /** The classpath root carrying the handle and the {@code @service}/{@code @condition} stubs. */
    private static List<Path> testClassesRoot() {
        try {
            return List.of(Path.of(SessionIdentityRejectionPipelineTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()));
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("the test classes' own classpath entry", e);
        }
    }
}
