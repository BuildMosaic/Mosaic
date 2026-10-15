package publicapi
import org.buildmosaic.core.injection.*
class Metrics
val SharedKey = CanvasKey(Metrics::class, "named")
val EmptyKey = CanvasKey(Metrics::class, "")
val NullKey = CanvasKey(Metrics::class)
