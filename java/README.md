# AURAL Java clean-room foundations

This is an independent Java 21 interoperability experiment. It is not an official
AURAL reference implementation and does not yet implement ZIP or package verification.

Build from the `java` directory on Windows without changing machine policy:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

Build and run all foundation tests:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\test.ps1
```

The project has no third-party dependencies and is compiled with `javac --release 21`.
