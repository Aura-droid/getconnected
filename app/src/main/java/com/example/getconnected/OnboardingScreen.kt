package com.example.getconnected

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * First-run work-preference wizard shown once after registration (skippable).
 * Account type (Employer vs Seeker) is fixed at sign-up and cannot be changed here.
 *
 * Step 1: job fields (taaluma), multi-select.
 * Step 2: qualification level + experience (optional, individually skippable).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    startIntent: String,
    onComplete: (intent: String, fields: List<String>, level: String, experience: String) -> Unit,
    onSkip: () -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    val intent = startIntent
    var selectedFields by remember { mutableStateOf<List<String>>(emptyList()) }
    var level by remember { mutableStateOf("") }
    var experience by remember { mutableStateOf("") }

    BackHandler {
        if (step > 0) step-- else onSkip()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Get Connected",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Tupendekeze fursa bora kwako",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))

        LinearProgressIndicator(
            progress = { (step + 1) / 2f },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Hatua ${step + 1} kati ya 2",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        when (step) {
            0 -> {
                Text(
                    text = "Tuupe taaluma zako",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Chagua zote unazofanya — tutaonyesha fursa kulingana na hizi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                FieldChips(selected = selectedFields, onToggle = { field ->
                    selectedFields =
                        if (selectedFields.contains(field)) selectedFields - field
                        else selectedFields + field
                })
            }

            1 -> {
                Text(
                    text = "Kiwango chako na uzoefu",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Hiki ni chaguo — tukisahau bado utapendekezwa kazi zako.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                LevelChips(selected = level, onSelect = { level = it })
                Spacer(modifier = Modifier.height(16.dp))
                ExperienceChips(selected = experience, onSelect = { experience = it })
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (step == 0) {
                TextButton(onClick = onSkip) {
                    Text("Ruka kwa sasa")
                }
            } else {
                TextButton(onClick = { step-- }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Rudi")
                }
            }

            Button(
                onClick = {
                    when (step) {
                        0 -> if (selectedFields.isNotEmpty()) step++
                        1 -> onComplete(intent, selectedFields, level, experience)
                    }
                },
                enabled = when (step) {
                    0 -> selectedFields.isNotEmpty()
                    else -> level.isNotBlank()
                },
                shape = MaterialTheme.shapes.medium
            ) {
                Text(if (step == 1) "Maliza" else "Endelea")
            }
        }

        if (step == 1) {
            TextButton(
                onClick = { onComplete(intent, selectedFields, level, experience) },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Ruka hatua hii", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/** Multi-select field chips laid out two per row. */
@Composable
fun FieldChips(
    selected: List<String>,
    onToggle: (String) -> Unit,
    single: Boolean = false
) {
    WorkPreferences.FIELDS.chunked(2).forEach { row ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            row.forEach { field ->
                val isSelected = selected.contains(field)
                FilterChip(
                    selected = isSelected,
                    onClick = { onToggle(field) },
                    label = {
                        Text(
                            text = field,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            if (row.size == 1) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Single-select qualification levels. */
@Composable
fun LevelChips(selected: String, onSelect: (String) -> Unit) {
    Text(
        text = "Kiwango cha taaluma",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(8.dp))
    WorkPreferences.LEVELS.chunked(2).forEach { row ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            row.forEach { level ->
                FilterChip(
                    selected = selected == level,
                    onClick = { onSelect(level) },
                    label = {
                        Text(
                            text = WorkPreferences.levelLabel(level),
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            if (row.size == 1) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Single-select experience chips. */
@Composable
fun ExperienceChips(selected: String, onSelect: (String) -> Unit) {
    Text(
        text = "Uzoefu",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(8.dp))
    WorkPreferences.EXPERIENCES.chunked(2).forEach { row ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            row.forEach { exp ->
                FilterChip(
                    selected = selected == exp,
                    onClick = { onSelect(exp) },
                    label = {
                        Text(
                            text = WorkPreferences.experienceLabel(exp),
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            if (row.size == 1) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * Reusable "work preferences" editor used by Profile → Kigezo cha Kazi.
 * Account type (intent) is locked and cannot be switched here.
 */
@Composable
fun WorkPreferencesEditor(
    initialIntent: String,
    initialFields: List<String>,
    initialLevel: String,
    initialExperience: String,
    onSave: (intent: String, fields: List<String>, level: String, experience: String) -> Unit,
    onDismiss: () -> Unit
) {
    val intent = initialIntent
    var fields by remember(initialFields) { mutableStateOf(initialFields) }
    var level by remember(initialLevel) { mutableStateOf(initialLevel) }
    var experience by remember(initialExperience) { mutableStateOf(initialExperience) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kigezo cha Kazi") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 4.dp)
            ) {
                Text(
                    text = "Aina ya Akaunti: " + if (intent == "EMPLOYER") "Mwajiri" else "Mtafuta Kazi",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Akaunti haiwezi kubadilishwa mtindo. Ili kubadilisha, tafadhali fungua akaunti mpya.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Taaluma zangu",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                FieldChips(selected = fields, onToggle = { field ->
                    fields =
                        if (fields.contains(field)) fields - field
                        else fields + field
                })

                Spacer(modifier = Modifier.height(8.dp))
                LevelChips(selected = level, onSelect = { level = it })

                Spacer(modifier = Modifier.height(8.dp))
                ExperienceChips(selected = experience, onSelect = { experience = it })
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(intent, fields, level, experience) },
                enabled = fields.isNotEmpty()
            ) {
                Text("Hifadhi")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Ghairi") }
        }
    )
}
