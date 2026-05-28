package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.ArgPath;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.MappingEntry;
import no.sikt.graphitron.rewrite.model.ServiceMethodCall;
import no.sikt.graphitron.rewrite.model.ValueShape;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R238 emitter unit-tier tests. Asserts on the statement-list shape returned by
 * {@link ServiceMethodCallEmitter#emit} for the carrier arms ({@link ServiceMethodCall.Static},
 * {@link ServiceMethodCall.Instance}) and {@link MappingEntry} arms (FromArg/FromContext/FromDsl).
 * Renders {@code CodeBlock.toString()} once per case to anchor the structural intent without
 * pinning the full generated body.
 */
@UnitTier
class ServiceMethodCallEmitterTest {

    private static final String OUTPUT_PACKAGE = "com.example.gen";

    @Test
    void emit_static_singleScalarFromArg_producesAssignmentAndFinalCall() {
        var stringType = ClassName.get(String.class);
        var entry = new MappingEntry.FromArg("title",
            new ValueShape.Scalar(stringType, ArgPath.head("title"), new CallSiteExtraction.Direct()));
        var call = new ServiceMethodCall.Static(
            "com.example.Svc", "findByTitle", List.of(entry), stringType);

        var stmts = ServiceMethodCallEmitter.emit(call, OUTPUT_PACKAGE);

        assertThat(stmts).hasSize(2);
        assertThat(stmts.get(0).toString())
            .contains("title")
            .contains("env.getArgument(\"title\")");
        assertThat(stmts.get(1).toString())
            .contains("result")
            .contains("com.example.Svc.findByTitle(title)");
    }

    @Test
    void emit_static_fromDslMethodArg_emitsDslPreludeOnceAndUsesDslInCallList() {
        var entry = new MappingEntry.FromDsl();
        var call = new ServiceMethodCall.Static(
            "com.example.Svc", "queryAll", List.of(entry), ClassName.get(String.class));

        var stmts = ServiceMethodCallEmitter.emit(call, OUTPUT_PACKAGE);

        assertThat(stmts).hasSize(2);
        assertThat(stmts.get(0).toString())
            .as("DSL prelude emitted once when method has a FromDsl entry")
            .contains("DSLContext dsl")
            .contains("getDslContext(env)");
        assertThat(stmts.get(1).toString()).contains("queryAll(dsl)");
    }

    @Test
    void emit_static_fromContext_emitsCastedContextLookup() {
        var stringType = ClassName.get(String.class);
        var entry = new MappingEntry.FromContext("userId", stringType, "userId");
        var call = new ServiceMethodCall.Static(
            "com.example.Svc", "doThing", List.of(entry), stringType);

        var stmts = ServiceMethodCallEmitter.emit(call, OUTPUT_PACKAGE);

        assertThat(stmts.get(0).toString())
            .contains("userId")
            .contains("getContextArgument(env, \"userId\")");
        assertThat(stmts.get(1).toString()).contains("doThing(userId)");
    }

    @Test
    void emit_instance_emitsDslPreludeAndNewServiceCtor() {
        var entry = new MappingEntry.FromArg("title",
            new ValueShape.Scalar(ClassName.get(String.class),
                ArgPath.head("title"), new CallSiteExtraction.Direct()));
        var call = new ServiceMethodCall.Instance(
            "com.example.Svc",
            List.of(new MappingEntry.FromDsl()),
            "findByTitle",
            List.of(entry),
            ClassName.get(String.class));

        var stmts = ServiceMethodCallEmitter.emit(call, OUTPUT_PACKAGE);

        assertThat(stmts).hasSizeGreaterThanOrEqualTo(3);
        assertThat(stmts.get(0).toString())
            .as("Instance carriers always emit the DSL prelude")
            .contains("DSLContext dsl");
        TypeName lastStmtMarker = ClassName.get("com.example", "Svc");
        assertThat(stmts.getLast().toString())
            .contains("new com.example.Svc(dsl)")
            .contains(".findByTitle(title)");
    }

    @Test
    void emit_instance_methodAlsoHasFromDsl_dslPreludeEmittedOnce() {
        // Cross-round case: instance ctor binds dsl; method also takes DSLContext. Both call
        // positions read the shared `dsl` local; prelude appears exactly once.
        var call = new ServiceMethodCall.Instance(
            "com.example.Svc",
            List.of(new MappingEntry.FromDsl()),
            "doThing",
            List.of(new MappingEntry.FromDsl()),
            ClassName.get(String.class));

        var stmts = ServiceMethodCallEmitter.emit(call, OUTPUT_PACKAGE);

        long preludeCount = stmts.stream()
            .map(Object::toString)
            .filter(s -> s.contains("DSLContext dsl"))
            .count();
        assertThat(preludeCount)
            .as("prelude declared once across both rounds")
            .isEqualTo(1);
        // The method-args call positions read 'dsl' too.
        assertThat(stmts.getLast().toString())
            .contains("new com.example.Svc(dsl)")
            .contains(".doThing(dsl)");
    }

    @Test
    void emit_static_noEntries_emitsBareCall() {
        var call = new ServiceMethodCall.Static(
            "com.example.Svc", "doThing", List.of(), ClassName.get(String.class));

        var stmts = ServiceMethodCallEmitter.emit(call, OUTPUT_PACKAGE);

        assertThat(stmts).hasSize(1);
        assertThat(stmts.getFirst().toString())
            .contains("result = com.example.Svc.doThing()");
    }
}
