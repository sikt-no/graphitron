package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;

import java.util.Objects;
import no.sikt.graphitron.render.CatalogRefs;

/**
 * One observed binding from a producer site that reaches an SDL type. Carries the reflected
 * {@link Class} the producer names and a structured description of where the binding came
 * from. Surfaces in {@link Rejection.AuthorError.RecordBindingMultiProducer} when
 * two or more producers reach the same SDL type with disagreeing classes.
 *
 * <p>The arms correspond to the producer sources the reflection walk visits:
 * {@link RootService} for an {@code @service} method's return type, {@link RootTable} for an
 * SDL Object's {@code @table} resolution, {@link ParentAccessor} for an SDL parent's
 * accessor return type, and
 * {@link DmlEmitted} for a DML mutation fetcher's emitted output shape and
 * {@link RoutineEmitted} for a routine-write mutation fetcher's (the two arms whose
 * source is generator-emitted rather than developer-authored reflection).
 */
public sealed interface ProducerBinding
    permits ProducerBinding.RootService, ProducerBinding.RootTable,
            ProducerBinding.ParentAccessor,
            ProducerBinding.DmlEmitted, ProducerBinding.ServiceEmitted,
            ProducerBinding.RoutineEmitted {

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
     * {@code reflectedClass.getName()} matches {@code CatalogRefs.recordClass(tableRef).reflectionName()}.
     */
    record DmlEmitted(
        Class<?> reflectedClass,
        TableRef tableRef,
        DmlKind kind,
        Arity arrival,
        SourceLocation location
    ) implements ProducerBinding, EmittedCarrierBinding {
        public DmlEmitted {
            Objects.requireNonNull(reflectedClass, "reflectedClass");
            Objects.requireNonNull(tableRef, "tableRef");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(arrival, "arrival");
            Objects.requireNonNull(location, "location");
            String expected = CatalogRefs.recordClass(tableRef).reflectionName();
            if (!reflectedClass.getName().equals(expected)) {
                throw new IllegalArgumentException(
                    "ProducerBinding.DmlEmitted: reflectedClass (" + reflectedClass.getName()
                        + ") must equal CatalogRefs.recordClass(tableRef).reflectionName() (" + expected
                        + ") so the per-SDL-type binding fold matches RootTable for "
                        + "the same TableRef");
            }
        }

        @Override public String describe() {
            return "DML " + kind + " (" + arrival + ") emitted from '"
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
     * a record-sourced {@code ChildField.BatchedTableField} with {@code SourceKey.Wrap.TableRecord} for
     * the payload's data field.
     *
     * <p>Compact-constructor invariants mirror {@link DmlEmitted}: every component non-null,
     * {@code reflectedClass.getName()} equals {@code CatalogRefs.recordClass(tableRef).reflectionName()}.
     */
    record ServiceEmitted(
        Class<?> reflectedClass,
        TableRef tableRef,
        Arity arrival,
        String parentTypeName,
        String fieldName,
        String serviceClassName,
        String methodName,
        SourceLocation location
    ) implements ProducerBinding, EmittedCarrierBinding {
        public ServiceEmitted {
            Objects.requireNonNull(reflectedClass, "reflectedClass");
            Objects.requireNonNull(tableRef, "tableRef");
            Objects.requireNonNull(arrival, "arrival");
            Objects.requireNonNull(location, "location");
            String expected = CatalogRefs.recordClass(tableRef).reflectionName();
            if (!reflectedClass.getName().equals(expected)) {
                throw new IllegalArgumentException(
                    "ProducerBinding.ServiceEmitted: reflectedClass (" + reflectedClass.getName()
                        + ") must equal CatalogRefs.recordClass(tableRef).reflectionName() (" + expected
                        + ") so the per-SDL-type binding fold matches RootTable "
                        + "for the same TableRef");
            }
        }

        @Override public String describe() {
            return "@service-carrier (" + arrival + ") on " + parentTypeName + "."
                + fieldName + " via " + serviceClassName + "." + methodName;
        }
    }

    /**
     * A routine-write mutation fetcher's emitted key shape, observed as the producer-side
     * binding for the payload SDL type of a hop-less {@code @routine} Mutation field returning
     * a payload carrier. The fetcher's {@code env.getSource()} carries {@code RecordN<...>}
     * (single data field) or {@code Result<RecordN<...>>} (list data field) projected under the
     * carried {@link TableRef}'s primary-key fields, captured off the routine's own result rows
     * inside the write transaction.
     *
     * <p>Third sibling of {@link DmlEmitted} and {@link ServiceEmitted} (the
     * {@link EmittedCarrierBinding} capability). What this arm carries that its siblings cannot:
     * {@link #capturedPairs()}, the name-matched pairs keying the capture. A hop out of a
     * routine result has no FK metadata to ride, so the pairing matches the target table's
     * primary-key columns by SQL name against the routine's result columns; the pairs' target
     * side is the target table's PK by construction (the same value
     * {@link EmittedCarrierBinding#correlationColumns()} answers on every arm), and the pairs'
     * source side is the routine-only fact step 1 alone consumes. Derived once, at grounding
     * ({@code RecordBindingResolver}), from the routine's result table and the data-field
     * element table's primary key; every reader reads this carried result.
     *
     * <p>{@code arrival} is the payload's <em>data-field</em> arrival (the data field's SDL
     * wrapper, the only cardinality claim in the system for this shape).
     *
     * <p>Compact-constructor invariants mirror the siblings ({@code reflectedClass} equals the
     * target {@code tableRef}'s record class) plus the capture's own: pairs non-empty and every
     * pair name-matched (source and target column share an SQL name, case-insensitively).
     */
    record RoutineEmitted(
        Class<?> reflectedClass,
        TableRef tableRef,
        Arity arrival,
        String routineName,
        TableRef routineResultTable,
        java.util.List<JoinSlot.FkSlot> capturedPairs,
        String parentTypeName,
        String fieldName,
        SourceLocation location
    ) implements ProducerBinding, EmittedCarrierBinding {
        public RoutineEmitted {
            Objects.requireNonNull(reflectedClass, "reflectedClass");
            Objects.requireNonNull(tableRef, "tableRef");
            Objects.requireNonNull(arrival, "arrival");
            Objects.requireNonNull(routineName, "routineName");
            Objects.requireNonNull(routineResultTable, "routineResultTable");
            Objects.requireNonNull(location, "location");
            capturedPairs = java.util.List.copyOf(capturedPairs);
            if (capturedPairs.isEmpty()) {
                throw new IllegalArgumentException(
                    "ProducerBinding.RoutineEmitted: capturedPairs must be non-empty; a "
                        + "pair-less capture has no key for the data field's re-read");
            }
            for (var pair : capturedPairs) {
                if (!pair.sourceSide().sqlName().equalsIgnoreCase(pair.targetSide().sqlName())) {
                    throw new IllegalArgumentException(
                        "ProducerBinding.RoutineEmitted: capturedPairs must be name-matched; "
                            + "pair (" + pair.sourceSide().sqlName() + " -> "
                            + pair.targetSide().sqlName() + ") is not");
                }
            }
            String expected = CatalogRefs.recordClass(tableRef).reflectionName();
            if (!reflectedClass.getName().equals(expected)) {
                throw new IllegalArgumentException(
                    "ProducerBinding.RoutineEmitted: reflectedClass (" + reflectedClass.getName()
                        + ") must equal CatalogRefs.recordClass(tableRef).reflectionName() (" + expected
                        + ") so the per-SDL-type binding fold matches RootTable for "
                        + "the same TableRef");
            }
        }

        @Override public String describe() {
            return "@routine carrier (" + arrival + ") on " + parentTypeName + "."
                + fieldName + " via routine '" + routineName + "' emitted from '"
                + tableRef.tableName() + "'";
        }
    }
}
