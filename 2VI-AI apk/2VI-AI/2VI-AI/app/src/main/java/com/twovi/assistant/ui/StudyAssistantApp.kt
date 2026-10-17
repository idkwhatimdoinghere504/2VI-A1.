package com.twovi.assistant.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.twovi.assistant.BuildConfig
import com.twovi.assistant.ai.GeminiTutor
import com.twovi.assistant.ai.StudyImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.UUID

private enum class StudyTab(val title: String) {
    Home("Learn"),
    History("History"),
    Tools("Study tools"),
    Profile("Profile"),
}

private data class StudyAnswer(
    val question: String,
    val explanation: String,
    val subject: String,
    val saved: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Composable
fun StudyAssistantApp(initialQuestion: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tutor = remember { GeminiTutor() }
    val history = remember { mutableStateListOf<StudyAnswer>() }
    var tab by remember { mutableStateOf(StudyTab.Home) }
    var subject by remember { mutableStateOf("Math") }
    var question by remember(initialQuestion) { mutableStateOf(initialQuestion) }
    var apiKey by remember { mutableStateOf(BuildConfig.GEMINI_API_KEY_BUILT_IN) }
    var attachedImage by remember { mutableStateOf<StudyImage?>(null) }
    var answer by remember { mutableStateOf<StudyAnswer?>(null) }
    var busy by remember { mutableStateOf(false) }
    var attaching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var keyDialog by remember { mutableStateOf(false) }
    var pendingCapture by remember { mutableStateOf<File?>(null) }

    fun readPhoto(uri: Uri) {
        scope.launch {
            attaching = true
            message = null
            try {
                attachedImage = GeminiTutor.readImage(context, uri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: IOException) {
                message = exception.message ?: "Could not read that photo. Try another image."
            } catch (exception: SecurityException) {
                message = exception.message ?: "Could not read that photo. Try another image."
            } finally {
                attaching = false
                if (uri.scheme == "file") uri.path?.let(::File)?.delete()
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) readPhoto(uri)
    }
    val camera = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { captured ->
        val file = pendingCapture
        pendingCapture = null
        if (captured && file != null) {
            readPhoto(Uri.fromFile(file))
        } else {
            file?.delete()
        }
    }

    val voiceInput = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val recognized = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!recognized.isNullOrBlank()) {
                question = recognized
                message = null
            }
        }
    }

    fun startVoiceInput() {
        try {
            voiceInput.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your study question"),
            )
        } catch (_: ActivityNotFoundException) {
            message = "Voice input is not available on this device."
        }
    }

    fun showCameraError(error: String) {
        pendingCapture?.delete()
        pendingCapture = null
        message = error
    }

    fun launchCamera() {
        try {
            val directory = File(context.cacheDir, "scans")
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Could not prepare a photo for the camera.")
            }
            val file = File(directory, "scan-${UUID.randomUUID()}.jpg")
            pendingCapture = file
            val uri = FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file,
            )
            camera.launch(uri)
        } catch (exception: IOException) {
            showCameraError(exception.message ?: "Could not prepare a photo for the camera.")
        } catch (exception: IllegalArgumentException) {
            showCameraError(exception.message ?: "Could not prepare a photo for the camera.")
        } catch (exception: ActivityNotFoundException) {
            showCameraError("No camera app is available on this device.")
        } catch (exception: SecurityException) {
            showCameraError(exception.message ?: "Could not open the camera.")
        }
    }

    fun solveQuestion() {
        if (question.isBlank() && attachedImage == null) {
            message = "Type a question or add a photo to get started."
            return
        }
        val key = apiKey.trim()
        if (key.isEmpty()) {
            message = "Add your Gemini API key in Profile to get an explanation."
            return
        }
        val sentQuestion = question.trim().ifBlank { "Explain the question in my photo." }
        val sentImage = attachedImage
        val sentSubject = subject
        busy = true
        message = null
        answer = null
        scope.launch {
            try {
                val explanation = tutor.solve(key, sentSubject, sentQuestion, sentImage)
                val solved = StudyAnswer(
                    question = sentQuestion,
                    explanation = explanation,
                    subject = sentSubject,
                )
                history.add(0, solved)
                answer = solved
                question = ""
                attachedImage = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: IOException) {
                message = exception.message ?: "We couldn't solve that right now. Please try again."
            } finally {
                busy = false
            }
        }
    }

    fun toggleSaved(item: StudyAnswer) {
        val index = history.indexOfFirst { it.createdAt == item.createdAt }
        if (index >= 0) {
            val updated = item.copy(saved = !item.saved)
            history[index] = updated
            if (answer?.createdAt == item.createdAt) answer = updated
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                StudyTab.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = tab == destination,
                        onClick = {
                            tab = destination
                            message = null
                        },
                        icon = {
                            Icon(
                                imageVector = when (destination) {
                                    StudyTab.Home -> Icons.Filled.Home
                                    StudyTab.History -> Icons.Filled.History
                                    StudyTab.Tools -> Icons.Filled.MenuBook
                                    StudyTab.Profile -> Icons.Filled.Person
                                },
                                contentDescription = destination.title,
                            )
                        },
                        label = { Text(destination.title) },
                    )
                }
            }
        },
    ) { insets ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .imePadding(),
        ) {
            when (tab) {
                StudyTab.Home -> HomeScreen(
                    subject = subject,
                    onSubjectChange = { subject = it },
                    question = question,
                    onQuestionChange = { question = it },
                    imageAttached = attachedImage != null,
                    attaching = attaching,
                    busy = busy,
                    answer = answer,
                    message = message,
                    onCamera = ::launchCamera,
                    onPickPhoto = { photoPicker.launch("image/*") },
                    onRemovePhoto = { attachedImage = null },
                    onVoice = ::startVoiceInput,
                    onSolve = ::solveQuestion,
                    onSaveAnswer = { answer?.let(::toggleSaved) },
                    apiConfigured = apiKey.isNotBlank(),
                    onOpenProfile = { tab = StudyTab.Profile },
                )
                StudyTab.History -> HistoryScreen(
                    answers = history,
                    onOpen = {
                        answer = it
                        tab = StudyTab.Home
                    },
                    onSave = ::toggleSaved,
                )
                StudyTab.Tools -> ToolsScreen(
                    onSelect = {
                        question = it
                        answer = null
                        message = null
                        tab = StudyTab.Home
                    },
                )
                StudyTab.Profile -> ProfileScreen(
                    hasKey = apiKey.isNotBlank(),
                    onEditKey = { keyDialog = true },
                )
            }
        }
    }

    if (keyDialog) {
        ApiKeyDialog(
            initialKey = apiKey,
            onDismiss = { keyDialog = false },
            onSave = {
                apiKey = it.trim()
                keyDialog = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(
    subject: String,
    onSubjectChange: (String) -> Unit,
    question: String,
    onQuestionChange: (String) -> Unit,
    imageAttached: Boolean,
    attaching: Boolean,
    busy: Boolean,
    answer: StudyAnswer?,
    message: String?,
    onCamera: () -> Unit,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onVoice: () -> Unit,
    onSolve: () -> Unit,
    onSaveAnswer: () -> Unit,
    apiConfigured: Boolean,
    onOpenProfile: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        BrandHeader(onSettings = onOpenProfile)
        Spacer(Modifier.height(26.dp))
        Text(
            text = "A little help goes\na long way.",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            lineHeight = 34.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Ask a question. Understand every step.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))
        SubjectPicker(subject = subject, onSubjectChange = onSubjectChange)
        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = question,
                    onValueChange = onQuestionChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(138.dp),
                    placeholder = {
                        Text("Type a question or tell me what feels confusing…")
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    trailingIcon = {
                        IconButton(onClick = onVoice, enabled = !busy) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = "Speak a question",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
                if (imageAttached || attaching) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (attaching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Preparing photo…", style = MaterialTheme.typography.labelMedium)
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Image,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Question photo attached", style = MaterialTheme.typography.labelMedium)
                            IconButton(onClick = onRemovePhoto, modifier = Modifier.size(34.dp)) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Remove photo",
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = onCamera,
                        enabled = !busy && !attaching,
                        shape = RoundedCornerShape(13.dp),
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Scan")
                    }
                    FilledTonalButton(
                        onClick = onPickPhoto,
                        enabled = !busy && !attaching,
                        shape = RoundedCornerShape(13.dp),
                    ) {
                        Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Photo")
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = onSolve,
                        enabled = !busy && !attaching,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Solve question",
                                tint = Color.White,
                            )
                        }
                    }
                }
                if (!apiConfigured) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Add a Gemini API key in Profile to get started.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(onClick = onOpenProfile),
                    )
                }
            }
        }

        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        Spacer(Modifier.height(22.dp))
        Text("Try asking", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "Explain fractions simply",
                "Balance a chemical equation",
                "Help me understand gravity",
            ).forEach { sample ->
                SuggestionChip(text = sample, onClick = { onQuestionChange(sample) })
            }
        }

        if (answer != null) {
            Spacer(Modifier.height(24.dp))
            AnswerCard(answer = answer, onSave = onSaveAnswer)
        } else {
            Spacer(Modifier.height(22.dp))
            FeatureNote()
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun BrandHeader(onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("2V", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("2VI", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(
                "STUDY COACH",
                fontWeight = FontWeight.SemiBold,
                fontSize = 9.sp,
                letterSpacing = 1.3.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Open profile and settings")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubjectPicker(subject: String, onSubjectChange: (String) -> Unit) {
    val subjects = listOf("Math", "Physics", "Chemistry", "Biology", "Other")
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        subjects.forEach { item ->
            val selected = item == subject
            Text(
                text = item,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface,
                    )
                    .clickable { onSubjectChange(item) }
                    .padding(horizontal = 15.dp, vertical = 9.dp),
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun FeatureNote() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Lightbulb,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Learn it, don’t just look it up", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(
                    "2VI breaks problems into steps so the idea sticks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AnswerCard(answer: StudyAnswer, onSave: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "2VI explanation · ${answer.subject}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onSave) {
                    Icon(
                        imageVector = Icons.Filled.Bookmark,
                        contentDescription = if (answer.saved) "Remove saved answer" else "Save answer",
                        tint = if (answer.saved) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (answer.question.isNotBlank()) {
                Text(
                    answer.question,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Divider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
            }
            Text(
                answer.explanation,
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 25.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun HistoryScreen(
    answers: List<StudyAnswer>,
    onOpen: (StudyAnswer) -> Unit,
    onSave: (StudyAnswer) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text("Your learning", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(
            "Pick up where you left off.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        if (answers.isEmpty()) {
            EmptyState(
                icon = { Icon(Icons.Filled.History, contentDescription = null) },
                title = "Your questions will show up here",
                description = "Solve a problem and you can come back to its explanation anytime.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(answers, key = { it.createdAt }) { item ->
                    HistoryCard(item = item, onOpen = { onOpen(item) }, onSave = { onSave(item) })
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(item: StudyAnswer, onOpen: () -> Unit, onSave: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(start = 15.dp, top = 12.dp, end = 6.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.subject.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text(item.question, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            }
            IconButton(onClick = onSave) {
                Icon(
                    Icons.Filled.Bookmark,
                    contentDescription = if (item.saved) "Remove saved answer" else "Save answer",
                    tint = if (item.saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class StudyTool(
    val title: String,
    val description: String,
    val prompt: String,
)

@Composable
private fun ToolsScreen(onSelect: (String) -> Unit) {
    val tools = listOf(
        StudyTool(
            "Explain a concept",
            "Get a plain-language explanation with examples.",
            "Explain this concept simply, then give me one example: ",
        ),
        StudyTool(
            "Make a quick quiz",
            "Practice recall with a few questions and answers.",
            "Create a short 5-question quiz about: ",
        ),
        StudyTool(
            "Build a study plan",
            "Turn a topic and deadline into manageable study sessions.",
            "Create a realistic study plan for learning: ",
        ),
        StudyTool(
            "Review my writing",
            "Get clear feedback on grammar, structure, and tone.",
            "Review my writing for clarity and grammar. Explain your suggested edits: ",
        ),
    )
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text("Study tools", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Small ways to make your next study session easier.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            items(tools) { tool ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(tool.prompt) },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tool.title, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(3.dp))
                            Text(tool.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(hasKey: Boolean, onEditKey: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text("Profile", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("2VI Study Coach", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (hasKey) "AI tutor ready" else "Connect an AI tutor",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (hasKey) {
                        Icon(Icons.Filled.Check, contentDescription = "Gemini API key configured", tint = MaterialTheme.colorScheme.secondary)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Divider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onEditKey) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (hasKey) "Manage Gemini API key" else "Add Gemini API key")
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("About your key", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Text(
            "A key entered here stays in app memory for this session. A key supplied at build time is packaged with the app. Questions and attached photos are sent to Gemini when you ask for an explanation.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 22.sp,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Get a key from Google AI Studio. Usage and billing are subject to your Google account and API plan.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyState(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 70.dp, start = 18.dp, end = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Spacer(Modifier.height(16.dp))
        Text(title, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            description,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ApiKeyDialog(
    initialKey: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var key by remember(initialKey) { mutableStateOf(initialKey) }
    var linkError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect Gemini") },
        text = {
            Column {
                Text(
                    "Paste an API key from Google AI Studio. A key entered here stays in memory until you close the app.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Gemini API key") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                TextButton(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://aistudio.google.com/app/apikey"),
                                ),
                            )
                            linkError = null
                        } catch (_: ActivityNotFoundException) {
                            linkError = "No browser is available to open Google AI Studio."
                        }
                    },
                ) {
                    Text("Get a key from Google AI Studio")
                }
                if (linkError != null) {
                    Text(
                        linkError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(key) }) {
                Text("Save for this session")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
