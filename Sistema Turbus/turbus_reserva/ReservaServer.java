package turbus_reserva;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import turbus_comun.modelos.Pasajero;
import turbus_comun.modelos.Reserva;
import turbus_comun.protocolo.Mensaje;
 
/**
 * Servidor TCP multi-hilo para el servicio de reservas de Turbus.
 *
 * Criterios a cumplir: server TCP con ServerSocket, comunicación con ObjectStreams, pool de hilos, bloqueo fino con ReentrantLock y manejo de excepciones para robustez.
 */
public class ReservaServer {
 
    public static final int PUERTO    = 9002;
    private static final int POOL_SIZE = 20;
 
    private final ConcurrentHashMap<String, Reserva> reservas =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> estadoAsientos =
            new ConcurrentHashMap<>();
 
    // Criterio de bloqueo: un ReentrantLock por asiento (viajeId-asiento) para evitar conflictos entre hilos al reservar el mismo asiento.
    private final ConcurrentHashMap<String, ReentrantLock> locks =
            new ConcurrentHashMap<>();
 
    private final ExecutorService pool =
            Executors.newFixedThreadPool(POOL_SIZE);
    private final ScheduledExecutorService limpiador =
            Executors.newSingleThreadScheduledExecutor();
 
    public ReservaServer() {
        inicializarAsientos();
        programarLimpieza();
    }
 
    // Función principal del servidor: acepta conexiones y delega a hilos del pool.
    public void iniciar() {
        try (ServerSocket serverSocket = new ServerSocket(PUERTO)) {
            System.out.println("==============================================");
            System.out.println("  Turbus - Servidor de Reservas ACTIVO");
            System.out.println("  Puerto TCP : " + PUERTO);
            System.out.println("  Pool hilos : " + POOL_SIZE);
            System.out.println("==============================================");
            while (true) {
                try {
                    Socket clienteSocket = serverSocket.accept();
                    pool.submit(new ManejadorCliente(clienteSocket));
                } catch (IOException e) {
                    System.err.println("[ReservaServer] Error aceptando cliente: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[ReservaServer] Error fatal puerto " + PUERTO + ": " + e.getMessage());
        } finally {
            pool.shutdown();
            limpiador.shutdown();
        }
    }
 
    // ManejadorCliente: cada conexión se procesa en un hilo separado, leyendo la solicitud, procesándola y enviando la respuesta.
 
    private class ManejadorCliente implements Runnable {
        private final Socket socket;
        ManejadorCliente(Socket socket) { this.socket = socket; }
 
        @Override
        // Función principal del hilo: manejar la comunicación con el cliente, procesar la solicitud y enviar la respuesta. Manejo de excepciones para desconexiones y errores de clase.
        public void run() {
            String addr = socket.getRemoteSocketAddress().toString();
            System.out.printf("[ReservaServer] Cliente: %s (hilo: %s)%n",
                    addr, Thread.currentThread().getName());
            try (ObjectOutputStream salida = new ObjectOutputStream(socket.getOutputStream());
                 ObjectInputStream  entrada = new ObjectInputStream(socket.getInputStream())) {
                Mensaje solicitud = (Mensaje) entrada.readObject();
                System.out.printf("[ReservaServer] Op '%s' de %s%n", solicitud.getTipo(), addr);
                Mensaje respuesta = procesarSolicitud(solicitud);
                salida.writeObject(respuesta);
                salida.flush();
            } catch (ClassNotFoundException e) {
                System.err.println("[ReservaServer] Clase desconocida: " + e.getMessage());
            } catch (IOException e) {
                System.err.println("[ReservaServer] Cliente desconectado: " + addr);
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
 
        // Función que procesa la solicitud según su tipo, llamando a la lógica de negocio correspondiente. Manejo de excepciones para errores de negocio (asiento ocupado, reserva no encontrada) y errores internos del servidor.
        private Mensaje procesarSolicitud(Mensaje sol) {
            try {
                Object[] p = sol.getParametros();
                return switch (sol.getTipo()) {
                    case Mensaje.CREAR_RESERVA -> {
                        int viajeId = (Integer) p[0];
                        int asiento = (Integer) p[1];
                        Pasajero pasajero = (Pasajero) p[2];
                        Reserva r = crearReserva(viajeId, asiento, pasajero);
                        yield Mensaje.ok(r);
                    }
                    case Mensaje.CONFIRMAR_PAGO -> {
                        String reservaId = (String) p[0];
                        double monto     = (Double) p[1];
                        String medio     = (String) p[2];
                        boolean aprobado = confirmarPago(reservaId, monto, medio);
                        yield Mensaje.ok(aprobado);
                    }
                    case Mensaje.CONSULTAR_ASIENTOS -> {
                        // BusquedaServer solo conoce disponibilidad inicial, no reservas reales.
                        int viajeId = (Integer) p[0];
                        Map<Integer, Boolean> estado = consultarEstadoAsientos(viajeId);
                        yield Mensaje.ok(new HashMap<>(estado));
                    }
                    case Mensaje.CANCELAR_RESERVA -> {
                        boolean ok = cancelarReserva((String) p[0]);
                        yield Mensaje.ok(ok);
                    }
                    case Mensaje.CONSULTAR_RESERVA -> {
                        Reserva r = reservas.get((String) p[0]);
                        yield Mensaje.ok(r);
                    }
                    case Mensaje.PING -> Mensaje.ok(Boolean.TRUE);
                    default -> Mensaje.error("Operación desconocida: " + sol.getTipo());
                };
            } catch (IllegalStateException e) {
                // Error de negocio: asiento ocupado, reserva no encontrada, etc.
                return Mensaje.errorNegocio(e.getMessage());
            } catch (Exception e) {
                System.err.println("[ReservaServer] Error interno: " + e.getMessage());
                return Mensaje.error("Error interno del servidor de reservas.");
            }
        }
    }
 
    /** Lógica de negocio: creación de reservas, confirmación de pagos, cancelaciones, consulta de estado de asientos. 
     * Uso de ReentrantLock para evitar double-booking y manejo de estados de reserva (pendiente, confirmada, cancelada, expirada).
    */
    private Reserva crearReserva(int viajeId, int asiento, Pasajero pasajero) {
        String clave = viajeId + "-" + asiento;
        ReentrantLock lock = locks.computeIfAbsent(clave, k -> new ReentrantLock());
 
        System.out.printf("[ReservaServer] Intento: Viaje#%d Asiento#%d | %s (hilo: %s)%n",
                viajeId, asiento, pasajero.getRut(), Thread.currentThread().getName());
 
        lock.lock();
        try {
            // Inicialización dinámica: si el asiento nunca fue visto, se crea como disponible.
            // Esto elimina la necesidad de pre-cargar el catálogo en ReservaServer y evita la desincronización con BusquedaServer.
            estadoAsientos.putIfAbsent(clave, true);
 
            Boolean disponible = estadoAsientos.get(clave);
            if (!disponible)
                throw new IllegalStateException("El asiento #" + asiento + " ya está ocupado o reservado.");
 
            estadoAsientos.put(clave, false);
            String id = UUID.randomUUID().toString();
            Reserva r = new Reserva(id, viajeId, asiento, pasajero, LocalDateTime.now());
            reservas.put(id, r);
            System.out.printf("[ReservaServer] Reserva creada: %s%n", r);
            return r;
        } finally {
            lock.unlock();  // SIEMPRE se libera, como parte del criterio de bloqueo fino para evitar deadlocks y garantizar la consistencia del estado de los asientos.
        }
    }
 
    // Función que simula la confirmación de pago, con una tasa de aprobación del 90%. Si el pago es aprobado, se confirma la reserva; si es rechazado, se cancela y se libera el asiento.
    private boolean confirmarPago(String reservaId, double monto, String medio) {
        Reserva r = reservas.get(reservaId);
        if (r == null)
            throw new IllegalStateException("Reserva '" + reservaId + "' no encontrada.");
        if (r.getEstado() != Reserva.Estado.PENDIENTE)
            throw new IllegalStateException("La reserva no está en estado PENDIENTE.");
 
        boolean aprobado = simularPago(monto, medio);
        if (aprobado) {
            r.setEstado(Reserva.Estado.CONFIRMADA);
            r.setMontoPagado(monto);
            System.out.printf("[ReservaServer] Pago APROBADO: %s $%.0f%n",
                    reservaId.substring(0,8), monto);
        } else {
            cancelarReservaInterna(r);
            System.out.printf("[ReservaServer] Pago RECHAZADO: %s — asiento liberado%n",
                    reservaId.substring(0,8));
        }
        return aprobado;
    }
 
    private boolean cancelarReserva(String reservaId) {
        Reserva r = reservas.get(reservaId);
        if (r == null) return false;
        cancelarReservaInterna(r);
        return true;
    }
 
    private void cancelarReservaInterna(Reserva r) {
        r.setEstado(Reserva.Estado.CANCELADA);
        estadoAsientos.put(r.getViajeId() + "-" + r.getNumeroAsiento(), true);
    }
 
    // Función programada que se ejecuta cada minuto para limpiar reservas pendientes que hayan expirado (no confirmadas en 15 minutos). Cambia su estado a EXPIRADA y libera el asiento.
    private void programarLimpieza() {
        limpiador.scheduleAtFixedRate(() -> {
            LocalDateTime ahora = LocalDateTime.now();
            int n = 0;
            for (Reserva r : reservas.values()) {
                if (r.getEstado() == Reserva.Estado.PENDIENTE
                        && ahora.isAfter(r.getExpiraEn())) {
                    cancelarReservaInterna(r);
                    r.setEstado(Reserva.Estado.EXPIRADA);
                    n++;
                }
            }
            if (n > 0) System.out.println("[ReservaServer] Limpieza: " + n + " expiradas.");
        }, 1, 1, TimeUnit.MINUTES);
    }
 
    /**
     * La función retorna el estado real de cada asiento para un viaje.
     * Incluye todos los asientos vistos (reservados o confirmados como ocupados).
     * Para asientos no vistos aún, los considera disponibles.
     */
    private Map<Integer, Boolean> consultarEstadoAsientos(int viajeId) {
        Map<Integer, Boolean> estado = new HashMap<>();
        // Recolectar todos los asientos conocidos para este viaje
        String prefijo = viajeId + "-";
        for (Map.Entry<String, Boolean> entry : estadoAsientos.entrySet()) {
            if (entry.getKey().startsWith(prefijo)) {
                int numAsiento = Integer.parseInt(entry.getKey().substring(prefijo.length()));
                estado.put(numAsiento, entry.getValue());
            }
        }
        return estado;
    }
 
    // Función que simula el proceso de pago, con una demora aleatoria y una tasa de aprobación del 90%. Permite probar la lógica de confirmación y rechazo de pagos en el sistema.
    private boolean simularPago(double monto, String medio) {
        try { Thread.sleep(200 + (long)(Math.random() * 600)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return Math.random() < 0.90; // 90% de aprobación
    }
 
    private void inicializarAsientos() {
        // Capacidades exactas del catálogo de BusquedaServer:
        // Viajes 1-9  : semicama, 44 asientos (rutas Santiago <-> Valparaíso)
        // Viajes 10-15: saloncama, 46 asientos (rutas largas)
        for (int vid : new int[]{1,2,3,4,5,6,7,8,9})
            for (int a = 1; a <= 44; a++) estadoAsientos.put(vid + "-" + a, true);
        for (int vid : new int[]{10,11,12,13,14,15})
            for (int a = 1; a <= 46; a++) estadoAsientos.put(vid + "-" + a, true);
    }
 
    // Punto de entrada del programa: crea una instancia de ReservaServer e inicia el servicio.
    public static void main(String[] args) { new ReservaServer().iniciar(); }
}