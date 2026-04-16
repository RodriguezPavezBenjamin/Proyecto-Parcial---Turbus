import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;

public class TurbusServer {
    private static ConcurrentHashMap<Integer, Boolean> asientos = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        int puerto = 5000;
        for (int i = 1; i <= 20; i++) asientos.put(i, true);

        try (ServerSocket serverSocket = new ServerSocket(puerto)) {
            System.out.println("=================================================");
            System.out.println("    SERVIDOR TURBUS INICIADO - PUERTO " + puerto);
            System.out.println("=================================================\n");
            mostrarBus();

            while (true) {
                Socket clienteSocket = serverSocket.accept();
                new Thread(new ClienteHandler(clienteSocket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void mostrarBus() {
        System.out.println("\n[===== BUS TURBUS de 20 ASIENTOS ================]");
        System.out.println("|                                                |");
        System.out.println("|  CABINA DEL PILOTO                  [PUERTA]   |");
        System.out.println("|                                                |");
        
        synchronized (asientos) {
            int fila = 0;
            for (int i = 1; i <= 20; i++) {
                if (i % 4 == 1) {
                    System.out.print("|  ");
                    fila++;
                }
                
                String icono = asientos.get(i) ? "[ ] " : "[X] ";
                System.out.print(icono);
                String numAsiento = String.format("%02d", i);
                System.out.print(numAsiento + "  ");
                
                if (i % 4 == 0) {
                    System.out.println("              |");
                }
            }
        }
        
        System.out.println("|                                                |");
        System.out.println("[================================================]");
        System.out.println("  [ ] = DISPONIBLE       |       [X] = OCUPADO\n");
    }

    static class ClienteHandler implements Runnable {
        private Socket socket;

        public ClienteHandler(Socket socket) { this.socket = socket; }

        @Override
        public void run() {
            try (DataInputStream in = new DataInputStream(socket.getInputStream());
                 DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

                String bienvenida = "========================================\n" +
                                  "    BIENVENIDO A TURBUS\n" +
                                  "  Ingrese nº de asiento a reservar\n" +
                                  "  (1-20):\n" +
                                  "========================================";
                out.writeUTF(bienvenida);
                out.flush();
                
                int asientoDeseado = in.readInt();

                synchronized (asientos) {
                    if (asientoDeseado < 1 || asientoDeseado > 20) {
                        out.writeUTF("[!] ERROR: Asiento inválido. Debe ser entre 1 y 20.");
                        return;
                    }
                    
                    if (asientos.getOrDefault(asientoDeseado, false)) {
                        asientos.put(asientoDeseado, false);
                        String respuesta = "[OK] RESERVA EXITOSA!\n" +
                                         "====================================\n" +
                                         ">>> ASIENTO CONFIRMADO: " + asientoDeseado + "\n" +
                                         "====================================";
                        out.writeUTF(respuesta);
                        System.out.println("[OK] Cliente reservó asiento #" + asientoDeseado);
                    } else {
                        out.writeUTF("[!] ASIENTO OCUPADO\nIntenta con otro asiento disponible.");
                        System.out.println("[!] Intento fallido: asiento #" + asientoDeseado + " ya ocupado");
                    }
                }
                
                mostrarBus();
                
            } catch (IOException e) {
                System.out.println("[*] Desconexión: Cliente se desconectó.");
            }
        }
    }
}