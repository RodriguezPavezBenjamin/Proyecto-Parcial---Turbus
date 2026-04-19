package turbus_comun.protocolo;

/**
 * Excepción para errores de lógica de negocio (asiento ocupado, reserva no encontrada, viaje inexistente).
 *
 * Criterio a cumplir: separar errores de negocio de errores de red permite al cliente tomar decisiones distintas:
 *   - IOException / RemoteException → reintentar conexión
 *   - NegocioException              → mostrar mensaje al usuario, NO reintentar
 */
public class NegocioException extends Exception {
 
    private static final long serialVersionUID = 1L;
 
    public NegocioException(String mensaje) {
        super(mensaje);
    }
}
