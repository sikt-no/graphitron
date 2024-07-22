package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Address;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.AddressRecord;

public class AddressJOOQMapper {
    public static List<AddressRecord> toJOOQRecord(List<Address> address, String path,
                                                   RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var ctx = transform.getCtx();
        var addressRecordList = new ArrayList<AddressRecord>();

        if (address != null) {
            for (var itAddress : address) {
                if (itAddress == null) continue;
                var addressRecord = new AddressRecord();
                addressRecord.attach(ctx.configuration());
                var address_inner = itAddress.getInner();
                if (address_inner != null) {
                    if (arguments.contains(pathHere + "inner/postalCode")) {
                        addressRecord.setPostalCode(address_inner.getPostalCode());
                    }
                }

                addressRecordList.add(addressRecord);
            }
        }

        return addressRecordList;
    }
}
