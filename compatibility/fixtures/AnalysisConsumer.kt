package certification

import org.buildmosaic.core.*
import org.buildmosaic.core.injection.*

val Greeting by singleTile { source<String>() }
suspend fun safe(): String = canvas { instance("ready") }.withMosaic { compose(Greeting) }
suspend fun missing(): Int = canvas { }.withMosaic { source<Int>() }
class Reader {
    fun Mosaic.read(): String = source<String>()
    fun invoke(receiver: Mosaic): String = receiver.read()
}
suspend fun unknown(): String = canvas { instance("ready") }.withMosaic { Reader().invoke(this) }
