SELECT DISTINCT o.graph_name, o.coordinate, 'objectMember' AS rule, 'OBJECT' AS kind, pa.target_class_name AS class_name
  FROM graphitron_occurrence o
  JOIN graphitron_occurrence p ON p.graph_name = o.graph_name AND p.coordinate = o.parent_coordinate
  JOIN graphitron_accessor_path pa ON pa.graph_name = p.graph_name AND pa.coordinate = p.coordinate
  JOIN graphitron_field cf ON cf.graph_name = o.graph_name AND cf.coordinate = o.element_coordinate
 WHERE o.kind = 'FIELD'
   AND NOT EXISTS (SELECT 1 FROM store_graph_source gs
                     JOIN sql_table st ON st.source_name = gs.source_name
                    WHERE gs.graph_name = o.graph_name AND st.record_class_fqn = pa.target_class_name)
   AND NOT EXISTS (SELECT 1 FROM graphitron_errors_field ef
                     WHERE ef.graph_name = cf.graph_name AND ef.type_name = cf.type_name
                       AND ef.field_name = cf.field_name)
