package turbus_busqueda;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import turbus_comun.modelos.Viaje;
import turbus_comun.protocolo.Mensaje;
 
/**
 * Servidor TCP multi-hilo para el servicio de búsqueda de Turbus.
 *
 * Criterios a cumplir: usar ServerSocket/Socket, serializar objeto Mensaje y manejar concurrencia con ExecutorService,
 */
public class BusquedaServer {
 
    public static final int PUERTO    = 9001;
    private static final int POOL_SIZE = 20;
 
    private final ConcurrentHashMap<Integer, Viaje> catalogoViajes =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Boolean>>
            mapaAsientos = new ConcurrentHashMap<>();
    private final ExecutorService pool =
            Executors.newFixedThreadPool(POOL_SIZE);
 
    public BusquedaServer() { cargarDatosIniciales(); }
 
    // Función principal del servidor: aceptar clientes y delegar a hilos del pool (criterio de servidor multi-hilo)
    public void iniciar() {
        try (ServerSocket serverSocket = new ServerSocket(PUERTO)) {
            System.out.println("==============================================");
            System.out.println("  Turbus - Servidor de Búsqueda ACTIVO");
            System.out.println("  Puerto TCP : " + PUERTO);
            System.out.println("  Pool hilos : " + POOL_SIZE);
            System.out.println("==============================================");
            while (true) {
                try {
                    Socket clienteSocket = serverSocket.accept();
                    pool.submit(new ManejadorCliente(clienteSocket));
                } catch (IOException e) {
                    System.err.println("[BusquedaServer] Error al aceptar cliente: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[BusquedaServer] Error fatal puerto " + PUERTO + ": " + e.getMessage());
        } finally {
            pool.shutdown();
        }
    }

    // Función que maneja la comunicación con un cliente específico (criterio de manejo de clientes)
    private class ManejadorCliente implements Runnable {
        private final Socket socket;
        ManejadorCliente(Socket socket) { this.socket = socket; }
 
        @Override
        public void run() {
            String addr = socket.getRemoteSocketAddress().toString();
            System.out.printf("[BusquedaServer] Cliente conectado: %s (hilo: %s)%n",
                    addr, Thread.currentThread().getName());
            try (ObjectOutputStream salida = new ObjectOutputStream(socket.getOutputStream());
                 ObjectInputStream  entrada = new ObjectInputStream(socket.getInputStream())) {
                Mensaje solicitud = (Mensaje) entrada.readObject();
                System.out.printf("[BusquedaServer] Op '%s' de %s%n", solicitud.getTipo(), addr);
                Mensaje respuesta = procesarSolicitud(solicitud);
                salida.writeObject(respuesta);
                salida.flush();
            } catch (ClassNotFoundException e) {
                System.err.println("[BusquedaServer] Clase desconocida: " + e.getMessage());
            } catch (IOException e) {
                System.err.println("[BusquedaServer] Cliente desconectado: " + addr);
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
        
        // Función secundaria que procesa la solicitud del cliente y retorna una respuesta
        private Mensaje procesarSolicitud(Mensaje sol) {
            try {
                Object[] p = sol.getParametros();
                return switch (sol.getTipo()) {
                    case Mensaje.BUSCAR_VIAJES -> {
                        List<Viaje> result = buscarViajes(
                                (String) p[0], (String) p[1], (LocalDate) p[2]);
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
                    case Mensaje.PING -> Mensaje.ok(Boolean.TRUE);
                    default -> Mensaje.error("Operación desconocida: " + sol.getTipo());
                };
            } catch (IllegalArgumentException e) {
                return Mensaje.errorNegocio(e.getMessage());
            } catch (Exception e) {
                System.err.println("[BusquedaServer] Error interno: " + e.getMessage());
                return Mensaje.error("Error interno del servidor de búsqueda.");
            }
        }
    }
 
    // Lógica de negocio (criterio de búsqueda de viajes, consulta de asientos, etc.)
    
    // Función que busca viajes según criterios y retorna una lista ordenada por hora de salida
    private List<Viaje> buscarViajes(String origen, String destino, LocalDate fecha) {
        return catalogoViajes.values().stream()
                .filter(v -> v.getOrigen().equalsIgnoreCase(origen))
                .filter(v -> v.getDestino().equalsIgnoreCase(destino))
                .filter(v -> v.getFecha().equals(fecha))
                .filter(v -> v.getAsientosDisponibles() > 0)
                .sorted(Comparator.comparing(Viaje::getHoraSalida))
                .collect(Collectors.toList());
    }
 
    // Función que retorna un mapa de asientos disponibles para un viaje específico
    public synchronized Map<Integer, Boolean> consultarAsientos(int viajeId) {
        ConcurrentHashMap<Integer, Boolean> asientos = mapaAsientos.get(viajeId);
        if (asientos == null) throw new IllegalArgumentException("Viaje #" + viajeId + " no existe.");
        return new HashMap<>(asientos);
    }
    
    // Función que retorna una lista de destinos disponibles desde un origen dado, ordenada alfabéticamente
    private List<String> obtenerDestinos(String origen) {
        return catalogoViajes.values().stream()
                .filter(v -> v.getOrigen().equalsIgnoreCase(origen))
                .map(Viaje::getDestino).distinct().sorted()
                .collect(Collectors.toList());
    }
 
    // Funciones para marcar un asiento como ocupado o liberarlo, actualizando también el conteo de asientos disponibles (criterio de concurrencia)
    public synchronized void marcarAsientoOcupado(int viajeId, int asiento) {
        ConcurrentHashMap<Integer, Boolean> a = mapaAsientos.get(viajeId);
        if (a != null) {
            a.put(asiento, false);
            Viaje v = catalogoViajes.get(viajeId);
            if (v != null) v.setAsientosDisponibles(v.getAsientosDisponibles() - 1);
        }
    }
 
    // Función para liberar un asiento, marcándolo como disponible y actualizando el conteo de asientos disponibles
    public synchronized void liberarAsiento(int viajeId, int asiento) {
        ConcurrentHashMap<Integer, Boolean> a = mapaAsientos.get(viajeId);
        if (a != null) {
            a.put(asiento, true);
            Viaje v = catalogoViajes.get(viajeId);
            if (v != null) v.setAsientosDisponibles(v.getAsientosDisponibles() + 1);
        }
    }
 
    // Función que retorna una lista de rutas únicas (origen a destino y fecha) ordenadas alfabéticamente
    private List<String> listarRutas() {
        // Retorna líneas "Origen → Destino  |  dd/MM/yyyy" únicas y ordenadas
        java.time.format.DateTimeFormatter fmt =
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        return catalogoViajes.values().stream()
                .map(v -> v.getOrigen() + " -> " + v.getDestino()
                        + "  |  " + v.getFecha().format(fmt))
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }
 
    // Función que carga viajes de ejemplo al catálogo, con fechas fijas para facilitar las pruebas
    private void cargarDatosIniciales() {
        // Fechas fijas — independiente de cuándo arranca el servidor.
        LocalDate d1 = LocalDate.of(2026, 4, 18);
        LocalDate d2 = LocalDate.of(2026, 4, 19);
        LocalDate d3 = LocalDate.of(2026, 4, 20);
 
        // Santiago → Valparaiso
        agregarViaje(new Viaje(1,"Santiago","Valparaiso",d1,LocalTime.of(8,0),LocalTime.of(9,30),4500,44,"semicama"));
        agregarViaje(new Viaje(2,"Santiago","Valparaiso",d1,LocalTime.of(12,0),LocalTime.of(13,30),4500,44,"semicama"));
        agregarViaje(new Viaje(3,"Santiago","Valparaiso",d1,LocalTime.of(18,0),LocalTime.of(19,30),5000,44,"saloncama"));
        agregarViaje(new Viaje(4,"Santiago","Valparaiso",d2,LocalTime.of(8,0),LocalTime.of(9,30),4500,44,"semicama"));
        agregarViaje(new Viaje(5,"Santiago","Valparaiso",d2,LocalTime.of(20,0),LocalTime.of(21,30),5000,44,"saloncama"));
        agregarViaje(new Viaje(6,"Santiago","Valparaiso",d3,LocalTime.of(10,0),LocalTime.of(11,30),4500,44,"semicama"));
 
        // Valparaiso → Santiago
        agregarViaje(new Viaje(7,"Valparaiso","Santiago",d1,LocalTime.of(7,0),LocalTime.of(8,30),4500,44,"semicama"));
        agregarViaje(new Viaje(8,"Valparaiso","Santiago",d1,LocalTime.of(15,0),LocalTime.of(16,30),4500,44,"semicama"));
        agregarViaje(new Viaje(9,"Valparaiso","Santiago",d2,LocalTime.of(9,0),LocalTime.of(10,30),4500,44,"semicama"));
 
        // Santiago → Concepcion
        agregarViaje(new Viaje(10,"Santiago","Concepcion",d1,LocalTime.of(22,0),LocalTime.of(6,0),15000,46,"saloncama"));
        agregarViaje(new Viaje(11,"Santiago","Concepcion",d2,LocalTime.of(22,0),LocalTime.of(6,0),15000,46,"saloncama"));
 
        // Santiago → La Serena
        agregarViaje(new Viaje(12,"Santiago","La Serena",d1,LocalTime.of(20,30),LocalTime.of(4,30),18000,46,"saloncama"));
        agregarViaje(new Viaje(13,"Santiago","La Serena",d2,LocalTime.of(21,0),LocalTime.of(5,0),18000,46,"saloncama"));
 
        // Concepcion → Santiago
        agregarViaje(new Viaje(14,"Concepcion","Santiago",d2,LocalTime.of(21,0),LocalTime.of(5,0),15000,46,"saloncama"));
        agregarViaje(new Viaje(15,"Concepcion","Santiago",d3,LocalTime.of(20,0),LocalTime.of(4,0),15000,46,"saloncama"));
 
        System.out.println("[BusquedaServer] Viajes cargados para: " + d1 + ", " + d2 + ", " + d3);
    }
    // Función auxiliar que agrega un viaje al catálogo y crea su mapa de asientos disponibles
    private void agregarViaje(Viaje viaje) {
        catalogoViajes.put(viaje.getId(), viaje);
        ConcurrentHashMap<Integer, Boolean> a = new ConcurrentHashMap<>();
        for (int i = 1; i <= viaje.getTotalAsientos(); i++) a.put(i, true);
        mapaAsientos.put(viaje.getId(), a);
    }

    // Punto de entrada del programa
    public static void main(String[] args) { new BusquedaServer().iniciar(); }
}