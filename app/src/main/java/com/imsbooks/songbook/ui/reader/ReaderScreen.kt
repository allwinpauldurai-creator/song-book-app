package com.imsbooks.songbook.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.imsbooks.songbook.ui.appViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** PDF reader (web: /read/:id): swipe or buttons to turn pages, pinch or double-tap to zoom, go to page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookId: String, onBack: () -> Unit) {
    val vm = appViewModel(key = "reader-$bookId") { ReaderViewModel(it, bookId) }
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.error != null -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = vm::open) { Text("Try again") }
                        OutlinedButton(onClick = onBack) { Text("Back") }
                    }
                }
                !state.ready -> Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val p = state.downloadProgress
                    if (p == null || p < 0) {
                        CircularProgressIndicator()
                        Text("Opening book…", Modifier.padding(top = 16.dp))
                    } else {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        Text("Downloading book… ${(p * 100).roundToInt()}%", Modifier.padding(top = 16.dp))
                    }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    state.notice?.let { com.imsbooks.songbook.ui.components.Notice(it, Modifier.padding(8.dp)) }
                    PdfPager(vm, state.pageCount, state.initialPage)
                }
            }
        }
    }
}

@Composable
private fun PdfPager(vm: ReaderViewModel, pageCount: Int, initialPage: Int) {
    val pager = rememberPagerState(initialPage = initialPage) { pageCount }
    val scope = rememberCoroutineScope()
    var zoomed by remember { mutableStateOf(false) }
    var goToOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { vm.onPageSettled(it) }
    }

    Column(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF2B2B2B)),
        ) { index ->
            ZoomablePage(vm, index, isCurrent = index == pager.currentPage, onZoomChange = { zoomed = it })
        }
        BottomAppBar(Modifier.navigationBarsPadding()) {
            IconButton(
                onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                enabled = pager.currentPage > 0,
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous page") }
            if (pageCount > 1) {
                var sliding by remember { mutableFloatStateOf(-1f) }
                Slider(
                    value = if (sliding >= 0) sliding else pager.currentPage.toFloat(),
                    onValueChange = { sliding = it },
                    onValueChangeFinished = {
                        val target = sliding.roundToInt()
                        sliding = -1f
                        scope.launch { pager.scrollToPage(target) }
                    },
                    valueRange = 0f..(pageCount - 1).toFloat(),
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Page slider" },
                )
            } else {
                Box(Modifier.weight(1f))
            }
            TextButton(onClick = { goToOpen = true }, modifier = Modifier.width(96.dp)) {
                Text("${pager.currentPage + 1} / $pageCount")
            }
            IconButton(
                onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                enabled = pager.currentPage < pageCount - 1,
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next page") }
        }
    }

    if (goToOpen) {
        GoToPageDialog(pageCount, onDismiss = { goToOpen = false }) { page ->
            goToOpen = false
            scope.launch { pager.scrollToPage(page - 1) }
        }
    }
}

@Composable
private fun ZoomablePage(vm: ReaderViewModel, index: Int, isCurrent: Boolean, onZoomChange: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Reset zoom when the page is swiped away.
    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        // Render at 2x the screen width so zooming in stays sharp.
        val widthPx = with(density) { maxWidth.toPx() }.toInt() * 2
        val bitmap by produceState<Bitmap?>(null, index, widthPx) { value = vm.renderPage(index, widthPx) }

        val transform = rememberTransformableState { _, zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 5f)
            offset = if (scale == 1f) Offset.Zero else clampOffset(offset + panChange, scale, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
            if (isCurrent) onZoomChange(scale > 1f)
        }

        val b = bitmap
        if (b == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { tap ->
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                                val center = Offset(size.width / 2f, size.height / 2f)
                                offset = clampOffset((center - tap) * 1.5f, scale, size.width.toFloat(), size.height.toFloat())
                            }
                            onZoomChange(scale > 1f)
                        })
                    }
                    .transformable(transform, canPan = { scale > 1f })
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}

private fun clampOffset(offset: Offset, scale: Float, width: Float, height: Float): Offset {
    val maxX = (width * (scale - 1f)) / 2f
    val maxY = (height * (scale - 1f)) / 2f
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

@Composable
private fun GoToPageDialog(pageCount: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val page = text.toIntOrNull()
    val valid = page != null && page in 1..pageCount
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() }.take(5) },
                label = { Text("Page (1–$pageCount)") },
                singleLine = true,
                isError = text.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            )
        },
        confirmButton = { TextButton(onClick = { if (valid) onGo(page) }, enabled = valid) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
