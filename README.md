# Práctica 8: Microservicios (de la PWA de la práctica 7 a servicios independientes)

La boletería de la práctica 7 —la misma app instalable, la misma API REST, el mismo manifest y service worker, el mismo diseño azul—
ahora está partida en **microservicios**: programas pequeños, cada uno con **una sola responsabilidad**, su **propio puerto**,
sus **propios datos** y que se encuentran entre sí por un **registro de servicios**. La app del navegador no cambia: sigue hablando con
la misma API (`/api/v1`), solo que ahora detrás de esa puerta hay varios servicios.

```
                                  ┌───────────────┐
        Navegador / app ─ HTTP ─> │ Interfaz      │  gateway: PWA + API REST v1, disyuntor, token
        (manifest + SW)           │ :8080         │
                                  └──┬──────┬─────┘
                       descubre      │      │ reenvía /compras, /eventos
                    ┌────────────────▼┐   ┌─▼────────────┐        ┌─────────────┐
                    │ Registro :5100  │   │ Compras :5200│─ elige ─> Caja C1 :5001 ─┐
                    │ (directorio)    │   │ compras,     │  cajas   Caja C2 :5002 ─┤ venden
                    └──▲──▲──▲────────┘   │ folios,      │  vivas                   ▼
              latidos ─┘  │  └─ latidos   │ bitácora     │                 Sistema (inventario) :5000
                          └ cada servicio └──────────────┘                 vende de forma atómica
                            se anuncia
```

| Servicio | Responsabilidad | Datos propios |
|----------|-----------------|---------------|
| `Registro` | Directorio: cada servicio avisa "aquí estoy" cada 2 s; si deja de avisar 6 s, se quita | Lista de servicios vivos |
| `Sistema` | **Inventario** del evento; vende de forma atómica (nunca de más) | Boletos y peticiones atendidas |
| `Caja` (varias) | Semáforo de clientes simultáneos, sesiones, lease, relojes de Lamport | Sesiones de la caja |
| `Compras` | Recibe pedidos, lanza un `Cliente` por compra, asigna folios, idempotencia y bitácora | Compras, folios, eventos |
| `Interfaz` | **API Gateway / BFF**: sirve la PWA, valida la petición (CSRF, tipo, tamaño), agrega estados y reenvía | Nada (solo copias en memoria) |
| `Cliente`, `Web` | Biblioteca compartida (lógica del cliente, HTTP, JSON, registro, disyuntor) | — |

Solo usa el JDK 17+.

## Qué cambió respecto a la práctica 7

| Práctica 7 (un monolito con partes) | Práctica 8 (microservicios) |
|---|---|
| `Interfaz` guardaba las compras, los folios y la bitácora | Eso lo hace **Compras**; `Interfaz` solo es la puerta |
| Las cajas y el sistema se pasaban como argumentos `host:puerto` | Se **descubren** en el **Registro**: abrir otra caja la hace aparecer sola, y una caja caída desaparece |
| Si algo fallaba, la `Interfaz` dependía de todo | **Disyuntor**: si Compras falla 3 veces seguidas, el gateway responde `503` + `Retry-After` al instante y reintenta a los 5 s |
| `/salud` decía solo `ok` | `/salud` lista cada servicio; la app lo muestra en **Cajas → Servicios** |
| Un contenedor | Un contenedor **por servicio** con `docker compose` (el modo de un solo proceso sigue existiendo) |

Decisiones de diseño: el gateway valida lo que es "de la puerta" (CSRF, `Content-Type`, tamaño, JSON, formato de la llave) y **Compras**
valida las reglas del negocio (nombre, de 1 a 8 boletos, máximo de compras activas); así Compras no confía en que nadie la valide antes.
Cada servicio exige el **token interno** y los puertos internos no se publican en Docker.

## Inicio rápido (Windows)

```bat
cd practica-08-microservicios
iniciar.bat
```

Un solo proceso levanta los seis servicios (cada uno en su puerto y hablando por HTTP con los demás) y abre <http://localhost:8080>
(usa **Chrome o Edge**). Linux/macOS: `./iniciar.sh`. Variantes:

- `iniciar.bat demo` — 6 boletos y cajas de 1 cliente (la fila se ve rápido).
- `iniciar.bat separado` — **cada servicio en su propia ventana**. Cierra la de una caja o la de *Compras* y mira cómo reacciona la app (**Cajas → Servicios**); vuelve a abrirla con el comando de esa ventana y se reincorpora sola.

Orden manual (cada uno en su terminal, desde esta carpeta, con `-Dtkn=secreto` igual en todos):

```
java -Dtkn=secreto -cp out Registro 5100
java -Dtkn=secreto -cp out Sistema 20 5000 localhost:5100
java -Dtkn=secreto -cp out Caja 2 5001 C1 localhost:5100
java -Dtkn=secreto -cp out Caja 2 5002 C2 localhost:5100
java -Dtkn=secreto -cp out Compras 5200 localhost:5100
java -Dtkn=secreto -cp out Interfaz 8080 localhost:5100
```

> Ejecuta desde esta carpeta (busca los archivos en `web/`; otra ruta: `-Dweb=ruta\a\web`).

## Probar la tolerancia a fallos

1. `iniciar.bat separado` y abre la app → pestaña **Cajas**: todos *En línea*.
2. Cierra la ventana de **Caja C2**: en ~6 s desaparece del directorio y las compras nuevas usan solo C1.
3. Cierra **Compras**: `GET /api/v1/compras` responde `503` con `Retry-After: 5`; el cartel de boletos sigue mostrándose (lo da el inventario) y la app guarda la compra en cola.
4. Vuelve a abrir Compras: el disyuntor se cierra solo y la cola se envía (una sola vez, por la `Idempotency-Key`).

## Desplegar en internet (con HTTPS)

**Opción A — un servicio (la más fácil, p. ej. Render):** el `Dockerfile` corre los seis servicios en un solo contenedor.
Sube el repo a GitHub → en [Render](https://render.com) *New → Web Service → Runtime: Docker*. El hosting define `PORT`, da el certificado HTTPS y el programa genera un
token interno aleatorio. Variables opcionales: `BOLETOS` (20) y `CUPO` (2).

**Opción B — microservicios de verdad (un contenedor por servicio):**

```
docker compose up --build        # http://localhost:8080
docker compose stop caja2        # cierra una caja: el resto sigue
docker compose start caja2       # vuelve a anunciarse sola
```

Define `TKN=un-secreto-largo` (y opcionalmente `BOLETOS`, `CUPO`) en un archivo `.env` junto al `docker-compose.yml`. Solo `interfaz` publica el puerto 8080.

## Instalarla como app

- **Chrome/Edge (PC y Android):** icono de instalar en la barra de direcciones, o el botón **Instalar** de la propia app.
- **iOS (Safari):** Compartir → *Añadir a pantalla de inicio* (la app muestra una pista).
- `localhost` cuenta como contexto seguro, por eso el service worker funciona sin HTTPS. En otra máquina se necesita HTTPS.

## Probar el modo sin conexión

1. Abre la app y navega un poco (se guarda la copia).
2. Cierra la ventana de **Interfaz** (o en DevTools → Network → *Offline*).
3. Recarga: la app abre desde caché, el indicador pasa a **Datos guardados** y aparece el aviso amarillo.
4. Compra: queda en **Mis boletos → En cola**. Al volver el servidor se envía sola, **una sola vez** (`Idempotency-Key`).

DevTools → **Application** muestra *Manifest*, *Service Workers* y *Cache Storage*.

## API REST v1 (`docs/openapi.json`)

| Método y ruta | Descripción | Respuestas |
|---|---|---|
| `GET /api/v1/salud` | Estado de cada microservicio (`ok` / `degradado`) y del disyuntor | 200 |
| `GET /api/v1/evento` | Boletos totales, vendidos, disponibles, `instancia` | 200, **304** (ETag) |
| `GET /api/v1/cajas` | Cajas y ocupación | 200, 304 |
| `GET /api/v1/eventos?desde=N` | Bitácora incremental | 200 |
| `GET /api/v1/compras?ids=&activas=&limite=` | Lista de compras | 200 |
| `POST /api/v1/compras` | Crea una compra `{nombre, boletos}` | **202** + `Location`, **200** si repite la llave, 400, 403, 413, 415, **429** + `Retry-After`, **503** si Compras no está disponible |
| `GET /api/v1/compras/{id}` | Estado de una compra | 200, 404, 405 (+`Allow`) |

Decisiones de diseño: la compra es **asíncrona** (202 y se consulta), `Idempotency-Key` evita cobrar dos veces al reintentar
(respuesta con `Idempotent-Replayed: true`), y los recursos tienen enlaces (`enlaces.self`).

## Web App Manifest (`web/manifest.webmanifest`)

| Campo | Valor / para qué sirve |
|---|---|
| `id`, `start_url`, `scope` | Identidad estable de la app; `/?origen=pwa` permite medir aperturas como app |
| `name`, `short_name`, `lang`, `dir`, `description` | Texto bajo el icono y en la instalación |
| `display: standalone` + `display_override` | Ventana sin barra del navegador (con alternativas) |
| `theme_color`, `background_color` | Color de la barra y de la pantalla de arranque |
| `icons` | 192, 512, *maskable* 512 y SVG |
| `shortcuts` | Atajos de icono: *Comprar boletos* y *Mis boletos* |
| `screenshots` | Capturas móvil y ancha para el diálogo de instalación |
| `launch_handler`, `categories`, `orientation` | Reutiliza la ventana abierta; clasificación; orientación |

## Service worker (`web/sw.js`)

| Recurso | Estrategia | Por qué |
|---|---|---|
| Navegación (HTML) | Red primero (3 s) → `/` en caché → `offline.html` | Siempre la versión nueva, pero abre sin red |
| CSS/JS/manifest | *Stale-while-revalidate* | Rápido y se actualiza solo |
| Iconos | Caché primero | No cambian |
| `GET /api/v1/*` | Red primero con respaldo en caché (marca `X-Desde-Cache`) | Datos frescos; si no hay red, últimos datos |
| `GET /api/v1/eventos` | Solo red | Es una bitácora en vivo |
| `POST` | No se intercepta | La cola offline la maneja la app |

Cachés versionadas (`boleteria-app-v3`, `boleteria-datos-v3`). Ciclo de actualización: si hay un SW nuevo, la app muestra
**"Hay una versión nueva" → Actualizar** (`ACTIVAR` → `skipWaiting` → recarga). También hay Background Sync (`enviar-cola`),
clic en notificaciones y *badge* con el número de boletos.

## Seguridad

- Solo el gateway es público. Los servicios se hablan con un **token interno** (`Authorization: Bearer`) que el navegador nunca ve; en local solo `127.0.0.1` y verificación de `Host`.
- CSP estricta (`default-src 'none'`, sin scripts ni estilos en línea), `X-Content-Type-Options`, `Referrer-Policy`, `frame-ancestors 'none'`.
- `X-Requested-With` obligatorio en POST (anti-CSRF) y `Content-Type: application/json`.
- Archivos estáticos solo por lista permitida (sin *path traversal*). Cuerpo máximo 2 KB, máximo de compras activas.

## Limitaciones

- La compra offline se encola en `localStorage` y se envía al volver la app/servidor; no es una bandeja IndexedDB completa.
- Las compras viven en la memoria del servicio **Compras**: si se reinicia se pierden y la app las marca como *sesión anterior*.
- No hay notificaciones push reales (requieren un servidor Web Push y HTTPS).
