package com.espe.billetera

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transacciones")
data class TransaccionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val cuentaPropietario: String,
    val tipo: String,
    val detalle: String,
    val comentario: String,
    val monto: Double,
    val fecha: String,
    val esIngreso: Boolean,
    val esServicio: Boolean = false
)

@Dao
interface TransaccionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertar(transaccion: TransaccionEntity)

    @Query("SELECT * FROM transacciones WHERE cuentaPropietario = :cuenta ORDER BY id DESC")
    fun obtenerHistorialPorCuenta(cuenta: String): Flow<List<TransaccionEntity>>
}

@Database(entities = [TransaccionEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transaccionDao(): TransaccionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "banca_p2p_room_db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}