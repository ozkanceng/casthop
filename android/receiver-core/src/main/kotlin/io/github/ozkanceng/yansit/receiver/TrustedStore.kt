package io.github.ozkanceng.yansit.receiver

import io.github.ozkanceng.yansit.protocol.Bytes
import io.github.ozkanceng.yansit.protocol.ControlCodec
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/** Where pairing keys live between runs (PROTOCOL.md §11): local app data, never a log. */
public interface TrustedStore {
    public fun load(): List<TrustedSender>

    public fun save(senders: List<TrustedSender>)
}

/**
 * One sender per line: `key_id pair_key device_id name`, hex except the base64url name. The file is written next to
 * its target and moved over it, so a crash leaves the old list intact. Unreadable lines are skipped.
 */
public class FileTrustedStore(private val file: File) : TrustedStore {
    override fun load(): List<TrustedSender> {
        if (!file.isFile) return emptyList()
        return try {
            file.readLines().mapNotNull(::parseLine)
        } catch (_: IOException) {
            emptyList()
        }
    }

    override fun save(senders: List<TrustedSender>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.delete()
        tmp.createNewFile()
        // Owner only, before any key is written.
        tmp.setReadable(false, false)
        tmp.setWritable(false, false)
        tmp.setReadable(true, true)
        tmp.setWritable(true, true)
        tmp.writeText(senders.joinToString("") { formatLine(it) + "\n" })
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun formatLine(t: TrustedSender): String {
        val name = Base64.getUrlEncoder().withoutPadding().encodeToString(t.deviceName.toByteArray(Charsets.UTF_8))
        return "${t.keyId.toHex()} ${t.pairKey.toHex()} ${t.deviceId.toHex()} $name"
    }

    private fun parseLine(line: String): TrustedSender? {
        val parts = line.split(' ')
        if (parts.size != FIELD_COUNT) return null
        return try {
            val keyId = Bytes.fromHex(parts[0])
            val pairKey = Bytes.fromHex(parts[1])
            val deviceId = Bytes.fromHex(parts[2])
            if (keyId.size != ControlCodec.ID_SIZE || pairKey.size != ControlCodec.KEY_SIZE ||
                deviceId.size != ControlCodec.ID_SIZE
            ) {
                return null
            }
            TrustedSender(keyId, pairKey, deviceId, String(Base64.getUrlDecoder().decode(parts[3]), Charsets.UTF_8))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        const val FIELD_COUNT = 4
    }
}
