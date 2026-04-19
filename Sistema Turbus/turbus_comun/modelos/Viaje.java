package turbus_comun.modelos;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalTime;
 
/**
 * DTO que representa un viaje disponible.
 * Debe implementar Serializable para poder viajar por la red en RMI.
 */
public class Viaje implements Serializable {
 
    private static final long serialVersionUID = 1L;
 
    private int id;
    private String origen;
    private String destino;
    private LocalDate fecha;
    private LocalTime horaSalida;
    private LocalTime horaLlegada;
    private double precio;
    private int totalAsientos;
    private int asientosDisponibles;
    private String tipoBus;  // "semicama", "saloncama"
 
    public Viaje() {}
 
    public Viaje(int id, String origen, String destino,
                 LocalDate fecha, LocalTime horaSalida, LocalTime horaLlegada,
                 double precio, int totalAsientos, String tipoBus) {
        this.id = id;
        this.origen = origen;
        this.destino = destino;
        this.fecha = fecha;
        this.horaSalida = horaSalida;
        this.horaLlegada = horaLlegada;
        this.precio = precio;
        this.totalAsientos = totalAsientos;
        this.asientosDisponibles = totalAsientos;
        this.tipoBus = tipoBus;
    }
 
    // Getters y setters para acceder a los atributos del viaje.
    public int getId() { return id; }
    public String getOrigen() { return origen; }
    public String getDestino() { return destino; }
    public LocalDate getFecha() { return fecha; }
    public LocalTime getHoraSalida() { return horaSalida; }
    public LocalTime getHoraLlegada() { return horaLlegada; }
    public double getPrecio() { return precio; }
    public int getTotalAsientos() { return totalAsientos; }
    public int getAsientosDisponibles() { return asientosDisponibles; }
    public void setAsientosDisponibles(int n) { this.asientosDisponibles = n; }
    public String getTipoBus() { return tipoBus; }
 
    @Override
    public String toString() {
        return String.format("[Viaje#%d] %s → %s | %s %s | $%.0f | %d/%d disponibles",
                id, origen, destino, fecha, horaSalida, precio,
                asientosDisponibles, totalAsientos);
    }
}
