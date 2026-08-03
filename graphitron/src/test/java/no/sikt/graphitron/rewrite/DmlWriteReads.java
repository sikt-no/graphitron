package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.DeleteRows;
import no.sikt.graphitron.rewrite.model.DmlWriteField;
import no.sikt.graphitron.rewrite.model.InputArgRef;
import no.sikt.graphitron.rewrite.model.OperationMember;
import no.sikt.graphitron.rewrite.model.UpdateRows;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserting write-arm extractors for tests reading DML payloads off the folded leaves: each
 * read asserts the expected verb arm first, so a fixture that silently classifies onto a
 * different verb fails with the discriminating message instead of a bare cast error.
 */
public final class DmlWriteReads {

    private DmlWriteReads() {}

    public static ArgumentRef.InputTypeArg.TableInputArg insertInputOf(DmlWriteField f) {
        assertThat(f.write()).as("expected an Insert write arm")
            .isInstanceOf(OperationMember.Write.Insert.class);
        return ((OperationMember.Write.Insert) f.write()).input();
    }

    public static UpdateRows updateRowsOf(DmlWriteField f) {
        assertThat(f.write()).as("expected an Update write arm")
            .isInstanceOf(OperationMember.Write.Update.class);
        return ((OperationMember.Write.Update) f.write()).updateRows();
    }

    public static DeleteRows deleteRowsOf(DmlWriteField f) {
        assertThat(f.write()).as("expected a Delete write arm")
            .isInstanceOf(OperationMember.Write.Delete.class);
        return ((OperationMember.Write.Delete) f.write()).deleteRows();
    }

    public static InputArgRef updateArgOf(DmlWriteField f) {
        assertThat(f.write()).as("expected an Update write arm")
            .isInstanceOf(OperationMember.Write.Update.class);
        return ((OperationMember.Write.Update) f.write()).inputArg();
    }

    public static InputArgRef deleteArgOf(DmlWriteField f) {
        assertThat(f.write()).as("expected a Delete write arm")
            .isInstanceOf(OperationMember.Write.Delete.class);
        return ((OperationMember.Write.Delete) f.write()).inputArg();
    }
}
