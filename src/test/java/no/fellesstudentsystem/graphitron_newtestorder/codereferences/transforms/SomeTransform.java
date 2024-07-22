package no.fellesstudentsystem.graphitron_newtestorder.codereferences.transforms;

import org.jooq.DSLContext;
import org.jooq.UpdatableRecord;

import java.util.List;

public class SomeTransform {
    public static <T extends UpdatableRecord<T>> List<T> someTransform(DSLContext ctx, List<T> records) {
        return null;
    }
}
