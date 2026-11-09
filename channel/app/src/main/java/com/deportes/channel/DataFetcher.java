package com.deportes.channel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Lee los eventos ya procesados desde GitHub Pages (sports.json). No calcula nada ni usa
 * claves de API: cambiar el panel es editar config.json/motogp.json en el repo.
 */
public class DataFetcher {

    private static final String[] URLS = {
            "https://juanalboradait.github.io/tv-deportes/sports.json",
            "https://raw.githubusercontent.com/JuanAlboradaIT/tv-deportes/master/sports.json"
    };
    private static final ZoneId ZONE = ZoneId.of("Europe/Madrid");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    /** La fila de canales solo enseña los próximos días. */
    private static final int DAYS_AHEAD = 4;

    public static class Event {
        public final String sport, comp, name, iso, time;
        public final LocalDate date;
        Event(String sport, String comp, String name, String iso, String time, LocalDate date) {
            this.sport = sport; this.comp = comp; this.name = name;
            this.iso = iso; this.time = time; this.date = date;
        }
    }

    public static List<Event> fetchAll() throws Exception {
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
        LocalDate today = LocalDate.now(ZONE);
        LocalDate max = today.plusDays(DAYS_AHEAD);

        List<Event> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String iso = o.getString("iso");
            Event e = makeEvent(sportOf(o.getString("comp")), o.getString("comp"),
                    o.getString("name"), iso);
            if (e.date.isBefore(today) || e.date.isAfter(max)) continue;
            out.add(e);
        }
        out.sort((a, b) -> OffsetDateTime.parse(a.iso).toInstant()
                .compareTo(OffsetDateTime.parse(b.iso).toInstant()));
        return out;
    }

    /** El JSON no trae el deporte; se deduce del nombre de la competición. */
    private static String sportOf(String comp) {
        if (comp.startsWith("MotoGP")) return "MotoGP";
        if (comp.contains("GP")) return "F1";
        return "Fútbol";
    }

    private static Event makeEvent(String sport, String comp, String name, String iso) {
        OffsetDateTime odt = OffsetDateTime.parse(iso);
        ZonedDateTime z = odt.atZoneSameInstant(ZONE);
        return new Event(sport, comp, name, iso, z.format(HM), z.toLocalDate());
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "deportes-channel/1.0");
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
