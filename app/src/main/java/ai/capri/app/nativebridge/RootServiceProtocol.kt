package ai.capri.app.nativebridge

import org.json.JSONObject
import java.io.File

internal data class RootServiceRequest(val key: String, val command: String, val lifetime: Int)

internal object RootServiceProtocol {
    fun request(params: JSONObject): RootServiceRequest {
        val key = params.requiredText("request_key", 80)
        require(key.matches(Regex("[A-Za-z0-9_-]+"))) { "request_key must be an ASCII identifier." }
        val command = params.requiredText("command", 32000)
        require('\u0000' !in command) { "command must not contain NUL." }
        return RootServiceRequest(key, command, params.boundedInt("lifetime_seconds", 180, 30, 600))
    }

    /** Supervisor and watchdog share one verified process group; commands never enter trusted code. */
    fun supervisor(directory: File, cwd: File, lifetime: Int): String {
        val original = RootProtocol.supervisor(directory, cwd, lifetime + 10)
        val watchdog = """
            (
                elapsed=0
                while [ "${'$'}elapsed" -lt $lifetime ]; do
                    sleep 1
                    elapsed=${'$'}((elapsed + 1))
                    [ -s ${RootProtocol.quote(File(directory, "stop").path)} ] && break
                    size=${'$'}(wc -c < ${RootProtocol.quote(File(directory, "stdout").path)})
                    errors=${'$'}(wc -c < ${RootProtocol.quote(File(directory, "stderr").path)})
                    [ "${'$'}((size + errors))" -le 262144 ] || break
                done
                printf expired > ${RootProtocol.quote(File(directory, "stop").path)}
                kill -KILL -${'$'}${'$'}
            ) >/dev/null 2>&1 &
        """.trimIndent()
        val out=RootProtocol.quote(File(directory,"stdout.pipe").path)
        val err=RootProtocol.quote(File(directory,"stderr.pipe").path)
        val command="/system/bin/sh ${RootProtocol.quote(File(directory,"command.sh").path)}"
        val collectors="""
            mkfifo $out $err || exit 125
            head -c 131072 < $out > ${RootProtocol.quote(File(directory,"stdout").path)} &
            out_reader=${'$'}!
            head -c 131072 < $err > ${RootProtocol.quote(File(directory,"stderr").path)} &
            err_reader=${'$'}!
            $command
        """.trimIndent()
        return original.replace("( sleep ${lifetime + 10}; kill -KILL -${'$'}${'$'} ) >/dev/null 2>&1 &", watchdog)
            .replace(command,collectors)
            .replace("> ${RootProtocol.quote(File(directory,"stdout").path)} 2> ${RootProtocol.quote(File(directory,"stderr").path)} &","> $out 2> $err &")
            .replace("code=${'$'}?", "code=${'$'}?\nwait \"${'$'}out_reader\" \"${'$'}err_reader\"")
    }
}
