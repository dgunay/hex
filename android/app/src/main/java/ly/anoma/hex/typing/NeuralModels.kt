package ly.anoma.hex.typing

import android.content.Context
import java.io.File

/** Labs › Keyboard › Neural autocorrect › Model: GGUF language models copied onto the phone beside the downloaded one
 * ([ly.anoma.hex.speech.ModelStore.neural]), to try one (a fine-tune) against it in real typing. They go in the app's
 * `neural-models` folder, on this debug build with
 * `adb push model.gguf /data/local/tmp/ && adb shell run-as ly.anoma.hex cp /data/local/tmp/model.gguf no_backup/neural-models/`.
 * Nothing downloads them; they are used as found, with no checksum. */
internal object NeuralModels {
    private fun dir(context: Context) = File(context.noBackupFilesDir, "neural-models")

    /** The copied models' names (their files' names without .gguf), sorted. */
    fun names(context: Context): List<String> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".gguf") }.orEmpty().map { it.name.removeSuffix(".gguf") }.sorted()

    /** The copied model [name] names, or null when it's the downloaded one ("") or no longer there. */
    fun file(context: Context, name: String): File? =
        name.takeIf { it.isNotEmpty() && '/' !in it }?.let { File(dir(context), "$it.gguf") }?.takeIf { it.isFile }
}
