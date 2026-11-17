package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.ClassAccessorResolver.ArgShape;
import no.sikt.graphitron.rewrite.ClassAccessorResolver.Candidate;
import no.sikt.graphitron.rewrite.ClassAccessorResolver.CandidateKind;
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
 * directly against small synthetic classes (a pure reflection surface): a record reads its
 * components, any other class its get and is getters. Plus the meta-test pinning the resolver's fork
 * on {@code Class.isRecord()} against the class-shape decision {@link TypeBuilder#buildResultTypeFromClass}
 * makes.
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
        public String getValue(String x) { return x; }
    }

    static class FieldPojo {
        public String label = "";
    }

    static class BaseAccessor {
        public String getInherited() { return ""; }
    }

    static class SubAccessor extends BaseAccessor {}

    static class StaticPojo {
        public static String getThing() { return ""; }
    }

    interface HasValue { Object getValue(); }

    static class CovariantValue implements HasValue {
        @Override public String getValue() { return ""; }
    }

    record RecordImplementingInterface(String primary) implements HasValue {
        @Override public String getValue() { return primary; }
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

    // ---- the class's form ----

    @Test
    void pojo_readsTheGetter_bareNameIsANearMiss() {
        var candidates = ClassAccessorResolver.enumerate(DualPojo.class, "primary", ALL_KINDS, NO_ARGS);
        var first = firstAccepted(candidates);
        assertThat(first).isNotNull();
        assertThat(first.memberName()).isEqualTo("getPrimary");
        assertThat(first.prefixed()).isTrue();
        assertThat(candidates).filteredOn(c -> c instanceof Candidate.Accepted).hasSize(1);
        assertThat(candidates).anySatisfy(c -> assertThat(c).isInstanceOfSatisfying(Candidate.NearMiss.class,
            nm -> assertThat(nm.reason()).contains("primary()").contains("getPrimary()")));
    }

    @Test
    void record_readsItsComponent_getterIsANearMiss() {
        var candidates = ClassAccessorResolver.enumerate(DualRecord.class, "primary", ALL_KINDS, NO_ARGS);
        var first = firstAccepted(candidates);
        assertThat(first).isNotNull();
        assertThat(first.memberName()).isEqualTo("primary");
        assertThat(first.prefixed()).isFalse();
        assertThat(candidates).filteredOn(c -> c instanceof Candidate.Accepted).hasSize(1);
        assertThat(candidates).anySatisfy(c -> assertThat(c).isInstanceOfSatisfying(Candidate.NearMiss.class,
            nm -> assertThat(nm.reason()).contains("getPrimary()").contains("components only")));
    }

    @Test
    void record_methodThatIsNoComponent_isNotRead() {
        // RecordImplementingInterface's getValue() is no component: a record is read through its
        // components only.
        var probe = ClassAccessorResolver.probe(RecordImplementingInterface.class, "value", NO_ARGS);
        assertThat(probe).isInstanceOf(AccessorProbe.NoMatch.class);
        assertThat(((AccessorProbe.NoMatch) probe).gatedNearMiss()).isTrue();
        assertThat(((AccessorProbe.NoMatch) probe).reason()).contains("components only");
    }

    // ---- is-gate ----

    @Test
    void isPrefix_matchesOnlyBooleanReturn() {
        var candidates = ClassAccessorResolver.enumerate(BoolPojo.class, "active",
            ALL_KINDS, NO_ARGS);
        var first = firstAccepted(candidates);
        assertThat(first).isNotNull();
        assertThat(first.memberName()).isEqualTo("isActive");
    }

    @Test
    void isPrefix_nonBooleanReturn_isGatedNearMissNotMatch() {
        var candidates = ClassAccessorResolver.enumerate(NonBooleanIsPojo.class, "active",
            ALL_KINDS, NO_ARGS);
        assertThat(firstAccepted(candidates)).isNull();
        assertThat(hasNearMiss(candidates)).isTrue();

        var probe = ClassAccessorResolver.probe(NonBooleanIsPojo.class, "active",
            NO_ARGS);
        assertThat(probe).isInstanceOf(AccessorProbe.NoMatch.class);
        var noMatch = (AccessorProbe.NoMatch) probe;
        assertThat(noMatch.gatedNearMiss()).isTrue();
        assertThat(noMatch.reason()).contains("boolean");
    }

    // ---- parameter shape / arity ----

    @Test
    void perArgument_arityAndTypeMatch() {
        var candidates = ClassAccessorResolver.enumerate(ArgPojo.class, "value",
            ALL_KINDS, new PerArgument(List.of(new ArgShape("x", String.class))));
        var first = firstAccepted(candidates);
        assertThat(first).isNotNull();
        assertThat(first.memberName()).isEqualTo("getValue");
        assertThat(((Method) first.member()).getParameterCount()).isEqualTo(1);
    }

    @Test
    void perArgument_arityMismatch_isNearMissNotMatch() {
        // getValue(String) probed with zero expected arguments: arity gate rejects it.
        var candidates = ClassAccessorResolver.enumerate(ArgPojo.class, "value",
            ALL_KINDS, NO_ARGS);
        assertThat(firstAccepted(candidates)).isNull();
        assertThat(hasNearMiss(candidates)).isTrue();

        var probe = ClassAccessorResolver.probe(ArgPojo.class, "value", NO_ARGS);
        assertThat(probe).isInstanceOf(AccessorProbe.NoMatch.class);
        assertThat(((AccessorProbe.NoMatch) probe).gatedNearMiss()).isTrue();
    }

    @Test
    void perArgument_unresolvableArgumentDegradesToArityOnly() {
        // An Object arg type (unresolvable at the walk phase) matches any single-parameter method by
        // arity; per-parameter type assignability is skipped for that position.
        var candidates = ClassAccessorResolver.enumerate(ArgPojo.class, "value",
            ALL_KINDS, new PerArgument(List.of(new ArgShape("x", Object.class))));
        assertThat(firstAccepted(candidates)).isNotNull();
    }

    // ---- a public field is not read ----

    @Test
    void publicField_isNeverAccepted_andNamesTheGetterToWrite() {
        var noArg = ClassAccessorResolver.enumerate(FieldPojo.class, "label", ALL_KINDS, NO_ARGS);
        assertThat(firstAccepted(noArg)).isNull();
        assertThat(noArg).anySatisfy(c -> assertThat(c).isInstanceOfSatisfying(Candidate.NearMiss.class,
            nm -> assertThat(nm.reason()).contains("public field `label`").contains("getLabel()")));

        var resolution = ClassAccessorResolver.resolve(FieldPojo.class, "label", String.class, NO_ARGS);
        assertThat(resolution).isInstanceOf(AccessorResolution.Rejected.class);
    }

    // ---- member filter ----

    @Test
    void inheritedAccessor_isMatched() {
        var candidates = ClassAccessorResolver.enumerate(SubAccessor.class, "inherited",
            ALL_KINDS, NO_ARGS);
        assertThat(firstAccepted(candidates)).isNotNull();
    }

    @Test
    void staticMethod_isNotMatched() {
        var candidates = ClassAccessorResolver.enumerate(StaticPojo.class, "thing",
            ALL_KINDS, NO_ARGS);
        assertThat(firstAccepted(candidates)).isNull();
    }

    @Test
    void objectDeclaredMember_isNotMatched() {
        // getClass() is declared by Object; the member filter excludes it, so base name "class" finds
        // no accessor.
        var candidates = ClassAccessorResolver.enumerate(SubAccessor.class, "class",
            ALL_KINDS, NO_ARGS);
        assertThat(firstAccepted(candidates)).isNull();
    }

    @Test
    void covariantBridgeMethod_isSkipped_realOverrideWins() {
        // CovariantValue.getValue() returns String, overriding HasValue.getValue():Object; the compiler
        // generates a bridge getValue():Object. The bridge is skipped, so resolve picks the real method.
        var resolution = ClassAccessorResolver.resolve(CovariantValue.class, "value",
            String.class, NO_ARGS);
        assertThat(resolution).isInstanceOf(AccessorResolution.Resolved.class);
        var method = switch ((AccessorResolution.Resolved) resolution) {
            case AccessorResolution.BareName bn -> bn.method();
            case AccessorResolution.GetterPrefixed gp -> gp.method();
        };
        assertThat(method).isNotNull();
        assertThat(method.getReturnType()).isEqualTo(String.class);
        assertThat(method.isBridge()).isFalse();
    }

    // ---- probe grounding ----

    @Test
    void probe_groundsOnFirstMatchWithReturnType() {
        var probe = ClassAccessorResolver.probe(DualPojo.class, "primary", NO_ARGS);
        assertThat(probe).isInstanceOf(AccessorProbe.Grounded.class);
        var grounded = (AccessorProbe.Grounded) probe;
        assertThat(grounded.memberName()).isEqualTo("getPrimary");
        assertThat(grounded.genericReturnType()).isEqualTo(String.class);
    }

    @Test
    void probe_plainAbsence_isNotGatedNearMiss() {
        var probe = ClassAccessorResolver.probe(DualPojo.class, "missing", NO_ARGS);
        assertThat(probe).isInstanceOf(AccessorProbe.NoMatch.class);
        assertThat(((AccessorProbe.NoMatch) probe).gatedNearMiss()).isFalse();
    }

    // ---- the record fork meta-test ----

    @Test
    void readsComponents_exactlyForJavaRecordVariant() {
        // The resolver reads a class's components exactly when the class is a record, and
        // buildResultTypeFromClass produces a JavaRecordType exactly then too, so walk and emission
        // never split on which members a class offers.
        List<Class<?>> matrix = List.of(
            DualRecord.class,                                                   // Java record
            DualPojo.class,                                                     // plain POJO
            RecordImplementingInterface.class,                                  // record implementing an interface
            no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord.class // jOOQ Record subclass
        );
        for (Class<?> cls : matrix) {
            boolean readsComponents = cls.isRecord();
            boolean javaRecordVariant =
                TypeBuilder.resultVariantKindFor(cls) == TypeBuilder.ResultVariantKind.JAVA_RECORD;
            assertThat(readsComponents)
                .as("%s reads its components iff its result variant is JAVA_RECORD", cls.getName())
                .isEqualTo(javaRecordVariant);
        }
    }
}
