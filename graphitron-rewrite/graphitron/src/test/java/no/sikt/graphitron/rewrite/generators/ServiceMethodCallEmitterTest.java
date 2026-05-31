package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
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

    @Test
    void emit_static_recordInputArg_callsCreateBeanHelper() {
        // RecordInput at a top-level path: emitter calls the create<Bean>() helper produced by
        // InputBeanInstantiationEmitter, passing the raw env argument map through unchanged.
        var beanClass = ClassName.get("com.example", "MyInput");
        var stringType = ClassName.get(String.class);
        var fields = List.of(new ValueShape.FieldBinding(
            "title", "title",
            new ValueShape.Scalar(stringType,
                new ArgPath("input", List.of(new ArgPath.Segment("title", false))),
                new CallSiteExtraction.Direct())));
        var entry = new MappingEntry.FromArg("input",
            new ValueShape.RecordInput(beanClass, fields));
        var call = new ServiceMethodCall.Static(
            "com.example.Svc", "save", List.of(entry), stringType);

        var stmts = ServiceMethodCallEmitter.emit(call, OUTPUT_PACKAGE);

        assertThat(stmts).hasSize(2);
        assertThat(stmts.get(0).toString())
            .as("RecordInput var-decl invokes the singular createBean helper")
            .contains("createMyInput(env.getArgument(\"input\"))")
            .contains("com.example.MyInput input");
        assertThat(stmts.get(1).toString()).contains("save(input)");
    }

    @Test
    void emit_static_javaBeanInputArg_callsCreateBeanHelper() {
        var beanClass = ClassName.get("com.example", "MyBean");
        var stringType = ClassName.get(String.class);
        var fields = List.of(new ValueShape.FieldBinding(
            "title", "title",
            new ValueShape.Scalar(stringType,
                new ArgPath("input", List.of(new ArgPath.Segment("title", false))),
                new CallSiteExtraction.Direct())));
        var entry = new MappingEntry.FromArg("input",
            new ValueShape.JavaBeanInput(beanClass, fields));
        var call = new ServiceMethodCall.Static(
            "com.example.Svc", "save", List.of(entry), stringType);

        var stmts = ServiceMethodCallEmitter.emit(call, OUTPUT_PACKAGE);

        assertThat(stmts).hasSize(2);
        assertThat(stmts.get(0).toString())
            .as("JavaBeanInput uses the same singular helper convention as RecordInput")
            .contains("createMyBean(env.getArgument(\"input\"))");
    }

    @Test
    void emit_static_multiSegmentScalarPath_emitsNullSafeMapChain() {
        // NestedInputField-style scalar with a two-segment path. The walker unwraps to
        // ValueShape.Scalar with deeperSegments=[items, id]; the emitter must produce a
        // null-safe Map traversal that compiles under -source 17 (wildcard-parameterised
        // instanceof, no unconditional Map pattern).
        var intType = ClassName.get(Integer.class);
        var entry = new MappingEntry.FromArg("filmId",
            new ValueShape.Scalar(intType,
                new ArgPath("input", List.of(
                    new ArgPath.Segment("items", false),
                    new ArgPath.Segment("id", false))),
                new CallSiteExtraction.Direct()));
        var call = new ServiceMethodCall.Static(
            "com.example.Svc", "find", List.of(entry), intType);

        var stmts = ServiceMethodCallEmitter.emit(call, OUTPUT_PACKAGE);

        String varDecl = stmts.get(0).toString();
        assertThat(varDecl)
            .as("Outer arg unwraps via instanceof Map<?, ?> map1 binding")
            .contains("env.getArgument(\"input\") instanceof java.util.Map<?, ?> map1");
        assertThat(varDecl)
            .as("Inner segment rebinds via instanceof Map<?, ?> map2 against map1.get(...)")
            .contains("map1.get(\"items\") instanceof java.util.Map<?, ?> map2");
        assertThat(varDecl)
            .as("Leaf segment casts the final Map.get to the declared Java type")
            .contains("map2.get(\"id\")")
            .contains("java.lang.Integer")
            .endsWith(": null");
    }

    @Test
    void emit_static_listBearingPath_emitsStreamMapChain() {
        // ArgPath with a liftsList=true intermediate segment: argMapping `filmIds: input.items.id`
        // where SDL `items: [FilmIdItem!]!`. The emitter must dispatch at the list segment to
        // `list.stream().map(elem -> ...).toList()` rather than nested Map.get. The leaf cast strips
        // one List<> wrap per liftsList segment, so the inner `map.get("id")` cast is Integer (not
        // List<Integer>) — wrapping it in .toList() produces the declared List<Integer>.
        var intType = ClassName.get(Integer.class);
        var listOfInt = ParameterizedTypeName.get(ClassName.get(java.util.List.class), intType);
        var entry = new MappingEntry.FromArg("filmIds",
            new ValueShape.Scalar(listOfInt,
                new ArgPath("input", List.of(
                    new ArgPath.Segment("items", true),
                    new ArgPath.Segment("id", false))),
                new CallSiteExtraction.Direct()));
        var call = new ServiceMethodCall.Static(
            "com.example.Svc", "find", List.of(entry), intType);

        var stmts = ServiceMethodCallEmitter.emit(call, OUTPUT_PACKAGE);

        String varDecl = stmts.get(0).toString();
        assertThat(varDecl)
            .as("Outer arg rebinds as a Map (wildcard-parameterised for -source 17)")
            .contains("env.getArgument(\"input\") instanceof java.util.Map<?, ?> map1");
        assertThat(varDecl)
            .as("List-bearing segment narrows map1.get(items) to List<?> and streams")
            .contains("map1.get(\"items\") instanceof java.util.List<?> list2")
            .contains("list2.stream().map(elem3")
            .contains(".toList()");
        assertThat(varDecl)
            .as("Inside the lambda the per-element value rebinds as Map and reads the leaf key, "
                + "cast to the stripped inner type (Integer) — NOT the declared List<Integer>")
            .contains("elem3 instanceof java.util.Map<?, ?> map4")
            .contains("(java.lang.Integer) map4.get(\"id\")");
    }

    @Test
    void emit_static_twoListBearingPath_emitsNestedStreamMap() {
        // Two-list-deep argMapping `filmIdGroups: input.groups.items.id` from R84 Phase D-list:
        // streams over `groups`, then over each group's `items`. The leaf cast lands on
        // List<List<Integer>>; the walker uses Direct as the leaf transform.
        var intType = ClassName.get(Integer.class);
        var listOfList = ParameterizedTypeName.get(ClassName.get(java.util.List.class),
            ParameterizedTypeName.get(ClassName.get(java.util.List.class), intType));
        var entry = new MappingEntry.FromArg("filmIdGroups",
            new ValueShape.Scalar(listOfList,
                new ArgPath("input", List.of(
                    new ArgPath.Segment("groups", true),
                    new ArgPath.Segment("items", true),
                    new ArgPath.Segment("id", false))),
                new CallSiteExtraction.Direct()));
        var call = new ServiceMethodCall.Static(
            "com.example.Svc", "find", List.of(entry), intType);

        var stmts = ServiceMethodCallEmitter.emit(call, OUTPUT_PACKAGE);

        String varDecl = stmts.get(0).toString();
        // Two streams, one per liftsList segment; nested .toList() calls.
        assertThat(varDecl.split("\\.stream\\(\\)\\.map\\(", -1)).hasSize(3);
        assertThat(varDecl.split("\\.toList\\(\\)", -1)).hasSize(3);
    }

    @Test
    void emit_static_listOfRecordInputArg_callsCreateBeanListHelper() {
        var beanClass = ClassName.get("com.example", "MyInput");
        var stringType = ClassName.get(String.class);
        var fields = List.of(new ValueShape.FieldBinding(
            "title", "title",
            new ValueShape.Scalar(stringType,
                new ArgPath("inputs", List.of(new ArgPath.Segment("title", false))),
                new CallSiteExtraction.Direct())));
        var listShape = new ValueShape.ListOf(ArgPath.head("inputs"),
            new ValueShape.RecordInput(beanClass, fields));
        var entry = new MappingEntry.FromArg("inputs", listShape);
        var call = new ServiceMethodCall.Static(
            "com.example.Svc", "saveAll", List.of(entry), stringType);

        var stmts = ServiceMethodCallEmitter.emit(call, OUTPUT_PACKAGE);

        assertThat(stmts).hasSize(2);
        assertThat(stmts.get(0).toString())
            .as("ListOf(RecordInput) invokes the plural createBeanList helper")
            .contains("createMyInputList(env.getArgument(\"inputs\"))");
        assertThat(stmts.get(1).toString()).contains("saveAll(inputs)");
    }
}
