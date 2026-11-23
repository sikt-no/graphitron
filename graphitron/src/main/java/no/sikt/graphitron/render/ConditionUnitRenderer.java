package no.sikt.graphitron.render;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.command.SessionAccessors;
import no.sikt.graphitron.command.UnitMethodRef;
import no.sikt.graphitron.command.UnitRef;
import no.sikt.graphitron.emit.BuildQuery;
import no.sikt.graphitron.emit.ConditionEmitter;
import no.sikt.graphitron.emit.ConditionQuery;
import no.sikt.graphitron.emit.FillEmitter;
import no.sikt.graphitron.emit.NodeIdDecodeEmitter;
import no.sikt.graphitron.emit.NodeIdDecodeQuery;
import no.sikt.graphitron.model.read.StoreHandle;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code <Parent>Conditions} classes rendered from the store: one method per field and table a
 * condition filters, at the address {@link Addresses} mints for the field, the participant's
 * where the table is the rows of one participant told apart by its table, and beside a faceted
 * field's method its base and each facet's own filter. Every WHERE consumer calls these by the same
 * address and signature ({@code ConditionGlueCall}): the table, the argument map, and the environment
 * or the executing context where a call reads the request context or the session.
 *
 * <p>Each method is {@link ConditionEmitter}'s for the store's row; a row with nothing to filter is
 * {@code DSL.noCondition()}, its callers calling it all the same. A row the emitter does not emit is a
 * shape the store refuses, so meeting one here is a generator bug naming the codes that refuse it.
 * The method is per field while the store's rows are per occurrence: a field reached under two
 * parents is one method, and its occurrences render it alike, since every fact a condition is
 * rendered from is the field declaration's and its target table's, never the parent's, and the
 * session it asks for is asked for by the field ({@code ConditionNestedSessionTest}).
 */
public final class ConditionUnitRenderer {

    private ConditionUnitRenderer() {}

    private static final ClassName CONDITION = ClassName.get("org.jooq", "Condition");
    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final TypeName ARGS_MAP = ParameterizedTypeName.get(ClassName.get(Map.class),
        ClassName.get(String.class), ClassName.get(Object.class));

    /** One conditions class, at the address its callers name it by. */
    public record Unit(UnitRef ref, TypeSpec spec) {}

    /**
     * Where a conditions class and its methods are, as the plan's addresses mint them, so the class
     * is found by the name every caller already calls it by.
     */
    public interface Addresses {
        UnitRef conditions(String parentType);
        UnitMethodRef conditionMethod(String parentType, String fieldName);
        UnitMethodRef participantConditionMethod(String parentType, String fieldName, String participantType);
        UnitMethodRef facetBaseConditionMethod(String parentType, String fieldName);
        UnitMethodRef facetConditionMethod(String parentType, String fieldName, String facet);
    }

    /** Renders one conditions class per type whose fields are filtered, in the store's row order. */
    public static List<Unit> render(StoreHandle store, String outputPackage, Addresses units) {
        var rows = ConditionQuery.read(store);
        var decodes = NodeIdDecodeQuery.read(store);
        var builds = BuildQuery.read(store);
        var byOwner = new LinkedHashMap<UnitRef, List<ConditionQuery.Row>>();
        for (var row : rows) {
            byOwner.computeIfAbsent(units.conditions(row.parentType()), k -> new ArrayList<>()).add(row);
        }
        var out = new ArrayList<Unit>(byOwner.size());
        for (var entry : byOwner.entrySet()) {
            var type = TypeSpec.classBuilder(entry.getKey().simpleName()).addModifiers(Modifier.PUBLIC);
            RequestContextHelper.collectInto(type, outputPackage, context -> {
                var runtime = runtime(outputPackage, context);
                var aliases = TableAliasFragments.tableAliasesClass(outputPackage);
                var rendered = new LinkedHashMap<String, Rendered>();
                for (var row : entry.getValue()) {
                    var ref = row.participantType() == null
                        ? units.conditionMethod(row.parentType(), row.fieldName())
                        : units.participantConditionMethod(row.parentType(), row.fieldName(), row.participantType());
                    add(rendered, ref, row, row, decodes, runtime, aliases, builds);
                    if (row.participantType() == null) {
                        if (!row.facets().isEmpty()) {
                            add(rendered, units.facetBaseConditionMethod(row.parentType(), row.fieldName()), row,
                                row.facetBase(), decodes, runtime, aliases, builds);
                        }
                        for (var facet : row.facets()) {
                            add(rendered, units.facetConditionMethod(row.parentType(), row.fieldName(), facet), row,
                                row.facetOwn(facet), decodes, runtime, aliases, builds);
                        }
                    }
                }
                rendered.values().forEach(r -> {
                    type.addMethod(r.method());
                    r.helpers().forEach(type::addMethod);
                });
            });
            out.add(new Unit(entry.getKey(), type.build()));
        }
        return out;
    }

    /** One method as rendered, and the occurrence it was rendered for first. */
    private record Rendered(String coordinate, MethodSpec method, List<MethodSpec> helpers) {}

    private static void add(Map<String, Rendered> rendered, UnitMethodRef ref, ConditionQuery.Row occurrence,
                            ConditionQuery.Row row, NodeIdDecodeQuery decodes, FillEmitter.Runtime runtime,
                            ClassName aliases, Map<BuildQuery.Root, BuildQuery.Result> builds) {
        var result = ConditionEmitter.emit(row, ref.methodName(), decodes, runtime, aliases, builds);
        var now = switch (result) {
            case ConditionEmitter.Result.Emitted emitted ->
                new Rendered(occurrence.coordinate(), emitted.method(), emitted.helpers());
            case ConditionEmitter.Result.Nothing ignored ->
                new Rendered(occurrence.coordinate(), nothing(ref.methodName(), row), List.of());
            case ConditionEmitter.Result.NotYet notYet -> throw new IllegalStateException(
                "Graphitron generator bug: the condition of " + occurrence.coordinate() + " on "
                + row.table().sqlName() + " is not emitted (" + notYet.reason() + " at " + notYet.term()
                + "): the store accepted the graph, where " + String.join(", ", notYet.reason().refusedBy())
                + " should have refused it");
        };
        var before = rendered.putIfAbsent(ref.methodName(), now);
        if (before != null && !before.method().equals(now.method())) {
            throw new IllegalStateException("Graphitron generator bug: " + before.coordinate() + " and "
                + now.coordinate() + " are occurrences of one field whose conditions render differently, where"
                + " every fact a condition is rendered from is the field declaration's and its target table's,"
                + " and the field's condition is one method, " + ref.methodName());
        }
    }

    /** A filtered field whose occurrence has nothing to compare: its callers call it all the same. */
    private static MethodSpec nothing(String name, ConditionQuery.Row row) {
        var method = MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(CONDITION)
            .addParameter(ClassName.bestGuess(row.table().tableClassName()), "table")
            .addParameter(ARGS_MAP, "args");
        if (row.takesEnv()) {
            method.addParameter(ClassName.get("graphql.schema", "DataFetchingEnvironment"), "env");
        }
        if (row.takesDsl()) {
            method.addParameter(ClassName.get("org.jooq", "DSLContext"), "dsl");
        }
        return method.addStatement("return $T.noCondition()", DSL).build();
    }

    /** The generated classes a condition reads a context key, the session and a node id's decode through. */
    private static FillEmitter.Runtime runtime(String outputPackage, RequestContextHelper context) {
        var ownedConnections = ClassName.get(outputPackage + ".schema", "OwnedConnections");
        return new FillEmitter.Runtime(
            key -> CodeBlock.of("$L.getContextArgument(env, $S)", context.call(), key),
            coordinate -> CodeBlock.of("$T.sessionHandle(dsl, $S)", ownedConnections, coordinate),
            (member, coordinate) -> CodeBlock.of("$T.$L(dsl, $S)", ownedConnections,
                SessionAccessors.memberMethod(member), coordinate),
            new NodeIdDecodeEmitter.Runtime(NodeIdEncoderRef.of(outputPackage), outputPackage));
    }
}
