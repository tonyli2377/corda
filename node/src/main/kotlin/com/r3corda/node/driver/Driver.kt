package com.r3corda.node.driver

import com.google.common.net.HostAndPort
import com.r3corda.core.crypto.Party
import com.r3corda.core.crypto.generateKeyPair
import com.r3corda.core.messaging.MessagingService
import com.r3corda.core.node.NodeInfo
import com.r3corda.core.node.services.NetworkMapCache
import com.r3corda.core.node.services.ServiceType
import com.r3corda.node.services.config.NodeConfiguration
import com.r3corda.node.services.config.NodeConfigurationFromConfig
import com.r3corda.node.services.messaging.ArtemisMessagingClient
import com.r3corda.node.services.network.InMemoryNetworkMapCache
import com.r3corda.node.services.network.NetworkMapService
import com.r3corda.node.utilities.AffinityExecutor
import com.typesafe.config.Config
import com.typesafe.config.ConfigRenderOptions
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLClassLoader
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * This file defines a small "Driver" DSL for starting up nodes.
 *
 * The process the driver is run in behaves as an Artemis client and starts up other processes. Namely it first
 * bootstraps a network map service to allow the specified nodes to connect to, then starts up the actual nodes.
 *
 * TODO The driver actually starts up as an Artemis server now that may route traffic. Fix this once the client MessagingService is done.
 * TODO The nodes are started up sequentially which is quite slow. Either speed up node startup or make startup parallel somehow.
 * TODO The driver now polls the network map cache for info about newly started up nodes, this could be done asynchronously(?).
 * TODO The network map service bootstrap is hacky (needs to fake the service's public key in order to retrieve the true one), needs some thought.
 */

private val log: Logger = LoggerFactory.getLogger(DriverDSL::class.java)

/**
 * This is the interface that's exposed to
 */
interface DriverDSLExposedInterface {
    fun startNode(providedName: String? = null, advertisedServices: Set<ServiceType> = setOf()): NodeInfo
    fun waitForAllNodesToFinish()
    val messagingService: MessagingService
    val networkMapCache: NetworkMapCache
}

interface DriverDSLInternalInterface : DriverDSLExposedInterface {
    fun start()
    fun shutdown()
}

sealed class PortAllocation {
    abstract fun nextPort(): Int
    fun nextHostAndPort(): HostAndPort = HostAndPort.fromParts("localhost", nextPort())

    class Incremental(private var portCounter: Int) : PortAllocation() {
        override fun nextPort() = portCounter++
    }
    class RandomFree(): PortAllocation() {
        override fun nextPort() = ServerSocket(0).use { it.localPort }
    }
}

/**
 * [driver] allows one to start up nodes like this:
 *   driver {
 *     val noService = startNode("NoService")
 *     val notary = startNode("Notary")
 *
 *     (...)
 *   }
 *
 * The driver implicitly bootstraps a [NetworkMapService] that may be accessed through a local cache [DriverDSL.networkMapCache]
 * The driver is an artemis node itself, the messaging service may be accessed by [DriverDSL.messagingService]
 *
 * @param baseDirectory The base directory node directories go into, defaults to "build/<timestamp>/". The node
 *   directories themselves are "<baseDirectory>/<legalName>/", where legalName defaults to "<randomName>-<messagingPort>"
 *   and may be specified in [DriverDSL.startNode].
 * @param portAllocation The port allocation strategy to use for the messaging and the web server addresses. Defaults to incremental.
 * @param debugPortAllocation The port allocation strategy to use for jvm debugging. Defaults to incremental.
 * @param dsl The dsl itself
 * @return The value returned in the [dsl] closure
  */
fun <A> driver(
        baseDirectory: String = "build/${getTimestampAsDirectoryName()}",
        portAllocation: PortAllocation = PortAllocation.Incremental(10000),
        debugPortAllocation: PortAllocation = PortAllocation.Incremental(5005),
        dsl: DriverDSLExposedInterface.() -> A
) = genericDriver(
        driverDsl = DriverDSL(
            portAllocation = portAllocation,
            debugPortAllocation = debugPortAllocation,
            baseDirectory = baseDirectory
        ),
        coerce = { it },
        dsl = dsl
)

/**
 * This is a helper method to allow extending of the DSL, along the lines of
 *   interface SomeOtherExposedDSLInterface : DriverDSLExposedInterface
 *   interface SomeOtherInternalDSLInterface : DriverDSLInternalInterface, SomeOtherExposedDSLInterface
 *   class SomeOtherDSL(val driverDSL : DriverDSL) : DriverDSLInternalInterface by driverDSL, SomeOtherInternalDSLInterface
 *
 * @param coerce We need this explicit coercion witness because we can't put an extra DI : D bound in a `where` clause
 */
fun <DI : DriverDSLExposedInterface, D : DriverDSLInternalInterface, A> genericDriver(
        driverDsl: D,
        coerce: (D) -> DI,
        dsl: DI.() -> A
): A {
    var shutdownHook: Thread? = null
    try {
        driverDsl.start()
        val returnValue = dsl(coerce(driverDsl))
        shutdownHook = Thread({
            driverDsl.shutdown()
        })
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        return returnValue
    } finally {
        driverDsl.shutdown()
        if (shutdownHook != null) {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        }
    }
}

private fun getTimestampAsDirectoryName(): String {
    val tz = TimeZone.getTimeZone("UTC")
    val df = SimpleDateFormat("yyyyMMddHHmmss")
    df.timeZone = tz
    return df.format(Date())
}

fun addressMustBeBound(hostAndPort: HostAndPort) {
    poll {
        try {
            Socket(hostAndPort.hostText, hostAndPort.port).close()
            Unit
        } catch (_exception: SocketException) {
            null
        }
    }
}

fun addressMustNotBeBound(hostAndPort: HostAndPort) {
    poll {
        try {
            Socket(hostAndPort.hostText, hostAndPort.port).close()
            null
        } catch (_exception: SocketException) {
            Unit
        }
    }
}

fun <A> poll(f: () -> A?): A {
    var counter = 0
    var result = f()
    while (result == null && counter < 120) {
        counter++
        Thread.sleep(500)
        result = f()
    }
    if (result == null) {
        throw Exception("Poll timed out")
    }
    return result
}

class DriverDSL(
        val portAllocation: PortAllocation,
        val debugPortAllocation: PortAllocation,
        val baseDirectory: String
) : DriverDSLInternalInterface {

    override val networkMapCache = InMemoryNetworkMapCache()
    private val networkMapName = "NetworkMapService"
    private val networkMapAddress = portAllocation.nextHostAndPort()
    private var networkMapNodeInfo: NodeInfo? = null
    private val registeredProcesses = LinkedList<Process>()

    //TODO: remove this once we can bundle quasar properly.
    private val quasarJarPath: String by lazy {
        val cl = ClassLoader.getSystemClassLoader()
        val urls = (cl as URLClassLoader).urLs
        val quasarPattern = ".*quasar.*\\.jar$".toRegex()
        val quasarFileUrl = urls.first { quasarPattern.matches(it.path) }
        Paths.get(quasarFileUrl.toURI()).toString()
    }

    val driverNodeConfiguration = NodeConfigurationFromConfig(
            NodeConfiguration.loadConfig(
                    baseDirectoryPath = Paths.get(baseDirectory, "driver-artemis"),
                    allowMissingConfig = true,
                    configOverrides = mapOf(
                            "myLegalName" to "driver-artemis"
                    )
            )
    )

    override val messagingService = ArtemisMessagingClient(
            Paths.get(baseDirectory, "driver-artemis"),
            driverNodeConfiguration,
            serverHostPort = networkMapAddress,
            myHostPort = portAllocation.nextHostAndPort(),
            executor = AffinityExecutor.ServiceAffinityExecutor("Client thread", 1)
    )
    var messagingServiceStarted = false

    fun registerProcess(process: Process) = registeredProcesses.push(process)

    override fun waitForAllNodesToFinish() {
        registeredProcesses.forEach {
            it.waitFor()
        }
    }

    override fun shutdown() {
        registeredProcesses.forEach(Process::destroy)
        /** Wait 5 seconds, then [Process.destroyForcibly] */
        val finishedFuture = Executors.newSingleThreadExecutor().submit {
            waitForAllNodesToFinish()
        }
        try {
            finishedFuture.get(5, TimeUnit.SECONDS)
        } catch (exception: TimeoutException) {
            finishedFuture.cancel(true)
            registeredProcesses.forEach {
                it.destroyForcibly()
            }
        }
        if (messagingServiceStarted)
            messagingService.stop()

        // Check that we shut down properly
        addressMustNotBeBound(messagingService.myHostPort)
        addressMustNotBeBound(networkMapAddress)
    }

    /**
     * Starts a [Node] in a separate process.
     *
     * @param providedName Optional name of the node, which will be its legal name in [Party]. Defaults to something
     *   random. Note that this must be unique as the driver uses it as a primary key!
     * @param advertisedServices The set of services to be advertised by the node. Defaults to empty set.
     * @return The [NodeInfo] of the started up node retrieved from the network map service.
     */
    override fun startNode(providedName: String?, advertisedServices: Set<ServiceType>): NodeInfo {
        val messagingAddress = portAllocation.nextHostAndPort()
        val apiAddress = portAllocation.nextHostAndPort()
        val debugPort = debugPortAllocation.nextPort()
        val name = providedName ?: "${pickA(name)}-${messagingAddress.port}"

        val nodeDirectory = "$baseDirectory/$name"

        val config = NodeConfiguration.loadConfig(
                baseDirectoryPath = Paths.get(nodeDirectory),
                allowMissingConfig = true,
                configOverrides = mapOf(
                        "myLegalName" to name
                )
        )

        val driverCliParams = NodeRunner.CliParams(
                services = advertisedServices,
                networkMapName = networkMapNodeInfo!!.identity.name,
                networkMapPublicKey = networkMapNodeInfo!!.identity.owningKey,
                networkMapAddress = networkMapAddress,
                messagingAddress = messagingAddress,
                apiAddress = apiAddress,
                baseDirectory = nodeDirectory
        )
        registerProcess(DriverDSL.startNode(config, driverCliParams, name, quasarJarPath, debugPort))

        return poll {
            networkMapCache.partyNodes.forEach {
                if (it.identity.name == name) {
                    return@poll it
                }
            }
            null
        }
    }

    override fun start() {
        startNetworkMapService()
        messagingService.configureWithDevSSLCertificate()
        messagingService.start()
        thread { messagingService.run() }
        messagingServiceStarted = true
        // We fake the network map's NodeInfo with a random public key in order to retrieve the correct NodeInfo from
        // the network map service itself
        val fakeNodeInfo = NodeInfo(
                address = ArtemisMessagingClient.makeRecipient(networkMapAddress),
                identity = Party(
                        name = networkMapName,
                        owningKey = generateKeyPair().public
                ),
                advertisedServices = setOf(NetworkMapService.Type)
        )
        networkMapCache.addMapService(messagingService, fakeNodeInfo, true)
        networkMapNodeInfo = poll {
            networkMapCache.partyNodes.forEach {
                if (it.identity.name == networkMapName) {
                    return@poll it
                }
            }
            null
        }
    }

    private fun startNetworkMapService() {
        val apiAddress = portAllocation.nextHostAndPort()
        val debugPort = debugPortAllocation.nextPort()

        val nodeDirectory = "$baseDirectory/$networkMapName"

        val config = NodeConfiguration.loadConfig(
                baseDirectoryPath = Paths.get(nodeDirectory),
                allowMissingConfig = true,
                configOverrides = mapOf(
                        "myLegalName" to networkMapName
                )
        )

        val driverCliParams = NodeRunner.CliParams(
                services = setOf(NetworkMapService.Type),
                networkMapName = null,
                networkMapPublicKey = null,
                networkMapAddress = null,
                messagingAddress = networkMapAddress,
                apiAddress = apiAddress,
                baseDirectory = nodeDirectory
        )
        log.info("Starting network-map-service")
        registerProcess(startNode(config, driverCliParams, networkMapName, quasarJarPath, debugPort))
    }

    companion object {

        val name = arrayOf(
                "Alice",
                "Bob",
                "EvilBank",
                "NotSoEvilBank"
        )
        fun <A> pickA(array: Array<A>): A = array[Math.abs(Random().nextInt()) % array.size]

        private fun startNode(
                config: Config,
                cliParams: NodeRunner.CliParams,
                legalName: String,
                quasarJarPath: String,
                debugPort: Int
        ): Process {

            // Write node.conf
            writeConfig(cliParams.baseDirectory, "node.conf", config)

            val className = NodeRunner::class.java.canonicalName
            val separator = System.getProperty("file.separator")
            val classpath = System.getProperty("java.class.path")
            val path = System.getProperty("java.home") + separator + "bin" + separator + "java"
            val javaArgs = listOf(path) +
                    listOf("-Dname=$legalName", "-javaagent:$quasarJarPath",
                            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=$debugPort",
                            "-cp", classpath, className) +
                    cliParams.toCliArguments()
            val builder = ProcessBuilder(javaArgs)
            builder.redirectError(Paths.get("error.$className.log").toFile())
            builder.inheritIO()
            val process = builder.start()
            addressMustBeBound(cliParams.messagingAddress)
            // TODO There is a race condition here. Even though the messaging address is bound it may be the case that
            // the handlers for the advertised services are not yet registered. A hacky workaround is that we wait for
            // the web api address to be bound as well, as that starts after the services. Needs rethinking.
            addressMustBeBound(cliParams.apiAddress)

            return process
        }
    }
}

fun writeConfig(path: String, filename: String, config: Config) {
    File(path).mkdirs()
    File("$path/$filename").writeText(config.root().render(ConfigRenderOptions.concise()))
}
