package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.LifterRef;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_CLASS_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_LIFTER;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_METHOD;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_TARGET_COLUMNS;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_AS_CONNECTION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_BATCH_KEY_LIFTER;
import static no.sikt.graphitron.rewrite.BuildContext.asMap;

/**
 * Resolves {@code @batchKeyLifter} on a child field whose {@code @record} parent has no FK
 * metadata in the jOOQ catalog. The schema author supplies a static Java method that lifts a
 * batch-key {@code RowN} value out of the parent DTO; this resolver classifies the directive,
 * reflects on the lifter method, validates the arity / column-class match, and produces a
 * {@link BatchKey.LifterRowKeyed} together with the {@link JoinStep.LiftedHop} the rows-method
 * emitter consumes.
 *
 * <p>Sibling of the ten directive / projection resolvers R6 lifted out of {@code FieldBuilder}
 * ({@link ServiceDirectiveResolver}, {@link LookupKeyDirectiveResolver},
 * {@link ExternalFieldDirectiveResolver}, etc.). Same shape: sealed {@link Resolved} result with
 * {@code Ok} and {@code Rejected} arms; constructor-injected dependencies; single
 * {@link #resolve} entry point.
 *
 * <p>Two paired classifier checks the emitters depend on:
 *
 * <ul>
 *   <li>{@code lifter-classifies-as-record-table-field} — every successful lifter resolution
 *       projects into {@code RecordTableField} or {@code RecordLookupTableField}, both of
 *       which narrow {@code batchKey()} to {@link BatchKey.RecordParentBatchKey} at the model
 *       level. The {@code SplitRowsMethodEmitter} prelude relies on this routing.</li>
 *   <li>{@code lifter-batchkey-is-lifterrowkeyed} — on the lifter path, the produced
 *       {@link BatchKey.RecordParentBatchKey} is always {@link BatchKey.LifterRowKeyed}, never
 *       {@link BatchKey.RowKeyed}. {@code GeneratorUtils.buildRecordParentKeyExtraction}'s
 *       two-arm sealed switch and the rows-method prelude's {@code RecordParentBatchKey}
 *       accessor pair rely on this so the type system carries the discrimination.</li>
 * </ul>
 *
 * <p>The single-hop guarantee (every lifter path is one hop) is <em>not</em> a keyed fact: it
 * is a structural model property — {@link BatchKey.LifterRowKeyed} holds a single
 * {@link JoinStep.LiftedHop}, not a list. No producer / consumer pair is needed; find-usages
 * on {@code LifterRowKeyed.hop} recovers the same navigation.
 */
final class BatchKeyLifterDirectiveResolver {

    /** Maximum {@code RowN} arity supported by jOOQ's typed Row interface. */
    private static final int ROWN_CEILING = 22;

    /**
     * Outcome of {@link #resolve}. Two terminal arms; the caller exhausts them with a switch.
     *
     * <ul>
     *   <li>{@link Ok} — successful resolution, carrying the resolved {@link BatchKey.LifterRowKeyed}
     *       and the {@link ReturnTypeRef.TableBoundReturnType} for the field. The published
     *       {@link JoinStep.LiftedHop} is reachable via {@code batchKey.hop()}; no second copy
     *       of the column tuple exists.</li>
     *   <li>{@link Rejected} — every error path: wrong parent shape, missing class, missing
     *       method, return-type mismatch, arity / column-class mismatch, target-column miss.</li>
     * </ul>
     */
    sealed interface Resolved {
        record Ok(
            BatchKey.LifterRowKeyed batchKey,
            ReturnTypeRef.TableBoundReturnType tbReturnType
        ) implements Resolved {

            /** Single source of truth for the target-side column tuple. */
            public JoinStep.LiftedHop liftedHop() {
                return batchKey.hop();
            }

            public TableRef targetTable() {
                return batchKey.hop().targetTable();
            }
        }

        record Rejected(Rejection rejection) implements Resolved {
            public String message() { return rejection.message(); }
            public RejectionKind kind() { return RejectionKind.of(rejection); }
        }
    }

    private final BuildContext ctx;
    private final FieldBuilder fb;

    BatchKeyLifterDirectiveResolver(BuildContext ctx, FieldBuilder fb) {
        this.ctx = ctx;
        this.fb = fb;
    }

    @no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck(
        key = "lifter-classifies-as-record-table-field",
        description = "A field carrying @batchKeyLifter classifies as RecordTableField or "
            + "RecordLookupTableField (never any other variant). The RecordParentBatchKey-typed "
            + "call sites in GeneratorUtils.buildRecordParentKeyExtraction depend on this routing "
            + "so the type system excludes @service-only BatchKey permits at the parameter type.")
    @no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck(
        key = "lifter-batchkey-is-lifterrowkeyed",
        description = "On the lifter path, the field's BatchKey is LifterRowKeyed (and never any "
            + "other RecordParentBatchKey permit). buildRecordParentKeyExtraction's two-arm switch "
            + "and the rows-method prelude's RecordParentBatchKey accessor pair rely on this so "
            + "the type system carries the discrimination.")
    Resolved resolve(
            String parentTypeName,
            GraphQLFieldDefinition fieldDef,
            GraphitronType.ResultType parentResultType,
            String elementTypeName) {
        String fieldName = fieldDef.getName();

        // 0. @asConnection short-circuit (Invariant #9): the connection classifier expands to
        // Connection<T> and routes through pagination helpers that don't share the rows-method
        // DataLoader path.
        if (fieldDef.hasAppliedDirective(DIR_AS_CONNECTION)) {
            return new Resolved.Rejected(Rejection.structural("@batchKeyLifter is not supported on @asConnection fields"));
        }

        // 1. Parent shape: must be PojoResultType or JavaRecordType, both with non-null fqClassName.
        String parentFqClassName = parentBackingClass(parentResultType);
        if (parentFqClassName == null) {
            return new Resolved.Rejected(Rejection.structural(rejectByParentShape(parentResultType, parentTypeName, fieldName)));
        }

        // 2. Resolve the field's @table return type. The directive only applies to table-bound
        //    returns; non-table returns are rejected with a directive-specific message.
        GraphitronType target = ctx.types.get(elementTypeName);
        if (!(target instanceof GraphitronType.TableBackedType tbt)) {
            return new Resolved.Rejected(Rejection.structural("@batchKeyLifter on '" + parentTypeName + "." + fieldName
                + "' applies only to fields whose return type is @table-bound; got '"
                + elementTypeName + "'"));
        }
        TableRef targetTable = tbt.table();
        var tbReturnType = new ReturnTypeRef.TableBoundReturnType(
            elementTypeName, targetTable, fb.buildWrapper(fieldDef));

        // 3. Read the directive payload.
        var dir = fieldDef.getAppliedDirective(DIR_BATCH_KEY_LIFTER);
        if (dir == null) {
            // Caller pre-checked hasAppliedDirective; reaching here is a classifier bug.
            throw new IllegalStateException(
                "BatchKeyLifterDirectiveResolver invoked on field without @batchKeyLifter: "
                + parentTypeName + "." + fieldName);
        }

        @SuppressWarnings("unchecked")
        List<String> targetColumnNames = Optional.ofNullable(dir.getArgument(ARG_TARGET_COLUMNS))
            .map(a -> (List<String>) a.getValue())
            .orElse(List.of());
        if (targetColumnNames.isEmpty()) {
            return new Resolved.Rejected(Rejection.structural("@batchKeyLifter on '" + parentTypeName + "." + fieldName
                + "' must declare at least one target column"));
        }

        // 4. Resolve each targetColumn against the field's @table return.
        List<ColumnRef> targetColumns = new ArrayList<>(targetColumnNames.size());
        for (String columnName : targetColumnNames) {
            var columnEntry = ctx.catalog.findColumn(targetTable.tableName(), columnName);
            if (columnEntry.isEmpty()) {
                List<String> candidates = ctx.catalog.allColumnsOf(targetTable.tableName())
                    .stream().map(JooqCatalog.ColumnEntry::sqlName).toList();
                return new Resolved.Rejected(Rejection.structural("@batchKeyLifter on '" + parentTypeName + "." + fieldName
                    + "': target column '" + columnName + "' not found on table '"
                    + targetTable.tableName() + "'"
                    + BuildContext.candidateHint(columnName, candidates)));
            }
            var ce = columnEntry.get();
            targetColumns.add(new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()));
        }

        // 5. Reflect the lifter method.
        var lifterArg = dir.getArgument(ARG_LIFTER);
        if (lifterArg == null) {
            return new Resolved.Rejected(Rejection.structural("@batchKeyLifter on '" + parentTypeName + "." + fieldName
                + "' is missing the 'lifter' argument"));
        }
        Map<String, Object> lifterRefMap = asMap(lifterArg.getValue());
        String lifterClassName = Optional.ofNullable(lifterRefMap.get(ARG_CLASS_NAME))
            .map(Object::toString).orElse(null);
        String lifterMethodName = Optional.ofNullable(lifterRefMap.get(ARG_METHOD))
            .map(Object::toString).orElse(null);
        if (lifterClassName == null) {
            return new Resolved.Rejected(Rejection.structural("@batchKeyLifter on '" + parentTypeName + "." + fieldName
                + "': lifter reference is missing 'className'"));
        }
        if (lifterMethodName == null) {
            return new Resolved.Rejected(Rejection.structural("@batchKeyLifter on '" + parentTypeName + "." + fieldName
                + "': lifter reference is missing 'method'"));
        }

        Class<?> lifterClass;
        try {
            lifterClass = Class.forName(lifterClassName);
        } catch (ClassNotFoundException e) {
            return new Resolved.Rejected(Rejection.structural("@batchKeyLifter on '" + parentTypeName + "." + fieldName
                + "': lifter class '" + lifterClassName + "' could not be loaded"));
        }

        Class<?> parentClass;
        try {
            parentClass = Class.forName(parentFqClassName);
        } catch (ClassNotFoundException e) {
            return new Resolved.Rejected(Rejection.structural("@batchKeyLifter on '" + parentTypeName + "." + fieldName
                + "': parent backing class '" + parentFqClassName + "' could not be loaded"));
        }

        List<Method> namedMethods = new ArrayList<>();
        for (Method m : lifterClass.getDeclaredMethods()) {
            if (m.getName().equals(lifterMethodName) && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                namedMethods.add(m);
            }
        }
        if (namedMethods.isEmpty()) {
            List<String> candidates = new ArrayList<>();
            for (Method m : lifterClass.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    candidates.add(m.getName());
                }
            }
            return new Resolved.Rejected(Rejection.structural("@batchKeyLifter on '" + parentTypeName + "." + fieldName
                + "': no static method named '" + lifterMethodName + "' on class '"
                + lifterClassName + "'"
                + BuildContext.candidateHint(lifterMethodName, candidates)));
        }
        if (namedMethods.size() > 1) {
            return new Resolved.Rejected(Rejection.structural("@batchKeyLifter on '" + parentTypeName + "." + fieldName
                + "': multiple static methods named '" + lifterMethodName + "' on class '"
                + lifterClassName + "'; the lifter must be uniquely identifiable by name"));
        }
        Method lifterMethod = namedMethods.get(0);

        // 5a. Single parameter, assignable from parent's backing class.
        if (lifterMethod.getParameterCount() != 1) {
            return new Resolved.Rejected(Rejection.structural("@batchKeyLifter on '" + parentTypeName + "." + fieldName
                + "': lifter method '" + lifterMethodName + "' must take exactly one parameter; got "
                + lifterMethod.getParameterCount()));
        }
        Class<?> liftedParam = lifterMethod.getParameterTypes()[0];
        if (!liftedParam.isAssignableFrom(parentClass)) {
            return new Resolved.Rejected(Rejection.structural("@batchKeyLifter on '" + parentTypeName + "." + fieldName
                + "': lifter method '" + lifterMethodName + "' parameter type '"
                + liftedParam.getName() + "' is not assignable from the parent's backing class '"
                + parentFqClassName + "'"));
        }

        // 5b. Return type must be org.jooq.Row1..Row22.
        Type genericReturn = lifterMethod.getGenericReturnType();
        if (!(genericReturn instanceof ParameterizedType pt)
                || !(pt.getRawType() instanceof Class<?> rawReturn)
                || !rawReturn.getName().startsWith("org.jooq.Row")) {
            return new Resolved.Rejected(Rejection.structural("@batchKeyLifter on '" + parentTypeName + "." + fieldName
                + "': lifter method '" + lifterMethodName
                + "' must return org.jooq.Row1..Row" + ROWN_CEILING + "; got '"
                + genericReturn.getTypeName() + "'"));
        }
        String suffix = rawReturn.getName().substring("org.jooq.Row".length());
        int rowArity;
        try {
            rowArity = Integer.parseInt(suffix);
        } catch (NumberFormatException e) {
            return new Resolved.Rejected(Rejection.structural("@batchKeyLifter on '" + parentTypeName + "." + fieldName
                + "': lifter method '" + lifterMethodName
                + "' must return org.jooq.Row1..Row" + ROWN_CEILING + "; got '"
                + rawReturn.getName() + "'"));
        }
        if (rowArity < 1 || rowArity > ROWN_CEILING) {
            return new Resolved.Rejected(Rejection.structural("@batchKeyLifter on '" + parentTypeName + "." + fieldName
                + "': lifter method '" + lifterMethodName
                + "' return arity " + rowArity + " is outside the supported range Row1..Row"
                + ROWN_CEILING));
        }

        // 5c. Arity must match targetColumns.size().
        if (rowArity != targetColumns.size()) {
            return new Resolved.Rejected(Rejection.structural("@batchKeyLifter on '" + parentTypeName + "." + fieldName
                + "': lifter '" + lifterMethodName + "' Row<N> arity " + rowArity
                + " does not match targetColumns size " + targetColumns.size()));
        }

        // 5d. Each RowN type argument must equal the corresponding target column's Java type
        //     by exact equality (boxed names; no wildcard tolerance).
        Type[] rowTypeArgs = pt.getActualTypeArguments();
        for (int i = 0; i < rowTypeArgs.length; i++) {
            Type arg = rowTypeArgs[i];
            if (arg instanceof WildcardType) {
                return new Resolved.Rejected(Rejection.structural("@batchKeyLifter on '" + parentTypeName + "." + fieldName
                    + "': lifter '" + lifterMethodName + "' RowN type at position " + i
                    + " is a wildcard '" + arg.getTypeName()
                    + "'; declare a concrete type matching target column '"
                    + targetColumns.get(i).sqlName() + "' (Java type '"
                    + targetColumns.get(i).columnClass() + "')"));
            }
            if (!(arg instanceof Class<?> argClass)) {
                return new Resolved.Rejected(Rejection.structural("@batchKeyLifter on '" + parentTypeName + "." + fieldName
                    + "': lifter '" + lifterMethodName + "' RowN type at position " + i
                    + " has unsupported shape '" + arg.getTypeName()
                    + "'; declare a concrete class matching target column '"
                    + targetColumns.get(i).sqlName() + "' (Java type '"
                    + targetColumns.get(i).columnClass() + "')"));
            }
            String expected = targetColumns.get(i).columnClass();
            if (!argClass.getName().equals(expected)) {
                return new Resolved.Rejected(Rejection.structural("@batchKeyLifter on '" + parentTypeName + "." + fieldName
                    + "': lifter '" + lifterMethodName + "' RowN type at position " + i
                    + " ('" + argClass.getName() + "') does not match target column '"
                    + targetColumns.get(i).sqlName() + "' Java type ('" + expected + "')"));
            }
        }

        // 6. Build the LiftedHop and the LifterRowKeyed BatchKey. Single source of truth: the
        //    BatchKey reads through hop.targetColumns(); no second copy of the column list exists.
        String alias = fieldName + "_0";
        var hop = new JoinStep.LiftedHop(targetTable, targetColumns, alias);
        var lifterRef = new LifterRef(ClassName.get(lifterClass), lifterMethodName);
        var batchKey = new BatchKey.LifterRowKeyed(hop, lifterRef);
        return new Resolved.Ok(batchKey, tbReturnType);
    }

    /**
     * Returns the parent's backing class FQ name, or {@code null} if the parent is not
     * a {@code @record} shape that admits a lifter (Invariant #1).
     */
    private static String parentBackingClass(GraphitronType.ResultType parentResultType) {
        if (parentResultType instanceof GraphitronType.PojoResultType prt && prt.fqClassName() != null) {
            return prt.fqClassName();
        }
        if (parentResultType instanceof GraphitronType.JavaRecordType jrt && jrt.fqClassName() != null) {
            return jrt.fqClassName();
        }
        return null;
    }

    private static String rejectByParentShape(
            GraphitronType.ResultType parentResultType, String parentTypeName, String fieldName) {
        if (parentResultType instanceof GraphitronType.JooqTableRecordType
                || parentResultType instanceof GraphitronType.JooqRecordType) {
            return "@batchKeyLifter on '" + parentTypeName + "." + fieldName
                + "' is not supported on jOOQ-backed @record parents; the catalog record's "
                + "columns drive batching — use the existing FK path or @reference";
        }
        return "@batchKeyLifter on '" + parentTypeName + "." + fieldName
            + "' requires the @record parent to declare a backing class via "
            + "@record(record: {className: ...})";
    }
}
