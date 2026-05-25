package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.JoinSlot;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.LifterRef;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.TypeNames;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_CLASS_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_METHOD;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_AS_CONNECTION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_REFERENCE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_SOURCE_ROW;

/**
 * Resolves {@code @sourceRow} on a child field whose {@code @record} parent has no FK
 * metadata in the jOOQ catalog. The schema author supplies a static Java method that
 * extracts a {@code RowN} value out of the parent DTO; this resolver classifies the
 * directive, reflects on the lifter method, derives the expected parent-side column tuple
 * from {@code @reference}'s first hop or the leaf target's PK, validates the arity / column-
 * class match, and produces the {@link SourceKey} + {@link LoaderRegistration} pair together
 * with the {@link JoinStep} chain the rows-method emitter consumes.
 *
 * <p>Sibling of the directive / projection resolvers ({@link ServiceDirectiveResolver},
 * {@link LookupKeyDirectiveResolver}, {@link ExternalFieldDirectiveResolver}, etc.). Same
 * shape: sealed {@link Resolved} result with {@code Ok} and {@code Rejected} arms;
 * constructor-injected dependencies; single {@link #resolve} entry point.
 *
 * <p>Three paired classifier checks the emitters depend on:
 *
 * <ul>
 *   <li>{@code sourcerow-classifies-as-record-table-field} — every successful
 *       {@code @sourceRow} resolution projects into {@code RecordTableField} or
 *       {@code RecordLookupTableField}; the produced {@link SourceKey}'s
 *       {@link SourceKey.Reader} is always {@link SourceKey.Reader.SourceRowsCall}.</li>
 *   <li>{@code sourcerow-leafkey-sourcerows-singlehop} — on the no-{@code @reference}
 *       path, the produced {@link SourceKey}'s path is a single
 *       {@link JoinStep.LiftedHop} (the leaf-PK shape).</li>
 *   <li>{@code sourcerow-pathkey-sourcerows-fkchain} — on the {@code @reference}-composed
 *       path, the produced {@link SourceKey}'s path is the resolved FK chain (one or more
 *       {@link JoinStep.FkJoin}s) from the parent's lifter columns to the leaf table.</li>
 * </ul>
 *
 * <h2>Rejection messages</h2>
 *
 * <p>The user-facing diagnostic strings emitted on the arity / per-position-type mismatch
 * paths are anchored to the howto's "Rejection messages" subsection so the schema-author
 * guidance and the resolver's emitted strings share one source. The two templates:
 *
 * <ul>
 *   <li><b>{@code @reference} present.</b>
 *       {@code "@sourceRow on '<parent>.<field>': lifter '<method>' RowN type at position <i> ('<actual>') does not match first-hop source-side column '<col>' of FK '<fk>' (Java type '<expected>')"}</li>
 *   <li><b>Leaf-PK case.</b>
 *       {@code "@sourceRow on '<parent>.<field>': lifter '<method>' RowN type at position <i> ('<actual>') does not match primary key column '<col>' of '<leaf>' (Java type '<expected>')"}</li>
 * </ul>
 */
final class SourceRowDirectiveResolver {

    /** Maximum {@code RowN} arity supported by jOOQ's typed Row interface. */
    private static final int ROWN_CEILING = 22;

    /**
     * Outcome of {@link #resolve}. Two terminal arms; the caller exhausts them with a switch.
     *
     * <ul>
     *   <li>{@link Ok} — successful resolution, carrying the resolved {@link SourceKey} +
     *       {@link LoaderRegistration} pair, the {@link JoinStep} chain the rows-method
     *       emitter consumes, and the {@link ReturnTypeRef.TableBoundReturnType} for the
     *       field. {@code joinPath} is {@code [hop]} on the leaf-PK arm and the resolved
     *       {@link JoinStep.FkJoin} chain on the {@code @reference} arm.</li>
     *   <li>{@link Rejected} — every error path: wrong parent shape, missing class, missing
     *       method, return-type mismatch, arity / column-class mismatch, {@code @reference}
     *       parse failure.</li>
     * </ul>
     */
    sealed interface Resolved {
        record Ok(
            SourceKey sourceKey,
            LoaderRegistration loaderRegistration,
            List<JoinStep> joinPath,
            ReturnTypeRef.TableBoundReturnType tbReturnType
        ) implements Resolved {}

        record Rejected(Rejection rejection) implements Resolved {
            public String message() { return rejection.message(); }
            public RejectionKind kind() { return RejectionKind.of(rejection); }
        }
    }

    private final BuildContext ctx;
    private final FieldBuilder fb;

    SourceRowDirectiveResolver(BuildContext ctx, FieldBuilder fb) {
        this.ctx = ctx;
        this.fb = fb;
    }

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
            return new Resolved.Rejected(Rejection.structural("@sourceRow is not supported on @asConnection fields"));
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
            return new Resolved.Rejected(Rejection.structural("@sourceRow on '" + parentTypeName + "." + fieldName
                + "' applies only to fields whose return type is @table-bound; got '"
                + elementTypeName + "'"));
        }
        TableRef leafTable = tbt.table();
        var tbReturnType = new ReturnTypeRef.TableBoundReturnType(
            elementTypeName, leafTable, fb.buildWrapper(fieldDef));

        // 3. Read the directive payload. Flat (className, method) args, not the
        //    ExternalCodeReference wrapper used by older directives — @sourceRow is brand-new
        //    and carries no legacy 'name:' migration.
        var dir = fieldDef.getAppliedDirective(DIR_SOURCE_ROW);
        if (dir == null) {
            // Caller pre-checked hasAppliedDirective; reaching here is a classifier bug.
            throw new IllegalStateException(
                "SourceRowDirectiveResolver invoked on field without @sourceRow: "
                + parentTypeName + "." + fieldName);
        }
        String lifterClassName = Optional.ofNullable(dir.getArgument(ARG_CLASS_NAME))
            .map(a -> a.getValue())
            .map(Object::toString).orElse(null);
        String lifterMethodName = Optional.ofNullable(dir.getArgument(ARG_METHOD))
            .map(a -> a.getValue())
            .map(Object::toString).orElse(null);
        if (lifterClassName == null) {
            return new Resolved.Rejected(Rejection.structural("@sourceRow on '" + parentTypeName + "." + fieldName
                + "' is missing 'className'"));
        }
        if (lifterMethodName == null) {
            return new Resolved.Rejected(Rejection.structural("@sourceRow on '" + parentTypeName + "." + fieldName
                + "' is missing 'method'"));
        }

        // 4. Reflect the lifter method: class load, method discovery, single-parameter
        //    check, RowN raw-return + arity-bounds checks. The arity check against the
        //    derived parent-side tuple lives in step 6, not here.
        Class<?> lifterClass;
        try {
            lifterClass = Class.forName(lifterClassName, false, ctx.codegenLoader());
        } catch (ClassNotFoundException e) {
            return new Resolved.Rejected(Rejection.structural("@sourceRow on '" + parentTypeName + "." + fieldName
                + "': lifter class '" + lifterClassName + "' could not be loaded"));
        }

        Class<?> parentClass;
        try {
            parentClass = Class.forName(parentFqClassName, false, ctx.codegenLoader());
        } catch (ClassNotFoundException e) {
            return new Resolved.Rejected(Rejection.structural("@sourceRow on '" + parentTypeName + "." + fieldName
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
            return new Resolved.Rejected(Rejection.unknownLifterMethod(
                "@sourceRow on '" + parentTypeName + "." + fieldName
                + "': no static method named '" + lifterMethodName + "' on class '"
                + lifterClassName + "'",
                lifterMethodName, candidates));
        }
        if (namedMethods.size() > 1) {
            return new Resolved.Rejected(Rejection.structural("@sourceRow on '" + parentTypeName + "." + fieldName
                + "': multiple static methods named '" + lifterMethodName + "' on class '"
                + lifterClassName + "'; the lifter must be uniquely identifiable by name"));
        }
        Method lifterMethod = namedMethods.get(0);

        if (lifterMethod.getParameterCount() != 1) {
            return new Resolved.Rejected(Rejection.structural("@sourceRow on '" + parentTypeName + "." + fieldName
                + "': lifter method '" + lifterMethodName + "' must take exactly one parameter; got "
                + lifterMethod.getParameterCount()));
        }
        Class<?> liftedParam = lifterMethod.getParameterTypes()[0];
        if (!liftedParam.isAssignableFrom(parentClass)) {
            return new Resolved.Rejected(Rejection.structural("@sourceRow on '" + parentTypeName + "." + fieldName
                + "': lifter method '" + lifterMethodName + "' parameter type '"
                + liftedParam.getName() + "' is not assignable from the parent's backing class '"
                + parentFqClassName + "'"));
        }

        Type genericReturn = lifterMethod.getGenericReturnType();
        if (!(genericReturn instanceof ParameterizedType pt)
                || !(pt.getRawType() instanceof Class<?> rawReturn)
                || !rawReturn.getName().startsWith("org.jooq.Row")) {
            return new Resolved.Rejected(Rejection.structural("@sourceRow on '" + parentTypeName + "." + fieldName
                + "': lifter method '" + lifterMethodName
                + "' must return org.jooq.Row1..Row" + ROWN_CEILING + "; got '"
                + TypeNames.simple(TypeName.get(genericReturn)) + "'"));
        }
        String suffix = rawReturn.getName().substring("org.jooq.Row".length());
        int rowArity;
        try {
            rowArity = Integer.parseInt(suffix);
        } catch (NumberFormatException e) {
            return new Resolved.Rejected(Rejection.structural("@sourceRow on '" + parentTypeName + "." + fieldName
                + "': lifter method '" + lifterMethodName
                + "' must return org.jooq.Row1..Row" + ROWN_CEILING + "; got '"
                + rawReturn.getSimpleName() + "'"));
        }
        if (rowArity < 1 || rowArity > ROWN_CEILING) {
            return new Resolved.Rejected(Rejection.structural("@sourceRow on '" + parentTypeName + "." + fieldName
                + "': lifter method '" + lifterMethodName
                + "' return arity " + rowArity + " is outside the supported range Row1..Row"
                + ROWN_CEILING));
        }

        // 5. Derive the expected parent-side column tuple by directive shape.
        boolean hasReference = fieldDef.hasAppliedDirective(DIR_REFERENCE);
        Derivation derivation;
        if (hasReference) {
            // @reference present: delegate to parsePath with startSqlTableName = null (the
            // supported "source not table-backed" path). The first FK hop's source-side
            // columns are the lifter's contract; subsequent hops walk the catalog FK chain.
            var parsed = ctx.parsePath(fieldDef, fieldName, /*startSqlTableName=*/ null,
                leafTable.tableName(), tbReturnType.wrapper().isList());
            if (parsed.hasError()) {
                return new Resolved.Rejected(Rejection.structural(
                    "@sourceRow on '" + parentTypeName + "." + fieldName
                    + "' has @reference parse error: " + parsed.errorMessage()));
            }
            if (parsed.elements().isEmpty()) {
                return new Resolved.Rejected(Rejection.structural(
                    "@sourceRow on '" + parentTypeName + "." + fieldName
                    + "' carries an empty @reference path; remove @reference for the leaf-PK "
                    + "case or supply at least one path element"));
            }
            JoinStep firstStep = parsed.elements().getFirst();
            if (!(firstStep instanceof JoinStep.WithTarget wt)) {
                return new Resolved.Rejected(Rejection.structural(
                    "@sourceRow on '" + parentTypeName + "." + fieldName
                    + "': @reference's first element resolved to a ConditionJoin which has no "
                    + "source-side columns; @sourceRow's first hop must be an FK"));
            }
            String fkLabel = firstStep instanceof JoinStep.FkJoin fk
                ? fk.fk().constantName() : "(non-FK first hop)";
            derivation = new Derivation.Path(parsed.elements(), wt.sourceSideColumns(), fkLabel);
        } else {
            // No @reference: read the leaf target's PK columns directly. The hop is a single
            // column-equality JOIN where source-side and target-side fold onto the same column.
            List<ColumnRef> leafPk = leafTable.primaryKeyColumns();
            if (leafPk.isEmpty()) {
                return new Resolved.Rejected(Rejection.structural(
                    "@sourceRow on '" + parentTypeName + "." + fieldName
                    + "' (no @reference) requires the leaf target table '" + leafTable.tableName()
                    + "' to have a primary key; the catalog reports none"));
            }
            derivation = new Derivation.Leaf(leafPk, leafTable.tableName());
        }

        // 6. Validate RowN arity and per-position erasure against the derived tuple.
        List<ColumnRef> expectedTuple = derivation.expectedTuple();
        if (rowArity != expectedTuple.size()) {
            return new Resolved.Rejected(Rejection.structural("@sourceRow on '" + parentTypeName + "." + fieldName
                + "': lifter '" + lifterMethodName + "' Row<N> arity " + rowArity
                + " does not match the derived parent-side tuple size " + expectedTuple.size()
                + " " + derivation.derivedShapeLabel()));
        }
        Type[] rowTypeArgs = pt.getActualTypeArguments();
        for (int i = 0; i < rowTypeArgs.length; i++) {
            Type arg = rowTypeArgs[i];
            ColumnRef col = expectedTuple.get(i);
            if (arg instanceof WildcardType) {
                return new Resolved.Rejected(Rejection.structural("@sourceRow on '" + parentTypeName + "." + fieldName
                    + "': lifter '" + lifterMethodName + "' RowN type at position " + i
                    + " is a wildcard '" + arg.getTypeName() + "'; declare a concrete type "
                    + derivation.colMismatchTail(col)));
            }
            if (!(arg instanceof Class<?> argClass)) {
                return new Resolved.Rejected(Rejection.structural("@sourceRow on '" + parentTypeName + "." + fieldName
                    + "': lifter '" + lifterMethodName + "' RowN type at position " + i
                    + " has unsupported shape '" + arg.getTypeName() + "'; declare a concrete "
                    + "class " + derivation.colMismatchTail(col)));
            }
            String expected = col.columnClass();
            if (!argClass.getName().equals(expected)) {
                return new Resolved.Rejected(Rejection.structural(
                    derivation.perPositionMismatchMessage(parentTypeName, fieldName,
                        lifterMethodName, i, argClass.getName(), col, expected)));
            }
        }

        // 7. Construct the SourceKey + LoaderRegistration pair. Wrap is always Row (the lifter
        // contract emits a RowN<...> key), Reader is SourceRowsCall(lifter). Cardinality follows
        // the field wrapper. LoaderRegistration is the @sourceRow constant
        // (POSITIONAL_LIST + LOAD_ONE + valueIsList from wrapper).
        var lifterRef = new LifterRef(ClassName.get(lifterClass), lifterMethodName);
        boolean isList = tbReturnType.wrapper().isList();
        SourceKey.Cardinality cardinality = isList ? SourceKey.Cardinality.MANY : SourceKey.Cardinality.ONE;
        LoaderRegistration loaderRegistration = new LoaderRegistration(
            isList,
            LoaderRegistration.Container.POSITIONAL_LIST,
            LoaderRegistration.Dispatch.LOAD_ONE);
        return switch (derivation) {
            case Derivation.Leaf leaf -> {
                String alias = fieldName + "_0";
                List<JoinSlot.LifterSlot> slots = leaf.expectedTuple().stream()
                    .map(JoinSlot.LifterSlot::new)
                    .toList();
                var hop = new JoinStep.LiftedHop(leafTable, slots, alias);
                SourceKey sourceKey = new SourceKey(
                    leafTable,
                    leaf.expectedTuple(),
                    List.of(hop),
                    new SourceKey.Wrap.Row(),
                    cardinality,
                    new SourceKey.Reader.SourceRowsCall(lifterRef));
                yield new Resolved.Ok(sourceKey, loaderRegistration, List.of(hop), tbReturnType);
            }
            case Derivation.Path path -> {
                SourceKey sourceKey = new SourceKey(
                    leafTable,
                    path.expectedTuple(),
                    path.steps(),
                    new SourceKey.Wrap.Row(),
                    cardinality,
                    new SourceKey.Reader.SourceRowsCall(lifterRef));
                yield new Resolved.Ok(sourceKey, loaderRegistration, path.steps(), tbReturnType);
            }
        };
    }

    /**
     * Builder-internal classification of the parent-side derivation: leaf-PK (no
     * {@code @reference}) vs. resolved FK chain ({@code @reference} present). Captures the
     * derived column tuple plus the diagnostic-message fragments that distinguish the two
     * cases, so step 6's per-position-mismatch loop emits the right user-facing wording
     * without re-asking which case it is in.
     */
    private sealed interface Derivation {

        List<ColumnRef> expectedTuple();
        String derivedShapeLabel();
        String colMismatchTail(ColumnRef col);
        String perPositionMismatchMessage(
            String parentTypeName, String fieldName, String lifterMethodName,
            int position, String actualType, ColumnRef expectedCol, String expectedType);

        record Leaf(List<ColumnRef> expectedTuple, String leafTableName) implements Derivation {
            @Override public String derivedShapeLabel() {
                return "(leaf-PK columns of '" + leafTableName + "')";
            }
            @Override public String colMismatchTail(ColumnRef col) {
                return "matching primary key column '" + col.sqlName() + "' of '" + leafTableName
                    + "' (Java type '" + col.columnClass() + "')";
            }
            @Override public String perPositionMismatchMessage(
                    String parentTypeName, String fieldName, String lifterMethodName,
                    int position, String actualType, ColumnRef expectedCol, String expectedType) {
                return "@sourceRow on '" + parentTypeName + "." + fieldName
                    + "': lifter '" + lifterMethodName + "' RowN type at position " + position
                    + " ('" + actualType + "') does not match primary key column '"
                    + expectedCol.sqlName() + "' of '" + leafTableName
                    + "' (Java type '" + expectedType + "')";
            }
        }

        record Path(List<JoinStep> steps, List<ColumnRef> expectedTuple, String firstFkLabel)
                implements Derivation {
            @Override public String derivedShapeLabel() {
                return "(first-hop source-side columns of FK '" + firstFkLabel + "')";
            }
            @Override public String colMismatchTail(ColumnRef col) {
                return "matching first-hop source-side column '" + col.sqlName() + "' of FK '"
                    + firstFkLabel + "' (Java type '" + col.columnClass() + "')";
            }
            @Override public String perPositionMismatchMessage(
                    String parentTypeName, String fieldName, String lifterMethodName,
                    int position, String actualType, ColumnRef expectedCol, String expectedType) {
                return "@sourceRow on '" + parentTypeName + "." + fieldName
                    + "': lifter '" + lifterMethodName + "' RowN type at position " + position
                    + " ('" + actualType + "') does not match first-hop source-side column '"
                    + expectedCol.sqlName() + "' of FK '" + firstFkLabel
                    + "' (Java type '" + expectedType + "')";
            }
        }
    }

    /**
     * Returns the parent's backing class FQ name, or {@code null} if the parent is not
     * a {@code @record} shape that admits a {@code @sourceRow} (Invariant #1).
     */
    private static String parentBackingClass(GraphitronType.ResultType parentResultType) {
        if (parentResultType instanceof GraphitronType.PojoResultType.Backed b) {
            return b.fqClassName();
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
            return "@sourceRow on '" + parentTypeName + "." + fieldName
                + "' is not supported on jOOQ-backed @record parents; the catalog record's "
                + "columns drive batching — use the existing FK path or @reference";
        }
        return "@sourceRow on '" + parentTypeName + "." + fieldName
            + "' requires the @record parent to declare a backing class via "
            + "@record(record: {className: ...})";
    }
}
