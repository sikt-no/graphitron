package no.sikt.graphitron.rewrite.fixtures.codegen;

import org.jooq.codegen.JavaGenerator;
import org.jooq.codegen.JavaWriter;
import org.jooq.meta.TableDefinition;

import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Custom jOOQ code generator for graphitron-rewrite test fixtures. Appends NodeId-metadata
 * constants ({@code __NODE_TYPE_ID} and {@code __NODE_KEY_COLUMNS}) to specific fixture tables
 * across the public, nodeidfixture, and idreffixture schemas, mimicking what Sikt's
 * {@code KjerneJooqGenerator} emits for NodeId-bearing tables in production.
 *
 * <p>The mapping is hard-coded in {@link #METADATA}. Tables outside the map generate as stock jOOQ
 * output. The point is that the rewrite's classifier is exercised against real generator output
 * rather than a hand-edited approximation of it, so jOOQ upgrades can't silently drift the
 * fixture away from the generator contract.
 *
 * <p>This class is loaded by the {@code jooq-codegen-maven} plugin in
 * {@code graphitron-fixtures} and must therefore live in its own module (the plugin
 * runs in {@code generate-sources}, before the consuming module's own classes are compiled).
 */
public class NodeIdFixtureGenerator extends JavaGenerator {

    private record Metadata(String typeId, List<String> keyColumnFields) {}

    /**
     * Fixture tables that should gain NodeId metadata. Table SQL name (lowercase) → (typeId,
     * key-column Java field names). Key columns are referenced off the generated table singleton
     * (e.g. {@code BAR.ID_1}); the field names here are the Java constants jOOQ emits for each
     * column (uppercase of the SQL column name).
     */
    private static final Map<String, Metadata> METADATA = Map.of(
        "bar", new Metadata("Bar", List.of("ID_1", "ID_2")),
        "baz", new Metadata("Baz", List.of("ID")),
        // A customized numeric typeId ("10154") distinct from any GraphQL type name. With a
        // @node type plus a nesting-projection @table type over this table, the decode helper must
        // resolve through the @node-only NodeIndex (decode<TypeName>); the old findGraphQLTypeForTable
        // detour returned empty (two object types) and fell back to decode10154, which the encoder
        // never emits. No existing fixture had a numeric/custom typeId.
        "shared_node", new Metadata("10154", List.of("ID")),
        "studieprogram", new Metadata("Studieprogram", List.of("STUDIEPROGRAM_ID")),
        // Composite-PK NodeType in the public (sakila-derived) schema, used by
        // GraphQLQueryTest's filmActorByNodeId round-trip for the LookupArg.DecodedRecord
        // arm. PK column order matches `init.sql`'s declaration: actor_id first, film_id second.
        "film_actor", new Metadata("FilmActor", List.of("ACTOR_ID", "FILM_ID")),
        // Rooted-at-parent fixture. NodeId keys on PK_ID only; the table also
        // exposes a unique ALT_KEY column targeted by `child_ref.parent_alt_key`'s FK. The
        // FK does not positionally match __NODE_KEY_COLUMNS — that mismatch is the test
        // surface for the rooted-at-parent JOIN-with-projection emission path.
        "parent_node", new Metadata("ParentNode", List.of("PK_ID")),
        // Arity > 22 rejection fixture. NodeIdLeafResolver.resolve rejects any
        // NodeType with > 22 key columns (jOOQ's typed Record/Row caps at Row22); this
        // 23-column composite-PK table is the smallest case that exercises that guard.
        "too_wide", new Metadata("TooWide", List.of(
            "K1",  "K2",  "K3",  "K4",  "K5",  "K6",  "K7",  "K8",
            "K9",  "K10", "K11", "K12", "K13", "K14", "K15", "K16",
            "K17", "K18", "K19", "K20", "K21", "K22", "K23")),
        // Multi-hop @reference on @nodeId, identity-carrying lift fixture.
        // level_a is the NodeType target reached via the chain
        // level_c -> level_b -> level_a (FK on (s, k1, k2) then FK on (k1, k2)),
        // both adjacent pairs satisfying the lift predicate so the terminal hop's
        // source-side tuple lifts back to level_c.(k1, k2) on the parent table.
        "level_a", new Metadata("LevelA", List.of("K1", "K2")),
        // Lift-failure fixture: lift_fail_a has the same arity-2 NodeType key
        // shape, but the lift_fail_b -> lift_fail_a FK uses (a_k1, a_k2) which do not
        // appear in lift_fail_b's column list when traversed from lift_fail_c (which
        // only carries fk_b). The lift predicate fails at hop[1] and the resolver
        // rejects via LIFT_FAILURE_MARKER.
        "lift_fail_a", new Metadata("LiftFailA", List.of("K1", "K2")),
        // Permutation fixture: reordered_pk_parent declares its PK as
        // (pk_a, pk_b, pk_c) — the order we publish as __NODE_KEY_COLUMNS. The
        // reordered_fk_child FK references reordered_pk_parent(pk_b, pk_c, pk_a) in a
        // *different* order. NodeId encode/decode follows __NODE_KEY_COLUMNS; the FK's
        // declared target order is different. The resolver's DirectFk arm permutes the
        // lifted source columns into __NODE_KEY_COLUMNS order so the emitter's
        // positional bind between decoded keys and liftedSourceColumns stays correct.
        // Test surface: NodeIdPipelineTest.InputFieldFkTargetNodeIdCase
        // .FK_TARGET_REORDERED_KEY_PERMUTATION_DIRECT_FK.
        "reordered_pk_parent", new Metadata("ReorderedPkParent", List.of("PK_A", "PK_B", "PK_C"))
    );

    @Override
    protected void generateTableClassFooter(TableDefinition table, JavaWriter out) {
        super.generateTableClassFooter(table, out);
        Metadata meta = METADATA.get(table.getOutputName().toLowerCase(Locale.ROOT));
        if (meta == null) {
            return;
        }
        String singleton = table.getOutputName().toUpperCase(Locale.ROOT);
        out.println();
        out.println("public static final String __NODE_TYPE_ID = \"%s\";", meta.typeId());
        StringBuilder keys = new StringBuilder("public static final org.jooq.Field<?>[] __NODE_KEY_COLUMNS = { ");
        for (int i = 0; i < meta.keyColumnFields().size(); i++) {
            if (i > 0) keys.append(", ");
            keys.append(singleton).append('.').append(meta.keyColumnFields().get(i));
        }
        keys.append(" };");
        out.println(keys.toString());
    }
}
