package com.wobbz.fartloop.feature.library.components

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wobbz.fartloop.design.theme.*
import com.wobbz.fartloop.feature.library.model.*
import timber.log.Timber
import java.net.MalformedURLException
import java.net.URL

/**
 * Dialog for entering and validating stream URLs
 *
 * UX Finding: URL input should provide immediate feedback for user confidence
 * Real-time validation prevents submission of obviously invalid URLs
 * Visual indicators (icons, colors) help users understand validation state
 *
 * Validation Strategy Finding: Client-side validation for format, server-side for accessibility
 * Basic URL format checking happens immediately for responsive feedback
 * Network validation (HEAD requests) happens on user confirmation to validate stream accessibility
 *
 * @param isVisible Whether the dialog should be shown
 * @param initialUrl Initial URL text (for editing existing URLs)
 * @param validationResult Current validation state from server
 * @param onDismiss Callback when dialog should be dismissed
 * @param onUrlConfirmed Callback when user confirms a valid URL
 * @param onUrlChanged Callback when URL text changes (for real-time validation)
 */
@Composable
fun UrlInputDialog(
    isVisible: Boolean,
    initialUrl: String = "",
    validationResult: UrlValidationResult? = null,
    onDismiss: () -> Unit,
    onUrlConfirmed: (String) -> Unit,
    onUrlChanged: (String) -> Unit = { },
    modifier: Modifier = Modifier
) {
    if (isVisible) {
        // Local state for URL input
        var urlText by remember(initialUrl) { mutableStateOf(initialUrl) }
        val focusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current

        // Client-side URL validation for immediate feedback
        val clientValidation = remember(urlText) {
            validateUrlFormat(urlText)
        }

        // Combined validation state (client + server)
        // TYPE CONVERSION FINDING: Convert ClientUrlValidation to UrlValidationResult for consistent typing
        // Server validation takes precedence, but client validation provides immediate feedback
        val finalValidation = validationResult ?: when (clientValidation) {
            is ClientUrlValidation.Valid -> UrlValidationResult.Valid(
                contentType = null,
                contentLength = null,
                responseCode = 200  // Assume OK for client-side validation
            )
            is ClientUrlValidation.Invalid -> UrlValidationResult.Invalid(clientValidation.reason)
            is ClientUrlValidation.Empty -> UrlValidationResult.Invalid("URL cannot be empty")
        }
        val isValidFormat = clientValidation is ClientUrlValidation.Valid
        val canSubmit = isValidFormat && validationResult !is UrlValidationResult.Loading

        Dialog(
            onDismissRequest = {
                onDismiss()
                Timber.d("UrlInputDialog dismissed")
            },
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Card(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = FartLooperCustomShapes.bottomSheet,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Dialog header
                    UrlDialogHeader()

                    // URL input field with validation
                    UrlInputField(
                        urlText = urlText,
                        onUrlChanged = { newUrl ->
                            urlText = newUrl
                            onUrlChanged(newUrl)
                        },
                        validation = finalValidation,
                        focusRequester = focusRequester,
                        onSubmit = {
                            if (canSubmit) {
                                onUrlConfirmed(urlText.trim())
                                keyboardController?.hide()
                            }
                        }
                    )

                    // Validation feedback
                    ValidationFeedback(
                        validation = finalValidation,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Action buttons
                    UrlDialogActions(
                        canSubmit = canSubmit,
                        isLoading = validationResult is UrlValidationResult.Loading,
                        onConfirm = {
                            if (canSubmit) {
                                onUrlConfirmed(urlText.trim())
                                Timber.d("UrlInputDialog confirmed: $urlText")
                            }
                        },
                        onCancel = {
                            onDismiss()
                            Timber.d("UrlInputDialog cancelled")
                        }
                    )
                }
            }
        }

        // Auto-focus the input field when dialog appears
        LaunchedEffect(isVisible) {
            if (isVisible) {
                focusRequester.requestFocus()
            }
        }
    }
}

/**
 * Dialog header with title and description
 */
@Composable
private fun UrlDialogHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Add Stream URL",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = "Enter a direct link to an audio stream or file",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * URL input field with real-time validation feedback
 *
 * Input Design Finding: Visual validation state prevents user confusion
 * Color-coded outline and trailing icons immediately indicate validation status
 * Supporting text provides specific error context when validation fails
 */
@Composable
private fun UrlInputField(
    urlText: String,
    onUrlChanged: (String) -> Unit,
    validation: UrlValidationResult,
    focusRequester: FocusRequester,
    onSubmit: () -> Unit
) {
    // Determine field appearance based on validation state
    // TYPE MISMATCH FIXING: validation parameter is UrlValidationResult, not ClientUrlValidation
    // Client validation happens inside this component, server validation comes from outside
    val fieldState = when (validation) {
        is UrlValidationResult.Valid -> FieldState.SUCCESS
        is UrlValidationResult.Invalid -> FieldState.ERROR
        is UrlValidationResult.Loading -> FieldState.LOADING
    }

    OutlinedTextField(
        value = urlText,
        onValueChange = onUrlChanged,
        label = { Text("Stream URL") },
        placeholder = { Text("https://example.com/stream.mp3") },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Link,
                contentDescription = "URL",
                tint = when (fieldState) {
                    FieldState.SUCCESS -> MetricColors.Success
                    FieldState.ERROR -> MetricColors.Error
                    FieldState.LOADING -> MaterialTheme.colorScheme.primary
                    FieldState.VALID -> MaterialTheme.colorScheme.primary
                    FieldState.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        },
        trailingIcon = {
            when (fieldState) {
                FieldState.SUCCESS -> Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Valid URL",
                    tint = MetricColors.Success
                )
                FieldState.ERROR -> Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "Invalid URL",
                    tint = MetricColors.Error
                )
                FieldState.LOADING -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                else -> null
            }
        },
        isError = fieldState == FieldState.ERROR,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = { onSubmit() }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
    )
}

/**
 * Validation feedback text with specific error messages
 *
 * Error Messaging Finding: Specific error messages enable user self-correction
 * Generic "invalid URL" messages don't help users understand what's wrong
 * Contextual guidance (missing protocol, invalid characters) enables quick fixes
 */
@Composable
private fun ValidationFeedback(
    validation: UrlValidationResult,
    modifier: Modifier = Modifier
) {
    // TYPE MISMATCH FIXING: Handle all UrlValidationResult cases explicitly
    // No need for else clause since all sealed class cases are covered
    val (message, color, icon) = when (validation) {
        is UrlValidationResult.Valid -> Triple(
            "Stream accessible • ${validation.contentType ?: "Unknown format"}",
            MetricColors.Success,
            Icons.Default.CheckCircle
        )
        is UrlValidationResult.Invalid -> Triple(
            validation.reason,
            MetricColors.Error,
            Icons.Default.Error
        )
        is UrlValidationResult.Loading -> Triple(
            "Checking stream accessibility...",
            MaterialTheme.colorScheme.primary,
            Icons.Default.CloudSync
        )
    }

    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )

            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = color
            )
        }
    }
}

/**
 * Dialog action buttons (Cancel/Add)
 */
@Composable
private fun UrlDialogActions(
    canSubmit: Boolean,
    isLoading: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
    ) {
        TextButton(onClick = onCancel) {
            Text("Cancel")
        }

        Button(
            onClick = onConfirm,
            enabled = canSubmit && !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(if (isLoading) "Checking..." else "Add Stream")
        }
    }
}

/**
 * Client-side URL format validation
 *
 * Validation Logic Finding: Progressively stricter validation improves UX
 * 1. Empty state (neutral) - no feedback needed
 * 2. Basic format check (immediate) - prevents obvious errors
 * 3. Network validation (on-demand) - confirms accessibility
 */
private sealed interface ClientUrlValidation {
    data object Empty : ClientUrlValidation
    data class Valid(val url: String) : ClientUrlValidation
    data class Invalid(val reason: String) : ClientUrlValidation
}

private fun validateUrlFormat(url: String): ClientUrlValidation {
    return when {
        url.isBlank() -> ClientUrlValidation.Empty

        !url.startsWith("http://") && !url.startsWith("https://") ->
            ClientUrlValidation.Invalid("URL must start with http:// or https://")

        url.length < 10 ->
            ClientUrlValidation.Invalid("URL too short")

        url.contains(" ") ->
            ClientUrlValidation.Invalid("URLs cannot contain spaces")

        else -> try {
            URL(url) // Java URL validation
            ClientUrlValidation.Valid(url)
        } catch (e: MalformedURLException) {
            ClientUrlValidation.Invalid("Invalid URL format: ${e.message?.take(50) ?: "Unknown error"}")
        }
    }
}

/**
 * Field visual state for consistent styling
 */
private enum class FieldState {
    NEUTRAL,    // Default state, no validation
    VALID,      // Valid format, not yet server-validated
    SUCCESS,    // Server validation successful
    ERROR,      // Validation failed
    LOADING     // Server validation in progress
}

/**
 * Previews for different dialog states
 */
@Preview(name = "URL Dialog - Empty")
@Composable
private fun UrlInputDialogEmptyPreview() {
    FartLooperThemePreview {
        UrlInputDialog(
            isVisible = true,
            initialUrl = "",
            validationResult = null,
            onDismiss = { },
            onUrlConfirmed = { }
        )
    }
}

@Preview(name = "URL Dialog - Valid URL")
@Composable
private fun UrlInputDialogValidPreview() {
    FartLooperThemePreview {
        UrlInputDialog(
            isVisible = true,
            initialUrl = "https://example.com/stream.mp3",
            validationResult = UrlValidationResult.Valid(
                contentType = "audio/mpeg",
                contentLength = 2048576L,
                responseCode = 200
            ),
            onDismiss = { },
            onUrlConfirmed = { }
        )
    }
}

@Preview(name = "URL Dialog - Loading")
@Composable
private fun UrlInputDialogLoadingPreview() {
    FartLooperThemePreview {
        UrlInputDialog(
            isVisible = true,
            initialUrl = "https://stream.example.com/radio",
            validationResult = UrlValidationResult.Loading,
            onDismiss = { },
            onUrlConfirmed = { }
        )
    }
}
