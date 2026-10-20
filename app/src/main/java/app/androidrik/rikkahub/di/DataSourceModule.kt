package app.androidrik.rikkahub.di

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpHeaders
import io.pebbletemplates.pebble.PebbleEngine
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import app.androidrik.ai.provider.ProviderManager
import app.androidrik.mediagen.provider.MediaGenerationManager
import app.androidrik.rikkahub.data.ai.MemoryConsolidationAgent
import app.androidrik.rikkahub.data.ai.MemoryConsolidationGenerationAgent
import app.androidrik.rikkahub.data.ai.MemoryConsolidationRuntime
import app.androidrik.rikkahub.data.ai.MemoryConsolidationScheduler
import app.androidrik.rikkahub.data.ai.MemoryConsolidationTrigger
import app.androidrik.rikkahub.data.ai.MemoryConsolidationWorker
import app.androidrik.rikkahub.data.ai.MemoryConsolidator
import app.androidrik.rikkahub.data.ai.AppForegroundState
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.repository.MemoryProposalRepository
import app.androidrik.rikkahub.data.repository.MemoryProposalStore
import app.androidrik.rikkahub.data.repository.MemoryRepository
import app.androidrik.rikkahub.data.repository.MemoryTransactionRunner
import app.androidrik.rikkahub.data.repository.RoomMemoryTransactionRunner
import app.androidrik.common.http.AcceptLanguageBuilder
import app.androidrik.common.android.sanitizeSensitiveText
import app.androidrik.rikkahub.BuildConfig
import app.androidrik.rikkahub.data.ai.AIRequestInterceptor
import app.androidrik.rikkahub.data.ai.RequestLoggingInterceptor
import app.androidrik.rikkahub.data.ai.transformers.AssistantTemplateLoader
import app.androidrik.rikkahub.data.ai.GenerationHandler
import app.androidrik.rikkahub.data.ai.UsageRecorder
import app.androidrik.rikkahub.data.ai.transformers.TemplateTransformer
import app.androidrik.rikkahub.data.rag.RagRepository
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.files.RemoteFileStore
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.fts.MessageFtsManager
import app.androidrik.rikkahub.data.db.fts.SimpleDictManager
import app.androidrik.rikkahub.data.db.migrations.Migration_6_7
import app.androidrik.rikkahub.data.db.migrations.Migration_11_12
import app.androidrik.rikkahub.data.db.migrations.Migration_13_14
import app.androidrik.rikkahub.data.db.migrations.Migration_14_15
import app.androidrik.rikkahub.data.db.migrations.Migration_15_16
import app.androidrik.rikkahub.data.db.migrations.Migration_27_28
import app.androidrik.rikkahub.data.db.migrations.Migration_28_29
import app.androidrik.rikkahub.data.db.migrations.Migration_40_41
import app.androidrik.rikkahub.data.db.migrations.Migration_41_42
import app.androidrik.rikkahub.data.db.migrations.Migration_43_44
import app.androidrik.rikkahub.data.ai.mcp.McpManager
import app.androidrik.rikkahub.data.network.RikkaProxyAuthenticator
import app.androidrik.rikkahub.data.network.SettingsProxyAuthenticator
import app.androidrik.rikkahub.data.network.SettingsProxySelector
import app.androidrik.rikkahub.data.network.SocksCredentialRegistry
import app.androidrik.rikkahub.data.sync.webdav.WebDavSync
import app.androidrik.search.SearchService
import app.androidrik.rikkahub.data.sync.S3Sync
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

val dataSourceModule = module {
    single {
        SettingsStore(context = get(), scope = get())
    }

    // 素材上传（媒体生成参考素材等）：独立 uploadS3Config，与备份 S3 桶互不影响
    single {
        RemoteFileStore(
            settingsStore = get(),
            httpClient = get(),
            scope = get<AppScope>(),
        )
    }

    single {
        val context: Context = get()
        Room.databaseBuilder(context, AppDatabase::class.java, "rikka_hub")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                Migration_6_7, Migration_11_12, Migration_13_14, Migration_14_15,
                Migration_15_16, Migration_27_28, Migration_28_29, Migration_40_41, Migration_41_42,
                Migration_43_44,
            )
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    val dictDir = SimpleDictManager.extractDict(context)
                    val cursor = db.query("SELECT jieba_dict(?)", arrayOf(dictDir.absolutePath))
                    cursor.use {
                        if (it.moveToFirst()) {
                            val result = it.getString(0)
                            val success = result?.trimEnd('/') == dictDir.absolutePath.trimEnd('/')
                            if (!success) {
                                android.util.Log.e(
                                    "DataSourceModule",
                                    "jieba_dict failed: $result, path=${dictDir.absolutePath}"
                                )
                            }
                        }
                    }
                    db.execSQL(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
                            text,
                            node_id UNINDEXED,
                            message_id UNINDEXED,
                            conversation_id UNINDEXED,
                            title UNINDEXED,
                            update_at UNINDEXED,
                            tokenize = 'simple'
                        )
                        """.trimIndent()
                    )
                }
            })
            .openHelperFactory(
                RequerySQLiteOpenHelperFactory(
                    listOf(
                RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                    options.customExtensions.add(
                        SQLiteCustomExtension(
                            context.applicationInfo.nativeLibraryDir + "/libsimple",
                            null
                        )
                    )
                    options
                }
            )))
            .build()
    }

    single {
        AssistantTemplateLoader(settingsStore = get())
    }

    single {
        PebbleEngine.Builder()
            .loader(get<AssistantTemplateLoader>())
            .defaultLocale(Locale.getDefault())
            .autoEscaping(false)
            .build()
    }

    single { TemplateTransformer(engine = get(), settingsStore = get()) }

    single {
        get<AppDatabase>().conversationDao()
    }

    single {
        get<AppDatabase>().memoryDao()
    }

    single {
        get<AppDatabase>().memoryConsolidationDao()
    }

    single {
        get<AppDatabase>().knowledgeBaseDao()
    }

    single {
        RagRepository(
            context = get(),
            dao = get(),
            settingsStore = get(),
            providerManager = get(),
            json = get(),
        )
    }

    single {
        get<AppDatabase>().genMediaDao()
    }

    single {
        get<AppDatabase>().mediaCreationDao()
    }

    single {
        get<AppDatabase>().messageNodeDao()
    }

    single {
        get<AppDatabase>().managedFileDao()
    }

    single {
        get<AppDatabase>().favoriteDao()
    }

    single {
        get<AppDatabase>().workspaceDao()
    }

    single {
        get<AppDatabase>().folderDao()
    }

    single {
        get<AppDatabase>().compressionCheckpointDao()
    }

    single {
        get<AppDatabase>().agentRunDao()
    }

    single {
        get<AppDatabase>().usageRecordDao()
    }

    // 用量流水写入器：每次模型请求记一行（见 UsageRecorder 与 docs/design/design-usage-stats.md）
    single {
        UsageRecorder(dao = get(), scope = get<AppScope>())
    }

    single {
        MessageFtsManager(get())
    }

    single { McpManager(settingsStore = get(), appScope = get(), filesManager = get(), appEventBus = get()) }

    single {
        GenerationHandler(
            context = get(),
            providerManager = get(),
            json = get(),
            usageRecorder = get(),
        )
    }

    single<OkHttpClient> {
        val settingsStore: SettingsStore = get()
        val acceptLang = AcceptLanguageBuilder.fromAndroid(get())
            .build()

        // 两层代理的全局层（docs/design/design-proxy-two-layer.md §二）：
        // 只装在共享 client 上——ai 模块对全局设置零感知（provider 选 Inherit 即不配置）。
        val socksRegistry = SocksCredentialRegistry()
        runCatching {
            java.net.Authenticator.setDefault(RikkaProxyAuthenticator(socksRegistry))
        }
        // 启动即刷新一次凭据表；之后随 settings 变化持续跟进（热切换，无需重建 client）
        get<AppScope>().launch {
            settingsStore.settingsFlowRaw.collect { settings ->
                socksRegistry.update(
                    globalProxy = settings.globalProxy,
                    providers = settings.providers.map { it.proxy },
                )
            }
        }

        OkHttpClient.Builder()
            .proxySelector(SettingsProxySelector(settingsStore))
            .proxyAuthenticator(SettingsProxyAuthenticator(settingsStore))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()
                    .addHeader(HttpHeaders.AcceptLanguage, acceptLang)

                if (originalRequest.header(HttpHeaders.UserAgent) == null) {
                    requestBuilder.addHeader(HttpHeaders.UserAgent, "RikkaHub-Android/${BuildConfig.VERSION_NAME}")
                }

                chain.proceed(requestBuilder.build())
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val contentTypeHeader = request.header("Content-Type")
                if (
                    contentTypeHeader != null &&
                    contentTypeHeader.contains(";") &&
                    contentTypeHeader.substringBefore(";").trim().equals("application/json", ignoreCase = true)
                ) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Content-Type", contentTypeHeader.substringBefore(";").trim())
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
            .addNetworkInterceptor(RequestLoggingInterceptor())
            .addInterceptor(AIRequestInterceptor())
            .apply {
                // 仅 debug 输出 HTTP 日志：release 不挂该拦截器（避免把请求行/凭据打到 logcat）。
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor { message ->
                            // 请求行含完整 URL：预签名 query 参数（X-Amz-Signature 等）先统一脱敏再进 logcat。
                            Log.d("HttpLogging", sanitizeSensitiveText(message))
                        }.apply {
                            redactHeader("Authorization")
                            redactHeader("x-api-key")
                            redactHeader("x-goog-api-key")
                            redactHeader("Proxy-Authorization")
                            level = HttpLoggingInterceptor.Level.HEADERS
                        }
                    )
                }
            }
            .build().also { SearchService.init(it, get()) }
    }

    single {
        ProviderManager(client = get(), context = get())
    }

    single {
        MediaGenerationManager(client = get())
    }

    single {
        WebDavSync(
            settingsStore = get(),
            json = get(),
            context = get(),
            httpClient = get(),
            appDatabase = get(),
        )
    }

    single<HttpClient> {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(20, TimeUnit.SECONDS)
                    readTimeout(10, TimeUnit.MINUTES)
                    writeTimeout(120, TimeUnit.SECONDS)
                    followSslRedirects(true)
                    followRedirects(true)
                    retryOnConnectionFailure(true)
                }
            }
        }
    }

    single {
        S3Sync(
            settingsStore = get(),
            json = get(),
            context = get(),
            httpClient = get(),
            appDatabase = get(),
        )
    }

    // 这里原本有一个指向 `https://api.rikka-ai.com`（上游作者自己的中转）的 Retrofit 与
    // `RikkaHubAPI`（**空接口**，全仓零调用）。那个内置供应商已从出厂默认移除 ⇒ 两者都是死代码，
    // 一并删掉：留着等于把一个已经不再提供的上游服务地址继续编进包里。
    // 见 `DefaultProviders.kt` 的 `DEFAULT_AUTO_MODEL_ID` kdoc。

    // ---- 做梦（V3）：整理 agent / worker / 触发器 / 运行时 ----
    // 全部是 single：同一记忆库不许并发跑两次（调度器内部还有 Mutex，这里是第二道）。

    // 同样必须绑定到接口：worker 要的是 MemoryConsolidationAgent
    single<MemoryConsolidationAgent> {
        MemoryConsolidationGenerationAgent(
            generationHandler = get(),
            memoryRepository = get(),
            json = get(),
        )
    }

    // 建议的落库与应用：要在 worker 之前建（MemoryConsolidator 委托给它）
    //
    // **必须显式绑定到接口 `MemoryProposalStore`**（`MemoryConsolidator` 的形参是那个窄接口）。
    // 按具体类型注册时 Koin 只认 `MemoryProposalRepository`，而 `get<MemoryProposalStore>()`
    // 会抛 `NoDefinitionFound`——**真机装机当场崩在 Application.onCreate**
    // （`Could not create instance for '[Singleton: …]'` → `No definition found for type …`）。
    // 与下面那条 worker 绑定是**同一个坑的第二例**：源码扫描守卫看不出这种绑定缺失，
    // 只有真机（或 Koin 的 verify）能发现。已在 `MemoryProposalWiringTest` 补一条守卫钉住。
    // All memory DAOs and nested units of work join this same Room database transaction.
    single<MemoryTransactionRunner> { RoomMemoryTransactionRunner(get<AppDatabase>()) }

    single<MemoryProposalStore> {
        val database: AppDatabase = get()
        MemoryProposalRepository(
            proposalDAO = database.memoryProposalDao(),
            memoryRepository = get(),
            transactions = get(),
        )
    }

    // 必须显式绑定到**接口**：调度器要的是 MemoryConsolidationWorker，
    // 按具体类型注册时 Koin 只认 MemoryConsolidator，`get()` 会抛 NoDefinitionFound——
    // 真机才发现（源码扫描守卫看不出这种绑定缺失）
    single<MemoryConsolidationWorker> {
        val memoryRepository: MemoryRepository = get()
        val settingsStore: SettingsStore = get()
        MemoryConsolidator(
            repository = get(),
            memories = { memoryId -> memoryRepository.getMemoriesOfAssistant(memoryId) },
            agent = get(),
            settings = { settingsStore.settingsFlow.first() },
            proposals = get(),
            transactions = get(),
        )
    }

    single { AppForegroundState() }

    single {
        val foreground: AppForegroundState = get()
        MemoryConsolidationScheduler(
            // **必须是后台调度器**：AppScope 是 Dispatchers.Main，而整理要跑模型循环、
            // 切轮次、数 token——放主线程等于拿输入响应当代价（同 AppScope 的那批主线程账单）。
            // 只换 dispatcher，Job 仍挂在 AppScope 上（进程级生命周期不变）。
            scope = CoroutineScope(get<AppScope>().coroutineContext + Dispatchers.Default),
            worker = get(),
            isAppVisible = { foreground.isForeground },
        )
    }

    single {
        val conversationRepository: ConversationRepository = get()
        MemoryConsolidationTrigger(
            scheduler = get(),
            conversations = { id -> conversationRepository.getConversationById(id) },
        )
    }

    single {
        MemoryConsolidationRuntime(
            trigger = get(),
            scheduler = get(),
            foreground = get(),
            appEventBus = get(),
            appScope = get<AppScope>(),
        )
    }
}
