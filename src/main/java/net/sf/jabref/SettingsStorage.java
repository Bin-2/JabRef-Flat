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
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.prefs.InvalidPreferencesFormatException;
import java.util.prefs.Preferences;

/**
 * Bootstrap configuration for the preferences backing store.
 *
 * The backing store must be selected before java.util.prefs.Preferences is
 * initialized. Therefore the storage mode and XML file location are mirrored
 * in a tiny properties file outside Preferences itself.
 */
public final class SettingsStorage {

    public static final String STORAGE_REGISTRY = "registry";
    public static final String STORAGE_XML = "xml";

    static final String XML_FILE_PROPERTY = "jabref.preferences.xmlFile";
    static final String XML_LOAD_FAILED_PROPERTY = "jabref.preferences.xmlLoadFailed";

    private static final String BOOTSTRAP_FILE_NAME = "jabref-storage.properties";
    private static final String KEY_STORAGE = "storage";
    private static final String KEY_XML_FILE = "xmlFile";
    private static final String KEY_PENDING_REGISTRY_IMPORT = "pendingRegistryImport";

    private static String activeStorage = STORAGE_REGISTRY;
    private static String configuredXmlPath = JabRefPreferences.DEFAULT_SETTINGS_XML_PATH;
    private static File bootstrapFile;
    private static boolean initialized;

    private SettingsStorage() {
    }

    /**
     * Must be the first application initialization performed from JabRef.main().
     */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }

        bootstrapFile = locateBootstrapFile();
        Properties properties = loadBootstrapProperties(bootstrapFile);
        activeStorage = normalizeStorage(properties.getProperty(KEY_STORAGE));
        configuredXmlPath = normalizeXmlPath(properties.getProperty(KEY_XML_FILE));

        if (STORAGE_XML.equals(activeStorage)) {
            File xmlFile = resolveXmlFile(configuredXmlPath);
            System.setProperty(XML_FILE_PROPERTY, xmlFile.getAbsolutePath());
            System.setProperty("java.util.prefs.PreferencesFactory", XmlPreferencesFactory.class.getName());
            Preferences root = Preferences.userRoot();
            if (!(root instanceof XmlPreferences)) {
                throw new IllegalStateException("XML settings mode could not initialize the XML Preferences backend");
            }
        } else if (Boolean.parseBoolean(properties.getProperty(KEY_PENDING_REGISTRY_IMPORT))) {
            importPendingXmlIntoRegistry(properties);
        }

        initialized = true;
    }

    public static String getActiveStorage() {
        return activeStorage;
    }

    public static boolean isRegistryActive() {
        return STORAGE_REGISTRY.equals(activeStorage);
    }

    public static boolean isXmlActive() {
        return STORAGE_XML.equals(activeStorage);
    }

    public static String getConfiguredXmlPath() {
        return configuredXmlPath;
    }

    public static File getConfiguredXmlFile() {
        return resolveXmlFile(configuredXmlPath);
    }

    public static String getDefaultXmlPath() {
        File applicationDirectory = getApplicationDirectory();
        if (applicationDirectory.canWrite()) {
            return JabRefPreferences.DEFAULT_SETTINGS_XML_PATH;
        }
        File userDirectory = new File(System.getProperty("user.home", "."), ".jabref");
        return new File(userDirectory, JabRefPreferences.DEFAULT_SETTINGS_XML_PATH).getAbsolutePath();
    }

    public static File resolveXmlFile(String path) {
        String normalizedPath = normalizeXmlPath(path);
        File file = new File(normalizedPath);
        if (!file.isAbsolute()) {
            file = new File(getApplicationDirectory(), normalizedPath);
        }
        return file.getAbsoluteFile();
    }

    public static File getApplicationDirectory() {
        try {
            URI location = JabRef.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            File codeLocation = new File(location);
            if (codeLocation.isFile()) {
                File parent = codeLocation.getParentFile();
                if (parent != null) {
                    return parent.getAbsoluteFile();
                }
            } else if (codeLocation.isDirectory()) {
                return codeLocation.getAbsoluteFile();
            }
        } catch (Exception ignored) {
            // Fall back to the working directory below.
        }
        return new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
    }

    /**
     * Commit a storage configuration after the current backing store and XML
     * mirror have both been written successfully.
     */
    public static synchronized void commitConfiguration(String storage, String xmlPath,
            boolean migrateXmlToRegistryOnNextStart) throws IOException {
        String normalizedStorage = normalizeStorage(storage);
        String normalizedXmlPath = normalizeXmlPath(xmlPath);

        Properties properties = loadBootstrapProperties(getBootstrapFile());
        properties.setProperty(KEY_STORAGE, normalizedStorage);
        properties.setProperty(KEY_XML_FILE, normalizedXmlPath);
        if (migrateXmlToRegistryOnNextStart) {
            properties.setProperty(KEY_PENDING_REGISTRY_IMPORT, Boolean.TRUE.toString());
        } else {
            properties.remove(KEY_PENDING_REGISTRY_IMPORT);
        }
        storeBootstrapProperties(properties);
        configuredXmlPath = normalizedXmlPath;
    }

    private static void importPendingXmlIntoRegistry(Properties properties) {
        File xmlFile = resolveXmlFile(configuredXmlPath);
        if (!xmlFile.isFile()) {
            System.err.println("Pending JabRef XML-to-Registry migration skipped: file not found: "
                    + xmlFile.getAbsolutePath());
            return;
        }

        InputStream input = null;
        try {
            input = new FileInputStream(xmlFile);
            Preferences.importPreferences(input);
            properties.remove(KEY_PENDING_REGISTRY_IMPORT);
            storeBootstrapProperties(properties);
        } catch (IOException ex) {
            System.err.println("Could not migrate JabRef XML preferences to Registry: " + ex.getMessage());
        } catch (InvalidPreferencesFormatException ex) {
            System.err.println("Could not migrate JabRef XML preferences to Registry: " + ex.getMessage());
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // Nothing useful can be done during startup cleanup.
                }
            }
        }
    }

    private static String normalizeStorage(String storage) {
        return STORAGE_XML.equalsIgnoreCase(storage) ? STORAGE_XML : STORAGE_REGISTRY;
    }

    private static String normalizeXmlPath(String path) {
        if ((path == null) || path.trim().isEmpty()) {
            return getDefaultXmlPath();
        }
        return path.trim();
    }

    private static File getBootstrapFile() {
        if (bootstrapFile == null) {
            bootstrapFile = locateBootstrapFile();
        }
        return bootstrapFile;
    }

    private static File locateBootstrapFile() {
        String explicit = System.getProperty("jabref.storageBootstrapFile");
        if ((explicit != null) && !explicit.trim().isEmpty()) {
            return new File(explicit.trim()).getAbsoluteFile();
        }

        File applicationFile = new File(getApplicationDirectory(), BOOTSTRAP_FILE_NAME);
        File applicationDirectory = applicationFile.getParentFile();
        if ((applicationFile.exists() && applicationFile.canWrite())
                || ((applicationDirectory != null) && applicationDirectory.canWrite())) {
            return applicationFile;
        }

        return new File(System.getProperty("user.home", "."), "." + BOOTSTRAP_FILE_NAME).getAbsoluteFile();
    }

    private static Properties loadBootstrapProperties(File file) {
        Properties properties = new Properties();
        if ((file == null) || !file.isFile()) {
            return properties;
        }

        InputStream input = null;
        try {
            input = new FileInputStream(file);
            properties.load(input);
        } catch (IOException ex) {
            System.err.println("Could not read JabRef storage bootstrap file: " + ex.getMessage());
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // Nothing useful can be done during startup cleanup.
                }
            }
        }
        return properties;
    }

    private static void storeBootstrapProperties(Properties properties) throws IOException {
        File file = getBootstrapFile();
        File parent = file.getParentFile();
        if ((parent != null) && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Could not create directory: " + parent.getAbsolutePath());
        }

        File temp = new File(file.getAbsolutePath() + ".tmp");
        OutputStream output = null;
        try {
            output = new FileOutputStream(temp);
            properties.store(output, "JabRef settings storage bootstrap");
        } finally {
            if (output != null) {
                output.close();
            }
        }

        try {
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            temp.delete();
            throw ex;
        }
    }
}
