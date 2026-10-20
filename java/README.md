# AURAL Java clean-room verifier experiment

This is an independent, standard-library-only Java 21 interoperability experiment.
It is not an official AURAL reference implementation, a certification, or a complete
AURAL package verifier.

`org.auralinterop.manifest.ManifestValidator` is the Draft 0.2 manifest API. Its
`validate(byte[])` method returns an immutable, parser-independent `Manifest`, or
throws `ManifestValidationException` with a stable failure stage. Validation performs
strict UTF-8/I-JSON-profile parsing, schema and semantic checks, JCS canonicalization,
and exact canonical-byte comparison in that order.

## Classic and ZIP64 structural parser

`org.auralinterop.zip.ZipStructureParser` directly parses raw ZIP records through one
read-only `FileChannel`, via the existing `BoundedFileReader`. It does not use
`ZipFile`, `ZipInputStream`, extraction, decompression, or entry names as filesystem
paths. High-level extraction APIs are not sufficient for this profile because their
central-directory view does not independently prove local-header agreement, exact raw
name bytes, absence of hidden local records, or a prefix/gap/overlap/trailer-free
physical layout.

The Assignment 008D-3F-7E parser supports classic records plus the permitted ZIP64
subset defined by frozen AURAL Draft 0.2 section 6 and PKWARE APPNOTE 6.3.10
(FINAL, 2022-11-01). It enforces:

- one disk, exactly two entries, and exact raw ASCII names `manifest.json` and
  `audio/original.wav`;
- stored method 0, flags zero except optional UTF-8 bit 11, and no data descriptors;
- zero archive and entry comments, and no extra fields in a classic archive;
- exact ZIP64 EOCD version-1 records (record-size field 44, physical size 56), exact
  20-byte single-disk locators, and no extensible data;
- ordered ZIP64 `0x0001` values only for matching size or local-offset sentinels, with
  no missing, duplicate, unnecessary, surplus, or foreign extra fields;
- resolution of every ZIP64 unsigned 64-bit value before comparison or positioning,
  with rejection of high-bit values and values beyond the relevant AURAL limit;
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
payloads. Entry sizes and local offsets are resolved `long` values; the result also
exposes their raw unsigned 32-bit fields and whether each value came from ZIP64.
Archive results expose whether terminal ZIP64 records were used and their exact
physical ranges. `ZipStructureException.reason()` provides controlled structural
failure classification. The legacy `ZIP64_UNSUPPORTED_IN_THIS_STAGE` enum value and
query method remain source-compatible with 7D callers, but the 7E parser no longer
emits that result for supported ZIP64 structures.

### ZIP64 architecture and supported profile

The parser first reads the terminal classic EOCD as raw fields. `Zip64EndParser` then
resolves that record either directly or through the immediately preceding locator and
ZIP64 EOCD. Only after terminal consistency and the 4 MiB directory bound are proven
does the existing central parser allocate its bounded directory buffer.

`Zip64ExtraField` is the single entry-resolution layer shared by local and central
headers. It retains the raw sentinels separately from resolved values, follows APPNOTE
ordering (uncompressed size, compressed size, local-header offset, disk start), and
uses only those values selected by corresponding sentinels. Local ZIP64 size extras
require both local size fields, as APPNOTE 4.5.3 specifies. Disk-start expansion is
rejected: a single-disk AURAL entry is representable as classic disk zero, so the
sentinel and extra value would be unnecessary. An entry that actually uses ZIP64 must
declare version-needed 4.5.

Terminal ZIP64 records are required whenever any classic EOCD field is a sentinel.
They must resolve to disk zero, exactly two entries, the bounded central-directory
size and offset, and exact contiguous placement. Every classic non-sentinel field must
equal the corresponding ZIP64 value. The ZIP64 EOCD version-needed is exactly 4.5;
its version-made-by must advertise 4.5 or later while allowing creator systems to
vary.

Positive tests include small sentinel-expanded entry sizes, independently expanded
central sizes, local-only expansion, 64-bit local-header offsets, combined ordered
extras, both physical entry orders, both central enumeration orders, individual and
combined classic EOCD sentinels, mixed classic/ZIP64 terminal metadata, zero-sized
expanded entries, and the exact 1 MiB manifest boundary. These are actual parser
acceptances.

Malformed signatures, lengths, versions, disks, counts, offsets, sizes, record
placement, classic/ZIP64 contradictions, high-bit unsigned values, missing or
duplicate terminal records, and malformed/unneeded/surplus extras are controlled
rejections. Strict physical layout still rejects prefixes, gaps, overlaps, hidden
locals, data descriptors, undeclared records, and trailers.

Structural acceptance means only that the ZIP envelope satisfies the Draft
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
declaration at 8 GiB. Unsigned 64-bit values are rejected before arithmetic when their
high bit is set or their resource-specific limit is exceeded. Normal tests exercise
small sentinel fixtures, near-4-GiB declarations, 4-GiB declarations and offsets,
high-bit rejection, and range overrun without large allocation or disk writes.

### Optional genuine large-file test

`Zip64LargeFileTest` is compiled but never registered in the normal suite. It streams
a real 4 GiB zero-filled original, computes its ZIP CRC32, places the manifest and
central directory beyond the classic offset range, emits exact ZIP64 entry and
terminal records, parses the result, and deletes the temporary archive in `finally`.
It does not rely on a merely large invalid sparse length.

The opt-in run has an apparent and worst-case physical size of about 4.01 GiB plus a
512 MiB safety margin. It checks usable space before creation. Use a dedicated
temporary directory and invoke only after accepting that disk and time cost:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
$cp = '..\build\classes-main;..\build\classes-test'
java -cp $cp org.auralinterop.tests.Zip64LargeFileTest --run D:\dedicated-aural-temp
```

The test cleans up on success or failure. If the JVM or machine is terminated, remove
any `aural-zip64-large-*.aural` file from that dedicated directory manually. This test
was designed but was not run for Assignment 7E because explicit authorization is
required.

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
- Frozen Draft 0.2 section 6.4 says terminal ZIP64 records are mandatory when the
  classic EOCD uses a sentinel, but does not explicitly say whether those records are
  permitted when every classic EOCD field is non-sentinel. This experiment chooses
  the conservative behavior: reject an otherwise exact terminal ZIP64 envelope when
  no classic EOCD sentinel applies. The alternate interpretation would accept that
  redundant envelope, so this ambiguity affects acceptance. A future AURAL revision
  should say explicitly either “MUST NOT appear without an EOCD sentinel” or “MAY
  appear without a sentinel if every value agrees.” Both interpretations are covered
  by tests; the conservative interpretation is the active one.

Full CRC32 streaming, manifest integration, WAVE identification,
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
The build also enables `-Xlint:all -Werror`. The normal suite currently contains 304
tests: all 223 pre-7E tests remain registered, and 81 independently assembled ZIP64
tests were added (22 valid acceptances and 59 malformed/adversarial rejections). The
nine pre-existing 7D ZIP64 boundary tests now assert 7E behavior rather than a temporary
unsupported result; across both sets, ZIP64 coverage is 23 acceptances and 67
rejections. The optional >4 GiB test is not included in that count.
