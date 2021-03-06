//--------------------------------------------------
// Class ComplexBotDriver
//--------------------------------------------------
// Written by Kenvix <i@kenvix.com>
//--------------------------------------------------

package com.kenvix.complexbot

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.CacheStats
import com.google.common.cache.LoadingCache
import com.kenvix.android.utils.Coroutines
import com.kenvix.complexbot.rpc.thrift.BackendBridge
import com.kenvix.moecraftbot.mirai.lib.bot.AbstractDriver
import com.kenvix.moecraftbot.ng.Defines
import com.kenvix.moecraftbot.ng.lib.Cached
import com.kenvix.moecraftbot.ng.lib.CachedClasses
import com.kenvix.moecraftbot.ng.lib.cacheLoader
import com.kenvix.utils.exception.NotFoundException
import com.kenvix.utils.log.LoggingOutputStream
import com.mongodb.client.result.UpdateResult
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.IO
import org.apache.commons.collections4.map.ReferenceMap
import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.TFramedTransport
import org.apache.thrift.transport.TSocket
import org.bson.types.ObjectId
import org.litote.kmongo.Id
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.eq
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.HashMap

class ComplexBotDriver : AbstractDriver<ComplexBotConfig>(), Cached {
    override val driverName: String get() = "ComplexBot"
    override val driverVersion: String get() = "0.1"
    override val driverVersionCode: Int get() = 1
    override val configFileName: String get() = "complexbot"

    private val GroupOptionsMaxCacheSeconds: Long = 60 * 10 //TODO

    private var backendPort: Int = 48519
    private var backendHost: String = "localhost"
    private var backendDir: String? = "./backend"
    private var backendRuntimeFileName: String = "python"
    private val backendScriptFile: String
        get() = "$backendDir/main.py"
    private val coroutine = Coroutines()

    private var backendSocket: Socket? = null

    var backendClient: BackendBridge.Client? = null
        private set

    private var backendProcess: Process? = null
        @Synchronized get
        @Synchronized set
    var miraiComponent: ComplexBotMiraiComponent? = null
        private set

    lateinit var groupMongoCollection: CoroutineCollection<GroupOptions>

    val groupOptionsCache: LoadingCache<Long, GroupOptions> = CacheBuilder.newBuilder().apply {
        recordStats()
        expireAfterWrite(GroupOptionsMaxCacheSeconds, TimeUnit.SECONDS)
        removalListener<Long, GroupOptions> { runBlocking { saveGroupOptions(it.value) } }
    }.build(cacheLoader { key ->
        runBlocking {
            groupMongoCollection.findOne(GroupOptions::groupId eq key) ?: createGroupOptions(key)
        }
    })

    init {
        CachedClasses.add(this)
    }

    override fun onEnable() {
        super.onEnable()
        runBlocking {
            loadComplexBotOptions()
            groupMongoCollection = Defines.mongoDatabase.getCollection("groupOptions")

            loadBackend()
            loadMiraiBot()
        }
    }

    override fun onDisable() = runBlocking {
        super.onDisable()
        enabledFeatures.forEach { it.onDisable() }

        if (miraiComponent != null) {
            miraiComponent!!.close()
            miraiComponent = null
        }

        stopBackend()
        coroutine.close()
    }

    override fun onSystemConsoleInput(input: String): Boolean {
        return super.onSystemConsoleInput(input)
    }

    private fun loadComplexBotOptions() {
        if (System.getProperties().contains("complexbot.backend.port"))
            backendPort = System.getProperties().getProperty("complexbot.backend.port").toInt()

        if (System.getProperties().contains("complexbot.backend.host"))
            backendHost = System.getProperties().getProperty("complexbot.backend.host")

        if (System.getProperties().contains("complexbot.backend.dir"))
            backendDir = System.getProperties().getProperty("complexbot.backend.dir")

        if (System.getProperties().getProperty("complexbot.backend.noinstance")?.toBoolean() == true)
            backendDir = null

        if (System.getProperties().contains("complexbot.backend.runtime.filename"))
            backendRuntimeFileName = System.getProperties().getProperty("complexbot.backend.runtime.filename")
    }

    internal suspend fun loadBackend() = withContext(IO) {
        if (backendDir != null) {
            if (backendProcess != null)
                throw IllegalStateException("Trying to start a new backend without stopping old one")

            if (!File(backendScriptFile).exists())
                throw FileNotFoundException("Backend Script file not found on $backendScriptFile")

            logger.info("Starting backend process $backendRuntimeFileName $backendScriptFile on port $backendPort")
            val backendArgs = "main.py --host $backendHost --port $backendPort"
            val processBuilder = ProcessBuilder(
                    backendRuntimeFileName,
                    *backendArgs.split(' ').toTypedArray()
            ).redirectErrorStream(true).directory(File(backendDir!!))

            logger.trace("Work Dir: " + Paths.get(backendDir!!).toAbsolutePath())
            logger.trace("$ $backendRuntimeFileName $backendArgs")

            backendProcess = processBuilder.start()!!.apply {
                launch(IO) { inputStream.transferTo(LoggingOutputStream(LoggerFactory.getLogger("Backend.Out"))) }
                launch(IO) { errorStream.transferTo(LoggingOutputStream(LoggerFactory.getLogger("Backend.Err"))) }
                launch(IO) {
                    if (waitFor(10, TimeUnit.SECONDS))
                        throw IllegalStateException("Backend process exited unexpectedly with code ${exitValue()}")
                }
            }
        }

        logger.info("Connecting to backend on $backendHost:$backendPort")
        backendSocket = Socket().apply {
            keepAlive = true
            connect(InetSocketAddress(backendHost, backendPort), SocketTimeout)
       }

        val transportSocket = TSocket(backendSocket)
        val transport = TFramedTransport(transportSocket)
        val protocol = TBinaryProtocol(transport)
        backendClient = BackendBridge.Client(protocol)
        transportSocket.setTimeout(1000)

        if (backendClient!!.ping("hello") == "hello") {
            logger.info("Backend connected")
        } else {
            throw IllegalStateException("Unrecognized backend reply. expected hello")
        }
    }

    internal suspend fun stopBackend() = withContext(IO) {
        if (backendSocket != null) {
            logger.info("Closing backend connection (port ${backendSocket!!.port})")
            backendSocket!!.close()
            backendSocket = null
        }

        if (backendProcess != null) {
            if (backendProcess!!.isAlive) {
                logger.info("Stopping backend process (PID ${backendProcess!!.pid()}) in 10 seconds")
                backendProcess!!.destroy()
                if (!backendProcess!!.waitFor(10, TimeUnit.SECONDS)) {
                    logger.warn("Backend process not responded, Force killing")
                    backendProcess!!.destroyForcibly()
                    backendProcess!!.waitFor()
                } else {
                    logger.info("Backend process exited with code ${backendProcess!!.exitValue()}")
                }
            }

            backendProcess = null
        }
    }

    internal fun loadMiraiBot() {
        logger.debug("Loading mirai")

        callBridge = object : CallBridge {
            override val backendClient: BackendBridge.Client
                get() = this@ComplexBotDriver.backendClient!!

            override val config: ComplexBotConfig
                get() = this@ComplexBotDriver.config.content

            override val driver: ComplexBotDriver
                get() = this@ComplexBotDriver

            override fun getGroupOptions(groupId: Long): GroupOptions
                    = this@ComplexBotDriver.getGroupOptions(groupId)

            override fun saveGroupOptions(options: GroupOptions): UpdateResult
                    = this@ComplexBotDriver.saveGroupOptions(options)

            override fun setGroupOptions(options: GroupOptions)
                    = this@ComplexBotDriver.setGroupOptions(options)

            override fun getAllGroupOptions(): List<GroupOptions>
                    = this@ComplexBotDriver.getAllGroupOptions()
        }

        if (miraiComponent == null) {
            miraiComponent = ComplexBotMiraiComponent(
                qq = config.content.bot.qq,
                password = config.content.bot.password
            )
            miraiComponent!!.start()
        }
    }

    fun getGroupOptions(groupId: Long): GroupOptions {
        return groupOptionsCache[groupId]
    }

    fun getAllGroupOptions(): List<GroupOptions> = runBlocking {
        groupMongoCollection.find().toList()
    }

    fun setGroupOptions(options: GroupOptions) {
        groupOptionsCache.put(options.groupId, options)
    }

    fun saveGroupOptions(options: GroupOptions): UpdateResult = runBlocking {
        groupMongoCollection.updateOneById(options._id, options)
    }

    private fun createGroupOptions(groupId: Long): GroupOptions = runBlocking {
        GroupOptions(groupId = groupId).also { groupMongoCollection.insertOne(it) }
    }

    companion object {
        const val SocketTimeout = 10000
    }

    override fun invalidateAll() {
        groupOptionsCache.invalidateAll()
    }

    override fun cleanUpAll() {
        groupOptionsCache.cleanUp()
    }

    override fun getStats(): List<CacheStats> {
        return listOf(groupOptionsCache.stats())
    }
}