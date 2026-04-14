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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import com.deryk.skarmetoo.data.ScreenshotEntry
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
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier =
                Modifier.padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = bottomPadding,
                ),
            enterTransition = {
                when (targetState.destination.route) {
                    Routes.SETTINGS -> slideInHorizontally(initialOffsetX = { 1000 }) + fadeIn()
                    Routes.GALLERY -> slideInHorizontally(initialOffsetX = { -1000 }) + fadeIn()
                    Routes.ONBOARDING -> fadeIn()
                    else -> slideInHorizontally(initialOffsetX = { 1000 }) + fadeIn()
                }
            },
            exitTransition = {
                when (targetState.destination.route) {
                    Routes.SETTINGS -> slideOutHorizontally(targetOffsetX = { -1000 }) + fadeOut()
                    Routes.GALLERY -> slideOutHorizontally(targetOffsetX = { 1000 }) + fadeOut()
                    Routes.ONBOARDING -> fadeOut()
                    else -> slideOutHorizontally(targetOffsetX = { -1000 }) + fadeOut()
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    viewModel: ScreenshotViewModel,
    onScreenshotClick: (Long) -> Unit,
    scrollToTopKey: Int = 0,
    refreshKey: Int = 0,
    logoRes: Int = R.drawable.app_logo,
) {
    val entries by viewModel.entries.collectAsState()
    val isModelReady by viewModel.isModelReady.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val analysisProgress by viewModel.analysisProgress.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val currentImageProgress by viewModel.currentImageProgress.collectAsState()
    var selectedTag by remember { mutableStateOf<String?>(null) }
    val isSortDescending by viewModel.isSortDescending.collectAsState()
    val gridState = rememberLazyStaggeredGridState()

    LaunchedEffect(scrollToTopKey) {
        if (scrollToTopKey > 0) {
            gridState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(refreshKey) {
        if (refreshKey > 0) {
            gridState.animateScrollToItem(0)
            viewModel.refreshImages()
        }
    }

    val allTags =
        remember(entries) {
            entries.flatMap { it.getTagList() }
                .groupBy { it.lowercase() }
                .mapValues { it.value.size }
                .entries
                .sortedByDescending { it.value }
                .map { it.key }
                .take(20)
        }

    val filteredEntries =
        remember(entries, selectedTag, isSortDescending) {
            val filtered =
                if (selectedTag == null) {
                    entries
                } else {
                    entries.filter { entry ->
                        entry.getTagList().any { it.equals(selectedTag, ignoreCase = true) }
                    }
                }

            if (isSortDescending) {
                filtered.sortedByDescending { it.id }
            } else {
                filtered.sortedBy { it.id }
            }
        }

    val pendingCount =
        remember(entries) {
            entries.count { it.summary.isBlank() && !it.isAnalyzing }
        }
    val analyzingCount =
        remember(entries) {
            entries.count { it.isAnalyzing }
        }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { viewModel.refreshImages() },
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(id = logoRes),
                    contentDescription = stringResource(R.string.logo),
                    modifier = Modifier.size(36.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    "Skarmetoo",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.weight(1f))

                if (pendingCount > 0 || analyzingCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    if (isModelReady) viewModel.analyzeUnprocessed()
                                },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (analyzingCount > 0) {
                                CircularProgressIndicator(
                                    progress = { currentImageProgress },
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.error,
                                    trackColor = MaterialTheme.colorScheme.errorContainer,
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.Schedule,
                                    null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.items_left, (pendingCount + analyzingCount).toString()),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .combinedClickable(
                                    onDoubleClick = {
                                        if (isModelReady) viewModel.forceAnalyzeUnprocessed()
                                    },
                                    onClick = {
                                        // Single tap does nothing or maybe toast
                                    },
                                ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.done),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                leadingIcon = {
                    Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.outline)
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Rounded.Close, "Clear")
                        }
                    }
                },
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.outline,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ),
            )

            if (entries.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.padding(vertical = 12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = true,
                            onClick = { viewModel.toggleSortOrder() },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (isSortDescending) Icons.Rounded.South else Icons.Rounded.North,
                                        null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        if (isSortDescending) {
                                            stringResource(R.string.newest_first)
                                        } else {
                                            stringResource(R.string.oldest_first)
                                        },
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            },
                            shape = RoundedCornerShape(20.dp),
                            colors =
                                FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                        )
                    }
                    items(allTags) { tag ->
                        FilterChip(
                            selected = selectedTag == tag,
                            onClick = {
                                selectedTag = if (selectedTag == tag) null else tag
                            },
                            label = {
                                Text(
                                    tag,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            },
                            shape = RoundedCornerShape(20.dp),
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.PhotoLibrary,
                            null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                            modifier = Modifier.size(72.dp),
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.no_screenshots_yet),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.select_albums_in_settings_to_get_started),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                        )
                    }
                }
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    state = gridState,
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalItemSpacing = 10.dp,
                ) {
                    items(filteredEntries, key = { it.id }) { entry ->
                        ScreenshotGridItem(
                            entry = entry,
                            currentImageProgress = if (entry.isAnalyzing) currentImageProgress else 0f,
                            onClick = { onScreenshotClick(entry.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ScreenshotGridItem(
    entry: ScreenshotEntry,
    currentImageProgress: Float = 0f,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        Column {
            if (entry.imageUri.isNotBlank()) {
                AsyncImage(
                    model = entry.imageUri,
                    contentDescription = entry.summary,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                    contentScale = ContentScale.FillWidth,
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.BrokenImage,
                        null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            if (entry.isAnalyzing) {
                LinearProgressIndicator(
                    progress = { currentImageProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }

            Column(modifier = Modifier.padding(10.dp)) {
                if (entry.summary.isNotBlank()) {
                    Text(
                        text = entry.summary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 18.sp,
                    )
                } else if (entry.isAnalyzing) {
                    Text(
                        stringResource(R.string.analyzing) + "...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        stringResource(R.string.not_analyzed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                if (entry.tags.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = entry.getTagList().joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
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
