package com.example.apis // Manten tu paquete original aquí

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// --- DOMINIO Y REPOSITORIO ---
sealed class Prioridad(val nivel: String) {
    object Alta : Prioridad("Alta")
    object Media : Prioridad("Media")
    object Baja : Prioridad("Baja")
}

sealed class EstadoRecordatorio(val descripcion: String) {
    object Pendiente : EstadoRecordatorio("Pendiente")
    object Completado : EstadoRecordatorio("Completado")
}

data class Recordatorio(
    val id: Int,
    val titulo: String,
    val descripcion: String,
    val categoria: String,
    val prioridad: Prioridad,
    val estado: EstadoRecordatorio = EstadoRecordatorio.Pendiente
)

interface RecordatorioRepository {
    suspend fun obtenerRecordatorios(): List<Recordatorio>
    suspend fun obtenerPorId(id: Int): Recordatorio?
    suspend fun guardar(recordatorio: Recordatorio)
}

class RecordatorioRepositoryImpl : RecordatorioRepository {
    private val memoria = mutableListOf<Recordatorio>()
    private var contadorId = 1

    override suspend fun obtenerRecordatorios(): List<Recordatorio> {
        delay(800) // Simula asincronía
        return memoria.toList()
    }

    override suspend fun obtenerPorId(id: Int): Recordatorio? {
        return memoria.find { it.id == id }
    }

    override suspend fun guardar(recordatorio: Recordatorio) {
        delay(800)
        memoria.add(recordatorio.copy(id = contadorId++))
    }
}

// --- ESTADOS (UiState) ---
sealed interface ListUiState {
    object Cargando : ListUiState
    object Vacio : ListUiState
    data class Exito(val datos: List<Recordatorio>) : ListUiState
    data class Error(val mensaje: String) : ListUiState
}

data class FormUiState(
    val titulo: String = "",
    val descripcion: String = "",
    val categoria: String = "General",
    val prioridad: Prioridad = Prioridad.Media,
    val cargando: Boolean = false,
    val exito: Boolean = false
) {
    val tituloValido = titulo.trim().length >= 4
    val descValida = descripcion.length <= 200
    val esValido = tituloValido && descValida
}

// --- VIEWMODEL ---
class RecordatoriosViewModel : ViewModel() {
    private val repository: RecordatorioRepository = RecordatorioRepositoryImpl()

    private val _listState = MutableStateFlow<ListUiState>(ListUiState.Cargando)
    val listState = _listState.asStateFlow()

    private val _formState = MutableStateFlow(FormUiState())
    val formState = _formState.asStateFlow()

    init {
        cargarRecordatorios()
    }

    fun cargarRecordatorios(simularError: Boolean = false) {
        viewModelScope.launch {
            _listState.update { ListUiState.Cargando }
            delay(500)
            if (simularError) {
                _listState.update { ListUiState.Error("Error de conexión simulado") }
                return@launch
            }
            val datos = repository.obtenerRecordatorios()
            if (datos.isEmpty()) _listState.update { ListUiState.Vacio }
            else _listState.update { ListUiState.Exito(datos) }
        }
    }

    fun actualizarFormulario(titulo: String, desc: String) {
        _formState.update { it.copy(titulo = titulo, descripcion = desc) }
    }

    fun guardarRecordatorio() {
        if (!_formState.value.esValido) return
        viewModelScope.launch {
            _formState.update { it.copy(cargando = true) }
            repository.guardar(
                Recordatorio(
                    id = 0,
                    titulo = _formState.value.titulo.trim(),
                    descripcion = _formState.value.descripcion,
                    categoria = _formState.value.categoria,
                    prioridad = _formState.value.prioridad
                )
            )
            _formState.update { FormUiState(exito = true) }
            cargarRecordatorios()
        }
    }

    fun resetFormulario() {
        _formState.update { FormUiState() }
    }
}

// --- NAVEGACIÓN Y COMPONENTES ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { AppNavigation() }
        }
    }
}

@Composable
fun AppNavigation(viewModel: RecordatoriosViewModel = viewModel()) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "lista") {
        composable("lista") {
            PantallaLista(
                viewModel = viewModel,
                onCrear = {
                    viewModel.resetFormulario()
                    navController.navigate("crear")
                },
                onDetalle = { id -> navController.navigate("detalle/$id") }
            )
        }
        composable("crear") {
            PantallaCrear(
                viewModel = viewModel,
                onVolver = { navController.popBackStack() }
            )
        }
        composable("detalle/{id}") { navBackStackEntry ->
            val id = navBackStackEntry.arguments?.getString("id")?.toIntOrNull() ?: 0
            PantallaDetalle(id = id, onVolver = { navController.popBackStack() })
        }
    }
}

// --- PANTALLAS ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaLista(viewModel: RecordatoriosViewModel, onCrear: () -> Unit, onDetalle: (Int) -> Unit) {
    val state by viewModel.listState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Recordatorios") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onCrear) { Icon(Icons.Default.Add, "Crear") }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            when (state) {
                is ListUiState.Cargando -> CircularProgressIndicator()
                is ListUiState.Vacio -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No hay recordatorios")
                        Button(onClick = onCrear, modifier = Modifier.padding(top = 8.dp)) { Text("Crear uno") }
                        Button(onClick = { viewModel.cargarRecordatorios(true) }) { Text("Simular Error") }
                    }
                }
                is ListUiState.Error -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text((state as ListUiState.Error).mensaje, color = Color.Red)
                        Button(onClick = { viewModel.cargarRecordatorios() }) { Text("Reintentar") }
                    }
                }
                is ListUiState.Exito -> {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        items((state as ListUiState.Exito).datos) { rec ->
                            CardItem(rec, onClick = { onDetalle(rec.id) })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaCrear(viewModel: RecordatoriosViewModel, onVolver: () -> Unit) {
    val state by viewModel.formState.collectAsStateWithLifecycle()

    LaunchedEffect(state.exito) {
        if (state.exito) onVolver()
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Nuevo Recordatorio") }) }) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = state.titulo,
                onValueChange = { viewModel.actualizarFormulario(it, state.descripcion) },
                label = { Text("Título (Mín. 4 caracteres)") },
                isError = !state.tituloValido && state.titulo.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.descripcion,
                onValueChange = { viewModel.actualizarFormulario(state.titulo, it) },
                label = { Text("Descripción (Máx. 200 caracteres)") },
                isError = !state.descValida,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            Button(
                onClick = { viewModel.guardarRecordatorio() },
                enabled = state.esValido && !state.cargando,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            ) {
                if (state.cargando) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                else Text("Guardar")
            }
            Button(onClick = onVolver, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaDetalle(id: Int, onVolver: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Detalle ID: $id") }) }) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Text("Estás viendo el detalle del recordatorio $id.", style = MaterialTheme.typography.titleLarge)
            Button(onClick = onVolver, modifier = Modifier.padding(top = 16.dp)) { Text("Volver") }
        }
    }
}

@Composable
fun CardItem(rec: Recordatorio, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() }) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(rec.titulo, style = MaterialTheme.typography.titleMedium)
            Text("Categoría: ${rec.categoria} | Prioridad: ${rec.prioridad.nivel}")
            Text("Estado: ${rec.estado.descripcion}")
        }
    }
}