package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;

import java.util.List;
import java.util.Objects;

/**
 * Pre-resolved shape of the graphitron-emitted record for one SDL {@code input} type. Lives
 * on every {@link HasInputRecordShape} carrier (the four {@link GraphitronType.InputType}
 * leaves and {@link GraphitronType.TableInputType}). One {@link InputComponent} per SDL
 * field, in declaration order.
 *
 * <p>The compact constructor is the producer-side rejection backing the
 * {@code input-record.shape-from-input-type} load-bearing classifier check: a builder
 * site that fails to construct a shape (null {@code recordClass}, empty
 * {@code components}) surfaces the input type as {@link GraphitronType.UnclassifiedType}
 * via the existing fail-mode.
 *
 * @param recordClass the emitted record's fully-qualified Java class name (always
 *     {@code <outputPackage>.inputs.<InputName>})
 * @param components  one per SDL field, in declaration order
 */
public record InputRecordShape(
    ClassName recordClass,
    List<InputComponent> components
) {
    public InputRecordShape {
        Objects.requireNonNull(recordClass, "recordClass");
        Objects.requireNonNull(components, "components");
        if (components.isEmpty()) {
            throw new IllegalStateException(
                "InputRecordShape must have at least one component");
        }
        components = List.copyOf(components);
    }

    /**
     * One component on an {@link InputRecordShape}, matching one SDL input field.
     *
     * @param sdlFieldName        SDL field name as declared in the schema (e.g. {@code "filmId"});
     *                            surfaces verbatim in {@code ConstraintViolation.getPropertyPath()}.
     * @param javaComponentName   Java record component name (usually identical to
     *                            {@code sdlFieldName}; a sanitiser would diverge here if the SDL
     *                            name collides with a Java keyword).
     * @param javaType            classifier-resolved Java type the record component declares.
     *                            For scalar fields, derived via R101's
     *                            {@link no.sikt.graphitron.rewrite.ScalarTypeResolver}; for list
     *                            fields, a {@code List<X>} parameterisation over the element's
     *                            Java type; for nested input refs, the emitted record's
     *                            {@link ClassName}. An SDL field whose scalar fails to classify
     *                            surfaces as {@link GraphitronType.UnclassifiedType} on the
     *                            parent input type via the existing fail-mode and never reaches
     *                            the emitter.
     * @param nullable            {@code false} when the SDL marks the field non-null with
     *                            {@code !}, {@code true} otherwise. Wire-format null and an
     *                            absent key both materialise as {@code null} per the spec's
     *                            symmetric-null contract.
     */
    public record InputComponent(
        String sdlFieldName,
        String javaComponentName,
        TypeName javaType,
        boolean nullable
    ) {
        public InputComponent {
            Objects.requireNonNull(sdlFieldName, "sdlFieldName");
            Objects.requireNonNull(javaComponentName, "javaComponentName");
            Objects.requireNonNull(javaType, "javaType");
        }
    }
}
