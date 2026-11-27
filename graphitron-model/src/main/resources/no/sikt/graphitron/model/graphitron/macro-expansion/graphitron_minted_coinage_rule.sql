-- The mint's own provenance. The minted sets are keyed on the coordinate of the thing minted rather
-- than of the thing that minted it, so a reader wanting to carry something from the application to
-- what it coined needs the pairing back. The type candidates keep it, and this is their projection.
SELECT DISTINCT graph_name, type_name, carrier_coordinate AS coordinate
  FROM graphitron_type_minted_candidate
