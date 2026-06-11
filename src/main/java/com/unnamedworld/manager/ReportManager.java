package com.unnamedworld.manager;

import com.google.gson.*;
import com.unnamedworld.ServerMod;

import java.io.*;
import java.util.*;

public class ReportManager {

    public static final ReportManager INSTANCE = new ReportManager();

    public static class Report {
        public final int    id;
        public final String playerUUID;
        public final String playerName;
        public final String message;
        public final long   timestamp;
        public boolean      resolved;

        Report(int id, String playerUUID, String playerName, String message, long timestamp, boolean resolved) {
            this.id         = id;
            this.playerUUID = playerUUID;
            this.playerName = playerName;
            this.message    = message;
            this.timestamp  = timestamp;
            this.resolved   = resolved;
        }
    }

    private final List<Report> reports = new ArrayList<>();
    private int  nextId   = 1;
    private File saveFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public void init(File configDir) {
        saveFile = new File(configDir, "reports.json");
        load();
    }

    // --- Public API ---

    public synchronized int addReport(UUID playerUUID, String playerName, String message) {
        int id = nextId++;
        reports.add(new Report(id, playerUUID.toString(), playerName, message,
                System.currentTimeMillis(), false));
        save();
        return id;
    }

    public synchronized List<Report> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(reports));
    }

    public synchronized List<Report> getUnresolved() {
        List<Report> result = new ArrayList<>();
        for (Report r : reports) {
            if (!r.resolved) result.add(r);
        }
        return result;
    }

    public synchronized Report getById(int id) {
        for (Report r : reports) {
            if (r.id == id) return r;
        }
        return null;
    }

    public synchronized boolean resolve(int id) {
        for (Report r : reports) {
            if (r.id == id) {
                if (r.resolved) return false;
                r.resolved = true;
                save();
                return true;
            }
        }
        return false;
    }

    public synchronized boolean delete(int id) {
        Iterator<Report> it = reports.iterator();
        while (it.hasNext()) {
            if (it.next().id == id) {
                it.remove();
                save();
                return true;
            }
        }
        return false;
    }

    // --- Persistence ---

    private void load() {
        if (!saveFile.exists()) return;
        try (Reader r = new FileReader(saveFile)) {
            JsonObject obj = gson.fromJson(r, JsonObject.class);
            if (obj == null) return;
            if (obj.has("nextId"))   nextId = obj.get("nextId").getAsInt();
            if (obj.has("reports")) {
                for (JsonElement elem : obj.getAsJsonArray("reports")) {
                    JsonObject ro = elem.getAsJsonObject();
                    reports.add(new Report(
                            ro.get("id").getAsInt(),
                            ro.get("playerUUID").getAsString(),
                            ro.get("playerName").getAsString(),
                            ro.get("message").getAsString(),
                            ro.get("timestamp").getAsLong(),
                            ro.get("resolved").getAsBoolean()
                    ));
                }
            }
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to load reports", e);
        }
    }

    private void save() {
        try (Writer w = new FileWriter(saveFile)) {
            JsonObject obj = new JsonObject();
            obj.addProperty("nextId", nextId);
            JsonArray arr = new JsonArray();
            for (Report r : reports) {
                JsonObject ro = new JsonObject();
                ro.addProperty("id",         r.id);
                ro.addProperty("playerUUID", r.playerUUID);
                ro.addProperty("playerName", r.playerName);
                ro.addProperty("message",    r.message);
                ro.addProperty("timestamp",  r.timestamp);
                ro.addProperty("resolved",   r.resolved);
                arr.add(ro);
            }
            obj.add("reports", arr);
            gson.toJson(obj, w);
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to save reports", e);
        }
    }
}
