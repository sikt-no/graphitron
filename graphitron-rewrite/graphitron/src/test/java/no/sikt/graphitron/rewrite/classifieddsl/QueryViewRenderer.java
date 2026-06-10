package no.sikt.graphitron.rewrite.classifieddsl;

import graphql.analysis.QueryTraverser;
import graphql.analysis.QueryVisitorFieldEnvironment;
import graphql.analysis.QueryVisitorStub;
import graphql.language.AstPrinter;
import graphql.language.Directive;
import graphql.language.Document;
import graphql.language.FieldDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeDefinition;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.TestSchemaHelper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R281's query-as-view renderer (Spec §"Rendering: queries as views over the corpus"). Prose embeds a
 * GraphQL <em>query</em> (or a fragment {@code on Type}) naming the coordinates it wants to show; the
 * renderer resolves that selection against the assembled corpus schema and regenerates minimal SDL for
 * the touched closure. One mechanism does the three jobs the Spec asks for at once:
 *
 * <ol>
 *   <li><b>Import what's relevant</b>: the query's selection set is the projection, reusing GraphQL's
 *       own selection mechanism (graphql-java's {@code QueryTraverser}) instead of bespoke
 *       include-tags.</li>
 *   <li><b>Strip the test directives</b>: regeneration emits only real schema, so {@code @classified}
 *       / {@code @classifiedType} are simply never printed, while real Graphitron directives
 *       ({@code @table}, {@code @field}, {@code @asConnection}, ...) survive.</li>
 *   <li><b>Bound the snippet</b>: each touched type is pruned to exactly the selected fields, so a
 *       sibling field or type the query did not name is not dragged in.</li>
 * </ol>
 *
 * <p>The selection-and-prune technique follows {@code no.sikt.fs.app.util.GraphQLSubsetter}
 * (the <em>graphql-scissors</em> library): walk the query with {@code QueryTraverser} to collect the
 * touched fields per parent, then keep only those fields. Where scissors strips <em>all</em> directives
 * and re-assembles through {@code makeExecutableSchema} + {@code SchemaPrinter}, this renderer prints
 * the pruned <em>AST</em> directly ({@link AstPrinter}). That keeps real directives without forcing the
 * whole directive-definition and enum vocabulary into the pruned subset, and prints the <em>authored</em>
 * SDL from the parsed registry (so {@code @asConnection} renders as written rather than as the
 * generated {@code *Connection} wrapper the schema transform produces).
 *
 * <p><strong>Closure note.</strong> The projection is the query's selected field-container reachability:
 * scalars, enums, and input-argument types referenced by a kept field are not expanded (they are not
 * field containers the query descends into). Generated types (the {@code @asConnection} {@code *Connection}
 * / {@code *Edge} wrappers) are likewise not expanded, the authored field that produces them prints
 * instead. R279's fuller "node + parent + target + participant directives" closure is the consuming doc
 * slice's concern; the Spec scopes this to "cheap insurance, not a hard gate."
 */
public final class QueryViewRenderer {

    private QueryViewRenderer() {}

    private static final Set<String> INTERNAL_DIRECTIVES =
        Set.of(ClassifiedDsl.CLASSIFIED, ClassifiedDsl.CLASSIFIED_TYPE);

    /** Renders the SDL closure the {@code selection} (a query or fragment document) touches over {@code fixtureSdl}. */
    public static String render(String fixtureSdl, String selection) {
        String full = ClassifiedDsl.PRELUDE + "\n" + fixtureSdl;
        TypeDefinitionRegistry registry = TestSchemaHelper.parseRegistryWithPrelude(full);
        GraphQLSchema schema = GraphitronSchemaBuilder.buildBundle(registry, TestConfiguration.testContext()).assembled();

        Document doc = new Parser().parseDocument(selection);
        Map<String, Set<String>> fieldsByParent = touchedFieldsByParent(schema, doc);

        var sb = new StringBuilder();
        for (var entry : fieldsByParent.entrySet()) {
            registry.getType(entry.getKey()).ifPresent(def -> {
                String printed = AstPrinter.printAst(prune(def, entry.getValue())).strip();
                if (!printed.isEmpty()) sb.append(printed).append("\n\n");
            });
        }
        return sb.toString().strip();
    }

    /** Walks the query against the assembled schema, recording the touched field names per parent type. */
    private static Map<String, Set<String>> touchedFieldsByParent(GraphQLSchema schema, Document doc) {
        Map<String, Set<String>> fieldsByParent = new LinkedHashMap<>();
        QueryTraverser.newQueryTraverser()
            .schema(schema)
            .document(doc)
            .variables(Collections.emptyMap())
            .build()
            .visitPreOrder(new QueryVisitorStub() {
                @Override
                public void visitField(QueryVisitorFieldEnvironment env) {
                    fieldsByParent
                        .computeIfAbsent(env.getFieldsContainer().getName(), k -> new LinkedHashSet<>())
                        .add(env.getFieldDefinition().getName());
                }
            });
        return fieldsByParent;
    }

    /** Keeps only the touched fields of {@code def} and strips the internal directives from what remains. */
    private static TypeDefinition<?> prune(TypeDefinition<?> def, Set<String> keep) {
        return switch (def) {
            case ObjectTypeDefinition o -> o.transform(b -> b
                .fieldDefinitions(keptFields(o.getFieldDefinitions(), keep))
                .directives(realDirectives(o.getDirectives())));
            case InterfaceTypeDefinition i -> i.transform(b -> b
                .definitions(keptFields(i.getFieldDefinitions(), keep))
                .directives(realDirectives(i.getDirectives())));
            default -> def;
        };
    }

    private static List<FieldDefinition> keptFields(List<FieldDefinition> fields, Set<String> keep) {
        return fields.stream()
            .filter(f -> keep.contains(f.getName()))
            .map(f -> f.transform(b -> b.directives(realDirectives(f.getDirectives()))))
            .toList();
    }

    private static List<Directive> realDirectives(List<Directive> directives) {
        return directives.stream().filter(d -> !INTERNAL_DIRECTIVES.contains(d.getName())).toList();
    }
}
