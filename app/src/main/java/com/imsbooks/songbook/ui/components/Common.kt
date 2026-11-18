package com.imsbooks.songbook.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.imsbooks.songbook.SongBookApplication
import com.imsbooks.songbook.util.Mobile

/** Book cover (3:4) loaded from the API, with a lettered placeholder when there is none. */
@Composable
fun BookCover(url: String?, title: String, modifier: Modifier = Modifier) {
    val container = (LocalContext.current.applicationContext as SongBookApplication).container
    val absolute = container.absoluteUrl(url)
    Box(
        modifier
            .aspectRatio(3f / 4f)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        if (absolute == null) {
            CoverPlaceholder(title)
        } else {
            SubcomposeAsyncImage(
                model = absolute,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = { CoverPlaceholder(title) },
                loading = { Box(Modifier.fillMaxSize()) },
            )
        }
    }
}

@Composable
private fun CoverPlaceholder(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            title.trim().take(1).uppercase(),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Error message with an optional retry, used for every failed load. */
@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
        if (onRetry != null) Button(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
fun EmptyState(title: String, body: String?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** Inline message box (info or error), like MUI's Alert. */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, isError: Boolean = false) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun MobileField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Mobile number",
    showError: Boolean = false,
    serverError: String? = null,
    imeAction: ImeAction = ImeAction.Next,
) {
    val invalid = showError && Mobile.normalize(value) == null
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(25)) },
        label = { Text(label) },
        singleLine = true,
        isError = invalid || serverError != null,
        supportingText = {
            Text(serverError ?: if (invalid) "Enter a valid mobile number." else Mobile.HELP)
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = imeAction),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Next,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(128)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        trailingIcon = {
            TextButton(
                onClick = { visible = !visible },
                modifier = Modifier.semantics { contentDescription = if (visible) "Hide password" else "Show password" },
            ) { Text(if (visible) "Hide" else "Show") }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/** Material "download" glyph (not part of material-icons-core). */
val DownloadIcon: ImageVector by lazy {
    materialIcon(name = "Filled.Download") {
        materialPath {
            moveTo(19f, 9f); horizontalLineToRelative(-4f); verticalLineTo(3f); horizontalLineTo(9f)
            verticalLineToRelative(6f); horizontalLineTo(5f); lineToRelative(7f, 7f); lineToRelative(7f, -7f); close()
            moveTo(5f, 18f); verticalLineToRelative(2f); horizontalLineToRelative(14f); verticalLineToRelative(-2f)
            horizontalLineTo(5f); close()
        }
    }
}

/** Material "menu_book"-style open book glyph. */
val BookIcon: ImageVector by lazy {
    materialIcon(name = "Filled.Book") {
        materialPath {
            moveTo(21f, 5f); curveToRelative(-1.11f, -0.35f, -2.33f, -0.5f, -3.5f, -0.5f)
            curveToRelative(-1.95f, 0f, -4.05f, 0.4f, -5.5f, 1.5f)
            curveToRelative(-1.45f, -1.1f, -3.55f, -1.5f, -5.5f, -1.5f)
            reflectiveCurveTo(2.45f, 4.9f, 1f, 6f); verticalLineToRelative(14.65f)
            curveToRelative(1.45f, -1.1f, 3.55f, -1.5f, 5.5f, -1.5f)
            reflectiveCurveToRelative(4.05f, 0.4f, 5.5f, 1.5f)
            curveToRelative(1.45f, -1.1f, 3.55f, -1.5f, 5.5f, -1.5f)
            curveToRelative(1.17f, 0f, 2.39f, 0.15f, 3.5f, 0.5f)
            curveToRelative(0.75f, 0.25f, 1.4f, 0.55f, 2f, 1f); verticalLineTo(6f)
            curveToRelative(-0.6f, -0.45f, -1.25f, -0.75f, -2f, -1f); close()
            moveTo(21f, 18.5f); curveToRelative(-1.1f, -0.35f, -2.3f, -0.5f, -3.5f, -0.5f)
            curveToRelative(-1.7f, 0f, -4.15f, 0.65f, -5.5f, 1.5f); verticalLineTo(8f)
            curveToRelative(1.35f, -0.85f, 3.8f, -1.5f, 5.5f, -1.5f)
            curveToRelative(1.2f, 0f, 2.4f, 0.15f, 3.5f, 0.5f); verticalLineToRelative(11.5f); close()
        }
    }
}
