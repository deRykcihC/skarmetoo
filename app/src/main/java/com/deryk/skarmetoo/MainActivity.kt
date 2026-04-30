package com.deryk.skarmetoo

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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

  private val _newIntentFlow =
      kotlinx.coroutines.flow.MutableSharedFlow<Intent>(extraBufferCapacity = 1)
  val newIntentFlow = _newIntentFlow

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    _newIntentFlow.tryEmit(intent)
  }

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

      val isDarkMode by viewModel.isDarkMode.collectAsState()

      CompositionLocalProvider(
          LocalContext provides localeContext,
          androidx.compose.ui.platform.LocalConfiguration provides
              localeContext.resources.configuration,
          androidx.activity.compose.LocalActivityResultRegistryOwner provides
              (context as androidx.activity.result.ActivityResultRegistryOwner),
      ) {
        SkarmetooTheme(darkTheme = isDarkMode) {
          CompositionLocalProvider(
              com.deryk.skarmetoo.ui.theme.LocalIsDarkMode provides isDarkMode) {
                MainApp(viewModel = viewModel)
              }
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

fun android.content.Context.findComponentActivity(): ComponentActivity? {
  var context = this
  while (context is android.content.ContextWrapper) {
    if (context is ComponentActivity) return context
    context = context.baseContext
  }
  return null
}

// --- Main App ---
@Composable
fun MainApp(viewModel: ScreenshotViewModel) {
  val navController = rememberNavController()
  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = navBackStackEntry?.destination?.route
  val context = LocalContext.current

  val showBottomBar =
      currentRoute == Routes.GALLERY ||
          currentRoute == Routes.SETTINGS ||
          currentRoute == Routes.EXPERIMENTAL

  var galleryScrollKey by remember { mutableIntStateOf(0) }
  var galleryRefreshKey by remember { mutableIntStateOf(0) }
  val galleryScrollState = androidx.compose.foundation.rememberScrollState()
  var isScreenSaverActive by remember { mutableStateOf(false) }
  var lastGalleryClickTime by remember { mutableLongStateOf(0L) }
  val isEasterEgg = remember { kotlin.random.Random.nextFloat() < 0.069f }
  val logoRes = if (isEasterEgg) R.drawable.app_logo_rainbow else R.drawable.app_logo

  val startDestination = remember {
    if (viewModel.hasSeenOnboarding.value) Routes.GALLERY else Routes.ONBOARDING
  }

  val activity = context.findComponentActivity() as? MainActivity

  LaunchedEffect(activity) {
    activity?.newIntentFlow?.collect { newIntent ->
      if (newIntent.action == "SHOW_GALLERY") {
        newIntent.setAction(null) // Clear action so we don't repeatedly navigate on recomposition
        if (currentRoute != Routes.GALLERY) {
          navController.navigate(Routes.GALLERY) {
            popUpTo(navController.graph.startDestinationId) { saveState = true }
            launchSingleTop = true
            restoreState = true
          }
        }
      }
    }
  }

  // Refresh entries and resume analysis when app returns to foreground
  val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer =
        androidx.lifecycle.LifecycleEventObserver { _, event ->
          if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
            viewModel.refreshEntries()
            viewModel.resumeAnalysisIfNeeded()
          }
        }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

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
                      if (currentRoute == Routes.GALLERY) Icons.Rounded.PhotoLibrary
                      else Icons.Outlined.PhotoLibrary,
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
                      if (currentRoute == Routes.SETTINGS) Icons.Rounded.Settings
                      else Icons.Outlined.Settings,
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
                      if (currentRoute == Routes.EXPERIMENTAL) Icons.Rounded.Science
                      else Icons.Outlined.Science,
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
    val bottomPadding by
        animateDpAsState(
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
              Routes.SETTINGS,
              Routes.EXPERIMENTAL -> slideInHorizontally(initialOffsetX = { 1000 }) + fadeIn()
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
              Routes.SETTINGS,
              Routes.EXPERIMENTAL -> slideOutHorizontally(targetOffsetX = { -1000 }) + fadeOut()
              else -> fadeOut()
            }
          }
        },
        popEnterTransition = { slideInHorizontally(initialOffsetX = { -1000 }) + fadeIn() },
        popExitTransition = { slideOutHorizontally(targetOffsetX = { 1000 }) + fadeOut() },
    ) {
      composable(Routes.ONBOARDING) {
        OnboardingScreen(
            viewModel = viewModel,
            onFinish = {
              viewModel.setHasSeenOnboarding(true)
              navController.navigate(Routes.GALLERY) {
                popUpTo(Routes.ONBOARDING) { inclusive = true }
              }
            })
      }
      composable(Routes.GALLERY) {
        GalleryScreen(
            viewModel = viewModel,
            onScreenshotClick = { id -> navController.navigate(Routes.detail(id)) },
            scrollToTopKey = galleryScrollKey,
            refreshKey = galleryRefreshKey,
            logoRes = logoRes,
            scrollState = galleryScrollState,
        )
      }
      composable(Routes.SETTINGS) {
        SettingsScreen(
            viewModel = viewModel,
            onStartScreenSaver = { isScreenSaverActive = true },
            logoRes = logoRes,
            onRevisitTutorial = { navController.navigate(Routes.ONBOARDING) },
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
