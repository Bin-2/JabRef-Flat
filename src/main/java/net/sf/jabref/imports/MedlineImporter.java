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
package net.sf.jabref.imports;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import net.sf.jabref.BibtexEntry;
import net.sf.jabref.OutputPrinter;

/**
 * Importer for the Refer/Endnote format.
 *
 * check here for details on the format
 * http://www.ecst.csuchico.edu/~jacobsd/bib/formats/endnote.html
 */
public class MedlineImporter extends ImportFormat {

    private static Logger logger = Logger.getLogger(MedlineImporter.class.toString());

    /**
     * Return the name of this import format.
     */
    public String getFormatName() {
        return "Medline";
    }

    /*
     * (non-Javadoc)
     * 
     * @see net.sf.jabref.imports.ImportFormat#getCLIId()
     */
    public String getCLIId() {
        return "medline";
    }

    /**
     * Check whether the source is in the correct format for this importer.
     */
    public boolean isRecognizedFormat(InputStream stream) throws IOException {

        BufferedReader in = new BufferedReader(ImportFormatReader.getReaderDefaultEncoding(stream));
        String str;
        int i = 0;
        while (((str = in.readLine()) != null) && (i < 50)) {

            if (str.toLowerCase().contains("<pubmedarticle>")) {
                return true;
            }

            i++;
        }

        return false;
    }

    /**
     * Fetch and parse an medline item from eutils.ncbi.nlm.nih.gov.
     *
     * @param id One or several ids, separated by ","
     *
     * @return Will return an empty list on error.
     */
    public static List<BibtexEntry> fetchMedline(String id, OutputPrinter status) {
        try {
            return fetchMedlineChecked(id, status);
        } catch (IOException e) {
            logger.log(Level.WARNING, e.getLocalizedMessage(), e);
            if (status != null) {
                status.showMessage(e.getLocalizedMessage());
            }
            return new ArrayList<BibtexEntry>();
        }
    }

    /**
     * Fetch and parse one or more PubMed records. Network failures are
     * propagated so the Web Search UI can distinguish them from an empty
     * result set.
     */
    public static List<BibtexEntry> fetchMedlineChecked(String id, OutputPrinter status) throws IOException {
        String xml = NcbiEutils.efetch(id);
        return new MedlineImporter().importEntries(
                new ByteArrayInputStream(xml.getBytes("UTF-8")), status);
    }

    /**
     * Parse the entries in the source, and return a List of BibtexEntry
     * objects.
     */
    public List<BibtexEntry> importEntries(InputStream stream, OutputPrinter status) throws IOException {

        // Obtain a factory object for creating SAX parsers
        SAXParserFactory parserFactory = SAXParserFactory.newInstance();

        // Configure the factory object to specify attributes of the parsers it
        // creates
        parserFactory.setValidating(false);
        parserFactory.setNamespaceAware(true);
        disableExternalEntities(parserFactory);

        // Now create a SAXParser object
        ArrayList<BibtexEntry> bibItems = new ArrayList<BibtexEntry>();
        try {
            SAXParser parser = parserFactory.newSAXParser(); // May throw
            // exceptions
            MedlineHandler handler = new MedlineHandler();
            // Start the parser. It reads the file and calls methods of the
            // handler.
            parser.parse(stream, handler);

            // Switch this to true if you want to make a local copy for testing.
            if (false) {
                stream.reset();
                FileOutputStream out = new FileOutputStream(new File("/home/alver/ut.txt"));
                int c;
                while ((c = stream.read()) != -1) {
                    out.write((char) c);
                }
                out.close();
            }

            // When you're done, report the results stored by your handler
            // object
            bibItems = handler.getItems();
        } catch (javax.xml.parsers.ParserConfigurationException e1) {
            logger.log(Level.SEVERE, e1.getLocalizedMessage(), e1);
            if (status != null) {
                status.showMessage(e1.getLocalizedMessage());
            }
        } catch (org.xml.sax.SAXException e2) {
            logger.log(Level.SEVERE, e2.getLocalizedMessage(), e2);
            if (status != null) {
                status.showMessage(e2.getLocalizedMessage());
            }
        } catch (java.io.IOException e3) {
            logger.log(Level.SEVERE, e3.getLocalizedMessage(), e3);
            if (status != null) {
                status.showMessage(e3.getLocalizedMessage());
            }
        }

        return bibItems;
    }

    private static void disableExternalEntities(SAXParserFactory parserFactory) {
        setFeatureQuietly(parserFactory, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureQuietly(parserFactory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeatureQuietly(parserFactory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    }

    private static void setFeatureQuietly(SAXParserFactory parserFactory, String feature, boolean value) {
        try {
            parserFactory.setFeature(feature, value);
        } catch (Exception e) {
            logger.log(Level.FINE, "XML parser does not support feature " + feature, e);
        }
    }

}
