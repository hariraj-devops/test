# Embedded Catalog

The `embedded-catalog` module contains an implementation of a subset of 
Nessie gRPC APIs that is used for so called "Embedded Nessie" use cases:

* Iceberg metadata for Reflections.
* Iceberg metadata for Infinite Splits.

Implemented APIs:
* `getDefaultBranch`
* `getAllReferences`
* `getReferenceByName`
* `commitMultipleOperations`
  * Only for `ICEBERG_TABLE` and `NAMESPACE` objects.
  * Only the `metadataLocation` attribute is preserved for tables.
    * Note: other Iceberg Table attributes are not used in "embedded use cases" 
* `getMultipleContents`
* `getEntries`
  * Only for getting all entries or entries for specific keys.
  * Filters and min/max key parameters are not supported.
  * Pagination is not supported.
    * Note: pagination is not needed for "embedded use cases".

The catalog data is stored directly in `KVStore`.

Note: the Nessie Data Model is not used in the backend as there
is no need for that in "embedded use cases".
