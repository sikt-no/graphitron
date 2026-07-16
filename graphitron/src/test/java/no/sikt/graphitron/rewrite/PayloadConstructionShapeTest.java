package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.FieldBuilder.PayloadSdlField;
import no.sikt.graphitron.rewrite.model.PayloadConstructionShape;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link FieldBuilder#resolvePayloadConstructionShape} predicate behaviour: predicates
 * run in order, all-fields-ctor short-circuits, mutable-bean is the fallback admission, and
 * neither-matches is the only rejection mode. R201 adds the {@code @field(name:)} remap on the
 * mutable-bean arm (setter base = directive value when present, SDL name otherwise).
 */
@UnitTier
class PayloadConstructionShapeTest {

    /** SDL fields with no {@code @field} directive: Java base name equals the SDL field name. */
    private static List<PayloadSdlField> sdl(String... names) {
        return Arrays.stream(names)
            .map(n -> new PayloadSdlField(n, n, false))
            .toList();
    }

    /** A single {@code @field(name: base)} SDL field: base diverges from the SDL name. */
    private static PayloadSdlField remapped(String sdlFieldName, String base) {
        return new PayloadSdlField(sdlFieldName, base, true);
    }

    // ===== Fixture payload classes =====

    record RecordPayload(String data, List<Object> errors) {}

    public static final class BeanPayload {
        private String data;
        private List<Object> errors;
        public BeanPayload() {}
        public void setData(String data) { this.data = data; }
        public void setErrors(List<Object> errors) { this.errors = errors; }
        public String getData() { return data; }
        public List<Object> getErrors() { return errors; }
    }

    /** Hand-rolled POJO supporting both shapes: all-fields ctor and no-arg ctor + setters. */
    public static final class BothShapesPayload {
        private String data;
        private List<Object> errors;
        public BothShapesPayload() {}
        public BothShapesPayload(String data, List<Object> errors) {
            this.data = data;
            this.errors = errors;
        }
        public void setData(String data) { this.data = data; }
        public void setErrors(List<Object> errors) { this.errors = errors; }
    }

    /** No-arg ctor present but missing a setter for the "errors" SDL field. */
    public static final class MissingErrorsSetter {
        public MissingErrorsSetter() {}
        public void setData(String data) {}
    }

    /** Neither shape matches: no constructors at all is structurally impossible in Java, so use
     *  a class with two arbitrary ctors and no public no-arg ctor. */
    public static final class NeitherShape {
        public NeitherShape(int a) {}
        public NeitherShape(int a, int b) {}
        // No public no-arg ctor; arity-2 ctor's parameter count is 2 not 2 (data,errors), but
        // it's not the all-fields ctor (param types don't matter to the predicate; the type
        // checks happen at the resolve site). To make this really "neither", we keep multiple
        // arity-2 ctors so the all-fields predicate can't disambiguate.
        public NeitherShape(String a, List<Object> b) {}
    }

    /** Setter accepting Optional<T>; mirrors legacy ReflectionHelpers.setterAcceptsOptional. */
    public static final class OptionalSetterPayload {
        public OptionalSetterPayload() {}
        public void setRating(Optional<Integer> rating) {}
        public void setErrors(List<Object> errors) {}
    }

    /** Bean payload using camelCase SDL field names that resolve through Java-bean naming. */
    public static final class CamelCaseSetterPayload {
        public CamelCaseSetterPayload() {}
        public void setXRating(Integer xRating) {}
        public void setErrors(List<Object> errors) {}
    }

    // ===== Predicate cases =====

    @Test
    void recordPayload_resolvesAsAllFieldsCtor() {
        var result = FieldBuilder.resolvePayloadConstructionShape(
            RecordPayload.class, sdl("data", "errors"));
        assertThat(result).isInstanceOf(FieldBuilder.PayloadConstructionShapeResult.Resolved.class);
        var shape = ((FieldBuilder.PayloadConstructionShapeResult.Resolved) result).shape();
        assertThat(shape).isInstanceOf(PayloadConstructionShape.AllFieldsCtor.class);
    }

    @Test
    void beanPayload_resolvesAsMutableBean() {
        var result = FieldBuilder.resolvePayloadConstructionShape(
            BeanPayload.class, sdl("data", "errors"));
        assertThat(result).isInstanceOf(FieldBuilder.PayloadConstructionShapeResult.Resolved.class);
        var shape = ((FieldBuilder.PayloadConstructionShapeResult.Resolved) result).shape();
        assertThat(shape).isInstanceOf(PayloadConstructionShape.MutableBean.class);
        var mb = (PayloadConstructionShape.MutableBean) shape;
        assertThat(mb.bindings()).hasSize(2);
        assertThat(mb.bindings().get(0).sdlFieldName()).isEqualTo("data");
        assertThat(mb.bindings().get(0).setter().getName()).isEqualTo("setData");
        assertThat(mb.bindings().get(1).sdlFieldName()).isEqualTo("errors");
        assertThat(mb.bindings().get(1).setter().getName()).isEqualTo("setErrors");
    }

    @Test
    void bothShapesPayload_classifiesAsAllFieldsCtor_canonicalWins() {
        // Predicate 1 short-circuits the walk: AllFieldsCtor wins because it ran first. The
        // setter-shape predicate is unreached.
        var result = FieldBuilder.resolvePayloadConstructionShape(
            BothShapesPayload.class, sdl("data", "errors"));
        assertThat(result).isInstanceOf(FieldBuilder.PayloadConstructionShapeResult.Resolved.class);
        var shape = ((FieldBuilder.PayloadConstructionShapeResult.Resolved) result).shape();
        assertThat(shape).isInstanceOf(PayloadConstructionShape.AllFieldsCtor.class);
    }

    @Test
    void missingErrorsSetter_rejectsWithStructuredReason() {
        var result = FieldBuilder.resolvePayloadConstructionShape(
            MissingErrorsSetter.class, sdl("data", "errors"));
        assertThat(result).isInstanceOf(FieldBuilder.PayloadConstructionShapeResult.Reject.class);
        var reject = (FieldBuilder.PayloadConstructionShapeResult.Reject) result;
        assertThat(reject.reason()).contains("setErrors");
        assertThat(reject.reason()).contains("errors");
    }

    @Test
    void neitherShape_rejects() {
        var result = FieldBuilder.resolvePayloadConstructionShape(
            NeitherShape.class, sdl("data", "errors"));
        assertThat(result).isInstanceOf(FieldBuilder.PayloadConstructionShapeResult.Reject.class);
    }

    @Test
    void optionalSetter_marksAcceptsOptional() {
        var result = FieldBuilder.resolvePayloadConstructionShape(
            OptionalSetterPayload.class, sdl("rating", "errors"));
        assertThat(result).isInstanceOf(FieldBuilder.PayloadConstructionShapeResult.Resolved.class);
        var mb = (PayloadConstructionShape.MutableBean)
            ((FieldBuilder.PayloadConstructionShapeResult.Resolved) result).shape();
        assertThat(mb.bindings().get(0).acceptsOptional()).isTrue();
        assertThat(mb.bindings().get(1).acceptsOptional()).isFalse();
    }

    @Test
    void camelCaseSdlField_resolvesUnderJavaBeanNaming() {
        // SDL "xRating" -> setter "setXRating" under Java-bean first-letter-upper naming.
        var result = FieldBuilder.resolvePayloadConstructionShape(
            CamelCaseSetterPayload.class, sdl("xRating", "errors"));
        assertThat(result).isInstanceOf(FieldBuilder.PayloadConstructionShapeResult.Resolved.class);
        var mb = (PayloadConstructionShape.MutableBean)
            ((FieldBuilder.PayloadConstructionShapeResult.Resolved) result).shape();
        assertThat(mb.bindings().get(0).setter().getName()).isEqualTo("setXRating");
    }

    // ===== R201: @field(name:) remap on the mutable-bean arm =====

    /** Setters whose bases diverge from the SDL field names; only @field(name:) can bind them. */
    public static final class DivergentSetterPayload {
        public DivergentSetterPayload() {}
        public void setInfo(String info) {}
        public void setFailures(List<Object> failures) {}
    }

    @Test
    void remappedSetters_resolveViaFieldDirective() {
        // SDL data @field(name: "info") + errors @field(name: "failures"). The bean arm looks up
        // setInfo / setFailures (not setData / setErrors), binds them, and keeps the SDL field
        // name as the wire identity on each SetterBinding.
        var result = FieldBuilder.resolvePayloadConstructionShape(
            DivergentSetterPayload.class,
            List.of(remapped("data", "info"), remapped("errors", "failures")));
        assertThat(result).isInstanceOf(FieldBuilder.PayloadConstructionShapeResult.Resolved.class);
        var mb = (PayloadConstructionShape.MutableBean)
            ((FieldBuilder.PayloadConstructionShapeResult.Resolved) result).shape();
        assertThat(mb.bindings()).hasSize(2);
        assertThat(mb.bindings().get(0).sdlFieldName()).isEqualTo("data");
        assertThat(mb.bindings().get(0).setter().getName()).isEqualTo("setInfo");
        assertThat(mb.bindings().get(1).sdlFieldName()).isEqualTo("errors");
        assertThat(mb.bindings().get(1).setter().getName()).isEqualTo("setFailures");
    }

    @Test
    void remappedButMissingSetter_rejectsNamingSdlFieldDirectiveValueAndParenthetical() {
        // BeanPayload exposes setData / setErrors. A @field(name: "info") on the data field remaps
        // the lookup to setInfo, which does not exist. The reject names the SDL field ('data'),
        // the directive value ('info'), and the (remapped to 'info' by @field) parenthetical so a
        // failed override reads as an override, not a plain missing setter.
        var result = FieldBuilder.resolvePayloadConstructionShape(
            BeanPayload.class,
            List.of(remapped("data", "info"), new PayloadSdlField("errors", "errors", false)));
        assertThat(result).isInstanceOf(FieldBuilder.PayloadConstructionShapeResult.Reject.class);
        var reason = ((FieldBuilder.PayloadConstructionShapeResult.Reject) result).reason();
        assertThat(reason)
            .contains("setInfo")
            .contains("'data'")
            .contains("(remapped to 'info' by @field)");
    }

    @Test
    void remappedDirectiveValue_convertedUnderJavaBeanNaming() {
        // The directive value is camelCased into the setter name the same way an SDL field name is:
        // @field(name: "xRating") -> setXRating.
        var result = FieldBuilder.resolvePayloadConstructionShape(
            CamelCaseSetterPayload.class,
            List.of(remapped("data", "xRating"), new PayloadSdlField("errors", "errors", false)));
        assertThat(result).isInstanceOf(FieldBuilder.PayloadConstructionShapeResult.Resolved.class);
        var mb = (PayloadConstructionShape.MutableBean)
            ((FieldBuilder.PayloadConstructionShapeResult.Resolved) result).shape();
        assertThat(mb.bindings().get(0).setter().getName()).isEqualTo("setXRating");
    }
}
