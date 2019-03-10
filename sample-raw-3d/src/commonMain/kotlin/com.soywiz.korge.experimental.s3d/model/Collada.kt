package com.soywiz.korge.experimental.s3d.model

import com.soywiz.kds.*
import com.soywiz.kds.iterators.*
import com.soywiz.korag.*
import com.soywiz.korag.shader.*
import com.soywiz.korge.experimental.s3d.*
import com.soywiz.korge.experimental.s3d.model.internal.*
import com.soywiz.korio.file.*
import com.soywiz.korio.serialization.xml.*
import com.soywiz.korio.util.*
import com.soywiz.korma.geom.*
import kotlin.math.*

suspend fun VfsFile.readColladaLibrary(): Library3D = ColladaParser.parse(readXml())

class ColladaParser {
	interface SourceParam {
		val name: String
	}
	data class MatrixSourceParam(override val name: String, val matrices: Array<Matrix3D>) : SourceParam
	data class FloatSourceParam(override val name: String, val floats: FloatArrayList) : SourceParam
	data class NamesSourceParam(override val name: String, val names: ArrayList<String>) : SourceParam
	data class Source(val id: String, val params: FastStringMap<SourceParam>)
	data class Input(val semantic: String, val offset: Int, val source: Source, val indices: IntArrayList)
	data class Geometry(val id: String, val name: String, val inputs: FastStringMap<Input> = FastStringMap())
	data class Skin(
		val controllerId: String,
		val controllerName: String,
		val inputs: FastStringMap<Input>,
		val vcounts: IntArrayList,
		val bindShapeMatrix: Matrix3D,
		val jointInputs: FastStringMap<Input>,
		val skinSource: String
	) {
		val maxVcount = vcounts.map { it }.max() ?: 0
	}

	companion object {
		fun parse(xml: Xml): Library3D = ColladaParser().parse(xml)
	}

	fun parse(xml: Xml): Library3D = Library3D().apply {
		parseCameras(xml)
		parseLights(xml)
		parseImages(xml)
		parseEffects(xml)
		parseMaterials(xml)
		val geometries = parseGeometries(xml)
		parseAnimations(xml)
		val skins = parseControllers(xml)
		for (skin in skins) {
			library.skins[skin.controllerId] = skin
		}
		parseVisualScenes(xml)
		parseScene(xml)
		generateGeometries(geometries, skins)
	}

	fun Library3D.generateGeometries(geometries: List<Geometry>, skins: List<Skin>) {
		val geomIdToSkin = FastStringMap<Skin>()

		for (skin in skins) {
			geomIdToSkin[skin.skinSource] = skin
		}

		for (geom in geometries) {
			val px = FloatArrayList()
			val py = FloatArrayList()
			val pz = FloatArrayList()

			val nx = FloatArrayList()
			val ny = FloatArrayList()
			val nz = FloatArrayList()

			val u0 = FloatArrayList()
			val v0 = FloatArrayList()

			val weightIndices = Array(16) { FloatArrayList() }
			val weightWeights = Array(16) { FloatArrayList() }

			val VERTEX = geom.inputs["VERTEX"] ?: error("Do not have vertices!")
			val VERTEX_indices = VERTEX.indices

			for (pname in listOf("X", "Y", "Z")) {
				val p = (VERTEX.source.params[pname] as? FloatSourceParam)?.floats
				val array = when (pname) {
					"X" -> px
					"Y" -> py
					"Z" -> pz
					else -> TODO()
				}
				if (p != null) {
					//println(VERTEX.indices)
					VERTEX.indices.fastForEach { index ->
						array.add(p[index])
					}
				}
			}

			val NORMAL = geom.inputs["NORMAL"]
			if (NORMAL != null) {
				for (pname in listOf("X", "Y", "Z")) {
					val p = (NORMAL.source.params[pname] as? FloatSourceParam)?.floats
					val array = when (pname) {
						"X" -> nx
						"Y" -> ny
						"Z" -> nz
						else -> TODO()
					}
					if (p != null) {
						NORMAL.indices.fastForEach { index -> array.add(p[index]) }
					}
				}
			}

			val skin = geomIdToSkin[geom.id]

			val maxWeights: Int

			if (skin != null) {
				val joint = skin.inputs["JOINT"] ?: error("Can't find JOINT")
				val weight = skin.inputs["WEIGHT"] ?: error("Can't find WEIGHT")
				maxWeights = skin.maxVcount.nextMultipleOf(4)
				var pos = 0

				if (maxWeights > 4) error("Too much weights for the current implementation $maxWeights > 4")

				val jointSrcParam = joint.source.params["JOINT"] as NamesSourceParam
				val weightSrcParam = weight.source.params["WEIGHT"] as FloatSourceParam

				val jointsToIndex = FastStringMap<Int>()
				jointSrcParam.names.fastForEachWithIndex { index, value ->
					jointsToIndex[value] = index
				}

				for (vcount in skin.vcounts) {
					//println("-- vcount=$vcount")
					for (n in 0 until vcount) {
						//joint.source = joint.indices[pos]
						val jointIndex = joint.indices[pos]
						//val jointtName = jointSrcParam.names[jointIndex]
						val w = weightSrcParam.floats[weight.indices[pos]]
						//println("$jointName[$joinIndex]: $weight")
						weightIndices[n].add(jointIndex.toFloat())
						weightWeights[n].add(w)
						pos++
					}
					for (n in vcount until maxWeights) {
						weightIndices[n].add(-1f)
						weightWeights[n].add(0f)
					}
				}
				println("jointSrcParam: $jointSrcParam")
				println("weightSrcParam: $weightSrcParam")
				println("joint: $joint")
				println("weight: $weight")
				println("---")
			} else {
				maxWeights = 0
			}

			val skinDef = if (skin != null) {
				val JOINT = skin.jointInputs["JOINT"] ?: error("Can't find JOINT")
				val INV_BIND_MATRIX = skin.jointInputs["INV_BIND_MATRIX"] ?: error("Can't find INV_BIND_MATRIX")
				val JOINT_NAMES = (JOINT.source.params["JOINT"] as? NamesSourceParam)?.names ?: error("Can't find JOINT.JOINT")
				val TRANSFORM = (INV_BIND_MATRIX.source.params["TRANSFORM"] as? MatrixSourceParam)?.matrices ?: error("Can't find INV_BIND_MATRIX.TRANSFORM")
				Library3D.SkinDef(skin.bindShapeMatrix, JOINT_NAMES.zip(TRANSFORM).map { Library3D.BoneDef(it.first, it.second) })
			} else {
				null
			}

			// @TODO: We should use separate components
			val combinedData = floatArrayListOf()
			val hasNormals = (nx.size >= px.size)
			for (n in 0 until px.size) {
				combinedData.add(px[n])
				combinedData.add(py[n])
				combinedData.add(pz[n])
				if (hasNormals) {
					combinedData.add(nx[n])
					combinedData.add(ny[n])
					combinedData.add(nz[n])
				}
				if (maxWeights > 0) {
					for (m in 0 until maxWeights) {
						combinedData.add(weightIndices[m][VERTEX_indices[n]])
					}
				}
				if (maxWeights > 0) {
					for (m in 0 until maxWeights) {
						combinedData.add(weightWeights[m][VERTEX_indices[n]])
					}
				}
			}

			//println(combinedData.toString())

			geometryDefs[geom.id] = Library3D.RawGeometryDef(
				Mesh3D(
					combinedData.toFloatArray(),
					VertexLayout(buildList {
						add(Shaders3D.a_pos)
						if (hasNormals) add(Shaders3D.a_norm)
						if (maxWeights >= 4) add(Shaders3D.a_boneIndex0)
						if (maxWeights >= 4) add(Shaders3D.a_weight0)
					}),
					null,
					AG.DrawType.TRIANGLES,
					maxWeights = maxWeights
				).apply {
					if (skinDef != null) {
						this.skeleton = Skeleton3D(skinDef.bindShapeMatrix, skinDef.bones.map { it.toBone() })
					}
				},
				skinDef
			)
			log { "px: $px" }
			log { "py: $py" }
			log { "pz: $pz" }
			log { "nx: $nx" }
			log { "ny: $ny" }
			log { "nz: $nz" }
		}
	}

	fun Library3D.parseScene(xml: Xml) {
		val scene = xml["scene"]
	}

	fun parseControllers(xml: Xml): List<Skin> {
		val skins = arrayListOf<Skin>()
		for (controller in xml["library_controllers"]["controller"]) {
			val controllerId = controller.str("id")
			val controllerName = controller.str("name")
			val skin = controller["skin"].firstOrNull()
			if (skin != null) {
				val skinSource = skin.str("source")
				val bindShapeMatrix = skin["bind_shape_matrix"].firstOrNull()?.text?.reader()?.readMatrix3D() ?: Matrix3D()
				for (source in parseSources(skin, this@ColladaParser.sourceArrayParams)) {
					sources[source.id] = source
				}
				val jointsXml = skin["joints"].firstOrNull()
				val jointInputs = FastStringMap<Input>()
				if (jointsXml != null) {
					for (input in jointsXml["input"]) {
						val semantic = input.str("semantic")
						val sourceId = input.str("source").trim('#')
						val offset = input.int("offset")
						val source = sources[sourceId] ?: continue
						jointInputs[semantic] = Input(semantic, offset, source, intArrayListOf())
					}
				}
				val vertexWeightsXml = skin["vertex_weights"].firstOrNull()
				val inputs = arrayListOf<Input>()
				if (vertexWeightsXml != null) {
					val count = vertexWeightsXml.int("count")
					val vcount = (vertexWeightsXml["vcount"].firstOrNull()?.text ?: "").reader().readInts()
					val v = (vertexWeightsXml["v"].firstOrNull()?.text ?: "").reader().readInts()
					for (input in vertexWeightsXml["input"]) {
						val semantic = input.str("semantic")
						val sourceId = input.str("source").trim('#')
						val offset = input.int("offset")
						val source = sources[sourceId] ?: continue
						inputs += Input(semantic, offset, source, intArrayListOf())
					}
					val stride = (inputs.map { it.offset }.max() ?: 0) + 1

					for (i in inputs) {
						for (n in 0 until v.size / stride) {
							i.indices += v[n * stride + i.offset]
						}
					}

					skins += Skin(controllerId, controllerName, inputs.associate { it.semantic to it }.toMap().toFast(), vcount, bindShapeMatrix, jointInputs, skinSource.trim('#'))
				}
			}
		}
		return skins
	}

	fun Library3D.parseMaterials(xml: Xml) {
		for (material in xml["library_materials"]["material"]) {
		}
	}

	fun Library3D.parseEffects(xml: Xml) {
		for (animation in xml["library_effects"]["effect"]) {
		}
	}

	fun Library3D.parseImages(xml: Xml) {
		for (animation in xml["library_images"]["image"]) {
		}
	}

	fun Library3D.parseAnimations(xml: Xml) {
		for (animation in xml["library_animations"]["animation"]) {
		}
	}

	fun Library3D.parseLights(xml: Xml) {
		for (light in xml["library_lights"]["light"]) {
			val id = light.getString("id")
			val name = light.getString("name")
			log { "Light id=$id, name=$name" }
		}
	}

	fun Library3D.parseCameras(xml: Xml) {
		for (camera in xml["library_cameras"]["camera"]) {
			val id = camera.getString("id") ?: "Unknown"
			val name = camera.getString("name") ?: "Unknown"
			var persp: Library3D.PerspectiveCameraDef? = null
			for (v in camera["optics"]["technique_common"].allChildren) {
				when (v.nameLC) {
					"_text_" -> Unit
					"perspective" -> {
						val xfov = v["xfov"].firstOrNull()?.text?.toDoubleOrNull() ?: 45.0
						val znear = v["znear"].firstOrNull()?.text?.toDoubleOrNull() ?: 0.01
						val zfar = v["zfar"].firstOrNull()?.text?.toDoubleOrNull() ?: 100.0
						persp = Library3D.PerspectiveCameraDef(xfov.degrees, znear, zfar)
					}
					else -> {
						log { "Unsupported camera technique ${v.nameLC}" }
					}
				}
			}

			cameraDefs[id] = persp ?: Library3D.CameraDef()
			log { "Camera id=$id, name=$name, persp=$persp" }
		}
	}

	fun Library3D.parseGeometries(xml: Xml): List<Geometry> {
		val geometries = arrayListOf<Geometry>()

		for (geometry in xml["library_geometries"]["geometry"]) {
			val id = geometry.getString("id") ?: "unknown"
			val name = geometry.getString("name") ?: "unknown"
			val geom = Geometry(id, name)
			geometries += geom
			log { "Geometry id=$id, name=$name" }
			for (mesh in geometry["mesh"]) {
				for (source in parseSources(mesh, this@ColladaParser.sourceArrayParams)) {
					sources[source.id] = source
				}

				for (vertices in mesh["vertices"]) {
					val verticesId = vertices.getString("id") ?: vertices.getString("name") ?: "unknown"
					log { "vertices: $vertices" }
					for (input in vertices["input"]) {
						val semantic = input.str("semantic", "UNKNOWN")
						val source = input.getString("source")?.trim('#') ?: "unknown"
						val rsource = sources[source]
						if (rsource != null) {
							sources[verticesId] = rsource
						}
					}
				}

				log { "SOURCES.KEYS: " + sources.keys }
				log { "SOURCES: ${sources.keys.map { it to sources[it] }.toMap()}" }

				for (triangles in mesh["triangles"]) {
					val trianglesCount = triangles.getInt("count") ?: 0
					log { "triangles: $triangles" }
					var stride = 1
					val inputs = arrayListOf<Input>()
					for (input in triangles["input"]) {
						val offset = input.getInt("offset") ?: 0
						stride = max(stride, offset + 1)

						val semantic = input.getString("semantic") ?: "unknown"
						val source = input.getString("source")?.trim('#') ?: "unknown"
						val rsource = sources[source] ?: continue
						inputs += Input(semantic, offset, rsource, intArrayListOf())
						log { "INPUT: semantic=$semantic, source=$source, offset=$offset, source=$rsource" }
					}
					val pdata = (triangles["p"].firstOrNull()?.text ?: "").reader().readInts()
					//println("P: " + pdata.toList())
					for (input in inputs) {
						log { "INPUT: semantic=${input.semantic}, trianglesCount=$trianglesCount, stride=$stride, offset=${input.offset}" }
						for (n in 0 until trianglesCount * 3) {
							input.indices.add(pdata[input.offset + n * stride])
						}
						log { "  - ${input.indices}" }
					}
					for (input in inputs) {
						geom.inputs[input.semantic] = input
					}
				}
			}
		}

		return geometries
	}

	fun Library3D.parseVisualScenes(xml: Xml) {
		for (vscene in xml["library_visual_scenes"]["visual_scene"]) {
			val id = vscene.getString("id")
			val name = vscene.getString("name")
			log { "VisualScene id=$id, name=$name" }
			for (node in vscene["node"]) {
				val id = node.getString("id")
				val name = node.getString("name")
				var transform = Matrix3D()
				node.allNodeChildren.fastForEach { v ->
					when (v.nameLC) {
						"matrix" -> {
							val sid = v.getString("sid")
							val matrix = v.text.reader().readMatrix3D()
							when (sid) {
								"transform" -> {
									transform = matrix
								}
								else -> {
									log { "  Unhandled matrix sid=$sid" }
								}
							}
						}
						else -> {
							log { "  Unhandled ${v.nameLC}" }
						}
					}
				}
				log { "  Node id=$id, name=$name, transform=$transform" }
			}
		}
	}

	val sourceArrayParams = FastStringMap<SourceParam>()
	val sources = FastStringMap<Source>()

	fun parseSources(xml: Xml, arraySourceParams: FastStringMap<SourceParam>): List<Source> {
		val sources = arrayListOf<Source>()
		for (source in xml["source"]) {
			val sourceId = source.str("id")
			val sourceParams = FastStringMap<SourceParam>()
			for (item in source.allNodeChildren) {
				when (item.nameLC) {
					"float_array", "name_array" -> {
						val arrayId = item.str("id")
						val arrayCount = item.int("count")
						val arrayDataStr = item.text
						val arrayDataReader = arrayDataStr.reader()
						val arraySourceParam = when (item.nameLC) {
							"float_array" -> FloatSourceParam(arrayId, arrayDataReader.readFloats(FloatArrayList(arrayCount)))
							"name_array" -> NamesSourceParam(arrayId, arrayDataReader.readIds(ArrayList(arrayCount)))
							else -> TODO()
						}
						arraySourceParams[arraySourceParam.name] = arraySourceParam
					}
					"technique_common" -> {
						for (accessor in item["accessor"]) {
							val accessorArrayId = accessor.str("source").trim('#')
							val offset = accessor.int("offset")
							val count = accessor.int("count")
							val stride = accessor.int("stride")
							val data = arraySourceParams[accessorArrayId]
							if (data != null) {
								for ((index, param) in accessor["param"].withIndex()) {
									val paramName = param.str("name")
									val paramType = param.str("type")
									val paramOffset = param.int("offset", index)
									val totalOffset = offset + paramOffset

									when (paramType) {
										"float" -> {
											val floats = (data as FloatSourceParam).floats.data
											val out = FloatArray(count)
											for (n in 0 until count) out[n] = floats[(n * stride) + totalOffset]
											sourceParams[paramName] = FloatSourceParam(paramName, FloatArrayList(*out))
										}
										"float4x4" -> {
											val floats = (data as FloatSourceParam).floats.data
											val out = Array(count) { Matrix3D() }
											for (n in 0 until count) {
												val off = (n * stride) + totalOffset
												val mat = out[n]
												for (m in 0 until 16) mat.data[m] = floats[off + m]
												//mat.transpose()
											}
											sourceParams[paramName] = MatrixSourceParam(paramName, out)
										}
										"name" -> {
											val data = (data as NamesSourceParam).names
											val out = ArrayList<String>(count)
											for (n in 0 until count) out.add(data[(n * stride) + totalOffset])
											sourceParams[paramName] = NamesSourceParam(paramName, out)
										}
										else -> error("Unsupported paramType=$paramType")
									}

									//params[paramName] =
								}
							}
						}
					}
					"extra" -> {
					}
					else -> {
						error("Unsupported tag <${item.nameLC}> in <source>")
					}
				}
			}
			sources += Source(sourceId, sourceParams)
		}
		return sources
	}

	inline fun log(str: () -> String) {
		// DO NOTHING
	}

	@Deprecated("", ReplaceWith("log { str }", "com.soywiz.korge.experimental.s3d.model.ColladaParser.log"))
	inline fun log(str: String) = log { str }
}
