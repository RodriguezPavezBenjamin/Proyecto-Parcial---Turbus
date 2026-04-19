package turbus_cliente;

import java.io.*;
import java.net.Socket;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import turbus_comun.modelos.Pasajero;
import turbus_comun.modelos.Reserva;
import turbus_comun.modelos.Viaje;
import turbus_comun.protocolo.Mensaje;
import turbus_comun.protocolo.NegocioException;
 
/**
 * Terminal interactivo para simular la compra de un pasaje Turbus. Se conecta a los servidores TCP reales (BusquedaServer y ReservaServer).
 *
 * Flujo de compra:
 *   1. Ingresa origen, destino y fecha
 *   2. Se listan los viajes disponibles
 *   3. El usuario elige un viaje → se muestra el mapa de asientos
 *   4. El usuario elige un asiento → se crea la reserva
 *   5. Se confirma el pago → ticket final
 */
public class TerminalInteractivo {
 
    private static final String HOST_BUSQUEDA   = "localhost";
    private static final String HOST_RESERVAS   = "localhost";
    private static final int    PUERTO_BUSQUEDA = 9001;
    private static final int    PUERTO_RESERVAS = 9002;
 
    private static final String RESET    = "\u001B[0m";
    private static final String VERDE    = "\u001B[32m";
    private static final String ROJO     = "\u001B[31m";
    private static final String CYAN     = "\u001B[36m";
    private static final String AMARILLO = "\u001B[33m";
    private static final String NEGRITA  = "\u001B[1m";
 
    private final Scanner scanner = new Scanner(System.in);
 
    public static void main(String[] args) {
        new TerminalInteractivo().ejecutar();
    }
 
    public void ejecutar() {
        limpiarPantalla();
        imprimirBanner();
 
        while (true) {
            try {
                // Mostrar rutas disponibles antes de pedir datos de búsqueda, para orientar al usuario.
                mostrarRutasDisponibles();
 
                // Paso 1: datos de búsqueda
                String origen  = pedirTexto("  Ciudad de origen  : ");
                String destino = pedirTexto("  Ciudad de destino : ");
                LocalDate fecha = pedirFecha("  Fecha (dd/MM/yyyy): ");
 
                // Paso 2: buscar viajes
                System.out.println();
                System.out.println(CYAN + "  Buscando viajes..." + RESET);
 
                @SuppressWarnings("unchecked")
                List<Viaje> viajes = (List<Viaje>) enviarMensaje(
                        HOST_BUSQUEDA, PUERTO_BUSQUEDA,
                        new Mensaje(Mensaje.BUSCAR_VIAJES, origen, destino, fecha));
 
                if (viajes.isEmpty()) {
                    System.out.println(AMARILLO
                            + "\n  No hay viajes para esa ruta y fecha.\n" + RESET);
                    if (!preguntarSiNo("  ¿Buscar otra ruta? (s/n): ")) break;
                    limpiarPantalla();
                    imprimirBanner();
                    continue;
                }
 
                // Paso 3: elegir viaje
                mostrarTablaViajes(viajes);
                int idx = pedirOpcion("  Seleccione viaje (#): ", 1, viajes.size()) - 1;
                Viaje viaje = viajes.get(idx);
 
                // Paso 4: mapa de asientos
                // ReservaServer es la fuente del estado de asientos.
                // BusquedaServer solo sabe cuántos hay en total, no cuáles están reservados.
                @SuppressWarnings("unchecked")
                Map<Integer, Boolean> asientos = (Map<Integer, Boolean>) enviarMensaje(
                        HOST_RESERVAS, PUERTO_RESERVAS,
                        new Mensaje(Mensaje.CONSULTAR_ASIENTOS, viaje.getId()));
 
                mostrarMapaAsientos(asientos, viaje);
 
                // Paso 5: elegir asiento
                int asiento = pedirOpcion("  Número de asiento : ", 1, viaje.getTotalAsientos());
                if (!Boolean.TRUE.equals(asientos.get(asiento))) {
                    System.out.println(ROJO + "\n  Asiento no disponible. Elija otro.\n" + RESET);
                    continue;
                }
 
                // Paso 6: datos del pasajero
                System.out.println();
                System.out.println(NEGRITA + "  Datos del pasajero" + RESET);
                String rut      = pedirTexto("  RUT (ej: 12345678-9): ");
                String nombre   = pedirTexto("  Nombre             : ");
                String apellido = pedirTexto("  Apellido           : ");
                String email    = pedirTexto("  Email              : ");
                Pasajero pasajero = new Pasajero(rut, nombre, apellido, email);
 
                // Paso 7: crear reserva
                System.out.println(CYAN + "\n  Creando reserva..." + RESET);
                Reserva reserva = (Reserva) enviarMensaje(
                        HOST_RESERVAS, PUERTO_RESERVAS,
                        new Mensaje(Mensaje.CREAR_RESERVA, viaje.getId(), asiento, pasajero));
 
                System.out.println(VERDE + "\n  Reserva creada. Tiene 10 minutos para pagar." + RESET);
                System.out.println("  ID : " + reserva.getId());
                System.out.printf ("  Monto : $%.0f%n", viaje.getPrecio());
 
                // Paso 8: pago y ticket
                System.out.println();
                System.out.println(NEGRITA + "  Medio de pago:" + RESET);
                System.out.println("    1. WebPay");
                System.out.println("    2. Débito");
                System.out.println("    3. Efectivo");
                int opPago = pedirOpcion("  Seleccione (#)     : ", 1, 3);
                String medioPago = new String[]{"webpay","debito","efectivo"}[opPago - 1];
 
                System.out.println(CYAN + "\n  Procesando pago..." + RESET);
                Boolean pagado = (Boolean) enviarMensaje(
                        HOST_RESERVAS, PUERTO_RESERVAS,
                        new Mensaje(Mensaje.CONFIRMAR_PAGO,
                                reserva.getId(), viaje.getPrecio(), medioPago));
 
                if (Boolean.TRUE.equals(pagado)) {
                    imprimirTicket(viaje, asiento, pasajero, reserva);
                } else {
                    System.out.println(ROJO
                            + "\n  Pago rechazado. Reserva cancelada y asiento liberado.\n" // Recordar que un pago rechazado no debe dejar la reserva ni el asiento bloqueados.
                            + RESET);
                }
 
            } catch (NegocioException e) {
                System.out.println(ROJO + "\n  " + e.getMessage() + "\n" + RESET);
            } catch (IOException e) {
                System.out.println(ROJO
                        + "\n  Error de conexión: " + e.getMessage()
                        + "\n  Verifique que los servidores estén activos.\n" + RESET);
            }
 
            if (!preguntarSiNo("\n  ¿Realizar otra compra? (s/n): ")) break;
            limpiarPantalla();
            imprimirBanner();
        }
 
        System.out.println("\n  Gracias por usar Turbus. ¡Buen viaje!\n");
    }
 
    // Función para mostrar rutas disponibles al iniciar, para orientar al usuario sobre qué ciudades y fechas puede buscar.
    private void mostrarRutasDisponibles() {
        try {
            @SuppressWarnings("unchecked")
            List<String> rutas = (List<String>) enviarMensaje(
                    HOST_BUSQUEDA, PUERTO_BUSQUEDA,
                    new Mensaje(Mensaje.LISTAR_RUTAS));
 
            System.out.println(NEGRITA + "  Rutas y fechas disponibles:" + RESET);
            System.out.println("  " + "─".repeat(42));
            rutas.forEach(r -> System.out.println("    " + r));
            System.out.println("  " + "─".repeat(42));
            System.out.println();
        } catch (Exception e) {
            System.out.println(AMARILLO
                    + "  (No se pudieron cargar las rutas disponibles)" + RESET);
            System.out.println();
        }
    }
 
    // Función para mostrar los viajes encontrados en formato de tabla, con colores y formato para mejorar la legibilidad.
    private void mostrarTablaViajes(List<Viaje> viajes) {
        System.out.println();
        System.out.println(NEGRITA
                + "  #   Salida   Llegada  Tipo        Disponibles  Precio" + RESET);
        System.out.println("  " + "─".repeat(56));
        for (int i = 0; i < viajes.size(); i++) {
            Viaje v = viajes.get(i);
            System.out.printf("  %-3d %-8s %-8s %-11s %-12d $%.0f%n",
                    i + 1, v.getHoraSalida(), v.getHoraLlegada(),
                    v.getTipoBus(), v.getAsientosDisponibles(), v.getPrecio());
        }
        System.out.println();
    }
 
    // Función para mostrar el mapa de asientos de un viaje, con colores para indicar disponibilidad. Se adapta al tipo de bus (saloncama o normal) y muestra los números de asiento.
    private void mostrarMapaAsientos(Map<Integer, Boolean> asientos, Viaje viaje) {
        System.out.println(NEGRITA + "\n  Mapa de asientos — " + viaje.getTipoBus()
                + " (" + viaje.getTotalAsientos() + " asientos)" + RESET);
        System.out.println("  " + VERDE + "[##]" + RESET + " disponible   "
                + ROJO  + "[XX]" + RESET + " ocupado\n");
 
        boolean esSaloncama = "saloncama".equals(viaje.getTipoBus());
        int cols = esSaloncama ? 3 : 4;
        System.out.println(esSaloncama ? "       A    B    C" : "       A    B    C    D");
 
        int total = viaje.getTotalAsientos();
        int filas = (int) Math.ceil((double) total / cols);
        int num = 1;
        for (int fila = 1; fila <= filas; fila++) {
            System.out.printf("  %2d  ", fila);
            for (int col = 0; col < cols && num <= total; col++) {
                if (!esSaloncama && col == 2) System.out.print(" ");
                if (Boolean.TRUE.equals(asientos.get(num)))
                    System.out.printf(VERDE + "[%2d]" + RESET + " ", num);
                else
                    System.out.print(ROJO + "[XX]" + RESET + " ");
                num++;
            }
            System.out.println();
        }
        System.out.println();
    }
 
    // Función para imprimir el ticket final después de una compra exitosa, con formato visual que resalta los datos importantes del viaje, pasajero y reserva.
    private void imprimirTicket(Viaje v, int asiento, Pasajero p, Reserva r) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        System.out.println(VERDE + NEGRITA);
        System.out.println("  ╔══════════════════════════════════════╗");
        System.out.println("  ║       TURBUS — PASAJE EMITIDO        ║");
        System.out.println("  ╠══════════════════════════════════════╣");
        System.out.printf ("  ║  Pasajero : %-25s║%n", p.getNombre() + " " + p.getApellido());
        System.out.printf ("  ║  RUT      : %-25s║%n", p.getRut());
        System.out.println("  ╠══════════════════════════════════════╣");
        System.out.printf ("  ║  Origen   : %-25s║%n", v.getOrigen());
        System.out.printf ("  ║  Destino  : %-25s║%n", v.getDestino());
        System.out.printf ("  ║  Fecha    : %-25s║%n", v.getFecha().format(fmt));
        System.out.printf ("  ║  Salida   : %-25s║%n", v.getHoraSalida());
        System.out.printf ("  ║  Asiento  : %-25d║%n", asiento);
        System.out.printf ("  ║  Tipo bus : %-25s║%n", v.getTipoBus());
        System.out.println("  ╠══════════════════════════════════════╣");
        System.out.printf ("  ║  TOTAL    : $%-24.0f║%n", v.getPrecio());
        System.out.printf ("  ║  Reserva  : %-25s║%n", r.getId().substring(0,8) + "...");
        System.out.println("  ╚══════════════════════════════════════╝");
        System.out.println(RESET);
    }
 
    // Función para imprimir el banner de bienvenida al iniciar la terminal, con colores y formato para hacerlo visualmente atractivo.
    private void imprimirBanner() {
        System.out.println(CYAN + NEGRITA);
        System.out.println("  ╔════════════════════════════════════════╗");
        System.out.println("  ║    TURBUS — Reserva de pasajes         ║");
        System.out.println("  ║    Sistema Distribuido · Puerto 9001   ║");
        System.out.println("  ╚════════════════════════════════════════╝");
        System.out.println(RESET);
    }
 
    // Función para pedir entrada de texto al usuario, con un mensaje de prompt.
    private String pedirTexto(String prompt) {
        System.out.print(prompt);
        return scanner.nextLine().trim();
    }
 
    // Función para pedir una fecha al usuario, con validación de formato. Se repite hasta que se ingrese una fecha válida en formato dd/MM/yyyy.
    private LocalDate pedirFecha(String prompt) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        while (true) {
            System.out.print(prompt);
            try { return LocalDate.parse(scanner.nextLine().trim(), fmt); }
            catch (DateTimeParseException e) {
                System.out.println(ROJO + "  Formato inválido. Use dd/MM/yyyy" + RESET);
            }
        }
    }
 
    // Función para pedir una opción numérica al usuario dentro de un rango, con validación. Se repite hasta que se ingrese un número válido entre min y max.
    private int pedirOpcion(String prompt, int min, int max) {
        while (true) {
            System.out.print(prompt);
            try {
                int v = Integer.parseInt(scanner.nextLine().trim());
                if (v >= min && v <= max) return v;
                System.out.printf(ROJO + "  Ingrese entre %d y %d%n" + RESET, min, max);
            } catch (NumberFormatException e) {
                System.out.println(ROJO + "  Número inválido." + RESET);
            }
        }
    }
 
    private boolean preguntarSiNo(String prompt) {
        System.out.print(prompt);
        return scanner.nextLine().trim().equalsIgnoreCase("s");
    }
 
    private void limpiarPantalla() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }
 
    // Función para enviar un mensaje a través de TCP y recibir una respuesta.
    private Object enviarMensaje(String host, int puerto, Mensaje solicitud)
            throws IOException, NegocioException {
        try (Socket socket = new Socket(host, puerto);
             ObjectOutputStream salida = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream  entrada = new ObjectInputStream(socket.getInputStream())) {
            salida.writeObject(solicitud);
            salida.flush();
            Mensaje r = (Mensaje) entrada.readObject();
            if (r.esOk())           return r.getResultado();
            if (r.esErrorNegocio()) throw new NegocioException(r.getMensajeError());
            throw new IOException("Error servidor: " + r.getMensajeError());
        } catch (ClassNotFoundException e) {
            throw new IOException("Protocolo incompatible: " + e.getMessage());
        }
    }
}
