package no.sikt.graphitron.rewrite.walker;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.PathExpr;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.MappingEntry;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.ServiceMethodCall;
import no.sikt.graphitron.rewrite.model.ServiceMethodCallError;
import no.sikt.graphitron.rewrite.model.ValueShape;
import no.sikt.graphitron.rewrite.model.WalkerResult;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R238 walker unit-tier tests. Exercises the {@link ServiceMethodCallWalker#walk} translator
 * over each {@link MappingEntry} arm and the static-vs-instance {@link ServiceMethodCall} fork,
 * plus the cross-round {@code MultipleDslContextSlots} invariant.
 *
 * <p>The translator's substrate today is a resolved {@link MethodRef.Service}; the tests
 * build that input directly rather than going through the SDL+classloader path. When the
 * walker absorbs fresh reflection (follow-up to R238), these cases re-anchor on the SDL fixture.
 */
@UnitTier
class ServiceMethodCallWalkerTest {

    private final ServiceMethodCallWalker walker = new ServiceMethodCallWalker();

    @Test
    void walk_staticMethod_dslSlot_producesStaticCarrierWithFromDsl() {
        var method = service("com.example.Svc", "doThing", TypeName.OBJECT,
            new MethodRef.CallShape.Static(true),
            param("dslCtx", ClassName.get("org.jooq", "DSLContext"), new ParamSource.DslContext()));

        var result = walker.walk(null, method);

        assertThat(result).isInstanceOf(WalkerResult.Ok.class);
        ServiceMethodCall call = ((WalkerResult.Ok<ServiceMethodCall>) result).carrier();
        assertThat(call).isInstanceOf(ServiceMethodCall.Static.class);
        assertThat(call.methodArgs()).hasSize(1).first().isInstanceOf(MappingEntry.FromDsl.class);
    }

    @Test
    void walk_instanceWithDslHolder_addsFromDslCtorArg() {
        var method = service("com.example.Svc", "doThing", TypeName.OBJECT,
            new MethodRef.CallShape.InstanceWithDslHolder());

        var call = ok(walker.walk(null, method));

        assertThat(call).isInstanceOf(ServiceMethodCall.Instance.class);
        var inst = (ServiceMethodCall.Instance) call;
        assertThat(inst.ctorArgs()).hasSize(1).first().isInstanceOf(MappingEntry.FromDsl.class);
        assertThat(inst.methodArgs()).isEmpty();
    }

    @Test
    void walk_contextParam_producesFromContextEntry() {
        var stringType = ClassName.get(String.class);
        var method = service("com.example.Svc", "doThing", TypeName.OBJECT,
            new MethodRef.CallShape.Static(false),
            param("userId", stringType, new ParamSource.Context()));

        var call = ok(walker.walk(null, method));

        assertThat(call.methodArgs()).hasSize(1);
        var entry = (MappingEntry.FromContext) call.methodArgs().getFirst();
        assertThat(entry.javaName()).isEqualTo("userId");
        assertThat(entry.contextKey()).isEqualTo("userId");
        assertThat(entry.javaType()).isEqualTo(stringType);
    }

    @Test
    void walk_argParam_directLeaf_producesFromArgWithScalarShape() {
        var stringType = ClassName.get(String.class);
        var arg = new ParamSource.Arg(new CallSiteExtraction.Direct(), new PathExpr.Head("title"));
        var method = service("com.example.Svc", "doThing", TypeName.OBJECT,
            new MethodRef.CallShape.Static(false),
            param("title", stringType, arg));

        var call = ok(walker.walk(null, method));

        var fromArg = (MappingEntry.FromArg) call.methodArgs().getFirst();
        assertThat(fromArg.javaName()).isEqualTo("title");
        var scalar = (ValueShape.Scalar) fromArg.shape();
        assertThat(scalar.javaType()).isEqualTo(stringType);
        assertThat(scalar.sdlPath().outerArgName()).isEqualTo("title");
        assertThat(scalar.sdlPath().deeperSegments()).isEmpty();
        assertThat(scalar.leafTransform()).isInstanceOf(CallSiteExtraction.Direct.class);
    }

    @Test
    void walk_argParam_enumValueOfLeaf_preservesLeafTransform() {
        var enumType = ClassName.bestGuess("com.example.Genre");
        var arg = new ParamSource.Arg(
            new CallSiteExtraction.EnumValueOf("com.example.Genre"),
            new PathExpr.Head("genre"));
        var method = service("com.example.Svc", "doThing", TypeName.OBJECT,
            new MethodRef.CallShape.Static(false),
            param("genre", enumType, arg));

        var call = ok(walker.walk(null, method));

        var scalar = (ValueShape.Scalar) ((MappingEntry.FromArg) call.methodArgs().getFirst()).shape();
        assertThat(scalar.leafTransform()).isInstanceOf(CallSiteExtraction.EnumValueOf.class);
        assertThat(((CallSiteExtraction.EnumValueOf) scalar.leafTransform()).enumClassName())
            .isEqualTo("com.example.Genre");
    }

    @Test
    void walk_argParam_listOfBean_wrapsInListOf() {
        var beanClass = ClassName.get("com.example", "Bean");
        var listOfBean = ParameterizedTypeName.get(ClassName.get(List.class), beanClass);
        var bean = new CallSiteExtraction.InputBean(beanClass,
            CallSiteExtraction.InputBean.Target.RECORD,
            List.of(new CallSiteExtraction.FieldBinding(
                "name", "name", new CallSiteExtraction.Direct(), false, String.class.getName())));
        var arg = new ParamSource.Arg(bean, new PathExpr.Head("beans"));
        var method = service("com.example.Svc", "doThing", TypeName.OBJECT,
            new MethodRef.CallShape.Static(false),
            param("beans", listOfBean, arg));

        var call = ok(walker.walk(null, method));

        var shape = ((MappingEntry.FromArg) call.methodArgs().getFirst()).shape();
        assertThat(shape).isInstanceOf(ValueShape.ListOf.class);
        assertThat(((ValueShape.ListOf) shape).elementShape()).isInstanceOf(ValueShape.RecordInput.class);
    }

    @Test
    void walk_argParam_singleBean_recordTarget_producesRecordInput() {
        var beanClass = ClassName.get("com.example", "Bean");
        var bean = new CallSiteExtraction.InputBean(beanClass,
            CallSiteExtraction.InputBean.Target.RECORD,
            List.of(new CallSiteExtraction.FieldBinding(
                "name", "name", new CallSiteExtraction.Direct(), false, String.class.getName())));
        var arg = new ParamSource.Arg(bean, new PathExpr.Head("input"));
        var method = service("com.example.Svc", "doThing", TypeName.OBJECT,
            new MethodRef.CallShape.Static(false),
            param("input", beanClass, arg));

        var call = ok(walker.walk(null, method));

        var shape = ((MappingEntry.FromArg) call.methodArgs().getFirst()).shape();
        assertThat(shape).isInstanceOf(ValueShape.RecordInput.class);
        assertThat(((ValueShape.RecordInput) shape).javaClass()).isEqualTo(beanClass);
    }

    @Test
    void walk_argParam_javaBeanTarget_producesJavaBeanInput() {
        var beanClass = ClassName.get("com.example", "Bean");
        var bean = new CallSiteExtraction.InputBean(beanClass,
            CallSiteExtraction.InputBean.Target.JAVA_BEAN,
            List.of(new CallSiteExtraction.FieldBinding(
                "name", "name", new CallSiteExtraction.Direct(), false, String.class.getName())));
        var arg = new ParamSource.Arg(bean, new PathExpr.Head("input"));
        var method = service("com.example.Svc", "doThing", TypeName.OBJECT,
            new MethodRef.CallShape.Static(false),
            param("input", beanClass, arg));

        var call = ok(walker.walk(null, method));

        var shape = ((MappingEntry.FromArg) call.methodArgs().getFirst()).shape();
        assertThat(shape).isInstanceOf(ValueShape.JavaBeanInput.class);
    }

    @Test
    void walk_multipleDslSlots_inMethodRound_raisesError() {
        var dslType = ClassName.get("org.jooq", "DSLContext");
        var method = service("com.example.Svc", "doThing", TypeName.OBJECT,
            new MethodRef.CallShape.Static(true),
            param("dsl1", dslType, new ParamSource.DslContext()),
            param("dsl2", dslType, new ParamSource.DslContext()));

        var result = walker.walk(null, method);

        assertThat(result).isInstanceOf(WalkerResult.Err.class);
        var err = (WalkerResult.Err<ServiceMethodCall>) result;
        assertThat(err.errors()).anyMatch(e ->
            e instanceof ServiceMethodCallError.MultipleDslContextSlots ms
                && ms.round() == ServiceMethodCallError.Round.METHOD);
    }

    @Test
    void walk_carrierJavaReturnType_passesThroughFromMethodRef() {
        var returnType = ClassName.get("com.example", "Result");
        var method = service("com.example.Svc", "doThing", returnType,
            new MethodRef.CallShape.Static(false));

        var call = ok(walker.walk(null, method));

        assertThat(call.javaReturnType()).isEqualTo(returnType);
    }

    @Test
    void walk_nestedInputField_flattensToMultiSegmentArgPath() {
        var stringType = ClassName.get(String.class);
        // input.where.id
        PathExpr path = new PathExpr.Step(
            new PathExpr.Step(new PathExpr.Head("input"), "where", false),
            "id", false);
        var nested = new CallSiteExtraction.NestedInputField("input",
            List.of("where", "id"), new CallSiteExtraction.Direct());
        var arg = new ParamSource.Arg(nested, path);
        var method = service("com.example.Svc", "doThing", TypeName.OBJECT,
            new MethodRef.CallShape.Static(false),
            param("id", stringType, arg));

        var call = ok(walker.walk(null, method));

        var scalar = (ValueShape.Scalar) ((MappingEntry.FromArg) call.methodArgs().getFirst()).shape();
        assertThat(scalar.sdlPath().outerArgName()).isEqualTo("input");
        assertThat(scalar.sdlPath().deeperSegments()).containsExactly("where", "id");
    }

    // ===== helpers =====

    private static ServiceMethodCall ok(WalkerResult<ServiceMethodCall> r) {
        assertThat(r).isInstanceOf(WalkerResult.Ok.class);
        return ((WalkerResult.Ok<ServiceMethodCall>) r).carrier();
    }

    private static MethodRef.Service service(
        String className, String methodName, TypeName returnType,
        MethodRef.CallShape callShape, MethodRef.Param... params
    ) {
        return new MethodRef.Service(className, methodName, returnType,
            List.of(params), List.of(), callShape);
    }

    private static MethodRef.Param param(String name, TypeName javaType, ParamSource source) {
        return new MethodRef.Param.Typed(name, javaType.toString(), javaType, source);
    }
}
