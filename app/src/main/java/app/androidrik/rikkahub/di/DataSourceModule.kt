package app.androidrik.rikkahub.di

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
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
import app.androidrik.common.http.AcceptLanguageBuilder
import app.androidrik.rikkahub.BuildConfig
import app.androidrik.rikkahub.data.ai.AIRequestInterceptor
import app.androidrik.rikkahub.data.ai.RequestLoggingInterceptor
import app.androidrik.rikkahub.data.ai.transformers.AssistantTemplateLoader
import app.androidrik.rikkahub.data.ai.GenerationHandler
import app.androidrik.rikkahub.data.ai.transformers.TemplateTransformer
import app.androidrik.rikkahub.data.rag.RagRepository
import app.androidrik.rikkahub.data.api.RikkaHubAPI
import app.androidrik.rikkahub.data.datastore.SettingsStore
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

    single {
        val context: Context = get()
        Room.databaseBuilder(context, AppDatabase::class.java, "rikka_hub")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(Migration_6_7, Migration_11_12, Migration_13_14, Migration_14_15, Migration_15_16, Migration_27_28, Migration_28_29)
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
        MessageFtsManager(get())
    }

    single { McpManager(settingsStore = get(), appScope = get(), filesManager = get(), appEventBus = get()) }

    single {
        GenerationHandler(
            context = get(),
            providerManager = get(),
            json = get()
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
            .addInterceptor(HttpLoggingInterceptor().apply {
                redactHeader("Proxy-Authorization")
                level = HttpLoggingInterceptor.Level.HEADERS
            })
            .build().also { SearchService.init(it, get()) }
    }

    single {
        ProviderManager(client = get(), context = get())
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

    single<Retrofit> {
        Retrofit.Builder()
            .baseUrl("https://api.rikka-ai.com")
            .addConverterFactory(get<Json>().asConverterFactory("application/json; charset=UTF8".toMediaType()))
            .build()
    }

    single<RikkaHubAPI> {
        get<Retrofit>().create(RikkaHubAPI::class.java)
    }

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
    single<MemoryProposalStore> {
        val database: AppDatabase = get()
        MemoryProposalRepository(
            proposalDAO = database.memoryProposalDao(),
            memoryRepository = get(),
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
