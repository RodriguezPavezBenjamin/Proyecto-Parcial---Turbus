package turbus_comun.protocolo;

import java.io.Serializable;
 
/**
 * Protocolo de comunicación entre cliente y servidor vía Sockets TCP.
 *
 * Usamos un objeto Mensaje serializable que viaja explícitamente por un Socket. Esto cumple los criterios de:
 *   - Criterio 1: la comunicación ocurre a través de Socket/ServerSocket
 *   - Criterio 2: Mensaje implementa Serializable → marshalling explícito
 *
 * Funcionamiento del protocolo básico:
 *   Cliente → envía Mensaje(tipo, parámetros)
 *   Servidor → responde Mensaje(RESPUESTA_OK/ERROR, resultado)
 */
public class Mensaje implements Serializable {
 
    private static final long serialVersionUID = 1L;
 
    // Tipos de operación para BusquedaService (cliente → servidor)
    public static final String BUSCAR_VIAJES       = "BUSCAR_VIAJES";
    public static final String CONSULTAR_ASIENTOS  = "CONSULTAR_ASIENTOS";
    public static final String OBTENER_DESTINOS    = "OBTENER_DESTINOS";
    public static final String PING                = "PING";
    public static final String LISTAR_RUTAS       = "LISTAR_RUTAS";
 
    // Tipos de operación para ReservaService (cliente → servidor) 
    public static final String CREAR_RESERVA       = "CREAR_RESERVA";
    public static final String CONFIRMAR_PAGO      = "CONFIRMAR_PAGO";
    public static final String CANCELAR_RESERVA    = "CANCELAR_RESERVA";
    public static final String CONSULTAR_RESERVA   = "CONSULTAR_RESERVA";
 
    // Tipos de respuesta (servidor → cliente)
    public static final String RESPUESTA_OK        = "OK";
    public static final String RESPUESTA_ERROR     = "ERROR";
    public static final String RESPUESTA_NEGOCIO   = "NEGOCIO"; // error lógico, no de red
 
    // Campos del mensaje 
    private final String tipo;
    private final Object[] parametros;  // argumentos de la operación
    private final Object resultado;     // payload de la respuesta
    private final String mensajeError;  // descripción si tipo=ERROR
 
    // Constructor para REQUEST (cliente → servidor)
    public Mensaje(String tipo, Object... parametros) {
        this.tipo = tipo;
        this.parametros = parametros;
        this.resultado = null;
        this.mensajeError = null;
    }
 
    // Constructor para RESPONSE exitosa (servidor → cliente)
    public static Mensaje ok(Object resultado) {
        return new Mensaje(RESPUESTA_OK, resultado, null);
    }
 
    // Constructor para RESPONSE de error de red/sistema
    public static Mensaje error(String descripcion) {
        return new Mensaje(RESPUESTA_ERROR, null, descripcion);
    }
 
    // Constructor para RESPONSE de error de negocio (asiento ocupado, etc.)
    public static Mensaje errorNegocio(String descripcion) {
        return new Mensaje(RESPUESTA_NEGOCIO, null, descripcion);
    }
 
    // Constructor interno
    private Mensaje(String tipo, Object resultado, String mensajeError) {
        this.tipo = tipo;
        this.resultado = resultado;
        this.parametros = new Object[0];
        this.mensajeError = mensajeError;
    }
 
    // Getters para acceder a los campos del mensaje.
    public String getTipo()           { return tipo; }
    public Object[] getParametros()   { return parametros; }
    public Object getResultado()      { return resultado; }
    public String getMensajeError()   { return mensajeError; }
 
    // Métodos de conveniencia para verificar el tipo de mensaje.
    public boolean esOk()             { return RESPUESTA_OK.equals(tipo); }
    public boolean esErrorRed()       { return RESPUESTA_ERROR.equals(tipo); }
    public boolean esErrorNegocio()   { return RESPUESTA_NEGOCIO.equals(tipo); }
}
