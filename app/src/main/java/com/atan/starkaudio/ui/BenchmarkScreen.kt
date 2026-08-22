package com.atan.starkaudio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.atan.starkaudio.StarkSignalApplication
import com.atan.starkaudio.R
import com.atan.starkaudio.benchmark.BenchmarkApproval
import com.atan.starkaudio.benchmark.BenchmarkReport
import com.atan.starkaudio.benchmark.BenchmarkRunner
import com.atan.starkaudio.transcription.BundledModelInstaller
import com.atan.starkaudio.ui.theme.Amber
import com.atan.starkaudio.ui.theme.InkMuted
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/** Present only when the benchmark build supplies a verified candidate model. */
@Composable
fun BenchmarkScreen() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val graph = (context.applicationContext as StarkSignalApplication).services
    val scope = rememberCoroutineScope()
    val model = remember { BundledModelInstaller.manifestOrNull() }
    val runner = remember(model) { model?.let { BenchmarkRunner(context, it, graph.benchmarkRuns) } }
    val readyCopy = stringResource(R.string.benchmark_ready)
    val savedCopy = stringResource(R.string.benchmark_report_saved)
    val saveFailedCopy = stringResource(R.string.benchmark_report_save_failed)
    val runningCopy = stringResource(R.string.benchmark_running)
    val interruptedCopy = stringResource(R.string.benchmark_interrupted)
    val stoppingCopy = stringResource(R.string.benchmark_stopping)
    val reliabilityRunningCopy = stringResource(R.string.benchmark_reliability_running)
    val reliabilityCompleteCopy = stringResource(R.string.benchmark_reliability_complete)
    val reliabilityStoppedCopy = stringResource(R.string.benchmark_reliability_stopped)
    var progress by remember(readyCopy) { mutableStateOf(readyCopy) }
    var report by remember { mutableStateOf<BenchmarkReport?>(null) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && report != null) runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(report!!.toPrivacySafeJson()) } }.onSuccess { progress = savedCopy }.onFailure { progress = saveFailedCopy }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Icon(Icons.Outlined.Science, null, tint = Amber)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.benchmark_title), style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.benchmark_privacy), color = InkMuted)
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.benchmark_candidate), style = MaterialTheme.typography.labelLarge, color = Amber)
                Text(model?.id ?: stringResource(R.string.benchmark_missing_model))
                Text(stringResource(R.string.benchmark_hash, model?.sha256?.take(16) ?: stringResource(R.string.benchmark_unavailable)), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = InkMuted)
                Text(stringResource(R.string.benchmark_gates), style = MaterialTheme.typography.bodySmall, color = InkMuted)
                Text(stringResource(R.string.benchmark_recovery_guidance), style = MaterialTheme.typography.bodySmall, color = InkMuted)
            } }
        }
        item {
            Button(enabled = runner != null && report == null && activeJob == null, onClick = {
                activeJob = scope.launch {
                    progress = runningCopy
                    runCatching { runner!!.run { completed, total -> progress = resources.getQuantityString(R.plurals.benchmark_case_progress, total, completed, total) } }
                        .onSuccess { report = it; val approval = BenchmarkApproval.evaluate(it); progress = if (approval.approved) resources.getString(R.string.benchmark_gates_passed) else resources.getQuantityString(R.plurals.benchmark_gate_issues, approval.reasons.size, approval.reasons.size) }
                        .onFailure { progress = it.message ?: interruptedCopy }
                    activeJob = null
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.benchmark_run)) }
        }
        if (activeJob != null) item {
            OutlinedButton(onClick = { activeJob?.cancel(); progress = stoppingCopy }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.benchmark_cancel)) }
        }
        item { Text(progress, color = InkMuted) }
        if (report != null && activeJob == null && report?.endurance60MinPassed != true) item {
            OutlinedButton(onClick = {
                activeJob = scope.launch {
                    progress = reliabilityRunningCopy
                    runCatching { runner!!.runReliabilityChecks(report!!) { done, target -> progress = resources.getString(R.string.benchmark_endurance_progress, done / 60_000, target / 60_000) } }
                        .onSuccess { report = it; progress = reliabilityCompleteCopy }
                        .onFailure { progress = it.message ?: reliabilityStoppedCopy }
                    activeJob = null
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.benchmark_reliability)) }
        }
        if (report != null) item {
            OutlinedButton(onClick = { export.launch("stark-signal-${report!!.modelId}-${report!!.modelSha256.take(12)}-report.json") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.benchmark_export)) }
        }
        item { Text(stringResource(R.string.benchmark_license_notice), style = MaterialTheme.typography.bodySmall, color = InkMuted) }
    }
}
