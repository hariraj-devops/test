## Notes on generated files.

The files in this directory are for testing the generator.

The simplest way to regenerate the files is to add Files.writeString calls before the assertions.

For example, for this test snippet:
```java
  @Test
  public void testGenerateWithProtos() throws Exception {
    ...
    Path javaPath = Path.of(tempDir.toString(), "com/dremio/test/dac/api", "RestResource.java");
    Path javaImplPath = Path.of(tempDir.toString(), "com/dremio/test/dac/api", "RestResourceImpl");
    Assertions.assertEquals(
        Files.readString(Path.of(GENERATED_FILES_BASE_DIR, "with_protos", "RestResource.java")),
        Files.readString(javaPath));
  }
```
Change it to below:
```java
  @Test
  public void testGenerateWithProtos() throws Exception {
    ...
    Path javaPath = Path.of(tempDir.toString(), "com/dremio/test/dac/api", "RestResource.java");
    Path javaImplPath = Path.of(tempDir.toString(), "com/dremio/test/dac/api", "RestResourceImpl");
    // Insert this call temporarily, then remove.
    Files.writeString(
        Path.of(GENERATED_FILES_BASE_DIR, "with_protos", "RestResource.java"),
        Files.readString(javaPath));
    Assertions.assertEquals(
        Files.readString(Path.of(GENERATED_FILES_BASE_DIR, "with_protos", "RestResource.java")),
        Files.readString(javaPath));
  }
```

