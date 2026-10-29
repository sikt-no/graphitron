package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.config.SessionStateConfig;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;
import no.sikt.graphitron.rewrite.session.SessionHooks;
import no.sikt.graphitron.rewrite.session.SessionHooksFixtures;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDL to classified schema to validated schema for {@code $session.<member>}: a member binding
 * classifies to the member variants on a {@code @service} and on a {@code @condition}, and the
 * validator checks it against the handle type's decoded members (an unknown member, a parameter
 * type that is not the member's, a boxed member bound to a primitive parameter). The handle is the
 * record {@code SessionHookStub.RecordHandle(long brukerId, Long sokerId)}, resolved through the
 * real session-hook resolution so the decode is the production one.
 */
@PipelineTier
class SessionMemberBindingPipelineTest {

    private static final String STUB = "no.sikt.graphitron.rewrite.SessionHookStub";
    private static final String CONDITIONS = "no.sikt.graphitron.rewrite.TestConditionStub";
    private static final String SERVICES = "no.sikt.graphitron.rewrite.TestServiceStub";

    private static SessionHooks recordHandleHooks() {
        var catalog = new ServiceCatalog(new BuildContext(null, null, new RunContext(
            List.of(), java.nio.file.Path.of("."), "SessionMemberBindingPipelineTest",
            java.nio.file.Path.of("."), "unused", "unused")));
        return catalog.resolveSessionHooks(
            SessionStateConfig.from(STUB + "#mountRecordHandle", null), null).hooks();
    }

    private static GraphitronSchema build(String sdl) {
        return SessionHooksFixtures.withHooks(TestSchemaHelper.buildSchema(sdl), recordHandleHooks());
    }

    private static List<String> errors(GraphitronSchema schema) {
        return new GraphitronSchemaValidator().validate(schema).stream().map(e -> e.message()).toList();
    }

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

    // ===== @service =====

    @Test
    void serviceMemberBinding_classifiesToTheMemberVariant_andAcceptsABoxedParameterOfAPrimitiveMember() {
        var schema = build(serviceSdl("brukerOf", "brukerId: $session.brukerId"));

        var field = (no.sikt.graphitron.rewrite.model.ServiceField) schema.field("Query", "bruker");
        assertThat(field.serviceMethodCall().methodArgs()).anySatisfy(entry ->
            assertThat(entry).isInstanceOfSatisfying(
                no.sikt.graphitron.rewrite.model.MappingEntry.FromSessionHandleMember.class,
                member -> assertThat(member.member()).isEqualTo("brukerId")));
        assertThat(errors(schema)).isEmpty();
    }

    @Test
    void serviceMemberBinding_primitiveParameterOfAPrimitiveMember_isAccepted() {
        assertThat(errors(build(serviceSdl("brukerOfPrimitive", "brukerId: $session.brukerId")))).isEmpty();
    }

    @Test
    void serviceMemberBinding_boxedMemberIntoAPrimitiveParameter_rejectsNamingBothDeclarations() {
        assertThat(errors(build(serviceSdl("sokerOfPrimitive", "sokerId: $session.sokerId"))))
            .singleElement().asString()
            .contains("sokerId").contains("long").contains("java.lang.Long");
    }

    @Test
    void serviceMemberBinding_unknownMember_rejectsListingTheReadableMembers() {
        assertThat(errors(build(serviceSdl("brukerOf", "brukerId: $session.nope"))))
            .singleElement().asString()
            .contains("no readable member 'nope'").contains("[brukerId, sokerId]");
    }

    // ===== @condition =====

    @Test
    void conditionMemberBinding_classifiesAsASessionRead_thatTakesTheStatementsDsl() {
        var schema = build(conditionSdl("sessionBoxed", "brukerId: $session.brukerId"));

        var field = (SqlGeneratingField) schema.field("Query", "films");
        var param = field.filters().get(0).callParams().get(0);
        assertThat(param.extraction()).isEqualTo(new CallSiteExtraction.SessionMemberRead("brukerId"));
        assertThat(param.readSource()).isEqualTo(no.sikt.graphitron.rewrite.model.CallParam.ReadSource.SESSION);
        assertThat(field.conditionsReadSession()).isTrue();
        assertThat(field.conditionsReadRequestContext()).isFalse();
        assertThat(errors(schema)).isEmpty();
    }

    @Test
    void conditionWholeHandleBinding_isAcceptedAgainstTheHandleType() {
        var schema = build(conditionSdl("sessionWhole", "handle: $session"));

        var param = ((SqlGeneratingField) schema.field("Query", "films")).filters().get(0).callParams().get(0);
        assertThat(param.extraction()).isInstanceOf(CallSiteExtraction.SessionHandleRead.class);
        assertThat(errors(schema)).isEmpty();
    }

    @Test
    void conditionMemberBinding_unknownMember_andTypeMismatch_rejectAtTheirCoordinate() {
        assertThat(errors(build(conditionSdl("sessionBoxed", "brukerId: $session.nope"))))
            .singleElement().asString().contains("Query.films").contains("no readable member 'nope'");
        assertThat(errors(build(conditionSdl("sessionWrongType", "brukerId: $session.brukerId"))))
            .singleElement().asString().contains("java.lang.String").contains("long");
        assertThat(errors(build(conditionSdl("sessionBoxedIntoPrimitive", "sokerId: $session.sokerId"))))
            .singleElement().asString().contains("sokerId");
    }

    @Test
    void conditionSessionBinding_leavesAnArgumentBesideItBoundAsAnArgument() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query {
                films(title: String): [Film!]! @condition(condition: {className: "%s",
                    method: "sessionBesideArgument", argMapping: "brukerId: $session.brukerId"})
            }
            """.formatted(CONDITIONS));

        var params = ((SqlGeneratingField) schema.field("Query", "films")).filters().stream()
            .flatMap(f -> f.callParams().stream()).toList();
        assertThat(params).extracting(p -> p.readSource()).contains(
            no.sikt.graphitron.rewrite.model.CallParam.ReadSource.ARGUMENT_MAP,
            no.sikt.graphitron.rewrite.model.CallParam.ReadSource.SESSION);
        assertThat(errors(schema)).isEmpty();
    }

    @Test
    void pathStepCondition_doesNotAdmitTheSessionSigil() {
        // The path-step @condition parses at REFERENCE_STEP, which keeps rejecting $session.
        var scan = ArgBindingMap.parseArgMapping("x: $session.brukerId",
            no.sikt.graphitron.model.grammar.ArgMappingSigil.Site.REFERENCE_STEP);
        assertThat(scan).isInstanceOf(ArgBindingMap.ParsedArgMapping.ParseError.class);
    }

    @Test
    void sessionBinding_withNoSessionHooksConfigured_rejectsNamingTheConfiguration() {
        var schema = TestSchemaHelper.buildSchema(conditionSdl("sessionBoxed", "brukerId: $session.brukerId"));
        assertThat(errors(schema)).singleElement().asString()
            .contains("$session.brukerId").contains("no method-hook <sessionState>");
    }

    @Test
    void parameterSources_theMemberVariantIsDistinctFromTheWholeHandle() {
        assertThat(new ParamSource.SessionHandleMember("a")).isNotEqualTo(new ParamSource.SessionHandle());
    }

    // ===== Where a session-reading condition cannot run =====

    private static GraphitronSchema buildFanned(String sdl) {
        return SessionHooksFixtures.withHooks(TestSchemaHelper.buildSchema(sdl,
            no.sikt.graphitron.common.configuration.TestConfiguration.testContext().withTenantColumn("film_id")),
            recordHandleHooks());
    }

    private static final String SESSION_CONDITION = """
        @condition(condition: {className: "%s", method: "sessionBoxed",
            argMapping: "brukerId: $session.brukerId"})""".formatted(CONDITIONS);

    @Test
    void sessionConditionOnAFanOutField_rejectsNamingTheFannedCoordinate() {
        var schema = buildFanned("""
            type Film @table(name: "film") { title: String }
            type Query { films: [Film!]! @tenantFanOut %s }
            """.formatted(SESSION_CONDITION));

        assertThat(errors(schema)).anySatisfy(message -> assertThat(message)
            .contains("@tenantFanOut field 'Query.films'")
            .contains("A @service bound to $session still works under fan-out"));
    }

    @Test
    void sessionConditionInASubtreeProjectedUnderAFanOutField_rejectsAtTheFannedField() {
        var schema = buildFanned("""
            type Film @table(name: "film") {
                title: String
                inventories: [Inventory!]! %s
            }
            type Inventory @table(name: "inventory") { inventoryId: Int @field(name: "inventory_id") }
            type Query { films: [Film!]! @tenantFanOut }
            """.formatted(SESSION_CONDITION));

        assertThat(errors(schema)).anySatisfy(message -> assertThat(message)
            .contains("(at 'Film.inventories')").contains("'Query.films'"));
    }

    @Test
    void sessionConditionBelowANodeType_rejects_becauseTheNodeDispatchCannotHandItTheStatementsDsl() {
        var schema = build("""
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") @node {
                id: ID! @nodeId
                inventories: [Inventory!]! %s
            }
            type Inventory @table(name: "inventory") { inventoryId: Int @field(name: "inventory_id") }
            type Query { films: [Film!]! }
            """.formatted(SESSION_CONDITION.replace("sessionBoxed", "sessionBoxed")));

        assertThat(errors(schema)).anySatisfy(message -> assertThat(message)
            .contains("(at 'Film.inventories')").contains("not supported under type 'Film'"));
    }
}
