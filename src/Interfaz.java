import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class Interfaz {
    private static final int MAX_CUERPO = 2048;
    private static final String API = "/api/v1";
    private static final Pattern LLAVE = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final Pattern ESTATICO = Pattern.compile("/(app\\.css|app\\.js|sw\\.js|manifest\\.webmanifest|offline\\.html|favicon\\.svg)|/icons/[a-z0-9-]+\\.(png|svg)");

    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; " + "connect-src 'self'; manifest-src 'self'; worker-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private static class EstadoSistema {
        final boolean enLinea;
        final String evento;
        final int total, vendidos, disponibles, rlj;

        EstadoSistema(boolean enLinea, String evento, int total, int vendidos, int disponibles, int rlj) {
            this.enLinea = enLinea;
            this.evento = evento;
            this.total = total;
            this.vendidos = vendidos;
            this.disponibles = disponibles;
            this.rlj = rlj;
        }
    }

    private static class EstadoCaja {
        final boolean enLinea;
        final String id, direccion;
        final int ventas, maxC, sesiones, rlj;

        EstadoCaja(boolean enLinea, String id, String direccion, int ventas, int maxC, int sesiones, int rlj) {
            this.enLinea = enLinea;
            this.id = id;
            this.direccion = direccion;
            this.ventas = ventas;
            this.maxC = maxC;
            this.sesiones = sesiones;
            this.rlj = rlj;
        }
    }

    private static class EstadoServicio {
        final String nombre, tipo, direccion;
        final boolean enLinea;

        EstadoServicio(String nombre, String tipo, String direccion, boolean enLinea) {
            this.nombre = nombre;
            this.tipo = tipo;
            this.direccion = direccion;
            this.enLinea = enLinea;
        }
    }

    private final int prto;
    private final boolean publico = System.getenv("PORT") != null || Boolean.getBoolean("publico");
    private final Path web = Paths.get(System.getProperty("web", "web")).toAbsolutePath().normalize();
    private final Web.Directorio directorio;
    private final Web.Disyuntor discompras = new Web.Disyuntor();
    private volatile EstadoSistema estadoSistema = new EstadoSistema(false, "", 0, 0, 0, 0);
    private volatile List<EstadoCaja> estadoCajas = new ArrayList<>();
    private volatile List<EstadoServicio> estadoServicios = new ArrayList<>();
    private volatile boolean registroEnLinea = false;
    private volatile long instancia = System.currentTimeMillis();

    Interfaz(int prto, Web.Nodo registro) {
        this.prto = prto;
        this.directorio = new Web.Directorio(registro);
    }

    private void sondear() {
        List<Web.Servicio> todos;
        try {
            Web.get(directorio.registro(), "/servicios", Duration.ofMillis(800));
            registroEnLinea = true;
        } catch (Exception e) {
            registroEnLinea = false;
        }
        todos = directorio.todos();
        List<EstadoServicio> servicios = new ArrayList<>();
        servicios.add(new EstadoServicio("registro", "registro", directorio.registro().toString(), registroEnLinea));

        Web.Nodo inv = null;
        for (Web.Servicio s : todos) { if (s.tipo.equals("inventario")) { inv = s.nodo; break; } }
        if (inv == null) {
            EstadoSistema a = estadoSistema;
            estadoSistema = new EstadoSistema(false, a.evento, a.total, a.vendidos, a.disponibles, a.rlj);
            servicios.add(new EstadoServicio("inventario", "inventario", "—", false));
        } else {
            try {
                String j = Web.get(inv, "/estado", Duration.ofMillis(800));
                estadoSistema = new EstadoSistema(true, Web.campo(j, "evento"),
                        Integer.parseInt(Web.campo(j, "total")), Integer.parseInt(Web.campo(j, "vendidos")),
                        Integer.parseInt(Web.campo(j, "disponibles")), Integer.parseInt(Web.campo(j, "reloj")));
                servicios.add(new EstadoServicio("inventario", "inventario", inv.toString(), true));
            } catch (Exception e) {
                EstadoSistema a = estadoSistema;
                estadoSistema = new EstadoSistema(false, a.evento, a.total, a.vendidos, a.disponibles, a.rlj);
                servicios.add(new EstadoServicio("inventario", "inventario", inv.toString(), false));
            }
        }

        Web.Nodo comp = null;
        for (Web.Servicio s : todos) { if (s.tipo.equals("compras")) { comp = s.nodo; break; } }
        boolean comprasOk = false;
        if (comp != null) {
            try {
                instancia = Long.parseLong(Web.campo(Web.get(comp, "/estado", Duration.ofMillis(800)), "instancia"));
                comprasOk = true;
            } catch (Exception e) {
                comprasOk = false;
            }
        }
        servicios.add(new EstadoServicio("compras", "compras", comp == null ? "—" : comp.toString(), comprasOk));

        List<EstadoCaja> cajas = new ArrayList<>();
        for (Web.Servicio s : todos) {
            if (!s.tipo.equals("caja")) { continue; }
            EstadoCaja e;
            try {
                String j = Web.get(s.nodo, "/estado", Duration.ofMillis(800));
                e = new EstadoCaja(true, Web.campo(j, "idCaja"), s.nodo.toString(), Integer.parseInt(Web.campo(j, "ventas")),
                        Integer.parseInt(Web.campo(j, "maxClientes")), Integer.parseInt(Web.campo(j, "sesiones")),
                        Integer.parseInt(Web.campo(j, "reloj")));
            } catch (Exception ex) {
                e = new EstadoCaja(false, s.nombre, s.nodo.toString(), 0, 0, 0, 0);
            }
            cajas.add(e);
            servicios.add(new EstadoServicio(e.id, "caja", e.direccion, e.enLinea));
        }
        estadoCajas = cajas;
        estadoServicios = servicios;
    }

    private static String error(String msj, String campo) {
        return "{\"error\":" + Web.esc(msj) + (campo == null ? "" : ",\"campo\":" + Web.esc(campo)) + "}";
    }

    private String jsonEvento() {
        EstadoSistema s = estadoSistema;
        return "{\"nombre\":" + Web.esc(s.evento.isEmpty() ? "Concierto de ópera" : s.evento)
                + ",\"enLinea\":" + s.enLinea + ",\"total\":" + s.total + ",\"vendidos\":" + s.vendidos
                + ",\"disponibles\":" + s.disponibles + ",\"reloj\":" + s.rlj + ",\"instancia\":" + instancia + "}";
    }

    private String jsonCajas() {
        StringBuilder b = new StringBuilder("{\"cajas\":[");
        List<EstadoCaja> es = estadoCajas;
        for (int i = 0; i < es.size(); i++) {
            EstadoCaja e = es.get(i);
            if (i > 0) { b.append(','); }
            b.append("{\"id\":").append(Web.esc(e.id))
                    .append(",\"direccion\":").append(Web.esc(e.direccion))
                    .append(",\"enLinea\":").append(e.enLinea)
                    .append(",\"ventas\":").append(e.ventas)
                    .append(",\"maxClientes\":").append(e.maxC)
                    .append(",\"sesiones\":").append(e.sesiones)
                    .append(",\"reloj\":").append(e.rlj).append('}');
        }
        return b.append("]}").toString();
    }

    private String jsonSalud() {
        List<EstadoServicio> es = estadoServicios;
        boolean todos = !es.isEmpty();
        StringBuilder b = new StringBuilder("],\"circuitoCompras\":" + Web.esc(discompras.abierto() ? "abierto" : "cerrado") + "}");
        StringBuilder l = new StringBuilder();
        for (int i = 0; i < es.size(); i++) {
            EstadoServicio e = es.get(i);
            if (!e.enLinea) { todos = false; }
            if (i > 0) { l.append(','); }
            l.append("{\"nombre\":").append(Web.esc(e.nombre)).append(",\"tipo\":").append(Web.esc(e.tipo))
                    .append(",\"direccion\":").append(Web.esc(e.direccion)).append(",\"enLinea\":").append(e.enLinea).append('}');
        }
        return "{\"estado\":" + Web.esc(todos ? "ok" : "degradado") + ",\"servicios\":[" + l + b;
    }

    private static void cabeceras(HttpExchange ex, String tipo) {
        var h = ex.getResponseHeaders();
        h.set("Content-Type", tipo);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Content-Security-Policy", CSP);
    }

    private static void responder(HttpExchange ex, int codigo, String tipo, byte[] cuerpo, String... extra) throws IOException {
        cabeceras(ex, tipo);
        for (int i = 0; i + 1 < extra.length; i += 2) { ex.getResponseHeaders().set(extra[i], extra[i + 1]); }
        boolean sinCuerpo = codigo == 304 || ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(codigo, sinCuerpo ? -1 : cuerpo.length);
        if (!sinCuerpo) {
            try (OutputStream os = ex.getResponseBody()) { os.write(cuerpo); }
        }
    }

    private static void json(HttpExchange ex, int codigo, String cuerpo, String... extra) throws IOException {
        String[] todo = Arrays.copyOf(extra, extra.length + 2);
        todo[extra.length] = "Cache-Control";
        todo[extra.length + 1] = "no-store";
        responder(ex, codigo, "application/json; charset=UTF-8", cuerpo.getBytes(StandardCharsets.UTF_8), todo);
    }

    private static void jsonConEtag(HttpExchange ex, String cuerpo) throws IOException {
        String etag = "\"" + Integer.toHexString(cuerpo.hashCode()) + "\"";
        String previo = ex.getRequestHeaders().getFirst("If-None-Match");
        if (etag.equals(previo)) {
            responder(ex, 304, "application/json", new byte[0], "ETag", etag, "Cache-Control", "no-cache");
            return;
        }
        responder(ex, 200, "application/json; charset=UTF-8", cuerpo.getBytes(StandardCharsets.UTF_8),
                "ETag", etag, "Cache-Control", "no-cache");
    }

    private boolean hostValido(HttpExchange ex) {
        if (publico) { return true; }
        String host = ex.getRequestHeaders().getFirst("Host");
        return host != null && (host.equals("localhost:" + prto) || host.equals("127.0.0.1:" + prto));
    }

    private static boolean metodo(HttpExchange ex, String... permitidos) throws IOException {
        String m = ex.getRequestMethod();
        for (String p : permitidos) {
            if (p.equals(m) || (p.equals("GET") && m.equals("HEAD"))) { return true; }
        }
        json(ex, 405, error("Método no permitido", null), "Allow", String.join(", ", permitidos));
        return false;
    }

    private void estatico(HttpExchange ex, String ruta) throws IOException {
        if (!metodo(ex, "GET")) { return; }
        if (ruta.equals("/") || ruta.equals("/index.html")) { ruta = "/index.html"; }
        else if (!ESTATICO.matcher(ruta).matches()) {
            responder(ex, 404, "text/plain; charset=UTF-8", "No encontrado".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Path archivo = web.resolve(ruta.substring(1)).normalize();
        if (!archivo.startsWith(web) || !Files.isRegularFile(archivo)) {
            responder(ex, 404, "text/plain; charset=UTF-8", ("No se encontró " + ruta
                    + ". Ejecute la app desde la carpeta practica-08-microservicios.").getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] datos = Files.readAllBytes(archivo);
        String nombre = archivo.getFileName().toString();
        String tipo = nombre.endsWith(".html") ? "text/html; charset=UTF-8"
                : nombre.endsWith(".css") ? "text/css; charset=UTF-8"
                : nombre.endsWith(".js") ? "text/javascript; charset=UTF-8"
                : nombre.endsWith(".webmanifest") ? "application/manifest+json; charset=UTF-8"
                : nombre.endsWith(".svg") ? "image/svg+xml"
                : "image/png";
        String etag = "\"" + Integer.toHexString(Arrays.hashCode(datos)) + "\"";
        if (etag.equals(ex.getRequestHeaders().getFirst("If-None-Match"))) {
            responder(ex, 304, tipo, new byte[0], "ETag", etag, "Cache-Control", "no-cache");
            return;
        }
        if (nombre.equals("sw.js")) {
            responder(ex, 200, tipo, datos, "ETag", etag, "Cache-Control", "no-cache", "Service-Worker-Allowed", "/");
        } else {
            responder(ex, 200, tipo, datos, "ETag", etag, "Cache-Control", "no-cache");
        }
    }

    private void reenviar(HttpExchange ex, String metodo, String camino, String cuerpo, Map<String, String> cab) throws IOException {
        Web.Nodo destino = directorio.uno("compras");
        if (destino == null || !discompras.permite()) {
            json(ex, 503, error("El servicio de compras no está disponible. Intenta en unos segundos", null), "Retry-After", "5");
            return;
        }
        Web.Resp r;
        try {
            r = Web.llamar(metodo, destino, camino, cuerpo, cab, Duration.ofSeconds(5));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            discompras.fallo();
            json(ex, 503, error("El servicio de compras no responde. Intenta en unos segundos", null), "Retry-After", "5");
            return;
        }
        if (r.codigo >= 500) { discompras.fallo(); } else { discompras.exito(); }
        List<String> extra = new ArrayList<>();
        for (String h : new String[] { "Location", "Retry-After", "Idempotent-Replayed", "Allow" }) {
            String v = r.cabecera(h);
            if (v != null) { extra.add(h); extra.add(v); }
        }
        json(ex, r.codigo, r.cuerpo, extra.toArray(new String[0]));
    }

    private void api(HttpExchange ex, String ruta) throws IOException {
        String r = ruta.substring(API.length());
        if (r.endsWith("/") && r.length() > 1) { r = r.substring(0, r.length() - 1); }
        String consulta = ex.getRequestURI().getRawQuery();
        String sufijo = consulta == null ? "" : "?" + consulta;

        if (r.equals("/salud")) {
            if (metodo(ex, "GET")) { json(ex, 200, jsonSalud()); }
        } else if (r.equals("/evento")) {
            if (metodo(ex, "GET")) { jsonConEtag(ex, jsonEvento()); }
        } else if (r.equals("/cajas")) {
            if (metodo(ex, "GET")) { jsonConEtag(ex, jsonCajas()); }
        } else if (r.equals("/eventos")) {
            if (metodo(ex, "GET")) { reenviar(ex, "GET", "/eventos" + sufijo, null, null); }
        } else if (r.equals("/compras")) {
            if (!metodo(ex, "GET", "POST")) { return; }
            if (ex.getRequestMethod().equals("POST")) { crearCompra(ex); } else { reenviar(ex, "GET", "/compras" + sufijo, null, null); }
        } else if (r.matches("/compras/\\d{1,9}")) {
            if (metodo(ex, "GET")) { reenviar(ex, "GET", r, null, null); }
        } else {
            json(ex, 404, error("Recurso no encontrado", null));
        }
    }

    private void crearCompra(HttpExchange ex) throws IOException {
        if (ex.getRequestHeaders().getFirst("X-Requested-With") == null) {
            json(ex, 403, error("Falta el encabezado X-Requested-With", null));
            return;
        }
        String tipo = ex.getRequestHeaders().getFirst("Content-Type");
        if (tipo == null || !tipo.toLowerCase().startsWith("application/json")) {
            json(ex, 415, error("Use Content-Type: application/json", null));
            return;
        }
        byte[] crudo;
        try (InputStream in = ex.getRequestBody()) { crudo = in.readNBytes(MAX_CUERPO + 1); }
        if (crudo.length > MAX_CUERPO) {
            json(ex, 413, error("El cuerpo es demasiado grande", null));
            return;
        }
        String texto = new String(crudo, StandardCharsets.UTF_8);
        if (Web.leerJson(texto) == null) {
            json(ex, 400, error("El cuerpo no es un objeto JSON válido", null));
            return;
        }
        String llave = ex.getRequestHeaders().getFirst("Idempotency-Key");
        if (llave != null && !LLAVE.matcher(llave).matches()) {
            json(ex, 400, error("Idempotency-Key debe tener de 8 a 64 caracteres (letras, números, - y _)", "Idempotency-Key"));
            return;
        }
        Map<String, String> cab = new HashMap<>();
        cab.put("Content-Type", "application/json");
        if (llave != null) { cab.put("Idempotency-Key", llave); }
        reenviar(ex, "POST", "/compras", texto, cab);
    }

    private void atender(HttpExchange ex) throws IOException {
        try {
            if (!hostValido(ex)) {
                responder(ex, 403, "text/plain; charset=UTF-8", "Host no permitido".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String ruta = ex.getRequestURI().getPath();
            if (ruta.equals(API) || ruta.startsWith(API + "/")) { api(ex, ruta); } else { estatico(ex, ruta); }
        } catch (RuntimeException e) {
            System.out.println("[Interfaz] Error atendiendo " + ex.getRequestURI() + ": " + e);
            try {
                json(ex, 500, error("Error interno", null));
            } catch (IOException | RuntimeException ignorado) {}
        } finally {
            ex.close();
        }
    }

    void iniciar() throws IOException {
        HttpServer servidor = HttpServer.create(new InetSocketAddress(publico ? InetAddress.getByName("0.0.0.0") : InetAddress.getLoopbackAddress(), prto), 0);
        servidor.setExecutor(Executors.newFixedThreadPool(8));
        servidor.createContext("/", this::atender);

        ScheduledExecutorService sondeo = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sondeo");
            t.setDaemon(true);
            return t;
        });
        sondeo.scheduleWithFixedDelay(() -> {
            try {
                sondear();
            } catch (Throwable t) {
                System.out.println("[Interfaz] Error al consultar los servicios: " + t);
            }
        }, 0, 500, TimeUnit.MILLISECONDS);

        servidor.start();
        System.out.println();
        System.out.println("[Interfaz] Gateway disponible en http://localhost:" + prto + "  (ábrela en Chrome o Edge para instalar la app)");
        System.out.println("[Interfaz] API REST en http://localhost:" + prto + API);
        System.out.println("[Interfaz] Registro de servicios: " + directorio.registro());
        System.out.println("[Interfaz] Archivos de la app: " + web);
        System.out.println(publico
                ? "[Interfaz] Modo público: acepta conexiones de la red (el token interno no sale del servidor)."
                : "[Interfaz] Solo acepta conexiones desde este equipo. El token no sale de este proceso.");
        System.out.println();
    }

    private static void todoEnUno() throws IOException {
        if (System.getProperty("tkn") == null) { System.setProperty("tkn", java.util.UUID.randomUUID().toString()); }
        String pe = System.getenv("PORT");
        int puerto = pe != null ? Integer.parseInt(pe) : 8080;
        String boletos = System.getenv("BOLETOS") != null ? System.getenv("BOLETOS") : "20";
        String cupo = System.getenv("CUPO") != null ? System.getenv("CUPO") : "2";
        Registro.main(new String[] { "5100" });
        Sistema.main(new String[] { boletos, "5000", "localhost:5100" });
        Caja.main(new String[] { cupo, "5001", "C1", "localhost:5100" });
        Caja.main(new String[] { cupo, "5002", "C2", "localhost:5100" });
        Compras.main(new String[] { "5200", "localhost:5100" });
        new Interfaz(puerto, new Web.Nodo("localhost", 5100)).iniciar();
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) { todoEnUno(); return; }
        int puerto = Integer.parseInt(args[0]);
        Web.Nodo registro = Web.Nodo.de(args.length > 1 ? args[1] : "localhost:5100");
        if (registro == null) {
            System.out.println("El Registro debe indicarse como host:puerto");
            return;
        }
        new Interfaz(puerto, registro).iniciar();
    }
}
