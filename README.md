# Sistema Distribuido de Reservas "Turbus" (parte 2): Arquitectura de Varios Nodos

**Proyecto Parcial - ICI-4344 Computación Paralela y Distribuida | PUCV**

## Integrantes

* Gerald Espinoza Tapia
* Ignacio Carrillo
* Benjamin Velasquez
* Benjamín Rodríguez Pavez

## El Problema y la Evolución del Sistema
El sistema Turbus permite la búsqueda de viajes y la reserva concurrente de pasajes. En la primera fase de este proyecto, la solución consistió en un esquema Cliente-Servidor simple. Sin embargo, para esta segunda etapa (Parte 2), el proyecto ha evolucionado a una **arquitectura distribuida tolerante a fallos**, resolviendo problemas avanzados de concurrencia, elección de líderes y replicación de estado entre múltiples nodos para garantizar la alta disponibilidad.

## Archivos y Estructura del Proyecto
El código se encuentra organizado en el directorio `Sistema Turbus` con las siguientes carpetas principales:
* `turbus_comun`: Contiene los modelos (`Viaje`, `Reserva`, `Pasajero`) y la clase `Mensaje.java`, que define el protocolo de comunicación serializado y ahora incluye **relojes de Lamport**.
* `turbus_nodo`: Contiene `NodoServidor.java`, la evolución unificada de los antiguos servidores separados. Cada instancia funciona de forma independiente y coordina con la red.
* `turbus_cliente`: Contiene el `TerminalInteractivo.java` para usuarios humanos, y el nuevo `LoadTester.java` para realizar pruebas de estrés concurrentes.

## Arquitectura Distribuida (características de la parte 2)
El sistema cumple estrictamente con los siguientes requerimientos distribuidos:
* **1. Topología Multinodo y Transparencia:** ya no existen servidores de búsqueda y reserva por separado. Todos los nodos son réplicas integrales (Nodos 1, 2 y 3). El cliente se conecta aleatoriamente a cualquiera de ellos sin conocer la estructura interna.
* **2. Ordenamiento de Eventos:** cada mensaje en la red transporta una marca de tiempo de Lamport. Los nodos sincronizan y actualizan sus relojes locales (`max(local, remoto) + 1`), logrando un ordenamiento parcial estricto de eventos.
* **3. Algoritmo del Abusón "Bully":** al iniciar, los nodos negocian y eligen un Coordinador (el nodo con mayor ID activo). Si un cliente solicita una reserva a un nodo subordinado, este "reenvía" (Forward) la transacción al Coordinador de manera transparente.
* **4. Replicación y Tolerancia a Fallos:** El Coordinador aprueba las reservas y transmite el estado al resto (`SYNC_RESERVA`). Los nodos ejecutan "Heartbeats" constantes. Si el Coordinador "muere" (crash), un nodo vivo detecta la ausencia e inicia inmediatamente una nueva elección Bully, recuperando la red sin afectar a los clientes.
* **5. Alta Concurrencia:** Arquitectura basada en Thread Pools (`POOL_SIZE = 100`) y colas de red extendidas capaces de soportar miles de conexiones por segundo sin denegación de servicio.

## Cómo Ejecutar y Probar el Sistema (Carpeta 'Sistema Turbus')

> Recomendamos compilar todo el proyecto antes de probar si estás usando la consola. Asegúrate de estar en la carpeta Sistema Turbus:
> `javac -d out turbus_comun/modelos/*.java turbus_comun/protocolo/*.java turbus_nodo/*.java turbus_cliente/*.java`

### Levantar la Red Distribuida
Abre 3 terminales distintas y ejecuta un nodo en cada una:
1. Nodo 1: `java -cp out turbus_nodo.NodoServidor 1 8001`
2. Nodo 2: `java -cp out turbus_nodo.NodoServidor 2 8002`
3. Nodo 3: `java -cp out turbus_nodo.NodoServidor 3 8003` (Este nodo se proclamará Coordinador automáticamente).

### Probar el Cliente Interactivo
Abre una 4ta terminal:
`java -cp out turbus_cliente.TerminalInteractivo`.
Podrás buscar viajes y reservar asientos. En las consolas de los servidores verás cómo se reenvían las peticiones al Coordinador y se replican.

### Probar la Prueba de Carga (Test de Estrés)
Abre una 5ta terminal:
`java -cp out turbus_cliente.LoadTester`.
Esto simulará 50 usuarios concurrentes haciendo búsquedas y reservas durante 60 segundos. Si deseas probar la tolerancia a fallos en vivo, cierra la terminal del Nodo Coordinador mientras el Test de Carga se está ejecutando y observa cómo el sistema elige un nuevo líder y continúa respondiendo.
