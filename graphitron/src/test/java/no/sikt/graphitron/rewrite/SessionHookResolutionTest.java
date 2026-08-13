package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.ReflectionError;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.session.SessionHooks;
import no.sikt.graphitron.rewrite.session.SessionStateConfig;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier coverage of {@link ServiceCatalog#resolveSessionHooks}: the authored
 * {@code <mount>}/{@code <unmount>} strings reflected into the total {@link SessionHooks}
 * carrier, with every rejection landing as a typed {@link Rejection} and the carrier falling
 * back to {@link SessionHooks.NotConfigured} (never a partial resolution). The signature
 * contract itself, exactly one seam parameter as the overload selector, payload parameters as
 * named context slots, the mount's return as the handle later type-checked against the unmount,
 * is exercised against {@link SessionHookStub}'s shapes; the name-less complement reflects
 * {@code no.sikt.graphitron.codereferences.noparams.NoParamsServiceStub}, compiled without
 * {@code -parameters}.
 */
@UnitTier
class SessionHookResolutionTest {

    private static final String STUB = "no.sikt.graphitron.rewrite.SessionHookStub";
    private static final String NOPARAMS_STUB =
        "no.sikt.graphitron.codereferences.noparams.NoParamsServiceStub";

    private static ServiceCatalog newCatalog() {
        return new ServiceCatalog(new BuildContext(null, null, stubRewriteContext()));
    }

    private static RewriteContext stubRewriteContext() {
        return new RewriteContext(
            java.util.List.of(),
            java.nio.file.Path.of("."), "SessionHookResolutionTest",
            java.nio.file.Path.of("."),
            "unused",
            "unused");
    }

    private static ServiceCatalog.SessionHookResolution resolve(String mount, String unmount) {
        return newCatalog().resolveSessionHooks(SessionStateConfig.from(mount, unmount));
    }

    private static String ref(String method) {
        return STUB + "#" + method;
    }

    // ===== Acceptance =====

    @Test
    void noneConfig_resolvesToNotConfiguredWithNoRejections() {
        var result = newCatalog().resolveSessionHooks(SessionStateConfig.none());
        assertThat(result.hooks()).isSameAs(SessionHooks.NotConfigured.INSTANCE);
        assertThat(result.rejections()).isEmpty();
    }

    @Test
    void handleReturningMountWithMatchingUnmount_resolvesToHandled() {
        var result = resolve(ref("mountHandled"), ref("unmountHandled"));

        assertThat(result.rejections()).isEmpty();
        assertThat(result.hooks()).isInstanceOf(SessionHooks.Handled.class);
        var handled = (SessionHooks.Handled) result.hooks();
        assertThat(handled.handleType().toString()).isEqualTo("java.lang.String");
        assertThat(handled.mount().methodName()).isEqualTo("mountHandled");
        assertThat(handled.unmount()).isPresent();
    }

    @Test
    void voidMountWithoutUnmount_resolvesToHandleLess() {
        var result = resolve(ref("mountHandleLess"), null);

        assertThat(result.rejections()).isEmpty();
        assertThat(result.hooks()).isInstanceOf(SessionHooks.HandleLess.class);
        assertThat(result.hooks().unmountRef()).isEmpty();
    }

    @Test
    void seamParameterKind_isDecidedByTheDeclaredType() {
        // mountHandled declares Configuration; mountHandleLess declares Connection. The seam
        // kind rides the resolved parameter, so the emitted hook builds the right value.
        var configuration = resolve(ref("mountHandled"), null).hooks().mountRef().orElseThrow();
        var seam = configuration.params().stream()
            .filter(p -> p.source() instanceof ParamSource.SessionSeam)
            .findFirst().orElseThrow();
        assertThat(((ParamSource.SessionSeam) seam.source()).kind())
            .isEqualTo(ParamSource.SessionSeam.Kind.CONFIGURATION);

        var connection = resolve(ref("mountHandleLess"), null).hooks().mountRef().orElseThrow();
        var connSeam = connection.params().stream()
            .filter(p -> p.source() instanceof ParamSource.SessionSeam)
            .findFirst().orElseThrow();
        assertThat(((ParamSource.SessionSeam) connSeam.source()).kind())
            .isEqualTo(ParamSource.SessionSeam.Kind.CONNECTION);
    }

    @Test
    void mountPayloadParameters_becomeNamedContextParamsInDeclarationOrder() {
        var result = resolve(ref("mountMultiPayload"), null);

        assertThat(result.rejections()).isEmpty();
        var payload = result.hooks().payloadParams();
        assertThat(payload).extracting(MethodRef.Param::name).containsExactly("claims", "fnr");
        assertThat(payload).allSatisfy(p ->
            assertThat(p.source()).isInstanceOf(ParamSource.Context.class));
        assertThat(payload).extracting(MethodRef.Param::typeName)
            .containsExactly("java.lang.String", "java.lang.Long");
    }

    @Test
    void handleIgnoringUnmount_seamOnlySignature_acceptedAgainstAHandleReturningMount() {
        var result = resolve(ref("mountHandled"), ref("unmountSeamOnly"));

        assertThat(result.rejections()).isEmpty();
        assertThat(result.hooks()).isInstanceOf(SessionHooks.Handled.class);
        var unmount = result.hooks().unmountRef().orElseThrow();
        assertThat(unmount.params()).allSatisfy(p ->
            assertThat(p.source()).isInstanceOf(ParamSource.SessionSeam.class));
    }

    @Test
    void unmountReturnValue_isAcceptedAndCarriedAsDeclared() {
        // The runtime discards it; resolution neither rejects nor rewrites the declaration.
        var result = resolve(ref("mountHandled"), ref("unmountReturning"));
        assertThat(result.rejections()).isEmpty();
        assertThat(result.hooks().unmountRef().orElseThrow().returnType().toString())
            .isEqualTo("int");
    }

    @Test
    void tripleOverload_resolvesToTheOneSeamBearingDeclaration() {
        // jOOQ's generated Routines shape: the executing method plus field-expression
        // overloads of the same name. The seam filter picks the executing one.
        var result = resolve(ref("overloaded"), null);

        assertThat(result.rejections()).isEmpty();
        var mount = result.hooks().mountRef().orElseThrow();
        assertThat(mount.params()).hasSize(2);
        assertThat(result.hooks().stringConstructiblePayload()).contains("claims");
    }

    // ===== Rejections (each falls back to NotConfigured, total) =====

    @Test
    void unresolvableClass_rejectsWithClassNotLoaded() {
        var result = resolve("com.example.NoSuchClass#mount", null);

        assertThat(result.hooks()).isSameAs(SessionHooks.NotConfigured.INSTANCE);
        assertThat(result.rejections()).singleElement()
            .isInstanceOf(ReflectionError.ClassNotLoaded.class);
    }

    @Test
    void unresolvableMethod_rejectsNamingTheDeclaredAlternatives() {
        var result = resolve(ref("noSuchMethod"), null);

        assertThat(result.hooks()).isSameAs(SessionHooks.NotConfigured.INSTANCE);
        assertThat(result.rejections()).singleElement()
            .isInstanceOf(Rejection.AuthorError.UnknownName.class);
    }

    @Test
    void nonStaticMethod_rejectsWithHookNotStatic() {
        var result = resolve(ref("notStatic"), null);

        assertThat(result.hooks()).isSameAs(SessionHooks.NotConfigured.INSTANCE);
        assertThat(result.rejections()).singleElement()
            .isInstanceOf(ReflectionError.HookNotStatic.class);
    }

    @Test
    void noSeamParameter_rejectsNamingEveryInspectedCandidate() {
        var result = resolve(ref("noSeam"), null);

        assertThat(result.hooks()).isSameAs(SessionHooks.NotConfigured.INSTANCE);
        assertThat(result.rejections()).singleElement()
            .isInstanceOfSatisfying(ReflectionError.SeamParameterMissing.class, missing ->
                assertThat(missing.candidateSignatures())
                    .containsExactly("noSeam(java.lang.String)"));
    }

    @Test
    void twoSeamParameters_isNotAQualifyingCandidateEither() {
        // "Exactly one" cuts both ways: a two-seam declaration is as unresolvable as none.
        var result = resolve(ref("twoSeams"), null);

        assertThat(result.hooks()).isSameAs(SessionHooks.NotConfigured.INSTANCE);
        assertThat(result.rejections()).singleElement()
            .isInstanceOf(ReflectionError.SeamParameterMissing.class);
    }

    @Test
    void twoSeamBearingOverloads_rejectAsAmbiguousNamingTheQualifyingOnes() {
        var result = resolve(ref("ambiguous"), null);

        assertThat(result.hooks()).isSameAs(SessionHooks.NotConfigured.INSTANCE);
        assertThat(result.rejections()).singleElement()
            .isInstanceOfSatisfying(ReflectionError.SeamCandidateAmbiguous.class, ambiguous ->
                assertThat(ambiguous.candidateSignatures()).hasSize(2));
    }

    @Test
    void declaredCheckedException_rejectsNamingTheExceptions() {
        var result = resolve(ref("throwsChecked"), null);

        assertThat(result.hooks()).isSameAs(SessionHooks.NotConfigured.INSTANCE);
        assertThat(result.rejections()).singleElement()
            .isInstanceOfSatisfying(ReflectionError.HookThrowsChecked.class, checked ->
                assertThat(checked.declaredExceptions()).containsExactly("java.io.IOException"));
    }

    @Test
    void handleTypeMismatch_rejectsNamingBothSignatures() {
        var result = resolve(ref("mountIntHandle"), ref("unmountHandled"));

        assertThat(result.hooks()).isSameAs(SessionHooks.NotConfigured.INSTANCE);
        assertThat(result.rejections()).singleElement()
            .isInstanceOfSatisfying(ReflectionError.HandleTypeMismatch.class, mismatch -> {
                assertThat(mismatch.handleTypeSimple()).isEqualTo("java.lang.Integer");
                assertThat(mismatch.unmountParamTypeSimple()).isEqualTo("java.lang.String");
            });
    }

    @Test
    void voidMountWithHandleTakingUnmount_rejectsAsMismatchNamingVoid() {
        var result = resolve(ref("mountHandleLess"), ref("unmountHandled"));

        assertThat(result.hooks()).isSameAs(SessionHooks.NotConfigured.INSTANCE);
        assertThat(result.rejections()).singleElement()
            .isInstanceOfSatisfying(ReflectionError.HandleTypeMismatch.class, mismatch ->
                assertThat(mismatch.handleTypeSimple()).isEqualTo("void"));
    }

    @Test
    void payloadParameterWithoutCompiledName_rejectsThroughTheParametersGate() {
        var result = resolve(NOPARAMS_STUB + "#mountNameless", null);

        assertThat(result.hooks()).isSameAs(SessionHooks.NotConfigured.INSTANCE);
        assertThat(result.rejections()).singleElement()
            .isInstanceOf(ReflectionError.ParameterNamesMissing.class);
    }

    @Test
    void mountAndUnmountBothBroken_collectBothRejections() {
        // Resolution is not first-error-wins across the pair: each reference reflects
        // independently so the author sees the whole repair in one build.
        var result = resolve(ref("noSeam"), ref("throwsChecked"));

        assertThat(result.hooks()).isSameAs(SessionHooks.NotConfigured.INSTANCE);
        assertThat(result.rejections()).hasSize(2);
    }
}
