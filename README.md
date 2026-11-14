# Práctica 8: Microservicios (boletería)

La boletería de la práctica 7 ahora está dividida en **microservicios**: programas pequeños, cada uno con su propio puerto, que se encuentran entre sí mediante un **Registro**. La app del navegador funciona igual: abre <http://localhost:8080>.

| Servicio | Puerto | Qué hace |
|----------|--------|----------|
| `Registro` | 5100 | Directorio de servicios vivos |
| `Sistema` | 5000 | Inventario de boletos |
| `Caja` (C1, C2) | 5001, 5002 | Atiende clientes (con límite simultáneo) |
| `Compras` | 5200 | Procesa compras, folios y bitácora |
| `Interfaz` | 8080 | Puerta de entrada: sirve la app y la API |

**Requisito:** JDK 17 o superior. Usa **Chrome o Edge**.

---

## Cómo ejecutarla

Desde la carpeta `TGKL_Practica8`:

**Windows**
```bat
iniciar.bat
```

**Linux / macOS**
```bash
./iniciar.sh
```

Esto levanta los seis servicios en un solo proceso y abre <http://localhost:8080>.

### Variantes

| Comando | Qué hace |
|---------|----------|
| `iniciar.bat demo` | 6 boletos y cajas de 1 cliente (la fila se ve rápido) |
| `iniciar.bat separado` | Cada servicio en su propia ventana (útil para probar fallos) |

### Con Docker

```bash
docker compose up --build     # abre http://localhost:8080
```

Crea un archivo `.env` junto a `docker-compose.yml` con `TKN=un-secreto-largo` (opcional: `BOLETOS`, `CUPO`).

### Manualmente (una terminal por servicio)

Ejecuta cada comando en su propia terminal, desde esta carpeta, en este orden. El `-Dtkn=secreto` debe ser igual en todos.

```
java -Dtkn=secreto -cp out Registro 5100
java -Dtkn=secreto -cp out Sistema 20 5000 localhost:5100
java -Dtkn=secreto -cp out Caja 2 5001 C1 localhost:5100
java -Dtkn=secreto -cp out Caja 2 5002 C2 localhost:5100
java -Dtkn=secreto -cp out Compras 5200 localhost:5100
java -Dtkn=secreto -cp out Interfaz 8080 localhost:5100
```

> Si algo no carga, revisa que estés ejecutando desde la carpeta de la práctica (necesita encontrar la carpeta `web/`).

---

## Pruebas

### 1. Tolerancia a fallos

1. Ejecuta `iniciar.bat separado` y abre la app → pestaña **Cajas**. Todo debe aparecer *En línea*.
2. **Cierra la ventana de Caja C2.** En unos 6 segundos desaparece de la lista y las compras nuevas usan solo C1.
3. **Cierra la ventana de Compras.** Al consultar `GET /api/v1/compras` responde `503` con `Retry-After: 5`. El cartel de boletos sigue funcionando y la app guarda tus compras en cola.
4. **Vuelve a abrir Compras** con el comando de su ventana. Se reincorpora sola y la cola se envía (una sola vez).

Con Docker:
```bash
docker compose stop caja2     # cierra una caja
docker compose start caja2    # vuelve a anunciarse sola
```

### 2. Modo sin conexión

1. Abre la app y navega un poco (así se guarda la copia).
2. Cierra la ventana de **Interfaz** (o en DevTools → Network → *Offline*).
3. Recarga la página: abre desde caché y aparece el aviso amarillo **Datos guardados**.
4. Haz una compra: queda en **Mis boletos → En cola**.
5. Al volver el servidor, la compra se envía sola, **una sola vez**.

> En DevTools → **Application** puedes ver *Manifest*, *Service Workers* y *Cache Storage*.

### 3. Instalarla como app (opcional)

- **Chrome/Edge:** icono de instalar en la barra de direcciones, o el botón **Instalar** de la app.
- **iOS (Safari):** Compartir → *Añadir a pantalla de inicio*.

---

## Limitaciones

- Las compras viven en la memoria de **Compras**: si ese servicio se reinicia, se pierden.
- No hay notificaciones push reales.
