package com.soywiz.korge.newui

import com.soywiz.korge.view.*
import com.soywiz.korma.geom.*

// @TODO: Move to Korge
inline fun <T : View> T.size(width: Number, height: Number): T = this.apply {
	this.width = width.toDouble()
	this.height = height.toDouble()
}

// @TODO: Move to Korge
inline fun <T : View> T.localMouseXY(views: Views, target: Point = Point()): Point = target.setTo(localMouseX(views), localMouseY(views))
