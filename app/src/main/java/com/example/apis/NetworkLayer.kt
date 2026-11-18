package com.example.apis

import com.google.gson.Gson
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

// --- DTOs (Separados del dominio y de Room) ---
data class RecordatorioDto(
    val remoteId: String,
    val titulo: String,
    val descripcion: String,
    val categoria: String,
    val prioridad: String,
    val estado: String,
    val timestamp: Long
)

// --- INTERFAZ RETROFIT ---
interface RecordatorioApiService {
    @GET("recordatorios")
    suspend fun obtenerRemotos(): Response<List<RecordatorioDto>>

    @POST("recordatorios")
    suspend fun enviarRecordatorio(@Body dto: RecordatorioDto): Response<RecordatorioDto>
}

// --- SIMULADOR DE SERVIDOR Y RED EN OKHTTP ---
object NetworkSimulatorConfig {
    var modoSinConexion: Boolean = false
    var simularErrorServidor: Boolean = false
    var simularTimeout: Boolean = false
}

class SimulatedBackendInterceptor : Interceptor {
    private val gson = Gson()
    private val almacenamientoNube = mutableMapOf<String, RecordatorioDto>()

    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        Thread.sleep(500) // Simula latencia de red

        if (NetworkSimulatorConfig.modoSinConexion) {
            throw IOException("Sin conexión a internet (Modo Offline activo)")
        }
        if (NetworkSimulatorConfig.simularTimeout) {
            throw SocketTimeoutException("Timeout agotado esperando al servidor")
        }
        if (NetworkSimulatorConfig.simularErrorServidor) {
            return okhttp3.Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Internal Server Error")
                .body("{\"error\":\"Falla interna en servidor\"}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        return when (request.method) {
            "POST" -> {
                val buffer = okio.Buffer()
                request.body?.writeTo(buffer)
                val bodyStr = buffer.readUtf8()
                val dto = gson.fromJson(bodyStr, RecordatorioDto::class.java)
                // Idempotencia: usa remoteId como clave para evitar duplicados
                almacenamientoNube[dto.remoteId] = dto
                okhttp3.Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(gson.toJson(dto).toResponseBody("application/json".toMediaType()))
                    .build()
            }
            "GET" -> {
                val lista = almacenamientoNube.values.toList()
                okhttp3.Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(gson.toJson(lista).toResponseBody("application/json".toMediaType()))
                    .build()
            }
            else -> okhttp3.Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(400)
                .message("Bad Request")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }
    }
}

object RetrofitClient {
    val apiService: RecordatorioApiService by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(SimulatedBackendInterceptor())
            .build()

        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(RecordatorioApiService::class.java)
    }
}