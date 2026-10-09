package no.sikt.graphitron.rewrite.test.services;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Methods whose names an editor resolves a declaration through. {@link #price}, {@link #discount}
 * and {@link #computeCol} are one-argument field methods. The producers ground a type on a class of
 * each member shape: {@link NamedRecord} offers {@code firstName} as a record component,
 * {@link NamedBean} as a bean accessor, {@link MemberlessShape} offers nothing, and
 * {@link LocalDate} is a class on no entry a graph reads, so a member name inside it names nothing.
 */
public final class NamedMemberService {

    private NamedMemberService() {}

    public static Field<BigDecimal> price(DSLContext ctx) {
        return DSL.inline(BigDecimal.ZERO);
    }

    public static Field<BigDecimal> discount(DSLContext ctx) {
        return DSL.inline(BigDecimal.ZERO);
    }

    public static Field<String> computeCol(DSLContext ctx) {
        return DSL.inline("");
    }

    public static NamedRecord makeRecord() {
        return null;
    }

    public static NamedBean makeBean() {
        return null;
    }

    public static MemberlessShape makeMemberless() {
        return null;
    }

    public static LocalDate makeUnread() {
        return null;
    }
}
