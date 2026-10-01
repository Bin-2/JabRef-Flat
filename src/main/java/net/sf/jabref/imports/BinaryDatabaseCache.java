package net.sf.jabref.imports;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;

import net.sf.jabref.BibtexDatabase;
import net.sf.jabref.BibtexEntry;
import net.sf.jabref.BibtexEntryType;
import net.sf.jabref.BibtexFields;
import net.sf.jabref.BibtexString;
import net.sf.jabref.CustomEntryType;
import net.sf.jabref.GUIGlobals;
import net.sf.jabref.Globals;
import net.sf.jabref.KeyCollisionException;
import net.sf.jabref.MetaData;
import net.sf.jabref.UnknownEntryType;
import net.sf.jabref.groups.GroupTreeNode;
import net.sf.jabref.groups.VersionHandling;

/**
 * Binary companion cache for a BibTeX database.
 *
 * <p>The .bib file is always the only valid source. A cache is used only when
 * the source file identity and the parser settings stored in the cache still
 * match. Any cache read/write problem must be handled by the caller as a cache
 * miss and must never prevent normal BibTeX loading or saving.</p>
 *
 * <h2>Why this format exists</h2>
 * <p>Parsing a large .bib file is expensive. This class serialises the result
 * of a successful parse into a companion file {@code <bibfile>.jbc} so the next
 * startup can rebuild the in-memory model without re-running the parser. It is
 * deliberately <em>not</em> a general-purpose serialisation format: it assumes
 * a fixed schema (the order of fields below), a fixed JVM/library version
 * family, and no nullable structural elements.</p>
 *
 * <h2>On-disk layout (all integers big-endian, as written by
 * {@link DataOutputStream})</h2>
 * <pre>
 *   int32   MAGIC                 0x4A424331 ("JBC1")
 *   int32   FORMAT_VERSION        currently 2
 *   int64   source length         of the .bib file at snapshot time
 *   int64   source lastModified   of the .bib file at snapshot time
 *   byte[32] source SHA-256       of the .bib file at snapshot time
 *
 *   bool    biblatexMode          parser preference at write time
 *   bool    autoDoubleBraces      parser preference at write time
 *   string  bracesAroundCapitals  parser preference at write time
 *
 *   -- cache header --
 *   string?  encoding             may be null
 *   string?  jabrefVersion        may be null
 *   int32   jabrefMajorVersion
 *   int32   jabrefMinorVersion
 *   int32   jabrefMinor2Version
 *   int32   warningCount
 *   string[warningCount] warnings
 *
 *   -- custom entry types --
 *   int32   typeCount
 *   { string name, string requiredFields, string optionalFields } * typeCount
 *
 *   string?  preamble             may be null
 *
 *   -- BibTeX @string macros --
 *   int32   stringCount
 *   { string id, string name, string content } * stringCount
 *
 *   -- entries --
 *   int32   entryCount
 *   { string id, string typeName, int32 fieldCount,
 *     { string fieldName, string? fieldValue } * fieldCount } * entryCount
 *
 *   -- metadata --
 *   int32   metaKeyCount
 *   { string key, int32 valueCount (-1 == null list),
 *     string?[valueCount] values } * metaKeyCount
 *   bool    hasGroupTree
 *   string  groupTree             only if hasGroupTree, newline-separated
 * </pre>
 *
 * <h2>String encoding</h2>
 * <p>All strings are written as {@code int32 length} followed by exactly that
 * many bytes of <strong>standard UTF-8</strong> (not Java's modified UTF-8).
 * A length of {@code -1} means {@code null}; that sentinel is only used by
 * {@link #writeNullableString}, never by {@link #writeString}. This is why
 * most of the payload looks like plain text in a hex editor: only the length
 * prefixes and the header are binary.</p>
 *
 * <h2>Consistency model</h2>
 * <p>A cache is valid iff the .bib file's length, lastModified and SHA-256
 * all match the values stored in the cache. Because length+lastModified are
 * checked cheaply on load, the SHA-256 is only recomputed lazily in the
 * background ({@link #startBackgroundVerification}) or at the save barrier
 * ({@link #verifyBeforeSave}). See {@link CacheVerification}.</p>
 *
 * <h2>Failure policy</h2>
 * <ul>
 *   <li>{@link #loadIfValid} returns {@code null} for a missing, stale or
 *       header-mismatched cache, and throws {@link IOException} only for a
 *       cache that is present but structurally corrupt/truncated.</li>
 *   <li>{@link #write} throws on any failure; callers treat that as "no cache"
 *       and continue.</li>
 *   <li>{@link #writeAsync} invalidates the previous cache <em>before</em>
 *       doing anything that can fail, so a crash mid-regeneration can never
 *       leave a stale .jbc that would be trusted on the next startup.</li>
 * </ul>
 *
 * <p>All methods are static; this class is not instantiable.</p>
 */
public final class BinaryDatabaseCache {

    /*
     * Format v2, in order:
     *   magic/version, source size/mtime/SHA-256, parser preferences,
     *   parser-result header, custom entry types, preamble, BibTeX strings,
     *   entries, metadata, optional group tree.
     *
     * The reader and writer must evolve together. Any layout change requires a
     * FORMAT_VERSION bump rather than attempting to reinterpret older caches.
     */
    private static final Logger LOGGER = Logger.getLogger(BinaryDatabaseCache.class.getName());

    /** Identifies this file family before any variable-length data is read. */
    private static final int MAGIC = 0x4a424331; // "JBC1"

    /**
     * Binary layout version. Increment this whenever the serialized field order
     * or interpretation changes; older caches will then be ignored and rebuilt.
     */
    private static final int FORMAT_VERSION = 2;
    private static final int SHA256_LENGTH = 32;

    /* Defensive limits for values read from a corrupt or incompatible cache. */
    private static final int MAX_STRING_BYTES = 256 * 1024 * 1024;
    private static final int MAX_ENTRIES = 5_000_000;
    private static final int MAX_FIELDS_PER_ENTRY = 100_000;
    private static final int MAX_BIBTEX_STRINGS = 1_000_000;
    private static final int MAX_CUSTOM_TYPES = 100_000;
    private static final int MAX_WARNINGS = 1_000_000;
    private static final int MAX_METADATA_KEYS = 100_000;
    private static final int MAX_METADATA_VALUES = 1_000_000;

    private static final Pattern VERSION_PATTERN = Pattern.compile("([0-9]+)\\.([0-9]+).*");
    private static final Pattern VERSION_PATTERN_2 = Pattern.compile("([0-9]+)\\.([0-9]+)\\.([0-9]+).*");

    /*
     * Per-file verification state survives the fast cache load and coordinates
     * background verification with the save barrier.
     */
    private static final ConcurrentHashMap<String, CacheVerification> VERIFICATIONS =
            new ConcurrentHashMap<String, CacheVerification>();

    /*
     * A monotonically increasing generation prevents an older asynchronous
     * cache writer from replacing the result of a newer save.
     */
    private static final ConcurrentHashMap<String, Long> ASYNC_WRITE_GENERATIONS =
            new ConcurrentHashMap<String, Long>();
    private static final AtomicLong ASYNC_WRITE_SEQUENCE = new AtomicLong();
    private static final Object ASYNC_WRITE_LOCK = new Object();

    /** Lifecycle of a cache's SHA-256 verification. */
    public enum VerificationStatus {
        /** Verification has not been started yet. */
        PENDING,
        /** A background verifier is currently hashing the .bib file. */
        VERIFYING,
        /** The .bib file matches the cached snapshot. */
        VERIFIED,
        /** The .bib file no longer matches; the cache must not be trusted. */
        STALE,
        /** Verification could not be completed (I/O error, etc.). */
        FAILED
    }

    /** Receives the result of the optional background SHA-256 verification. */
    public interface VerificationListener {
        void verificationCompleted(File bibFile, VerificationStatus status, String message);
    }

    private BinaryDatabaseCache() {
    }

    /** Returns the companion cache path, for example {@code library.bib.jbc}. */
    public static File getCacheFile(File bibFile) {
        return new File(bibFile.getPath() + ".jbc");
    }

    /**
     * Loads a cache after inexpensive validation.
     *
     * <p>Magic/version, source length and timestamp, invalidation marker, and
     * parser-relevant preferences are checked before database objects are
     * reconstructed. A successful load records the stored SHA-256 digest as a
     * pending verification; the digest is intentionally not recomputed here so
     * cached startup remains fast.</p>
     *
     * @return reconstructed parser result, or {@code null} when the cache must
     *         be ignored and the caller should parse the {@code .bib} file
     * @throws IOException if a cache that passed the initial checks is corrupt
     *         or truncated
     */
    public static ParserResult loadIfValid(File bibFile) throws IOException {
        if ((bibFile == null) || !bibFile.isFile()) {
            return null;
        }

        File cacheFile = getCacheFile(bibFile);
        // A marker file means a save/async-write is in flight or was
        // interrupted; the .jbc next to it must not be trusted.
        if (getInvalidationMarkerFile(bibFile).isFile()) {
            return null;
        }
        if (!cacheFile.isFile()) {
            return null;
        }

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new FileInputStream(cacheFile), 256 * 1024))) {
            if (in.readInt() != MAGIC) {
                return null;
            }
            if (in.readInt() != FORMAT_VERSION) {
                return null;
            }

            long sourceLength = in.readLong();
            long sourceLastModified = in.readLong();
            byte[] sourceSha256 = new byte[SHA256_LENGTH];
            in.readFully(sourceSha256);
            // Cheap staleness check: if length or mtime differ, the cache is
            // definitively out of date; drop the verification entry too.
            if ((sourceLength != bibFile.length())
                    || (sourceLastModified != bibFile.lastModified())) {
                clearVerification(bibFile);
                return null;
            }

            // Parser settings are part of cache identity: a cache produced
            // under different settings would decode fields differently.
            if (in.readBoolean() != Globals.prefs.getBoolean("biblatexMode")) {
                return null;
            }
            if (in.readBoolean() != Globals.prefs.getBoolean("autoDoubleBraces")) {
                return null;
            }
            String bracesAroundCapitals = readString(in);
            if (!bracesAroundCapitals.equals(Globals.prefs.get("putBracesAroundCapitals"))) {
                return null;
            }

            String encoding = readNullableString(in);
            String jabrefVersion = readNullableString(in);
            int jabrefMajorVersion = in.readInt();
            int jabrefMinorVersion = in.readInt();
            int jabrefMinor2Version = in.readInt();

            int warningCount = readCount(in, MAX_WARNINGS, "warning count");
            List<String> warnings = new ArrayList<String>(warningCount);
            for (int i = 0; i < warningCount; i++) {
                warnings.add(readString(in));
            }

            BibtexDatabase database = new BibtexDatabase();
            HashMap<String, BibtexEntryType> entryTypes = readCustomEntryTypes(in);
            ParserResult result = new ParserResult(database, null, entryTypes);
            result.setEncoding(encoding);
            result.setFile(bibFile);
            result.setJabrefVersion(jabrefVersion);
            result.setJabrefMajorVersion(jabrefMajorVersion);
            result.setJabrefMinorVersion(jabrefMinorVersion);
            result.setJabrefMinor2Version(jabrefMinor2Version);
            for (String warning : warnings) {
                result.addWarning(warning);
            }

            database.setPreamble(readNullableString(in));
            readBibtexStrings(in, database);
            readEntries(in, database, entryTypes, result);

            MetaData metaData = readMetaData(in, database, bibFile);
            result.setMetaData(metaData);

            // Defer the SHA-256 check: register the snapshot as PENDING so a
            // later startBackgroundVerification()/verifyBeforeSave() can
            // confirm the .bib still hashes to the same bytes.
            rememberPendingVerification(bibFile, sourceLength, sourceLastModified, sourceSha256);
            return result;
        } catch (EOFException ex) {
            throw new IOException("Truncated binary cache: " + cacheFile.getPath(), ex);
        } catch (KeyCollisionException ex) {
            throw new IOException("Invalid binary cache: duplicate internal ID", ex);
        } catch (RuntimeException ex) {
            // Length/count validation failures surface as IOException; other
            // runtime errors here mean the bytes are not a JBC1 file at all.
            throw new IOException("Invalid binary cache: " + ex.getMessage(), ex);
        }
    }

    /**
     * Writes a cache for a freshly parsed BibTeX file.
     *
     * <p>This path is used when no valid cache was available during open. It
     * snapshots the source file, serializes the parser result, rechecks the
     * source, and replaces the cache only if the source stayed unchanged.</p>
     */
    public static void write(File bibFile, ParserResult result) throws IOException {
        if ((result == null) || (result.getDatabase() == null) || (result.getMetaData() == null)) {
            throw new IOException("Cannot cache an incomplete parser result");
        }

        CacheHeaderData header = new CacheHeaderData();
        header.encoding = result.getEncoding();
        header.jabrefVersion = result.getJabrefVersion();
        header.jabrefMajorVersion = result.getJabrefMajorVersion();
        header.jabrefMinorVersion = result.getJabrefMinorVersion();
        header.jabrefMinor2Version = result.getJabrefMinor2Version();
        header.warnings = result.warnings();

        Map<String, BibtexEntryType> customTypes = collectParsedCustomEntryTypes(
                result.getEntryTypes());
        writeInternal(bibFile, result.getDatabase(), result.getMetaData(), customTypes, header);
    }

    /**
     * Queues cache regeneration after a successful normal {@code .bib} save.
     *
     * <p>The source file has already been committed when this method is called.
     * A compact immutable snapshot of the in-memory database is prepared
     * synchronously; hashing and cache I/O then continue on a low-priority
     * daemon thread.</p>
     *
     * <p>The previous cache is invalidated before snapshot preparation. A
     * {@code .jbc.invalid} marker therefore survives a crash or shutdown until
     * the replacement cache has been completely written. Generation numbers
     * ensure that a cache task started by an older save cannot replace one from
     * a newer save.</p>
     */
    public static void writeAsync(File bibFile, BibtexDatabase database,
            MetaData metaData, String encoding) throws IOException {
        if ((bibFile == null) || !bibFile.isFile()) {
            throw new IOException("Source BibTeX file does not exist");
        }

        final String key = fileKey(bibFile);
        final long generation;
        final CacheVerification verification = new CacheVerification(
                0L, 0L, null, VerificationStatus.VERIFYING);

        // Invalidate the previous cache before doing anything that can fail.
        // Once the .bib has been committed, an older .jbc must never be used
        // on the next startup, even if preparing the replacement cache fails.
        synchronized (ASYNC_WRITE_LOCK) {
            invalidateExistingCache(bibFile);
            generation = ASYNC_WRITE_SEQUENCE.incrementAndGet();
            ASYNC_WRITE_GENERATIONS.put(key, generation);
            VERIFICATIONS.put(key, verification);
        }

        // Snapshot the in-memory model on the calling thread; this is the
        // only part that touches live JabRef objects.
        CacheWriteData preparedData = null;
        IOException preparationError = null;
        try {
            if ((database == null) || (metaData == null)) {
                throw new IOException("Cannot cache a null database or metadata object");
            }
            if (!metaData.isGroupTreeValid()) {
                throw new IOException("Metadata group tree is invalid; cache not written");
            }
            preparedData = createWriteData(database, metaData, encoding);
        } catch (IOException ex) {
            preparationError = ex;
        } catch (RuntimeException ex) {
            preparationError = new IOException("Could not snapshot database for binary cache", ex);
        }

        final CacheWriteData data = preparedData;
        final IOException snapshotError = preparationError;

        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                SourceSnapshot snapshot;
                try {
                    // Hash the just-committed .bib. This is the source of
                    // truth the cache will be validated against later.
                    snapshot = createStableSnapshot(bibFile);
                    publishVerifiedSnapshot(verification, snapshot);
                } catch (IOException ex) {
                    failPendingVerification(verification,
                            "Could not verify newly saved BibTeX file: " + ex.getMessage());
                    LOGGER.warning("Could not prepare binary database cache for '"
                            + bibFile.getPath() + "': " + ex.getMessage());
                    return;
                }

                // A newer write superseded us while we were hashing.
                if (!isCurrentAsyncGeneration(key, generation)) {
                    return;
                }
                if (snapshotError != null) {
                    LOGGER.warning("Binary database cache remains invalid for '"
                            + bibFile.getPath() + "': " + snapshotError.getMessage());
                    return;
                }

                try {
                    writePreparedInternal(bibFile, data, snapshot, key, generation);
                    if (isCurrentAsyncGeneration(key, generation)) {
                        LOGGER.info("Wrote binary database cache: "
                                + getCacheFile(bibFile).getPath());
                    }
                } catch (SourceChangedException ex) {
                    if (isCurrentAsyncGeneration(key, generation)) {
                        markVerificationStale(verification, ex.getMessage());
                        LOGGER.info("Skipped stale binary database cache for '"
                                + bibFile.getPath() + "': " + ex.getMessage());
                    }
                } catch (IOException ex) {
                    LOGGER.warning("Could not write binary database cache for '"
                            + bibFile.getPath() + "': " + ex.getMessage());
                }
            }
        }, "JBC writer: " + bibFile.getName());
        writer.setDaemon(true);
        writer.setPriority(Thread.MIN_PRIORITY);
        writer.start();
    }

    /**
     * Captures the mutable database state needed by the background writer.
     * Strings are immutable, so the snapshot mainly copies collection structure
     * and references rather than duplicating all bibliography text.
     */
    private static CacheWriteData createWriteData(BibtexDatabase database,
            MetaData metaData, String encoding) throws IOException {
        CacheHeaderData header = new CacheHeaderData();
        header.encoding = encoding;
        header.jabrefVersion = GUIGlobals.version;
        setVersionNumbers(header, GUIGlobals.version);
        header.warnings = new String[0];

        List<BibtexEntry> sourceEntries = database.getEntriesSnapshot();
        Map<String, BibtexEntry> keyWinners = database.getKeyToEntryMapSnapshot();

        // Partition entries into "non-winners" (duplicate cite keys that lose
        // the database lookup) and "winners". The cache write order must put
        // non-winners first so that replaying insertEntry() reproduces the
        // same winner for each duplicate key.
        List<EntrySnapshot> nonWinners = new ArrayList<EntrySnapshot>(sourceEntries.size());
        List<EntrySnapshot> winners = new ArrayList<EntrySnapshot>(sourceEntries.size());
        LinkedHashMap<String, CustomTypeSnapshot> customTypes =
                new LinkedHashMap<String, CustomTypeSnapshot>();

        for (BibtexEntry entry : sourceEntries) {
            EntrySnapshot entrySnapshot = snapshotEntry(entry);
            String citeKey = entry.getCiteKey();
            if ((citeKey != null) && (keyWinners.get(citeKey) != entry)) {
                nonWinners.add(entrySnapshot);
            } else {
                winners.add(entrySnapshot);
            }

            // Collect custom types actually referenced by entries. Only types
            // that are not standard built-ins are cached.
            BibtexEntryType type = entry.getType();
            if ((type instanceof CustomEntryType)
                    && (BibtexEntryType.getStandardType(type.getName()) == null)) {
                CustomEntryType custom = (CustomEntryType) type;
                customTypes.put(type.getName().toLowerCase(Locale.US),
                        new CustomTypeSnapshot(custom.getName(),
                                custom.getRequiredFieldsString(),
                                join(custom.getOptionalFields(), ";")));
            }
        }

        // Preserve the non-winners-first ordering established above.
        List<EntrySnapshot> entries = new ArrayList<EntrySnapshot>(sourceEntries.size());
        entries.addAll(nonWinners);
        entries.addAll(winners);

        List<BibtexStringSnapshot> strings = new ArrayList<BibtexStringSnapshot>();
        for (BibtexString string : database.getStringValuesSnapshot()) {
            strings.add(new BibtexStringSnapshot(string.getId(), string.getName(),
                    string.getContent()));
        }

        List<MetaDataSnapshot> meta = new ArrayList<MetaDataSnapshot>();
        for (String metaKey : metaData) {
            List<String> values = metaData.getData(metaKey);
            meta.add(new MetaDataSnapshot(metaKey,
                    values == null ? null : values.toArray(new String[values.size()])));
        }

        GroupTreeNode groups = metaData.getGroups();
        String groupTree = null;
        if ((groups != null) && (groups.getChildCount() > 0)) {
            groupTree = groups.getTreeAsString();
        }

        return new CacheWriteData(
                Globals.prefs.getBoolean("biblatexMode"),
                Globals.prefs.getBoolean("autoDoubleBraces"),
                Globals.prefs.get("putBracesAroundCapitals"),
                header,
                new ArrayList<CustomTypeSnapshot>(customTypes.values()),
                database.getPreamble(), strings, entries, meta, groupTree);
    }

    /** Copies an entry's id, type and all fields into an immutable snapshot. */
    private static EntrySnapshot snapshotEntry(BibtexEntry entry) {
        Collection<String> fields = entry.getAllFields();
        String[] names = new String[fields.size()];
        String[] values = new String[fields.size()];
        int index = 0;
        for (String field : fields) {
            names[index] = field;
            values[index] = entry.getField(field);
            index++;
        }
        return new EntrySnapshot(entry.getId(), entry.getType().getName(), names, values);
    }

    /**
     * Serializes a prepared snapshot and publishes it only if both the source
     * snapshot and asynchronous generation are still current.
     */
    private static void writePreparedInternal(File bibFile, CacheWriteData data,
            SourceSnapshot snapshot, String key, long generation) throws IOException {
        File cacheFile = getCacheFile(bibFile);
        File parent = cacheFile.getAbsoluteFile().getParentFile();
        File tempFile = File.createTempFile(cacheFile.getName() + ".", ".tmp", parent);
        boolean moved = false;

        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                    new FileOutputStream(tempFile), 256 * 1024))) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT_VERSION);
                out.writeLong(snapshot.length);
                out.writeLong(snapshot.lastModified);
                out.write(snapshot.sha256);

                out.writeBoolean(data.biblatexMode);
                out.writeBoolean(data.autoDoubleBraces);
                writeString(out, data.bracesAroundCapitals);
                writeCacheHeader(out, data.header);
                writeCustomEntryTypes(out, data.customTypes);
                writeNullableString(out, data.preamble);
                writeBibtexStrings(out, data.strings);
                writeEntries(out, data.entries);
                writeMetaData(out, data.metaData, data.groupTree);
            }

            // Re-check generation and source identity before committing. The
            // source check is what makes a cache "wrong file, wrong content"
            // safe: if the .bib changed under us, we drop the temp file.
            if (!isCurrentAsyncGeneration(key, generation)) {
                return;
            }
            if (!sourceStillMatchesSnapshot(bibFile, snapshot)) {
                throw new SourceChangedException(
                        "Source BibTeX file changed while binary cache was being written");
            }
            if (!isCurrentAsyncGeneration(key, generation)) {
                return;
            }

            synchronized (ASYNC_WRITE_LOCK) {
                if (!isCurrentAsyncGeneration(key, generation)) {
                    return;
                }
                try {
                    Files.move(tempFile.toPath(), cacheFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    // Some filesystems cannot do atomic moves; plain replace
                    // is still safe because the marker file gates trust.
                    Files.move(tempFile.toPath(), cacheFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                moved = true;
                deleteInvalidationMarker(bibFile);
            }
        } finally {
            if (!moved) {
                tempFile.delete();
            }
        }
    }

    /** Writes parser-result metadata that must survive a cache round trip. */
    private static void writeCacheHeader(DataOutputStream out, CacheHeaderData header)
            throws IOException {
        writeNullableString(out, header.encoding);
        writeNullableString(out, header.jabrefVersion);
        out.writeInt(header.jabrefMajorVersion);
        out.writeInt(header.jabrefMinorVersion);
        out.writeInt(header.jabrefMinor2Version);

        String[] warnings = header.warnings != null ? header.warnings : new String[0];
        out.writeInt(warnings.length);
        for (String warning : warnings) {
            writeString(out, warning);
        }
    }

    /** True if {@code generation} is still the newest async write for {@code key}. */
    private static boolean isCurrentAsyncGeneration(String key, long generation) {
        Long current = ASYNC_WRITE_GENERATIONS.get(key);
        return (current != null) && (current.longValue() == generation);
    }

    /**
     * Marker used to reject a cache while an asynchronous replacement is in
     * progress or when cache preparation failed after the source was saved.
     */
    private static File getInvalidationMarkerFile(File bibFile) {
        return new File(getCacheFile(bibFile).getPath() + ".invalid");
    }

    /** Creates (or truncates) the invalidation marker, creating parent dirs as needed. */
    private static void createInvalidationMarker(File bibFile) throws IOException {
        File marker = getInvalidationMarkerFile(bibFile);
        File parent = marker.getAbsoluteFile().getParentFile();
        if ((parent != null) && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create cache marker directory");
        }
        try (FileOutputStream out = new FileOutputStream(marker, false)) {
            out.write(0);
        }
    }

    /**
     * Makes any existing cache unusable. Prefer a marker so the old cache can
     * remain on disk until its replacement is ready; fall back to deleting or
     * truncating the cache if a marker cannot be created.
     */
    private static void invalidateExistingCache(File bibFile) throws IOException {
        try {
            createInvalidationMarker(bibFile);
            return;
        } catch (IOException markerError) {
            File cacheFile = getCacheFile(bibFile);
            if (!cacheFile.exists() || cacheFile.delete()) {
                return;
            }
            try (FileOutputStream out = new FileOutputStream(cacheFile, false)) {
                out.write(0);
                return;
            } catch (IOException cacheError) {
                markerError.addSuppressed(cacheError);
                throw markerError;
            }
        }
    }

    /** Removes the invalidation marker; failure is logged but not fatal. */
    private static void deleteInvalidationMarker(File bibFile) {
        File marker = getInvalidationMarkerFile(bibFile);
        if (marker.exists() && !marker.delete()) {
            LOGGER.warning("Could not delete binary cache invalidation marker: "
                    + marker.getPath());
        }
    }

    /** Marks a verification as VERIFIED and releases anyone waiting on it. */
    private static void publishVerifiedSnapshot(CacheVerification verification,
            SourceSnapshot snapshot) {
        synchronized (verification) {
            verification.length = snapshot.length;
            verification.lastModified = snapshot.lastModified;
            verification.sha256 = snapshot.sha256.clone();
            verification.status = VerificationStatus.VERIFIED;
            verification.message = null;
            verification.done.countDown();
        }
    }

    /** Marks a verification as FAILED and releases anyone waiting on it. */
    private static void failPendingVerification(CacheVerification verification, String message) {
        synchronized (verification) {
            verification.status = VerificationStatus.FAILED;
            verification.message = message;
            verification.done.countDown();
        }
    }

    /** Marks a verification as STALE and releases anyone waiting on it. */
    private static void markVerificationStale(CacheVerification verification, String message) {
        synchronized (verification) {
            verification.status = VerificationStatus.STALE;
            verification.message = message;
            verification.done.countDown();
        }
    }

    /**
     * Synchronous write path used by {@link #write}. Derives entry ordering
     * itself (non-winners first) rather than relying on a pre-built snapshot.
     */
    private static void writeInternal(File bibFile, BibtexDatabase database,
            MetaData metaData, Map<String, BibtexEntryType> customTypes,
            CacheHeaderData header) throws IOException {
        if ((bibFile == null) || !bibFile.isFile()) {
            throw new IOException("Source BibTeX file does not exist");
        }
        if (!metaData.isGroupTreeValid()) {
            throw new IOException("Metadata group tree is invalid; cache not written");
        }

        SourceSnapshot snapshot = createStableSnapshot(bibFile);

        File cacheFile = getCacheFile(bibFile);
        File parent = cacheFile.getAbsoluteFile().getParentFile();
        File tempFile = File.createTempFile(cacheFile.getName() + ".", ".tmp", parent);
        boolean moved = false;

        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                    new FileOutputStream(tempFile), 256 * 1024))) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT_VERSION);
                out.writeLong(snapshot.length);
                out.writeLong(snapshot.lastModified);
                out.write(snapshot.sha256);

                out.writeBoolean(Globals.prefs.getBoolean("biblatexMode"));
                out.writeBoolean(Globals.prefs.getBoolean("autoDoubleBraces"));
                writeString(out, Globals.prefs.get("putBracesAroundCapitals"));
                writeCacheHeader(out, header);

                writeCustomEntryTypes(out, customTypes);
                writeNullableString(out, database.getPreamble());
                writeBibtexStrings(out, database.getStringValues());
                writeEntries(out, database);
                writeMetaData(out, metaData);
            }

            if (!sourceStillMatchesSnapshot(bibFile, snapshot)) {
                throw new IOException("Source BibTeX file changed while binary cache was being written");
            }

            try {
                Files.move(tempFile.toPath(), cacheFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tempFile.toPath(), cacheFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            rememberVerifiedSnapshot(bibFile, snapshot);
            deleteInvalidationMarker(bibFile);
        } finally {
            if (!moved) {
                tempFile.delete();
            }
        }
    }


    /** Returns the verification state for a file loaded from or associated with a cache. */
    public static VerificationStatus getVerificationStatus(File bibFile) {
        CacheVerification verification = VERIFICATIONS.get(fileKey(bibFile));
        return verification == null ? null : verification.status;
    }

    /**
     * Starts SHA-256 verification for a cache loaded through the fast metadata
     * path. Calling this for a file with no pending verification is a no-op.
     */
    public static void startBackgroundVerification(File bibFile, VerificationListener listener) {
        CacheVerification verification = VERIFICATIONS.get(fileKey(bibFile));
        if (verification == null) {
            return;
        }

        synchronized (verification) {
            if (verification.status != VerificationStatus.PENDING) {
                return;
            }
            verification.status = VerificationStatus.VERIFYING;
            verification.listener = listener;
            Thread verifier = new Thread(new Runnable() {
                @Override
                public void run() {
                    completeVerification(bibFile, verification);
                }
            }, "JBC SHA-256 verifier: " + bibFile.getName());
            verifier.setDaemon(true);
            verifier.setPriority(Thread.MIN_PRIORITY);
            verifier.start();
        }
    }

    /**
     * Save barrier for a database that may have been reconstructed from cache.
     *
     * <p>Any pending background verification is completed first. The source is
     * then checked again immediately before the save, because an earlier
     * successful verification does not prove that the file remained unchanged.</p>
     *
     * @return {@code true} only when the source still matches the recorded
     *         length, modification time, and SHA-256 digest
     * @throws IOException when the verification itself cannot be completed
     */
    public static boolean verifyBeforeSave(File bibFile) throws IOException {
        CacheVerification verification = VERIFICATIONS.get(fileKey(bibFile));
        if (verification == null) {
            return true;
        }

        // Kick off hashing if nobody has yet.
        if (verification.status == VerificationStatus.PENDING) {
            startBackgroundVerification(bibFile, null);
        }

        if (verification.status == VerificationStatus.VERIFYING) {
            try {
                verification.done.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while verifying binary cache", ex);
            }
        }

        if (verification.status == VerificationStatus.STALE) {
            return false;
        }
        if (verification.status == VerificationStatus.FAILED) {
            throw new IOException(verification.message != null
                    ? verification.message : "Binary cache verification failed");
        }

        // Re-check immediately before each save. A successful background check
        // only proves the file at the time that check ran.
        VerificationResult result = verifySnapshot(bibFile, verification);
        applyVerificationResult(bibFile, verification, result);
        if (result.status == VerificationStatus.FAILED) {
            throw new IOException(result.message);
        }
        return result.status == VerificationStatus.VERIFIED;
    }

    /** Runs on the background verifier thread; notifies the listener on completion. */
    private static void completeVerification(File bibFile, CacheVerification verification) {
        VerificationResult result = verifySnapshot(bibFile, verification);
        applyVerificationResult(bibFile, verification, result);
        VerificationListener listener = verification.listener;
        if (listener != null) {
            listener.verificationCompleted(bibFile, result.status, result.message);
        }
    }

    /**
     * Cheap metadata check first, then SHA-256. Returns VERIFIED only if all
     * three (length, lastModified, digest) match the recorded snapshot.
     */
    private static VerificationResult verifySnapshot(File bibFile, CacheVerification verification) {
        try {
            if (!bibFile.isFile()) {
                return new VerificationResult(VerificationStatus.STALE,
                        "Source BibTeX file no longer exists");
            }
            if ((bibFile.length() != verification.length)
                    || (bibFile.lastModified() != verification.lastModified)) {
                return new VerificationResult(VerificationStatus.STALE,
                        "Source BibTeX file metadata changed after cache creation");
            }
            byte[] actual = sha256(bibFile);
            if (!Arrays.equals(actual, verification.sha256)) {
                return new VerificationResult(VerificationStatus.STALE,
                        "Source BibTeX file content does not match the binary cache");
            }
            return new VerificationResult(VerificationStatus.VERIFIED, null);
        } catch (IOException ex) {
            return new VerificationResult(VerificationStatus.FAILED,
                    "Could not verify source BibTeX file: " + ex.getMessage());
        }
    }

    /** Stores the outcome of a verification and releases waiters. */
    private static void applyVerificationResult(File bibFile, CacheVerification verification,
            VerificationResult result) {
        synchronized (verification) {
            verification.status = result.status;
            verification.message = result.message;
            verification.done.countDown();
        }
    }

    /** Registers a loaded cache as PENDING verification (digest not yet checked). */
    private static void rememberPendingVerification(File bibFile, long length,
            long lastModified, byte[] sha256) {
        VERIFICATIONS.put(fileKey(bibFile),
                new CacheVerification(length, lastModified, sha256, VerificationStatus.PENDING));
    }

    /** Registers a just-written cache as already VERIFIED (we hashed it ourselves). */
    private static void rememberVerifiedSnapshot(File bibFile, SourceSnapshot snapshot) {
        CacheVerification verification = new CacheVerification(snapshot.length,
                snapshot.lastModified, snapshot.sha256, VerificationStatus.VERIFIED);
        verification.done.countDown();
        VERIFICATIONS.put(fileKey(bibFile), verification);
    }

    /** Forgets any verification state for the given .bib file. */
    private static void clearVerification(File bibFile) {
        if (bibFile != null) {
            VERIFICATIONS.remove(fileKey(bibFile));
        }
    }

    /** Canonical absolute path used as the map key for all per-file state. */
    private static String fileKey(File file) {
        return file.getAbsoluteFile().toPath().normalize().toString();
    }

    /**
     * Computes a digest and rejects the snapshot if cheap file metadata changes
     * during hashing. This prevents publishing a checksum for a moving target.
     */
    private static SourceSnapshot createStableSnapshot(File bibFile) throws IOException {
        long lengthBefore = bibFile.length();
        long modifiedBefore = bibFile.lastModified();
        byte[] digest = sha256(bibFile);
        long lengthAfter = bibFile.length();
        long modifiedAfter = bibFile.lastModified();
        if ((lengthBefore != lengthAfter) || (modifiedBefore != modifiedAfter)) {
            throw new IOException("Source BibTeX file changed while calculating cache checksum");
        }
        return new SourceSnapshot(lengthAfter, modifiedAfter, digest);
    }

    /** Rechecks metadata and SHA-256 before a newly written cache is published. */
    private static boolean sourceStillMatchesSnapshot(File bibFile, SourceSnapshot snapshot)
            throws IOException {
        if ((bibFile.length() != snapshot.length)
                || (bibFile.lastModified() != snapshot.lastModified)) {
            return false;
        }
        return Arrays.equals(snapshot.sha256, sha256(bibFile));
    }

    /** Computes SHA-256 using a buffered streaming read. */
    private static byte[] sha256(File file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("SHA-256 is not available", ex);
        }

        byte[] buffer = new byte[256 * 1024];
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file),
                buffer.length)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    /** Keeps only custom (non-standard) types from a parser result's type map. */
    private static Map<String, BibtexEntryType> collectParsedCustomEntryTypes(
            Map<String, BibtexEntryType> parserTypes) {
        Map<String, BibtexEntryType> result = new LinkedHashMap<String, BibtexEntryType>();
        if (parserTypes == null) {
            return result;
        }
        for (BibtexEntryType type : parserTypes.values()) {
            if (type instanceof CustomEntryType) {
                result.put(type.getName().toLowerCase(Locale.US), type);
            }
        }
        return result;
    }

    /**
     * Walks a database's entries to collect custom (non-standard) types.
     * Note: unlike {@link #collectParsedCustomEntryTypes} this is driven by
     * the types actually referenced by entries.
     */
    private static Map<String, BibtexEntryType> collectSavedCustomEntryTypes(
            BibtexDatabase database) {
        Map<String, BibtexEntryType> result = new LinkedHashMap<String, BibtexEntryType>();
        for (BibtexEntry entry : database.getEntries()) {
            BibtexEntryType type = entry.getType();
            if ((type instanceof CustomEntryType)
                    && (BibtexEntryType.getStandardType(type.getName()) == null)) {
                result.put(type.getName().toLowerCase(Locale.US), type);
            }
        }
        return result;
    }

    /** Writes custom types from live objects (synchronous write path). */
    private static void writeCustomEntryTypes(DataOutputStream out,
            Map<String, BibtexEntryType> customTypes) throws IOException {
        out.writeInt(customTypes.size());
        for (BibtexEntryType type : customTypes.values()) {
            CustomEntryType custom = (CustomEntryType) type;
            writeString(out, custom.getName());
            writeString(out, custom.getRequiredFieldsString());
            writeString(out, join(custom.getOptionalFields(), ";"));
        }
    }

    /** Writes custom types from immutable snapshots (async write path). */
    private static void writeCustomEntryTypes(DataOutputStream out,
            List<CustomTypeSnapshot> customTypes) throws IOException {
        out.writeInt(customTypes.size());
        for (CustomTypeSnapshot custom : customTypes) {
            writeString(out, custom.name);
            writeString(out, custom.requiredFields);
            writeString(out, custom.optionalFields);
        }
    }

    /** Reads the custom type table and indexes it by lower-cased name. */
    private static HashMap<String, BibtexEntryType> readCustomEntryTypes(DataInputStream in)
            throws IOException {
        int count = readCount(in, MAX_CUSTOM_TYPES, "custom entry type count");
        HashMap<String, BibtexEntryType> result = new HashMap<String, BibtexEntryType>();
        for (int i = 0; i < count; i++) {
            String name = readString(in);
            String required = readString(in);
            String optional = readString(in);
            CustomEntryType type = new CustomEntryType(name, required, optional);
            result.put(name.toLowerCase(Locale.US), type);
        }
        return result;
    }

    /** Writes BibTeX @string macros from live objects. */
    private static void writeBibtexStrings(DataOutputStream out,
            Collection<BibtexString> strings) throws IOException {
        out.writeInt(strings.size());
        for (BibtexString string : strings) {
            writeString(out, string.getId());
            writeString(out, string.getName());
            writeString(out, string.getContent());
        }
    }

    /** Writes BibTeX @string macros from snapshots. */
    private static void writeBibtexStrings(DataOutputStream out,
            List<BibtexStringSnapshot> strings) throws IOException {
        out.writeInt(strings.size());
        for (BibtexStringSnapshot string : strings) {
            writeString(out, string.id);
            writeString(out, string.name);
            writeString(out, string.content);
        }
    }

    /** Reads @string macros into the database. */
    private static void readBibtexStrings(DataInputStream in, BibtexDatabase database)
            throws IOException, KeyCollisionException {
        int count = readCount(in, MAX_BIBTEX_STRINGS, "BibTeX string count");
        for (int i = 0; i < count; i++) {
            String id = readString(in);
            String name = readString(in);
            String content = readString(in);
            database.addString(new BibtexString(id, name, content));
        }
    }

    /**
     * Writes entries while preserving the current winner for duplicate citation
     * keys. Since insertion updates the lookup winner, non-winners are emitted
     * before winners.
     */
    private static void writeEntries(DataOutputStream out, BibtexDatabase database)
            throws IOException {
        Collection<BibtexEntry> entries = database.getEntries();
        out.writeInt(entries.size());

        // For duplicate cite keys, preserve which entry currently wins in the
        // database key lookup by writing non-winning entries first.
        List<BibtexEntry> winners = new ArrayList<BibtexEntry>(entries.size());
        for (BibtexEntry entry : entries) {
            String citeKey = entry.getCiteKey();
            if ((citeKey != null) && (database.getEntryByKey(citeKey) != entry)) {
                writeEntry(out, entry);
            } else {
                winners.add(entry);
            }
        }
        for (BibtexEntry entry : winners) {
            writeEntry(out, entry);
        }
    }

    /**
     * Writes entries from pre-ordered snapshots. Does <em>not</em> reorder;
     * ordering is the responsibility of {@link #createWriteData}.
     */
    private static void writeEntries(DataOutputStream out,
            List<EntrySnapshot> entries) throws IOException {
        out.writeInt(entries.size());
        for (EntrySnapshot entry : entries) {
            writeString(out, entry.id);
            writeString(out, entry.typeName);
            out.writeInt(entry.fieldNames.length);
            for (int i = 0; i < entry.fieldNames.length; i++) {
                writeString(out, entry.fieldNames[i]);
                writeNullableString(out, entry.fieldValues[i]);
            }
        }
    }

    /** Writes one live entry: id, type name, then (name, value?) field pairs. */
    private static void writeEntry(DataOutputStream out, BibtexEntry entry) throws IOException {
        writeString(out, entry.getId());
        writeString(out, entry.getType().getName());

        Collection<String> fields = entry.getAllFields();
        out.writeInt(fields.size());
        for (String field : fields) {
            writeString(out, field);
            writeNullableString(out, entry.getField(field));
        }
    }

    /**
     * Reads entries into the database. Unknown entry types are downgraded to
     * {@code OTHER} but their original name is kept in a warning, so the user
     * can see what was lost.
     */
    private static void readEntries(DataInputStream in, BibtexDatabase database,
            Map<String, BibtexEntryType> customTypes, ParserResult result)
            throws IOException, KeyCollisionException {
        int entryCount = readCount(in, MAX_ENTRIES, "entry count");
        for (int i = 0; i < entryCount; i++) {
            String id = readString(in);
            String typeName = readString(in);
            BibtexEntryType type = resolveEntryType(typeName, customTypes);
            boolean unknownType = type instanceof UnknownEntryType;

            int fieldCount = readCount(in, MAX_FIELDS_PER_ENTRY, "field count");
            Map<String, String> fields = new HashMap<String, String>(Math.max(16, fieldCount * 2));
            for (int j = 0; j < fieldCount; j++) {
                fields.put(readString(in), readNullableString(in));
            }

            BibtexEntry entry = new BibtexEntry(id, unknownType ? BibtexEntryType.OTHER : type);
            entry.setField(fields);
            if (unknownType) {
                result.addWarning(Globals.lang("unknown entry type") + ": "
                        + typeName + ":" + entry.getField(BibtexFields.KEY_FIELD)
                        + " . " + Globals.lang("Type set to 'other'") + ".");
            }
            // insertEntry returns true on duplicate cite key; record it so the
            // caller can surface the same warning the parser would have.
            boolean duplicateKey = database.insertEntry(entry);
            if (duplicateKey) {
                result.addDuplicateKey(entry.getCiteKey());
            } else if ((entry.getCiteKey() == null) || entry.getCiteKey().isEmpty()) {
                result.addWarning(Globals.lang("empty BibTeX key") + ": "
                        + entry.getAuthorTitleYear(40) + " ("
                        + Globals.lang("grouping may not work for this entry") + ")");
            }
        }
    }

    /**
     * Resolves a type name to a standard type first, then a cached custom
     * type. Falls back to {@link UnknownEntryType} (which the caller downgrades
     * to {@code OTHER}) rather than dropping the name.
     */
    private static BibtexEntryType resolveEntryType(String name,
            Map<String, BibtexEntryType> customTypes) {
        BibtexEntryType type = BibtexEntryType.getType(name);
        if (type != null) {
            return type;
        }
        type = customTypes.get(name.toLowerCase(Locale.US));
        if (type != null) {
            return type;
        }
        // A cache written from a normal parser result should not contain an
        // unresolved type, but retaining its name is safer than losing it.
        return new UnknownEntryType(name);
    }

    /** Writes metadata (key -> values, with null-list sentinel) and the group tree. */
    private static void writeMetaData(DataOutputStream out, MetaData metaData)
            throws IOException {
        List<String> keys = new ArrayList<String>();
        for (String key : metaData) {
            keys.add(key);
        }
        out.writeInt(keys.size());
        for (String key : keys) {
            writeString(out, key);
            List<String> values = metaData.getData(key);
            if (values == null) {
                out.writeInt(-1);
            } else {
                out.writeInt(values.size());
                for (String value : values) {
                    writeNullableString(out, value);
                }
            }
        }

        GroupTreeNode groups = metaData.getGroups();
        if ((groups == null) || (groups.getChildCount() == 0)) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            writeString(out, groups.getTreeAsString());
        }
    }

    /** Snapshot variant of {@link #writeMetaData(DataOutputStream, MetaData)}. */
    private static void writeMetaData(DataOutputStream out,
            List<MetaDataSnapshot> metaData, String groupTree) throws IOException {
        out.writeInt(metaData.size());
        for (MetaDataSnapshot item : metaData) {
            writeString(out, item.key);
            if (item.values == null) {
                out.writeInt(-1);
            } else {
                out.writeInt(item.values.length);
                for (String value : item.values) {
                    writeNullableString(out, value);
                }
            }
        }

        if (groupTree == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            writeString(out, groupTree);
        }
    }

    /**
     * Reads metadata and, if present, reconstructs the group tree via
     * {@link VersionHandling#importGroups}. A failure to rebuild groups is
     * reported as a corrupt-cache IOException rather than silently dropping
     * the tree.
     */
    private static MetaData readMetaData(DataInputStream in,
            BibtexDatabase database, File bibFile) throws IOException {
        MetaData metaData = new MetaData();
        int keyCount = readCount(in, MAX_METADATA_KEYS, "metadata key count");
        for (int i = 0; i < keyCount; i++) {
            String key = readString(in);
            int valueCount = in.readInt();
            if (valueCount == -1) {
                continue;
            }
            validateCount(valueCount, MAX_METADATA_VALUES, "metadata value count");
            List<String> values = new ArrayList<String>(valueCount);
            for (int j = 0; j < valueCount; j++) {
                values.add(readNullableString(in));
            }
            metaData.putData(key, values);
        }

        if (in.readBoolean()) {
            String tree = readString(in);
            List<String> groupData = new ArrayList<String>();
            StringTokenizer tokenizer = new StringTokenizer(tree, "\n");
            while (tokenizer.hasMoreTokens()) {
                groupData.add(tokenizer.nextToken());
            }
            try {
                GroupTreeNode root = VersionHandling.importGroups(
                        groupData, database, VersionHandling.CURRENT_VERSION);
                metaData.setGroups(root);
            } catch (Exception ex) {
                throw new IOException("Could not reconstruct cached group tree", ex);
            }
        }

        metaData.setFile(bibFile);
        return metaData;
    }

    /** Reads a collection count only after enforcing the corresponding safety limit. */
    private static int readCount(DataInputStream in, int maximum, String what)
            throws IOException {
        int value = in.readInt();
        validateCount(value, maximum, what);
        return value;
    }

    /** Throws if {@code value} is negative or above {@code maximum}. */
    private static void validateCount(int value, int maximum, String what)
            throws IOException {
        if ((value < 0) || (value > maximum)) {
            throw new IOException("Invalid " + what + ": " + value);
        }
    }

    /** Writes a non-null UTF-8 string as a 32-bit byte length followed by bytes. */
    private static void writeString(DataOutputStream out, String value)
            throws IOException {
        if (value == null) {
            throw new IOException("Unexpected null string in cache data");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("String too large for binary cache: " + bytes.length + " bytes");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /** Reads the length-prefixed UTF-8 representation used by {@link #writeString}. */
    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if ((length < 0) || (length > MAX_STRING_BYTES)) {
            throw new IOException("Invalid string length in binary cache: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Writes a possibly-null string. {@code null} is encoded as length
     * {@code -1}; otherwise identical to {@link #writeString}.
     */
    private static void writeNullableString(DataOutputStream out, String value)
            throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("String too large for binary cache: " + bytes.length + " bytes");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /** Reads a string written by {@link #writeNullableString} ({@code -1} -> null). */
    private static String readNullableString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length == -1) {
            return null;
        }
        if ((length < 0) || (length > MAX_STRING_BYTES)) {
            throw new IOException("Invalid string length in binary cache: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Joins an array with a separator; null/empty input yields "". */
    private static String join(String[] values, String separator) {
        if ((values == null) || (values.length == 0)) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                result.append(separator);
            }
            result.append(values[i]);
        }
        return result.toString();
    }

    /** Parses "a.b[.c]" version components into the header's int fields. */
    private static void setVersionNumbers(CacheHeaderData header, String version) {
        if (version == null) {
            return;
        }
        Matcher matcher = VERSION_PATTERN.matcher(version);
        if (matcher.matches()) {
            header.jabrefMajorVersion = Integer.parseInt(matcher.group(1));
            header.jabrefMinorVersion = Integer.parseInt(matcher.group(2));
        }
        Matcher matcher2 = VERSION_PATTERN_2.matcher(version);
        if (matcher2.matches()) {
            header.jabrefMinor2Version = Integer.parseInt(matcher2.group(3));
        }
    }

    /** Immutable (length, mtime, SHA-256) identity of a .bib file. */
    private static final class SourceSnapshot {
        final long length;
        final long lastModified;
        final byte[] sha256;

        SourceSnapshot(long length, long lastModified, byte[] sha256) {
            this.length = length;
            this.lastModified = lastModified;
            this.sha256 = sha256 == null ? null : sha256.clone();
        }
    }

    /** Everything needed to write a cache, detached from live JabRef objects. */
    private static final class CacheWriteData {
        final boolean biblatexMode;
        final boolean autoDoubleBraces;
        final String bracesAroundCapitals;
        final CacheHeaderData header;
        final List<CustomTypeSnapshot> customTypes;
        final String preamble;
        final List<BibtexStringSnapshot> strings;
        final List<EntrySnapshot> entries;
        final List<MetaDataSnapshot> metaData;
        final String groupTree;

        CacheWriteData(boolean biblatexMode, boolean autoDoubleBraces,
                String bracesAroundCapitals, CacheHeaderData header,
                List<CustomTypeSnapshot> customTypes, String preamble,
                List<BibtexStringSnapshot> strings, List<EntrySnapshot> entries,
                List<MetaDataSnapshot> metaData, String groupTree) {
            this.biblatexMode = biblatexMode;
            this.autoDoubleBraces = autoDoubleBraces;
            this.bracesAroundCapitals = bracesAroundCapitals;
            this.header = header;
            this.customTypes = customTypes;
            this.preamble = preamble;
            this.strings = strings;
            this.entries = entries;
            this.metaData = metaData;
            this.groupTree = groupTree;
        }
    }

    /** Immutable snapshot of one custom entry type. */
    private static final class CustomTypeSnapshot {
        final String name;
        final String requiredFields;
        final String optionalFields;

        CustomTypeSnapshot(String name, String requiredFields, String optionalFields) {
            this.name = name;
            this.requiredFields = requiredFields;
            this.optionalFields = optionalFields;
        }
    }

    /** Immutable snapshot of one BibTeX @string macro. */
    private static final class BibtexStringSnapshot {
        final String id;
        final String name;
        final String content;

        BibtexStringSnapshot(String id, String name, String content) {
            this.id = id;
            this.name = name;
            this.content = content;
        }
    }

    /** Immutable snapshot of one entry: parallel name/value arrays. */
    private static final class EntrySnapshot {
        final String id;
        final String typeName;
        final String[] fieldNames;
        final String[] fieldValues;

        EntrySnapshot(String id, String typeName, String[] fieldNames, String[] fieldValues) {
            this.id = id;
            this.typeName = typeName;
            this.fieldNames = fieldNames;
            this.fieldValues = fieldValues;
        }
    }

    /** Immutable snapshot of one metadata key and its (possibly null) values. */
    private static final class MetaDataSnapshot {
        final String key;
        final String[] values;

        MetaDataSnapshot(String key, String[] values) {
            this.key = key;
            this.values = values;
        }
    }

    /** Thrown when the source .bib changed between snapshot and commit. */
    private static final class SourceChangedException extends IOException {
        SourceChangedException(String message) {
            super(message);
        }
    }

    /**
     * Mutable verification state for one .bib file. Fields are volatile and
     * mutated under {@code synchronized(this)}; {@link #done} is released
     * exactly once when a terminal status is reached.
     */
    private static final class CacheVerification {
        volatile long length;
        volatile long lastModified;
        volatile byte[] sha256;
        final CountDownLatch done = new CountDownLatch(1);
        volatile VerificationStatus status;
        volatile String message;
        volatile VerificationListener listener;

        CacheVerification(long length, long lastModified, byte[] sha256,
                VerificationStatus status) {
            this.length = length;
            this.lastModified = lastModified;
            this.sha256 = sha256 == null ? null : sha256.clone();
            this.status = status;
        }
    }

    /** Outcome of a verification pass. */
    private static final class VerificationResult {
        final VerificationStatus status;
        final String message;

        VerificationResult(VerificationStatus status, String message) {
            this.status = status;
            this.message = message;
        }
    }

    /** Header fields persisted in the cache (encoding, JabRef version, warnings). */
    private static final class CacheHeaderData {
        String encoding;
        String jabrefVersion;
        int jabrefMajorVersion;
        int jabrefMinorVersion;
        int jabrefMinor2Version;
        String[] warnings;
    }
}