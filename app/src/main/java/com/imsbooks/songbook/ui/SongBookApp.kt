package com.imsbooks.songbook.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.imsbooks.songbook.data.session.SessionState
import com.imsbooks.songbook.ui.admin.AdminBooksScreen
import com.imsbooks.songbook.ui.admin.AdminHomeScreen
import com.imsbooks.songbook.ui.admin.AdminNav
import com.imsbooks.songbook.ui.admin.AuditScreen
import com.imsbooks.songbook.ui.admin.BookEditScreen
import com.imsbooks.songbook.ui.admin.CategoriesScreen
import com.imsbooks.songbook.ui.admin.MembershipsScreen
import com.imsbooks.songbook.ui.admin.NewUserScreen
import com.imsbooks.songbook.ui.admin.RequestsReviewScreen
import com.imsbooks.songbook.ui.admin.UserDetailScreen
import com.imsbooks.songbook.ui.admin.UsersScreen
import com.imsbooks.songbook.ui.auth.ForgotPasswordScreen
import com.imsbooks.songbook.ui.auth.LoginScreen
import com.imsbooks.songbook.ui.auth.RegisterScreen
import com.imsbooks.songbook.ui.book.BookDetailScreen
import com.imsbooks.songbook.ui.catalogue.CatalogueScreen
import com.imsbooks.songbook.ui.components.DownloadIcon
import com.imsbooks.songbook.ui.downloads.DownloadsScreen
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
@Serializable data object DownloadsRoute                                      // (app only) offline books
@Serializable data object MeRoute                                             // /me
@Serializable data object ProfileRoute                                        // /me/profile
@Serializable data class RequestsRoute(val bookId: String? = null, val bookTitle: String? = null) // /me/requests
@Serializable data class ReaderRoute(val bookId: String)                      // /read/:id

// Management area (/admin): admins see everything, publishers only their books.
@Serializable data object AdminHomeRoute                                      // /admin
@Serializable data class AdminUsersRoute(val status: String? = null, val role: String? = null) // /admin/users
@Serializable data class AdminUserRoute(val id: String)                       // /admin/users/:id
@Serializable data object AdminNewUserRoute                                   // /admin/users (add dialog)
@Serializable data class AdminMembershipsRoute(val status: String? = null)    // /admin/memberships
@Serializable data class AdminBooksRoute(val status: String? = null)          // /admin/books
@Serializable data class AdminBookRoute(val id: String? = null)               // /admin/books/new, /admin/books/:id
@Serializable data object AdminCategoriesRoute                                // /admin/categories
@Serializable data class AdminRequestsRoute(val status: String? = null)       // /admin/requests
@Serializable data object AdminAuditRoute                                     // /admin/audit

private data class Tab(val route: Any, val label: String, val icon: ImageVector)

private val MEMBER_TABS = listOf(
    Tab(CatalogueRoute, "Books", Icons.Filled.Home),
    Tab(DownloadsRoute, "Downloads", DownloadIcon),
    Tab(MeRoute, "My library", Icons.Filled.Person),
)
private val MANAGE_TAB = Tab(AdminHomeRoute, "Manage", Icons.Filled.Settings)

@Composable
fun SongBookApp() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val session by appContainer().session.state.collectAsStateWithLifecycle()
    val isStaff = (session as? SessionState.SignedIn)?.user?.isStaff == true
    val tabs = if (isStaff) MEMBER_TABS + MANAGE_TAB else MEMBER_TABS
    val showTabs = tabs.any { destination?.hasRoute(it.route::class) == true }

    val adminNav = AdminNav(
        onUsers = { status, role -> nav.navigate(AdminUsersRoute(status, role)) },
        onNewUser = { nav.navigate(AdminNewUserRoute) },
        onMemberships = { nav.navigate(AdminMembershipsRoute(it)) },
        onBooks = { nav.navigate(AdminBooksRoute(it)) },
        onCategories = { nav.navigate(AdminCategoriesRoute) },
        onRequests = { nav.navigate(AdminRequestsRoute(it)) },
        onAudit = { nav.navigate(AdminAuditRoute) },
    )

    androidx.compose.material3.Scaffold(
        bottomBar = {
            if (showTabs) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = destination?.hasRoute(tab.route::class) == true
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
                composable<DownloadsRoute> {
                    DownloadsScreen(
                        onRead = { nav.navigate(ReaderRoute(it)) },
                        onBrowse = { nav.switchTab(CatalogueRoute) },
                        onLogin = { nav.navigate(LoginRoute()) },
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
                        onDownloads = { nav.switchTab(DownloadsRoute) },
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

                // ---------------------------------------------------------------- management area
                composable<AdminHomeRoute> {
                    AdminHomeScreen(adminNav) {
                        // Publishers only manage their own books: the tab opens that list directly.
                        AdminBooksScreen(
                            status = null,
                            onBack = null,
                            onOpen = { nav.navigate(AdminBookRoute(it)) },
                            onNew = { nav.navigate(AdminBookRoute()) },
                        )
                    }
                }
                composable<AdminUsersRoute> { entry ->
                    val r = entry.toRoute<AdminUsersRoute>()
                    UsersScreen(
                        r.status, r.role,
                        onBack = { nav.popBackStack() },
                        onOpen = { nav.navigate(AdminUserRoute(it)) },
                        onNew = { nav.navigate(AdminNewUserRoute) },
                    )
                }
                composable<AdminNewUserRoute> {
                    NewUserScreen(
                        onBack = { nav.popBackStack() },
                        onCreated = { id -> nav.navigate(AdminUserRoute(id)) { popUpTo<AdminNewUserRoute> { inclusive = true } } },
                    )
                }
                composable<AdminUserRoute> { entry ->
                    UserDetailScreen(entry.toRoute<AdminUserRoute>().id, onBack = { nav.popBackStack() })
                }
                composable<AdminMembershipsRoute> { entry ->
                    MembershipsScreen(
                        entry.toRoute<AdminMembershipsRoute>().status,
                        onBack = { nav.popBackStack() },
                        onOpenUser = { nav.navigate(AdminUserRoute(it)) },
                    )
                }
                composable<AdminBooksRoute> { entry ->
                    AdminBooksScreen(
                        entry.toRoute<AdminBooksRoute>().status,
                        onBack = { nav.popBackStack() },
                        onOpen = { nav.navigate(AdminBookRoute(it)) },
                        onNew = { nav.navigate(AdminBookRoute()) },
                    )
                }
                composable<AdminBookRoute> { entry ->
                    BookEditScreen(
                        entry.toRoute<AdminBookRoute>().id,
                        onBack = { nav.popBackStack() },
                        onOpenPublic = { nav.navigate(BookRoute(it)) },
                    )
                }
                composable<AdminCategoriesRoute> { CategoriesScreen(onBack = { nav.popBackStack() }) }
                composable<AdminRequestsRoute> { entry ->
                    RequestsReviewScreen(
                        entry.toRoute<AdminRequestsRoute>().status,
                        onBack = { nav.popBackStack() },
                        onOpenUser = { nav.navigate(AdminUserRoute(it)) },
                        onOpenBook = { nav.navigate(AdminBookRoute(it)) },
                    )
                }
                composable<AdminAuditRoute> {
                    AuditScreen(onBack = { nav.popBackStack() }, onOpenUser = { nav.navigate(AdminUserRoute(it)) })
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
