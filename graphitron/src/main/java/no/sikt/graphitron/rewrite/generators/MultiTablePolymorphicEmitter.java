package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.AnnotationSpec;
import no.sikt.graphitron.javapoet.ArrayTypeName;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.generators.util.PolymorphicSelectionSetClassGenerator;
import no.sikt.graphitron.rewrite.generators.util.ValuesJoinRowBuilder;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.ServiceMethodCall;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.*;

/**
 * Emits the two-stage fetcher methods for {@link QueryField.QueryInterfaceField} and
 * {@link QueryField.QueryUnionField} (multi-table polymorphism).
 *
 * <p>Stage 1 is one SQL statement: a narrow UNION ALL across participant tables projecting
 * {@code (__typename, __pk0__, ..., __sortN__)} per branch. The database does ORDER BY in one
 * shot. Stage 2 dispatches per {@code __typename} using {@link ValuesJoinRowBuilder} (the same
 * shape the federation {@code _entities} dispatcher uses): one
 * {@code SELECT <Type>.$fields(...) FROM t JOIN VALUES(...) ON t.PK = input.PK ORDER BY idx} per
 * non-empty group. Result records carry the synthetic {@code __typename} column projected as a
 * literal so the schema-class TypeResolver routes each row to the correct concrete GraphQL type.
 *
 * <p><b>Join syntax: {@code .on(...)}, not {@code .using(...)}.</b> Stage 2's projection includes
 * {@code <TypeName>.$fields(env.getSelectionSet(), t, env)}, which references {@code t.<col>}
 * directly; USING would collapse the joined PK columns and risk colliding with $fields-emitted
 * projections. See {@link no.sikt.graphitron.rewrite.generators.util.SelectMethodBody}'s
 * Javadoc for the same rationale on the federation dispatch path.
 *
 * <p>v1 scope: PK-bearing participants with uniform PK arity per interface or union. Validation
 * (in {@code GraphitronSchemaValidator.validateInterfaceType} / {@code validateUnionType})
 * rejects participants without a PK and arity mismatches before code generation runs;
 * collision-resolution and mixed-arity NULL-padding are tracked as follow-ups.
 */
public final class MultiTablePolymorphicEmitter {

    // Synthetic SQL column aliases for the stage-1 projection. The double-underscore wrapping
    // (__name__) is a deliberate collision-avoidance device, NOT the lazy dunder convention this
    // class otherwise avoids for Java locals: these names live in the result-set column namespace
    // alongside real table columns, which the consumer's DB schema controls. Wrapping in __ keeps
    // a synthetic alias from colliding with a real column the consumer happens to name `sort`,
    // `idx`, `typename`, etc. They reach generated code as string literals (.as("__sort__"),
    // r.get("__typename")), never as Java identifiers, so the no-regression meta-test (which scans
    // for dunder-prefixed identifiers) correctly leaves them alone.

    /** Synthetic stage-1 projection column carrying the participant typename literal. */
    public static final String TYPENAME_COLUMN = "__typename";
    /**
     * Synthetic projection alias carrying the discriminator value for a single-table discriminated
     * interface ({@code @table @discriminate}). The discriminated {@code TypeResolver} routes off this
     * alias rather than the raw discriminator column name: when the interface also exposes the
     * discriminator as a queryable field, the real column is projected by the participant {@code $fields}
     * too, and a bare read of the unaliased name matches both projections ambiguously (jOOQ logs
     * {@code Ambiguous match found} and resolves to the first by luck). Same {@code __}-wrapping
     * collision-avoidance rationale as {@link #TYPENAME_COLUMN}; reaches generated code only as a string
     * literal (a {@code .as("__discriminator__")} projection and a {@code record.get(DSL.name(...))} read).
     */
    public static final String DISCRIMINATOR_COLUMN = "__discriminator__";
    /** Stage-1 sort key column alias. Single PK projects the column directly; composite PKs use {@code DSL.jsonbArray(...)}. */
    public static final String SORT_COLUMN = "__sort__";
    /** Stage-1 parent-index column alias; drives the Java-side scatter back to the originating parent row. */
    public static final String IDX_COLUMN = "__idx__";
    /** Stage-1 windowed row-number alias; the outer SELECT filters {@code RN_COLUMN <= page.limit()} for the per-partition limit. */
    public static final String RN_COLUMN = "__rn__";
    /** Stage-1 PK projection alias prefix; per-slot index appended ({@code __pk0__}, {@code __pk1__}, …). */
    public static final String PK_COLUMN_PREFIX = "__pk";
    /** Stage-1 PK projection alias suffix. */
    public static final String PK_COLUMN_SUFFIX = "__";

    private static final ClassName ARRAY_LIST          = ClassName.get("java.util", "ArrayList");
    private static final ClassName LINKED_HASH_MAP     = ClassName.get("java.util", "LinkedHashMap");
    private static final ClassName COLLECTIONS         = ClassName.get("java.util", "Collections");
    private static final ClassName MAP                 = ClassName.get("java.util", "Map");
    private static final ClassName FIELD               = ClassName.get("org.jooq", "Field");
    private static final ClassName SORT_FIELD          = ClassName.get("org.jooq", "SortField");
    private static final ClassName TABLE               = ClassName.get("org.jooq", "Table");
    private static final ClassName DSL_CONTEXT         = ClassName.get("org.jooq", "DSLContext");
    private static final ClassName ROW1                = ClassName.get("org.jooq", "Row1");
    private static final ClassName ROW2                = ClassName.get("org.jooq", "Row2");
    private static final ClassName RECORD2             = ClassName.get("org.jooq", "Record2");
    /** Returns {@code org.jooq.RowN} for the given arity (1..22). jOOQ's typed Row tops out at Row22. */
    private static ClassName rowClass(int arity) { return ClassName.get("org.jooq", "Row" + arity); }
    /** Returns {@code org.jooq.RecordN} for the given arity (1..22). jOOQ's typed Record tops out at Record22. */
    private static ClassName recordClass(int arity) { return ClassName.get("org.jooq", "Record" + arity); }
    private static final ClassName DATA_LOADER         = ClassName.get("org.dataloader", "DataLoader");
    private static final ClassName DATA_LOADER_FACTORY = ClassName.get("org.dataloader", "DataLoaderFactory");
    private static final ClassName BATCH_LOADER_ENV    = ClassName.get("org.dataloader", "BatchLoaderEnvironment");
    private static final ClassName COMPLETABLE_FUTURE  = ClassName.get("java.util.concurrent", "CompletableFuture");
    private static final ClassName DATA_FETCHER_RESULT = ClassName.get("graphql.execution", "DataFetcherResult");
    private static final ClassName JSONB                = ClassName.get("org.jooq", "JSONB");
    /** Stage-1 derived-table alias used by the connection-mode UNION-ALL wrapper. */
    private static final String CONNECTION_PAGES_ALIAS = "pages";
    /** Local variable name carrying the UNION-ALL derived table — referenced by both the page query and {@code ConnectionResult.table()} for {@code totalCount}. */
    private static final String CONNECTION_PAGES_LOCAL = "pagesTable";
    /** Directive context surfaced in {@link ValuesJoinRowBuilder}'s arity-cap error messages. */
    private static final String DIRECTIVE_CONTEXT = "@interface participant PK";

    private MultiTablePolymorphicEmitter() {}

    /**
     * Root-fetcher overload: emits the public main fetcher plus one private
     * {@code select<Participant>For<Field>} helper per table-bound participant. Stage 1's
     * UNION ALL has no per-branch WHERE; the SELECT spans the full participant tables.
     *
     * <p>{@code registry} is the enclosing {@code <Type>Fetchers} class's
     * {@link CompositeDecodeHelperRegistry} (R384 phase 0): {@code @nodeId}-decoded filter args on
     * the branch path lift their decode helpers onto the same class that hosts the fetcher call
     * site, mirroring the split-rows / lookup-rows precedent in {@code TypeFetcherGenerator}.
     */
    public static List<MethodSpec> emitMethods(
            TypeFetcherEmissionContext ctx,
            String fieldName,
            List<ParticipantRef> participants,
            Map<String, List<WhereFilter>> participantFilters,
            boolean isList,
            String outputPackage,
            CompositeDecodeHelperRegistry registry) {
        var tableBoundParticipants = participants.stream()
            .filter(p -> p instanceof ParticipantRef.TableBound)
            .map(p -> (ParticipantRef.TableBound) p)
            .toList();
        var methods = new ArrayList<MethodSpec>();
        methods.add(buildMainFetcher(ctx, fieldName, tableBoundParticipants, participantFilters, isList, outputPackage, registry));
        for (var participant : tableBoundParticipants) {
            methods.add(buildPerTypenameSelect(fieldName, participant, false, outputPackage));
        }
        return methods;
    }

    /**
     * Route (a) entry point (R365): a root {@code @service} field returning a multitable
     * interface/union. Unlike {@link #emitMethods}, there is no stage-1 discovery UNION ALL — the
     * service hands back the concrete PK-populated {@code TableRecord}s directly, so the Java type
     * of each returned record <em>is</em> the discriminator. The main fetcher calls the service,
     * dispatches each returned record on its runtime class against the participant set to build the
     * same {@code (idx, pks)} buckets stage 2 consumes, then reuses {@link #buildPerTypenameSelect}
     * verbatim to auto-fetch the selected columns by PK and tag each row with the {@code __typename}
     * literal the schema-class TypeResolver routes on.
     */
    public static List<MethodSpec> emitServiceMethods(
            TypeFetcherEmissionContext ctx,
            String fieldName,
            ServiceMethodCall serviceCall,
            List<ParticipantRef> participants,
            boolean isList,
            String outputPackage) {
        var tableBoundParticipants = participants.stream()
            .filter(p -> p instanceof ParticipantRef.TableBound)
            .map(p -> (ParticipantRef.TableBound) p)
            .toList();
        var methods = new ArrayList<MethodSpec>();
        methods.add(buildServiceMainFetcher(ctx, fieldName, serviceCall, tableBoundParticipants, isList, outputPackage));
        for (var participant : tableBoundParticipants) {
            methods.add(buildPerTypenameSelect(fieldName, participant, false, outputPackage));
        }
        return methods;
    }

    /**
     * Route (a) main fetcher (R365). Emits the service call, normalises its return into a flat
     * {@code List<Record>} in input order, dispatches each record on its runtime class against the
     * participant set into {@code (idx, pks)} buckets, then dispatches per typename to the shared
     * stage-2 {@link #buildPerTypenameSelect} helper. The record-class dispatch is the discriminator
     * (no synthesized {@code __typename} column or PK round-trip needed to identify the type, unlike
     * the query path whose UNION-ALL projection erases the Java type); the participant's table is
     * used only for the by-PK auto-fetch in stage 2.
     *
     * <p><b>Drop contract.</b> Two kinds of returned record produce no output node and are dropped
     * silently: (a) a record whose runtime class matches no participant falls through the dispatch
     * chain; (b) a matched record whose primary key matches no live row makes stage 2's
     * {@code JOIN input ON t.PK = input.PK} yield nothing, so {@code result[idx]} stays null. Both
     * are service-contract violations (the service is expected to return live, participant-typed,
     * PK-populated records). For a list return the surviving payload is simply shorter; for a single
     * non-null return the null payload surfaces as a graphql-java non-null violation.
     */
    private static MethodSpec buildServiceMainFetcher(
            TypeFetcherEmissionContext ctx,
            String fieldName, ServiceMethodCall serviceCall,
            List<ParticipantRef.TableBound> participants, boolean isList, String outputPackage) {

        var listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        TypeName valueType = isList ? listOfRecord : RECORD;

        var builder = MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");

        builder.beginControlFlow("try");

        // Service call: declares `<reflectedReturnType> result = ServiceClass.method(args);` and a
        // `dsl` local iff the method binds a DSLContext / is instance-shaped. Stage 2's by-PK
        // auto-fetch needs a `dsl` local, so declare one here when the service call did not.
        ServiceMethodCallEmitter.emit(serviceCall, outputPackage).forEach(builder::addStatement);
        if (!ServiceMethodCallEmitter.declaresDslLocal(serviceCall)) {
            builder.addStatement("$T dsl = $L.getDslContext(env)", DSL_CONTEXT, ctx.graphitronContextCall());
        }

        // Normalise the service return into a flat List<Record> in input order. Records that match
        // no participant fall through the dispatch chain below and are skipped.
        builder.addCode(buildServiceNormaliseToRecords(isList));

        if (participants.isEmpty()) {
            if (isList) {
                builder.addStatement("$T payload = $T.of()", listOfRecord, LIST);
            } else {
                builder.addStatement("$T payload = ($T) null", RECORD, RECORD);
            }
            builder.addCode(returnSyncSuccess(valueType, "payload"));
            builder.nextControlFlow("catch ($T e)", Exception.class);
            builder.addCode(noChannelCatchArm(outputPackage));
            builder.endControlFlow();
            return builder.build();
        }

        builder.addStatement("Object[] dispatched = new Object[records.size()]");
        builder.addCode(buildServiceDispatchBlock(participants, fieldName));

        if (isList) {
            builder.addStatement("$T payload = new $T<>(records.size())", listOfRecord, ARRAY_LIST);
            builder.beginControlFlow("for (Object o : dispatched)");
            builder.addStatement("if (o instanceof $T r) payload.add(r)", RECORD);
            builder.endControlFlow();
        } else {
            builder.addStatement("$T payload = dispatched.length == 0 ? null : ($T) dispatched[0]", RECORD, RECORD);
        }
        builder.addCode(returnSyncSuccess(valueType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(noChannelCatchArm(outputPackage));
        builder.endControlFlow();
        return builder.build();
    }

    /**
     * Builds the route (a) record-class dispatch (R365): groups the service-returned
     * {@code records} into {@code byType} {@code (idx, pks)} buckets by matching each record's
     * runtime class against each participant's {@code recordClass}, then dispatches per typename to
     * the shared stage-2 {@link #buildPerTypenameSelect} helper (scattering into {@code dispatched}).
     * The {@code (idx, pks)} binding shape is byte-identical to {@link #buildPerTypenameDispatcher}'s
     * so the per-typename helpers are reused unchanged. The validator rejects every same-table
     * participant collision up front as an author error (same-table polymorphism must be a
     * single-table discriminated interface, or the types split), so every participant reaching here
     * has a distinct record class and the if/else-if chain assigns each record to exactly one arm.
     */
    private static CodeBlock buildServiceDispatchBlock(
            List<ParticipantRef.TableBound> participants, String fieldName) {
        var b = CodeBlock.builder();
        var listOfObjArray = ParameterizedTypeName.get(LIST, ArrayTypeName.of(ClassName.get(Object.class)));
        var byTypeMap = ParameterizedTypeName.get(MAP, ClassName.get(String.class), listOfObjArray);
        b.addStatement("$T byType = new $T<>()", byTypeMap, LINKED_HASH_MAP);
        b.beginControlFlow("for (int i = 0; i < records.size(); i++)");
        b.addStatement("$T rec = records.get(i)", RECORD);
        for (int p = 0; p < participants.size(); p++) {
            var participant = participants.get(p);
            var recordCls = participant.table().recordClass();
            var pks = CodeBlock.builder().add("new Object[]{");
            var pkCols = participant.table().primaryKeyColumns();
            for (int s = 0; s < pkCols.size(); s++) {
                if (s > 0) pks.add(", ");
                pks.add("rec.get($T.$L.$L)", participant.table().constantsClass(),
                    participant.table().javaFieldName(), pkCols.get(s).javaName());
            }
            pks.add("}");
            if (p == 0) {
                b.beginControlFlow("if (rec instanceof $T)", recordCls);
            } else {
                b.nextControlFlow("else if (rec instanceof $T)", recordCls);
            }
            b.addStatement("byType.computeIfAbsent($S, k -> new $T<>()).add(new Object[]{i, $L})",
                participant.typeName(), ARRAY_LIST, pks.build());
        }
        b.endControlFlow();
        b.endControlFlow();
        for (var participant : participants) {
            String typeName = participant.typeName();
            b.beginControlFlow("if (byType.containsKey($S))", typeName);
            b.addStatement("$L(byType.get($S), env, dsl, dispatched)",
                perTypenameMethodName(fieldName, typeName), typeName);
            b.endControlFlow();
        }
        return b.build();
    }

    /**
     * Extracted service-call normalise snippet (R405): declares {@code List<Record> records} holding
     * the service return flattened into input order. Shared by {@link #buildServiceMainFetcher}
     * (route (a)) and {@link #buildServiceTableInterfaceFetcher} (R405 single-table interface) so the
     * "call the service, flatten to {@code List<Record>} in input order" logic lives in one place.
     * The single-value arm routes through an {@code Object} local so the downcast to {@code Record} is
     * never flagged redundant under {@code -Werror}, whatever the method's declared single return type.
     */
    private static CodeBlock buildServiceNormaliseToRecords(boolean isList) {
        var listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        var b = CodeBlock.builder();
        b.addStatement("$T records = new $T<>()", listOfRecord, ARRAY_LIST);
        if (isList) {
            b.beginControlFlow("if (result != null)");
            b.beginControlFlow("for (Object o : result)");
            b.addStatement("if (o != null) records.add(($T) o)", RECORD);
            b.endControlFlow();
            b.endControlFlow();
        } else {
            b.addStatement("Object single = result");
            b.beginControlFlow("if (single != null)");
            b.addStatement("records.add(($T) single)", RECORD);
            b.endControlFlow();
        }
        return b.build();
    }

    /**
     * R405 entry point: a root {@code @service} field (query or mutation) returning a single-table
     * discriminated interface ({@code @table @discriminate}). Unlike route (a)
     * ({@link #emitServiceMethods}), there is no runtime-class dispatch and no per-typename UNION: every
     * service-returned record is the same shared-table record, so class dispatch cannot tell the
     * subtypes apart. Instead the single emitted fetcher collects the shared table's PKs off the
     * service records and runs one by-PK SELECT reusing the read-side single-table discrimination
     * projection ({@code __discriminator__} + participant fields + discriminator-gated cross-table LEFT
     * JOINs, via {@link TypeFetcherGenerator#buildTableInterfaceReprojection}); the per-
     * {@code TableInterfaceType} {@code TypeResolver} routes each row off the live discriminator value.
     * One method only — the read-side projection and the {@code TypeResolver} are reused verbatim.
     */
    public static List<MethodSpec> emitServiceTableInterfaceMethods(
            TypeFetcherEmissionContext ctx, String fieldName, ServiceMethodCall serviceCall,
            ReturnTypeRef.TableBoundReturnType returnType, String discriminatorColumn,
            List<String> knownDiscriminatorValues, List<ParticipantRef> participants,
            boolean isList, String outputPackage) {
        return List.of(buildServiceTableInterfaceFetcher(ctx, fieldName, serviceCall, returnType,
            discriminatorColumn, knownDiscriminatorValues, participants, isList, outputPackage));
    }

    /**
     * R405 main (and only) fetcher for the single-table service-interface return. Emits: the service
     * call + a {@code dsl} local (when the call did not declare one), the normalise-to-{@code records}
     * snippet, the shared-table local, a by-PK {@code WHERE row(pk…) IN (…)} condition, the reused
     * read-side re-projection (including the PK columns so the fetched Record carries them), a single
     * {@code fetch()}, then a by-PK re-map of the fetched rows to input positions.
     *
     * <p><b>Drop / null contract</b> (aligned with route (a)'s {@link #buildServiceMainFetcher}): a
     * service-returned PK that matches no live row, or whose discriminator is not a known participant
     * value (filtered by the reused discriminator {@code IN} restriction), drops from a list return
     * (the surviving payload is simply shorter) and yields {@code null} for a single return (surfacing
     * as a graphql-java non-null violation if the field is non-null). Re-mapping by input position
     * preserves order among the survivors.
     */
    private static MethodSpec buildServiceTableInterfaceFetcher(
            TypeFetcherEmissionContext ctx, String fieldName, ServiceMethodCall serviceCall,
            ReturnTypeRef.TableBoundReturnType returnType, String discriminatorColumn,
            List<String> knownDiscriminatorValues, List<ParticipantRef> participants,
            boolean isList, String outputPackage) {

        var tableRef = returnType.table();
        var names = GeneratorUtils.ResolvedTableNames.of(tableRef, returnType.returnTypeName(), outputPackage);
        var listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        var resultOfRecord = ParameterizedTypeName.get(RESULT, RECORD);
        TypeName valueType = isList ? listOfRecord : RECORD;

        var builder = MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");

        builder.beginControlFlow("try");
        // Service call: declares `result` and a `dsl` local iff the method binds a DSLContext /
        // is instance-shaped. The by-PK re-fetch needs `dsl`, so declare one here when it did not.
        ServiceMethodCallEmitter.emit(serviceCall, outputPackage).forEach(builder::addStatement);
        if (!ServiceMethodCallEmitter.declaresDslLocal(serviceCall)) {
            builder.addStatement("$T dsl = $L.getDslContext(env)", DSL_CONTEXT, ctx.graphitronContextCall());
        }
        builder.addCode(buildServiceNormaliseToRecords(isList));

        // Shared @table instance for the by-PK re-projection.
        builder.addCode(GeneratorUtils.declareTableLocal(names, tableRef));
        String tableLocal = names.tableLocalName();

        // WHERE row(pk…) IN (:servicePks) — row-value IN so single-column and composite PKs are
        // covered uniformly. `condition` is the base; the reused projection ANDs the discriminator IN.
        builder.addCode(buildPkInCondition(tableRef, tableLocal));

        // Reuse the read-side discriminator-filter + projection + join assembly, additionally
        // projecting the shared table's PK columns so the fetched Record carries them for the re-map.
        builder.addCode(TypeFetcherGenerator.buildTableInterfaceReprojection(ctx, participants,
            discriminatorColumn, knownDiscriminatorValues, tableRef.primaryKeyColumns(), tableLocal, outputPackage));

        builder.addStatement("$T fetched = step.where(condition).fetch()", resultOfRecord);
        builder.addCode(buildServiceTableInterfaceRemap(tableRef, tableLocal, isList, valueType));

        builder.addCode(returnSyncSuccess(valueType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(noChannelCatchArm(outputPackage));
        builder.endControlFlow();
        return builder.build();
    }

    /**
     * R405: emits the by-PK row-value IN condition off the normalised {@code records} list. Builds a
     * {@code List<RowN>} of the shared table's PK values per service record (input order) and
     * {@code DSL.row(pkCols).in(pkRows)}. Row-value IN keeps single-column and composite PKs uniform.
     */
    private static CodeBlock buildPkInCondition(TableRef tableRef, String tableLocal) {
        var pkCols = tableRef.primaryKeyColumns();
        var rowN = ClassName.get("org.jooq", "RowN");
        var listOfRowN = ParameterizedTypeName.get(LIST, rowN);
        var b = CodeBlock.builder();
        b.addStatement("$T pkRows = new $T<>()", listOfRowN, ARRAY_LIST);
        b.beginControlFlow("for ($T rec : records)", RECORD);
        var cells = CodeBlock.builder();
        for (int i = 0; i < pkCols.size(); i++) {
            if (i > 0) cells.add(", ");
            cells.add("rec.get($L.$L)", tableLocal, pkCols.get(i).javaName());
        }
        b.addStatement("pkRows.add($T.row(new Object[]{$L}))", DSL, cells.build());
        b.endControlFlow();
        var lhs = CodeBlock.builder();
        for (int i = 0; i < pkCols.size(); i++) {
            if (i > 0) lhs.add(", ");
            lhs.add("$L.$L", tableLocal, pkCols.get(i).javaName());
        }
        b.addStatement("$T condition = $T.row(new $T<?>[]{$L}).in(pkRows)", CONDITION, DSL, FIELD, lhs.build());
        return b.build();
    }

    /**
     * R405: re-maps the fetched rows back to input positions by PK. Builds a {@code Map<List<Object>,
     * Record>} keyed on the shared table's PK values, then walks the service {@code records} in order,
     * placing each matched fetched row into the payload at its input position (dropping unmatched PKs
     * per the drop contract). The by-PK re-map is an internal round-trip detail and does not put null
     * holes into a list return.
     */
    private static CodeBlock buildServiceTableInterfaceRemap(
            TableRef tableRef, String tableLocal, boolean isList, TypeName valueType) {
        var pkCols = tableRef.primaryKeyColumns();
        var arrays = ClassName.get("java.util", "Arrays");
        var listOfObject = ParameterizedTypeName.get(LIST, ClassName.get(Object.class));
        var mapType = ParameterizedTypeName.get(MAP, listOfObject, RECORD);
        java.util.function.Function<String, CodeBlock> keyOf = recVar -> {
            var k = CodeBlock.builder().add("$T.<$T>asList(", arrays, Object.class);
            for (int i = 0; i < pkCols.size(); i++) {
                if (i > 0) k.add(", ");
                k.add("$L.get($L.$L)", recVar, tableLocal, pkCols.get(i).javaName());
            }
            k.add(")");
            return k.build();
        };
        var b = CodeBlock.builder();
        b.addStatement("$T byPk = new $T<>()", mapType, LINKED_HASH_MAP);
        b.beginControlFlow("for ($T r : fetched)", RECORD);
        b.addStatement("byPk.put($L, r)", keyOf.apply("r"));
        b.endControlFlow();
        if (isList) {
            b.addStatement("$T payload = new $T<>()", valueType, ARRAY_LIST);
            b.beginControlFlow("for ($T rec : records)", RECORD);
            b.addStatement("$T match = byPk.get($L)", RECORD, keyOf.apply("rec"));
            b.addStatement("if (match != null) payload.add(match)");
            b.endControlFlow();
        } else {
            b.addStatement("$T payload = records.isEmpty() ? null : byPk.get($L)",
                RECORD, keyOf.apply("records.get(0)"));
        }
        return b.build();
    }

    /**
     * Child-fetcher overload. Routes on cardinality:
     *
     * <ul>
     *   <li><b>List</b>: registers a {@link org.dataloader.DataLoader} keyed on the parent's
     *       {@link no.sikt.graphitron.rewrite.model.SourceKey} and emits a paired
     *       {@code rows<Field>(List<RowN<…>>, env)} batch loader that runs ONE polymorphic
     *       UNION ALL with {@code JOIN parentInput} per branch, scattering typed Records into
     *       per-parent {@code List<Record>} buckets. Same SQL shape as the connection arm minus
     *       the windowed-CTE pagination.</li>
     *   <li><b>Single</b>: per-parent inline fetcher with structural ranking by
     *       {@code parentRecord.<parent_pk>}-correlated WHERE on each branch. No batching is
     *       available at this cardinality (one record per parent invocation; nothing to dedup).</li>
     * </ul>
     *
     * @param participantJoinPaths typename-keyed FK chain from the parent table to each
     *                              {@link ParticipantRef.TableBound} participant. Non-empty.
     *                              v1 supports only single-hop FK chains.
     * @param parentSourceKey       parent-object source-side key, projected from the field's
     *                              parent classification. Through R102 the classifier produces
     *                              only catalog-FK / {@code ColumnRead}-reader parent keys
     *                              (table-backed parents); R105 wires the class-backed-parent
     *                              classifier arm to reach the lifter and accessor reader permits.
     * @param parentResultType      the parent's classified {@link GraphitronType.ResultType};
     *                              threaded into {@link GeneratorUtils#buildRecordParentKeyExtraction}
     *                              so {@code env.getSource()} is cast and read against the right
     *                              Java type.
     */
    public static List<MethodSpec> emitMethods(
            TypeFetcherEmissionContext ctx,
            String fieldName,
            List<ParticipantRef> participants,
            Map<String, List<JoinStep>> participantJoinPaths,
            SourceKey parentSourceKey,
            GraphitronType.ResultType parentResultType,
            boolean isList,
            String outputPackage) {
        var tableBoundParticipants = participants.stream()
            .filter(p -> p instanceof ParticipantRef.TableBound)
            .map(p -> (ParticipantRef.TableBound) p)
            .toList();
        var methods = new ArrayList<MethodSpec>();
        if (isList && !tableBoundParticipants.isEmpty()) {
            methods.add(buildBatchedListFetcher(ctx, fieldName, parentSourceKey, parentResultType, outputPackage));
            methods.add(buildBatchedListRowsMethod(ctx, fieldName, tableBoundParticipants,
                participantJoinPaths, parentSourceKey, outputPackage));
        } else {
            methods.add(buildScalarPerParentFetcher(ctx, fieldName, tableBoundParticipants,
                participantJoinPaths, parentSourceKey, isList, outputPackage));
        }
        for (var participant : tableBoundParticipants) {
            methods.add(buildPerTypenameSelect(fieldName, participant, false,
                outputPackage));
        }
        return methods;
    }

    /**
     * Connection-fetcher entry point. Emits the public main fetcher
     * plus one private {@code select<Participant>For<Field>} helper per table-bound participant.
     * Two forms switch on {@code parentKey}:
     *
     * <ul>
     *   <li><b>{@code parentKey == null}</b> (root) — the main fetcher returns a
     *       {@code DataFetcherResult<ConnectionResult>}; stage 1 wraps the UNION-ALL of
     *       per-branch projections in a derived table {@code pages} so cursor decode + seek +
     *       LIMIT N+1 apply uniformly across the union; stage 2 reuses the VALUES-JOIN dispatch
     *       and projects {@code __sort__} on each typed Record so
     *       {@code ConnectionHelper.encodeCursor} can read the sort key. {@code totalCount}
     *       runs a polymorphic {@code SELECT count(*) FROM (UNION ALL) AS pages} via the same
     *       UNION-ALL derived table the page query uses (B4b), lazy on selection.</li>
     *   <li><b>{@code parentKey != null}</b> (DataLoader-batched windowed CTE; B4c-2) —
     *       emits a {@code DataLoader}-registering main fetcher plus a
     *       {@code rows<Field>(List<RowN<...>>, env)} rows method. The rows method builds a typed
     *       {@code parentInput} {@code VALUES (idx, parent_pk...)} table, runs ONE polymorphic
     *       UNION-ALL with {@code JOIN parentInput} per branch, wraps it in
     *       {@code WITH ranked AS (... ROW_NUMBER() OVER (PARTITION BY __idx__ ORDER BY
     *       effectiveOrderBy))}, and filters {@code __rn__ <= page.limit()}. Stage 2 dispatches
     *       per typename (reusing {@link #buildPerTypenameSelect}) and writes typed Records to
     *       {@code result[outerIdx]}; an {@code int[] parentIdxByOuter} populated from each
     *       stage-1 row's {@code __idx__} buckets typed Records into per-parent
     *       {@code List<Record>}. Each per-parent {@code ConnectionResult} reuses the shared
     *       {@code pagesTable} with a per-parent condition {@code __idx__.eq(i)} so
     *       {@code totalCount} resolves to that parent's UNION-ALL row count.</li>
     * </ul>
     *
     * <p>v1 scope: forward/backward pagination ({@code first}/{@code last}/{@code after}/
     * {@code before}); single-PK participants only (validator rejects composite-PK participants
     * because the JSONB cursor round-trip is not yet covered); single-hop FK paths for child
     * connections; parent PK arity 1..21 for the batched form (parent PK + idx fits in
     * {@code Row22}; validator rejects above).
     *
     * @param participantJoinPaths typename-keyed FK chain from the parent table to each
     *                              {@link ParticipantRef.TableBound} participant. Must be
     *                              {@code Map.of()} when {@code parentKey} is null;
     *                              non-empty otherwise.
     * @param parentSourceKey       parent-object source-side key; non-null for child
     *                              connections, null for root queries.
     * @param parentResultType      the parent's classified {@link GraphitronType.ResultType};
     *                              non-null when {@code parentSourceKey} is non-null. Threaded
     *                              into {@link GeneratorUtils#buildRecordParentKeyExtraction} so
     *                              {@code env.getSource()} is cast and read against the right
     *                              Java type.
     */
    public static List<MethodSpec> emitConnectionMethods(
            TypeFetcherEmissionContext ctx,
            String fieldName,
            List<ParticipantRef> participants,
            Map<String, List<WhereFilter>> participantFilters,
            Map<String, List<JoinStep>> participantJoinPaths,
            int defaultPageSize,
            SourceKey parentSourceKey,
            GraphitronType.ResultType parentResultType,
            String outputPackage,
            CompositeDecodeHelperRegistry registry) {
        var tableBoundParticipants = participants.stream()
            .filter(p -> p instanceof ParticipantRef.TableBound)
            .map(p -> (ParticipantRef.TableBound) p)
            .toList();
        var methods = new ArrayList<MethodSpec>();
        // The empty-tableBound defensive path falls into the root branch; both fetcher builders
        // emit a non-throwing empty payload when participants is empty.
        if (parentSourceKey != null && !tableBoundParticipants.isEmpty()) {
            methods.add(buildBatchedConnectionFetcher(ctx, fieldName, parentSourceKey, parentResultType,
                outputPackage));
            methods.add(buildBatchedConnectionRowsMethod(ctx, fieldName, tableBoundParticipants,
                participantJoinPaths, defaultPageSize, parentSourceKey, outputPackage));
        } else {
            methods.add(buildRootConnectionFetcher(ctx, fieldName, tableBoundParticipants,
                participantFilters, defaultPageSize, outputPackage, registry));
        }
        for (var participant : tableBoundParticipants) {
            methods.add(buildPerTypenameSelect(fieldName, participant, true,
                outputPackage));
        }
        return methods;
    }

    /**
     * Root-side fetcher (no parent-FK WHERE, no batching). Runs stage 1 (narrow UNION ALL of
     * per-branch {@code (typename, pk0..pkN, sort)} projections), groups results by
     * {@code __typename} into binding tuples, dispatches per typename to the per-branch stage-2
     * helper, and merges the typed Records back in stage-1 order via the {@code Object[] result}
     * scatter pattern shared with the federation dispatcher.
     *
     * <p>Used for {@link QueryField.QueryInterfaceField} / {@link QueryField.QueryUnionField}'s
     * non-connection arm; child cases fork off in {@link #emitMethods}'s child overload.
     */
    private static MethodSpec buildMainFetcher(
            TypeFetcherEmissionContext ctx,
            String fieldName, List<ParticipantRef.TableBound> participants,
            Map<String, List<WhereFilter>> participantFilters,
            boolean isList, String outputPackage,
            CompositeDecodeHelperRegistry registry) {

        var listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        TypeName valueType = isList ? listOfRecord : RECORD;

        var builder = MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");

        builder.beginControlFlow("try");
        builder.addStatement("$T dsl = $L.getDslContext(env)", DSL_CONTEXT, ctx.graphitronContextCall());

        if (participants.isEmpty()) {
            if (isList) {
                builder.addStatement("$T payload = $T.of()", listOfRecord, LIST);
            } else {
                builder.addStatement("$T payload = ($T) null", RECORD, RECORD);
            }
            builder.addCode(returnSyncSuccess(valueType, "payload"));
            builder.nextControlFlow("catch ($T e)", Exception.class);
            builder.addCode(noChannelCatchArm(outputPackage));
            builder.endControlFlow();
            return builder.build();
        }

        stampUncheckedSuppressionIfNeeded(builder, participantFilters);
        builder.addCode(buildStage1Block(ctx, participants, Map.of(), participantFilters, registry));

        int pkArity = participants.get(0).table().primaryKeyColumns().size();
        builder.addStatement("Object[] result = new Object[stage1.size()]");
        builder.addCode(buildPerTypenameDispatcher(participants, fieldName, pkArity));

        if (isList) {
            builder.addStatement("$T payload = new $T<>(stage1.size())", listOfRecord, ARRAY_LIST);
            builder.beginControlFlow("for (Object o : result)");
            builder.addStatement("if (o instanceof $T r) payload.add(r)", RECORD);
            builder.endControlFlow();
        } else {
            builder.addStatement("$T payload = result.length == 0 ? null : ($T) result[0]", RECORD, RECORD);
        }
        builder.addCode(returnSyncSuccess(valueType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(noChannelCatchArm(outputPackage));
        builder.endControlFlow();
        return builder.build();
    }

    /**
     * Child non-list per-parent fetcher. Single-cardinality multi-table polymorphic child fields
     * have nothing to batch (one record per parent invocation; nothing to dedup), so this stays
     * inline per parent, runs stage 1 with per-branch parent-FK {@code WHERE}, dispatches per
     * typename, and returns the single typed Record. List-cardinality children use the
     * DataLoader-batched {@link #buildBatchedListFetcher} / {@link #buildBatchedListRowsMethod}
     * path instead.
     *
     * <p>The per-branch {@code WHERE} (see {@link #branchParentFkWhere}) reads the hub-side FK
     * value off a {@code parentRecord} local whose binding depends on the parent's classification:
     *
     * <ul>
     *   <li><b>Table-backed parent</b> ({@link GraphitronType.JooqTableRecordType}; {@link
     *       SourceKey.Reader.ColumnRead}): {@code Record parentRecord = (Record) env.getSource()}.
     *       The parent record is the hub itself; its FK columns are read by name.</li>
     *   <li><b>Record-backed parent</b> ({@link GraphitronType.PojoResultType} / {@link
     *       GraphitronType.JavaRecordType}; {@link SourceKey.Reader.AccessorCall} at {@link
     *       SourceKey.Cardinality#ONE}): {@code Record parentRecord = ((Backing) env.getSource())
     *       .<accessor>()}, where {@code Backing} and the accessor name come straight off the
     *       {@link no.sikt.graphitron.rewrite.model.AccessorRef}. The single-cardinality typed
     *       accessor returns the hub {@code TableRecord} directly; its FK columns are read by name
     *       exactly as the table-backed arm does. A null accessor return yields a null payload
     *       (no hub, no polymorphic child).</li>
     * </ul>
     *
     * <p>Mirrors the list arm's {@link GeneratorUtils#buildRecordParentKeyExtraction} accessor
     * handling, but inline: single cardinality reads the hub record's FK columns directly rather
     * than projecting a batch key and joining a {@code VALUES} table, so it needs only
     * {@code parentSourceKey} (the {@code AccessorRef}), not the parent {@code ResultType} the
     * batched key extraction threads through.
     */
    private static MethodSpec buildScalarPerParentFetcher(
            TypeFetcherEmissionContext ctx,
            String fieldName, List<ParticipantRef.TableBound> participants,
            Map<String, List<JoinStep>> participantJoinPaths,
            SourceKey parentSourceKey,
            boolean isList, String outputPackage) {

        var listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        TypeName valueType = isList ? listOfRecord : RECORD;

        var builder = MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");

        builder.beginControlFlow("try");
        builder.addStatement("$T dsl = $L.getDslContext(env)", DSL_CONTEXT, ctx.graphitronContextCall());

        if (participants.isEmpty()) {
            if (isList) {
                builder.addStatement("$T payload = $T.of()", listOfRecord, LIST);
            } else {
                builder.addStatement("$T payload = ($T) null", RECORD, RECORD);
            }
            builder.addCode(returnSyncSuccess(valueType, "payload"));
            builder.nextControlFlow("catch ($T e)", Exception.class);
            builder.addCode(noChannelCatchArm(outputPackage));
            builder.endControlFlow();
            return builder.build();
        }

        if (parentSourceKey != null
                && parentSourceKey.reader() instanceof SourceKey.Reader.AccessorCall ac) {
            // Record-backed parent: the typed single-cardinality accessor returns the hub
            // TableRecord whose FK columns branchParentFkWhere reads by name. A null hub means no
            // polymorphic child for this parent. Reaching here implies cardinality ONE: emitMethods
            // routes list-cardinality AccessorCall parents to the batched buildBatchedListFetcher,
            // so the accessor return is a single TableRecord (not a List), assignable to Record.
            var accessor = ac.accessor();
            builder.addStatement("$T parentRecord = (($T) env.getSource()).$L()",
                RECORD, accessor.parentBackingClass(), accessor.methodName());
            builder.beginControlFlow("if (parentRecord == null)");
            builder.addStatement("$T payload = ($T) null", RECORD, RECORD);
            builder.addCode(returnSyncSuccess(valueType, "payload"));
            builder.endControlFlow();
        } else {
            // Table-backed parent: env.getSource() is the hub jOOQ Record itself.
            builder.addStatement("$T parentRecord = ($T) env.getSource()", RECORD, RECORD);
        }

        // Child polymorphic fields carry no field-level filter surface (R363 is root-only), so no
        // decode registry is threaded here.
        builder.addCode(buildStage1Block(ctx, participants, participantJoinPaths, Map.of(), null));

        int pkArity = participants.get(0).table().primaryKeyColumns().size();
        builder.addStatement("Object[] result = new Object[stage1.size()]");
        builder.addCode(buildPerTypenameDispatcher(participants, fieldName, pkArity));

        if (isList) {
            builder.addStatement("$T payload = new $T<>(stage1.size())", listOfRecord, ARRAY_LIST);
            builder.beginControlFlow("for (Object o : result)");
            builder.addStatement("if (o instanceof $T r) payload.add(r)", RECORD);
            builder.endControlFlow();
        } else {
            builder.addStatement("$T payload = result.length == 0 ? null : ($T) result[0]", RECORD, RECORD);
        }
        builder.addCode(returnSyncSuccess(valueType, "payload"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(noChannelCatchArm(outputPackage));
        builder.endControlFlow();
        return builder.build();
    }

    /**
     * Shared per-typename dispatch helper. Groups stage-1 rows by {@code __typename} into
     * {@code (idx, pks)} bindings, then dispatches per typename to the stage-2 helper that
     * scatters typed Records back into {@code result[idx]}. Used by {@link #buildMainFetcher}
     * (root) and {@link #buildScalarPerParentFetcher} (child non-list); the windowed-CTE
     * connection-rows arm has a structurally different stage-2 over the ranked CTE and stays
     * inline.
     */
    private static CodeBlock buildPerTypenameDispatcher(
            List<ParticipantRef.TableBound> participants, String fieldName, int pkArity) {
        var b = CodeBlock.builder();
        var listOfObjArray = ParameterizedTypeName.get(LIST, ArrayTypeName.of(ClassName.get(Object.class)));
        var byTypeMap = ParameterizedTypeName.get(MAP, ClassName.get(String.class), listOfObjArray);
        b.addStatement("$T byType = new $T<>()", byTypeMap, LINKED_HASH_MAP);
        b.beginControlFlow("for (int i = 0; i < stage1.size(); i++)");
        b.addStatement("$T r = stage1.get(i)", RECORD);
        b.addStatement("String tn = r.get($S, String.class)", TYPENAME_COLUMN);
        var pksBuilder = CodeBlock.builder().add("new Object[]{");
        for (int s = 0; s < pkArity; s++) {
            if (s > 0) pksBuilder.add(", ");
            pksBuilder.add("r.get($S)", PK_COLUMN_PREFIX + s + PK_COLUMN_SUFFIX);
        }
        pksBuilder.add("}");
        b.addStatement("Object[] pks = $L", pksBuilder.build());
        b.addStatement("byType.computeIfAbsent(tn, k -> new $T<>()).add(new Object[]{i, pks})", ARRAY_LIST);
        b.endControlFlow();
        for (var participant : participants) {
            String typeName = participant.typeName();
            b.beginControlFlow("if (byType.containsKey($S))", typeName);
            b.addStatement("$L(byType.get($S), env, dsl, result)",
                perTypenameMethodName(fieldName, typeName), typeName);
            b.endControlFlow();
        }
        return b.build();
    }

    /**
     * Root connection main fetcher. Mirrors {@link #buildMainFetcher} for the
     * list path but returns {@code DataFetcherResult<ConnectionResult>}: stage 1 wraps the
     * per-branch UNION ALL in a derived table {@code pages} and applies
     * {@code .orderBy/.seek/.limit} from a {@code ConnectionHelper.PageRequest} so cursor
     * decoding and page-size + 1 over-fetch happen against the union as a whole; stage 2
     * dispatches per-typename and the resulting typed Records carry both {@code __typename}
     * (for the schema-class TypeResolver) and {@code __sort__} (for
     * {@code ConnectionHelper.encodeCursor}).
     *
     * <p>{@code ConnectionResult} is constructed with {@code (table, condition) =
     * (pagesTable, DSL.noCondition())} so the same UNION-ALL derived table backs both the page
     * query and {@code ConnectionHelper.totalCount}'s {@code SELECT count(*)}; the count
     * resolver remains lazy on selection (graphql-java only invokes it when the client picks
     * the field). The empty-participants defensive path keeps {@code (null, null)} since there
     * is no derived table to count.
     *
     * <p>Root-only: child interface/union connections route through
     * {@link #buildBatchedConnectionFetcher} via the {@code parentKey != null} arm of
     * {@link #emitConnectionMethods}.
     */
    private static MethodSpec buildRootConnectionFetcher(
            TypeFetcherEmissionContext ctx,
            String fieldName, List<ParticipantRef.TableBound> participants,
            Map<String, List<WhereFilter>> participantFilters,
            int defaultPageSize, String outputPackage,
            CompositeDecodeHelperRegistry registry) {

        var connectionResultClass = ClassName.get(outputPackage + ".util",
            no.sikt.graphitron.rewrite.generators.util.ConnectionResultClassGenerator.CLASS_NAME);
        var connectionHelperClass = ClassName.get(outputPackage + ".util",
            no.sikt.graphitron.rewrite.generators.util.ConnectionHelperClassGenerator.CLASS_NAME);
        var pageRequestClass = ClassName.get(outputPackage + ".util",
            "ConnectionHelper", "PageRequest");

        TypeName valueType = connectionResultClass;

        var builder = MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(syncResultType(valueType))
            .addParameter(ENV, "env");

        builder.beginControlFlow("try");
        builder.addStatement("$T dsl = $L.getDslContext(env)", DSL_CONTEXT, ctx.graphitronContextCall());

        if (participants.isEmpty()) {
            // Defensive empty path: validator rejects an empty participant set, but emit a
            // non-throwing fallback so the generator output type-checks. List.of() flows cleanly
            // through ConnectionResult — pageInfo.startCursor/endCursor return null, edges/nodes
            // return empty.
            builder.addStatement("$T page = $T.pageRequest(null, null, null, null, $L,\n"
                + "        $T.of(), $T.of(), $T.of())",
                pageRequestClass, connectionHelperClass, defaultPageSize, LIST, LIST, LIST);
            builder.addStatement("$T payload = new $T($T.of(), page, null, null)",
                valueType, connectionResultClass, LIST);
            builder.addCode(returnSyncSuccess(valueType, "payload"));
            builder.nextControlFlow("catch ($T e)", Exception.class);
            builder.addCode(noChannelCatchArm(outputPackage));
            builder.endControlFlow();
            return builder.build();
        }

        // Sort-key Field<T>: validator enforces uniform PK arity across participants.
        // Single-PK participants project the PK column directly and the sort key is typed as the
        // PK class. Composite-PK participants project {@code DSL.jsonbArray(pk0..pkN)} and the
        // sort key is typed as JSONB; PostgreSQL's JSONB lexicographic ordering reproduces the
        // multi-column ordering, and the cursor round-trips JSONB through
        // {@code ConnectionHelper.encodeCursor / decodeCursor} via {@code JSONB.toString()} +
        // {@code Convert.convert(String, JSONB.class)}.
        int pkArity = participants.get(0).table().primaryKeyColumns().size();
        ClassName pkColumnClass;
        if (pkArity == 1) {
            var firstPk = participants.get(0).table().primaryKeyColumns().get(0);
            pkColumnClass = ClassName.bestGuess(firstPk.columnClass());
        } else {
            pkColumnClass = JSONB;
        }
        var sortFieldType = ParameterizedTypeName.get(FIELD, pkColumnClass);
        var listOfSortField = ParameterizedTypeName.get(LIST, ParameterizedTypeName.get(SORT_FIELD,
            WildcardTypeName.subtypeOf(Object.class)));
        var fieldWildcard = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        var listOfFieldWildcard = ParameterizedTypeName.get(LIST, fieldWildcard);

        builder.addStatement("$T sortField = $T.field($T.name($S), $T.class)",
            sortFieldType, DSL, DSL, SORT_COLUMN, pkColumnClass);
        // __typename ASC is appended as a secondary sort and as a seek tiebreaker so rows with
        // identical PKs across participants (e.g. Film(1) and Actor(1)) order deterministically
        // and the cursor "after sort=3" resolves consistently across pages. Without it, ties
        // resolve undefined-order on the database side, and pagination at a tie boundary can
        // double-count or skip rows depending on the planner.
        var fieldOfString = ParameterizedTypeName.get(FIELD, ClassName.get(String.class));
        builder.addStatement("$T tieField = $T.field($T.name($S), String.class)",
            fieldOfString, DSL, DSL, TYPENAME_COLUMN);
        builder.addStatement("$T orderBy = $T.of(sortField.asc(), tieField.asc())", listOfSortField, LIST);
        builder.addStatement("$T extraFields = new $T<>($T.<$T>of(sortField, tieField))",
            listOfFieldWildcard, ARRAY_LIST, LIST, fieldWildcard);

        builder.addStatement("$T first = env.getArgument($S)", ClassName.get(Integer.class), "first");
        builder.addStatement("$T last = env.getArgument($S)", ClassName.get(Integer.class), "last");
        builder.addStatement("String after = env.getArgument($S)", "after");
        builder.addStatement("String before = env.getArgument($S)", "before");

        builder.addStatement("$T page = $T.pageRequest(first, last, after, before, $L,\n"
            + "        orderBy, extraFields, $T.of())",
            pageRequestClass, connectionHelperClass, defaultPageSize, LIST);

        // Stage 1: UNION ALL of branches as derived table; outer SELECT applies .orderBy/.seek/.limit.
        stampUncheckedSuppressionIfNeeded(builder, participantFilters);
        builder.addCode(buildStage1ConnectionBlock(ctx, participants, participantFilters, registry));

        // Stage 1.5: group stage-1 rows by __typename into (idx, pks) bindings. Reads all
        // {@code __pk0__..__pkN__} columns per row so the per-typename helper has the full PK
        // tuple for ValuesJoinRowBuilder dispatch.
        builder.addStatement("Object[] result = new Object[stage1.size()]");
        var listOfObjArray = ParameterizedTypeName.get(LIST, ArrayTypeName.of(ClassName.get(Object.class)));
        var byTypeMap = ParameterizedTypeName.get(MAP, ClassName.get(String.class), listOfObjArray);
        builder.addStatement("$T byType = new $T<>()", byTypeMap, LINKED_HASH_MAP);
        builder.beginControlFlow("for (int i = 0; i < stage1.size(); i++)");
        builder.addStatement("$T r = stage1.get(i)", RECORD);
        builder.addStatement("String tn = r.get($S, String.class)", TYPENAME_COLUMN);
        var pksBuilder = CodeBlock.builder().add("new Object[]{");
        for (int s = 0; s < pkArity; s++) {
            if (s > 0) pksBuilder.add(", ");
            pksBuilder.add("r.get($S)", PK_COLUMN_PREFIX + s + PK_COLUMN_SUFFIX);
        }
        pksBuilder.add("}");
        builder.addStatement("Object[] pks = $L", pksBuilder.build());
        builder.addStatement("byType.computeIfAbsent(tn, k -> new $T<>()).add(new Object[]{i, pks})", ARRAY_LIST);
        builder.endControlFlow();

        // Stage 2: per-typename dispatch.
        for (var participant : participants) {
            String typeName = participant.typeName();
            builder.beginControlFlow("if (byType.containsKey($S))", typeName);
            builder.addStatement("$L(byType.get($S), env, dsl, result)",
                perTypenameMethodName(fieldName, typeName), typeName);
            builder.endControlFlow();
        }

        // Merge: walk result[] in stage-1 order; each non-null entry is a typed Record carrying
        // __typename and __sort__. ConnectionResult.trimmedResult() trims to pageSize.
        var listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        builder.addStatement("$T payload = new $T<>(stage1.size())", listOfRecord, ARRAY_LIST);
        builder.beginControlFlow("for (Object o : result)");
        builder.addStatement("if (o instanceof $T r) payload.add(r)", RECORD);
        builder.endControlFlow();

        // Bind the same UNION-ALL derived table {@code pagesTable} onto ConnectionResult so
        // ConnectionHelper.totalCount can issue {@code SELECT count(*) FROM (UNION ALL) AS pages}
        // lazily on selection.
        builder.addStatement("$T cr = new $T(payload, page, $L, $T.noCondition())",
            valueType, connectionResultClass, CONNECTION_PAGES_LOCAL, DSL);
        builder.addCode(returnSyncSuccess(valueType, "cr"));
        builder.nextControlFlow("catch ($T e)", Exception.class);
        builder.addCode(noChannelCatchArm(outputPackage));
        builder.endControlFlow();
        return builder.build();
    }

    /**
     * Connection-mode stage 1: emits the per-branch UNION ALL of {@code (typename, pk0, sort)}
     * projections as a derived table {@code pages} held in a local variable
     * {@code pagesTable} (typed {@code Table<?>}); the page query then runs
     * {@code dsl.select(...).from(pagesTable).orderBy().seek().limit().fetch()} so cursor
     * decoding and page-size + 1 over-fetch happen uniformly across the union.
     *
     * <p>Materialising the derived table as a local lets the connection fetcher pass the
     * <em>same</em> {@code Table<?>} reference into {@code ConnectionResult} so
     * {@code ConnectionHelper.totalCount} can issue {@code SELECT count(*) FROM pagesTable}
     * lazily on selection (B4b). The carrier condition is {@code DSL.noCondition()}; root
     * connections have no parent restriction.
     */
    private static CodeBlock buildStage1ConnectionBlock(
            TypeFetcherEmissionContext ctx,
            List<ParticipantRef.TableBound> participants,
            Map<String, List<WhereFilter>> participantFilters,
            CompositeDecodeHelperRegistry registry) {
        var b = CodeBlock.builder();

        for (var participant : participants) {
            var jooqTableClass = participant.table().tableClass();
            String alias = "stage1_" + participant.typeName();
            b.addStatement("$T $L = $T.$L", jooqTableClass, alias, participant.table().constantsClass(), participant.table().javaFieldName());
        }

        var plumbing = declareFilterPlumbing(b, participants, participantFilters);

        var tableWildcard = ParameterizedTypeName.get(TABLE, WildcardTypeName.subtypeOf(Object.class));
        b.add("$T $L =\n", tableWildcard, CONNECTION_PAGES_LOCAL);
        for (int p = 0; p < participants.size(); p++) {
            var participant = participants.get(p);
            String alias = "stage1_" + participant.typeName();
            // Root connection has no parent-FK restriction, so the branch WHERE is the @field filter
            // predicate alone (R363). buildStage1ConnectionBlock previously emitted no per-branch WHERE.
            CodeBlock branchWhere = branchFilterWhere(ctx, participant, participantFilters, registry, plumbing);
            if (p == 0) {
                b.add("    dsl.select($L)\n", branchProjection(participant, alias));
                b.add("        .from($L)\n", alias);
                if (branchWhere != null) {
                    b.add("        .where($L)\n", branchWhere);
                }
            } else {
                b.add("    .unionAll(dsl.select($L)\n", branchProjection(participant, alias));
                if (branchWhere != null) {
                    b.add("        .from($L)\n", alias);
                    b.add("        .where($L))\n", branchWhere);
                } else {
                    b.add("        .from($L))\n", alias);
                }
            }
        }
        b.add("    .asTable($S);\n", CONNECTION_PAGES_ALIAS);

        // PK columns: type each {@code __pk_i__} by the participant's PK column class. Validator
        // enforces uniform PK arity across participants; we read PK column types from the first
        // participant since uniform-PK-types-across-participants is implicit in the union (jOOQ's
        // UNION ALL would fail to compile otherwise).
        var firstParticipantPks = participants.get(0).table().primaryKeyColumns();
        int pkArity = firstParticipantPks.size();
        var resultBound = ParameterizedTypeName.get(RESULT, WildcardTypeName.subtypeOf(RECORD));
        b.add("$T stage1 = dsl\n", resultBound);
        b.add("    .select(\n");
        b.add("        $T.field($T.name($S), $T.class),\n", DSL, DSL, TYPENAME_COLUMN, ClassName.get(String.class));
        for (int s = 0; s < pkArity; s++) {
            ClassName pkColClass = ClassName.bestGuess(firstParticipantPks.get(s).columnClass());
            b.add("        $T.field($T.name($S), $T.class),\n", DSL, DSL,
                PK_COLUMN_PREFIX + s + PK_COLUMN_SUFFIX, pkColClass);
        }
        b.add("        sortField)\n");
        b.add("    .from($L)\n", CONNECTION_PAGES_LOCAL);
        b.add("    .orderBy(page.effectiveOrderBy())\n");
        b.add("    .seek(page.seekFields())\n");
        b.add("    .limit(page.limit())\n");
        b.add("    .fetch();\n");
        return b.build();
    }

    /**
     * Emits the stage-1 SELECT chain: one branch per participant, glued by {@code .unionAll(...)}.
     * Each branch projects {@code DSL.inline("<TypeName>").as("__typename")} plus the participant's
     * PK columns aliased to {@code __pk0__..__pkN__}, plus a {@code __sort__} key. The composite-PK
     * sort key uses {@code DSL.jsonbArray(...)}; single-column PKs project the column directly.
     *
     * <p>For the child-fetcher form, each branch additionally carries a
     * {@code .where(<parent-FK predicate>)} restricting that participant to rows whose FK
     * matches the carrier {@code parentRecord}'s PK. The predicate is derived from the
     * single-hop FK in {@code participantJoinPaths}; multi-hop chains and condition-joins are
     * not supported in v1 (the classifier's auto-discovery only produces single-hop FK paths).
     *
     * <p>The result is declared as {@code Result<? extends Record>} so jOOQ's typed
     * {@code Result<RecordN<...>>} inference (one type-arg per projected column) widens to a
     * uniform Record-iterable shape that the dispatch loop can consume without raw types.
     */
    private static CodeBlock buildStage1Block(
            TypeFetcherEmissionContext ctx,
            List<ParticipantRef.TableBound> participants,
            Map<String, List<JoinStep>> participantJoinPaths,
            Map<String, List<WhereFilter>> participantFilters,
            CompositeDecodeHelperRegistry registry) {
        var b = CodeBlock.builder();

        // Declare per-participant table aliases for stage 1. Stage-1 aliases are distinct from
        // any stage-2 locals (the stage-2 helpers declare their own t inside their method body).
        for (var participant : participants) {
            var jooqTableClass = participant.table().tableClass();
            String alias = "stage1_" + participant.typeName();
            b.addStatement("$T $L = $T.$L", jooqTableClass, alias, participant.table().constantsClass(), participant.table().javaFieldName());
        }

        var plumbing = declareFilterPlumbing(b, participants, participantFilters);

        var resultBound = ParameterizedTypeName.get(RESULT,
            WildcardTypeName.subtypeOf(RECORD));
        b.add("$T stage1 = ", resultBound);
        for (int p = 0; p < participants.size(); p++) {
            var participant = participants.get(p);
            String alias = "stage1_" + participant.typeName();
            // Combine the parent-FK predicate (child fetchers) with the @field filter predicate
            // (R363, root fields); either may be null, in which case the other stands alone.
            CodeBlock branchWhere = andWhere(
                branchParentFkWhere(participant, participantJoinPaths),
                branchFilterWhere(ctx, participant, participantFilters, registry, plumbing));
            if (p == 0) {
                b.add("dsl.select($L)\n", branchProjection(participant, alias));
                b.add("    .from($L)\n", alias);
                if (branchWhere != null) {
                    b.add("    .where($L)\n", branchWhere);
                }
            } else {
                b.add("    .unionAll(dsl.select($L)\n", branchProjection(participant, alias));
                if (branchWhere != null) {
                    b.add("        .from($L)\n", alias);
                    b.add("        .where($L))\n", branchWhere);
                } else {
                    b.add("        .from($L))\n", alias);
                }
            }
        }
        b.add("    .orderBy($T.field($T.name($S)))\n", DSL, DSL, SORT_COLUMN);
        b.add("    .fetch();\n");
        return b.build();
    }

    /**
     * Builds the parent-FK WHERE predicate for one stage-1 branch in the child-fetcher form, or
     * returns {@code null} when no joinPath applies (root-fetcher form, or the participant is
     * absent from {@code participantJoinPaths}).
     *
     * <p>Single-hop FK only: per-slot AND-chain
     * {@code <participantTable>.<slot.targetSide()>.eq(parentRecord.get(DSL.name("<slot.sourceSide().sqlName()>"), <Type>.class))}.
     * Synthesis-time slot orientation means the parent side is always {@code slot.sourceSide()} and
     * the participant side {@code slot.targetSide()}, regardless of which end of the catalog FK each
     * maps to; iteration is direction-blind.
     *
     * <p>The two-arg {@code parentRecord.get(Name, Class)} form returns the typed value
     * (rather than {@code Object}) so the typed {@code Field<T>.eq(T)} overload selects
     * cleanly without an unchecked cast.
     */
    private static CodeBlock branchParentFkWhere(ParticipantRef.TableBound participant,
            Map<String, List<JoinStep>> participantJoinPaths) {
        var path = participantJoinPaths.get(participant.typeName());
        if (path == null || path.isEmpty()) return null;
        if (!(path.get(0) instanceof JoinStep.FkJoin fkJoin)) return null;
        if (fkJoin.slotCount() == 0) return null;

        String tableAlias = "stage1_" + participant.typeName();
        var b = CodeBlock.builder();
        int i = 0;
        for (var slot : fkJoin.slots()) {
            ColumnRef parentSide = slot.sourceSide();
            ColumnRef participantSide = slot.targetSide();
            ClassName parentColClass = ClassName.bestGuess(parentSide.columnClass());
            if (i == 0) {
                b.add("$L.$L.eq(parentRecord.get($T.name($S), $T.class))",
                    tableAlias, participantSide.javaName(), DSL, parentSide.sqlName(), parentColClass);
            } else {
                b.add(".and($L.$L.eq(parentRecord.get($T.name($S), $T.class)))",
                    tableAlias, participantSide.javaName(), DSL, parentSide.sqlName(), parentColClass);
            }
            i++;
        }
        return b.build();
    }

    /**
     * Pre-declared extraction plumbing shared by every stage-1 branch of one fetcher method
     * (R384 phase 0). {@code fkTargetAliases} maps each {@link no.sikt.graphitron.rewrite.model.FkTargetConditionFilter}
     * to its declared per-hop alias locals (namespaced by the participant's {@code stage1_<Type>}
     * base, so two participants' filters never collide); {@code liftedOuters} maps a
     * {@link CallSiteExtraction.NestedInputField#outerArgName()} referenced by ≥2 call params
     * across all participants to the shared {@code Map<?, ?>} local holding its one
     * {@code env.getArgument} read.
     */
    private record FilterPlumbing(
            Map<WhereFilter, List<String>> fkTargetAliases,
            Map<String, String> liftedOuters) {
        static final FilterPlumbing EMPTY = new FilterPlumbing(Map.of(), Map.of());
    }

    /**
     * Declares, as statements ahead of the inline stage-1 union expression, every local the branch
     * filter terms need but cannot introduce themselves (filter terms compose as expressions):
     *
     * <ul>
     *   <li>FK-target join-hop aliases ({@link FkTargetConditionEmitter#declareAliases}), invoked
     *       once per participant with the participant's {@code stage1_<Type>} base alias so the
     *       Java locals stay unique across participants within the one enclosing method;</li>
     *   <li>one {@code List<String> <name>Keys} local per {@code JooqConvert}-list arg name,
     *       deduped across participants (the env argument is the same for every branch), matching
     *       the single-table {@code QueryConditionsGenerator} pre-lift;</li>
     *   <li>one {@code Map<?, ?>} local per nested-input outer arg referenced ≥2 times across all
     *       participants' filters ({@link QueryConditionsGenerator#computeLiftedOuters}) — on this
     *       path two branches filtering on the same input object share the read.</li>
     * </ul>
     *
     * Returns {@link FilterPlumbing#EMPTY} without emitting anything when no participant carries
     * filters (the child-fetcher and service forms).
     */
    private static FilterPlumbing declareFilterPlumbing(CodeBlock.Builder b,
            List<ParticipantRef.TableBound> participants,
            Map<String, List<WhereFilter>> participantFilters) {
        if (participantFilters.values().stream().allMatch(List::isEmpty)) {
            return FilterPlumbing.EMPTY;
        }
        var fkTargetAliases = new java.util.IdentityHashMap<WhereFilter, List<String>>();
        for (var participant : participants) {
            var filters = participantFilters.getOrDefault(participant.typeName(), List.of());
            if (filters.isEmpty()) continue;
            fkTargetAliases.putAll(FkTargetConditionEmitter.declareAliases(
                b, filters, "stage1_" + participant.typeName(), false));
        }
        var allFilters = participants.stream()
            .flatMap(p -> participantFilters.getOrDefault(p.typeName(), List.<WhereFilter>of()).stream())
            .toList();
        var declaredKeys = new java.util.LinkedHashSet<String>();
        for (var filter : allFilters) {
            for (var param : filter.callParams()) {
                if (param.extraction() instanceof CallSiteExtraction.JooqConvert && param.list()
                        && declaredKeys.add(param.name())) {
                    b.addStatement("$T<$T> $L = env.getArgument($S)",
                        LIST, String.class, toCamelCase(param.name()) + "Keys", param.name());
                }
            }
        }
        var liftedOuters = QueryConditionsGenerator.computeLiftedOuters(allFilters);
        for (var entry : liftedOuters.entrySet()) {
            b.addStatement("$T<?, ?> $L = env.getArgument($S) instanceof $T<?, ?> map ? map : null",
                Map.class, entry.getValue(), entry.getKey(), Map.class);
        }
        return new FilterPlumbing(fkTargetAliases, liftedOuters);
    }

    /**
     * Builds the {@code @field} filter predicate for one stage-1 branch by ANDing each lowered
     * {@link WhereFilter} as a condition term bound to the branch alias {@code stage1_<Type>} (R363),
     * or {@code null} when the participant has no filters. Reuses
     * {@link FkTargetConditionEmitter#emitTerm} so a participant's filters bind to its own table
     * exactly as the single-table fetcher path does — each participant's filters were lowered against
     * its own table, with a participant-named {@code <Participant>Conditions} method.
     *
     * <p>R384 phase 0 threads the real extraction plumbing through this seam: the enclosing
     * {@code <Type>Fetchers} class's {@link CompositeDecodeHelperRegistry} (decode helpers land on
     * the class hosting the call site, mirroring the split-rows / lookup-rows precedent), the
     * lifted-outer Map locals, and the FK-target aliases declared by
     * {@link #declareFilterPlumbing} ahead of the union expression. The classifier
     * ({@code FieldBuilder.firstUnsupportedFilterArg}) still gates which extraction kinds reach
     * here; each R384 phase flips one arm on top of this shared threading.
     */
    private static CodeBlock branchFilterWhere(TypeFetcherEmissionContext ctx,
            ParticipantRef.TableBound participant, Map<String, List<WhereFilter>> participantFilters,
            CompositeDecodeHelperRegistry registry, FilterPlumbing plumbing) {
        var filters = participantFilters.getOrDefault(participant.typeName(), List.of());
        if (filters.isEmpty()) return null;
        String alias = "stage1_" + participant.typeName();
        var b = CodeBlock.builder();
        for (int i = 0; i < filters.size(); i++) {
            var term = FkTargetConditionEmitter.emitTerm(ctx, filters.get(i), alias,
                registry, plumbing.liftedOuters(), plumbing.fkTargetAliases());
            if (i == 0) {
                b.add("$L", term);
            } else {
                b.add(".and($L)", term);
            }
        }
        return b.build();
    }

    /**
     * Stamps {@code @SuppressWarnings("unchecked")} on a root fetcher method when any participant
     * filter carries a call param whose extraction emits an unchecked cast
     * ({@link CallParam#emitsUncheckedCast()} — the model owns that fact). Today that is a list-typed
     * {@link CallSiteExtraction.NestedInputField} extracting as {@code (List<X>) map.get(key)} at the
     * branch call site (R383). The single-table {@code QueryConditionsGenerator} folds over the same
     * model predicate for its {@code <field>Condition} method, so the two paths cannot drift. Stamped
     * only when such a param is present, at the narrowest enclosing member.
     */
    private static void stampUncheckedSuppressionIfNeeded(
            MethodSpec.Builder builder, Map<String, List<WhereFilter>> participantFilters) {
        boolean needsSuppression = participantFilters.values().stream()
            .flatMap(List::stream)
            .flatMap(f -> f.callParams().stream())
            .anyMatch(CallParam::emitsUncheckedCast);
        if (needsSuppression) {
            builder.addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                .addMember("value", "$S", "unchecked")
                .build());
        }
    }

    /** ANDs two nullable branch WHERE predicates; returns whichever is non-null, or null if both are. */
    private static CodeBlock andWhere(CodeBlock first, CodeBlock second) {
        if (first == null) return second;
        if (second == null) return first;
        return CodeBlock.of("$L.and($L)", first, second);
    }

    /**
     * Builds the per-branch projection list (typename literal + PK columns + sort key) used
     * inside one {@code dsl.select(...)} clause of the stage-1 union.
     */
    private static CodeBlock branchProjection(ParticipantRef.TableBound participant, String tableAlias) {
        var pks = participant.table().primaryKeyColumns();
        var b = CodeBlock.builder();
        b.add("$T.inline($S).as($S)", DSL, participant.typeName(), TYPENAME_COLUMN);
        for (int s = 0; s < pks.size(); s++) {
            b.add(", $L.$L.as($S)", tableAlias, pks.get(s).javaName(), PK_COLUMN_PREFIX + s + PK_COLUMN_SUFFIX);
        }
        // Sort key: single column projects the PK directly; composite uses jsonbArray for
        // element-wise comparison in PostgreSQL.
        if (pks.size() == 1) {
            b.add(", $L.$L.as($S)", tableAlias, pks.get(0).javaName(), SORT_COLUMN);
        } else {
            var jsonbArgs = CodeBlock.builder();
            for (int s = 0; s < pks.size(); s++) {
                if (s > 0) jsonbArgs.add(", ");
                jsonbArgs.add("$L.$L", tableAlias, pks.get(s).javaName());
            }
            b.add(", $T.jsonbArray($L).as($S)", DSL, jsonbArgs.build(), SORT_COLUMN);
        }
        return b.build();
    }

    /**
     * Batched child-connection main fetcher: registers a {@link org.dataloader.DataLoader} keyed on the
     * parent's {@link no.sikt.graphitron.rewrite.model.SourceKey} and delegates to a
     * {@code rows<Field>(List<RowN<PK1...PKn>>, env)} batch loader. The body shape mirrors
     * {@code TypeFetcherGenerator.buildSplitQueryDataFetcher}: build the tenant-scoped DataLoader
     * name, {@code computeIfAbsent} the loader, extract the parent PK from {@code env.getSource()}
     * via {@link GeneratorUtils#buildRecordParentKeyExtraction}, then return
     * {@code loader.load(key, env).thenApply(...).exceptionally(...)}.
     *
     * <p>Parent PK arity 1..21 enforced upstream (validator's
     * {@code validateChildMultiTableParentPk}); the {@code parentInput} VALUES table widens to
     * {@code Row<N+1>} including {@code idx}, which tops out at Row22.
     */
    private static MethodSpec buildBatchedConnectionFetcher(
            TypeFetcherEmissionContext ctx,
            String fieldName,
            SourceKey parentSourceKey,
            GraphitronType.ResultType parentResultType,
            String outputPackage) {

        var connectionResultClass = ClassName.get(outputPackage + ".util",
            no.sikt.graphitron.rewrite.generators.util.ConnectionResultClassGenerator.CLASS_NAME);
        TypeName valueType = connectionResultClass;

        TypeName keyType = parentSourceKey.keyElementType();
        TypeName loaderType = ParameterizedTypeName.get(DATA_LOADER, keyType, valueType);
        TypeName lambdaKeysType = ParameterizedTypeName.get(LIST, keyType);
        String rowsMethodName = "rows" + cap(fieldName);

        var lambdaBlock = CodeBlock.builder()
            .add("($T keys, $T batchEnv) -> {\n", lambdaKeysType, BATCH_LOADER_ENV)
            .indent()
            .addStatement("$T dfe = ($T) batchEnv.getKeyContextsList().get(0)", ENV, ENV)
            .addStatement("return $T.completedFuture($L(keys, dfe))", COMPLETABLE_FUTURE, rowsMethodName)
            .unindent()
            .add("}")
            .build();

        var builder = MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(asyncResultType(valueType))
            .addParameter(ENV, "env");

        builder.addStatement(
            "$T name = $T.join($S, env.getExecutionStepInfo().getPath().getKeysOnly())",
            String.class, String.class, "/");
        builder.addCode(
            "$T loader = env.getDataLoaderRegistry()\n"
            + "    .computeIfAbsent(name, k -> $T.newDataLoader($L));\n",
            loaderType, DATA_LOADER_FACTORY, lambdaBlock);

        // Parent-object key extraction: delegated to the canonical Reader × Cardinality helper.
        // Emits the typed {@code <KeyType> key = ...} statement consumed by load(key, env).
        builder.addCode(GeneratorUtils.buildRecordParentKeyExtraction(parentSourceKey, parentResultType));

        builder.addCode(CodeBlock.builder()
            .add("return loader.load(key, env)\n")
            .add("    ").add(asyncWrapTail(valueType, outputPackage)).add(";\n")
            .build());

        return builder.build();
    }

    /**
     * Batched child-list main fetcher: registers a {@link org.dataloader.DataLoader} keyed on the
     * parent's {@link no.sikt.graphitron.rewrite.model.SourceKey} and delegates to a
     * {@code rows<Field>(List<RowN<…>>, env)} batch loader returning {@code List<List<Record>>}
     * (one bucket per parent in the batch). Same async-tail shape as the connection arm; only the
     * value type differs ({@code List<Record>} per parent vs. {@code ConnectionResult}).
     *
     * <p>The load site forks on the parent key's
     * {@link no.sikt.graphitron.rewrite.model.SourceKey.Cardinality}, matching what
     * {@link GeneratorUtils#buildRecordParentKeyExtraction} declared:
     * {@link no.sikt.graphitron.rewrite.model.SourceKey.Cardinality#ONE} readers (catalog-FK
     * {@code ColumnRead} on a {@code @table} parent, accessor-single on a record parent) declare a
     * single {@code key} and dispatch {@code loader.load(key, env)};
     * {@link no.sikt.graphitron.rewrite.model.SourceKey.Cardinality#MANY} readers (accessor-many,
     * produced-record-many on a Pojo / {@code @record} carrier) declare a {@code List<key> keys}
     * and dispatch {@code loader.loadMany(keys, …)}, then concat the one-bucket-per-element
     * {@code List<List<Record>>} into the field's single flat {@code List<Record>}. Without this
     * fork the MANY arm emitted {@code loader.load(key, env)} against an out-of-scope loop-local
     * {@code key} and failed javac (R366).
     */
    private static MethodSpec buildBatchedListFetcher(
            TypeFetcherEmissionContext ctx,
            String fieldName,
            SourceKey parentSourceKey,
            GraphitronType.ResultType parentResultType,
            String outputPackage) {

        TypeName listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        TypeName valueType = listOfRecord;

        TypeName keyType = parentSourceKey.keyElementType();
        TypeName loaderType = ParameterizedTypeName.get(DATA_LOADER, keyType, valueType);
        TypeName lambdaKeysType = ParameterizedTypeName.get(LIST, keyType);
        String rowsMethodName = "rows" + cap(fieldName);

        var lambdaBlock = CodeBlock.builder()
            .add("($T keys, $T batchEnv) -> {\n", lambdaKeysType, BATCH_LOADER_ENV)
            .indent()
            .addStatement("$T dfe = ($T) batchEnv.getKeyContextsList().get(0)", ENV, ENV)
            .addStatement("return $T.completedFuture($L(keys, dfe))", COMPLETABLE_FUTURE, rowsMethodName)
            .unindent()
            .add("}")
            .build();

        var builder = MethodSpec.methodBuilder(fieldName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(asyncResultType(valueType))
            .addParameter(ENV, "env");

        builder.addStatement(
            "$T name = $T.join($S, env.getExecutionStepInfo().getPath().getKeysOnly())",
            String.class, String.class, "/");
        builder.addCode(
            "$T loader = env.getDataLoaderRegistry()\n"
            + "    .computeIfAbsent(name, k -> $T.newDataLoader($L));\n",
            loaderType, DATA_LOADER_FACTORY, lambdaBlock);

        builder.addCode(GeneratorUtils.buildRecordParentKeyExtraction(parentSourceKey, parentResultType));

        // Dispatch on the parent key's cardinality, mirroring how
        // TypeFetcherGenerator.buildRecordBasedDataFetcher branches load vs loadMany. The key
        // extraction above declares a single {@code key} for the ONE readers (ColumnRead,
        // AccessorCall-single) and a {@code List<key> keys} for the MANY readers
        // (AccessorCall-many, ProducedRecordRead-many); the load site must match.
        if (parentSourceKey.cardinality() == SourceKey.Cardinality.MANY) {
            // loadMany yields one List<Record> bucket per element key (the loader value is
            // List<Record>, not Record, on this polymorphic path). The field surface is a single
            // flat list, so concat the per-element buckets in key order before the async tail.
            builder.addCode(CodeBlock.builder()
                .add("return loader.loadMany(keys, $T.nCopies(keys.size(), env))\n", COLLECTIONS)
                .add("    .thenApply(buckets -> buckets.stream().flatMap($T::stream).toList())\n", LIST)
                .add("    ").add(asyncWrapTail(valueType, outputPackage)).add(";\n")
                .build());
        } else {
            builder.addCode(CodeBlock.builder()
                .add("return loader.load(key, env)\n")
                .add("    ").add(asyncWrapTail(valueType, outputPackage)).add(";\n")
                .build());
        }

        return builder.build();
    }

    /**
     * Batched child-connection rows method: issues ONE SQL statement covering every parent in the
     * DataLoader batch, scatters typed Records into a {@code List<ConnectionResult>} indexed
     * 1:1 with the {@code keys} list.
     *
     * <p>Stage-1 shape:
     * <pre>{@code
     * Table<?> pagesTable =
     *     dsl.select(<typename, pk0, sort, parentInput.idx>)
     *        .from(stage1_Customer).join(parentInput).on(stage1_Customer.address_id = parentInput.address_id)
     *     .unionAll(
     *     dsl.select(<...>)
     *        .from(stage1_Staff).join(parentInput).on(stage1_Staff.address_id = parentInput.address_id))
     *     .asTable("pages");
     * Table<?> ranked = dsl
     *     .select(__typename, __pk0__, __sort__, __idx__,
     *             ROW_NUMBER() OVER (PARTITION BY __idx__ ORDER BY page.effectiveOrderBy()) AS __rn__)
     *     .from(pagesTable)
     *     .orderBy(page.effectiveOrderBy())
     *     .seek(page.seekFields())
     *     .asTable("ranked");
     * Result<? extends Record> stage1 = dsl.select().from(ranked)
     *     .where(ranked.field("__rn__", Integer.class).le(DSL.val(page.limit())))
     *     .fetch();
     * }</pre>
     *
     * <p>Stage-2 dispatch (per typename) writes typed Records to {@code result[outerIdx]}; a
     * parallel {@code int[] parentIdxByOuter} populated from each stage-1 row's {@code __idx__}
     * scatters the typed Records into per-parent {@code List<Record>} buckets. Each
     * per-parent {@code ConnectionResult} re-uses the SAME {@code pagesTable} reference but
     * applies a per-parent condition {@code __idx__.eq(i)} so {@code totalCount} resolves to
     * that parent's count via {@code SELECT count(*) FROM pagesTable WHERE __idx__ = :i} — N
     * count queries when totalCount is selected (vs. one per page invocation under B4c-1's
     * inline form), but the page-rows query is now batched.
     *
     * <p>Cursor semantics: graphql-java resolves field arguments once per field selection
     * regardless of parent-fanout, so every key in the batch shares the same
     * {@code first/last/after/before} values. The shared {@code PageRequest} produces
     * {@code page.effectiveOrderBy()} and {@code page.seekFields()} which fold into both the
     * {@code ROW_NUMBER() OVER (...)} clause and the seek-WHERE before {@code __rn__} is
     * computed; per-partition limit is enforced by {@code __rn__ <= page.limit()} on the outer
     * SELECT.
     */
    private static MethodSpec buildBatchedConnectionRowsMethod(
            TypeFetcherEmissionContext ctx,
            String fieldName, List<ParticipantRef.TableBound> participants,
            Map<String, List<JoinStep>> participantJoinPaths,
            int defaultPageSize, SourceKey parentSourceKey,
            String outputPackage) {

        var connectionResultClass = ClassName.get(outputPackage + ".util",
            no.sikt.graphitron.rewrite.generators.util.ConnectionResultClassGenerator.CLASS_NAME);
        var connectionHelperClass = ClassName.get(outputPackage + ".util",
            no.sikt.graphitron.rewrite.generators.util.ConnectionHelperClassGenerator.CLASS_NAME);
        var pageRequestClass = ClassName.get(outputPackage + ".util",
            "ConnectionHelper", "PageRequest");
        var integerClass = ClassName.get(Integer.class);
        var stringClass = ClassName.get(String.class);

        String rowsMethodName = "rows" + cap(fieldName);

        // Parent FK / lifter-projected columns on the source side; arity 1..21 enforced upstream
        // by validateChildMultiTableParentPk (idx adds one slot to the parentInput Row<N+1>).
        var parentPkCols = parentSourceKey.columns();
        int parentKeyArity = parentPkCols.size();
        TypeName keyType = parentSourceKey.keyElementType();
        TypeName keysListType = ParameterizedTypeName.get(LIST, keyType);
        TypeName listOfConnectionResult = ParameterizedTypeName.get(LIST, connectionResultClass);

        // Participant PK (single column for connection mode — validator enforces).
        ColumnRef firstParticipantPk = participants.get(0).table().primaryKeyColumns().get(0);
        ClassName participantPkClass = ClassName.bestGuess(firstParticipantPk.columnClass());

        var b = CodeBlock.builder();

        // Empty-input short-circuit — before touching the DSL context.
        b.beginControlFlow("if (keys.isEmpty())");
        b.addStatement("return $T.of()", LIST);
        b.endControlFlow();

        b.addStatement("$T dsl = $L.getDslContext(env)", DSL_CONTEXT, ctx.graphitronContextCall());

        // Per-participant table aliases for stage 1.
        for (var participant : participants) {
            var jooqTableClass = participant.table().tableClass();
            String alias = "stage1_" + participant.typeName();
            b.addStatement("$T $L = $T.$L", jooqTableClass, alias, participant.table().constantsClass(), participant.table().javaFieldName());
        }

        b.add(buildParentInputValuesEmitter(parentPkCols, keyType));

        // Pagination args (shared across the batch — graphql-java resolves field args once per
        // selection regardless of fanout).
        b.addStatement("$T first = env.getArgument($S)", integerClass, "first");
        b.addStatement("$T last = env.getArgument($S)", integerClass, "last");
        b.addStatement("$T after = env.getArgument($S)", stringClass, "after");
        b.addStatement("$T before = env.getArgument($S)", stringClass, "before");

        // Sort fields — sortField is typed by the participant PK class (validator enforces
        // single-column participant PK in connection mode); tieField gives deterministic
        // ordering for cross-participant PK ties.
        TypeName sortFieldType = ParameterizedTypeName.get(FIELD, participantPkClass);
        b.addStatement("$T sortField = $T.field($T.name($S), $T.class)",
            sortFieldType, DSL, DSL, SORT_COLUMN, participantPkClass);
        TypeName fieldOfString = ParameterizedTypeName.get(FIELD, stringClass);
        b.addStatement("$T tieField = $T.field($T.name($S), String.class)",
            fieldOfString, DSL, DSL, TYPENAME_COLUMN);
        TypeName listOfSortField = ParameterizedTypeName.get(LIST,
            ParameterizedTypeName.get(SORT_FIELD, WildcardTypeName.subtypeOf(Object.class)));
        TypeName fieldWildcard = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        TypeName listOfFieldWildcard = ParameterizedTypeName.get(LIST, fieldWildcard);
        b.addStatement("$T orderBy = $T.of(sortField.asc(), tieField.asc())", listOfSortField, LIST);
        b.addStatement("$T extraFields = new $T<>($T.<$T>of(sortField, tieField))",
            listOfFieldWildcard, ARRAY_LIST, LIST, fieldWildcard);

        b.addStatement("$T page = $T.pageRequest(first, last, after, before, $L,\n"
            + "        orderBy, extraFields, $T.of())",
            pageRequestClass, connectionHelperClass, defaultPageSize, LIST);

        // Stage 1: per-branch UNION ALL projecting (typename, pk0, sort, idx) plus JOIN parentInput.
        // Wrap as derived table "pages". The same pagesTable reference is shared across every
        // per-parent ConnectionResult; per-parent totalCount filters with __idx__.eq(i).
        TypeName tableWildcard = ParameterizedTypeName.get(TABLE, WildcardTypeName.subtypeOf(Object.class));
        b.add("$T $L =\n", tableWildcard, CONNECTION_PAGES_LOCAL);
        for (int p = 0; p < participants.size(); p++) {
            var participant = participants.get(p);
            String alias = "stage1_" + participant.typeName();
            CodeBlock projection = batchedBranchProjection(participant, alias);
            CodeBlock joinPredicate = batchedBranchJoinPredicate(participant, participantJoinPaths);
            if (p == 0) {
                b.add("    dsl.select($L)\n", projection);
                b.add("        .from($L)\n", alias);
                b.add("        .join(parentInput).on($L)\n", joinPredicate);
            } else {
                b.add("    .unionAll(dsl.select($L)\n", projection);
                b.add("        .from($L)\n", alias);
                b.add("        .join(parentInput).on($L))\n", joinPredicate);
            }
        }
        b.add("    .asTable($S);\n", CONNECTION_PAGES_ALIAS);

        // idxField: shared between the ranked CTE's PARTITION BY and per-parent ConnectionResult
        // condition.
        TypeName idxFieldType = ParameterizedTypeName.get(FIELD, integerClass);
        b.addStatement("$T idxField = $T.field($T.name($S), $T.class)",
            idxFieldType, DSL, DSL, IDX_COLUMN, integerClass);

        // Ranked CTE: ROW_NUMBER() OVER (PARTITION BY __idx__ ORDER BY effectiveOrderBy).
        // The .orderBy/.seek on this CTE filter rows BEFORE ROW_NUMBER is computed (the seek
        // predicate is jOOQ-translated to WHERE), giving correct cursor-driven per-partition
        // pagination.
        b.add("$T<?> ranked = dsl\n", TABLE);
        b.add("    .select(\n");
        b.add("        $T.field($T.name($S), $T.class),\n", DSL, DSL, TYPENAME_COLUMN, stringClass);
        b.add("        $T.field($T.name($S), $T.class),\n", DSL, DSL, PK_COLUMN_PREFIX + 0 + PK_COLUMN_SUFFIX, participantPkClass);
        b.add("        sortField,\n");
        b.add("        idxField,\n");
        b.add("        $T.rowNumber().over($T.partitionBy(idxField).orderBy(page.effectiveOrderBy())).as($S))\n",
            DSL, DSL, RN_COLUMN);
        b.add("    .from($L)\n", CONNECTION_PAGES_LOCAL);
        b.add("    .orderBy(page.effectiveOrderBy())\n");
        b.add("    .seek(page.seekFields())\n");
        b.add("    .asTable($S);\n", "ranked");

        // Outer SELECT: filter rn <= page.limit() — per-partition limit.
        TypeName resultBound = ParameterizedTypeName.get(RESULT, WildcardTypeName.subtypeOf(RECORD));
        b.add("$T stage1 = dsl\n", resultBound);
        b.add("    .select()\n");
        b.add("    .from(ranked)\n");
        b.add("    .where(ranked.field($S, $T.class).le($T.val(page.limit())))\n",
            RN_COLUMN, integerClass, DSL);
        b.add("    .fetch();\n");

        // Bucketize: group by typename, populate parentIdxByOuter parallel array.
        b.addStatement("Object[] result = new Object[stage1.size()]");
        TypeName listOfObjArray = ParameterizedTypeName.get(LIST, ArrayTypeName.of(ClassName.get(Object.class)));
        TypeName byTypeMap = ParameterizedTypeName.get(MAP, stringClass, listOfObjArray);
        b.addStatement("$T byType = new $T<>()", byTypeMap, LINKED_HASH_MAP);
        b.addStatement("int[] parentIdxByOuter = new int[stage1.size()]");
        b.beginControlFlow("for (int outerIdx = 0; outerIdx < stage1.size(); outerIdx++)");
        b.addStatement("$T r = stage1.get(outerIdx)", RECORD);
        b.addStatement("parentIdxByOuter[outerIdx] = r.get($S, $T.class)", IDX_COLUMN, integerClass);
        b.addStatement("String tn = r.get($S, String.class)", TYPENAME_COLUMN);
        b.addStatement("Object[] pks = new Object[]{r.get($S)}", PK_COLUMN_PREFIX + 0 + PK_COLUMN_SUFFIX);
        b.addStatement("byType.computeIfAbsent(tn, k -> new $T<>()).add(new Object[]{outerIdx, pks})", ARRAY_LIST);
        b.endControlFlow();

        // Stage 2: per-typename dispatch — writes typed Records to result[outerIdx].
        for (var participant : participants) {
            String typeName = participant.typeName();
            b.beginControlFlow("if (byType.containsKey($S))", typeName);
            b.addStatement("$L(byType.get($S), env, dsl, result)",
                perTypenameMethodName(fieldName, typeName), typeName);
            b.endControlFlow();
        }

        // Scatter: walk result[] in stage-1 order; each non-null Record's parent_idx routes
        // it to the corresponding bucket. Order preserved within each parent's bucket because
        // stage-1's outer .orderBy(effectiveOrderBy) globally sorts the ranked output and
        // bucketing keeps relative order.
        TypeName listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        TypeName listOfListOfRecord = ParameterizedTypeName.get(LIST, listOfRecord);
        b.addStatement("$T buckets = new $T<>(keys.size())", listOfListOfRecord, ARRAY_LIST);
        b.beginControlFlow("for (int i = 0; i < keys.size(); i++)");
        b.addStatement("buckets.add(new $T<>())", ARRAY_LIST);
        b.endControlFlow();
        b.beginControlFlow("for (int outerIdx = 0; outerIdx < result.length; outerIdx++)");
        b.beginControlFlow("if (result[outerIdx] instanceof $T r)", RECORD);
        b.addStatement("buckets.get(parentIdxByOuter[outerIdx]).add(r)");
        b.endControlFlow();
        b.endControlFlow();

        // Build per-parent ConnectionResults — share pagesTable for totalCount, per-parent
        // condition __idx__ = i so COUNT(*) restricts to that parent's slice of the union.
        b.addStatement("$T out = new $T<>(keys.size())", listOfConnectionResult, ARRAY_LIST);
        b.beginControlFlow("for (int i = 0; i < keys.size(); i++)");
        b.addStatement("out.add(new $T(buckets.get(i), page, $L, idxField.eq(i)))",
            connectionResultClass, CONNECTION_PAGES_LOCAL);
        b.endControlFlow();
        b.addStatement("return out");

        return MethodSpec.methodBuilder(rowsMethodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(listOfConnectionResult)
            .addParameter(keysListType, "keys")
            .addParameter(ENV, "env")
            .addCode(b.build())
            .build();
    }

    /**
     * B4c-2 per-branch projection: same shape as {@link #branchProjection} but additionally
     * projects {@code parentInput.field(0).as(IDX_COLUMN)} so the windowed CTE can partition by
     * the parent index. Single-PK participants only (validator-enforced for connection mode);
     * the {@code __sort__} alias is the participant PK directly.
     */
    private static CodeBlock batchedBranchProjection(ParticipantRef.TableBound participant, String tableAlias) {
        var pks = participant.table().primaryKeyColumns();
        var b = CodeBlock.builder();
        b.add("$T.inline($S).as($S)", DSL, participant.typeName(), TYPENAME_COLUMN);
        b.add(", $L.$L.as($S)", tableAlias, pks.get(0).javaName(), PK_COLUMN_PREFIX + 0 + PK_COLUMN_SUFFIX);
        b.add(", $L.$L.as($S)", tableAlias, pks.get(0).javaName(), SORT_COLUMN);
        b.add(", parentInput.field(0, $T.class).as($S)", ClassName.get(Integer.class), IDX_COLUMN);
        return b.build();
    }

    /**
     * B4c-2 per-branch JOIN predicate: per-slot equality AND-chain
     * {@code <participant>.<slot.targetSide()>.eq(parentInput.field(<slot.sourceSide().sqlName()>, <Type>.class))}
     * across {@code fkJoin.slots()}. Single-hop FK only.
     *
     * <p>Slot orientation at synthesis time means the parent side is always
     * {@code slot.sourceSide()} and the participant side {@code slot.targetSide()}, regardless of
     * which end of the catalog FK each maps to. Direction-blind iteration over slots produces a
     * type-correct AND-chain even when a multi-column FK declares its columns in a different
     * order than the parent's {@code @node(keyColumns: [...])} directive: positional misuse is
     * structurally impossible because no two parallel lists are paired.
     */
    private static CodeBlock batchedBranchJoinPredicate(ParticipantRef.TableBound participant,
            Map<String, List<JoinStep>> participantJoinPaths) {
        var path = participantJoinPaths.get(participant.typeName());
        var fkJoin = (JoinStep.FkJoin) path.get(0);
        String tableAlias = "stage1_" + participant.typeName();
        var b = CodeBlock.builder();
        int i = 0;
        for (var slot : fkJoin.slots()) {
            ColumnRef parentCol = slot.sourceSide();
            ColumnRef participantCol = slot.targetSide();
            ClassName parentColClass = ClassName.bestGuess(parentCol.columnClass());
            if (i == 0) {
                b.add("$L.$L.eq(parentInput.field($S, $T.class))",
                    tableAlias, participantCol.javaName(), parentCol.sqlName(), parentColClass);
            } else {
                b.add(".and($L.$L.eq(parentInput.field($S, $T.class)))",
                    tableAlias, participantCol.javaName(), parentCol.sqlName(), parentColClass);
            }
            i++;
        }
        return b.build();
    }

    /**
     * Stage-2 per-typename SELECT helper: takes the stage-1 binding tuples for one typename,
     * issues the {@code VALUES (idx, pk0, ..., pkN) JOIN <table> ON t.PK = input.pk0 ... ORDER BY idx}
     * SELECT, and scatters each typed Record back into {@code result[idx]}. Inherits the
     * dispatcher-shape {@code .on(...)} (not {@code .using(...)}); see {@code SelectMethodBody}'s
     * class-level Javadoc for the rationale.
     */
    private static MethodSpec buildPerTypenameSelect(
            String fieldName, ParticipantRef.TableBound participant,
            boolean includeSortKey,
            String outputPackage) {
        var jooqTableClass = participant.table().tableClass();
        var typeClass = ClassName.get(outputPackage + ".types", participant.typeName());

        var listOfBindings = ParameterizedTypeName.get(LIST, ArrayTypeName.of(ClassName.get(Object.class)));
        String tableLocal = "t";
        String inputAlias = decap(participant.typeName()) + "Input";
        List<ColumnRef> columns = participant.table().primaryKeyColumns();
        Function<ColumnRef, ColumnRef> columnFn = Function.identity();

        var b = CodeBlock.builder();
        b.addStatement("if (bindings.isEmpty()) return");
        b.addStatement("$T $L = $T.$L", jooqTableClass, tableLocal, participant.table().constantsClass(), participant.table().javaFieldName());

        // Typed Row<N+1>[] declaration delegated to ValuesJoinRowBuilder. The for-loop body
        // unpacks the dispatcher's (idx, pks) binding tuple into row cells.
        ValuesJoinRowBuilder.emitRowArrayDecl(b, columns, columnFn, DIRECTIVE_CONTEXT, "rows", "bindings.size()");
        b.beginControlFlow("for (int i = 0; i < bindings.size(); i++)");
        b.addStatement("Object[] binding = bindings.get(i)");
        b.addStatement("int idx = (int) binding[0]");
        b.addStatement("Object[] cols = (Object[]) binding[1]");
        CodeBlock cells = ValuesJoinRowBuilder.cellsCode(
            columns, columnFn, CodeBlock.of("$T.val(idx, $T.class)", DSL, Integer.class), tableLocal,
            (_, idx) -> CodeBlock.of("cols[$L]", idx));
        b.addStatement("rows[i] = $T.row($L)", DSL, cells);
        b.endControlFlow();

        b.addStatement("$T<?> input = $T.values(rows).as($L)",
            TABLE, DSL, ValuesJoinRowBuilder.aliasArgs(columns, columnFn, inputAlias));

        // Field projection: <TypeName>.$fields(...) plus the synthetic __typename literal so
        // the schema-class TypeResolver routes each row back to its concrete GraphQL type.
        // The parent's flattened DataFetchingFieldSelectionSet is restricted to this
        // participant's SelectedFields via PolymorphicSelectionSet.restrictTo, so the per-typename
        // SELECT projects only columns actually requested for this variant. Unfiltered selection
        // set would over-select shared-name fields from inactive branches (R108).
        var fieldWildcard = ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
        var arrayListOfField = ParameterizedTypeName.get(ARRAY_LIST, fieldWildcard);
        var polymorphicSelectionSet = ClassName.get(
            outputPackage + ".util", PolymorphicSelectionSetClassGenerator.CLASS_NAME);
        b.addStatement("$T fields = new $T($T.$$fields($T.restrictTo(env.getSelectionSet(), $S), $L, env))",
            arrayListOfField, arrayListOfField, typeClass,
            polymorphicSelectionSet, participant.typeName(), tableLocal);
        b.addStatement("fields.add($T.inline($S).as($S))", DSL, participant.typeName(), TYPENAME_COLUMN);

        // Connection mode: project the synthetic {@code __sort__} alias
        // on each typed stage-2 Record so {@code ConnectionHelper.encodeCursor(record,
        // [DSL.field("__sort__")])} can read it back when emitting per-edge cursors and
        // pageInfo.start/endCursor. Single-PK projects the PK column directly (typed by the PK
        // column class); composite-PK projects {@code DSL.jsonbArray(pk0..pkN)} so the value
        // round-trips through PostgreSQL's lexicographic JSONB ordering.
        if (includeSortKey) {
            var pks = participant.table().primaryKeyColumns();
            if (pks.size() == 1) {
                b.addStatement("fields.add($L.$L.as($S))", tableLocal, pks.get(0).javaName(), SORT_COLUMN);
            } else {
                var jsonbArgs = CodeBlock.builder();
                for (int i = 0; i < pks.size(); i++) {
                    if (i > 0) jsonbArgs.add(", ");
                    jsonbArgs.add("$L.$L", tableLocal, pks.get(i).javaName());
                }
                b.addStatement("fields.add($T.jsonbArray($L).as($S))", DSL, jsonbArgs.build(), SORT_COLUMN);
            }
        }

        // idx column from the input derived table — needed both for the projection and the
        // ORDER BY, so materialise once.
        var fieldOfInteger = ParameterizedTypeName.get(FIELD, ClassName.get(Integer.class));
        b.addStatement("$T idxCol = input.field($S, $T.class)", fieldOfInteger, "idx", Integer.class);
        b.addStatement("fields.add(idxCol)");

        // ON predicate: per-PK-slot equality. Mirrors the dispatcher in SelectMethodBody.
        var on = CodeBlock.builder();
        for (int i = 0; i < columns.size(); i++) {
            var col = columns.get(i);
            var colClass = ClassName.bestGuess(col.columnClass());
            if (i == 0) {
                on.add("$L.$L.eq(input.field($S, $T.class))",
                    tableLocal, col.javaName(), col.sqlName(), colClass);
            } else {
                on.add(".and($L.$L.eq(input.field($S, $T.class)))",
                    tableLocal, col.javaName(), col.sqlName(), colClass);
            }
        }
        b.beginControlFlow("for ($T r : dsl.select(fields).from($L).join(input).on($L).orderBy(idxCol).fetch())",
            RECORD, tableLocal, on.build());
        b.addStatement("int outIdx = r.get(idxCol)");
        b.addStatement("result[outIdx] = r");
        b.endControlFlow();

        return MethodSpec.methodBuilder(perTypenameMethodName(fieldName, participant.typeName()))
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addParameter(listOfBindings, "bindings")
            .addParameter(ENV, "env")
            .addParameter(DSL_CONTEXT, "dsl")
            .addParameter(ArrayTypeName.of(ClassName.get(Object.class)), "result")
            .addCode(b.build())
            .build();
    }

    /**
     * Shared {@code parentInput VALUES} table emitter. Materialises the DataLoader keys into a
     * typed {@code Row<N+1><Integer, T1...Tn>[]} array (idx + parent PK), then aliases as
     * {@code parentInput("idx", pk_col_0_sqlName, ..., pk_col_N-1_sqlName)}. Used by every
     * batched-rows method (list arm and connection arm) so the JOIN parentInput predicate has a
     * consistent shape.
     */
    private static CodeBlock buildParentInputValuesEmitter(
            List<ColumnRef> parentPkCols, TypeName keyType) {
        var integerClass = ClassName.get(Integer.class);
        int parentKeyArity = parentPkCols.size();
        int parentRowArity = parentKeyArity + 1;
        TypeName[] parentRowTypeArgs = new TypeName[parentRowArity];
        parentRowTypeArgs[0] = integerClass;
        for (int i = 0; i < parentKeyArity; i++) {
            parentRowTypeArgs[i + 1] = ClassName.bestGuess(parentPkCols.get(i).columnClass());
        }
        TypeName parentRowType = ParameterizedTypeName.get(rowClass(parentRowArity), parentRowTypeArgs);
        TypeName parentRecordType = ParameterizedTypeName.get(recordClass(parentRowArity), parentRowTypeArgs);
        TypeName parentInputTableType = ParameterizedTypeName.get(TABLE, parentRecordType);

        var b = CodeBlock.builder();
        b.add("@$T({$S, $S})\n", ClassName.get("java.lang", "SuppressWarnings"), "unchecked", "rawtypes");
        b.addStatement("$T[] parentRows = ($T[]) new $T[keys.size()]",
            parentRowType, parentRowType, rowClass(parentRowArity));
        b.beginControlFlow("for (int i = 0; i < keys.size(); i++)");
        b.addStatement("$T k = keys.get(i)", keyType);
        var parentRowArgs = CodeBlock.builder();
        parentRowArgs.add("$T.inline(i)", DSL);
        for (int i = 0; i < parentKeyArity; i++) {
            parentRowArgs.add(", k.field$L()", i + 1);
        }
        b.addStatement("parentRows[i] = $T.row($L)", DSL, parentRowArgs.build());
        b.endControlFlow();

        var parentInputAliasArgs = CodeBlock.builder();
        parentInputAliasArgs.add("$S, $S", "parentInput", "idx");
        for (var col : parentPkCols) {
            parentInputAliasArgs.add(", $S", col.sqlName());
        }
        b.addStatement("$T parentInput = $T.values(parentRows).as($L)",
            parentInputTableType, DSL, parentInputAliasArgs.build());
        return b.build();
    }

    /**
     * Batched child-list rows method: issues ONE SQL statement covering every parent in the
     * DataLoader batch and returns {@code List<List<Record>>} indexed 1:1 with the keys list.
     * Same SQL shape as {@link #buildBatchedConnectionRowsMethod} minus the windowed CTE: stage
     * 1 unions per-participant {@code SELECT ... JOIN parentInput} branches projecting
     * {@code (typename, pk0..pkN, __idx__)}; stage 2 dispatches per typename and scatters typed
     * Records into per-parent buckets via {@code __idx__}.
     */
    private static MethodSpec buildBatchedListRowsMethod(
            TypeFetcherEmissionContext ctx,
            String fieldName, List<ParticipantRef.TableBound> participants,
            Map<String, List<JoinStep>> participantJoinPaths,
            SourceKey parentSourceKey,
            String outputPackage) {

        var integerClass = ClassName.get(Integer.class);
        var stringClass = ClassName.get(String.class);
        String rowsMethodName = "rows" + cap(fieldName);

        var parentPkCols = parentSourceKey.columns();
        TypeName keyType = parentSourceKey.keyElementType();
        TypeName keysListType = ParameterizedTypeName.get(LIST, keyType);
        TypeName listOfRecord = ParameterizedTypeName.get(LIST, RECORD);
        TypeName listOfListOfRecord = ParameterizedTypeName.get(LIST, listOfRecord);

        // Participant PK arity (uniform across participants — validator enforces). List form
        // doesn't constrain to single-column the way connection does, so iterate per slot.
        int participantPkArity = participants.get(0).table().primaryKeyColumns().size();

        var b = CodeBlock.builder();

        b.beginControlFlow("if (keys.isEmpty())");
        b.addStatement("return $T.of()", LIST);
        b.endControlFlow();

        b.addStatement("$T dsl = $L.getDslContext(env)", DSL_CONTEXT, ctx.graphitronContextCall());

        for (var participant : participants) {
            var jooqTableClass = participant.table().tableClass();
            String alias = "stage1_" + participant.typeName();
            b.addStatement("$T $L = $T.$L", jooqTableClass, alias,
                participant.table().constantsClass(), participant.table().javaFieldName());
        }

        b.add(buildParentInputValuesEmitter(parentPkCols, keyType));

        // Stage 1: per-branch UNION ALL projecting (typename, pk0..pkN, __idx__) plus
        // JOIN parentInput. No windowed CTE — list arm has no per-parent pagination.
        TypeName resultBound = ParameterizedTypeName.get(RESULT, WildcardTypeName.subtypeOf(RECORD));
        b.add("$T stage1 = ", resultBound);
        for (int p = 0; p < participants.size(); p++) {
            var participant = participants.get(p);
            String alias = "stage1_" + participant.typeName();
            CodeBlock projection = batchedListBranchProjection(participant, alias);
            CodeBlock joinPredicate = batchedBranchJoinPredicate(participant, participantJoinPaths);
            if (p == 0) {
                b.add("dsl.select($L)\n", projection);
                b.add("    .from($L)\n", alias);
                b.add("    .join(parentInput).on($L)\n", joinPredicate);
            } else {
                b.add("    .unionAll(dsl.select($L)\n", projection);
                b.add("        .from($L)\n", alias);
                b.add("        .join(parentInput).on($L))\n", joinPredicate);
            }
        }
        b.add("    .fetch();\n");

        // idx slot — used both for bucketing and per-typename dispatch carrying.
        TypeName idxFieldType = ParameterizedTypeName.get(FIELD, integerClass);
        b.addStatement("$T idxField = $T.field($T.name($S), $T.class)",
            idxFieldType, DSL, DSL, IDX_COLUMN, integerClass);

        // Bucketize: byType + parentIdxByOuter parallel array (mirrors connection-rows scatter).
        b.addStatement("Object[] result = new Object[stage1.size()]");
        TypeName listOfObjArray = ParameterizedTypeName.get(LIST, ArrayTypeName.of(ClassName.get(Object.class)));
        TypeName byTypeMap = ParameterizedTypeName.get(MAP, stringClass, listOfObjArray);
        b.addStatement("$T byType = new $T<>()", byTypeMap, LINKED_HASH_MAP);
        b.addStatement("int[] parentIdxByOuter = new int[stage1.size()]");
        b.beginControlFlow("for (int outerIdx = 0; outerIdx < stage1.size(); outerIdx++)");
        b.addStatement("$T r = stage1.get(outerIdx)", RECORD);
        b.addStatement("parentIdxByOuter[outerIdx] = r.get(idxField)");
        b.addStatement("String tn = r.get($S, String.class)", TYPENAME_COLUMN);
        var pksBuilder = CodeBlock.builder().add("new Object[]{");
        for (int s = 0; s < participantPkArity; s++) {
            if (s > 0) pksBuilder.add(", ");
            pksBuilder.add("r.get($S)", PK_COLUMN_PREFIX + s + PK_COLUMN_SUFFIX);
        }
        pksBuilder.add("}");
        b.addStatement("Object[] pks = $L", pksBuilder.build());
        b.addStatement("byType.computeIfAbsent(tn, k -> new $T<>()).add(new Object[]{outerIdx, pks})", ARRAY_LIST);
        b.endControlFlow();

        // Stage 2: per-typename dispatch — writes typed Records to result[outerIdx].
        for (var participant : participants) {
            String typeName = participant.typeName();
            b.beginControlFlow("if (byType.containsKey($S))", typeName);
            b.addStatement("$L(byType.get($S), env, dsl, result)",
                perTypenameMethodName(fieldName, typeName), typeName);
            b.endControlFlow();
        }

        // Scatter into per-parent buckets.
        b.addStatement("$T buckets = new $T<>(keys.size())", listOfListOfRecord, ARRAY_LIST);
        b.beginControlFlow("for (int i = 0; i < keys.size(); i++)");
        b.addStatement("buckets.add(new $T<>())", ARRAY_LIST);
        b.endControlFlow();
        b.beginControlFlow("for (int outerIdx = 0; outerIdx < result.length; outerIdx++)");
        b.beginControlFlow("if (result[outerIdx] instanceof $T r)", RECORD);
        b.addStatement("buckets.get(parentIdxByOuter[outerIdx]).add(r)");
        b.endControlFlow();
        b.endControlFlow();
        b.addStatement("return buckets");

        return MethodSpec.methodBuilder(rowsMethodName)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(listOfListOfRecord)
            .addParameter(keysListType, "keys")
            .addParameter(ENV, "env")
            .addCode(b.build())
            .build();
    }

    /**
     * List-arm per-branch projection: typename literal + ALL participant PK columns
     * ({@code __pk0__..__pkN__}) + {@code __idx__}. Differs from the connection-arm projection
     * ({@link #batchedBranchProjection}) by projecting the full PK arity (not just the first
     * column) and by skipping {@code __sort__} (no cursor encoding on the list arm).
     */
    private static CodeBlock batchedListBranchProjection(ParticipantRef.TableBound participant, String tableAlias) {
        var pks = participant.table().primaryKeyColumns();
        var b = CodeBlock.builder();
        b.add("$T.inline($S).as($S)", DSL, participant.typeName(), TYPENAME_COLUMN);
        for (int s = 0; s < pks.size(); s++) {
            b.add(", $L.$L.as($S)", tableAlias, pks.get(s).javaName(), PK_COLUMN_PREFIX + s + PK_COLUMN_SUFFIX);
        }
        b.add(", parentInput.field(0, $T.class).as($S)", ClassName.get(Integer.class), IDX_COLUMN);
        return b.build();
    }

    private static String perTypenameMethodName(String fieldName, String typeName) {
        return "select" + typeName + "For" + cap(fieldName);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String decap(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    // -- Mirror of TypeFetcherGenerator's private helpers; copied to keep this emitter
    // -- self-contained while we tune the cross-class coupling. If a third consumer
    // -- emerges, lift these to GeneratorUtils.

    private static TypeName syncResultType(TypeName valueType) {
        return ParameterizedTypeName.get(
            ClassName.get("graphql.execution", "DataFetcherResult"),
            valueType.isPrimitive() ? valueType.box() : valueType);
    }

    private static CodeBlock returnSyncSuccess(TypeName valueType, String payloadLocal) {
        TypeName boxed = valueType.isPrimitive() ? valueType.box() : valueType;
        return CodeBlock.of("return $T.<$T>newResult().data($L).build();\n",
            ClassName.get("graphql.execution", "DataFetcherResult"), boxed, payloadLocal);
    }

    private static CodeBlock noChannelCatchArm(String outputPackage) {
        var errorRouter = ClassName.get(
            outputPackage + ".schema",
            no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator.CLASS_NAME);
        // Route through surfaceClientErrorOrRedact so a GraphitronClientException (e.g. a
        // malformed/wrong-type @nodeId filter id) surfaces its real message while internal faults
        // still redact (R378); uniform with TypeFetcherGenerator's no-channel arm.
        return CodeBlock.of("return $T.surfaceClientErrorOrRedact(e, env);\n", errorRouter);
    }

    /** {@code CompletableFuture<DataFetcherResult<P>>}; primitives box. Mirror of TypeFetcherGenerator's helper. */
    private static TypeName asyncResultType(TypeName valueType) {
        return ParameterizedTypeName.get(COMPLETABLE_FUTURE, syncResultType(valueType));
    }

    /**
     * Async tail for fetchers whose body ends with {@code loader.load(key, env)}: lifts the
     * payload into a {@code DataFetcherResult<P>} via {@code .thenApply(...)} and routes any
     * escape via {@code .exceptionally(t -> ErrorRouter.redact(...))}. Mirrors
     * {@code TypeFetcherGenerator.asyncWrapTail} for the no-error-channel path
     * ({@link no.sikt.graphitron.rewrite.model.ChildField.InterfaceField} /
     * {@link no.sikt.graphitron.rewrite.model.ChildField.UnionField} do not implement
     * {@code WithErrorChannel}).
     */
    private static CodeBlock asyncWrapTail(TypeName valueType, String outputPackage) {
        var errorRouter = ClassName.get(
            outputPackage + ".schema",
            no.sikt.graphitron.rewrite.generators.schema.ErrorRouterClassGenerator.CLASS_NAME);
        TypeName boxed = valueType.isPrimitive() ? valueType.box() : valueType;
        return CodeBlock.builder()
            .add(".thenApply(payload -> $T.<$T>newResult().data(payload).build())\n",
                DATA_FETCHER_RESULT, boxed)
            .add(".exceptionally(t -> $T.redact(t, env))", errorRouter)
            .build();
    }

}
