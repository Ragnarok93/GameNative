import java.io.File
class Container(val rootDir:File)
object LegacyReader {
const val STATS_RELATIVE_PATH="stats.txt"
const val STATS_FRESHNESS_MS=2000L
    enum class NativeHealthState {
        NOT_APPLICABLE,
        STARTING,
        SOURCE_CADENCE_SUFFICIENT,
        WSI_ACTIVE_AWAITING_CONFIRMATION,
        HEALTHY,
        CONFIRMATION_LAGGING,
        CONFIRMATION_UNAVAILABLE,
        GENERATION_STALLED,
        FAILED,
    }


    enum class RuntimeStatus {
        UNKNOWN,
        PASS_THROUGH,
        SOURCE_ONLY,
        GENERATING,
        DEGRADED,
    }

    data class RuntimeState(
        val status: RuntimeStatus,
        val resident: Boolean,
        val sourceOnly: Boolean,
        val generationReady: Boolean,
        val generationInitialized: Boolean,
        val generatedPresented: Boolean,
        val degraded: Boolean,
        val multiplier: Int,
        val fresh: Boolean,
        val framegenSupportKnown: Boolean = false,
        val framegenSupported: Boolean = false,
        val vulkanPath: String? = null,
        val spirvTarget: String? = null,
        val synchronizationPath: String? = null,
        val ahbMode: String? = null,
        val rejectionReason: String? = null,
        val wsiGenerating: Boolean = false,
        val displayConfirmedGenerating: Boolean = false,
        val displayConfirmationAvailable: Boolean = false,
        val presentationDegraded: Boolean = false,
        val nativeHealthState: NativeHealthState = NativeHealthState.NOT_APPLICABLE,
        val consumedTransactionId: Long? = null,
        val consumedConfigurationRevision: Long? = null,
    ) {
        fun acknowledgesSourceOnly(transactionId: Long, revision: Long): Boolean =
            readyForSourceOnly &&
                consumedTransactionId == transactionId &&
                consumedConfigurationRevision == revision

        val readyForGeneration: Boolean
            get() = fresh && resident && generationReady && multiplier >= 2 && !degraded &&
                (!framegenSupportKnown || framegenSupported)

        val readyForSourceOnly: Boolean
            get() = fresh && resident && sourceOnly && !generationReady && !degraded
    }


    fun readLegacyRuntimeState(container: Container): RuntimeState {
        val statsFile = File(container.rootDir, STATS_RELATIVE_PATH)
        if (!statsFile.isFile) return unknownRuntimeState(fresh = false)
        val fresh = System.currentTimeMillis() - statsFile.lastModified() in 0L..STATS_FRESHNESS_MS
        if (!fresh) return unknownRuntimeState(fresh = false)

        return runCatching {
            val values = statsFile.readLines()
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
                }
                .toMap()
            val degraded = values["degraded"] == "1"
            val sourceOnly = values["source_only"] == "1"
            val generationReady = values["generation_ready"] == "1"
            val resident = values["resident"] == "1" || values["active"] == "1" || sourceOnly
            val framegenSupportKnown = values["framegen_support_known"] == "1"
            val framegenSupported = values["framegen_supported"] == "1"
            val unsupported = framegenSupportKnown && !framegenSupported
            val status = if (degraded || unsupported) {
                RuntimeStatus.DEGRADED
            } else {
                when (values["state"]) {
                    "source_only" -> RuntimeStatus.SOURCE_ONLY
                    "generating" -> RuntimeStatus.GENERATING
                    "degraded" -> RuntimeStatus.DEGRADED
                    "pass_through" -> RuntimeStatus.PASS_THROUGH
                    else -> when {
                        generationReady -> RuntimeStatus.GENERATING
                        sourceOnly -> RuntimeStatus.SOURCE_ONLY
                        resident -> RuntimeStatus.PASS_THROUGH
                        else -> RuntimeStatus.UNKNOWN
                    }
                }
            }
            RuntimeState(
                status = status,
                resident = resident,
                sourceOnly = sourceOnly,
                generationReady = generationReady,
                generationInitialized = values["generation_initialized"] == "1",
                generatedPresented = values["generated_presented"] == "1",
                degraded = degraded,
                multiplier = values["multiplier"]?.toIntOrNull() ?: 0,
                fresh = true,
                framegenSupportKnown = framegenSupportKnown,
                framegenSupported = framegenSupported,
                vulkanPath = values["framegen_vulkan_path"]?.takeIf { it.isNotBlank() },
                spirvTarget = values["framegen_spirv_target"]?.takeIf { it.isNotBlank() },
                synchronizationPath = values["framegen_sync_path"]?.takeIf { it.isNotBlank() },
                ahbMode = values["framegen_ahb_mode"]?.takeIf { it.isNotBlank() },
                rejectionReason = values["framegen_rejection_reason"]?.takeIf { it.isNotBlank() },
                consumedTransactionId = values["transaction_id"]?.toLongOrNull(),
                consumedConfigurationRevision = values["configuration_revision"]?.toLongOrNull(),
            )
        }.getOrElse {
            unknownRuntimeState(fresh = false)
        }
    }

    private fun unknownRuntimeState(fresh: Boolean) = RuntimeState(
        status = RuntimeStatus.UNKNOWN,
        resident = false,
        sourceOnly = false,
        generationReady = false,
        generationInitialized = false,
        generatedPresented = false,
        degraded = false,
        multiplier = 0,
        fresh = fresh,
    )


}
fun main() {
 val root=java.nio.file.Files.createTempDirectory("lsfg-legacy-ack-").toFile()
 try {
  val stats=File(root,"stats.txt")
  val state="state=source_only\nresident=1\nsource_only=1\ngeneration_ready=0\nmultiplier=1\n"
  for ((name,identities,expected) in listOf(
      Triple("missing", "", false),
      Triple("wrong-transaction", "transaction_id=8\nconfiguration_revision=13\n", false),
      Triple("wrong-revision", "transaction_id=9\nconfiguration_revision=12\n", false),
      Triple("matching", "transaction_id=9\nconfiguration_revision=13\n", true))) {
   stats.writeText(state+identities)
   val ack=LegacyReader.readLegacyRuntimeState(Container(root)).acknowledgesSourceOnly(9,13)
   check(ack == expected) { "case=$name expected=$expected got=$ack" }
   println("PASS legacy ack $name: $ack")
  }
 } finally { root.deleteRecursively() }
}
