import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Web {
    static final String tkn_pre = "boleteria-2026";
    static final String tkn = System.getProperty("tkn", tkn_pre);
    static final int NOMBRE_MAX = 40;
    static final int RELOJ_MAX = 1_000_000_000;
    private static final int HILOS = Integer.getInteger("hilos", 16);
    private static final int COLA = Integer.getInteger("cola", 64);

    private Web() { }

    static class Nodo {
        final String host;
        final int puerto;

        Nodo(String host, int puerto) {
            this.host = host;
            this.puerto = puerto;
        }

        static Nodo de(String texto) {
            String[] hp = texto.split(":");
            return hp.length == 2 ? new Nodo(hp[0], Integer.parseInt(hp[1])) : null;
        }

        @Override
        public String toString() { return host + ":" + puerto; }
    }

    static class ErrorHttp extends IOException {
        final int codigo;

        ErrorHttp(int codigo, String cuerpo) {
            super("HTTP " + codigo + " " + cuerpo);
            this.codigo = codigo;
        }
    }

    interface Accion { void ejecutar(HttpExchange ex, Map<String, String> p) throws IOException; }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private static String enviar(HttpRequest.Builder b, Nodo nodo, String camino, String sesion, Duration limite)
            throws IOException, InterruptedException {
        b.uri(URI.create("http://" + nodo.host + ":" + nodo.puerto + camino))
                .timeout(limite)
                .header("Authorization", "Bearer " + tkn);
        if (sesion != null) {
            b.header("X-Sesion", sesion);
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) {
            throw new ErrorHttp(r.statusCode(), r.body());
        }
        return r.body();
    }

    static String post(Nodo nodo, String camino, String sesion, Duration limite) throws IOException, InterruptedException {
        return enviar(HttpRequest.newBuilder().POST(HttpRequest.BodyPublishers.noBody()), nodo, camino, sesion, limite);
    }

    static String get(Nodo nodo, String camino, Duration limite) throws IOException, InterruptedException {
        return enviar(HttpRequest.newBuilder().GET(), nodo, camino, null, limite);
    }

    static String campo(String json, String nombre) {
        Matcher m = Pattern.compile("\"" + nombre + "\"\\s*:\\s*\"?([^\",}]*)\"?").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    static String enc(String texto) { return URLEncoder.encode(texto, StandardCharsets.UTF_8); }

    static Map<String, String> parametros(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q != null) {
            for (String par : q.split("&")) {
                String[] kv = par.split("=", 2);
                m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                      kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
        }
        return m;
    }

    static void responder(HttpExchange ex, int codigo, String json) throws IOException {
        byte[] cuerpo = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(codigo, cuerpo.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(cuerpo); }
    }

    static boolean autenticado(HttpExchange ex) {
        String recibido = ex.getRequestHeaders().getFirst("Authorization");
        if (recibido == null) { return false; }
        String esperado = "Bearer " + tkn;
        return MessageDigest.isEqual(recibido.getBytes(StandardCharsets.UTF_8), esperado.getBytes(StandardCharsets.UTF_8));
    }

    static String limpiar(String texto) {
        if (texto == null) { return ""; }
        String limpio = texto.replaceAll("\\p{Cntrl}", "").trim();
        return limpio.length() > NOMBRE_MAX ? limpio.substring(0, NOMBRE_MAX) : limpio;
    }

    static int leerReloj(String texto) {
        int valor = Integer.parseInt(texto);
        if (valor < 0 || valor > RELOJ_MAX) {
            throw new NumberFormatException("reloj fuera de rango");
        }
        return valor;
    }

    static void ruta(HttpServer http, String camino, String metodo, Accion accion) {
        http.createContext(camino, ex -> {
            try {
                if (!autenticado(ex)) {
                    ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                    responder(ex, 401, "{\"error\":\"Token ausente o invalido\"}");
                    return;
                }
                if (!ex.getRequestMethod().equalsIgnoreCase(metodo)) {
                    responder(ex, 405, "{\"error\":\"Use el metodo " + metodo + "\"}");
                    return;
                }
                accion.ejecutar(ex, parametros(ex));
            } catch (NumberFormatException e) {
                responder(ex, 400, "{\"error\":\"Parametro numerico invalido o fuera de rango\"}");
            } finally {
                ex.close();
            }
        });
    }

    static ThreadPoolExecutor poolAcotado() {
        return new ThreadPoolExecutor(HILOS, HILOS, 0L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(COLA), new ThreadPoolExecutor.AbortPolicy());
    }

    static String infoPool() { return "hilos: " + HILOS + ", cola: " + COLA; }

    // ------------------------------------------------------------------ JSON mínimo (compartido por los servicios)
    static String esc(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) { b.append(String.format("\\u%04x", (int) c)); } else { b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    static Map<String, String> leerJson(String txt) {
        Map<String, String> m = new HashMap<>();
        int i = 0, n = txt.length();
        while (i < n && Character.isWhitespace(txt.charAt(i))) { i++; }
        if (i >= n || txt.charAt(i++) != '{') { return null; }
        while (true) {
            while (i < n && Character.isWhitespace(txt.charAt(i))) { i++; }
            if (i < n && txt.charAt(i) == '}') { return m; }
            if (i >= n || txt.charAt(i) != '"') { return null; }
            int[] pos = {i};
            String clave = leerCadena(txt, pos);
            if (clave == null) { return null; }
            i = pos[0];
            while (i < n && Character.isWhitespace(txt.charAt(i))) { i++; }
            if (i >= n || txt.charAt(i++) != ':') { return null; }
            while (i < n && Character.isWhitespace(txt.charAt(i))) { i++; }
            if (i >= n) { return null; }
            String valor;
            if (txt.charAt(i) == '"') {
                pos[0] = i;
                valor = leerCadena(txt, pos);
                if (valor == null) { return null; }
                i = pos[0];
            } else {
                int ini = i;
                while (i < n && "-+.0123456789eE".indexOf(txt.charAt(i)) >= 0) { i++; }
                if (i == ini) { return null; }
                valor = txt.substring(ini, i);
            }
            m.put(clave, valor);
            while (i < n && Character.isWhitespace(txt.charAt(i))) { i++; }
            if (i < n && txt.charAt(i) == ',') { i++; continue; }
            if (i < n && txt.charAt(i) == '}') { return m; }
            return null;
        }
    }

    private static String leerCadena(String t, int[] pos) {
        StringBuilder b = new StringBuilder();
        int i = pos[0] + 1;
        while (i < t.length()) {
            char c = t.charAt(i++);
            if (c == '"') {
                pos[0] = i;
                return b.toString();
            }
            if (c == '\\') {
                if (i >= t.length()) { return null; }
                char e = t.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'u':
                        if (i + 4 > t.length()) { return null; }
                        try {
                            b.append((char) Integer.parseInt(t.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            return null;
                        }
                        i += 4;
                        break;
                    default: b.append(e);
                }
            } else {
                b.append(c);
            }
        }
        return null;
    }


    // ------------------------------------------------------------------ llamadas entre microservicios
    /** Dirección con la que un servicio se anuncia en el Registro (en Docker: el nombre del contenedor). */
    static final String ANUNCIO = System.getenv("HOST_ANUNCIO") != null ? System.getenv("HOST_ANUNCIO") : System.getProperty("host", "localhost");

    /** Respuesta HTTP completa (no lanza excepción por códigos 4xx/5xx: el gateway los reenvía tal cual). */
    static class Resp {
        final int codigo;
        final String cuerpo;
        final java.net.http.HttpHeaders cab;

        Resp(int codigo, String cuerpo, java.net.http.HttpHeaders cab) {
            this.codigo = codigo;
            this.cuerpo = cuerpo;
            this.cab = cab;
        }

        String cabecera(String nombre) { return cab.firstValue(nombre).orElse(null); }
    }

    static Resp llamar(String metodo, Nodo nodo, String camino, String cuerpo, Map<String, String> cabeceras, Duration limite)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://" + nodo.host + ":" + nodo.puerto + camino))
                .timeout(limite)
                .header("Authorization", "Bearer " + tkn)
                .method(metodo, cuerpo == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(cuerpo, StandardCharsets.UTF_8));
        if (cabeceras != null) { cabeceras.forEach(b::header); }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(r.statusCode(), r.body(), r.headers());
    }

    static class Servicio {
        final String nombre, tipo;
        final Nodo nodo;

        Servicio(String nombre, String tipo, Nodo nodo) {
            this.nombre = nombre;
            this.tipo = tipo;
            this.nodo = nodo;
        }
    }

    /** Un servicio avisa al Registro "aquí estoy" cada 2 s (latido). Si deja de avisar, el Registro lo olvida. */
    static void anunciar(Nodo registro, String nombre, String tipo, int puerto) {
        if (registro == null) { return; }
        String camino = "/registrar?nombre=" + enc(nombre) + "&tipo=" + enc(tipo) + "&host=" + enc(ANUNCIO) + "&puerto=" + puerto;
        ScheduledExecutorService latido = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "latido-" + nombre);
            t.setDaemon(true);
            return t;
        });
        latido.scheduleWithFixedDelay(() -> {
            try {
                post(registro, camino, null, Duration.ofSeconds(2));
            } catch (Exception e) {
                // el Registro puede estar apagado un momento: se reintenta en el siguiente latido
            }
        }, 0, 2, TimeUnit.SECONDS);
    }

    /** Pregunta al Registro quién ofrece un servicio. Guarda la última respuesta buena por si el Registro falla. */
    static class Directorio {
        private final Nodo registro;
        private volatile List<Servicio> ultimo = new ArrayList<>();
        private volatile long hora;

        Directorio(Nodo registro) { this.registro = registro; }

        Nodo registro() { return registro; }

        synchronized List<Servicio> todos() {
            if (System.currentTimeMillis() - hora < 400) { return ultimo; }
            try {
                String j = get(registro, "/servicios", Duration.ofMillis(800));
                List<Servicio> lista = new ArrayList<>();
                Matcher m = Pattern.compile("\\{[^{}]*\\}").matcher(j);
                while (m.find()) {
                    String o = m.group();
                    if (campo(o, "puerto").isEmpty()) { continue; }
                    lista.add(new Servicio(campo(o, "nombre"), campo(o, "tipo"),
                            new Nodo(campo(o, "host"), Integer.parseInt(campo(o, "puerto")))));
                }
                ultimo = lista;
            } catch (Exception e) {
                ultimo = new ArrayList<>();
            }
            hora = System.currentTimeMillis();
            return ultimo;
        }

        List<Servicio> de(String tipo) {
            List<Servicio> r = new ArrayList<>();
            for (Servicio s : todos()) { if (s.tipo.equals(tipo)) { r.add(s); } }
            r.sort((x, y) -> x.nombre.compareTo(y.nombre));
            return r;
        }

        Nodo uno(String tipo) {
            List<Servicio> r = de(tipo);
            return r.isEmpty() ? null : r.get(0).nodo;
        }
    }

    /** Disyuntor (circuit breaker): tras 3 fallos seguidos deja de llamar 5 s y responde rápido "no disponible". */
    static class Disyuntor {
        private int fallos;
        private long abiertoHasta;

        synchronized boolean permite() { return System.currentTimeMillis() >= abiertoHasta; }

        synchronized void exito() { fallos = 0; }

        synchronized void fallo() {
            if (++fallos >= 3) { abiertoHasta = System.currentTimeMillis() + 5000; fallos = 0; }
        }

        synchronized boolean abierto() { return System.currentTimeMillis() < abiertoHasta; }
    }

    /** Respuesta con cabeceras extra (Location, Retry-After...). */
    static void responder(HttpExchange ex, int codigo, String json, String... extra) throws IOException {
        for (int i = 0; i + 1 < extra.length; i += 2) { ex.getResponseHeaders().set(extra[i], extra[i + 1]); }
        responder(ex, codigo, json);
    }
}
