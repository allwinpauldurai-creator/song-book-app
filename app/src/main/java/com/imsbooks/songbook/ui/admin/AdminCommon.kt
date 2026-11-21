package com.imsbooks.songbook.ui.admin

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.imsbooks.songbook.data.ApiException
import com.imsbooks.songbook.data.PageResponse
import com.imsbooks.songbook.data.PickedFile
import com.imsbooks.songbook.data.apiCall
import com.imsbooks.songbook.data.describe
import com.imsbooks.songbook.data.formatBytes
import com.imsbooks.songbook.data.userMessage
import com.imsbooks.songbook.ui.components.ErrorState
import com.imsbooks.songbook.ui.components.LoadingBox
import com.imsbooks.songbook.util.titleCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ---------------------------------------------------------------- errors

/** Same wording as the web admin (adminErrorMessage); falls back to the server's message. */
fun adminErrorMessage(e: Throwable): String = when ((e as? ApiException)?.code) {
    "CONCURRENT_MODIFICATION" -> "Someone else changed this record since you opened it. Reload and try again."
    "LAST_ADMIN" -> "This is the last active administrator. Make another user an admin first."
    "FILE_TOO_LARGE" -> "The file is too large (PDF max 100 MB, cover max 5 MB)."
    else -> if ((e as? ApiException)?.status == 403) "You do not have permission to do this." else e.userMessage()
}

/** Field errors from a 400 response, keyed by field name. */
fun Throwable?.fields(): Map<String, String> = (this as? ApiException)?.fieldMap.orEmpty()

// ---------------------------------------------------------------- paging

data class PagedState<T>(
    val items: List<T> = emptyList(),
    val page: Int = -1,
    val totalPages: Int = 0,
    val totalElements: Long = 0,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val canLoadMore: Boolean get() = !loading && error == null && page + 1 < totalPages
    val initialLoading: Boolean get() = loading && items.isEmpty()
}

/** Infinite list over a paged admin endpoint; [refresh] restarts from page 0 (e.g. after a filter change). */
class PagedLoader<T>(
    private val scope: CoroutineScope,
    private val json: Json,
    private val pageSize: Int = 20,
    private val fetch: suspend (page: Int, size: Int) -> PageResponse<T>,
) {
    private val _state = MutableStateFlow(PagedState<T>())
    val state: StateFlow<PagedState<T>> = _state.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        job = scope.launch {
            try {
                val p = apiCall(json) { fetch(0, pageSize) }
                _state.value = PagedState(p.content, p.page, p.totalPages, p.totalElements)
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = adminErrorMessage(e)) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (!s.canLoadMore) return
        _state.update { it.copy(loading = true) }
        job = scope.launch {
            try {
                val p = apiCall(json) { fetch(s.page + 1, pageSize) }
                _state.update { it.copy(items = it.items + p.content, page = p.page, totalPages = p.totalPages, loading = false) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, error = adminErrorMessage(e)) }
            }
        }
    }
}

/** Adds the rows of a [PagedState] to a LazyColumn, with loading, empty, error and "load more" rows. */
fun <T> LazyListScope.pagedItems(
    state: PagedState<T>,
    emptyText: String,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    key: (T) -> Any,
    row: @Composable (T) -> Unit,
) {
    when {
        state.error != null && state.items.isEmpty() -> item { ErrorState(state.error, onRetry = onRetry) }
        state.initialLoading -> item { LoadingBox() }
        state.items.isEmpty() && state.page >= 0 -> item {
            Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
        }
        else -> {
            items(state.items.size, key = { key(state.items[it]) }) { row(state.items[it]) }
            if (state.loading) item { LoadingBox() }
            if (state.error != null) item { ErrorState(state.error, onRetry = onLoadMore) }
            if (state.canLoadMore) item {
                // Reaching the end loads the next page.
                androidx.compose.runtime.LaunchedEffect(state.page) { onLoadMore() }
            }
        }
    }
}

// ---------------------------------------------------------------- status chips

private val green = Color(0xFF2E7D32)
private val amber = Color(0xFFB26A00)
private val red = Color(0xFFC62828)
private val blue = Color(0xFF1565C0)
private val grey = Color(0xFF616161)

@Composable
fun StatusChip(label: String, color: Color) {
    Surface(shape = MaterialTheme.shapes.small, color = color.copy(alpha = 0.12f), contentColor = color) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
fun AccountChip(status: String) = StatusChip(
    titleCase(status),
    when (status) { "ACTIVE" -> green; "LOCKED" -> red; else -> grey },
)

@Composable
fun BookChip(status: String) = StatusChip(
    titleCase(status),
    when (status) { "PUBLISHED" -> green; "DRAFT" -> blue; "INACTIVE" -> amber; else -> grey },
)

@Composable
fun MembershipChip(status: String, validToday: Boolean? = null) = StatusChip(
    if (validToday == false && status == "ACTIVE") "Active (not in date range)" else titleCase(status),
    when (status) { "ACTIVE" -> if (validToday == false) amber else green; "SUSPENDED" -> amber; else -> grey },
)

@Composable
fun RequestChip(status: String) = StatusChip(
    titleCase(status),
    when (status) { "PENDING" -> amber; "APPROVED" -> green; else -> red },
)

// ---------------------------------------------------------------- form fields

/** Read-only text field that opens a menu; [options] maps value -> label (null value = "any"/"none"). */
@Composable
fun DropdownField(
    label: String,
    value: String?,
    options: List<Pair<String?, String>>,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == value }?.second ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            supportingText = supportingText?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        // Transparent overlay so the whole field opens the menu.
        Box(Modifier.matchParentSize().clickable(enabled = enabled) { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(v) })
            }
        }
    }
}

/** ISO date ("2026-11-19") field with a calendar picker; [value] null/blank = no date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    value: String?,
    onChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    clearable: Boolean = false,
    isError: Boolean = false,
    supportingText: String? = null,
    enabled: Boolean = true,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = value?.let { com.imsbooks.songbook.util.formatDate(it) } ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            singleLine = true,
            isError = isError,
            supportingText = supportingText?.let { { Text(it) } },
            trailingIcon = {
                if (clearable && !value.isNullOrBlank() && enabled) {
                    IconButton(onClick = { onChange(null) }) { Icon(Icons.Filled.Clear, contentDescription = "Clear $label") }
                } else {
                    Icon(Icons.Filled.DateRange, contentDescription = null)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // Leave the trailing icon clickable for "clear".
        Box(Modifier.matchParentSize().padding(end = 48.dp).clickable(enabled = enabled) { open = true })
    }
    if (open) {
        val initial = value?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(picker) }
    }
}

/** "Choose file" row using the system picker; shows the chosen name and size, or [error]. */
@Composable
fun FilePickField(
    label: String,
    mimeTypes: Array<String>,
    file: PickedFile?,
    onChange: (PickedFile?) -> Unit,
    hint: String,
    error: String?,
    enabled: Boolean = true,
) {
    val resolver = LocalContext.current.contentResolver
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onChange(resolver.describe(uri))
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { launcher.launch(mimeTypes) }, enabled = enabled) {
                Text(if (file == null) "Choose file" else "Change")
            }
            if (file != null) {
                Text(
                    "${file.name} · ${formatBytes(file.size.coerceAtLeast(0))}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onChange(null) }, enabled = enabled) {
                    Icon(Icons.Filled.Clear, contentDescription = "Remove $label")
                }
            }
        }
        Text(
            error ?: hint,
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    busy: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body)
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !busy,
                colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
            ) { Text(if (busy) "Working…" else confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

fun todayIso(): String = LocalDate.now().toString()
