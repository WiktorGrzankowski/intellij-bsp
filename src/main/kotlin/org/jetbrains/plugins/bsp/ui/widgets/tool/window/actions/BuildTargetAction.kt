package org.jetbrains.plugins.bsp.ui.widgets.tool.window.actions

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import com.intellij.project.stateStore
import org.jetbrains.plugins.bsp.connection.BspConnectionService
import org.jetbrains.plugins.bsp.import.VeryTemporaryBspResolver
import org.jetbrains.plugins.bsp.services.BspBuildConsoleService
import org.jetbrains.plugins.bsp.services.BspSyncConsoleService
import org.jetbrains.plugins.bsp.ui.widgets.tool.window.all.targets.BspAllTargetsWidgetBundle

public class BuildTargetAction(
  private val target: BuildTargetIdentifier
) : AnAction(BspAllTargetsWidgetBundle.message("widget.build.target.popup.message")) {

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project

    if (project != null) {
      doAction(project)
    } else {
      log.warn("BuildTargetAction cannot be performed! Project not available.")
    }
  }

  private fun doAction(project: Project) {
    val bspConnectionService = BspConnectionService.getInstance(project)
    val bspSyncConsoleService = BspSyncConsoleService.getInstance(project)
    val bspBuildConsoleService = BspBuildConsoleService.getInstance(project)

    val bspResolver = VeryTemporaryBspResolver(
      project.stateStore.projectBasePath,
      bspConnectionService.connection!!.server!!,
      bspSyncConsoleService.bspSyncConsole,
      bspBuildConsoleService.bspBuildConsole
    )
    runBackgroundableTask("Build single target", project) {
      bspResolver.buildTarget(target)
    }
  }

  private companion object {
    private val log = logger<BuildTargetAction>()
  }
}