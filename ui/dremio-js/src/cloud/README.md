# Dremio JS SDK

## Usage

```typescript
import { Dremio, Query } from "@dremio/dremio-js/oss";

// Configure the Dremio SDK with your access token and target instance
const dremio = Dremio({
  token: "YOUR_ACCESS_TOKEN",
  origin: "https://your_dremio_instance.example.com:9047",
});

// Set the DC project ID you want to work with
const PROJECT_ID = "12345678-abcd-9876-5432-123456789abc";

// List all of the sources available in the Dremio instance
for await (const source of dremio.catalog(PROJECT_ID).list().data()) {
  console.log(source);
}

// Fetch a `CatalogObject` for a view in the Dremio instance's catalog
const myView = await dremio
  .catalog(PROJECT_ID)
  .retrieveByPath(["my_source", "my_view"])
  .then((retrieveResult) => retrieveResult.unwrap());

// Fetch the wiki for the view
const myViewWiki = await myView
  .wiki()
  .then((retrieveResult) => retrieveResult.unwrap());

// Update the wiki contents
await myViewWiki.update({ text: "Hello world!" });

// Create a query
const query = new Query("SELECT * FROM mydata;");

// Create a job from the query
const job = await dremio
  .jobs(PROJECT_ID)
  .create(query)
  .then((result) => result.unwrap());

// Job results can be iterated as Apache Arrow RecordBatches (shown)
// or as JSON batches (via `.jsonBatches()`)
for await (const recordBatch of job.results.recordBatches()) {
  console.table([...recordBatch]);
}

// Delete all of the scripts owned by a specific user
for await (const script of dremio
  .scripts(PROJECT_ID)
  .list()
  .data()
  .filter((script) => script.owner.id === "1234-56-7891")) {
  await script.delete();
}
```
