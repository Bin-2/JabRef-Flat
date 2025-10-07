package net.sf.jabref.imports;

import net.sf.jabref.JabRef;
import net.sf.jabref.JabRefFrame;
import net.sf.jabref.SidePaneManager;
import net.sf.jabref.TestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.swing.*;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Tests GeneralFetcher
 *
 * @author Dennis Hartrampf, Ines Moosdorf
 */
public class GeneralFetcherTest {
    static JabRefFrame jrf;
    static SidePaneManager spm;
    static GeneralFetcher gf;
    static ACMPortalFetcher acmpf;

    /**
     * Tests the reset-button. Types a text into tf, pushs reset and check tf's
     * text
     *
     * @throws Exception
     */
    @Test
    public void testResetButton() throws Exception {
        String testString = "test string";
        JTextField tf = (JTextField) TestUtils.getChildNamed(gf, "tf");
        assertNotNull(tf); // tf found?
        tf.setText(testString);
        tf.postActionEvent(); // send message
        assertEquals(testString, tf.getText());
        JButton reset = (JButton) TestUtils.getChildNamed(gf, "reset");
        assertNotNull(reset); // reset found?
        reset.doClick(); // "click" reset
        assertEquals("", tf.getText());
    }

    /**
     * Get an instance of JabRef via its singleton and get a GeneralFetcher and an ACMPortalFetcher
     */
    @Before
    public void setUp() {
        JabRef.main(new String[0]);
        jrf = JabRef.jrf;
        spm = jrf.sidePaneManager;
        acmpf = new ACMPortalFetcher();
        ArrayList<EntryFetcher> al = new ArrayList<EntryFetcher>();
        al.add(acmpf);
        gf = new GeneralFetcher(spm, jrf, al);
    }

    @After
    public void tearDown() {
        gf = null;
        acmpf = null;
        spm = null;
        jrf = null;
    }

}
