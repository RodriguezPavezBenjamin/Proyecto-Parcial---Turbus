# Sistema Distribuido de Reservas "Turbus" 

**Proyecto Parcial - ICI-4344 Computación Paralela y Distribuida | PUCV**

## Integrantes

* Gerald Espinoza Tapia
* Ignacio Carrillo
* Benjamin Velasquez
* Benjamín Rodríguez Pavez

## El Problema
El sistema de Turbus permite la búsqueda de viajes entre distintas ciudades y gestiona la reserva concurrente de pasajes, permitiendo al usuario escoger un asiento especifico dentro del viaje seleccionado y reservarlo. El desafío principal de este proyecto es desarrollar un sistema distribuido que implemente estas dos funcionalidades principales (búsqueda y reserva), cumpliendo algunos criterios obligatorios para asegurar el correcto funcionamiento del sistema y evitar ciertos problemas que podrían surgir, como condiciones de carrera (que dos sucursales vendan el mismo asiento exacto al mismo tiempo) o que la caída de un nodo cliente no afecte la disponibilidad del servidor central.

## Arquitectura y Solución
Este proyecto implementa los requisitos técnicos solicitados en la asignatura:
* **Comunicación y Marshalling:** Uso de Sockets TCP para conexión remota y Marshalling para el envío de estructuras de datos.
* **Gestión de concurrencia:** Servidor Multi-hilo (un hilo por conexión) con uso de bloques `synchronized` para proteger los recursos compartidos (inventario) o `Locked` para evitar la reserva simúltanea de un mismo objeto (asiento).
* **Tolerancia a Fallos:** Manejo de excepciones por fallos de red (Crash/Omisión) aislando los errores de red de los clientes para mantener el servidor vivo, y por lógica del negocio (asiento ocupado/reserva no encontrada).
* **Transparencia de acceso y ubicación:** El cliente opera sin necesidad de conocer el mecanismo o dónde opera la arquitectura subyacente.

## Como ejecutar...
1. Iniciar el servidor central ejecutando `TurbusServer.java` (escucha en el puerto 5000).
2. Levantar los terminales de venta ejecutando múltiples instancias de `TurbusClient.java`.
3. Ingresar el número de asiento a reservar en las consolas de los clientes.

## Como ejecutar... (carpeta 'Sistema Turbus')
1. Iniciar el servidor de reservas `ReservaServer.java`, ubicado en la carpeta `Sistema Turbus\turbus_reserva`.
2. Iniciar el servidor de búsqueda `BusquedaServer.java`, ubicado en `Sistema Turbus\turbus_busqueda`.
3. Levantar el terminal interactivo de cliente `TerminalInteractivo.java`, ubicado en `Sistema Turbus\turbus_cliente`, y seguir los pasos que se indican en la terminal.
