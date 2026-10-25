package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Eventos deportivos para el panel de la home (fork). F1 por Jolpica (sin clave), fútbol por
 * API-Football (clave en BuildConfig) y MotoGP por calendario estático. Se muestran los 3 próximos
 * días con eventos (saltando días vacíos) y se omiten los eventos con más de 3 horas.
 */
final class Sports {

    private static final String FOOTBALL_KEY = BuildConfig.FOOTBALL_KEY;
    private static final ZoneId ZONE = ZoneId.of("Europe/Madrid");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final int LOOKAHEAD_DAYS = 14;

    static final class Event {
        final String time, name, comp, iso, day;
        final boolean highlight;
        Event(String time, String name, String comp, String iso, boolean highlight) {
            this.time = time; this.name = name; this.comp = comp; this.iso = iso;
            this.day = dayLabel(iso);
            this.highlight = highlight;
        }
    }

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

    private static final String[][] STATIC = {
        {"2026-10-24T04:00:00Z", "MotoGP · GP Australia", "Sprint"},
        {"2026-10-25T03:00:00Z", "MotoGP · GP Australia", "Carrera"},
        {"2026-10-31T07:00:00Z", "MotoGP · GP Malasia", "Sprint"},
        {"2026-11-01T07:00:00Z", "MotoGP · GP Malasia", "Carrera"},
        {"2026-11-07T17:00:00Z", "MotoGP · GP Qatar", "Sprint"},
        {"2026-11-08T17:00:00Z", "MotoGP · GP Qatar", "Carrera"},
        {"2026-11-21T15:00:00Z", "MotoGP · GP Portugal", "Sprint"},
        {"2026-11-22T13:00:00Z", "MotoGP · GP Portugal", "Carrera"},
        {"2026-11-28T14:00:00Z", "MotoGP · GP Valencia", "Sprint"},
        {"2026-11-29T13:00:00Z", "MotoGP · GP Valencia", "Carrera"},
    };

    static List<Event> fetch() throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate max = today.plusDays(LOOKAHEAD_DAYS);
        Instant cutoff = Instant.now().minus(Duration.ofHours(3));

        List<Event> all = new ArrayList<>();
        all.addAll(fetchF1(today, max));
        all.addAll(fetchFootball(today, max));
        for (String[] s : STATIC) {
            LocalDate d = dateOf(s[0]);
            if (!d.isBefore(today) && !d.isAfter(max)) {
                all.add(new Event(timeOf(s[0]), s[2], s[1], s[0], true));
            }
        }

        // Omitir eventos que empezaron hace más de 3 horas.
        List<Event> fresh = new ArrayList<>();
        for (Event e : all) {
            if (!OffsetDateTime.parse(e.iso).toInstant().isBefore(cutoff)) fresh.add(e);
        }
        fresh.sort((a, b) -> a.iso.compareTo(b.iso));

        // Los 20 primeros (10 por columna), mirando hacia el futuro lo que haga falta.
        return fresh.size() > 20 ? fresh.subList(0, 20) : fresh;
    }

    private static LocalDate dateOf(String iso) {
        return OffsetDateTime.parse(iso).atZoneSameInstant(ZONE).toLocalDate();
    }

    private static String timeOf(String iso) {
        return OffsetDateTime.parse(iso).atZoneSameInstant(ZONE).format(HM);
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase();
    }

    private static String dayLabel(String iso) {
        LocalDate d = dateOf(iso);
        LocalDate today = LocalDate.now(ZONE);
        if (d.equals(today)) return "Hoy";
        if (d.equals(today.plusDays(1))) return "Mañana";
        String wd = d.getDayOfWeek().getDisplayName(
                java.time.format.TextStyle.SHORT, java.util.Locale.forLanguageTag("es"));
        return wd.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + wd.substring(1);
    }

    private static boolean isWomens(String name) {
        if (name == null) return false;
        String n = normalize(name);
        return n.contains("femenino") || n.contains("femenina") || n.contains("women")
                || n.contains("woman") || n.contains("nwsl") || n.contains("wsl");
    }

    /** Equipos de interés (primer equipo, sin filiales/B). */
    private static final String[] PRIORITY_TEAMS = {
        "real madrid", "almeria", "atletico madrid", "atletico de madrid", "barcelona",
        "manchester united", "manchester city", "paris saint germain",
        "liverpool", "arsenal",
    };

    /** Indicadores de filial/B/reservas en el nombre (U21, U23, II, Castilla, B...). */
    private static final String[] RESERVE_MARKERS = {
        " b", " ii", " castilla", " u17", " u18", " u19", " u20", " u21", " u23", " u24",
        " reserva", " filial", " reserves", " academy", " youth", " eds",
    };

    private static boolean isPriorityTeam(String name) {
        if (name == null) return false;
        String n = normalize(name);
        for (String m : RESERVE_MARKERS) if (n.contains(m)) return false;
        for (String t : PRIORITY_TEAMS) if (n.contains(t)) return true;
        return false;
    }

    /** Real Madrid y Almería (los que se destacan en negrita). */
    private static boolean isHighlightTeam(String name) {
        if (name == null) return false;
        String n = normalize(name);
        for (String m : RESERVE_MARKERS) if (n.contains(m)) return false;
        return n.contains("real madrid") || n.contains("almeria");
    }

    private static List<Event> fetchF1(LocalDate today, LocalDate max) throws Exception {
        List<Event> out = new ArrayList<>();
        int year = LocalDate.now(ZONE).getYear();
        String json = httpGet("https://api.jolpi.ca/ergast/f1/" + year + ".json", null);
        JSONArray races = new JSONObject(json).getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races");
        for (int i = 0; i < races.length(); i++) {
            JSONObject r = races.getJSONObject(i);
            String raceName = r.getString("raceName").replace("Grand Prix", "GP").trim();
            addSession(out, today, max, raceName, "Libres 1", r.optJSONObject("FirstPractice"));
            addSession(out, today, max, raceName, "Libres 2", r.optJSONObject("SecondPractice"));
            addSession(out, today, max, raceName, "Libres 3", r.optJSONObject("ThirdPractice"));
            addSession(out, today, max, raceName, "Sprint", r.optJSONObject("Sprint"));
            addSession(out, today, max, raceName, "Clasif.", r.optJSONObject("Qualifying"));
            String iso = r.getString("date") + "T" + z(r.optString("time", "00:00:00Z"));
            LocalDate d = dateOf(iso);
            if (!d.isBefore(today) && !d.isAfter(max)) out.add(new Event(timeOf(iso), "Carrera", raceName, iso, true));
        }
        return out;
    }

    private static void addSession(List<Event> out, LocalDate today, LocalDate max, String comp, String name, JSONObject s) {
        if (s == null) return;
        String iso = s.optString("date", "") + "T" + z(s.optString("time", "00:00:00Z"));
        if (iso.startsWith("T")) return;
        LocalDate d = dateOf(iso);
        if (!d.isBefore(today) && !d.isAfter(max)) out.add(new Event(timeOf(iso), name, comp, iso, true));
    }

    private static String z(String t) {
        return (t != null && t.endsWith("Z")) ? t : t + "Z";
    }

    private static List<Event> fetchFootball(LocalDate today, LocalDate max) throws Exception {
        List<Event> out = new ArrayList<>();
        if (FOOTBALL_KEY == null || FOOTBALL_KEY.isEmpty()) return out;
        for (int d = 0; d < LOOKAHEAD_DAYS; d++) {
            String date = today.plusDays(d).toString();
            String json = httpGet("https://v3.football.api-sports.io/fixtures?date=" + date, FOOTBALL_KEY);
            JSONArray arr = new JSONObject(json).optJSONArray("response");
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject f = arr.getJSONObject(i);
                JSONObject league = f.getJSONObject("league");
                int lid = league.getInt("id");
                String leagueName = league.getString("name");
                if (isWomens(leagueName)) continue;
                String home = f.getJSONObject("teams").getJSONObject("home").getString("name");
                String away = f.getJSONObject("teams").getJSONObject("away").getString("name");
                if (isWomens(home) || isWomens(away)) continue;
                boolean priority = isPriorityTeam(home) || isPriorityTeam(away);
                boolean champions = lid == 2;
                if (!priority && !champions) continue;
                String comp = LEAGUE_NAMES.containsKey(lid) ? LEAGUE_NAMES.get(lid) : leagueName;
                String name = home + " vs " + away;
                boolean highlight = isHighlightTeam(home) || isHighlightTeam(away);
                String iso = f.getJSONObject("fixture").getString("date");
                LocalDate dd = dateOf(iso);
                if (dd.isBefore(today) || dd.isAfter(max)) continue;
                out.add(new Event(timeOf(iso), name, comp, iso, highlight));
            }
        }
        return out;
    }

    private static String httpGet(String urlStr, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "tvhome-sports/1.0");
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

    private Sports() {
    }
}
