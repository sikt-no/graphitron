package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RewriteContextTest {

    @Test
    void schemaInputsList_isImmutable() {
        var mutable = new ArrayList<SchemaInput>();
        mutable.add(SchemaInput.plain("/a"));
        var ctx = ctx(mutable, Map.of());

        assertThatThrownBy(() -> ctx.schemaInputs().add(SchemaInput.plain("/b")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void namedReferencesMap_isImmutable() {
        var mutable = new HashMap<String, String>();
        mutable.put("k", "v");
        var ctx = ctx(List.of(), mutable);

        assertThatThrownBy(() -> ctx.namedReferences().put("x", "y"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mutatingPassedList_doesNotAffectContext() {
        var mutable = new ArrayList<SchemaInput>();
        mutable.add(SchemaInput.plain("/a"));
        var ctx = ctx(mutable, Map.of());

        mutable.add(SchemaInput.plain("/b"));

        assertThat(ctx.schemaInputs()).hasSize(1);
    }

    private static RewriteContext ctx(
            List<SchemaInput> schemaInputs,
            Map<String, String> namedReferences) {
        return new RewriteContext(
            schemaInputs, Path.of(""), Path.of(""), "pkg", "jooq",
            namedReferences);
    }
}
