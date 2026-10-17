@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber")

package com.cococopi.andromcp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cococopi.andromcp.agent.AgentChatItem
import com.cococopi.andromcp.agent.AgentViewModel
import com.cococopi.andromcp.agent.ToolStepState
import com.cococopi.andromcp.agent.llm.LlmConfig
import com.cococopi.andromcp.agent.llm.ModelProvider

/**
 * The in-app agent: one chat that can actually act on the phone.
 *
 * Tool calls appear inline as they run, irreversible ones stop at a card that names the tool and its
 * arguments before anything happens, and the gear opens the provider/model/key settings — the same
 * configuration the loop reads at the start of every turn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    modifier: Modifier = Modifier,
    viewModel: AgentViewModel = hiltViewModel(),
) {
    val chatItems by viewModel.items.collectAsStateWithLifecycle()
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()
    val pendingApproval by viewModel.pendingApproval.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Agent") },
            actions = {
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Agent settings")
                }
            },
            windowInsets = WindowInsets(0),
        )

        LazyColumn(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(chatItems) { item -> ChatRow(item) }
        }

        pendingApproval?.let { call ->
            ApprovalCard(
                toolName = call.name,
                arguments = call.arguments.toString(),
                onAllow = { viewModel.resolveApproval(true) },
                onDeny = { viewModel.resolveApproval(false) },
            )
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask the phone to do something") },
                maxLines = 3,
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (isRunning) {
                IconButton(onClick = viewModel::stop) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            } else {
                IconButton(
                    onClick = {
                        viewModel.send(input)
                        input = ""
                    },
                    enabled = input.isNotBlank(),
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }

    if (showSettings) {
        ProviderSettingsDialog(
            initial = config,
            onDismiss = { showSettings = false },
            onSave = { provider, model, baseUrl, apiKey ->
                viewModel.saveProvider(provider, model, baseUrl, apiKey)
                showSettings = false
            },
        )
    }
}

@Composable
private fun ChatRow(item: AgentChatItem) {
    when (item) {
        is AgentChatItem.UserMessage -> {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    text = item.text,
                    modifier =
                        Modifier
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
                            .padding(10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        is AgentChatItem.AssistantMessage -> {
            Text(text = item.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
        }

        is AgentChatItem.ToolStep -> {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(8.dp),
            ) {
                Text(
                    text = "${stateLabel(item.state)} ${item.name}",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (item.detail.isNotBlank()) {
                    Text(
                        text = item.detail,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        is AgentChatItem.Failure -> {
            Text(
                text = item.text,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun stateLabel(state: ToolStepState): String =
    when (state) {
        ToolStepState.RUNNING -> "running"
        ToolStepState.OK -> "done"
        ToolStepState.ERROR -> "failed"
        ToolStepState.DENIED -> "refused"
    }

@Composable
private fun ApprovalCard(
    toolName: String,
    arguments: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = "Approve this action?", style = MaterialTheme.typography.titleSmall)
            Text(text = toolName, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = arguments.take(300),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDeny) { Text("Refuse") }
                Button(onClick = onAllow) { Text("Allow once") }
            }
        }
    }
}

@Composable
private fun ProviderSettingsDialog(
    initial: LlmConfig,
    onDismiss: () -> Unit,
    onSave: (ModelProvider, String, String, String) -> Unit,
) {
    var provider by remember { mutableStateOf(initial.provider) }
    var model by remember { mutableStateOf(initial.effectiveModel) }
    var baseUrl by remember { mutableStateOf(initial.effectiveBaseUrl) }
    var apiKey by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agent model") },
        text = {
            Column {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { menuOpen = true }
                            .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = provider.displayName, modifier = Modifier.weight(1f))
                    Text(text = "change", style = MaterialTheme.typography.labelMedium)
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        ModelProvider.entries.forEach { entry ->
                            DropdownMenuItem(
                                text = { Text(entry.displayName) },
                                onClick = {
                                    provider = entry
                                    model = entry.defaultModel
                                    baseUrl = entry.defaultBaseUrl
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Endpoint") },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key (leave blank to keep the stored one)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (!provider.requiresApiKey) {
                    Text(
                        text = "This provider needs no key.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(provider, model, baseUrl, apiKey) }) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
