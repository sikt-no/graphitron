package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunContextTest {

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

    private static RunContext ctx(List<SchemaInput> schemaInputs) {
        return new RunContext(
            schemaInputs, Path.of(""), "RunContextTest", Path.of(""), "pkg", "jooq");
    }
}
