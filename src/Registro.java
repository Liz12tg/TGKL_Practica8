import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Registro {
    private static final long VIGENCIA_MS = Long.getLong("vigencia", 6L) * 1000;
    private static final List<String> TIPOS = List.of("inventario", "caja", "compras");

    private static class Entrada {
        final String nombre, tipo, host;
        final int puerto;
        volatile long visto = System.currentTimeMillis();

        Entrada(String nombre, String tipo, String host, int puerto) {
            this.nombre = nombre;
            this.tipo = tipo;
            this.host = host;
            this.puerto = puerto;
        }
    }

    private final Map<String, Entrada> servicios = new ConcurrentHashMap<>();

    private void registrar(String nombre, String tipo, String host, int puerto) {
        String clave = tipo + "/" + nombre;
        Entrada previa = servicios.get(clave);
        if (previa != null && previa.host.equals(host) && previa.puerto == puerto) {
            previa.visto = System.currentTimeMillis();
            return;
        }
        servicios.put(clave, new Entrada(nombre, tipo, host, puerto));
        System.out.println("[Registro] + " + tipo + " '" + nombre + "' en " + host + ":" + puerto);
    }

    private String listar() {
        long ahora = System.currentTimeMillis();
        StringBuilder b = new StringBuilder("{\"servicios\":[");
        List<Entrada> vivos = new ArrayList<>();
        for (Map.Entry<String, Entrada> e : servicios.entrySet()) {
            if (ahora - e.getValue().visto > VIGENCIA_MS) {
                if (servicios.remove(e.getKey(), e.getValue())) {
                    System.out.println("[Registro] - " + e.getValue().tipo + " '" + e.getValue().nombre + "' dejó de avisar: se quita del directorio");
                }
            } else {
                vivos.add(e.getValue());
            }
        }
        vivos.sort((x, y) -> (x.tipo + x.nombre).compareTo(y.tipo + y.nombre));
        for (int i = 0; i < vivos.size(); i++) {
            Entrada e = vivos.get(i);
            if (i > 0) { b.append(','); }
            b.append("{\"nombre\":").append(Web.esc(e.nombre)).append(",\"tipo\":").append(Web.esc(e.tipo))
                    .append(",\"host\":").append(Web.esc(e.host)).append(",\"puerto\":").append(e.puerto).append('}');
        }
        return b.append("]}").toString();
    }

    public static void main(String[] args) throws IOException {
        int puerto = args.length > 0 ? Integer.parseInt(args[0]) : 5100;
        Registro r = new Registro();

        HttpServer http = HttpServer.create(new InetSocketAddress(puerto), 0);
        http.setExecutor(Web.poolAcotado());
        Web.ruta(http, "/registrar", "POST", (ex, p) -> {
            String nombre = Web.limpiar(p.get("nombre"));
            String tipo = p.get("tipo");
            String host = Web.limpiar(p.get("host"));
            int pto = Integer.parseInt(p.getOrDefault("puerto", "0"));
            if (nombre.isEmpty() || host.isEmpty() || tipo == null || !TIPOS.contains(tipo) || pto < 1 || pto > 65535
                    || !host.matches("[A-Za-z0-9.-]{1,100}")) {
                Web.responder(ex, 400, "{\"error\":\"Faltan o son invalidos: nombre, tipo (inventario|caja|compras), host, puerto\"}");
                return;
            }
            r.registrar(nombre, tipo, host, pto);
            Web.responder(ex, 200, "{\"estado\":\"ok\"}");
        });
        Web.ruta(http, "/servicios", "GET", (ex, p) -> Web.responder(ex, 200, r.listar()));
        http.start();

        System.out.println();
        System.out.println("[Registro] Directorio de servicios en http://localhost:" + puerto);
        System.out.println("[Registro] Un servicio que no avisa en " + (VIGENCIA_MS / 1000) + " s se quita solo.");
        System.out.println("[Registro] Autenticación: Bearer token" + (Web.tkn.equals(Web.tkn_pre) ? " (token por defecto: cámbielo con -Dtkn=...)" : "") + "\n");
    }
}
