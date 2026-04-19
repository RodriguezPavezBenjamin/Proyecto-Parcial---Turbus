package turbus_comun.modelos;

import java.io.Serializable;

// Clase que representa a un pasajero, con su información personal y métodos para acceder a ella.
public class Pasajero implements Serializable {
 
    private static final long serialVersionUID = 1L;
 
    private String rut;
    private String nombre;
    private String apellido;
    private String email;
 
    public Pasajero() {}
 
    public Pasajero(String rut, String nombre, String apellido, String email) {
        this.rut = rut;
        this.nombre = nombre;
        this.apellido = apellido;
        this.email = email;
    }
 
    // Getters para acceder a los atributos del pasajero.
    public String getRut() { return rut; }
    public String getNombre() { return nombre; }
    public String getApellido() { return apellido; }
    public String getEmail() { return email; }
 
    @Override
    public String toString() {
        return nombre + " " + apellido + " (" + rut + ")";
    }
}
