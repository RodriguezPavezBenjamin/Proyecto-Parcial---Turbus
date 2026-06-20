package turbus_cliente;

import java.io.*;
import java.net.Socket;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import turbus_comun.modelos.Pasajero;
import turbus_comun.protocolo.Mensaje;
import turbus_comun.protocolo.NegocioException;

// Generador de carga para probar el sistema Turbus bajo condiciones de alta concurrencia.
public class LoadTester {

    private static final String HOST = "localhost";
    private static final int[] PUERTOS_NODOS = {8001, 8002, 8003}; // Puertos de los nodos del sistema
    private static final int NUM_HILOS = 50; // Número de hilos concurrentes para generar carga (se puede ajustar según la capacidad del sistema)
    private static final int DURACION_SEGUNDOS = 60; // Duración total de la prueba en segundos

    private final AtomicInteger peticionesTotales = new AtomicInteger(0);
    private final AtomicInteger peticionesExitosas = new AtomicInteger(0);
    private final AtomicInteger errores = new AtomicInteger(0);
    private final ConcurrentLinkedQueue<Long> latencias = new ConcurrentLinkedQueue<>();
    
    // Reloj local de Lamport del load tester
    private final AtomicInteger relojLamport = new AtomicInteger(0);

    private volatile boolean corriendo = true;

    public static void main(String[] args) {
        new LoadTester().iniciar();
    }

    public void iniciar() {
        System.out.println("==============================================");
        System.out.println("  Turbus - Generador de Carga (Load Tester)");
        System.out.printf("  Hilos Concurrentes : %d%n", NUM_HILOS);
        System.out.printf("  Duración de Prueba : %d segundos%n", DURACION_SEGUNDOS);
        System.out.println("==============================================");
        System.out.println("Iniciando prueba de carga...");

        ExecutorService pool = Executors.newFixedThreadPool(NUM_HILOS);
        
        for (int i = 0; i < NUM_HILOS; i++) {
            pool.submit(this::ejecutarCarga);
        }

        // Esperar la duración de la prueba
        try {
            for(int i = 0; i < DURACION_SEGUNDOS; i++) {
                Thread.sleep(1000);
                if (i % 10 == 0 && i > 0) {
                    System.out.println("  Progresando... " + i + " segundos completados.");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        corriendo = false;
        System.out.println("Deteniendo hilos de carga. Esto puede tardar unos segundos...");
        pool.shutdown();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        imprimirMetricas();
    }

    private void ejecutarCarga() {
        Random rand = new Random();
        LocalDate fecha = LocalDate.of(2026, 4, 18);
        String[] origenes = {"Santiago", "Valparaiso"};
        String[] destinos = {"Valparaiso", "Santiago"};

        while (corriendo) {
            long inicio = System.currentTimeMillis();
            boolean exito = false;
            
            int puertoObj = PUERTOS_NODOS[rand.nextInt(PUERTOS_NODOS.length)];
            
            try {
                // 80% Busquedas, 20% Intentos de Reserva (que pueden fallar por concurrencia, lo cual es un resultado esperado y no un error del sistema)
                if (rand.nextDouble() < 0.80) {
                    int o = rand.nextInt(2);
                    Mensaje m = new Mensaje(Mensaje.BUSCAR_VIAJES, origenes[o], destinos[o], fecha);
                    enviarMensaje(puertoObj, m);
                    exito = true;
                } else {
                    int viajeId = rand.nextInt(9) + 1; // Viajes del 1 al 9 existen
                    int asiento = rand.nextInt(44) + 1;
                    Pasajero p = new Pasajero("11111111-1", "Test", "Load", "test@load.com");
                    Mensaje m = new Mensaje(Mensaje.CREAR_RESERVA, viajeId, asiento, p);
                    enviarMensaje(puertoObj, m);
                    exito = true;
                }
            } catch (NegocioException e) {
                // Error de negocio (asiento ocupado) cuenta como éxito a nivel de sistema distribuido ya que el sistema lo proceso correctamente bajo concurrencia.
                exito = true; 
            } catch (IOException e) {
                // Error de red
                exito = false;
            }

            long latencia = System.currentTimeMillis() - inicio;
            latencias.add(latencia);
            
            peticionesTotales.incrementAndGet();
            if (exito) peticionesExitosas.incrementAndGet();
            else errores.incrementAndGet();
            
            // Pausa de 150ms para evitar agotar los puertos efímeros del sistema operativo (TIME_WAIT)
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
        }
    }

    private Object enviarMensaje(int puerto, Mensaje solicitud) throws IOException, NegocioException {
        solicitud.setRelojLamport(relojLamport.incrementAndGet());
        
        try (Socket socket = new Socket(HOST, puerto);
             ObjectOutputStream salida = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream  entrada = new ObjectInputStream(socket.getInputStream())) {
            
            socket.setSoTimeout(3000);
            salida.writeObject(solicitud);
            salida.flush();
            Mensaje r = (Mensaje) entrada.readObject();
            
            relojLamport.updateAndGet(t -> Math.max(t, r.getRelojLamport()) + 1);
            
            if (r.esOk())           return r.getResultado();
            if (r.esErrorNegocio()) throw new NegocioException(r.getMensajeError());
            throw new IOException("Error servidor: " + r.getMensajeError());
        } catch (ClassNotFoundException e) {
            throw new IOException("Protocolo incompatible");
        }
    }

    private void imprimirMetricas() {
        System.out.println("\n==============================================");
        System.out.println("  RESULTADOS DE LA PRUEBA DE CARGA");
        System.out.println("==============================================");
        
        long total = peticionesTotales.get();
        long exito = peticionesExitosas.get();
        long error = errores.get();
        double throughput = (double) total / DURACION_SEGUNDOS;

        System.out.printf("  Total de peticiones  : %d%n", total);
        System.out.printf("  Peticiones exitosas  : %d%n", exito);
        System.out.printf("  Errores (caídas red) : %d (%.2f%%)%n", error, (error * 100.0) / Math.max(1, total));
        System.out.printf("  Throughput           : %.2f peticiones/seg%n", throughput);
        
        List<Long> latList = new ArrayList<>(latencias);
        if (!latList.isEmpty()) {
            Collections.sort(latList);
            long min = latList.get(0);
            long max = latList.get(latList.size() - 1);
            double avg = latList.stream().mapToLong(Long::longValue).average().orElse(0.0);
            long p95 = latList.get((int) (latList.size() * 0.95));
            
            System.out.printf("  Latencia Mínima      : %d ms%n", min);
            System.out.printf("  Latencia Promedio    : %.2f ms%n", avg);
            System.out.printf("  Latencia p95         : %d ms%n", p95);
            System.out.printf("  Latencia Máxima      : %d ms%n", max);
        }
        System.out.println("==============================================");
    }
}
