package com.example.apis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// 1. Modelado con Sealed Classes (Consigna 2)
sealed class Prioridad(val nivel: String) {
    object Alta : Prioridad("Alta")
    object Media : Prioridad("Media")
    object Baja : Prioridad("Baja")
}

sealed class Estado(val descripcion: String) {
    object Pendiente : Estado("Pendiente")
    object Completado : Estado("Completado")
}

// 2. Data Class Inmutable (Consigna 2)
data class Recordatorio(
    val id: Int,
    val titulo: String,
    val categoria: String,
    val prioridad: Prioridad,
    val estado: Estado,
    val notasOpcionales: String? = null // Dato opcional
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PantallaRecordatorios()
                }
            }
        }
    }
}

@Composable
fun PantallaRecordatorios() {
    // 3. Uso de Colecciones: Set, Map y List (Consigna 3)
    val categoriasUnicas = setOf("Estudio", "Personal", "Negocio")

    val coloresCategoria = mapOf(
        "Estudio" to Color(0xFF4CAF50), // Verde
        "Personal" to Color(0xFF2196F3), // Azul
        "Negocio" to Color(0xFFFF9800)  // Naranja
    )

    val listaRecordatorios = listOf(
        Recordatorio(1, "Entregar TP de Programación Mobile", "Estudio", Prioridad.Alta, Estado.Pendiente),
        Recordatorio(2, "Renovar Carnet de conducir", "Personal", Prioridad.Media, Estado.Completado, "Lunes 20/10"),
        Recordatorio(3, "Cargado de productos a Instagram", "Negocio", Prioridad.Baja, Estado.Pendiente)
    )

    // Filtrado de ejemplo: solo mostramos los pendientes o los importantes
    val recordatoriosAptos = listaRecordatorios.filter {
        it.estado == Estado.Pendiente || it.prioridad == Prioridad.Media
    }

    // 4. Interfaz con Compose y Material 3 (Consigna 4)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Mis Recordatorios",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        items(recordatoriosAptos) { recordatorio ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = recordatorio.titulo,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val colorCat = coloresCategoria[recordatorio.categoria] ?: Color.Gray
                        Text(
                            text = "Categoría: ${recordatorio.categoria}",
                            color = colorCat,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "| Prioridad: ${recordatorio.prioridad.nivel}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Estado: ${recordatorio.estado.descripcion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (recordatorio.estado == Estado.Completado) Color.Gray else Color.Red
                    )

                    recordatorio.notasOpcionales?.let { notas ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Notas: $notas",
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                }
            }
        }
    }
}