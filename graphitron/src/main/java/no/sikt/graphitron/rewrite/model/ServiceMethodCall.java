package no.sikt.graphitron.rewrite.model;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.TableRef;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * A structured description of one {@code @service} method invocation.
 * Produced by {@code ServiceMethodCallWalker} from the SDL definition plus codegen-classloader
 * reflection; consumed by {@code ServiceMethodCallEmitter} to emit the fetcher lambda body's
 * statement list.
 *
 * <p>{@link Static} carries one round ({@code methodArgs}). {@link Instance} carries two
 * ({@code ctorArgs} then {@code methodArgs}); field order mirrors evaluation order. The
 * walker enforces two cross-round invariants: {@link MappingEntry.FromArg} is invalid in
 * {@code ctorArgs}, and two {@link MappingEntry.FromDsl} entries cannot coexist in the same
 * round.
 *
 * <p>{@code javaReturnType} is the structured Java return type used to type the emitter's
 * {@code result} local. The GraphQL-side classification of the field's return lives on the
 * permit's existing {@code ReturnTypeRef} component; the two classifications are orthogonal.
 * {@code returnContainer} is the container that return type is, read once where the return is
 * reflected so no consumer inspects a type name.
 *
 * <p>{@link #validationTargets()} derives, from {@code methodArgs}, the values the pre-execution
 * validation pass checks: the parameters bound to a class the consumer wrote, and the jOOQ records
 * the call writes columns of.
 */
public sealed interface ServiceMethodCall permits ServiceMethodCall.Static, ServiceMethodCall.Instance {

    String fqClassName();
    String methodName();
    List<MappingEntry> methodArgs();
    TypeName javaReturnType();
    ReturnContainer returnContainer();

    /**
     * The method parameters the pre-execution validation pass checks, in parameter order: a class the
     * consumer wrote ({@link ValueShape.BoundClass}), validated whole, and a jOOQ record carrying at
     * least one plain column binding ({@link ValueShape.JooqRecordInput}), validated column by column,
     * each directly or as the elements of a list. These are the very instances the call is about to
     * pass. Parameters binding a scalar, an enum or a {@code Map} pass-through yield none, and so does
     * a record whose bindings are all key decodes, graphitron having decoded those values itself.
     */
    default List<ValidationTarget> validationTargets() {
        var targets = new java.util.ArrayList<ValidationTarget>();
        for (MappingEntry entry : methodArgs()) {
            if (!(entry instanceof MappingEntry.FromArg arg)) {
                continue;
            }
            var direct = ValidationSubject.of(arg.shape());
            if (direct.isPresent()) {
                targets.add(new ValidationTarget.Single(arg.javaName(), direct.get()));
            } else if (arg.shape() instanceof ValueShape.ListOf list) {
                ValidationSubject.of(list.elementShape())
                    .ifPresent(subject -> targets.add(new ValidationTarget.PerElement(arg.javaName(), subject)));
            }
        }
        return List.copyOf(targets);
    }

    record Static(
        String fqClassName,
        String methodName,
        List<MappingEntry> methodArgs,
        TypeName javaReturnType,
        ReturnContainer returnContainer
    ) implements ServiceMethodCall {
        public Static {
            methodArgs = List.copyOf(methodArgs);
            Objects.requireNonNull(returnContainer, "returnContainer");
        }
    }

    record Instance(
        String fqClassName,
        List<MappingEntry> ctorArgs,
        String methodName,
        List<MappingEntry> methodArgs,
        TypeName javaReturnType,
        ReturnContainer returnContainer
    ) implements ServiceMethodCall {
        public Instance {
            ctorArgs = List.copyOf(ctorArgs);
            methodArgs = List.copyOf(methodArgs);
            Objects.requireNonNull(returnContainer, "returnContainer");
        }
    }

    /**
     * One value the pre-execution validation pass checks: the call-site local a parameter's value is
     * declared in, and what is checked on it. {@code local} is {@link MappingEntry.FromArg#javaName()},
     * the name the emitted call passes. The cardinality, one value or a list validated element by
     * element, is orthogonal to the subject.
     */
    sealed interface ValidationTarget {

        /** The call-site local the value is declared in. */
        String local();

        /** What is checked on the value, or on each of its elements. */
        ValidationSubject subject();

        /** A parameter whose value is one instance. */
        record Single(String local, ValidationSubject subject) implements ValidationTarget {
            public Single {
                Objects.requireNonNull(local, "local");
                Objects.requireNonNull(subject, "subject");
            }
        }

        /** A {@code List} parameter; validated element by element. */
        record PerElement(String local, ValidationSubject subject) implements ValidationTarget {
            public PerElement {
                Objects.requireNonNull(local, "local");
                Objects.requireNonNull(subject, "subject");
            }
        }
    }

    /**
     * What the pre-execution validation pass checks on one value: the pre-step's own view of a
     * parameter, not a property of its shape.
     */
    sealed interface ValidationSubject {

        /** The Java type of the value, or of each element. */
        TypeName javaType();

        /**
         * The subject a value of {@code shape} has, empty where it has none: a bound class, or a jOOQ
         * record with a plain column binding.
         */
        static java.util.Optional<ValidationSubject> of(ValueShape shape) {
            return switch (shape) {
                case ValueShape.BoundClass bound -> java.util.Optional.of(new WholeObject(bound));
                case ValueShape.JooqRecordInput record when !record.carrier().columnBindings().isEmpty() ->
                    java.util.Optional.of(new WrittenColumns(record));
                default -> java.util.Optional.empty();
            };
        }

        /**
         * A class the consumer wrote, validated whole with the constraints declared on it, and
         * transitively where it asks for that with {@code @Valid}.
         */
        record WholeObject(ValueShape.BoundClass boundClass) implements ValidationSubject {
            public WholeObject {
                Objects.requireNonNull(boundClass, "boundClass");
            }

            @Override public TypeName javaType() { return boundClass.javaClass(); }
        }

        /**
         * A jOOQ record, validated one property at a time for the columns it writes: each plain
         * column binding whose column the record reads through a known getter is one
         * {@link ColumnCheck}. Key decodes are not checked, graphitron having decoded those values.
         */
        record WrittenColumns(ValueShape.JooqRecordInput record) implements ValidationSubject {
            public WrittenColumns {
                Objects.requireNonNull(record, "record");
            }

            @Override public TypeName javaType() { return record.javaType(); }

            /**
             * Which of the record's plain column bindings are checked, and which cannot be:
             * {@code propertyOf} answers the Bean Validation property the record reads a column
             * through, empty where the capture found no getter. The pre-step reads the checks and
             * the lint the unchecked bindings, so the two cannot disagree about a column.
             */
            public Partition partition(java.util.function.BiFunction<TableRef, ColumnRef,
                    java.util.Optional<String>> propertyOf) {
                var table = record.carrier().table();
                var checks = new java.util.ArrayList<ColumnCheck>();
                var unchecked = new java.util.ArrayList<CallSiteExtraction.ColumnBinding>();
                for (var binding : record.carrier().columnBindings()) {
                    propertyOf.apply(table, binding.column()).ifPresentOrElse(
                        property -> checks.add(new ColumnCheck(binding.column(), property, binding.path())),
                        () -> unchecked.add(binding));
                }
                return new Partition(checks, unchecked);
            }
        }

        /**
         * One column check: the column the changed-flag guard ({@code touched}) tests, the property
         * {@code validateProperty} names, and the binding's primary SDL path the violation is
         * reported at (the live name in an alias group).
         */
        record ColumnCheck(ColumnRef column, String property, List<String> primaryPath) {
            public ColumnCheck {
                Objects.requireNonNull(column, "column");
                Objects.requireNonNull(property, "property");
                primaryPath = List.copyOf(primaryPath);
            }
        }

        /** A {@link WrittenColumns} subject's bindings, split into those checked and those not. */
        record Partition(List<ColumnCheck> checks, List<CallSiteExtraction.ColumnBinding> unchecked) {
            public Partition {
                checks = List.copyOf(checks);
                unchecked = List.copyOf(unchecked);
            }
        }
    }

    /**
     * The container the service method's Java return type is: what an empty answer to the call
     * would be, where graphitron can build one.
     */
    sealed interface ReturnContainer {

        /** A {@code java.util.List} or {@code java.util.Collection}. */
        record ListLike() implements ReturnContainer {
            public static final ListLike INSTANCE = new ListLike();
        }

        /** A {@code java.util.Set}. */
        record SetLike() implements ReturnContainer {
            public static final SetLike INSTANCE = new SetLike();
        }

        /** A jOOQ {@code Result} over {@code table}'s record class. */
        record JooqResult(TableRef table) implements ReturnContainer {
            public JooqResult {
                Objects.requireNonNull(table, "table");
            }
        }

        /**
         * Anything else: a payload class, a scalar, a single record, an {@code Optional}, a
         * {@code CompletableFuture}, or a {@code Result} over a record class the catalog does not
         * name.
         */
        record Other() implements ReturnContainer {
            public static final Other INSTANCE = new Other();
        }

        /**
         * Classifies a reflected return type. {@code recordTable} resolves a jOOQ record class to
         * its catalog table, for the {@link JooqResult} arm.
         */
        static ReturnContainer of(TypeName javaReturnType,
                                  Function<ClassName, Optional<TableRef>> recordTable) {
            if (!(javaReturnType instanceof ParameterizedTypeName ptn) || ptn.typeArguments().size() != 1) {
                return Other.INSTANCE;
            }
            return switch (ptn.rawType().canonicalName()) {
                case "java.util.List", "java.util.Collection" -> ListLike.INSTANCE;
                case "java.util.Set" -> SetLike.INSTANCE;
                case "org.jooq.Result" -> ptn.typeArguments().get(0) instanceof ClassName record
                    ? recordTable.apply(record).<ReturnContainer>map(JooqResult::new).orElse(Other.INSTANCE)
                    : Other.INSTANCE;
                default -> Other.INSTANCE;
            };
        }
    }
}
