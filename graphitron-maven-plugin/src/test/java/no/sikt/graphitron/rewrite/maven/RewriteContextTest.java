package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RewriteContextTest {

    @Test
    void schemaInputsList_isImmutable() {
        var mutable = new ArrayList<SchemaInput>();
        mutable.add(SchemaInput.named("/a"));
        var ctx = ctx(mutable);

        assertThatThrownBy(() -> ctx.schemaInputs().add(SchemaInput.named("/b")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mutatingPassedList_doesNotAffectContext() {
        var mutable = new ArrayList<SchemaInput>();
        mutable.add(SchemaInput.named("/a"));
        var ctx = ctx(mutable);

        mutable.add(SchemaInput.named("/b"));

        assertThat(ctx.schemaInputs()).hasSize(1);
    }

    private static RewriteContext ctx(List<SchemaInput> schemaInputs) {
        return new RewriteContext(
            schemaInputs, Path.of(""), "RewriteContextTest", Path.of(""), "pkg", "jooq");
    }
}
