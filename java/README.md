# AURAL Java clean-room foundations

This is an independent, standard-library-only Java 21 interoperability experiment.
It is not an official AURAL reference implementation and does not yet implement ZIP
or package verification.

`org.auralinterop.manifest.ManifestValidator` is the Draft 0.2 manifest API. Its
`validate(byte[])` method returns an immutable, parser-independent `Manifest`, or
throws `ManifestValidationException` with a stable failure stage. Validation performs
strict UTF-8/I-JSON-profile parsing, schema and semantic checks, JCS canonicalization,
and exact canonical-byte comparison in that order.

Build from the `java` directory on Windows without changing machine policy:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

Build and run all foundation tests:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\test.ps1
```

The project has no third-party dependencies and is compiled with `javac --release 21`.
