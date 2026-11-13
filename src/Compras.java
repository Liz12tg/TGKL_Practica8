import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class Compras {
    private static final int COLORES = 8;
    private static final int MAX_ACTIVOS = 30;
    private static final int MAX_BOLETOS = Integer.getInteger("limite", 8);
    private static final int MEMORIA_LLAVES = 1000;
    private static final String API = "/api/v1";
    private static final Pattern LLAVE = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final long INSTANCIA = System.currentTimeMillis();

    private static class Compra {
        final int id, pedidos, color;
        final String nom;
        final long creada = System.currentTimeMillis();
        final List<Integer> folios = new CopyOnWriteArrayList<>();
        volatile int comprados, rlj;
        volatile String estado = "buscando", caja = "", msj = "Llegó a la taquilla.";
        volatile boolean activa = true;

        Compra(int id, String nom, int pedidos) {
            this.id = id;
            this.nom = nom;
            this.pedidos = pedidos;
            this.color = (id - 1) % COLORES;
        }

        String resultado() {
            if (activa) { return null; }
            return comprados >= pedidos ? "completa" : comprados > 0 ? "parcial" : "ninguno";
        }
    }

    private static class Evento {
        final int compra, rlj;
        final String nom, tipo, caja, txt;

        Evento(int compra, String nom, String tipo, String caja, int rlj, String txt) {
            this.compra = compra;
            this.nom = nom;
            this.tipo = tipo;
            this.caja = caja;
            this.rlj = rlj;
            this.txt = txt;
        }
    }

    private final Web.Directorio directorio;
    private final List<Evento> eventos = new ArrayList<>();
    private final List<Compra> compras = new CopyOnWriteArrayList<>();
    private final AtomicInteger contador = new AtomicInteger(0);
    private final AtomicInteger folio = new AtomicInteger(0);
    private volatile boolean folioIniciado = false;
    private final Map<String, Integer> llaves = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> e) { return size() > MEMORIA_LLAVES; }
    };

    Compras(Web.Directorio directorio) { this.directorio = directorio; }

    private synchronized void registrar(Compra c, String tipo, String caja, int rlj, int faltan, String txt) {
        c.rlj = rlj;
        c.comprados = c.pedidos - faltan;
        switch (tipo) {
            case "vendido":
                c.folios.add(folio.incrementAndGet());
                c.estado = "en_caja";
                c.caja = caja;
                break;
            case "conectado":
            case "agotado":
                c.estado = "en_caja";
                c.caja = caja;
                break;
            case "fila":
                c.estado = "en_fila";
                c.caja = "";
                break;
            case "intento":
            case "llena":
            case "reintento":
            case "rechazo":
            case "error":
                c.estado = "buscando";
                break;
            case "fin":
                c.estado = "terminada";
                c.activa = false;
                c.caja = "";
                break;
            default:
                break;
        }
        if (txt != null) { c.msj = txt; }
        eventos.add(new Evento(c.id, c.nom, tipo, caja, rlj, txt));
    }

    private static String error(String msj, String campo) {
        return "{\"error\":" + Web.esc(msj) + (campo == null ? "" : ",\"campo\":" + Web.esc(campo)) + "}";
    }

    private static String jsonCompra(Compra c) {
        StringBuilder b = new StringBuilder("{\"id\":").append(c.id)
                .append(",\"nombre\":").append(Web.esc(c.nom))
                .append(",\"boletos\":").append(c.pedidos)
                .append(",\"comprados\":").append(c.comprados)
                .append(",\"estado\":").append(Web.esc(c.estado))
                .append(",\"resultado\":").append(c.resultado() == null ? "null" : Web.esc(c.resultado()))
                .append(",\"caja\":").append(Web.esc(c.caja))
                .append(",\"mensaje\":").append(Web.esc(c.msj))
                .append(",\"reloj\":").append(c.rlj)
                .append(",\"color\":").append(c.color)
                .append(",\"creada\":").append(c.creada)
                .append(",\"folios\":[");
        List<Integer> f = c.folios;
        for (int i = 0; i < f.size(); i++) {
            if (i > 0) { b.append(','); }
            b.append(f.get(i));
        }
        return b.append("],\"enlaces\":{\"self\":\"").append(API).append("/compras/").append(c.id).append("\"}}").toString();
    }

    private String jsonCompras(Set<Integer> ids, boolean soloActivas, int limite) {
        List<Compra> elegidas = new ArrayList<>();
        for (Compra c : compras) {
            if (ids != null && !ids.contains(c.id)) { continue; }
            if (soloActivas && !c.activa) { continue; }
            elegidas.add(c);
        }
        int desde = Math.max(0, elegidas.size() - limite);
        StringBuilder b = new StringBuilder("{\"total\":" + elegidas.size() + ",\"compras\":[");
        for (int i = desde; i < elegidas.size(); i++) {
            if (i > desde) { b.append(','); }
            b.append(jsonCompra(elegidas.get(i)));
        }
        return b.append("]}").toString();
    }

    private synchronized String jsonEventos(int desde) {
        boolean reinicio = desde > eventos.size();
        if (reinicio || desde < 0) { desde = 0; }
        StringBuilder b = new StringBuilder("{\"reinicio\":").append(reinicio).append(",\"eventos\":[");
        for (int i = desde; i < eventos.size(); i++) {
            Evento e = eventos.get(i);
            if (e.txt == null) { continue; }
            if (b.charAt(b.length() - 1) != '[') { b.append(','); }
            b.append("{\"compra\":").append(e.compra)
                    .append(",\"nombre\":").append(Web.esc(e.nom))
                    .append(",\"tipo\":").append(Web.esc(e.tipo))
                    .append(",\"caja\":").append(Web.esc(e.caja))
                    .append(",\"reloj\":").append(e.rlj)
                    .append(",\"texto\":").append(Web.esc(e.txt)).append('}');
        }
        return b.append("],\"siguiente\":").append(eventos.size()).append('}').toString();
    }

    private String estado() {
        long activas = compras.stream().filter(c -> c.activa).count();
        return "{\"instancia\":" + INSTANCIA + ",\"compras\":" + compras.size() + ",\"activas\":" + activas + "}";
    }

    private void listar(HttpExchange ex, Map<String, String> q) throws IOException {
        Set<Integer> ids = null;
        if (q.containsKey("ids")) {
            ids = new HashSet<>();
            for (String t : q.get("ids").split(",")) {
                try {
                    ids.add(Integer.parseInt(t.trim()));
                } catch (NumberFormatException e) {
                    Web.responder(ex, 400, error("ids debe ser una lista de números separados por coma", "ids"));
                    return;
                }
            }
        }
        int limite = 100;
        if (q.containsKey("limite")) {
            try {
                limite = Math.max(1, Math.min(500, Integer.parseInt(q.get("limite"))));
            } catch (NumberFormatException e) {
                Web.responder(ex, 400, error("limite debe ser un número entre 1 y 500", "limite"));
                return;
            }
        }
        Web.responder(ex, 200, jsonCompras(ids, "true".equals(q.get("activas")), limite));
    }

    private void crear(HttpExchange ex) throws IOException {
        Map<String, String> cuerpo = Web.leerJson(new String(ex.getRequestBody().readNBytes(4096), java.nio.charset.StandardCharsets.UTF_8));
        if (cuerpo == null) {
            Web.responder(ex, 400, error("El cuerpo no es un objeto JSON válido", null));
            return;
        }
        String nombre = Web.limpiar(cuerpo.get("nombre"));
        if (nombre.isEmpty()) {
            Web.responder(ex, 400, error("Escribe tu nombre", "nombre"));
            return;
        }
        int boletos;
        try {
            boletos = Integer.parseInt(cuerpo.getOrDefault("boletos", ""));
        } catch (NumberFormatException e) {
            boletos = 0;
        }
        if (boletos < 1 || boletos > MAX_BOLETOS) {
            Web.responder(ex, 400, error("Puedes comprar de 1 a " + MAX_BOLETOS + " boletos", "boletos"));
            return;
        }
        String llave = ex.getRequestHeaders().getFirst("Idempotency-Key");
        if (llave != null && !LLAVE.matcher(llave).matches()) {
            Web.responder(ex, 400, error("Idempotency-Key debe tener de 8 a 64 caracteres (letras, números, - y _)", "Idempotency-Key"));
            return;
        }

        Compra nueva;
        synchronized (llaves) {
            if (llave != null && llaves.containsKey(llave)) {
                int id = llaves.get(llave);
                Compra previa = compras.stream().filter(x -> x.id == id).findFirst().orElse(null);
                if (previa != null) {
                    Web.responder(ex, 200, jsonCompra(previa), "Location", API + "/compras/" + id, "Idempotent-Replayed", "true");
                    return;
                }
            }
            long activas = compras.stream().filter(c -> c.activa).count();
            if (activas >= MAX_ACTIVOS) {
                Web.responder(ex, 429, error("Hay " + activas + " compras en curso. Intenta en unos segundos", null), "Retry-After", "5");
                return;
            }
            iniciarFolios();
            nueva = new Compra(contador.incrementAndGet(), nombre, boletos);
            compras.add(nueva);
            if (llave != null) { llaves.put(llave, nueva.id); }
        }

        List<Web.Nodo> orden = new ArrayList<>();
        for (Web.Servicio s : directorio.de("caja")) { orden.add(s.nodo); }
        final Compra c = nueva;
        final int pedidos = boletos;
        if (orden.isEmpty()) {
            registrar(c, "error", "", 0, pedidos, "No hay cajas abiertas en este momento.");
            registrar(c, "fin", "", 0, pedidos, "Terminó: no hay cajas abiertas.");
        } else {
            Collections.rotate(orden, -((nueva.id - 1) % orden.size()));
            Thread hilo = new Thread(() -> {
                try {
                    Cliente.comprar(nombre, pedidos, orden, (t, caja, reloj, faltan, texto) -> registrar(c, t, caja, reloj, faltan, texto));
                } catch (RuntimeException e) {
                    registrar(c, "error", "", c.rlj, pedidos - c.comprados, "Error inesperado: " + e.getMessage());
                    registrar(c, "fin", "", c.rlj, pedidos - c.comprados, "Terminó por un error.");
                }
            }, "cliente-" + nombre);
            hilo.setDaemon(true);
            hilo.start();
        }
        Web.responder(ex, 202, jsonCompra(c), "Location", API + "/compras/" + c.id);
    }

    private void iniciarFolios() {
        if (folioIniciado) { return; }
        try {
            Web.Nodo inv = directorio.uno("inventario");
            if (inv != null) {
                folio.compareAndSet(0, Integer.parseInt(Web.campo(Web.get(inv, "/estado", Duration.ofMillis(800)), "vendidos")));
                folioIniciado = true;
            }
        } catch (Exception e) {
        }
    }

    private void atender(HttpExchange ex) throws IOException {
        try {
            if (!Web.autenticado(ex)) {
                ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                Web.responder(ex, 401, "{\"error\":\"Token ausente o invalido\"}");
                return;
            }
            String r = ex.getRequestURI().getPath();
            String m = ex.getRequestMethod();
            if (r.equals("/compras")) {
                if (m.equals("GET")) { listar(ex, Web.parametros(ex)); }
                else if (m.equals("POST")) { crear(ex); }
                else { Web.responder(ex, 405, error("Método no permitido", null), "Allow", "GET, POST"); }
            } else if (r.matches("/compras/\\d{1,9}")) {
                if (!m.equals("GET")) { Web.responder(ex, 405, error("Método no permitido", null), "Allow", "GET"); return; }
                int id = Integer.parseInt(r.substring("/compras/".length()));
                Compra c = compras.stream().filter(x -> x.id == id).findFirst().orElse(null);
                if (c == null) { Web.responder(ex, 404, error("La compra no existe", null)); } else { Web.responder(ex, 200, jsonCompra(c)); }
            } else {
                Web.responder(ex, 404, error("Recurso no encontrado", null));
            }
        } finally {
            ex.close();
        }
    }

    public static void main(String[] args) throws IOException {
        int puerto = args.length > 0 ? Integer.parseInt(args[0]) : 5200;
        Web.Nodo registro = Web.Nodo.de(args.length > 1 ? args[1] : "localhost:5100");
        if (registro == null) {
            System.out.println("El Registro debe indicarse como host:puerto");
            return;
        }
        Compras servicio = new Compras(new Web.Directorio(registro));

        HttpServer http = HttpServer.create(new InetSocketAddress(puerto), 0);
        http.setExecutor(Web.poolAcotado());
        http.createContext("/compras", servicio::atender);
        Web.ruta(http, "/eventos", "GET", (ex, p) -> {
            int desde;
            try {
                desde = Integer.parseInt(p.getOrDefault("desde", "0"));
            } catch (NumberFormatException e) {
                desde = 0;
            }
            Web.responder(ex, 200, servicio.jsonEventos(desde));
        });
        Web.ruta(http, "/estado", "GET", (ex, p) -> Web.responder(ex, 200, servicio.estado()));
        http.start();
        Web.anunciar(registro, "compras", "compras", puerto);

        System.out.println();
        System.out.println("[Compras] Servicio de compras en http://localhost:" + puerto);
        System.out.println("[Compras] Busca las cajas en el Registro " + registro + " en cada compra");
        System.out.println("[Compras] Máximo de compras activas: " + MAX_ACTIVOS + ", boletos por compra: " + MAX_BOLETOS + "\n");
    }
}
