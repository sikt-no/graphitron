package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;

import java.util.List;

/**
 * The shape of a GraphQL-argument-sourced value at a {@link MappingEntry.FromArg} slot.
 *
 * <p>Walked through {@link RecordInput#fields}, {@link JavaBeanInput#fields}, and
 * {@link ListOf#elementShape}; every node carries its Java {@link TypeName} so emitters can
 * declare type-correct casts at every step.
 *
 * <p>Paths live on the data-bearing leaves ({@link Scalar}, {@link ListOf}), not on composites.
 */
public sealed interface ValueShape permits ValueShape.Scalar, ValueShape.ListOf, ValueShape.RecordInput, ValueShape.JavaBeanInput, ValueShape.JooqRecordInput {

    TypeName javaType();

    /**
     * A scalar or enum leaf. {@code leafTransform} is one of {@link CallSiteExtraction}'s four
     * leaf arms ({@code Direct}, {@code EnumValueOf}, {@code JooqConvert}, {@code NodeIdDecodeKeys});
     * the walker enforces that restriction structurally.
     */
    record Scalar(TypeName javaType, ArgPath sdlPath, CallSiteExtraction leafTransform) implements ValueShape {}

    record ListOf(ArgPath sdlPath, ValueShape elementShape) implements ValueShape {
        @Override public TypeName javaType() {
            return ParameterizedTypeName.get(ClassName.get(List.class), elementShape.javaType());
        }
    }

    /** Construction via record canonical ctor; field order is the record-component order. */
    record RecordInput(ClassName javaClass, List<FieldBinding> fields) implements ValueShape {
        public RecordInput { fields = List.copyOf(fields); }
        @Override public TypeName javaType() { return javaClass; }
    }

    /** Construction via no-arg ctor + setters; field order is irrelevant to construction. */
    record JavaBeanInput(ClassName javaClass, List<FieldBinding> fields) implements ValueShape {
        public JavaBeanInput { fields = List.copyOf(fields); }
        @Override public TypeName javaType() { return javaClass; }
    }

    /**
     * Construction of a generated jOOQ {@link org.jooq.TableRecord} param via the
     * {@code create<Record>} helper (R311). A <em>path-carrying leaf</em>, not a pathless composite
     * like {@link RecordInput} / {@link JavaBeanInput}: a jOOQ record binds on the column / identity
     * axes rather than per-SDL-field {@link FieldBinding}s, so there are no per-field {@code ValueShape}
     * children to hang paths on, and {@code JooqRecordInput} carries its own {@code sdlPath} exactly as
     * {@link Scalar} does. It also carries the whole {@link CallSiteExtraction.JooqRecord} construction
     * carrier (the {@code Scalar}-carries-{@code leafTransform} precedent), so the helper-queue collector
     * can register the {@code create<Record>} helper from the {@code ValueShape} alone. {@code javaType()}
     * is the record class read off the carrier's table.
     */
    record JooqRecordInput(CallSiteExtraction.JooqRecord carrier, ArgPath sdlPath) implements ValueShape {
        @Override public TypeName javaType() { return carrier.table().recordClass(); }
    }

    /**
     * One field of a {@link RecordInput} or {@link JavaBeanInput}. {@code sdlFieldName} is the
     * SDL input-object field name; {@code javaFieldName} is the matching Java component/setter
     * suffix; {@code shape} carries the value tree below.
     */
    record FieldBinding(String sdlFieldName, String javaFieldName, ValueShape shape) {}
}
