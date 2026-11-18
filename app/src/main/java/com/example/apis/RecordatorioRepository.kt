package com.example.apis

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID

// --- MODELO DE DOMINIO (Usado por ViewModel y Compose) ---
enum class EstadoSync(val etiqueta: String) {
    PENDIENTE_SYNC("Pendiente de sincronizar"),
    SINCRONIZADO("Sincronizado"),
    ERROR_SYNC("Error al sincronizar")
}

data class Recordatorio(
    val id: Int = 0,
    val remoteId: String = UUID.randomUUID().toString(),
    val titulo: String,
    val descripcion: String,
    val categoria: String,
    val prioridad: String,
    val completado: Boolean = false,
    val fechaCreacion: Long = System.currentTimeMillis(),
    val estadoSync: EstadoSync = EstadoSync.PENDIENTE_SYNC
)

// --- MAPEOS EXPLÍCITOS ---
fun RecordatorioEntity.toDomain(): Recordatorio = Recordatorio(
    id = id,
    remoteId = remoteId,
    titulo = titulo,
    descripcion = descripcion,
    categoria = categoria,
    prioridad = prioridad,
    completado = estado == "Completado",
    fechaCreacion = fechaCreacion,
    estadoSync = try { EstadoSync.valueOf(estadoSync) } catch (e: Exception) { EstadoSync.PENDIENTE_SYNC }
)

fun Recordatorio.toEntity(): RecordatorioEntity = RecordatorioEntity(
    id = id,
    remoteId = remoteId,
    titulo = titulo,
    descripcion = descripcion,
    categoria = categoria,
    prioridad = prioridad,
    estado = if (completado) "Completado" else "Pendiente",
    fechaCreacion = fechaCreacion,
    estadoSync = estadoSync.name
)

fun RecordatorioEntity.toDto(): RecordatorioDto = RecordatorioDto(
    remoteId = remoteId,
    titulo = titulo,
    descripcion = descripcion,
    categoria = categoria,
    prioridad = prioridad,
    estado = estado,
    timestamp = fechaCreacion
)

fun RecordatorioDto.toEntity(): RecordatorioEntity = RecordatorioEntity(
    remoteId = remoteId,
    titulo = titulo,
    descripcion = descripcion,
    categoria = categoria,
    prioridad = prioridad,
    estado = estado,
    fechaCreacion = timestamp,
    estadoSync = EstadoSync.SINCRONIZADO.name
)

// --- RESULTADO DE SINCRONIZACIÓN ---
sealed interface SyncResult {
    object Exito : SyncResult
    data class ErrorRecuperable(val mensaje: String) : SyncResult
}

// --- REPOSITORIO LOCAL-FIRST ---
class RecordatorioRepository(
    private val dao: RecordatorioDao,
    private val api: RecordatorioApiService,
    private val prefsManager: PreferencesManager
) {
    // Mutex evita que dos eventos disparen la sincronización simultáneamente y dupliquen envíos
    private val syncMutex = Mutex()

    val preferenciasFlow: Flow<UserPreferences> = prefsManager.preferencesFlow

    // Room es la única fuente de verdad, combinada con las preferencias de DataStore
    val recordatoriosFlow: Flow<List<Recordatorio>> = combine(
        dao.observarTodos(),
        prefsManager.preferencesFlow
    ) { entidades, prefs ->
        val filtrados = entidades
            .map { it.toDomain() }
            .filter { prefs.mostrarCompletados || !it.completado }

        when (prefs.criterioOrden) {
            CriterioOrden.FECHA -> filtrados.sortedByDescending { it.fechaCreacion }
            CriterioOrden.TITULO -> filtrados.sortedBy { it.titulo.lowercase() }
            CriterioOrden.PRIORIDAD -> {
                val peso = mapOf("Alta" to 1, "Media" to 2, "Baja" to 3)
                filtrados.sortedBy { peso[it.prioridad] ?: 4 }
            }
        }
    }

    fun observarRecordatorio(id: Int): Flow<Recordatorio?> =
        dao.observarPorId(id).combine(prefsManager.preferencesFlow) { entity, _ -> entity?.toDomain() }

    // 1. Guarda primero en Room (offline-first) y luego intenta sincronizar
    suspend fun guardarRecordatorio(recordatorio: Recordatorio): SyncResult {
        val entidadPendiente = recordatorio.copy(estadoSync = EstadoSync.PENDIENTE_SYNC).toEntity()
        if (recordatorio.id == 0) {
            dao.insertar(entidadPendiente)
        } else {
            dao.actualizar(entidadPendiente)
        }
        return sincronizarPendientes()
    }

    suspend fun alternarCompletado(recordatorio: Recordatorio): SyncResult {
        val actualizado = recordatorio.copy(
            completado = !recordatorio.completado,
            estadoSync = EstadoSync.PENDIENTE_SYNC
        )
        dao.actualizar(actualizado.toEntity())
        return sincronizarPendientes()
    }

    suspend fun eliminarRecordatorio(recordatorio: Recordatorio) {
        dao.eliminar(recordatorio.toEntity())
    }

    // 2. Sincronización controlada sin duplicaciones
    suspend fun sincronizarPendientes(): SyncResult = syncMutex.withLock {
        try {
            val pendientes = dao.obtenerPendientesSync()
            for (item in pendientes) {
                val response = api.enviarRecordatorio(item.toDto())
                when {
                    response.isSuccessful -> {
                        dao.actualizar(item.copy(estadoSync = EstadoSync.SINCRONIZADO.name))
                    }
                    response.code() in 400..499 -> {
                        dao.actualizar(item.copy(estadoSync = EstadoSync.ERROR_SYNC.name))
                        return@withLock SyncResult.ErrorRecuperable("Solicitud inválida (HTTP ${response.code()})")
                    }
                    else -> {
                        dao.actualizar(item.copy(estadoSync = EstadoSync.ERROR_SYNC.name))
                        return@withLock SyncResult.ErrorRecuperable("Error del servidor (HTTP ${response.code()})")
                    }
                }
            }

            // Descargar novedades remotas sin duplicar registros existentes
            val remoteResponse = api.obtenerRemotos()
            if (remoteResponse.isSuccessful) {
                remoteResponse.body()?.forEach { dto ->
                    val existente = dao.buscarPorRemoteId(dto.remoteId)
                    if (existente == null) {
                        dao.insertar(dto.toEntity())
                    }
                }
            }
            SyncResult.Exito
        } catch (e: SocketTimeoutException) {
            SyncResult.ErrorRecuperable("Tiempo de espera agotado (Timeout). Datos guardados localmente.")
        } catch (e: IOException) {
            SyncResult.ErrorRecuperable("Sin conexión. Los cambios se guardaron offline y se sincronizarán luego.")
        } catch (e: Exception) {
            SyncResult.ErrorRecuperable("Error inesperado de red: ${e.localizedMessage}")
        }
    }

    suspend fun setTemaOscuro(oscuro: Boolean) = prefsManager.actualizarTemaOscuro(oscuro)
    suspend fun setMostrarCompletados(mostrar: Boolean) = prefsManager.actualizarMostrarCompletados(mostrar)
    suspend fun setCriterioOrden(criterio: CriterioOrden) = prefsManager.actualizarCriterioOrden(criterio)
}