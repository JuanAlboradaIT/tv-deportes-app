package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Eventos deportivos del panel de la home (fork).
 *
 * Se descargan ya procesados desde GitHub Pages (sports.json): así el panel se puede
 * cambiar sin tocar la TV (equipos, horarios de MotoGP, competiciones, horizonte...).
 * La app solo aplica el filtro de 3 horas, ordena y limita a 20 eventos. Las horas se
 * muestran siempre en Europe/Madrid.
 */
final class Sports {

    /** JSON generado cada 4h por el Action de GitHub Pages. */
    private static final String[] URLS = {
            "https://juanalboradait.github.io/tv-deportes/sports.json",
            "https://raw.githubusercontent.com/JuanAlboradaIT/tv-deportes/master/sports.json"
    };
    private static final ZoneId ZONE = ZoneId.of("Europe/Madrid");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MAX_EVENTS = 20;
    private static final int CUTOFF_HOURS = 3;

    static final class Event {
        final String time, name, comp, iso, day;
        final boolean highlight;
        Event(String time, String name, String comp, String iso, boolean highlight) {
            this.time = time; this.name = name; this.comp = comp; this.iso = iso;
            this.day = dayLabel(iso);
            this.highlight = highlight;
        }
    }

    static List<Event> fetch() throws Exception {
        Exception last = null;
        for (String base : URLS) {
            try {
                // El parámetro evita servir una copia cacheada tras cambiar el JSON.
                return parse(httpGet(base + "?t=" + System.currentTimeMillis()));
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("sin datos de deportes");
    }

    private static List<Event> parse(String json) throws Exception {
        JSONArray arr = new JSONArray(json);

        Instant cutoff = Instant.now().minus(Duration.ofHours(CUTOFF_HOURS));
        List<Event> fresh = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String iso = o.getString("iso");
            Instant when = OffsetDateTime.parse(iso).toInstant();
            if (when.isBefore(cutoff)) continue; // empezó hace más de 3 horas
            fresh.add(new Event(timeOf(iso), o.getString("name"), o.getString("comp"), iso,
                    o.optBoolean("highlight", false)));
        }
        fresh.sort((a, b) -> OffsetDateTime.parse(a.iso).toInstant()
                .compareTo(OffsetDateTime.parse(b.iso).toInstant()));
        return fresh.size() > MAX_EVENTS ? fresh.subList(0, MAX_EVENTS) : fresh;
    }

    private static LocalDate dateOf(String iso) {
        return OffsetDateTime.parse(iso).atZoneSameInstant(ZONE).toLocalDate();
    }

    private static String timeOf(String iso) {
        return OffsetDateTime.parse(iso).atZoneSameInstant(ZONE).format(HM);
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

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "tvhome-sports/1.0");
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
