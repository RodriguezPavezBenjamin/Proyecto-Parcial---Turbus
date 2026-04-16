import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.Socket;
import java.util.Scanner;

public class TurbusClient {
    public static void main(String[] args) {
        String host = "localhost";
        int puerto = 5000;

        try (Socket socket = new Socket(host, puerto);
             DataOutputStream out = new DataOutputStream(socket.getOutputStream());
             DataInputStream in = new DataInputStream(socket.getInputStream());
             Scanner sc = new Scanner(System.in)) {

            System.out.println("\n[========================================]");
            System.out.println("[    CLIENTE TURBUS - CONECTANDO...    ]");
            System.out.println("[========================================]\n");

            String mensajeBienvenida = in.readUTF();
            System.out.println(mensajeBienvenida);
            System.out.print("\n> Ingresa tu asiento: ");
            
            int asiento = sc.nextInt();
            out.writeInt(asiento);
            out.flush();
            
            String respuesta = in.readUTF();
            System.out.println("\n" + respuesta + "\n");

        } catch (ConnectException e) {
            System.err.println("\n[!] ERROR: No se puede conectar con el servidor.");
            System.err.println("    Asegúrate de que el servidor esté ejecutándose en localhost:5000\n");
        } catch (IOException e) {
            System.err.println("[!] ERROR DE CONEXIÓN: El servidor no responde.\n");
        }
    }
}