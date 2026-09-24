package com.example.barelabel.model;

/**
 * One autocomplete row: the display label plus the USDA fdcId of the record
 * it came from (0 when the row is not backed by a USDA record, e.g. offline
 * unbranded completions). Carrying the fdcId lets a tapped suggestion fetch
 * its exact USDA record instead of re-running a fuzzy text search on the label.
 */
public class Suggestion {
    public final String label;
    public final long fdcId;

    public Suggestion(String label, long fdcId) {
        this.label = label;
        this.fdcId = fdcId;
    }

    @Override
    public String toString() {
        return label;
    }
}
