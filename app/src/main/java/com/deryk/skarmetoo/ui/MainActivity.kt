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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.deryk.skarmetoo.R
import com.deryk.skarmetoo.network.BenchmarkLeaderboardClient
import com.deryk.skarmetoo.network.LeaderboardMessagingService
import com.deryk.skarmetoo.ui.components.ExpressiveNavigationItem
import com.deryk.skarmetoo.ui.components.LocalFloatingNavigationBottomInset
import com.deryk.skarmetoo.ui.components.LocalFloatingNavigationEndInset
import com.deryk.skarmetoo.ui.components.hapticOnClick
import com.deryk.skarmetoo.ui.screens.DetailScreen
import com.deryk.skarmetoo.ui.screens.DuplicateImagesScreen
import com.deryk.skarmetoo.ui.screens.EmbeddingGemmaSkippedImagesScreen
import com.deryk.skarmetoo.ui.screens.GalleryScreen
import com.deryk.skarmetoo.ui.screens.LeaderboardScreen
import com.deryk.skarmetoo.ui.screens.MoreModelsScreen
import com.deryk.skarmetoo.ui.screens.OnboardingScreen
import com.deryk.skarmetoo.ui.screens.ScreenSaver
import com.deryk.skarmetoo.ui.screens.SettingsScreen
import com.deryk.skarmetoo.ui.theme.AppMotion
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

    LeaderboardMessagingService.ensureNotificationChannel(this)

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
                  // Normalize the temporary Simplified Chinese preference to the app's
                  // supported Traditional Chinese locale.
                  "zh-rCN" -> java.util.Locale("zh", "TW")
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
  const val LEADERBOARD = "leaderboard"
  const val MORE_MODELS = "more_models"
  const val DUPLICATE_IMAGES = "duplicate_images"
  const val EMBEDDING_GEMMA_SKIPPED_IMAGES = "embedding_gemma_skipped_images"
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

  val showBottomBar = !isPickMode && currentRoute in listOf(Routes.GALLERY, Routes.SETTINGS)
  val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  val density = LocalDensity.current
  var bottomBarHeight by remember(density) { mutableStateOf(0.dp) }
  var landscapePillWidth by remember(density) { mutableStateOf(0.dp) }
  val activeAnalysisIds by viewModel.activeAnalysisIds.collectAsState()
  val isAnalysisRunning by viewModel.isAnalysisRunning.collectAsState()
  val isAnalysisPaused by viewModel.isAnalysisPaused.collectAsState()
  val currentImageProgress by viewModel.currentImageProgress.collectAsState()
  val pendingCount by viewModel.pendingImageCount.collectAsState()
  val analyzingCount by viewModel.analyzingImageCount.collectAsState()
  val isModelReady by viewModel.isModelReady.collectAsState()
  val desktopProgress by viewModel.desktopProgress.collectAsState()
  val selectedModel by viewModel.selectedModel.collectAsState()
  val benchmarkLeaderboardOptedIn by viewModel.benchmarkLeaderboardOptedIn.collectAsState()

  val galleryScrollState = androidx.compose.foundation.rememberScrollState()
  var isScreenSaverActive by remember { mutableStateOf(false) }
  var focusActiveAnalysisRequest by remember { mutableStateOf(false) }
  var landscapeControlsVisible by remember { mutableStateOf(true) }
  LaunchedEffect(currentRoute, isLandscape) { landscapeControlsVisible = true }
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

    if (action == BenchmarkLeaderboardClient.ACTION_SHOW_LEADERBOARD) {
      intent.setAction(null)
      val currentRoute = navController.currentBackStackEntry?.destination?.route
      if (currentRoute != Routes.LEADERBOARD) {
        navController.navigate(Routes.LEADERBOARD) {
          popUpTo(navController.graph.startDestinationId) { saveState = true }
          launchSingleTop = true
          restoreState = true
        }
      }
    } else if (action == "SHOW_GALLERY") {
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

  val navigationStatus: @Composable () -> Unit = {
    val isDesktopActive = selectedModel == com.deryk.skarmetoo.viewmodel.ModelType.DESKTOP && desktopProgress.isRunning
    val hasAnalysisWork = isAnalysisPaused || isAnalysisRunning || pendingCount > 0 || analyzingCount > 0 || isDesktopActive
    val statusColor = if (hasAnalysisWork) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        modifier = Modifier.height(56.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .combinedClickable(
                onClick = hapticOnClick {
                  if (isModelReady && pendingCount > 0 && !isAnalysisRunning) viewModel.analyzeUnprocessed()
                },
                onDoubleClick = {
                  if (activeAnalysisIds.isNotEmpty() || analyzingCount > 0) {
                    if (currentRoute != Routes.GALLERY) {
                      navController.navigate(Routes.GALLERY) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                      }
                    }
                    focusActiveAnalysisRequest = true
                  } else if (isModelReady) viewModel.forceAnalyzeUnprocessed()
                },
            ),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = if (hasAnalysisWork) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = statusColor,
        shadowElevation = 2.dp,
    ) {
      Row(
          modifier = Modifier.padding(horizontal = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        if (isAnalysisRunning && !isDesktopActive && analyzingCount <= 1) {
          CircularProgressIndicator(
              progress = { currentImageProgress }, modifier = Modifier.size(18.dp),
              strokeWidth = 2.dp, color = statusColor,
              trackColor = MaterialTheme.colorScheme.errorContainer,
          )
        } else {
          Icon(
              when {
                isDesktopActive -> Icons.Rounded.Computer
                isAnalysisPaused && activeAnalysisIds.isEmpty() -> Icons.Rounded.Pause
                hasAnalysisWork -> Icons.Rounded.Schedule
                else -> Icons.Rounded.CheckCircle
              },
              contentDescription = null, modifier = Modifier.size(18.dp),
          )
        }
        Text(
            text = if (isDesktopActive) (desktopProgress.total - desktopProgress.processed).coerceAtLeast(0).toString()
                else if (hasAnalysisWork) (pendingCount + analyzingCount).toString()
                else stringResource(R.string.done),
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
      }
    }
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
            enter =
                slideInVertically(animationSpec = AppMotion.spatial(), initialOffsetY = { it }) +
                    fadeIn(animationSpec = AppMotion.effects()),
            exit =
                slideOutVertically(animationSpec = AppMotion.spatial(), targetOffsetY = { it }) +
                    fadeOut(animationSpec = AppMotion.fastEffects()),
        ) {
          Box(
              modifier =
                  Modifier.fillMaxWidth()
                      .onSizeChanged { size ->
                        bottomBarHeight = with(density) { size.height.toDp() }
                      }
                      .windowInsetsPadding(
                          WindowInsets.navigationBars.only(
                              WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                      .padding(horizontal = 24.dp, vertical = 8.dp),
              contentAlignment = Alignment.Center,
          ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              navigationStatus()
              NavigationBar(
                modifier =
                    Modifier.width(184.dp)
                        .height(64.dp) // Match the Details floating toolbar height.
                        .clip(RoundedCornerShape(32.dp)),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
              ExpressiveNavigationItem(
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
                  icon =
                      if (currentRoute == Routes.GALLERY) Icons.Rounded.Home
                      else Icons.Outlined.Home,
                  label = stringResource(R.string.gallery),
                  indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                  selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                  modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
              )
              ExpressiveNavigationItem(
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
                  icon =
                      if (currentRoute == Routes.SETTINGS) Icons.Rounded.Settings
                      else Icons.Outlined.Settings,
                  label = stringResource(R.string.settings),
                  indicatorColor = MaterialTheme.colorScheme.tertiaryContainer,
                  selectedContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                  modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
              )
            }
            }
          }
        }
      },
  ) { innerPadding ->
    val routeOrder = listOf(Routes.GALLERY, Routes.SETTINGS)
    val detailRoutes = setOf(Routes.DETAIL, Routes.DUPLICATE_DETAIL)
    fun navigateToSwipedDetail(route: String, direction: Int) {
      navController.navigate(route)
      // Store direction on this navigation entry, so later image taps can't inherit it.
      navController.currentBackStackEntry?.savedStateHandle?.set(
          "detailImageSwipeDirection", direction)
    }

    val secondaryRoutes =
        setOf(
            Routes.DETAIL,
            Routes.DUPLICATE_DETAIL,
            Routes.MORE_MODELS,
            Routes.DUPLICATE_IMAGES,
            Routes.EMBEDDING_GEMMA_SKIPPED_IMAGES,
            Routes.LEADERBOARD,
            Routes.ONBOARDING,
        )
    val systemBottomPadding =
        ScaffoldDefaults.contentWindowInsets.asPaddingValues().calculateBottomPadding()

    fun NavGraphBuilder.insetComposable(
        route: String,
        arguments: List<NamedNavArgument> = emptyList(),
        content: @Composable (NavBackStackEntry) -> Unit,
    ) {
      composable(route, arguments = arguments) { entry ->
        // Insets belong to this destination so the outgoing screen stays still during navigation.
        val isMainScreen = entry.destination.route in routeOrder
        val topPadding =
            if (isLandscape && isMainScreen) {
              innerPadding.calculateTopPadding() * 0.5f
            } else if (!isPickMode && isMainScreen) {
              (innerPadding.calculateTopPadding() - 12.dp).coerceAtLeast(0.dp)
            } else {
              innerPadding.calculateTopPadding()
            }
        // Pages draw behind the system navigation area; their controls apply their own safe insets.
        val cutoutPadding = WindowInsets.displayCutout.asPaddingValues()
        val layoutDirection = LocalLayoutDirection.current
        // Use the device's cutout inset, allowing the page's existing content margin to supply
        // part of that spacing rather than adding a second margin beyond the safe boundary.
        val landscapeStartPadding =
            (cutoutPadding.calculateStartPadding(layoutDirection) -
                if (entry.destination.route == Routes.GALLERY) 12.dp else 8.dp).coerceAtLeast(0.dp)
        val landscapeEndPadding =
            (cutoutPadding.calculateEndPadding(layoutDirection) - 8.dp).coerceAtLeast(0.dp)
        Box(
            Modifier.fillMaxSize()
                .then(
                    if (isLandscape && isMainScreen)
                        Modifier.padding(start = landscapeStartPadding, end = landscapeEndPadding)
                    else Modifier)
                .padding(top = topPadding),
        ) {
          CompositionLocalProvider(
              LocalFloatingNavigationBottomInset provides
                  if (!isPickMode && !isLandscape && isMainScreen)
                      bottomBarHeight.coerceAtLeast(systemBottomPadding)
                  else 0.dp,
              LocalFloatingNavigationEndInset provides
                  if (!isPickMode && isLandscape && isMainScreen)
                      (landscapePillWidth.coerceAtLeast(96.dp) - landscapeEndPadding).coerceAtLeast(0.dp)
                  else 0.dp,
          ) {
            content(entry)
          }
        }
      }
    }

    Box(modifier = Modifier.fillMaxSize()) {
      NavHost(
          navController = navController,
          startDestination = startDestination,
          modifier = Modifier.fillMaxSize(),
          enterTransition = {
            val initialRoute = initialState.destination.route
            val targetRoute = targetState.destination.route
            val initialIndex = routeOrder.indexOf(initialRoute)
            val targetIndex = routeOrder.indexOf(targetRoute)

            if (isLandscape && initialRoute in routeOrder && targetRoute in routeOrder) {
              val direction = if (routeOrder.indexOf(targetRoute) > routeOrder.indexOf(initialRoute)) 1 else -1
              slideInVertically(animationSpec = AppMotion.spatial(), initialOffsetY = { it * direction }) +
                  fadeIn(animationSpec = AppMotion.effects())
            } else if (initialRoute in detailRoutes && targetRoute in detailRoutes) {
              val direction = targetState.savedStateHandle.get<Int>("detailImageSwipeDirection") ?: 1
              slideInHorizontally(
                  animationSpec = AppMotion.spatial(), initialOffsetX = { it * direction })
            } else if (initialRoute == Routes.GALLERY && targetRoute == Routes.DETAIL) {
              slideInHorizontally(animationSpec = AppMotion.spatial(), initialOffsetX = { it })
            } else if (initialRoute == Routes.SETTINGS &&
                (targetRoute == Routes.MORE_MODELS ||
                    targetRoute == Routes.DUPLICATE_IMAGES ||
                    targetRoute == Routes.EMBEDDING_GEMMA_SKIPPED_IMAGES)) {
              slideInHorizontally(animationSpec = AppMotion.spatial(), initialOffsetX = { it }) +
                  fadeIn(animationSpec = AppMotion.effects())
            } else if (targetRoute in secondaryRoutes) {
              slideInHorizontally(animationSpec = AppMotion.spatial(), initialOffsetX = { it })
            } else if (initialRoute == Routes.SETTINGS && targetRoute == Routes.GALLERY) {
              slideInHorizontally(animationSpec = AppMotion.spatial(), initialOffsetX = { -it }) +
                  fadeIn(animationSpec = AppMotion.effects())
            } else if (initialIndex != -1 && targetIndex != -1 && targetRoute != Routes.GALLERY) {
              if (targetIndex > initialIndex) {
                slideInHorizontally(animationSpec = AppMotion.spatial(), initialOffsetX = { it }) +
                    fadeIn(animationSpec = AppMotion.effects())
              } else {
                slideInHorizontally(animationSpec = AppMotion.spatial(), initialOffsetX = { -it }) +
                    fadeIn(animationSpec = AppMotion.effects())
              }
            } else {
              fadeIn(animationSpec = AppMotion.effects())
            }
          },
          exitTransition = {
            val initialRoute = initialState.destination.route
            val targetRoute = targetState.destination.route
            val initialIndex = routeOrder.indexOf(initialRoute)
            val targetIndex = routeOrder.indexOf(targetRoute)

            if (isLandscape && initialRoute in routeOrder && targetRoute in routeOrder) {
              val direction = if (routeOrder.indexOf(targetRoute) > routeOrder.indexOf(initialRoute)) 1 else -1
              slideOutVertically(animationSpec = AppMotion.spatial(), targetOffsetY = { -it * direction }) +
                  fadeOut(animationSpec = AppMotion.fastEffects())
            } else if (initialRoute in detailRoutes && targetRoute in detailRoutes) {
              val direction = targetState.savedStateHandle.get<Int>("detailImageSwipeDirection") ?: 1
              slideOutHorizontally(
                  animationSpec = AppMotion.spatial(), targetOffsetX = { -it * direction })
            } else if (initialRoute == Routes.GALLERY && targetRoute == Routes.DETAIL) {
              slideOutHorizontally(animationSpec = AppMotion.spatial(), targetOffsetX = { -it })
            } else if (initialRoute == Routes.SETTINGS &&
                (targetRoute == Routes.MORE_MODELS ||
                    targetRoute == Routes.DUPLICATE_IMAGES ||
                    targetRoute == Routes.EMBEDDING_GEMMA_SKIPPED_IMAGES)) {
              slideOutHorizontally(animationSpec = AppMotion.spatial(), targetOffsetX = { -it }) +
                  fadeOut(animationSpec = AppMotion.fastEffects())
            } else if (targetRoute in secondaryRoutes) {
              slideOutHorizontally(animationSpec = AppMotion.spatial(), targetOffsetX = { -it })
            } else if (initialRoute == Routes.SETTINGS && targetRoute == Routes.GALLERY) {
              slideOutHorizontally(animationSpec = AppMotion.spatial(), targetOffsetX = { it }) +
                  fadeOut(animationSpec = AppMotion.fastEffects())
            } else if (initialIndex != -1 && targetIndex != -1 && targetRoute != Routes.GALLERY) {
              if (targetIndex > initialIndex) {
                slideOutHorizontally(animationSpec = AppMotion.spatial(), targetOffsetX = { -it }) +
                    fadeOut(animationSpec = AppMotion.fastEffects())
              } else {
                slideOutHorizontally(animationSpec = AppMotion.spatial(), targetOffsetX = { it }) +
                    fadeOut(animationSpec = AppMotion.fastEffects())
              }
            } else {
              fadeOut(animationSpec = AppMotion.fastEffects())
            }
          },
          popEnterTransition = {
            val initialRoute = initialState.destination.route
            val targetRoute = targetState.destination.route
            if (isLandscape && initialRoute in routeOrder && targetRoute in routeOrder) {
              val direction = if (routeOrder.indexOf(targetRoute) > routeOrder.indexOf(initialRoute)) 1 else -1
              slideInVertically(animationSpec = AppMotion.spatial(), initialOffsetY = { it * direction }) +
                  fadeIn(animationSpec = AppMotion.effects())
            } else if (initialRoute in detailRoutes && targetRoute in detailRoutes) {
              // Undo the direction recorded when the departing image was pushed.
              val direction = initialState.savedStateHandle.get<Int>("detailImageSwipeDirection") ?: 1
              slideInHorizontally(
                  animationSpec = AppMotion.linear(300), initialOffsetX = { -it * direction })
            } else if (initialRoute in secondaryRoutes) {
              // Duration-based slides can be scrubbed by NavHost's predictive back progress.
              slideInHorizontally(animationSpec = AppMotion.linear(300), initialOffsetX = { -it })
            } else if (initialRoute == Routes.SETTINGS && targetRoute == Routes.GALLERY) {
              slideInHorizontally(animationSpec = AppMotion.spatial(), initialOffsetX = { -it }) +
                  fadeIn(animationSpec = AppMotion.effects())
            } else {
              fadeIn(animationSpec = AppMotion.effects())
            }
          },
          popExitTransition = {
            val initialRoute = initialState.destination.route
            val targetRoute = targetState.destination.route
            if (isLandscape && initialRoute in routeOrder && targetRoute in routeOrder) {
              val direction = if (routeOrder.indexOf(targetRoute) > routeOrder.indexOf(initialRoute)) 1 else -1
              slideOutVertically(animationSpec = AppMotion.spatial(), targetOffsetY = { -it * direction }) +
                  fadeOut(animationSpec = AppMotion.fastEffects())
            } else if (initialRoute in detailRoutes && targetRoute in detailRoutes) {
              val direction = initialState.savedStateHandle.get<Int>("detailImageSwipeDirection") ?: 1
              slideOutHorizontally(
                  animationSpec = AppMotion.linear(300), targetOffsetX = { it * direction })
            } else if (initialRoute in secondaryRoutes) {
              slideOutHorizontally(animationSpec = AppMotion.linear(300), targetOffsetX = { it })
            } else if (initialRoute == Routes.SETTINGS && targetRoute == Routes.GALLERY) {
              slideOutHorizontally(animationSpec = AppMotion.spatial(), targetOffsetX = { it }) +
                  fadeOut(animationSpec = AppMotion.fastEffects())
            } else {
              fadeOut(animationSpec = AppMotion.fastEffects())
            }
          },
      ) {
        insetComposable(Routes.ONBOARDING) {
          OnboardingScreen(
              viewModel = viewModel,
              onFinish = {
                viewModel.setHasSeenOnboarding(true)
                navController.navigate(Routes.GALLERY) {
                  popUpTo(Routes.ONBOARDING) { inclusive = true }
                }
              })
        }
        insetComposable(Routes.SETTINGS) {
          SettingsScreen(
              viewModel = viewModel,
              semanticViewModel = semanticViewModel,
              onStartScreenSaver = { isScreenSaverActive = true },
              logoRes = logoRes,
              onRevisitTutorial = { navController.navigate(Routes.ONBOARDING) },
              onOpenMoreModels = { navController.navigate(Routes.MORE_MODELS) },
              onOpenDuplicateImages = { navController.navigate(Routes.DUPLICATE_IMAGES) },
              onOpenSkippedImages = {
                navController.navigate(Routes.EMBEDDING_GEMMA_SKIPPED_IMAGES)
              },
              onOpenLeaderboard = { navController.navigate(Routes.LEADERBOARD) },
          )
        }
        insetComposable(Routes.LEADERBOARD) {
          LeaderboardScreen(
              currentDeviceId = viewModel.benchmarkDeviceId,
              leaderboardOptedIn = benchmarkLeaderboardOptedIn,
              onLeaderboardOptInChanged = viewModel::setBenchmarkLeaderboardOptedIn,
              onBack = { navController.popBackStack() },
          )
        }
        insetComposable(Routes.EMBEDDING_GEMMA_SKIPPED_IMAGES) {
          EmbeddingGemmaSkippedImagesScreen(
              viewModel = viewModel,
              onBack = { navController.popBackStack() },
              onScreenshotClick = { id -> navController.navigate(Routes.detail(id)) },
          )
        }
        insetComposable(Routes.DUPLICATE_IMAGES) {
          DuplicateImagesScreen(
              viewModel = viewModel,
              onBack = { navController.popBackStack() },
              onScreenshotClick = { id -> navController.navigate(Routes.duplicateDetail(id)) },
          )
        }
        insetComposable(Routes.MORE_MODELS) {
          MoreModelsScreen(
              viewModel = viewModel,
              onBack = { navController.popBackStack() },
              onActivateModel = { model ->
                viewModel.setGgufModelAsActive(model)
                navController.popBackStack()
              },
          )
        }
        insetComposable(Routes.GALLERY) {
          GalleryScreen(
              viewModel = viewModel,
              onScreenshotClick = { id -> navController.navigate(Routes.detail(id)) },
              scrollState = galleryScrollState,
              logoRes = logoRes,
              isPickMode = isPickMode,
              focusActiveAnalysisRequest = focusActiveAnalysisRequest,
              onFocusActiveAnalysisHandled = { focusActiveAnalysisRequest = false },
              landscapeControlsVisible = landscapeControlsVisible,
              onHideLandscapeControls = { landscapeControlsVisible = false },
          )
        }
        insetComposable(
            Routes.DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStackEntry ->
          val id = backStackEntry.arguments?.getLong("id") ?: return@insetComposable
          val previousRoute = navController.previousBackStackEntry?.destination?.route
          DetailScreen(
              viewModel = viewModel,
              semanticViewModel = semanticViewModel,
              entryId = id,
              onBack = { navController.popBackStack(Routes.GALLERY, inclusive = false) },
              onTagClick = { tag ->
                viewModel.setSearchQuery(tag)
                if (previousRoute == Routes.DUPLICATE_IMAGES) {
                  navController.popBackStack(Routes.DUPLICATE_IMAGES, inclusive = false)
                } else if (previousRoute == Routes.EMBEDDING_GEMMA_SKIPPED_IMAGES) {
                  navController.popBackStack(
                      Routes.EMBEDDING_GEMMA_SKIPPED_IMAGES, inclusive = false)
                } else {
                  navController.popBackStack(Routes.GALLERY, inclusive = false)
                }
              },
              onScreenshotClick = { matchedId -> navController.navigate(Routes.detail(matchedId)) },
              onImageSwipe = { matchedId, direction ->
                navigateToSwipedDetail(Routes.detail(matchedId), direction)
              },
          )
        }
        insetComposable(
            Routes.DUPLICATE_DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStackEntry ->
          val id = backStackEntry.arguments?.getLong("id") ?: return@insetComposable
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
              onBack = { navController.popBackStack(Routes.GALLERY, inclusive = false) },
              onTagClick = { tag ->
                viewModel.setSearchQuery(tag)
                navController.popBackStack(Routes.DUPLICATE_IMAGES, inclusive = false)
              },
              onScreenshotClick = { matchedId ->
                navController.navigate(Routes.duplicateDetail(matchedId))
              },
              onImageSwipe = { matchedId, direction ->
                navigateToSwipedDetail(Routes.duplicateDetail(matchedId), direction)
              },
              swipeEntryIds = duplicateSwipeEntryIds,
          )
        }
      }

      AnimatedVisibility(
          visible = showBottomBar && isLandscape && landscapeControlsVisible,
          modifier =
              Modifier.align(Alignment.CenterEnd)
                  .onSizeChanged { size ->
                    landscapePillWidth = with(density) { size.width.toDp() }
                  }
                  .windowInsetsPadding(
                      WindowInsets.systemBars
                          .union(WindowInsets.displayCutout)
                          .only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
                  .padding(horizontal = 8.dp, vertical = 8.dp),
          enter =
              slideInHorizontally(animationSpec = AppMotion.timed(240), initialOffsetX = { it }) +
                  fadeIn(animationSpec = AppMotion.effects()),
          exit =
              slideOutHorizontally(animationSpec = AppMotion.linear(240), targetOffsetX = { it }) +
                  fadeOut(animationSpec = AppMotion.fastEffects()),
      ) {
        Surface(
            modifier = Modifier.width(80.dp),
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
          Column(
              modifier = Modifier.padding(vertical = 12.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Image(
                painter = painterResource(id = logoRes),
                contentDescription = stringResource(R.string.logo),
                modifier = Modifier.size(40.dp).align(Alignment.CenterHorizontally),
            )
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
            ExpressiveNavigationItem(
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
                icon =
                    if (currentRoute == Routes.GALLERY) Icons.Rounded.Home else Icons.Outlined.Home,
                label = stringResource(R.string.gallery),
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.width(64.dp),
            )
            ExpressiveNavigationItem(
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
                icon =
                    if (currentRoute == Routes.SETTINGS) Icons.Rounded.Settings
                    else Icons.Outlined.Settings,
                label = stringResource(R.string.settings),
                indicatorColor = MaterialTheme.colorScheme.tertiaryContainer,
                selectedContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.width(64.dp),
            )
          }
        }
      }
      AnimatedVisibility(
          visible = showBottomBar && isLandscape && !landscapeControlsVisible,
          modifier = Modifier.align(Alignment.CenterEnd)
              .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End))
              .padding(end = 8.dp),
          enter = fadeIn(animationSpec = AppMotion.timed(240)),
          exit = fadeOut(animationSpec = AppMotion.linear(120)),
      ) {
        FilledTonalIconButton(
            onClick = hapticOnClick { landscapeControlsVisible = true },
            modifier = Modifier.size(48.dp),
            shape = androidx.compose.foundation.shape.CircleShape,
        ) {
          Icon(Icons.Rounded.ChevronLeft, stringResource(R.string.show_navigation))
        }
      }
    }
  }

  if (isScreenSaverActive) {
    ScreenSaver(viewModel = viewModel, onClose = { isScreenSaverActive = false })
  }
}
