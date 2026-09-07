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
package net.sf.jabref.net;

import net.sf.jabref.Globals;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Small HTTP/URL download helper used by legacy JabRef network features.
 * Each public download operation creates a new connection; response data is
 * not cached by this class.
 *
 * @author Erik Putrycz erik.putrycz-at-nrc-cnrc.gc.ca
 * @author Simon Harrer
 */
public class URLDownload {

    public static final int DEFAULT_CONNECT_TIMEOUT = 10000;
    public static final int DEFAULT_READ_TIMEOUT = 30000;

    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_ERROR_RESPONSE_BYTES = 4096;
    private static final String DEFAULT_ENCODING = "UTF-8";

    public static URLDownload buildMonitoredDownload(final Component component, URL source) {
        return new URLDownload(source) {
            @Override
            protected InputStream monitorInputStream(InputStream in) {
                return new ProgressMonitorInputStream(component, "Downloading " + this.getSource().toString(), in);
            }
        };
    }

    private final URL source;
    private final Map<String, String> requestProperties = new LinkedHashMap<>();
    private int connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private int readTimeout = DEFAULT_READ_TIMEOUT;

    /**
     * @param source The URL to download.
     */
    public URLDownload(URL source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        this.source = source;
        setCookieHandler();
    }

    public URL getSource() {
        return source;
    }

    /**
     * Adds or replaces a request header for subsequent requests made by this
     * URLDownload instance. This is useful for provider-specific Accept and
     * Authorization headers while keeping the old downloader API intact.
     */
    public URLDownload setRequestProperty(String name, String value) {
        if ((name == null) || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Request property name must not be empty");
        }
        if (value == null) {
            requestProperties.remove(name);
        } else {
            requestProperties.put(name, value);
        }
        return this;
    }

    public URLDownload setConnectTimeout(int timeout) {
        if (timeout < 0) {
            throw new IllegalArgumentException("Connect timeout must not be negative");
        }
        connectTimeout = timeout;
        return this;
    }

    public URLDownload setReadTimeout(int timeout) {
        if (timeout < 0) {
            throw new IllegalArgumentException("Read timeout must not be negative");
        }
        readTimeout = timeout;
        return this;
    }

    private static void setCookieHandler() {
        try {
            if (CookieHandler.getDefault() == null) {
                CookieHandler.setDefault(new CookieHandlerImpl());
            }
        } catch (SecurityException ignored) {
            // Setting or getting the system default cookie handler is forbidden.
        }
    }

    public String determineMimeType() throws IOException {
        URLConnection connection = openConnection();
        InputStream input = null;
        try {
            String contentType = connection.getContentType();
            input = connection.getInputStream();
            return contentType;
        } finally {
            closeQuietly(input);
            disconnect(connection);
        }
    }

    /**
     * Opens a configured GET connection. HTTP redirects are followed explicitly
     * so HTTP-to-HTTPS redirects work consistently. HTTP error status codes are
     * reported as IOException instead of being mistaken for valid content.
     */
    protected URLConnection openConnection() throws IOException {
        URL current = source;

        for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
            URLConnection connection = current.openConnection();
            configureConnection(connection, current);
            connection.connect();

            if (!(connection instanceof HttpURLConnection)) {
                return connection;
            }

            HttpURLConnection http = (HttpURLConnection) connection;
            int status = http.getResponseCode();

            if (isRedirect(status)) {
                String location = http.getHeaderField("Location");
                if ((location == null) || location.trim().isEmpty()) {
                    http.disconnect();
                    throw new IOException("HTTP " + status + " redirect without Location for " + current);
                }
                if (redirectCount == MAX_REDIRECTS) {
                    http.disconnect();
                    throw new IOException("Too many HTTP redirects while downloading " + source);
                }

                URL next = new URL(current, location);
                http.disconnect();
                current = next;
                continue;
            }

            if ((status < 200) || (status >= 300)) {
                String message = buildHttpErrorMessage(http, current, status);
                http.disconnect();
                throw new IOException(message);
            }

            return connection;
        }

        throw new IOException("Too many HTTP redirects while downloading " + source);
    }

    private void configureConnection(URLConnection connection, URL current) {
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", getDefaultUserAgent());

        boolean sameOrigin = sameOrigin(source, current);
        for (Map.Entry<String, String> property : requestProperties.entrySet()) {
            if (!sameOrigin && isSensitiveHeader(property.getKey())) {
                continue;
            }
            connection.setRequestProperty(property.getKey(), property.getValue());
        }

        if (connection instanceof HttpURLConnection) {
            ((HttpURLConnection) connection).setInstanceFollowRedirects(false);
        }
    }

    private static String getDefaultUserAgent() {
        String version = Globals.VERSION;
        if ((version == null) || version.trim().isEmpty()) {
            return "JabRef";
        }
        return "JabRef/" + version;
    }

    private static boolean sameOrigin(URL first, URL second) {
        return first.getProtocol().equalsIgnoreCase(second.getProtocol())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URL url) {
        int port = url.getPort();
        return port >= 0 ? port : url.getDefaultPort();
    }

    private static boolean isSensitiveHeader(String header) {
        return "Authorization".equalsIgnoreCase(header)
                || "Proxy-Authorization".equalsIgnoreCase(header)
                || "Cookie".equalsIgnoreCase(header);
    }

    private static boolean isRedirect(int status) {
        return (status == HttpURLConnection.HTTP_MOVED_PERM)
                || (status == HttpURLConnection.HTTP_MOVED_TEMP)
                || (status == HttpURLConnection.HTTP_SEE_OTHER)
                || (status == 307)
                || (status == 308);
    }

    private static String buildHttpErrorMessage(HttpURLConnection connection, URL url, int status) {
        StringBuilder message = new StringBuilder();
        message.append("HTTP ").append(status);

        try {
            String responseMessage = connection.getResponseMessage();
            if ((responseMessage != null) && !responseMessage.isEmpty()) {
                message.append(' ').append(responseMessage);
            }
        } catch (IOException ignored) {
        }

        message.append(" for ").append(url);

        String errorBody = readErrorBody(connection);
        if (!errorBody.isEmpty()) {
            message.append(": ").append(errorBody);
        }
        return message.toString();
    }

    private static String readErrorBody(HttpURLConnection connection) {
        InputStream input = connection.getErrorStream();
        if (input == null) {
            return "";
        }

        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            int total = 0;
            while (total < MAX_ERROR_RESPONSE_BYTES) {
                int count = input.read(buffer, 0, Math.min(buffer.length, MAX_ERROR_RESPONSE_BYTES - total));
                if (count < 0) {
                    break;
                }
                output.write(buffer, 0, count);
                total += count;
            }
            return new String(output.toByteArray(), DEFAULT_ENCODING).replace('\n', ' ').replace('\r', ' ').trim();
        } catch (IOException ignored) {
            return "";
        } finally {
            closeQuietly(input);
        }
    }

    /**
     * Uses the response charset when one is declared. If the server does not
     * declare one, JabRef's configured default encoding is used.
     */
    public String downloadToString() throws IOException {
        URLConnection connection = openConnection();
        String encoding = determineEncoding(connection, getConfiguredDefaultEncoding());
        return downloadToString(connection, encoding);
    }

    public String downloadToString(String encoding) throws IOException {
        if ((encoding == null) || encoding.trim().isEmpty()) {
            throw new IllegalArgumentException("encoding must not be empty");
        }
        URLConnection connection = openConnection();
        return downloadToString(connection, encoding);
    }

    private String downloadToString(URLConnection connection, String encoding) throws IOException {
        InputStream input = null;
        Reader reader = null;
        StringWriter output = new StringWriter();
        try {
            input = openResponseStream(connection);
            input = monitorInputStream(new BufferedInputStream(input));
            reader = new BufferedReader(new InputStreamReader(input, encoding));
            copy(reader, output);
            return output.toString();
        } finally {
            closeQuietly(reader);
            if (reader == null) {
                closeQuietly(input);
            }
            disconnect(connection);
        }
    }

    private static String determineEncoding(URLConnection connection, String fallback) {
        String contentType = connection.getContentType();
        if (contentType == null) {
            return fallback;
        }

        String[] parts = contentType.split(";");
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].trim();
            int equals = part.indexOf('=');
            if ((equals <= 0) || !"charset".equalsIgnoreCase(part.substring(0, equals).trim())) {
                continue;
            }
            String charsetName = part.substring(equals + 1).trim();
            if ((charsetName.length() >= 2) && charsetName.startsWith("\"") && charsetName.endsWith("\"")) {
                charsetName = charsetName.substring(1, charsetName.length() - 1);
            }
            try {
                if (Charset.isSupported(charsetName)) {
                    return charsetName;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return fallback;
    }

    private static String getConfiguredDefaultEncoding() {
        if (Globals.prefs == null) {
            return DEFAULT_ENCODING;
        }
        String encoding = Globals.prefs.get("defaultEncoding");
        if ((encoding == null) || encoding.trim().isEmpty()) {
            return DEFAULT_ENCODING;
        }
        return encoding;
    }

    private static void copy(Reader in, Writer out) throws IOException {
        char[] buffer = new char[4096];
        int count;
        while ((count = in.read(buffer)) >= 0) {
            out.write(buffer, 0, count);
        }
    }

    /**
     * Downloads to a temporary file in the destination directory and replaces
     * the destination only after the complete response has been received.
     */
    public void downloadToFile(File destination) throws IOException {
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null");
        }

        File absoluteDestination = destination.getAbsoluteFile();
        File parent = absoluteDestination.getParentFile();
        if ((parent != null) && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw new IOException("Could not create destination directory " + parent);
        }

        String prefix = absoluteDestination.getName();
        while (prefix.length() < 3) {
            prefix += "_";
        }
        File temporary = File.createTempFile(prefix, ".part", parent);
        boolean completed = false;

        URLConnection connection = null;
        InputStream input = null;
        OutputStream output = null;
        try {
            connection = openConnection();
            input = openResponseStream(connection);
            input = monitorInputStream(new BufferedInputStream(input));
            output = new BufferedOutputStream(new FileOutputStream(temporary));
            copy(input, output);
            output.flush();
            completed = true;
        } finally {
            closeQuietly(output);
            closeQuietly(input);
            disconnect(connection);
            if (!completed) {
                temporary.delete();
            }
        }

        try {
            Files.move(temporary.toPath(), absoluteDestination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary.toPath(), absoluteDestination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (temporary.exists()) {
                temporary.delete();
            }
        }
    }

    private static InputStream openResponseStream(URLConnection connection) throws IOException {
        InputStream input = connection.getInputStream();
        String contentEncoding = connection.getContentEncoding();
        if (contentEncoding == null) {
            return input;
        }
        if ("gzip".equalsIgnoreCase(contentEncoding)) {
            return new GZIPInputStream(input);
        }
        if ("deflate".equalsIgnoreCase(contentEncoding)) {
            return new InflaterInputStream(input);
        }
        return input;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) >= 0) {
            out.write(buffer, 0, bytesRead);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static void disconnect(URLConnection connection) {
        if (connection instanceof HttpURLConnection) {
            ((HttpURLConnection) connection).disconnect();
        }
    }

    protected InputStream monitorInputStream(InputStream in) {
        return in;
    }

    @Override
    public String toString() {
        return "URLDownload{" + "source=" + source + '}';
    }
}
