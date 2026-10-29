package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;

import java.util.List;
import no.sikt.graphitron.render.CatalogRefs;

/**
 * The shape of a GraphQL-argument-sourced value at a {@link MappingEntry.FromArg} slot.
 *
 * <p>Walked through {@link RecordInput#fields}, {@link JavaBeanInput#fields}, and
 * {@link ListOf#elementShape}; every node carries its Java {@link TypeName} so emitters can
 * declare type-correct casts at every step.
 *
 * <p>Paths live on the data-bearing leaves ({@link Scalar}, {@link ListOf}). A class-bound
 * composite ({@link BoundClass}) carries no path of its own, only the name of the field argument
 * its value is read from, since a bound class always binds a whole argument.
 */
public sealed interface ValueShape permits ValueShape.Scalar, ValueShape.ListOf, ValueShape.BoundClass, ValueShape.JooqRecordInput {

    TypeName javaType();

    /**
     * A scalar or enum leaf. {@code leafTransform} is one of {@link CallSiteExtraction}'s four
     * leaf arms ({@code Direct}, {@code EnumValueOf}, {@code JooqConvert}, {@code NodeIdDecodeKeys});
     * the walker enforces that restriction structurally.
     *
     * <p>{@code definition} is the schema coordinate of the SDL slot the leaf reads, spelled
     * through {@link no.sikt.graphitron.model.catalog.SchemaCoordinateSyntax}: {@code Input.field}
     * for an input field, {@code Type.field(arg:)} for a top-level argument, and for a mapping that
     * projects past the SDL (a key column of a decoded id) the last declared slot. {@code sdlPath} is
     * where the value sits at this use site; {@code definition} is which declaration it is, which
     * the path alone does not say once an input type is reached from more than one field.
     */
    record Scalar(TypeName javaType, ArgPath sdlPath, CallSiteExtraction leafTransform,
                  String definition) implements ValueShape {
        public Scalar {
            java.util.Objects.requireNonNull(definition, "definition");
        }
    }

    record ListOf(ArgPath sdlPath, ValueShape elementShape) implements ValueShape {
        @Override public TypeName javaType() {
            return ParameterizedTypeName.get(ClassName.get(List.class), elementShape.javaType());
        }
    }

    /**
     * A value built into a class the consumer wrote, from the SDL input fields {@link #fields()}
     * binds. {@code argName} is the field argument the value is read from: a bound class always
     * binds a whole top-level argument, and a nested one (a {@link FieldBinding}'s shape) is read
     * from inside the same argument. It is carried rather than recovered from a leaf's path, so
     * the composite says what it binds without a walk of its children.
     */
    sealed interface BoundClass extends ValueShape permits RecordInput, JavaBeanInput {
        ClassName javaClass();
        String argName();
        List<FieldBinding> fields();

        @Override default TypeName javaType() { return javaClass(); }
    }

    /** Construction via record canonical ctor; field order is the record-component order. */
    record RecordInput(ClassName javaClass, String argName, List<FieldBinding> fields) implements BoundClass {
        public RecordInput {
            java.util.Objects.requireNonNull(argName, "argName");
            fields = List.copyOf(fields);
        }
    }

    /** Construction via no-arg ctor + setters; field order is irrelevant to construction. */
    record JavaBeanInput(ClassName javaClass, String argName, List<FieldBinding> fields) implements BoundClass {
        public JavaBeanInput {
            java.util.Objects.requireNonNull(argName, "argName");
            fields = List.copyOf(fields);
        }
    }

    /**
     * Construction of a generated jOOQ {@link org.jooq.TableRecord} param via the
 * {@code create<Record>} helper. A <em>path-carrying leaf</em>, not a pathless composite
     * like a {@link BoundClass}: a jOOQ record binds on the column / identity
     * axes rather than per-SDL-field {@link FieldBinding}s, so there are no per-field {@code ValueShape}
     * children to hang paths on, and {@code JooqRecordInput} carries its own {@code sdlPath} exactly as
     * {@link Scalar} does. It also carries the whole {@link CallSiteExtraction.JooqRecord} construction
     * carrier (the {@code Scalar}-carries-{@code leafTransform} precedent), so the helper-queue collector
     * can register the {@code create<Record>} helper from the {@code ValueShape} alone. {@code javaType()}
     * is the record class read off the carrier's table.
     */
    record JooqRecordInput(CallSiteExtraction.JooqRecord carrier, ArgPath sdlPath) implements ValueShape {
        @Override public TypeName javaType() { return CatalogRefs.recordClass(carrier.table()); }
    }

    /**
     * One field of a {@link RecordInput} or {@link JavaBeanInput}. {@code accessPath} is the ordered
     * chain of SDL input-object field names from the bean's own wire {@code Map} down to the leaf,
     * carrying the same meaning as {@link CallSiteExtraction.FieldBinding#accessPath()}: the last
     * element is the {@code Map} key ({@link #mapKey()}), earlier elements are enclosing grouping
     * input fields the leaf was flattened out of. {@code javaFieldName} is the matching Java
     * component/setter suffix; {@code shape} carries the value tree below; {@code definition} is
     * the schema coordinate of the input field bound, as on
     * {@link CallSiteExtraction.FieldBinding#definition()}.
     */
    record FieldBinding(List<String> accessPath, String javaFieldName, ValueShape shape,
                        String definition) {
        public FieldBinding {
            java.util.Objects.requireNonNull(definition, "definition");
            if (accessPath == null || accessPath.isEmpty()) {
                throw new IllegalArgumentException("FieldBinding accessPath must be non-empty");
            }
            for (var element : accessPath) {
                if (element == null || element.isEmpty()) {
                    throw new IllegalArgumentException("FieldBinding accessPath elements must be non-empty");
                }
            }
            accessPath = List.copyOf(accessPath);
        }

        /** The wire {@code Map} key for this binding's value: the last {@link #accessPath()} element. */
        public String mapKey() {
            return accessPath.get(accessPath.size() - 1);
        }
    }
}
