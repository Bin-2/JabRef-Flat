/*  Copyright (C) 2003-2011 JabRef contributors.
    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.
 */
package net.sf.jabref.imports;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

import net.sf.jabref.net.URLDownload;

/**
 * Shared access to the NCBI Entrez E-utilities used by the PubMed/Medline
 * fetcher. Requests are serialized and paced below NCBI's unauthenticated
 * limit of three requests per second.
 */
final class NcbiEutils {

    private static final String BASE_URL = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/";
    private static final String TOOL_NAME = "JabRefLegacy";
    private static final long MIN_REQUEST_INTERVAL_MS = 350L;

    private static long lastRequestTime;

    private NcbiEutils() {
    }

    static String esearch(String term, int retstart, int retmax) throws IOException {
        Map<String, String> parameters = new LinkedHashMap<String, String>();
        parameters.put("db", "pubmed");
        parameters.put("retmode", "xml");
        parameters.put("retstart", Integer.toString(retstart));
        parameters.put("retmax", Integer.toString(retmax));
        parameters.put("term", term);
        parameters.put("tool", TOOL_NAME);
        return request("esearch.fcgi", parameters);
    }

    static String efetch(String ids) throws IOException {
        Map<String, String> parameters = new LinkedHashMap<String, String>();
        parameters.put("db", "pubmed");
        parameters.put("retmode", "xml");
        parameters.put("id", ids);
        parameters.put("tool", TOOL_NAME);
        return request("efetch.fcgi", parameters);
    }

    private static String request(String endpoint, Map<String, String> parameters) throws IOException {
        paceRequest();
        URL url = new URL(BASE_URL + endpoint + '?' + encodeParameters(parameters));
        return new URLDownload(url)
                .setRequestProperty("Accept", "application/xml, text/xml;q=0.9, */*;q=0.1")
                .downloadToString("UTF-8");
    }

    private static synchronized void paceRequest() throws IOException {
        long now = System.currentTimeMillis();
        long wait = MIN_REQUEST_INTERVAL_MS - (now - lastRequestTime);
        if (wait > 0L) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting to contact NCBI", e);
            }
        }
        lastRequestTime = System.currentTimeMillis();
    }

    private static String encodeParameters(Map<String, String> parameters) throws IOException {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            if (result.length() > 0) {
                result.append('&');
            }
            result.append(urlEncode(parameter.getKey()));
            result.append('=');
            result.append(urlEncode(parameter.getValue()));
        }
        return result.toString();
    }

    private static String urlEncode(String value) throws IOException {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IOException("UTF-8 is not available", e);
        }
    }
}
