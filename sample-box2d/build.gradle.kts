import com.soywiz.korge.gradle.*

apply(plugin = "korge")

dependencies {
	add("commonMainApi", "com.soywiz:korma-shape-ops:$kormaVersion")
	add("commonMainApi", "com.soywiz:korma-triangulate-pathfind:$kormaVersion")
	add("commonMainApi", "com.soywiz:korge-dragonbones:$korgeVersion")
	add("commonMainApi", "com.soywiz:korge-box2d:$korgeVersion")
}

korge {
	id = "com.soywiz.sample1"
	name = "Sample1"
	description = "A sample using Korge and the gradle plugin"
	orientation = com.soywiz.korge.gradle.Orientation.LANDSCAPE
	jvmMainClassName = "Sample1Kt"

	admob("ca-app-pub-xxxxxxxx~yyyyyy")
}
