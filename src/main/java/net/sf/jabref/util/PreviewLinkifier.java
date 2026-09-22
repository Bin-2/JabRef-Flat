package net.sf.jabref.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts bare HTTP(S) URLs in already-rendered preview HTML into clickable
 * hyperlinks. This class operates only on the HTML string passed to it; it does
 * not modify a {@code BibtexEntry}, its fields, or any persisted record data.
 *
 * <p>
 * The input is treated as HTML, not as plain text. URL detection is therefore
 * applied only to text outside HTML tags. Existing anchors are deliberately
 * left untouched, so valid markup such as:</p>
 *
 * <pre>
 * &lt;a href="https://example.org"&gt;Example&lt;/a&gt;
 * </pre>
 *
 * <p>
 * will not be wrapped a second time. URLs in tag attributes are also ignored.
 * Text inside {@code <script>} and {@code <style>} elements is excluded because
 * their contents are not ordinary rendered text.</p>
 *
 * <p>
 * This is intentionally a small HTML-aware scanner rather than a full HTML
 * parser. Its purpose is limited to preview rendering, where preserving
 * existing markup is more important than normalizing or rewriting the
 * document.</p>
 */
public final class PreviewLinkifier {

    /**
     * Candidate URL matcher used only on text outside HTML tags. The pattern
     * stops at whitespace, markup delimiters, and quote characters; punctuation
     * that may belong to surrounding prose is handled by {@link #trimUrlEnd}.
     */
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)\\bhttps?://[^\\s<>\\\"']+");

    private PreviewLinkifier() {
    }

    /**
     * Returns preview HTML with bare HTTP(S) URLs converted to anchor elements.
     * Existing HTML tags and existing anchors are preserved as supplied.
     *
     * @param html rendered preview HTML; may be {@code null}
     * @return the linkified HTML, or the original {@code null}/empty value
     */
    public static String linkify(String html) {
        if (html == null || html.length() == 0) {
            return html;
        }

        StringBuilder result = new StringBuilder(html.length() + 64);
        int position = 0;

        // Depth counters let us leave existing links and raw-text elements alone.
        // Depth is used instead of a boolean so malformed/nested input degrades
        // safely without causing URLs inside an existing anchor to be rewrapped.
        int anchorDepth = 0;
        int rawTextDepth = 0;

        while (position < html.length()) {
            int tagStart = html.indexOf('<', position);
            if (tagStart < 0) {
                appendText(result, html.substring(position), anchorDepth == 0 && rawTextDepth == 0);
                break;
            }

            if (tagStart > position) {
                appendText(result, html.substring(position, tagStart), anchorDepth == 0 && rawTextDepth == 0);
            }

            // Preserve comments exactly. A URL in a comment is not rendered text.
            if (html.startsWith("<!--", tagStart)) {
                int commentEnd = html.indexOf("-->", tagStart + 4);
                if (commentEnd < 0) {
                    result.append(html.substring(tagStart));
                    break;
                }
                commentEnd += 3;
                result.append(html.substring(tagStart, commentEnd));
                position = commentEnd;
                continue;
            }

            // A literal '<' can occur in text. Do not treat it as markup unless
            // the following characters look like the beginning of an HTML tag.
            if (!isTagStart(html, tagStart)) {
                result.append('<');
                position = tagStart + 1;
                continue;
            }

            // Attribute values may themselves contain '>', so locate the end of
            // the tag while respecting single- and double-quoted attributes.
            int tagEnd = findTagEnd(html, tagStart + 1);
            if (tagEnd < 0) {
                result.append(html.substring(tagStart));
                break;
            }

            String tag = html.substring(tagStart, tagEnd + 1);
            result.append(tag);

            TagInfo tagInfo = parseTag(tag);
            if (tagInfo != null) {
                if ("a".equals(tagInfo.name)) {
                    anchorDepth = updateDepth(anchorDepth, tagInfo);
                } else if ("script".equals(tagInfo.name) || "style".equals(tagInfo.name)) {
                    rawTextDepth = updateDepth(rawTextDepth, tagInfo);
                }
            }

            position = tagEnd + 1;
        }

        return result.toString();
    }

    /**
     * Appends one text segment, optionally replacing bare URLs with anchors.
     * The caller guarantees that {@code text} is outside an HTML tag.
     */
    private static void appendText(StringBuilder result, String text, boolean linkify) {
        if (!linkify || text.length() == 0) {
            result.append(text);
            return;
        }

        Matcher matcher = URL_PATTERN.matcher(text);
        int previousEnd = 0;
        while (matcher.find()) {
            // The regex intentionally accepts common URL punctuation. Remove only
            // punctuation that belongs to the surrounding prose, not the URL.
            int urlEnd = trimUrlEnd(text, matcher.start(), matcher.end());
            if (urlEnd <= matcher.start()) {
                continue;
            }

            result.append(text.substring(previousEnd, matcher.start()));
            String url = text.substring(matcher.start(), urlEnd);
            result.append("<a href=\"").append(url).append("\">").append(url).append("</a>");
            result.append(text.substring(urlEnd, matcher.end()));
            previousEnd = matcher.end();
        }
        result.append(text.substring(previousEnd));
    }

    /**
     * Removes punctuation commonly placed immediately after a URL in prose.
     * Closing brackets are removed only when they are unmatched within the URL,
     * preserving valid addresses such as {@code https://example.org/a_(b)}.
     */
    private static int trimUrlEnd(String text, int start, int end) {
        while (end > start) {
            char last = text.charAt(end - 1);
            if (last == '.' || last == ',' || last == ';' || last == ':' || last == '!' || last == '?') {
                end--;
                continue;
            }
            if (last == ')' && count(text, start, end, '(') < count(text, start, end, ')')) {
                end--;
                continue;
            }
            if (last == ']' && count(text, start, end, '[') < count(text, start, end, ']')) {
                end--;
                continue;
            }
            if (last == '}' && count(text, start, end, '{') < count(text, start, end, '}')) {
                end--;
                continue;
            }
            break;
        }
        return end;
    }

    /**
     * Counts one delimiter inside a candidate URL range.
     */
    private static int count(String text, int start, int end, char character) {
        int count = 0;
        for (int i = start; i < end; i++) {
            if (text.charAt(i) == character) {
                count++;
            }
        }
        return count;
    }

    /**
     * Performs a lightweight check that a '<' really begins an HTML-like tag.
     * This prevents comparison text such as "x < y" from swallowing the rest of
     * the preview while still accepting normal opening and closing tags.
     */
    private static boolean isTagStart(String html, int tagStart) {
        int next = tagStart + 1;
        if (next >= html.length()) {
            return false;
        }

        char c = html.charAt(next);
        if (Character.isLetter(c) || c == '!' || c == '?') {
            return true;
        }
        if (c != '/') {
            return false;
        }

        next++;
        while (next < html.length() && Character.isWhitespace(html.charAt(next))) {
            next++;
        }
        return next < html.length() && Character.isLetter(html.charAt(next));
    }

    /**
     * Finds the terminating '>' of a tag while ignoring '>' characters inside
     * quoted attribute values.
     */
    private static int findTagEnd(String html, int position) {
        char quote = 0;
        for (int i = position; i < html.length(); i++) {
            char c = html.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Extracts only the tag information needed by the scanner. No attempt is
     * made to parse attributes because URL attributes must be preserved
     * verbatim.
     */
    private static TagInfo parseTag(String tag) {
        int i = 1;
        while (i < tag.length() && Character.isWhitespace(tag.charAt(i))) {
            i++;
        }

        boolean closing = false;
        if (i < tag.length() && tag.charAt(i) == '/') {
            closing = true;
            i++;
            while (i < tag.length() && Character.isWhitespace(tag.charAt(i))) {
                i++;
            }
        }

        int nameStart = i;
        while (i < tag.length()) {
            char c = tag.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != ':' && c != '-') {
                break;
            }
            i++;
        }
        if (i == nameStart) {
            return null;
        }

        String name = tag.substring(nameStart, i).toLowerCase(Locale.ENGLISH);
        boolean selfClosing = false;
        for (int j = tag.length() - 2; j >= 0; j--) {
            char c = tag.charAt(j);
            if (Character.isWhitespace(c)) {
                continue;
            }
            selfClosing = c == '/';
            break;
        }

        return new TagInfo(name, closing, selfClosing);
    }

    /**
     * Updates the nesting depth for elements whose text must not be linkified.
     * A defensive floor of zero tolerates unmatched closing tags in preview
     * HTML.
     */
    private static int updateDepth(int depth, TagInfo tagInfo) {
        if (tagInfo.closing) {
            return Math.max(0, depth - 1);
        }
        if (!tagInfo.selfClosing) {
            return depth + 1;
        }
        return depth;
    }

    /**
     * Minimal structural information needed to track protected element depth.
     */
    private static final class TagInfo {

        private final String name;
        private final boolean closing;
        private final boolean selfClosing;

        private TagInfo(String name, boolean closing, boolean selfClosing) {
            this.name = name;
            this.closing = closing;
            this.selfClosing = selfClosing;
        }
    }
}
