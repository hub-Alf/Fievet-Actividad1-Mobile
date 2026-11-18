package com.example.apis

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "recordatorios")
data class RecordatorioEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val remoteId: String, // UUID único para evitar duplicados al sincronizar
    val titulo: String,
    val descripcion: String,
    val categoria: String,
    val prioridad: String, // "Alta", "Media", "Baja"
    val estado: String,    // "Pendiente", "Completado"
    val fechaCreacion: Long,
    val estadoSync: String // "PENDIENTE_SYNC", "SINCRONIZADO", "ERROR_SYNC"
)

@Dao
interface RecordatorioDao {
    @Query("SELECT * FROM recordatorios ORDER BY fechaCreacion DESC")
    fun observarTodos(): Flow<List<RecordatorioEntity>>

    @Query("SELECT * FROM recordatorios WHERE prioridad = :prioridad ORDER BY fechaCreacion DESC")
    fun observarPorPrioridad(prioridad: String): Flow<List<RecordatorioEntity>>

    @Query("SELECT * FROM recordatorios WHERE id = :id LIMIT 1")
    fun observarPorId(id: Int): Flow<RecordatorioEntity?>

    @Query("SELECT * FROM recordatorios WHERE remoteId = :remoteId LIMIT 1")
    suspend fun buscarPorRemoteId(remoteId: String): RecordatorioEntity?

    @Query("SELECT * FROM recordatorios WHERE estadoSync != 'SINCRONIZADO'")
    suspend fun obtenerPendientesSync(): List<RecordatorioEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertar(recordatorio: RecordatorioEntity): Long

    @Update
    suspend fun actualizar(recordatorio: RecordatorioEntity)

    @Delete
    suspend fun eliminar(recordatorio: RecordatorioEntity)
}

@Database(entities = [RecordatorioEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordatorioDao(): RecordatorioDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "recordatorios_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}