package no.sikt.graphitron.example.generated.graphitron.model;

import java.lang.Object;
import java.lang.Override;
import java.util.Objects;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.VacationRecord;

public class VacationDestination {
    private VacationRecord vacation_destination_vacation_fkey;

    private VacationRecord vacationDescriptionKey;

    public VacationDestination() {
    }

    public VacationDestination(VacationRecord vacation_destination_vacation_fkey) {
        this.vacation_destination_vacation_fkey = vacation_destination_vacation_fkey;
        this.vacationDescriptionKey = vacation_destination_vacation_fkey;
    }

    public VacationRecord getVacation_destination_vacation_fkey() {
        return vacation_destination_vacation_fkey;
    }

    public VacationRecord getVacationDescriptionKey() {
        return vacationDescriptionKey;
    }

    public void setVacationDescriptionKey(VacationRecord vacationDescriptionKey) {
        this.vacationDescriptionKey = vacationDescriptionKey;
    }

    @Override
    public int hashCode() {
        return Objects.hash(vacation_destination_vacation_fkey, vacationDescriptionKey);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final VacationDestination that = (VacationDestination) obj;
        return Objects.equals(vacation_destination_vacation_fkey, that.vacation_destination_vacation_fkey) && Objects.equals(vacationDescriptionKey, that.vacationDescriptionKey);
    }
}
