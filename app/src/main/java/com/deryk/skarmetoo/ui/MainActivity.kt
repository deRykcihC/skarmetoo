package com.deryk.skarmetoo.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.deryk.skarmetoo.R
import com.deryk.skarmetoo.ui.components.hapticOnClick
import com.deryk.skarmetoo.ui.screens.DetailScreen
import com.deryk.skarmetoo.ui.screens.DuplicateImagesScreen
import com.deryk.skarmetoo.ui.screens.GalleryScreen
import com.deryk.skarmetoo.ui.screens.MoreModelsScreen
import com.deryk.skarmetoo.ui.screens.OnboardingScreen
import com.deryk.skarmetoo.ui.screens.ScreenSaver
import com.deryk.skarmetoo.ui.screens.SettingsScreen
import com.deryk.skarmetoo.ui.theme.SkarmetooTheme
import com.deryk.skarmetoo.ui.theme.uiScaleForDensityDpi
import com.deryk.skarmetoo.viewmodel.ScreenshotViewModel
import com.deryk.skarmetoo.viewmodel.SemanticSearchViewModel

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
          remember(currentLanguage, configuration) {
            val locale =
                when (currentLanguage) {
                  "zh-rTW" -> java.util.Locale("zh", "TW")
                  else -> java.util.Locale(currentLanguage)
                }
            java.util.Locale.setDefault(locale)
            val config = android.content.res.Configuration(configuration)
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
          val darkSystemBarColor = MaterialTheme.colorScheme.background.toArgb()
          SideEffect {
            val transparent = android.graphics.Color.TRANSPARENT
            val systemBarColor = if (isDarkMode) darkSystemBarColor else transparent
            val systemBarStyle =
                if (isDarkMode) {
                  androidx.activity.SystemBarStyle.dark(darkSystemBarColor)
                } else {
                  androidx.activity.SystemBarStyle.light(transparent, transparent)
                }

            this@MainActivity.enableEdgeToEdge(
                statusBarStyle = systemBarStyle,
                navigationBarStyle = systemBarStyle,
            )
            val window = this@MainActivity.window
            window.statusBarColor = systemBarColor
            window.navigationBarColor = systemBarColor
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
              window.isStatusBarContrastEnforced = false
              window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, window.decorView).apply {
              isAppearanceLightStatusBars = !isDarkMode
              isAppearanceLightNavigationBars = !isDarkMode
            }
          }
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
  const val MORE_MODELS = "more_models"
  const val DUPLICATE_IMAGES = "duplicate_images"
  const val GALLERY = "gallery"
  const val DETAIL = "detail/{id}"
  const val DUPLICATE_DETAIL = "detail/{id}/duplicate"

  fun detail(id: Long) = "detail/$id"

  fun duplicateDetail(id: Long) = "detail/$id/duplicate"
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
  val semanticViewModel: SemanticSearchViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

  val showBottomBar =
      !isPickMode && (currentRoute == Routes.SETTINGS || currentRoute == Routes.GALLERY)
  val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  val activeAnalysisIds by viewModel.activeAnalysisIds.collectAsState()
  val isAnalysisRunning by viewModel.isAnalysisRunning.collectAsState()
  val isAnalysisPaused by viewModel.isAnalysisPaused.collectAsState()
  val currentImageProgress by viewModel.currentImageProgress.collectAsState()
  val pendingCount by viewModel.pendingImageCount.collectAsState()
  val analyzingCount by viewModel.analyzingImageCount.collectAsState()
  val isModelReady by viewModel.isModelReady.collectAsState()
  val desktopProgress by viewModel.desktopProgress.collectAsState()
  val selectedModel by viewModel.selectedModel.collectAsState()

  val galleryScrollState = androidx.compose.foundation.rememberScrollState()
  var isScreenSaverActive by remember { mutableStateOf(false) }
  var focusActiveAnalysisRequest by remember { mutableStateOf(false) }
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
            visible = showBottomBar && !isLandscape,
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
    Row(
        modifier =
            Modifier.fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = bottomPadding,
                )) {
          NavHost(
              navController = navController,
              startDestination = startDestination,
              modifier = Modifier.weight(1f).fillMaxHeight(),
              enterTransition = {
                val initialRoute = initialState.destination.route
                val targetRoute = targetState.destination.route
                val initialIndex = routeOrder.indexOf(initialRoute)
                val targetIndex = routeOrder.indexOf(targetRoute)

                if (initialRoute == Routes.SETTINGS &&
                    (targetRoute == Routes.MORE_MODELS || targetRoute == Routes.DUPLICATE_IMAGES)) {
                  slideInHorizontally(initialOffsetX = { it }) + fadeIn()
                } else if (initialRoute == Routes.SETTINGS && targetRoute == Routes.GALLERY) {
                  slideInHorizontally(initialOffsetX = { -it }) + fadeIn()
                } else if (initialIndex != -1 &&
                    targetIndex != -1 &&
                    targetRoute != Routes.GALLERY) {
                  if (targetIndex > initialIndex) {
                    slideInHorizontally(initialOffsetX = { it }) + fadeIn()
                  } else {
                    slideInHorizontally(initialOffsetX = { -it }) + fadeIn()
                  }
                } else {
                  fadeIn()
                }
              },
              exitTransition = {
                val initialRoute = initialState.destination.route
                val targetRoute = targetState.destination.route
                val initialIndex = routeOrder.indexOf(initialRoute)
                val targetIndex = routeOrder.indexOf(targetRoute)

                if (initialRoute == Routes.SETTINGS &&
                    (targetRoute == Routes.MORE_MODELS || targetRoute == Routes.DUPLICATE_IMAGES)) {
                  slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
                } else if (initialRoute == Routes.SETTINGS && targetRoute == Routes.GALLERY) {
                  slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
                } else if (initialIndex != -1 &&
                    targetIndex != -1 &&
                    targetRoute != Routes.GALLERY) {
                  if (targetIndex > initialIndex) {
                    slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
                  } else {
                    slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
                  }
                } else {
                  fadeOut()
                }
              },
              popEnterTransition = {
                val initialRoute = initialState.destination.route
                val targetRoute = targetState.destination.route
                if ((initialRoute == Routes.MORE_MODELS ||
                    initialRoute == Routes.DUPLICATE_IMAGES) && targetRoute == Routes.SETTINGS) {
                  slideInHorizontally(initialOffsetX = { -it }) + fadeIn()
                } else if (initialRoute == Routes.SETTINGS && targetRoute == Routes.GALLERY) {
                  slideInHorizontally(initialOffsetX = { -it }) + fadeIn()
                } else {
                  fadeIn()
                }
              },
              popExitTransition = {
                val initialRoute = initialState.destination.route
                val targetRoute = targetState.destination.route
                if ((initialRoute == Routes.MORE_MODELS ||
                    initialRoute == Routes.DUPLICATE_IMAGES) && targetRoute == Routes.SETTINGS) {
                  slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
                } else if (initialRoute == Routes.SETTINGS && targetRoute == Routes.GALLERY) {
                  slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
                } else {
                  fadeOut()
                }
              },
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
                  semanticViewModel = semanticViewModel,
                  onStartScreenSaver = { isScreenSaverActive = true },
                  logoRes = logoRes,
                  onRevisitTutorial = { navController.navigate(Routes.ONBOARDING) },
                  onOpenMoreModels = { navController.navigate(Routes.MORE_MODELS) },
                  onOpenDuplicateImages = { navController.navigate(Routes.DUPLICATE_IMAGES) },
              )
            }
            composable(Routes.DUPLICATE_IMAGES) {
              DuplicateImagesScreen(
                  viewModel = viewModel,
                  onBack = { navController.popBackStack() },
                  onScreenshotClick = { id -> navController.navigate(Routes.duplicateDetail(id)) },
              )
            }
            composable(Routes.MORE_MODELS) {
              MoreModelsScreen(
                  onBack = { navController.popBackStack() },
                  onActivateModel = { model ->
                    viewModel.setGgufModelAsActive(model)
                    navController.popBackStack()
                  },
              )
            }
            composable(Routes.GALLERY) {
              GalleryScreen(
                  viewModel = viewModel,
                  onScreenshotClick = { id -> navController.navigate(Routes.detail(id)) },
                  scrollState = galleryScrollState,
                  logoRes = logoRes,
                  isPickMode = isPickMode,
                  focusActiveAnalysisRequest = focusActiveAnalysisRequest,
                  onFocusActiveAnalysisHandled = { focusActiveAnalysisRequest = false },
              )
            }
            composable(
                Routes.DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { backStackEntry ->
              val id = backStackEntry.arguments?.getLong("id") ?: return@composable
              val previousRoute = navController.previousBackStackEntry?.destination?.route
              DetailScreen(
                  viewModel = viewModel,
                  semanticViewModel = semanticViewModel,
                  entryId = id,
                  onBack = {
                    if (previousRoute == Routes.DUPLICATE_IMAGES) {
                      navController.popBackStack()
                    } else {
                      navController.popBackStack(Routes.GALLERY, inclusive = false)
                    }
                  },
                  onTagClick = { tag ->
                    viewModel.setSearchQuery(tag)
                    if (previousRoute == Routes.DUPLICATE_IMAGES) {
                      navController.popBackStack(Routes.DUPLICATE_IMAGES, inclusive = false)
                    } else {
                      navController.popBackStack(Routes.GALLERY, inclusive = false)
                    }
                  },
                  onScreenshotClick = { matchedId ->
                    navController.navigate(Routes.detail(matchedId))
                  })
            }
            composable(
                Routes.DUPLICATE_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { backStackEntry ->
              val id = backStackEntry.arguments?.getLong("id") ?: return@composable
              val entries by viewModel.entries.collectAsState()
              val duplicateSwipeEntryIds =
                  remember(id, entries) {
                    val imageHash = entries.firstOrNull { it.id == id }?.imageHash.orEmpty()
                    if (imageHash.isBlank()) {
                      null
                    } else {
                      entries
                          .filter { it.imageHash == imageHash }
                          .sortedByDescending { it.sortKey }
                          .map { it.id }
                          .takeIf { it.size > 1 }
                    }
                  }
              DetailScreen(
                  viewModel = viewModel,
                  semanticViewModel = semanticViewModel,
                  entryId = id,
                  onBack = {
                    navController.popBackStack(Routes.DUPLICATE_IMAGES, inclusive = false)
                  },
                  onTagClick = { tag ->
                    viewModel.setSearchQuery(tag)
                    navController.popBackStack(Routes.DUPLICATE_IMAGES, inclusive = false)
                  },
                  onScreenshotClick = { matchedId ->
                    navController.navigate(Routes.duplicateDetail(matchedId))
                  },
                  swipeEntryIds = duplicateSwipeEntryIds,
              )
            }
          }

          AnimatedVisibility(
              visible = showBottomBar && isLandscape,
              enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
              exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
          ) {
            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                containerColor =
                    if (currentRoute == Routes.GALLERY || currentRoute == Routes.SETTINGS) {
                      Color.Transparent
                    } else MaterialTheme.colorScheme.surfaceContainer,
            ) {
              Spacer(modifier = Modifier.height(12.dp))
              Image(
                  painter = painterResource(id = logoRes),
                  contentDescription = stringResource(R.string.logo),
                  modifier = Modifier.size(40.dp).align(Alignment.CenterHorizontally),
              )
              Spacer(modifier = Modifier.height(10.dp))
              val isDesktopActive =
                  selectedModel == com.deryk.skarmetoo.viewmodel.ModelType.DESKTOP &&
                      desktopProgress.isRunning
              val desktopPending =
                  if (isDesktopActive)
                      (desktopProgress.total - desktopProgress.processed).coerceAtLeast(0)
                  else 0
              val hasAnalysisWork =
                  isAnalysisPaused ||
                      isAnalysisRunning ||
                      pendingCount > 0 ||
                      analyzingCount > 0 ||
                      isDesktopActive
              Surface(
                  modifier =
                      Modifier.align(Alignment.CenterHorizontally)
                          .size(56.dp)
                          .clip(RoundedCornerShape(16.dp))
                          .combinedClickable(
                              onDoubleClick = {
                                if (activeAnalysisIds.isNotEmpty() || analyzingCount > 0) {
                                  if (currentRoute != Routes.GALLERY) {
                                    navController.navigate(Routes.GALLERY) {
                                      popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                      }
                                      launchSingleTop = true
                                      restoreState = true
                                    }
                                  }
                                  focusActiveAnalysisRequest = true
                                } else if (isModelReady) {
                                  viewModel.forceAnalyzeUnprocessed()
                                }
                              },
                              onClick = {
                                if (isModelReady && pendingCount > 0 && !isAnalysisRunning) {
                                  viewModel.analyzeUnprocessed()
                                }
                              },
                          ),
                  shape = RoundedCornerShape(16.dp),
                  color =
                      if (hasAnalysisWork) MaterialTheme.colorScheme.errorContainer
                      else MaterialTheme.colorScheme.secondaryContainer,
              ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                  when {
                    isDesktopActive ->
                        Icon(
                            Icons.Rounded.Computer,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    isAnalysisPaused && activeAnalysisIds.isEmpty() ->
                        Icon(
                            Icons.Rounded.Pause,
                            contentDescription = stringResource(R.string.pause),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    analyzingCount == 1 || isAnalysisRunning ->
                        CircularProgressIndicator(
                            progress = { currentImageProgress },
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.error,
                            trackColor = MaterialTheme.colorScheme.errorContainer,
                        )
                    hasAnalysisWork ->
                        Icon(
                            Icons.Rounded.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    else ->
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                  }
                  Spacer(modifier = Modifier.height(3.dp))
                  Text(
                      text =
                          if (isDesktopActive) {
                            desktopPending.toString()
                          } else if (hasAnalysisWork) {
                            (pendingCount + analyzingCount).toString()
                          } else {
                            stringResource(R.string.done)
                          },
                      style = MaterialTheme.typography.labelMedium,
                      fontWeight = FontWeight.Bold,
                      color =
                          if (hasAnalysisWork) MaterialTheme.colorScheme.error
                          else MaterialTheme.colorScheme.onSecondaryContainer,
                  )
                }
              }
              Spacer(modifier = Modifier.weight(1f))
              NavigationRailItem(
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
                        stringResource(R.string.gallery),
                    )
                  },
                  label = { Text(stringResource(R.string.gallery)) },
                  colors =
                      NavigationRailItemDefaults.colors(
                          indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                          selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                          selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                      ),
              )
              NavigationRailItem(
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
                        stringResource(R.string.settings),
                    )
                  },
                  label = { Text(stringResource(R.string.settings)) },
                  colors =
                      NavigationRailItemDefaults.colors(
                          indicatorColor = MaterialTheme.colorScheme.tertiaryContainer,
                          selectedIconColor = MaterialTheme.colorScheme.onTertiaryContainer,
                          selectedTextColor = MaterialTheme.colorScheme.onTertiaryContainer,
                      ),
              )
              Spacer(modifier = Modifier.weight(1f))
            }
          }
        }
  }

  if (isScreenSaverActive) {
    ScreenSaver(viewModel = viewModel, onClose = { isScreenSaverActive = false })
  }
}
