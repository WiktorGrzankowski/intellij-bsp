package org.jetbrains.plugins.bsp.ui.widgets.tool.window.components

import ch.epfl.scala.bsp4j.BuildTarget
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import org.jetbrains.plugins.bsp.ui.widgets.tool.window.all.targets.BspAllTargetsWidgetBundle
import org.jetbrains.plugins.bsp.ui.widgets.tool.window.search.SearchBarPanel
import java.awt.Component
import java.awt.event.MouseListener
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Panel containing target tree and target search components, both containing the same collection of build targets.
 * `BspPanelComponent` extends [JPanel], which makes it possible to use it directly as a UI component
 */
public class BspPanelComponent private constructor(
  private val targetIcon: Icon,
  private val toolName: String,
  private val targetTree: BuildTargetTree,
  private val targetSearch: BuildTargetSearch
) : JPanel(VerticalLayout(0)) {

  private val emptyTreeMessage = JBLabel(
    BspAllTargetsWidgetBundle.message("widget.no.targets.message"),
    SwingConstants.CENTER
  )

  /**
   * @property targetIcon icon which will be shown next to build targets in this panel
   * @property toolName name of the tool providing the build targets
   * @property targets collection of build targets this panel will contain
   * @property searchBarPanel searchbar panel responsible for providing user's search queries
   */
  public constructor(
    targetIcon: Icon,
    toolName: String,
    targets: Collection<BuildTarget>,
    searchBarPanel: SearchBarPanel
  ) : this(
    targetIcon = targetIcon,
    toolName = toolName,
    targetTree = BuildTargetTree(targetIcon, toolName, targets),
    targetSearch = BuildTargetSearch(targetIcon, toolName, targets, searchBarPanel)
  )

  init {
    targetSearch.addQueryChangeListener(::onSearchQueryUpdate)
    replacePanelContent(null, chooseNewContent())
  }

  private fun onSearchQueryUpdate() {
    val newPanelContent = chooseNewContent()
    val oldPanelContent = getCurrentContent()

    if (newPanelContent != oldPanelContent) {
      replacePanelContent(oldPanelContent, newPanelContent)
    }
  }

  private fun chooseNewContent(): JComponent =
    when {
      targetTree.isEmpty() -> emptyTreeMessage
      targetSearch.isSearchActive() -> targetSearch.targetSearchPanel
      else -> targetTree.treeComponent
    }

  private fun getCurrentContent(): Component? =
    try {
      this.getComponent(0)
    } catch (_: ArrayIndexOutOfBoundsException) {
      log.warn("Sidebar widget panel does not have enough children")
      null
    }

  private fun replacePanelContent(oldContent: Component?, newContent: JComponent) {
    oldContent?.let { this.remove(it) }
    this.add(newContent)
    this.revalidate()
    this.repaint()
  }

  public fun wrappedInScrollPane(): JBScrollPane {
    val scrollPane = JBScrollPane(this)
    val headerComponent = targetSearch.searchBarPanel
    headerComponent.isEnabled = !targetTree.isEmpty()
    scrollPane.setColumnHeaderView(headerComponent)
    return scrollPane
  }

  /**
   * Adds a mouse listener to this panel's target tree and target search components
   *
   * @param listenerBuilder mouse listener builder
   */
  public fun addMouseListener(listenerBuilder: (BuildTargetContainer) -> MouseListener) {
    targetTree.addMouseListener(listenerBuilder)
    targetSearch.addMouseListener(listenerBuilder)
  }

  /**
   * Creates a new panel with given targets. Mouse listeners added to target tree and target search components
   * will be copied using [BuildTargetContainer.createNewWithTargets]
   *
   * @param targets collection of build targets which the new panel will contain
   * @return newly created panel
   */
  public fun createNewWithTargets(targets: Collection<BuildTarget>): BspPanelComponent =
    BspPanelComponent(
      targetIcon,
      toolName,
      targetTree.createNewWithTargets(targets),
      targetSearch.createNewWithTargets(targets)
    )

  private companion object {
    private val log = logger<BspPanelComponent>()
  }
}
