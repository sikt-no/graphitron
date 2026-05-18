package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;

/**
 * One observed binding from a producer site that reaches an SDL type. Carries the reflected
 * {@link Class} the producer names and a structured description of where the binding came
 * from. Surfaces in {@link Rejection.AuthorError.RecordBindingMultiProducer} when
 * two or more producers reach the same SDL type with disagreeing classes.
 *
 * <p>Four arms correspond to the producer sources R96's reflection walk visits:
 * {@link RootService} for an {@code @service} method's return type, {@link RootTable} for an
 * SDL Object's {@code @table} resolution, {@link RootTableMethod} for a {@code @tableMethod}
 * return type, and {@link ParentAccessor} for an SDL parent's accessor return type.
 */
public sealed interface ProducerBinding
    permits ProducerBinding.RootService, ProducerBinding.RootTable,
            ProducerBinding.RootTableMethod, ProducerBinding.ParentAccessor {

    /** The reflected Java class this producer named for the SDL type. */
    Class<?> reflectedClass();

    /** Source location of the producer site, for diagnostic placement. */
    SourceLocation location();

    /** Human-readable site description ("@service on Query.films via FilmService.findAll"). */
    String describe();

    /**
     * An {@code @service} field's reflected return-element class. {@code parentTypeName} and
     * {@code fieldName} locate the SDL field; {@code serviceClassName} and {@code methodName}
     * locate the Java implementation.
     */
    record RootService(
        Class<?> reflectedClass,
        String parentTypeName,
        String fieldName,
        String serviceClassName,
        String methodName,
        SourceLocation location
    ) implements ProducerBinding {
        @Override public String describe() {
            return "@service on " + parentTypeName + "." + fieldName
                + " via " + serviceClassName + "." + methodName;
        }
    }

    /**
     * An SDL Object's {@code @table}-resolved jOOQ {@code TableRecord} class. The same record
     * class reaches both the result-side {@link GraphitronType.JooqTableRecordType} binding and
     * the input-side {@link GraphitronType.JooqTableRecordInputType} binding for a
     * {@code @table}-carrying type.
     */
    record RootTable(
        Class<?> reflectedClass,
        String sdlTypeName,
        String tableSqlName,
        SourceLocation location
    ) implements ProducerBinding {
        @Override public String describe() {
            return "@table on " + sdlTypeName + " resolving to '" + tableSqlName + "'";
        }
    }

    /**
     * A {@code @tableMethod} field's reflected return-element class. The holder class is the
     * resolved Java class hosting the method ({@code Function} subclass with a static factory).
     */
    record RootTableMethod(
        Class<?> reflectedClass,
        String parentTypeName,
        String fieldName,
        String holderClassName,
        String methodName,
        SourceLocation location
    ) implements ProducerBinding {
        @Override public String describe() {
            return "@tableMethod on " + parentTypeName + "." + fieldName
                + " via " + holderClassName + "." + methodName;
        }
    }

    /**
     * An SDL parent's accessor return-element class. The parent type already has a resolved
     * binding; the resolver reflected the named accessor on that class to get the SDL field's
     * binding. Both result-side parent accessors (getters / record components / field reads)
     * and input-side nested-input-field accessors lift through this arm; the arm shape is the
     * same on either axis.
     */
    record ParentAccessor(
        Class<?> reflectedClass,
        String parentTypeName,
        String parentReflectedClassName,
        String fieldName,
        String accessorName,
        SourceLocation location
    ) implements ProducerBinding {
        @Override public String describe() {
            return "accessor " + parentReflectedClassName + "." + accessorName
                + " on " + parentTypeName + "." + fieldName;
        }
    }
}
