/*  Copyright (C) 2003-2011 JabRef contributors.
    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along
    with this program; if not, write to the Free Software Foundation, Inc.,
    51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package net.sf.jabref.external;

import net.sf.jabref.*;

import java.io.File;
import java.io.IOException;
import java.io.FilenameFilter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;

/**
 * Created by IntelliJ IDEA. User: alver Date: Apr 12, 2008 Time: 1:46:44 PM To
 * change this template use File | Settings | File Templates.
 */
public class RegExpFileSearch {

    final static String EXT_MARKER = "__EXTENSION__";

    // UPDATE: cheap filename prefilter modes used before the full regular expression.
    private static final int PREFILTER_NONE = 0;
    private static final int PREFILTER_CONTAINS = 1;
    private static final int PREFILTER_STARTS_WITH = 2;

    public static void main(String[] args) {
        BibtexEntry entry = new BibtexEntry(Util.createNeutralId());
        entry.setField(BibtexFields.KEY_FIELD, "raffel01");
        entry.setField("year", "2001");
        ArrayList<String> extensions = new ArrayList<String>();
        extensions.add("pdf");
        extensions.add("ps");
        extensions.add("txt");
        List<File> dirs = new ArrayList<File>();
        dirs.add(new File("/home/alver/Desktop/Tromso_2008"));
        System.out.println(findFiles(entry, extensions, dirs,
                "**/[bibtexkey].*\\\\.[extension]"));
    }

    /**
     * Search for file links for a set of entries using regexp. Lists of
     * extensions and directories are given.
     *
     * @param entries The entries to search for.
     * @param extensions The extensions that are acceptable.
     * @param directories The root directories to search.
     * @param regExp The expression deciding which names are acceptable.
     * @return A map linking each given entry to a list of files matching the
     * given criteria.
     */
    public static Map<BibtexEntry, java.util.List<File>> findFilesForSet(Collection<BibtexEntry> entries,
            Collection<String> extensions, List<File> directories, String regExp) {

        // UPDATE: the original implementation called findFiles(...) once per entry,
        // which caused entries x full recursive directory traversal. The optimized
        // path below is limited to the common recursive form "**/<filename-regexp>".
        // Other patterns keep the old compatibility path.
        if (canUseBatchSearch(regExp)) {
            return findFilesForSetBatch(entries, extensions, directories, regExp);
        }

        Map<BibtexEntry, java.util.List<File>> res = new HashMap<BibtexEntry, List<File>>();
        for (BibtexEntry entry : entries) {
            res.put(entry, findFiles(entry, extensions, directories, regExp));
        }
        return res;
    }

    /**
     * Method for searching for files using regexp. A list of extensions and
     * directories can be given.
     *
     * @param entry The entry to search for.
     * @param extensions The extensions that are acceptable.
     * @param directories The root directories to search.
     * @param regularExpression The expression deciding which names are
     * acceptable.
     * @return A list of files paths matching the given criteria.
     */
    public static List<File> findFiles(BibtexEntry entry, Collection<String> extensions,
            Collection<File> directories, String regularExpression) {

        // UPDATE: use the same optimized code path for one-entry recursive searches.
        // This preserves the public API and avoids repeated pattern compilation.
        if (canUseBatchSearch(regularExpression)) {
            Map<BibtexEntry, List<File>> result = findFilesForSetBatch(
                    Collections.singleton(entry), extensions, directories, regularExpression);
            List<File> files = result.get(entry);
            return files == null ? Collections.<File>emptyList() : files;
        }

        String extensionRegExp = buildExtensionRegExp(extensions);
        return findFile(entry, null, directories, regularExpression, extensionRegExp, true);
    }

    /**
     * Searches the given directory and file name pattern for a file for the
     * bibtexentry.
     *
     * Used to fix:
     *
     * http://sourceforge.net/tracker/index.php?func=detail&aid=1503410&group_id=92314&atid=600309
     *
     * Requirements: - Be able to find the associated PDF in a set of given
     * directories. - Be able to return a relative path or absolute path. - Be
     * fast. - Allow for flexible naming schemes in the PDFs.
     *
     * Syntax scheme for file:
     * <ul>
     * <li>* Any subDir</li>
     * <li>** Any subDir (recursiv)</li>
     * <li>[key] Key from bibtex file and database</li>
     * <li>.* Anything else is taken to be a Regular expression.</li>
     * </ul>
     *
     * @param entry non-null
     * @param database non-null
     * @param dirs A set of root directories to start the search from. Paths are
     * returned relative to these directories if relative is set to true. These
     * directories will not be expanded or anything. Use the file attribute for
     * this.
     * @param file non-null
     *
     * @param relative whether to return relative file paths or absolute ones
     *
     * @return Will return the first file found to match the given criteria or
     * null if none was found.
     */
    public static List<File> findFile(BibtexEntry entry, BibtexDatabase database, Collection<File> dirs,
            String file, String extensionRegExp, boolean relative) {
        ArrayList<File> res = new ArrayList<File>();
        for (File directory : dirs) {
            if (directory == null) {
                continue;
            }
            List<File> tmp = findFile(entry, database, directory.getPath(), file, extensionRegExp, relative);
            if (tmp != null) {
                res.addAll(tmp);
            }
        }
        return res;
    }

    /**
     * Internal Version of findFile, which also accepts a current directory to
     * base the search on.
     *
     */
    public static List<File> findFile(BibtexEntry entry, BibtexDatabase database, String directory,
            String file, String extensionRegExp, boolean relative) {

        List<File> res;
        File root;
        if (directory == null) {
            root = new File(".");
        } else {
            root = new File(directory);
        }
        if (!root.exists()) {
            return null;
        }
        res = findFile(entry, database, root, file, extensionRegExp);

        if (relative && res.size() > 0) {
            try {
                // UPDATE: root.getCanonicalPath() used to be called once per match.
                // It can touch the filesystem, so compute it once per root search.
                String rootCanonicalPath = root.getCanonicalPath();
                for (int i = 0; i < res.size(); i++)
                    try {
                    /**
                     * [ 1601651 ] PDF subdirectory - missing first character
                     *
                     * http://sourceforge.net/tracker/index.php?func=detail&aid=1601651&group_id=92314&atid=600306
                     */
                    // Changed by M. Alver 2007.01.04:
                    // Remove first character if it is a directory separator character:
                    String tmp = res.get(i).getCanonicalPath().substring(rootCanonicalPath.length());
                    if ((tmp.length() > 1) && (tmp.charAt(0) == File.separatorChar)) {
                        tmp = tmp.substring(1);
                    }
                    res.set(i, new File(tmp));

                } catch (IOException e) {
                    e.printStackTrace();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return res;
    }

    /**
     * The actual work-horse. Will find absolute filepaths starting from the
     * given directory using the given regular expression string for search.
     */
    protected static List<File> findFile(BibtexEntry entry, BibtexDatabase database, File directory,
            String file, String extensionRegExp) {

        ArrayList<File> res = new ArrayList<File>();

        if (file.startsWith("/")) {
            directory = new File(".");
            file = file.substring(1);
        }

        // Escape handling...
        String[] fileParts = parseFileParts(file);

        if (fileParts.length == 0) {
            return res;
        }

        if (fileParts.length > 1) {

            for (int i = 0; i < fileParts.length - 1; i++) {

                String dirToProcess = fileParts[i];
                dirToProcess = Util.expandBrackets(dirToProcess, entry, database);

                if (dirToProcess.matches("^.:$")) { // Windows Drive Letter
                    directory = new File(dirToProcess + "/");
                    continue;
                }
                if (dirToProcess.equals(".")) { // Stay in current directory
                    continue;
                }
                if (dirToProcess.equals("..")) {
                    directory = new File(directory.getParent());
                    continue;
                }
                if (dirToProcess.equals("*")) { // Do for all direct subdirs

                    File[] subDirs = directory.listFiles();
                    if (subDirs != null) {
                        String restOfFileString = Util.join(fileParts, "/", i + 1, fileParts.length);
                        for (File subDir : subDirs) {
                            if (subDir.isDirectory()) {
                                res.addAll(findFile(entry, database, subDir,
                                        restOfFileString, extensionRegExp));
                            }
                        }
                    }
                }
                // Do for all direct and indirect subdirs
                if (dirToProcess.equals("**")) {
                    List<File> toDo = new LinkedList<File>();
                    String restOfFileString = Util.join(fileParts, "/", i + 1, fileParts.length);

                    // UPDATE: keep the original "**" semantics: match files in the
                    // current directory as well as in all indirect subdirectories.
                    res.addAll(findFile(entry, database, directory, restOfFileString,
                            extensionRegExp));
                    toDo.add(directory);

                    while (!toDo.isEmpty()) {

                        // Get all subdirs of each of the elements found in toDo
                        File current = toDo.remove(0);
                        File[] children = current.listFiles();
                        if (children == null) // No permission?
                        {
                            continue;
                        }

                        for (File child : children) {
                            // UPDATE: the old code added every child to toDo, including
                            // ordinary files. That caused listFiles() calls on files during
                            // the traversal. Queue directories only.
                            if (!child.isDirectory()) {
                                continue;
                            }
                            toDo.add(child);
                            res.addAll(findFile(entry, database, child, restOfFileString,
                                    extensionRegExp));
                        }
                    }
                    return res;

                }

            } // End process directory information
        }

        // Last step: check if the given file can be found in this directory
        String filePart = fileParts[fileParts.length - 1].replaceAll("\\[extension\\]", EXT_MARKER);
        String filenameToLookFor = Util.expandBrackets(filePart, entry, database)
                .replaceAll(EXT_MARKER, extensionRegExp);
        final Pattern toMatch = compileFileNamePattern(filenameToLookFor);

        File[] matches = directory.listFiles(new FilenameFilter() {
            public boolean accept(File arg0, String arg1) {
                return toMatch.matcher(arg1).matches();
            }
        });
        if (matches != null && (matches.length > 0)) {
            Collections.addAll(res, matches);
        }
        return res;
    }

    // UPDATE: optimized regexp search path for the common recursive form
    // "**/<filename-regexp>". It scans configured directories once, filters by
    // extension before regex matching, applies a safe cheap BibTeX-key prefilter,
    // and finally verifies the full filename regexp.
    private static Map<BibtexEntry, List<File>> findFilesForSetBatch(Collection<BibtexEntry> entries,
            Collection<String> extensions, Collection<File> directories, String regExp) {

        Map<BibtexEntry, List<File>> res = new HashMap<BibtexEntry, List<File>>();
        for (BibtexEntry entry : entries) {
            res.put(entry, new ArrayList<File>());
        }

        long t0 = System.nanoTime();
        Set<String> extensionSet = buildExtensionSet(extensions);
        String extensionRegExp = buildExtensionRegExp(extensionSet);
        String filePart = getRecursiveFilePart(regExp);

        List<EntrySearchSpec> specs = new ArrayList<EntrySearchSpec>();
        for (BibtexEntry entry : entries) {
            specs.add(EntrySearchSpec.create(entry, null, filePart, extensionRegExp));
        }
        long t1 = System.nanoTime();

        SearchStats stats = new SearchStats();
        scanRecursive(directories, extensionSet, specs, res, stats);
        long t2 = System.nanoTime();

        int found = 0;
        for (List<File> files : res.values()) {
            found += files.size();
        }

        System.out.printf("RegExpFileSearch.audit batch: entries=%d, dirs=%d, extensions=%d, visitedDirs=%d, "
                + "visitedFiles=%d, extensionCandidates=%d, regexCandidates=%d, regexMatchChecks=%d, "
                + "literalFastChecks=%d, literalFastMatches=%d, matches=%d, "
                + "setup=%.3f ms, scanAndMatch=%.3f ms%n",
                entries.size(), directories == null ? 0 : directories.size(), extensionSet.size(),
                stats.visitedDirs, stats.visitedFiles, stats.extensionCandidates,
                stats.regexCandidates, stats.regexMatchChecks, stats.literalFastChecks,
                stats.literalFastMatches, found, (t1 - t0) / 1000000.0, (t2 - t1) / 1000000.0);

        return res;
    }

    // UPDATE: batch search is intentionally limited to patterns of the form
    // "**/<filename-regexp>". More complex directory expressions keep the old
    // compatibility path.
    private static boolean canUseBatchSearch(String regExp) {
        String[] parts = parseFileParts(regExp);
        return (parts.length == 2) && parts[0].equals("**") && (parts[1].length() > 0);
    }

    private static String getRecursiveFilePart(String regExp) {
        String[] parts = parseFileParts(regExp);
        return parts[1];
    }

    private static String buildExtensionRegExp(Collection<String> extensions) {
        StringBuilder sb = new StringBuilder();
        if (extensions != null) {
            for (Iterator<String> i = extensions.iterator(); i.hasNext();) {
                String extension = normalizeExtension(i.next());
                if (extension.length() == 0) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("|");
                }
                sb.append(Pattern.quote(extension));
            }
        }
        return "(" + sb.toString() + ")";
    }

    private static Set<String> buildExtensionSet(Collection<String> extensions) {
        Set<String> result = new HashSet<String>();
        if (extensions == null) {
            return result;
        }
        for (String extension : extensions) {
            String normalizedExtension = normalizeExtension(extension);
            if (normalizedExtension.length() > 0) {
                result.add(normalizedExtension.toLowerCase());
            }
        }
        return result;
    }

    private static String normalizeExtension(String extension) {
        if (extension == null) {
            return "";
        }
        String result = extension.trim();
        while (result.startsWith(".")) {
            result = result.substring(1);
        }
        return result;
    }

    private static String[] parseFileParts(String file) {
        String normalized = normalizeFileExpression(file);
        if (normalized.length() == 0) {
            return new String[0];
        }
        return normalized.split("/");
    }

    private static String normalizeFileExpression(String file) {
        if (file == null) {
            return "";
        }

        // Escape handling...
        Matcher m = Pattern.compile("([^\\\\])\\\\([^\\\\])").matcher(file);
        StringBuffer s = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(s, m.group(1) + "/" + m.group(2));
        }
        m.appendTail(s);
        return s.toString();
    }

    private static Pattern compileFileNamePattern(String filenameToLookFor) {
        return Pattern.compile("^" + filenameToLookFor.replaceAll("\\\\\\\\", "\\\\") + "$",
                Pattern.CASE_INSENSITIVE);
    }

    private static void scanRecursive(Collection<File> directories,
            Set<String> extensionSet,
            List<EntrySearchSpec> specs,
            Map<BibtexEntry, List<File>> result,
            SearchStats stats) {

        if (directories == null) {
            return;
        }

        for (File root : directories) {
            if ((root == null) || !root.isDirectory()) {
                continue;
            }

            String rootPath = root.getAbsolutePath();
            scanRootRecursive(root, rootPath, extensionSet, specs, result, stats);
        }
    }

    private static void scanRootRecursive(File root,
            String rootPath,
            Set<String> extensionSet,
            List<EntrySearchSpec> specs,
            Map<BibtexEntry, List<File>> result,
            SearchStats stats) {

        List<File> toDo = new LinkedList<File>();
        toDo.add(root);

        while (!toDo.isEmpty()) {
            File current = toDo.remove(0);
            stats.visitedDirs++;

            File[] children = current.listFiles();
            if (children == null) {
                continue;
            }

            for (File child : children) {
                if (child.isDirectory()) {
                    toDo.add(child);
                    continue;
                }

                stats.visitedFiles++;
                String fileName = child.getName();
                String extension = Util.getFileExtension(child);
                if (extension == null) {
                    continue;
                }

                extension = extension.toLowerCase();
                if (!extensionSet.isEmpty() && !extensionSet.contains(extension)) {
                    continue;
                }
                stats.extensionCandidates++;

                String baseNameLower = getBaseName(fileName).toLowerCase();
                String relativePath = toRelativePathFast(rootPath, child);

                for (EntrySearchSpec spec : specs) {
                    if (!spec.acceptsByCheapFilter(baseNameLower)) {
                        continue;
                    }
                    stats.regexCandidates++;
                    if (spec.usesLiteralFastMatch()) {
                        stats.literalFastChecks++;
                    } else {
                        stats.regexMatchChecks++;
                    }
                    if (spec.matches(fileName, baseNameLower)) {
                        if (spec.usesLiteralFastMatch()) {
                            stats.literalFastMatches++;
                        }
                        result.get(spec.entry).add(new File(relativePath));
                    }
                }
            }
        }
    }

    private static String toRelativePathFast(String rootPath, File file) {
        String path = file.getAbsolutePath();
        String relativePath;
        if (path.startsWith(rootPath)) {
            relativePath = path.substring(rootPath.length());
            if ((relativePath.length() > 0) && ((relativePath.charAt(0) == File.separatorChar)
                    || (relativePath.charAt(0) == '/') || (relativePath.charAt(0) == '\\'))) {
                relativePath = relativePath.substring(1);
            }
        } else {
            relativePath = file.getName();
        }
        return relativePath;
    }

    private static String getBaseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            return fileName.substring(0, dot);
        }
        return fileName;
    }

    private static int determinePrefilterMode(String filePart) {
        int markerIndex = filePart.indexOf("[bibtexkey]");
        if (markerIndex < 0) {
            return PREFILTER_NONE;
        }

        String beforeMarker = filePart.substring(0, markerIndex);
        if (beforeMarker.length() == 0) {
            return PREFILTER_STARTS_WITH;
        }
        if (beforeMarker.equals(".*") || beforeMarker.endsWith(".*")) {
            return PREFILTER_CONTAINS;
        }
        return PREFILTER_NONE;
    }

    // UPDATE: for the two common recursive patterns, extension filtering plus
    // the cheap BibTeX-key filename filter is equivalent to the original regexp
    // check. This avoids an unnecessary final regexp match and, more importantly,
    // avoids rejecting candidates if escaping differs between stored preferences
    // and Java regexp strings.
    private static int determineLiteralFastMatchMode(String filePart) {
        String normalized = filePart.replaceAll("\\[extension\\]", EXT_MARKER);

        if (normalized.equals(".*[bibtexkey].*" + "\\\\." + EXT_MARKER)
                || normalized.equals(".*[bibtexkey].*" + "\\." + EXT_MARKER)) {
            return PREFILTER_CONTAINS;
        }

        if (normalized.equals("[bibtexkey].*" + "\\\\." + EXT_MARKER)
                || normalized.equals("[bibtexkey].*" + "\\." + EXT_MARKER)) {
            return PREFILTER_STARTS_WITH;
        }

        return PREFILTER_NONE;
    }

    private static boolean isSafeLiteralPrefilter(String value) {
        if ((value == null) || (value.length() == 0)) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c == '.') || (c == '*') || (c == '+') || (c == '?') || (c == '[') || (c == ']')
                    || (c == '(') || (c == ')') || (c == '{') || (c == '}') || (c == '\\')
                    || (c == '^') || (c == '$') || (c == '|')) {
                return false;
            }
        }
        return true;
    }

    private static final class EntrySearchSpec {

        final BibtexEntry entry;
        final Pattern pattern;
        final int prefilterMode;
        final int literalFastMatchMode;
        final String citeKeyLower;

        private EntrySearchSpec(BibtexEntry entry, Pattern pattern,
                int prefilterMode, int literalFastMatchMode, String citeKeyLower) {
            this.entry = entry;
            this.pattern = pattern;
            this.prefilterMode = prefilterMode;
            this.literalFastMatchMode = literalFastMatchMode;
            this.citeKeyLower = citeKeyLower;
        }

        static EntrySearchSpec create(BibtexEntry entry, BibtexDatabase database,
                String filePart, String extensionRegExp) {
            String filePartWithExtensionMarker = filePart.replaceAll("\\[extension\\]", EXT_MARKER);
            String filenameToLookFor = Util.expandBrackets(filePartWithExtensionMarker, entry, database)
                    .replaceAll(EXT_MARKER, extensionRegExp);
            Pattern pattern = compileFileNamePattern(filenameToLookFor);

            int prefilterMode = determinePrefilterMode(filePart);
            int literalFastMatchMode = determineLiteralFastMatchMode(filePart);
            String expandedKey = Util.expandBrackets("[bibtexkey]", entry, database);
            if (expandedKey == null) {
                expandedKey = entry == null ? null : entry.getCiteKey();
            }

            String citeKeyLower = null;
            if (isSafeLiteralPrefilter(expandedKey)) {
                citeKeyLower = expandedKey.toLowerCase();
            } else {
                prefilterMode = PREFILTER_NONE;
                literalFastMatchMode = PREFILTER_NONE;
            }

            return new EntrySearchSpec(entry, pattern, prefilterMode,
                    literalFastMatchMode, citeKeyLower);
        }

        boolean acceptsByCheapFilter(String baseNameLower) {
            if ((prefilterMode == PREFILTER_NONE) || (citeKeyLower == null)) {
                return true;
            }
            if (prefilterMode == PREFILTER_STARTS_WITH) {
                return baseNameLower.startsWith(citeKeyLower);
            }
            if (prefilterMode == PREFILTER_CONTAINS) {
                return baseNameLower.indexOf(citeKeyLower) >= 0;
            }
            return true;
        }

        boolean usesLiteralFastMatch() {
            return literalFastMatchMode != PREFILTER_NONE;
        }

        boolean matches(String fileName, String baseNameLower) {
            if ((literalFastMatchMode != PREFILTER_NONE) && (citeKeyLower != null)) {
                if (literalFastMatchMode == PREFILTER_STARTS_WITH) {
                    return baseNameLower.startsWith(citeKeyLower);
                }
                if (literalFastMatchMode == PREFILTER_CONTAINS) {
                    return baseNameLower.indexOf(citeKeyLower) >= 0;
                }
            }
            return pattern.matcher(fileName).matches();
        }
    }

    private static final class SearchStats {

        int visitedDirs;
        int visitedFiles;
        int extensionCandidates;
        int regexCandidates;
        int regexMatchChecks;
        int literalFastChecks;
        int literalFastMatches;
    }

}
