import java.io.File
import java.net.URLClassLoader

val url = File("C:/Users/manis/.gradle/caches/8.13/transforms/3f1e5b9b9abc152f864e4b6b357b4dc5/transformed/library-2.6.7/jars/classes.jar").toURI().toURL()
val url2 = File("C:/Users/manis/.gradle/caches/8.13/transforms/045667d1ea32f94eea28ed6a86b16307/transformed/encoder-2.6.7/jars/classes.jar").toURI().toURL()
val loader = URLClassLoader(arrayOf(url, url2))
val clazz = loader.loadClass("com.pedro.library.rtmp.RtmpDisplay")
println("RtmpDisplay methods:")
clazz.methods.filter { it.name.contains("icro") || it.name.contains("udio") }.forEach { println(it) }

val clazzBase = loader.loadClass("com.pedro.library.base.DisplayBase")
println("\nDisplayBase methods:")
clazzBase.methods.filter { it.name.contains("udio") || it.name.contains("icro") }.forEach { println(it) }

val audioCtrl = loader.loadClass("com.pedro.common.AudioCodec")

