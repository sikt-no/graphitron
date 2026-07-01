package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.PayloadConstructionShape;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link FieldBuilder#resolvePayloadConstructionShape} predicate behaviour: predicates
 * run in order, all-fields-ctor short-circuits, mutable-bean is the fallback admission, and
 * neither-matches is the only rejection mode.
 */
@UnitTier
class PayloadConstructionShapeTest {

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
            RecordPayload.class, List.of("data", "errors"));
        assertThat(result).isInstanceOf(FieldBuilder.PayloadConstructionShapeResult.Resolved.class);
        var shape = ((FieldBuilder.PayloadConstructionShapeResult.Resolved) result).shape();
        assertThat(shape).isInstanceOf(PayloadConstructionShape.AllFieldsCtor.class);
    }

    @Test
    void beanPayload_resolvesAsMutableBean() {
        var result = FieldBuilder.resolvePayloadConstructionShape(
            BeanPayload.class, List.of("data", "errors"));
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
            BothShapesPayload.class, List.of("data", "errors"));
        assertThat(result).isInstanceOf(FieldBuilder.PayloadConstructionShapeResult.Resolved.class);
        var shape = ((FieldBuilder.PayloadConstructionShapeResult.Resolved) result).shape();
        assertThat(shape).isInstanceOf(PayloadConstructionShape.AllFieldsCtor.class);
    }

    @Test
    void missingErrorsSetter_rejectsWithStructuredReason() {
        var result = FieldBuilder.resolvePayloadConstructionShape(
            MissingErrorsSetter.class, List.of("data", "errors"));
        assertThat(result).isInstanceOf(FieldBuilder.PayloadConstructionShapeResult.Reject.class);
        var reject = (FieldBuilder.PayloadConstructionShapeResult.Reject) result;
        assertThat(reject.reason()).contains("setErrors");
        assertThat(reject.reason()).contains("errors");
    }

    @Test
    void neitherShape_rejects() {
        var result = FieldBuilder.resolvePayloadConstructionShape(
            NeitherShape.class, List.of("data", "errors"));
        assertThat(result).isInstanceOf(FieldBuilder.PayloadConstructionShapeResult.Reject.class);
    }

    @Test
    void optionalSetter_marksAcceptsOptional() {
        var result = FieldBuilder.resolvePayloadConstructionShape(
            OptionalSetterPayload.class, List.of("rating", "errors"));
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
            CamelCaseSetterPayload.class, List.of("xRating", "errors"));
        assertThat(result).isInstanceOf(FieldBuilder.PayloadConstructionShapeResult.Resolved.class);
        var mb = (PayloadConstructionShape.MutableBean)
            ((FieldBuilder.PayloadConstructionShapeResult.Resolved) result).shape();
        assertThat(mb.bindings().get(0).setter().getName()).isEqualTo("setXRating");
    }
}
