package com.deryk.skarmetoo

import android.content.Intent
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.deryk.skarmetoo.ui.theme.SkarmetooTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ScreenshotViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Removed early permission request

        enableEdgeToEdge(
            statusBarStyle =
                androidx.activity.SystemBarStyle.light(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ),
            navigationBarStyle =
                androidx.activity.SystemBarStyle.light(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ),
        )
        setContent {
            val context = LocalContext.current
            val currentLanguage by viewModel.appLanguage.collectAsState()

            val localeContext =
                remember(currentLanguage) {
                    val locale =
                        when (currentLanguage) {
                            "zh-rTW" -> java.util.Locale("zh", "TW")
                            else -> java.util.Locale(currentLanguage)
                        }
                    java.util.Locale.setDefault(locale)
                    val config = android.content.res.Configuration(context.resources.configuration)
                    config.setLocale(locale)

                    val localeResources = context.createConfigurationContext(config).resources
                    object : android.content.ContextWrapper(context) {
                        override fun getResources(): android.content.res.Resources {
                            return localeResources
                        }
                    }
                }

            CompositionLocalProvider(
                LocalContext provides localeContext,
                androidx.compose.ui.platform.LocalConfiguration provides localeContext.resources.configuration,
                androidx.activity.compose.LocalActivityResultRegistryOwner provides (context as androidx.activity.result.ActivityResultRegistryOwner),
            ) {
                SkarmetooTheme {
                    MainApp(viewModel = viewModel)
                }
            }
        }
    }
}

// --- Navigation ---
object Routes {
    const val ONBOARDING = "onboarding"
    const val GALLERY = "gallery"
    const val SETTINGS = "settings"
    const val EXPERIMENTAL = "experimental"
    const val DETAIL = "detail/{id}"

    fun detail(id: Long) = "detail/$id"
}

// --- Main App ---
@Composable
fun MainApp(viewModel: ScreenshotViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val context = LocalContext.current

    val showBottomBar = currentRoute == Routes.GALLERY || currentRoute == Routes.SETTINGS || currentRoute == Routes.EXPERIMENTAL

    var galleryScrollKey by remember { mutableIntStateOf(0) }
    var galleryRefreshKey by remember { mutableIntStateOf(0) }
    var isScreenSaverActive by remember { mutableStateOf(false) }
    var lastGalleryClickTime by remember { mutableLongStateOf(0L) }
    val isEasterEgg = remember { kotlin.random.Random.nextFloat() < 0.069f }
    val logoRes = if (isEasterEgg) R.drawable.app_logo_rainbow else R.drawable.app_logo

    val startDestination = remember { if (viewModel.hasSeenOnboarding.value) Routes.GALLERY else Routes.ONBOARDING }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            ) {
                NavigationBar(tonalElevation = 4.dp) {
                    NavigationBarItem(
                        selected = currentRoute == Routes.GALLERY,
                        onClick = {
                            val now = System.currentTimeMillis()
                            if (currentRoute != Routes.GALLERY) {
                                navController.navigate(Routes.GALLERY) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            } else {
                                if (now - lastGalleryClickTime < 400) {
                                    galleryRefreshKey++
                                    lastGalleryClickTime = 0L
                                } else {
                                    galleryScrollKey++
                                    lastGalleryClickTime = now
                                }
                            }
                        },
                        icon = {
                            Icon(
                                if (currentRoute == Routes.GALLERY) Icons.Rounded.PhotoLibrary else Icons.Outlined.PhotoLibrary,
                                "Gallery",
                            )
                        },
                        label = { Text(stringResource(R.string.gallery)) },
                        colors =
                            NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.SETTINGS,
                        onClick = {
                            if (currentRoute != Routes.SETTINGS) {
                                navController.navigate(Routes.SETTINGS) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                if (currentRoute == Routes.SETTINGS) Icons.Rounded.Settings else Icons.Outlined.Settings,
                                "Settings",
                            )
                        },
                        label = { Text(stringResource(R.string.settings)) },
                        colors =
                            NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.tertiaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ),
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.EXPERIMENTAL,
                        onClick = {
                            if (currentRoute != Routes.EXPERIMENTAL) {
                                navController.navigate(Routes.EXPERIMENTAL) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                if (currentRoute == Routes.EXPERIMENTAL) Icons.Rounded.Science else Icons.Outlined.Science,
                                "Experimental",
                            )
                        },
                        label = { Text(stringResource(R.string.experimental)) },
                        colors =
                            NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                    )
                }
            }
        },
    ) { innerPadding ->
        val bottomPadding by animateDpAsState(
            targetValue = innerPadding.calculateBottomPadding(),
            label = "bottomPadding",
        )
        val routeOrder = listOf(Routes.GALLERY, Routes.SETTINGS, Routes.EXPERIMENTAL)
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier =
                Modifier.padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = bottomPadding,
                ),
            enterTransition = {
                val initialRoute = initialState.destination.route
                val targetRoute = targetState.destination.route
                val initialIndex = routeOrder.indexOf(initialRoute)
                val targetIndex = routeOrder.indexOf(targetRoute)

                if (initialIndex != -1 && targetIndex != -1) {
                    if (targetIndex > initialIndex) {
                        slideInHorizontally(initialOffsetX = { it }) + fadeIn()
                    } else {
                        slideInHorizontally(initialOffsetX = { -it }) + fadeIn()
                    }
                } else {
                    when (targetRoute) {
                        Routes.GALLERY -> slideInHorizontally(initialOffsetX = { -1000 }) + fadeIn()
                        Routes.SETTINGS, Routes.EXPERIMENTAL -> slideInHorizontally(initialOffsetX = { 1000 }) + fadeIn()
                        else -> fadeIn()
                    }
                }
            },
            exitTransition = {
                val initialRoute = initialState.destination.route
                val targetRoute = targetState.destination.route
                val initialIndex = routeOrder.indexOf(initialRoute)
                val targetIndex = routeOrder.indexOf(targetRoute)

                if (initialIndex != -1 && targetIndex != -1) {
                    if (targetIndex > initialIndex) {
                        slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
                    } else {
                        slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
                    }
                } else {
                    when (targetRoute) {
                        Routes.GALLERY -> slideOutHorizontally(targetOffsetX = { 1000 }) + fadeOut()
                        Routes.SETTINGS, Routes.EXPERIMENTAL -> slideOutHorizontally(targetOffsetX = { -1000 }) + fadeOut()
                        else -> fadeOut()
                    }
                }
            },
            popEnterTransition = { slideInHorizontally(initialOffsetX = { -1000 }) + fadeIn() },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { 1000 }) + fadeOut() },
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onFinish = {
                        viewModel.setHasSeenOnboarding(true)
                        navController.navigate(Routes.GALLERY) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    }
                )
            }
            composable(Routes.GALLERY) {
                GalleryScreen(
                    viewModel = viewModel,
                    onScreenshotClick = { id -> navController.navigate(Routes.detail(id)) },
                    scrollToTopKey = galleryScrollKey,
                    refreshKey = galleryRefreshKey,
                    logoRes = logoRes,
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    viewModel = viewModel,
                    onStartScreenSaver = { isScreenSaverActive = true },
                    logoRes = logoRes,
                    onRevisitTutorial = {
                        navController.navigate(Routes.ONBOARDING)
                    }
                )
            }
            composable(Routes.EXPERIMENTAL) {
                ExperimentalScreen(
                    viewModel = viewModel,
                    onScreenshotClick = { id -> navController.navigate(Routes.detail(id)) },
                    logoRes = logoRes,
                )
            }
            composable(
                Routes.DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getLong("id") ?: return@composable
                DetailScreen(
                    viewModel = viewModel,
                    entryId = id,
                    onBack = { navController.popBackStack() },
                    onTagClick = { tag ->
                        viewModel.setSearchQuery(tag)
                        navController.popBackStack()
                    },
                )
            }
        }
    }

    if (isScreenSaverActive) {
        ScreenSaver(viewModel = viewModel, onClose = { isScreenSaverActive = false })
    }
}

@Composable
fun ScreenSaver(
    viewModel: ScreenshotViewModel,
    onClose: () -> Unit,
) {
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var currentTime by remember { mutableStateOf("") }
    val analysisProgress by viewModel.analysisProgress.collectAsState()
    val context = LocalContext.current
    val activity = context.findActivity()

    DisposableEffect(Unit) {
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(Unit) {
        val locale = java.util.Locale.getDefault()
        val pattern = if (locale.language == "zh") "HH:mm\nM月d日, yyyy" else "HH:mm\nMMM dd, yyyy"
        val format = java.text.SimpleDateFormat(pattern, locale)
        while (true) {
            currentTime = format.format(java.util.Date())
            kotlinx.coroutines.delay(1000)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            offsetX = kotlin.random.Random.nextInt(-50, 50).toFloat()
            offsetY = kotlin.random.Random.nextInt(-100, 100).toFloat()
            kotlinx.coroutines.delay(60000)
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.offset(x = offsetX.dp, y = offsetY.dp),
        ) {
            Text(
                text = currentTime,
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.displayMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(32.dp))
            analysisProgress?.let { (remaining, _) ->
                Text(
                    text = stringResource(R.string.analyzing_in_background),
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.items_left, remaining),
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyLarge,
                )
            } ?: run {
                Text(
                    text = stringResource(R.string.all_images_analyzed),
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Text(
            text = stringResource(R.string.tap_to_exit),
            color = Color.White.copy(alpha = 0.3f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

fun android.content.Context.findActivity(): android.app.Activity? {
    var context = this
    while (context is android.content.ContextWrapper) {
        if (context is android.app.Activity) return context
        context = context.baseContext
    }
    return null
}
