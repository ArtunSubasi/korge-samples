package com.soywiz.korge.newui

import com.soywiz.korge.render.*
import com.soywiz.korge.view.*

inline fun Container.uiScrollableArea(
	width: Number = 256.0,
	height: Number = 256.0,
	contentWidth: Number = 512.0,
	contentHeight: Number = 512.0,
	buttonSize: Number = 32.0,
	verticalScroll: Boolean = true,
	horizontalScroll: Boolean = true,
	config: UIScrollableArea.() -> Unit = {},
	block: Container.() -> Unit = {}
): UIScrollableArea = UIScrollableArea(
	width.toDouble(), height.toDouble(), contentWidth.toDouble(), contentHeight.toDouble(), buttonSize.toDouble(), verticalScroll, horizontalScroll
).also { addChild(it) }.also(config).also { block(it.container) }

// @TODO: Optimize this!
// @TODO: Add an actualContainer = this inside Container
open class UIScrollableArea(
	width: Double = 256.0,
	height: Double = 256.0,
	contentWidth: Double = 512.0,
	contentHeight: Double = 512.0,
	buttonSize: Double = 32.0,
	verticalScroll: Boolean = true,
	horizontalScroll: Boolean = true
) : UIView(width, height) {
	var buttonSize by uiObservable(buttonSize) { updatedSize() }

	var contentWidth by uiObservable(contentWidth) { updatedSize() }
	var contentHeight by uiObservable(contentHeight) { updatedSize() }

	var verticalScroll by uiObservable(verticalScroll) { updatedSize() }
	var horizontalScroll by uiObservable(horizontalScroll) { updatedSize() }

	val clientWidth get() = width - buttonSize
	val clientHeight get() = height - buttonSize

	val clipContainer = clipContainer(clientWidth, clientHeight)
	val container = clipContainer.fixedSizeContainer(contentWidth, contentHeight)
	val horScroll = uiScrollBar(width, buttonSize).also { it.onChange { moved() } }
	val verScroll = uiScrollBar(buttonSize, height).also { it.onChange { moved() } }

	init {
		updatedSize()
	}

	override fun updatedSize() {
		super.updatedSize()

		horScroll.totalSize = contentWidth
		horScroll.pageSize = clientWidth
		horScroll.stepSize = clientWidth / 4

		verScroll.totalSize = contentHeight
		verScroll.pageSize = clientHeight
		verScroll.stepSize = clientHeight / 4

		horScroll.size(width - buttonSize, buttonSize).position(0, height - buttonSize).also { it.visible = horizontalScroll }
		verScroll.size(buttonSize, height - buttonSize).position(width - buttonSize, 0).also { it.visible = verticalScroll }
	}

	protected fun moved() {
		//println("" + verScroll.current + " :: " + verScroll.totalSize + " :: " + verScroll.pageSize + " :: " + verScroll.stepSize)
		container.x = -horScroll.current
		container.y = -verScroll.current
	}
}
