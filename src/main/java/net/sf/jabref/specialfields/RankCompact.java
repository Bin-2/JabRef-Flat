package net.sf.jabref.specialfields;

import java.util.ArrayList;

import javax.swing.ImageIcon;

import net.sf.jabref.GUIGlobals;
import net.sf.jabref.Globals;

public class RankCompact extends Rank {

    private static RankCompact INSTANCE = null;

    public RankCompact() {
        ArrayList<SpecialFieldValue> values = new ArrayList<SpecialFieldValue>();
        //lab.setName("i");
        values.add(new SpecialFieldValue(this, Globals.lang("null"), "clearRank", Globals.lang("Clear rank"), null, Globals.lang("No rank information")));
        values.add(SpecialFieldValue.withThemeIcon(this, Globals.lang("rank1"), "setRank1", Globals.lang("Set rank to one note"), "rankL1", Globals.lang("One star")));
        values.add(SpecialFieldValue.withThemeIcon(this, Globals.lang("rank2"), "setRank2", Globals.lang("Set rank to two notes"), "rankL2", Globals.lang("Two stars")));
        values.add(SpecialFieldValue.withThemeIcon(this, Globals.lang("rank3"), "setRank3", Globals.lang("Set rank to three notes"), "rankL3", Globals.lang("Three stars")));
        values.add(SpecialFieldValue.withThemeIcon(this, Globals.lang("rank4"), "setRank4", Globals.lang("Set rank to four notes"), "rankL4", Globals.lang("Four stars")));
        values.add(SpecialFieldValue.withThemeIcon(this, Globals.lang("rank5"), "setRank5", Globals.lang("Set rank to five notes"), "rankL5", Globals.lang("Five stars")));
        this.setValues(values);
        TEXT_DONE_PATTERN = "Set rank to '%0' for %1 entries";
    }

    public static RankCompact getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new RankCompact();
        }
        return INSTANCE;
    }

    public ImageIcon getRepresentingIcon() {
        return GUIGlobals.getImageIcon("rankingFav");
    }

}
