/*
 Copyright (C) 2003  Nathan Dunn

 All programs in this directory and
 subdirectories are published under the GNU General Public License as
 described below.

 This program is free software; you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation; either version 2 of the License, or (at
 your option) any later version.

 This program is distributed in the hope that it will be useful, but
 WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program; if not, write to the Free Software
 Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 USA

 Further information about the GNU GPL is available at:
 http://www.gnu.org/copyleft/gpl.ja.html

 */
package net.sf.jabref;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

public class SearchRuleSet implements SearchRule {

    protected List<SearchRule> ruleSet = new ArrayList<>();

    public void addRule(SearchRule newRule) {
        ruleSet.add(newRule);
    }

    public void clearRules() {
        ruleSet.clear();
    }

    @Override
    public int applyRule(Map<String, String> searchString, BibtexEntry bibtexEntry) throws PatternSyntaxException {
        int score = 0;
        for (SearchRule rule : ruleSet) {
            score += rule.applyRule(searchString, bibtexEntry);
        }
        return score;
    }

    @Override
    public boolean validateSearchStrings(Map<String, String> searchStrings) {
        for (SearchRule rule : ruleSet) {
            if (!rule.validateSearchStrings(searchStrings)) {
                return false;
            }
        }
        return true;
    }
}
