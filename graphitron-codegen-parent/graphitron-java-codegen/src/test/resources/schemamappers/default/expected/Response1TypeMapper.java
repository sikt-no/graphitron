package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Response1;
import java.lang.String;

public class Response1TypeMapper {
    public static Response1 recordToGraphType(String response1Record, String _iv_path,
                                              RecordTransformer _iv_transform) {
        var _iv_pathHere = _iv_path.isEmpty() ? _iv_path : _iv_path + "/";
        var _iv_select = _iv_transform.getSelect();
        var response1 = new Response1();

        if (_iv_select.contains(_iv_pathHere + "id1")) {
            response1.setId1(response1Record);
        }


        return response1;
    }
}
