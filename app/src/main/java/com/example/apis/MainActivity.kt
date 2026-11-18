package com.example.apis

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// --- ESTADOS DE UI ---
data class MainUiState(
    val recordatorios: List<Recordatorio> = emptyList(),
    val preferencias: UserPreferences = UserPreferences(),
    val sincronizando: Boolean = false,
    val mensajeEstadoRed: String? = null,
    val modoOfflineSimulado: Boolean = false,
    val simularErrorHttp: Boolean = false
)

data class FormUiState(
    val titulo: String = "",
    val descripcion: String = "",
    val categoria: String = "Estudio",
    val prioridad: String = "Media",
    val guardando: Boolean = false,
    val guardadoExitoso: Boolean = false
) {
    val tituloValido = titulo.trim().length >= 4
    val descValida = descripcion.length <= 200
    val esValido = tituloValido && descValida
}

// --- VIEWMODEL ---
class RecordatoriosViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: RecordatorioRepository

    init {
        val db = AppDatabase.getInstance(application)
        val prefs = PreferencesManager(application)
        repository = RecordatorioRepository(db.recordatorioDao(), RetrofitClient.apiService, prefs)
    }

    private val _syncStatus = MutableStateFlow<Pair<Boolean, String?>>(false to null)
    private val _networkFlags = MutableStateFlow(false to false) // offline, errorHttp

    val uiState: StateFlow<MainUiState> = combine(
        repository.recordatoriosFlow,
        repository.preferenciasFlow,
        _syncStatus,
        _networkFlags
    ) { lista, prefs, sync, flags ->
        MainUiState(
            recordatorios = lista,
            preferencias = prefs,
            sincronizando = sync.first,
            mensajeEstadoRed = sync.second,
            modoOfflineSimulado = flags.first,
            simularErrorHttp = flags.second
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MainUiState())

    private val _formState = MutableStateFlow(FormUiState())
    val formState: StateFlow<FormUiState> = _formState.asStateFlow()

    init {
        sincronizar()
    }

    fun observarDetalle(id: Int): Flow<Recordatorio?> = repository.observarRecordatorio(id)

    fun actualizarFormulario(titulo: String, desc: String, cat: String, prio: String) {
        _formState.update { it.copy(titulo = titulo, descripcion = desc, categoria = cat, prioridad = prio) }
    }

    fun resetFormulario() {
        _formState.update { FormUiState() }
    }

    fun guardarRecordatorio() {
        val actual = _formState.value
        if (!actual.esValido) return
        viewModelScope.launch {
            _formState.update { it.copy(guardando = true) }
            val resultado = repository.guardarRecordatorio(
                Recordatorio(
                    titulo = actual.titulo.trim(),
                    descripcion = actual.descripcion.trim(),
                    categoria = actual.categoria,
                    prioridad = actual.prioridad
                )
            )
            procesarResultadoSync(resultado)
            _formState.update { FormUiState(guardadoExitoso = true) }
        }
    }

    fun alternarCompletado(rec: Recordatorio) {
        viewModelScope.launch {
            val resultado = repository.alternarCompletado(rec)
            procesarResultadoSync(resultado)
        }
    }

    fun eliminarRecordatorio(rec: Recordatorio) {
        viewModelScope.launch {
            repository.eliminarRecordatorio(rec)
        }
    }

    fun sincronizar() {
        viewModelScope.launch {
            _syncStatus.value = true to null
            val resultado = repository.sincronizarPendientes()
            procesarResultadoSync(resultado)
        }
    }

    private fun procesarResultadoSync(resultado: SyncResult) {
        _syncStatus.value = when (resultado) {
            is SyncResult.Exito -> false to "Sincronizado con el servidor"
            is SyncResult.ErrorRecuperable -> false to resultado.mensaje
        }
    }

    // Controles de simulación de red
    fun toggleModoOffline(offline: Boolean) {
        NetworkSimulatorConfig.modoSinConexion = offline
        _networkFlags.update { offline to it.second }
        if (!offline) sincronizar() // Al volver la conexión, sincroniza pendientes
    }

    fun toggleErrorServidor(error: Boolean) {
        NetworkSimulatorConfig.simularErrorServidor = error
        _networkFlags.update { it.first to error }
    }

    // Preferencias DataStore
    fun cambiarTemaOscuro(oscuro: Boolean) = viewModelScope.launch { repository.setTemaOscuro(oscuro) }
    fun cambiarMostrarCompletados(mostrar: Boolean) = viewModelScope.launch { repository.setMostrarCompletados(mostrar) }
    fun cambiarOrden(criterio: CriterioOrden) = viewModelScope.launch { repository.setCriterioOrden(criterio) }
}

// --- ACTIVITY Y TEMA DINÁMICO ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: RecordatoriosViewModel = viewModel()
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val colorScheme = if (state.preferencias.temaOscuro) darkColorScheme() else lightColorScheme()

            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation(viewModel)
                }
            }
        }
    }
}

@Composable
fun AppNavigation(viewModel: RecordatoriosViewModel) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "lista") {
        composable("lista") {
            PantallaLista(
                viewModel = viewModel,
                onCrear = {
                    viewModel.resetFormulario()
                    navController.navigate("crear")
                },
                onDetalle = { id -> navController.navigate("detalle/$id") },
                onAjustes = { navController.navigate("ajustes") }
            )
        }
        composable("crear") {
            PantallaCrear(viewModel = viewModel, onVolver = { navController.popBackStack() })
        }
        composable("ajustes") {
            PantallaAjustes(viewModel = viewModel, onVolver = { navController.popBackStack() })
        }
        composable("detalle/{id}") { backStack ->
            val id = backStack.arguments?.getString("id")?.toIntOrNull() ?: 0
            PantallaDetalle(id = id, viewModel = viewModel, onVolver = { navController.popBackStack() })
        }
    }
}

// --- PANTALLA LISTADO ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaLista(
    viewModel: RecordatoriosViewModel,
    onCrear: () -> Unit,
    onDetalle: (Int) -> Unit,
    onAjustes: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recordatorios Local-First") },
                actions = {
                    IconButton(onClick = { viewModel.sincronizar() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sincronizar")
                    }
                    IconButton(onClick = onAjustes) {
                        Icon(Icons.Default.Settings, contentDescription = "Preferencias")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCrear) {
                Icon(Icons.Default.Add, contentDescription = "Nuevo")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Barra de simulación de conectividad para pruebas
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Modo Offline (Sin Red)", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Switch(checked = state.modoOfflineSimulado, onCheckedChange = { viewModel.toggleModoOffline(it) })
                    }
                    state.mensajeEstadoRed?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (msg.contains("Sin conexión") || msg.contains("Error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (state.sincronizando) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (state.recordatorios.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No hay recordatorios guardados en Room.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.recordatorios, key = { it.id }) { rec ->
                        Card(modifier = Modifier.fillMaxWidth().clickable { onDetalle(rec.id) }) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = rec.completado,
                                    onCheckedChange = { viewModel.alternarCompletado(rec) }
                                )
                                Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                    Text(rec.titulo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("${rec.categoria} • Prioridad: ${rec.prioridad}", style = MaterialTheme.typography.bodySmall)
                                    val colorSync = when (rec.estadoSync) {
                                        EstadoSync.SINCRONIZADO -> Color(0xFF2E7D32)
                                        EstadoSync.PENDIENTE_SYNC -> Color(0xFFEF6C00)
                                        EstadoSync.ERROR_SYNC -> Color(0xFFC62828)
                                    }
                                    Text("Estado nube: ${rec.estadoSync.etiqueta}", color = colorSync, style = MaterialTheme.typography.labelSmall)
                                }
                                IconButton(onClick = { viewModel.eliminarRecordatorio(rec) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Eliminar")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- PANTALLA CREAR ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaCrear(viewModel: RecordatoriosViewModel, onVolver: () -> Unit) {
    val state by viewModel.formState.collectAsStateWithLifecycle()
    val categorias = listOf("Estudio", "Personal", "Negocio")
    val prioridades = listOf("Alta", "Media", "Baja")

    LaunchedEffect(state.guardadoExitoso) {
        if (state.guardadoExitoso) onVolver()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nuevo Recordatorio") },
                navigationIcon = {
                    IconButton(onClick = onVolver) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.titulo,
                onValueChange = { viewModel.actualizarFormulario(it, state.descripcion, state.categoria, state.prioridad) },
                label = { Text("Título (Mín. 4 caracteres)") },
                isError = !state.tituloValido && state.titulo.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.descripcion,
                onValueChange = { viewModel.actualizarFormulario(state.titulo, it, state.categoria, state.prioridad) },
                label = { Text("Descripción (Máx. 200 caracteres)") },
                isError = !state.descValida,
                modifier = Modifier.fillMaxWidth()
            )

            Text("Categoría:", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categorias.forEach { cat ->
                    FilterChip(
                        selected = state.categoria == cat,
                        onClick = { viewModel.actualizarFormulario(state.titulo, state.descripcion, cat, state.prioridad) },
                        label = { Text(cat) }
                    )
                }
            }

            Text("Prioridad:", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                prioridades.forEach { prio ->
                    FilterChip(
                        selected = state.prioridad == prio,
                        onClick = { viewModel.actualizarFormulario(state.titulo, state.descripcion, state.categoria, prio) },
                        label = { Text(prio) }
                    )
                }
            }

            Button(
                onClick = { viewModel.guardarRecordatorio() },
                enabled = state.esValido && !state.guardando,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.guardando) "Guardando..." else "Guardar en Room")
            }
        }
    }
}

// --- PANTALLA DETALLE ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaDetalle(id: Int, viewModel: RecordatoriosViewModel, onVolver: () -> Unit) {
    val recordatorio by viewModel.observarDetalle(id).collectAsStateWithLifecycle(initialValue = null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Detalle del Recordatorio") },
                navigationIcon = {
                    IconButton(onClick = onVolver) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (recordatorio == null) {
                Text("Cargando registro desde Room...")
            } else {
                val rec = recordatorio!!
                Text(rec.titulo, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("ID Local (Room): ${rec.id}")
                Text("UUID Remoto: ${rec.remoteId}")
                Text("Descripción: ${rec.descripcion.ifBlank { "Sin descripción" }}")
                Text("Categoría: ${rec.categoria}")
                Text("Prioridad: ${rec.prioridad}")
                Text("Estado: ${if (rec.completado) "Completado" else "Pendiente"}")
                Text("Sincronización: ${rec.estadoSync.etiqueta}")
            }
        }
    }
}

// --- PANTALLA PREFERENCIAS (DATASTORE) ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaAjustes(viewModel: RecordatoriosViewModel, onVolver: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs = state.preferencias

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preferencias (DataStore)") },
                navigationIcon = {
                    IconButton(onClick = onVolver) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Tema Oscuro", modifier = Modifier.weight(1f))
                Switch(checked = prefs.temaOscuro, onCheckedChange = { viewModel.cambiarTemaOscuro(it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Mostrar recordatorios completados", modifier = Modifier.weight(1f))
                Switch(checked = prefs.mostrarCompletados, onCheckedChange = { viewModel.cambiarMostrarCompletados(it) })
            }
            Text("Criterio de ordenamiento:", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CriterioOrden.values().forEach { criterio ->
                    FilterChip(
                        selected = prefs.criterioOrden == criterio,
                        onClick = { viewModel.cambiarOrden(criterio) },
                        label = { Text(criterio.name) }
                    )
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Simular Error HTTP 500 en servidor", modifier = Modifier.weight(1f))
                Switch(checked = state.simularErrorHttp, onCheckedChange = { viewModel.toggleErrorServidor(it) })
            }
        }
    }
}