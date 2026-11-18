package com.example.apis

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ajustes_recordatorios")

enum class CriterioOrden { FECHA, PRIORIDAD, TITULO }

data class UserPreferences(
    val temaOscuro: Boolean = false,
    val mostrarCompletados: Boolean = true,
    val criterioOrden: CriterioOrden = CriterioOrden.FECHA
)

class PreferencesManager(private val context: Context) {

    companion object {
        val KEY_TEMA_OSCURO = booleanPreferencesKey("tema_oscuro")
        val KEY_MOSTRAR_COMPLETADOS = booleanPreferencesKey("mostrar_completados")
        val KEY_CRITERIO_ORDEN = stringPreferencesKey("criterio_orden")
    }

    val preferencesFlow: Flow<UserPreferences> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences())
            else throw exception
        }
        .map { prefs ->
            val ordenStr = prefs[KEY_CRITERIO_ORDEN] ?: CriterioOrden.FECHA.name
            val ordenValido = try {
                CriterioOrden.valueOf(ordenStr)
            } catch (e: IllegalArgumentException) {
                CriterioOrden.FECHA // Valor predeterminado ante dato inválido
            }
            UserPreferences(
                temaOscuro = prefs[KEY_TEMA_OSCURO] ?: false,
                mostrarCompletados = prefs[KEY_MOSTRAR_COMPLETADOS] ?: true,
                criterioOrden = ordenValido
            )
        }

    suspend fun actualizarTemaOscuro(oscuro: Boolean) {
        context.dataStore.edit { it[KEY_TEMA_OSCURO] = oscuro }
    }

    suspend fun actualizarMostrarCompletados(mostrar: Boolean) {
        context.dataStore.edit { it[KEY_MOSTRAR_COMPLETADOS] = mostrar }
    }

    suspend fun actualizarCriterioOrden(criterio: CriterioOrden) {
        context.dataStore.edit { it[KEY_CRITERIO_ORDEN] = criterio.name }
    }
}