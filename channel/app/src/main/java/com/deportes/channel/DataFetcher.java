package com.deportes.channel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DataFetcher {

    private static final String FOOTBALL_KEY = BuildConfig.FOOTBALL_KEY;
    private static final ZoneId ZONE = ZoneId.of("Europe/Madrid");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private static final Map<Integer, String> LEAGUE_NAMES = new HashMap<>();
    static {
        LEAGUE_NAMES.put(140, "La Liga");
        LEAGUE_NAMES.put(39, "Premier League");
        LEAGUE_NAMES.put(2, "Champions League");
        LEAGUE_NAMES.put(3, "Europa League");
        LEAGUE_NAMES.put(848, "Conference League");
        LEAGUE_NAMES.put(143, "Copa del Rey");
        LEAGUE_NAMES.put(141, "Segunda División");
    }

    // Solo MotoGP (calendario estático). F1 va por Jolpica.
    private static final String[][] STATIC = {
        {"2026-10-24T04:00:00Z", "MotoGP", "MotoGP · GP Australia", "Sprint"},
        {"2026-10-25T03:00:00Z", "MotoGP", "MotoGP · GP Australia", "Carrera"},
        {"2026-10-31T07:00:00Z", "MotoGP", "MotoGP · GP Malasia", "Sprint"},
        {"2026-11-01T07:00:00Z", "MotoGP", "MotoGP · GP Malasia", "Carrera"},
        {"2026-11-07T17:00:00Z", "MotoGP", "MotoGP · GP Qatar", "Sprint"},
        {"2026-11-08T17:00:00Z", "MotoGP", "MotoGP · GP Qatar", "Carrera"},
        {"2026-11-21T15:00:00Z", "MotoGP", "MotoGP · GP Portugal", "Sprint"},
        {"2026-11-22T13:00:00Z", "MotoGP", "MotoGP · GP Portugal", "Carrera"},
        {"2026-11-28T14:00:00Z", "MotoGP", "MotoGP · GP Valencia", "Sprint"},
        {"2026-11-29T13:00:00Z", "MotoGP", "MotoGP · GP Valencia", "Carrera"},
    };

    public static class Event {
        public final String sport, comp, name, iso, time;
        public final LocalDate date;
        Event(String sport, String comp, String name, String iso, String time, LocalDate date) {
            this.sport = sport; this.comp = comp; this.name = name;
            this.iso = iso; this.time = time; this.date = date;
        }
    }

    public static List<Event> fetchAll() throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate max = today.plusDays(3);
        List<Event> out = new ArrayList<>();
        out.addAll(fetchF1(today, max));
        out.addAll(fetchFootball(today, max));
        for (String[] s : STATIC) {
            Event e = makeEvent(s[1], s[2], s[3], s[0]);
            if (!e.date.isBefore(today) && !e.date.isAfter(max)) out.add(e);
        }
        out.sort((a, b) -> a.iso.compareTo(b.iso));
        return out;
    }

    private static Event makeEvent(String sport, String comp, String name, String iso) {
        OffsetDateTime odt = OffsetDateTime.parse(iso);
        ZonedDateTime z = odt.atZoneSameInstant(ZONE);
        return new Event(sport, comp, name, iso, z.format(HM), z.toLocalDate());
    }

    private static List<Event> fetchF1(LocalDate today, LocalDate max) throws Exception {
        List<Event> out = new ArrayList<>();
        int year = LocalDate.now(ZONE).getYear();
        String json = httpGet("https://api.jolpi.ca/ergast/f1/" + year + ".json", null);
        JSONArray races = new JSONObject(json).getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races");
        for (int i = 0; i < races.length(); i++) {
            JSONObject r = races.getJSONObject(i);
            String raceName = r.getString("raceName");
            addSession(out, today, max, raceName, "Libres 1", r.optJSONObject("FirstPractice"));
            addSession(out, today, max, raceName, "Libres 2", r.optJSONObject("SecondPractice"));
            addSession(out, today, max, raceName, "Libres 3", r.optJSONObject("ThirdPractice"));
            addSession(out, today, max, raceName, "Sprint", r.optJSONObject("Sprint"));
            addSession(out, today, max, raceName, "Clasificación", r.optJSONObject("Qualifying"));
            addEvent(out, today, max, "F1", raceName, "Carrera", r.getString("date") + "T" + z(r.optString("time", "00:00:00Z")));
        }
        return out;
    }

    private static void addSession(List<Event> out, LocalDate today, LocalDate max, String comp, String name, JSONObject s) {
        if (s == null) return;
        addEvent(out, today, max, "F1", comp, name, s.optString("date", "") + "T" + z(s.optString("time", "00:00:00Z")));
    }

    private static void addEvent(List<Event> out, LocalDate today, LocalDate max, String sport, String comp, String name, String iso) {
        if (iso.startsWith("T")) return;
        Event e = makeEvent(sport, comp, name, iso);
        if (!e.date.isBefore(today) && !e.date.isAfter(max)) out.add(e);
    }

    private static String z(String t) {
        return (t != null && t.endsWith("Z")) ? t : t + "Z";
    }

    private static boolean isPriorityTeam(String name) {
        if (name == null) return false;
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase();
        return n.contains("real madrid") || n.contains("almeria");
    }

    private static List<Event> fetchFootball(LocalDate today, LocalDate max) throws Exception {
        List<Event> out = new ArrayList<>();
        if (FOOTBALL_KEY == null || FOOTBALL_KEY.isEmpty()) return out;
        for (int d = 0; d < 4; d++) {
            String date = today.plusDays(d).toString();
            String json = httpGet("https://v3.football.api-sports.io/fixtures?date=" + date, FOOTBALL_KEY);
            JSONArray arr = new JSONObject(json).optJSONArray("response");
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject f = arr.getJSONObject(i);
                JSONObject league = f.getJSONObject("league");
                int lid = league.getInt("id");
                String home = f.getJSONObject("teams").getJSONObject("home").getString("name");
                String away = f.getJSONObject("teams").getJSONObject("away").getString("name");
                if (isPriorityTeam(home) || isPriorityTeam(away)) {
                    String comp = LEAGUE_NAMES.containsKey(lid) ? LEAGUE_NAMES.get(lid) : league.getString("name");
                    String name = home + " vs " + away;
                    String iso = f.getJSONObject("fixture").getString("date");
                    Event e = makeEvent("Fútbol", comp, name, iso);
                    if (!e.date.isBefore(today) && !e.date.isAfter(max)) out.add(e);
                }
            }
        }
        return out;
    }

    private static String httpGet(String urlStr, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "deportes-channel/1.0");
        if (key != null) c.setRequestProperty("x-apisports-key", key);
        int code = c.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        c.disconnect();
        return sb.toString();
    }
}
