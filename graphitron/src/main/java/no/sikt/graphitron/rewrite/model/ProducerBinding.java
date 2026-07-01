package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;

import java.util.Objects;

/**
 * One observed binding from a producer site that reaches an SDL type. Carries the reflected
 * {@link Class} the producer names and a structured description of where the binding came
 * from. Surfaces in {@link Rejection.AuthorError.RecordBindingMultiProducer} when
 * two or more producers reach the same SDL type with disagreeing classes.
 *
 * <p>Five arms correspond to the producer sources R96's reflection walk visits:
 * {@link RootService} for an {@code @service} method's return type, {@link RootTable} for an
 * SDL Object's {@code @table} resolution, {@link RootTableMethod} for a {@code @tableMethod}
 * return type, {@link ParentAccessor} for an SDL parent's accessor return type, and
 * {@link DmlEmitted} for a DML mutation fetcher's emitted output shape (the only arm whose
 * source is generator-emitted rather than developer-authored reflection).
 */
public sealed interface ProducerBinding
    permits ProducerBinding.RootService, ProducerBinding.RootTable,
            ProducerBinding.RootTableMethod, ProducerBinding.ParentAccessor,
            ProducerBinding.DmlEmitted, ProducerBinding.ServiceEmitted {

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

    /**
     * A DML mutation fetcher's emitted row shape, observed as the producer-side binding for the
     * mutation's payload SDL type. The fetcher's {@code env.getSource()} carries
     * {@code RecordN<...>} (single-row) or {@code Result<RecordN<...>>} (bulk) projected on the
     * carried {@link TableRef}'s primary-key columns; {@link #reflectedClass()} returns the
     * record class for that {@code TableRef}, which is the same class {@link RootTable} grounds
     * with for an SDL Object carrying {@code @table} resolving to the same table. The fold's
     * class-identity agreement therefore re-emerges through
     * {@link no.sikt.graphitron.rewrite.RecordBindingResolver}'s per-SDL-type fold; this arm
     * is the structural replacement for the retired
     * {@code mutation-dml-record-field.data-table-equals-input-table} invariant.
     *
     * <p>Compact-constructor invariants: every component is non-null and
     * {@code reflectedClass.getName()} matches {@code tableRef.recordClass().reflectionName()}.
     */
    record DmlEmitted(
        Class<?> reflectedClass,
        TableRef tableRef,
        DmlKind kind,
        SourceKey.Cardinality cardinality,
        SourceLocation location
    ) implements ProducerBinding {
        public DmlEmitted {
            Objects.requireNonNull(reflectedClass, "reflectedClass");
            Objects.requireNonNull(tableRef, "tableRef");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(cardinality, "cardinality");
            Objects.requireNonNull(location, "location");
            String expected = tableRef.recordClass().reflectionName();
            if (!reflectedClass.getName().equals(expected)) {
                throw new IllegalArgumentException(
                    "ProducerBinding.DmlEmitted: reflectedClass (" + reflectedClass.getName()
                        + ") must equal tableRef.recordClass().reflectionName() (" + expected
                        + ") so the per-SDL-type binding fold matches RootTable for "
                        + "the same TableRef");
            }
        }

        @Override public String describe() {
            return "DML " + kind + " (" + cardinality + ") emitted from '"
                + tableRef.tableName() + "'";
        }
    }

    /**
     * An {@code @service} mutation field's reflected return-element class, observed as the
     * producer-side binding for the payload SDL type of an {@code @service}-carrier shape.
     * The {@code @service} method returns {@code XRecord} (single-cardinality) or
     * {@code List<XRecord>} (list-cardinality) where {@code X} matches the payload's
     * inner {@code @table}-typed data field's record class; the observation grounds when the
     * structural read of the payload's SDL fields finds exactly one non-errors-shaped
     * {@code @table}-typed data field whose record class equals the method's return-element
     * class.
     *
     * <p>Sibling of {@link DmlEmitted}: where {@code DmlEmitted} represents the generator-
     * emitted {@code RecordN<PK>} shape a DML mutation fetcher places into
     * {@code env.getSource()}, this represents the developer-emitted {@code XRecord} /
     * {@code List<XRecord>} shape an {@code @service} method returns. The classifier-side
     * dispatch ({@code FieldBuilder.classifyChildFieldOnResultType}) reads the binding via
     * {@code TypeBuilder.serviceEmittedBinding} to construct
     * {@code ChildField.RecordTableField} with {@code SourceKey.Wrap.TableRecord} for
     * the payload's data field.
     *
     * <p>Compact-constructor invariants mirror {@link DmlEmitted}: every component non-null,
     * {@code reflectedClass.getName()} equals {@code tableRef.recordClass().reflectionName()}.
     */
    record ServiceEmitted(
        Class<?> reflectedClass,
        TableRef tableRef,
        SourceKey.Cardinality cardinality,
        String parentTypeName,
        String fieldName,
        String serviceClassName,
        String methodName,
        SourceLocation location
    ) implements ProducerBinding {
        public ServiceEmitted {
            Objects.requireNonNull(reflectedClass, "reflectedClass");
            Objects.requireNonNull(tableRef, "tableRef");
            Objects.requireNonNull(cardinality, "cardinality");
            Objects.requireNonNull(location, "location");
            String expected = tableRef.recordClass().reflectionName();
            if (!reflectedClass.getName().equals(expected)) {
                throw new IllegalArgumentException(
                    "ProducerBinding.ServiceEmitted: reflectedClass (" + reflectedClass.getName()
                        + ") must equal tableRef.recordClass().reflectionName() (" + expected
                        + ") so the per-SDL-type binding fold matches RootTable "
                        + "for the same TableRef");
            }
        }

        @Override public String describe() {
            return "@service-carrier (" + cardinality + ") on " + parentTypeName + "."
                + fieldName + " via " + serviceClassName + "." + methodName;
        }
    }
}
