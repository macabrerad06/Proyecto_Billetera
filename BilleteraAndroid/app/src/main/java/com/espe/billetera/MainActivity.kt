package com.espe.billetera

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

val BlackBg = Color(0xFF09090B)
val CardDark = Color(0xFF141418)
val BorderDark = Color(0xFF27272A)
val GoldAccent = Color(0xFFFBBF24)
val GreenIn = Color(0xFF22C55E)
val RedOut = Color(0xFFEF4444)

@OptIn(ExperimentalCoroutinesApi::class)
class WalletViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("banca_prefs", Context.MODE_PRIVATE)
    private val dao = AppDatabase.getDatabase(application).transaccionDao()

    private val _usuarioActual = MutableStateFlow<UsuarioDto?>(null)
    val usuarioActual = _usuarioActual.asStateFlow()

    val historial: Flow<List<TransaccionEntity>> = _usuarioActual.flatMapLatest { user ->
        if (user == null) flowOf(emptyList())
        else dao.obtenerHistorialPorCuenta(user.cuenta)
    }

    private val _wsConectado = MutableStateFlow(false)
    val wsConectado = _wsConectado.asStateFlow()

    private val _ipServidor = MutableStateFlow(prefs.getString("ip_servidor", "192.168.100.15:3000") ?: "192.168.100.15:3000")
    val ipServidor = _ipServidor.asStateFlow()

    private var webSocket: WebSocket? = null
    private val okHttpClient = OkHttpClient()

    fun cambiarIpServidor(nuevaIp: String) {
        val limpia = nuevaIp.trim().removePrefix("http://").removePrefix("ws://").removeSuffix("/")
        prefs.edit().putString("ip_servidor", limpia).apply()
        _ipServidor.value = limpia
        if (_usuarioActual.value != null) conectarWebSocket()
    }

    fun login(identificador: String, pin: String, onMsg: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val api = WalletApiService.create(_ipServidor.value)
                val res = api.login(AuthRequest(identificador = identificador, pin = pin))
                if (res.isSuccessful && res.body()?.usuario != null) {
                    withContext(Dispatchers.Main) {
                        _usuarioActual.value = res.body()!!.usuario
                        conectarWebSocket()
                    }
                } else {
                    withContext(Dispatchers.Main) { onMsg("Usuario/Cuenta o PIN incorrectos") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onMsg("Sin conexión al servidor ${_ipServidor.value}") }
            }
        }
    }

    fun registrar(nombre: String, usuario: String, pin: String, saldoIni: Double, onMsg: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val api = WalletApiService.create(_ipServidor.value)
                val res = api.registro(AuthRequest(nombre = nombre, usuario = usuario, pin = pin, saldoInicial = saldoIni))
                if (res.isSuccessful && res.body()?.usuario != null) {
                    val nuevo = res.body()!!.usuario!!
                    withContext(Dispatchers.Main) {
                        _usuarioActual.value = nuevo
                        onMsg("Cuenta creada exitosamente: Nro. ${nuevo.cuenta}")
                        conectarWebSocket()
                    }
                } else {
                    withContext(Dispatchers.Main) { onMsg("El usuario ya existe o faltan datos") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onMsg("Error de red con ${_ipServidor.value}") }
            }
        }
    }

    fun cerrarSesion() {
        webSocket?.cancel()
        _wsConectado.value = false
        _usuarioActual.value = null
    }

    fun verificarCuentaDestino(cuentaDestino: String, onResult: (Boolean, String) -> Unit) {
        val miCuenta = _usuarioActual.value?.cuenta ?: ""
        if (cuentaDestino == miCuenta) {
            onResult(false, "No puedes transferir a tu propia cuenta")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val api = WalletApiService.create(_ipServidor.value)
                val res = api.verificarCuenta(cuentaDestino)
                withContext(Dispatchers.Main) {
                    if (res.isSuccessful && res.body()?.existe == true) {
                        onResult(true, res.body()!!.nombre ?: "Titular Verificado")
                    } else {
                        onResult(false, "Esta cuenta bancaria NO existe")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, "Error al verificar la cuenta") }
            }
        }
    }

    fun conectarWebSocket() {
        val user = _usuarioActual.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            webSocket?.cancel()
            val request = Request.Builder().url("ws://${_ipServidor.value}").build()
            webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    ws.send(JSONObject().put("tipo", "SUSCRIBIR").put("cuenta", user.cuenta).toString())
                    viewModelScope.launch(Dispatchers.Main) { _wsConectado.value = true }
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    val json = JSONObject(text)
                    if (json.optString("tipo") == "TRANSFERENCIA_RECIBIDA") {
                        val ctaDest = json.optString("cuentaDestino")
                        val actual = _usuarioActual.value
                        if (actual != null && ctaDest == actual.cuenta) {
                            val monto = json.optDouble("monto", 0.0)
                            val saldoNuevo = json.optDouble("saldoNuevo", actual.saldo + monto)
                            val emisor = json.optString("nombreEmisor", "Remitente")
                            val ctaEmi = json.optString("cuentaEmisor", "Externa")
                            val coment = json.optString("comentario", "Transferencia P2P")

                            viewModelScope.launch(Dispatchers.IO) {
                                dao.insertar(
                                    TransaccionEntity(
                                        cuentaPropietario = actual.cuenta,
                                        tipo = "Transferencia Recibida",
                                        detalle = "De: $emisor (Cta. $ctaEmi)",
                                        comentario = coment,
                                        monto = monto,
                                        fecha = fechaActual(),
                                        esIngreso = true
                                    )
                                )
                                withContext(Dispatchers.Main) {
                                    _usuarioActual.value = actual.copy(saldo = saldoNuevo)
                                }
                            }
                        }
                    }
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    viewModelScope.launch(Dispatchers.Main) { _wsConectado.value = false }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    viewModelScope.launch(Dispatchers.Main) { _wsConectado.value = false }
                }
            })
        }
    }

    fun ejecutarOperacion(
        accion: String,
        monto: Double,
        cuentaDest: String,
        nombreDest: String,
        contrato: String,
        comentario: String,
        onMensaje: (String) -> Unit
    ) {
        val user = _usuarioActual.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val api = WalletApiService.create(_ipServidor.value)
                val req = OperacionRequest(
                    cuenta = user.cuenta,
                    cuentaOrigen = user.cuenta,
                    cuentaDestino = cuentaDest,
                    contrato = contrato,
                    monto = monto,
                    comentario = comentario
                )

                val res = when (accion) {
                    "DEPOSITAR" -> api.depositar(req)
                    "RETIRAR" -> api.retirar(req)
                    "TRANSFERIR" -> api.transferir(req)
                    "SERVICIO" -> api.pagarServicio(req)
                    else -> null
                }

                if (res != null && res.isSuccessful && res.body() != null) {
                    val nuevoSaldo = res.body()!!.saldo ?: user.saldo
                    val (tipoTxt, detTxt, esIng, esServ) = when (accion) {
                        "DEPOSITAR" -> Quad("Depósito en Cuenta", "Titular: ${user.nombre}", true, false)
                        "RETIRAR" -> Quad("Retiro de Efectivo", "Retiro sin tarjeta", false, false)
                        "TRANSFERIR" -> Quad("Transferencia Enviada", "Para: $nombreDest (Cta. $cuentaDest)", false, false)
                        else -> Quad("Servicio Básico", "Contrato: $contrato", false, true)
                    }

                    dao.insertar(
                        TransaccionEntity(
                            cuentaPropietario = user.cuenta,
                            tipo = tipoTxt,
                            detalle = detTxt,
                            comentario = comentario.ifEmpty { "Operación procesada" },
                            monto = monto,
                            fecha = fechaActual(),
                            esIngreso = esIng,
                            esServicio = esServ
                        )
                    )

                    withContext(Dispatchers.Main) {
                        _usuarioActual.value = user.copy(saldo = nuevoSaldo)
                        onMensaje(res.body()!!.mensaje ?: "Operación exitosa")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onMensaje("Error: Fondos insuficientes o cuenta inválida")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onMensaje("Error de conexión al servidor")
                }
            }
        }
    }

    private fun fechaActual() = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
}

data class Quad(val t: String, val d: String, val i: Boolean, val s: Boolean)

class MainActivity : ComponentActivity() {
    private val viewModel: WalletViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = BlackBg) {
                    val usuario by viewModel.usuarioActual.collectAsState()
                    if (usuario == null) {
                        AuthScreen(viewModel)
                    } else {
                        BancaDashboardScreen(viewModel, usuario!!)
                    }
                }
            }
        }
    }
}

@Composable
fun AuthScreen(viewModel: WalletViewModel) {
    val context = LocalContext.current
    val ipActual by viewModel.ipServidor.collectAsState()
    var modoRegistro by remember { mutableStateOf(false) }
    var mostrarModalIp by remember { mutableStateOf(false) }

    var identificador by remember { mutableStateOf("") }
    var nombre by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var saldoInicial by remember { mutableStateOf("500") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(CardDark, RoundedCornerShape(14.dp))
                    .border(1.5.dp, GoldAccent, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("B", color = GoldAccent, fontSize = 24.sp, fontWeight = FontWeight.Black)
            }
            IconButton(onClick = { mostrarModalIp = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Configurar IP", tint = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Banca Móvil P2P", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Text("Servidor: $ipActual", color = Color.Gray, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardDark, RoundedCornerShape(12.dp))
                .padding(4.dp)
        ) {
            Button(
                onClick = { modoRegistro = false },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = if (!modoRegistro) BorderDark else Color.Transparent)
            ) { Text("Iniciar Sesión", color = Color.White, fontSize = 12.sp) }

            Button(
                onClick = { modoRegistro = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = if (modoRegistro) BorderDark else Color.Transparent)
            ) { Text("Crear Cuenta", color = Color.White, fontSize = 12.sp) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (modoRegistro) {
            OutlinedTextField(
                value = nombre,
                onValueChange = { nombre = it },
                label = { Text("Nombre y Apellido del Titular", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        OutlinedTextField(
            value = identificador,
            onValueChange = { identificador = it },
            label = { Text(if (modoRegistro) "Nombre de Usuario" else "Usuario o Nro. de Cuenta", color = Color.Gray) },
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it },
            label = { Text("PIN de Seguridad", color = Color.Gray) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        )

        if (modoRegistro) {
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = saldoInicial,
                onValueChange = { saldoInicial = it },
                label = { Text("Monto de Apertura ($)", color = Color.Gray) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                if (modoRegistro) {
                    viewModel.registrar(nombre, identificador, pin, saldoInicial.toDoubleOrNull() ?: 0.0) {
                        Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                    }
                } else {
                    viewModel.login(identificador, pin) {
                        Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GoldAccent)
        ) {
            Text(
                if (modoRegistro) "Abrir Cuenta Ahora" else "Ingresar a mi Banca",
                color = Color.Black,
                fontWeight = FontWeight.Bold
            )
        }
    }

    if (mostrarModalIp) {
        var nuevaIp by remember { mutableStateOf(ipActual) }
        AlertDialog(
            onDismissRequest = { mostrarModalIp = false },
            title = { Text("Configurar IP del Servidor PC") },
            text = {
                OutlinedTextField(
                    value = nuevaIp,
                    onValueChange = { nuevaIp = it },
                    label = { Text("IP:Puerto (ej. 192.168.100.15:3000)") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.cambiarIpServidor(nuevaIp)
                    mostrarModalIp = false
                }) { Text("Guardar") }
            }
        )
    }
}

@Composable
fun BancaDashboardScreen(viewModel: WalletViewModel, usuario: UsuarioDto) {
    val context = LocalContext.current
    val conectado by viewModel.wsConectado.collectAsState()
    val historial by viewModel.historial.collectAsState(initial = emptyList())
    var modalAccion by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(CardDark, CircleShape)
                        .border(1.5.dp, GoldAccent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(usuario.nombre.take(2).uppercase(), color = GoldAccent, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("Bienvenido(a)", color = Color.Gray, fontSize = 11.sp)
                    Text(usuario.nombre, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(if (conectado) GreenIn else RedOut, CircleShape)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(if (conectado) "En línea" else "Desconectado", color = Color.White, fontSize = 11.sp)
                IconButton(onClick = { viewModel.cerrarSesion() }) {
                    Icon(Icons.Default.ExitToApp, contentDescription = "Salir", tint = Color.Gray)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Tarjeta Principal Estilo Pichincha en Negro Profundo
        Card(
            modifier = Modifier.fillMaxWidth().border(1.dp, BorderDark, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardDark)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("CUENTA DE AHORROS", color = GoldAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "Nro. ${usuario.cuenta} 📋",
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("cuenta", usuario.cuenta))
                            Toast.makeText(context, "Cuenta copiada: ${usuario.cuenta}", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text("Saldo disponible", color = Color.Gray, fontSize = 12.sp)
                Text(
                    text = "$${String.format(Locale.US, "%.2f", usuario.saldo)}",
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BotonBanca("Transferir", Icons.Default.Send, Modifier.weight(1f)) { modalAccion = "TRANSFERIR" }
            BotonBanca("Depositar", Icons.Default.AddCircle, Modifier.weight(1f)) { modalAccion = "DEPOSITAR" }
            BotonBanca("Retirar", Icons.Default.RemoveCircle, Modifier.weight(1f)) { modalAccion = "RETIRAR" }
            BotonBanca("Servicios", Icons.Default.Lightbulb, Modifier.weight(1f)) { modalAccion = "SERVICIO" }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text("Últimos movimientos", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(historial) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth().border(1.dp, BorderDark, RoundedCornerShape(14.dp)),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = CardDark)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.tipo, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(item.detalle, color = Color.LightGray, fontSize = 12.sp)
                            Text("💬 \"${item.comentario}\"", color = GoldAccent, fontSize = 11.sp, fontStyle = FontStyle.Italic)
                            Text(item.fecha, color = Color.Gray, fontSize = 10.sp)
                        }
                        Text(
                            text = (if (item.esIngreso) "+$" else "-$") + String.format(Locale.US, "%.2f", item.monto),
                            color = if (item.esIngreso) GreenIn else RedOut,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }

    if (modalAccion != null) {
        var cuentaDest by remember { mutableStateOf("") }
        var contrato by remember { mutableStateOf("") }
        var montoTxt by remember { mutableStateOf("") }
        var comentario by remember { mutableStateOf("") }
        var cuentaValida by remember { mutableStateOf(false) }
        var infoTitular by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { modalAccion = null },
            containerColor = CardDark,
            title = { Text("Operación: $modalAccion", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    if (modalAccion == "TRANSFERIR") {
                        OutlinedTextField(
                            value = cuentaDest,
                            onValueChange = {
                                cuentaDest = it
                                if (it.length >= 6) {
                                    viewModel.verificarCuentaDestino(it) { ok, nombre ->
                                        cuentaValida = ok
                                        infoTitular = nombre
                                    }
                                } else {
                                    cuentaValida = false
                                    infoTitular = ""
                                }
                            },
                            label = { Text("Nro. Cuenta Destino (10 dígitos)", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (infoTitular.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (cuentaValida) "✅ Titular: $infoTitular" else "❌ $infoTitular",
                                color = if (cuentaValida) GreenIn else RedOut,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (modalAccion == "SERVICIO") {
                        OutlinedTextField(
                            value = contrato,
                            onValueChange = { contrato = it },
                            label = { Text("Empresa y Código de Contrato", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    OutlinedTextField(
                        value = montoTxt,
                        onValueChange = { montoTxt = it },
                        label = { Text("Monto ($)", color = Color.Gray) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = comentario,
                        onValueChange = { comentario = it },
                        label = { Text("Comentario / Motivo", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val m = montoTxt.toDoubleOrNull() ?: 0.0
                        if (m <= 0) return@Button
                        if (modalAccion == "TRANSFERIR" && !cuentaValida) {
                            Toast.makeText(context, "La cuenta destino NO existe", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        viewModel.ejecutarOperacion(modalAccion!!, m, cuentaDest, infoTitular, contrato, comentario) {
                            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                        }
                        modalAccion = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GoldAccent)
                ) { Text("Confirmar", color = Color.Black, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { modalAccion = null }) { Text("Cancelar", color = Color.Gray) }
            }
        )
    }
}

@Composable
fun BotonBanca(
    titulo: String,
    icono: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .background(CardDark, RoundedCornerShape(16.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icono, contentDescription = titulo, tint = GoldAccent, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.height(6.dp))
        Text(titulo, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}