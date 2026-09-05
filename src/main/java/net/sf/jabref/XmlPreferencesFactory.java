/*  Copyright (C) 2003-2015 JabRef contributors.
 This program is free software; you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation; either version 2 of the License, or
 (at your option) any later version.
 */
package net.sf.jabref;

import java.io.File;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/**
 * java.util.prefs backend that stores the complete user preference tree in XML.
 */
public final class XmlPreferencesFactory implements PreferencesFactory {

    private final Preferences userRoot;
    private final Preferences systemRoot;

    public XmlPreferencesFactory() {
        String xmlPath = System.getProperty(SettingsStorage.XML_FILE_PROPERTY,
                JabRefPreferences.DEFAULT_SETTINGS_XML_PATH);
        userRoot = XmlPreferences.createRoot(new File(xmlPath));
        systemRoot = XmlPreferences.createRoot(null);
    }

    @Override
    public Preferences systemRoot() {
        return systemRoot;
    }

    @Override
    public Preferences userRoot() {
        return userRoot;
    }
}
