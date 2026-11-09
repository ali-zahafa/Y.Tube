package com.tensei.ytxtube

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tensei.ytxtube.SearchFilters.DateFilter
import com.tensei.ytxtube.SearchFilters.TypeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Upload date and content type filters for search results. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchFilterSheet(current: SearchFilters, onApply: (SearchFilters) -> Unit, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var draft by remember { mutableStateOf(current) }
    var pickingRange by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 26.dp)) {
            Text("Filters", fontFamily = RoundedHeavy, fontWeight = FontWeight.Bold, fontSize = 20.sp)

            Spacer(Modifier.height(16.dp))
            Text("Upload date", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateFilter.entries.forEach { option ->
                    val label = if (option == DateFilter.Custom && draft.date == DateFilter.Custom && draft.from != null) {
                        "${draft.from} – ${draft.to ?: draft.from}"
                    } else option.label
                    FilterChip(
                        selected = draft.date == option,
                        onClick = {
                            if (option == DateFilter.Custom) pickingRange = true
                            else draft = draft.copy(date = option, from = null, to = null)
                        },
                        label = { Text(label) },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("Type", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TypeFilter.entries.forEach { option ->
                    FilterChip(
                        selected = draft.type == option,
                        onClick = { draft = draft.copy(type = option) },
                        label = { Text(option.label) },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { draft = SearchFilters() }) { Text("Reset") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onApply(draft); onDismiss() },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary),
                ) { Text("Apply", fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (pickingRange) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = draft.from?.toEpochMillis(),
            initialSelectedEndDateMillis = draft.to?.toEpochMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingRange = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val from = state.selectedStartDateMillis?.toLocalDate()
                        val to = state.selectedEndDateMillis?.toLocalDate() ?: from
                        draft = draft.copy(date = DateFilter.Custom, from = from, to = to)
                        pickingRange = false
                    },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingRange = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.weight(1f))
        }
    }
}

private fun LocalDate.toEpochMillis(): Long = atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

private fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
