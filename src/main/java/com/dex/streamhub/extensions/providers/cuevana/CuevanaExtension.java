package com.dex.streamhub.extensions.providers.cuevana;

import android.text.Html;
import android.util.Log;

import com.dex.streamhub.extensions.api.ExtensionProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Extensión Cuevana3 para MovaPlus, cargada dinámicamente por ExtensionLoader.
 *
 * Es tu ServerCuevana.java original, con dos cambios:
 *  1. Implementa ExtensionProvider (getName/getVersion/fetchCatalog/fetchSearch/
 *     fetchDetail/fetchServers) para que el loader pueda usarla sin conocer
 *     Cuevana específicamente.
 *  2. Requiere un constructor público sin argumentos (lo exige DexClassLoader
 *     al hacer newInstance()).
 *
 * Toda la lógica interna de scraping es idéntica a la que ya tenías.
 */
public final class CuevanaExtension implements ExtensionProvider {

    private static final String BASE_URL = "https://cuevana3.sc";
    private static final String API_BASE = BASE_URL + "/wp-json/cuevana/v1";
    private static final String IMAGE_BASE = "https://image.tmdb.org/t/p/original";
    private static final String TAG = "CuevanaExtension";
    private static final int TIMEOUT = 20000;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    static {
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
        }
    }

    // Constructor público sin argumentos: obligatorio para que el loader la instancie.
    public CuevanaExtension() {
    }

    // ─── CONTRATO ExtensionProvider ──────────────────────────────────────

    @Override
    public String getName() {
        return "Cuevana3";
    }

    @Override
    public int getVersion() {
        return 1; // súbelo cada vez que publiques un .jar nuevo
    }

    @Override
    public List<Map<String, String>> fetchCatalog(String type, int page) throws Exception {
        return "series".equals(type) ? fetchSeriesList(page) : fetchMovieList(page);
    }

    @Override
    public List<Map<String, String>> fetchSearch(String query, int page) throws Exception {
        return fetchSearchResults(query, page);
    }

    @Override
    public Map<String, Object> fetchDetail(String detailUrl) throws Exception {
        return fetchDetailInternal(detailUrl);
    }

    @Override
    public List<Map<String, String>> fetchServers(String episodeUrl) throws Exception {
        return fetchEmbeds(episodeUrl);
    }

    // ─── LISTADO DE PELÍCULAS Y SERIES (idéntico a ServerCuevana.java) ──

    public static List<Map<String, String>> fetchMovieList(int page) throws Exception {
        return fetchChome(page, "movies");
    }

    public static List<Map<String, String>> fetchSeriesList(int page) throws Exception {
        return fetchChome(page, "series");
    }

    private static List<Map<String, String>> fetchChome(int page, String type) throws Exception {
        String url = API_BASE + "/chome?paged=" + page + "&limit=20&lang=any&type=" + type;
        String json = fetchJson(url);

        if (json == null || json.trim().isEmpty()) {
            Log.w(TAG, "fetchChome: respuesta vacía para " + type);
            return new ArrayList<>();
        }

        return parseChomeResponse(json, type);
    }

    private static List<Map<String, String>> parseChomeResponse(String json, String defaultType) throws Exception {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray dataArray = root.optJSONArray("data");
            if (dataArray == null) return new ArrayList<>();

            List<Map<String, String>> items = new ArrayList<>();
            for (int i = 0; i < dataArray.length(); i++) {
                JSONObject item = dataArray.getJSONObject(i);
                JSONObject info = item.optJSONObject("info");
                Map<String, String> map = new HashMap<>();

                // El id real viene en info._id, no a nivel raíz (confirmado en producción)
                String id = info != null ? String.valueOf(info.optLong("_id")) : "";

                String title = item.optString("title", "Sin título");
                map.put("title", Html.fromHtml(title).toString());

                String link = item.optString("link", "");
                if (link.startsWith("/")) link = BASE_URL + link;
                if (id.isEmpty() || id.equals("0")) id = extractPostId(link);
                map.put("id", id == null ? "" : id);
                map.put("url", link);

                String image = item.optString("image", "");
                if (image.isEmpty()) image = item.optString("poster", "");
                if (image.isEmpty() && info != null) image = info.optString("cover", "");
                map.put("image", resolveImage(image));

                String type = item.optString("type", defaultType);
                String year = item.optString("year", "");
                if (year.isEmpty() && info != null) {
                    String release = info.optString("release", "");
                    if (!release.isEmpty() && release.length() >= 4) {
                        year = release.substring(0, 4);
                    }
                }
                map.put("year", year);

                String synopsis = item.optString("synopsis", "");
                if (synopsis.isEmpty() && info != null) synopsis = info.optString("desc", "");
                if (!synopsis.isEmpty() && !synopsis.equals("null")) {
                    map.put("synopsis", Html.fromHtml(synopsis).toString());
                }

                map.put("type", type);
                items.add(map);
            }

            Log.d(TAG, "parseChomeResponse: " + items.size() + " items para " + defaultType);
            return items;

        } catch (Exception e) {
            Log.e(TAG, "parseChomeResponse: error", e);
            return new ArrayList<>();
        }
    }

    // ─── BÚSQUEDA ──────────────────────────────────────────────────────

    public static List<Map<String, String>> fetchSearchResults(String query, int page) throws Exception {
        String encoded = URLEncoder.encode(query, "UTF-8");
        String url = API_BASE + "/search?q=" + encoded;
        String json = fetchJson(url);

        if (json == null || json.trim().isEmpty()) return new ArrayList<>();

        try {
            JSONObject root = new JSONObject(json);
            JSONArray dataArray = root.optJSONArray("data");
            if (dataArray == null) return new ArrayList<>();

            List<Map<String, String>> items = new ArrayList<>();
            for (int i = 0; i < dataArray.length(); i++) {
                JSONObject item = dataArray.getJSONObject(i);
                Map<String, String> map = mapFromSearchOrSingleItem(item);
                if (map != null) items.add(map);
            }
            return items;

        } catch (Exception e) {
            Log.e(TAG, "fetchSearchResults: error parseando JSON", e);
            return new ArrayList<>();
        }
    }

    // ─── DETALLE ──────────────────────────────────────────────────────

    private static Map<String, Object> fetchDetailInternal(String detailUrl) throws Exception {
        String postId = extractPostId(detailUrl);
        boolean isSeries = detailUrl.contains("/series-online/");
        String type = isSeries ? "series" : "movies";

        if (postId == null || postId.isEmpty()) {
            return emptyDetail(isSeries);
        }

        String url = API_BASE + "/single/?pid=" + postId + "&type=" + type;
        String json = fetchJson(url);

        if (json == null || json.trim().isEmpty()) {
            return emptyDetail(isSeries);
        }

        try {
            JSONObject root = new JSONObject(json);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return emptyDetail(isSeries);

            String title = data.optString("title", "Sin título");
            String id = data.optString("id", postId);

            JSONObject info = data.optJSONObject("info");
            Map<String, Object> detail = new HashMap<>();
            detail.put("title", Html.fromHtml(title).toString());
            detail.put("isSeries", isSeries);
            detail.put("id", id);

            if (info != null) {
                detail.put("poster", resolveImage(info.optString("cover", "")));
                detail.put("backdrop", resolveImage(info.optString("backdrop", "")));

                String release = info.optString("release", "");
                if (!release.isEmpty() && release.length() >= 4) {
                    detail.put("year", release.substring(0, 4));
                }

                String rating = info.optString("ratingValue", "");
                detail.put("rating", rating);
                detail.put("duration", info.optString("runtime", ""));

                String desc = info.optString("desc", "");
                if (!desc.isEmpty() && !desc.equals("null")) {
                    detail.put("synopsis", Html.fromHtml(desc).toString());
                } else {
                    String tituloOriginal = info.optString("original_title", title);
                    String tipoContenido = isSeries ? "Serie" : "Película";
                    StringBuilder synopsis = new StringBuilder();
                    synopsis.append(tituloOriginal).append(" es una ").append(tipoContenido);
                    if (!release.isEmpty() && release.length() >= 4) {
                        synopsis.append(" estrenada en ").append(release, 0, 4);
                    }
                    synopsis.append(". Disfrútala gratis solo en Cuevana3");
                    detail.put("synopsis", synopsis.toString());
                }

                JSONArray genres = info.optJSONArray("genres");
                String genresStr = extractStringArray(genres);
                detail.put("genres", genresStr.isEmpty() ? (isSeries ? "Serie" : "Película") : genresStr);

                JSONArray director = info.optJSONArray("director");
                String directorStr = extractStringArray(director);
                detail.put("director", directorStr.isEmpty() ? "Desconocido" : directorStr);

                JSONArray cast = info.optJSONArray("cast");
                String castStr = extractStringArray(cast);
                detail.put("cast", castStr.isEmpty() ? "No especificado" : castStr);
            } else {
                detail.put("poster", "");
                detail.put("synopsis", "Sin información disponible.");
                detail.put("genres", isSeries ? "Serie" : "Película");
                detail.put("director", "Desconocido");
                detail.put("cast", "No especificado");
            }

            if (isSeries) {
                detail.put("episodes", fetchEpisodes(postId));
            } else {
                List<Map<String, String>> episodes = new ArrayList<>();
                Map<String, String> ep = new HashMap<>();
                ep.put("title", "Ver Película");
                ep.put("url", detailUrl);
                ep.put("image", (String) detail.get("poster"));
                ep.put("id", postId);
                episodes.add(ep);
                detail.put("episodes", episodes);
            }

            return detail;

        } catch (Exception e) {
            Log.e(TAG, "fetchDetail: error", e);
            return emptyDetail(isSeries);
        }
    }

    private static String extractStringArray(JSONArray array) {
        if (array == null || array.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            try {
                Object item = array.get(i);
                String value = "";
                if (item instanceof JSONArray) {
                    JSONArray pair = (JSONArray) item;
                    if (pair.length() > 0) value = pair.optString(0, "");
                } else if (item instanceof String) {
                    value = (String) item;
                } else {
                    value = array.optString(i, "");
                }
                if (!value.isEmpty()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(value);
                }
            } catch (JSONException e) {
                String val = array.optString(i, "");
                if (!val.isEmpty()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(val);
                }
            }
        }
        return sb.toString();
    }

    private static Map<String, Object> emptyDetail(boolean isSeries) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("title", "Sin título");
        detail.put("poster", "");
        detail.put("synopsis", "No se pudo cargar la información.");
        detail.put("isSeries", isSeries);
        detail.put("genres", isSeries ? "Serie" : "Película");
        detail.put("director", "Desconocido");
        detail.put("cast", "No especificado");
        detail.put("episodes", new ArrayList<Map<String, String>>());
        return detail;
    }

    // ─── EPISODIOS ──────────────────────────────────────────────────────

    private static List<Map<String, String>> fetchEpisodes(String postId) throws Exception {
        String url = API_BASE + "/Episodes/" + postId;
        String json = fetchJson(url);
        if (json == null || json.trim().isEmpty()) return new ArrayList<>();

        try {
            JSONObject root = new JSONObject(json);
            JSONArray seasons = root.optJSONArray("data");
            List<Map<String, String>> episodes = new ArrayList<>();
            if (seasons == null) return episodes;

            for (int s = 0; s < seasons.length(); s++) {
                JSONArray seasonEpisodes = seasons.optJSONArray(s);
                if (seasonEpisodes == null) continue;

                for (int e = 0; e < seasonEpisodes.length(); e++) {
                    JSONObject ep = seasonEpisodes.getJSONObject(e);
                    Map<String, String> map = new HashMap<>();

                    String epTitle = ep.optString("name", "Episodio " + (e + 1));
                    if (epTitle.isEmpty()) epTitle = "Episodio " + (e + 1);
                    map.put("title", epTitle);
                    map.put("season", String.valueOf(ep.optInt("snum", s + 1)));
                    map.put("episode", String.valueOf(ep.optInt("enum", e + 1)));
                    map.put("overview", ep.optString("overview", ""));
                    map.put("image", resolveImage(ep.optString("still_path", "")));

                    String slug = ep.optString("slug", "");
                    String epUrl;
                    if (!slug.isEmpty()) {
                        epUrl = BASE_URL + slug;
                    } else {
                        int seasonNum = ep.optInt("snum", s + 1);
                        int episodeNum = ep.optInt("enum", e + 1);
                        epUrl = BASE_URL + "/ver-el-episodio/" + postId + "/temporada-" + seasonNum + "/episodio-" + episodeNum + "/";
                    }
                    map.put("url", epUrl);
                    map.put("id", postId);
                    episodes.add(map);
                }
            }
            return episodes;

        } catch (Exception e) {
            Log.e(TAG, "fetchEpisodes: error", e);
            return new ArrayList<>();
        }
    }

    // ─── SERVIDORES / EMBEDS ────────────────────────────────────────────

    public static List<Map<String, String>> fetchEmbeds(String episodeUrl) throws Exception {
        String postId = extractPostId(episodeUrl);
        if (postId == null) return new ArrayList<>();

        boolean isEpisode = episodeUrl.contains("/ver-el-episodio/");
        String json;

        if (isEpisode) {
            String season = "1";
            String episode = "1";
            Matcher sm = Pattern.compile("temporada[-\\s]*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(episodeUrl);
            if (sm.find()) season = sm.group(1);
            Matcher em = Pattern.compile("episodio[-\\s]*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(episodeUrl);
            if (em.find()) episode = em.group(1);
            json = fetchJson(API_BASE + "/episode/" + postId + "/" + season + "/" + episode);
        } else {
            json = fetchJson(API_BASE + "/player/" + postId);
        }

        if (json == null || json.trim().isEmpty()) return new ArrayList<>();

        try {
            JSONObject root = new JSONObject(json);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return new ArrayList<>();

            JSONArray embedsArr = data.optJSONArray("embeds");
            List<Map<String, String>> result = new ArrayList<>();
            if (embedsArr == null) return result;

            for (int i = 0; i < embedsArr.length(); i++) {
                JSONObject e = embedsArr.getJSONObject(i);
                String url = e.optString("url", "");
                if (url.isEmpty()) continue;

                String resolvedUrl = resolveFakePlayerUrl(url);
                if (resolvedUrl != null && !resolvedUrl.isEmpty()) url = resolvedUrl;

                Map<String, String> map = new HashMap<>();
                map.put("server", e.optString("server", ""));
                map.put("service_name", e.optString("service_name", ""));
                map.put("url", url);
                map.put("quality", e.optString("quality", ""));
                map.put("audio", e.optString("audio", ""));
                result.add(map);
            }
            return result;

        } catch (Exception e) {
            Log.e(TAG, "fetchEmbeds: error parseando", e);
            return new ArrayList<>();
        }
    }

    private static String resolveFakePlayerUrl(String fakeUrl) {
        if (fakeUrl == null || fakeUrl.isEmpty()) return fakeUrl;
        if (!fakeUrl.contains("fakeplayer") && !fakeUrl.contains("doo.lat") && !fakeUrl.contains("goo.lat")) {
            return fakeUrl;
        }
        try {
            String html = fetchHtml(fakeUrl);
            if (html == null || html.isEmpty()) return fakeUrl;

            Pattern urlPattern = Pattern.compile("var\\s+url\\s*=\\s*['\"]([^'\"]+)['\"]");
            Matcher urlMatcher = urlPattern.matcher(html);
            if (urlMatcher.find()) return urlMatcher.group(1);

            Pattern embedPattern = Pattern.compile("(https?://(?:streamwish|doodstream|filemoon|embedwish|waaw|sbface|voe)\\.[^\\s'\"]+)");
            Matcher embedMatcher = embedPattern.matcher(html);
            if (embedMatcher.find()) return embedMatcher.group(1);

            Pattern anyUrlPattern = Pattern.compile("(https?://[^\\s'\"]+\\.(?:to|com|net|tv|sx|io|xyz)/[^\\s'\"]+)");
            Matcher anyMatcher = anyUrlPattern.matcher(html);
            while (anyMatcher.find()) {
                String found = anyMatcher.group(1);
                if (!found.contains("fakeplayer") && !found.contains("histats") &&
                        !found.contains("yandex") && !found.contains("cloudflare") &&
                        !found.contains("google") && !found.contains("analytics") &&
                        !found.contains("doubleclick") && !found.contains("ads") &&
                        !found.contains("googlesyndication")) {
                    return found;
                }
            }
            return fakeUrl;

        } catch (Exception e) {
            Log.e(TAG, "resolveFakePlayerUrl: error resolviendo " + fakeUrl, e);
            return fakeUrl;
        }
    }

    // ─── UTILIDADES ──────────────────────────────────────────────────

    private static Map<String, String> mapFromSearchOrSingleItem(JSONObject item) {
        Map<String, String> map = new HashMap<>();

        String title = item.optString("title", "Sin título");
        map.put("title", Html.fromHtml(title).toString());

        String link = item.optString("link", "");
        if (!link.isEmpty() && link.startsWith("/")) link = BASE_URL + link;
        map.put("url", link);

        JSONObject info = item.optJSONObject("info");
        String id = "";
        String type = link.contains("/series-online/") ? "series" : "movies";

        if (info != null) {
            id = info.has("_id") ? String.valueOf(info.optLong("_id")) : "";
            map.put("id", id);
            map.put("image", resolveImage(info.optString("cover", "")));

            String release = info.optString("release", "");
            if (!release.isEmpty() && release.length() >= 4) map.put("year", release.substring(0, 4));

            String desc = info.optString("desc", "");
            if (!desc.isEmpty() && !desc.equals("null")) map.put("synopsis", Html.fromHtml(desc).toString());

            map.put("rating", info.optString("ratingValue", ""));
            map.put("runtime", info.optString("runtime", ""));

            String infoType = info.optString("type", "");
            if (infoType.equalsIgnoreCase("movie")) type = "movies";
            else if (!infoType.isEmpty()) type = "series";

            String genresStr = extractStringArray(info.optJSONArray("genres"));
            if (!genresStr.isEmpty()) map.put("genres", genresStr);

            String directorStr = extractStringArray(info.optJSONArray("director"));
            if (!directorStr.isEmpty()) map.put("director", directorStr);

            String castStr = extractStringArray(info.optJSONArray("cast"));
            if (!castStr.isEmpty()) map.put("cast", castStr);
        } else {
            map.put("id", "");
            map.put("image", "");
        }

        map.put("type", type);
        return map;
    }

    private static String resolveImage(String path) {
        if (path == null || path.isEmpty()) return "";
        if (path.startsWith("http")) return path;
        if (path.startsWith("/")) return IMAGE_BASE + path;
        return IMAGE_BASE + "/" + path;
    }

    private static String extractPostId(String detailUrl) {
        Matcher m = Pattern.compile("/(\\d+)/[^/]+/?$").matcher(detailUrl);
        if (m.find()) return m.group(1);
        m = Pattern.compile("/(?:peliculas-online|series-online|ver-el-episodio)/(\\d+)/").matcher(detailUrl);
        if (m.find()) return m.group(1);
        return null;
    }

    // ─── HTTP GET (idéntico a ServerCuevana.java) ────────────────────────

    private static String fetchHtml(String urlString) throws Exception {
        HttpURLConnection conn = createConnection(urlString);
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        try {
            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) return "";
            byte[] data = readStream(stream);
            String body = decompress(data, conn.getContentEncoding());
            return body != null ? body : "";
        } catch (Exception e) {
            return "";
        } finally {
            conn.disconnect();
        }
    }

    private static String fetchJson(String urlString) throws Exception {
        HttpURLConnection conn = createConnection(urlString);
        try {
            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) return null;
            byte[] data = readStream(stream);
            String body = decompress(data, conn.getContentEncoding());

            String contentType = conn.getContentType();
            boolean looksJson = contentType != null && contentType.contains("json");
            if (code != 200 || (!looksJson && body != null && body.trim().startsWith("<"))) {
                return null;
            }
            return body;
        } catch (Exception e) {
            return null;
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection createConnection(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8");
        conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
        conn.setRequestProperty("Referer", BASE_URL + "/");
        conn.setRequestProperty("Origin", BASE_URL);
        conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        conn.setRequestProperty("Sec-Fetch-Mode", "cors");
        conn.setRequestProperty("Sec-Fetch-Site", "same-origin");
        conn.setRequestProperty("Connection", "keep-alive");
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setInstanceFollowRedirects(true);
        return conn;
    }

    private static byte[] readStream(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        is.close();
        return baos.toByteArray();
    }

    private static String decompress(byte[] data, String contentEncoding) throws Exception {
        if (data == null || data.length == 0) return "";

        boolean isGzip = "gzip".equalsIgnoreCase(contentEncoding)
                || (data.length > 2 && data[0] == 0x1F && data[1] == (byte) 0x8B);
        boolean isDeflate = !isGzip && ("deflate".equalsIgnoreCase(contentEncoding)
                || (data.length > 2 && data[0] == 0x78));

        if (isGzip) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            GZIPInputStream gzis = new GZIPInputStream(new java.io.ByteArrayInputStream(data));
            byte[] buf = new byte[8192];
            int n;
            while ((n = gzis.read(buf)) != -1) baos.write(buf, 0, n);
            gzis.close();
            return new String(baos.toByteArray(), "UTF-8");
        } else if (isDeflate) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                InflaterInputStream iis = new InflaterInputStream(new java.io.ByteArrayInputStream(data));
                byte[] buf = new byte[8192];
                int n;
                while ((n = iis.read(buf)) != -1) baos.write(buf, 0, n);
                iis.close();
                return new String(baos.toByteArray(), "UTF-8");
            } catch (Exception e) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                InflaterInputStream iis = new InflaterInputStream(
                        new java.io.ByteArrayInputStream(data), new Inflater(true));
                byte[] buf = new byte[8192];
                int n;
                while ((n = iis.read(buf)) != -1) baos.write(buf, 0, n);
                iis.close();
                return new String(baos.toByteArray(), "UTF-8");
            }
        }
        return new String(data, "UTF-8");
    }
}
