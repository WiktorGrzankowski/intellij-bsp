package org.jetbrains.plugins.bsp.server.tasks

import ch.epfl.scala.bsp4j.BuildClientCapabilities
import ch.epfl.scala.bsp4j.BuildServerCapabilities
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.DependencySourcesParams
import ch.epfl.scala.bsp4j.DependencySourcesResult
import ch.epfl.scala.bsp4j.InitializeBuildParams
import ch.epfl.scala.bsp4j.InitializeBuildResult
import ch.epfl.scala.bsp4j.JavacOptionsParams
import ch.epfl.scala.bsp4j.JavacOptionsResult
import ch.epfl.scala.bsp4j.JvmBuildTarget
import ch.epfl.scala.bsp4j.ResourcesParams
import ch.epfl.scala.bsp4j.ResourcesResult
import ch.epfl.scala.bsp4j.SourcesParams
import ch.epfl.scala.bsp4j.SourcesResult
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult
import com.google.gson.JsonObject
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkProvider
import com.intellij.openapi.progress.indeterminateStep
import com.intellij.openapi.progress.progressStep
import com.intellij.openapi.progress.withBackgroundProgress
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.jetbrains.magicmetamodel.MagicMetaModelDiff
import org.jetbrains.magicmetamodel.ProjectDetails
import org.jetbrains.magicmetamodel.impl.PerformanceLogger.logPerformance
import org.jetbrains.magicmetamodel.impl.PerformanceLogger.logPerformanceSuspend
import org.jetbrains.magicmetamodel.impl.workspacemodel.impl.updaters.transformers.extractJvmBuildTarget
import org.jetbrains.magicmetamodel.impl.workspacemodel.impl.updaters.transformers.javaVersionToJdkName
import org.jetbrains.plugins.bsp.config.ProjectPropertiesService
import org.jetbrains.plugins.bsp.server.client.importSubtaskId
import org.jetbrains.plugins.bsp.server.connection.BspServer
import org.jetbrains.plugins.bsp.server.connection.reactToExceptionIn
import org.jetbrains.plugins.bsp.services.MagicMetaModelService
import org.jetbrains.plugins.bsp.ui.console.BspConsoleService
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import kotlin.io.path.toPath


public class CollectProjectDetailsTask(project: Project, private val taskId: Any) :
  BspServerTask<ProjectDetails>("collect project details", project) {

  private val cancelOnFuture = CompletableFuture<Void>()

  private val bspSyncConsole = BspConsoleService.getInstance(project).bspSyncConsole

  private var magicMetaModelDiff: MagicMetaModelDiff? = null

  private lateinit var uniqueJdkInfos: Set<JvmBuildTarget>

  private val jdkTable = ProjectJdkTable.getInstance()

  private val coroutineJob = Job()

  public suspend fun execute(name: String, cancelable: Boolean) {
    withContext(coroutineJob) {
      try {
        withBackgroundProgress(project, name, cancelable) {
          doExecute()
        }
      } catch (e: CancellationException) {
        onCancel()
      }
    }
  }

  private suspend fun doExecute() {
    val projectDetails = progressStep(endFraction = 0.5, text = "Collecting project details") {
      calculateProjectDetailsSubtask()
    }
    indeterminateStep(text = "Calculating all unique jdk infos") {
      calculateAllUniqueJdkInfosSubtask(projectDetails)
    }
    progressStep(endFraction = 0.75, "Updating magic meta model diff") {
      updateMMMDiffSubtask(projectDetails)
    }
    progressStep(endFraction = 1.0, "Post-processing magic meta model") {
      postprocessingMMMSubtask()
    }
  }

  private fun calculateProjectDetailsSubtask() =
    logPerformance("collect-project-details") { executeWithServerIfConnected { collectModel(it, cancelOnFuture) } }

  private fun collectModel(server: BspServer, cancelOn: CompletableFuture<Void>): ProjectDetails? {
    fun isCancellationException(e: Throwable): Boolean =
      e is CompletionException && e.cause is CancellationException

    fun isTimeoutException(e: Throwable): Boolean =
      e is CompletionException && e.cause is TimeoutException

    fun errorCallback(e: Throwable) = when {
      isCancellationException(e) -> bspSyncConsole.finishTask(taskId, "Canceled", FailureResultImpl("The task has been canceled!"))
      isTimeoutException(e) -> bspSyncConsole.finishTask(taskId, "Timed out", FailureResultImpl(BspTasksBundle.message("task.timeout.message")))
      else -> bspSyncConsole.finishTask(taskId, "Failed", FailureResultImpl(e))
    }

    bspSyncConsole.startSubtask(taskId, importSubtaskId, "Collecting model...")

    val initializeBuildResult = queryForInitialize(server).catchSyncErrors { errorCallback(it) }.get()
    server.onBuildInitialized()

    val projectDetails =
      calculateProjectDetailsWithCapabilities(server, initializeBuildResult.capabilities, { errorCallback(it) }, cancelOn)

    bspSyncConsole.finishSubtask(importSubtaskId, "Collecting model done!")

    return projectDetails
  }

  private fun queryForInitialize(server: BspServer): CompletableFuture<InitializeBuildResult> {
    val buildParams = createInitializeBuildParams()

    return server.buildInitialize(buildParams)
  }

  private fun createInitializeBuildParams(): InitializeBuildParams {
    val projectProperties = ProjectPropertiesService.getInstance(project).value
    val projectBaseDir = projectProperties.projectRootDir
    val params = InitializeBuildParams(
      "IntelliJ-BSP",
      "0.0.1",
      "2.0.0",
      projectBaseDir.toString(),
      BuildClientCapabilities(listOf("java"))
    )
    val dataJson = JsonObject()
    dataJson.addProperty("clientClassesRootDir", "$projectBaseDir/out")
    params.data = dataJson

    return params
  }

  private fun calculateAllUniqueJdkInfosSubtask(projectDetails: ProjectDetails?) {
    projectDetails?.let {
      bspSyncConsole.startSubtask(taskId, "calculate-all-unique-jdk-infos", "Calculating all unique jdk infos...")
      uniqueJdkInfos = logPerformance("calculate-all-unique-jdk-infos") { calculateAllUniqueJdkInfos(projectDetails) }
      bspSyncConsole.finishSubtask("calculate-all-unique-jdk-infos", "Calculating all unique jdk infos done!")
    }
  }

  private fun calculateAllUniqueJdkInfos(projectDetails: ProjectDetails): Set<JvmBuildTarget> = projectDetails.targets.mapNotNull(::extractJvmBuildTarget).toSet()

  private fun updateMMMDiffSubtask(projectDetails: ProjectDetails?) {
    val magicMetaModelService = MagicMetaModelService.getInstance(project)
    bspSyncConsole.startSubtask(taskId, "calculate-project-structure", "Calculating project structure...")
    projectDetails?.let {
      logPerformance("initialize-magic-meta-model") { magicMetaModelService.initializeMagicModel(projectDetails) }
    }
    magicMetaModelDiff = logPerformance("load-default-targets") { magicMetaModelService.value.loadDefaultTargets() }
    bspSyncConsole.finishSubtask("calculate-project-structure", "Calculating project structure done!")
  }

  private suspend fun postprocessingMMMSubtask() {
    addBspFetchedJdks()
    applyChangesOnWorkspaceModel()
  }

  private suspend fun addBspFetchedJdks() {
    bspSyncConsole.startSubtask(taskId, "add-bsp-fetched-jdks", "Adding BSP-fetched JDKs...")
    logPerformanceSuspend("add-bsp-fetched-jdks") { uniqueJdkInfos.forEach { addJdk(it) } }
    bspSyncConsole.finishSubtask("add-bsp-fetched-jdks", "Adding BSP-fetched JDKs done!")
  }

  private suspend fun addJdk(jdkInfo: JvmBuildTarget) {
    val jdk = ExternalSystemJdkProvider.getInstance().createJdk(
      jdkInfo.javaVersion.javaVersionToJdkName(project.name),
      URI.create(jdkInfo.javaHome).toPath().toString()
    )

    addJdkIfNeeded(jdk)
  }

  private suspend fun addJdkIfNeeded(jdk: Sdk) {
    val existingJdk = jdkTable.findJdk(jdk.name, jdk.sdkType.name)
    if (existingJdk == null || existingJdk.homePath != jdk.homePath) {
      withContext(Dispatchers.EDT) {
        runWriteAction {
          existingJdk?.let { jdkTable.removeJdk(existingJdk) }
          jdkTable.addJdk(jdk)
        }
      }
    }
  }

  private suspend fun applyChangesOnWorkspaceModel() {
    bspSyncConsole.startSubtask(taskId, "apply-on-workspace-model", "Applying changes...")

    logPerformanceSuspend("apply-changes-on-workspace-model") {
      withContext(Dispatchers.EDT) {
        runWriteAction { magicMetaModelDiff?.applyOnWorkspaceModel() }
      }
    }

    bspSyncConsole.finishSubtask("apply-on-workspace-model", "Applying changes done!")
  }

  private fun onCancel() {
    cancelOnFuture.cancel(true)
    bspSyncConsole.finishTask(taskId, "Canceled", FailureResultImpl("The task has been canceled!"))
  }

  public fun cancelExecution() {
    cancelOnFuture.cancel(true)
    coroutineJob.cancel()
  }
}

public fun calculateProjectDetailsWithCapabilities(
  server: BspServer,
  buildServerCapabilities: BuildServerCapabilities,
  errorCallback: (Throwable) -> Unit,
  cancelOn: CompletableFuture<Void> = CompletableFuture(),
): ProjectDetails? {
  try {
    val workspaceBuildTargetsResult = queryForBuildTargets(server).reactToExceptionIn(cancelOn).catchSyncErrors(errorCallback).get()

    val allTargetsIds = calculateAllTargetsIds(workspaceBuildTargetsResult)

    val sourcesFuture = queryForSourcesResult(server, allTargetsIds).reactToExceptionIn(cancelOn).catchSyncErrors(errorCallback)

    val resourcesFuture =
      queryForTargetResources(server, buildServerCapabilities, allTargetsIds)?.reactToExceptionIn(cancelOn)?.catchSyncErrors(errorCallback)
    val dependencySourcesFuture =
      queryForDependencySources(server, buildServerCapabilities, allTargetsIds)?.reactToExceptionIn(cancelOn)?.catchSyncErrors(errorCallback)

    val javaTargetsIds = calculateJavaTargetsIds(workspaceBuildTargetsResult)
    val javacOptionsFuture = queryForJavacOptions(server, javaTargetsIds)?.reactToExceptionIn(cancelOn)?.catchSyncErrors(errorCallback)

    return ProjectDetails(
      targetsId = allTargetsIds,
      targets = workspaceBuildTargetsResult.targets.toSet(),
      sources = sourcesFuture.get().items,
      resources = resourcesFuture?.get()?.items ?: emptyList(),
      dependenciesSources = dependencySourcesFuture?.get()?.items ?: emptyList(),
      javacOptions = javacOptionsFuture?.get()?.items ?: emptyList()
    )
  } catch (e: Exception) {
    // TODO the type xd
    val log = logger<Any>()

    if (e is ExecutionException && e.cause is CancellationException) {
      log.debug("calculateProjectDetailsWithCapabilities has been cancelled!", e)
    } else {
      log.error("calculateProjectDetailsWithCapabilities has failed!", e)
    }

    return null
  }
}

private fun queryForBuildTargets(server: BspServer): CompletableFuture<WorkspaceBuildTargetsResult> =
  server.workspaceBuildTargets()

private fun calculateAllTargetsIds(workspaceBuildTargetsResult: WorkspaceBuildTargetsResult): List<BuildTargetIdentifier> =
  workspaceBuildTargetsResult.targets.map { it.id }

private fun queryForSourcesResult(
  server: BspServer,
  allTargetsIds: List<BuildTargetIdentifier>
): CompletableFuture<SourcesResult> {
  val sourcesParams = SourcesParams(allTargetsIds)

  return server.buildTargetSources(sourcesParams)
}

private fun queryForTargetResources(
  server: BspServer,
  capabilities: BuildServerCapabilities,
  allTargetsIds: List<BuildTargetIdentifier>
): CompletableFuture<ResourcesResult>? {
  val resourcesParams = ResourcesParams(allTargetsIds)

  return if (capabilities.resourcesProvider) server.buildTargetResources(resourcesParams)
  else null
}

private fun queryForDependencySources(
  server: BspServer,
  capabilities: BuildServerCapabilities,
  allTargetsIds: List<BuildTargetIdentifier>
): CompletableFuture<DependencySourcesResult>? {
  val dependencySourcesParams = DependencySourcesParams(allTargetsIds)

  return if (capabilities.dependencySourcesProvider) server.buildTargetDependencySources(dependencySourcesParams)
  else null
}

private fun calculateJavaTargetsIds(workspaceBuildTargetsResult: WorkspaceBuildTargetsResult): List<BuildTargetIdentifier> =
  workspaceBuildTargetsResult.targets.filter { it.languageIds.contains("java") }.map { it.id }

private fun queryForJavacOptions(
  server: BspServer,
  javaTargetsIds: List<BuildTargetIdentifier>
): CompletableFuture<JavacOptionsResult>? {
  return if (javaTargetsIds.isNotEmpty()) {
    val javacOptionsParams = JavacOptionsParams(javaTargetsIds)
    server.buildTargetJavacOptions(javacOptionsParams)
  } else null
}

private fun <T> CompletableFuture<T>.catchSyncErrors(errorCallback: (Throwable) -> Unit): CompletableFuture<T> =
  this.whenComplete { _, exception ->
    exception?.let { errorCallback(it) }
  }
