package club.xiaojiawei.hsscript.utils

import club.xiaojiawei.hsscriptbase.config.log
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Obtains Authenticode and PE version-resource evidence for the exact queried image path. */
internal object WindowsAuthenticodeEvidenceProvider {
    private const val IMAGE_PATH_ENV = "HSSCRIPT_OWNER_IMAGE_PATH"
    private const val TIMEOUT_SECONDS = 5L
    private val mapper = ObjectMapper()

    private val powershellPath: Path? by lazy {
        System.getenv("SystemRoot")?.let {
            Path.of(it, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
        }?.takeIf(Files::isRegularFile)
    }

    private val queryScript = """
        ${'$'}ErrorActionPreference = 'Stop'
        Import-Module (Join-Path ${'$'}PSHOME 'Modules\Microsoft.PowerShell.Security\Microsoft.PowerShell.Security.psd1') -ErrorAction Stop
        Import-Module (Join-Path ${'$'}PSHOME 'Modules\Microsoft.PowerShell.Management\Microsoft.PowerShell.Management.psd1') -ErrorAction Stop
        ${'$'}image = ${'$'}env:$IMAGE_PATH_ENV
        ${'$'}signature = Get-AuthenticodeSignature -LiteralPath ${'$'}image
        ${'$'}version = (Get-Item -LiteralPath ${'$'}image).VersionInfo
        ${'$'}signer = ${'$'}null
        if (${ '$' }signature.SignerCertificate) {
            ${'$'}signer = ${'$'}signature.SignerCertificate.GetNameInfo(
                [System.Security.Cryptography.X509Certificates.X509NameType]::SimpleName, ${'$'}false)
        }
        ${'$'}result = [ordered]@{
            status = ${'$'}signature.Status.ToString()
            signerSimpleName = ${'$'}signer
            originalFilename = ${'$'}version.OriginalFilename
            productName = ${'$'}version.ProductName
        }
        [Console]::WriteLine((ConvertTo-Json -InputObject ${'$'}result -Compress))
    """.trimIndent()

    fun inspect(imagePath: String): AuthenticodeEvidence? {
        if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) return null
        val executable = powershellPath ?: return null
        return try {
            val process = ProcessBuilder(
                executable.toString(),
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                queryScript,
            ).redirectErrorStream(true).apply {
                environment()[IMAGE_PATH_ENV] = imagePath
            }.start()
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(500, TimeUnit.MILLISECONDS)
                log.warn { "PROCESS_SIGNATURE_PROBE_FAILED reason=timeout" }
                return null
            }
            val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText().trim() }
            if (process.exitValue() != 0 || output.isBlank()) {
                val diagnostic = output.replace(imagePath, "<image>").replace(Regex("\\s+"), " ").take(240)
                log.warn {
                    "PROCESS_SIGNATURE_PROBE_FAILED reason=nonzero-or-empty exit=${process.exitValue()} detail=$diagnostic"
                }
                return null
            }
            val record = mapper.readTree(output)
            AuthenticodeEvidence(
                status = record.path("status").asText(""),
                signerSimpleName = record.path("signerSimpleName").takeUnless { it.isNull }?.asText(),
                originalFilename = record.path("originalFilename").takeUnless { it.isNull }?.asText(),
                productName = record.path("productName").takeUnless { it.isNull }?.asText(),
            )
        } catch (error: Exception) {
            log.warn(error) { "PROCESS_SIGNATURE_PROBE_FAILED reason=exception" }
            null
        }
    }
}
