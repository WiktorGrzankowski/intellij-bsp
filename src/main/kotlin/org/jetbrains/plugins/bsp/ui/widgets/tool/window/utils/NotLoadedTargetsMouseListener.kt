package org.jetbrains.plugins.bsp.ui.widgets.tool.window.utils

import ch.epfl.scala.bsp4j.BuildTarget
import com.intellij.codeInsight.hints.presentation.MouseButton
import com.intellij.codeInsight.hints.presentation.mouseButton
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import org.jetbrains.plugins.bsp.server.connection.BspConnectionService
import org.jetbrains.plugins.bsp.services.MagicMetaModelService
import org.jetbrains.plugins.bsp.ui.widgets.tool.window.all.targets.BspAllTargetsWidgetBundle
import org.jetbrains.plugins.bsp.ui.widgets.tool.window.components.BuildTargetContainer
import java.awt.event.MouseEvent
import java.awt.event.MouseListener

public class NotLoadedTargetsMouseListener(
  private val container: BuildTargetContainer,
  private val project: Project,
) : MouseListener {

  override fun mouseClicked(e: MouseEvent?) {
    e?.let { mouseClickedNotNull(it) }
  }

  private fun mouseClickedNotNull(mouseEvent: MouseEvent) {
    if (mouseEvent.mouseButton == MouseButton.Right) {
      showPopup(mouseEvent)
    }
  }

  private fun showPopup(mouseEvent: MouseEvent) {
    val actionGroup = calculatePopupGroup()
     if (actionGroup != null) {
      val context = DataManager.getInstance().getDataContext(mouseEvent.component)
      val mnemonics = JBPopupFactory.ActionSelectionAid.MNEMONICS
      JBPopupFactory.getInstance()
        .createActionGroupPopup(null, actionGroup, context, mnemonics, true)
        .showInBestPositionFor(context)
    }
  }

  private fun calculatePopupGroup(): ActionGroup? {
    val target = container.getSelectedBuildTarget()
    val isConnected = BspConnectionService.getInstance(project).value?.isConnected() == true

    return if (target != null && isConnected) {
      val action = LoadTargetAction(
        BspAllTargetsWidgetBundle.message("widget.load.target.popup.message"),
        target
      )
      DefaultActionGroup().also { it.addAction(action) }
    } else null
  }

  override fun mousePressed(e: MouseEvent?) { /* nothing to do */ }

  override fun mouseReleased(e: MouseEvent?) { /* nothing to do */ }

  override fun mouseEntered(e: MouseEvent?) { /* nothing to do */ }

  override fun mouseExited(e: MouseEvent?) { /* nothing to do */ }
}

private class LoadTargetAction(
  text: String,
  private val target: BuildTarget
) : AnAction(text) {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project

    if (project != null) {
      val magicMetaModel = MagicMetaModelService.getInstance(project).value
      val diff = magicMetaModel.loadTarget(target.id)
      runWriteAction { diff?.applyOnWorkspaceModel() }
    }
  }
}
