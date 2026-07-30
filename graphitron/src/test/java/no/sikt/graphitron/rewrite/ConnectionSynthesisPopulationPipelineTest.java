package no.sikt.graphitron.rewrite;

import graphql.language.FieldDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.Type;
import graphql.language.TypeDefinition;
import graphql.language.TypeName;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.model.ConnectionSynthesis;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The connection-synthesis relation's population pin. Three legs over one fixture that carries
 * every carrier form (derived-name directive-driven, named directive-driven with facets, and
 * structural):
 *
 * <ol>
 *   <li><b>Key set.</b> The relation's key set equals the model-derived carrier predicate over
 *       the fixture SDL: directive-driven bare-list carriers ({@code @asConnection} on a bare
 *       list) union structural connection-typed returns (a declared type matching the
 *       {@code edges} to {@code node} pattern). Derived from the pre-assembly SDL because the
 *       rebuilt assembled schema has already retyped the directive carriers. The predicate walks
 *       every object definition; the fixture keeps every type reachable, matching the walk's
 *       reachable surface.</li>
 *   <li><b>Minted-name closure, both legs.</b> Registry leg: every minted name's registry entry
 *       is an instance of the arm the row declares. The demote carve-out is stated here, not
 *       discovered: a minted name colliding with an SDL declaration demotes to
 *       {@code UnclassifiedType} with its own diagnostic, so a declared/actual pairing mismatch
 *       in an <em>accepted</em> fixture is a producer bug, while a rejected schema surfaces the
 *       demote's diagnostic instead of this pin. Assembled leg: every absent-from-assembled
 *       minted name is present on the rebuilt assembled schema, and every rewriting
 *       directive-driven carrier is retyped to its Connection with {@code first} / {@code after}
 *       appended (the registry-versus-assembled delta the projection-snapshot differential is
 *       blind to).</li>
 *   <li><b>Non-vacuity witness.</b> The fixture population is non-empty and names a known row,
 *       so an empty relation can never compile green through legs one and two.</li>
 * </ol>
 *
 * <p>Subsumes the retired assembled-delta pin (its registry-agrees-with-assembled lesson is leg
 * two; its carrier-retyping lesson is the assembled leg's carrier check).
 */
@PipelineTier
class ConnectionSynthesisPopulationPipelineTest {

    private static final String FIXTURE = """
        type Film @table(name: "film") { id: ID title: String }
        input FilmFilter {
            title: [String!] @field(name: "title") @asFacet
        }
        type FilmsConnection {
            edges: [FilmsEdge!]!
            nodes: [Film!]!
            pageInfo: PageInfo!
        }
        type FilmsEdge { cursor: String! node: Film! }
        type PageInfo {
            hasNextPage: Boolean!
            hasPreviousPage: Boolean!
            startCursor: String
            endCursor: String
        }
        type Query {
            films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
            legacyFilms(filter: FilmFilter): [Film!]!
                @asConnection(connectionName: "LegacyFilmsConnection")
                @defaultOrder(primaryKey: true)
            structuralFilms: FilmsConnection! @defaultOrder(primaryKey: true)
        }
        """;

    @Test
    void relationKeySetEqualsTheModelDerivedCarrierPredicate() {
        var bundle = TestSchemaHelper.buildBundle(FIXTURE);
        var relation = bundle.model().connectionSynthesis();

        assertThat(relation.rows().keySet())
            .containsExactlyInAnyOrderElementsOf(
                carrierPredicate(TestSchemaHelper.parseRegistryWithPrelude(FIXTURE)));

        // Non-vacuity witness: the population is non-empty and names a known row.
        assertThat(relation.rows()).isNotEmpty();
        assertThat(relation.row("Query", "films"))
            .isInstanceOf(ConnectionSynthesis.DirectiveDriven.class);
        assertThat(relation.row("Query", "structuralFilms"))
            .isInstanceOf(ConnectionSynthesis.Structural.class);
    }

    @Test
    void mintedNameClosure_registryAndAssembledLegsAgree() {
        var bundle = TestSchemaHelper.buildBundle(FIXTURE);
        var relation = bundle.model().connectionSynthesis();
        var assembled = bundle.assembled();

        // Registry leg: every minted name (per-row and schema-grain) pairs its declared arm with
        // the registry's actual entry. See the class javadoc for the demote carve-out.
        var allMinted = new LinkedHashSet<ConnectionSynthesis.MintedName>();
        relation.rows().values().forEach(row -> allMinted.addAll(row.mintedNames()));
        allMinted.addAll(relation.sharedMinted());
        assertThat(allMinted).isNotEmpty();
        for (var minted : allMinted) {
            assertThat(bundle.model().types().get(minted.name()))
                .as("registry entry for minted name '%s' must be the declared %s arm",
                    minted.name(), minted.declaredArm().getSimpleName())
                .isInstanceOf(minted.declaredArm());
        }

        // Assembled leg: every absent-from-assembled minted name is present on the rebuilt
        // assembled schema (the additionalType set is the rows' stored discriminators).
        for (var minted : allMinted) {
            if (minted.absentFromAssembled()) {
                assertThat(assembled.getType(minted.name()))
                    .as("rebuilt assembled schema must carry synthesised type '%s'", minted.name())
                    .isInstanceOf(GraphQLObjectType.class);
            }
        }
        // The named connection's facet surface exists end to end (the connectionName: override
        // no longer forfeits facets).
        assertThat(assembled.getType("LegacyFilmsConnectionFacets")).isNotNull();

        // Assembled leg, carrier half: every rewriting directive-driven row's carrier is retyped
        // to its Connection with first/after appended; structural carriers stay untouched.
        for (var row : relation.rows().values()) {
            var parent = (GraphQLObjectType) assembled.getType(row.parentTypeName());
            var carrier = parent.getFieldDefinition(row.fieldName());
            if (row instanceof ConnectionSynthesis.DirectiveDriven dd && dd.rewritesCarrierReturnType()) {
                assertThat(((graphql.schema.GraphQLNamedType) GraphQLTypeUtil.unwrapAll(carrier.getType())).getName())
                    .as("carrier %s.%s must be retyped to its Connection", row.parentTypeName(), row.fieldName())
                    .isEqualTo(dd.connectionName());
                assertThat(carrier.getArgument("first")).isNotNull();
                assertThat(carrier.getArgument("after")).isNotNull();
            }
        }
    }

    /**
     * The model-derived carrier predicate over the pre-assembly SDL: directive-driven bare-list
     * carriers union structural connection-typed returns.
     */
    private static Set<FieldCoordinates> carrierPredicate(TypeDefinitionRegistry registry) {
        var carriers = new LinkedHashSet<FieldCoordinates>();
        for (TypeDefinition<?> def : registry.types().values()) {
            if (!(def instanceof ObjectTypeDefinition obj)) continue;
            for (FieldDefinition fd : obj.getFieldDefinitions()) {
                boolean hasAsConnection = fd.getDirectives().stream()
                    .anyMatch(d -> d.getName().equals("asConnection"));
                boolean bareList = stripNonNull(fd.getType()) instanceof ListType;
                boolean directiveDriven = hasAsConnection && bareList;
                boolean structural = !bareList && isConnectionShaped(registry, baseName(fd.getType()));
                if (directiveDriven || structural) {
                    carriers.add(FieldCoordinates.coordinates(obj.getName(), fd.getName()));
                }
            }
        }
        return carriers;
    }

    /** A declared object type matching the {@code edges} to {@code node} connection pattern. */
    private static boolean isConnectionShaped(TypeDefinitionRegistry registry, String typeName) {
        var def = registry.getTypeOrNull(typeName, ObjectTypeDefinition.class);
        if (def == null) return false;
        var edges = def.getFieldDefinitions().stream()
            .filter(f -> f.getName().equals("edges")).findFirst().orElse(null);
        if (edges == null) return false;
        var edgeDef = registry.getTypeOrNull(baseName(edges.getType()), ObjectTypeDefinition.class);
        return edgeDef != null && edgeDef.getFieldDefinitions().stream()
            .anyMatch(f -> f.getName().equals("node"));
    }

    private static Type<?> stripNonNull(Type<?> type) {
        return type instanceof NonNullType nn ? nn.getType() : type;
    }

    private static String baseName(Type<?> type) {
        Type<?> cur = type;
        while (true) {
            if (cur instanceof NonNullType nn) cur = nn.getType();
            else if (cur instanceof ListType list) cur = list.getType();
            else return ((TypeName) cur).getName();
        }
    }
}
