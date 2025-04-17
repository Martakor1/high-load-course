package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import okhttp3.*
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.FixedWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.Executors

// Advice: always treat time as a Duration
class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)

        val emptyBody = RequestBody.create(null, ByteArray(0))
        val mapper = ObjectMapper().registerKotlinModule()
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAverageProcessingTime = properties.averageProcessingTime
    private val rateLimitPerSec = properties.rateLimitPerSec
    private val parallelRequests = properties.parallelRequests

    //case-5
    //private val OK_HTTP_CLIENT_TIMEOUT = requestAverageProcessingTime.multipliedBy(2) //just because (excel)
    //parallelRequests / rateLimitPerSec.toLong()
    //private val OK_HTTP_CLIENT_TIMEOUT = Duration.ofSeconds(1) //case-6 5win/5rps
    private val OK_HTTP_CLIENT_TIMEOUT =
        Duration.ofSeconds(20) //case-7 20000win/1000rps=20s (исчерпаем все окна за 20s и выйдем на оборот при том же avgTime=20)

//    private val client = OkHttpClient.Builder() //default timeout = 10s!
//        .callTimeout(OK_HTTP_CLIENT_TIMEOUT) //full call to serv, write + read
//        .readTimeout(OK_HTTP_CLIENT_TIMEOUT) //only between reed packages in tcp buffer
//        .writeTimeout(OK_HTTP_CLIENT_TIMEOUT) //only between packages to write (send) in tcp buffer
//        .connectTimeout(OK_HTTP_CLIENT_TIMEOUT) //only for establishing connection
//        .connectionPool(ConnectionPool(properties.parallelRequests, 10, TimeUnit.SECONDS))
//        .protocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE)) //HTTP2 enable
//        .build()

    private val client = HttpClient.newBuilder() //case-7
        .executor(Executors.newFixedThreadPool(20))
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(OK_HTTP_CLIENT_TIMEOUT)
        .build()

    //case 1-2
    private val rpsLimiter = FixedWindowRateLimiter(rateLimitPerSec, 1, TimeUnit.SECONDS)

    //     private val parallelRequestSemaphore = Semaphore(parallelRequests)
    //case 3
//    private val rpsLimiter = LeakingBucketRateLimiter(
//        rateLimitPerSec,
//        Duration.ofSeconds(1),
//        rateLimitPerSec * 2
//    );
    //case 4
    private val statsService = StatisticService();
    private val requestCSVService = RequestCSVService();
    //private val rpsLimiter = CountingRateLimiter(rateLimitPerSec, 1, TimeUnit.SECONDS);


    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        logger.warn("[$accountName] Submitting payment request for payment $paymentId")

        val transactionId = UUID.randomUUID() //statsService.getPercentile95()
        if (deadline - now() < 0) {
            requestCSVService.addRequestData(0, 0, paymentId, transactionId)
            paymentESService.update(paymentId) {
                it.logProcessing(false, now(), transactionId, reason = "Request timeout.")
            }
            return
        }

        logger.info("[$accountName] Submit for $paymentId , txId: $transactionId")

        // Вне зависимости от исхода оплаты важно отметить что она была отправлена.
        // Это требуется сделать ВО ВСЕХ СЛУЧАЯХ, поскольку эта информация используется сервисом тестирования.
        paymentESService.update(paymentId) {
            it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
        }
        // (blocking) case-2, 5req on semaphore / 5 procTime = 1rps

//        parallelRequestSemaphore.acquire()
//        logger.info("Acquire. Semaphore queue length: ${parallelRequestSemaphore.queueLength}")
        rpsLimiter.tickBlocking()

        val timeout = OK_HTTP_CLIENT_TIMEOUT
        val request = HttpRequest.newBuilder()
            .uri(URI("http://localhost:1234/external/process?serviceName=${serviceName}&accountName=${accountName}&transactionId=$transactionId&paymentId=$paymentId&amount=$amount&timeout=${timeout}"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        // case-3 практика показывает, что parallel совсем чуть-чуть ломается, если оставить только лимитер, без paralSemaphore
//        if (!rpsLimiter.tick()) {
//            logger.error("[$accountName] RPS for payment: $transactionId, payment: $paymentId")
//            paymentESService.update(paymentId) {
//                it.logProcessing(false, now(), transactionId, reason = "RPS reached")
//            }
//            parallelRequestSemaphore.release()
//            return
//        }
        val createdAt = now();
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply { response ->

            val body = try {
                mapper.readValue(response.body(), ExternalSysResponse::class.java)
            } catch (e: Exception) {
                logger.error("[$accountName] [ERROR] Payment processed for txId: $transactionId, payment: $paymentId, result code: ${response.statusCode()}, reason: ${response.body()}")
                ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, e.message)
            }

            val callTime = now() - createdAt
            statsService.addTime(callTime)
            requestCSVService.addRequestData(callTime, response.statusCode(), paymentId, transactionId)

            logger.warn("[$accountName] Payment processed for txId: $transactionId, payment: $paymentId, succeeded: ${body.result}, message: ${body.message}")

            // Здесь мы обновляем состояние оплаты в зависимости от результата в базе данных оплат.
            // Это требуется сделать ВО ВСЕХ ИСХОДАХ (успешная оплата / неуспешная / ошибочная ситуация)
            paymentESService.update(paymentId) {
                it.logProcessing(body.result, now(), transactionId, reason = body.message)
            }

//                parallelRequestSemaphore.release()
//                logger.info("Release. Semaphore queue length: ${parallelRequestSemaphore.queueLength}")
            if (!body.result) {
                performPaymentAsync(paymentId, amount, paymentStartedAt, deadline)
            }
        }.exceptionally { e ->
            logger.info("Catch exception ${e.message}")
            requestCSVService.addRequestData(now() - createdAt, 0, paymentId, transactionId)
            when (e) {
                is SocketTimeoutException -> {
                    logger.error(
                        "[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId",
                        e
                    )
                    paymentESService.update(paymentId) {
                        it.logProcessing(false, now(), transactionId, reason = "Request timeout.")
                    }
                }

                else -> {
                    logger.error(
                        "[$accountName] Payment failed for txId: $transactionId, payment: $paymentId",
                        e
                    )

                    paymentESService.update(paymentId) {
                        it.logProcessing(false, now(), transactionId, reason = e.message)
                    }
                }
            }
//            parallelRequestSemaphore.release()
//            logger.info("Release in catch. Semaphore queue length: ${parallelRequestSemaphore.queueLength}")
            performPaymentAsync(paymentId, amount, paymentStartedAt, deadline)
        }

    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

}

fun now() = System.currentTimeMillis()

