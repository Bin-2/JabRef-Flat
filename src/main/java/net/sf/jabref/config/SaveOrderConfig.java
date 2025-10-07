package net.sf.jabref.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores the save order config from MetaData
 *
 * Format: <choice>, pair of field + ascending (boolean)
 */
public class SaveOrderConfig {

    public boolean saveInOriginalOrder, saveInSpecifiedOrder;

    // quick hack for outside modifications
    public SortCriterion sortCriteria[] = new SortCriterion[3];

    public static class SortCriterion {

        public String field;
        public boolean descending = false;

        public SortCriterion() {
            this.field = "";
        }

        public SortCriterion(String field, String descending) {
            this.field = field;
            this.descending = Boolean.parseBoolean(descending);
        }
    }

    public SaveOrderConfig() {
        // fill default values
        setSaveInOriginalOrder();
        sortCriteria[0] = new SortCriterion();
        sortCriteria[1] = new SortCriterion();
        sortCriteria[2] = new SortCriterion();
    }

    public SaveOrderConfig(List<String> data) {
        if (data == null) {
            throw new NullPointerException();
        }
        if (data.size() == 0) {
            throw new IllegalArgumentException();
        }

        String choice = data.get(0);
        if ("original".equals(choice)) {
            setSaveInOriginalOrder();
        } else if ("specified".equals(choice)) {
            setSaveInSpecifiedOrder();
        } else {
            // fallback
            setSaveInSpecifiedOrder();
        }

        if (data.size() >= 3) {
            sortCriteria[0] = new SortCriterion(data.get(1), data.get(2));
        } else {
            sortCriteria[0] = new SortCriterion();
        }
        if (data.size() >= 5) {
            sortCriteria[1] = new SortCriterion(data.get(3), data.get(4));
        } else {
            sortCriteria[1] = new SortCriterion();
        }
        if (data.size() >= 7) {
            sortCriteria[2] = new SortCriterion(data.get(5), data.get(6));
        } else {
            sortCriteria[2] = new SortCriterion();
        }
    }

    public void setSaveInOriginalOrder() {
        this.saveInOriginalOrder = true;
        this.saveInSpecifiedOrder = false;
    }

    public void setSaveInSpecifiedOrder() {
        this.saveInOriginalOrder = false;
        this.saveInSpecifiedOrder = true;
    }

    public List<String> getList() {
        List<String> res = new ArrayList<>(7);

        // Add elements in order instead of inserting at specific indices
        if (saveInOriginalOrder) {
            res.add("original");
        } else {
            assert (saveInSpecifiedOrder);
            res.add("specified");
        }

        res.add(sortCriteria[0].field);
        res.add(Boolean.toString(sortCriteria[0].descending));
        res.add(sortCriteria[1].field);
        res.add(Boolean.toString(sortCriteria[1].descending));
        res.add(sortCriteria[2].field);
        res.add(Boolean.toString(sortCriteria[2].descending));

        return res;
    }

}
