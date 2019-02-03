import com.soywiz.korge.Korge
import com.soywiz.korge.input.onClick
import com.soywiz.korge.scene.Module
import com.soywiz.korge.scene.Scene
import com.soywiz.korge.view.Container
import com.soywiz.korge.view.position
import com.soywiz.korge.view.solidRect
import com.soywiz.korge.view.text
import com.soywiz.korim.color.Colors
import com.soywiz.korinject.AsyncInjector
import com.soywiz.korio.async.launchImmediately
import kotlin.reflect.KClass

suspend fun main() = Korge(Korge.Config(module = MyModule))

object MyModule : Module() {
	override val mainScene: KClass<out Scene> = MyScene1::class

	override suspend fun init(injector: AsyncInjector): Unit = injector.run {
		mapInstance(MyDependency("HELLO WORLD"))
		mapPrototype { MyScene1(get()) }
		mapPrototype { MyScene2(get()) }
	}
}

class MyDependency(val value: String)

class MyScene1(val myDependency: MyDependency) : Scene() {
	override suspend fun Container.sceneInit() {
		text("MyScene1: ${myDependency.value}")
		solidRect(100, 100, Colors.RED) {
			position(200, 200)
			onClick {
				launchImmediately {
					sceneContainer.changeTo<MyScene2>()
				}
			}
		}
	}
}

class MyScene2(val myDependency: MyDependency) : Scene() {
	override suspend fun Container.sceneInit() {
		text("MyScene2: ${myDependency.value}")
		solidRect(100, 100, Colors.BLUE) {
			position(200, 200)
			onClick {
				launchImmediately {
					sceneContainer.changeTo<MyScene1>(MyDependency("From MyScene2"))
				}
			}
		}
	}
}
