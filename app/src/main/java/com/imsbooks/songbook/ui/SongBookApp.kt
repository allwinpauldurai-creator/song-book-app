package com.imsbooks.songbook.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.imsbooks.songbook.ui.auth.ForgotPasswordScreen
import com.imsbooks.songbook.ui.auth.LoginScreen
import com.imsbooks.songbook.ui.auth.RegisterScreen
import com.imsbooks.songbook.ui.book.BookDetailScreen
import com.imsbooks.songbook.ui.catalogue.CatalogueScreen
import com.imsbooks.songbook.ui.member.MeScreen
import com.imsbooks.songbook.ui.member.ProfileScreen
import com.imsbooks.songbook.ui.member.RequestsScreen
import com.imsbooks.songbook.ui.reader.ReaderScreen
import kotlinx.serialization.Serializable

// Type-safe routes (web routes in brackets).
@Serializable data object CatalogueRoute                                      // /books
@Serializable data class BookRoute(val id: String)                            // /books/:id
@Serializable data class LoginRoute(val readBookId: String? = null, val notice: String? = null) // /login?next=
@Serializable data object RegisterRoute                                       // /register
@Serializable data object ForgotPasswordRoute                                 // /forgot-password
@Serializable data object MeRoute                                             // /me
@Serializable data object ProfileRoute                                        // /me/profile
@Serializable data class RequestsRoute(val bookId: String? = null, val bookTitle: String? = null) // /me/requests
@Serializable data class ReaderRoute(val bookId: String)                      // /read/:id

private data class Tab(val route: Any, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(CatalogueRoute, "Books", Icons.Filled.Home),
    Tab(MeRoute, "My library", Icons.Filled.Person),
)

@Composable
fun SongBookApp() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val showTabs = destination?.hasRoute<CatalogueRoute>() == true || destination?.hasRoute<MeRoute>() == true

    androidx.compose.material3.Scaffold(
        bottomBar = {
            if (showTabs) {
                NavigationBar {
                    TABS.forEach { tab ->
                        val selected = destination.hasRoute(tab.route::class)
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { outer ->
        Box(Modifier.padding(bottom = outer.calculateBottomPadding()).fillMaxSize()) {
            NavHost(nav, startDestination = CatalogueRoute) {
                composable<CatalogueRoute> {
                    CatalogueScreen(onOpenBook = { nav.navigate(BookRoute(it)) })
                }
                composable<BookRoute> { entry ->
                    val id = entry.toRoute<BookRoute>().id
                    BookDetailScreen(
                        bookId = id,
                        onBack = { nav.popBackStack() },
                        onRead = { nav.navigate(ReaderRoute(it)) },
                        onLogin = { nav.navigate(LoginRoute(readBookId = it)) },
                        onRegister = { nav.navigate(RegisterRoute) },
                        onSuggestCorrection = { bookId, title -> nav.navigate(RequestsRoute(bookId, title)) },
                    )
                }
                composable<LoginRoute> { entry ->
                    val route = entry.toRoute<LoginRoute>()
                    LoginScreen(
                        notice = route.notice,
                        onBack = { nav.popBackStack() },
                        onSignedIn = {
                            nav.popBackStack()
                            route.readBookId?.let { nav.navigate(ReaderRoute(it)) }
                        },
                        onRegister = { nav.navigate(RegisterRoute) { popUpTo<LoginRoute> { inclusive = true } } },
                        onForgotPassword = { nav.navigate(ForgotPasswordRoute) },
                    )
                }
                composable<RegisterRoute> {
                    RegisterScreen(
                        onBack = { nav.popBackStack() },
                        onRegistered = { nav.switchTab(MeRoute) },
                        onLogin = { nav.navigate(LoginRoute()) { popUpTo<RegisterRoute> { inclusive = true } } },
                        onForgotPassword = { nav.navigate(ForgotPasswordRoute) },
                    )
                }
                composable<ForgotPasswordRoute> {
                    ForgotPasswordScreen(
                        onBack = { nav.popBackStack() },
                        onDone = {
                            nav.navigate(LoginRoute(notice = "Your password was changed. Log in with the new password.")) {
                                popUpTo<ForgotPasswordRoute> { inclusive = true }
                            }
                        },
                    )
                }
                composable<MeRoute> {
                    MeScreen(
                        onLogin = { nav.navigate(LoginRoute()) },
                        onRegister = { nav.navigate(RegisterRoute) },
                        onOpenBook = { nav.navigate(BookRoute(it)) },
                        onRead = { nav.navigate(ReaderRoute(it)) },
                        onProfile = { nav.navigate(ProfileRoute) },
                        onRequests = { nav.navigate(RequestsRoute()) },
                    )
                }
                composable<ProfileRoute> {
                    ProfileScreen(
                        onBack = { nav.popBackStack() },
                        onSignedOut = {
                            nav.navigate(LoginRoute(notice = "Your password was changed. Log in again on this device.")) {
                                popUpTo<ProfileRoute> { inclusive = true }
                            }
                        },
                    )
                }
                composable<RequestsRoute> { entry ->
                    val route = entry.toRoute<RequestsRoute>()
                    RequestsScreen(route.bookId, route.bookTitle, onBack = { nav.popBackStack() })
                }
                composable<ReaderRoute> { entry ->
                    ReaderScreen(entry.toRoute<ReaderRoute>().bookId, onBack = { nav.popBackStack() })
                }
            }
        }
    }
}

/** Bottom-tab navigation: single copy of each tab, state saved and restored. */
private fun NavHostController.switchTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
