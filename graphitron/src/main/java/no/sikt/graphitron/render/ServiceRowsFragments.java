package no.sikt.graphitron.render;

import no.sikt.graphitron.command.Invocation;
import no.sikt.graphitron.command.LaunchSource;
import no.sikt.graphitron.command.LauncherCommand;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.RowsMethodShape;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.List;

/**
 * The {@code @service} child launchers' composition fragments: the pure-delegation body
 * ({@link #delegateBody}) and the table lift's identity-join re-projection
 * ({@link #liftBody}). Both consume the shell's two argument-assembly fragments (the tenancy
 * {@code dsl} declaration and the composed service-call expression, the two named carve-outs)
 * and read every other fact off the command arms.
 *
 * <p>Both bodies deliberately open with NO empty-keys gate, unlike every SQL-composing batched
 * sibling in {@link BatchedRowsFragments}: a service rows method hands the empty batch to the
 * developer's method (adding the gate would change behaviour and, for the mapped container,
 * the returned type's shape). A copy-paste of the sibling arms' opening gate here is a
 * behaviour change, not a tidy-up.
 */
final class ServiceRowsFragments {

    private ServiceRowsFragments() {}

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName RECORD = ClassName.get("org.jooq", "Record");
    private static final ClassName RESULT = ClassName.get("org.jooq", "Result");
    private static final ClassName FIELD = ClassName.get("org.jooq", "Field");
    private static final ClassName TABLE = ClassName.get("org.jooq", "Table");
    private static final ClassName LIST_CN = ClassName.get("java.util", "List");
    private static final ClassName MAP_CN = ClassName.get("java.util", "Map");
    private static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");
    private static final ClassName LINKED_HASH_MAP = ClassName.get("java.util", "LinkedHashMap");

    /**
     * The rows method's return type for a service row: the delegate arm returns the developer
     * method's own declared type (the classifier's return-shape equality makes it THE outer
     * type), the lift arm the loader-container wrap with the per-key value pinned to
     * {@code Record} (the projected row), through the one wrap formula the classifier's check
     * reads.
     */
    static TypeName valueTypeOf(LauncherCommand row) {
        var batched = (Invocation.Batched) row.invocation();
        return switch (row.source()) {
            case LaunchSource.ServiceCall sc -> sc.method().returnType();
            case LaunchSource.ServiceTableLift ignored -> RowsMethodShape.outerRowsReturnType(
                RECORD,
                batched.loader().valueIsList(),
                batched.sourceKey().keyElementType(),
                batched.loader().container() == LoaderRegistration.Container.MAPPED_SET);
            default -> throw new IllegalArgumentException(
                "not a service row: " + row.source().getClass().getSimpleName());
        };
    }

    /**
     * The delegate arm's whole body: the {@code dsl} declaration exactly when the borrowed
     * call shape needs one (the one arm where the rendered method may bind no
     * {@code DSLContext} at all), then the bare delegation. The call expression is the shell's
     * fragment; see the class note for the deliberate absence of the empty-keys gate.
     */
    static CodeBlock delegateBody(LaunchSource.ServiceCall sc, CodeBlock dslDeclaration,
            CodeBlock serviceCall) {
        var body = CodeBlock.builder();
        if (sc.method().callShape().needsDsl()) {
            body.add(dslDeclaration);
        }
        body.addStatement("return $L", serviceCall);
        return body.build();
    }

    /**
     * The lift arm's whole body: call the developer's method, normalise the returned container
     * to a parent-indexed {@code List<List<XRecord>>}, flatten to {@code (idx, seq, pk...)}
     * VALUES rows, re-project the returned records' PKs by identity through the projection
     * unit's {@code $project} (so multiset sub-fields resolve off the projected row), ordered
     * by {@code seq} so each parent bucket keeps the service's flatten order, and re-wrap into
     * the loader's container shape. The {@code dsl} local is arm-entailed (the lift always
     * SELECTs), so the declaration is added unconditionally.
     */
    static CodeBlock liftBody(LauncherCommand row, LaunchSource.ServiceTableLift lift,
            CodeBlock dslDeclaration, CodeBlock serviceCall) {
        var batched = (Invocation.Batched) row.invocation();
        String fieldName = row.coordinate().getFieldName();

        TableRef table = lift.table();
        SourceKey sourceKey = batched.sourceKey();
        boolean isMapped = batched.loader().container() == LoaderRegistration.Container.MAPPED_SET;
        boolean isList = batched.loader().valueIsList();
        TypeName keyElement = sourceKey.keyElementType();
        TypeName xRecord = CatalogRefs.recordClass(table);
        List<ColumnRef> pks = table.primaryKeyColumns();
        // VALUES row shape: (parentIdx, seq, pk…). idx drives the scatter back to the parent; seq
        // is the global flatten order so each parent's records keep the order the service returned
        // them in (the re-projection JOIN does not otherwise preserve it).
        int arity = pks.size() + 2;

        ClassName suppress = ClassName.get("java.lang", "SuppressWarnings");

        // The developer method's declared type IS the fetched container's type: the
        // classifier's return-shape equality pins it to the loader-container wrap over the
        // returned table's record class.
        TypeName serviceReturn = lift.method().returnType();

        TypeName[] rowTypeArgs = new TypeName[arity];
        rowTypeArgs[0] = ClassName.get(Integer.class);
        rowTypeArgs[1] = ClassName.get(Integer.class);
        for (int i = 0; i < pks.size(); i++) {
            rowTypeArgs[i + 2] = CatalogRefs.columnType(pks.get(i));
        }
        // ValuesJoinRowBuilder's schemes take the slot count and add the idx cell themselves;
        // the lift's extra slot beyond the PKs is the seq cell.
        TypeName rowType = ParameterizedTypeName.get(ValuesJoinRowBuilder.rowClass(pks.size() + 1), rowTypeArgs);
        TypeName recordRowType = ParameterizedTypeName.get(ValuesJoinRowBuilder.recordClass(pks.size() + 1), rowTypeArgs);
        TypeName projInputTableType = ParameterizedTypeName.get(TABLE, recordRowType);

        TypeName listX = ParameterizedTypeName.get(LIST_CN, xRecord);
        TypeName listListX = ParameterizedTypeName.get(LIST_CN, listX);
        TypeName listOfRowType = ParameterizedTypeName.get(LIST_CN, rowType);
        TypeName listOfRecord = ParameterizedTypeName.get(LIST_CN, RECORD);
        TypeName listOfListOfRecord = ParameterizedTypeName.get(LIST_CN, listOfRecord);
        TypeName keyOrderType = ParameterizedTypeName.get(LIST_CN, keyElement);
        TypeName wildField = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        TypeName listOfField = ParameterizedTypeName.get(LIST_CN, wildField);
        ClassName typeClass = className(lift.projection());

        var body = CodeBlock.builder();
        body.add(dslDeclaration);
        // 1. Call the developer's @service method: returns real XRecords in the loader container.
        body.addStatement("$T fetched = $L", serviceReturn, serviceCall);

        // 2. Normalise to a parent-indexed List<List<XRecord>> (and, for the mapped container, the
        //    parent-key order so the result Map can be rebuilt). Single-cardinality returns collapse
        //    to a singleton-or-empty list per parent.
        if (isMapped) {
            body.addStatement("$T keyOrder = new $T<>(fetched.keySet())", keyOrderType, ARRAY_LIST);
            body.addStatement("$T perParent = new $T<>(keyOrder.size())", listListX, ARRAY_LIST);
            if (isList) {
                body.beginControlFlow("for ($T k : keyOrder)", keyElement)
                    .addStatement("perParent.add(fetched.get(k))")
                    .endControlFlow();
            } else {
                body.beginControlFlow("for ($T k : keyOrder)", keyElement)
                    .addStatement("$T rec = fetched.get(k)", xRecord)
                    .addStatement("perParent.add(rec == null ? $T.of() : $T.of(rec))", LIST_CN, LIST_CN)
                    .endControlFlow();
            }
        } else {
            if (isList) {
                body.addStatement("$T perParent = fetched", listListX);
            } else {
                body.addStatement("$T perParent = new $T<>(fetched.size())", listListX, ARRAY_LIST);
                body.beginControlFlow("for ($T rec : fetched)", xRecord)
                    .addStatement("perParent.add(rec == null ? $T.of() : $T.of(rec))", LIST_CN, LIST_CN)
                    .endControlFlow();
            }
        }

        // 3. Flatten to VALUES rows of (parentIdx, seq, returned-record PK…).
        body.addStatement("$T rows = new $T<>()", listOfRowType, ARRAY_LIST);
        body.addStatement("int seq = 0");
        body.beginControlFlow("for (int idx = 0; idx < perParent.size(); idx++)");
        body.beginControlFlow("for ($T rec : perParent.get(idx))", xRecord);
        // Cells delegated to the shared VALUES-cell authority so converter-backed target PKs
        // bind through the column's registered Converter DataType.
        CodeBlock liftOwnerExpr = CodeBlock.of("$T.$L", CatalogRefs.constantsClass(table), table.javaFieldName());
        CodeBlock liftCells = ValuesJoinRowBuilder.cellsCode(
            pks, java.util.function.Function.identity(),
            CodeBlock.of("$T.inline(idx), $T.inline(seq)", DSL, DSL), liftOwnerExpr,
            (pk, i) -> CodeBlock.of("rec.get($L.$L)", liftOwnerExpr, pk.javaName()));
        body.addStatement("rows.add($T.row($L))", DSL, liftCells);
        body.addStatement("seq++");
        body.endControlFlow();
        body.endControlFlow();

        // 4. Per-parent buckets; run the identity-join re-projection only when there are keys
        //    (DSL.values rejects an empty row array). A parent the service returned nothing for
        //    keeps its empty bucket, which is the agreed drop-out semantics.
        body.addStatement("$T byParent = new $T<>(perParent.size())", listOfListOfRecord, ARRAY_LIST);
        body.beginControlFlow("for (int i = 0; i < perParent.size(); i++)")
            .addStatement("byParent.add(new $T<>())", ARRAY_LIST)
            .endControlFlow();
        body.beginControlFlow("if (!rows.isEmpty())");
        // Generic array creation is the one unavoidable unchecked cast (Java forbids new RowN<...>[]);
        // scoped to this one line, matching the batched arms' parent-input VALUES.
        body.add("@$T({$S, $S})\n", suppress, "unchecked", "rawtypes");
        body.addStatement("$T[] rowArray = ($T[]) rows.toArray(new $T[0])",
            rowType, rowType, ValuesJoinRowBuilder.rowClass(pks.size() + 1));
        var valuesAlias = CodeBlock.builder();
        valuesAlias.add("$S, $S, $S", "projectionInput", "idx", "seq");
        for (ColumnRef pk : pks) {
            valuesAlias.add(", $S", pk.sqlName());
        }
        body.addStatement("$T projectionInput = $T.values(rowArray).as($L)",
            projInputTableType, DSL, valuesAlias.build());
        body.addStatement("$T boundTable = $T.$L.as($S)",
            CatalogRefs.tableClass(table), CatalogRefs.constantsClass(table), table.javaFieldName(), fieldName);
        body.addStatement("$T selectFields = new $T<>($L)",
            listOfField, ARRAY_LIST, ProjectionCall.fromEnvSelection(typeClass, "boundTable"));
        body.addStatement("selectFields.add(projectionInput.field(0, $T.class).as($S))",
            Integer.class, no.sikt.graphitron.command.ReservedAliases.IDX);
        var onCond = CodeBlock.builder();
        for (int i = 0; i < pks.size(); i++) {
            if (i > 0) onCond.add(".and(");
            ColumnRef pk = pks.get(i);
            onCond.add("boundTable.$L.eq($L)",
                pk.javaName(), projectionInputFieldLookup(pk, table));
            if (i > 0) onCond.add(")");
        }
        var sel = CodeBlock.builder();
        sel.add("$T<$T> flat = dsl\n", RESULT, RECORD);
        sel.indent();
        sel.add(".select(selectFields)\n");
        sel.add(".from(boundTable)\n");
        sel.add(".join(projectionInput).on($L)\n", onCond.build());
        // seq (VALUES column 1) is the service's flatten order; ordering the flat result by it keeps
        // each parent bucket in the order the service returned, since the scatter appends in fetch order.
        sel.add(".orderBy(projectionInput.field(1, $T.class))\n", Integer.class);
        sel.add(".fetch();\n");
        sel.unindent();
        body.add(sel.build());
        body.beginControlFlow("for ($T row : flat)", RECORD)
            .addStatement("byParent.get(row.get($S, $T.class)).add(row)",
                no.sikt.graphitron.command.ReservedAliases.IDX, Integer.class)
            .endControlFlow();
        body.endControlFlow();

        // 5. Re-wrap into the loader's container shape: one read per axis, four tails.
        if (isList && !isMapped) {
            body.addStatement("return byParent");
        } else if (isList) {
            TypeName mapType = ParameterizedTypeName.get(MAP_CN, keyElement, listOfRecord);
            body.addStatement("$T out = new $T<>()", mapType, LINKED_HASH_MAP);
            body.beginControlFlow("for (int i = 0; i < keyOrder.size(); i++)")
                .addStatement("out.put(keyOrder.get(i), byParent.get(i))")
                .endControlFlow();
            body.addStatement("return out");
        } else if (!isMapped) {
            body.addStatement("$T out = new $T<>(byParent.size())", listOfRecord, ARRAY_LIST);
            body.beginControlFlow("for ($T bucket : byParent)", listOfRecord)
                .addStatement("out.add(bucket.isEmpty() ? null : bucket.get(0))")
                .endControlFlow();
            body.addStatement("return out");
        } else {
            TypeName mapType = ParameterizedTypeName.get(MAP_CN, keyElement, RECORD);
            body.addStatement("$T out = new $T<>()", mapType, LINKED_HASH_MAP);
            body.beginControlFlow("for (int i = 0; i < keyOrder.size(); i++)")
                .addStatement("$T bucket = byParent.get(i)", listOfRecord)
                .addStatement("out.put(keyOrder.get(i), bucket.isEmpty() ? null : bucket.get(0))")
                .endControlFlow();
            body.addStatement("return out");
        }

        return body.build();
    }

    /**
     * The typed {@code projectionInput.field(...)} lookup for one identity-join slot, by
     * sqlName plus the owner column's {@code DataType} so converter-backed PKs keep faithful
     * type metadata, symmetric with the VALUES cells.
     */
    private static CodeBlock projectionInputFieldLookup(ColumnRef pk, TableRef table) {
        return CodeBlock.of("projectionInput.field($S, $T.$L.$L.getDataType())",
            pk.sqlName(), CatalogRefs.constantsClass(table), table.javaFieldName(), pk.javaName());
    }

    private static ClassName className(no.sikt.graphitron.command.UnitRef ref) {
        return ClassName.get(ref.packageName(), ref.simpleName());
    }
}
