/*  Copyright (C) 2003-2015 JabRef contributors.
 This program is free software; you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation; either version 2 of the License, or
 (at your option) any later version.
 */
package net.sf.jabref;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * File-backed Preferences implementation used by XmlPreferencesFactory.
 */
final class XmlPreferences extends AbstractPreferences {

    private static final class Storage {
        private final File file;
        private XmlPreferences root;
        private boolean loading;

        Storage(File file) {
            this.file = file;
        }

        synchronized void load() {
            if ((file == null) || !file.isFile() || (file.length() == 0)) {
                return;
            }

            loading = true;
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setValidating(false);
                factory.setExpandEntityReferences(false);
                setFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities", false);
                setFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities", false);
                setFeatureQuietly(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

                DocumentBuilder builder = factory.newDocumentBuilder();
                builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
                builder.setErrorHandler(new ErrorHandler() {
                    @Override
                    public void warning(SAXParseException exception) throws SAXException {
                        throw exception;
                    }

                    @Override
                    public void error(SAXParseException exception) throws SAXException {
                        throw exception;
                    }

                    @Override
                    public void fatalError(SAXParseException exception) throws SAXException {
                        throw exception;
                    }
                });

                InputStream input = null;
                try {
                    input = new FileInputStream(file);
                    Document document = builder.parse(input);
                    Element preferencesElement = document.getDocumentElement();
                    Element rootElement = firstDirectChild(preferencesElement, "root");
                    if (rootElement == null) {
                        throw new IOException("Preferences XML does not contain a root element");
                    }
                    readNode(rootElement, root);
                } finally {
                    if (input != null) {
                        input.close();
                    }
                }
            } catch (Exception ex) {
                System.setProperty(SettingsStorage.XML_LOAD_FAILED_PROPERTY, Boolean.TRUE.toString());
                preserveUnreadableFile(file);
                System.err.println("Could not read JabRef XML preferences: " + ex.getMessage());
            } finally {
                loading = false;
            }
        }

        synchronized void store() throws BackingStoreException {
            if (loading || (file == null)) {
                return;
            }

            File absoluteFile = file.getAbsoluteFile();
            File parent = absoluteFile.getParentFile();
            if ((parent != null) && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new BackingStoreException("Could not create directory: " + parent.getAbsolutePath());
            }

            File temp = new File(absoluteFile.getAbsolutePath() + ".tmp");
            OutputStream output = null;
            try {
                output = new FileOutputStream(temp);
                root.exportSubtree(output);
            } catch (IOException ex) {
                temp.delete();
                throw new BackingStoreException(ex);
            } finally {
                if (output != null) {
                    try {
                        output.close();
                    } catch (IOException ex) {
                        temp.delete();
                        throw new BackingStoreException(ex);
                    }
                }
            }

            try {
                try {
                    Files.move(temp.toPath(), absoluteFile.toPath(), StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temp.toPath(), absoluteFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ex) {
                temp.delete();
                throw new BackingStoreException(ex);
            }
        }

        private static void setFeatureQuietly(DocumentBuilderFactory factory, String feature, boolean value) {
            try {
                factory.setFeature(feature, value);
            } catch (Exception ignored) {
                // Parser implementations differ; the EntityResolver remains as a fallback.
            }
        }

        private static void preserveUnreadableFile(File file) {
            File copy = new File(file.getAbsolutePath() + ".unreadable");
            int suffix = 1;
            while (copy.exists()) {
                copy = new File(file.getAbsolutePath() + ".unreadable." + suffix++);
            }
            try {
                Files.copy(file.toPath(), copy.toPath());
            } catch (IOException ignored) {
                // The original is deliberately left untouched even if copying fails.
            }
        }

        private static void readNode(Element xmlNode, XmlPreferences preferencesNode) {
            Element map = firstDirectChild(xmlNode, "map");
            if (map != null) {
                NodeList children = map.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node child = children.item(i);
                    if ((child instanceof Element) && "entry".equals(child.getNodeName())) {
                        Element entry = (Element) child;
                        preferencesNode.values.put(entry.getAttribute("key"), entry.getAttribute("value"));
                    }
                }
            }

            NodeList children = xmlNode.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if ((child instanceof Element) && "node".equals(child.getNodeName())) {
                    Element childElement = (Element) child;
                    String name = childElement.getAttribute("name");
                    XmlPreferences childPreferences = (XmlPreferences) preferencesNode.node(name);
                    readNode(childElement, childPreferences);
                }
            }
        }

        private static Element firstDirectChild(Element parent, String name) {
            NodeList children = parent.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if ((child instanceof Element) && name.equals(child.getNodeName())) {
                    return (Element) child;
                }
            }
            return null;
        }
    }

    private final Storage storage;
    private final Map<String, String> values = new LinkedHashMap<String, String>();
    private final Map<String, XmlPreferences> children = new LinkedHashMap<String, XmlPreferences>();

    static Preferences createRoot(File file) {
        Storage storage = new Storage(file == null ? null : file.getAbsoluteFile());
        XmlPreferences root = new XmlPreferences(null, "", storage);
        storage.root = root;
        storage.load();
        return root;
    }

    private XmlPreferences(AbstractPreferences parent, String name, Storage storage) {
        super(parent, name);
        this.storage = storage;
    }

    @Override
    protected void putSpi(String key, String value) {
        values.put(key, value);
    }

    @Override
    protected String getSpi(String key) {
        return values.get(key);
    }

    @Override
    protected void removeSpi(String key) {
        values.remove(key);
    }

    @Override
    protected void removeNodeSpi() throws BackingStoreException {
        if (parent() instanceof XmlPreferences) {
            ((XmlPreferences) parent()).children.remove(name());
        }
    }

    @Override
    protected String[] keysSpi() throws BackingStoreException {
        return values.keySet().toArray(new String[values.size()]);
    }

    @Override
    protected String[] childrenNamesSpi() throws BackingStoreException {
        return children.keySet().toArray(new String[children.size()]);
    }

    @Override
    protected AbstractPreferences childSpi(String name) {
        XmlPreferences child = children.get(name);
        if (child == null) {
            child = new XmlPreferences(this, name, storage);
            children.put(name, child);
        }
        return child;
    }

    @Override
    protected void syncSpi() throws BackingStoreException {
        // JabRef is the sole writer in XML mode. Reloading live nodes would invalidate callers.
    }

    @Override
    protected void flushSpi() throws BackingStoreException {
        storage.store();
    }
}
