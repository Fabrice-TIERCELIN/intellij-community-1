// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.wm.impl

import com.intellij.openapi.ui.popup.JBPopup
import org.jetbrains.annotations.Nls
import java.awt.Color
import java.awt.event.ActionListener
import java.awt.event.InputEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.UIManager
import kotlin.properties.Delegates
import kotlin.reflect.KProperty

@Suppress("LeakingThis")
abstract class ToolbarComboWidget: JComponent() {
  val pressListeners: MutableList<ActionListener> = mutableListOf()

  var text: @Nls String? by Delegates.observable("", this::fireUpdateEvents)

  var leftIcons: List<Icon> by Delegates.observable(emptyList(), this::fireUpdateEvents)
  var rightIcons: List<Icon> by Delegates.observable(emptyList(), this::fireUpdateEvents)
  var leftIconsGap: Int by Delegates.observable(0, this::fireUpdateEvents)
  var rightIconsGap: Int by Delegates.observable(0, this::fireUpdateEvents)
  var hoverBackground: Color? by Delegates.observable(null, this::fireUpdateEvents)
  var transparentHoverBackground: Color? by Delegates.observable(null, this::fireUpdateEvents)
  var highlightBackground: Color? by Delegates.observable(null, this::fireUpdateEvents)
  var isExpandable: Boolean by Delegates.observable(true, this::fireUpdateEvents)
  var isPopupShowing: Boolean by Delegates.observable(false, this::fireUpdateEvents)

  init {
    // set UI for component
    updateUI()
    isOpaque = false
  }

  abstract fun doExpand(e: InputEvent?)

  open fun createPopup(e: InputEvent?): JBPopup? = null

  override fun getUIClassID(): String = "ToolbarComboWidgetUI"

  override fun updateUI() {
    setUI(UIManager.getUI(this))
  }

  fun addPressListener(action: ActionListener) {
    val listenersCountBefore = pressListeners.size
    pressListeners += action
    firePropertyChange("pressListenersCount", listenersCountBefore, pressListeners.size)
  }

  private fun fireUpdateEvents(prop: KProperty<*>, oldValue: Any?, newValue: Any?) {
    firePropertyChange(prop.name, oldValue, newValue)
    invalidate()
    repaint()
  }
}
