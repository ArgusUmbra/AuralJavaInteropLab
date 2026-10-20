# AURAL Java clean-room verifier experiment

This is an independent, standard-library-only Java 21 interoperability experiment.
It is not an official AURAL reference implementation, a certification, or a complete
AURAL package verifier.

`org.auralinterop.manifest.ManifestValidator` is the Draft 0.2 manifest API. Its
`validate(byte[])` method returns an immutable, parser-independent `Manifest`, or
throws `ManifestValidationException` with a stable failure stage. Validation performs
strict UTF-8/I-JSON-profile parsing, schema and semantic checks, JCS canonicalization,
and exact canonical-byte comparison in that order.

## Classic ZIP structural parser

`org.auralinterop.zip.ZipStructureParser` directly parses raw ZIP records through one
read-only `FileChannel`, via the existing `BoundedFileReader`. It does not use
`ZipFile`, `ZipInputStream`, extraction, decompression, or entry names as filesystem
paths. High-level extraction APIs are not sufficient for this profile because their
central-directory view does not independently prove local-header agreement, exact raw
name bytes, absence of hidden local records, or a prefix/gap/overlap/trailer-free
physical layout.

The Assignment 008D-3F-7D parser supports classic EOCD, classic central-directory file
headers, and classic local-file headers. It enforces:

- one disk, exactly two entries, and exact raw ASCII names `manifest.json` and
  `audio/original.wav`;
- stored method 0, flags zero except optional UTF-8 bit 11, and no data descriptors;
- zero archive and entry comments, and no extra fields in a classic archive;
- version-needed 10, 20, or 45, valid MS-DOS timestamps, and regular-file metadata;
- local/central agreement for version-needed, flags, method, timestamp, CRC32
  declaration, sizes, and filename bytes;
- equal compressed and uncompressed sizes, plus manifest/original declared limits;
- exact entry, central-directory, and EOCD bounds with checked 64-bit arithmetic;
- offset-zero first local header and contiguous, non-overlapping local headers, data,
  central directory, and terminal EOCD; and
- no prefix, hidden local entry, undeclared gap, overlap, data overrun, duplicate name,
  unexpected entry, central-directory residue, or trailer.

Reader order is intentionally not fixed. Local entries are validated by their actual
offsets and may place the audio first. Central-directory enumeration may also use
either order. The result list is returned in physical local-entry order.

Use the parser as follows:

```java
ZipArchiveStructure structure = new ZipStructureParser().parse(archivePath);
ZipEntryStructure manifest = structure.entry("manifest.json");
long manifestDataOffset = manifest.dataOffset();
```

`ZipArchiveStructure`, `ZipEntryStructure`, and `ByteRange` expose immutable or
defensively copied metadata and validated half-open ranges. They do not contain entry
payloads. `ZipStructureException.reason()` provides controlled structural failure
classification. `ZIP64_UNSUPPORTED_IN_THIS_STAGE` is a temporary implementation
boundary, not a normative AURAL rejection: recognizable sentinel/extra-field cases
take that path, while malformed or unnecessary ZIP64 extra fields remain invalid.

Structural acceptance means only that the classic ZIP envelope satisfies the Draft
0.2 ZIP profile. It does **not** validate actual entry CRC32 values, manifest bytes or
semantics, manifest/ZIP cross-entry consistency, WAVE identity, audio byte count,
SHA-256, or complete package validity. It also does not turn successful parsing into a
guarantee against concurrent in-place modification; later verification must consume
the same open archive snapshot and apply a mutation policy.

### Resource safety

The parser checks the 8 GiB + 16 MiB physical archive limit before record-directed
allocation. It caps the central-directory buffer at 4 MiB, requires exactly two
records, bounds local variable fields by their unsigned 16-bit lengths, uses 64-bit
positions and checked range arithmetic, detects short reads, and performs no
decompression or writes. The manifest declaration is capped at 1 MiB and the original
declaration at 8 GiB. The architecture is ready for bounded 64-bit ZIP64 positions,
but Assignment 7D deliberately does not decode ZIP64 records or expanded values.

### Frozen fixture smoke scope

The public 30-case Draft 0.2 inventory is used only as a smoke layer; no fixture names,
IDs, hashes, or expected answers occur in production code.

- Structural accepts (4): all four `valid-*` packages. This establishes only that
  their ZIP envelopes are valid.
- Structural rejects (10): invalid compression, duplicate entry, entry comment,
  forbidden extra field, local/central CRC declaration mismatch, malformed local
  header, special-file metadata, trailer, unnecessary ZIP64 extra, and unsafe ZIP
  filename. Their published reason is decided entirely by ZIP structure.
- `NOT_YET_APPLICABLE` (16): altered original, asset hash, critical extension,
  duplicate JSON key, excessive manifest-declared asset size, excessive JSON depth,
  fraction, malformed JSON, negative zero, noncanonical JSON, noncharacter, unknown
  manifest member, unpaired surrogate, unsafe JSON integer, invalid UTF-8, and version.
  Their ZIP structure is accepted and their final rejection belongs to later semantic
  or integrity layers.

These smoke results are not a claim of complete 30-case Core Reader conformance.

### Recorded specification interpretations

The frozen text intentionally makes several metadata fields non-semantic but does not
fully spell out every malformed encoding. This experiment records these choices
without treating them as new normative requirements:

- “valid MS-DOS modification timestamps” is interpreted as valid hour/minute/second
  bit ranges and a real Gregorian date in the representable 1980–2107 range.
- Version-needed 10, 20, and 45 are all accepted for a classic stored entry. Merely
  using 45 does not imply that ZIP64 records must exist.
- The DOS directory bit in the low attribute byte and the Unix file-type nibble in the
  high word are checked regardless of creator system. A zero Unix type or regular-file
  type is accepted; permission bits, creator platform, and internal attributes vary.
- A second EOCD is rejected when it creates undeclared bytes or contradictory terminal
  placement. EOCD-signature octets occurring inside legitimate entry data are not
  globally banned.

ZIP64 expansion, EOCD/locator consistency, and ZIP64 extra-value agreement remain for
Assignment 7E. Full CRC32 streaming, manifest integration, WAVE identification,
original byte count/SHA-256, and complete package verdicts remain for Assignment 7F.

Build from the `java` directory on Windows without changing machine policy:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

Build and run all foundation tests:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\test.ps1
```

The project has no third-party dependencies and is compiled with `javac --release 21`.
The build also enables `-Xlint:all -Werror`. The test suite includes 84 independently
constructed raw structural cases plus 30 frozen-fixture smoke classifications, in
addition to the 109 pre-existing foundation and manifest tests.
