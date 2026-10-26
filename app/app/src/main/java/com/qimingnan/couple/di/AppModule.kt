package com.qimingnan.couple.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.qimingnan.couple.BuildConfig
import com.qimingnan.couple.core.Clock
import com.qimingnan.couple.core.CoupleJson
import com.qimingnan.couple.data.auth.DeviceIdentity
import com.qimingnan.couple.data.auth.EncryptedTokenStore
import com.qimingnan.couple.data.auth.TokenRefresher
import com.qimingnan.couple.data.auth.TokenStore
import com.qimingnan.couple.data.bootstrap.BootstrapManager
import com.qimingnan.couple.data.bootstrap.BootstrapStore
import com.qimingnan.couple.data.bootstrap.ConfigVerifyWorker
import com.qimingnan.couple.data.bootstrap.DataStoreBootstrapStore
import com.qimingnan.couple.data.bootstrap.OkHttpServerProbe
import com.qimingnan.couple.data.bootstrap.ServerProbe
import com.qimingnan.couple.data.bootstrap.apiRoot
import com.qimingnan.couple.data.db.AppDatabase
import com.qimingnan.couple.data.db.ChatDao
import com.qimingnan.couple.data.db.FeedDao
import com.qimingnan.couple.data.db.ProfileDao
import com.qimingnan.couple.data.demo.DemoInterceptor
import com.qimingnan.couple.data.net.AuthHeaderInterceptor
import com.qimingnan.couple.data.net.BaseUrlInterceptor
import com.qimingnan.couple.data.net.CoupleApi
import com.qimingnan.couple.data.net.TokenAuthenticator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class PlainClient
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApiClient
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun bindTokenStore(impl: EncryptedTokenStore): TokenStore
    @Binds abstract fun bindBootstrapStore(impl: DataStoreBootstrapStore): BootstrapStore
    @Binds abstract fun bindServerProbe(impl: OkHttpServerProbe): ServerProbe
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton @ApplicationScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun clock(): Clock = Clock.System

    private fun logging() = HttpLoggingInterceptor().apply {
        // 只在 debug 打印请求行，绝不打印 body（含密码/token）
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        redactHeader("Authorization")
    }

    /** 无鉴权客户端：健康检查、公钥、refresh。 */
    @Provides @Singleton @PlainClient
    fun plainClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(DemoInterceptor()) // debug 调试账号：只拦截 demo.couple.local
        .addInterceptor(logging())
        .build()

    @Provides @Singleton
    fun tokenRefresher(
        tokenStore: TokenStore,
        @PlainClient client: OkHttpClient,
        bootstrapStore: BootstrapStore,
        device: DeviceIdentity,
    ): TokenRefresher = TokenRefresher(
        tokenStore = tokenStore,
        client = client,
        apiRootProvider = { bootstrapStore.currentConfigBlocking()?.baseUrl?.let(::apiRoot) },
        deviceIdProvider = { device.deviceId },
    )

    @Provides @Singleton @ApiClient
    fun apiClient(
        @PlainClient plain: OkHttpClient,
        bootstrapStore: BootstrapStore,
        tokenStore: TokenStore,
        refresher: TokenRefresher,
    ): OkHttpClient = plain.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .apply { interceptors().clear() }
        .addInterceptor(BaseUrlInterceptor.fromStore(bootstrapStore))
        .addInterceptor(AuthHeaderInterceptor(tokenStore))
        .addInterceptor(DemoInterceptor())
        .addInterceptor(logging())
        .authenticator(TokenAuthenticator(refresher))
        .build()

    @Provides @Singleton
    fun retrofit(@ApiClient client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(CoupleApi.PLACEHOLDER_BASE)
        .client(client)
        .addConverterFactory(CoupleJson.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides @Singleton
    fun coupleApi(retrofit: Retrofit): CoupleApi = retrofit.create(CoupleApi::class.java)

    /** Coil 全局 ImageLoader（媒体经 apiClient 带 Bearer 加载）。 */
    @Provides @Singleton
    fun imageLoader(@ApplicationContext context: Context, @ApiClient client: OkHttpClient): coil.ImageLoader =
        coil.ImageLoader.Builder(context)
            .okHttpClient(client)
            .crossfade(true)
            .build()

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "couple.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()

    @Provides
    fun profileDao(db: AppDatabase): ProfileDao = db.profileDao()

    @Provides
    fun feedDao(db: AppDatabase): FeedDao = db.feedDao()

    @Provides
    fun chatDao(db: AppDatabase): ChatDao = db.chatDao()

    @Provides @Singleton
    fun bootstrapManager(
        @ApplicationContext context: Context,
        store: BootstrapStore,
        probe: ServerProbe,
        clock: Clock,
    ): BootstrapManager = BootstrapManager(
        store = store,
        probe = probe,
        clock = clock,
        allowCleartext = BuildConfig.ALLOW_CLEARTEXT_DEBUG,
        scheduler = ConfigVerifyWorker.scheduler { WorkManager.getInstance(context) },
    )
}
