package turbus_comun.modelos;

import java.io.Serializable;
import java.time.LocalDateTime;
 
// Clase que representa una reserva de asiento en un viaje, con su estado y métodos para acceder a la información.
public class Reserva implements Serializable {
 
    private static final long serialVersionUID = 1L;
 
    public enum Estado { PENDIENTE, CONFIRMADA, CANCELADA, EXPIRADA }
 
    private String id;          // ID generado al crear la reserva
    private int viajeId;
    private int numeroAsiento;
    private Pasajero pasajero;
    private Estado estado;
    private LocalDateTime creadaEn;
    private LocalDateTime expiraEn;  // bloqueo temporal de 10 minutos
    private double montoPagado;
 
    public Reserva() {}
 
    public Reserva(String id, int viajeId, int numeroAsiento,
                   Pasajero pasajero, LocalDateTime creadaEn) {
        this.id = id;
        this.viajeId = viajeId;
        this.numeroAsiento = numeroAsiento;
        this.pasajero = pasajero;
        this.estado = Estado.PENDIENTE;
        this.creadaEn = creadaEn;
        this.expiraEn = creadaEn.plusMinutes(10);
    }
 
    // Getters y setters para acceder a los atributos de la reserva.
    public String getId() { return id; }
    public int getViajeId() { return viajeId; }
    public int getNumeroAsiento() { return numeroAsiento; }
    public Pasajero getPasajero() { return pasajero; }
    public Estado getEstado() { return estado; }
    public void setEstado(Estado estado) { this.estado = estado; }
    public LocalDateTime getCreadaEn() { return creadaEn; }
    public LocalDateTime getExpiraEn() { return expiraEn; }
    public double getMontoPagado() { return montoPagado; }
    public void setMontoPagado(double monto) { this.montoPagado = monto; }
 
    @Override
    public String toString() {
        return String.format("[Reserva %s] Viaje#%d Asiento#%d | %s | Estado: %s",
                id.substring(0, 8), viajeId, numeroAsiento, pasajero, estado);
    }
}
