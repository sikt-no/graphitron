package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.ClassAccessorResolver.ArgShape;
import no.sikt.graphitron.rewrite.ClassAccessorResolver.Candidate;
import no.sikt.graphitron.rewrite.ClassAccessorResolver.CandidateKind;
import no.sikt.graphitron.rewrite.ClassAccessorResolver.CandidateOrder;
import no.sikt.graphitron.rewrite.ClassAccessorResolver.PerArgument;
import no.sikt.graphitron.rewrite.model.AccessorProbe;
import no.sikt.graphitron.rewrite.model.AccessorResolution;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier coverage for the shared candidate enumeration in {@link ClassAccessorResolver}: the
 * name rules, the {@code is}-gate, the member filter, and the per-kind parameter-shape rule, driven
 * directly against small synthetic classes (a pure reflection surface). Plus the order-bridge
 * meta-test pinning {@link ClassAccessorResolver#forBackingClass} against the class-shape decision
 * {@link TypeBuilder#buildResultTypeFromClass} derives its emission-side order from.
 */
@UnitTier
class ClassAccessorResolverTest {

    private static final Set<CandidateKind> ALL_KINDS = EnumSet.allOf(CandidateKind.class);
    private static final PerArgument NO_ARGS = new PerArgument(List.of());

    // ---- synthetic fixtures ----

    static class DualPojo {
        public String primary() { return ""; }
        public String getPrimary() { return ""; }
    }

    record DualRecord(String primary) {
        public String getPrimary() { return ""; }
    }

    static class BoolPojo {
        public boolean isActive() { return true; }
    }

    static class NonBooleanIsPojo {
        public String isActive() { return ""; }
    }

    static class ArgPojo {
        public String value(String x) { return x; }
    }

    static class FieldPojo {
        public String label = "";
    }

    static class BaseAccessor {
        public String inherited() { return ""; }
    }

    static class SubAccessor extends BaseAccessor {}

    static class StaticPojo {
        public static String getThing() { return ""; }
    }

    interface HasValue { Object value(); }

    static class CovariantValue implements HasValue {
        @Override public String value() { return ""; }
    }

    record RecordImplementingInterface(String primary) implements HasValue {
        @Override public String value() { return primary; }
    }

    // ---- helpers ----

    private static Candidate.Accepted firstAccepted(List<Candidate> candidates) {
        return candidates.stream()
            .filter(c -> c instanceof Candidate.Accepted)
            .map(c -> (Candidate.Accepted) c)
            .findFirst()
            .orElse(null);
    }

    private static boolean hasNearMiss(List<Candidate> candidates) {
        return candidates.stream().anyMatch(c -> c instanceof Candidate.NearMiss);
    }

    // ---- candidate order ----

    @Test
    void pojoOrder_prefersGetterOverBareName() {
        var candidates = ClassAccessorResolver.enumerate(DualPojo.class, "primary",
            CandidateOrder.POJO_FIRST, ALL_KINDS, NO_ARGS);
        var first = firstAccepted(candidates);
        assertThat(first).isNotNull();
        assertThat(first.memberName()).isEqualTo("getPrimary");
        assertThat(first.prefixed()).isTrue();
    }

    @Test
    void recordOrder_prefersBareNameOverGetter() {
        var candidates = ClassAccessorResolver.enumerate(DualRecord.class, "primary",
            CandidateOrder.RECORD_FIRST, ALL_KINDS, NO_ARGS);
        var first = firstAccepted(candidates);
        assertThat(first).isNotNull();
        assertThat(first.memberName()).isEqualTo("primary");
        assertThat(first.prefixed()).isFalse();
    }

    // ---- is-gate ----

    @Test
    void isPrefix_matchesOnlyBooleanReturn() {
        var candidates = ClassAccessorResolver.enumerate(BoolPojo.class, "active",
            CandidateOrder.POJO_FIRST, ALL_KINDS, NO_ARGS);
        var first = firstAccepted(candidates);
        assertThat(first).isNotNull();
        assertThat(first.memberName()).isEqualTo("isActive");
    }

    @Test
    void isPrefix_nonBooleanReturn_isGatedNearMissNotMatch() {
        var candidates = ClassAccessorResolver.enumerate(NonBooleanIsPojo.class, "active",
            CandidateOrder.POJO_FIRST, ALL_KINDS, NO_ARGS);
        assertThat(firstAccepted(candidates)).isNull();
        assertThat(hasNearMiss(candidates)).isTrue();

        var probe = ClassAccessorResolver.probe(NonBooleanIsPojo.class, "active",
            NO_ARGS, CandidateOrder.POJO_FIRST);
        assertThat(probe).isInstanceOf(AccessorProbe.NoMatch.class);
        var noMatch = (AccessorProbe.NoMatch) probe;
        assertThat(noMatch.gatedNearMiss()).isTrue();
        assertThat(noMatch.reason()).contains("boolean");
    }

    // ---- parameter shape / arity ----

    @Test
    void perArgument_arityAndTypeMatch() {
        var candidates = ClassAccessorResolver.enumerate(ArgPojo.class, "value",
            CandidateOrder.POJO_FIRST, ALL_KINDS, new PerArgument(List.of(new ArgShape("x", String.class))));
        var first = firstAccepted(candidates);
        assertThat(first).isNotNull();
        assertThat(first.memberName()).isEqualTo("value");
        assertThat(((Method) first.member()).getParameterCount()).isEqualTo(1);
    }

    @Test
    void perArgument_arityMismatch_isNearMissNotMatch() {
        // value(String) probed with zero expected arguments: arity gate rejects it.
        var candidates = ClassAccessorResolver.enumerate(ArgPojo.class, "value",
            CandidateOrder.POJO_FIRST, ALL_KINDS, NO_ARGS);
        assertThat(firstAccepted(candidates)).isNull();
        assertThat(hasNearMiss(candidates)).isTrue();

        var probe = ClassAccessorResolver.probe(ArgPojo.class, "value", NO_ARGS, CandidateOrder.POJO_FIRST);
        assertThat(probe).isInstanceOf(AccessorProbe.NoMatch.class);
        assertThat(((AccessorProbe.NoMatch) probe).gatedNearMiss()).isTrue();
    }

    @Test
    void perArgument_unresolvableArgumentDegradesToArityOnly() {
        // An Object arg type (unresolvable at the walk phase) matches any single-parameter method by
        // arity; per-parameter type assignability is skipped for that position.
        var candidates = ClassAccessorResolver.enumerate(ArgPojo.class, "value",
            CandidateOrder.POJO_FIRST, ALL_KINDS, new PerArgument(List.of(new ArgShape("x", Object.class))));
        assertThat(firstAccepted(candidates)).isNotNull();
    }

    // ---- public-field fallback ----

    @Test
    void publicField_matchesOnlyWhenNoArguments() {
        var noArg = ClassAccessorResolver.enumerate(FieldPojo.class, "label",
            CandidateOrder.POJO_FIRST, ALL_KINDS, NO_ARGS);
        var first = firstAccepted(noArg);
        assertThat(first).isNotNull();
        assertThat(first.member()).isInstanceOf(java.lang.reflect.Field.class);

        var withArgs = ClassAccessorResolver.enumerate(FieldPojo.class, "label",
            CandidateOrder.POJO_FIRST, ALL_KINDS, new PerArgument(List.of(new ArgShape("x", String.class))));
        assertThat(firstAccepted(withArgs)).isNull();
        assertThat(hasNearMiss(withArgs)).isTrue();
    }

    @Test
    void recordSourceKinds_excludeFieldAndEnvAndPerArgumentBeyondZero() {
        // The record-source reduction requests PER_ARGUMENT_METHOD only with a zero-arg shape: the
        // public field is unrepresentable, so it is never produced (not merely filtered downstream).
        var candidates = ClassAccessorResolver.enumerate(FieldPojo.class, "label",
            CandidateOrder.POJO_FIRST, EnumSet.of(CandidateKind.PER_ARGUMENT_METHOD), NO_ARGS);
        assertThat(firstAccepted(candidates)).isNull();
    }

    // ---- member filter ----

    @Test
    void inheritedAccessor_isMatched() {
        var candidates = ClassAccessorResolver.enumerate(SubAccessor.class, "inherited",
            CandidateOrder.RECORD_FIRST, ALL_KINDS, NO_ARGS);
        assertThat(firstAccepted(candidates)).isNotNull();
    }

    @Test
    void staticMethod_isNotMatched() {
        var candidates = ClassAccessorResolver.enumerate(StaticPojo.class, "thing",
            CandidateOrder.POJO_FIRST, ALL_KINDS, NO_ARGS);
        assertThat(firstAccepted(candidates)).isNull();
    }

    @Test
    void objectDeclaredMember_isNotMatched() {
        // getClass() is declared by Object; the member filter excludes it, so base name "class" finds
        // no accessor.
        var candidates = ClassAccessorResolver.enumerate(SubAccessor.class, "class",
            CandidateOrder.POJO_FIRST, ALL_KINDS, NO_ARGS);
        assertThat(firstAccepted(candidates)).isNull();
    }

    @Test
    void covariantBridgeMethod_isSkipped_realOverrideWins() {
        // CovariantValue.value() returns String, overriding HasValue.value():Object; the compiler
        // generates a bridge value():Object. The bridge is skipped, so resolve picks the real method.
        var resolution = ClassAccessorResolver.resolve(CovariantValue.class, "value",
            String.class, NO_ARGS, CandidateOrder.RECORD_FIRST);
        assertThat(resolution).isInstanceOf(AccessorResolution.Resolved.class);
        var method = switch ((AccessorResolution.Resolved) resolution) {
            case AccessorResolution.BareName bn -> bn.method();
            case AccessorResolution.GetterPrefixed gp -> gp.method();
            case AccessorResolution.FieldRead fr -> null;
        };
        assertThat(method).isNotNull();
        assertThat(method.getReturnType()).isEqualTo(String.class);
        assertThat(method.isBridge()).isFalse();
    }

    // ---- probe grounding ----

    @Test
    void probe_groundsOnFirstMatchWithReturnType() {
        var probe = ClassAccessorResolver.probe(DualPojo.class, "primary", NO_ARGS, CandidateOrder.POJO_FIRST);
        assertThat(probe).isInstanceOf(AccessorProbe.Grounded.class);
        var grounded = (AccessorProbe.Grounded) probe;
        assertThat(grounded.memberName()).isEqualTo("getPrimary");
        assertThat(grounded.genericReturnType()).isEqualTo(String.class);
    }

    @Test
    void probe_plainAbsence_isNotGatedNearMiss() {
        var probe = ClassAccessorResolver.probe(DualPojo.class, "missing", NO_ARGS, CandidateOrder.POJO_FIRST);
        assertThat(probe).isInstanceOf(AccessorProbe.NoMatch.class);
        assertThat(((AccessorProbe.NoMatch) probe).gatedNearMiss()).isFalse();
    }

    // ---- order-bridge meta-test ----

    @Test
    void forBackingClass_equalsRecordFirst_exactlyForJavaRecordVariant() {
        // Matrix of backing-class shapes: the binding walk's candidate-order derivation
        // (forBackingClass) must select RECORD_FIRST exactly when buildResultTypeFromClass would
        // produce a JavaRecordType, so walk and emission never split their candidate order.
        List<Class<?>> matrix = List.of(
            DualRecord.class,                                                   // Java record
            DualPojo.class,                                                     // plain POJO
            RecordImplementingInterface.class,                                  // record implementing an interface
            no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord.class // jOOQ Record subclass
        );
        for (Class<?> cls : matrix) {
            boolean recordFirst = ClassAccessorResolver.forBackingClass(cls) == CandidateOrder.RECORD_FIRST;
            boolean javaRecordVariant =
                TypeBuilder.resultVariantKindFor(cls) == TypeBuilder.ResultVariantKind.JAVA_RECORD;
            assertThat(recordFirst)
                .as("candidate order for %s must be RECORD_FIRST iff its result variant is JAVA_RECORD", cls.getName())
                .isEqualTo(javaRecordVariant);
        }
    }
}
