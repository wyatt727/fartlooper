@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.wobbz.fartloop.feature.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.wobbz.fartloop.design.theme.*
import com.wobbz.fartloop.feature.library.components.*
import com.wobbz.fartloop.feature.library.model.*
import timber.log.Timber

/**
 * Library screen for managing audio clips and media sources
 *
 * Architecture Finding: Centralized state management simplifies complex UI interactions
 * File picking, URL validation, waveform analysis all coordinate through single state
 * This prevents race conditions and inconsistent UI states during async operations
 *
 * UX Design Finding: Progressive disclosure reduces cognitive load
 * Empty state → Add options → Selection management → Preview/details
 * Each state shows only relevant actions to guide user through their tasks
 *
 * Performance Finding: Lazy loading for large clip libraries is essential
 * Waveform generation and file analysis are expensive operations
 * Virtualized list with thumbnail caching prevents UI lag with many clips
 *
 * @param uiState Current state of the library including clips and loading states
 * @param onClipSelected Callback when user selects a clip for playback
 * @param onClipRemoved Callback when user removes a clip from library
 * @param onFilePickerResult Callback with result from file picker (SAF)
 * @param onUrlValidated Callback when URL validation completes
 * @param onShowUrlDialog Callback to show URL input dialog
 * @param onDismissUrlDialog Callback to hide URL input dialog
 * @param modifier Optional modifier for the screen container
 */
@Composable
fun LibraryScreen(
    uiState: LibraryUiState,
    onClipSelected: (ClipItem) -> Unit,
    onClipRemoved: (ClipItem) -> Unit,
    onFilePickerResult: (Uri) -> Unit,
    onUrlValidated: (String) -> Unit,
    onShowUrlDialog: () -> Unit,
    onDismissUrlDialog: () -> Unit,
    onRetryLoad: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // File picker launcher for Storage Access Framework
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { selectedUri ->
            onFilePickerResult(selectedUri)
            Timber.d("LibraryScreen file picked: $selectedUri")
        }
    }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Screen header with title and actions
        LibraryScreenHeader(
            hasClips = uiState.hasLocalClips,
            onFilePickerClick = {
                // Launch SAF file picker for audio files
                // MIME type filter Finding: Specific audio/* filter improves user experience
                // Users see only relevant files instead of navigating through documents/images
                filePickerLauncher.launch(arrayOf("audio/*"))
                Timber.d("LibraryScreen launched file picker")
            },
            onUrlInputClick = {
                onShowUrlDialog()
                Timber.d("LibraryScreen showing URL dialog")
            }
        )

        // Main content area
        Box(modifier = Modifier.weight(1f)) {
            when {
                // Loading state
                uiState.isLoading -> {
                    LibraryLoadingState(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                // Error state
                uiState.hasError -> {
                    LibraryErrorState(
                        errorMessage = uiState.errorMessage ?: "Unknown error",
                        onRetry = onRetryLoad,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                // Empty state (no clips)
                uiState.availableClips.isEmpty() -> {
                    LibraryEmptyState(
                        onFilePickerClick = {
                            filePickerLauncher.launch(arrayOf("audio/*"))
                        },
                        onUrlInputClick = onShowUrlDialog,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                // Content state (clips available)
                else -> {
                    ClipLibraryContent(
                        clips = uiState.availableClips,
                        currentSelection = uiState.currentMediaSource,
                        waveformData = uiState.waveformData,
                        onClipSelected = onClipSelected,
                        onClipRemoved = onClipRemoved,
                        listState = listState,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    // URL input dialog overlay
    UrlInputDialog(
        isVisible = uiState.isUrlDialogVisible,
        initialUrl = uiState.urlInputText,
        onDismiss = onDismissUrlDialog,
        onUrlConfirmed = { url ->
            onUrlValidated(url)
            onDismissUrlDialog()
        }
    )
}

/**
 * Screen header with title and action buttons
 *
 * Header Design Finding: Context-aware actions improve discoverability
 * When library is empty, prominently show add actions
 * When library has content, actions are available but less prominent
 */
@Composable
private fun LibraryScreenHeader(
    hasClips: Boolean,
    onFilePickerClick: () -> Unit,
    onUrlInputClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Library",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (hasClips) {
                Text(
                    text = "Select audio clip for blasting",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Add content actions
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Add file button
            IconButton(
                onClick = onFilePickerClick
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "Add local file",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // Add URL button
            IconButton(
                onClick = onUrlInputClick
            ) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = "Add stream URL",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * Main content area showing clip library
 *
 * List Performance Finding: LazyColumn with key stability prevents recomposition issues
 * ClipItem.id provides stable keys for efficient list updates during selection changes
 * Waveform data is shared between selected items to prevent redundant calculations
 */
@Composable
private fun ClipLibraryContent(
    clips: List<ClipItem>,
    currentSelection: MediaSource?,
    waveformData: WaveformData?,
    onClipSelected: (ClipItem) -> Unit,
    onClipRemoved: (ClipItem) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            items = clips,
            key = { clip -> clip.id }
        ) { clip ->
            // Check if this clip is currently selected
            val isSelected = when (currentSelection) {
                is MediaSource.Local -> clip.source is MediaSource.Local &&
                    (clip.source as MediaSource.Local).file.absolutePath == currentSelection.file.absolutePath
                is MediaSource.Remote -> clip.source is MediaSource.Remote &&
                    (clip.source as MediaSource.Remote).url == currentSelection.url
                null -> false
            }

            // Use waveform data only for selected clip
            val clipWaveformData = if (isSelected) waveformData else null

            ClipThumbnail(
                clipItem = clip,
                isSelected = isSelected,
                waveformData = clipWaveformData,
                onClick = onClipSelected,
                onRemove = onClipRemoved,
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItemPlacement() // Smooth reordering animations
            )
        }

        // Add content action item at the bottom
        item(key = "add_content_footer") {
            AddContentFooter(
                onFilePickerClick = {
                    // File picker handled at screen level
                },
                onUrlInputClick = {
                    // URL input handled at screen level
                }
            )
        }
    }
}

/**
 * Loading state display
 */
@Composable
private fun LibraryLoadingState(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "Loading library...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Error state display with retry option
 */
@Composable
private fun LibraryErrorState(
    errorMessage: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = "Error",
            tint = MetricColors.Error,
            modifier = Modifier.size(48.dp)
        )

        Text(
            text = "Error loading library",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

/**
 * Empty state with prominent add actions
 *
 * Empty State Design Finding: Clear guidance prevents user confusion
 * Large, friendly icons and descriptive text help users understand next steps
 * Multiple pathways (file or URL) accommodate different user preferences
 */
@Composable
private fun LibraryEmptyState(
    onFilePickerClick: () -> Unit,
    onUrlInputClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Large friendly icon
        Icon(
            imageVector = Icons.Default.LibraryMusic,
            contentDescription = "Empty library",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(80.dp)
        )

        // Descriptive text
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "No audio clips yet",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Add local files or stream URLs to get started",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        // Action cards
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Add local file card
            Card(
                onClick = onFilePickerClick,
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Add file",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp)
                    )

                    Text(
                        text = "Add File",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Pick from device storage",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Add URL card
            Card(
                onClick = onUrlInputClick,
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = "Add URL",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(32.dp)
                    )

                    Text(
                        text = "Add URL",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "Stream from web",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Footer with additional add content options
 */
@Composable
private fun AddContentFooter(
    onFilePickerClick: () -> Unit,
    onUrlInputClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add more",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "Add more clips",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )

            TextButton(onClick = onFilePickerClick) {
                Text("File")
            }

            TextButton(onClick = onUrlInputClick) {
                Text("URL")
            }
        }
    }
}

/**
 * Preview for LibraryScreen in different states
 */
@Preview(name = "Library Screen - Empty")
@Composable
private fun LibraryScreenEmptyPreview() {
    FartLooperThemePreview {
        LibraryScreen(
            uiState = LibraryUiState(),
            onClipSelected = { },
            onClipRemoved = { },
            onFilePickerResult = { },
            onUrlValidated = { },
            onShowUrlDialog = { },
            onDismissUrlDialog = { }
        )
    }
}

@Preview(name = "Library Screen - With Clips")
@Composable
private fun LibraryScreenContentPreview() {
    FartLooperThemePreview {
        LibraryScreen(
            uiState = LibraryUiState(
                availableClips = listOf(
                    ClipItem(
                        id = "1",
                        source = MediaSource.Local(
                            file = java.io.File("/mock/fart.mp3"),
                            displayName = "Fart Sound.mp3",
                            sizeBytes = 2048576,
                            mimeType = "audio/mpeg"
                        ),
                        duration = 45000L,
                        isCurrentSelection = true
                    ),
                    ClipItem(
                        id = "2",
                        source = MediaSource.Remote(
                            url = "https://example.com/stream.mp3",
                            displayName = "Radio Stream"
                        )
                    )
                ),
                currentMediaSource = MediaSource.Local(
                    file = java.io.File("/mock/fart.mp3"),
                    displayName = "Fart Sound.mp3",
                    sizeBytes = 2048576
                ),
                waveformData = WaveformData.mock()
            ),
            onClipSelected = { },
            onClipRemoved = { },
            onFilePickerResult = { },
            onUrlValidated = { },
            onShowUrlDialog = { },
            onDismissUrlDialog = { }
        )
    }
}
