# Sistema Distribuido de Reservas "Turbus" 

**Proyecto Parcial - ICI-4344 Computación Paralela y Distribuida | PUCV**

## Integrantes

* [Nombre Integrante]
* Ignacio Carrillo
* Benjamin Velasquez
* Benjamín Rodríguez Pavez

## El Problema
El sistema gestiona la reserva concurrente de pasajes. El desafío principal es evitar condiciones de carrera (que dos sucursales vendan el mismo asiento exacto al mismo tiempo) y garantizar que la caída de un nodo cliente no afecte la disponibilidad del servidor central.

## Arquitectura y Solución
Este proyecto implementa los requisitos técnicos solicitados en la asignatura:
* **Comunicación:** Uso de Sockets TCP para conexión remota y Marshalling para el envío de estructuras de datos.
* **Concurrencia:** Servidor Multi-hilo (un hilo por conexión) con uso de bloques `synchronized` para proteger los recursos compartidos (inventario).
* **Tolerancia a Fallos:** Manejo de excepciones (Crash/Omisión) aislando los errores de red de los clientes para mantener el servidor vivo.
* **Transparencia:** El cliente opera sin necesidad de conocer la arquitectura subyacente.

## Como ejecutar...
1. Iniciar el servidor central ejecutando `TurbusServer.java` (escucha en el puerto 5000).
2. Levantar los terminales de venta ejecutando múltiples instancias de `TurbusClient.java`.
3. Ingresar el número de asiento a reservar en las consolas de los clientes.