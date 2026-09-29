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
package net.sf.jabref.label;

import net.sf.jabref.AuthorList;
import net.sf.jabref.BibtexEntry;
import net.sf.jabref.BibtexFields;

public class ArticleLabelRule extends DefaultLabelRule {

    // this is the rule used handle articles
    // we try (first author last name)/(year)/(first unique journal word)
    public String applyRule(BibtexEntry oldEntry) {
        String oldLabel = (oldEntry.getField(BibtexFields.KEY_FIELD));
        String newLabel = "";

        //## to be done: i need to check if the key is unique else need to make another one with suffix
        try {
            String author = oldEntry.getField("author");
            String[] tokens = author.split("\\band\\b");
            if (tokens.length > 0) { // if author is empty
                if (tokens[0].indexOf(",") > 0) {
                    tokens[0] = AuthorList.fixAuthor_firstNameFirst(tokens[0]); // convert lastname, firstname to firstname lastname
                }
                String[] firstAuthor = tokens[0].replaceAll("\\s+", " ").split(" ");
                // lastname, firstname

                newLabel += firstAuthor[firstAuthor.length - 1];
            }
        } catch (Throwable t) {
            System.out.println("error getting author: " + t);
        }

        // use the year token
        try {
            if (!newLabel.equals("")) {
                if (oldEntry.getField("year") != null) {
                    newLabel += String.valueOf(oldEntry.getField("year"));
                }
            } else {
                newLabel = oldLabel; // don't make a key since there is no author
            }
        } catch (Throwable t) {
            System.out.println("error getting year: " + t);
        }

        // now check for uniqueness
        // i need access to basepanes: checkForDuplicateKey
        // oldEntry.setField(Globals.KEY_FIELD,newLabel) ;
        return newLabel;
    }
}
