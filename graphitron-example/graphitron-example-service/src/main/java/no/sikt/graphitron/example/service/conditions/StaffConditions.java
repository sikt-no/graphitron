package no.sikt.graphitron.example.service.conditions;

import no.sikt.graphitron.example.generated.jooq.tables.Address;
import no.sikt.graphitron.example.generated.jooq.tables.Staff;
import org.jooq.Condition;

import static org.jooq.impl.DSL.noCondition;

public class StaffConditions {
    public static Condition isManagerOfAStore(Staff staff, Boolean isManager) {
        return isManager == null ? noCondition() :
                staff.store().MANAGER_STAFF_ID.cast(Integer.class).eq(staff.STAFF_ID).eq(isManager);
    }

    public static Condition withAddressIdInCity300(Address address, Integer addressId) {
        return addressId == null ? noCondition() : address.CITY_ID.eq((short) 300);
    }
}
