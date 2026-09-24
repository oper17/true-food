package com.example.barelabel.model;

import com.example.barelabel.model.ProductResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One persisted scan-history entry: everything needed to re-display a past
 * verdict and to compare two products without hitting the network again.
 */
public class ScannedProduct {
    public String id;
    public String name;
    public String brand;
    public String ingredients;
    public List<String> flagged = new ArrayList<>();
    public boolean clean;
    public long timestamp;
    public String gtin;

    public ScannedProduct() {
        this.id = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
    }

    public static ScannedProduct fromProductResult(ProductResult r) {
        ScannedProduct p = new ScannedProduct();
        p.name = r.name == null ? "" : r.name;
        p.brand = r.brand == null ? "" : r.brand;
        p.ingredients = r.ingredients == null ? "" : r.ingredients;
        if (r.flagged != null) p.flagged.addAll(r.flagged);
        p.clean = p.flagged.isEmpty();
        p.gtin = r.gtinUpc == null ? "" : r.gtinUpc;
        return p;
    }

    public String displayName() {
        if (brand == null || brand.trim().isEmpty()) return name;
        return name + " (" + brand + ")";
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("brand", brand);
            o.put("ingredients", ingredients);
            JSONArray arr = new JSONArray();
            for (String f : flagged) arr.put(f);
            o.put("flagged", arr);
            o.put("clean", clean);
            o.put("timestamp", timestamp);
            o.put("gtin", gtin);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static ScannedProduct fromJson(JSONObject o) {
        if (o == null) return null;
        try {
            ScannedProduct p = new ScannedProduct();
            p.id = o.optString("id", UUID.randomUUID().toString());
            p.name = o.optString("name", "");
            p.brand = o.optString("brand", "");
            p.ingredients = o.optString("ingredients", "");
            JSONArray arr = o.optJSONArray("flagged");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String f = arr.optString(i, "").trim();
                    if (!f.isEmpty()) p.flagged.add(f);
                }
            }
            p.clean = o.optBoolean("clean", p.flagged.isEmpty());
            p.timestamp = o.optLong("timestamp", System.currentTimeMillis());
            p.gtin = o.optString("gtin", "");
            return p;
        } catch (Exception e) {
            return null;
        }
    }
}
