package com.imsbooks.songbook.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.imsbooks.songbook.AppContainer
import com.imsbooks.songbook.SongBookApplication

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as SongBookApplication).container

/** ViewModel built from the app container; [key] separates instances per route argument (e.g. book id). */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}
