package no.sikt.graphitron.rewrite.classifieddsl;

import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.parser.Parser;
import graphql.schema.GraphQLCompositeType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.idl.SchemaPrinter;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.TestSchemaHelper;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * R281 slice 1 <em>prototype</em> of the query-as-view renderer (Spec §"Rendering: queries as views
 * over the corpus"). Prose embeds a GraphQL <em>query</em> (or a fragment {@code on Type}) naming the
 * coordinates it wants to show; the renderer resolves that selection against the assembled corpus
 * schema and regenerates SDL for the touched closure. One mechanism does the three jobs the Spec asks
 * for at once:
 *
 * <ol>
 *   <li><b>Import what's relevant</b>: the query's selection set is the projection, reusing GraphQL's
 *       own selection mechanism instead of bespoke include-tags.</li>
 *   <li><b>Strip the test directives</b>: regeneration emits only real schema, so {@code @classified}
 *       / {@code @classifiedType} are simply never printed, the render-strip falls out of
 *       regeneration rather than being a separate filter.</li>
 *   <li><b>Bound the snippet</b>: only the touched types are printed.</li>
 * </ol>
 *
 * <p><strong>Prototype scope.</strong> The projection is type-granular (each touched type prints in
 * full via {@link SchemaPrinter}, not field-pruned) and the closure is the query's selected
 * return-type reachability, not yet R279's full "node + parent + target + participant directives"
 * closure. That fuller closure is the consuming doc slice's concern; the Spec scopes this to "cheap
 * insurance, not a hard gate."
 */
public final class QueryViewRenderer {

    private QueryViewRenderer() {}

    private static final Set<String> INTERNAL_DIRECTIVES =
        Set.of(ClassifiedDsl.CLASSIFIED, ClassifiedDsl.CLASSIFIED_TYPE);

    private static final Set<String> BUILTIN_SCALARS =
        Set.of("String", "Int", "Float", "Boolean", "ID");

    /** Renders the SDL closure the {@code selection} (a query or fragment document) touches over {@code fixtureSdl}. */
    public static String render(String fixtureSdl, String selection) {
        String full = ClassifiedDsl.PRELUDE + "\n" + fixtureSdl;
        TypeDefinitionRegistry registry = TestSchemaHelper.parseRegistryWithPrelude(full);
        GraphQLSchema schema = GraphitronSchemaBuilder.buildBundle(registry, TestConfiguration.testContext()).assembled();

        Document doc = new Parser().parseDocument(selection);
        Map<String, FragmentDefinition> fragments = new HashMap<>();
        for (var def : doc.getDefinitions()) {
            if (def instanceof FragmentDefinition fd) fragments.put(fd.getName(), fd);
        }

        Set<String> touched = new LinkedHashSet<>();
        for (var def : doc.getDefinitions()) {
            switch (def) {
                case OperationDefinition op -> {
                    GraphQLObjectType root = switch (op.getOperation()) {
                        case MUTATION -> schema.getMutationType();
                        case SUBSCRIPTION -> schema.getSubscriptionType();
                        case QUERY -> schema.getQueryType();
                    };
                    if (root != null) {
                        touched.add(root.getName());
                        collect(schema, root, op.getSelectionSet(), fragments, touched);
                    }
                }
                case FragmentDefinition fd -> {
                    // The type-display case: a fragment whose `on Type` names a type directly.
                    if (schema.getType(fd.getTypeCondition().getName()) instanceof GraphQLCompositeType ct) {
                        touched.add(((GraphQLNamedType) ct).getName());
                        collect(schema, ct, fd.getSelectionSet(), fragments, touched);
                    }
                }
                default -> { }
            }
        }
        return printProjection(schema, touched);
    }

    private static void collect(GraphQLSchema schema, GraphQLCompositeType parent, SelectionSet ss,
                                Map<String, FragmentDefinition> fragments, Set<String> touched) {
        if (ss == null || parent == null) return;
        for (Selection<?> sel : ss.getSelections()) {
            switch (sel) {
                case Field f -> {
                    GraphQLFieldDefinition fd = fieldDef(parent, f.getName());
                    if (fd == null) continue;
                    GraphQLType named = GraphQLTypeUtil.unwrapAll(fd.getType());
                    if (named instanceof GraphQLNamedType nt) touched.add(nt.getName());
                    if (named instanceof GraphQLCompositeType ct) {
                        collect(schema, ct, f.getSelectionSet(), fragments, touched);
                    }
                }
                case InlineFragment inf -> {
                    GraphQLCompositeType on = inf.getTypeCondition() == null ? parent
                        : asComposite(schema.getType(inf.getTypeCondition().getName()));
                    if (on instanceof GraphQLNamedType nt) touched.add(nt.getName());
                    collect(schema, on, inf.getSelectionSet(), fragments, touched);
                }
                case FragmentSpread fs -> {
                    FragmentDefinition fd = fragments.get(fs.getName());
                    if (fd == null) continue;
                    GraphQLCompositeType on = asComposite(schema.getType(fd.getTypeCondition().getName()));
                    if (on instanceof GraphQLNamedType nt) touched.add(nt.getName());
                    collect(schema, on, fd.getSelectionSet(), fragments, touched);
                }
                default -> { }
            }
        }
    }

    private static GraphQLFieldDefinition fieldDef(GraphQLCompositeType parent, String name) {
        return switch (parent) {
            case GraphQLObjectType o -> o.getFieldDefinition(name);
            case GraphQLInterfaceType i -> i.getFieldDefinition(name);
            default -> null; // union: descended through inline fragments instead
        };
    }

    private static GraphQLCompositeType asComposite(GraphQLType t) {
        return t instanceof GraphQLCompositeType ct ? ct : null;
    }

    private static String printProjection(GraphQLSchema schema, Set<String> touched) {
        var options = SchemaPrinter.Options.defaultOptions()
            .includeSchemaDefinition(false)
            .includeDirectiveDefinitions(false)
            .includeDirectives(name -> !INTERNAL_DIRECTIVES.contains(name));
        var printer = new SchemaPrinter(options);

        var sb = new StringBuilder();
        for (String name : touched) {
            GraphQLType t = schema.getType(name);
            if (t == null) continue;
            if (t instanceof GraphQLScalarType && BUILTIN_SCALARS.contains(name)) continue;
            String printed = printer.print(t).strip();
            if (!printed.isEmpty()) sb.append(printed).append("\n\n");
        }
        return sb.toString().strip();
    }
}
