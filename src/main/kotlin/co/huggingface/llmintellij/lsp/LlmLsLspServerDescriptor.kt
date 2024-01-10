package co.huggingface.llmintellij.lsp

import co.huggingface.llmintellij.LlmSettingsState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import io.ktor.util.*
import org.apache.http.HttpHost
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.impl.client.LaxRedirectStrategy
import org.eclipse.lsp4j.services.LanguageServer
import java.io.*
import java.net.ConnectException
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.io.path.Path
import kotlin.io.path.deleteIfExists


class LlmLsLspServerDescriptor(project: Project) : ProjectWideLspServerDescriptor(project, "LlmLs") {
    private val logger = Logger.getInstance("llmLsLspServerDescriptor")

    override fun isSupportedFile(file: VirtualFile) = true

    override fun createCommandLine(): GeneralCommandLine {
        val settings = LlmSettingsState.instance
        val binaryPath = downloadLlmLs(logger, settings.lsp.binaryPath, settings.lsp.version) ?: throw Error("llm-ls binary path is not set")
        settings.lsp.binaryPath = binaryPath
        return GeneralCommandLine().apply {
            exePath = binaryPath
            withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            withCharset(Charsets.UTF_8)
            withEnvironment("LLM_LOG_LEVEL", settings.lsp.logLevel)
            addParameter("--stdio")
        }
    }

    override val lsp4jServerClass: Class<out LanguageServer> = LlmLsLanguageServer::class.java

    override val lspGoToDefinitionSupport = false

    override val lspCompletionSupport = null

    override val lspDiagnosticsSupport = null

    override val lspCodeActionsSupport = null

    override val lspCommandsSupport = null
}

fun isWindows(os: String): Boolean {
    return os.toLowerCasePreservingASCIIRules().indexOf("win") >= 0
}

fun isMac(os: String): Boolean {
    return os.toLowerCasePreservingASCIIRules().indexOf("mac") >= 0
}

fun isUnix(os: String): Boolean {
    val osLower = os.toLowerCasePreservingASCIIRules()
    return osLower.indexOf("nix") >= 0 || osLower.indexOf("nux") >= 0 || osLower.indexOf("aix") > 0
}

fun buildBinaryName(logger: Logger): String? {
    val os = System.getProperty("os.name")

    var arch = System.getProperty("os.arch")
    if (arch == "amd64") {
        arch = "x86_64"
    }

    var osSuffix: String? = null
    if (isMac(os)) {
        osSuffix = "apple-darwin"
    } else if (isUnix(os)) {
        osSuffix = "unknown-linux-gnu"
    } else if (isWindows(os)) {
        osSuffix = "pc-windows-msvc"
    }

    if (osSuffix == null) {
        logger.error("Unsupported architecture or OS: $arch $os")
        return null
    }

    return "llm-ls-$arch-$osSuffix"
}

fun buildUrl(binName: String, version: String): String {
    return "https://github.com/huggingface/llm-ls/releases/download/$version/$binName.gz"
}

private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate>? {
        return null
    }

    override fun checkClientTrusted(
        certs: Array<X509Certificate>, authType: String
    ) {
    }

    override fun checkServerTrusted(
        certs: Array<X509Certificate>, authType: String
    ) {
    }
}
)

fun downloadAndUnzip(logger: Logger, url: String, binDir: File, binName: String, targetPath: String) {
    val extractedBinPath = File(binDir, binName).absolutePath
    val zipPath = "$extractedBinPath.gz"

    try {
        //FileOutputStream(zipPath).write(URL(url).readBytes())

        val sslContext: SSLContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        val client = HttpClientBuilder.create()
            //.setRedirectStrategy(DefaultRedirectStrategy())
            .setRedirectStrategy(LaxRedirectStrategy())
            //.setProxy(HttpHost("www-relay.lottery.co.at", 480, "http"))
            .build()
        val response = client.execute(HttpGet(url))
        val reader = BufferedReader(InputStreamReader(response.entity.content))
        FileOutputStream(zipPath).write(reader.use { it.read() })


        /*
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder().uri(URI.create(url)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        FileOutputStream(zipPath).write(response.body().toByteArray())
         */
    } catch (e: ConnectException) {
        logger.error("Error during download: $e")
    } catch (e: IOException) {
        logger.error("Error during zip file creation: $e")
    }

    /*
    val downloadCommand = "curl -k -L -o $zipPath $url"
    runCommand(downloadCommand)
    logger.info("Download command: $downloadCommand")
     */

    try {
        val inputByteStream = FileInputStream(zipPath)
        val outputByteStream = FileOutputStream(extractedBinPath)

        outputByteStream.write(GZIPInputStream(inputByteStream).use { it.readBytes() })
        logger.info("Successfully extracted llm-ls")
    } catch (e: IOException)
    {
        logger.error("Gzip exception: $e")
    }

    try {
        Files.move(Path(extractedBinPath), Path(targetPath))
    } catch (e: IOException) {
        logger.error("Move failed: $e")
    }

    try {
        Path(targetPath).toFile().setExecutable(true, false)
    } catch (e: IOException) {
        logger.error("Set file permissions failed: $e")
    }

    Path(zipPath).deleteIfExists()
}

fun runCommand(command: String) {
    val process = Runtime.getRuntime().exec(command)

    process.waitFor()
}

fun downloadLlmLs(logger: Logger, binaryPath: String?, version: String): String? {
    if (binaryPath != null && binaryPath.endsWith(version) && File(binaryPath).exists()) {
        return binaryPath
    }

    val binDir = File(System.getProperty("user.home"), ".cache/llm_intellij/bin")
    binDir.mkdirs()

    val binName = buildBinaryName(logger) ?: return null

    val fullPath = File(binDir, "$binName-$version")

    if (!fullPath.exists()) {
        val url = buildUrl(binName, version)
        downloadAndUnzip(logger, url, binDir, binName, fullPath.absolutePath)
        logger.info("Successfully downloaded llm-ls")
    }

    return fullPath.absolutePath
}
