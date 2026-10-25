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
    private static final int LOOKAHEAD_DAYS = 10;

    static final class Event {
        final String time, name, comp, iso, day;
        Event(String time, String name, String comp, String iso) {
            this.time = time; this.name = name; this.comp = comp; this.iso = iso;
            this.day = dayLabel(iso);
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

    // Clubes "grandes" para detectar partidazos (nombre normalizado, coincidencia por subcadena).
    private static final String[] BIG = {
        "real madrid", "barcelona", "atletico madrid", "atletico de madrid", "sevilla",
        "manchester city", "arsenal", "liverpool", "manchester united", "chelsea", "tottenham",
        "bayern", "dortmund", "leipzig", "leverkusen",
        "juventus", "inter", "milan", "napoli", "roma", "atalanta",
        "paris saint", "marseille", "monaco", "lyon",
        "porto", "benfica", "sporting", "ajax", "psv", "feyenoord",
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
                all.add(new Event(timeOf(s[0]), s[2], s[1], s[0]));
            }
        }

        // Omitir eventos que empezaron hace más de 3 horas.
        List<Event> fresh = new ArrayList<>();
        for (Event e : all) {
            if (!OffsetDateTime.parse(e.iso).toInstant().isBefore(cutoff)) fresh.add(e);
        }
        fresh.sort((a, b) -> a.iso.compareTo(b.iso));

        // Quedarnos con los 3 próximos días que tengan eventos.
        return firstThreeDays(fresh);
    }

    private static List<Event> firstThreeDays(List<Event> all) {
        LinkedHashMap<LocalDate, List<Event>> byDay = new LinkedHashMap<>();
        for (Event e : all) {
            LocalDate d = dateOf(e.iso);
            if (!byDay.containsKey(d)) byDay.put(d, new ArrayList<Event>());
            byDay.get(d).add(e);
        }
        List<LocalDate> days = new ArrayList<>(byDay.keySet());
        Collections.sort(days);
        List<Event> out = new ArrayList<>();
        for (int i = 0; i < days.size() && i < 3; i++) {
            out.addAll(byDay.get(days.get(i)));
        }
        return out;
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

    /** Primer equipo (no filial/B): "Real Madrid" sí, "Real Madrid II"/"Almería B" no. */
    private static boolean isFirstTeam(String name, String target) {
        if (name == null) return false;
        String n = normalize(name);
        if (!n.contains(target)) return false;
        return !(n.contains(" ii") || n.contains(" castilla") || n.contains(" b")
                || n.contains("reserva") || n.contains("filial"));
    }

    private static boolean isBigTeam(String name) {
        if (name == null) return false;
        String n = normalize(name);
        for (String b : BIG) if (n.contains(b)) return true;
        return false;
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
            if (!d.isBefore(today) && !d.isAfter(max)) out.add(new Event(timeOf(iso), "Carrera", raceName, iso));
        }
        return out;
    }

    private static void addSession(List<Event> out, LocalDate today, LocalDate max, String comp, String name, JSONObject s) {
        if (s == null) return;
        String iso = s.optString("date", "") + "T" + z(s.optString("time", "00:00:00Z"));
        if (iso.startsWith("T")) return;
        LocalDate d = dateOf(iso);
        if (!d.isBefore(today) && !d.isAfter(max)) out.add(new Event(timeOf(iso), name, comp, iso));
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
                boolean priority = isFirstTeam(home, "real madrid") || isFirstTeam(away, "real madrid")
                        || isFirstTeam(home, "almeria") || isFirstTeam(away, "almeria");
                boolean partidazo = isBigTeam(home) && isBigTeam(away);
                if (!priority && !partidazo) continue;
                String comp = LEAGUE_NAMES.containsKey(lid) ? LEAGUE_NAMES.get(lid) : leagueName;
                String name = (partidazo ? "★ " : "") + home + " vs " + away;
                String iso = f.getJSONObject("fixture").getString("date");
                LocalDate dd = dateOf(iso);
                if (dd.isBefore(today) || dd.isAfter(max)) continue;
                out.add(new Event(timeOf(iso), name, comp, iso));
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
