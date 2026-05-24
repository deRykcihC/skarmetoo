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
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.deryk.skarmetoo.ui.theme.SkarmetooTheme
import com.deryk.skarmetoo.ui.theme.uiScaleForDensityDpi

class MainActivity : ComponentActivity() {
  private val viewModel: ScreenshotViewModel by viewModels()

  private val _newIntentFlow =
      kotlinx.coroutines.flow.MutableSharedFlow<Intent>(extraBufferCapacity = 1)
  val newIntentFlow = _newIntentFlow

  private val _isPickMode = androidx.compose.runtime.mutableStateOf(false)
  val isPickMode: androidx.compose.runtime.State<Boolean> = _isPickMode

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    _newIntentFlow.tryEmit(intent)
    _isPickMode.value =
        intent.action == Intent.ACTION_PICK || intent.action == Intent.ACTION_GET_CONTENT
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    _isPickMode.value =
        intent?.action == Intent.ACTION_PICK || intent?.action == Intent.ACTION_GET_CONTENT

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
      val configuration = LocalConfiguration.current
      val currentLanguage by viewModel.appLanguage.collectAsState()
      val baseDensity = LocalDensity.current
      val uiScale =
          remember(configuration.densityDpi) { uiScaleForDensityDpi(configuration.densityDpi) }
      val scaledDensity =
          remember(baseDensity, uiScale) {
            Density(density = baseDensity.density * uiScale, fontScale = baseDensity.fontScale)
          }

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
          LocalDensity provides scaledDensity,
          androidx.activity.compose.LocalActivityResultRegistryOwner provides
              (context as androidx.activity.result.ActivityResultRegistryOwner),
      ) {
        SkarmetooTheme(darkTheme = isDarkMode) {
          CompositionLocalProvider(
              com.deryk.skarmetoo.ui.theme.LocalIsDarkMode provides isDarkMode) {
                MainApp(viewModel = viewModel, isPickMode = isPickMode.value)
              }
        }
      }
    }
  }
}

// --- Navigation ---
object Routes {
  const val ONBOARDING = "onboarding"
  const val SETTINGS = "settings"
  const val GALLERY = "gallery"
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(viewModel: ScreenshotViewModel, isPickMode: Boolean = false) {
  val navController = rememberNavController()
  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = navBackStackEntry?.destination?.route
  val context = LocalContext.current

  val showBottomBar =
      !isPickMode &&
          (currentRoute == Routes.SETTINGS ||
              currentRoute == Routes.GALLERY)

  val galleryScrollState = androidx.compose.foundation.rememberScrollState()
  var isScreenSaverActive by remember { mutableStateOf(false) }
  val isEasterEgg = remember { kotlin.random.Random.nextFloat() < 0.069f }
  val logoRes = if (isEasterEgg) R.drawable.app_logo_rainbow else R.drawable.app_logo

  val startDestination = remember {
    if (viewModel.hasSeenOnboarding.value) Routes.GALLERY else Routes.ONBOARDING
  }

  val activity = context.findComponentActivity() as? MainActivity

  fun processLaunchIntent(
      intent: Intent,
      viewModel: ScreenshotViewModel,
      navController: androidx.navigation.NavController
  ) {
    val action = intent.action
    val uri = intent.data

    if (action == "SHOW_GALLERY") {
      intent.setAction(null) // Clear action so we don't repeatedly navigate on recomposition
      val currentRoute = navController.currentBackStackEntry?.destination?.route
      if (currentRoute != Routes.GALLERY) {
        navController.navigate(Routes.GALLERY) {
          popUpTo(navController.graph.startDestinationId) { saveState = true }
          launchSingleTop = true
          restoreState = true
        }
      }
    } else if (action == Intent.ACTION_VIEW && uri != null) {
      intent.setAction(null) // Clear action so we don't repeatedly navigate on recomposition
      viewModel.getOrCreateEntryForUri(uri) { entryId ->
        if (entryId > 0) {
          navController.navigate(Routes.detail(entryId)) { launchSingleTop = true }
        }
      }
    }
  }

  LaunchedEffect(activity) {
    // Process initial intent
    activity?.intent?.let { initialIntent ->
      processLaunchIntent(initialIntent, viewModel, navController)
    }
    // Collect subsequent intents
    activity?.newIntentFlow?.collect { newIntent ->
      processLaunchIntent(newIntent, viewModel, navController)
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
      topBar = {
        if (isPickMode) {
          TopAppBar(
              title = { Text(stringResource(R.string.select_photo_title)) },
              navigationIcon = {
                IconButton(onClick = hapticOnClick { activity?.finish() }) {
                  Icon(Icons.Rounded.Close, stringResource(R.string.cancel))
                }
              },
              colors =
                  TopAppBarDefaults.topAppBarColors(
                      containerColor = MaterialTheme.colorScheme.surfaceContainer,
                      titleContentColor = MaterialTheme.colorScheme.onSurface))
        }
      },
      bottomBar = {
        AnimatedVisibility(
            visible = showBottomBar,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
          NavigationBar(tonalElevation = 4.dp) {
            NavigationBarItem(
                selected = currentRoute == Routes.GALLERY,
                onClick =
                    hapticOnClick {
                      if (currentRoute != Routes.GALLERY) {
                        navController.navigate(Routes.GALLERY) {
                          popUpTo(navController.graph.startDestinationId) { saveState = true }
                          launchSingleTop = true
                          restoreState = true
                        }
                      }
                    },
                icon = {
                  Icon(
                      if (currentRoute == Routes.GALLERY) Icons.Rounded.Home
                      else Icons.Outlined.Home,
                      "Home",
                  )
                },
                label = { Text(stringResource(R.string.gallery)) },
                colors =
                    NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
            NavigationBarItem(
                selected = currentRoute == Routes.SETTINGS,
                onClick =
                    hapticOnClick {
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
          }
        }
      },
  ) { innerPadding ->
    val bottomPadding by
        animateDpAsState(
            targetValue = innerPadding.calculateBottomPadding(),
            label = "bottomPadding",
        )
    val routeOrder = listOf(Routes.GALLERY, Routes.SETTINGS)
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
              Routes.SETTINGS -> slideInHorizontally(initialOffsetX = { 1000 }) + fadeIn()
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
              Routes.SETTINGS -> slideOutHorizontally(targetOffsetX = { -1000 }) + fadeOut()
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
      composable(Routes.SETTINGS) {
        SettingsScreen(
            viewModel = viewModel,
            onStartScreenSaver = { isScreenSaverActive = true },
            logoRes = logoRes,
            onRevisitTutorial = { navController.navigate(Routes.ONBOARDING) },
        )
      }
      composable(Routes.GALLERY) {
        GalleryScreen(
            viewModel = viewModel,
            onScreenshotClick = { id -> navController.navigate(Routes.detail(id)) },
            scrollState = galleryScrollState,
            logoRes = logoRes,
            isPickMode = isPickMode,
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
