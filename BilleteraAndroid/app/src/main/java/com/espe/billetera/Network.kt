package com.espe.billetera

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

data class UsuarioDto(
    val cuenta: String,
    val usuario: String,
    val nombre: String,
    val saldo: Double
)

data class AuthRequest(
    val identificador: String? = null,
    val usuario: String? = null,
    val nombre: String? = null,
    val pin: String,
    val saldoInicial: Double? = 500.0
)

data class AuthResponse(
    val status: String?,
    val usuario: UsuarioDto?,
    val error: String?
)

data class VerificarCuentaResponse(
    val existe: Boolean,
    val cuenta: String?,
    val nombre: String?,
    val error: String?
)

data class OperacionRequest(
    val cuenta: String? = null,
    val cuentaOrigen: String? = null,
    val cuentaDestino: String? = null,
    val contrato: String? = null,
    val monto: Double,
    val comentario: String? = ""
)

data class OperacionResponse(
    val status: String?,
    val saldo: Double?,
    val mensaje: String?,
    val error: String?
)

interface WalletApiService {
    @POST("api/login")
    suspend fun login(@Body req: AuthRequest): Response<AuthResponse>

    @POST("api/registro")
    suspend fun registro(@Body req: AuthRequest): Response<AuthResponse>

    @GET("api/cuenta/{numero}")
    suspend fun verificarCuenta(@Path("numero") numero: String): Response<VerificarCuentaResponse>

    @POST("api/deposito")
    suspend fun depositar(@Body req: OperacionRequest): Response<OperacionResponse>

    @POST("api/retiro")
    suspend fun retirar(@Body req: OperacionRequest): Response<OperacionResponse>

    @POST("api/transferir")
    suspend fun transferir(@Body req: OperacionRequest): Response<OperacionResponse>

    @POST("api/servicios")
    suspend fun pagarServicio(@Body req: OperacionRequest): Response<OperacionResponse>

    companion object {
        fun create(ipServidor: String): WalletApiService {
            return Retrofit.Builder()
                .baseUrl("http://$ipServidor/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(WalletApiService::class.java)
        }
    }
}