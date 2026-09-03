package net.sf.jabref.specialfields;

import java.util.ArrayList;

import javax.swing.ImageIcon;

import net.sf.jabref.GUIGlobals;
import net.sf.jabref.Globals;

public class RankExtended extends Rank {

    private static RankExtended INSTANCE = null;

    public RankExtended() {
        super();
        ArrayList<SpecialFieldValue> values = new ArrayList<SpecialFieldValue>();
        values.add(new SpecialFieldValue(this, Globals.lang("null"), "clearRank", Globals.lang("Clear rank"), null, Globals.lang("No rank information")));
        values.add(SpecialFieldValue.withThemeIcon(this, Globals.lang("rank1"), "setRank1", Globals.lang("Set rank to one star"), "rank1", Globals.lang("One star")));
        values.add(SpecialFieldValue.withThemeIcon(this, Globals.lang("rank2"), "setRank2", Globals.lang("Set rank to two stars"), "rank2", Globals.lang("Two stars")));
        values.add(SpecialFieldValue.withThemeIcon(this, Globals.lang("rank3"), "setRank3", Globals.lang("Set rank to three stars"), "rank3", Globals.lang("Three stars")));
        values.add(SpecialFieldValue.withThemeIcon(this, Globals.lang("rank4"), "setRank4", Globals.lang("Set rank to four stars"), "rank4", Globals.lang("Four stars")));
        values.add(SpecialFieldValue.withThemeIcon(this, Globals.lang("rank5"), "setRank5", Globals.lang("Set rank to five stars"), "rank5", Globals.lang("Five stars")));
        this.setValues(values);
    }

    public static RankExtended getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new RankExtended();
        }
        return INSTANCE;
    }

    public ImageIcon getRepresentingIcon() {
        return GUIGlobals.getImageIcon("rankingFav");
    }

}
