package turbus_nodo;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import turbus_comun.modelos.Pasajero;
import turbus_comun.modelos.Reserva;
import turbus_comun.modelos.Viaje;
import turbus_comun.protocolo.Mensaje;

public class NodoServidor {
    private final int id;
    private final int puerto;
    private static final int POOL_SIZE = 100; // Incrementado para manejar 50+ hilos sin encolar demasiado (ajustar según capacidad)

    // Reloj Lógico de Lamport
    private final AtomicInteger relojLocal = new AtomicInteger(0);

    // Discovery / Membership: Mapa de ID -> Puerto (se asume conocimiento previo de la topología para simplificar)
    private final Map<Integer, Integer> nodosConocidos = new HashMap<>();

    // Estado - Búsqueda
    private final ConcurrentHashMap<Integer, Viaje> catalogoViajes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Boolean>> mapaAsientos = new ConcurrentHashMap<>();

    // Estado - Reservas
    private final ConcurrentHashMap<String, Reserva> reservas = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> estadoAsientos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    // Estado Coordinación (Bully)
    private Integer idCoordinador = null;
    private volatile boolean enEleccion = false;

    private final ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE);
    
    // Heartbeat o monitorización periódica para detectar fallos del coordinador
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();

    public NodoServidor(int id, int puerto) {
        this.id = id;
        this.puerto = puerto;
        cargarDatosIniciales();
        
        // Registrar nodos de la topología
        nodosConocidos.put(1, 8001);
        nodosConocidos.put(2, 8002);
        nodosConocidos.put(3, 8003);
    }

    public void iniciar() {
        // Aumentar el backlog del ServerSocket a 1000 para evitar "Connection refused" bajo alta carga
        try (ServerSocket serverSocket = new ServerSocket(puerto, 1000)) {
            System.out.println("==============================================");
            System.out.printf("  Turbus - Nodo Servidor ACTIVO (ID: %d) [T: %d]%n", id, relojLocal.get());
            System.out.println("  Puerto TCP : " + puerto);
            System.out.println("==============================================");
            
            // Iniciar elección del algoritmo Bully al arrancar
            new Thread(this::iniciarEleccion).start();
            
            // Iniciar Heartbeats (cada 3 segundos)
            heartbeatScheduler.scheduleAtFixedRate(this::verificarCoordinador, 5, 3, TimeUnit.SECONDS);
            
            while (true) {
                try {
                    Socket clienteSocket = serverSocket.accept();
                    pool.submit(new ManejadorCliente(clienteSocket));
                } catch (IOException e) {
                    System.err.printf("[Nodo-%d | T: %d] Error aceptando cliente: %s%n", id, relojLocal.get(), e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.printf("[Nodo-%d | T: %d] Error fatal puerto %d: %s%n", id, relojLocal.get(), puerto, e.getMessage());
        } finally {
            pool.shutdown();
            heartbeatScheduler.shutdown();
        }
    }

    private class ManejadorCliente implements Runnable {
        private final Socket socket;
        ManejadorCliente(Socket socket) { this.socket = socket; }

        @Override
        public void run() {
            try (ObjectOutputStream salida = new ObjectOutputStream(socket.getOutputStream());
                 ObjectInputStream  entrada = new ObjectInputStream(socket.getInputStream())) {
                
                Mensaje solicitud = (Mensaje) entrada.readObject();
                // Actualizar reloj local de Lamport al recibir mensaje con la función: max(local, remoto) + 1
                int tActual = relojLocal.updateAndGet(t -> Math.max(t, solicitud.getRelojLamport()) + 1);
                
                System.out.printf("[Nodo-%d | T: %d] Op '%s' recibida%n", id, tActual, solicitud.getTipo());
                
                Mensaje respuesta = procesarSolicitud(solicitud);
                
                // Incrementar reloj local antes de enviar y adjuntarlo al mensaje
                int tEnvio = relojLocal.incrementAndGet();
                respuesta.setRelojLamport(tEnvio);
                
                salida.writeObject(respuesta);
                salida.flush();
            } catch (ClassNotFoundException | IOException e) {
                // ignorar desconexiones
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }

        private Mensaje procesarSolicitud(Mensaje sol) {
            try {
                Object[] p = sol.getParametros();
                return switch (sol.getTipo()) {
                    case Mensaje.BUSCAR_VIAJES -> {
                        List<Viaje> result = buscarViajes((String) p[0], (String) p[1], (LocalDate) p[2]);
                        yield Mensaje.ok(new ArrayList<>(result));
                    }
                    case Mensaje.CONSULTAR_ASIENTOS -> {
                        Map<Integer, Boolean> result = consultarAsientos((Integer) p[0]);
                        yield Mensaje.ok(new HashMap<>(result));
                    }
                    case Mensaje.OBTENER_DESTINOS -> {
                        List<String> result = obtenerDestinos((String) p[0]);
                        yield Mensaje.ok(new ArrayList<>(result));
                    }
                    case Mensaje.LISTAR_RUTAS -> {
                        List<String> rutas = listarRutas();
                        yield Mensaje.ok(new ArrayList<>(rutas));
                    }
                    case Mensaje.CREAR_RESERVA -> {
                        if (idCoordinador != null && idCoordinador != id) {
                            // Reenviar al coordinador
                            int puertoCoord = nodosConocidos.get(idCoordinador);
                            System.out.printf("[Nodo-%d | T: %d] Reenviando CREAR_RESERVA al coordinador %d%n", id, relojLocal.get(), idCoordinador);
                            Mensaje respuestaCoord = enviarMensajeInterNodo(puertoCoord, sol);
                            yield respuestaCoord;
                        } else {
                            Reserva r = crearReserva((Integer) p[0], (Integer) p[1], (Pasajero) p[2]);
                            yield Mensaje.ok(r);
                        }
                    }
                    case Mensaje.CONFIRMAR_PAGO -> {
                        if (idCoordinador != null && idCoordinador != id) {
                            // Reenviar al coordinador
                            int puertoCoord = nodosConocidos.get(idCoordinador);
                            System.out.printf("[Nodo-%d | T: %d] Reenviando CONFIRMAR_PAGO al coordinador %d%n", id, relojLocal.get(), idCoordinador);
                            Mensaje respuestaCoord = enviarMensajeInterNodo(puertoCoord, sol);
                            yield respuestaCoord;
                        } else {
                            boolean aprobado = confirmarPago((String) p[0], (Double) p[1], (String) p[2]);
                            yield Mensaje.ok(aprobado);
                        }
                    }
                    case Mensaje.CANCELAR_RESERVA -> {
                        if (idCoordinador != null && idCoordinador != id) {
                            int puertoCoord = nodosConocidos.get(idCoordinador);
                            Mensaje respuestaCoord = enviarMensajeInterNodo(puertoCoord, sol);
                            yield respuestaCoord;
                        } else {
                            boolean ok = cancelarReserva((String) p[0]);
                            yield Mensaje.ok(ok);
                        }
                    }
                    case Mensaje.CONSULTAR_RESERVA -> {
                        Reserva r = reservas.get((String) p[0]);
                        yield Mensaje.ok(r);
                    }
                    
                    // OPERACIONES BULLY Y REPLICACIÓN
                    case Mensaje.ELECTION -> {
                        if (!enEleccion) {
                            new Thread(NodoServidor.this::iniciarEleccion).start();
                        }
                        yield Mensaje.ok(Mensaje.OK_ELECTION);
                    }
                    case Mensaje.COORDINATOR -> {
                        idCoordinador = (Integer) p[0];
                        enEleccion = false;
                        System.out.printf("[Nodo-%d | T: %d] Nodo %d aceptado como COORDINADOR.%n", id, relojLocal.get(), idCoordinador);
                        yield Mensaje.ok(Boolean.TRUE);
                    }
                    case Mensaje.SYNC_RESERVA -> {
                        Reserva r = (Reserva) p[0];
                        boolean esCancelacion = p.length > 1 && (Boolean) p[1];
                        sincronizarReserva(r, esCancelacion);
                        yield Mensaje.ok(Boolean.TRUE);
                    }
                    case Mensaje.PING -> Mensaje.ok(Boolean.TRUE);
                    default -> Mensaje.error("Operación desconocida: " + sol.getTipo());
                };
            } catch (IllegalStateException | IllegalArgumentException e) {
                return Mensaje.errorNegocio(e.getMessage());
            } catch (Exception e) {
                e.printStackTrace();
                return Mensaje.error("Error interno del Nodo " + id);
            }
        }
    }

    private List<Viaje> buscarViajes(String origen, String destino, LocalDate fecha) {
        return catalogoViajes.values().stream()
                .filter(v -> v.getOrigen().equalsIgnoreCase(origen))
                .filter(v -> v.getDestino().equalsIgnoreCase(destino))
                .filter(v -> v.getFecha().equals(fecha))
                .filter(v -> v.getAsientosDisponibles() > 0)
                .sorted(Comparator.comparing(Viaje::getHoraSalida))
                .collect(Collectors.toList());
    }

    public synchronized Map<Integer, Boolean> consultarAsientos(int viajeId) {
        ConcurrentHashMap<Integer, Boolean> asientos = mapaAsientos.get(viajeId);
        if (asientos == null) throw new IllegalArgumentException("Viaje #" + viajeId + " no existe.");
        
        Map<Integer, Boolean> asientosReales = new HashMap<>(asientos);
        for (int i = 1; i <= asientosReales.size(); i++) {
            String clave = viajeId + "-" + i;
            if (estadoAsientos.containsKey(clave)) {
                asientosReales.put(i, estadoAsientos.get(clave));
            }
        }
        return asientosReales;
    }

    private List<String> obtenerDestinos(String origen) {
        return catalogoViajes.values().stream()
                .filter(v -> v.getOrigen().equalsIgnoreCase(origen))
                .map(Viaje::getDestino).distinct().sorted()
                .collect(Collectors.toList());
    }

    public synchronized void marcarAsientoOcupado(int viajeId, int asiento) {
        ConcurrentHashMap<Integer, Boolean> a = mapaAsientos.get(viajeId);
        if (a != null) {
            a.put(asiento, false);
            Viaje v = catalogoViajes.get(viajeId);
            if (v != null) v.setAsientosDisponibles(v.getAsientosDisponibles() - 1);
        }
    }

    public synchronized void liberarAsiento(int viajeId, int asiento) {
        ConcurrentHashMap<Integer, Boolean> a = mapaAsientos.get(viajeId);
        if (a != null) {
            a.put(asiento, true);
            Viaje v = catalogoViajes.get(viajeId);
            if (v != null) v.setAsientosDisponibles(v.getAsientosDisponibles() + 1);
        }
    }

    private List<String> listarRutas() {
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        return catalogoViajes.values().stream()
                .map(v -> v.getOrigen() + " -> " + v.getDestino() + "  |  " + v.getFecha().format(fmt))
                .distinct().sorted().collect(Collectors.toList());
    }

    private void cargarDatosIniciales() {
        LocalDate d1 = LocalDate.of(2026, 4, 18);
        LocalDate d2 = LocalDate.of(2026, 4, 19);
        LocalDate d3 = LocalDate.of(2026, 4, 20);

        agregarViaje(new Viaje(1,"Santiago","Valparaiso",d1,LocalTime.of(8,0),LocalTime.of(9,30),4500,44,"semicama"));
        agregarViaje(new Viaje(2,"Santiago","Valparaiso",d1,LocalTime.of(12,0),LocalTime.of(13,30),4500,44,"semicama"));
        agregarViaje(new Viaje(3,"Santiago","Valparaiso",d1,LocalTime.of(18,0),LocalTime.of(19,30),5000,44,"saloncama"));
        agregarViaje(new Viaje(4,"Santiago","Valparaiso",d2,LocalTime.of(8,0),LocalTime.of(9,30),4500,44,"semicama"));
        agregarViaje(new Viaje(5,"Santiago","Valparaiso",d2,LocalTime.of(20,0),LocalTime.of(21,30),5000,44,"saloncama"));
        agregarViaje(new Viaje(6,"Santiago","Valparaiso",d3,LocalTime.of(10,0),LocalTime.of(11,30),4500,44,"semicama"));
        
        agregarViaje(new Viaje(7,"Valparaiso","Santiago",d1,LocalTime.of(7,0),LocalTime.of(8,30),4500,44,"semicama"));
        agregarViaje(new Viaje(8,"Valparaiso","Santiago",d1,LocalTime.of(15,0),LocalTime.of(16,30),4500,44,"semicama"));
    }

    private void agregarViaje(Viaje viaje) {
        catalogoViajes.put(viaje.getId(), viaje);
        ConcurrentHashMap<Integer, Boolean> a = new ConcurrentHashMap<>();
        for (int i = 1; i <= viaje.getTotalAsientos(); i++) a.put(i, true);
        mapaAsientos.put(viaje.getId(), a);
    }

    private Reserva crearReserva(int viajeId, int asiento, Pasajero pasajero) {
        String clave = viajeId + "-" + asiento;
        ReentrantLock lock = locks.computeIfAbsent(clave, k -> new ReentrantLock());

        lock.lock();
        try {
            estadoAsientos.putIfAbsent(clave, true);
            Boolean disponible = estadoAsientos.get(clave);
            if (!disponible)
                throw new IllegalStateException("El asiento #" + asiento + " ya está ocupado o reservado.");

            estadoAsientos.put(clave, false);
            marcarAsientoOcupado(viajeId, asiento);

            String resId = UUID.randomUUID().toString();
            Reserva r = new Reserva(resId, viajeId, asiento, pasajero, java.time.LocalDateTime.now());
            reservas.put(resId, r);
            
            System.out.printf("[Nodo-%d | T: %d] Reserva %s creada para viaje %d asiento %d%n", 
                    id, relojLocal.get(), resId.substring(0,8), viajeId, asiento);
                    
            // Replicar a los demás nodos
            replicarReserva(r, false);
            
            return r;
        } finally {
            lock.unlock();
        }
    }

    private boolean confirmarPago(String reservaId, double monto, String medio) {
        Reserva r = reservas.get(reservaId);
        if (r == null) throw new IllegalStateException("Reserva '" + reservaId + "' no encontrada.");
        if (r.getEstado() != Reserva.Estado.PENDIENTE) throw new IllegalStateException("La reserva no está en estado PENDIENTE.");

        boolean aprobado = simularPago(monto, medio);
        if (aprobado) {
            r.setEstado(Reserva.Estado.CONFIRMADA);
            r.setMontoPagado(monto);
            System.out.printf("[Nodo-%d | T: %d] Pago aprobado para reserva %s%n", 
                    id, relojLocal.get(), reservaId.substring(0,8));
            replicarReserva(r, false);
        } else {
            cancelarReservaInterna(r);
            System.out.printf("[Nodo-%d | T: %d] Pago rechazado para reserva %s%n", 
                    id, relojLocal.get(), reservaId.substring(0,8));
        }
        return aprobado;
    }

    private boolean cancelarReserva(String reservaId) {
        Reserva r = reservas.get(reservaId);
        if (r == null) return false;
        if (r.getEstado() == Reserva.Estado.CANCELADA || r.getEstado() == Reserva.Estado.EXPIRADA) return false;
        
        cancelarReservaInterna(r);
        return true;
    }

    private void cancelarReservaInterna(Reserva r) {
        String clave = r.getViajeId() + "-" + r.getNumeroAsiento();
        ReentrantLock lock = locks.computeIfAbsent(clave, k -> new ReentrantLock());
        lock.lock();
        try {
            r.setEstado(Reserva.Estado.CANCELADA);
            estadoAsientos.put(clave, true);
            liberarAsiento(r.getViajeId(), r.getNumeroAsiento());
            replicarReserva(r, true);
        } finally {
            lock.unlock();
        }
    }

    private boolean simularPago(double monto, String medio) {
        return Math.random() < 0.90;
    }
    
    // LÓGICA DE ALGORITMO BULLY: El nodo con ID más alto que esté activo se convierte en coordinador. Si el coordinador falla, los nodos detectan su ausencia y se inicia una nueva elección.
    private synchronized void iniciarEleccion() {
        enEleccion = true;
        System.out.printf("[Nodo-%d | T: %d] Iniciando elección (Algoritmo Bully)...%n", id, relojLocal.get());
        boolean alguienRespondio = false;

        for (Map.Entry<Integer, Integer> nodo : nodosConocidos.entrySet()) {
            if (nodo.getKey() > this.id) {
                try {
                    Mensaje m = new Mensaje(Mensaje.ELECTION);
                    Mensaje respuesta = enviarMensajeInterNodo(nodo.getValue(), m);
                    if (respuesta != null && Mensaje.OK_ELECTION.equals(respuesta.getResultado())) {
                        alguienRespondio = true;
                    }
                } catch (Exception e) {
                    // Nodo mayor no disponible
                }
            }
        }

        if (!alguienRespondio) {
            proclamarseCoordinador();
        }
    }

    private void proclamarseCoordinador() {
        this.idCoordinador = this.id;
        this.enEleccion = false;
        System.out.printf("[Nodo-%d | T: %d] *** ME PROCLAMO COORDINADOR ***%n", id, relojLocal.get());
        
        for (Map.Entry<Integer, Integer> nodo : nodosConocidos.entrySet()) {
            if (nodo.getKey() != this.id) {
                try {
                    enviarMensajeInterNodo(nodo.getValue(), new Mensaje(Mensaje.COORDINATOR, this.id));
                } catch (Exception e) {
                    // Ignorar si no está
                }
            }
        }
    }
    
    // LÓGICA DE TOLERANCIA A FALLOS (HEARTBEAT): El coordinador envía PINGs periódicos a los nodos, y los nodos hacen lo mismo con el coordinador. 
    // Si no hay respuesta, se asume que el nodo está caído y se inicia una nueva elección.
    private void verificarCoordinador() {
        if (idCoordinador == null || idCoordinador == this.id || enEleccion) {
            return; // Significado: Soy el coordinador, o aún no hay uno, o estamos en medio de una elección
        }
        
        int puertoCoord = nodosConocidos.get(idCoordinador);
        try {
            Mensaje respuesta = enviarMensajeInterNodo(puertoCoord, new Mensaje(Mensaje.PING));
            if (respuesta == null || !respuesta.esOk()) {
                throw new IOException("Respuesta inválida del PING");
            }
            // Heartbeat OK
        } catch (IOException e) {
            System.err.printf("[Nodo-%d | T: %d] ¡ALERTA! Coordinador %d caído o no responde. Iniciando nueva elección...%n", 
                    id, relojLocal.get(), idCoordinador);
            idCoordinador = null; // Reiniciar estado
            iniciarEleccion();
        }
    }
    
    // LÓGICA DE REPLICACIÓN: El coordinador replica las reservas a los demás nodos para mantener consistencia eventual
    private void replicarReserva(Reserva r, boolean esCancelacion) {
        if (idCoordinador == null || idCoordinador != this.id) return; // Solo el coordinador replica
        
        for (Map.Entry<Integer, Integer> nodo : nodosConocidos.entrySet()) {
            if (nodo.getKey() != this.id) {
                try {
                    enviarMensajeInterNodo(nodo.getValue(), new Mensaje(Mensaje.SYNC_RESERVA, r, esCancelacion));
                } catch (Exception e) {
                    // Ignorar nodo desconectado por ahora
                }
            }
        }
    }
    
    private void sincronizarReserva(Reserva r, boolean esCancelacion) {
        String clave = r.getViajeId() + "-" + r.getNumeroAsiento();
        reservas.put(r.getId(), r);
        if (esCancelacion) {
            estadoAsientos.put(clave, true);
            liberarAsiento(r.getViajeId(), r.getNumeroAsiento());
        } else {
            estadoAsientos.put(clave, false);
            marcarAsientoOcupado(r.getViajeId(), r.getNumeroAsiento());
        }
        System.out.printf("[Nodo-%d | T: %d] Reserva %s sincronizada (Cancelada: %b)%n", id, relojLocal.get(), r.getId().substring(0,8), esCancelacion);
    }
    
    // COMUNICACIÓN INTER-NODO: Enviar mensajes con reloj de Lamport y esperar respuesta
    private Mensaje enviarMensajeInterNodo(int puertoDestino, Mensaje solicitud) throws IOException {
        int tEnvio = relojLocal.incrementAndGet();
        solicitud.setRelojLamport(tEnvio);
        
        try (Socket socket = new Socket("localhost", puertoDestino);
             ObjectOutputStream salida = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream entrada = new ObjectInputStream(socket.getInputStream())) {
            
            socket.setSoTimeout(2000); 
            salida.writeObject(solicitud);
            salida.flush();
            Mensaje r = (Mensaje) entrada.readObject();
            relojLocal.updateAndGet(t -> Math.max(t, r.getRelojLamport()) + 1);
            return r;
        } catch (ClassNotFoundException e) {
            throw new IOException("Clase no encontrada", e);
        }
    }

    public static void main(String[] args) {
        int id = 1;
        int puerto = 8001;
        if (args.length >= 2) {
            id = Integer.parseInt(args[0]);
            puerto = Integer.parseInt(args[1]);
        }
        new NodoServidor(id, puerto).iniciar();
    }
}
