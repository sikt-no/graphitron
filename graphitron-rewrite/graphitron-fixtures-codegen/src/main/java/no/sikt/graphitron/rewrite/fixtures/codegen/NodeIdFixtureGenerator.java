package no.sikt.graphitron.rewrite.fixtures.codegen;

import org.jooq.codegen.JavaGenerator;
import org.jooq.codegen.JavaWriter;
import org.jooq.meta.TableDefinition;

import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Custom jOOQ code generator for graphitron-rewrite test fixtures. Appends NodeId-metadata
 * constants ({@code __NODE_TYPE_ID} and {@code __NODE_KEY_COLUMNS}) to specific fixture tables,
 * mimicking what Sikt's {@code KjerneJooqGenerator} emits for NodeId-bearing tables in production.
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
        "baz", new Metadata("Baz", List.of("ID"))
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
