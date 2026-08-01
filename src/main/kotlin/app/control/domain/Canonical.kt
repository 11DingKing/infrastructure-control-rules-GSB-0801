package app.control.domain

import java.security.MessageDigest
import kotlinx.serialization.json.Json

/**
 * 规范化 JSON：字段顺序 = 声明顺序、无多余空白、显式输出 null，
 * 因此同一 [EvaluationResult] 的序列化字节恒定。
 */
object Canonical {

    private val json = Json {
        encodeDefaults = true
        prettyPrint = false
    }

    fun bytesOf(result: EvaluationResult): ByteArray =
        json.encodeToString(EvaluationResult.serializer(), result).toByteArray(Charsets.UTF_8)

    fun stringOf(result: EvaluationResult): String =
        json.encodeToString(EvaluationResult.serializer(), result)

    fun parse(encoded: String): EvaluationResult =
        json.decodeFromString(EvaluationResult.serializer(), encoded)

    /** SHA-256（十六进制小写），作为结果的可复现内容指纹。 */
    fun hashOf(result: EvaluationResult): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytesOf(result))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
